package com.github.dmadapter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.mybatis.SqlRewriteConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class SqlRewriteConfigUpdater {
    private static final String VALIDATION_REPORT_JSON = "sql-validation-report.json";
    private static final Pattern IDENTITY_INSERT_TABLE_PATTERN = Pattern.compile(
            "(?is)\\bINSERT\\s+INTO\\s+((?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"|`[^`]+`)"
                    + "(?:\\s*\\.\\s*(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"|`[^`]+`))?)\\s*\\("
    );
    private static final Pattern NO_IDENTITY_TABLE_PATTERN = Pattern.compile(
            "(?iu)(?:表|TABLE)\\s*\\[([^\\]]+)]\\s*(?:不存在\\s*IDENTITY\\s*列|"
                    + "(?:(?:DOES|DID)\\s+NOT|(?:DOESN|DIDN)['’]?T)\\s+CONTAINS?\\s+IDENTITY\\s+COLUMN)"
    );
    private final UpsertKeyInference inference = new UpsertKeyInference();
    private final ObjectMapper objectMapper = new ObjectMapper();

    SqlRewriteConfigUpdate update(
            AdapterContext context,
            Path rewriteConfigPath,
            SqlRewriteConfig loadedRewriteConfig,
            List<RewriteConfigCandidate> candidates,
            Map<String, TableKeyMetadata> metadataByTable,
            boolean metadataAvailable
    ) {
        return update(
                context,
                rewriteConfigPath,
                loadedRewriteConfig,
                candidates,
                metadataByTable,
                metadataAvailable,
                Map.of()
        );
    }

    SqlRewriteConfigUpdate update(
            AdapterContext context,
            Path rewriteConfigPath,
            SqlRewriteConfig loadedRewriteConfig,
            List<RewriteConfigCandidate> candidates,
            Map<String, TableKeyMetadata> metadataByTable,
            boolean metadataAvailable,
            Map<String, DamengMetadataReader.AutoIncrementKind> autoIncrementKinds
    ) {
        List<String> warnings = new ArrayList<>();
        RewriteConfigModel model = RewriteConfigModel.load(rewriteConfigPath);
        IdentityInsertReconciliation reconciliation = reconcileIdentityInsertTables(
                context,
                model,
                autoIncrementKinds,
                warnings
        );
        boolean learnedIdentityInsertTable = learnIdentityInsertTables(
                context,
                model,
                reconciliation.nonIdentityTables(),
                warnings
        );
        boolean identityInsertTablesChanged = reconciliation.changed() || learnedIdentityInsertTable;
        if (candidates.isEmpty()) {
            if (!identityInsertTablesChanged) {
                return new SqlRewriteConfigUpdate(loadedRewriteConfig, Optional.empty(), warnings);
            }
            SqlRewriteConfig rewriteConfig = mergedRewriteConfig(loadedRewriteConfig, model, Map.of());
            Optional<FileChange> fileChange = writeIfChanged(context, rewriteConfigPath, model);
            return new SqlRewriteConfigUpdate(rewriteConfig, fileChange, warnings);
        }
        Map<String, List<String>> inferredMethodKeys = new LinkedHashMap<>();
        Map<String, List<UpsertKeyInference.InferenceResult>> tableInferenceResults = new LinkedHashMap<>();

        for (RewriteConfigCandidate candidate : candidates) {
            model.ensureTable(candidate.tableName(), List.of());
            TableKeyMetadata metadata = metadataByTable.get(
                    DamengMetadataReader.normalizeTableName(candidate.tableName())
            );
            boolean staleConflictKeyGroups = hasStaleConflictKeyGroups(candidate, model, metadata);
            if (staleConflictKeyGroups) {
                model.removeMethodConflictKeyGroups(candidate.methodKey());
                model.removeMethodResolution(candidate.methodKey());
                warnings.add("Discarded stale conflictKeyGroups for " + candidate.methodKey()
                        + " because they no longer match the reachable primary/unique keys "
                        + "in current project DDL metadata.");
            }
            boolean configured = candidate.outerJoinSource()
                    ? hasConfiguredKeyColumns(model.tableColumns(candidate.tableName()))
                    : hasConfiguredKeyColumns(model.methodColumns(candidate.methodKey()))
                    || !model.methodConflictKeyGroups(candidate.methodKey()).isEmpty()
                    || hasConfiguredKeyColumns(model.tableColumns(candidate.tableName()));
            if (configured) {
                if (!candidate.outerJoinSource()) {
                    model.ensureMethod(candidate.methodKey(), List.of());
                    model.removeMethodResolution(candidate.methodKey());
                }
            } else if (metadataAvailable) {
                Optional<UpsertKeyInference.InferenceResult> result =
                        inferCandidateKey(candidate, metadata);
                result.ifPresent(inferenceResult -> {
                    tableInferenceResults
                            .computeIfAbsent(DamengMetadataReader.normalizeTableName(candidate.tableName()), ignored -> new ArrayList<>())
                            .add(inferenceResult);
                    if (inferenceResult.hasMultipleConflictKeys()) {
                        model.ensureMethod(candidate.methodKey(), List.of());
                        model.putMethodConflictKeyGroups(
                                candidate.methodKey(),
                                inferenceResult.conflictKeyGroups()
                        );
                        model.removeMethodResolution(candidate.methodKey());
                        warnings.add("Inferred INSERT IGNORE conflictKeyGroups "
                                + inferenceResult.conflictKeyGroups()
                                + " for " + candidate.methodKey()
                                + " from " + inferenceResult.source() + ".");
                    } else if (inferenceResult.inferred()) {
                        if (candidate.outerJoinSource()) {
                            model.putTable(candidate.tableName(), inferenceResult.keyColumns());
                            warnings.add("Inferred source keyColumns " + inferenceResult.keyColumns()
                                    + " for outer UPDATE JOIN table " + candidate.tableName()
                                    + " from " + inferenceResult.source() + ".");
                        } else {
                            inferredMethodKeys.put(candidate.methodKey(), inferenceResult.keyColumns());
                            model.putMethod(candidate.methodKey(), inferenceResult.keyColumns());
                            model.removeMethodConflictKeyGroups(candidate.methodKey());
                            model.removeMethodResolution(candidate.methodKey());
                            warnings.add("Inferred keyColumns " + inferenceResult.keyColumns()
                                    + " for " + candidate.methodKey()
                                    + " from " + inferenceResult.source() + ".");
                        }
                    } else {
                        if (candidate.outerJoinSource()) {
                            warnings.add("Could not prove source keyColumns for outer UPDATE JOIN table "
                                    + candidate.tableName() + ": " + inferenceResult.reason());
                        } else {
                            model.ensureMethod(candidate.methodKey(), List.of());
                            model.removeMethodConflictKeyGroups(candidate.methodKey());
                            model.putMethodResolution(candidate.methodKey(), inferenceResult.resolutionCode());
                            if (UpsertKeyInference.RESOLUTION_INSERT_IGNORE_AS_PLAIN_INSERT.equals(
                                    inferenceResult.resolutionCode()
                            )) {
                                warnings.add("Resolved " + candidate.methodKey()
                                        + " as a plain INSERT: " + inferenceResult.reason());
                            } else {
                                warnings.add("Could not infer keyColumns for " + candidate.methodKey()
                                        + ": " + inferenceResult.reason());
                            }
                        }
                    }
                });
            } else {
                if (!candidate.outerJoinSource()) {
                    model.ensureMethod(candidate.methodKey(), List.of());
                    model.putMethodResolution(
                            candidate.methodKey(),
                            UpsertKeyInference.RESOLUTION_METADATA_UNAVAILABLE
                    );
                }
            }
        }

        applyUnambiguousTableKeys(model, candidates, tableInferenceResults, warnings);

        SqlRewriteConfig rewriteConfig = mergedRewriteConfig(loadedRewriteConfig, model, inferredMethodKeys);
        Optional<FileChange> fileChange = writeIfChanged(context, rewriteConfigPath, model);
        return new SqlRewriteConfigUpdate(rewriteConfig, fileChange, warnings);
    }

    private boolean hasStaleConflictKeyGroups(
            RewriteConfigCandidate candidate,
            RewriteConfigModel model,
            TableKeyMetadata metadata
    ) {
        if (!candidate.insertIgnore() || metadata == null || !metadata.tableFound()) {
            return false;
        }
        List<List<String>> configuredGroups = model.methodConflictKeyGroups(candidate.methodKey());
        if (configuredGroups.isEmpty()) {
            return false;
        }
        Set<String> insertedColumns = normalizedColumns(candidate.insertColumns());
        Set<String> reachableKeys = new LinkedHashSet<>();
        for (TableConstraint constraint : metadata.constraints()) {
            List<String> columns = constraint.columns().stream()
                    .map(DamengMetadataReader::normalizeIdentifier)
                    .toList();
            if (!columns.isEmpty() && insertedColumns.containsAll(columns)) {
                reachableKeys.add(columns.toString());
            }
        }
        Set<String> configuredKeys = configuredGroups.stream()
                .map(group -> group.stream()
                        .map(DamengMetadataReader::normalizeIdentifier)
                        .toList()
                        .toString())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return !configuredKeys.equals(reachableKeys);
    }

    private Optional<UpsertKeyInference.InferenceResult> inferCandidateKey(
            RewriteConfigCandidate candidate,
            TableKeyMetadata metadata
    ) {
        if (candidate.outerJoinSource() && metadata != null && metadata.primaryKeys().size() == 1) {
            TableConstraint primaryKey = metadata.primaryKeys().get(0);
            return Optional.of(UpsertKeyInference.InferenceResult.inferred(
                    primaryKey.columns(),
                    "primary key " + primaryKey.name()
            ));
        }
        return inference.infer(candidate, metadata);
    }

    private boolean learnIdentityInsertTables(
            AdapterContext context,
            RewriteConfigModel model,
            Set<String> nonIdentityTables,
            List<String> warnings
    ) {
        Path reportPath = context.reportDir().resolve(VALIDATION_REPORT_JSON);
        if (!Files.isRegularFile(reportPath)) {
            return false;
        }
        boolean changed = false;
        try {
            JsonNode records = objectMapper.readTree(reportPath.toFile()).path("records");
            if (!records.isArray()) {
                return false;
            }
            for (JsonNode record : records) {
                if (!"FAILED".equalsIgnoreCase(record.path("status").asText())) {
                    continue;
                }
                String details = record.path("summary").asText("") + "\n"
                        + record.path("message").asText("");
                if (!details.contains("SET IDENTITY_INSERT") || !details.contains("自增列")) {
                    continue;
                }
                Matcher matcher = IDENTITY_INSERT_TABLE_PATTERN.matcher(details);
                if (!matcher.find()) {
                    continue;
                }
                String tableName = matcher.group(1).replaceAll("\\s+", "");
                if (nonIdentityTables.contains(DamengMetadataReader.normalizeTableName(tableName))) {
                    continue;
                }
                if (model.addIdentityInsertTable(tableName)) {
                    changed = true;
                    warnings.add("Learned identityInsertTables entry " + tableName
                            + " from the previous Dameng validation failure.");
                }
            }
        } catch (IOException ignored) {
            // A malformed or partially written previous report must not block migration.
        }
        return changed;
    }

    private IdentityInsertReconciliation reconcileIdentityInsertTables(
            AdapterContext context,
            RewriteConfigModel model,
            Map<String, DamengMetadataReader.AutoIncrementKind> autoIncrementKinds,
            List<String> warnings
    ) {
        boolean changed = false;
        Set<String> nonIdentityTables = new LinkedHashSet<>();
        Set<String> confirmedIdentityTables = new LinkedHashSet<>();
        Map<String, DamengMetadataReader.AutoIncrementKind> kinds = autoIncrementKinds == null
                ? Map.of()
                : autoIncrementKinds;
        for (Map.Entry<String, DamengMetadataReader.AutoIncrementKind> entry : kinds.entrySet()) {
            String tableName = entry.getKey();
            DamengMetadataReader.AutoIncrementKind kind = entry.getValue();
            if (kind == DamengMetadataReader.AutoIncrementKind.IDENTITY) {
                confirmedIdentityTables.add(DamengMetadataReader.normalizeTableName(tableName));
                continue;
            }
            if (kind == DamengMetadataReader.AutoIncrementKind.NOT_FOUND) {
                continue;
            }
            nonIdentityTables.add(DamengMetadataReader.normalizeTableName(tableName));
            if (model.removeIdentityInsertTable(tableName)) {
                changed = true;
                String reason = kind == DamengMetadataReader.AutoIncrementKind.AUTO_INCREMENT
                        ? "uses AUTO_INCREMENT rather than IDENTITY"
                        : "does not contain an IDENTITY column";
                warnings.add("Removed identityInsertTables entry " + tableName
                        + " because the target Dameng table " + reason + ".");
            }
        }

        Path reportPath = context.reportDir().resolve(VALIDATION_REPORT_JSON);
        if (!Files.isRegularFile(reportPath)) {
            return new IdentityInsertReconciliation(changed, nonIdentityTables);
        }
        try {
            JsonNode records = objectMapper.readTree(reportPath.toFile()).path("records");
            if (!records.isArray()) {
                return new IdentityInsertReconciliation(changed, nonIdentityTables);
            }
            for (JsonNode record : records) {
                if (!"FAILED".equalsIgnoreCase(record.path("status").asText())) {
                    continue;
                }
                String details = record.path("summary").asText("") + "\n"
                        + record.path("message").asText("");
                Matcher matcher = NO_IDENTITY_TABLE_PATTERN.matcher(details);
                while (matcher.find()) {
                    String tableName = matcher.group(1).trim();
                    String normalizedTable = DamengMetadataReader.normalizeTableName(tableName);
                    if (confirmedIdentityTables.contains(normalizedTable)) {
                        continue;
                    }
                    nonIdentityTables.add(normalizedTable);
                    if (model.removeIdentityInsertTable(tableName)) {
                        changed = true;
                        warnings.add("Removed identityInsertTables entry " + tableName
                                + " because the previous Dameng validation reported that the target table "
                                + "has no IDENTITY column.");
                    }
                }
            }
        } catch (IOException ignored) {
            // A malformed or partially written previous report must not block migration.
        }
        return new IdentityInsertReconciliation(changed, nonIdentityTables);
    }

    private void applyUnambiguousTableKeys(
            RewriteConfigModel model,
            List<RewriteConfigCandidate> candidates,
            Map<String, List<UpsertKeyInference.InferenceResult>> tableInferenceResults,
            List<String> warnings
    ) {
        Set<String> candidateTables = new LinkedHashSet<>();
        for (RewriteConfigCandidate candidate : candidates) {
            candidateTables.add(DamengMetadataReader.normalizeTableName(candidate.tableName()));
        }
        for (String table : candidateTables) {
            if (hasConfiguredKeyColumns(model.tableColumns(table))) {
                continue;
            }
            List<UpsertKeyInference.InferenceResult> results = tableInferenceResults.getOrDefault(table, List.of());
            if (results.isEmpty() || results.stream().anyMatch(result -> !result.inferred())) {
                continue;
            }
            List<String> first = results.get(0).keyColumns();
            boolean allSame = results.stream().allMatch(result -> normalizedColumns(result.keyColumns()).equals(normalizedColumns(first)));
            if (allSame) {
                model.putTable(table, first);
            } else {
                warnings.add("Table-level keyColumns for " + table
                        + " were left empty because mapper methods inferred different keys.");
            }
        }
    }

    private SqlRewriteConfig mergedRewriteConfig(
            SqlRewriteConfig loadedRewriteConfig,
            RewriteConfigModel model,
            Map<String, List<String>> inferredMethodKeys
    ) {
        Map<String, List<String>> tableKeys = new LinkedHashMap<>(loadedRewriteConfig.tableKeyColumns());
        for (String table : model.tableNames()) {
            tableKeys.remove(table);
        }
        tableKeys.putAll(model.nonEmptyTableKeys());
        Map<String, List<String>> methodKeys = new LinkedHashMap<>(loadedRewriteConfig.methodKeyColumns());
        for (String method : model.methodNames()) {
            methodKeys.remove(method);
        }
        for (Map.Entry<String, List<String>> entry : inferredMethodKeys.entrySet()) {
            methodKeys.putIfAbsent(entry.getKey(), entry.getValue());
        }
        methodKeys.putAll(model.nonEmptyMethodKeys());
        return new SqlRewriteConfig(
                tableKeys,
                methodKeys,
                loadedRewriteConfig.ignoredMissingTables(),
                loadedRewriteConfig.ignoredMissingColumns(),
                loadedRewriteConfig.ignoredMissingSchemas(),
                mergedIdentityInsertTables(loadedRewriteConfig, model),
                model.methodResolutions(),
                mergedMethodConflictKeyGroups(loadedRewriteConfig, model)
        );
    }

    private Map<String, List<List<String>>> mergedMethodConflictKeyGroups(
            SqlRewriteConfig loadedRewriteConfig,
            RewriteConfigModel model
    ) {
        Map<String, List<List<String>>> groups =
                new LinkedHashMap<>(loadedRewriteConfig.methodConflictKeyGroups());
        for (String method : model.methodNames()) {
            groups.remove(method);
        }
        groups.putAll(model.nonEmptyMethodConflictKeyGroups());
        return groups;
    }

    private Set<String> mergedIdentityInsertTables(SqlRewriteConfig loadedRewriteConfig, RewriteConfigModel model) {
        Set<String> identityInsertTables = new LinkedHashSet<>(loadedRewriteConfig.identityInsertTables());
        identityInsertTables.removeIf(table -> model.removedIdentityInsertTables().contains(
                DamengMetadataReader.normalizeTableName(table)
        ));
        identityInsertTables.addAll(model.identityInsertTables());
        return identityInsertTables;
    }

    private Optional<FileChange> writeIfChanged(AdapterContext context, Path rewriteConfigPath, RewriteConfigModel model) {
        String content = model.toYaml();
        if (Files.isRegularFile(rewriteConfigPath)) {
            try {
                if (Files.readString(rewriteConfigPath, StandardCharsets.UTF_8).equals(content)) {
                    return Optional.empty();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read SQL rewrite config: " + rewriteConfigPath, e);
            }
        }
        boolean existedBeforeWrite = Files.exists(rewriteConfigPath);
        if (context.dryRun()) {
            String action = existedBeforeWrite ? "UPDATE" : "CREATE";
            return Optional.of(FileChange.planned(
                    rewriteConfigPath.toString(),
                    action,
                    "Maintain SQL rewrite config keyColumns for upsert/insert-ignore rewrites"
            ));
        }
        try {
            Files.createDirectories(rewriteConfigPath.getParent());
            Files.writeString(rewriteConfigPath, content, StandardCharsets.UTF_8);
            String action = existedBeforeWrite ? "UPDATE" : "CREATE";
            return Optional.of(FileChange.applied(
                    rewriteConfigPath.toString(),
                    action,
                    "Maintained SQL rewrite config keyColumns for upsert/insert-ignore rewrites"
            ));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write SQL rewrite config: " + rewriteConfigPath, e);
        }
    }

    private boolean hasConfiguredKeyColumns(List<String> columns) {
        return columns != null && !columns.isEmpty();
    }

    private Set<String> normalizedColumns(List<String> columns) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String column : columns) {
            normalized.add(DamengMetadataReader.normalizeIdentifier(column));
        }
        return normalized;
    }

    private static final class RewriteConfigModel {
        private final LinkedHashMap<String, List<String>> tableKeys = new LinkedHashMap<>();
        private final LinkedHashMap<String, List<String>> methodKeys = new LinkedHashMap<>();
        private final LinkedHashMap<String, List<List<String>>> methodConflictKeyGroups =
                new LinkedHashMap<>();
        private final LinkedHashMap<String, String> methodResolutions = new LinkedHashMap<>();
        private final List<String> identityInsertLines = new ArrayList<>();
        private final LinkedHashSet<String> identityInsertTables = new LinkedHashSet<>();
        private final LinkedHashSet<String> removedIdentityInsertTables = new LinkedHashSet<>();
        private final List<String> validationIgnoreLines = new ArrayList<>();
        private final List<String> validationArgsLines = new ArrayList<>();

        static RewriteConfigModel load(Path path) {
            RewriteConfigModel model = new RewriteConfigModel();
            if (path == null || !Files.isRegularFile(path)) {
                return model;
            }
            try {
                model.parse(Files.readAllLines(path, StandardCharsets.UTF_8));
                return model;
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read SQL rewrite config: " + path, e);
            }
        }

        void ensureTable(String table, List<String> columns) {
            if (table == null || table.isBlank()) {
                return;
            }
            if (tableKey(table).isEmpty()) {
                tableKeys.put(table.trim(), cleanColumns(columns));
            }
        }

        void putTable(String table, List<String> columns) {
            String key = tableKey(table).orElse(table.trim());
            tableKeys.put(key, cleanColumns(columns));
        }

        void ensureMethod(String method, List<String> columns) {
            if (method == null || method.isBlank()) {
                return;
            }
            methodKeys.putIfAbsent(method.trim(), cleanColumns(columns));
        }

        void putMethod(String method, List<String> columns) {
            if (method == null || method.isBlank()) {
                return;
            }
            methodKeys.put(method.trim(), cleanColumns(columns));
        }

        void putMethodConflictKeyGroups(String method, List<List<String>> conflictKeyGroups) {
            if (method == null || method.isBlank()) {
                return;
            }
            methodKeys.putIfAbsent(method.trim(), List.of());
            List<List<String>> cleanGroups =
                    (conflictKeyGroups == null ? List.<List<String>>of() : conflictKeyGroups).stream()
                            .map(RewriteConfigModel::cleanColumns)
                            .filter(group -> !group.isEmpty())
                            .toList();
            if (!cleanGroups.isEmpty()) {
                methodConflictKeyGroups.put(method.trim(), List.copyOf(cleanGroups));
            }
        }

        void removeMethodConflictKeyGroups(String method) {
            if (method != null) {
                methodConflictKeyGroups.remove(method.trim());
            }
        }

        void putMethodResolution(String method, String resolutionCode) {
            if (method == null || method.isBlank() || resolutionCode == null || resolutionCode.isBlank()) {
                return;
            }
            methodResolutions.put(method.trim(), resolutionCode.trim());
        }

        void removeMethodResolution(String method) {
            if (method != null) {
                methodResolutions.remove(method.trim());
            }
        }

        List<String> tableColumns(String table) {
            Optional<String> key = tableKey(table);
            return key.map(value -> tableKeys.getOrDefault(value, List.of())).orElse(List.of());
        }

        List<String> methodColumns(String method) {
            return methodKeys.getOrDefault(method, List.of());
        }

        List<List<String>> methodConflictKeyGroups(String method) {
            return methodConflictKeyGroups.getOrDefault(method, List.of());
        }

        Map<String, List<String>> nonEmptyTableKeys() {
            Map<String, List<String>> result = new LinkedHashMap<>();
            tableKeys.forEach((table, columns) -> {
                if (!columns.isEmpty()) {
                    result.put(table, columns);
                }
            });
            return result;
        }

        Set<String> tableNames() {
            return Set.copyOf(tableKeys.keySet());
        }

        Map<String, List<String>> nonEmptyMethodKeys() {
            Map<String, List<String>> result = new LinkedHashMap<>();
            methodKeys.forEach((method, columns) -> {
                if (!columns.isEmpty()) {
                    result.put(method, columns);
                }
            });
            return result;
        }

        Set<String> methodNames() {
            return Set.copyOf(methodKeys.keySet());
        }

        Map<String, List<List<String>>> nonEmptyMethodConflictKeyGroups() {
            return Map.copyOf(methodConflictKeyGroups);
        }

        Map<String, String> methodResolutions() {
            return Map.copyOf(methodResolutions);
        }

        Set<String> identityInsertTables() {
            return Set.copyOf(identityInsertTables);
        }

        Set<String> removedIdentityInsertTables() {
            return Set.copyOf(removedIdentityInsertTables);
        }

        boolean addIdentityInsertTable(String tableName) {
            if (tableName == null || tableName.isBlank() || !identityInsertTables.add(tableName.trim())) {
                return false;
            }
            while (!identityInsertLines.isEmpty()
                    && identityInsertLines.get(identityInsertLines.size() - 1).isBlank()) {
                identityInsertLines.remove(identityInsertLines.size() - 1);
            }
            if (identityInsertLines.isEmpty()) {
                identityInsertLines.add("identityInsertTables:");
            }
            identityInsertLines.add("  - \"" + escapeYaml(tableName.trim()) + "\"");
            return true;
        }

        boolean removeIdentityInsertTable(String tableName) {
            String normalized = DamengMetadataReader.normalizeTableName(tableName);
            List<String> matches = identityInsertTables.stream()
                    .filter(candidate -> DamengMetadataReader.normalizeTableName(candidate).equals(normalized))
                    .toList();
            if (matches.isEmpty()) {
                return false;
            }
            identityInsertTables.removeAll(matches);
            removedIdentityInsertTables.add(normalized);
            rebuildIdentityInsertLines();
            return true;
        }

        private void rebuildIdentityInsertLines() {
            identityInsertLines.clear();
            if (identityInsertTables.isEmpty()) {
                return;
            }
            identityInsertLines.add("identityInsertTables:");
            identityInsertTables.forEach(table -> identityInsertLines.add(
                    "  - \"" + escapeYaml(table) + "\""
            ));
        }

        String toYaml() {
            StringBuilder yaml = new StringBuilder();
            yaml.append("# dm-adapter SQL rewrite config.\n")
                    .append("# keyColumns may be inferred from Dameng primary/unique metadata when DM_SQL_VALIDATION is enabled.\n");
            if (!identityInsertLines.isEmpty()) {
                identityInsertLines.forEach(line -> yaml.append(line).append("\n"));
                yaml.append("\n");
            }
            yaml.append("upsertKeys:\n")
                    .append("  tables:\n");
            if (tableKeys.isEmpty()) {
                yaml.append("    {}\n");
            } else {
                tableKeys.forEach((table, columns) -> yaml.append("    \"")
                        .append(escapeYaml(table))
                        .append("\":\n")
                        .append("      keyColumns: ")
                        .append(inlineList(columns))
                        .append("\n"));
            }
            yaml.append("  methods:\n");
            if (methodKeys.isEmpty()) {
                yaml.append("    {}\n");
            } else {
                methodKeys.forEach((method, columns) -> {
                    yaml.append("    \"")
                            .append(escapeYaml(method))
                            .append("\":\n")
                            .append("      keyColumns: ")
                            .append(inlineList(columns))
                            .append("\n");
                    List<List<String>> conflictKeyGroups =
                            methodConflictKeyGroups.getOrDefault(method, List.of());
                    if (!conflictKeyGroups.isEmpty()) {
                        yaml.append("      conflictKeyGroups: ")
                                .append(nestedInlineList(conflictKeyGroups))
                                .append("\n");
                    }
                });
            }
            if (!methodResolutions.isEmpty()) {
                yaml.append("\n")
                        .append("upsertKeyResolutions:\n")
                        .append("  methods:\n");
                methodResolutions.forEach((method, resolutionCode) -> yaml.append("    \"")
                        .append(escapeYaml(method))
                        .append("\": \"")
                        .append(escapeYaml(resolutionCode))
                        .append("\"\n"));
            }
            if (!validationArgsLines.isEmpty()) {
                yaml.append("\n");
                validationArgsLines.forEach(line -> yaml.append(line).append("\n"));
            }
            if (!validationIgnoreLines.isEmpty()) {
                yaml.append("\n");
                validationIgnoreLines.forEach(line -> yaml.append(line).append("\n"));
            }
            return yaml.toString();
        }

        private void parse(List<String> lines) {
            identityInsertLines.addAll(topLevelBlockStartingWith(lines, "identityInsertTables:"));
            identityInsertTables.addAll(parseIdentityInsertTables(identityInsertLines));
            validationArgsLines.addAll(topLevelBlock(lines, "validationArgs:"));
            validationIgnoreLines.addAll(topLevelBlock(lines, "validationIgnores:"));
            String section = "";
            String currentName = "";
            for (String line : lines) {
                String withoutComment = stripComment(line);
                String trimmed = withoutComment.trim();
                if (trimmed.isBlank() || "{}".equals(trimmed)) {
                    continue;
                }
                int indent = leadingSpaces(withoutComment);
                if (indent == 0 && "upsertKeys:".equals(trimmed)) {
                    section = "upsertKeys";
                    currentName = "";
                    continue;
                }
                if (indent == 0 && "upsertKeyResolutions:".equals(trimmed)) {
                    section = "upsertKeyResolutions";
                    currentName = "";
                    continue;
                }
                if (indent == 0) {
                    section = "";
                    currentName = "";
                    continue;
                }
                if (indent == 2
                        && ("upsertKeyResolutions".equals(section)
                        || "upsertKeyResolutionMethods".equals(section))
                        && "methods:".equals(trimmed)) {
                    section = "upsertKeyResolutionMethods";
                    currentName = "";
                    continue;
                }
                if ("upsertKeyResolutionMethods".equals(section) && indent == 4) {
                    int colon = trimmed.lastIndexOf(':');
                    if (colon > 0) {
                        String method = unquote(trimmed.substring(0, colon).trim());
                        String resolutionCode = unquote(trimmed.substring(colon + 1).trim());
                        if (!method.isBlank() && !resolutionCode.isBlank()) {
                            methodResolutions.put(method, resolutionCode);
                        }
                    }
                    continue;
                }
                if (indent == 2
                        && ("upsertKeys".equals(section) || "tables".equals(section) || "methods".equals(section))
                        && "tables:".equals(trimmed)) {
                    section = "tables";
                    currentName = "";
                    continue;
                }
                if (indent == 2
                        && ("upsertKeys".equals(section) || "tables".equals(section) || "methods".equals(section))
                        && "methods:".equals(trimmed)) {
                    section = "methods";
                    currentName = "";
                    continue;
                }
                if (indent == 4 && ("tables".equals(section) || "methods".equals(section)) && trimmed.endsWith(":")) {
                    currentName = unquote(trimmed.substring(0, trimmed.length() - 1).trim());
                    if ("tables".equals(section)) {
                        tableKeys.putIfAbsent(currentName, List.of());
                    } else {
                        methodKeys.putIfAbsent(currentName, List.of());
                    }
                    continue;
                }
                if (indent == 6
                        && ("tables".equals(section) || "methods".equals(section))
                        && !currentName.isBlank()
                        && trimmed.startsWith("keyColumns:")) {
                    List<String> keyColumns = parseInlineList(trimmed.substring("keyColumns:".length()));
                    if ("tables".equals(section)) {
                        tableKeys.put(currentName, keyColumns);
                    } else {
                        methodKeys.put(currentName, keyColumns);
                    }
                }
                if (indent == 6
                        && "methods".equals(section)
                        && !currentName.isBlank()
                        && trimmed.startsWith("conflictKeyGroups:")) {
                    List<List<String>> conflictKeyGroups = parseNestedInlineLists(
                            trimmed.substring("conflictKeyGroups:".length())
                    );
                    if (!conflictKeyGroups.isEmpty()) {
                        methodConflictKeyGroups.put(currentName, conflictKeyGroups);
                    }
                }
            }
        }

        private static List<String> parseIdentityInsertTables(List<String> lines) {
            List<String> tables = new ArrayList<>();
            for (String line : lines) {
                String withoutComment = stripComment(line);
                String trimmed = withoutComment.trim();
                int indent = leadingSpaces(withoutComment);
                if (indent == 0 && trimmed.startsWith("identityInsertTables:")) {
                    tables.addAll(parseInlineList(trimmed.substring("identityInsertTables:".length())));
                    continue;
                }
                if (indent >= 2 && trimmed.startsWith("- ")) {
                    String table = unquote(trimmed.substring(2).trim());
                    if (!table.isBlank()) {
                        tables.add(table);
                    }
                }
            }
            return tables;
        }

        private static List<String> topLevelBlock(List<String> lines, String header) {
            return topLevelBlock(lines, trimmed -> header.equals(trimmed));
        }

        private static List<String> topLevelBlockStartingWith(List<String> lines, String headerPrefix) {
            return topLevelBlock(lines, trimmed -> trimmed.startsWith(headerPrefix));
        }

        private static List<String> topLevelBlock(List<String> lines, java.util.function.Predicate<String> headerMatcher) {
            List<String> block = new ArrayList<>();
            boolean capturing = false;
            for (String line : lines) {
                String trimmed = line.trim();
                int indent = leadingSpaces(line);
                if (!capturing) {
                    if (indent == 0 && headerMatcher.test(trimmed)) {
                        capturing = true;
                        block.add(line);
                    }
                    continue;
                }
                if (indent == 0 && !trimmed.isBlank() && !trimmed.startsWith("#")) {
                    break;
                }
                block.add(line);
            }
            while (!block.isEmpty() && block.get(block.size() - 1).trim().isBlank()) {
                block.remove(block.size() - 1);
            }
            return block;
        }

        private Optional<String> tableKey(String table) {
            String normalized = normalizeTableName(table);
            return tableKeys.keySet().stream()
                    .filter(existing -> normalizeTableName(existing).equals(normalized))
                    .findFirst();
        }

        private String normalizeTableName(String table) {
            return DamengMetadataReader.normalizeTableName(table).toLowerCase(Locale.ROOT);
        }

        private static List<String> parseInlineList(String value) {
            String trimmed = value.trim();
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                String scalar = unquote(trimmed);
                return scalar.isBlank() ? List.of() : List.of(scalar);
            }
            String body = trimmed.substring(1, trimmed.length() - 1).trim();
            if (body.isBlank()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (String item : body.split(",")) {
                String scalar = unquote(item.trim());
                if (!scalar.isBlank()) {
                    values.add(scalar);
                }
            }
            return values;
        }

        private static List<List<String>> parseNestedInlineLists(String value) {
            String trimmed = value.trim();
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                return List.of();
            }
            String body = trimmed.substring(1, trimmed.length() - 1).trim();
            List<List<String>> groups = new ArrayList<>();
            int index = 0;
            while (index < body.length()) {
                while (index < body.length()
                        && (Character.isWhitespace(body.charAt(index)) || body.charAt(index) == ',')) {
                    index++;
                }
                if (index >= body.length() || body.charAt(index) != '[') {
                    return List.of();
                }
                int close = body.indexOf(']', index + 1);
                if (close < 0) {
                    return List.of();
                }
                List<String> group = parseInlineList(body.substring(index, close + 1));
                if (group.isEmpty()) {
                    return List.of();
                }
                groups.add(group);
                index = close + 1;
            }
            return List.copyOf(groups);
        }

        private static List<String> cleanColumns(List<String> columns) {
            if (columns == null || columns.isEmpty()) {
                return List.of();
            }
            return columns.stream()
                    .filter(column -> column != null && !column.isBlank())
                    .map(String::trim)
                    .toList();
        }

        private static String inlineList(List<String> values) {
            if (values == null || values.isEmpty()) {
                return "[]";
            }
            return "[" + String.join(", ", values.stream().map(RewriteConfigModel::quoteValue).toList()) + "]";
        }

        private static String nestedInlineList(List<List<String>> groups) {
            return "[" + String.join(", ", groups.stream()
                    .map(RewriteConfigModel::inlineList)
                    .toList()) + "]";
        }

        private static String quoteValue(String value) {
            return "\"" + escapeYaml(value) + "\"";
        }

        private static String escapeYaml(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String stripComment(String line) {
            boolean singleQuoted = false;
            boolean doubleQuoted = false;
            for (int i = 0; i < line.length(); i++) {
                char current = line.charAt(i);
                if (current == '\'' && !doubleQuoted) {
                    singleQuoted = !singleQuoted;
                } else if (current == '"' && !singleQuoted) {
                    doubleQuoted = !doubleQuoted;
                } else if (current == '#' && !singleQuoted && !doubleQuoted) {
                    return line.substring(0, i);
                }
            }
            return line;
        }

        private static int leadingSpaces(String line) {
            int count = 0;
            while (count < line.length() && line.charAt(count) == ' ') {
                count++;
            }
            return count;
        }

        private static String unquote(String value) {
            String trimmed = value.trim();
            if (trimmed.length() >= 2
                    && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                    || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
            return trimmed;
        }
    }

    private record IdentityInsertReconciliation(boolean changed, Set<String> nonIdentityTables) {
        private IdentityInsertReconciliation {
            nonIdentityTables = Set.copyOf(nonIdentityTables == null ? Set.of() : nonIdentityTables);
        }
    }
}
