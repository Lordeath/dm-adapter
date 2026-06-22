package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MySqlToDmSqlConverter implements SqlConverter {
    private static final String TOKEN = "(?:\\d+|#\\{[^}]+}|\\$\\{[^}]+})";
    private static final Pattern IFNULL_PATTERN = Pattern.compile("\\bIFNULL\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOW_PATTERN = Pattern.compile("\\bNOW\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_FORMAT_PATTERN = Pattern.compile("\\bDATE_FORMAT\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP_CONCAT_PATTERN = Pattern.compile("\\bGROUP_CONCAT\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIND_IN_SET_PATTERN = Pattern.compile("\\bFIND_IN_SET\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final List<String> MYSQL_FUNCTIONS_REQUIRING_REVIEW = List.of(
            "DATE_ADD",
            "DATE_SUB",
            "STR_TO_DATE",
            "UNIX_TIMESTAMP",
            "FROM_UNIXTIME",
            "TIMESTAMPDIFF",
            "CONCAT_WS",
            "JSON_ARRAY",
            "JSON_CONTAINS",
            "JSON_EXTRACT",
            "JSON_INSERT",
            "JSON_KEYS",
            "JSON_LENGTH",
            "JSON_OBJECT",
            "JSON_QUOTE",
            "JSON_REMOVE",
            "JSON_REPLACE",
            "JSON_SEARCH",
            "JSON_SET",
            "JSON_TYPE",
            "JSON_UNQUOTE",
            "JSON_VALID"
    );
    private static final Pattern LIMIT_COMMA_PATTERN = Pattern.compile(
            "(?is)^(?<base>.+?)\\s+LIMIT\\s+(?<offset>" + TOKEN + ")\\s*,\\s*(?<size>" + TOKEN + ")\\s*;?\\s*$");
    private static final Pattern LIMIT_OFFSET_PATTERN = Pattern.compile(
            "(?is)^(?<base>.+?)\\s+LIMIT\\s+(?<size>" + TOKEN + ")\\s+OFFSET\\s+(?<offset>" + TOKEN + ")\\s*;?\\s*$");
    private static final Pattern LIMIT_SIZE_PATTERN = Pattern.compile(
            "(?is)^(?<base>.+?)\\s+LIMIT\\s+(?<size>" + TOKEN + ")\\s*;?\\s*$");

    @Override
    public SqlConversionResult convert(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlConversionResult.unchanged(sql == null ? "" : sql);
        }
        String original = sql;
        String unsupportedReason = unsupportedReason(original);
        if (!unsupportedReason.isBlank()) {
            return SqlConversionResult.manualReview(original, unsupportedReason);
        }

        String converted = original;
        List<String> rules = new ArrayList<>();

        DoubleQuotedStringConversion doubleQuotedStringConversion = convertDoubleQuotedStringLiterals(converted);
        if (doubleQuotedStringConversion.changed()) {
            converted = doubleQuotedStringConversion.convertedSql();
            rules.add("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
        }

        Matcher ifNullMatcher = IFNULL_PATTERN.matcher(converted);
        if (ifNullMatcher.find()) {
            converted = ifNullMatcher.replaceAll("NVL(");
            rules.add("IFNULL_TO_NVL");
        }

        Matcher nowMatcher = NOW_PATTERN.matcher(converted);
        if (nowMatcher.find()) {
            converted = nowMatcher.replaceAll("SYSDATE");
            rules.add("NOW_TO_SYSDATE");
        }

        DamengReservedColumnRenamer.RenameResult renameResult =
                DamengReservedColumnRenamer.renameBareIdentifiers(converted);
        if (renameResult.changed()) {
            converted = renameResult.convertedSql();
            rules.add(DamengReservedColumnRenamer.RULE_NAME);
        }

        LimitConversion limitConversion = convertLimit(converted);
        if (limitConversion.manualReviewReason() != null) {
            return SqlConversionResult.manualReview(original, limitConversion.manualReviewReason());
        }
        if (limitConversion.convertedSql() != null) {
            converted = limitConversion.convertedSql();
            rules.add(limitConversion.ruleName());
        }

        parseIfPossible(converted);
        if (rules.isEmpty()) {
            return SqlConversionResult.unchanged(original);
        }
        return SqlConversionResult.changed(original, converted, rules);
    }

    private String unsupportedReason(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (DATE_FORMAT_PATTERN.matcher(sql).find()) {
            return "DATE_FORMAT requires manual confirmation because format tokens may not map 1:1 to Dameng.";
        }
        if (upper.contains("ON DUPLICATE KEY UPDATE")) {
            return "ON DUPLICATE KEY UPDATE has no safe automatic Dameng rewrite in MVP.";
        }
        if (upper.contains("REPLACE INTO")) {
            return "REPLACE INTO has no safe automatic Dameng rewrite in MVP.";
        }
        if (GROUP_CONCAT_PATTERN.matcher(sql).find()) {
            return "GROUP_CONCAT requires manual confirmation for Dameng aggregate syntax.";
        }
        if (FIND_IN_SET_PATTERN.matcher(sql).find()) {
            return "FIND_IN_SET requires manual confirmation because it is MySQL-specific.";
        }
        String mysqlFunction = firstMySqlFunctionRequiringReview(sql);
        if (!mysqlFunction.isBlank()) {
            return mysqlFunction + " requires manual confirmation because Dameng support or syntax may differ from MySQL.";
        }
        if (sql.contains("`")) {
            return "Backtick quoted identifiers require manual confirmation. Consider Dameng double-quoted identifiers only after verifying object-name case sensitivity and reserved words.";
        }
        return "";
    }

    private String firstMySqlFunctionRequiringReview(String sql) {
        for (String functionName : MYSQL_FUNCTIONS_REQUIRING_REVIEW) {
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(functionName) + "\\s*\\(", Pattern.CASE_INSENSITIVE);
            if (pattern.matcher(sql).find()) {
                return functionName;
            }
        }
        return "";
    }

    private DoubleQuotedStringConversion convertDoubleQuotedStringLiterals(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = appendSingleQuotedString(sql, index, converted);
            } else if (current == '"') {
                DoubleQuotedStringLiteral literal = readDoubleQuotedStringLiteral(sql, index);
                if (literal.closed()) {
                    appendSingleQuotedStringLiteral(converted, literal.value());
                    index = literal.nextIndex();
                    changed = true;
                } else {
                    converted.append(sql, index, sql.length());
                    index = sql.length();
                }
            } else if (startsLineComment(sql, index)) {
                index = appendUntilLineEnd(sql, index, converted);
            } else if (startsBlockComment(sql, index)) {
                index = appendUntilBlockCommentEnd(sql, index, converted);
            } else {
                converted.append(current);
                index++;
            }
        }
        return new DoubleQuotedStringConversion(converted.toString(), changed);
    }

    private int appendSingleQuotedString(String sql, int start, StringBuilder converted) {
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

    private DoubleQuotedStringLiteral readDoubleQuotedStringLiteral(String sql, int start) {
        StringBuilder value = new StringBuilder();
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\\' && index + 1 < sql.length()) {
                char escaped = sql.charAt(index + 1);
                if (escaped == '"' || escaped == '\'') {
                    value.append(escaped);
                } else {
                    value.append(current).append(escaped);
                }
                index += 2;
            } else if (current == '"') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '"') {
                    value.append(current);
                    index += 2;
                } else {
                    return new DoubleQuotedStringLiteral(value.toString(), index + 1, true);
                }
            } else {
                value.append(current);
                index++;
            }
        }
        return new DoubleQuotedStringLiteral("", start, false);
    }

    private void appendSingleQuotedStringLiteral(StringBuilder converted, String value) {
        converted.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\'') {
                converted.append("''");
            } else {
                converted.append(current);
            }
        }
        converted.append('\'');
    }

    private boolean startsLineComment(String sql, int index) {
        return sql.startsWith("--", index)
                || (sql.charAt(index) == '#' && (index + 1 >= sql.length() || sql.charAt(index + 1) != '{'));
    }

    private int appendUntilLineEnd(String sql, int start, StringBuilder converted) {
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

    private boolean startsBlockComment(String sql, int index) {
        return index + 1 < sql.length() && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*';
    }

    private int appendUntilBlockCommentEnd(String sql, int start, StringBuilder converted) {
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

    private LimitConversion convertLimit(String sql) {
        if (!containsLimit(sql)) {
            return LimitConversion.none();
        }

        Matcher commaMatcher = LIMIT_COMMA_PATTERN.matcher(sql);
        if (commaMatcher.matches() && startsWithSelect(commaMatcher.group("base"))) {
            String base = stripTrailingWhitespace(commaMatcher.group("base"));
            String offset = commaMatcher.group("offset");
            String size = commaMatcher.group("size");
            return LimitConversion.converted(
                    base + " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY",
                    "LIMIT_OFFSET_TO_DM_FETCH"
            );
        }

        Matcher offsetMatcher = LIMIT_OFFSET_PATTERN.matcher(sql);
        if (offsetMatcher.matches() && startsWithSelect(offsetMatcher.group("base"))) {
            String base = stripTrailingWhitespace(offsetMatcher.group("base"));
            String offset = offsetMatcher.group("offset");
            String size = offsetMatcher.group("size");
            return LimitConversion.converted(
                    base + " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY",
                    "LIMIT_OFFSET_TO_DM_FETCH"
            );
        }

        Matcher sizeMatcher = LIMIT_SIZE_PATTERN.matcher(sql);
        if (sizeMatcher.matches() && startsWithSelect(sizeMatcher.group("base"))) {
            String base = stripTrailingWhitespace(sizeMatcher.group("base"));
            String size = sizeMatcher.group("size");
            return LimitConversion.converted(base + " FETCH FIRST " + size + " ROWS ONLY", "LIMIT_TO_DM_FETCH");
        }

        return LimitConversion.manual("LIMIT clause is present but not simple enough for automatic conversion.");
    }

    private boolean containsLimit(String sql) {
        return Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find();
    }

    private boolean startsWithSelect(String sql) {
        return sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select");
    }

    private String stripTrailingWhitespace(String value) {
        return value.replaceFirst("\\s+$", "");
    }

    private void parseIfPossible(String sql) {
        try {
            CCJSqlParserUtil.parse(maskMyBatisPlaceholders(sql));
        } catch (Exception ignored) {
            // Parsing is an aid for future expansion. MVP rules remain conservative and deterministic.
        }
    }

    private String maskMyBatisPlaceholders(String sql) {
        return sql.replaceAll("#\\{[^}]+}", "0")
                .replaceAll("\\$\\{[^}]+}", "0");
    }

    private record DoubleQuotedStringConversion(String convertedSql, boolean changed) {
    }

    private record DoubleQuotedStringLiteral(String value, int nextIndex, boolean closed) {
    }

    private record LimitConversion(String convertedSql, String ruleName, String manualReviewReason) {
        static LimitConversion none() {
            return new LimitConversion(null, null, null);
        }

        static LimitConversion converted(String convertedSql, String ruleName) {
            return new LimitConversion(convertedSql, ruleName, null);
        }

        static LimitConversion manual(String reason) {
            return new LimitConversion(null, null, reason);
        }
    }
}
