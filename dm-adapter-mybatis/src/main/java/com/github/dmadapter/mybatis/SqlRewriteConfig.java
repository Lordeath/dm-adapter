package com.github.dmadapter.mybatis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record SqlRewriteConfig(
        Map<String, List<String>> tableKeyColumns,
        Map<String, List<String>> methodKeyColumns
) {
    public SqlRewriteConfig {
        tableKeyColumns = normalizeTableKeys(tableKeyColumns);
        methodKeyColumns = normalizeMethodKeys(methodKeyColumns);
    }

    public static SqlRewriteConfig empty() {
        return new SqlRewriteConfig(Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return tableKeyColumns.isEmpty() && methodKeyColumns.isEmpty();
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
