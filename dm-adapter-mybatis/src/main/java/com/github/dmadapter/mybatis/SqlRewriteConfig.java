package com.github.dmadapter.mybatis;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record SqlRewriteConfig(
        Map<String, List<String>> tableKeyColumns,
        Map<String, List<String>> methodKeyColumns,
        Set<String> ignoredMissingTables,
        Set<String> ignoredMissingColumns,
        Set<String> ignoredMissingSchemas
) {
    public SqlRewriteConfig(Map<String, List<String>> tableKeyColumns, Map<String, List<String>> methodKeyColumns) {
        this(tableKeyColumns, methodKeyColumns, Set.of(), Set.of(), Set.of());
    }

    public SqlRewriteConfig(
            Map<String, List<String>> tableKeyColumns,
            Map<String, List<String>> methodKeyColumns,
            Set<String> ignoredMissingTables
    ) {
        this(tableKeyColumns, methodKeyColumns, ignoredMissingTables, Set.of(), Set.of());
    }

    public SqlRewriteConfig(
            Map<String, List<String>> tableKeyColumns,
            Map<String, List<String>> methodKeyColumns,
            Set<String> ignoredMissingTables,
            Set<String> ignoredMissingColumns
    ) {
        this(tableKeyColumns, methodKeyColumns, ignoredMissingTables, ignoredMissingColumns, Set.of());
    }

    public SqlRewriteConfig {
        tableKeyColumns = normalizeTableKeys(tableKeyColumns);
        methodKeyColumns = normalizeMethodKeys(methodKeyColumns);
        ignoredMissingTables = normalizeTables(ignoredMissingTables);
        ignoredMissingColumns = normalizeColumns(ignoredMissingColumns);
        ignoredMissingSchemas = normalizeSchemas(ignoredMissingSchemas);
    }

    public static SqlRewriteConfig empty() {
        return new SqlRewriteConfig(Map.of(), Map.of(), Set.of(), Set.of(), Set.of());
    }

    public boolean isEmpty() {
        return tableKeyColumns.isEmpty()
                && methodKeyColumns.isEmpty()
                && ignoredMissingTables.isEmpty()
                && ignoredMissingColumns.isEmpty()
                && ignoredMissingSchemas.isEmpty();
    }

    public List<String> keyColumnsFor(String methodKey, String tableName) {
        if (methodKey != null && !methodKey.isBlank()) {
            List<String> methodKeys = methodKeyColumns.get(methodKey);
            if (methodKeys != null && !methodKeys.isEmpty()) {
                return methodKeys;
            }
        }
        if (tableName == null || tableName.isBlank()) {
            return List.of();
        }
        return tableKeyColumns.getOrDefault(normalizeTableName(tableName), List.of());
    }

    public List<String> methodKeyColumns(String methodKey) {
        return methodKeyColumns.getOrDefault(methodKey, List.of());
    }

    static String normalizeTableName(String tableName) {
        String normalized = tableName.trim()
                .replace("\"", "")
                .replace("`", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static Map<String, List<String>> normalizeTableKeys(Map<String, List<String>> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        keys.forEach((table, columns) -> {
            List<String> cleanColumns = cleanColumns(columns);
            if (table != null && !table.isBlank() && !cleanColumns.isEmpty()) {
                normalized.put(normalizeTableName(table), cleanColumns);
            }
        });
        return Map.copyOf(normalized);
    }

    private static Map<String, List<String>> normalizeMethodKeys(Map<String, List<String>> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        keys.forEach((method, columns) -> {
            List<String> cleanColumns = cleanColumns(columns);
            if (method != null && !method.isBlank() && !cleanColumns.isEmpty()) {
                normalized.put(method.trim(), cleanColumns);
            }
        });
        return Map.copyOf(normalized);
    }

    private static Set<String> normalizeTables(Set<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String table : tables) {
            if (table != null && !table.isBlank()) {
                normalized.add(normalizeTableName(table));
            }
        }
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeColumns(Set<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String column : columns) {
            if (column != null && !column.isBlank()) {
                normalized.add(normalizeColumnName(column));
            }
        }
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeSchemas(Set<String> schemas) {
        if (schemas == null || schemas.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String schema : schemas) {
            if (schema != null && !schema.isBlank()) {
                normalized.add(normalizeSchemaName(schema));
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeColumnName(String column) {
        return column.trim()
                .replace("\"", "")
                .replace("`", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeSchemaName(String schema) {
        return schema.trim()
                .replace("\"", "")
                .replace("`", "")
                .toLowerCase(Locale.ROOT);
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
}
