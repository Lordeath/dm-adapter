package com.github.dmadapter.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InsertColumnExtractor {
    private InsertColumnExtractor() {
    }

    static String tableName(String sql) {
        Matcher matcher = Pattern.compile(
                "(?is)\\binsert\\s+(?:ignore\\s+)?into\\s+([^\\s(<]+)"
        ).matcher(sql == null ? "" : sql);
        return matcher.find()
                ? matcher.group(1).replace("`", "").replace("\"", "").trim()
                : "";
    }

    static List<String> columns(String sql, String tableName) {
        if (sql == null || sql.isBlank() || tableName == null || tableName.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile(
                "(?is)\\binsert\\s+(?:ignore\\s+)?into\\s+"
                        + Pattern.quote(tableName)
                        + "\\s*(?<tail>[\\s\\S]*)"
        ).matcher(sql);
        if (!matcher.find()) {
            return List.of();
        }
        String tail = matcher.group("tail");
        String columnList = myBatisTrimColumnList(tail)
                .or(() -> parenthesizedColumnList(tail))
                .orElseGet(() -> looseColumnList(tail));
        if (columnList.isBlank()) {
            return List.of();
        }
        return splitColumns(columnList);
    }

    private static Optional<String> myBatisTrimColumnList(String text) {
        Matcher matcher = Pattern.compile("(?is)^\\s*<trim\\b[^>]*>(?<body>[\\s\\S]*?)</trim>").matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String body = matcher.group("body")
                .replaceAll("(?is)<!--.*?-->", "\n")
                .replaceAll("(?is)<[^>]+>", "\n");
        return Optional.of(body);
    }

    private static Optional<String> parenthesizedColumnList(String text) {
        int open = text.indexOf('(');
        if (open < 0) {
            return Optional.empty();
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return Optional.of(text.substring(open + 1, i));
                }
            }
        }
        return Optional.empty();
    }

    private static String looseColumnList(String text) {
        Matcher valuesMatcher = Pattern.compile("(?is)\\bvalues\\b").matcher(text);
        if (!valuesMatcher.find()) {
            return "";
        }
        return text.substring(0, valuesMatcher.start());
    }

    private static List<String> splitColumns(String columnList) {
        List<String> columns = new ArrayList<>();
        for (String part : columnList.split(",")) {
            String column = part.replace("`", "").replace("\"", "").trim();
            if (column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                columns.add(column);
            }
        }
        return columns;
    }
}
