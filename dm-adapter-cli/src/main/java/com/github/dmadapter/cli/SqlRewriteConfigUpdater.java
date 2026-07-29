package com.github.dmadapter.cli;

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

class SqlRewriteConfigUpdater {
    private final UpsertKeyInference inference = new UpsertKeyInference();

    SqlRewriteConfigUpdate update(
            AdapterContext context,
            Path rewriteConfigPath,
            SqlRewriteConfig loadedRewriteConfig,
            List<RewriteConfigCandidate> candidates,
            Map<String, TableKeyMetadata> metadataByTable,
            boolean metadataAvailable
    ) {
        List<String> warnings = new ArrayList<>();
        if (candidates.isEmpty()) {
            return new SqlRewriteConfigUpdate(loadedRewriteConfig, Optional.empty(), List.of());
        }

        RewriteConfigModel model = RewriteConfigModel.load(rewriteConfigPath);
        Map<String, List<String>> inferredMethodKeys = new LinkedHashMap<>();
        Map<String, List<UpsertKeyInference.InferenceResult>> tableInferenceResults = new LinkedHashMap<>();

        for (RewriteConfigCandidate candidate : candidates) {
            model.ensureTable(candidate.tableName(), List.of());
            if (metadataAvailable
                    && !hasConfiguredKeyColumns(model.methodColumns(candidate.methodKey()))
                    && !hasConfiguredKeyColumns(model.tableColumns(candidate.tableName()))) {
                TableKeyMetadata metadata = metadataByTable.get(DamengMetadataReader.normalizeTableName(candidate.tableName()));
                Optional<UpsertKeyInference.InferenceResult> result = inference.infer(candidate, metadata);
                result.ifPresent(inferenceResult -> {
                    tableInferenceResults
                            .computeIfAbsent(DamengMetadataReader.normalizeTableName(candidate.tableName()), ignored -> new ArrayList<>())
                            .add(inferenceResult);
                    if (inferenceResult.inferred()) {
                        inferredMethodKeys.put(candidate.methodKey(), inferenceResult.keyColumns());
                        model.putMethod(candidate.methodKey(), inferenceResult.keyColumns());
                        warnings.add("Inferred keyColumns " + inferenceResult.keyColumns()
                                + " for " + candidate.methodKey()
                                + " from " + inferenceResult.source() + ".");
                    } else {
                        model.ensureMethod(candidate.methodKey(), List.of());
                        warnings.add("Could not infer keyColumns for " + candidate.methodKey()
                                + ": " + inferenceResult.reason());
                    }
                });
            } else {
                model.ensureMethod(candidate.methodKey(), List.of());
            }
        }

        applyUnambiguousTableKeys(model, candidates, tableInferenceResults, warnings);

        SqlRewriteConfig rewriteConfig = mergedRewriteConfig(loadedRewriteConfig, model, inferredMethodKeys);
        Optional<FileChange> fileChange = writeIfChanged(context, rewriteConfigPath, model);
        return new SqlRewriteConfigUpdate(rewriteConfig, fileChange, warnings);
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
        tableKeys.putAll(model.nonEmptyTableKeys());
        Map<String, List<String>> methodKeys = new LinkedHashMap<>(loadedRewriteConfig.methodKeyColumns());
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
                mergedIdentityInsertTables(loadedRewriteConfig, model)
        );
    }

    private Set<String> mergedIdentityInsertTables(SqlRewriteConfig loadedRewriteConfig, RewriteConfigModel model) {
        Set<String> identityInsertTables = new LinkedHashSet<>(loadedRewriteConfig.identityInsertTables());
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
        private final List<String> identityInsertLines = new ArrayList<>();
        private final LinkedHashSet<String> identityInsertTables = new LinkedHashSet<>();
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

        List<String> tableColumns(String table) {
            Optional<String> key = tableKey(table);
            return key.map(value -> tableKeys.getOrDefault(value, List.of())).orElse(List.of());
        }

        List<String> methodColumns(String method) {
            return methodKeys.getOrDefault(method, List.of());
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

        Map<String, List<String>> nonEmptyMethodKeys() {
            Map<String, List<String>> result = new LinkedHashMap<>();
            methodKeys.forEach((method, columns) -> {
                if (!columns.isEmpty()) {
                    result.put(method, columns);
                }
            });
            return result;
        }

        Set<String> identityInsertTables() {
            return Set.copyOf(identityInsertTables);
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
                methodKeys.forEach((method, columns) -> yaml.append("    \"")
                        .append(escapeYaml(method))
                        .append("\":\n")
                        .append("      keyColumns: ")
                        .append(inlineList(columns))
                        .append("\n"));
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
}
