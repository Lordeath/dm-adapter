package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MySqlToDmSqlConverter implements SqlConverter {
    public static final String MYSQL_AES_BASE64_TO_DM_AES128_ECB_RULE = "MYSQL_AES_BASE64_TO_DM_AES128_ECB";

    private static final int DM_AES128_ECB_ALGORITHM_ID = 513;
    private static final String AES_ENCRYPT = "AES_ENCRYPT";
    private static final String AES_DECRYPT = "AES_DECRYPT";
    private static final String FROM_BASE64 = "FROM_BASE64";
    private static final String TO_BASE64 = "TO_BASE64";
    private static final String AES_MANUAL_REVIEW_REASON =
            "AES_ENCRYPT/AES_DECRYPT is present but only Base64-wrapped AES password SQL is supported for automatic Dameng rewrite.";
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

        AesBase64Conversion aesBase64Conversion = convertBase64Aes(converted);
        if (aesBase64Conversion.changed()) {
            converted = aesBase64Conversion.convertedSql();
            rules.add(MYSQL_AES_BASE64_TO_DM_AES128_ECB_RULE);
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
        if (containsAesFunction(sql)) {
            AesBase64Conversion aesBase64Conversion = convertBase64Aes(sql);
            if (!aesBase64Conversion.changed() || containsAesFunction(aesBase64Conversion.convertedSql())) {
                return AES_MANUAL_REVIEW_REASON;
            }
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

    private boolean containsAesFunction(String sql) {
        return containsFunction(sql, AES_ENCRYPT) || containsFunction(sql, AES_DECRYPT);
    }

    private AesBase64Conversion convertBase64Aes(String sql) {
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
            } else if (startsFunction(sql, index, TO_BASE64)) {
                FunctionCall functionCall = readFunctionCall(sql, index, TO_BASE64);
                String replacement = functionCall == null ? null : rewriteToBase64AesEncrypt(functionCall);
                if (replacement == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(replacement);
                    index = functionCall.endIndex();
                    changed = true;
                }
            } else if (startsFunction(sql, index, AES_DECRYPT)) {
                FunctionCall functionCall = readFunctionCall(sql, index, AES_DECRYPT);
                String replacement = functionCall == null ? null : rewriteAesDecrypt(functionCall);
                if (replacement == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(replacement);
                    index = functionCall.endIndex();
                    changed = true;
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new AesBase64Conversion(changed ? converted.toString() : sql, changed);
    }

    private String rewriteToBase64AesEncrypt(FunctionCall toBase64Call) {
        List<TopLevelArgument> toBase64Arguments = splitTopLevelArguments(toBase64Call.body());
        if (toBase64Arguments.size() != 1) {
            return null;
        }

        FunctionCall aesEncryptCall = readOnlyFunctionCall(toBase64Arguments.get(0).text(), AES_ENCRYPT);
        if (aesEncryptCall == null) {
            return null;
        }
        List<TopLevelArgument> aesEncryptArguments = splitTopLevelArguments(aesEncryptCall.body());
        if (aesEncryptArguments.size() != 2) {
            return null;
        }

        String plainText = aesEncryptArguments.get(0).text().trim();
        String key = normalizedStringLiteral(aesEncryptArguments.get(1).text());
        if (plainText.isBlank() || key == null) {
            return null;
        }
        return "TO_BASE64(SF_ENCRYPT_CHAR("
                + plainText
                + ", "
                + DM_AES128_ECB_ALGORITHM_ID
                + ", "
                + key
                + ", NULL))";
    }

    private String rewriteAesDecrypt(FunctionCall aesDecryptCall) {
        List<TopLevelArgument> aesDecryptArguments = splitTopLevelArguments(aesDecryptCall.body());
        if (aesDecryptArguments.size() != 2) {
            return null;
        }

        FunctionCall fromBase64Call = readOnlyFunctionCall(aesDecryptArguments.get(0).text(), FROM_BASE64);
        if (fromBase64Call == null) {
            return null;
        }
        List<TopLevelArgument> fromBase64Arguments = splitTopLevelArguments(fromBase64Call.body());
        if (fromBase64Arguments.size() != 1) {
            return null;
        }

        String cipherText = fromBase64Arguments.get(0).text().trim();
        String key = normalizedStringLiteral(aesDecryptArguments.get(1).text());
        if (cipherText.isBlank() || key == null) {
            return null;
        }
        return "SF_DECRYPT_TO_CHAR(FROM_BASE64("
                + cipherText
                + "), "
                + DM_AES128_ECB_ALGORITHM_ID
                + ", "
                + key
                + ", NULL)";
    }

    private FunctionCall readOnlyFunctionCall(String expression, String functionName) {
        int leadingWhitespace = leadingWhitespaceLength(expression);
        FunctionCall functionCall = readFunctionCall(expression, leadingWhitespace, functionName);
        if (functionCall == null || !expression.substring(functionCall.endIndex()).isBlank()) {
            return null;
        }
        return functionCall;
    }

    private int leadingWhitespaceLength(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private boolean containsFunction(String sql, String functionName) {
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (startsFunction(sql, index, functionName)) {
                return true;
            } else {
                index++;
            }
        }
        return false;
    }

    private FunctionCall readFunctionCall(String sql, int functionNameStart, String functionName) {
        if (!startsFunction(sql, functionNameStart, functionName)) {
            return null;
        }
        int openParenIndex = functionNameStart + functionName.length();
        while (openParenIndex < sql.length() && Character.isWhitespace(sql.charAt(openParenIndex))) {
            openParenIndex++;
        }
        int closeParenIndex = findMatchingParen(sql, openParenIndex);
        if (closeParenIndex < 0) {
            return null;
        }
        return new FunctionCall(
                functionNameStart,
                openParenIndex,
                closeParenIndex,
                closeParenIndex + 1,
                sql.substring(openParenIndex + 1, closeParenIndex)
        );
    }

    private boolean startsFunction(String sql, int index, String functionName) {
        if (index > 0 && isIdentifierPart(sql.charAt(index - 1))) {
            return false;
        }
        if (index + functionName.length() > sql.length()
                || !sql.regionMatches(true, index, functionName, 0, functionName.length())) {
            return false;
        }
        int afterName = index + functionName.length();
        if (afterName < sql.length() && isIdentifierPart(sql.charAt(afterName))) {
            return false;
        }
        while (afterName < sql.length() && Character.isWhitespace(sql.charAt(afterName))) {
            afterName++;
        }
        return afterName < sql.length() && sql.charAt(afterName) == '(';
    }

    private int findMatchingParen(String sql, int openParenIndex) {
        int depth = 0;
        int index = openParenIndex;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
                index++;
            } else {
                index++;
            }
        }
        return -1;
    }

    private List<TopLevelArgument> splitTopLevelArguments(String body) {
        List<TopLevelArgument> arguments = new ArrayList<>();
        int depth = 0;
        int argumentStart = 0;
        int index = 0;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(body, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(body, index);
            } else if (startsMyBatisPlaceholder(body, index)) {
                index = skipMyBatisPlaceholder(body, index);
            } else if (startsLineComment(body, index)) {
                index = skipUntilLineEnd(body, index);
            } else if (startsBlockComment(body, index)) {
                index = skipUntilBlockCommentEnd(body, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (current == ',' && depth == 0) {
                arguments.add(new TopLevelArgument(body.substring(argumentStart, index), argumentStart, index));
                index++;
                argumentStart = index;
            } else {
                index++;
            }
        }
        arguments.add(new TopLevelArgument(body.substring(argumentStart), argumentStart, body.length()));
        return arguments;
    }

    private String normalizedStringLiteral(String expression) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.charAt(0) == '\'') {
            SingleQuotedStringLiteral literal = readSingleQuotedStringLiteral(trimmed, 0);
            return literal.closed() && literal.nextIndex() == trimmed.length() ? trimmed : null;
        }
        if (trimmed.charAt(0) == '"') {
            DoubleQuotedStringLiteral literal = readDoubleQuotedStringLiteral(trimmed, 0);
            if (!literal.closed() || literal.nextIndex() != trimmed.length()) {
                return null;
            }
            StringBuilder singleQuoted = new StringBuilder();
            appendSingleQuotedStringLiteral(singleQuoted, literal.value());
            return singleQuoted.toString();
        }
        return null;
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

    private SingleQuotedStringLiteral readSingleQuotedStringLiteral(String sql, int start) {
        StringBuilder value = new StringBuilder();
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\\' && index + 1 < sql.length()) {
                value.append(current).append(sql.charAt(index + 1));
                index += 2;
            } else if (current == '\'') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    value.append(current);
                    index += 2;
                } else {
                    return new SingleQuotedStringLiteral(value.toString(), index + 1, true);
                }
            } else {
                value.append(current);
                index++;
            }
        }
        return new SingleQuotedStringLiteral("", start, false);
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

    private int appendDoubleQuotedText(String sql, int start, StringBuilder converted) {
        int end = skipDoubleQuotedText(sql, start);
        converted.append(sql, start, end);
        return end;
    }

    private boolean startsMyBatisPlaceholder(String sql, int index) {
        return index + 1 < sql.length()
                && (sql.charAt(index) == '#' || sql.charAt(index) == '$')
                && sql.charAt(index + 1) == '{';
    }

    private int appendMyBatisPlaceholder(String sql, int start, StringBuilder converted) {
        int end = skipMyBatisPlaceholder(sql, start);
        converted.append(sql, start, end);
        return end;
    }

    private int skipSingleQuotedString(String sql, int start) {
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '\\' && index < sql.length()) {
                index++;
            } else if (current == '\'') {
                if (index < sql.length() && sql.charAt(index) == '\'') {
                    index++;
                } else {
                    break;
                }
            }
        }
        return index;
    }

    private int skipDoubleQuotedText(String sql, int start) {
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '\\' && index < sql.length()) {
                index++;
            } else if (current == '"') {
                if (index < sql.length() && sql.charAt(index) == '"') {
                    index++;
                } else {
                    break;
                }
            }
        }
        return index;
    }

    private int skipMyBatisPlaceholder(String sql, int start) {
        int end = sql.indexOf('}', start + 2);
        return end < 0 ? sql.length() : end + 1;
    }

    private int skipUntilLineEnd(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '\n') {
                break;
            }
        }
        return index;
    }

    private int skipUntilBlockCommentEnd(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '*' && index < sql.length() && sql.charAt(index) == '/') {
                index++;
                break;
            }
        }
        return index;
    }

    private boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
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

    private record AesBase64Conversion(String convertedSql, boolean changed) {
    }

    private record FunctionCall(int startIndex, int openParenIndex, int closeParenIndex, int endIndex, String body) {
    }

    private record TopLevelArgument(String text, int startIndex, int endIndex) {
    }

    private record SingleQuotedStringLiteral(String value, int nextIndex, boolean closed) {
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
