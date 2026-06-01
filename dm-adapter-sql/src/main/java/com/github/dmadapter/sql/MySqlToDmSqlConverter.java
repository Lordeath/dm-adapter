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
        if (sql.contains("`")) {
            return "Backtick quoted identifiers require manual confirmation before converting to Dameng quoting rules.";
        }
        return "";
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
