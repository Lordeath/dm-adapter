package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MySqlToDmSqlConverter implements SqlConverter {
    public static final String MYSQL_AES_BASE64_TO_DM_AES128_ECB_RULE = "MYSQL_AES_BASE64_TO_DM_AES128_ECB";
    public static final String MYSQL_BACKTICK_IDENTIFIER_RULE = "MYSQL_BACKTICK_IDENTIFIER_TO_DM";
    public static final String UPDATE_SET_TABLE_ORDER_RULE = "UPDATE_SET_TABLE_ORDER_TO_STANDARD_UPDATE";
    public static final String MYSQL_DATE_SUB_INTERVAL_RULE = "MYSQL_DATE_SUB_INTERVAL_TO_DATEADD";
    public static final String MYSQL_DATE_SUB_NOW_DAY_RULE = MYSQL_DATE_SUB_INTERVAL_RULE;
    public static final String MYSQL_REGEXP_OPERATOR_RULE = "MYSQL_REGEXP_OPERATOR_TO_REGEXP_LIKE";
    public static final String MYSQL_CAST_UNSIGNED_RULE = "MYSQL_CAST_UNSIGNED_TO_BIGINT";
    public static final String MYSQL_CAST_SIGNED_RULE = "MYSQL_CAST_SIGNED_TO_BIGINT";
    public static final String MYSQL_CONVERT_UNSIGNED_RULE = "MYSQL_CONVERT_UNSIGNED_TO_BIGINT";
    public static final String MYSQL_DATE_ADD_INTERVAL_RULE = "MYSQL_DATE_ADD_INTERVAL_TO_DATEADD";
    public static final String MYSQL_SUBDATE_RULE = "MYSQL_SUBDATE_TO_DATEADD";
    public static final String MYSQL_LOCATE_NUMERIC_NEEDLE_RULE = "MYSQL_LOCATE_NUMERIC_NEEDLE_CAST";
    public static final String MYSQL_MAKEDATE_RULE = "MYSQL_MAKEDATE_TO_DATEADD";
    public static final String MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE =
            "MYSQL_INFORMATION_SCHEMA_COLUMNS_TO_ALL_TAB_COLUMNS";
    public static final String MYSQL_INFORMATION_SCHEMA_TABLES_RULE =
            "MYSQL_INFORMATION_SCHEMA_TABLES_TO_ALL_TABLES";
    public static final String MYSQL_INSERT_IGNORE_TO_DM_MERGE_RULE = "MYSQL_INSERT_IGNORE_TO_DM_MERGE";
    public static final String MYSQL_WITH_RECURSIVE_ALIAS_RULE = "MYSQL_WITH_RECURSIVE_COLUMN_ALIAS";
    public static final String MYSQL_UPDATE_JOIN_RULE = "MYSQL_UPDATE_JOIN_TO_DM_UPDATE_FROM";
    public static final String MYSQL_TABLE_ALIAS_AS_RULE = "MYSQL_TABLE_ALIAS_AS_TO_DM";
    public static final String MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE = "MYSQL_GROUP_CONCAT_TO_DM_LISTAGG";
    public static final String MYSQL_CONCAT_TO_DM_OPERATOR_RULE = "MYSQL_CONCAT_TO_DM_OPERATOR";
    public static final String MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT_RULE =
            "MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT";
    public static final String MYSQL_SUBSTRING_INDEX_TO_REGEXP_SUBSTR_RULE =
            "MYSQL_SUBSTRING_INDEX_TO_REGEXP_SUBSTR";
    public static final String MYSQL_HAVING_AGGREGATE_ALIAS_RULE = "MYSQL_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION";
    public static final String MYSQL_NOT_FIND_IN_SET_RULE = "MYSQL_NOT_FIND_IN_SET_TO_EQUALS_ZERO";
    public static final String MYSQL_STR_TO_DATE_YEARMONTH_RULE = "MYSQL_STR_TO_DATE_YEARMONTH_TO_TO_DATE";
    public static final String MYSQL_PERIOD_DIFF_YEARMONTH_RULE = "MYSQL_PERIOD_DIFF_YEARMONTH_TO_DATEDIFF";
    public static final String MYSQL_DATEDIFF_2ARG_RULE = "MYSQL_DATEDIFF_2ARG_TO_DM_DATEDIFF";
    public static final String MYSQL_COUNT_CONDITION_OR_NULL_RULE = "MYSQL_COUNT_CONDITION_OR_NULL_TO_CASE";
    public static final String MYSQL_COUNT_DISTINCT_IF_TO_CASE_RULE = "MYSQL_COUNT_DISTINCT_IF_TO_CASE";
    public static final String MYSQL_IF_TO_CASE_RULE = "MYSQL_IF_TO_CASE";
    public static final String MYSQL_NOT_ISNULL_RULE = "MYSQL_NOT_ISNULL_TO_CASE";
    public static final String MYSQL_BOOLEAN_OPERATOR_RULE = "MYSQL_BOOLEAN_OPERATOR_TO_WORD_OPERATOR";
    public static final String MYSQL_BARE_BOOLEAN_PREDICATE_RULE = "MYSQL_BARE_BOOLEAN_PREDICATE_TO_EQUALS_ONE";
    public static final String MYSQL_BOOLEAN_LITERAL_COMPARISON_RULE =
            "MYSQL_BOOLEAN_LITERAL_COMPARISON_TO_NUMERIC";
    public static final String MYSQL_JSON_TABLE_JOIN_TO_DM_CROSS_JOIN_RULE =
            "MYSQL_JSON_TABLE_JOIN_TO_DM_CROSS_JOIN";
    public static final String MYSQL_IMPLICIT_CROSS_JOIN_RULE = "MYSQL_IMPLICIT_CROSS_JOIN_TO_DM";
    public static final String MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE = "MYSQL_TEMPORARY_TABLE_AS_SELECT_TO_DM";
    public static final String MYSQL_TEMPORARY_TABLE_AS_SELECT_FOREACH_LITERAL_RULE =
            "MYSQL_TEMPORARY_TABLE_AS_SELECT_FOREACH_LITERAL";
    public static final String MYSQL_DELETE_ALIAS_STAR_RULE = "MYSQL_DELETE_ALIAS_STAR_TO_DM";
    public static final String DAMENG_KEYWORD_TABLE_ALIAS_RULE = "DAMENG_KEYWORD_TABLE_ALIAS_QUOTE";
    public static final String MYSQL_SINGLE_QUOTED_ALIAS_RULE = "MYSQL_SINGLE_QUOTED_ALIAS_TO_DM_IDENTIFIER";
    public static final String MYSQL_INSERT_VALUE_TO_VALUES_RULE = "MYSQL_INSERT_VALUE_TO_VALUES";
    public static final String MYSQL_INDEX_HINT_REMOVAL_RULE = "MYSQL_INDEX_HINT_REMOVED";
    public static final String MYSQL_CONVERT_DECIMAL_RULE = "MYSQL_CONVERT_DECIMAL_TO_CAST";
    public static final String MYSQL_CONVERT_CHAR_RULE = "MYSQL_CONVERT_CHAR_TO_CAST";
    public static final String MYSQL_CONVERT_GBK_ORDER_RULE = "MYSQL_CONVERT_GBK_ORDER_TO_NLSSORT";
    public static final String MYSQL_SELECT_MODIFIER_REMOVAL_RULE = "MYSQL_SELECT_MODIFIER_REMOVED";
    public static final String MYSQL_COLLATE_CLAUSE_REMOVAL_RULE = "MYSQL_COLLATE_CLAUSE_REMOVED";
    public static final String MYSQL_CHARACTER_SET_CLAUSE_REMOVAL_RULE = "MYSQL_CHARACTER_SET_CLAUSE_REMOVED";
    public static final String MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE = "MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY";
    public static final String MYSQL_ALTER_AUTO_INCREMENT_RESET_RULE = "MYSQL_ALTER_AUTO_INCREMENT_RESET_TO_DM";
    public static final String MYSQL_CREATE_TABLE_OPTION_REMOVAL_RULE = "MYSQL_CREATE_TABLE_OPTIONS_REMOVED";
    public static final String MYSQL_USING_BTREE_REMOVAL_RULE = "MYSQL_USING_BTREE_REMOVED";
    public static final String MYSQL_CREATE_TABLE_KEY_REMOVAL_RULE = "MYSQL_CREATE_TABLE_KEY_REMOVED";
    public static final String MYSQL_NUMERIC_TYPE_ATTRIBUTE_RULE = "MYSQL_NUMERIC_TYPE_ATTRIBUTE_TO_DM";
    public static final String MYSQL_DECIMAL_PRECISION_CAP_RULE = "MYSQL_DECIMAL_PRECISION_CAP_TO_DM";
    public static final String MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVAL_RULE =
            "MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVED";
    public static final String MYSQL_ON_UPDATE_TIMESTAMP_REMOVAL_RULE = "MYSQL_ON_UPDATE_TIMESTAMP_REMOVED";
    public static final String MYSQL_SESSION_VARIABLE_NOOP_RULE = "MYSQL_SESSION_VARIABLE_TO_NOOP";
    public static final String MYSQL_TRUNCATE_TABLE_RULE = "MYSQL_TRUNCATE_TABLE_TO_DM";
    public static final String DUPLICATE_WHERE_KEYWORD_RULE = "DUPLICATE_WHERE_KEYWORD_REMOVED";
    public static final String DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE = "DAMENG_KEYWORD_IDENTIFIER_QUOTE";
    public static final String MYSQL_UPDATE_ORDER_LIMIT_ONE_RULE = "MYSQL_UPDATE_ORDER_LIMIT_ONE_TO_ROWID";
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
    private static final Pattern CAST_SIGNED_BODY_PATTERN = Pattern.compile(
            "(?is)^\\s*(.+?)\\s+AS\\s+SIGNED(?:\\s+INTEGER)?\\s*$"
    );
    private static final Pattern DECIMAL_TARGET_TYPE_PATTERN = Pattern.compile(
            "(?is)^\\s*DECIMAL\\s*\\(\\s*\\d+\\s*(?:,\\s*\\d+)?\\s*\\)\\s*$"
    );
    private static final Pattern CHAR_TARGET_TYPE_PATTERN = Pattern.compile(
            "(?is)^\\s*CHAR(?:\\s*\\(\\s*\\d+\\s*\\))?\\s*$"
    );
    private static final Pattern MYSQL_INTERVAL_PATTERN = Pattern.compile(
            "(?is)^\\s*INTERVAL\\s+(.+?)\\s+(YEAR|MONTH|WEEK|DAY|HOUR|MINUTE|SECOND)\\s*$"
    );
    private static final Pattern MYSQL_ALTER_TABLE_AUTO_INCREMENT_RESET = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(.+?)\\s+AUTO_INCREMENT\\s*=\\s*[-+]?\\d+\\s*;?\\s*$"
    );
    private static final Pattern SIMPLE_INTERVAL_AMOUNT_PATTERN = Pattern.compile(
            "(?is)^[+-]?(?:\\d+|#\\{[^}]+}|\\$\\{[^}]+})$"
    );
    private static final List<String> MYSQL_INTERVAL_UNITS =
            List.of("YEAR", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE", "SECOND");
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
            "MAKEDATE",
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
            "CLUSTER",
            "CONNECT",
            "CREATE",
            "DATE",
            "DELETE",
            "DESC",
            "DISTINCT",
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
            "LIST",
            "NOT",
            "NULL",
            "ON",
            "OR",
            "ORDER",
            "OUTER",
            "PERCENT",
            "PRIOR",
            "REVERSE",
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
    private static final Pattern INFORMATION_SCHEMA_COLUMNS_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+column_name\\s+from\\s+information_schema\\s*\\.\\s*columns\\s+where\\s+"
                    + "(?<where>.+?)(?:\\s+order\\s+by\\s+ordinal_position\\s*(?<direction>asc|desc)?\\s*)?;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_COLUMNS_TABLE_LIST_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+table_name\\s+from\\s+information_schema\\s*\\.\\s*columns\\s+where\\s+"
                    + "table_name\\s+like\\s+(?<tableLike>.+?)\\s+and\\s+"
                    + "table_schema\\s*=\\s*\\(\\s*select\\s+database\\s*\\(\\s*\\)\\s*\\)\\s+and\\s+"
                    + "column_name\\s+not\\s+in\\s+(?<notIn>.+?)\\s+group\\s+by\\s+table_name\\s*;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_COLUMNS_TABLE_LIST_PREFIX_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+table_name\\s+from\\s+information_schema\\s*\\.\\s*columns\\s+where\\s+"
                    + "table_name\\s+like\\s+(?<tableLike>.+?)\\s+and\\s+"
                    + "table_schema\\s*=\\s*\\(\\s*select\\s+database\\s*\\(\\s*\\)\\s*\\)\\s+and\\s+"
                    + "column_name\\s+not\\s+in\\s*;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_TABLES_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+count\\s*\\(\\s*(?:\\*|1)\\s*\\)"
                    + "(?:\\s+(?:as\\s+)?(?<alias>[A-Za-z_][A-Za-z0-9_]*|\"[^\"]+\"))?"
                    + "\\s+from\\s+information_schema\\s*\\.\\s*tables\\s+where\\s+(?<where>.+?)\\s*;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_TABLES_DETAIL_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+table_name\\s+as\\s+(?<tableAlias>" + DM_IDENTIFIER + ")\\s*,\\s*"
                    + "create_time\\s+as\\s+(?<createAlias>" + DM_IDENTIFIER + ")\\s*,\\s*"
                    + "table_schema\\s+as\\s+(?<schemaAlias>" + DM_IDENTIFIER + ")\\s+"
                    + "from\\s+information_schema\\s*\\.\\s*(?:tables|\"tables\")\\s+where\\s+"
                    + "table_schema\\s*=\\s*\\(\\s*select\\s+database\\s*\\(\\s*\\)\\s*\\)\\s+and\\s+"
                    + "table_name\\s+like\\s+(?<tableLike>.+?)\\s*;?\\s*$"
    );
    private static final Pattern METADATA_TABLE_NAME_CONDITION = Pattern.compile(
            "(?is)\\btable_name\\s*=\\s*(?<value>\\?|#\\{[^}]+}|\\$\\{[^}]+}|'(?:''|[^'])*')"
    );
    private static final Pattern METADATA_TABLE_SCHEMA_CONDITION = Pattern.compile(
            "(?is)\\btable_schema\\s*=\\s*(?<value>\\?|#\\{[^}]+}|\\$\\{[^}]+}|'(?:''|[^'])*')"
    );
    private static final Pattern UPDATE_SET_QUALIFIED_ASSIGNMENT = Pattern.compile(
            "(?is)^\\s*(?<alias>\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*\\.\\s*"
                    + "(?:\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*="
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

        GenericConversion alterAutoIncrementResetConversion = convertMysqlAlterTableAutoIncrementReset(converted);
        if (alterAutoIncrementResetConversion.changed()) {
            converted = alterAutoIncrementResetConversion.convertedSql();
            rules.add(MYSQL_ALTER_AUTO_INCREMENT_RESET_RULE);
        }

        GenericConversion createTableOptionConversion = removeMysqlCreateTableOptions(converted);
        if (createTableOptionConversion.changed()) {
            converted = createTableOptionConversion.convertedSql();
            rules.add(MYSQL_CREATE_TABLE_OPTION_REMOVAL_RULE);
        }

        GenericConversion collateClauseConversion = removeMysqlCollateClauses(converted);
        if (collateClauseConversion.changed()) {
            converted = collateClauseConversion.convertedSql();
            rules.add(MYSQL_COLLATE_CLAUSE_REMOVAL_RULE);
        }

        GenericConversion characterSetClauseConversion = removeMysqlCharacterSetClauses(converted);
        if (characterSetClauseConversion.changed()) {
            converted = characterSetClauseConversion.convertedSql();
            rules.add(MYSQL_CHARACTER_SET_CLAUSE_REMOVAL_RULE);
        }

        GenericConversion autoIncrementConversion = convertMysqlAutoIncrement(converted);
        if (autoIncrementConversion.changed()) {
            converted = autoIncrementConversion.convertedSql();
            rules.add(MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE);
        }

        GenericConversion numericTypeAttributeConversion = convertMysqlNumericTypeAttributes(converted);
        if (numericTypeAttributeConversion.changed()) {
            converted = numericTypeAttributeConversion.convertedSql();
            rules.add(MYSQL_NUMERIC_TYPE_ATTRIBUTE_RULE);
        }

        GenericConversion decimalPrecisionConversion = capMysqlDecimalPrecision(converted);
        if (decimalPrecisionConversion.changed()) {
            converted = decimalPrecisionConversion.convertedSql();
            rules.add(MYSQL_DECIMAL_PRECISION_CAP_RULE);
        }

        GenericConversion columnCommentConversion = removeMysqlCreateTableColumnComments(converted);
        if (columnCommentConversion.changed()) {
            converted = columnCommentConversion.convertedSql();
            rules.add(MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVAL_RULE);
        }

        GenericConversion usingBtreeConversion = removeMysqlUsingBtreeClauses(converted);
        if (usingBtreeConversion.changed()) {
            converted = usingBtreeConversion.convertedSql();
            rules.add(MYSQL_USING_BTREE_REMOVAL_RULE);
        }

        GenericConversion createTableKeyConversion = removeMysqlCreateTableKeyDefinitions(converted);
        if (createTableKeyConversion.changed()) {
            converted = createTableKeyConversion.convertedSql();
            rules.add(MYSQL_CREATE_TABLE_KEY_REMOVAL_RULE);
        }

        GenericConversion onUpdateTimestampConversion = removeMysqlOnUpdateCurrentTimestamp(converted);
        if (onUpdateTimestampConversion.changed()) {
            converted = onUpdateTimestampConversion.convertedSql();
            rules.add(MYSQL_ON_UPDATE_TIMESTAMP_REMOVAL_RULE);
        }

        GenericConversion sessionVariableConversion = convertMysqlSessionVariableSetToNoop(converted);
        if (sessionVariableConversion.changed()) {
            converted = sessionVariableConversion.convertedSql();
            rules.add(MYSQL_SESSION_VARIABLE_NOOP_RULE);
        }

        GenericConversion truncateTableConversion = convertMysqlTruncateTableStatement(converted);
        if (truncateTableConversion.changed()) {
            converted = truncateTableConversion.convertedSql();
            rules.add(MYSQL_TRUNCATE_TABLE_RULE);
        }

        GenericConversion duplicateWhereConversion = removeDuplicateWhereKeyword(converted);
        if (duplicateWhereConversion.changed()) {
            converted = duplicateWhereConversion.convertedSql();
            rules.add(DUPLICATE_WHERE_KEYWORD_RULE);
        }

        GenericConversion dateSubConversion = convertDateSubInterval(converted);
        if (dateSubConversion.changed()) {
            converted = dateSubConversion.convertedSql();
            rules.add(MYSQL_DATE_SUB_INTERVAL_RULE);
        }

        GenericConversion subDateConversion = convertSubDateFunctions(converted);
        if (subDateConversion.changed()) {
            converted = subDateConversion.convertedSql();
            rules.add(MYSQL_SUBDATE_RULE);
        }

        GenericConversion dateAddIntervalConversion = convertDateAddInterval(converted);
        if (dateAddIntervalConversion.changed()) {
            converted = dateAddIntervalConversion.convertedSql();
            rules.add(MYSQL_DATE_ADD_INTERVAL_RULE);
        }

        GenericConversion makeDateConversion = convertMakeDateFunctions(converted);
        if (makeDateConversion.changed()) {
            converted = makeDateConversion.convertedSql();
            rules.add(MYSQL_MAKEDATE_RULE);
        }

        GenericConversion strToDateYearMonthConversion = convertStrToDateYearMonth(converted);
        if (strToDateYearMonthConversion.changed()) {
            converted = strToDateYearMonthConversion.convertedSql();
            rules.add(MYSQL_STR_TO_DATE_YEARMONTH_RULE);
        }

        GenericConversion periodDiffYearMonthConversion = convertPeriodDiffYearMonth(converted);
        if (periodDiffYearMonthConversion.changed()) {
            converted = periodDiffYearMonthConversion.convertedSql();
            rules.add(MYSQL_PERIOD_DIFF_YEARMONTH_RULE);
        }

        GenericConversion dateDiff2ArgConversion = convertDateDiff2Arg(converted);
        if (dateDiff2ArgConversion.changed()) {
            converted = dateDiff2ArgConversion.convertedSql();
            rules.add(MYSQL_DATEDIFF_2ARG_RULE);
        }

        GenericConversion unsignedCastConversion = convertUnsignedCasts(converted);
        if (unsignedCastConversion.changed()) {
            converted = unsignedCastConversion.convertedSql();
            rules.add(MYSQL_CAST_UNSIGNED_RULE);
        }

        GenericConversion signedCastConversion = convertSignedCasts(converted);
        if (signedCastConversion.changed()) {
            converted = signedCastConversion.convertedSql();
            rules.add(MYSQL_CAST_SIGNED_RULE);
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

        GenericConversion charConvertConversion = convertCharConvertFunctions(converted);
        if (charConvertConversion.changed()) {
            converted = charConvertConversion.convertedSql();
            rules.add(MYSQL_CONVERT_CHAR_RULE);
        }

        GenericConversion gbkOrderConversion = convertMysqlGbkOrderBy(converted);
        if (gbkOrderConversion.changed()) {
            converted = gbkOrderConversion.convertedSql();
            rules.add(MYSQL_CONVERT_GBK_ORDER_RULE);
        }

        GenericConversion temporaryTableConversion = convertMysqlTemporaryTableAsSelect(converted);
        if (temporaryTableConversion.changed()) {
            converted = temporaryTableConversion.convertedSql();
            rules.add(MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE);
        }

        GenericConversion deleteAliasStarConversion = convertMysqlDeleteAliasStar(converted);
        if (deleteAliasStarConversion.changed()) {
            converted = deleteAliasStarConversion.convertedSql();
            rules.add(MYSQL_DELETE_ALIAS_STAR_RULE);
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

        GenericConversion keywordTableAliasConversion = quoteDamengKeywordTableAliases(converted);
        if (keywordTableAliasConversion.changed()) {
            converted = keywordTableAliasConversion.convertedSql();
            rules.add(DAMENG_KEYWORD_TABLE_ALIAS_RULE);
        }

        GenericConversion implicitCrossJoinConversion = convertImplicitCrossJoins(converted);
        if (implicitCrossJoinConversion.changed()) {
            converted = implicitCrossJoinConversion.convertedSql();
            rules.add(MYSQL_IMPLICIT_CROSS_JOIN_RULE);
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

        GenericConversion substringIndexConversion = convertSubstringIndex(converted);
        if (substringIndexConversion.changed()) {
            converted = substringIndexConversion.convertedSql();
            rules.add(MYSQL_SUBSTRING_INDEX_TO_REGEXP_SUBSTR_RULE);
        }

        GenericConversion havingAggregateAliasConversion = convertHavingAggregateAliases(converted);
        if (havingAggregateAliasConversion.changed()) {
            converted = havingAggregateAliasConversion.convertedSql();
            rules.add(MYSQL_HAVING_AGGREGATE_ALIAS_RULE);
        }

        GenericConversion notFindInSetConversion = convertNotFindInSet(converted);
        if (notFindInSetConversion.changed()) {
            converted = notFindInSetConversion.convertedSql();
            rules.add(MYSQL_NOT_FIND_IN_SET_RULE);
        }

        GenericConversion notIsNullConversion = convertNotIsNull(converted);
        if (notIsNullConversion.changed()) {
            converted = notIsNullConversion.convertedSql();
            rules.add(MYSQL_NOT_ISNULL_RULE);
        }

        GenericConversion countConditionOrNullConversion = convertCountConditionOrNull(converted);
        if (countConditionOrNullConversion.changed()) {
            converted = countConditionOrNullConversion.convertedSql();
            rules.add(MYSQL_COUNT_CONDITION_OR_NULL_RULE);
        }

        GenericConversion countDistinctIfConversion = convertCountDistinctIfToCase(converted);
        if (countDistinctIfConversion.changed()) {
            converted = countDistinctIfConversion.convertedSql();
            rules.add(MYSQL_COUNT_DISTINCT_IF_TO_CASE_RULE);
        }

        GenericConversion booleanOperatorConversion = convertBooleanOperatorsInIfConditions(converted);
        if (booleanOperatorConversion.changed()) {
            converted = booleanOperatorConversion.convertedSql();
            rules.add(MYSQL_BOOLEAN_OPERATOR_RULE);
        }

        GenericConversion ifConversion = convertMysqlIfToCase(converted);
        if (ifConversion.changed()) {
            converted = ifConversion.convertedSql();
            rules.add(MYSQL_IF_TO_CASE_RULE);
        }

        GenericConversion booleanLiteralComparisonConversion = convertBooleanLiteralComparisons(converted);
        if (booleanLiteralComparisonConversion.changed()) {
            converted = booleanLiteralComparisonConversion.convertedSql();
            rules.add(MYSQL_BOOLEAN_LITERAL_COMPARISON_RULE);
        }

        GenericConversion bareBooleanPredicateConversion = convertBareBooleanPredicates(converted);
        if (bareBooleanPredicateConversion.changed()) {
            converted = bareBooleanPredicateConversion.convertedSql();
            rules.add(MYSQL_BARE_BOOLEAN_PREDICATE_RULE);
        }

        GenericConversion locateNumericNeedleConversion = convertLocateNumericNeedle(converted);
        if (locateNumericNeedleConversion.changed()) {
            converted = locateNumericNeedleConversion.convertedSql();
            rules.add(MYSQL_LOCATE_NUMERIC_NEEDLE_RULE);
        }

        GenericConversion likePlaceholderLiteralConversion = convertLikePlaceholderLiteralConcatenation(converted);
        if (likePlaceholderLiteralConversion.changed()) {
            converted = likePlaceholderLiteralConversion.convertedSql();
            rules.add(MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT_RULE);
        }

        GenericConversion concatConversion = convertConcat(converted);
        if (concatConversion.changed()) {
            converted = concatConversion.convertedSql();
            rules.add(MYSQL_CONCAT_TO_DM_OPERATOR_RULE);
        }

        GenericConversion informationSchemaColumnsConversion = convertInformationSchemaColumns(converted);
        if (informationSchemaColumnsConversion.changed()) {
            converted = informationSchemaColumnsConversion.convertedSql();
            rules.add(MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
        }

        GenericConversion informationSchemaTablesConversion = convertInformationSchemaTables(converted);
        if (informationSchemaTablesConversion.changed()) {
            converted = informationSchemaTablesConversion.convertedSql();
            rules.add(MYSQL_INFORMATION_SCHEMA_TABLES_RULE);
        }

        GenericConversion updateOrderLimitConversion = convertMysqlUpdateOrderLimitOne(converted);
        if (updateOrderLimitConversion.changed()) {
            converted = updateOrderLimitConversion.convertedSql();
            rules.add(MYSQL_UPDATE_ORDER_LIMIT_ONE_RULE);
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
                if (literal.closed() && !literal.value().isBlank() && isAsAliasPosition(sql, index)) {
                    converted.append(quoteSingleQuotedAliasValue(literal.value()));
                    index = literal.nextIndex();
                    changed = true;
                } else if (literal.closed()
                        && !literal.value().isBlank()
                        && isImplicitSelectAliasPosition(sql, index, literal.nextIndex())) {
                    converted.append(converted.length() > 0 && Character.isWhitespace(converted.charAt(converted.length() - 1))
                            ? "AS "
                            : " AS ");
                    converted.append(quoteSingleQuotedAliasValue(literal.value()));
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

    private String quoteSingleQuotedAliasValue(String value) {
        if (containsMyBatisPlaceholder(value)) {
            return "\"" + value + "\"";
        }
        return quoteDamengIdentifier(value);
    }

    private boolean isAsAliasPosition(String sql, int quoteIndex) {
        WordToken previousWord = previousWord(sql, quoteIndex);
        return previousWord != null
                && "AS".equalsIgnoreCase(previousWord.text())
                && isOnlyWhitespace(sql, previousWord.endIndex(), quoteIndex);
    }

    private boolean isImplicitSelectAliasPosition(String sql, int quoteIndex, int quoteEndIndex) {
        char previous = previousNonWhitespace(sql, quoteIndex);
        if (previous != ')' && previous != '"' && previous != '`' && !isIdentifierPart(previous)) {
            return false;
        }
        WordToken previousWord = previousWord(sql, quoteIndex);
        if (previousWord != null) {
            String upper = previousWord.text().toUpperCase(Locale.ROOT);
            if (Set.of("SELECT", "DISTINCT", "ALL", "WHERE", "AND", "OR", "ON", "IN", "LIKE", "IS", "THEN", "ELSE", "WHEN")
                    .contains(upper)) {
                return false;
            }
            if (Set.of("COMMENT", "DEFAULT").contains(upper)) {
                return false;
            }
        }
        int next = skipWhitespace(sql, quoteEndIndex);
        boolean hasAliasTerminator = next >= sql.length()
                || sql.charAt(next) == ','
                || startsKeyword(sql, next, "FROM");
        return hasAliasTerminator
                && (isInsideSelectList(sql, quoteIndex) || isLikelySelectListFragmentAliasPosition(sql, quoteIndex));
    }

    private boolean isInsideSelectList(String sql, int targetIndex) {
        Map<Integer, Boolean> selectListByDepth = new LinkedHashMap<>();
        int depth = 0;
        int index = 0;
        while (index < targetIndex && index < sql.length()) {
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
                int newDepth = Math.max(0, depth - 1);
                selectListByDepth.keySet().removeIf(level -> level > newDepth);
                depth = newDepth;
                index++;
            } else if (startsKeyword(sql, index, "SELECT")) {
                selectListByDepth.put(depth, true);
                index += "SELECT".length();
            } else if (startsKeyword(sql, index, "FROM")) {
                selectListByDepth.put(depth, false);
                index += "FROM".length();
            } else {
                index++;
            }
        }
        return Boolean.TRUE.equals(selectListByDepth.get(depth));
    }

    private boolean isLikelySelectListFragmentAliasPosition(String sql, int quoteIndex) {
        int boundary = previousTopLevelSelectItemBoundary(sql, quoteIndex);
        String itemPrefix = sql.substring(boundary, quoteIndex).trim();
        return !itemPrefix.isBlank()
                && !startsWithTopLevelSqlClauseKeyword(itemPrefix)
                && !containsTopLevelClauseKeyword(itemPrefix);
    }

    private int previousTopLevelSelectItemBoundary(String sql, int targetIndex) {
        int depth = 0;
        int boundary = 0;
        int index = 0;
        while (index < targetIndex && index < sql.length()) {
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
                depth = Math.max(0, depth - 1);
                index++;
            } else if (depth == 0 && current == ',') {
                boundary = index + 1;
                index++;
            } else if (depth == 0 && startsKeyword(sql, index, "SELECT")) {
                boundary = index + "SELECT".length();
                index += "SELECT".length();
            } else {
                index++;
            }
        }
        return boundary;
    }

    private boolean startsWithTopLevelSqlClauseKeyword(String sql) {
        int index = skipWhitespace(sql, 0);
        return startsKeyword(sql, index, "AND")
                || startsKeyword(sql, index, "OR")
                || startsKeyword(sql, index, "ON")
                || startsKeyword(sql, index, "WHERE")
                || startsKeyword(sql, index, "FROM")
                || startsKeyword(sql, index, "GROUP")
                || startsKeyword(sql, index, "ORDER")
                || startsKeyword(sql, index, "HAVING")
                || startsKeyword(sql, index, "LIMIT")
                || startsKeyword(sql, index, "JOIN")
                || startsKeyword(sql, index, "SET")
                || startsKeyword(sql, index, "VALUES");
    }

    private boolean containsTopLevelClauseKeyword(String sql) {
        return findTopLevelKeyword(sql, "FROM", 0) >= 0
                || findTopLevelKeyword(sql, "WHERE", 0) >= 0
                || findTopLevelKeyword(sql, "JOIN", 0) >= 0
                || findTopLevelKeyword(sql, "ON", 0) >= 0
                || findTopLevelKeyword(sql, "GROUP", 0) >= 0
                || findTopLevelKeyword(sql, "ORDER", 0) >= 0
                || findTopLevelKeyword(sql, "HAVING", 0) >= 0
                || findTopLevelKeyword(sql, "LIMIT", 0) >= 0
                || findTopLevelKeyword(sql, "UNION", 0) >= 0
                || findTopLevelKeyword(sql, "SET", 0) >= 0
                || findTopLevelKeyword(sql, "VALUES", 0) >= 0;
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

    private GenericConversion removeMysqlCollateClauses(String sql) {
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
            } else {
                int collateEnd = readMysqlCollateClauseEnd(sql, index);
                if (collateEnd < 0) {
                    converted.append(current);
                    index++;
                } else {
                    index = collateEnd;
                    changed = true;
                }
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private int readMysqlCollateClauseEnd(String sql, int index) {
        if (!startsKeyword(sql, index, "COLLATE")) {
            return -1;
        }
        int collationStart = skipWhitespace(sql, index + "COLLATE".length());
        if (collationStart < sql.length() && sql.charAt(collationStart) == '=') {
            collationStart = skipWhitespace(sql, collationStart + 1);
        }
        int cursor = collationStart;
        while (cursor < sql.length() && isIdentifierPart(sql.charAt(cursor))) {
            cursor++;
        }
        if (cursor == collationStart) {
            return -1;
        }
        return skipWhitespace(sql, cursor);
    }

    private GenericConversion removeMysqlCharacterSetClauses(String sql) {
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
            } else {
                int characterSetEnd = readMysqlCharacterSetClauseEnd(sql, index);
                if (characterSetEnd < 0) {
                    converted.append(current);
                    index++;
                } else {
                    index = characterSetEnd;
                    changed = true;
                }
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private int readMysqlCharacterSetClauseEnd(String sql, int index) {
        int cursor;
        if (startsKeyword(sql, index, "CHARACTER")) {
            int setIndex = skipWhitespace(sql, index + "CHARACTER".length());
            if (!startsKeyword(sql, setIndex, "SET")) {
                return -1;
            }
            cursor = skipWhitespace(sql, setIndex + "SET".length());
        } else if (startsKeyword(sql, index, "CHARSET")) {
            cursor = skipWhitespace(sql, index + "CHARSET".length());
            if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                cursor = skipWhitespace(sql, cursor + 1);
            }
        } else {
            return -1;
        }
        int charsetStart = cursor;
        while (cursor < sql.length() && isIdentifierPart(sql.charAt(cursor))) {
            cursor++;
        }
        if (cursor == charsetStart) {
            return -1;
        }
        return skipWhitespace(sql, cursor);
    }

    private GenericConversion convertMysqlAutoIncrement(String sql) {
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
            } else if (startsKeyword(sql, index, "AUTO_INCREMENT")) {
                int afterKeyword = skipWhitespace(sql, index + "AUTO_INCREMENT".length());
                if (afterKeyword < sql.length() && sql.charAt(afterKeyword) == '=') {
                    index = skipMysqlTableOptionValue(sql, afterKeyword + 1);
                } else {
                    converted.append("IDENTITY(1,1)");
                    index += "AUTO_INCREMENT".length();
                }
                changed = true;
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private GenericConversion convertMysqlAlterTableAutoIncrementReset(String sql) {
        Matcher matcher = MYSQL_ALTER_TABLE_AUTO_INCREMENT_RESET.matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String tableName = matcher.group(1).strip();
        return new GenericConversion("SET IDENTITY_INSERT " + tableName + " OFF", true);
    }

    private int skipMysqlTableOptionValue(String sql, int start) {
        int cursor = skipWhitespace(sql, start);
        while (cursor < sql.length()) {
            char current = sql.charAt(cursor);
            if (Character.isLetterOrDigit(current) || current == '_' || current == '.' || current == '-') {
                cursor++;
            } else {
                break;
            }
        }
        return skipWhitespace(sql, cursor);
    }

    private GenericConversion removeMysqlCreateTableOptions(String sql) {
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
            } else {
                int optionEnd = readMysqlCreateTableOptionEnd(sql, index);
                if (optionEnd < 0) {
                    converted.append(current);
                    index++;
                } else {
                    index = optionEnd;
                    changed = true;
                    appendSpaceBeforeNextTokenIfNeeded(converted, sql, index);
                }
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private int readMysqlCreateTableOptionEnd(String sql, int index) {
        if (startsKeyword(sql, index, "ENGINE")
                || startsKeyword(sql, index, "ROW_FORMAT")
                || startsKeyword(sql, index, "AUTO_INCREMENT")) {
            int cursor = skipWhitespace(sql, index + readIdentifierToken(sql, index).text().length());
            if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                return skipMysqlTableOptionValue(sql, cursor + 1);
            }
            return -1;
        }
        if (startsKeyword(sql, index, "DEFAULT")) {
            int cursor = skipWhitespace(sql, index + "DEFAULT".length());
            if (startsKeyword(sql, cursor, "CHARSET")) {
                return readMysqlCharacterSetClauseEnd(sql, cursor);
            }
            if (startsKeyword(sql, cursor, "CHARACTER")) {
                return readMysqlCharacterSetClauseEnd(sql, cursor);
            }
            if (startsKeyword(sql, cursor, "COLLATE")) {
                return readMysqlCollateClauseEnd(sql, cursor);
            }
            return -1;
        }
        if (startsKeyword(sql, index, "COMMENT") && previousNonWhitespace(sql, index) == ')') {
            int cursor = skipWhitespace(sql, index + "COMMENT".length());
            if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                cursor = skipWhitespace(sql, cursor + 1);
            }
            if (cursor < sql.length() && sql.charAt(cursor) == '\'') {
                return skipWhitespace(sql, skipSingleQuotedString(sql, cursor));
            }
        }
        return -1;
    }

    private GenericConversion convertMysqlNumericTypeAttributes(String sql) {
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
                IdentifierToken token = readIdentifierToken(sql, index);
                NumericTypeRewrite rewrite = token == null ? null : readMysqlNumericTypeRewrite(sql, token);
                if (rewrite == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(rewrite.replacement());
                    index = rewrite.endIndex();
                    changed = true;
                    appendSpaceBeforeNextTokenIfNeeded(converted, sql, index);
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private NumericTypeRewrite readMysqlNumericTypeRewrite(String sql, IdentifierToken token) {
        String upper = token.text().toUpperCase(Locale.ROOT);
        if (Set.of("BIGINT", "INT", "INTEGER", "SMALLINT", "TINYINT", "MEDIUMINT").contains(upper)) {
            int cursor = skipWhitespace(sql, token.endIndex());
            boolean changed = false;
            if (cursor < sql.length() && sql.charAt(cursor) == '(') {
                int close = readSimpleNumericTypeModifierEnd(sql, cursor, false);
                if (close > 0) {
                    cursor = skipWhitespace(sql, close);
                    changed = true;
                }
            }
            if (startsKeyword(sql, cursor, "UNSIGNED")) {
                cursor = skipWhitespace(sql, cursor + "UNSIGNED".length());
                changed = true;
            }
            return changed ? new NumericTypeRewrite(cursor, token.text()) : null;
        }
        if ("DOUBLE".equals(upper)) {
            int cursor = skipWhitespace(sql, token.endIndex());
            if (cursor < sql.length() && sql.charAt(cursor) == '(') {
                int close = readSimpleNumericTypeModifierEnd(sql, cursor, true);
                if (close > 0) {
                    String scale = sql.substring(cursor + 1, close - 1).trim();
                    return new NumericTypeRewrite(skipWhitespace(sql, close), "DECIMAL(" + scale + ")");
                }
            }
        }
        return null;
    }

    private int readSimpleNumericTypeModifierEnd(String sql, int openParenIndex, boolean allowComma) {
        int index = openParenIndex + 1;
        boolean hasDigit = false;
        boolean hasComma = false;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isDigit(current)) {
                hasDigit = true;
                index++;
            } else if (allowComma && current == ',' && !hasComma) {
                hasComma = true;
                index++;
            } else if (Character.isWhitespace(current)) {
                index++;
            } else if (current == ')') {
                return hasDigit ? index + 1 : -1;
            } else {
                return -1;
            }
        }
        return -1;
    }

    private GenericConversion capMysqlDecimalPrecision(String sql) {
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
                IdentifierToken token = readIdentifierToken(sql, index);
                NumericTypeRewrite rewrite = token == null ? null : readDecimalPrecisionRewrite(sql, token);
                if (rewrite == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(rewrite.replacement());
                    index = rewrite.endIndex();
                    changed = true;
                    appendSpaceBeforeNextTokenIfNeeded(converted, sql, index);
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private NumericTypeRewrite readDecimalPrecisionRewrite(String sql, IdentifierToken token) {
        String upper = token.text().toUpperCase(Locale.ROOT);
        if (!Set.of("DEC", "DECIMAL", "NUMERIC").contains(upper)) {
            return null;
        }
        int cursor = skipWhitespace(sql, token.endIndex());
        if (cursor >= sql.length() || sql.charAt(cursor) != '(') {
            return null;
        }
        int close = readSimpleNumericTypeModifierEnd(sql, cursor, true);
        if (close < 0) {
            return null;
        }
        String[] parts = sql.substring(cursor + 1, close - 1).trim().split(",", -1);
        if (parts.length == 0 || parts.length > 2) {
            return null;
        }
        Integer precision = parsePositiveInteger(parts[0].trim());
        if (precision == null) {
            return null;
        }
        Integer scale = null;
        if (parts.length == 2) {
            scale = parsePositiveInteger(parts[1].trim());
            if (scale == null) {
                return null;
            }
        }
        int cappedPrecision = Math.min(precision, 38);
        Integer cappedScale = scale == null ? null : Math.min(scale, cappedPrecision);
        if (precision == cappedPrecision && (scale == null || scale.equals(cappedScale))) {
            return null;
        }
        String replacement = token.text()
                + "("
                + cappedPrecision
                + (cappedScale == null ? "" : "," + cappedScale)
                + ")";
        return new NumericTypeRewrite(skipWhitespace(sql, close), replacement);
    }

    private Integer parsePositiveInteger(String value) {
        if (value.isEmpty()) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private GenericConversion removeMysqlCreateTableColumnComments(String sql) {
        int createIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, createIndex, "CREATE")) {
            return GenericConversion.unchanged(sql);
        }
        int tableIndex = findTopLevelKeyword(sql, "TABLE", createIndex + "CREATE".length());
        if (tableIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int openParenIndex = findTopLevelChar(sql, '(', tableIndex + "TABLE".length());
        if (openParenIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int closeParenIndex = findMatchingParen(sql, openParenIndex);
        if (closeParenIndex < 0) {
            return GenericConversion.unchanged(sql);
        }

        String body = sql.substring(openParenIndex + 1, closeParenIndex);
        List<TopLevelArgument> definitions = splitTopLevelArguments(body);
        List<String> convertedDefinitions = new ArrayList<>();
        boolean changed = false;
        for (TopLevelArgument definition : definitions) {
            GenericConversion conversion = removeMysqlColumnCommentClause(definition.text());
            convertedDefinitions.add(conversion.convertedSql());
            changed = changed || conversion.changed();
        }
        String convertedSql = changed
                ? sql.substring(0, openParenIndex + 1)
                        + String.join(",", convertedDefinitions)
                        + sql.substring(closeParenIndex)
                : sql;
        GenericConversion tableCommentConversion = removeMysqlCreateTableTrailingComment(convertedSql);
        if (tableCommentConversion.changed()) {
            convertedSql = tableCommentConversion.convertedSql();
            changed = true;
        }
        return changed ? new GenericConversion(convertedSql, true) : GenericConversion.unchanged(sql);
    }

    private GenericConversion removeMysqlCreateTableTrailingComment(String sql) {
        int createIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, createIndex, "CREATE")) {
            return GenericConversion.unchanged(sql);
        }
        int tableIndex = findTopLevelKeyword(sql, "TABLE", createIndex + "CREATE".length());
        if (tableIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int openParenIndex = findTopLevelChar(sql, '(', tableIndex + "TABLE".length());
        if (openParenIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int closeParenIndex = findMatchingParen(sql, openParenIndex);
        if (closeParenIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int commentIndex = skipWhitespace(sql, closeParenIndex + 1);
        if (!startsKeyword(sql, commentIndex, "COMMENT")) {
            return GenericConversion.unchanged(sql);
        }
        int cursor = skipWhitespace(sql, commentIndex + "COMMENT".length());
        if (cursor < sql.length() && sql.charAt(cursor) == '=') {
            cursor = skipWhitespace(sql, cursor + 1);
        }
        if (cursor >= sql.length()) {
            return GenericConversion.unchanged(sql);
        }
        char quote = sql.charAt(cursor);
        int commentEnd;
        if (quote == '\'') {
            commentEnd = skipSingleQuotedString(sql, cursor);
        } else if (quote == '"') {
            commentEnd = skipDoubleQuotedText(sql, cursor);
        } else {
            return GenericConversion.unchanged(sql);
        }
        int end = skipWhitespace(sql, commentEnd);
        return new GenericConversion(sql.substring(0, commentIndex) + sql.substring(end), true);
    }

    private GenericConversion removeMysqlColumnCommentClause(String definition) {
        StringBuilder converted = new StringBuilder(definition.length());
        boolean changed = false;
        int index = 0;
        while (index < definition.length()) {
            char current = definition.charAt(index);
            if (current == '\'') {
                index = appendSingleQuotedString(definition, index, converted);
            } else if (current == '"') {
                index = appendDoubleQuotedText(definition, index, converted);
            } else if (startsMyBatisPlaceholder(definition, index)) {
                index = appendMyBatisPlaceholder(definition, index, converted);
            } else if (startsLineComment(definition, index)) {
                index = appendUntilLineEnd(definition, index, converted);
            } else if (startsBlockComment(definition, index)) {
                index = appendUntilBlockCommentEnd(definition, index, converted);
            } else if (startsKeyword(definition, index, "COMMENT")) {
                int cursor = skipWhitespace(definition, index + "COMMENT".length());
                if (cursor < definition.length() && definition.charAt(cursor) == '=') {
                    cursor = skipWhitespace(definition, cursor + 1);
                }
                if (cursor < definition.length() && definition.charAt(cursor) == '\'') {
                    trimTrailingWhitespace(converted);
                    index = skipSingleQuotedString(definition, cursor);
                    changed = true;
                } else {
                    converted.append(current);
                    index++;
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : definition, changed);
    }

    private void trimTrailingWhitespace(StringBuilder builder) {
        while (!builder.isEmpty() && Character.isWhitespace(builder.charAt(builder.length() - 1))) {
            builder.setLength(builder.length() - 1);
        }
    }

    private GenericConversion removeMysqlUsingBtreeClauses(String sql) {
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
            } else if (startsKeyword(sql, index, "USING")) {
                int btreeIndex = skipWhitespace(sql, index + "USING".length());
                if (startsKeyword(sql, btreeIndex, "BTREE")) {
                    index = skipWhitespace(sql, btreeIndex + "BTREE".length());
                    changed = true;
                } else {
                    converted.append(current);
                    index++;
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private GenericConversion convertMysqlTruncateTableStatement(String sql) {
        int truncateIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, truncateIndex, "TRUNCATE")) {
            return GenericConversion.unchanged(sql);
        }
        int cursor = skipWhitespace(sql, truncateIndex + "TRUNCATE".length());
        if (startsKeyword(sql, cursor, "TABLE")) {
            return GenericConversion.unchanged(sql);
        }
        IdentifierToken table = readDynamicTableNameToken(sql, cursor);
        if (table == null) {
            return GenericConversion.unchanged(sql);
        }
        return new GenericConversion(
                sql.substring(0, truncateIndex) + "TRUNCATE TABLE " + sql.substring(cursor),
                true
        );
    }

    private GenericConversion removeMysqlCreateTableKeyDefinitions(String sql) {
        int createIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, createIndex, "CREATE")) {
            return GenericConversion.unchanged(sql);
        }
        int tableIndex = findTopLevelKeyword(sql, "TABLE", createIndex + "CREATE".length());
        if (tableIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int openParenIndex = findTopLevelChar(sql, '(', tableIndex + "TABLE".length());
        if (openParenIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int closeParenIndex = findMatchingParen(sql, openParenIndex);
        if (closeParenIndex < 0) {
            return GenericConversion.unchanged(sql);
        }

        String body = sql.substring(openParenIndex + 1, closeParenIndex);
        List<TopLevelArgument> definitions = splitTopLevelArguments(body);
        List<String> kept = new ArrayList<>();
        boolean changed = false;
        for (TopLevelArgument definition : definitions) {
            if (isMysqlCreateTableSecondaryKeyDefinition(definition.text())) {
                changed = true;
            } else {
                kept.add(definition.text());
            }
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        String replacement = String.join(",", kept);
        return new GenericConversion(
                sql.substring(0, openParenIndex + 1) + replacement + sql.substring(closeParenIndex),
                true
        );
    }

    private boolean isMysqlCreateTableSecondaryKeyDefinition(String definition) {
        int index = leadingWhitespaceLength(definition);
        if (startsKeyword(definition, index, "KEY") || startsKeyword(definition, index, "INDEX")) {
            return true;
        }
        if (startsKeyword(definition, index, "UNIQUE")) {
            int cursor = skipWhitespace(definition, index + "UNIQUE".length());
            return startsKeyword(definition, cursor, "KEY") || startsKeyword(definition, cursor, "INDEX");
        }
        return false;
    }

    private GenericConversion removeMysqlOnUpdateCurrentTimestamp(String sql) {
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
            } else if (startsKeyword(sql, index, "ON")) {
                int updateIndex = skipWhitespace(sql, index + "ON".length());
                int timestampIndex = skipWhitespace(sql, updateIndex + "UPDATE".length());
                if (startsKeyword(sql, updateIndex, "UPDATE")
                        && startsKeyword(sql, timestampIndex, "CURRENT_TIMESTAMP")) {
                    int cursor = skipWhitespace(sql, timestampIndex + "CURRENT_TIMESTAMP".length());
                    if (cursor < sql.length() && sql.charAt(cursor) == '(') {
                        int close = findMatchingParen(sql, cursor);
                        if (close > 0) {
                            cursor = skipWhitespace(sql, close + 1);
                        }
                    }
                    index = cursor;
                    changed = true;
                    appendSpaceBeforeNextTokenIfNeeded(converted, sql, index);
                } else {
                    converted.append(current);
                    index++;
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private GenericConversion convertMysqlSessionVariableSetToNoop(String sql) {
        String trimmed = sql.strip();
        if (!trimmed.matches("(?is)^set\\s+session\\s+(tmp_table_size|max_heap_table_size)\\s*=\\s*[^;]+;?\\s*$")) {
            return GenericConversion.unchanged(sql);
        }
        return new GenericConversion("SELECT 1", true);
    }

    private void appendSpaceBeforeNextTokenIfNeeded(StringBuilder converted, String sql, int nextIndex) {
        if (converted.isEmpty() || nextIndex >= sql.length()) {
            return;
        }
        char previous = converted.charAt(converted.length() - 1);
        char next = sql.charAt(nextIndex);
        if (!Character.isWhitespace(previous)
                && !Character.isWhitespace(next)
                && previous != '('
                && next != ')'
                && next != ','
                && next != ';') {
            converted.append(' ');
        }
    }

    private GenericConversion removeDuplicateWhereKeyword(String sql) {
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
            } else if (startsKeyword(sql, index, "WHERE")) {
                int afterFirstWhere = index + "WHERE".length();
                int secondWhereIndex = skipWhitespace(sql, afterFirstWhere);
                if (startsKeyword(sql, secondWhereIndex, "WHERE")) {
                    converted.append(sql, lastCopiedIndex, afterFirstWhere);
                    converted.append(' ');
                    int afterSecondWhere = skipWhitespace(sql, secondWhereIndex + "WHERE".length());
                    lastCopiedIndex = afterSecondWhere;
                    index = afterSecondWhere;
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

    private GenericConversion convertDateSubInterval(String sql) {
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
                String replacement = functionCall == null ? null : rewriteDateSubInterval(functionCall);
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

    private String rewriteDateSubInterval(FunctionCall dateSubCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(dateSubCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        Matcher matcher = MYSQL_INTERVAL_PATTERN.matcher(arguments.get(1).text());
        if (!matcher.matches()) {
            return null;
        }
        String amount = matcher.group(1).trim();
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);
        String dateExpression = arguments.get(0).text().trim();
        if (amount.isBlank() || dateExpression.isBlank()) {
            return null;
        }
        String dmDateExpression = isNowExpression(dateExpression) ? "SYSDATE" : dateExpression;
        return "DATEADD(" + unit + ", " + negatedIntervalAmount(amount) + ", " + dmDateExpression + ")";
    }

    private GenericConversion convertSubDateFunctions(String sql) {
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
            } else if (startsFunction(sql, index, "SUBDATE")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "SUBDATE");
                String replacement = functionCall == null ? null : rewriteSubDate(functionCall);
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

    private String rewriteSubDate(FunctionCall subDateCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(subDateCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String dateExpression = arguments.get(0).text().trim();
        String dayExpression = arguments.get(1).text().trim();
        if (dateExpression.isBlank() || dayExpression.isBlank()) {
            return null;
        }
        return "DATEADD(DAY, " + negatedIntervalAmount(dayExpression) + ", " + dateExpression + ")";
    }

    private GenericConversion convertDateAddInterval(String sql) {
        String converted = sql;
        boolean changed = false;
        for (int pass = 0; pass < 8; pass++) {
            GenericConversion functionConversion = convertDateAddFunctionInterval(converted);
            GenericConversion additionConversion = convertDateIntervalAddition(functionConversion.convertedSql());
            if (!functionConversion.changed() && !additionConversion.changed()) {
                break;
            }
            converted = additionConversion.convertedSql();
            changed = true;
        }
        return new GenericConversion(converted, changed);
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
            } else if (current == '+' || current == '-') {
                DateIntervalAddition addition = readDateIntervalAddition(sql, index);
                if (addition == null || addition.leftExpression().startIndex() < lastCopiedIndex) {
                    index++;
                } else {
                    String amount = current == '-'
                            ? negatedIntervalAmount(addition.intervalExpression().amount())
                            : addition.intervalExpression().amount();
                    converted.append(sql, lastCopiedIndex, addition.leftExpression().startIndex());
                    converted.append("DATEADD(")
                            .append(addition.intervalExpression().unit())
                            .append(", ")
                            .append(amount)
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
        } else if (previous == '\'') {
            start = findSingleQuotedStringStartEndingAt(sql, end);
            if (start < 0) {
                return null;
            }
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

    private int findSingleQuotedStringStartEndingAt(String sql, int end) {
        int closeIndex = end - 1;
        if (closeIndex <= 0 || sql.charAt(closeIndex) != '\'') {
            return -1;
        }
        int index = 0;
        while (index < closeIndex) {
            if (sql.charAt(index) == '\'') {
                int nextIndex = skipSingleQuotedString(sql, index);
                if (nextIndex == end) {
                    return index;
                }
                if (nextIndex > closeIndex) {
                    return -1;
                }
                index = nextIndex;
            } else {
                index++;
            }
        }
        return -1;
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

    private GenericConversion convertMakeDateFunctions(String sql) {
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
            } else if (startsFunction(sql, index, "MAKEDATE")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "MAKEDATE");
                String replacement = functionCall == null ? null : rewriteMakeDate(functionCall);
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

    private String rewriteMakeDate(FunctionCall makeDateCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(makeDateCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String yearExpression = arguments.get(0).text().trim();
        String dayOfYearExpression = arguments.get(1).text().trim();
        if (yearExpression.isBlank() || dayOfYearExpression.isBlank()) {
            return null;
        }
        return "DATEADD(DAY, "
                + dayOfYearExpression
                + " - 1, TO_DATE(CONCAT("
                + yearExpression
                + ", '-01-01'), 'YYYY-MM-DD'))";
    }

    private GenericConversion convertStrToDateYearMonth(String sql) {
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
            } else if (startsFunction(sql, index, "STR_TO_DATE")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "STR_TO_DATE");
                String replacement = functionCall == null ? null : rewriteStrToDateYearMonth(functionCall);
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

    private String rewriteStrToDateYearMonth(FunctionCall strToDateCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(strToDateCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String format = normalizedStringLiteral(arguments.get(1).text());
        if (!"'%Y%m'".equalsIgnoreCase(format)) {
            return null;
        }
        String expression = arguments.get(0).text().trim();
        if (expression.isBlank()) {
            return null;
        }
        return "TO_DATE(" + expression + ", 'YYYYMM')";
    }

    private GenericConversion convertPeriodDiffYearMonth(String sql) {
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
            } else if (startsFunction(sql, index, "PERIOD_DIFF")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "PERIOD_DIFF");
                String replacement = functionCall == null ? null : rewritePeriodDiffYearMonth(functionCall);
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

    private String rewritePeriodDiffYearMonth(FunctionCall periodDiffCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(periodDiffCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String left = yearMonthExtractExpression(arguments.get(0).text());
        String right = yearMonthExtractExpression(arguments.get(1).text());
        if (left == null || right == null) {
            return null;
        }
        return "DATEDIFF(MONTH, " + right + ", " + left + ")";
    }

    private String yearMonthExtractExpression(String expression) {
        String trimmed = expression.trim();
        int start = leadingWhitespaceLength(trimmed);
        FunctionCall extractCall = readFunctionCall(trimmed, start, "EXTRACT");
        if (extractCall != null && skipWhitespace(trimmed, extractCall.endIndex()) == trimmed.length()) {
            Matcher matcher = Pattern.compile("(?is)^\\s*YEAR_MONTH\\s+FROM\\s+(.+?)\\s*$")
                    .matcher(extractCall.body());
            if (matcher.matches()) {
                return matcher.group(1).trim();
            }
        }
        FunctionCall dateFormatCall = readFunctionCall(trimmed, start, "DATE_FORMAT");
        if (dateFormatCall == null || skipWhitespace(trimmed, dateFormatCall.endIndex()) != trimmed.length()) {
            return null;
        }
        List<TopLevelArgument> arguments = splitTopLevelArguments(dateFormatCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String format = normalizedStringLiteral(arguments.get(1).text());
        return "'%Y%m'".equalsIgnoreCase(format) ? arguments.get(0).text().trim() : null;
    }

    private GenericConversion convertDateDiff2Arg(String sql) {
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
            } else if (startsFunction(sql, index, "DATEDIFF")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "DATEDIFF");
                String replacement = functionCall == null ? null : rewriteDateDiff2Arg(functionCall);
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

    private String rewriteDateDiff2Arg(FunctionCall dateDiffCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(dateDiffCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String left = arguments.get(0).text().trim();
        String right = arguments.get(1).text().trim();
        if (left.isBlank() || right.isBlank()) {
            return null;
        }
        return "DATEDIFF(DAY, " + right + ", " + left + ")";
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
        if (trimmed.startsWith("+")) {
            return "-" + trimmed.substring(1).trim();
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

    private GenericConversion convertSignedCasts(String sql) {
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
                String replacement = functionCall == null ? null : rewriteSignedCast(functionCall);
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

    private String rewriteSignedCast(FunctionCall castCall) {
        Matcher matcher = CAST_SIGNED_BODY_PATTERN.matcher(castCall.body());
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

    private GenericConversion convertCharConvertFunctions(String sql) {
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
                String replacement = functionCall == null ? null : rewriteCharConvert(functionCall);
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

    private String rewriteCharConvert(FunctionCall convertCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(convertCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String expression = arguments.get(0).text().trim();
        String targetType = arguments.get(1).text().trim();
        if (expression.isBlank() || !CHAR_TARGET_TYPE_PATTERN.matcher(targetType).matches()) {
            return null;
        }
        return "CAST(" + expression + " AS VARCHAR(4000))";
    }

    private GenericConversion convertMysqlGbkOrderBy(String sql) {
        int orderIndex = findTopLevelKeyword(sql, "ORDER", 0);
        if (orderIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int byIndex = skipWhitespace(sql, orderIndex + "ORDER".length());
        if (!startsKeyword(sql, byIndex, "BY")) {
            return GenericConversion.unchanged(sql);
        }

        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = byIndex + "BY".length();
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
            } else if (startsFunction(sql, index, "CONVERT")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "CONVERT");
                String replacement = functionCall == null ? null : rewriteGbkOrderConvert(functionCall);
                if (replacement == null) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, functionCall.startIndex());
                    converted.append(replacement);
                    lastCopiedIndex = functionCall.endIndex();
                    index = functionCall.endIndex();
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

    private String rewriteGbkOrderConvert(FunctionCall convertCall) {
        Matcher matcher = Pattern.compile("(?is)^\\s*(.+?)\\s+USING\\s+GBK\\s*$").matcher(convertCall.body());
        if (!matcher.matches()) {
            return null;
        }
        String expression = matcher.group(1).trim();
        if (expression.isBlank()) {
            return null;
        }
        return "NLSSORT(" + expression + ", 'NLS_SORT=SCHINESE_PINYIN_M')";
    }

    private GenericConversion convertMysqlTemporaryTableAsSelect(String sql) {
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
            } else if (startsKeyword(sql, index, "CREATE")) {
                TextReplacement replacement = readMysqlTemporaryTableAsSelect(sql, index);
                if (replacement == null) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, replacement.startIndex());
                    converted.append(replacement.replacement());
                    lastCopiedIndex = replacement.endIndex();
                    index = replacement.endIndex();
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

    private TextReplacement readMysqlTemporaryTableAsSelect(String sql, int createIndex) {
        int index = skipWhitespace(sql, createIndex + "CREATE".length());
        if (startsKeyword(sql, index, "GLOBAL")) {
            index = skipWhitespace(sql, index + "GLOBAL".length());
        }
        if (!startsKeyword(sql, index, "TEMPORARY")) {
            return null;
        }
        index = skipWhitespace(sql, index + "TEMPORARY".length());
        if (!startsKeyword(sql, index, "TABLE")) {
            return null;
        }
        int tableNameStart = skipWhitespace(sql, index + "TABLE".length());
        int tableNameEnd = readCreateTableNameEnd(sql, tableNameStart);
        if (tableNameEnd <= tableNameStart) {
            return null;
        }
        index = skipWhitespace(sql, tableNameEnd);
        if (index < sql.length() && sql.charAt(index) == '(') {
            return null;
        }
        if (startsKeyword(sql, index, "AS")) {
            index = skipWhitespace(sql, index + "AS".length());
        }
        if (!startsKeyword(sql, index, "SELECT")) {
            return null;
        }
        String tableName = sql.substring(tableNameStart, tableNameEnd).trim();
        if (tableName.isBlank()) {
            return null;
        }
        return new TextReplacement(
                createIndex,
                index,
                "CREATE GLOBAL TEMPORARY TABLE " + tableName + " ON COMMIT PRESERVE ROWS AS "
        );
    }

    private int readCreateTableNameEnd(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current) || current == '(' || current == ';') {
                break;
            }
            if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else {
                index++;
            }
        }
        return index;
    }

    private GenericConversion convertMysqlDeleteAliasStar(String sql) {
        int deleteIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, deleteIndex, "DELETE")) {
            return GenericConversion.unchanged(sql);
        }
        int aliasStart = skipWhitespace(sql, deleteIndex + "DELETE".length());
        IdentifierToken deleteAlias = readIdentifierToken(sql, aliasStart);
        if (deleteAlias == null) {
            return GenericConversion.unchanged(sql);
        }
        int dotIndex = skipWhitespace(sql, deleteAlias.endIndex());
        if (dotIndex >= sql.length() || sql.charAt(dotIndex) != '.') {
            return GenericConversion.unchanged(sql);
        }
        int starIndex = skipWhitespace(sql, dotIndex + 1);
        if (starIndex >= sql.length() || sql.charAt(starIndex) != '*') {
            return GenericConversion.unchanged(sql);
        }
        int fromIndex = skipWhitespace(sql, starIndex + 1);
        if (!startsKeyword(sql, fromIndex, "FROM")) {
            return GenericConversion.unchanged(sql);
        }
        int tableStart = skipWhitespace(sql, fromIndex + "FROM".length());
        IdentifierToken table = readDynamicTableNameToken(sql, tableStart);
        if (table == null) {
            return GenericConversion.unchanged(sql);
        }
        int tableAliasStart = skipWhitespace(sql, table.endIndex());
        IdentifierToken tableAlias = readIdentifierToken(sql, tableAliasStart);
        if (tableAlias == null
                || !normalizeIdentifierKey(tableAlias.text()).equals(normalizeIdentifierKey(deleteAlias.text()))) {
            return GenericConversion.unchanged(sql);
        }
        return new GenericConversion(sql.substring(0, deleteIndex) + "delete from " + sql.substring(tableStart), true);
    }

    private IdentifierToken readDynamicTableNameToken(String sql, int start) {
        IdentifierToken identifier = readQualifiedIdentifierToken(sql, start);
        if (identifier != null) {
            return identifier;
        }
        if (start + 2 > sql.length() || !sql.startsWith("${", start)) {
            return null;
        }
        int end = skipMyBatisPlaceholder(sql, start);
        return end > start + 3 ? new IdentifierToken(sql.substring(start, end), end) : null;
    }

    private GenericConversion convertInformationSchemaColumns(String sql) {
        GenericConversion tableListConversion = convertInformationSchemaColumnsTableList(sql);
        if (tableListConversion.changed()) {
            return tableListConversion;
        }
        Matcher matcher = INFORMATION_SCHEMA_COLUMNS_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String whereClause = matcher.group("where");
        Matcher tableNameMatcher = METADATA_TABLE_NAME_CONDITION.matcher(whereClause);
        Matcher tableSchemaMatcher = METADATA_TABLE_SCHEMA_CONDITION.matcher(whereClause);
        if (!tableNameMatcher.find() || !tableSchemaMatcher.find()) {
            return GenericConversion.unchanged(sql);
        }
        String tableName = tableNameMatcher.group("value").trim();
        String tableSchema = tableSchemaMatcher.group("value").trim();
        String residual = tableNameMatcher.replaceAll("");
        residual = METADATA_TABLE_SCHEMA_CONDITION.matcher(residual).replaceAll("");
        residual = residual.replaceAll("(?i)\\bAND\\b", "")
                .replaceAll("[()\\s]", "");
        if (!residual.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String direction = matcher.group("direction");
        String converted = "SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE TABLE_NAME = UPPER("
                + tableName
                + ") AND OWNER = UPPER("
                + tableSchema
                + ") ORDER BY COLUMN_ID"
                + (direction == null || direction.isBlank() ? "" : " " + direction.toUpperCase(Locale.ROOT));
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaColumnsTableList(String sql) {
        Matcher matcher = INFORMATION_SCHEMA_COLUMNS_TABLE_LIST_PATTERN.matcher(sql);
        if (matcher.matches()) {
            String tableLike = matcher.group("tableLike").trim();
            String notIn = matcher.group("notIn").trim();
            if (tableLike.isBlank() || notIn.isBlank()) {
                return GenericConversion.unchanged(sql);
            }
            String converted = "SELECT TABLE_NAME\n"
                    + "FROM ALL_TAB_COLUMNS\n"
                    + "WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')\n"
                    + "AND TABLE_NAME LIKE UPPER(" + tableLike + ")\n"
                    + "AND COLUMN_NAME NOT IN\n"
                    + notIn
                    + "\nGROUP BY TABLE_NAME";
            return new GenericConversion(converted, true);
        }
        Matcher prefixMatcher = INFORMATION_SCHEMA_COLUMNS_TABLE_LIST_PREFIX_PATTERN.matcher(sql);
        if (!prefixMatcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String tableLike = prefixMatcher.group("tableLike").trim();
        if (tableLike.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String converted = "SELECT TABLE_NAME\n"
                + "FROM ALL_TAB_COLUMNS\n"
                + "WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')\n"
                + "AND TABLE_NAME LIKE UPPER(" + tableLike + ")\n"
                + "AND COLUMN_NAME NOT IN";
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaTables(String sql) {
        GenericConversion tableDetailConversion = convertInformationSchemaTablesDetail(sql);
        if (tableDetailConversion.changed()) {
            return tableDetailConversion;
        }
        Matcher matcher = INFORMATION_SCHEMA_TABLES_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String whereClause = matcher.group("where");
        Matcher tableNameMatcher = METADATA_TABLE_NAME_CONDITION.matcher(whereClause);
        if (!tableNameMatcher.find()) {
            return GenericConversion.unchanged(sql);
        }
        String tableName = tableNameMatcher.group("value").trim();
        Matcher tableSchemaMatcher = METADATA_TABLE_SCHEMA_CONDITION.matcher(whereClause);
        String tableSchema = tableSchemaMatcher.find() ? tableSchemaMatcher.group("value").trim() : "";
        String residual = tableNameMatcher.replaceAll("");
        residual = METADATA_TABLE_SCHEMA_CONDITION.matcher(residual).replaceAll("");
        residual = residual.replaceAll("(?i)\\bAND\\b", "")
                .replaceAll("[()\\s]", "");
        if (!residual.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String alias = matcher.group("alias");
        String converted = "SELECT COUNT(*)"
                + (alias == null || alias.isBlank() ? "" : " AS " + alias)
                + " FROM ALL_TABLES WHERE TABLE_NAME = UPPER("
                + tableName
                + ")"
                + (tableSchema.isBlank() ? "" : " AND OWNER = UPPER(" + tableSchema + ")");
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaTablesDetail(String sql) {
        Matcher matcher = INFORMATION_SCHEMA_TABLES_DETAIL_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String tableLike = matcher.group("tableLike").trim();
        if (tableLike.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String converted = "SELECT OBJECT_NAME AS " + matcher.group("tableAlias") + "\n"
                + ", CREATED AS " + matcher.group("createAlias") + "\n"
                + ", OWNER AS " + matcher.group("schemaAlias") + "\n"
                + "FROM ALL_OBJECTS\n"
                + "WHERE OBJECT_TYPE = 'TABLE'\n"
                + "AND OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')\n"
                + "AND OBJECT_NAME LIKE UPPER(" + tableLike + ")";
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertLocateNumericNeedle(String sql) {
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
            } else if (startsFunction(sql, index, "LOCATE")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "LOCATE");
                String replacement = functionCall == null ? null : rewriteLocateNumericNeedle(functionCall);
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

    private String rewriteLocateNumericNeedle(FunctionCall locateCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(locateCall.body());
        if (arguments.size() < 2) {
            return null;
        }
        String needle = arguments.get(0).text().trim();
        String haystack = arguments.get(1).text().trim();
        if (!isNumericMyBatisPlaceholder(needle) || readOnlyFunctionCall(haystack, "CONCAT") == null) {
            return null;
        }
        StringBuilder replacement = new StringBuilder("LOCATE(CAST(")
                .append(needle)
                .append(" AS VARCHAR(64)), ")
                .append(haystack);
        for (int i = 2; i < arguments.size(); i++) {
            replacement.append(", ").append(arguments.get(i).text().trim());
        }
        return replacement.append(")").toString();
    }

    private boolean isNumericMyBatisPlaceholder(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        if (!trimmed.startsWith("#{") || !trimmed.endsWith("}")) {
            return false;
        }
        String body = trimmed.substring(2, trimmed.length() - 1).trim();
        int commaIndex = body.indexOf(',');
        String propertyName = (commaIndex >= 0 ? body.substring(0, commaIndex) : body).trim();
        String normalized = propertyName.replace("_", "").replace("-", "").replace(".", "").toLowerCase(Locale.ROOT);
        return normalized.endsWith("id")
                || normalized.endsWith("ids")
                || normalized.contains("userid")
                || normalized.contains("enterpriseid")
                || normalized.contains("organizationid")
                || normalized.contains("precinctid")
                || normalized.endsWith("type")
                || normalized.endsWith("status")
                || normalized.endsWith("flag")
                || normalized.endsWith("count")
                || normalized.endsWith("index");
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
        List<StatementSegment> statements = splitTopLevelStatements(sql);
        if (statements.size() > 1) {
            StringBuilder converted = new StringBuilder(sql.length());
            boolean changed = false;
            for (StatementSegment statement : statements) {
                GenericConversion conversion = convertSingleMysqlUpdateJoin(statement.sql());
                converted.append(conversion.convertedSql()).append(statement.separator());
                changed = changed || conversion.changed();
            }
            return changed ? new GenericConversion(converted.toString(), true) : GenericConversion.unchanged(sql);
        }
        return convertSingleMysqlUpdateJoin(sql);
    }

    private GenericConversion convertSingleMysqlUpdateJoin(String sql) {
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
        if (updatesJoinedTableAlias(target, setClause)) {
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

    private List<StatementSegment> splitTopLevelStatements(String sql) {
        List<StatementSegment> statements = new ArrayList<>();
        int start = 0;
        int separatorIndex = findTopLevelChar(sql, ';', start);
        if (separatorIndex < 0) {
            return List.of(new StatementSegment(sql, ""));
        }
        while (separatorIndex >= 0) {
            statements.add(new StatementSegment(sql.substring(start, separatorIndex), ";"));
            start = separatorIndex + 1;
            separatorIndex = findTopLevelChar(sql, ';', start);
        }
        statements.add(new StatementSegment(sql.substring(start), ""));
        return statements;
    }

    private boolean updatesJoinedTableAlias(String target, String setClause) {
        String targetAlias = updateTargetAlias(target);
        if (targetAlias.isBlank()) {
            return false;
        }
        for (TopLevelArgument assignment : splitTopLevelArguments(setClause)) {
            Matcher matcher = UPDATE_SET_QUALIFIED_ASSIGNMENT.matcher(assignment.text());
            if (matcher.find() && !normalizeIdentifierKey(matcher.group("alias")).equals(targetAlias)) {
                return true;
            }
        }
        return false;
    }

    private String updateTargetAlias(String target) {
        String trimmed = target == null ? "" : target.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        List<String> parts = Pattern.compile("\\s+")
                .splitAsStream(trimmed)
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.isEmpty()) {
            return "";
        }
        String alias = parts.size() >= 2 ? parts.get(parts.size() - 1) : parts.get(0);
        if ("AS".equalsIgnoreCase(alias) && parts.size() >= 2) {
            alias = parts.get(parts.size() - 2);
        }
        return normalizeIdentifierKey(alias);
    }

    private String normalizeIdentifierKey(String identifier) {
        String trimmed = identifier == null ? "" : identifier.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed.toUpperCase(Locale.ROOT);
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

    private GenericConversion quoteDamengKeywordTableAliases(String sql) {
        List<TextReplacement> replacements = new ArrayList<>();
        Set<String> aliases = new java.util.LinkedHashSet<>();
        collectDamengKeywordTableAliases(sql, replacements, aliases);
        if (aliases.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        collectDamengKeywordAliasReferences(sql, replacements, aliases);
        return applyTextReplacements(sql, replacements);
    }

    private void collectDamengKeywordTableAliases(
            String sql,
            List<TextReplacement> replacements,
            Set<String> aliases
    ) {
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
            } else if (startsKeyword(sql, index, "FROM") || startsKeyword(sql, index, "JOIN")) {
                TableAlias alias = readTableAlias(sql, index, startsKeyword(sql, index, "FROM") ? "FROM" : "JOIN");
                if (alias == null) {
                    index++;
                } else {
                    String unquotedAlias = unquoteIdentifier(alias.text());
                    if (isDamengKeywordRequiringQuotes(unquotedAlias)) {
                        aliases.add(unquotedAlias.toUpperCase(Locale.ROOT));
                    }
                    if (!alias.text().startsWith("\"") && isDamengKeywordRequiringQuotes(unquotedAlias)) {
                        replacements.add(new TextReplacement(
                                alias.startIndex(),
                                alias.endIndex(),
                                quoteDamengIdentifier(unquotedAlias)
                        ));
                    }
                    index = alias.endIndex();
                }
            } else {
                index++;
            }
        }
    }

    private TableAlias readTableAlias(String sql, int keywordIndex, String keyword) {
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
        int aliasStart = skipWhitespace(sql, relationEnd);
        if (startsKeyword(sql, aliasStart, "AS")) {
            aliasStart = skipWhitespace(sql, aliasStart + "AS".length());
        }
        IdentifierToken alias = readIdentifierToken(sql, aliasStart);
        if (alias == null || isSqlClauseKeyword(alias.text())) {
            return null;
        }
        return new TableAlias(aliasStart, alias.endIndex(), alias.text());
    }

    private void collectDamengKeywordAliasReferences(
            String sql,
            List<TextReplacement> replacements,
            Set<String> aliases
    ) {
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
            } else {
                IdentifierToken identifier = readIdentifierToken(sql, index);
                if (identifier == null) {
                    index++;
                    continue;
                }
                String unquoted = unquoteIdentifier(identifier.text());
                int afterIdentifier = skipWhitespace(sql, identifier.endIndex());
                if (!identifier.text().startsWith("\"")
                        && aliases.contains(unquoted.toUpperCase(Locale.ROOT))
                        && afterIdentifier < sql.length()
                        && sql.charAt(afterIdentifier) == '.') {
                    replacements.add(new TextReplacement(
                            index,
                            identifier.endIndex(),
                            quoteDamengIdentifier(unquoted)
                    ));
                }
                index = identifier.endIndex();
            }
        }
    }

    private GenericConversion applyTextReplacements(String sql, List<TextReplacement> replacements) {
        if (replacements.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        replacements.sort((left, right) -> Integer.compare(left.startIndex(), right.startIndex()));
        StringBuilder converted = new StringBuilder(sql.length());
        int lastCopiedIndex = 0;
        boolean changed = false;
        for (TextReplacement replacement : replacements) {
            if (replacement.startIndex() < lastCopiedIndex) {
                continue;
            }
            converted.append(sql, lastCopiedIndex, replacement.startIndex());
            converted.append(replacement.replacement());
            lastCopiedIndex = replacement.endIndex();
            changed = true;
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        converted.append(sql, lastCopiedIndex, sql.length());
        return new GenericConversion(converted.toString(), true);
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

    private GenericConversion convertMysqlUpdateOrderLimitOne(String sql) {
        int updateIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, updateIndex, "UPDATE")) {
            return GenericConversion.unchanged(sql);
        }
        int setIndex = findTopLevelKeyword(sql, "SET", updateIndex + "UPDATE".length());
        if (setIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int whereIndex = findTopLevelKeyword(sql, "WHERE", setIndex + "SET".length());
        if (whereIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int orderIndex = findTopLevelKeyword(sql, "ORDER", whereIndex + "WHERE".length());
        if (orderIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int byIndex = skipWhitespace(sql, orderIndex + "ORDER".length());
        if (!startsKeyword(sql, byIndex, "BY")) {
            return GenericConversion.unchanged(sql);
        }
        int limitIndex = findTopLevelKeyword(sql, "LIMIT", byIndex + "BY".length());
        if (limitIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int statementEnd = stripTrailingSemicolon(sql);
        if (!isOnlyLimitOne(sql.substring(limitIndex + "LIMIT".length(), statementEnd))) {
            return GenericConversion.unchanged(sql);
        }

        int tableStart = skipWhitespace(sql, updateIndex + "UPDATE".length());
        IdentifierToken table = readQualifiedIdentifierToken(sql, tableStart);
        if (table == null || skipWhitespace(sql, table.endIndex()) != setIndex) {
            return GenericConversion.unchanged(sql);
        }
        String tableName = table.text();
        String setClause = sql.substring(setIndex + "SET".length(), whereIndex).trim();
        String whereClause = sql.substring(whereIndex + "WHERE".length(), orderIndex).trim();
        String orderClause = sql.substring(byIndex + "BY".length(), limitIndex).trim();
        if (setClause.isBlank() || whereClause.isBlank() || orderClause.isBlank()) {
            return GenericConversion.unchanged(sql);
        }

        StringBuilder converted = new StringBuilder(sql.length() + whereClause.length() + orderClause.length() + 80);
        converted.append(sql, 0, updateIndex)
                .append("update ")
                .append(tableName)
                .append(" set ")
                .append(setClause)
                .append(" where ROWID in (select rid from (select ROWID rid from ")
                .append(tableName)
                .append(" where ")
                .append(whereClause)
                .append(" order by ")
                .append(orderClause)
                .append(") where ROWNUM <= 1)")
                .append(sql.substring(statementEnd));
        return new GenericConversion(converted.toString(), true);
    }

    private boolean isOnlyLimitOne(String limitTail) {
        return "1".equals(limitTail == null ? "" : limitTail.trim());
    }

    private GenericConversion convertImplicitCrossJoins(String sql) {
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
                KeywordReplacement replacement = readImplicitCrossJoin(sql, index);
                if (replacement == null) {
                    index++;
                } else {
                    converted.append(sql, lastCopiedIndex, replacement.startIndex());
                    converted.append("CROSS JOIN");
                    lastCopiedIndex = replacement.endIndex();
                    index = replacement.endIndex();
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

    private KeywordReplacement readImplicitCrossJoin(String sql, int joinIndex) {
        int rewriteStart = implicitCrossJoinRewriteStart(sql, joinIndex);
        if (rewriteStart < 0) {
            return null;
        }
        int relationStart = skipWhitespace(sql, joinIndex + "JOIN".length());
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
        int sourceEnd = readOptionalTableAliasEnd(sql, relationEnd);
        int afterSource = skipWhitespace(sql, sourceEnd);
        if (startsKeyword(sql, afterSource, "ON") || startsKeyword(sql, afterSource, "USING")) {
            return null;
        }
        if (afterSource >= sql.length()
                || sql.charAt(afterSource) == ';'
                || startsJoinBoundary(sql, afterSource)
                || startsSqlClauseBoundary(sql, afterSource)) {
            return new KeywordReplacement(rewriteStart, joinIndex + "JOIN".length());
        }
        return null;
    }

    private int implicitCrossJoinRewriteStart(String sql, int joinIndex) {
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

    private boolean startsJoinBoundary(String sql, int index) {
        return startsKeyword(sql, index, "JOIN")
                || startsKeyword(sql, index, "INNER")
                || startsKeyword(sql, index, "LEFT")
                || startsKeyword(sql, index, "RIGHT")
                || startsKeyword(sql, index, "FULL")
                || startsKeyword(sql, index, "CROSS")
                || startsKeyword(sql, index, "NATURAL");
    }

    private boolean startsSqlClauseBoundary(String sql, int index) {
        return startsKeyword(sql, index, "WHERE")
                || startsKeyword(sql, index, "GROUP")
                || startsKeyword(sql, index, "ORDER")
                || startsKeyword(sql, index, "HAVING")
                || startsKeyword(sql, index, "LIMIT")
                || startsKeyword(sql, index, "OFFSET")
                || startsKeyword(sql, index, "FETCH")
                || startsKeyword(sql, index, "UNION");
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
                KeywordReplacement hint = readMysqlIndexHintRemoval(sql, index);
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

    private KeywordReplacement readMysqlIndexHintRemoval(String sql, int index) {
        String hintKeyword = mysqlIndexHintKeyword(sql, index);
        if (hintKeyword == null) {
            return null;
        }
        int cursor = skipWhitespace(sql, index + hintKeyword.length());
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

    private String mysqlIndexHintKeyword(String sql, int index) {
        for (String keyword : List.of("FORCE", "USE", "IGNORE")) {
            if (startsKeyword(sql, index, keyword)) {
                return keyword;
            }
        }
        return null;
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
        if ("PERCENT".equals(upper)) {
            return quoteDamengIdentifier(identifier);
        }
        if ("DESC".equals(upper) && previousNonWhitespace(sql, startIndex) == '.') {
            return "\"DESC\"";
        }
        if ("DISTINCT".equals(upper) && previousNonWhitespace(sql, startIndex) == ',') {
            return quoteDamengIdentifier(identifier);
        }
        if ("REVERSE".equals(upper) && nextNonWhitespace(sql, startIndex + identifier.length()) != '(') {
            return quoteDamengIdentifier(identifier);
        }
        if (previousNonWhitespace(sql, startIndex) == '.' && isDamengKeywordRequiringQuotes(identifier)) {
            return quoteDamengIdentifier(identifier);
        }
        return identifier;
    }

    private GenericConversion convertHavingAggregateAliases(String sql) {
        int selectIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, selectIndex, "SELECT")) {
            return GenericConversion.unchanged(sql);
        }
        int fromIndex = findTopLevelKeyword(sql, "FROM", selectIndex + "SELECT".length());
        if (fromIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int havingIndex = findTopLevelKeyword(sql, "HAVING", fromIndex + "FROM".length());
        if (havingIndex < 0) {
            return GenericConversion.unchanged(sql);
        }

        Map<String, String> aggregateAliases = aggregateSelectAliases(
                sql.substring(selectIndex + "SELECT".length(), fromIndex)
        );
        if (aggregateAliases.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }

        int havingStart = havingIndex + "HAVING".length();
        int havingEnd = firstTopLevelKeyword(
                sql,
                havingStart,
                "ORDER",
                "LIMIT",
                "OFFSET",
                "FETCH",
                "UNION"
        );
        if (havingEnd < 0) {
            havingEnd = sql.length();
        }

        List<TextReplacement> replacements = new ArrayList<>();
        int index = havingStart;
        while (index < havingEnd) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '"' || isIdentifierStart(current)) {
                IdentifierToken token = readIdentifierToken(sql, index);
                if (token == null) {
                    index++;
                    continue;
                }
                String expression = aggregateAliases.get(identifierKey(token.text()));
                if (expression != null && previousNonWhitespace(sql, index) != '.') {
                    replacements.add(new TextReplacement(index, token.endIndex(), "(" + expression + ")"));
                }
                index = token.endIndex();
            } else {
                index++;
            }
        }
        return applyTextReplacements(sql, replacements);
    }

    private Map<String, String> aggregateSelectAliases(String selectList) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (TopLevelArgument item : splitTopLevelArguments(selectList)) {
            AggregateAlias aggregateAlias = aggregateSelectAlias(item.text());
            if (aggregateAlias != null) {
                aliases.putIfAbsent(identifierKey(aggregateAlias.alias()), aggregateAlias.expression());
            }
        }
        return aliases;
    }

    private AggregateAlias aggregateSelectAlias(String selectItem) {
        String trimmed = selectItem == null ? "" : selectItem.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?is)^(.+?)\\s+(?:AS\\s+)?(" + DM_IDENTIFIER + ")\\s*$")
                .matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        String expression = matcher.group(1).trim();
        String alias = matcher.group(2).trim();
        if (expression.isBlank() || alias.isBlank() || isSqlClauseKeyword(alias)) {
            return null;
        }
        if (!containsAggregateFunction(expression)) {
            return null;
        }
        return new AggregateAlias(expression, alias);
    }

    private boolean containsAggregateFunction(String sql) {
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
            } else if (startsFunction(sql, index, "SUM")
                    || startsFunction(sql, index, "COUNT")
                    || startsFunction(sql, index, "AVG")
                    || startsFunction(sql, index, "MIN")
                    || startsFunction(sql, index, "MAX")
                    || startsFunction(sql, index, "LISTAGG")) {
                return true;
            } else {
                index++;
            }
        }
        return false;
    }

    private GenericConversion convertNotFindInSet(String sql) {
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
            } else if (current == '!') {
                int functionStart = skipWhitespace(sql, index + 1);
                FunctionCall functionCall = readFunctionCall(sql, functionStart, "FIND_IN_SET");
                if (functionCall == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(sql, functionStart, functionCall.endIndex()).append(" = 0");
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

    private GenericConversion convertNotIsNull(String sql) {
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
            } else if (current == '!') {
                int functionStart = skipWhitespace(sql, index + 1);
                FunctionCall functionCall = readFunctionCall(sql, functionStart, "ISNULL");
                if (functionCall == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append("CASE WHEN ")
                            .append(sql, functionStart, functionCall.endIndex())
                            .append(" THEN 0 ELSE 1 END");
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

    private GenericConversion convertCountConditionOrNull(String sql) {
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
            } else if (startsFunction(sql, index, "COUNT")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "COUNT");
                String replacement = functionCall == null ? null : rewriteCountConditionOrNull(functionCall);
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

    private String rewriteCountConditionOrNull(FunctionCall countCall) {
        String body = countCall.body();
        int orIndex = findTopLevelKeyword(body, "OR", 0);
        if (orIndex < 0) {
            return null;
        }
        String condition = body.substring(0, orIndex).trim();
        String right = body.substring(orIndex + "OR".length()).trim();
        if (condition.isBlank() || !"NULL".equalsIgnoreCase(right)) {
            return null;
        }
        return "COUNT(CASE WHEN " + condition + " THEN 1 END)";
    }

    private GenericConversion convertCountDistinctIfToCase(String sql) {
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
            } else if (startsFunction(sql, index, "COUNT")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "COUNT");
                String replacement = functionCall == null ? null : rewriteCountDistinctIfToCase(functionCall);
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

    private String rewriteCountDistinctIfToCase(FunctionCall countCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(countCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String first = arguments.get(0).text().trim();
        int firstStart = leadingWhitespaceLength(first);
        if (!startsKeyword(first, firstStart, "DISTINCT")) {
            return null;
        }
        String distinctExpression = first.substring(firstStart + "DISTINCT".length()).trim();
        if (distinctExpression.isBlank()) {
            return null;
        }
        String second = arguments.get(1).text().trim();
        FunctionCall ifCall = readFunctionCall(second, leadingWhitespaceLength(second), "IF");
        if (ifCall == null || skipWhitespace(second, ifCall.endIndex()) != second.length()) {
            return null;
        }
        List<TopLevelArgument> ifArguments = splitTopLevelArguments(ifCall.body());
        if (ifArguments.size() != 3) {
            return null;
        }
        String condition = ifArguments.get(0).text().trim();
        String whenTrue = ifArguments.get(1).text().trim();
        String whenFalse = ifArguments.get(2).text().trim();
        if (condition.isBlank()
                || !("TRUE".equalsIgnoreCase(whenTrue) || "1".equals(whenTrue))
                || !"NULL".equalsIgnoreCase(whenFalse)) {
            return null;
        }
        return "COUNT(DISTINCT CASE WHEN " + condition + " THEN " + distinctExpression + " ELSE NULL END)";
    }

    private GenericConversion convertBooleanOperatorsInIfConditions(String sql) {
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
            } else if (startsFunction(sql, index, "IF")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "IF");
                String replacement = functionCall == null ? null : rewriteIfBooleanOperators(functionCall);
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

    private String rewriteIfBooleanOperators(FunctionCall ifCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(ifCall.body());
        if (arguments.size() != 3) {
            return null;
        }
        GenericConversion condition = convertBooleanOperators(arguments.get(0).text());
        if (!condition.changed()) {
            return null;
        }
        return "IF(" + condition.convertedSql().trim()
                + ", " + arguments.get(1).text().trim()
                + ", " + arguments.get(2).text().trim()
                + ")";
    }

    private GenericConversion convertMysqlIfToCase(String sql) {
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
            } else if (startsFunction(sql, index, "IF")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "IF");
                String replacement = functionCall == null ? null : rewriteMysqlIfToCase(functionCall);
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

    private String rewriteMysqlIfToCase(FunctionCall ifCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(ifCall.body());
        if (arguments.size() != 3) {
            return null;
        }
        String condition = convertMysqlIfToCase(arguments.get(0).text()).convertedSql().trim();
        String whenTrue = convertMysqlIfToCase(arguments.get(1).text()).convertedSql().trim();
        String whenFalse = convertMysqlIfToCase(arguments.get(2).text()).convertedSql().trim();
        if (condition.isBlank() || whenTrue.isBlank() || whenFalse.isBlank()) {
            return null;
        }
        return "CASE WHEN " + condition + " THEN " + whenTrue + " ELSE " + whenFalse + " END";
    }

    private GenericConversion convertBooleanOperators(String expression) {
        StringBuilder converted = new StringBuilder(expression.length());
        boolean changed = false;
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (current == '\'') {
                index = appendSingleQuotedString(expression, index, converted);
            } else if (current == '"') {
                index = appendDoubleQuotedText(expression, index, converted);
            } else if (startsMyBatisPlaceholder(expression, index)) {
                index = appendMyBatisPlaceholder(expression, index, converted);
            } else if (startsLineComment(expression, index)) {
                index = appendUntilLineEnd(expression, index, converted);
            } else if (startsBlockComment(expression, index)) {
                index = appendUntilBlockCommentEnd(expression, index, converted);
            } else if (index + 1 < expression.length()
                    && expression.charAt(index) == '|'
                    && expression.charAt(index + 1) == '|') {
                converted.append("OR");
                index += 2;
                changed = true;
            } else if (index + 1 < expression.length()
                    && expression.charAt(index) == '&'
                    && expression.charAt(index + 1) == '&') {
                converted.append("AND");
                index += 2;
                changed = true;
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : expression, changed);
    }

    private GenericConversion convertBooleanLiteralComparisons(String sql) {
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
            } else {
                BooleanLiteralComparison comparison = readBooleanLiteralComparison(sql, index);
                if (comparison == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(comparison.replacement());
                    index = comparison.endIndex();
                    changed = true;
                }
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private BooleanLiteralComparison readBooleanLiteralComparison(String sql, int index) {
        if (index > 0 && isIdentifierPart(sql.charAt(index - 1))) {
            return null;
        }
        QualifiedIdentifier identifier = readQualifiedIdentifier(sql, index);
        if (identifier == null || !isLikelyBooleanColumn(identifier.lastToken())) {
            return null;
        }
        int operatorStart = skipWhitespace(sql, identifier.endIndex());
        String operator = readComparisonOperator(sql, operatorStart);
        if (operator.isBlank()) {
            return null;
        }
        int literalStart = skipWhitespace(sql, operatorStart + operator.length());
        String literalValue;
        int literalEnd;
        if (startsKeyword(sql, literalStart, "TRUE")) {
            literalValue = "1";
            literalEnd = literalStart + "TRUE".length();
        } else if (startsKeyword(sql, literalStart, "FALSE")) {
            literalValue = "0";
            literalEnd = literalStart + "FALSE".length();
        } else {
            return null;
        }
        return new BooleanLiteralComparison(
                literalEnd,
                sql.substring(index, literalStart) + literalValue
        );
    }

    private String readComparisonOperator(String sql, int index) {
        if (index >= sql.length()) {
            return "";
        }
        if (sql.startsWith("<>", index) || sql.startsWith("!=", index)) {
            return sql.substring(index, index + 2);
        }
        if (sql.charAt(index) == '=') {
            return "=";
        }
        return "";
    }

    private QualifiedIdentifier readQualifiedIdentifier(String sql, int index) {
        IdentifierToken token = readIdentifierToken(sql, index);
        if (token == null) {
            return null;
        }
        IdentifierToken lastToken = token;
        int end = skipWhitespace(sql, token.endIndex());
        while (end < sql.length() && sql.charAt(end) == '.') {
            end = skipWhitespace(sql, end + 1);
            token = readIdentifierToken(sql, end);
            if (token == null) {
                return null;
            }
            lastToken = token;
            end = skipWhitespace(sql, token.endIndex());
        }
        return new QualifiedIdentifier(unquoteIdentifier(lastToken.text()), end);
    }

    private GenericConversion convertBareBooleanPredicates(String sql) {
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
            } else {
                BareBooleanPredicate predicate = readBareBooleanPredicate(sql, index);
                if (predicate == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(sql, index, predicate.identifierEndIndex());
                    converted.append(" = 1");
                    index = predicate.identifierEndIndex();
                    changed = true;
                }
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private BareBooleanPredicate readBareBooleanPredicate(String sql, int keywordIndex) {
        String keyword = conditionConnectorKeyword(sql, keywordIndex);
        if (keyword == null) {
            return null;
        }
        int index = skipWhitespace(sql, keywordIndex + keyword.length());
        if (index >= sql.length()
                || startsKeyword(sql, index, "NOT")
                || startsKeyword(sql, index, "EXISTS")) {
            return null;
        }

        IdentifierToken token = readIdentifierToken(sql, index);
        if (token == null) {
            return null;
        }
        IdentifierToken lastToken = token;
        int end = skipWhitespace(sql, token.endIndex());
        while (end < sql.length() && sql.charAt(end) == '.') {
            end = skipWhitespace(sql, end + 1);
            token = readIdentifierToken(sql, end);
            if (token == null) {
                return null;
            }
            lastToken = token;
            end = skipWhitespace(sql, token.endIndex());
        }
        if (!isLikelyBooleanColumn(lastToken.text())
                || !isBareBooleanPredicateBoundary(sql, end)) {
            return null;
        }
        return new BareBooleanPredicate(lastToken.endIndex());
    }

    private String conditionConnectorKeyword(String sql, int index) {
        for (String keyword : List.of("WHERE", "AND", "OR", "ON")) {
            if (startsKeyword(sql, index, keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private boolean isLikelyBooleanColumn(String identifier) {
        String unquoted = unquoteIdentifier(identifier);
        String lower = unquoted.toLowerCase(Locale.ROOT);
        if (lower.startsWith("is_") || lower.startsWith("has_")) {
            return true;
        }
        if (lower.equals("isdelete")
                || lower.equals("isdeleted")
                || lower.equals("isvalid")
                || lower.equals("isenabled")
                || lower.equals("isactive")) {
            return true;
        }
        if (unquoted.length() > 2
                && unquoted.startsWith("is")
                && Character.isUpperCase(unquoted.charAt(2))) {
            return true;
        }
        return lower.endsWith("_flag") || lower.endsWith("flag");
    }

    private boolean isBareBooleanPredicateBoundary(String sql, int index) {
        int boundary = skipWhitespace(sql, index);
        if (boundary >= sql.length()) {
            return true;
        }
        char current = sql.charAt(boundary);
        if (current == ')' || current == ';') {
            return true;
        }
        return startsKeyword(sql, boundary, "AND")
                || startsKeyword(sql, boundary, "OR")
                || startsKeyword(sql, boundary, "GROUP")
                || startsKeyword(sql, boundary, "ORDER")
                || startsKeyword(sql, boundary, "HAVING")
                || startsKeyword(sql, boundary, "LIMIT")
                || startsKeyword(sql, boundary, "FETCH")
                || startsKeyword(sql, boundary, "UNION");
    }

    private int firstTopLevelKeyword(String sql, int start, String... keywords) {
        int result = -1;
        for (String keyword : keywords) {
            int index = findTopLevelKeyword(sql, keyword, start);
            if (index >= 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
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

        String separator = "','";
        if (separatorIndex >= 0) {
            separator = normalizedStringLiteral(body.substring(separatorIndex + "SEPARATOR".length()));
            if (separator == null) {
                return null;
            }
        } else if (!orderBy.isBlank()) {
            GroupConcatOrderBy orderByWithoutSeparator = extractTrailingGroupConcatSeparator(orderBy);
            if (orderByWithoutSeparator != null) {
                orderBy = orderByWithoutSeparator.orderBy();
                separator = orderByWithoutSeparator.separator();
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
        List<TopLevelArgument> expressionArguments = splitTopLevelArguments(expression);
        if (expressionArguments.isEmpty()) {
            return null;
        }
        String listaggExpression = expressionArguments.size() == 1
                ? expression
                : concatenateArguments(expressionArguments);
        if (listaggExpression == null) {
            return null;
        }

        if (orderBy.isBlank()) {
            orderBy = listaggExpression;
        }
        return "LISTAGG(" + (distinct ? "DISTINCT " : "") + listaggExpression + ", " + separator + ") WITHIN GROUP (ORDER BY " + orderBy + ")";
    }

    private GroupConcatOrderBy extractTrailingGroupConcatSeparator(String orderBy) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(orderBy);
        if (arguments.size() < 2) {
            return null;
        }
        String separator = normalizedStringLiteral(arguments.get(arguments.size() - 1).text());
        if (separator == null) {
            return null;
        }
        int orderByEnd = skipWhitespaceBackward(orderBy, arguments.get(arguments.size() - 1).startIndex());
        if (orderByEnd <= 0 || orderBy.charAt(orderByEnd - 1) != ',') {
            return null;
        }
        String trimmedOrderBy = orderBy.substring(0, orderByEnd - 1).trim();
        if (trimmedOrderBy.isBlank()) {
            return null;
        }
        return new GroupConcatOrderBy(trimmedOrderBy, separator);
    }

    private GenericConversion convertConcat(String sql) {
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
            } else if (startsFunction(sql, index, "CONCAT")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "CONCAT");
                String replacement = functionCall == null ? null : rewriteConcat(functionCall);
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

    private String rewriteConcat(FunctionCall concatCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(concatCall.body());
        if (arguments.isEmpty()) {
            return null;
        }
        return concatenateArguments(arguments);
    }

    private String concatenateArguments(List<TopLevelArgument> arguments) {
        StringBuilder expression = new StringBuilder();
        for (TopLevelArgument argument : arguments) {
            String text = argument.text().trim();
            if (text.isBlank() || text.contains("${")) {
                return null;
            }
            GenericConversion nestedConcatConversion = convertConcat(text);
            if (nestedConcatConversion.changed()) {
                text = nestedConcatConversion.convertedSql().trim();
            }
            if (!expression.isEmpty()) {
                expression.append(" || ");
            }
            expression.append("(").append(text).append(")");
        }
        return expression.toString();
    }

    private GenericConversion convertLikePlaceholderLiteralConcatenation(String sql) {
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
            } else if (startsKeyword(sql, index, "LIKE")) {
                LikePlaceholderLiteralConcat concat = readLikePlaceholderLiteralConcat(sql, index);
                if (concat == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(sql, index, concat.operandStartIndex());
                    converted.append(concat.replacement());
                    index = concat.endIndex();
                    changed = true;
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private LikePlaceholderLiteralConcat readLikePlaceholderLiteralConcat(String sql, int likeIndex) {
        int operandStart = skipWhitespace(sql, likeIndex + "LIKE".length());
        if (sql.startsWith("#{", operandStart)) {
            int placeholderEnd = skipMyBatisPlaceholder(sql, operandStart);
            int literalStart = skipWhitespace(sql, placeholderEnd);
            SingleQuotedStringLiteral literal = readClosedSingleQuotedStringLiteral(sql, literalStart);
            if (literal == null) {
                return null;
            }
            String placeholder = sql.substring(operandStart, placeholderEnd);
            String literalText = sql.substring(literalStart, literal.nextIndex());
            return new LikePlaceholderLiteralConcat(
                    operandStart,
                    literal.nextIndex(),
                    "(" + placeholder + ") || (" + literalText + ")"
            );
        }
        SingleQuotedStringLiteral literal = readClosedSingleQuotedStringLiteral(sql, operandStart);
        if (literal == null) {
            return null;
        }
        int placeholderStart = skipWhitespace(sql, literal.nextIndex());
        if (!sql.startsWith("#{", placeholderStart)) {
            return null;
        }
        int placeholderEnd = skipMyBatisPlaceholder(sql, placeholderStart);
        String literalText = sql.substring(operandStart, literal.nextIndex());
        String placeholder = sql.substring(placeholderStart, placeholderEnd);
        return new LikePlaceholderLiteralConcat(
                operandStart,
                placeholderEnd,
                "(" + literalText + ") || (" + placeholder + ")"
        );
    }

    private SingleQuotedStringLiteral readClosedSingleQuotedStringLiteral(String sql, int start) {
        if (start >= sql.length() || sql.charAt(start) != '\'') {
            return null;
        }
        SingleQuotedStringLiteral literal = readSingleQuotedStringLiteral(sql, start);
        return literal.closed() ? literal : null;
    }

    private GenericConversion convertSubstringIndex(String sql) {
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
            } else if (startsFunction(sql, index, "SUBSTRING_INDEX")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "SUBSTRING_INDEX");
                String replacement = functionCall == null ? null : rewriteSubstringIndex(functionCall);
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

    private String rewriteSubstringIndex(FunctionCall substringIndexCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(substringIndexCall.body());
        if (arguments.size() != 3) {
            return null;
        }
        String source = arguments.get(0).text().trim();
        String delimiter = stringLiteralValue(arguments.get(1).text());
        String count = arguments.get(2).text().trim();
        if (source.isBlank() || delimiter == null || delimiter.length() != 1) {
            return null;
        }
        String regex;
        if ("1".equals(count)) {
            regex = "[^" + escapeRegexCharacterClassChar(delimiter.charAt(0)) + "]+";
        } else if ("-1".equals(count)) {
            regex = "[^" + escapeRegexCharacterClassChar(delimiter.charAt(0)) + "]+$";
        } else {
            return null;
        }
        StringBuilder regexLiteral = new StringBuilder();
        appendSingleQuotedStringLiteral(regexLiteral, regex);
        return "REGEXP_SUBSTR(" + source + ", " + regexLiteral + ", 1, 1)";
    }

    private String stringLiteralValue(String expression) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.charAt(0) == '\'') {
            SingleQuotedStringLiteral literal = readSingleQuotedStringLiteral(trimmed, 0);
            return literal.closed() && literal.nextIndex() == trimmed.length() ? literal.value() : null;
        }
        if (trimmed.charAt(0) == '"') {
            DoubleQuotedStringLiteral literal = readDoubleQuotedStringLiteral(trimmed, 0);
            return literal.closed() && literal.nextIndex() == trimmed.length() ? literal.value() : null;
        }
        return null;
    }

    private String escapeRegexCharacterClassChar(char value) {
        return switch (value) {
            case '\\' -> "\\\\";
            case '^', '-', ']' -> "\\" + value;
            default -> Character.toString(value);
        };
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
                    converted.append(toDamengBacktickIdentifier(identifier.value()));
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

    private String toDamengBacktickIdentifier(String identifier) {
        if (containsMyBatisPlaceholder(identifier) || needsQuotedCasePreservation(identifier)) {
            return quoteDamengIdentifier(identifier);
        }
        return toDamengIdentifier(identifier);
    }

    private boolean containsMyBatisPlaceholder(String value) {
        return value.contains("${") || value.contains("#{");
    }

    private boolean needsQuotedCasePreservation(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isUpperCase(value.charAt(i))) {
                return true;
            }
        }
        return false;
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

    private char nextNonWhitespace(String sql, int afterIndex) {
        int index = afterIndex;
        while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
            index++;
        }
        return index < sql.length() ? sql.charAt(index) : '\0';
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
        if (commaMatcher.matches() && startsWithSelectOrSelectContinuationFragment(commaMatcher.group("base"))) {
            String base = stripTrailingWhitespace(commaMatcher.group("base"));
            String offset = commaMatcher.group("offset");
            String size = commaMatcher.group("size");
            return LimitConversion.converted(
                    base + " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY",
                    "LIMIT_OFFSET_TO_DM_FETCH"
            );
        }

        Matcher offsetMatcher = LIMIT_OFFSET_PATTERN.matcher(sql);
        if (offsetMatcher.matches() && startsWithSelectOrSelectContinuationFragment(offsetMatcher.group("base"))) {
            String base = stripTrailingWhitespace(offsetMatcher.group("base"));
            String offset = offsetMatcher.group("offset");
            String size = offsetMatcher.group("size");
            return LimitConversion.converted(
                    base + " OFFSET " + offset + " ROWS FETCH NEXT " + size + " ROWS ONLY",
                    "LIMIT_OFFSET_TO_DM_FETCH"
            );
        }

        Matcher sizeMatcher = LIMIT_SIZE_PATTERN.matcher(sql);
        if (sizeMatcher.matches() && startsWithSelectOrSelectContinuationFragment(sizeMatcher.group("base"))) {
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

    private boolean startsWithSelectOrSelectContinuationFragment(String sql) {
        if (startsWithSelect(sql)) {
            return true;
        }
        String lower = sql.stripLeading().toLowerCase(Locale.ROOT);
        return lower.startsWith("from")
                && (lower.length() == "from".length() || Character.isWhitespace(lower.charAt("from".length())));
    }

    private String stripTrailingWhitespace(String value) {
        return value.replaceFirst("\\s+$", "");
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

    private record TextReplacement(int startIndex, int endIndex, String replacement) {
    }

    private record NumericTypeRewrite(int endIndex, String replacement) {
    }

    private record BooleanLiteralComparison(int endIndex, String replacement) {
    }

    private record QualifiedIdentifier(String lastToken, int endIndex) {
    }

    private record BareBooleanPredicate(int identifierEndIndex) {
    }

    private record TableAlias(int startIndex, int endIndex, String text) {
    }

    private record WordToken(int startIndex, int endIndex, String text) {
    }

    private record Operand(int startIndex, int endIndex, String text) {
    }

    private record AliasAsRemoval(int asIndex, int aliasStartIndex, int aliasEndIndex) {
    }

    private record JoinSource(String sourceSql, String conditionSql) {
    }

    private record StatementSegment(String sql, String separator) {
    }

    private record IdentifierName(String text, String key) {
    }

    private record AggregateAlias(String expression, String alias) {
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

    private record GroupConcatOrderBy(String orderBy, String separator) {
    }

    private record LikePlaceholderLiteralConcat(int operandStartIndex, int endIndex, String replacement) {
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
