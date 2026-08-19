package com.github.dmadapter.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SqlScriptSourceSyntaxInspector {
    static final String DUPLICATE_DEFAULT_CODE = "ORIGINAL_SQL_DUPLICATE_DEFAULT";
    static final String CONTRADICTORY_NULLABILITY_CODE = "ORIGINAL_SQL_CONTRADICTORY_NULLABILITY";
    static final String INSERT_VALUE_COUNT_CODE = "ORIGINAL_SQL_INSERT_VALUE_COUNT";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:(?:GLOBAL\\s+)?TEMPORARY\\s+)?TABLE\\b"
    );
    private static final Pattern ALTER_TABLE = Pattern.compile("(?is)\\bALTER\\s+TABLE\\b");
    private static final Set<String> TABLE_DEFINITION_PREFIXES = Set.of(
            "PRIMARY", "CONSTRAINT", "KEY", "INDEX", "UNIQUE", "FULLTEXT", "SPATIAL", "FOREIGN", "CHECK"
    );

    private SqlScriptSourceSyntaxInspector() {
    }

    static String ddlManualReviewReason(String sql) {
        for (String definition : columnDefinitions(sql)) {
            if (keywordCount(definition, "DEFAULT") > 1) {
                return DUPLICATE_DEFAULT_CODE
                        + ": one column definition contains multiple DEFAULT clauses; fix the source SQL.";
            }
            if (hasContradictoryNullability(definition)) {
                return CONTRADICTORY_NULLABILITY_CODE
                        + ": one column definition contains both NULL and NOT NULL; fix the source SQL.";
            }
        }
        return "";
    }

    static boolean containsDuplicateColumnDefault(String sql) {
        return columnDefinitions(sql).stream().anyMatch(definition -> keywordCount(definition, "DEFAULT") > 1);
    }

    static boolean containsContradictoryColumnNullability(String sql) {
        return columnDefinitions(sql).stream().anyMatch(SqlScriptSourceSyntaxInspector::hasContradictoryNullability);
    }

    static String insertValueCountManualReviewReason(
            String sql,
            Map<String, ? extends Set<String>> knownTableColumns
    ) {
        InsertValuesShape insert = insertValuesShape(sql);
        if (insert == null || insert.rowValueCounts().isEmpty()) {
            return "";
        }
        int expected;
        if (insert.explicitColumnCount() >= 0) {
            expected = insert.explicitColumnCount();
        } else {
            Set<String> columns = knownTableColumns == null
                    ? null
                    : knownTableColumns.get(normalizedTableName(insert.tableName()));
            if (columns == null || columns.isEmpty()) {
                return "";
            }
            expected = columns.size();
        }
        for (int row = 0; row < insert.rowValueCounts().size(); row++) {
            int actual = insert.rowValueCounts().get(row);
            if (actual != expected) {
                return INSERT_VALUE_COUNT_CODE + ": INSERT into " + insert.tableName()
                        + " expects " + expected + " value(s), but VALUES row " + (row + 1)
                        + " contains " + actual + "; fix the source SQL.";
            }
        }
        return "";
    }

    static InsertValuesShape bareInsertValuesShape(String sql) {
        InsertValuesShape shape = insertValuesShape(sql);
        return shape == null || shape.explicitColumnCount() >= 0 ? null : shape;
    }

    private static List<String> columnDefinitions(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        String searchable = maskIgnored(sql);
        List<String> definitions = new ArrayList<>();
        Matcher create = CREATE_TABLE.matcher(searchable);
        while (create.find()) {
            int tableStart = skipOptionalIfNotExists(sql, skipWhitespace(sql, create.end()));
            Identifier table = readObjectIdentifier(sql, tableStart);
            if (table == null) {
                continue;
            }
            int open = skipWhitespace(sql, table.end());
            if (open >= sql.length() || sql.charAt(open) != '(') {
                continue;
            }
            int close = matchingParenthesis(sql, open);
            if (close < 0) {
                continue;
            }
            for (String definition : splitTopLevelComma(sql.substring(open + 1, close))) {
                if (!isTableConstraint(definition)) {
                    definitions.add(definition);
                }
            }
        }
        Matcher alter = ALTER_TABLE.matcher(searchable);
        while (alter.find()) {
            Identifier table = readObjectIdentifier(sql, skipWhitespace(sql, alter.end()));
            if (table == null) {
                continue;
            }
            int operationStart = skipWhitespace(sql, table.end());
            int end = statementEnd(sql, operationStart);
            for (String operation : splitTopLevelComma(sql.substring(operationStart, end))) {
                String definition = alterColumnDefinition(operation);
                if (!definition.isBlank()) {
                    definitions.addAll(splitTopLevelComma(definition));
                }
            }
        }
        return List.copyOf(definitions);
    }

    private static int skipOptionalIfNotExists(String sql, int start) {
        int cursor = start;
        for (String word : List.of("IF", "NOT", "EXISTS")) {
            if (!startsWord(sql, cursor, word)) {
                return start;
            }
            cursor = skipWhitespace(sql, cursor + word.length());
        }
        return cursor;
    }

    private static String alterColumnDefinition(String operation) {
        int cursor = skipWhitespace(operation, 0);
        String action = wordAt(operation, cursor);
        if (!Set.of("ADD", "MODIFY", "CHANGE").contains(action)) {
            return "";
        }
        cursor = skipWhitespace(operation, cursor + action.length());
        if (startsWord(operation, cursor, "COLUMN")) {
            cursor = skipWhitespace(operation, cursor + "COLUMN".length());
        }
        String next = wordAt(operation, cursor);
        if (TABLE_DEFINITION_PREFIXES.contains(next)) {
            return "";
        }
        if (cursor < operation.length() && operation.charAt(cursor) == '(') {
            int close = matchingParenthesis(operation, cursor);
            if (close > cursor) {
                return operation.substring(cursor + 1, close);
            }
        }
        return operation.substring(cursor);
    }

    private static boolean isTableConstraint(String definition) {
        int cursor = skipWhitespace(definition, 0);
        return TABLE_DEFINITION_PREFIXES.contains(wordAt(definition, cursor));
    }

    private static boolean hasContradictoryNullability(String definition) {
        List<String> words = wordsOutsideIgnored(definition);
        boolean explicitNull = false;
        boolean notNull = false;
        for (int index = 0; index < words.size(); index++) {
            if (!"NULL".equals(words.get(index))) {
                continue;
            }
            String previous = index == 0 ? "" : words.get(index - 1);
            if ("NOT".equals(previous)) {
                notNull = true;
            } else if (!"DEFAULT".equals(previous) && !"IS".equals(previous)) {
                explicitNull = true;
            }
        }
        return explicitNull && notNull;
    }

    private static int keywordCount(String definition, String keyword) {
        int count = 0;
        for (String word : wordsOutsideIgnored(definition)) {
            if (keyword.equals(word)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> wordsOutsideIgnored(String value) {
        String searchable = maskIgnored(value);
        Matcher words = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*").matcher(searchable);
        List<String> result = new ArrayList<>();
        while (words.find()) {
            result.add(words.group().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private static InsertValuesShape insertValuesShape(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String searchable = maskIgnored(sql);
        Matcher insert = Pattern.compile("(?is)\\bINSERT\\s+(?:IGNORE\\s+)?INTO\\s+").matcher(searchable);
        if (!insert.find()) {
            return null;
        }
        int cursor = skipWhitespace(sql, insert.end());
        Identifier table = readObjectIdentifier(sql, cursor);
        if (table == null) {
            return null;
        }
        cursor = skipWhitespace(sql, table.end());
        int explicitColumns = -1;
        if (cursor < sql.length() && sql.charAt(cursor) == '(') {
            int close = matchingParenthesis(sql, cursor);
            if (close < 0) {
                return null;
            }
            explicitColumns = splitTopLevelComma(sql.substring(cursor + 1, close)).size();
            cursor = skipWhitespace(sql, close + 1);
        }
        if (!startsWord(sql, cursor, "VALUES")) {
            return null;
        }
        cursor = skipWhitespace(sql, cursor + "VALUES".length());
        List<Integer> valueCounts = new ArrayList<>();
        while (cursor < sql.length() && sql.charAt(cursor) == '(') {
            int close = matchingParenthesis(sql, cursor);
            if (close < 0) {
                return null;
            }
            valueCounts.add(splitTopLevelComma(sql.substring(cursor + 1, close)).size());
            cursor = skipWhitespace(sql, close + 1);
            if (cursor >= sql.length() || sql.charAt(cursor) != ',') {
                break;
            }
            cursor = skipWhitespace(sql, cursor + 1);
        }
        return new InsertValuesShape(table.text(), explicitColumns, List.copyOf(valueCounts));
    }

    private static Identifier readObjectIdentifier(String sql, int start) {
        int cursor = readIdentifierPart(sql, start);
        if (cursor <= start) {
            return null;
        }
        int end = cursor;
        int separator = skipWhitespace(sql, cursor);
        if (separator < sql.length() && sql.charAt(separator) == '.') {
            int secondStart = skipWhitespace(sql, separator + 1);
            int secondEnd = readIdentifierPart(sql, secondStart);
            if (secondEnd <= secondStart) {
                return null;
            }
            end = secondEnd;
        }
        return new Identifier(sql.substring(start, end).strip(), end);
    }

    private static int readIdentifierPart(String sql, int start) {
        if (start >= sql.length()) {
            return start;
        }
        char first = sql.charAt(start);
        if (first == '`' || first == '"') {
            char quote = first;
            int cursor = start + 1;
            while (cursor < sql.length()) {
                if (sql.charAt(cursor) == quote) {
                    if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == quote) {
                        cursor += 2;
                    } else {
                        return cursor + 1;
                    }
                } else {
                    cursor++;
                }
            }
            return start;
        }
        int cursor = start;
        while (cursor < sql.length()) {
            char current = sql.charAt(cursor);
            if (!Character.isLetterOrDigit(current) && current != '_' && current != '$') {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private static String normalizedTableName(String table) {
        String[] parts = table.split("\\.");
        String last = parts[parts.length - 1].strip();
        if (last.length() >= 2
                && ((last.startsWith("`") && last.endsWith("`"))
                || (last.startsWith("\"") && last.endsWith("\"")))) {
            last = last.substring(1, last.length() - 1);
        }
        return last.toLowerCase(Locale.ROOT);
    }

    private static String maskIgnored(String sql) {
        StringBuilder masked = new StringBuilder(sql.length());
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                char quote = current;
                masked.append(' ');
                index++;
                while (index < sql.length()) {
                    current = sql.charAt(index);
                    masked.append(current == '\r' || current == '\n' ? current : ' ');
                    if (current == '\\' && quote == '\'' && index + 1 < sql.length()) {
                        masked.append(' ');
                        index += 2;
                    } else if (current == quote && index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                        masked.append(' ');
                        index += 2;
                    } else if (current == quote) {
                        index++;
                        break;
                    } else {
                        index++;
                    }
                }
            } else if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                masked.append("  ");
                index += 2;
                while (index < sql.length()) {
                    current = sql.charAt(index);
                    if (current == '*' && index + 1 < sql.length() && sql.charAt(index + 1) == '/') {
                        masked.append("  ");
                        index += 2;
                        break;
                    }
                    masked.append(current == '\r' || current == '\n' ? current : ' ');
                    index++;
                }
            } else if ((current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-')
                    || current == '#') {
                while (index < sql.length() && sql.charAt(index) != '\r' && sql.charAt(index) != '\n') {
                    masked.append(' ');
                    index++;
                }
            } else {
                masked.append(current);
                index++;
            }
        }
        return masked.toString();
    }

    private static List<String> splitTopLevelComma(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                int end = skipQuoted(text, index, current);
                index = end <= index ? index + 1 : end;
            } else if (current == '/' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
                int end = text.indexOf("*/", index + 2);
                index = end < 0 ? text.length() : end + 2;
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
                index++;
            } else if (current == ',' && depth == 0) {
                parts.add(text.substring(start, index).strip());
                start = ++index;
            } else {
                index++;
            }
        }
        parts.add(text.substring(start).strip());
        return parts;
    }

    private static int matchingParenthesis(String text, int open) {
        int depth = 0;
        int index = open;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                int end = skipQuoted(text, index, current);
                index = end <= index ? index + 1 : end;
            } else if (current == '/' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
                int end = text.indexOf("*/", index + 2);
                index = end < 0 ? text.length() : end + 2;
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')' && --depth == 0) {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private static int skipQuoted(String text, int start, char quote) {
        int cursor = start + 1;
        while (cursor < text.length()) {
            char current = text.charAt(cursor);
            if (current == '\\' && quote == '\'' && cursor + 1 < text.length()) {
                cursor += 2;
            } else if (current == quote && cursor + 1 < text.length() && text.charAt(cursor + 1) == quote) {
                cursor += 2;
            } else if (current == quote) {
                return cursor + 1;
            } else {
                cursor++;
            }
        }
        return text.length();
    }

    private static int statementEnd(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(sql, index, current);
            } else if (current == ';') {
                return index;
            } else {
                index++;
            }
        }
        return sql.length();
    }

    private static int skipWhitespace(String value, int start) {
        int cursor = Math.max(0, start);
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean startsWord(String value, int start, String word) {
        if (start < 0 || start + word.length() > value.length()
                || !value.regionMatches(true, start, word, 0, word.length())) {
            return false;
        }
        int end = start + word.length();
        return (start == 0 || !isWordCharacter(value.charAt(start - 1)))
                && (end == value.length() || !isWordCharacter(value.charAt(end)));
    }

    private static String wordAt(String value, int start) {
        int cursor = start;
        while (cursor < value.length() && isWordCharacter(value.charAt(cursor))) {
            cursor++;
        }
        return value.substring(start, cursor).toUpperCase(Locale.ROOT);
    }

    private static boolean isWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private record Identifier(String text, int end) {
    }

    record InsertValuesShape(String tableName, int explicitColumnCount, List<Integer> rowValueCounts) {
    }
}
