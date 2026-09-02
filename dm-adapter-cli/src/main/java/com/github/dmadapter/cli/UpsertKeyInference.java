package com.github.dmadapter.cli;

import com.github.dmadapter.mybatis.SqlRewriteConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class UpsertKeyInference {
    static final String RESOLUTION_ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY =
            SqlRewriteConfig.ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY;
    static final String RESOLUTION_INSERT_IGNORE_AS_PLAIN_INSERT =
            "INSERT_IGNORE_AS_PLAIN_INSERT";
    static final String RESOLUTION_MANUAL_KEY_COLUMNS_REQUIRED =
            SqlRewriteConfig.MANUAL_KEY_COLUMNS_REQUIRED;
    static final String RESOLUTION_METADATA_UNAVAILABLE =
            SqlRewriteConfig.KEY_METADATA_UNAVAILABLE;

    Optional<InferenceResult> infer(RewriteConfigCandidate candidate, TableKeyMetadata metadata) {
        if (metadata == null || !metadata.tableFound()) {
            return Optional.of(InferenceResult.unresolved(
                    RESOLUTION_METADATA_UNAVAILABLE,
                    "没有表 " + candidate.tableName() + " 的键元数据。"
            ));
        }
        if (normalizedColumns(candidate.insertColumns()).isEmpty()) {
            return Optional.of(InferenceResult.unresolved(
                    RESOLUTION_MANUAL_KEY_COLUMNS_REQUIRED,
                    "无法确定 " + candidate.methodKey() + " 的 INSERT 列；必须手工配置 keyColumns。"
            ));
        }
        if (candidate.insertIgnore() && hasNoReachableConflictKey(candidate, metadata)) {
            return Optional.of(InferenceResult.unresolved(
                    RESOLUTION_INSERT_IGNORE_AS_PLAIN_INSERT,
                    "INSERT IGNORE 在 "
                            + candidate.methodKey()
                            + " 中没有可触发的主键或唯一键冲突；可转换为普通 INSERT。"
            ));
        }
        if (metadata.constraints().isEmpty()) {
            return Optional.of(InferenceResult.unresolved(
                    RESOLUTION_ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY,
                    "未找到表 " + candidate.tableName() + " 的主键或唯一键元数据。"
            ));
        }

        List<TableConstraint> usablePrimaryKeys = usableConstraints(candidate, metadata.primaryKeys());
        List<TableConstraint> usableUniqueKeys = usableConstraints(candidate, metadata.uniqueKeys());
        if (candidate.insertIgnore()) {
            List<TableConstraint> reachableConflictKeys = new ArrayList<>();
            reachableConflictKeys.addAll(usablePrimaryKeys);
            reachableConflictKeys.addAll(usableUniqueKeys);
            if (reachableConflictKeys.size() == 1) {
                TableConstraint conflictKey = reachableConflictKeys.get(0);
                return Optional.of(InferenceResult.inferred(
                        conflictKey.columns(),
                        describeConstraint(conflictKey)
                ));
            }
            if (reachableConflictKeys.size() > 1) {
                return Optional.of(InferenceResult.multipleConflictKeys(
                        reachableConflictKeys.stream().map(TableConstraint::columns).toList(),
                        "冲突键 " + describe(reachableConflictKeys)
                ));
            }
        }
        if (usablePrimaryKeys.size() == 1) {
            return Optional.of(InferenceResult.inferred(
                    usablePrimaryKeys.get(0).columns(),
                    "主键 " + usablePrimaryKeys.get(0).name()
            ));
        }
        if (usablePrimaryKeys.size() > 1) {
            return Optional.of(InferenceResult.unresolved(
                    RESOLUTION_MANUAL_KEY_COLUMNS_REQUIRED,
                    "表 " + candidate.tableName() + " 匹配到多条主键元数据。"
            ));
        }

        if (usableUniqueKeys.size() == 1) {
            return Optional.of(InferenceResult.inferred(
                    usableUniqueKeys.get(0).columns(),
                    "唯一键 " + usableUniqueKeys.get(0).name()
            ));
        }
        if (usableUniqueKeys.size() > 1) {
            if (candidate.insertIgnore()) {
                return Optional.of(InferenceResult.multipleConflictKeys(
                        usableUniqueKeys.stream().map(TableConstraint::columns).toList(),
                        "唯一键 " + describe(usableUniqueKeys)
                ));
            }
            return Optional.of(InferenceResult.unresolved(
                    RESOLUTION_MANUAL_KEY_COLUMNS_REQUIRED,
                    "表 " + candidate.tableName() + " 的 INSERT 列匹配到多个唯一键："
                            + describe(usableUniqueKeys) + "。"
            ));
        }

        return Optional.of(InferenceResult.unresolved(
                RESOLUTION_ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY,
                candidate.methodKey() + " 的 INSERT 列中未完整包含任一主键或唯一键的所有列。"
        ));
    }

    private boolean hasNoReachableConflictKey(
            RewriteConfigCandidate candidate,
            TableKeyMetadata metadata
    ) {
        if (metadata.constraints().isEmpty()) {
            return true;
        }
        Set<String> insertedColumns = normalizedColumns(candidate.insertColumns());
        Set<String> autoGeneratedColumns = metadata.autoGeneratedColumns();
        return metadata.constraints().stream().allMatch(constraint ->
                constraint.columns().stream()
                        .map(DamengMetadataReader::normalizeIdentifier)
                        .anyMatch(column -> !insertedColumns.contains(column)
                                && autoGeneratedColumns.contains(column))
        );
    }

    private List<TableConstraint> usableConstraints(RewriteConfigCandidate candidate, List<TableConstraint> constraints) {
        Map<String, String> insertedColumns = normalizedColumnsByOriginal(candidate.insertColumns());
        return constraints.stream()
                .filter(constraint -> !constraint.columns().isEmpty())
                .map(constraint -> candidateColumnConstraint(constraint, insertedColumns))
                .flatMap(Optional::stream)
                .toList();
    }

    private Set<String> normalizedColumns(List<String> columns) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String column : columns) {
            String clean = DamengMetadataReader.normalizeIdentifier(column);
            if (!clean.isBlank() && clean.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                normalized.add(clean);
            }
        }
        return normalized;
    }

    private Map<String, String> normalizedColumnsByOriginal(List<String> columns) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String column : columns) {
            String clean = DamengMetadataReader.normalizeIdentifier(column);
            if (!clean.isBlank() && clean.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                normalized.putIfAbsent(clean, column);
            }
        }
        return normalized;
    }

    private Optional<TableConstraint> candidateColumnConstraint(
            TableConstraint constraint,
            Map<String, String> insertedColumns
    ) {
        List<String> candidateColumns = constraint.columns().stream()
                .map(DamengMetadataReader::normalizeIdentifier)
                .map(insertedColumns::get)
                .toList();
        if (candidateColumns.stream().anyMatch(column -> column == null || column.isBlank())) {
            return Optional.empty();
        }
        return Optional.of(new TableConstraint(constraint.name(), constraint.type(), candidateColumns));
    }

    private String describe(List<TableConstraint> constraints) {
        return constraints.stream()
                .map(constraint -> constraint.name() + constraint.columns())
                .toList()
                .toString();
    }

    private String describeConstraint(TableConstraint constraint) {
        return (constraint.type() == TableConstraint.ConstraintType.PRIMARY_KEY
                ? "主键 "
                : "唯一键 ") + constraint.name();
    }

    record InferenceResult(
            boolean inferred,
            List<String> keyColumns,
            List<List<String>> conflictKeyGroups,
            String source,
            String resolutionCode,
            String reason
    ) {
        static InferenceResult inferred(List<String> keyColumns, String source) {
            return new InferenceResult(
                    true,
                    keyColumns,
                    List.of(),
                    source == null ? "" : source,
                    "",
                    ""
            );
        }

        static InferenceResult multipleConflictKeys(
                List<List<String>> conflictKeyGroups,
                String source
        ) {
            return new InferenceResult(
                    false,
                    List.of(),
                    conflictKeyGroups,
                    source == null ? "" : source,
                    "",
                    ""
            );
        }

        static InferenceResult unresolved(String resolutionCode, String reason) {
            return new InferenceResult(
                    false,
                    List.of(),
                    List.of(),
                    "",
                    resolutionCode == null ? "" : resolutionCode,
                    reason == null ? "" : reason
            );
        }

        InferenceResult {
            keyColumns = List.copyOf(keyColumns == null ? List.of() : keyColumns);
            conflictKeyGroups = List.copyOf(
                    (conflictKeyGroups == null ? List.<List<String>>of() : conflictKeyGroups).stream()
                            .map(group -> List.copyOf(group == null ? List.of() : group))
                            .toList()
            );
        }

        boolean hasMultipleConflictKeys() {
            return !conflictKeyGroups.isEmpty();
        }
    }
}
