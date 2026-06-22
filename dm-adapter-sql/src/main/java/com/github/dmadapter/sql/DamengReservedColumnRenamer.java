package com.github.dmadapter.sql;

import java.util.Locale;
import java.util.Set;

public final class DamengReservedColumnRenamer {
    public static final String RULE_NAME = "DAMENG_RESERVED_COLUMN_RENAME";

    private static final Set<String> RESERVED_COLUMN_NAMES = Set.of(
            "ROWID",
            "ROWNUM",
            "TRXID",
            "PHYROWID",
            "VERSIONS_STARTTIME",
            "VERSIONS_ENDTIME",
            "VERSIONS_STARTTRXID",
            "VERSIONS_ENDTRXID",
            "VERSIONS_OPERATION"
    );

    private DamengReservedColumnRenamer() {
    }

    public static RenameResult renameBareIdentifiers(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new RenameResult(sql == null ? "" : sql, false);
        }

        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = appendSingleQuotedString(sql, index, converted);
            } else if (current == '"') {
                index = appendDoubleQuotedText(sql, index, converted);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = appendMyBatisPlaceholder(sql, index, converted);
            } else if (startsLineComment(sql, index)) {
                index = appendUntilLineEnd(sql, index, converted);
            } else if (startsBlockComment(sql, index)) {
                index = appendUntilBlockCommentEnd(sql, index, converted);
            } else if (isIdentifierStart(current)) {
                int end = index + 1;
                while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                String identifier = sql.substring(index, end);
                String renamed = renameColumnName(identifier);
                converted.append(renamed);
                changed = changed || !identifier.equals(renamed);
                index = end;
            } else {
                converted.append(current);
                index++;
            }
        }
        return new RenameResult(changed ? converted.toString() : sql, changed);
    }

    public static String renameColumnName(String columnName) {
        if (isReservedColumnName(columnName)) {
            return columnName + "_";
        }
        return columnName;
    }

    public static boolean isReservedColumnName(String columnName) {
        return columnName != null && RESERVED_COLUMN_NAMES.contains(columnName.toUpperCase(Locale.ROOT));
    }

    private static int appendSingleQuotedString(String sql, int start, StringBuilder converted) {
        int index = start;
        converted.append(sql.charAt(index++));
        while (index < sql.length()) {
            char current = sql.charAt(index);
            converted.append(current);
            index++;
            if (current == '\\' && index < sql.length()) {
                converted.append(sql.charAt(index++));
            } else if (current == '\'') {
                if (index < sql.length() && sql.charAt(index) == '\'') {
                    converted.append(sql.charAt(index++));
                } else {
                    break;
                }
            }
        }
        return index;
    }

    private static int appendDoubleQuotedText(String sql, int start, StringBuilder converted) {
        int index = start;
        converted.append(sql.charAt(index++));
        while (index < sql.length()) {
            char current = sql.charAt(index);
            converted.append(current);
            index++;
            if (current == '\\' && index < sql.length()) {
                converted.append(sql.charAt(index++));
            } else if (current == '"') {
                if (index < sql.length() && sql.charAt(index) == '"') {
                    converted.append(sql.charAt(index++));
                } else {
                    break;
                }
            }
        }
        return index;
    }

    private static boolean startsMyBatisPlaceholder(String sql, int index) {
        return index + 1 < sql.length()
                && (sql.charAt(index) == '#' || sql.charAt(index) == '$')
                && sql.charAt(index + 1) == '{';
    }

    private static int appendMyBatisPlaceholder(String sql, int start, StringBuilder converted) {
        int end = sql.indexOf('}', start + 2);
        if (end < 0) {
            converted.append(sql, start, sql.length());
            return sql.length();
        }
        converted.append(sql, start, end + 1);
        return end + 1;
    }

    private static boolean startsLineComment(String sql, int index) {
        return sql.startsWith("--", index)
                || (sql.charAt(index) == '#' && (index + 1 >= sql.length() || sql.charAt(index + 1) != '{'));
    }

    private static int appendUntilLineEnd(String sql, int start, StringBuilder converted) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            converted.append(current);
            index++;
            if (current == '\n') {
                break;
            }
        }
        return index;
    }

    private static boolean startsBlockComment(String sql, int index) {
        return index + 1 < sql.length() && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*';
    }

    private static int appendUntilBlockCommentEnd(String sql, int start, StringBuilder converted) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            converted.append(current);
            index++;
            if (current == '*' && index < sql.length() && sql.charAt(index) == '/') {
                converted.append(sql.charAt(index++));
                break;
            }
        }
        return index;
    }

    private static boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    public record RenameResult(String convertedSql, boolean changed) {
    }
}
