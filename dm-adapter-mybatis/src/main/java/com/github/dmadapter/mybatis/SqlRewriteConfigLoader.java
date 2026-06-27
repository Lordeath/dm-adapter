package com.github.dmadapter.mybatis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SqlRewriteConfigLoader {
    public SqlRewriteConfig load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return SqlRewriteConfig.empty();
        }
        try {
            return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read SQL rewrite config: " + path, e);
        }
    }

    SqlRewriteConfig parse(List<String> lines) {
        Map<String, List<String>> tableKeys = new LinkedHashMap<>();
        Map<String, List<String>> methodKeys = new LinkedHashMap<>();
        Set<String> ignoredMissingTables = new LinkedHashSet<>();
        Set<String> ignoredMissingColumns = new LinkedHashSet<>();
        Set<String> ignoredMissingSchemas = new LinkedHashSet<>();
        Set<String> identityInsertTables = new LinkedHashSet<>();
        String section = "";
        String currentName = "";
        for (String line : lines) {
            String withoutComment = stripComment(line);
            String trimmed = withoutComment.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int indent = leadingSpaces(withoutComment);
            if (indent == 0 && "upsertKeys:".equals(trimmed)) {
                section = "upsertKeys";
                currentName = "";
                continue;
            }
            if (indent == 0 && "validationIgnores:".equals(trimmed)) {
                section = "validationIgnores";
                currentName = "";
                continue;
            }
            if (indent == 0 && trimmed.startsWith("identityInsertTables:")) {
                section = "identityInsertTables";
                currentName = "";
                identityInsertTables.addAll(parseInlineList(trimmed.substring("identityInsertTables:".length())));
                continue;
            }
            if (indent == 0) {
                section = "";
                currentName = "";
                continue;
            }
            if ("identityInsertTables".equals(section) && indent >= 2 && trimmed.startsWith("- ")) {
                String table = unquote(trimmed.substring(2).trim());
                if (!table.isBlank()) {
                    identityInsertTables.add(table);
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
            if (isValidationIgnoresSection(section)
                    && indent == 2
                    && trimmed.startsWith("missingTables:")) {
                section = "validationMissingTables";
                ignoredMissingTables.addAll(parseInlineList(trimmed.substring("missingTables:".length())));
                currentName = "";
                continue;
            }
            if (isValidationIgnoresSection(section)
                    && indent == 2
                    && trimmed.startsWith("missingColumns:")) {
                section = "validationMissingColumns";
                ignoredMissingColumns.addAll(parseInlineList(trimmed.substring("missingColumns:".length())));
                currentName = "";
                continue;
            }
            if (isValidationIgnoresSection(section)
                    && indent == 2
                    && trimmed.startsWith("missingSchemas:")) {
                section = "validationMissingSchemas";
                ignoredMissingSchemas.addAll(parseInlineList(trimmed.substring("missingSchemas:".length())));
                currentName = "";
                continue;
            }
            if ("validationMissingTables".equals(section) && indent >= 4 && trimmed.startsWith("- ")) {
                String table = unquote(trimmed.substring(2).trim());
                if (!table.isBlank()) {
                    ignoredMissingTables.add(table);
                }
            }
            if ("validationMissingColumns".equals(section) && indent >= 4 && trimmed.startsWith("- ")) {
                String column = unquote(trimmed.substring(2).trim());
                if (!column.isBlank()) {
                    ignoredMissingColumns.add(column);
                }
            }
            if ("validationMissingSchemas".equals(section) && indent >= 4 && trimmed.startsWith("- ")) {
                String schema = unquote(trimmed.substring(2).trim());
                if (!schema.isBlank()) {
                    ignoredMissingSchemas.add(schema);
                }
            }
        }
        return new SqlRewriteConfig(
                tableKeys,
                methodKeys,
                ignoredMissingTables,
                ignoredMissingColumns,
                ignoredMissingSchemas,
                identityInsertTables
        );
    }

    private boolean isValidationIgnoresSection(String section) {
        return "validationIgnores".equals(section)
                || "validationMissingTables".equals(section)
                || "validationMissingColumns".equals(section)
                || "validationMissingSchemas".equals(section);
    }

    private String stripComment(String line) {
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

    private int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private List<String> parseInlineList(String value) {
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

    private String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
