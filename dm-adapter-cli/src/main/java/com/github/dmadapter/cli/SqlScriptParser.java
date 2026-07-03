package com.github.dmadapter.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SqlScriptParser {
    private static final Pattern DELIMITER_DIRECTIVE = Pattern.compile("(?i)^\\s*DELIMITER\\s+(\\S+)\\s*$");

    private SqlScriptParser() {
    }

    static List<String> statements(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder buffer = new StringBuilder();
        for (String line : lines(content)) {
            Matcher delimiterMatcher = DELIMITER_DIRECTIVE.matcher(line.strip());
            if (delimiterMatcher.matches() && isBlankSql(buffer.toString())) {
                delimiter = delimiterMatcher.group(1);
                continue;
            }
            buffer.append(line);
            while (true) {
                int delimiterIndex = delimiterIndex(buffer, delimiter);
                if (delimiterIndex < 0) {
                    break;
                }
                addStatement(statements, buffer.substring(0, delimiterIndex));
                buffer.delete(0, delimiterIndex + delimiter.length());
            }
        }
        addStatement(statements, buffer.toString());
        return List.copyOf(statements);
    }

    static String scriptContent(List<String> statements) {
        if (statements == null || statements.isEmpty()) {
            return "";
        }
        StringBuilder content = new StringBuilder();
        for (String statement : statements) {
            if (statement == null || statement.isBlank()) {
                continue;
            }
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append(statement.stripTrailing());
            if (!endsWithSemicolon(statement)) {
                content.append(";");
            }
        }
        if (!content.isEmpty()) {
            content.append("\n");
        }
        return content.toString();
    }

    static boolean executable(String statement) {
        return !isBlankSql(statement);
    }

    private static void addStatement(List<String> statements, String rawStatement) {
        String statement = rawStatement == null ? "" : rawStatement.strip();
        if (!isBlankSql(statement)) {
            statements.add(statement);
        }
    }

    private static List<String> lines(String content) {
        List<String> lines = new ArrayList<>();
        Matcher matcher = Pattern.compile(".*(?:\\R|$)").matcher(content);
        while (matcher.find()) {
            String line = matcher.group();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static int delimiterIndex(CharSequence sql, String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            return -1;
        }
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean backtickQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (singleQuoted) {
                if (current == '\\' && next != '\0') {
                    i++;
                } else if (current == '\'' && next == '\'') {
                    i++;
                } else if (current == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                if (current == '\\' && next != '\0') {
                    i++;
                } else if (current == '"' && next == '"') {
                    i++;
                } else if (current == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (backtickQuoted) {
                if (current == '`') {
                    backtickQuoted = false;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (current == '#') {
                lineComment = true;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (current == '\'') {
                singleQuoted = true;
            } else if (current == '"') {
                doubleQuoted = true;
            } else if (current == '`') {
                backtickQuoted = true;
            } else if (matchesAt(sql, delimiter, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matchesAt(CharSequence text, String value, int index) {
        if (index + value.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (text.charAt(index + i) != value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlankSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return true;
        }
        String withoutComments = sql
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)^\\s*--.*$", " ")
                .replaceAll("(?m)^\\s*#.*$", " ");
        return withoutComments.isBlank();
    }

    private static boolean endsWithSemicolon(String statement) {
        String stripped = statement == null ? "" : statement.stripTrailing();
        return stripped.endsWith(";");
    }
}
