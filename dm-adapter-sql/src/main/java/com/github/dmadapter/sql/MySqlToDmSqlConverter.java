package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MySqlToDmSqlConverter implements SqlConverter {
    public static final String MYSQL_AES_BASE64_TO_DM_AES128_ECB_RULE = "MYSQL_AES_BASE64_TO_DM_AES128_ECB";
    public static final String MYSQL_BACKTICK_IDENTIFIER_RULE = "MYSQL_BACKTICK_IDENTIFIER_TO_DM";
    public static final String UPDATE_SET_TABLE_ORDER_RULE = "UPDATE_SET_TABLE_ORDER_TO_STANDARD_UPDATE";
    public static final String MYSQL_DATE_SUB_NOW_DAY_RULE = "MYSQL_DATE_SUB_NOW_DAY_TO_DM";
    public static final String MYSQL_REGEXP_OPERATOR_RULE = "MYSQL_REGEXP_OPERATOR_TO_REGEXP_LIKE";
    public static final String MYSQL_CAST_UNSIGNED_RULE = "MYSQL_CAST_UNSIGNED_TO_BIGINT";
    public static final String MYSQL_CONVERT_UNSIGNED_RULE = "MYSQL_CONVERT_UNSIGNED_TO_BIGINT";
    public static final String MYSQL_DATE_ADD_INTERVAL_RULE = "MYSQL_DATE_ADD_INTERVAL_TO_DATEADD";
    public static final String MYSQL_INSERT_IGNORE_TO_DM_MERGE_RULE = "MYSQL_INSERT_IGNORE_TO_DM_MERGE";
    public static final String MYSQL_WITH_RECURSIVE_ALIAS_RULE = "MYSQL_WITH_RECURSIVE_COLUMN_ALIAS";
    public static final String MYSQL_UPDATE_JOIN_RULE = "MYSQL_UPDATE_JOIN_TO_DM_UPDATE_FROM";
    public static final String MYSQL_TABLE_ALIAS_AS_RULE = "MYSQL_TABLE_ALIAS_AS_TO_DM";
    public static final String MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE = "MYSQL_GROUP_CONCAT_TO_DM_LISTAGG";
    public static final String MYSQL_JSON_TABLE_JOIN_TO_DM_CROSS_JOIN_RULE =
            "MYSQL_JSON_TABLE_JOIN_TO_DM_CROSS_JOIN";
    public static final String MYSQL_SINGLE_QUOTED_ALIAS_RULE = "MYSQL_SINGLE_QUOTED_ALIAS_TO_DM_IDENTIFIER";
    public static final String MYSQL_INSERT_VALUE_TO_VALUES_RULE = "MYSQL_INSERT_VALUE_TO_VALUES";
    public static final String MYSQL_INDEX_HINT_REMOVAL_RULE = "MYSQL_INDEX_HINT_REMOVED";
    public static final String MYSQL_CONVERT_DECIMAL_RULE = "MYSQL_CONVERT_DECIMAL_TO_CAST";
    public static final String MYSQL_SELECT_MODIFIER_REMOVAL_RULE = "MYSQL_SELECT_MODIFIER_REMOVED";
    public static final String DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE = "DAMENG_KEYWORD_IDENTIFIER_QUOTE";
    public static final String MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE =
            "MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE";

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
    private static final Pattern GROUP_CONCAT_PATTERN = Pattern.compile("\\bGROUP_CONCAT\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_IGNORE_PATTERN = Pattern.compile(
            "\\bINSERT\\s+IGNORE\\s+INTO\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CAST_UNSIGNED_BODY_PATTERN = Pattern.compile(
            "(?is)^\\s*(.+?)\\s+AS\\s+UNSIGNED(?:\\s+INTEGER)?\\s*$"
    );
    private static final Pattern DECIMAL_TARGET_TYPE_PATTERN = Pattern.compile(
            "(?is)^\\s*DECIMAL\\s*\\(\\s*\\d+\\s*,\\s*\\d+\\s*\\)\\s*$"
    );
    private static final Pattern INTERVAL_DAY_PATTERN = Pattern.compile(
            "(?is)^\\s*INTERVAL\\s+(.+?)\\s+DAY\\s*$"
    );
    private static final Pattern MYSQL_INTERVAL_PATTERN = Pattern.compile(
            "(?is)^\\s*INTERVAL\\s+(.+?)\\s+(YEAR|MONTH|DAY|HOUR|MINUTE|SECOND)\\s*$"
    );
    private static final Pattern SIMPLE_INTERVAL_AMOUNT_PATTERN = Pattern.compile(
            "(?is)^-?(?:\\d+|#\\{[^}]+}|\\$\\{[^}]+})$"
    );
    private static final List<String> MYSQL_INTERVAL_UNITS =
            List.of("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND");
    private static final List<String> MYSQL_SELECT_MODIFIERS_TO_REMOVE = List.of(
            "SQL_BIG_RESULT",
            "SQL_SMALL_RESULT",
            "SQL_BUFFER_RESULT",
            "SQL_CALC_FOUND_ROWS",
            "SQL_CACHE",
            "SQL_NO_CACHE",
            "HIGH_PRIORITY",
            "STRAIGHT_JOIN"
    );
    private static final Set<String> MYSQL_SELECT_MODIFIER_CONTEXT_WORDS = Set.of(
            "SELECT",
            "ALL",
            "DISTINCT",
            "DISTINCTROW",
            "SQL_BIG_RESULT",
            "SQL_SMALL_RESULT",
            "SQL_BUFFER_RESULT",
            "SQL_CALC_FOUND_ROWS",
            "SQL_CACHE",
            "SQL_NO_CACHE",
            "HIGH_PRIORITY",
            "STRAIGHT_JOIN"
    );
    private static final Pattern CTE_ALIAS_PATTERN = Pattern.compile(
            "(?is).*\\s+AS\\s+(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*$"
    );
    private static final List<String> MYSQL_FUNCTIONS_REQUIRING_REVIEW = List.of(
            "DATE_SUB",
            "STR_TO_DATE",
            "UNIX_TIMESTAMP",
            "FROM_UNIXTIME",
            "TIMESTAMPDIFF",
            "CONCAT_WS",
            "JSON_CONTAINS",
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
            "JSON_UNQUOTE"
    );
    private static final Set<String> DAMENG_KEYWORDS_REQUIRING_QUOTES = Set.of(
            "ADD",
            "ALTER",
            "AND",
            "AS",
            "ASC",
            "BETWEEN",
            "BY",
            "COMMENT",
            "CONNECT",
            "CREATE",
            "DATE",
            "DELETE",
            "DESC",
            "DROP",
            "FROM",
            "FULL",
            "GROUP",
            "IN",
            "INDEX",
            "INNER",
            "INSERT",
            "INTERVAL",
            "INTO",
            "IS",
            "JOIN",
            "KEY",
            "LEFT",
            "LEVEL",
            "LIKE",
            "NOT",
            "NULL",
            "ON",
            "OR",
            "ORDER",
            "OUTER",
            "PRIOR",
            "RIGHT",
            "ROW",
            "SELECT",
            "SET",
            "START",
            "STATE",
            "TABLE",
            "TIME",
            "TIMESTAMP",
            "TYPE",
            "UNION",
            "UPDATE",
            "USER",
            "VALUES",
            "VERIFY",
            "VIEW",
            "WHERE",
            "WITH"
    );
    private static final Pattern LIMIT_COMMA_PATTERN = Pattern.compile(
            "(?is)^(?<base>.+?)\\s+LIMIT\\s+(?<offset>" + TOKEN + ")\\s*,\\s*(?<size>" + TOKEN + ")\\s*;?\\s*$");
    private static final Pattern LIMIT_OFFSET_PATTERN = Pattern.compile(
            "(?is)^(?<base>.+?)\\s+LIMIT\\s+(?<size>" + TOKEN + ")\\s+OFFSET\\s+(?<offset>" + TOKEN + ")\\s*;?\\s*$");
    private static final Pattern LIMIT_SIZE_PATTERN = Pattern.compile(
            "(?is)^(?<base>.+?)\\s+LIMIT\\s+(?<size>" + TOKEN + ")\\s*;?\\s*$");
    private static final String DM_IDENTIFIER = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")";
    private static final Pattern UPDATE_SET_TABLE_ORDER_PATTERN = Pattern.compile(
            "(?is)^(\\s*)update\\s+set\\s+("
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")?)(\\s+)(.+)$"
    );

    @Override
    public SqlConversionResult convert(String sql) {
        return convert(sql, List.of());
    }

    @Override
    public SqlConversionResult convert(String sql, List<String> upsertKeyColumns) {
        if (sql == null || sql.isBlank()) {
            return SqlConversionResult.unchanged(sql == null ? "" : sql);
        }
        String original = sql;
        String converted = original;
        List<String> rules = new ArrayList<>();

        DoubleQuotedStringConversion doubleQuotedStringConversion = convertDoubleQuotedStringLiterals(converted);
        if (doubleQuotedStringConversion.changed()) {
            converted = doubleQuotedStringConversion.convertedSql();
            rules.add("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
        }

        BacktickIdentifierConversion backtickIdentifierConversion = convertBacktickIdentifiers(converted);
        if (backtickIdentifierConversion.changed()) {
            converted = backtickIdentifierConversion.convertedSql();
            rules.add(MYSQL_BACKTICK_IDENTIFIER_RULE);
        }

        GenericConversion singleQuotedAliasConversion = convertSingleQuotedAliases(converted);
        if (singleQuotedAliasConversion.changed()) {
            converted = singleQuotedAliasConversion.convertedSql();
            rules.add(MYSQL_SINGLE_QUOTED_ALIAS_RULE);
        }

        GenericConversion selectModifierConversion = removeMysqlSelectModifiers(converted);
        if (selectModifierConversion.changed()) {
            converted = selectModifierConversion.convertedSql();
            rules.add(MYSQL_SELECT_MODIFIER_REMOVAL_RULE);
        }

        GenericConversion dateSubConversion = convertDateSubNowDay(converted);
        if (dateSubConversion.changed()) {
            converted = dateSubConversion.convertedSql();
            rules.add(MYSQL_DATE_SUB_NOW_DAY_RULE);
        }

        GenericConversion dateAddIntervalConversion = convertDateAddInterval(converted);
        if (dateAddIntervalConversion.changed()) {
            converted = dateAddIntervalConversion.convertedSql();
            rules.add(MYSQL_DATE_ADD_INTERVAL_RULE);
        }

        GenericConversion unsignedCastConversion = convertUnsignedCasts(converted);
        if (unsignedCastConversion.changed()) {
            converted = unsignedCastConversion.convertedSql();
            rules.add(MYSQL_CAST_UNSIGNED_RULE);
        }

        GenericConversion unsignedConvertConversion = convertUnsignedConvertFunctions(converted);
        if (unsignedConvertConversion.changed()) {
            converted = unsignedConvertConversion.convertedSql();
            rules.add(MYSQL_CONVERT_UNSIGNED_RULE);
        }

        GenericConversion decimalConvertConversion = convertDecimalConvertFunctions(converted);
        if (decimalConvertConversion.changed()) {
            converted = decimalConvertConversion.convertedSql();
            rules.add(MYSQL_CONVERT_DECIMAL_RULE);
        }

        GenericConversion withRecursiveConversion = addRecursiveCteColumnAliases(converted);
        if (withRecursiveConversion.changed()) {
            converted = withRecursiveConversion.convertedSql();
            rules.add(MYSQL_WITH_RECURSIVE_ALIAS_RULE);
        }

        GenericConversion regexpConversion = convertRegexpOperators(converted);
        if (regexpConversion.changed()) {
            converted = regexpConversion.convertedSql();
            rules.add(MYSQL_REGEXP_OPERATOR_RULE);
        }

        GenericConversion updateJoinConversion = convertMysqlUpdateJoin(converted);
        if (updateJoinConversion.changed()) {
            converted = updateJoinConversion.convertedSql();
            rules.add(MYSQL_UPDATE_JOIN_RULE);
        }

        GenericConversion tableAliasAsConversion = removeAsFromTableAliases(converted);
        if (tableAliasAsConversion.changed()) {
            converted = tableAliasAsConversion.convertedSql();
            rules.add(MYSQL_TABLE_ALIAS_AS_RULE);
        }

        GenericConversion jsonTableJoinConversion = convertJsonTableJoinWithoutCondition(converted);
        if (jsonTableJoinConversion.changed()) {
            converted = jsonTableJoinConversion.convertedSql();
            rules.add(MYSQL_JSON_TABLE_JOIN_TO_DM_CROSS_JOIN_RULE);
        }

        GenericConversion indexHintConversion = removeMysqlIndexHints(converted);
        if (indexHintConversion.changed()) {
            converted = indexHintConversion.convertedSql();
            rules.add(MYSQL_INDEX_HINT_REMOVAL_RULE);
        }

        UpdateSetTableOrderConversion updateSetTableOrderConversion = convertUpdateSetTableOrder(converted);
        if (updateSetTableOrderConversion.changed()) {
            converted = updateSetTableOrderConversion.convertedSql();
            rules.add(UPDATE_SET_TABLE_ORDER_RULE);
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

        GenericConversion keywordQuoteConversion = quoteDamengKeywordIdentifiers(converted);
        if (keywordQuoteConversion.changed()) {
            converted = keywordQuoteConversion.convertedSql();
            rules.add(DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE);
        }

        GenericConversion groupConcatConversion = convertGroupConcat(converted);
        if (groupConcatConversion.changed()) {
            converted = groupConcatConversion.convertedSql();
            rules.add(MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
        }

        LimitConversion limitConversion = convertLimit(converted);
        if (limitConversion.manualReviewReason() != null) {
            return manualReviewResult(original, converted, rules, limitConversion.manualReviewReason());
        }
        if (limitConversion.convertedSql() != null) {
            converted = limitConversion.convertedSql();
            rules.add(limitConversion.ruleName());
        }

        GenericConversion insertValueConversion = convertSingularInsertValueKeyword(converted);
        if (insertValueConversion.changed()) {
            converted = insertValueConversion.convertedSql();
            rules.add(MYSQL_INSERT_VALUE_TO_VALUES_RULE);
        }

        GenericConversion insertIgnoreConversion = convertInsertIgnore(converted, upsertKeyColumns);
        if (insertIgnoreConversion.changed()) {
            converted = insertIgnoreConversion.convertedSql();
            rules.add(MYSQL_INSERT_IGNORE_TO_DM_MERGE_RULE);
        }

        GenericConversion onDuplicateKeyUpdateConversion = convertOnDuplicateKeyUpdate(converted, upsertKeyColumns);
        if (onDuplicateKeyUpdateConversion.changed()) {
            converted = onDuplicateKeyUpdateConversion.convertedSql();
            rules.add(MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
        }

        String unsupportedReason = unsupportedReason(converted);
        if (!unsupportedReason.isBlank()) {
            return manualReviewResult(original, converted, rules, unsupportedReason);
        }

        parseIfPossible(converted);
        if (rules.isEmpty()) {
            return SqlConversionResult.unchanged(original);
        }
        return SqlConversionResult.changed(original, converted, rules);
    }

    private SqlConversionResult manualReviewResult(
            String original,
            String converted,
            List<String> rules,
            String reason
    ) {
        if (!rules.isEmpty() && !original.equals(converted)) {
            return SqlConversionResult.changedWithManualReview(original, converted, rules, reason);
        }
        return SqlConversionResult.manualReview(original, reason);
    }

    private String unsupportedReason(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT);
        if (upper.contains("ON DUPLICATE KEY UPDATE")) {
            return "ON DUPLICATE KEY UPDATE requires configured keyColumns for safe Dameng MERGE rewrite.";
        }
        if (upper.contains("REPLACE INTO")) {
            return "REPLACE INTO has no safe automatic Dameng rewrite in MVP.";
        }
        if (GROUP_CONCAT_PATTERN.matcher(sql).find()) {
            return "GROUP_CONCAT requires manual confirmation for Dameng aggregate syntax.";
        }
        if (upper.contains("INFORMATION_SCHEMA") || upper.contains("DATABASE()")) {
            return "MySQL metadata SQL such as information_schema/database() requires manual Dameng rewrite.";
        }
        if (containsKeywordOutsideIgnoredText(sql, "REGEXP")) {
            return "REGEXP requires manual confirmation because Dameng regular-expression syntax may differ from MySQL.";
        }
        if (INSERT_IGNORE_PATTERN.matcher(sql).find()) {
            return "INSERT IGNORE requires configured keyColumns for safe Dameng MERGE rewrite.";
        }
        if (containsMysqlUpdateJoin(sql)) {
            return "MySQL UPDATE JOIN is present but not simple enough for automatic Dameng UPDATE FROM rewrite.";
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
        return "";
    }

    private GenericConversion convertSingleQuotedAliases(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                SingleQuotedStringLiteral literal = readSingleQuotedStringLiteral(sql, index);
                if (literal.closed() && isAsAliasPosition(sql, index) && !literal.value().isBlank()) {
                    converted.append(quoteDamengIdentifier(literal.value()));
                    index = literal.nextIndex();
                    changed = true;
                } else {
                    index = appendSingleQuotedString(sql, index, converted);
                }
            } else if (current == '"') {
                index = appendDoubleQuotedText(sql, index, converted);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = appendMyBatisPlaceholder(sql, index, converted);
            } else if (startsLineComment(sql, index)) {
                index = appendUntilLineEnd(sql, index, converted);
            } else if (startsBlockComment(sql, index)) {
                index = appendUntilBlockCommentEnd(sql, index, converted);
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private boolean isAsAliasPosition(String sql, int quoteIndex) {
        WordToken previousWord = previousWord(sql, quoteIndex);
        return previousWord != null
                && "AS".equalsIgnoreCase(previousWord.text())
                && isOnlyWhitespace(sql, previousWord.endIndex(), quoteIndex);
    }

    private GenericConversion removeMysqlSelectModifiers(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        int lastCopiedIndex = 0;
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
            } else {
                KeywordReplacement modifier = readMysqlSelectModifierRemoval(sql, index);
                if (modifier == null) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, modifier.startIndex());
                    lastCopiedIndex = modifier.endIndex();
                    index = modifier.endIndex();
                    changed = true;
                }
            }
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        converted.append(sql, lastCopiedIndex, sql.length());
        return new GenericConversion(converted.toString(), true);
    }

    private KeywordReplacement readMysqlSelectModifierRemoval(String sql, int index) {
        for (String modifier : MYSQL_SELECT_MODIFIERS_TO_REMOVE) {
            if (startsKeyword(sql, index, modifier) && isMysqlSelectModifierContext(sql, index)) {
                return new KeywordReplacement(index, skipWhitespace(sql, index + modifier.length()));
            }
        }
        return null;
    }

    private boolean isMysqlSelectModifierContext(String sql, int index) {
        WordToken previousWord = previousWord(sql, index);
        return previousWord != null
                && isOnlyWhitespace(sql, previousWord.endIndex(), index)
                && MYSQL_SELECT_MODIFIER_CONTEXT_WORDS.contains(previousWord.text().toUpperCase(Locale.ROOT));
    }

    private GenericConversion convertDateSubNowDay(String sql) {
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
            } else if (startsFunction(sql, index, "DATE_SUB")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "DATE_SUB");
                String replacement = functionCall == null ? null : rewriteDateSubNowDay(functionCall);
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
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private String rewriteDateSubNowDay(FunctionCall dateSubCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(dateSubCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        Matcher matcher = INTERVAL_DAY_PATTERN.matcher(arguments.get(1).text());
        if (!matcher.matches()) {
            return null;
        }
        String amount = matcher.group(1).trim();
        if (amount.isBlank()) {
            return null;
        }
        String dateExpression = arguments.get(0).text().trim();
        if (isCurDateExpression(dateExpression)) {
            return "DATEADD(DAY, " + negatedIntervalAmount(amount) + ", " + dateExpression + ")";
        }
        if (!isNowExpression(dateExpression)) {
            return null;
        }
        return "(SYSDATE - " + amount + ")";
    }

    private GenericConversion convertDateAddInterval(String sql) {
        GenericConversion functionConversion = convertDateAddFunctionInterval(sql);
        GenericConversion additionConversion = convertDateIntervalAddition(functionConversion.convertedSql());
        return new GenericConversion(
                additionConversion.convertedSql(),
                functionConversion.changed() || additionConversion.changed()
        );
    }

    private GenericConversion convertDateAddFunctionInterval(String sql) {
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
            } else if (startsFunction(sql, index, "DATE_ADD")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "DATE_ADD");
                String replacement = functionCall == null ? null : rewriteDateAddInterval(functionCall);
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
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private GenericConversion convertDateIntervalAddition(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        int lastCopiedIndex = 0;
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
            } else if (current == '+') {
                DateIntervalAddition addition = readDateIntervalAddition(sql, index);
                if (addition == null || addition.leftExpression().startIndex() < lastCopiedIndex) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, addition.leftExpression().startIndex());
                    converted.append("DATEADD(")
                            .append(addition.intervalExpression().unit())
                            .append(", ")
                            .append(addition.intervalExpression().amount())
                            .append(", ")
                            .append(addition.leftExpression().text())
                            .append(")");
                    lastCopiedIndex = addition.intervalExpression().endIndex();
                    index = lastCopiedIndex;
                    changed = true;
                }
            } else {
                index++;
            }
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        converted.append(sql, lastCopiedIndex, sql.length());
        return new GenericConversion(converted.toString(), true);
    }

    private DateIntervalAddition readDateIntervalAddition(String sql, int plusIndex) {
        DateExpression leftExpression = readDateExpressionBeforePlus(sql, plusIndex);
        IntervalExpression intervalExpression = readIntervalExpressionAfterPlus(sql, plusIndex);
        if (leftExpression == null || intervalExpression == null) {
            return null;
        }
        return new DateIntervalAddition(leftExpression, intervalExpression);
    }

    private DateExpression readDateExpressionBeforePlus(String sql, int plusIndex) {
        int end = skipWhitespaceBackward(sql, plusIndex);
        if (end <= 0) {
            return null;
        }
        int start;
        char previous = sql.charAt(end - 1);
        if (previous == ')') {
            int openParenIndex = findMatchingOpenParenBackward(sql, end - 1);
            if (openParenIndex < 0) {
                return null;
            }
            start = readExpressionNameStartBeforeParen(sql, openParenIndex);
        } else if (previous == '}') {
            start = readMyBatisPlaceholderStartBackward(sql, end);
            if (start < 0) {
                return null;
            }
        } else {
            start = end - 1;
            while (start > 0 && isOperandIdentifierChar(sql.charAt(start - 1))) {
                start--;
            }
        }
        String expression = sql.substring(start, end).trim();
        if (expression.isBlank() || expression.endsWith("+") || expression.endsWith("-")) {
            return null;
        }
        return new DateExpression(start, end, expression);
    }

    private IntervalExpression readIntervalExpressionAfterPlus(String sql, int plusIndex) {
        int intervalStart = skipWhitespace(sql, plusIndex + 1);
        if (!startsKeyword(sql, intervalStart, "INTERVAL")) {
            return null;
        }
        int amountStart = skipWhitespace(sql, intervalStart + "INTERVAL".length());
        if (amountStart >= sql.length()) {
            return null;
        }
        int depth = 0;
        int index = amountStart;
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
            } else {
                if (current == '(') {
                    depth++;
                    index++;
                } else if (current == ')') {
                    if (depth == 0) {
                        return null;
                    }
                    depth--;
                    index++;
                } else {
                    String unit = intervalUnitAt(sql, index);
                    if (depth == 0 && unit != null) {
                        int amountEnd = skipWhitespaceBackward(sql, index);
                        String amount = sql.substring(amountStart, amountEnd).trim();
                        if (amount.isBlank()) {
                            return null;
                        }
                        return new IntervalExpression(amount, unit, index + unit.length());
                    }
                    index++;
                }
            }
        }
        return null;
    }

    private String intervalUnitAt(String sql, int index) {
        for (String unit : MYSQL_INTERVAL_UNITS) {
            if (startsKeyword(sql, index, unit)) {
                return unit;
            }
        }
        return null;
    }

    private String rewriteDateAddInterval(FunctionCall dateAddCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(dateAddCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String dateExpression = arguments.get(0).text().trim();
        Matcher matcher = MYSQL_INTERVAL_PATTERN.matcher(arguments.get(1).text());
        if (dateExpression.isBlank() || !matcher.matches()) {
            return null;
        }
        String amount = matcher.group(1).trim();
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);
        if (amount.isBlank()) {
            return null;
        }
        return "DATEADD(" + unit + ", " + amount + ", " + dateExpression + ")";
    }

    private boolean isNowExpression(String expression) {
        String trimmed = expression.trim();
        return "SYSDATE".equalsIgnoreCase(trimmed) || readOnlyFunctionCall(trimmed, "NOW") != null;
    }

    private boolean isCurDateExpression(String expression) {
        return readOnlyFunctionCall(expression.trim(), "CURDATE") != null;
    }

    private String negatedIntervalAmount(String amount) {
        String trimmed = amount.trim();
        if (trimmed.startsWith("-")) {
            return trimmed.substring(1).trim();
        }
        if (SIMPLE_INTERVAL_AMOUNT_PATTERN.matcher(trimmed).matches()) {
            return "-" + trimmed;
        }
        return "(0 - " + trimmed + ")";
    }

    private GenericConversion convertUnsignedCasts(String sql) {
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
            } else if (startsFunction(sql, index, "CAST")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "CAST");
                String replacement = functionCall == null ? null : rewriteUnsignedCast(functionCall);
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
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private String rewriteUnsignedCast(FunctionCall castCall) {
        Matcher matcher = CAST_UNSIGNED_BODY_PATTERN.matcher(castCall.body());
        if (!matcher.matches()) {
            return null;
        }
        String expression = matcher.group(1).trim();
        if (expression.isBlank()) {
            return null;
        }
        return "CAST(" + expression + " AS BIGINT)";
    }

    private GenericConversion convertUnsignedConvertFunctions(String sql) {
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
            } else if (startsFunction(sql, index, "CONVERT")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "CONVERT");
                String replacement = functionCall == null ? null : rewriteUnsignedConvert(functionCall);
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
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private String rewriteUnsignedConvert(FunctionCall convertCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(convertCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String expression = arguments.get(0).text().trim();
        String targetType = arguments.get(1).text().trim();
        if (expression.isBlank() || !targetType.matches("(?is)UNSIGNED(?:\\s+INTEGER)?")) {
            return null;
        }
        return "CAST(" + expression + " AS BIGINT)";
    }

    private GenericConversion convertDecimalConvertFunctions(String sql) {
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
            } else if (startsFunction(sql, index, "CONVERT")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "CONVERT");
                String replacement = functionCall == null ? null : rewriteDecimalConvert(functionCall);
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
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private String rewriteDecimalConvert(FunctionCall convertCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(convertCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String expression = arguments.get(0).text().trim();
        String targetType = arguments.get(1).text().trim();
        if (expression.isBlank() || !DECIMAL_TARGET_TYPE_PATTERN.matcher(targetType).matches()) {
            return null;
        }
        return "CAST(" + expression + " AS " + targetType + ")";
    }

    private GenericConversion addRecursiveCteColumnAliases(String sql) {
        int withIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, withIndex, "WITH")) {
            return GenericConversion.unchanged(sql);
        }
        int recursiveIndex = skipWhitespace(sql, withIndex + "WITH".length());
        if (!startsKeyword(sql, recursiveIndex, "RECURSIVE")) {
            return GenericConversion.unchanged(sql);
        }
        int cteNameStart = skipWhitespace(sql, recursiveIndex + "RECURSIVE".length());
        IdentifierToken cteName = readIdentifierToken(sql, cteNameStart);
        if (cteName == null) {
            return GenericConversion.unchanged(sql);
        }
        int afterCteName = skipWhitespace(sql, cteName.endIndex());
        if (afterCteName < sql.length() && sql.charAt(afterCteName) == '(') {
            return GenericConversion.unchanged(sql);
        }
        if (!startsKeyword(sql, afterCteName, "AS")) {
            return GenericConversion.unchanged(sql);
        }
        int openParenIndex = skipWhitespace(sql, afterCteName + "AS".length());
        if (openParenIndex >= sql.length() || sql.charAt(openParenIndex) != '(') {
            return GenericConversion.unchanged(sql);
        }
        int closeParenIndex = findMatchingParen(sql, openParenIndex);
        if (closeParenIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        List<String> aliases = inferCteColumnAliases(sql.substring(openParenIndex + 1, closeParenIndex));
        if (aliases.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        String converted = sql.substring(0, cteName.endIndex())
                + "("
                + String.join(", ", aliases)
                + ")"
                + sql.substring(cteName.endIndex());
        return new GenericConversion(converted, true);
    }

    private List<String> inferCteColumnAliases(String cteBody) {
        int selectIndex = findTopLevelKeyword(cteBody, "SELECT", 0);
        if (selectIndex < 0) {
            return List.of();
        }
        int fromIndex = findTopLevelKeyword(cteBody, "FROM", selectIndex + "SELECT".length());
        if (fromIndex < 0) {
            return List.of();
        }
        String selectList = cteBody.substring(selectIndex + "SELECT".length(), fromIndex);
        List<String> aliases = new ArrayList<>();
        for (TopLevelArgument item : splitTopLevelArguments(selectList)) {
            String alias = inferSelectItemAlias(item.text());
            if (alias == null || alias.isBlank()) {
                return List.of();
            }
            aliases.add(alias);
        }
        return aliases;
    }

    private String inferSelectItemAlias(String selectItem) {
        String trimmed = selectItem.trim();
        Matcher aliasMatcher = CTE_ALIAS_PATTERN.matcher(trimmed);
        if (aliasMatcher.matches()) {
            return aliasMatcher.group(1).trim();
        }
        int dotIndex = trimmed.lastIndexOf('.');
        String candidate = dotIndex >= 0 ? trimmed.substring(dotIndex + 1).trim() : trimmed;
        if (isSimpleIdentifier(candidate)) {
            return candidate;
        }
        return null;
    }

    private GenericConversion convertRegexpOperators(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        int lastCopiedIndex = 0;
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
            } else if (startsKeyword(sql, index, "REGEXP")) {
                RegexpExpression expression = readRegexpExpression(sql, index);
                if (expression != null && expression.startIndex() >= lastCopiedIndex) {
                    converted.append(sql, lastCopiedIndex, expression.startIndex());
                    converted.append(expression.replacement());
                    index = expression.endIndex();
                    lastCopiedIndex = expression.endIndex();
                    changed = true;
                } else {
                    index++;
                }
            } else {
                index++;
            }
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        converted.append(sql, lastCopiedIndex, sql.length());
        return new GenericConversion(converted.toString(), true);
    }

    private RegexpExpression readRegexpExpression(String sql, int regexpIndex) {
        int leftEnd = regexpIndex;
        boolean negated = false;
        WordToken previousWord = previousWord(sql, regexpIndex);
        if (previousWord != null
                && "NOT".equalsIgnoreCase(previousWord.text())
                && isOnlyWhitespace(sql, previousWord.endIndex(), regexpIndex)) {
            negated = true;
            leftEnd = previousWord.startIndex();
        }
        Operand left = readLeftOperand(sql, leftEnd);
        Operand right = readRightOperand(sql, regexpIndex + "REGEXP".length());
        if (left == null || right == null) {
            return null;
        }
        String replacement = (negated ? "NOT " : "")
                + "REGEXP_LIKE("
                + left.text().trim()
                + ", "
                + right.text().trim()
                + ")";
        return new RegexpExpression(left.startIndex(), right.endIndex(), replacement);
    }

    private Operand readLeftOperand(String sql, int endExclusive) {
        int end = skipWhitespaceBackward(sql, endExclusive);
        if (end <= 0) {
            return null;
        }
        int start;
        if (sql.charAt(end - 1) == ')') {
            start = findMatchingOpenParenBackward(sql, end - 1);
            if (start < 0) {
                return null;
            }
            start = readFunctionNameStartBeforeParen(sql, start);
        } else {
            start = end;
            while (start > 0 && isOperandIdentifierChar(sql.charAt(start - 1))) {
                start--;
            }
        }
        String text = sql.substring(start, end);
        return text.isBlank() ? null : new Operand(start, end, text);
    }

    private Operand readRightOperand(String sql, int startInclusive) {
        int start = skipWhitespace(sql, startInclusive);
        if (start >= sql.length()) {
            return null;
        }
        int end;
        char current = sql.charAt(start);
        if (startsMyBatisPlaceholder(sql, start)) {
            end = skipMyBatisPlaceholder(sql, start);
        } else if (current == '\'') {
            end = skipSingleQuotedString(sql, start);
        } else if (current == '"') {
            end = skipDoubleQuotedText(sql, start);
        } else if (current == '(') {
            int close = findMatchingParen(sql, start);
            if (close < 0) {
                return null;
            }
            end = close + 1;
        } else {
            end = start;
            while (end < sql.length() && !isRegexpRightBoundary(sql, end)) {
                end++;
            }
        }
        String text = sql.substring(start, end);
        return text.isBlank() ? null : new Operand(start, end, text);
    }

    private boolean isRegexpRightBoundary(String sql, int index) {
        char current = sql.charAt(index);
        if (current == ')' || current == ',' || current == ';') {
            return true;
        }
        return Character.isWhitespace(current)
                && (startsKeyword(sql, skipWhitespace(sql, index), "AND")
                || startsKeyword(sql, skipWhitespace(sql, index), "OR")
                || startsKeyword(sql, skipWhitespace(sql, index), "ORDER")
                || startsKeyword(sql, skipWhitespace(sql, index), "GROUP")
                || startsKeyword(sql, skipWhitespace(sql, index), "LIMIT"));
    }

    private GenericConversion convertMysqlUpdateJoin(String sql) {
        int updateIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, updateIndex, "UPDATE")) {
            return GenericConversion.unchanged(sql);
        }
        int joinIndex = findTopLevelKeyword(sql, "JOIN", updateIndex + "UPDATE".length());
        if (joinIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int setIndex = findTopLevelKeyword(sql, "SET", joinIndex + "JOIN".length());
        if (setIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int joinTypeStart = joinTypeStart(sql, joinIndex);
        String target = sql.substring(updateIndex + "UPDATE".length(), joinTypeStart).trim();
        String joinSource = sql.substring(joinIndex + "JOIN".length(), setIndex).trim();
        if (target.isBlank() || joinSource.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        int whereIndex = findTopLevelKeyword(sql, "WHERE", setIndex + "SET".length());
        int statementEnd = stripTrailingSemicolon(sql);
        String setClause = sql.substring(setIndex + "SET".length(), whereIndex < 0 ? statementEnd : whereIndex).trim();
        String whereClause = whereIndex < 0 ? "" : sql.substring(whereIndex + "WHERE".length(), statementEnd).trim();
        if (setClause.isBlank()) {
            return GenericConversion.unchanged(sql);
        }

        JoinSource splitJoin = splitJoinSource(joinSource);
        if (splitJoin.sourceSql().isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        if (findTopLevelKeyword(splitJoin.sourceSql(), "JOIN", 0) >= 0
                || findTopLevelKeyword(splitJoin.conditionSql(), "JOIN", 0) >= 0) {
            return GenericConversion.unchanged(sql);
        }
        List<String> whereParts = new ArrayList<>();
        if (!splitJoin.conditionSql().isBlank()) {
            whereParts.add(splitJoin.conditionSql());
        }
        if (!whereClause.isBlank()) {
            whereParts.add(whereClause);
        }

        StringBuilder converted = new StringBuilder(sql.length());
        converted.append(sql, 0, updateIndex)
                .append("update ")
                .append(target)
                .append(" set ")
                .append(setClause)
                .append(" from ")
                .append(splitJoin.sourceSql());
        if (!whereParts.isEmpty()) {
            converted.append(" where ").append(String.join(" and ", whereParts));
        }
        converted.append(sql.substring(statementEnd));
        return new GenericConversion(converted.toString(), true);
    }

    private GenericConversion removeAsFromTableAliases(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        int lastCopiedIndex = 0;
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
            } else if (startsKeyword(sql, index, "FROM")) {
                AliasAsRemoval removal = readTableAliasAsRemoval(sql, index, "FROM");
                if (removal == null) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, removal.asIndex());
                    converted.append(sql, removal.aliasStartIndex(), removal.aliasEndIndex());
                    lastCopiedIndex = removal.aliasEndIndex();
                    index = removal.aliasEndIndex();
                    changed = true;
                }
            } else if (startsKeyword(sql, index, "JOIN")) {
                AliasAsRemoval removal = readTableAliasAsRemoval(sql, index, "JOIN");
                if (removal == null) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, removal.asIndex());
                    converted.append(sql, removal.aliasStartIndex(), removal.aliasEndIndex());
                    lastCopiedIndex = removal.aliasEndIndex();
                    index = removal.aliasEndIndex();
                    changed = true;
                }
            } else {
                index++;
            }
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        converted.append(sql, lastCopiedIndex, sql.length());
        return new GenericConversion(converted.toString(), true);
    }

    private AliasAsRemoval readTableAliasAsRemoval(String sql, int keywordIndex, String keyword) {
        int relationStart = skipWhitespace(sql, keywordIndex + keyword.length());
        int relationEnd;
        if (relationStart < sql.length() && sql.charAt(relationStart) == '(') {
            relationEnd = findMatchingParen(sql, relationStart);
            if (relationEnd < 0) {
                return null;
            }
            relationEnd++;
        } else {
            IdentifierToken relation = readQualifiedIdentifierToken(sql, relationStart);
            if (relation == null) {
                return null;
            }
            relationEnd = relation.endIndex();
        }
        int asIndex = skipWhitespace(sql, relationEnd);
        if (!startsKeyword(sql, asIndex, "AS")) {
            return null;
        }
        int aliasStart = skipWhitespace(sql, asIndex + "AS".length());
        IdentifierToken alias = readIdentifierToken(sql, aliasStart);
        if (alias == null || isSqlClauseKeyword(alias.text())) {
            return null;
        }
        return new AliasAsRemoval(asIndex, aliasStart, alias.endIndex());
    }

    private IdentifierToken readQualifiedIdentifierToken(String sql, int start) {
        IdentifierToken first = readIdentifierToken(sql, start);
        if (first == null) {
            return null;
        }
        int end = first.endIndex();
        int cursor = skipWhitespace(sql, end);
        if (cursor < sql.length() && sql.charAt(cursor) == '.') {
            int secondStart = skipWhitespace(sql, cursor + 1);
            IdentifierToken second = readIdentifierToken(sql, secondStart);
            if (second == null) {
                return null;
            }
            end = second.endIndex();
        }
        return new IdentifierToken(sql.substring(start, end), end);
    }

    private boolean isSqlClauseKeyword(String value) {
        String upper = unquoteIdentifier(value).toUpperCase(Locale.ROOT);
        return Set.of(
                "WHERE",
                "ON",
                "INNER",
                "LEFT",
                "RIGHT",
                "FULL",
                "JOIN",
                "GROUP",
                "ORDER",
                "HAVING",
                "LIMIT",
                "FETCH",
                "UNION"
        ).contains(upper);
    }

    private String unquoteIdentifier(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("`") && trimmed.endsWith("`")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("``", "`");
        }
        return trimmed;
    }

    private boolean containsMysqlUpdateJoin(String sql) {
        int updateIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, updateIndex, "UPDATE")) {
            return false;
        }
        int joinIndex = findTopLevelKeyword(sql, "JOIN", updateIndex + "UPDATE".length());
        if (joinIndex < 0) {
            return false;
        }
        return findTopLevelKeyword(sql, "SET", joinIndex + "JOIN".length()) >= 0;
    }

    private GenericConversion convertJsonTableJoinWithoutCondition(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        int lastCopiedIndex = 0;
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
            } else if (startsKeyword(sql, index, "JOIN")) {
                JsonTableJoin join = readJsonTableJoinWithoutCondition(sql, index);
                if (join == null) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, join.rewriteStartIndex());
                    converted.append("CROSS JOIN");
                    lastCopiedIndex = index + "JOIN".length();
                    index = join.joinSourceEndIndex();
                    changed = true;
                }
            } else {
                index++;
            }
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        converted.append(sql, lastCopiedIndex, sql.length());
        return new GenericConversion(converted.toString(), true);
    }

    private JsonTableJoin readJsonTableJoinWithoutCondition(String sql, int joinIndex) {
        int rewriteStart = jsonTableJoinRewriteStart(sql, joinIndex);
        if (rewriteStart < 0) {
            return null;
        }
        int jsonTableStart = skipWhitespace(sql, joinIndex + "JOIN".length());
        FunctionCall jsonTableCall = readFunctionCall(sql, jsonTableStart, "JSON_TABLE");
        if (jsonTableCall == null) {
            return null;
        }
        int sourceEnd = readOptionalTableAliasEnd(sql, jsonTableCall.endIndex());
        int afterSource = skipWhitespace(sql, sourceEnd);
        if (startsKeyword(sql, afterSource, "ON") || startsKeyword(sql, afterSource, "USING")) {
            return null;
        }
        return new JsonTableJoin(rewriteStart, sourceEnd);
    }

    private int jsonTableJoinRewriteStart(String sql, int joinIndex) {
        WordToken word = previousWord(sql, joinIndex);
        if (word == null || !isOnlyWhitespace(sql, word.endIndex(), joinIndex)) {
            return joinIndex;
        }
        String upper = word.text().toUpperCase(Locale.ROOT);
        if ("INNER".equals(upper)) {
            return word.startIndex();
        }
        if (Set.of("LEFT", "RIGHT", "FULL", "CROSS", "NATURAL", "OUTER").contains(upper)) {
            return -1;
        }
        return joinIndex;
    }

    private int readOptionalTableAliasEnd(String sql, int relationEnd) {
        int aliasStart = skipWhitespace(sql, relationEnd);
        if (startsKeyword(sql, aliasStart, "AS")) {
            int afterAs = skipWhitespace(sql, aliasStart + "AS".length());
            IdentifierToken alias = readIdentifierToken(sql, afterAs);
            if (alias != null && !isSqlClauseKeyword(alias.text())) {
                return alias.endIndex();
            }
            return relationEnd;
        }
        IdentifierToken alias = readIdentifierToken(sql, aliasStart);
        if (alias != null && !isSqlClauseKeyword(alias.text())) {
            return alias.endIndex();
        }
        return relationEnd;
    }

    private GenericConversion removeMysqlIndexHints(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        int lastCopiedIndex = 0;
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
            } else {
                KeywordReplacement hint = readMysqlForceIndexHintRemoval(sql, index);
                if (hint == null) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, hint.startIndex());
                    lastCopiedIndex = hint.endIndex();
                    index = hint.endIndex();
                    changed = true;
                }
            }
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        converted.append(sql, lastCopiedIndex, sql.length());
        return new GenericConversion(converted.toString(), true);
    }

    private KeywordReplacement readMysqlForceIndexHintRemoval(String sql, int index) {
        if (!startsKeyword(sql, index, "FORCE")) {
            return null;
        }
        int cursor = skipWhitespace(sql, index + "FORCE".length());
        if (!startsKeyword(sql, cursor, "INDEX")) {
            return null;
        }
        cursor = skipWhitespace(sql, cursor + "INDEX".length());
        if (cursor >= sql.length() || sql.charAt(cursor) != '(') {
            return null;
        }
        int closeParenIndex = findMatchingParen(sql, cursor);
        if (closeParenIndex < 0) {
            return null;
        }
        return new KeywordReplacement(index, skipWhitespace(sql, closeParenIndex + 1));
    }

    private int joinTypeStart(String sql, int joinIndex) {
        WordToken word = previousWord(sql, joinIndex);
        if (word == null || !isOnlyWhitespace(sql, word.endIndex(), joinIndex)) {
            return joinIndex;
        }
        String upper = word.text().toUpperCase(Locale.ROOT);
        return Set.of("INNER", "LEFT", "RIGHT", "FULL", "CROSS").contains(upper) ? word.startIndex() : joinIndex;
    }

    private JoinSource splitJoinSource(String joinSource) {
        int onIndex = findTopLevelKeyword(joinSource, "ON", 0);
        if (onIndex < 0) {
            return new JoinSource(joinSource.strip(), "");
        }
        String source = joinSource.substring(0, onIndex).strip();
        String condition = joinSource.substring(onIndex + "ON".length()).strip();
        return new JoinSource(source, condition);
    }

    private int stripTrailingSemicolon(String sql) {
        int end = sql.length();
        while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
            end--;
        }
        if (end > 0 && sql.charAt(end - 1) == ';') {
            end--;
            while (end > 0 && Character.isWhitespace(sql.charAt(end - 1))) {
                end--;
            }
        }
        return end;
    }

    private GenericConversion convertSingularInsertValueKeyword(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        int lastCopiedIndex = 0;
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
            } else if (startsKeyword(sql, index, "INSERT")) {
                KeywordReplacement keyword = readSingularInsertValueKeyword(sql, index);
                if (keyword == null) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, keyword.startIndex());
                    converted.append("VALUES");
                    lastCopiedIndex = keyword.endIndex();
                    index = keyword.endIndex();
                    changed = true;
                }
            } else {
                index++;
            }
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        converted.append(sql, lastCopiedIndex, sql.length());
        return new GenericConversion(converted.toString(), true);
    }

    private KeywordReplacement readSingularInsertValueKeyword(String sql, int insertIndex) {
        int index = skipWhitespace(sql, insertIndex + "INSERT".length());
        if (startsKeyword(sql, index, "IGNORE")) {
            index = skipWhitespace(sql, index + "IGNORE".length());
        }
        if (!startsKeyword(sql, index, "INTO")) {
            return null;
        }
        index = skipWhitespace(sql, index + "INTO".length());
        int columnOpenIndex = findTopLevelChar(sql, '(', index);
        if (columnOpenIndex < 0) {
            return null;
        }
        int columnCloseIndex = findMatchingParen(sql, columnOpenIndex);
        if (columnCloseIndex < 0) {
            return null;
        }
        int valueIndex = skipWhitespace(sql, columnCloseIndex + 1);
        return startsKeyword(sql, valueIndex, "VALUE")
                ? new KeywordReplacement(valueIndex, valueIndex + "VALUE".length())
                : null;
    }

    private GenericConversion convertInsertIgnore(String sql, List<String> keyColumns) {
        InsertValues insert = readInsertValues(sql, true);
        if (insert == null || normalizedKeyColumns(keyColumns).isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        String converted = mergeSql(insert, List.of(), keyColumns);
        return converted == null ? GenericConversion.unchanged(sql) : new GenericConversion(converted, true);
    }

    private GenericConversion convertOnDuplicateKeyUpdate(String sql, List<String> keyColumns) {
        OnDuplicateKeyInsert insert = readOnDuplicateKeyInsert(sql);
        if (insert == null || normalizedKeyColumns(keyColumns).isEmpty()) {
            return GenericConversion.unchanged(sql);
        }

        List<UpdateAssignment> updateAssignments = readOnDuplicateKeyUpdateAssignments(insert.updateClause());
        if (updateAssignments.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }

        boolean allAssignmentsUseInsertColumns = updateAssignments.stream()
                .allMatch(assignment -> insert.columns().stream()
                        .anyMatch(column -> column.name().key().equals(assignment.column().key())));
        if (!allAssignmentsUseInsertColumns) {
            return GenericConversion.unchanged(sql);
        }

        String converted = mergeSql(insert.toInsertValues(), updateAssignments, keyColumns);
        return converted == null ? GenericConversion.unchanged(sql) : new GenericConversion(converted, true);
    }

    private String mergeSql(InsertValues insert, List<UpdateAssignment> updateAssignments, List<String> keyColumns) {
        List<String> normalizedKeys = normalizedKeyColumns(keyColumns);
        if (normalizedKeys.isEmpty()) {
            return null;
        }
        List<InsertColumn> matchColumns = new ArrayList<>();
        for (String keyColumn : normalizedKeys) {
            InsertColumn matchColumn = insert.columns().stream()
                    .filter(column -> column.name().key().equals(keyColumn))
                    .findFirst()
                    .orElse(null);
            if (matchColumn == null) {
                return null;
            }
            matchColumns.add(matchColumn);
        }
        List<UpdateAssignment> effectiveAssignments = updateAssignments.stream()
                .filter(assignment -> normalizedKeys.stream().noneMatch(key -> key.equals(assignment.column().key())))
                .toList();
        if (effectiveAssignments.stream().anyMatch(assignment -> insert.columns().stream()
                .noneMatch(column -> column.name().key().equals(assignment.column().key())))) {
            return null;
        }

        StringBuilder converted = new StringBuilder(insert.statementEnd() + 128);
        converted.append(insert.prefix());
        converted.append("MERGE INTO ")
                .append(insert.tableName())
                .append(" t\n")
                .append("USING (\n")
                .append("    SELECT ");
        for (int i = 0; i < insert.columns().size(); i++) {
            InsertColumn column = insert.columns().get(i);
            if (i > 0) {
                converted.append(", ");
            }
            converted.append(column.value())
                    .append(" AS ")
                    .append(dmIdentifier(column.name().text()));
        }
        converted.append(" FROM dual\n")
                .append(") s\n");
        converted.append("ON (");
        for (int i = 0; i < matchColumns.size(); i++) {
            InsertColumn matchColumn = matchColumns.get(i);
            if (i > 0) {
                converted.append(" AND ");
            }
            converted.append(qualifiedIdentifier("t", matchColumn.name()))
                    .append(" = ")
                    .append(qualifiedIdentifier("s", matchColumn.name()));
        }
        converted.append(")\n");
        if (!effectiveAssignments.isEmpty()) {
            converted.append("WHEN MATCHED THEN UPDATE SET ");
            for (int i = 0; i < effectiveAssignments.size(); i++) {
                UpdateAssignment assignment = effectiveAssignments.get(i);
                if (i > 0) {
                    converted.append(", ");
                }
                converted.append(qualifiedIdentifier("t", assignment.column()))
                        .append(" = ")
                        .append(qualifiedIdentifier("s", assignment.sourceColumn()));
            }
            converted.append("\n");
        }
        converted.append("WHEN NOT MATCHED THEN INSERT (");
        for (int i = 0; i < insert.columns().size(); i++) {
            if (i > 0) {
                converted.append(", ");
            }
            converted.append(dmIdentifier(insert.columns().get(i).name().text()));
        }
        converted.append(") VALUES (");
        for (int i = 0; i < insert.columns().size(); i++) {
            if (i > 0) {
                converted.append(", ");
            }
            converted.append(qualifiedIdentifier("s", insert.columns().get(i).name()));
        }
        converted.append(")");
        converted.append(insert.suffix());
        return converted.toString();
    }

    private List<String> normalizedKeyColumns(List<String> keyColumns) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String keyColumn : keyColumns) {
            if (keyColumn == null || keyColumn.isBlank()) {
                continue;
            }
            normalized.add(identifierKey(keyColumn));
        }
        return normalized;
    }

    private String qualifiedIdentifier(String qualifier, IdentifierName identifierName) {
        return qualifier + "." + dmIdentifier(identifierName.text());
    }

    private String dmIdentifier(String identifier) {
        return toDamengIdentifier(unquoteIdentifier(identifier));
    }

    private OnDuplicateKeyInsert readOnDuplicateKeyInsert(String sql) {
        InsertValues insert = readInsertValues(sql, false);
        if (insert == null) {
            return null;
        }
        int index = skipWhitespace(sql, insert.valuesCloseIndex() + 1);
        if (!startsKeyword(sql, index, "ON")) {
            return null;
        }
        index = skipWhitespace(sql, index + "ON".length());
        if (!startsKeyword(sql, index, "DUPLICATE")) {
            return null;
        }
        index = skipWhitespace(sql, index + "DUPLICATE".length());
        if (!startsKeyword(sql, index, "KEY")) {
            return null;
        }
        index = skipWhitespace(sql, index + "KEY".length());
        if (!startsKeyword(sql, index, "UPDATE")) {
            return null;
        }
        index = skipWhitespace(sql, index + "UPDATE".length());

        int statementEnd = stripTrailingSemicolon(sql);
        if (index >= statementEnd) {
            return null;
        }
        return new OnDuplicateKeyInsert(insert, sql.substring(index, statementEnd));
    }

    private InsertValues readInsertValues(String sql, boolean requireIgnore) {
        int insertIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, insertIndex, "INSERT")) {
            return null;
        }
        int index = skipWhitespace(sql, insertIndex + "INSERT".length());
        boolean ignore = startsKeyword(sql, index, "IGNORE");
        if (ignore) {
            index = skipWhitespace(sql, index + "IGNORE".length());
        }
        if (ignore != requireIgnore) {
            return null;
        }
        if (!startsKeyword(sql, index, "INTO")) {
            return null;
        }
        index = skipWhitespace(sql, index + "INTO".length());
        int columnOpenIndex = findTopLevelChar(sql, '(', index);
        if (columnOpenIndex < 0) {
            return null;
        }
        String tableName = sql.substring(index, columnOpenIndex).trim();
        if (tableName.isBlank() || containsMyBatisPlaceholder(tableName) || containsWhitespaceOutsideQuotedText(tableName)) {
            return null;
        }
        int columnCloseIndex = findMatchingParen(sql, columnOpenIndex);
        if (columnCloseIndex < 0) {
            return null;
        }

        List<IdentifierName> columnNames = readInsertColumns(sql.substring(columnOpenIndex + 1, columnCloseIndex));
        if (columnNames.isEmpty()) {
            return null;
        }

        index = skipWhitespace(sql, columnCloseIndex + 1);
        if (!startsKeyword(sql, index, "VALUES")) {
            return null;
        }
        index = skipWhitespace(sql, index + "VALUES".length());
        if (index >= sql.length() || sql.charAt(index) != '(') {
            return null;
        }
        int valuesCloseIndex = findMatchingParen(sql, index);
        if (valuesCloseIndex < 0) {
            return null;
        }
        List<TopLevelArgument> values = splitTopLevelArguments(sql.substring(index + 1, valuesCloseIndex));
        if (values.size() != columnNames.size()) {
            return null;
        }

        int statementEnd = stripTrailingSemicolon(sql);
        if (requireIgnore && !sql.substring(valuesCloseIndex + 1, statementEnd).isBlank()) {
            return null;
        }

        List<InsertColumn> columns = new ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            String value = values.get(i).text().trim();
            if (value.isBlank()) {
                return null;
            }
            columns.add(new InsertColumn(columnNames.get(i), value));
        }
        return new InsertValues(
                insertIndex,
                valuesCloseIndex,
                statementEnd,
                sql.substring(0, insertIndex),
                sql.substring(statementEnd),
                tableName,
                columns
        );
    }

    private List<IdentifierName> readInsertColumns(String columnList) {
        List<IdentifierName> columns = new ArrayList<>();
        for (TopLevelArgument column : splitTopLevelArguments(columnList)) {
            IdentifierName columnName = readIdentifierName(column.text(), false);
            if (columnName == null || columns.stream().anyMatch(existing -> existing.key().equals(columnName.key()))) {
                return List.of();
            }
            columns.add(columnName);
        }
        return columns;
    }

    private List<UpdateAssignment> readOnDuplicateKeyUpdateAssignments(String updateClause) {
        List<UpdateAssignment> assignments = new ArrayList<>();
        for (TopLevelArgument assignment : splitTopLevelArguments(updateClause)) {
            UpdateAssignment parsed = readOnDuplicateKeyUpdateAssignment(assignment.text());
            if (parsed == null
                    || assignments.stream().anyMatch(existing -> existing.column().key().equals(parsed.column().key()))) {
                return List.of();
            }
            assignments.add(parsed);
        }
        return assignments;
    }

    private UpdateAssignment readOnDuplicateKeyUpdateAssignment(String assignment) {
        int equalsIndex = findTopLevelChar(assignment, '=', 0);
        if (equalsIndex < 0) {
            return null;
        }
        IdentifierName targetColumn = readIdentifierName(assignment.substring(0, equalsIndex), true);
        if (targetColumn == null) {
            return null;
        }

        FunctionCall valuesCall = readOnlyFunctionCall(assignment.substring(equalsIndex + 1).trim(), "VALUES");
        if (valuesCall == null) {
            return null;
        }
        List<TopLevelArgument> valuesArguments = splitTopLevelArguments(valuesCall.body());
        if (valuesArguments.size() != 1) {
            return null;
        }
        IdentifierName sourceColumn = readIdentifierName(valuesArguments.get(0).text(), false);
        if (sourceColumn == null || !sourceColumn.key().equals(targetColumn.key())) {
            return null;
        }
        return new UpdateAssignment(targetColumn, sourceColumn);
    }

    private IdentifierName readIdentifierName(String expression, boolean allowQualifier) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int index = 0;
        IdentifierToken token = readIdentifierToken(trimmed, index);
        if (token == null) {
            return null;
        }
        IdentifierToken lastToken = token;
        index = skipWhitespace(trimmed, token.endIndex());
        while (index < trimmed.length() && trimmed.charAt(index) == '.') {
            if (!allowQualifier) {
                return null;
            }
            index = skipWhitespace(trimmed, index + 1);
            token = readIdentifierToken(trimmed, index);
            if (token == null) {
                return null;
            }
            lastToken = token;
            index = skipWhitespace(trimmed, token.endIndex());
        }
        if (index != trimmed.length()) {
            return null;
        }
        return new IdentifierName(lastToken.text(), identifierKey(lastToken.text()));
    }

    private String identifierKey(String identifier) {
        String trimmed = identifier.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"' && trimmed.charAt(trimmed.length() - 1) == '"') {
            trimmed = trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private boolean containsWhitespaceOutsideQuotedText(String value) {
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '"') {
                index = skipDoubleQuotedText(value, index);
            } else if (Character.isWhitespace(current)) {
                return true;
            } else {
                index++;
            }
        }
        return false;
    }

    private int findTopLevelChar(String sql, char target, int start) {
        int depth = 0;
        int index = Math.max(0, start);
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
                if (target == '(' && depth == 0) {
                    return index;
                }
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (current == target && depth == 0) {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private GenericConversion quoteDamengKeywordIdentifiers(String sql) {
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
                String quoted = quoteDamengKeywordIdentifierIfNeeded(sql, index, identifier);
                converted.append(quoted);
                changed = changed || !quoted.equals(identifier);
                index = end;
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private String quoteDamengKeywordIdentifierIfNeeded(String sql, int startIndex, String identifier) {
        String upper = identifier.toUpperCase(Locale.ROOT);
        if ("DIMENSION".equals(upper)) {
            return "\"DIMENSION\"";
        }
        if ("DESC".equals(upper) && previousNonWhitespace(sql, startIndex) == '.') {
            return "\"DESC\"";
        }
        if (previousNonWhitespace(sql, startIndex) == '.' && isDamengKeywordRequiringQuotes(identifier)) {
            return quoteDamengIdentifier(identifier);
        }
        return identifier;
    }

    private GenericConversion convertGroupConcat(String sql) {
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
            } else if (startsFunction(sql, index, "GROUP_CONCAT")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "GROUP_CONCAT");
                String replacement = functionCall == null ? null : rewriteGroupConcat(functionCall);
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
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private String rewriteGroupConcat(FunctionCall groupConcatCall) {
        String body = groupConcatCall.body();
        int orderIndex = findTopLevelKeyword(body, "ORDER", 0);
        int separatorIndex = findTopLevelKeyword(body, "SEPARATOR", 0);
        if (orderIndex >= 0 && separatorIndex >= 0 && orderIndex > separatorIndex) {
            return null;
        }

        String orderBy = "";
        if (orderIndex >= 0) {
            int byIndex = skipWhitespace(body, orderIndex + "ORDER".length());
            if (!startsKeyword(body, byIndex, "BY")) {
                return null;
            }
            int orderEnd = separatorIndex >= 0 ? separatorIndex : body.length();
            orderBy = body.substring(byIndex + "BY".length(), orderEnd).trim();
            if (orderBy.isBlank()) {
                return null;
            }
        }

        int expressionEnd = body.length();
        if (orderIndex >= 0) {
            expressionEnd = Math.min(expressionEnd, orderIndex);
        }
        if (separatorIndex >= 0) {
            expressionEnd = Math.min(expressionEnd, separatorIndex);
        }
        String expression = body.substring(0, expressionEnd).trim();
        boolean distinct = false;
        if (startsKeyword(expression, leadingWhitespaceLength(expression), "DISTINCT")) {
            distinct = true;
            expression = expression.substring(leadingWhitespaceLength(expression) + "DISTINCT".length()).trim();
        }
        if (expression.isBlank()) {
            return null;
        }
        if (splitTopLevelArguments(expression).size() != 1) {
            return null;
        }

        String separator = "','";
        if (separatorIndex >= 0) {
            separator = normalizedStringLiteral(body.substring(separatorIndex + "SEPARATOR".length()));
            if (separator == null) {
                return null;
            }
        }
        if (orderBy.isBlank()) {
            orderBy = expression;
        }
        return "LISTAGG(" + (distinct ? "DISTINCT " : "") + expression + ", " + separator + ") WITHIN GROUP (ORDER BY " + orderBy + ")";
    }

    private UpdateSetTableOrderConversion convertUpdateSetTableOrder(String sql) {
        Matcher matcher = UPDATE_SET_TABLE_ORDER_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return new UpdateSetTableOrderConversion(sql, false);
        }
        String converted = matcher.group(1)
                + "update "
                + matcher.group(2)
                + " set"
                + matcher.group(3)
                + matcher.group(4);
        return new UpdateSetTableOrderConversion(converted, true);
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

    private boolean containsKeywordOutsideIgnoredText(String sql, String keyword) {
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
            } else if (startsKeyword(sql, index, keyword)) {
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

    private BacktickIdentifierConversion convertBacktickIdentifiers(String sql) {
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
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                if (!identifier.closed()) {
                    converted.append(sql, index, sql.length());
                    index = sql.length();
                } else {
                    converted.append(toDamengIdentifier(identifier.value()));
                    index = identifier.nextIndex();
                    changed = true;
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new BacktickIdentifierConversion(changed ? converted.toString() : sql, changed);
    }

    private BacktickIdentifier readBacktickIdentifier(String sql, int start) {
        StringBuilder value = new StringBuilder();
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '`') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '`') {
                    value.append(current);
                    index += 2;
                } else {
                    return new BacktickIdentifier(value.toString(), index + 1, true);
                }
            } else {
                value.append(current);
                index++;
            }
        }
        return new BacktickIdentifier("", start, false);
    }

    private String toDamengIdentifier(String identifier) {
        if (containsMyBatisPlaceholder(identifier)) {
            return identifier;
        }
        if (isSimpleIdentifier(identifier) && !isDamengKeywordRequiringQuotes(identifier)) {
            return identifier;
        }
        return quoteDamengIdentifier(identifier);
    }

    private boolean containsMyBatisPlaceholder(String value) {
        return value.contains("${") || value.contains("#{");
    }

    private boolean isSimpleIdentifier(String value) {
        if (value.isBlank() || !(Character.isLetter(value.charAt(0)) || value.charAt(0) == '_')) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!(Character.isLetterOrDigit(current) || current == '_')) {
                return false;
            }
        }
        return true;
    }

    private boolean isDamengKeywordRequiringQuotes(String identifier) {
        return DAMENG_KEYWORDS_REQUIRING_QUOTES.contains(identifier.toUpperCase(Locale.ROOT));
    }

    private String quoteDamengIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
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

    private int skipWhitespace(String sql, int start) {
        int index = start;
        while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
            index++;
        }
        return index;
    }

    private int skipWhitespaceBackward(String sql, int endExclusive) {
        int index = Math.min(endExclusive, sql.length());
        while (index > 0 && Character.isWhitespace(sql.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private boolean startsKeyword(String sql, int index, String keyword) {
        if (index < 0 || index + keyword.length() > sql.length()) {
            return false;
        }
        if (index > 0 && isIdentifierPart(sql.charAt(index - 1))) {
            return false;
        }
        if (!sql.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        int afterKeyword = index + keyword.length();
        return afterKeyword >= sql.length() || !isIdentifierPart(sql.charAt(afterKeyword));
    }

    private int findTopLevelKeyword(String sql, String keyword, int start) {
        int depth = 0;
        int index = Math.max(0, start);
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
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (depth == 0 && startsKeyword(sql, index, keyword)) {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private IdentifierToken readIdentifierToken(String sql, int start) {
        if (start >= sql.length()) {
            return null;
        }
        if (sql.charAt(start) == '"') {
            int end = skipDoubleQuotedText(sql, start);
            return end > start + 1 ? new IdentifierToken(sql.substring(start, end), end) : null;
        }
        if (!isIdentifierStart(sql.charAt(start))) {
            return null;
        }
        int end = start + 1;
        while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
            end++;
        }
        return new IdentifierToken(sql.substring(start, end), end);
    }

    private WordToken previousWord(String sql, int beforeIndex) {
        int end = skipWhitespaceBackward(sql, beforeIndex);
        if (end <= 0 || !isIdentifierPart(sql.charAt(end - 1))) {
            return null;
        }
        int start = end - 1;
        while (start > 0 && isIdentifierPart(sql.charAt(start - 1))) {
            start--;
        }
        return new WordToken(start, end, sql.substring(start, end));
    }

    private boolean isOnlyWhitespace(String sql, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(sql.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private int findMatchingOpenParenBackward(String sql, int closeParenIndex) {
        int depth = 0;
        int index = closeParenIndex;
        while (index >= 0) {
            char current = sql.charAt(index);
            if (current == ')') {
                depth++;
                index--;
            } else if (current == '(') {
                depth--;
                if (depth == 0) {
                    return index;
                }
                index--;
            } else {
                index--;
            }
        }
        return -1;
    }

    private int readFunctionNameStartBeforeParen(String sql, int openParenIndex) {
        if (openParenIndex <= 0 || Character.isWhitespace(sql.charAt(openParenIndex - 1))) {
            return openParenIndex;
        }
        int start = openParenIndex;
        while (start > 0 && isIdentifierPart(sql.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    private int readExpressionNameStartBeforeParen(String sql, int openParenIndex) {
        int end = skipWhitespaceBackward(sql, openParenIndex);
        if (end <= 0 || !isIdentifierPart(sql.charAt(end - 1))) {
            return openParenIndex;
        }
        int start = end - 1;
        while (start > 0 && isIdentifierPart(sql.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    private int readMyBatisPlaceholderStartBackward(String sql, int endExclusive) {
        int start = sql.lastIndexOf("#{", endExclusive - 1);
        int dollarStart = sql.lastIndexOf("${", endExclusive - 1);
        start = Math.max(start, dollarStart);
        if (start < 0 || sql.indexOf('}', start + 2) != endExclusive - 1) {
            return -1;
        }
        return start;
    }

    private boolean isOperandIdentifierChar(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_'
                || value == '$'
                || value == '.'
                || value == '"';
    }

    private char previousNonWhitespace(String sql, int beforeIndex) {
        int index = beforeIndex - 1;
        while (index >= 0 && Character.isWhitespace(sql.charAt(index))) {
            index--;
        }
        return index >= 0 ? sql.charAt(index) : '\0';
    }

    private boolean isIdentifierStart(char value) {
        return Character.isLetter(value) || value == '_';
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

    private record BacktickIdentifierConversion(String convertedSql, boolean changed) {
    }

    private record UpdateSetTableOrderConversion(String convertedSql, boolean changed) {
    }

    private record GenericConversion(String convertedSql, boolean changed) {
        private static GenericConversion unchanged(String sql) {
            return new GenericConversion(sql, false);
        }
    }

    private record IdentifierToken(String text, int endIndex) {
    }

    private record RegexpExpression(int startIndex, int endIndex, String replacement) {
    }

    private record JsonTableJoin(int rewriteStartIndex, int joinSourceEndIndex) {
    }

    private record KeywordReplacement(int startIndex, int endIndex) {
    }

    private record WordToken(int startIndex, int endIndex, String text) {
    }

    private record Operand(int startIndex, int endIndex, String text) {
    }

    private record AliasAsRemoval(int asIndex, int aliasStartIndex, int aliasEndIndex) {
    }

    private record JoinSource(String sourceSql, String conditionSql) {
    }

    private record IdentifierName(String text, String key) {
    }

    private record InsertColumn(IdentifierName name, String value) {
    }

    private record UpdateAssignment(IdentifierName column, IdentifierName sourceColumn) {
    }

    private record InsertValues(
            int insertIndex,
            int valuesCloseIndex,
            int statementEnd,
            String prefix,
            String suffix,
            String tableName,
            List<InsertColumn> columns
    ) {
        private InsertValues {
            columns = List.copyOf(columns == null ? List.of() : columns);
        }
    }

    private record OnDuplicateKeyInsert(
            InsertValues insertValues,
            String updateClause
    ) {
        private List<InsertColumn> columns() {
            return insertValues.columns();
        }

        private InsertValues toInsertValues() {
            return insertValues;
        }
    }

    private record BacktickIdentifier(String value, int nextIndex, boolean closed) {
    }

    private record AesBase64Conversion(String convertedSql, boolean changed) {
    }

    private record FunctionCall(int startIndex, int openParenIndex, int closeParenIndex, int endIndex, String body) {
    }

    private record DateExpression(int startIndex, int endIndex, String text) {
    }

    private record IntervalExpression(String amount, String unit, int endIndex) {
    }

    private record DateIntervalAddition(DateExpression leftExpression, IntervalExpression intervalExpression) {
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
