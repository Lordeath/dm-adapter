package com.github.dmadapter.cli;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class UpsertKeyInference {
    Optional<InferenceResult> infer(RewriteConfigCandidate candidate, TableKeyMetadata metadata) {
        if (metadata == null || metadata.constraints().isEmpty()) {
            return Optional.of(InferenceResult.unresolved("No primary key or unique key metadata was found for table "
                    + candidate.tableName() + "."));
        }
        if (normalizedColumns(candidate.insertColumns()).isEmpty()) {
            return Optional.of(InferenceResult.unresolved("Could not determine INSERT columns for "
                    + candidate.methodKey() + "; keyColumns must be configured manually."));
        }

        List<TableConstraint> usablePrimaryKeys = usableConstraints(candidate, metadata.primaryKeys());
        if (usablePrimaryKeys.size() == 1) {
            return Optional.of(InferenceResult.inferred(
                    usablePrimaryKeys.get(0).columns(),
                    "primary key " + usablePrimaryKeys.get(0).name()
            ));
        }
        if (usablePrimaryKeys.size() > 1) {
            return Optional.of(InferenceResult.unresolved("Multiple primary key metadata rows matched table "
                    + candidate.tableName() + "."));
        }

        List<TableConstraint> usableUniqueKeys = usableConstraints(candidate, metadata.uniqueKeys());
        if (usableUniqueKeys.size() == 1) {
            return Optional.of(InferenceResult.inferred(
                    usableUniqueKeys.get(0).columns(),
                    "unique key " + usableUniqueKeys.get(0).name()
            ));
        }
        if (usableUniqueKeys.size() > 1) {
            return Optional.of(InferenceResult.unresolved("Multiple unique keys matched INSERT columns for table "
                    + candidate.tableName() + ": " + describe(usableUniqueKeys) + "."));
        }

        return Optional.of(InferenceResult.unresolved("No primary key or unique key columns are fully present in INSERT columns for "
                + candidate.methodKey() + "."));
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

    record InferenceResult(
            boolean inferred,
            List<String> keyColumns,
            String source,
            String reason
    ) {
        static InferenceResult inferred(List<String> keyColumns, String source) {
            return new InferenceResult(true, keyColumns, source == null ? "" : source, "");
        }

        static InferenceResult unresolved(String reason) {
            return new InferenceResult(false, List.of(), "", reason == null ? "" : reason);
        }

        InferenceResult {
            keyColumns = List.copyOf(keyColumns == null ? List.of() : keyColumns);
        }
    }
}
