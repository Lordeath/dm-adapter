package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MySqlToDmSqlConverter implements SqlConverter {
    public static final String MYSQL_BACKTICK_IDENTIFIER_RULE = "MYSQL_BACKTICK_IDENTIFIER_TO_DM";
    public static final String UPDATE_SET_TABLE_ORDER_RULE = "UPDATE_SET_TABLE_ORDER_TO_STANDARD_UPDATE";
    public static final String MYSQL_DATE_SUB_INTERVAL_RULE = "MYSQL_DATE_SUB_INTERVAL_TO_DATEADD";
    public static final String MYSQL_DATE_SUB_NOW_DAY_RULE = MYSQL_DATE_SUB_INTERVAL_RULE;
    public static final String MYSQL_CURRENT_SCHEMA_FUNCTION_RULE = "MYSQL_CURRENT_SCHEMA_FUNCTION_TO_DM";
    public static final String MYSQL_REGEXP_OPERATOR_RULE = "MYSQL_REGEXP_OPERATOR_TO_REGEXP_LIKE";
    public static final String MYSQL_NULL_SAFE_EQUAL_RULE = "MYSQL_NULL_SAFE_EQUAL_TO_DM";
    public static final String MYSQL_CAST_UNSIGNED_RULE = "MYSQL_CAST_UNSIGNED_TO_BIGINT";
    public static final String MYSQL_CAST_SIGNED_RULE = "MYSQL_CAST_SIGNED_TO_BIGINT";
    public static final String MYSQL_CONVERT_UNSIGNED_RULE = "MYSQL_CONVERT_UNSIGNED_TO_BIGINT";
    public static final String MYSQL_DATE_ADD_INTERVAL_RULE = "MYSQL_DATE_ADD_INTERVAL_TO_DATEADD";
    public static final String MYSQL_NUMERIC_IFNULL_COMPARISON_TO_NUMBER_RULE =
            "MYSQL_NUMERIC_IFNULL_COMPARISON_TO_NUMBER";
    @Deprecated
    public static final String MYSQL_NUMERIC_IFNULL_COMPARISON_CAST_RULE =
            MYSQL_NUMERIC_IFNULL_COMPARISON_TO_NUMBER_RULE;
    public static final String MYSQL_SUBDATE_RULE = "MYSQL_SUBDATE_TO_DATEADD";
    public static final String MYSQL_LOCATE_NUMERIC_NEEDLE_RULE = "MYSQL_LOCATE_NUMERIC_NEEDLE_CAST";
    public static final String MYSQL_MAKEDATE_RULE = "MYSQL_MAKEDATE_TO_DATEADD";
    public static final String MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE =
            "MYSQL_INFORMATION_SCHEMA_COLUMNS_TO_ALL_TAB_COLUMNS";
    public static final String MYSQL_INFORMATION_SCHEMA_TABLES_RULE =
            "MYSQL_INFORMATION_SCHEMA_TABLES_TO_ALL_TABLES";
    public static final String MYSQL_INFORMATION_SCHEMA_STATISTICS_RULE =
            "MYSQL_INFORMATION_SCHEMA_STATISTICS_TO_ALL_INDEXES";
    public static final String MYSQL_DESCRIBE_TABLE_RULE = "MYSQL_DESCRIBE_TABLE_TO_USER_TAB_COLUMNS";
    public static final String MYSQL_INSERT_IGNORE_TO_DM_MERGE_RULE = "MYSQL_INSERT_IGNORE_TO_DM_MERGE";
    public static final String MYSQL_WITH_RECURSIVE_ALIAS_RULE = "MYSQL_WITH_RECURSIVE_COLUMN_ALIAS";
    public static final String MYSQL_UPDATE_JOIN_RULE = "MYSQL_UPDATE_JOIN_TO_DM_UPDATE_FROM";
    public static final String MYSQL_TABLE_ALIAS_AS_RULE = "MYSQL_TABLE_ALIAS_AS_TO_DM";
    public static final String MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE = "MYSQL_GROUP_CONCAT_TO_DM_LISTAGG";
    public static final String MYSQL_HIERARCHY_USER_VARIABLE_TO_DM_CONNECT_BY_RULE =
            "MYSQL_HIERARCHY_USER_VARIABLE_TO_DM_CONNECT_BY";
    public static final String MYSQL_CONCAT_TO_DM_OPERATOR_RULE = "MYSQL_CONCAT_TO_DM_OPERATOR";
    public static final String MYSQL_SINGLE_ARGUMENT_CONCAT_RULE = "MYSQL_SINGLE_ARGUMENT_CONCAT_TO_EXPRESSION";
    public static final String MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT_RULE =
            "MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT";
    public static final String MYSQL_SUBSTRING_INDEX_TO_REGEXP_SUBSTR_RULE =
            "MYSQL_SUBSTRING_INDEX_TO_REGEXP_SUBSTR";
    public static final String MYSQL_HELP_TOPIC_SPLIT_TO_CROSS_APPLY_RULE =
            "MYSQL_HELP_TOPIC_SPLIT_TO_DM_CROSS_APPLY";
    public static final String MYSQL_HAVING_AGGREGATE_ALIAS_RULE = "MYSQL_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION";
    public static final String MYSQL_NOT_FIND_IN_SET_RULE = "MYSQL_NOT_FIND_IN_SET_TO_EQUALS_ZERO";
    public static final String MYSQL_STR_TO_DATE_YEARMONTH_RULE = "MYSQL_STR_TO_DATE_YEARMONTH_TO_TO_DATE";
    public static final String MYSQL_PERIOD_DIFF_YEARMONTH_RULE = "MYSQL_PERIOD_DIFF_YEARMONTH_TO_DATEDIFF";
    public static final String MYSQL_YEARWEEK_RULE = "MYSQL_YEARWEEK_TO_DM_WEEK";
    public static final String MYSQL_DATEDIFF_2ARG_RULE = "MYSQL_DATEDIFF_2ARG_TO_DM_DATEDIFF";
    public static final String MYSQL_COUNT_CONDITION_OR_NULL_RULE = "MYSQL_COUNT_CONDITION_OR_NULL_TO_CASE";
    public static final String MYSQL_COUNT_DISTINCT_IF_TO_CASE_RULE = "MYSQL_COUNT_DISTINCT_IF_TO_CASE";
    public static final String MYSQL_IF_TO_CASE_RULE = "MYSQL_IF_TO_CASE";
    public static final String MYSQL_NOT_ISNULL_RULE = "MYSQL_NOT_ISNULL_TO_CASE";
    public static final String MYSQL_BOOLEAN_NULL_PROJECTION_RULE =
            "MYSQL_BOOLEAN_NULL_PROJECTION_TO_CASE";
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
    public static final String MYSQL_DELETE_JOIN_RULE = "MYSQL_DELETE_JOIN_TO_DM_ROWID";
    public static final String DAMENG_KEYWORD_TABLE_ALIAS_RULE = "DAMENG_KEYWORD_TABLE_ALIAS_QUOTE";
    public static final String MYSQL_SINGLE_QUOTED_ALIAS_RULE = "MYSQL_SINGLE_QUOTED_ALIAS_TO_DM_IDENTIFIER";
    public static final String MYSQL_NUMERIC_LEADING_SELECT_ALIAS_RULE =
            "MYSQL_NUMERIC_LEADING_SELECT_ALIAS_QUOTE";
    public static final String MYSQL_INSERT_VALUE_TO_VALUES_RULE = "MYSQL_INSERT_VALUE_TO_VALUES";
    public static final String MYSQL_INDEX_HINT_REMOVAL_RULE = "MYSQL_INDEX_HINT_REMOVED";
    public static final String SQLSERVER_NOLOCK_HINT_REMOVAL_RULE = "SQLSERVER_NOLOCK_HINT_REMOVED";
    public static final String SQLSERVER_TOP_TO_DM_FETCH_FIRST_RULE = "SQLSERVER_TOP_TO_DM_FETCH_FIRST";
    public static final String SQLSERVER_DBO_SCHEMA_REMOVAL_RULE = "SQLSERVER_DBO_SCHEMA_REMOVED";
    public static final String SQLSERVER_STRING_PLUS_TO_DM_CONCAT_RULE =
            "SQLSERVER_STRING_PLUS_TO_DM_CONCAT";
    public static final String SQLSERVER_CHARINDEX_TO_DM_INSTR_RULE =
            "SQLSERVER_CHARINDEX_TO_DM_INSTR";
    public static final String MYSQL_CONVERT_DECIMAL_RULE = "MYSQL_CONVERT_DECIMAL_TO_CAST";
    public static final String MYSQL_CONVERT_CHAR_RULE = "MYSQL_CONVERT_CHAR_TO_CAST";
    public static final String MYSQL_CONVERT_GBK_ORDER_RULE = "MYSQL_CONVERT_GBK_ORDER_TO_NLSSORT";
    public static final String MYSQL_SELECT_MODIFIER_REMOVAL_RULE = "MYSQL_SELECT_MODIFIER_REMOVED";
    public static final String MYSQL_UNUSED_USER_VARIABLE_SELECT_ITEM_RULE =
            "MYSQL_UNUSED_USER_VARIABLE_SELECT_ITEM_REMOVED";
    public static final String MYSQL_TRAILING_SEMICOLON_REMOVAL_RULE = "MYSQL_TRAILING_SEMICOLON_REMOVED";
    public static final String MYSQL_HASH_LINE_COMMENT_RULE = "MYSQL_HASH_LINE_COMMENT_TO_DM";
    public static final String MYSQL_BIT_LITERAL_RULE = "MYSQL_BIT_LITERAL_TO_NUMERIC";
    public static final String MYSQL_COLLATE_CLAUSE_REMOVAL_RULE = "MYSQL_COLLATE_CLAUSE_REMOVED";
    public static final String MYSQL_CHARACTER_SET_CLAUSE_REMOVAL_RULE = "MYSQL_CHARACTER_SET_CLAUSE_REMOVED";
    public static final String MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE = "MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY";
    public static final String MYSQL_IDENTITY_PRIMARY_KEY_REDUNDANT_UNIQUE_RULE =
            "MYSQL_IDENTITY_PRIMARY_KEY_REDUNDANT_UNIQUE_REMOVED";
    public static final String MYSQL_IDENTITY_INLINE_PRIMARY_KEY_RULE =
            "MYSQL_IDENTITY_INLINE_PRIMARY_KEY_TO_TABLE_CONSTRAINT";
    public static final String MYSQL_ALTER_AUTO_INCREMENT_RESET_RULE = "MYSQL_ALTER_AUTO_INCREMENT_RESET_TO_DM";
    public static final String MYSQL_CREATE_TABLE_OPTION_REMOVAL_RULE = "MYSQL_CREATE_TABLE_OPTIONS_REMOVED";
    public static final String MYSQL_USING_BTREE_REMOVAL_RULE = "MYSQL_USING_BTREE_REMOVED";
    public static final String MYSQL_CREATE_TABLE_KEY_REMOVAL_RULE = "MYSQL_CREATE_TABLE_KEY_REMOVED";
    public static final String MYSQL_GENERATED_COLUMN_STORED_REMOVAL_RULE =
            "MYSQL_GENERATED_COLUMN_STORED_REMOVED";
    public static final String MYSQL_NUMERIC_TYPE_ATTRIBUTE_RULE = "MYSQL_NUMERIC_TYPE_ATTRIBUTE_TO_DM";
    public static final String MYSQL_TEXT_TYPE_TO_DM_CLOB_RULE = "MYSQL_TEXT_TYPE_TO_DM_CLOB";
    public static final String MYSQL_DECIMAL_PRECISION_CAP_RULE = "MYSQL_DECIMAL_PRECISION_CAP_TO_DM";
    public static final String MYSQL_CREATE_TABLE_COLUMN_COMMENT_TO_DM_RULE =
            "MYSQL_CREATE_TABLE_COLUMN_COMMENT_TO_DM";
    /**
     * @deprecated Dameng accepts single-quoted inline column comments in {@code CREATE TABLE}.
     * Use {@link #MYSQL_CREATE_TABLE_COLUMN_COMMENT_TO_DM_RULE} for syntax normalization.
     */
    @Deprecated
    public static final String MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVAL_RULE =
            "MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVED";
    public static final String MYSQL_ON_UPDATE_TIMESTAMP_TO_DM_RULE = "MYSQL_ON_UPDATE_TIMESTAMP_TO_DM";
    /**
     * @deprecated Use {@link #MYSQL_ON_UPDATE_TIMESTAMP_TO_DM_RULE}. The conversion now preserves the
     * automatic-update semantics with Dameng's {@code ON UPDATE NOW()} syntax.
     */
    @Deprecated
    public static final String MYSQL_ON_UPDATE_TIMESTAMP_REMOVAL_RULE = MYSQL_ON_UPDATE_TIMESTAMP_TO_DM_RULE;
    public static final String MYSQL_SESSION_VARIABLE_NOOP_RULE = "MYSQL_SESSION_VARIABLE_TO_NOOP";
    public static final String MYSQL_TRUNCATE_TABLE_RULE = "MYSQL_TRUNCATE_TABLE_TO_DM";
    public static final String DUPLICATE_WHERE_KEYWORD_RULE = "DUPLICATE_WHERE_KEYWORD_REMOVED";
    public static final String MYSQL_DUPLICATE_UPDATE_SET_LITERAL_RULE =
            "MYSQL_DUPLICATE_UPDATE_SET_LITERAL_REMOVED";
    public static final String DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE = "DAMENG_KEYWORD_IDENTIFIER_QUOTE";
    public static final String MYSQL_UPDATE_ORDER_LIMIT_ONE_RULE = "MYSQL_UPDATE_ORDER_LIMIT_ONE_TO_ROWID";
    public static final String MYSQL_DELETE_ORDER_LIMIT_ONE_RULE = "MYSQL_DELETE_ORDER_LIMIT_ONE_TO_ROWID";
    public static final String MYSQL_UPDATE_LIMIT_RULE = "MYSQL_UPDATE_LIMIT_TO_ROWID";
    public static final String MYSQL_DELETE_LIMIT_RULE = "MYSQL_DELETE_LIMIT_TO_ROWID";
    public static final String MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE =
            "MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE";
    public static final String MYSQL_INSERT_SELECT_ON_DUPLICATE_KEY_UPDATE_TO_DM_CURSOR_MERGE_RULE =
            "MYSQL_INSERT_SELECT_ON_DUPLICATE_KEY_UPDATE_TO_DM_CURSOR_MERGE";
    public static final String MISSING_UPSERT_KEY_COLUMNS = "MISSING_UPSERT_KEY_COLUMNS";
    public static final String UNSUPPORTED_INSERT_SELECT_UPSERT = "UNSUPPORTED_INSERT_SELECT_UPSERT";
    public static final String UPSERT_KEY_NOT_IN_INSERT_COLUMNS = "UPSERT_KEY_NOT_IN_INSERT_COLUMNS";
    public static final String UNSAFE_UPSERT_UPDATE_ASSIGNMENT = "UNSAFE_UPSERT_UPDATE_ASSIGNMENT";
    public static final String MYSQL_HOUR_SECOND_INTERVAL_RULE =
            "MYSQL_HOUR_SECOND_INTERVAL_TO_DATEADD_SECOND";
    public static final String MYSQL_TIME_TO_SEC_TIMEDIFF_RULE =
            "MYSQL_TIME_TO_SEC_TIMEDIFF_TO_DATEDIFF_SECOND";
    public static final String MYSQL_TIME_PART_TIMEDIFF_RULE =
            "MYSQL_TIME_PART_TIMEDIFF_TO_DATEDIFF_SECOND";
    public static final String MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE =
            "MYSQL_INTEGER_DIVISION_TO_DECIMAL";
    public static final String MYSQL_DIV_OPERATOR_TO_TRUNC_DECIMAL_RULE =
            "MYSQL_DIV_OPERATOR_TO_TRUNC_DECIMAL";

    private static final String DM_CURRENT_SCHEMA_EXPRESSION =
            "SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)";
    private static final String DECIMAL_ARITHMETIC_TYPE = "DECIMAL(38,10)";
    private static final String INTEGER_ARITHMETIC_MANUAL_REVIEW_REASON =
            "整数算术表达式风险：MySQL `/` 会产生小数，达梦整数/整数可能截断；"
                    + "请确认是否需要在除法前 CAST 为 DECIMAL(38,10)，并用 NULLIF 处理分母 0。";
    private static final String TOKEN = "(?:\\d+|#\\{[^}]+}|\\$\\{[^}]+})";
    private static final Pattern CREATE_TABLE_CANDIDATE_PATTERN = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:TEMPORARY\\s+)?TABLE\\b"
    );
    private static final Pattern JSON_TABLE_JOIN_CANDIDATE_PATTERN = Pattern.compile(
            "\\bJOIN\\s+JSON_TABLE\\s*\\(",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SELECT_CANDIDATE_PATTERN = Pattern.compile(
            "\\bSELECT\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TABLE_ALIAS_CANDIDATE_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DIV_OPERATOR_CANDIDATE_PATTERN = Pattern.compile(
            "\\bDIV\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SINGLE_QUOTED_ALIAS_CANDIDATE_PATTERN = Pattern.compile(
            "(?is)\\bAS\\s*'|[)A-Za-z0-9_.$}`']\\s*'"
    );
    private static final Pattern MYSQL_NUMERIC_TYPE_ATTRIBUTE_CANDIDATE_PATTERN = Pattern.compile(
            "\\b(?:(?:TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT|FLOAT|DOUBLE|REAL)\\s*\\("
                    + "|UNSIGNED\\b|ZEROFILL\\b)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DECIMAL_PRECISION_CANDIDATE_PATTERN = Pattern.compile(
            "\\b(?:DECIMAL|NUMERIC)\\s*\\(",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIME_PART_FUNCTION_CANDIDATE_PATTERN = Pattern.compile(
            "\\b(?:HOUR|MINUTE|SECOND)\\s*\\(",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern COUNT_FUNCTION_CANDIDATE_PATTERN = Pattern.compile(
            "\\bCOUNT\\s*\\(",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MYSQL_BIT_LITERAL_CANDIDATE_PATTERN = Pattern.compile(
            "\\bB'[01]",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPECIAL_DAMENG_IDENTIFIER_CANDIDATE_PATTERN = Pattern.compile(
            "\\b(?:AUDIT|DIMENSION|PERCENT|DESC|DISTINCT|REVERSE)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IFNULL_PATTERN = Pattern.compile("\\bIFNULL\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOW_PATTERN = Pattern.compile("\\bNOW\\s*\\(\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSDATE_FUNCTION_PATTERN = Pattern.compile(
            "\\bSYSDATE\\s*\\(\\s*\\)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GROUP_CONCAT_PATTERN = Pattern.compile("\\bGROUP_CONCAT\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_IGNORE_PATTERN = Pattern.compile(
            "\\bINSERT\\s+IGNORE\\s+INTO\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ON_DUPLICATE_KEY_UPDATE_PATTERN = Pattern.compile(
            "\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REPLACE_INTO_PATTERN = Pattern.compile(
            "\\bREPLACE\\s+INTO\\b",
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
    private static final Pattern INTEGER_LITERAL_PATTERN = Pattern.compile("^[+-]?\\d+$");
    private static final Pattern NUMERIC_JDBC_TYPE_PLACEHOLDER_PATTERN = Pattern.compile(
            "(?is)#\\{[^}]*\\bjdbcType\\s*=\\s*"
                    + "(?:TINYINT|SMALLINT|INTEGER|INT|BIGINT|DECIMAL|NUMERIC|FLOAT|DOUBLE|REAL)\\b[^}]*}"
    );
    private static final Pattern NUMERIC_CAST_PATTERN = Pattern.compile(
            "(?is)^CAST\\s*\\(.+\\s+AS\\s+"
                    + "(?:TINYINT|SMALLINT|INTEGER|INT|BIGINT|DECIMAL(?:\\s*\\([^)]*\\))?|NUMERIC(?:\\s*\\([^)]*\\))?)"
                    + "\\s*\\)$"
    );
    private static final Pattern NUMERIC_INTERVAL_EXPRESSION_PATTERN = Pattern.compile(
            "(?is)^(?:ABS|CEIL|CEILING|FLOOR|TRUNC|ROUND|WEEKDAY|QUARTER|YEAR|MONTH|DAY|HOUR|MINUTE|SECOND|"
                    + "DATEDIFF|TIMESTAMPDIFF|LENGTH|COUNT)\\s*\\("
    );
    private static final Pattern NUMERIC_ARITHMETIC_INTERVAL_PATTERN = Pattern.compile("^[0-9+*/%().\\s-]+$");
    private static final List<String> MYSQL_INTERVAL_UNITS =
            List.of("HOUR_SECOND", "YEAR", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE", "SECOND");
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
            "DATE_ADD",
            "DATE_SUB",
            "MAKEDATE",
            "PERIOD_DIFF",
            "TIMEDIFF",
            "TIME_TO_SEC",
            "YEARWEEK"
    );
    private static final Set<String> SIMPLE_ARITHMETIC_FUNCTION_OPERANDS = Set.of(
            "ABS",
            "AVG",
            "CEIL",
            "CEILING",
            "COALESCE",
            "COUNT",
            "DAY",
            "FLOOR",
            "IF",
            "IFNULL",
            "MAX",
            "MIN",
            "NULLIF",
            "NVL",
            "ROUND",
            "SUM",
            "DATEDIFF"
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
            "REF",
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
    private static final String UPDATE_IDENTIFIER =
            "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"|`[^`]+`)";
    private static final Pattern MYSQL_BOOLEAN_NULL_PROJECTION_PATTERN = Pattern.compile(
            "(?is)^\\s*(?<expression>"
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")*)\\s+IS\\s+(?<not>NOT\\s+)?NULL\\s*$"
    );
    private static final Pattern MYSQL_BOOLEAN_NULL_PROJECTION_CANDIDATE_PATTERN = Pattern.compile(
            "(?is)\\bIS\\s+(?:NOT\\s+)?NULL\\b"
    );
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
    private static final Pattern INFORMATION_SCHEMA_COLUMNS_COUNT_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+count\\s*\\(\\s*(?:\\*|1)\\s*\\)"
                    + "(?:\\s+(?:as\\s+)?(?<alias>" + DM_IDENTIFIER + "))?"
                    + "\\s+from\\s+information_schema\\s*\\.\\s*columns\\s+where\\s+(?<where>.+?)\\s*;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_COLUMNS_AGGREGATE_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+listagg\\s*\\(\\s*column_name\\s*,\\s*','\\s*\\)"
                    + "\\s+within\\s+group\\s*\\(\\s*order\\s+by\\s+column_name\\s*\\)"
                    + "(?:\\s+(?:as\\s+)?(?<alias>" + DM_IDENTIFIER + "))?"
                    + "\\s+from\\s+information_schema\\s*\\.\\s*columns\\s+where\\s+(?<where>.+?)\\s*;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_COLUMNS_SIMPLE_DETAIL_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+column_name\\s*,\\s*column_comment\\s*,\\s*data_type\\s*,\\s*"
                    + "is_nullable\\s*,\\s*column_default\\s*,\\s*character_maximum_length\\s+"
                    + "from\\s+information_schema\\s*\\.\\s*columns\\s+where\\s+(?<where>.+?)\\s*;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_COLUMNS_DETAIL_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+"
                    + "table_schema\\s+(?:as\\s+)?(?<schemaAlias>[A-Za-z_][A-Za-z0-9_$]*)\\s*,\\s*"
                    + "table_name\\s+(?:as\\s+)?(?<tableAlias>[A-Za-z_][A-Za-z0-9_$]*)\\s*,\\s*"
                    + "column_name\\s+(?:as\\s+)?(?<columnAlias>[A-Za-z_][A-Za-z0-9_$]*)\\s*,\\s*"
                    + "column_type\\s+(?:as\\s+)?(?<typeAlias>[A-Za-z_][A-Za-z0-9_$]*)\\s*,\\s*"
                    + "column_comment\\s+(?:as\\s+)?(?<commentAlias>[A-Za-z_][A-Za-z0-9_$]*)\\s*,\\s*"
                    + "is_nullable\\s+(?:as\\s+)?(?<nullableAlias>[A-Za-z_][A-Za-z0-9_$]*)\\s+"
                    + "from\\s+information_schema\\s*\\.\\s*columns\\s+where\\s+"
                    + "(?<where>.+?)\\s*;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_COLUMNS_RUNTIME_DETAIL_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+"
                    + "column_name\\s+(?:as\\s+)?(?<columnAlias>" + DM_IDENTIFIER + ")\\s*,\\s*"
                    + "data_type\\s+(?:as\\s+)?(?<dataTypeAlias>" + DM_IDENTIFIER + ")\\s*,\\s*"
                    + "column_type\\s+(?:as\\s+)?(?<columnTypeAlias>" + DM_IDENTIFIER + ")\\s*,\\s*"
                    + "column_comment\\s+(?:as\\s+)?(?<commentAlias>" + DM_IDENTIFIER + ")"
                    + "(?:\\s*,\\s*column_default\\s+(?:as\\s+)?"
                    + "(?<defaultAlias>" + DM_IDENTIFIER + "))?\\s+"
                    + "from\\s+information_schema\\s*\\.\\s*columns\\s+where\\s+"
                    + "(?<where>.+?)\\s*;?\\s*$"
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
    private static final Pattern INFORMATION_SCHEMA_TABLES_EXISTS_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+1\\s+from\\s+information_schema\\s*\\.\\s*tables\\s+"
                    + "where\\s+(?<where>.+?)\\s*;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_TABLES_LIST_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+table_name\\s+from\\s+information_schema\\s*\\.\\s*tables\\s+where\\s+"
                    + "(?<where>.+?)\\s+group\\s+by\\s+table_name"
                    + "(?:\\s+limit\\s+(?<limit>" + TOKEN + "))?\\s*;?\\s*$"
    );
    private static final Pattern INFORMATION_SCHEMA_TABLES_DETAIL_PATTERN = Pattern.compile(
            "(?is)^\\s*select\\s+table_name\\s+as\\s+(?<tableAlias>" + DM_IDENTIFIER + ")\\s*,\\s*"
                    + "create_time\\s+as\\s+(?<createAlias>" + DM_IDENTIFIER + ")\\s*,\\s*"
                    + "table_schema\\s+as\\s+(?<schemaAlias>" + DM_IDENTIFIER + ")\\s+"
                    + "from\\s+information_schema\\s*\\.\\s*(?:tables|\"tables\"|`tables`)\\s+where\\s+"
                    + "table_schema\\s*=\\s*\\(\\s*select\\s+database\\s*\\(\\s*\\)\\s*\\)\\s+and\\s+"
                    + "table_name\\s+like\\s+(?<tableLike>.+?)\\s*;?\\s*$"
    );
    private static final Pattern MYSQL_DESCRIBE_TABLE_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:desc|describe)\\s+(?<table>[^;\\s]+)\\s*;?\\s*$"
    );
    private static final Pattern METADATA_TABLE_NAME_CONDITION = Pattern.compile(
            "(?is)\\btable_name\\s*=\\s*(?<value>\\?|#\\{[^}]+}|\\$\\{[^}]+}|'(?:''|[^'])*')"
    );
    private static final Pattern METADATA_TABLE_NAME_LIKE_CONDITION = Pattern.compile(
            "(?is)\\btable_name\\s+like\\s+(?<value>\\?|#\\{[^}]+}|\\$\\{[^}]+}|'(?:''|[^'])*'|\\([^)]*\\))"
    );
    private static final Pattern METADATA_COLUMN_NAME_CONDITION = Pattern.compile(
            "(?is)\\bcolumn_name\\s*=\\s*(?<value>\\?|#\\{[^}]+}|\\$\\{[^}]+}|'(?:''|[^'])*')"
    );
    private static final Pattern METADATA_TABLE_SCHEMA_CONDITION = Pattern.compile(
            "(?is)\\btable_schema\\s*=\\s*(?<value>\\?|#\\{[^}]+}|\\$\\{[^}]+}|'(?:''|[^'])*'|database\\s*\\(\\s*\\)|\\(\\s*select\\s+database\\s*\\(\\s*\\)\\s*\\))"
    );
    private static final Pattern METADATA_TABLE_TYPE_CONDITION = Pattern.compile(
            "(?is)\\btable_type\\s*=\\s*(?<value>'(?:''|[^'])*')"
    );
    private static final Pattern UPDATE_SET_QUALIFIED_ASSIGNMENT = Pattern.compile(
            "(?is)^\\s*(?<alias>" + UPDATE_IDENTIFIER + ")\\s*\\.\\s*"
                    + "(?<column>" + UPDATE_IDENTIFIER + ")\\s*="
    );
    private static final String SIMPLE_IDENTIFIER = "[A-Za-z_][A-Za-z0-9_$]*";
    private static final String SIMPLE_QUALIFIED_IDENTIFIER =
            SIMPLE_IDENTIFIER + "(?:\\s*\\.\\s*" + SIMPLE_IDENTIFIER + ")?";
    private static final String MYSQL_UNQUOTED_ALIAS = "[A-Za-z0-9_$]+";
    private static final String MYBATIS_PARAMETER = "#\\{[^}]+}";
    private static final Pattern MYSQL_ANCESTOR_USER_VARIABLE_TRAVERSAL_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)SELECT\\s+GROUP_CONCAT\\s*\\(\\s*"
                    + "(?<valueColumn>" + DM_IDENTIFIER + ")\\s+ORDER\\s+BY\\s+"
                    + "(?<aggregateAlias>" + SIMPLE_IDENTIFIER + ")\\s*\\.\\s*"
                    + "(?<aggregateId>" + DM_IDENTIFIER + ")\\s+SEPARATOR\\s+"
                    + "(?<separator>'(?:''|[^'])*')\\s*\\)"
                    + "(?:\\s+(?:AS\\s+)?(?<resultAlias>" + DM_IDENTIFIER + "))?\\s+"
                    + "FROM\\s*\\(\\s*SELECT\\s+"
                    + "@(?<cursorRead>" + SIMPLE_IDENTIFIER + ")\\s+AS\\s+"
                    + "(?<walkIdAlias>" + SIMPLE_IDENTIFIER + ")\\s*,\\s*"
                    + "\\(\\s*SELECT\\s+@(?<cursorWrite>" + SIMPLE_IDENTIFIER + ")\\s*:=\\s*"
                    + "(?<parentColumn>" + DM_IDENTIFIER + ")\\s+FROM\\s+"
                    + "(?<lookupTable>" + SIMPLE_QUALIFIED_IDENTIFIER + ")\\s+WHERE\\s+"
                    + "(?<lookupId>" + DM_IDENTIFIER + ")\\s*=\\s*"
                    + "(?<lookupWalkIdAlias>" + SIMPLE_IDENTIFIER + ")\\s*\\)\\s+AS\\s+"
                    + "(?<scratchAlias>" + MYSQL_UNQUOTED_ALIAS + ")\\s*,\\s*"
                    + "@(?<levelWrite>" + SIMPLE_IDENTIFIER + ")\\s*:=\\s*"
                    + "@(?<levelRead>" + SIMPLE_IDENTIFIER + ")\\s*\\+\\s*1\\s+AS\\s+"
                    + "(?<levelAlias>" + SIMPLE_IDENTIFIER + ")\\s+FROM\\s+"
                    + "\\(\\s*SELECT\\s+@(?<seedCursor>" + SIMPLE_IDENTIFIER + ")\\s*:=\\s*"
                    + "(?<seed>" + MYBATIS_PARAMETER + ")\\s*\\)\\s+"
                    + "(?<varsAlias>" + SIMPLE_IDENTIFIER + ")\\s*,\\s*"
                    + "(?<driverTable>" + SIMPLE_QUALIFIED_IDENTIFIER + ")\\s+"
                    + "(?<driverAlias>" + SIMPLE_IDENTIFIER + ")\\s+WHERE\\s+"
                    + "(?<driverAliasRef>" + SIMPLE_IDENTIFIER + ")\\s*\\.\\s*"
                    + "(?<driverParent>" + DM_IDENTIFIER + ")\\s*!=\\s*0\\s*\\)\\s+"
                    + "(?<walkAlias>" + SIMPLE_IDENTIFIER + ")\\s+JOIN\\s+"
                    + "(?<outputTable>" + SIMPLE_QUALIFIED_IDENTIFIER + ")\\s+"
                    + "(?<outputAlias>" + SIMPLE_IDENTIFIER + ")\\s+ON\\s+"
                    + "(?<walkAliasRef>" + SIMPLE_IDENTIFIER + ")\\s*\\.\\s*"
                    + "(?<walkIdAliasRef>" + SIMPLE_IDENTIFIER + ")\\s*=\\s*"
                    + "(?<outputAliasRef>" + SIMPLE_IDENTIFIER + ")\\s*\\.\\s*"
                    + "(?<outputId>" + DM_IDENTIFIER + ")\\s+AND\\s+"
                    + "(?<filterAlias>" + SIMPLE_IDENTIFIER + ")\\s*\\.\\s*"
                    + "(?<filterId>" + DM_IDENTIFIER + ")\\s*!=\\s*"
                    + "(?<filterSeed>" + MYBATIS_PARAMETER + ")"
                    + "(?<trailing>\\s*;?\\s*)$"
    );
    private static final Pattern MYSQL_HELP_TOPIC_SPLIT_JOIN_PATTERN = Pattern.compile(
            "(?is)\\b(?:INNER\\s+)?JOIN\\s+mysql\\s*\\.\\s*help_topic\\s+"
                    + "(?<alias>" + SIMPLE_IDENTIFIER + ")\\s+ON\\s+"
                    + "(?<conditionAlias>" + SIMPLE_IDENTIFIER + ")\\s*\\.\\s*help_topic_id\\s*"
                    + "(?<lessThan>&lt;|<)\\s*\\(\\s*"
                    + "LENGTH\\s*\\(\\s*(?<source>" + SIMPLE_QUALIFIED_IDENTIFIER + ")\\s*\\)\\s*-\\s*"
                    + "LENGTH\\s*\\(\\s*REPLACE\\s*\\(\\s*"
                    + "(?<replaceSource>" + SIMPLE_QUALIFIED_IDENTIFIER + ")\\s*,\\s*','\\s*,\\s*''\\s*\\)\\s*\\)"
                    + "\\s*\\+\\s*1\\s*\\)"
    );

    @Override
    public SqlConversionResult convert(String sql) {
        return convert(sql, List.of());
    }

    public SqlConversionResult convertDynamicTextSegmentSafeRules(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlConversionResult.unchanged(sql == null ? "" : sql);
        }
        String original = sql;
        String converted = original;
        List<String> rules = new ArrayList<>();

        GenericConversion hashLineCommentConversion = convertMysqlHashLineComments(converted);
        if (hashLineCommentConversion.changed()) {
            converted = hashLineCommentConversion.convertedSql();
            rules.add(MYSQL_HASH_LINE_COMMENT_RULE);
        }

        GenericConversion bitLiteralConversion = convertMysqlBitLiterals(converted);
        if (bitLiteralConversion.changed()) {
            converted = bitLiteralConversion.convertedSql();
            rules.add(MYSQL_BIT_LITERAL_RULE);
        }

        DoubleQuotedStringConversion doubleQuotedStringConversion = convertDoubleQuotedStringLiterals(converted);
        if (doubleQuotedStringConversion.changed()) {
            converted = doubleQuotedStringConversion.convertedSql();
            rules.add("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
        }

        String reason = "";
        if (!converted.equals(original) && !reason.isBlank()) {
            return SqlConversionResult.changedWithManualReview(original, converted, rules, reason);
        }
        if (!converted.equals(original)) {
            return SqlConversionResult.changed(original, converted, rules);
        }
        if (!reason.isBlank()) {
            return SqlConversionResult.manualReview(original, reason);
        }
        return SqlConversionResult.unchanged(original);
    }

    /**
     * Removes MySQL-only literal table options from the text following a CREATE TABLE closing parenthesis.
     * The statement terminator and any following text are preserved.
     */
    public SqlConversionResult convertCreateTableTrailingOptions(String trailingOptions) {
        String original = trailingOptions == null ? "" : trailingOptions;
        GenericConversion conversion = removeMysqlCreateTableTrailingOptions(original);
        return conversion.changed()
                ? SqlConversionResult.changed(
                        original,
                        conversion.convertedSql(),
                        List.of(MYSQL_CREATE_TABLE_OPTION_REMOVAL_RULE)
                )
                : SqlConversionResult.unchanged(original);
    }

    public SqlConversionResult convertJoinedDelete(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlConversionResult.unchanged(sql == null ? "" : sql);
        }
        GenericConversion conversion = convertMysqlJoinedDelete(sql);
        return conversion.changed()
                ? SqlConversionResult.changed(sql, conversion.convertedSql(), List.of(MYSQL_DELETE_JOIN_RULE))
                : SqlConversionResult.unchanged(sql);
    }

    public SqlConversionResult convertRegexpOperatorExpressions(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlConversionResult.unchanged(sql == null ? "" : sql);
        }
        GenericConversion regexpConversion = convertRegexpOperators(sql);
        if (!regexpConversion.changed()) {
            return SqlConversionResult.unchanged(sql);
        }
        String converted = regexpConversion.convertedSql();
        List<String> rules = new ArrayList<>();
        rules.add(MYSQL_REGEXP_OPERATOR_RULE);
        GenericConversion singleArgumentConcatConversion = convertSingleArgumentConcat(converted);
        if (singleArgumentConcatConversion.changed()) {
            converted = singleArgumentConcatConversion.convertedSql();
            rules.add(MYSQL_SINGLE_ARGUMENT_CONCAT_RULE);
        }
        return SqlConversionResult.changed(sql, converted, rules);
    }

    @Override
    public SqlConversionResult convert(String sql, List<String> upsertKeyColumns) {
        return convert(sql, upsertKeyColumns, List.of());
    }

    public SqlConversionResult convertInsertIgnoreWithConflictKeyGroups(
            String sql,
            List<List<String>> conflictKeyGroups
    ) {
        return convert(sql, List.of(), conflictKeyGroups);
    }

    private SqlConversionResult convert(
            String sql,
            List<String> upsertKeyColumns,
            List<List<String>> insertIgnoreConflictKeyGroups
    ) {
        if (sql == null || sql.isBlank()) {
            return SqlConversionResult.unchanged(sql == null ? "" : sql);
        }
        String original = sql;
        String converted = original;
        List<String> rules = new ArrayList<>();

        GenericConversion hashLineCommentConversion = convertMysqlHashLineComments(converted);
        if (hashLineCommentConversion.changed()) {
            converted = hashLineCommentConversion.convertedSql();
            rules.add(MYSQL_HASH_LINE_COMMENT_RULE);
        }

        GenericConversion bitLiteralConversion = convertMysqlBitLiterals(converted);
        if (bitLiteralConversion.changed()) {
            converted = bitLiteralConversion.convertedSql();
            rules.add(MYSQL_BIT_LITERAL_RULE);
        }

        DoubleQuotedStringConversion doubleQuotedStringConversion = convertDoubleQuotedStringLiterals(converted);
        if (doubleQuotedStringConversion.changed()) {
            converted = doubleQuotedStringConversion.convertedSql();
            rules.add("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
        }

        GenericConversion singleQuotedAliasConversion = convertSingleQuotedAliases(converted);
        if (singleQuotedAliasConversion.changed()) {
            converted = singleQuotedAliasConversion.convertedSql();
            rules.add(MYSQL_SINGLE_QUOTED_ALIAS_RULE);
        }

        GenericConversion numericLeadingAliasConversion = quoteNumericLeadingSelectAliases(converted);
        if (numericLeadingAliasConversion.changed()) {
            converted = numericLeadingAliasConversion.convertedSql();
            rules.add(MYSQL_NUMERIC_LEADING_SELECT_ALIAS_RULE);
        }

        GenericConversion selectModifierConversion = removeMysqlSelectModifiers(converted);
        if (selectModifierConversion.changed()) {
            converted = selectModifierConversion.convertedSql();
            rules.add(MYSQL_SELECT_MODIFIER_REMOVAL_RULE);
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

        GenericConversion numericTypeAttributeConversion = convertMysqlNumericTypeAttributes(converted);
        if (numericTypeAttributeConversion.changed()) {
            converted = numericTypeAttributeConversion.convertedSql();
            rules.add(MYSQL_NUMERIC_TYPE_ATTRIBUTE_RULE);
        }

        GenericConversion createTableTextTypeConversion = convertMysqlCreateTableTextTypes(converted);
        if (createTableTextTypeConversion.changed()) {
            converted = createTableTextTypeConversion.convertedSql();
            rules.add(MYSQL_TEXT_TYPE_TO_DM_CLOB_RULE);
        }

        GenericConversion autoIncrementConversion = convertMysqlAutoIncrement(converted);
        if (autoIncrementConversion.changed()) {
            converted = autoIncrementConversion.convertedSql();
            rules.add(MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE);
        }

        GenericConversion redundantIdentityUniqueConversion = removeRedundantIdentityPrimaryKeyUnique(converted);
        if (redundantIdentityUniqueConversion.changed()) {
            converted = redundantIdentityUniqueConversion.convertedSql();
            rules.add(MYSQL_IDENTITY_PRIMARY_KEY_REDUNDANT_UNIQUE_RULE);
        }

        GenericConversion inlineIdentityPrimaryKeyConversion = normalizeIdentityInlinePrimaryKeys(converted);
        if (inlineIdentityPrimaryKeyConversion.changed()) {
            converted = inlineIdentityPrimaryKeyConversion.convertedSql();
            rules.add(MYSQL_IDENTITY_INLINE_PRIMARY_KEY_RULE);
        }

        GenericConversion decimalPrecisionConversion = capMysqlDecimalPrecision(converted);
        if (decimalPrecisionConversion.changed()) {
            converted = decimalPrecisionConversion.convertedSql();
            rules.add(MYSQL_DECIMAL_PRECISION_CAP_RULE);
        }

        GenericConversion columnCommentConversion = normalizeMysqlCreateTableColumnComments(converted);
        if (columnCommentConversion.changed()) {
            converted = columnCommentConversion.convertedSql();
            rules.add(MYSQL_CREATE_TABLE_COLUMN_COMMENT_TO_DM_RULE);
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

        GenericConversion generatedColumnStoredConversion = removeMysqlGeneratedColumnStoredAttributes(converted);
        if (generatedColumnStoredConversion.changed()) {
            converted = generatedColumnStoredConversion.convertedSql();
            rules.add(MYSQL_GENERATED_COLUMN_STORED_REMOVAL_RULE);
        }

        GenericConversion temporaryTableAsSelectConversion = convertMysqlTemporaryTableAsSelect(converted);
        if (temporaryTableAsSelectConversion.changed()) {
            converted = temporaryTableAsSelectConversion.convertedSql();
            rules.add(MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE);
        }

        GenericConversion onUpdateTimestampConversion = convertMysqlOnUpdateCurrentTimestamp(converted);
        if (onUpdateTimestampConversion.changed()) {
            converted = onUpdateTimestampConversion.convertedSql();
            rules.add(MYSQL_ON_UPDATE_TIMESTAMP_TO_DM_RULE);
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

        GenericConversion duplicateUpdateSetConversion = deduplicateLiteralUpdateSetAssignments(converted);
        if (duplicateUpdateSetConversion.changed()) {
            converted = duplicateUpdateSetConversion.convertedSql();
            rules.add(MYSQL_DUPLICATE_UPDATE_SET_LITERAL_RULE);
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

        GenericConversion hourSecondIntervalConversion = convertStrToDateHourSecondInterval(converted);
        if (hourSecondIntervalConversion.changed()) {
            converted = hourSecondIntervalConversion.convertedSql();
            rules.add(MYSQL_HOUR_SECOND_INTERVAL_RULE);
        }

        GenericConversion dateAddIntervalConversion = convertDateAddInterval(converted);
        if (dateAddIntervalConversion.changed()) {
            converted = dateAddIntervalConversion.convertedSql();
            rules.add(MYSQL_DATE_ADD_INTERVAL_RULE);
        }

        GenericConversion numericIfNullComparisonConversion = convertTimestampDiffNumericIfNullComparisons(converted);
        if (numericIfNullComparisonConversion.changed()) {
            converted = numericIfNullComparisonConversion.convertedSql();
            rules.add(MYSQL_NUMERIC_IFNULL_COMPARISON_TO_NUMBER_RULE);
        }

        GenericConversion makeDateConversion = convertMakeDateFunctions(converted);
        if (makeDateConversion.changed()) {
            converted = makeDateConversion.convertedSql();
            rules.add(MYSQL_MAKEDATE_RULE);
        }

        GenericConversion periodDiffYearMonthConversion = convertPeriodDiffYearMonth(converted);
        if (periodDiffYearMonthConversion.changed()) {
            converted = periodDiffYearMonthConversion.convertedSql();
            rules.add(MYSQL_PERIOD_DIFF_YEARMONTH_RULE);
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

        GenericConversion nullSafeEqualConversion = convertMysqlNullSafeEquals(converted);
        if (nullSafeEqualConversion.changed()) {
            converted = nullSafeEqualConversion.convertedSql();
            rules.add(MYSQL_NULL_SAFE_EQUAL_RULE);
        }

        GenericConversion helpTopicSplitConversion = convertMysqlHelpTopicSplit(converted);
        if (helpTopicSplitConversion.changed()) {
            converted = helpTopicSplitConversion.convertedSql();
            rules.add(MYSQL_HELP_TOPIC_SPLIT_TO_CROSS_APPLY_RULE);
        }

        GenericConversion tableAliasAsConversion = removeAsFromTableAliases(converted);
        if (tableAliasAsConversion.changed()) {
            converted = tableAliasAsConversion.convertedSql();
            rules.add(MYSQL_TABLE_ALIAS_AS_RULE);
        }

        GenericConversion indexHintConversion = removeMysqlIndexHints(converted);
        if (indexHintConversion.changed()) {
            converted = indexHintConversion.convertedSql();
            rules.add(MYSQL_INDEX_HINT_REMOVAL_RULE);
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

        GenericConversion noLockHintConversion = removeSqlServerNoLockHints(converted);
        if (noLockHintConversion.changed()) {
            converted = noLockHintConversion.convertedSql();
            rules.add(SQLSERVER_NOLOCK_HINT_REMOVAL_RULE);
        }

        GenericConversion keywordTableAliasConversion = quoteDamengKeywordTableAliases(converted);
        if (keywordTableAliasConversion.changed()) {
            converted = keywordTableAliasConversion.convertedSql();
            rules.add(DAMENG_KEYWORD_TABLE_ALIAS_RULE);
        }

        GenericConversion dboSchemaConversion = removeSqlServerDboSchemaQualifiers(converted);
        if (dboSchemaConversion.changed()) {
            converted = dboSchemaConversion.convertedSql();
            rules.add(SQLSERVER_DBO_SCHEMA_REMOVAL_RULE);
        }

        GenericConversion stringPlusConversion = convertSqlServerStringPlusOperators(converted);
        if (stringPlusConversion.changed()) {
            converted = stringPlusConversion.convertedSql();
            rules.add(SQLSERVER_STRING_PLUS_TO_DM_CONCAT_RULE);
        }

        GenericConversion charIndexConversion = convertSqlServerCharIndexFunctions(converted);
        if (charIndexConversion.changed()) {
            converted = charIndexConversion.convertedSql();
            rules.add(SQLSERVER_CHARINDEX_TO_DM_INSTR_RULE);
        }

        GenericConversion sqlServerTopConversion = convertSqlServerTopClauses(converted);
        if (sqlServerTopConversion.changed()) {
            converted = sqlServerTopConversion.convertedSql();
            rules.add(SQLSERVER_TOP_TO_DM_FETCH_FIRST_RULE);
        }

        GenericConversion deleteAliasStarAfterIndexHintConversion = convertMysqlDeleteAliasStar(converted);
        if (deleteAliasStarAfterIndexHintConversion.changed()) {
            converted = deleteAliasStarAfterIndexHintConversion.convertedSql();
            rules.add(MYSQL_DELETE_ALIAS_STAR_RULE);
        }

        UpdateSetTableOrderConversion updateSetTableOrderConversion = convertUpdateSetTableOrder(converted);
        if (updateSetTableOrderConversion.changed()) {
            converted = updateSetTableOrderConversion.convertedSql();
            rules.add(UPDATE_SET_TABLE_ORDER_RULE);
        }

        GenericConversion updateJoinConversion = convertMysqlUpdateJoin(converted);
        if (updateJoinConversion.changed()) {
            converted = updateJoinConversion.convertedSql();
            rules.add(MYSQL_UPDATE_JOIN_RULE);
        }

        DamengReservedColumnRenamer.RenameResult renameResult =
                DamengReservedColumnRenamer.renameBareIdentifiers(converted);
        if (renameResult.changed()) {
            converted = renameResult.convertedSql();
            rules.add(DamengReservedColumnRenamer.RULE_NAME);
        }

        GenericConversion deleteJoinConversion = convertMysqlJoinedDelete(converted);
        if (deleteJoinConversion.changed()) {
            converted = deleteJoinConversion.convertedSql();
            rules.add(MYSQL_DELETE_JOIN_RULE);
        }

        GenericConversion targetOnlyOuterJoinConversion = convertMysqlTargetOnlyOuterJoin(converted);
        if (targetOnlyOuterJoinConversion.changed()) {
            converted = targetOnlyOuterJoinConversion.convertedSql();
            rules.add(MYSQL_UPDATE_JOIN_RULE);
        }

        GenericConversion keywordQuoteConversion = quoteDamengKeywordIdentifiers(converted);
        if (keywordQuoteConversion.changed()) {
            converted = keywordQuoteConversion.convertedSql();
            rules.add(DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE);
        }

        GenericConversion hierarchyUserVariableConversion = convertMysqlAncestorUserVariableTraversal(converted);
        if (hierarchyUserVariableConversion.changed()) {
            converted = hierarchyUserVariableConversion.convertedSql();
            rules.add(MYSQL_HIERARCHY_USER_VARIABLE_TO_DM_CONNECT_BY_RULE);
        }

        GenericConversion groupConcatConversion = convertGroupConcat(converted);
        if (groupConcatConversion.changed()) {
            converted = groupConcatConversion.convertedSql();
            rules.add(MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
        }

        GenericConversion havingAggregateAliasConversion = convertHavingAggregateAliases(converted);
        if (havingAggregateAliasConversion.changed()) {
            converted = havingAggregateAliasConversion.convertedSql();
            rules.add(MYSQL_HAVING_AGGREGATE_ALIAS_RULE);
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

        GenericConversion notIsNullConversion = convertNotIsNull(converted);
        if (notIsNullConversion.changed()) {
            converted = notIsNullConversion.convertedSql();
            rules.add(MYSQL_NOT_ISNULL_RULE);
        }

        GenericConversion booleanNullProjectionConversion = convertBooleanNullProjection(converted);
        if (booleanNullProjectionConversion.changed()) {
            converted = booleanNullProjectionConversion.convertedSql();
            rules.add(MYSQL_BOOLEAN_NULL_PROJECTION_RULE);
        }

        GenericConversion booleanOperatorConversion = convertBooleanOperatorsInIfConditions(converted);
        if (booleanOperatorConversion.changed()) {
            converted = booleanOperatorConversion.convertedSql();
            rules.add(MYSQL_BOOLEAN_OPERATOR_RULE);
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

        GenericConversion singleArgumentConcatConversion = convertSingleArgumentConcat(converted);
        if (singleArgumentConcatConversion.changed()) {
            converted = singleArgumentConcatConversion.convertedSql();
            rules.add(MYSQL_SINGLE_ARGUMENT_CONCAT_RULE);
        }

        GenericConversion likePlaceholderLiteralConversion = convertLikePlaceholderLiteralConcatenation(converted);
        if (likePlaceholderLiteralConversion.changed()) {
            converted = likePlaceholderLiteralConversion.convertedSql();
            rules.add(MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT_RULE);
        }

        GenericConversion describeTableConversion = convertDescribeTable(converted);
        if (describeTableConversion.changed()) {
            converted = describeTableConversion.convertedSql();
            rules.add(MYSQL_DESCRIBE_TABLE_RULE);
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

        GenericConversion informationSchemaStatisticsConversion = convertInformationSchemaStatistics(converted);
        if (informationSchemaStatisticsConversion.changed()) {
            converted = informationSchemaStatisticsConversion.convertedSql();
            rules.add(MYSQL_INFORMATION_SCHEMA_STATISTICS_RULE);
        }

        GenericConversion currentSchemaFunctionConversion = convertMysqlCurrentSchemaFunctions(converted);
        if (currentSchemaFunctionConversion.changed()) {
            converted = currentSchemaFunctionConversion.convertedSql();
            rules.add(MYSQL_CURRENT_SCHEMA_FUNCTION_RULE);
        }

        GenericConversion yearWeekConversion = convertDefaultYearWeek(converted);
        if (yearWeekConversion.changed()) {
            converted = yearWeekConversion.convertedSql();
            rules.add(MYSQL_YEARWEEK_RULE);
        }

        GenericConversion timeToSecTimeDiffConversion = convertTimeToSecTimeDiff(converted);
        if (timeToSecTimeDiffConversion.changed()) {
            converted = timeToSecTimeDiffConversion.convertedSql();
            rules.add(MYSQL_TIME_TO_SEC_TIMEDIFF_RULE);
        }

        GenericConversion timePartTimeDiffConversion = convertTimePartTimeDiff(converted);
        if (timePartTimeDiffConversion.changed()) {
            converted = timePartTimeDiffConversion.convertedSql();
            rules.add(MYSQL_TIME_PART_TIMEDIFF_RULE);
        }

        GenericConversion updateOrderLimitConversion = convertMysqlUpdateOrderLimitOne(converted);
        if (updateOrderLimitConversion.changed()) {
            converted = updateOrderLimitConversion.convertedSql();
            rules.add(MYSQL_UPDATE_ORDER_LIMIT_ONE_RULE);
        }

        GenericConversion deleteOrderLimitConversion = convertMysqlDeleteOrderLimitOne(converted);
        if (deleteOrderLimitConversion.changed()) {
            converted = deleteOrderLimitConversion.convertedSql();
            rules.add(MYSQL_DELETE_ORDER_LIMIT_ONE_RULE);
        }

        GenericConversion updateLimitConversion = convertMysqlUpdateLimit(converted);
        if (updateLimitConversion.changed()) {
            converted = updateLimitConversion.convertedSql();
            rules.add(MYSQL_UPDATE_LIMIT_RULE);
        }

        GenericConversion deleteLimitConversion = convertMysqlDeleteLimit(converted);
        if (deleteLimitConversion.changed()) {
            converted = deleteLimitConversion.convertedSql();
            rules.add(MYSQL_DELETE_LIMIT_RULE);
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

        GenericConversion insertIgnoreConversion = insertIgnoreConflictKeyGroups == null
                || insertIgnoreConflictKeyGroups.isEmpty()
                ? convertInsertIgnore(converted, upsertKeyColumns)
                : convertInsertIgnoreWithConflictKeyGroupsInternal(
                        converted,
                        insertIgnoreConflictKeyGroups
                );
        if (insertIgnoreConversion.changed()) {
            converted = insertIgnoreConversion.convertedSql();
            rules.add(MYSQL_INSERT_IGNORE_TO_DM_MERGE_RULE);
        }

        UpsertConversion onDuplicateKeyUpdateConversion = convertOnDuplicateKeyUpdate(converted, upsertKeyColumns);
        if (onDuplicateKeyUpdateConversion.changed()) {
            converted = onDuplicateKeyUpdateConversion.convertedSql();
            rules.add(onDuplicateKeyUpdateConversion.ruleName());
        } else if (!onDuplicateKeyUpdateConversion.manualReviewReason().isBlank()) {
            return manualReviewResult(
                    original,
                    converted,
                    rules,
                    onDuplicateKeyUpdateConversion.manualReviewReason()
            );
        }

        GenericConversion unusedUserVariableSelectItemConversion = removeUnusedUserVariableSelectItems(converted);
        if (unusedUserVariableSelectItemConversion.changed()) {
            converted = unusedUserVariableSelectItemConversion.convertedSql();
            rules.add(MYSQL_UNUSED_USER_VARIABLE_SELECT_ITEM_RULE);
        }

        ArithmeticConversion arithmeticConversion = convertIntegerArithmeticExpressions(converted);
        if (arithmeticConversion.changed()) {
            converted = arithmeticConversion.convertedSql();
            rules.addAll(arithmeticConversion.appliedRules());
        }
        if (!arithmeticConversion.manualReviewReason().isBlank()) {
            return manualReviewResult(original, converted, rules, arithmeticConversion.manualReviewReason());
        }

        GenericConversion trailingSemicolonConversion = removeTrailingStatementSemicolons(converted);
        if (trailingSemicolonConversion.changed()) {
            converted = trailingSemicolonConversion.convertedSql();
            rules.add(MYSQL_TRAILING_SEMICOLON_REMOVAL_RULE);
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

    public SqlConversionResult convertOuterJoinWithUniqueSourceKeys(
            String sql,
            Map<String, List<String>> tableKeyColumns
    ) {
        if (sql == null || sql.isBlank()) {
            return convert(sql);
        }
        GenericConversion outerJoinConversion =
                convertUniqueSourceLeftJoinUpdate(
                        sql,
                        tableKeyColumns == null ? Map.of() : tableKeyColumns
                );
        if (!outerJoinConversion.changed()) {
            return convert(sql);
        }
        SqlConversionResult remaining = convert(outerJoinConversion.convertedSql());
        List<String> rules = new ArrayList<>();
        rules.add(MYSQL_UPDATE_JOIN_RULE);
        for (String rule : remaining.appliedRules()) {
            if (!rules.contains(rule)) {
                rules.add(rule);
            }
        }
        if (remaining.manualReviewRequired()) {
            return SqlConversionResult.changedWithManualReview(
                    sql,
                    remaining.convertedSql(),
                    rules,
                    remaining.reason()
            );
        }
        return SqlConversionResult.changed(sql, remaining.convertedSql(), rules);
    }

    public Optional<OuterJoinSourceKeyCandidate> outerJoinSourceKeyCandidate(String sql) {
        if (sql == null || sql.isBlank() || splitTopLevelStatements(sql).size() != 1) {
            return Optional.empty();
        }
        int updateIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, updateIndex, "UPDATE")) {
            return Optional.empty();
        }
        int joinIndex = findTopLevelKeyword(sql, "JOIN", updateIndex + "UPDATE".length());
        int setIndex = joinIndex < 0
                ? -1
                : findTopLevelKeyword(sql, "SET", joinIndex + "JOIN".length());
        if (joinIndex < 0
                || setIndex < 0
                || findTopLevelKeyword(sql, "JOIN", joinIndex + "JOIN".length()) >= 0) {
            return Optional.empty();
        }
        int joinTypeStart = joinTypeStart(sql, joinIndex);
        String joinType = sql.substring(joinTypeStart, joinIndex).strip();
        if (!Pattern.compile("(?is)^LEFT(?:\\s+OUTER)?$").matcher(joinType).matches()) {
            return Optional.empty();
        }
        String target = sql.substring(updateIndex + "UPDATE".length(), joinTypeStart).strip();
        String joinSource = sql.substring(joinIndex + "JOIN".length(), setIndex).strip();
        UpdateJoinChain chain = updateJoinChain(target, joinSource, true);
        if (chain == null || chain.tables().size() != 2 || chain.conditions().size() != 1) {
            return Optional.empty();
        }
        UpdateJoinTable sourceTable = chain.tables().get(1);
        if (sourceTable.tableSql().stripLeading().startsWith("(")) {
            return Optional.empty();
        }
        List<String> joinColumns = new ArrayList<>();
        for (String predicate : splitStrictTopLevelAndPredicates(chain.conditions().get(0))) {
            int equalsIndex = findTopLevelChar(predicate, '=', 0);
            if (equalsIndex < 0
                    || findTopLevelChar(predicate, '=', equalsIndex + 1) >= 0) {
                continue;
            }
            String left = predicate.substring(0, equalsIndex).strip();
            String right = predicate.substring(equalsIndex + 1).strip();
            String sourceColumn = qualifiedColumnForAlias(left, sourceTable.aliasKey());
            if (sourceColumn.isBlank()
                    && !referencesAliasOutsideIgnoredText(left, sourceTable.aliasKey())) {
                sourceColumn = qualifiedColumnForAlias(right, sourceTable.aliasKey());
            }
            if (!sourceColumn.isBlank()
                    && !joinColumns.stream()
                    .map(this::normalizeIdentifierKey)
                    .toList()
                    .contains(normalizeIdentifierKey(sourceColumn))) {
                joinColumns.add(sourceColumn);
            }
        }
        if (joinColumns.isEmpty()) {
            return Optional.empty();
        }
        String tableName = Pattern.compile("\\s+")
                .split(sourceTable.tableSql().strip(), 2)[0];
        return Optional.of(new OuterJoinSourceKeyCandidate(tableName, joinColumns));
    }

    private String qualifiedColumnForAlias(String expression, String aliasKey) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*(?<alias>" + DM_IDENTIFIER + ")\\s*\\.\\s*"
                        + "(?<column>" + DM_IDENTIFIER + ")\\s*$"
        ).matcher(expression == null ? "" : expression);
        if (!matcher.matches()
                || !normalizeIdentifierKey(matcher.group("alias")).equals(aliasKey)) {
            return "";
        }
        return matcher.group("column");
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

    private ArithmeticConversion convertIntegerArithmeticExpressions(String sql) {
        if (!hasPotentialSlashDivision(sql)
                && !DIV_OPERATOR_CANDIDATE_PATTERN.matcher(sql).find()) {
            return ArithmeticConversion.unchanged(sql);
        }
        ArithmeticConversion divConversion = convertMysqlDivOperators(sql);
        if (!divConversion.manualReviewReason().isBlank()) {
            return divConversion;
        }
        ArithmeticConversion slashConversion = convertSlashDivisionOperators(divConversion.convertedSql());
        List<String> appliedRules = new ArrayList<>(divConversion.appliedRules());
        for (String rule : slashConversion.appliedRules()) {
            if (!appliedRules.contains(rule)) {
                appliedRules.add(rule);
            }
        }
        return new ArithmeticConversion(
                slashConversion.convertedSql(),
                divConversion.changed() || slashConversion.changed(),
                slashConversion.manualReviewReason(),
                appliedRules
        );
    }

    private ArithmeticConversion convertMysqlDivOperators(String sql) {
        List<TextReplacement> replacements = new ArrayList<>();
        int protectedEnd = -1;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (startsMyBatisXmlTag(sql, index)) {
                index = skipMyBatisXmlTag(sql, index);
            } else if (startsKeyword(sql, index, "DIV")) {
                ArithmeticOperand left = readArithmeticLeftOperand(sql, index);
                ArithmeticOperand right = readArithmeticRightOperand(sql, index + "DIV".length());
                if (!isConvertibleArithmeticOperation(left, right) || left.startIndex() < protectedEnd) {
                    return ArithmeticConversion.manual(sql);
                }
                replacements.add(new TextReplacement(
                        left.startIndex(),
                        right.endIndex(),
                        "TRUNC(" + decimalArithmeticOperand(left.text()) + " / "
                                + nullSafeArithmeticDenominator(right.text()) + ", 0)"
                ));
                protectedEnd = right.endIndex();
                index = right.endIndex();
            } else {
                index++;
            }
        }
        GenericConversion conversion = applyTextReplacements(sql, replacements);
        return new ArithmeticConversion(
                conversion.convertedSql(),
                conversion.changed(),
                "",
                conversion.changed() ? List.of(MYSQL_DIV_OPERATOR_TO_TRUNC_DECIMAL_RULE) : List.of()
        );
    }

    private ArithmeticConversion convertSlashDivisionOperators(String sql) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (startsMyBatisXmlTag(sql, index)) {
                index = skipMyBatisXmlTag(sql, index);
            } else if (current == '/') {
                ArithmeticOperand left = readArithmeticLeftOperand(sql, index);
                ArithmeticOperand right = readArithmeticRightOperand(sql, index + 1);
                if (left != null
                        && right != null
                        && (isDecimalArithmeticExpression(left.text())
                        || isDecimalArithmeticExpression(right.text()))) {
                    index++;
                    continue;
                }
                if (!isConvertibleArithmeticOperation(left, right)) {
                    return ArithmeticConversion.manual(sql);
                }
                List<ArithmeticOperand> denominators = new ArrayList<>();
                denominators.add(right);
                int chainEnd = right.endIndex();
                while (true) {
                    int nextOperator = skipWhitespace(sql, chainEnd);
                    if (nextOperator >= sql.length() || sql.charAt(nextOperator) != '/') {
                        break;
                    }
                    ArithmeticOperand nextDenominator = readArithmeticRightOperand(sql, nextOperator + 1);
                    if (nextDenominator == null || !isSimpleArithmeticOperand(nextDenominator.text())) {
                        return ArithmeticConversion.manual(sql);
                    }
                    denominators.add(nextDenominator);
                    chainEnd = nextDenominator.endIndex();
                }
                StringBuilder replacement = new StringBuilder(decimalArithmeticOperand(left.text()));
                for (ArithmeticOperand denominator : denominators) {
                    replacement.append(" / ").append(nullSafeArithmeticDenominator(denominator.text()));
                }
                replacements.add(new TextReplacement(
                        left.startIndex(),
                        chainEnd,
                        replacement.toString()
                ));
                index = chainEnd;
            } else {
                index++;
            }
        }
        GenericConversion conversion = applyTextReplacements(sql, replacements);
        return new ArithmeticConversion(
                conversion.convertedSql(),
                conversion.changed(),
                "",
                conversion.changed() ? List.of(MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE) : List.of()
        );
    }

    private boolean isConvertibleArithmeticOperation(ArithmeticOperand left, ArithmeticOperand right) {
        return left != null
                && right != null
                && isSimpleArithmeticOperand(left.text())
                && isSimpleArithmeticOperand(right.text());
    }

    private ArithmeticOperand readArithmeticLeftOperand(String sql, int operatorIndex) {
        int end = skipWhitespaceBackward(sql, operatorIndex);
        if (end <= 0) {
            return null;
        }
        int start = arithmeticOperandStart(sql, end);
        if (start < 0) {
            return null;
        }
        start = includeUnarySign(sql, start);
        return new ArithmeticOperand(start, end, sql.substring(start, end));
    }

    private ArithmeticOperand readArithmeticRightOperand(String sql, int afterOperatorIndex) {
        int start = skipWhitespace(sql, afterOperatorIndex);
        if (start >= sql.length()) {
            return null;
        }
        int end = arithmeticOperandEnd(sql, start);
        if (end < 0) {
            return null;
        }
        return new ArithmeticOperand(start, end, sql.substring(start, end));
    }

    private int arithmeticOperandStart(String sql, int endExclusive) {
        int end = skipWhitespaceBackward(sql, endExclusive);
        if (end <= 0) {
            return -1;
        }
        char previous = sql.charAt(end - 1);
        if (previous == ')') {
            int openParenIndex = findMatchingOpenParenBackward(sql, end - 1);
            if (openParenIndex < 0) {
                return -1;
            }
            return readExpressionNameStartBeforeParen(sql, openParenIndex);
        }
        if (previous == '}') {
            return readHashMyBatisPlaceholderStartBackward(sql, end);
        }
        if (previous == '`') {
            int start = readBacktickIdentifierStartBackward(sql, end);
            return start < 0 ? -1 : extendQualifiedIdentifierStartBackward(sql, start);
        }
        if (isArithmeticIdentifierPart(previous) || previous == '.') {
            int start = end - 1;
            while (start > 0 && isArithmeticIdentifierPart(sql.charAt(start - 1))) {
                start--;
            }
            return start;
        }
        return -1;
    }

    private int arithmeticOperandEnd(String sql, int startInclusive) {
        int start = skipWhitespace(sql, startInclusive);
        if (start >= sql.length()) {
            return -1;
        }
        if ((sql.charAt(start) == '+' || sql.charAt(start) == '-')
                && start + 1 < sql.length()
                && startsArithmeticOperandWithoutSign(sql, start + 1)) {
            int signedEnd = arithmeticOperandEnd(sql, start + 1);
            return signedEnd > start + 1 ? signedEnd : -1;
        }
        if (sql.charAt(start) == '(') {
            int closeParenIndex = findMatchingParen(sql, start);
            return closeParenIndex < 0 ? -1 : closeParenIndex + 1;
        }
        if (startsHashMyBatisPlaceholder(sql, start)) {
            return skipMyBatisPlaceholder(sql, start);
        }
        if (sql.charAt(start) == '`') {
            BacktickIdentifier identifier = readBacktickIdentifier(sql, start);
            if (!identifier.closed()) {
                return -1;
            }
            return extendQualifiedIdentifierEnd(sql, identifier.nextIndex());
        }
        if (isIdentifierStart(sql.charAt(start))) {
            IdentifierToken token = readIdentifierToken(sql, start);
            if (token == null) {
                return -1;
            }
            int cursor = skipWhitespace(sql, token.endIndex());
            if (cursor < sql.length() && sql.charAt(cursor) == '(') {
                int closeParenIndex = findMatchingParen(sql, cursor);
                return closeParenIndex < 0 ? -1 : closeParenIndex + 1;
            }
            return extendQualifiedIdentifierEnd(sql, token.endIndex());
        }
        if (Character.isDigit(sql.charAt(start)) || sql.charAt(start) == '.') {
            return readNumericTokenEnd(sql, start);
        }
        return -1;
    }

    private boolean startsArithmeticOperandWithoutSign(String sql, int index) {
        if (index >= sql.length()) {
            return false;
        }
        char value = sql.charAt(index);
        return value == '('
                || value == '`'
                || value == '.'
                || Character.isDigit(value)
                || isIdentifierStart(value)
                || startsHashMyBatisPlaceholder(sql, index);
    }

    private int includeUnarySign(String sql, int operandStart) {
        int signIndex = skipWhitespaceBackward(sql, operandStart);
        if (signIndex <= 0) {
            return operandStart;
        }
        char sign = sql.charAt(signIndex - 1);
        if (sign != '+' && sign != '-') {
            return operandStart;
        }
        int beforeSign = skipWhitespaceBackward(sql, signIndex - 1);
        if (beforeSign == 0 || isUnarySignBoundary(sql.charAt(beforeSign - 1))) {
            return signIndex - 1;
        }
        return operandStart;
    }

    private boolean isUnarySignBoundary(char value) {
        return value == '('
                || value == ','
                || value == '='
                || value == '+'
                || value == '-'
                || value == '*'
                || value == '/'
                || value == '%'
                || value == '<'
                || value == '>';
    }

    private int readHashMyBatisPlaceholderStartBackward(String sql, int endExclusive) {
        int start = readMyBatisPlaceholderStartBackward(sql, endExclusive);
        if (start < 0 || sql.charAt(start) != '#') {
            return -1;
        }
        return start;
    }

    private boolean startsHashMyBatisPlaceholder(String sql, int index) {
        return index + 1 < sql.length() && sql.charAt(index) == '#' && sql.charAt(index + 1) == '{';
    }

    private int readBacktickIdentifierStartBackward(String sql, int endExclusive) {
        int start = endExclusive - 2;
        while (start >= 0) {
            if (sql.charAt(start) == '`') {
                return start;
            }
            start--;
        }
        return -1;
    }

    private int extendQualifiedIdentifierStartBackward(String sql, int start) {
        int cursor = skipWhitespaceBackward(sql, start);
        if (cursor <= 0 || sql.charAt(cursor - 1) != '.') {
            return start;
        }
        int qualifierEnd = skipWhitespaceBackward(sql, cursor - 1);
        if (qualifierEnd <= 0) {
            return start;
        }
        int qualifierStart;
        if (sql.charAt(qualifierEnd - 1) == '`') {
            qualifierStart = readBacktickIdentifierStartBackward(sql, qualifierEnd);
        } else {
            qualifierStart = qualifierEnd - 1;
            while (qualifierStart > 0 && isArithmeticIdentifierPart(sql.charAt(qualifierStart - 1))) {
                qualifierStart--;
            }
        }
        return qualifierStart < 0 ? start : extendQualifiedIdentifierStartBackward(sql, qualifierStart);
    }

    private int extendQualifiedIdentifierEnd(String sql, int end) {
        int cursor = skipWhitespace(sql, end);
        if (cursor >= sql.length() || sql.charAt(cursor) != '.') {
            return end;
        }
        int nextStart = skipWhitespace(sql, cursor + 1);
        if (nextStart >= sql.length()) {
            return end;
        }
        if (sql.charAt(nextStart) == '`') {
            BacktickIdentifier identifier = readBacktickIdentifier(sql, nextStart);
            return identifier.closed() ? extendQualifiedIdentifierEnd(sql, identifier.nextIndex()) : end;
        }
        IdentifierToken token = readIdentifierToken(sql, nextStart);
        return token == null ? end : extendQualifiedIdentifierEnd(sql, token.endIndex());
    }

    private int readNumericTokenEnd(String sql, int start) {
        int index = start;
        boolean seenDot = false;
        while (index < sql.length()) {
            char value = sql.charAt(index);
            if (Character.isDigit(value)) {
                index++;
            } else if (value == '.' && !seenDot) {
                seenDot = true;
                index++;
            } else if (value == 'e' || value == 'E') {
                index++;
                if (index < sql.length() && (sql.charAt(index) == '+' || sql.charAt(index) == '-')) {
                    index++;
                }
                while (index < sql.length() && Character.isDigit(sql.charAt(index))) {
                    index++;
                }
                break;
            } else {
                break;
            }
        }
        return index;
    }

    private boolean isSimpleArithmeticOperand(String expression) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.startsWith("${") || trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            return false;
        }
        if (trimmed.matches("(?is)[+-]?\\d+(?:\\.\\d+)?e[+-]?\\d+")) {
            return false;
        }
        if (trimmed.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")) {
            return true;
        }
        if (trimmed.startsWith("#{") && trimmed.endsWith("}")) {
            return true;
        }
        if (trimmed.startsWith("(") && matchingFinalParen(trimmed)) {
            String body = trimmed.substring(1, trimmed.length() - 1);
            return !containsKeywordOutsideIgnoredText(body, "SELECT")
                    && !containsKeywordOutsideIgnoredText(body, "FROM")
                    && !containsKeywordOutsideIgnoredText(body, "JOIN");
        }
        FunctionCall functionCall = readArithmeticOnlyFunctionCall(trimmed);
        if (functionCall != null) {
            String functionName = trimmed.substring(0, functionCall.openParenIndex()).trim().toUpperCase(Locale.ROOT);
            return SIMPLE_ARITHMETIC_FUNCTION_OPERANDS.contains(functionName)
                    || isDecimalCastFunction(trimmed, functionCall);
        }
        return isSimpleQualifiedIdentifier(trimmed);
    }

    private FunctionCall readArithmeticOnlyFunctionCall(String expression) {
        int leadingWhitespace = leadingWhitespaceLength(expression);
        IdentifierToken token = readIdentifierToken(expression, leadingWhitespace);
        if (token == null) {
            return null;
        }
        int openParenIndex = skipWhitespace(expression, token.endIndex());
        if (openParenIndex >= expression.length() || expression.charAt(openParenIndex) != '(') {
            return null;
        }
        int closeParenIndex = findMatchingParen(expression, openParenIndex);
        if (closeParenIndex < 0 || !expression.substring(closeParenIndex + 1).isBlank()) {
            return null;
        }
        return new FunctionCall(leadingWhitespace, openParenIndex, closeParenIndex, closeParenIndex + 1,
                expression.substring(openParenIndex + 1, closeParenIndex));
    }

    private boolean isDecimalCastFunction(String expression, FunctionCall functionCall) {
        String functionName = expression.substring(0, functionCall.openParenIndex()).trim();
        if (!functionName.equalsIgnoreCase("CAST")) {
            return false;
        }
        return Pattern.compile("(?is)\\s+AS\\s+(?:DECIMAL|NUMERIC|NUMBER|DOUBLE|FLOAT|REAL)\\b")
                .matcher(functionCall.body())
                .find();
    }

    private boolean matchingFinalParen(String expression) {
        int close = findMatchingParen(expression, 0);
        return close == expression.length() - 1;
    }

    private boolean isSimpleQualifiedIdentifier(String expression) {
        int index = 0;
        boolean readToken = false;
        while (index < expression.length()) {
            if (expression.charAt(index) == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(expression, index);
                if (!identifier.closed()) {
                    return false;
                }
                index = identifier.nextIndex();
                readToken = true;
            } else {
                IdentifierToken token = readIdentifierToken(expression, index);
                if (token == null) {
                    return false;
                }
                index = token.endIndex();
                readToken = true;
            }
            if (index == expression.length()) {
                return readToken;
            }
            if (expression.charAt(index) != '.') {
                return false;
            }
            index++;
            if (index == expression.length()) {
                return false;
            }
        }
        return readToken;
    }

    private String decimalArithmeticOperand(String expression) {
        String trimmed = expression.trim();
        if (isDecimalArithmeticExpression(trimmed)) {
            return trimmed;
        }
        return "CAST(" + trimmed + " AS " + DECIMAL_ARITHMETIC_TYPE + ")";
    }

    private String nullSafeArithmeticDenominator(String expression) {
        String trimmed = expression.trim();
        if (isNullifExpression(trimmed)) {
            return trimmed;
        }
        if (isDecimalArithmeticExpression(trimmed)) {
            return "NULLIF(" + trimmed + ", 0)";
        }
        return "NULLIF(CAST(" + trimmed + " AS " + DECIMAL_ARITHMETIC_TYPE + "), 0)";
    }

    private boolean isDecimalArithmeticExpression(String expression) {
        String trimmed = expression.trim();
        if (trimmed.matches("[+-]?(?:\\d+\\.\\d*|\\.\\d+)")) {
            return true;
        }
        FunctionCall functionCall = readArithmeticOnlyFunctionCall(trimmed);
        return functionCall != null && isDecimalCastFunction(trimmed, functionCall);
    }

    private boolean isNullifExpression(String expression) {
        return readOnlyFunctionCall(expression.trim(), "NULLIF") != null;
    }

    private boolean isArithmeticIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '.';
    }

    private boolean startsMyBatisXmlTag(String sql, int index) {
        if (index >= sql.length() || sql.charAt(index) != '<') {
            return false;
        }
        int cursor = index + 1;
        if (cursor < sql.length() && sql.charAt(cursor) == '/') {
            cursor++;
        }
        if (cursor >= sql.length() || !Character.isLetter(sql.charAt(cursor))) {
            return false;
        }
        int nameStart = cursor;
        while (cursor < sql.length() && Character.isLetter(sql.charAt(cursor))) {
            cursor++;
        }
        String tagName = sql.substring(nameStart, cursor).toLowerCase(Locale.ROOT);
        if (!Set.of(
                "bind",
                "choose",
                "foreach",
                "if",
                "include",
                "otherwise",
                "selectkey",
                "set",
                "sql",
                "trim",
                "when",
                "where"
        ).contains(tagName)) {
            return false;
        }
        return findMyBatisXmlTagEnd(sql, cursor) >= 0;
    }

    private int skipMyBatisXmlTag(String sql, int index) {
        int end = findMyBatisXmlTagEnd(sql, index + 1);
        return end < 0 ? index + 1 : end + 1;
    }

    private int findMyBatisXmlTagEnd(String sql, int start) {
        char quote = '\0';
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (quote != '\0') {
                if (current == quote) {
                    quote = '\0';
                }
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '>') {
                return index;
            }
            index++;
        }
        return -1;
    }

    private String unsupportedReason(String sql) {
        if (containsMysqlUpdateJoin(sql) && !isDamengNativeSingleTargetUpdateJoin(sql)) {
            if (containsUnresolvedOuterOrCrossUpdateJoin(sql)) {
                return "MySQL outer/cross UPDATE JOIN could not be converted safely: an equivalent "
                        + "Dameng rewrite requires proof that each target row receives values from at "
                        + "most one source row. That cardinality cannot be proven from this SQL; fix "
                        + "the original join/deduplication or provide a real uniqueness guarantee "
                        + "instead of choosing an arbitrary source row.";
            }
            return "MySQL UPDATE JOIN shape could not be converted safely to Dameng UPDATE FROM.";
        }
        if (sql.contains("<=>")
                && containsPatternOutsideIgnoredText(sql, Pattern.compile("<=>"))) {
            return "MySQL null-safe equality <=> could not be parsed safely for automatic Dameng rewrite.";
        }
        if (ON_DUPLICATE_KEY_UPDATE_PATTERN.matcher(sql).find()
                && containsPatternOutsideIgnoredText(sql, ON_DUPLICATE_KEY_UPDATE_PATTERN)) {
            return "ON DUPLICATE KEY UPDATE requires configured keyColumns for safe Dameng MERGE rewrite.";
        }
        if (REPLACE_INTO_PATTERN.matcher(sql).find()
                && containsPatternOutsideIgnoredText(sql, REPLACE_INTO_PATTERN)) {
            return "REPLACE INTO has no safe automatic Dameng rewrite in MVP.";
        }
        if (GROUP_CONCAT_PATTERN.matcher(sql).find()) {
            return "GROUP_CONCAT requires manual confirmation for Dameng aggregate syntax.";
        }
        if (containsMysqlMetadataOutsideIgnoredText(sql)) {
            return "MySQL metadata SQL such as information_schema/database() requires manual Dameng rewrite.";
        }
        if (containsKeywordOutsideIgnoredText(sql, "REGEXP")) {
            return "REGEXP requires manual confirmation because Dameng regular-expression syntax may differ from MySQL.";
        }
        if (INSERT_IGNORE_PATTERN.matcher(sql).find()
                && containsPatternOutsideIgnoredText(sql, INSERT_IGNORE_PATTERN)) {
            return "INSERT IGNORE requires configured keyColumns for safe Dameng MERGE rewrite.";
        }
        String statefulUserVariableReason = interdependentUserVariableAssignmentReason(sql);
        if (!statefulUserVariableReason.isBlank()) {
            return statefulUserVariableReason;
        }
        if (containsMysqlUserVariable(sql)) {
            return "MySQL user variables such as @var require ROW_NUMBER, explicit variables, or procedure-level rewrite for Dameng.";
        }
        String mysqlFunction = firstMySqlFunctionRequiringReview(sql);
        if (!mysqlFunction.isBlank()) {
            return mysqlFunction + " requires manual confirmation because Dameng support or syntax may differ from MySQL.";
        }
        return "";
    }

    public boolean isDamengNativeSingleTargetUpdateJoin(String sql) {
        long statementCount = splitTopLevelStatements(sql).stream()
                .filter(statement -> !statement.sql().isBlank())
                .count();
        if (statementCount != 1) {
            return false;
        }
        int updateIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, updateIndex, "UPDATE")) {
            return false;
        }
        int joinIndex = findTopLevelKeyword(sql, "JOIN", updateIndex + "UPDATE".length());
        if (joinIndex < 0) {
            return false;
        }
        int setIndex = findTopLevelKeyword(sql, "SET", joinIndex + "JOIN".length());
        if (setIndex < 0) {
            return false;
        }
        int joinTypeStart = joinTypeStart(sql, joinIndex);
        String target = sql.substring(updateIndex + "UPDATE".length(), joinTypeStart).strip();
        String targetAlias = updateTargetAlias(target);
        if (targetAlias.isBlank()) {
            return false;
        }
        String joinSource = sql.substring(joinIndex + "JOIN".length(), setIndex).strip();
        UpdateJoinChain chain = updateJoinChain(target, joinSource, true);
        if (chain == null
                || chain.tables().size() < 2
                || !chain.tables().get(0).aliasKey().equals(targetAlias)) {
            return false;
        }
        int whereIndex = findTopLevelKeyword(sql, "WHERE", setIndex + "SET".length());
        int statementEnd = stripTrailingSemicolon(sql);
        String setClause = sql.substring(
                setIndex + "SET".length(),
                whereIndex < 0 ? statementEnd : whereIndex
        ).strip();
        if (setClause.isBlank()) {
            return false;
        }
        Set<String> tableAliases = chain.tables().stream()
                .map(UpdateJoinTable::aliasKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> updatedAliases = new LinkedHashSet<>();
        for (TopLevelArgument assignment : splitTopLevelArguments(setClause)) {
            Matcher matcher = UPDATE_SET_QUALIFIED_ASSIGNMENT.matcher(assignment.text());
            if (!matcher.find()) {
                return false;
            }
            String updatedAlias = normalizeIdentifierKey(matcher.group("alias"));
            if (!tableAliases.contains(updatedAlias)) {
                return false;
            }
            updatedAliases.add(updatedAlias);
        }
        return updatedAliases.size() == 1;
    }

    private boolean containsTopLevelLeftJoin(String updateJoinClause) {
        int searchFrom = 0;
        while (searchFrom < updateJoinClause.length()) {
            int joinIndex = findTopLevelKeyword(updateJoinClause, "JOIN", searchFrom);
            if (joinIndex < 0) {
                return false;
            }
            int typeStart = joinTypeStart(updateJoinClause, joinIndex);
            String joinType = updateJoinClause.substring(typeStart, joinIndex).strip();
            if (Pattern.compile("(?is)^LEFT(?:\\s+OUTER)?$")
                    .matcher(joinType)
                    .matches()) {
                return true;
            }
            searchFrom = joinIndex + "JOIN".length();
        }
        return false;
    }

    private boolean containsMysqlMetadataOutsideIgnoredText(String sql) {
        return containsKeywordOutsideIgnoredText(sql, "INFORMATION_SCHEMA")
                || containsDatabaseFunctionOutsideIgnoredText(sql);
    }

    private boolean containsDatabaseFunctionOutsideIgnoredText(String sql) {
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (startsKeyword(sql, index, "DATABASE")) {
                int cursor = skipWhitespace(sql, index + "DATABASE".length());
                if (cursor < sql.length() && sql.charAt(cursor) == '(') {
                    int closeParen = skipWhitespace(sql, cursor + 1);
                    if (closeParen < sql.length() && sql.charAt(closeParen) == ')') {
                        return true;
                    }
                }
                index += "DATABASE".length();
            } else {
                index++;
            }
        }
        return false;
    }

    private boolean containsMysqlUserVariable(String sql) {
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '@') {
                int next = index + 1;
                if (next < sql.length()
                        && sql.charAt(next) != '@'
                        && isMysqlUserVariablePart(sql.charAt(next))) {
                    return true;
                }
                index++;
            } else {
                index++;
            }
        }
        return false;
    }

    private String interdependentUserVariableAssignmentReason(String sql) {
        String source = sql == null ? "" : sql;
        List<UserVariableAssignmentPosition> assignments = mysqlUserVariableAssignments(source);
        LinkedHashSet<String> assignedNames = new LinkedHashSet<>();
        for (UserVariableAssignmentPosition assignment : assignments) {
            assignedNames.add(assignment.name());
        }
        if (assignedNames.size() < 2) {
            return "";
        }
        boolean interdependent = false;
        for (int index = 0; index < assignments.size() && !interdependent; index++) {
            UserVariableAssignmentPosition assignment = assignments.get(index);
            int expressionEnd = index + 1 < assignments.size()
                    ? assignments.get(index + 1).startIndex()
                    : source.length();
            String expression = source.substring(assignment.expressionStartIndex(), expressionEnd);
            for (String assignedName : assignedNames) {
                if (assignedName.equals(assignment.name())) {
                    continue;
                }
                if (containsMysqlUserVariableReference(expression, assignedName, 0, 0)) {
                    interdependent = true;
                    break;
                }
            }
        }
        if (!interdependent) {
            return "";
        }
        List<String> displayNames = assignedNames.stream()
                .map(name -> "@" + name)
                .toList();
        return "Original SQL depends on the evaluation order of interdependent MySQL user-variable "
                + "assignments (" + String.join(", ", displayNames) + ") within one statement. "
                + "MySQL does not provide a stable SQL evaluation-order semantic for this pattern, "
                + "so an automatic Dameng rewrite would guess business intent. Fix the original SQL "
                + "with an explicit window/gaps-and-islands query, or provide the intended row order "
                + "and grouping semantics.";
    }

    private List<UserVariableAssignmentPosition> mysqlUserVariableAssignments(String sql) {
        List<UserVariableAssignmentPosition> assignments = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '@' && (index + 1 >= sql.length() || sql.charAt(index + 1) != '@')) {
                int nameStart = index + 1;
                int nameEnd = nameStart;
                while (nameEnd < sql.length() && isMysqlUserVariableNamePart(sql.charAt(nameEnd))) {
                    nameEnd++;
                }
                int assignmentOperator = skipWhitespace(sql, nameEnd);
                if (nameEnd > nameStart
                        && assignmentOperator + 1 < sql.length()
                        && sql.charAt(assignmentOperator) == ':'
                        && sql.charAt(assignmentOperator + 1) == '=') {
                    assignments.add(new UserVariableAssignmentPosition(
                            sql.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT),
                            index,
                            assignmentOperator + 2
                    ));
                    index = assignmentOperator + 2;
                } else {
                    index = nameEnd > nameStart ? nameEnd : index + 1;
                }
            } else {
                index++;
            }
        }
        return assignments;
    }

    private boolean isMysqlUserVariablePart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '.' || value == '`';
    }

    private GenericConversion removeUnusedUserVariableSelectItems(String sql) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (startsKeyword(sql, index, "SELECT")) {
                addUnusedUserVariableSelectItemReplacement(sql, index, replacements);
                index += "SELECT".length();
            } else {
                index++;
            }
        }
        return applyTextReplacements(sql, replacements);
    }

    private void addUnusedUserVariableSelectItemReplacement(
            String sql,
            int selectIndex,
            List<TextReplacement> replacements
    ) {
        if (previousNonWhitespace(sql, selectIndex) != '(') {
            return;
        }
        int selectListStart = selectIndex + "SELECT".length();
        int fromIndex = findTopLevelKeyword(sql, "FROM", selectListStart);
        if (fromIndex < 0) {
            return;
        }
        String selectList = sql.substring(selectListStart, fromIndex);
        List<TopLevelArgument> items = splitTopLevelArguments(selectList);
        if (items.size() < 2) {
            return;
        }
        List<TopLevelArgument> keptItems = new ArrayList<>();
        boolean removed = false;
        for (TopLevelArgument item : items) {
            UserVariableInitialization initialization = userVariableInitializationSelectItem(item.text());
            int itemStart = selectListStart + item.startIndex();
            int itemEnd = selectListStart + item.endIndex();
            if (initialization != null
                    && !containsMysqlUserVariableReference(sql, initialization.name(), itemStart, itemEnd)) {
                removed = true;
            } else {
                keptItems.add(item);
            }
        }
        if (!removed || keptItems.isEmpty()) {
            return;
        }
        replacements.add(new TextReplacement(
                selectListStart,
                fromIndex,
                rebuildSelectList(selectList, keptItems)
        ));
    }

    private String rebuildSelectList(String originalSelectList, List<TopLevelArgument> keptItems) {
        StringBuilder rebuilt = new StringBuilder(originalSelectList.length());
        String leadingWhitespace = leadingWhitespace(originalSelectList);
        TopLevelArgument first = keptItems.get(0);
        if (first.startIndex() > 0) {
            rebuilt.append(leadingWhitespace);
            rebuilt.append(first.text().stripLeading());
        } else {
            rebuilt.append(first.text());
        }
        for (int i = 1; i < keptItems.size(); i++) {
            rebuilt.append(',');
            rebuilt.append(keptItems.get(i).text());
        }
        return rebuilt.toString();
    }

    private UserVariableInitialization userVariableInitializationSelectItem(String item) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*@(?<name>[A-Za-z_][A-Za-z0-9_$]*)\\s*:=\\s*"
                        + "(?:[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)|NULL|TRUE|FALSE|'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\")\\s*$"
        ).matcher(item == null ? "" : item);
        return matcher.matches() ? new UserVariableInitialization(matcher.group("name")) : null;
    }

    private boolean containsMysqlUserVariableReference(
            String sql,
            String variableName,
            int excludedStart,
            int excludedEnd
    ) {
        int index = 0;
        while (index < sql.length()) {
            if (index >= excludedStart && index < excludedEnd) {
                index = excludedEnd;
                continue;
            }
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '@') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '@') {
                    index += 2;
                    continue;
                }
                int nameStart = index + 1;
                int nameEnd = nameStart;
                while (nameEnd < sql.length() && isMysqlUserVariableNamePart(sql.charAt(nameEnd))) {
                    nameEnd++;
                }
                if (nameEnd > nameStart
                        && sql.substring(nameStart, nameEnd).equalsIgnoreCase(variableName)) {
                    return true;
                }
                index = nameEnd > nameStart ? nameEnd : index + 1;
            } else {
                index++;
            }
        }
        return false;
    }

    private boolean isMysqlUserVariableNamePart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private GenericConversion removeTrailingStatementSemicolons(String sql) {
        if (sql == null || sql.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String stripped = sql.stripLeading();
        if (startsKeyword(stripped, 0, "BEGIN") || startsKeyword(stripped, 0, "DECLARE")) {
            return GenericConversion.unchanged(sql);
        }

        int trailingStart = sql.length();
        while (trailingStart > 0 && Character.isWhitespace(sql.charAt(trailingStart - 1))) {
            trailingStart--;
        }
        int contentEnd = trailingStart;
        int semicolonCount = 0;
        while (contentEnd > 0 && sql.charAt(contentEnd - 1) == ';') {
            semicolonCount++;
            contentEnd--;
            while (contentEnd > 0 && Character.isWhitespace(sql.charAt(contentEnd - 1))) {
                contentEnd--;
            }
        }
        if (semicolonCount < 2) {
            return GenericConversion.unchanged(sql);
        }
        return new GenericConversion(sql.substring(0, contentEnd) + sql.substring(trailingStart), true);
    }

    private String leadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(0, index);
    }

    private GenericConversion convertSingleQuotedAliases(String sql) {
        if (!SINGLE_QUOTED_ALIAS_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
        }
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

    private boolean hasPotentialSlashDivision(String sql) {
        int slash = sql.indexOf('/');
        while (slash >= 0) {
            int lineStart = Math.max(sql.lastIndexOf('\n', slash), sql.lastIndexOf('\r', slash)) + 1;
            int lineEnd = sql.indexOf('\n', slash + 1);
            if (lineEnd < 0) {
                lineEnd = sql.length();
            }
            if (!sql.substring(lineStart, lineEnd).strip().equals("/")) {
                return true;
            }
            slash = sql.indexOf('/', slash + 1);
        }
        return false;
    }

    private GenericConversion quoteNumericLeadingSelectAliases(String sql) {
        if (!SELECT_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
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
            } else if (Character.isDigit(current)
                    && (index == 0 || !isIdentifierPart(sql.charAt(index - 1)))) {
                int end = index + 1;
                boolean hasNonDigit = false;
                while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
                    hasNonDigit = hasNonDigit || !Character.isDigit(sql.charAt(end));
                    end++;
                }
                int next = skipWhitespace(sql, end);
                boolean aliasTerminator = next >= sql.length()
                        || sql.charAt(next) == ','
                        || startsKeyword(sql, next, "FROM");
                if (hasNonDigit
                        && previousNonWhitespace(sql, index) == ')'
                        && aliasTerminator
                        && (isInsideSelectList(sql, index)
                        || isLikelySelectListFragmentAliasPosition(sql, index))) {
                    converted.append("AS ").append(quoteDamengIdentifier(sql.substring(index, end)));
                    index = end;
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
            if (Set.of("COMMENT", "DEFAULT", "LEADING", "TRAILING", "BOTH").contains(upper)) {
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
        if (parenthesisDepthBefore(sql, quoteIndex) > 0) {
            return false;
        }
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

    private int parenthesisDepthBefore(String sql, int targetIndex) {
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
                depth = Math.max(0, depth - 1);
                index++;
            } else {
                index++;
            }
        }
        return depth;
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
        if (cursor < sql.length() && sql.charAt(cursor) == '\'') {
            int end = skipSingleQuotedString(sql, cursor);
            if (end <= cursor + 2) {
                return -1;
            }
            cursor = end;
        } else if (cursor < sql.length() && sql.charAt(cursor) == '"') {
            int end = skipDoubleQuotedText(sql, cursor);
            if (end <= cursor + 2) {
                return -1;
            }
            cursor = end;
        } else {
            while (cursor < sql.length() && isIdentifierPart(sql.charAt(cursor))) {
                cursor++;
            }
            if (cursor == collationStart) {
                return -1;
            }
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

    private CharacterSetClause readMysqlCharacterSetClause(String sql, int index) {
        int cursor;
        if (startsKeyword(sql, index, "CHARACTER")) {
            int setIndex = skipWhitespace(sql, index + "CHARACTER".length());
            if (!startsKeyword(sql, setIndex, "SET")) {
                return null;
            }
            cursor = skipWhitespace(sql, setIndex + "SET".length());
            if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                cursor = skipWhitespace(sql, cursor + 1);
            }
        } else if (startsKeyword(sql, index, "CHARSET")) {
            cursor = skipWhitespace(sql, index + "CHARSET".length());
            if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                cursor = skipWhitespace(sql, cursor + 1);
            }
        } else {
            return null;
        }
        String charsetName;
        if (cursor < sql.length() && sql.charAt(cursor) == '\'') {
            int end = skipSingleQuotedString(sql, cursor);
            if (end <= cursor + 2) {
                return null;
            }
            charsetName = sql.substring(cursor + 1, end - 1);
            cursor = end;
        } else if (cursor < sql.length() && sql.charAt(cursor) == '"') {
            int end = skipDoubleQuotedText(sql, cursor);
            if (end <= cursor + 2) {
                return null;
            }
            charsetName = sql.substring(cursor + 1, end - 1);
            cursor = end;
        } else {
            int charsetStart = cursor;
            while (cursor < sql.length() && isIdentifierPart(sql.charAt(cursor))) {
                cursor++;
            }
            if (cursor == charsetStart) {
                return null;
            }
            charsetName = sql.substring(charsetStart, cursor);
        }
        return new CharacterSetClause(charsetName, skipWhitespace(sql, cursor));
    }

    private int readMysqlCharacterSetClauseEnd(String sql, int index) {
        CharacterSetClause clause = readMysqlCharacterSetClause(sql, index);
        return clause == null ? -1 : clause.endIndex();
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
                    int optionEnd = skipMysqlTableOptionValue(sql, afterKeyword + 1);
                    converted.append(sql, index, optionEnd);
                    index = optionEnd;
                } else {
                    converted.append("IDENTITY(1,1)");
                    changed = true;
                    index += "AUTO_INCREMENT".length();
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private GenericConversion removeRedundantIdentityPrimaryKeyUnique(String sql) {
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

        List<TopLevelArgument> definitions = splitTopLevelArguments(sql.substring(openParenIndex + 1, closeParenIndex));
        Set<String> primaryKeyColumns = tablePrimaryKeyColumns(definitions);
        if (primaryKeyColumns.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }

        List<String> convertedDefinitions = new ArrayList<>(definitions.size());
        boolean changed = false;
        for (TopLevelArgument definition : definitions) {
            int cursor = skipWhitespace(definition.text(), 0);
            IdentifierToken column = readIdentifierToken(definition.text(), cursor);
            if (column == null
                    || !primaryKeyColumns.contains(normalizeIdentifierKey(unquoteIdentifier(column.text())))) {
                convertedDefinitions.add(definition.text());
                continue;
            }
            String attributes = definition.text().substring(column.endIndex());
            if (!Pattern.compile("(?is)\\bIDENTITY\\s*\\(").matcher(attributes).find()) {
                convertedDefinitions.add(definition.text());
                continue;
            }
            GenericConversion uniqueConversion = removeTopLevelUniqueAttribute(attributes);
            convertedDefinitions.add(
                    uniqueConversion.changed()
                            ? definition.text().substring(0, column.endIndex()) + uniqueConversion.convertedSql()
                            : definition.text()
            );
            changed |= uniqueConversion.changed();
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        return new GenericConversion(
                sql.substring(0, openParenIndex + 1)
                        + String.join(",", convertedDefinitions)
                        + sql.substring(closeParenIndex),
                true
        );
    }

    private Set<String> tablePrimaryKeyColumns(List<TopLevelArgument> definitions) {
        Set<String> columns = new LinkedHashSet<>();
        for (TopLevelArgument definition : definitions) {
            String text = definition.text();
            int cursor = skipWhitespace(text, 0);
            if (startsKeyword(text, cursor, "CONSTRAINT")) {
                cursor = skipWhitespace(text, cursor + "CONSTRAINT".length());
                IdentifierToken constraintName = readIdentifierToken(text, cursor);
                if (constraintName == null) {
                    continue;
                }
                cursor = skipWhitespace(text, constraintName.endIndex());
            }
            if (!startsKeyword(text, cursor, "PRIMARY")) {
                continue;
            }
            cursor = skipWhitespace(text, cursor + "PRIMARY".length());
            if (!startsKeyword(text, cursor, "KEY")) {
                continue;
            }
            int openParenIndex = findTopLevelChar(text, '(', cursor + "KEY".length());
            if (openParenIndex < 0) {
                continue;
            }
            int closeParenIndex = findMatchingParen(text, openParenIndex);
            if (closeParenIndex < 0) {
                continue;
            }
            for (TopLevelArgument columnArgument
                    : splitTopLevelArguments(text.substring(openParenIndex + 1, closeParenIndex))) {
                int columnStart = skipWhitespace(columnArgument.text(), 0);
                IdentifierToken column = readIdentifierToken(columnArgument.text(), columnStart);
                if (column != null
                        && columnArgument.text().substring(column.endIndex()).isBlank()) {
                    columns.add(normalizeIdentifierKey(unquoteIdentifier(column.text())));
                }
            }
        }
        return columns;
    }

    private GenericConversion removeTopLevelUniqueAttribute(String attributes) {
        int depth = 0;
        int index = 0;
        while (index < attributes.length()) {
            char current = attributes.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(attributes, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(attributes, index);
            } else if (startsMyBatisPlaceholder(attributes, index)) {
                index = skipMyBatisPlaceholder(attributes, index);
            } else if (startsLineComment(attributes, index)) {
                index = skipUntilLineEnd(attributes, index);
            } else if (startsBlockComment(attributes, index)) {
                index = skipUntilBlockCommentEnd(attributes, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (depth == 0 && startsKeyword(attributes, index, "UNIQUE")) {
                int removalStart = index;
                while (removalStart > 0 && Character.isWhitespace(attributes.charAt(removalStart - 1))) {
                    removalStart--;
                }
                int removalEnd = skipWhitespace(attributes, index + "UNIQUE".length());
                if (startsKeyword(attributes, removalEnd, "KEY")) {
                    removalEnd += "KEY".length();
                } else {
                    removalEnd = index + "UNIQUE".length();
                }
                return new GenericConversion(
                        attributes.substring(0, removalStart) + attributes.substring(removalEnd),
                        true
                );
            } else {
                index++;
            }
        }
        return GenericConversion.unchanged(attributes);
    }

    private GenericConversion normalizeIdentityInlinePrimaryKeys(String sql) {
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

        List<TopLevelArgument> definitions = splitTopLevelArguments(sql.substring(openParenIndex + 1, closeParenIndex));
        List<String> convertedDefinitions = new ArrayList<>();
        List<String> primaryKeyColumns = new ArrayList<>();
        boolean changed = false;
        for (TopLevelArgument definition : definitions) {
            IdentityPrimaryKeyColumn column = identityInlinePrimaryKeyColumn(definition.text());
            if (column == null) {
                convertedDefinitions.add(definition.text());
                continue;
            }
            convertedDefinitions.add(column.definitionWithoutInlinePrimaryKey());
            primaryKeyColumns.add(column.columnName());
            changed = true;
        }
        if (!changed || hasTablePrimaryKey(convertedDefinitions)) {
            return GenericConversion.unchanged(sql);
        }
        convertedDefinitions.add(" PRIMARY KEY (" + String.join(", ", primaryKeyColumns) + ")");
        return new GenericConversion(
                sql.substring(0, openParenIndex + 1)
                        + String.join(",", convertedDefinitions)
                        + sql.substring(closeParenIndex),
                true
        );
    }

    private IdentityPrimaryKeyColumn identityInlinePrimaryKeyColumn(String definition) {
        int cursor = skipWhitespace(definition, 0);
        IdentifierToken column = readIdentifierToken(definition, cursor);
        if (column == null) {
            return null;
        }
        String attributes = definition.substring(column.endIndex());
        if (!Pattern.compile("(?is)\\bIDENTITY\\s*\\(").matcher(attributes).find()
                || !Pattern.compile("(?is)\\bPRIMARY\\s+KEY\\b").matcher(attributes).find()) {
            return null;
        }
        String convertedAttributes = Pattern.compile("(?is)\\s+PRIMARY\\s+KEY\\b")
                .matcher(attributes)
                .replaceFirst("");
        return new IdentityPrimaryKeyColumn(
                column.text(),
                definition.substring(0, column.endIndex()) + convertedAttributes
        );
    }

    private boolean hasTablePrimaryKey(List<String> definitions) {
        for (String definition : definitions) {
            int cursor = skipWhitespace(definition, 0);
            if (startsKeyword(definition, cursor, "PRIMARY")) {
                return true;
            }
        }
        return false;
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
        if (!isCreateTableCandidate(sql)) {
            return GenericConversion.unchanged(sql);
        }
        int createIndex = findTopLevelKeyword(sql, "CREATE", 0);
        if (createIndex < 0) {
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

        String trailingOptions = sql.substring(closeParenIndex + 1);
        GenericConversion trailingConversion = removeMysqlCreateTableTrailingOptions(trailingOptions);
        if (!trailingConversion.changed()) {
            return GenericConversion.unchanged(sql);
        }
        return new GenericConversion(
                sql.substring(0, closeParenIndex + 1) + trailingConversion.convertedSql(),
                true
        );
    }

    private boolean isCreateTableCandidate(String sql) {
        int createIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, createIndex, "CREATE")) {
            return startsLineComment(sql, createIndex) || startsBlockComment(sql, createIndex)
                    ? CREATE_TABLE_CANDIDATE_PATTERN.matcher(sql).find()
                    : false;
        }
        int cursor = skipWhitespace(sql, createIndex + "CREATE".length());
        if (startsKeyword(sql, cursor, "TEMPORARY")) {
            cursor = skipWhitespace(sql, cursor + "TEMPORARY".length());
        }
        if (startsKeyword(sql, cursor, "TABLE")) {
            return true;
        }
        return startsLineComment(sql, cursor) || startsBlockComment(sql, cursor)
                ? CREATE_TABLE_CANDIDATE_PATTERN.matcher(sql).find()
                : false;
    }

    private GenericConversion removeMysqlCreateTableTrailingOptions(String trailingOptions) {
        StringBuilder converted = new StringBuilder(trailingOptions.length());
        boolean changed = false;
        int index = 0;
        while (index < trailingOptions.length()) {
            char current = trailingOptions.charAt(index);
            if (current == '\'') {
                index = appendSingleQuotedString(trailingOptions, index, converted);
            } else if (current == '"') {
                index = appendDoubleQuotedText(trailingOptions, index, converted);
            } else if (startsMyBatisPlaceholder(trailingOptions, index)) {
                index = appendMyBatisPlaceholder(trailingOptions, index, converted);
            } else if (startsLineComment(trailingOptions, index)) {
                index = appendUntilLineEnd(trailingOptions, index, converted);
            } else if (startsBlockComment(trailingOptions, index)) {
                index = appendUntilBlockCommentEnd(trailingOptions, index, converted);
            } else if (current == ';') {
                converted.append(trailingOptions, index, trailingOptions.length());
                break;
            } else {
                int optionEnd = readMysqlCreateTableTrailingOptionEnd(trailingOptions, index);
                if (optionEnd < 0) {
                    converted.append(current);
                    index++;
                } else {
                    index = optionEnd;
                    changed = true;
                    appendSpaceBeforeNextTokenIfNeeded(converted, trailingOptions, index);
                }
            }
        }
        return new GenericConversion(changed ? converted.toString() : trailingOptions, changed);
    }

    private int readMysqlCreateTableTrailingOptionEnd(String sql, int index) {
        if (startsKeyword(sql, index, "ENGINE")
                || startsKeyword(sql, index, "ROW_FORMAT")
                || startsKeyword(sql, index, "AUTO_INCREMENT")) {
            int cursor = skipWhitespace(sql, index + readIdentifierToken(sql, index).text().length());
            if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                cursor++;
            }
            int end = skipMysqlTableOptionValue(sql, cursor);
            return end > skipWhitespace(sql, cursor) ? end : -1;
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
        if (startsKeyword(sql, index, "CHARSET") || startsKeyword(sql, index, "CHARACTER")) {
            return readMysqlCharacterSetClauseEnd(sql, index);
        }
        if (startsKeyword(sql, index, "COLLATE")) {
            return readMysqlCollateClauseEnd(sql, index);
        }
        if (startsKeyword(sql, index, "COMMENT")) {
            int cursor = skipWhitespace(sql, index + "COMMENT".length());
            if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                cursor = skipWhitespace(sql, cursor + 1);
            }
            if (cursor < sql.length() && sql.charAt(cursor) == '\'') {
                return skipWhitespace(sql, skipSingleQuotedString(sql, cursor));
            }
            if (cursor < sql.length() && sql.charAt(cursor) == '"') {
                return skipWhitespace(sql, skipDoubleQuotedText(sql, cursor));
            }
        }
        return -1;
    }

    private GenericConversion convertMysqlNumericTypeAttributes(String sql) {
        if (!MYSQL_NUMERIC_TYPE_ATTRIBUTE_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
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

    private GenericConversion convertMysqlCreateTableTextTypes(String sql) {
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

        List<TopLevelArgument> definitions = splitTopLevelArguments(sql.substring(openParenIndex + 1, closeParenIndex));
        List<String> convertedDefinitions = new ArrayList<>(definitions.size());
        boolean changed = false;
        for (TopLevelArgument definition : definitions) {
            String text = definition.text();
            int columnStart = skipWhitespace(text, 0);
            IdentifierToken column = readIdentifierToken(text, columnStart);
            if (column == null) {
                convertedDefinitions.add(text);
                continue;
            }
            int typeStart = skipWhitespace(text, column.endIndex());
            IdentifierToken type = readIdentifierToken(text, typeStart);
            if (type == null || !Set.of("TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "TEXT")
                    .contains(type.text().toUpperCase(Locale.ROOT))) {
                convertedDefinitions.add(text);
                continue;
            }
            convertedDefinitions.add(text.substring(0, typeStart) + "CLOB" + text.substring(type.endIndex()));
            changed = true;
        }
        if (!changed) {
            return GenericConversion.unchanged(sql);
        }
        return new GenericConversion(
                sql.substring(0, openParenIndex + 1)
                        + String.join(",", convertedDefinitions)
                        + sql.substring(closeParenIndex),
                true
        );
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
            boolean numericAttributeFound;
            do {
                numericAttributeFound = false;
                if (startsKeyword(sql, cursor, "UNSIGNED")) {
                    cursor = skipWhitespace(sql, cursor + "UNSIGNED".length());
                    changed = true;
                    numericAttributeFound = true;
                } else if (startsKeyword(sql, cursor, "ZEROFILL")) {
                    cursor = skipWhitespace(sql, cursor + "ZEROFILL".length());
                    changed = true;
                    numericAttributeFound = true;
                }
            } while (numericAttributeFound);
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
        if (!DECIMAL_PRECISION_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
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

    private GenericConversion normalizeMysqlCreateTableColumnComments(String sql) {
        if (!isCreateTableCandidate(sql)) {
            return GenericConversion.unchanged(sql);
        }
        int createIndex = findTopLevelKeyword(sql, "CREATE", 0);
        if (createIndex < 0) {
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
            GenericConversion conversion = normalizeMysqlColumnCommentClause(definition.text());
            convertedDefinitions.add(conversion.convertedSql());
            changed = changed || conversion.changed();
        }
        return changed
                ? new GenericConversion(
                        sql.substring(0, openParenIndex + 1)
                                + String.join(",", convertedDefinitions)
                                + sql.substring(closeParenIndex),
                        true
                )
                : GenericConversion.unchanged(sql);
    }

    private GenericConversion normalizeMysqlColumnCommentClause(String definition) {
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
                int keywordEnd = index + "COMMENT".length();
                int cursor = skipWhitespace(definition, keywordEnd);
                if (cursor < definition.length() && definition.charAt(cursor) == '=') {
                    int literalStart = skipWhitespace(definition, cursor + 1);
                    if (literalStart < definition.length() && definition.charAt(literalStart) == '\'') {
                        converted.append(definition, index, keywordEnd).append(' ');
                        index = appendSingleQuotedString(definition, literalStart, converted);
                        changed = true;
                        continue;
                    }
                }
                converted.append(current);
                index++;
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
        int index = skipLeadingWhitespaceAndComments(definition);
        if (startsKeyword(definition, index, "KEY") || startsKeyword(definition, index, "INDEX")) {
            return true;
        }
        if (startsKeyword(definition, index, "UNIQUE")) {
            int cursor = skipWhitespace(definition, index + "UNIQUE".length());
            return startsKeyword(definition, cursor, "KEY") || startsKeyword(definition, cursor, "INDEX");
        }
        return false;
    }

    private int skipLeadingWhitespaceAndComments(String value) {
        int index = 0;
        while (index < value.length()) {
            index = skipWhitespace(value, index);
            if (startsLineComment(value, index)) {
                index = skipUntilLineEnd(value, index);
            } else if (startsBlockComment(value, index)) {
                index = skipUntilBlockCommentEnd(value, index);
            } else {
                return index;
            }
        }
        return index;
    }

    private GenericConversion removeMysqlGeneratedColumnStoredAttributes(String sql) {
        int createIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, createIndex, "CREATE")) {
            return GenericConversion.unchanged(sql);
        }
        int tableIndex = findTopLevelKeyword(sql, "TABLE", createIndex + "CREATE".length());
        if (tableIndex < 0) {
            return GenericConversion.unchanged(sql);
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
            } else if (startsKeyword(sql, index, "STORED") && previousNonWhitespace(sql, index) == ')') {
                trimTrailingWhitespace(converted);
                index = skipWhitespace(sql, index + "STORED".length());
                changed = true;
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private GenericConversion convertMysqlOnUpdateCurrentTimestamp(String sql) {
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
                    String precision = "";
                    if (cursor < sql.length() && sql.charAt(cursor) == '(') {
                        int close = findMatchingParen(sql, cursor);
                        if (close > 0) {
                            String candidate = sql.substring(cursor + 1, close).strip();
                            if (candidate.matches("\\d+")) {
                                precision = candidate;
                            }
                            cursor = skipWhitespace(sql, close + 1);
                        }
                    }
                    converted.append("ON UPDATE NOW(").append(precision).append(")");
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

    private GenericConversion deduplicateLiteralUpdateSetAssignments(String sql) {
        List<StatementSegment> statements = splitTopLevelStatements(sql);
        if (statements.size() > 1) {
            StringBuilder converted = new StringBuilder(sql.length());
            boolean changed = false;
            for (StatementSegment statement : statements) {
                GenericConversion conversion = deduplicateSingleLiteralUpdateSetAssignments(statement.sql());
                converted.append(conversion.convertedSql()).append(statement.separator());
                changed = changed || conversion.changed();
            }
            return changed ? new GenericConversion(converted.toString(), true) : GenericConversion.unchanged(sql);
        }
        return deduplicateSingleLiteralUpdateSetAssignments(sql);
    }

    private GenericConversion deduplicateSingleLiteralUpdateSetAssignments(String sql) {
        int updateIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, updateIndex, "UPDATE")) {
            return GenericConversion.unchanged(sql);
        }
        int setIndex = findTopLevelKeyword(sql, "SET", updateIndex + "UPDATE".length());
        if (setIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int statementEnd = stripTrailingSemicolon(sql);
        int whereIndex = findTopLevelKeyword(sql, "WHERE", setIndex + "SET".length());
        int orderIndex = findTopLevelKeyword(sql, "ORDER", setIndex + "SET".length());
        int limitIndex = findTopLevelKeyword(sql, "LIMIT", setIndex + "SET".length());
        int setEnd = minPositive(whereIndex, orderIndex, limitIndex, statementEnd);
        String setClause = sql.substring(setIndex + "SET".length(), setEnd);
        List<TopLevelArgument> assignments = splitTopLevelArguments(setClause);
        if (assignments.size() < 2) {
            return GenericConversion.unchanged(sql);
        }

        Map<String, Integer> lastIndexByColumn = new LinkedHashMap<>();
        Map<String, Boolean> simpleByColumn = new LinkedHashMap<>();
        List<String> columns = new ArrayList<>(assignments.size());
        for (int i = 0; i < assignments.size(); i++) {
            AssignmentParts assignment = updateAssignmentParts(assignments.get(i).text());
            if (assignment == null) {
                return GenericConversion.unchanged(sql);
            }
            columns.add(assignment.columnKey());
            lastIndexByColumn.put(assignment.columnKey(), i);
            simpleByColumn.merge(assignment.columnKey(), assignment.simpleLiteralValue(), Boolean::logicalAnd);
        }
        boolean hasDuplicate = lastIndexByColumn.size() < assignments.size();
        if (!hasDuplicate || simpleByColumn.values().stream().anyMatch(simple -> !simple)) {
            return GenericConversion.unchanged(sql);
        }

        List<String> keptAssignments = new ArrayList<>();
        for (int i = 0; i < assignments.size(); i++) {
            if (lastIndexByColumn.get(columns.get(i)) == i) {
                keptAssignments.add(assignments.get(i).text().trim());
            }
        }
        StringBuilder converted = new StringBuilder(sql.length());
        converted.append(sql, 0, setIndex + "SET".length());
        converted.append(' ');
        converted.append(String.join(", ", keptAssignments));
        if (setEnd < sql.length()
                && !Character.isWhitespace(converted.charAt(converted.length() - 1))
                && !Character.isWhitespace(sql.charAt(setEnd))) {
            converted.append(' ');
        }
        converted.append(sql, setEnd, sql.length());
        return new GenericConversion(converted.toString(), true);
    }

    private int minPositive(int... values) {
        int min = Integer.MAX_VALUE;
        for (int value : values) {
            if (value >= 0 && value < min) {
                min = value;
            }
        }
        return min == Integer.MAX_VALUE ? -1 : min;
    }

    private AssignmentParts updateAssignmentParts(String assignment) {
        int equalsIndex = topLevelEqualsIndex(assignment);
        if (equalsIndex < 0) {
            return null;
        }
        String column = assignment.substring(0, equalsIndex).trim();
        String value = assignment.substring(equalsIndex + 1).trim();
        if (column.isBlank() || value.isBlank()) {
            return null;
        }
        return new AssignmentParts(normalizeIdentifierKey(column), isSimpleLiteralOrNull(value));
    }

    private int topLevelEqualsIndex(String value) {
        int depth = 0;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(value, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(value, index);
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (startsLineComment(value, index)) {
                index = skipUntilLineEnd(value, index);
            } else if (startsBlockComment(value, index)) {
                index = skipUntilBlockCommentEnd(value, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
                index++;
            } else if (current == '=' && depth == 0) {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private boolean isSimpleLiteralOrNull(String value) {
        String stripped = value.strip();
        if (stripped.equalsIgnoreCase("NULL")) {
            return true;
        }
        if (Pattern.compile("[-+]?\\d+(?:\\.\\d+)?").matcher(stripped).matches()) {
            return true;
        }
        if (stripped.startsWith("'")) {
            SingleQuotedStringLiteral literal = readSingleQuotedStringLiteral(stripped, 0);
            return literal.closed() && literal.nextIndex() == stripped.length();
        }
        if (stripped.startsWith("\"")) {
            DoubleQuotedStringLiteral literal = readDoubleQuotedStringLiteral(stripped, 0);
            return literal.closed() && literal.nextIndex() == stripped.length();
        }
        return false;
    }

    private GenericConversion convertDateSubInterval(String sql) {
        String converted = sql;
        boolean changed = false;
        for (int pass = 0; pass < 8; pass++) {
            GenericConversion conversion = convertDateSubFunctionInterval(converted);
            if (!conversion.changed()) {
                break;
            }
            converted = conversion.convertedSql();
            changed = true;
        }
        return new GenericConversion(converted, changed);
    }

    private GenericConversion convertDateSubFunctionInterval(String sql) {
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
                    appendFunctionReplacement(converted, replacement, sql, functionCall);
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
        return "DATEADD(" + unit + ", " + negatedDmIntervalAmount(amount) + ", " + dmDateExpression + ")";
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
                    appendFunctionReplacement(converted, replacement, sql, functionCall);
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
        Matcher interval = MYSQL_INTERVAL_PATTERN.matcher(dayExpression);
        if (interval.matches()) {
            String amount = interval.group(1).trim();
            String unit = interval.group(2).toUpperCase(Locale.ROOT);
            if (amount.isBlank()) {
                return null;
            }
            return "DATEADD(" + unit + ", " + negatedDmIntervalAmount(amount) + ", " + dateExpression + ")";
        }
        return "DATEADD(DAY, " + negatedDmIntervalAmount(dayExpression) + ", " + dateExpression + ")";
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
            } else if (startsFunction(sql, index, "DATE_ADD") || startsFunction(sql, index, "ADDDATE")) {
                String functionName = startsFunction(sql, index, "DATE_ADD") ? "DATE_ADD" : "ADDDATE";
                FunctionCall functionCall = readFunctionCall(sql, index, functionName);
                String replacement = functionCall == null ? null : rewriteDateAddInterval(functionCall);
                if (replacement == null) {
                    converted.append(current);
                    index++;
                } else {
                    appendFunctionReplacement(converted, replacement, sql, functionCall);
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
                            ? negatedDmIntervalAmount(addition.intervalExpression().amount())
                            : dmIntervalAmount(addition.intervalExpression().amount());
                    String unit = addition.intervalExpression().unit();
                    String rewrittenUnit = unit;
                    if ("HOUR_SECOND".equalsIgnoreCase(unit)) {
                        String seconds = hourSecondIntervalSeconds(addition.intervalExpression().amount());
                        if (seconds == null) {
                            index++;
                            continue;
                        }
                        amount = current == '-' ? "-" + seconds : seconds;
                        rewrittenUnit = "SECOND";
                    }
                    converted.append(sql, lastCopiedIndex, addition.leftExpression().startIndex());
                    converted.append("DATEADD(")
                            .append(rewrittenUnit)
                            .append(", ")
                            .append(amount)
                            .append(", ")
                            .append("HOUR_SECOND".equalsIgnoreCase(unit)
                                    ? "CAST(" + addition.leftExpression().text() + " AS DATETIME)"
                                    : addition.leftExpression().text())
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
        return "DATEADD(" + unit + ", " + dmIntervalAmount(amount) + ", " + dateExpression + ")";
    }

    private GenericConversion convertTimestampDiffNumericIfNullComparisons(String sql) {
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
            } else if (startsFunction(sql, index, "TIMESTAMPDIFF")) {
                FunctionCall timestampDiff = readFunctionCall(sql, index, "TIMESTAMPDIFF");
                NumericIfNullComparison comparison = timestampDiff == null
                        ? null
                        : readNumericIfNullComparison(sql, timestampDiff.endIndex());
                if (comparison == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(sql, index, comparison.ifNullStart());
                    converted.append("TO_NUMBER(")
                            .append(sql, comparison.ifNullStart(), comparison.ifNullEnd())
                            .append(")");
                    index = comparison.ifNullEnd();
                    changed = true;
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private NumericIfNullComparison readNumericIfNullComparison(String sql, int timestampDiffEnd) {
        int operatorStart = skipWhitespace(sql, timestampDiffEnd);
        int operatorEnd = comparisonOperatorEnd(sql, operatorStart);
        if (operatorEnd < 0) {
            return null;
        }
        int ifNullStart = skipWhitespace(sql, operatorEnd);
        if (!startsFunction(sql, ifNullStart, "IFNULL")) {
            return null;
        }
        FunctionCall ifNull = readFunctionCall(sql, ifNullStart, "IFNULL");
        if (ifNull == null || !NUMERIC_JDBC_TYPE_PLACEHOLDER_PATTERN.matcher(ifNull.body()).find()) {
            return null;
        }
        return new NumericIfNullComparison(ifNullStart, ifNull.endIndex());
    }

    private int comparisonOperatorEnd(String sql, int index) {
        if (index >= sql.length()) {
            return -1;
        }
        if (index + 1 < sql.length()) {
            String twoCharacters = sql.substring(index, index + 2);
            if (">=".equals(twoCharacters)
                    || "<=".equals(twoCharacters)
                    || "<>".equals(twoCharacters)
                    || "!=".equals(twoCharacters)) {
                return index + 2;
            }
        }
        char operator = sql.charAt(index);
        return operator == '=' || operator == '>' || operator == '<' ? index + 1 : -1;
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
                    appendFunctionReplacement(converted, replacement, sql, functionCall);
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

    private GenericConversion convertStrToDateHourSecondInterval(String sql) {
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
                String replacement = functionCall == null ? null : rewriteStrToDateHourSecondInterval(functionCall);
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

    private String rewriteStrToDateHourSecondInterval(FunctionCall strToDateCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(strToDateCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String format = normalizedStringLiteral(arguments.get(1).text());
        if (!"'%Y-%m-%d %H:%i:%s'".equalsIgnoreCase(format)) {
            return null;
        }
        String expression = stripOuterParentheses(arguments.get(0).text().trim());
        int plusIndex = findTopLevelChar(expression, '+', 0);
        int minusIndex = findTopLevelChar(expression, '-', 0);
        int operatorIndex;
        char operator;
        if (plusIndex >= 0 && (minusIndex < 0 || plusIndex < minusIndex)) {
            operatorIndex = plusIndex;
            operator = '+';
        } else if (minusIndex >= 0) {
            operatorIndex = minusIndex;
            operator = '-';
        } else {
            return null;
        }
        DateIntervalAddition addition = readDateIntervalAddition(expression, operatorIndex);
        if (addition == null || !"HOUR_SECOND".equalsIgnoreCase(addition.intervalExpression().unit())) {
            return null;
        }
        String seconds = hourSecondIntervalSeconds(addition.intervalExpression().amount());
        if (seconds == null) {
            return null;
        }
        return "DATEADD(SECOND, "
                + (operator == '-' ? "-" : "")
                + seconds
                + ", CAST("
                + addition.leftExpression().text()
                + " AS DATETIME))";
    }

    private String stripOuterParentheses(String expression) {
        String converted = expression.strip();
        boolean changed;
        do {
            changed = false;
            if (converted.startsWith("(")) {
                int close = findMatchingParen(converted, 0);
                if (close == converted.length() - 1) {
                    converted = converted.substring(1, close).strip();
                    changed = true;
                }
            }
        } while (changed);
        return converted;
    }

    private String hourSecondIntervalSeconds(String amount) {
        String stripped = amount.strip();
        if (!stripped.startsWith("'")) {
            return null;
        }
        SingleQuotedStringLiteral literal = readSingleQuotedStringLiteral(stripped, 0);
        if (!literal.closed() || literal.nextIndex() != stripped.length()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?is)^(\\d{1,3}):(\\d{1,2}):(\\d{1,2})$")
                .matcher(literal.value().strip());
        if (!matcher.matches()) {
            return null;
        }
        long hours = Long.parseLong(matcher.group(1));
        long minutes = Long.parseLong(matcher.group(2));
        long seconds = Long.parseLong(matcher.group(3));
        if (minutes > 59 || seconds > 59) {
            return null;
        }
        return Long.toString(hours * 3600 + minutes * 60 + seconds);
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

    private GenericConversion convertDefaultYearWeek(String sql) {
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
            } else if (startsFunction(sql, index, "YEARWEEK")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "YEARWEEK");
                String replacement = functionCall == null ? null : rewriteDefaultYearWeek(functionCall);
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

    private String rewriteDefaultYearWeek(FunctionCall yearWeekCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(yearWeekCall.body());
        if (arguments.size() != 1) {
            return null;
        }
        String expression = unwrapDateOnlyFormat(arguments.get(0).text());
        if (expression.isBlank()) {
            return null;
        }
        String weekStart = "DATEADD(DAY, -WEEKDAY(" + expression + "), " + expression + ")";
        return "(YEAR(" + weekStart + ") * 100 + WEEK(" + expression + ", 2))";
    }

    private String unwrapDateOnlyFormat(String expression) {
        String trimmed = expression.trim();
        int start = leadingWhitespaceLength(trimmed);
        FunctionCall dateFormatCall = readFunctionCall(trimmed, start, "DATE_FORMAT");
        if (dateFormatCall == null || skipWhitespace(trimmed, dateFormatCall.endIndex()) != trimmed.length()) {
            return trimmed;
        }
        List<TopLevelArgument> arguments = splitTopLevelArguments(dateFormatCall.body());
        if (arguments.size() != 2) {
            return trimmed;
        }
        String format = normalizedStringLiteral(arguments.get(1).text());
        return "'%Y-%m-%d'".equalsIgnoreCase(format) ? arguments.get(0).text().trim() : trimmed;
    }

    private String rewritePeriodDiffYearMonth(FunctionCall periodDiffCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(periodDiffCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String left = yearMonthExtractExpression(arguments.get(0).text());
        String right = yearMonthExtractExpression(arguments.get(1).text());
        if (left != null && right != null) {
            return "DATEDIFF(MONTH, " + right + ", " + left + ")";
        }
        String leftIndex = yearMonthIndexExpression(arguments.get(0).text(), left);
        String rightIndex = yearMonthIndexExpression(arguments.get(1).text(), right);
        if (leftIndex == null || rightIndex == null) {
            return null;
        }
        return "(" + leftIndex + " - " + rightIndex + ")";
    }

    private String yearMonthIndexExpression(String expression, String dateExpression) {
        if (dateExpression != null) {
            return "(YEAR(" + dateExpression + ") * 12 + MONTH(" + dateExpression + "))";
        }
        String periodExpression = simpleYearMonthPeriodExpression(expression);
        if (periodExpression == null) {
            return null;
        }
        String numericPeriod = "CAST(" + periodExpression + " AS DECIMAL(38, 0))";
        String periodYear = "TRUNC(" + numericPeriod + " / 100, 0)";
        String normalizedYear = "(" + periodYear
                + " + CASE WHEN " + periodYear + " < 70 THEN 2000"
                + " WHEN " + periodYear + " < 100 THEN 1900 ELSE 0 END)";
        return "(" + normalizedYear + " * 12 + MOD(" + numericPeriod + ", 100))";
    }

    private String simpleYearMonthPeriodExpression(String expression) {
        String trimmed = expression.trim();
        if (startsHashMyBatisPlaceholder(trimmed, 0)
                && skipMyBatisPlaceholder(trimmed, 0) == trimmed.length()) {
            return trimmed;
        }
        if (trimmed.matches("(?:\\d{4}|\\d{6})")
                || trimmed.matches("'(?:\\d{4}|\\d{6})'")
                || isSimpleQualifiedIdentifier(trimmed)) {
            return trimmed;
        }
        return null;
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

    private GenericConversion convertTimeToSecTimeDiff(String sql) {
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
            } else if (startsFunction(sql, index, "TIME_TO_SEC")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "TIME_TO_SEC");
                String replacement = functionCall == null ? null : rewriteTimeToSecTimeDiff(functionCall);
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

    private String rewriteTimeToSecTimeDiff(FunctionCall timeToSecCall) {
        List<TopLevelArgument> outerArguments = splitTopLevelArguments(timeToSecCall.body());
        if (outerArguments.size() != 1) {
            return null;
        }
        String timeDiffExpression = outerArguments.get(0).text().trim();
        int start = leadingWhitespaceLength(timeDiffExpression);
        FunctionCall timeDiffCall = readFunctionCall(timeDiffExpression, start, "TIMEDIFF");
        if (timeDiffCall == null || skipWhitespace(timeDiffExpression, timeDiffCall.endIndex())
                != timeDiffExpression.length()) {
            return null;
        }
        List<TopLevelArgument> arguments = splitTopLevelArguments(timeDiffCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String end = dmDateTimeExpression(arguments.get(0).text());
        String startExpression = dmDateTimeExpression(arguments.get(1).text());
        if (end.isBlank() || startExpression.isBlank()) {
            return null;
        }
        return "DATEDIFF(SECOND, " + startExpression + ", " + end + ")";
    }

    private GenericConversion convertTimePartTimeDiff(String sql) {
        if (!TIME_PART_FUNCTION_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
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
            } else {
                String functionName = timePartFunctionName(sql, index);
                FunctionCall functionCall = functionName == null
                        ? null
                        : readFunctionCall(sql, index, functionName);
                String replacement = functionCall == null
                        ? null
                        : rewriteTimePartTimeDiff(functionName, functionCall);
                if (replacement == null) {
                    converted.append(current);
                    index++;
                } else {
                    converted.append(replacement);
                    index = functionCall.endIndex();
                    changed = true;
                }
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private String timePartFunctionName(String sql, int index) {
        for (String functionName : List.of("HOUR", "MINUTE", "SECOND")) {
            if (startsFunction(sql, index, functionName)) {
                return functionName;
            }
        }
        return null;
    }

    private String rewriteTimePartTimeDiff(String functionName, FunctionCall timePartCall) {
        List<TopLevelArgument> outerArguments = splitTopLevelArguments(timePartCall.body());
        if (outerArguments.size() != 1) {
            return null;
        }
        String timeDiffExpression = outerArguments.get(0).text().trim();
        int start = leadingWhitespaceLength(timeDiffExpression);
        FunctionCall timeDiffCall = readFunctionCall(timeDiffExpression, start, "TIMEDIFF");
        if (timeDiffCall == null || skipWhitespace(timeDiffExpression, timeDiffCall.endIndex())
                != timeDiffExpression.length()) {
            return null;
        }
        List<TopLevelArgument> arguments = splitTopLevelArguments(timeDiffCall.body());
        if (arguments.size() != 2) {
            return null;
        }
        String end = dmDateTimeExpression(arguments.get(0).text());
        String startExpression = dmDateTimeExpression(arguments.get(1).text());
        if (end.isBlank() || startExpression.isBlank()) {
            return null;
        }
        String seconds = "ABS(DATEDIFF(SECOND, " + startExpression + ", " + end + "))";
        return switch (functionName) {
            case "HOUR" -> "TRUNC(" + seconds + " / 3600)";
            case "MINUTE" -> "MOD(TRUNC(" + seconds + " / 60), 60)";
            case "SECOND" -> "MOD(" + seconds + ", 60)";
            default -> null;
        };
    }

    private String dmDateTimeExpression(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        return isNowExpression(trimmed) ? "SYSDATE" : trimmed;
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

    private String dmIntervalAmount(String amount) {
        String trimmed = amount == null ? "" : amount.trim();
        if (trimmed.isBlank()) {
            return trimmed;
        }
        if (INTEGER_LITERAL_PATTERN.matcher(trimmed).matches()
                || NUMERIC_JDBC_TYPE_PLACEHOLDER_PATTERN.matcher(trimmed).matches()
                || NUMERIC_CAST_PATTERN.matcher(trimmed).matches()
                || NUMERIC_INTERVAL_EXPRESSION_PATTERN.matcher(trimmed).find()
                || NUMERIC_ARITHMETIC_INTERVAL_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (readOnlyFunctionCall(trimmed, "IFNULL") != null
                || readOnlyFunctionCall(trimmed, "COALESCE") != null) {
            return "TO_NUMBER(" + trimmed + ")";
        }
        return "CAST(" + trimmed + " AS BIGINT)";
    }

    private String negatedDmIntervalAmount(String amount) {
        String trimmed = amount == null ? "" : amount.trim();
        if (trimmed.startsWith("-")) {
            return dmIntervalAmount(trimmed.substring(1));
        }
        if (trimmed.startsWith("+")) {
            return "-" + dmIntervalAmount(trimmed.substring(1));
        }
        String numericAmount = dmIntervalAmount(trimmed);
        if (INTEGER_LITERAL_PATTERN.matcher(trimmed).matches()
                || NUMERIC_JDBC_TYPE_PLACEHOLDER_PATTERN.matcher(trimmed).matches()) {
            return "-" + numericAmount;
        }
        return "(0 - " + numericAmount + ")";
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
        String expression = convertDecimalConvertFunctions(arguments.get(0).text()).convertedSql().trim();
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

    public SqlConversionResult convertGbkOrderExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return SqlConversionResult.unchanged(expression == null ? "" : expression);
        }
        int start = leadingWhitespaceLength(expression);
        if (!startsFunction(expression, start, "CONVERT")) {
            return SqlConversionResult.unchanged(expression);
        }
        FunctionCall functionCall = readFunctionCall(expression, start, "CONVERT");
        if (functionCall == null || !expression.substring(functionCall.endIndex()).isBlank()) {
            return SqlConversionResult.unchanged(expression);
        }
        String replacement = rewriteGbkOrderConvert(functionCall);
        if (replacement == null) {
            return SqlConversionResult.unchanged(expression);
        }
        String converted = expression.substring(0, start) + replacement
                + expression.substring(functionCall.endIndex());
        return SqlConversionResult.changed(
                expression,
                converted,
                List.of(MYSQL_CONVERT_GBK_ORDER_RULE)
        );
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
        if (!startsKeyword(sql, index, "SELECT") && !startsMyBatisXmlElement(sql, index, "foreach")) {
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

    private boolean startsMyBatisXmlElement(String sql, int index, String elementName) {
        if (index >= sql.length() || sql.charAt(index) != '<') {
            return false;
        }
        int nameIndex = skipWhitespace(sql, index + 1);
        if (nameIndex >= sql.length() || sql.charAt(nameIndex) == '/') {
            return false;
        }
        return startsKeyword(sql, nameIndex, elementName);
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

    private GenericConversion convertMysqlJoinedDelete(String sql) {
        List<StatementSegment> statements = splitTopLevelStatements(sql);
        if (statements.size() > 1) {
            StringBuilder converted = new StringBuilder(sql.length());
            boolean changed = false;
            for (StatementSegment statement : statements) {
                GenericConversion statementConversion = convertSingleMysqlJoinedDelete(statement.sql());
                converted.append(statementConversion.convertedSql()).append(statement.separator());
                changed = changed || statementConversion.changed();
            }
            return changed ? new GenericConversion(converted.toString(), true) : GenericConversion.unchanged(sql);
        }
        return convertSingleMysqlJoinedDelete(sql);
    }

    private GenericConversion convertSingleMysqlJoinedDelete(String sql) {
        int deleteIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, deleteIndex, "DELETE")) {
            return GenericConversion.unchanged(sql);
        }
        int targetsStart = skipWhitespace(sql, deleteIndex + "DELETE".length());
        int fromIndex = findTopLevelKeyword(sql, "FROM", targetsStart);
        if (fromIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int tableStart = skipWhitespace(sql, fromIndex + "FROM".length());
        int joinIndex = findTopLevelKeyword(sql, "JOIN", tableStart);
        if (joinIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int statementEnd = stripTrailingSemicolon(sql);
        int whereIndex = findTopLevelKeyword(sql, "WHERE", joinIndex + "JOIN".length());
        int joinClauseEnd = whereIndex < 0 ? statementEnd : whereIndex;
        if (joinClauseEnd <= joinIndex) {
            return GenericConversion.unchanged(sql);
        }

        int joinTypeStart = joinTypeStart(sql, joinIndex);
        String targetRelation = sql.substring(tableStart, joinTypeStart).strip();
        String joinSource = sql.substring(joinIndex + "JOIN".length(), joinClauseEnd).strip();
        UpdateJoinChain chain = updateJoinChain(targetRelation, joinSource, true);
        if (chain == null || chain.tables().size() < 2) {
            return GenericConversion.unchanged(sql);
        }

        List<String> targetAliases = new ArrayList<>();
        for (TopLevelArgument target : splitTopLevelArguments(sql.substring(targetsStart, fromIndex))) {
            String alias = deleteTargetAlias(target.text());
            if (alias.isBlank() || tableByAlias(chain.tables(), normalizeIdentifierKey(alias)) == null) {
                return GenericConversion.unchanged(sql);
            }
            targetAliases.add(alias);
        }
        if (targetAliases.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }

        String fromAndPredicates = sql.substring(tableStart, statementEnd).strip();
        String trailing = sql.substring(statementEnd);
        if (targetAliases.size() == 1) {
            String alias = targetAliases.get(0);
            UpdateJoinTable target = tableByAlias(chain.tables(), normalizeIdentifierKey(alias));
            String tableName = target == null ? "" : deleteTableName(target.tableSql());
            if (tableName.isBlank()) {
                return GenericConversion.unchanged(sql);
            }
            String rewritten = sql.substring(0, deleteIndex)
                    + "DELETE FROM " + tableName
                    + " WHERE ROWID IN (SELECT " + alias + ".ROWID FROM " + fromAndPredicates + ")"
                    + trailing;
            return new GenericConversion(rewritten, true);
        }

        if (targetAliases.size() != 2
                || chain.tables().size() != 2
                || !Pattern.compile("(?is)^LEFT(?:\\s+OUTER)?$")
                .matcher(sql.substring(joinTypeStart, joinIndex).strip())
                .matches()) {
            return GenericConversion.unchanged(sql);
        }
        UpdateJoinTable primary = chain.tables().get(0);
        UpdateJoinTable secondary = chain.tables().get(1);
        String primaryAlias = targetAliases.get(0);
        String secondaryAlias = targetAliases.get(1);
        if (!primary.aliasKey().equals(normalizeIdentifierKey(primaryAlias))
                || !secondary.aliasKey().equals(normalizeIdentifierKey(secondaryAlias))) {
            return GenericConversion.unchanged(sql);
        }
        String whereClause = whereIndex < 0
                ? ""
                : sql.substring(whereIndex + "WHERE".length(), statementEnd);
        if (referencesAliasOutsideIgnoredText(whereClause, secondary.aliasKey())) {
            return GenericConversion.unchanged(sql);
        }
        String primaryTable = deleteTableName(primary.tableSql());
        String secondaryTable = deleteTableName(secondary.tableSql());
        if (primaryTable.isBlank() || secondaryTable.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String rewritten = sql.substring(0, deleteIndex)
                + "BEGIN\n"
                + "DELETE FROM " + secondaryTable
                + " WHERE ROWID IN (SELECT " + secondaryAlias + ".ROWID FROM " + fromAndPredicates + ");\n"
                + "DELETE FROM " + primaryTable
                + " WHERE ROWID IN (SELECT " + primaryAlias + ".ROWID FROM " + fromAndPredicates + ");\n"
                + "END"
                + trailing;
        return new GenericConversion(rewritten, true);
    }

    private String deleteTargetAlias(String target) {
        String alias = target == null ? "" : target.strip();
        Matcher star = Pattern.compile("(?is)^(?<alias>" + UPDATE_IDENTIFIER + ")\\s*\\.\\s*\\*$")
                .matcher(alias);
        if (star.matches()) {
            return star.group("alias");
        }
        IdentifierToken token = readIdentifierToken(alias, 0);
        return token != null && skipWhitespace(alias, token.endIndex()) == alias.length()
                ? token.text()
                : "";
    }

    private String deleteTableName(String tableSql) {
        String relation = tableSql == null ? "" : tableSql.strip();
        IdentifierToken table = readQualifiedIdentifierToken(relation, 0);
        if (table == null) {
            return "";
        }
        int index = skipWhitespace(relation, table.endIndex());
        if (startsKeyword(relation, index, "AS")) {
            index = skipWhitespace(relation, index + "AS".length());
        }
        if (index < relation.length()) {
            IdentifierToken alias = readIdentifierToken(relation, index);
            if (alias == null || skipWhitespace(relation, alias.endIndex()) != relation.length()) {
                return "";
            }
        }
        return relation.substring(0, table.endIndex());
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
        if (findTopLevelKeyword(sql, "JOIN", tableAlias.endIndex()) >= 0) {
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
        GenericConversion descriptorConversion = convertInformationSchemaColumnDescriptors(sql);
        if (descriptorConversion.changed()) {
            return descriptorConversion;
        }
        GenericConversion runtimeDetailConversion = convertInformationSchemaRuntimeColumnDetails(sql);
        if (runtimeDetailConversion.changed()) {
            return runtimeDetailConversion;
        }
        GenericConversion detailConversion = convertInformationSchemaColumnsDetail(sql);
        if (detailConversion.changed()) {
            return detailConversion;
        }
        GenericConversion tableListConversion = convertInformationSchemaColumnsTableList(sql);
        if (tableListConversion.changed()) {
            return tableListConversion;
        }
        GenericConversion commonProjectionConversion = convertInformationSchemaCommonColumnProjections(sql);
        if (commonProjectionConversion.changed()) {
            return commonProjectionConversion;
        }
        Matcher matcher = INFORMATION_SCHEMA_COLUMNS_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String whereClause = matcher.group("where");
        Matcher tableNameMatcher = METADATA_TABLE_NAME_CONDITION.matcher(whereClause);
        Matcher tableSchemaMatcher = METADATA_TABLE_SCHEMA_CONDITION.matcher(whereClause);
        if (!tableNameMatcher.find()) {
            return GenericConversion.unchanged(sql);
        }
        String tableName = tableNameMatcher.group("value").trim();
        String tableSchema = tableSchemaMatcher.find() ? tableSchemaMatcher.group("value").trim() : "";
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
                + ")"
                + metadataOwnerCondition(tableSchema)
                + " ORDER BY COLUMN_ID"
                + (direction == null || direction.isBlank() ? "" : " " + direction.toUpperCase(Locale.ROOT));
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaRuntimeColumnDetails(String sql) {
        Matcher matcher = INFORMATION_SCHEMA_COLUMNS_RUNTIME_DETAIL_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        MetadataColumnFilter filter = parseMetadataColumnFilter(matcher.group("where"), false);
        if (filter == null) {
            return GenericConversion.unchanged(sql);
        }
        String owner = metadataOwnerExpression(filter.tableSchema());
        if (owner.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String defaultAlias = matcher.group("defaultAlias");
        String converted = "SELECT\n"
                + "    sc.NAME AS " + quotedMetadataAlias(matcher.group("columnAlias")) + ",\n"
                + "    sc.TYPE$ AS " + quotedMetadataAlias(matcher.group("dataTypeAlias")) + ",\n"
                + metadataColumnTypeProjection(matcher.group("columnTypeAlias"))
                + ",\n"
                + "    scc.COMMENT$ AS " + quotedMetadataAlias(matcher.group("commentAlias"))
                + (defaultAlias == null || defaultAlias.isBlank()
                        ? "\n"
                        : ",\n    sc.DEFVAL AS " + quotedMetadataAlias(defaultAlias) + "\n")
                + "FROM SYS.SYSCOLUMNS sc\n"
                + "JOIN SYS.SYSOBJECTS obj\n"
                + "    ON obj.ID = sc.ID\n"
                + "    AND obj.TYPE$ = 'SCHOBJ'\n"
                + "    AND obj.SUBTYPE$ = 'UTAB'\n"
                + "JOIN SYS.SYSOBJECTS sch\n"
                + "    ON sch.ID = obj.SCHID\n"
                + "    AND sch.TYPE$ = 'SCH'\n"
                + "LEFT JOIN SYS.SYSCOLUMNCOMMENTS scc\n"
                + "    ON scc.SCHNAME = sch.NAME\n"
                + "    AND scc.TVNAME = obj.NAME\n"
                + "    AND scc.COLNAME = sc.NAME\n"
                + "WHERE sch.NAME = " + owner + "\n"
                + "    AND obj.NAME = UPPER(" + filter.tableName() + ")\n"
                + "ORDER BY sc.COLID";
        return new GenericConversion(converted, true);
    }

    private String metadataColumnTypeProjection(String alias) {
        return "    CASE\n"
                + "        WHEN sc.SCALE = 0\n"
                + "            AND (REGEXP_LIKE(sc.TYPE$, 'INT|REAL|BIT|^DATE$|TIME|FLOAT|DOUBLE')\n"
                + "                OR sc.LENGTH$ = 2147483647)\n"
                + "            THEN sc.TYPE$\n"
                + "        WHEN sc.SCALE <> 0 AND REGEXP_LIKE(sc.TYPE$, 'NUM|DEC')\n"
                + "            THEN sc.TYPE$ || '(' || sc.LENGTH$ || ',' || sc.SCALE || ')'\n"
                + "        WHEN sc.SCALE <> 0 AND REGEXP_LIKE(sc.TYPE$, 'TIME')\n"
                + "            THEN sc.TYPE$ || '(' || sc.SCALE || ')'\n"
                + "        ELSE sc.TYPE$ || '(' || sc.LENGTH$ || ')'\n"
                + "    END AS " + quotedMetadataAlias(alias);
    }

    private GenericConversion convertInformationSchemaCommonColumnProjections(String sql) {
        Matcher countMatcher = INFORMATION_SCHEMA_COLUMNS_COUNT_PATTERN.matcher(sql);
        if (countMatcher.matches()) {
            return convertInformationSchemaColumnCount(
                    sql,
                    countMatcher.group("where"),
                    countMatcher.group("alias")
            );
        }
        Matcher aggregateMatcher = INFORMATION_SCHEMA_COLUMNS_AGGREGATE_PATTERN.matcher(sql);
        if (aggregateMatcher.matches()) {
            return convertInformationSchemaColumnAggregate(
                    sql,
                    aggregateMatcher.group("where"),
                    aggregateMatcher.group("alias")
            );
        }
        Matcher detailMatcher = INFORMATION_SCHEMA_COLUMNS_SIMPLE_DETAIL_PATTERN.matcher(sql);
        if (detailMatcher.matches()) {
            return convertInformationSchemaSimpleColumnDetail(sql, detailMatcher.group("where"));
        }
        return GenericConversion.unchanged(sql);
    }

    private GenericConversion convertInformationSchemaColumnCount(
            String sql,
            String whereClause,
            String alias
    ) {
        MetadataColumnFilter filter = parseMetadataColumnFilter(whereClause, true);
        if (filter == null || filter.columnName().isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String converted = "SELECT COUNT(*)"
                + (alias == null || alias.isBlank() ? "" : " AS " + alias)
                + " FROM ALL_TAB_COLUMNS"
                + metadataColumnFilterCondition(filter);
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaColumnAggregate(
            String sql,
            String whereClause,
            String alias
    ) {
        MetadataColumnFilter filter = parseMetadataColumnFilter(whereClause, false);
        if (filter == null) {
            return GenericConversion.unchanged(sql);
        }
        String converted = "SELECT LISTAGG(COLUMN_NAME, ',') WITHIN GROUP (ORDER BY COLUMN_ID)"
                + (alias == null || alias.isBlank() ? "" : " AS " + alias)
                + " FROM ALL_TAB_COLUMNS"
                + metadataColumnFilterCondition(filter);
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaSimpleColumnDetail(String sql, String whereClause) {
        MetadataColumnFilter filter = parseMetadataColumnFilter(whereClause, false);
        if (filter == null) {
            return GenericConversion.unchanged(sql);
        }
        String converted = "SELECT\n"
                + "    c.COLUMN_NAME AS COLUMN_NAME,\n"
                + "    cc.COMMENTS AS COLUMN_COMMENT,\n"
                + "    c.DATA_TYPE AS DATA_TYPE,\n"
                + "    CASE c.NULLABLE WHEN 'Y' THEN 'YES' ELSE 'NO' END AS IS_NULLABLE,\n"
                + "    c.DATA_DEFAULT AS COLUMN_DEFAULT,\n"
                + "    c.CHAR_LENGTH AS CHARACTER_MAXIMUM_LENGTH\n"
                + "FROM ALL_TAB_COLUMNS c\n"
                + "LEFT JOIN ALL_COL_COMMENTS cc\n"
                + "    ON cc.OWNER = c.OWNER\n"
                + "    AND cc.TABLE_NAME = c.TABLE_NAME\n"
                + "    AND cc.COLUMN_NAME = c.COLUMN_NAME\n"
                + metadataColumnFilterCondition(filter, "c.")
                + "\nORDER BY c.COLUMN_ID";
        return new GenericConversion(converted, true);
    }

    private MetadataColumnFilter parseMetadataColumnFilter(String whereClause, boolean requireColumnName) {
        Matcher tableNameMatcher = METADATA_TABLE_NAME_CONDITION.matcher(whereClause);
        if (!tableNameMatcher.find()) {
            return null;
        }
        String tableName = tableNameMatcher.group("value").trim();
        Matcher tableSchemaMatcher = METADATA_TABLE_SCHEMA_CONDITION.matcher(whereClause);
        String tableSchema = tableSchemaMatcher.find() ? tableSchemaMatcher.group("value").trim() : "";
        Matcher columnNameMatcher = METADATA_COLUMN_NAME_CONDITION.matcher(whereClause);
        String columnName = columnNameMatcher.find() ? columnNameMatcher.group("value").trim() : "";
        if (requireColumnName && columnName.isBlank()) {
            return null;
        }
        String residual = tableNameMatcher.replaceAll("");
        residual = METADATA_TABLE_SCHEMA_CONDITION.matcher(residual).replaceAll("");
        residual = METADATA_COLUMN_NAME_CONDITION.matcher(residual).replaceAll("");
        residual = residual.replaceAll("(?i)\\bAND\\b", "")
                .replaceAll("[()\\s]", "");
        if (!residual.isBlank()) {
            return null;
        }
        return new MetadataColumnFilter(tableName, tableSchema, columnName);
    }

    private String metadataColumnFilterCondition(MetadataColumnFilter filter) {
        return metadataColumnFilterCondition(filter, "");
    }

    private String metadataColumnFilterCondition(MetadataColumnFilter filter, String qualifier) {
        String owner = metadataOwnerExpression(filter.tableSchema());
        return " WHERE " + qualifier + "TABLE_NAME = UPPER(" + filter.tableName() + ")"
                + (owner.isBlank() ? "" : " AND " + qualifier + "OWNER = " + owner)
                + (filter.columnName().isBlank()
                        ? ""
                        : " AND " + qualifier + "COLUMN_NAME = UPPER(" + filter.columnName() + ")");
    }

    private GenericConversion convertInformationSchemaColumnDescriptors(String sql) {
        String alias = "(?:\"[^\"]+\"|`[^`]+`|[A-Za-z_][A-Za-z0-9_$]*)";
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*select\\s+"
                        + "c\\s*\\.\\s*COLUMN_NAME\\s+(?:as\\s+)?(?<columnAlias>" + alias + ")\\s*,\\s*"
                        + "c\\s*\\.\\s*COLUMN_TYPE\\s+(?:as\\s+)?(?<typeAlias>" + alias + ")\\s*,\\s*"
                        + "c\\s*\\.\\s*COLUMN_COMMENT\\s+(?:as\\s+)?(?<commentAlias>" + alias + ")\\s*,\\s*"
                        + "c\\s*\\.\\s*COLUMN_DEFAULT\\s+(?:as\\s+)?(?<defaultAlias>" + alias + ")\\s*,\\s*"
                        + "c\\s*\\.\\s*COLUMN_KEY\\s+(?:as\\s+)?(?<keyAlias>" + alias + ")\\s*,\\s*"
                        + "c\\s*\\.\\s*EXTRA\\s+(?:as\\s+)?(?<extraAlias>" + alias + ")\\s+"
                        + "from\\s+information_schema\\s*\\.\\s*(?:\"COLUMNS\"|`COLUMNS`|COLUMNS)\\s+c\\s+"
                        + "where\\s+(?:c\\s*\\.\\s*)?TABLE_SCHEMA\\s*=\\s*(?<schema>.+?)\\s+"
                        + "and\\s+(?:c\\s*\\.\\s*)?TABLE_NAME\\s*=\\s*(?<table>.+?)\\s+"
                        + "order\\s+by\\s+(?:c\\s*\\.\\s*)?ORDINAL_POSITION(?:\\s+(?:asc|desc))?\\s*;?\\s*$"
        ).matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String owner = metadataOwnerExpression(matcher.group("schema"));
        String table = matcher.group("table").trim();
        if (owner.isBlank() || table.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String converted = "SELECT\n"
                + "    sc.NAME AS " + quotedMetadataAlias(matcher.group("columnAlias")) + ",\n"
                + "    CASE\n"
                + "        WHEN sc.SCALE = 0\n"
                + "            AND (REGEXP_LIKE(sc.TYPE$, 'INT|REAL|BIT|^DATE$|TIME|FLOAT|DOUBLE')\n"
                + "                OR sc.LENGTH$ = 2147483647)\n"
                + "            THEN sc.TYPE$\n"
                + "        WHEN sc.SCALE <> 0 AND REGEXP_LIKE(sc.TYPE$, 'NUM|DEC')\n"
                + "            THEN sc.TYPE$ || '(' || sc.LENGTH$ || ',' || sc.SCALE || ')'\n"
                + "        WHEN sc.SCALE <> 0 AND REGEXP_LIKE(sc.TYPE$, 'TIME')\n"
                + "            THEN sc.TYPE$ || '(' || sc.SCALE || ')'\n"
                + "        ELSE sc.TYPE$ || '(' || sc.LENGTH$ || ')'\n"
                + "    END AS " + quotedMetadataAlias(matcher.group("typeAlias")) + ",\n"
                + "    scc.COMMENT$ AS " + quotedMetadataAlias(matcher.group("commentAlias")) + ",\n"
                + "    sc.DEFVAL AS " + quotedMetadataAlias(matcher.group("defaultAlias")) + ",\n"
                + "    CASE\n"
                + "        WHEN EXISTS (\n"
                + "            SELECT 1\n"
                + "            FROM ALL_CONS_COLUMNS acc\n"
                + "            JOIN ALL_CONSTRAINTS ac\n"
                + "                ON ac.OWNER = acc.OWNER\n"
                + "                AND ac.CONSTRAINT_NAME = acc.CONSTRAINT_NAME\n"
                + "            WHERE ac.OWNER = sch.NAME\n"
                + "                AND ac.TABLE_NAME = obj.NAME\n"
                + "                AND acc.COLUMN_NAME = sc.NAME\n"
                + "                AND ac.CONSTRAINT_TYPE = 'P'\n"
                + "        ) THEN 'PRI'\n"
                + "        WHEN EXISTS (\n"
                + "            SELECT 1\n"
                + "            FROM ALL_CONS_COLUMNS acc\n"
                + "            JOIN ALL_CONSTRAINTS ac\n"
                + "                ON ac.OWNER = acc.OWNER\n"
                + "                AND ac.CONSTRAINT_NAME = acc.CONSTRAINT_NAME\n"
                + "            WHERE ac.OWNER = sch.NAME\n"
                + "                AND ac.TABLE_NAME = obj.NAME\n"
                + "                AND acc.COLUMN_NAME = sc.NAME\n"
                + "                AND ac.CONSTRAINT_TYPE = 'U'\n"
                + "        ) THEN 'UNI'\n"
                + "        WHEN EXISTS (\n"
                + "            SELECT 1\n"
                + "            FROM ALL_IND_COLUMNS aic\n"
                + "            WHERE aic.TABLE_OWNER = sch.NAME\n"
                + "                AND aic.TABLE_NAME = obj.NAME\n"
                + "                AND aic.COLUMN_NAME = sc.NAME\n"
                + "        ) THEN 'MUL'\n"
                + "        ELSE ''\n"
                + "    END AS " + quotedMetadataAlias(matcher.group("keyAlias")) + ",\n"
                + "    CASE WHEN MOD(sc.INFO2, 2) = 1 THEN 'auto_increment' ELSE '' END AS "
                + quotedMetadataAlias(matcher.group("extraAlias")) + "\n"
                + "FROM SYS.SYSCOLUMNS sc\n"
                + "JOIN SYS.SYSOBJECTS obj\n"
                + "    ON obj.ID = sc.ID\n"
                + "    AND obj.TYPE$ = 'SCHOBJ'\n"
                + "    AND obj.SUBTYPE$ = 'UTAB'\n"
                + "JOIN SYS.SYSOBJECTS sch\n"
                + "    ON sch.ID = obj.SCHID\n"
                + "    AND sch.TYPE$ = 'SCH'\n"
                + "LEFT JOIN SYS.SYSCOLUMNCOMMENTS scc\n"
                + "    ON scc.SCHNAME = sch.NAME\n"
                + "    AND scc.TVNAME = obj.NAME\n"
                + "    AND scc.COLNAME = sc.NAME\n"
                + "WHERE sch.NAME = " + owner + "\n"
                + "    AND obj.NAME = UPPER(" + table + ")\n"
                + "ORDER BY sc.COLID";
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaStatistics(String sql) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*select\\s+distinct\\s+(?:s\\s*\\.\\s*)?INDEX_NAME\\s+"
                        + "from\\s+information_schema\\s*\\.\\s*(?:\"STATISTICS\"|`STATISTICS`|STATISTICS)"
                        + "(?:\\s+s)?\\s+"
                        + "where\\s+(?:s\\s*\\.\\s*)?TABLE_SCHEMA\\s*=\\s*(?<schema>.+?)\\s+"
                        + "and\\s+(?:s\\s*\\.\\s*)?TABLE_NAME\\s*=\\s*(?<table>.+?)\\s+"
                        + "and\\s+(?:s\\s*\\.\\s*)?INDEX_NAME\\s*(?:!=|<>)\\s*'PRIMARY'\\s*;?\\s*$"
        ).matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String owner = metadataOwnerExpression(matcher.group("schema"));
        String table = matcher.group("table").trim();
        if (owner.isBlank() || table.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String converted = "SELECT DISTINCT i.INDEX_NAME\n"
                + "FROM ALL_INDEXES i\n"
                + "WHERE i.OWNER = " + owner + "\n"
                + "    AND i.TABLE_NAME = UPPER(" + table + ")\n"
                + "    AND NOT EXISTS (\n"
                + "        SELECT 1\n"
                + "        FROM ALL_CONSTRAINTS ac\n"
                + "        WHERE ac.OWNER = i.OWNER\n"
                + "            AND ac.TABLE_NAME = i.TABLE_NAME\n"
                + "            AND ac.CONSTRAINT_TYPE = 'P'\n"
                + "            AND ac.INDEX_NAME = i.INDEX_NAME\n"
                + "    )";
        return new GenericConversion(converted, true);
    }

    private String metadataOwnerExpression(String tableSchema) {
        if (tableSchema == null || tableSchema.isBlank()) {
            return "";
        }
        if (isMysqlCurrentSchemaExpression(tableSchema)) {
            return DM_CURRENT_SCHEMA_EXPRESSION;
        }
        return "UPPER(" + tableSchema.trim() + ")";
    }

    private String quotedMetadataAlias(String value) {
        return "\"" + unquoteIdentifier(value).replace("\"", "\"\"") + "\"";
    }

    private GenericConversion convertInformationSchemaColumnsDetail(String sql) {
        Matcher matcher = INFORMATION_SCHEMA_COLUMNS_DETAIL_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String whereClause = matcher.group("where");
        Matcher tableNameMatcher = METADATA_TABLE_NAME_CONDITION.matcher(whereClause);
        Matcher tableSchemaMatcher = METADATA_TABLE_SCHEMA_CONDITION.matcher(whereClause);
        if (!tableNameMatcher.find()) {
            return GenericConversion.unchanged(sql);
        }
        String tableName = tableNameMatcher.group("value").trim();
        String tableSchema = tableSchemaMatcher.find() ? tableSchemaMatcher.group("value").trim() : "";
        String residual = tableNameMatcher.replaceAll("");
        residual = METADATA_TABLE_SCHEMA_CONDITION.matcher(residual).replaceAll("");
        residual = residual.replaceAll("(?i)\\bAND\\b", "")
                .replaceAll("[()\\s]", "");
        if (!residual.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String ownerCondition = tableSchema.isBlank()
                ? ""
                : isMysqlCurrentSchemaExpression(tableSchema)
                        ? " AND c.OWNER = " + DM_CURRENT_SCHEMA_EXPRESSION
                        : " AND c.OWNER = UPPER(" + tableSchema + ")";
        String converted = "SELECT\n"
                + "    c.OWNER AS \"" + matcher.group("schemaAlias") + "\",\n"
                + "    c.TABLE_NAME AS \"" + matcher.group("tableAlias") + "\",\n"
                + "    c.COLUMN_NAME AS \"" + matcher.group("columnAlias") + "\",\n"
                + "    c.DATA_TYPE AS \"" + matcher.group("typeAlias") + "\",\n"
                + "    cc.COMMENTS AS \"" + matcher.group("commentAlias") + "\",\n"
                + "    CASE c.NULLABLE WHEN 'Y' THEN 'YES' ELSE 'NO' END AS \""
                + matcher.group("nullableAlias") + "\"\n"
                + "FROM ALL_TAB_COLUMNS c\n"
                + "LEFT JOIN ALL_COL_COMMENTS cc\n"
                + "    ON cc.OWNER = c.OWNER\n"
                + "    AND cc.TABLE_NAME = c.TABLE_NAME\n"
                + "    AND cc.COLUMN_NAME = c.COLUMN_NAME\n"
                + "WHERE c.TABLE_NAME = UPPER(" + tableName + ")"
                + ownerCondition
                + "\nORDER BY c.COLUMN_ID";
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertDescribeTable(String sql) {
        Matcher matcher = MYSQL_DESCRIBE_TABLE_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String tableNameExpression = describeTableNameExpression(matcher.group("table").trim());
        if (tableNameExpression.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String converted = "SELECT COLUMN_NAME AS \"Field\", DATA_TYPE AS \"Type\", NULLABLE AS \"Null\", "
                + "NULL AS \"Key\", DATA_DEFAULT AS \"Default\", NULL AS \"Extra\" "
                + "FROM USER_TAB_COLUMNS WHERE TABLE_NAME = UPPER("
                + tableNameExpression
                + ") ORDER BY COLUMN_ID";
        return new GenericConversion(converted, true);
    }

    private String describeTableNameExpression(String tableName) {
        if (tableName.isBlank()) {
            return "";
        }
        if (tableName.equals("?")
                || tableName.startsWith("#{")
                || tableName.startsWith("'")
                || tableName.startsWith("\"")) {
            return tableName;
        }
        if (tableName.startsWith("${")) {
            return "'" + tableName + "'";
        }
        return "'" + tableName.replace("'", "''") + "'";
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
                    + "WHERE OWNER = " + DM_CURRENT_SCHEMA_EXPRESSION + "\n"
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
                + "WHERE OWNER = " + DM_CURRENT_SCHEMA_EXPRESSION + "\n"
                + "AND TABLE_NAME LIKE UPPER(" + tableLike + ")\n"
                + "AND COLUMN_NAME NOT IN";
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaTables(String sql) {
        GenericConversion tableDetailConversion = convertInformationSchemaTablesDetail(sql);
        if (tableDetailConversion.changed()) {
            return tableDetailConversion;
        }
        GenericConversion tableExistsConversion = convertInformationSchemaTableExists(sql);
        if (tableExistsConversion.changed()) {
            return tableExistsConversion;
        }
        GenericConversion simpleTableListConversion = convertInformationSchemaSimpleTableList(sql);
        if (simpleTableListConversion.changed()) {
            return simpleTableListConversion;
        }
        GenericConversion tableListConversion = convertInformationSchemaTablesList(sql);
        if (tableListConversion.changed()) {
            return tableListConversion;
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
        Matcher tableTypeMatcher = METADATA_TABLE_TYPE_CONDITION.matcher(whereClause);
        String tableType = tableTypeMatcher.find() ? tableTypeMatcher.group("value") : "";
        if (!tableType.isBlank() && !"'BASE TABLE'".equalsIgnoreCase(tableType)) {
            return GenericConversion.unchanged(sql);
        }
        String residual = tableNameMatcher.replaceAll("");
        residual = METADATA_TABLE_SCHEMA_CONDITION.matcher(residual).replaceAll("");
        residual = METADATA_TABLE_TYPE_CONDITION.matcher(residual).replaceAll("");
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
                + metadataOwnerCondition(tableSchema);
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaTableExists(String sql) {
        Matcher matcher = INFORMATION_SCHEMA_TABLES_EXISTS_PATTERN.matcher(sql);
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
        String converted = "SELECT 1 FROM ALL_TABLES WHERE TABLE_NAME = UPPER("
                + tableName
                + ")"
                + metadataOwnerCondition(tableSchema);
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaSimpleTableList(String sql) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*select\\s+table_name\\s+from\\s+information_schema\\s*\\.\\s*tables\\s+"
                        + "where\\s+(?<where>.+?)\\s*;?\\s*$"
        ).matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String whereClause = matcher.group("where");
        Matcher schemaMatcher = METADATA_TABLE_SCHEMA_CONDITION.matcher(whereClause);
        if (!schemaMatcher.find()) {
            return GenericConversion.unchanged(sql);
        }
        String schema = schemaMatcher.group("value").trim();
        Matcher typeMatcher = METADATA_TABLE_TYPE_CONDITION.matcher(whereClause);
        if (!typeMatcher.find()) {
            return GenericConversion.unchanged(sql);
        }
        String tableTypeLiteral = typeMatcher.group("value");
        String tableType = tableTypeLiteral.substring(1, tableTypeLiteral.length() - 1)
                .replace("''", "'");
        if (!"VIEW".equalsIgnoreCase(tableType) && !"BASE TABLE".equalsIgnoreCase(tableType)) {
            return GenericConversion.unchanged(sql);
        }
        Matcher likeMatcher = METADATA_TABLE_NAME_LIKE_CONDITION.matcher(whereClause);
        String tableLike = likeMatcher.find() ? likeMatcher.group("value").trim() : "";
        String residual = schemaMatcher.replaceAll("");
        residual = METADATA_TABLE_TYPE_CONDITION.matcher(residual).replaceAll("");
        residual = METADATA_TABLE_NAME_LIKE_CONDITION.matcher(residual).replaceAll("");
        residual = residual.replaceAll("(?i)\\bAND\\b", "")
                .replaceAll("[()\\s]", "");
        if (!residual.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String owner = metadataOwnerExpression(schema);
        if (owner.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        boolean view = "VIEW".equalsIgnoreCase(tableType);
        String nameColumn = view ? "VIEW_NAME" : "TABLE_NAME";
        String converted = "SELECT " + nameColumn + (view ? " AS TABLE_NAME" : "")
                + " FROM " + (view ? "ALL_VIEWS" : "ALL_TABLES")
                + " WHERE OWNER = " + owner
                + (tableLike.isBlank() ? "" : " AND " + nameColumn + " LIKE UPPER(" + tableLike + ")");
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertInformationSchemaTablesList(String sql) {
        Matcher matcher = INFORMATION_SCHEMA_TABLES_LIST_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return GenericConversion.unchanged(sql);
        }
        String whereClause = matcher.group("where");
        Matcher tableLikeMatcher = METADATA_TABLE_NAME_LIKE_CONDITION.matcher(whereClause);
        if (!tableLikeMatcher.find()) {
            return GenericConversion.unchanged(sql);
        }
        String tableLike = tableLikeMatcher.group("value").trim();
        Matcher tableSchemaMatcher = METADATA_TABLE_SCHEMA_CONDITION.matcher(whereClause);
        String tableSchema = tableSchemaMatcher.find() ? tableSchemaMatcher.group("value").trim() : "";
        String residual = tableLikeMatcher.replaceAll("");
        residual = METADATA_TABLE_SCHEMA_CONDITION.matcher(residual).replaceAll("");
        residual = residual.replaceAll("(?i)\\bAND\\b", "")
                .replaceAll("[()\\s]", "");
        if (!residual.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        String limit = matcher.group("limit");
        String converted = "SELECT TABLE_NAME FROM ALL_TABLES WHERE TABLE_NAME LIKE UPPER("
                + tableLike
                + ")"
                + metadataOwnerCondition(tableSchema)
                + " GROUP BY TABLE_NAME"
                + (limit == null || limit.isBlank() ? "" : " FETCH FIRST " + limit + " ROWS ONLY");
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
                + "AND OWNER = " + DM_CURRENT_SCHEMA_EXPRESSION + "\n"
                + "AND OBJECT_NAME LIKE UPPER(" + tableLike + ")";
        return new GenericConversion(converted, true);
    }

    private GenericConversion convertMysqlCurrentSchemaFunctions(String sql) {
        if (sql.toUpperCase(Locale.ROOT).contains("INFORMATION_SCHEMA")) {
            return GenericConversion.unchanged(sql);
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
            } else {
                FunctionCall schemaCall = readFunctionCall(sql, index, "SCHEMA");
                FunctionCall databaseCall = schemaCall == null ? readFunctionCall(sql, index, "DATABASE") : null;
                FunctionCall functionCall = schemaCall == null ? databaseCall : schemaCall;
                if (functionCall != null && functionCall.body().trim().isEmpty()) {
                    appendFunctionReplacement(converted, DM_CURRENT_SCHEMA_EXPRESSION, sql, functionCall);
                    index = functionCall.endIndex();
                    changed = true;
                } else {
                    converted.append(current);
                    index++;
                }
            }
        }
        return new GenericConversion(changed ? converted.toString() : sql, changed);
    }

    private String metadataOwnerCondition(String tableSchema) {
        if (tableSchema == null || tableSchema.isBlank()) {
            return "";
        }
        if (isMysqlCurrentSchemaExpression(tableSchema)) {
            return " AND OWNER = " + DM_CURRENT_SCHEMA_EXPRESSION;
        }
        return " AND OWNER = UPPER(" + tableSchema + ")";
    }

    private boolean isMysqlCurrentSchemaExpression(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.replaceAll("\\s+", "");
        return "database()".equalsIgnoreCase(normalized)
                || "(selectdatabase())".equalsIgnoreCase(normalized);
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
        return addRecursiveCteColumnAliases(sql, List.of());
    }

    /**
     * Adds the column alias list required by Dameng when a recursive CTE uses a
     * {@code SELECT *} anchor and the mapper result metadata supplies an exact,
     * ordered column list. The normal converter deliberately does not guess
     * columns for star projections; callers may use this overload only with
     * metadata tied to the current mapped statement.
     */
    public SqlConversionResult convertRecursiveCteWithFallbackColumns(
            String sql,
            List<String> fallbackColumns
    ) {
        if (sql == null || sql.isBlank() || fallbackColumns == null || fallbackColumns.isEmpty()) {
            return SqlConversionResult.unchanged(sql == null ? "" : sql);
        }
        GenericConversion conversion = addRecursiveCteColumnAliases(sql, fallbackColumns);
        return conversion.changed()
                ? SqlConversionResult.changed(
                        sql,
                        conversion.convertedSql(),
                        List.of(MYSQL_WITH_RECURSIVE_ALIAS_RULE)
                )
                : SqlConversionResult.unchanged(sql);
    }

    private GenericConversion addRecursiveCteColumnAliases(String sql, List<String> fallbackColumns) {
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
        String cteBody = sql.substring(openParenIndex + 1, closeParenIndex);
        List<String> aliases = inferCteColumnAliases(cteBody);
        if (aliases.isEmpty() && isRecursiveCteStarAnchor(cteBody)) {
            aliases = validatedCteFallbackColumns(fallbackColumns);
        }
        if (aliases.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        aliases = aliases.stream()
                .map(this::quoteRecursiveCteAliasIfNeeded)
                .toList();
        String converted = sql.substring(0, cteName.endIndex())
                + "("
                + String.join(", ", aliases)
                + ")"
                + sql.substring(cteName.endIndex());
        return new GenericConversion(converted, true);
    }

    private boolean isRecursiveCteStarAnchor(String cteBody) {
        int selectIndex = findTopLevelKeyword(cteBody, "SELECT", 0);
        if (selectIndex < 0) {
            return false;
        }
        int fromIndex = findTopLevelKeyword(cteBody, "FROM", selectIndex + "SELECT".length());
        if (fromIndex < 0) {
            return false;
        }
        String projection = cteBody.substring(selectIndex + "SELECT".length(), fromIndex).strip();
        return "*".equals(projection)
                || Pattern.compile("(?is)^(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*\\.\\s*\\*$")
                .matcher(projection)
                .matches();
    }

    private List<String> validatedCteFallbackColumns(List<String> fallbackColumns) {
        if (fallbackColumns == null || fallbackColumns.isEmpty()) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>(fallbackColumns.size());
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawColumn : fallbackColumns) {
            String column = rawColumn == null ? "" : rawColumn.strip();
            if (!isSimpleIdentifier(column)
                    || !normalized.add(column.replace("`", "").replace("\"", "")
                    .toLowerCase(Locale.ROOT))) {
                return List.of();
            }
            aliases.add(column);
        }
        return List.copyOf(aliases);
    }

    private String quoteRecursiveCteAliasIfNeeded(String alias) {
        String candidate = alias == null ? "" : alias.strip();
        if (!isSimpleIdentifier(candidate) || !isDamengKeywordRequiringQuotes(candidate)) {
            return candidate;
        }
        return quoteDamengIdentifier(candidate);
    }

    private List<String> inferCteColumnAliases(String cteBody) {
        int selectIndex = findTopLevelKeyword(cteBody, "SELECT", 0);
        if (selectIndex < 0) {
            return List.of();
        }
        int fromIndex = findTopLevelKeyword(cteBody, "FROM", selectIndex + "SELECT".length());
        int unionIndex = findTopLevelKeyword(cteBody, "UNION", selectIndex + "SELECT".length());
        int selectListEnd = fromIndex >= 0 && (unionIndex < 0 || fromIndex < unionIndex)
                ? fromIndex
                : unionIndex;
        if (selectListEnd < 0) {
            return List.of();
        }
        String selectList = cteBody.substring(selectIndex + "SELECT".length(), selectListEnd);
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
            } else if (startsMyBatisXmlTag(sql, index)) {
                index = skipMyBatisXmlTag(sql, index);
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

    private GenericConversion convertMysqlNullSafeEquals(String sql) {
        List<TextReplacement> replacements = new ArrayList<>();
        int protectedEnd = -1;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (startsMyBatisXmlTag(sql, index)) {
                index = skipMyBatisXmlTag(sql, index);
            } else if (sql.startsWith("<=>", index)) {
                Operand left = readNullSafeLeftOperand(sql, index);
                Operand right = readNullSafeRightOperand(sql, index + 3);
                if (left == null || right == null || left.startIndex() < protectedEnd) {
                    index += 3;
                    continue;
                }
                replacements.add(new TextReplacement(
                        left.startIndex(),
                        right.endIndex(),
                        nullSafeEqualityPredicate(left.text().trim(), right.text().trim())
                ));
                protectedEnd = right.endIndex();
                index = right.endIndex();
            } else {
                index++;
            }
        }
        return applyTextReplacements(sql, replacements);
    }

    private Operand readNullSafeLeftOperand(String sql, int operatorIndex) {
        int end = skipWhitespaceBackward(sql, operatorIndex);
        if (end <= 0) {
            return null;
        }
        int start;
        char previous = sql.charAt(end - 1);
        if (previous == ')') {
            int openParen = findMatchingOpenParenBackward(sql, end - 1);
            if (openParen < 0) {
                return null;
            }
            start = readExpressionNameStartBeforeParen(sql, openParen);
        } else if (previous == '\'') {
            start = findSingleQuotedStringStartEndingAt(sql, end);
        } else if (previous == '}') {
            start = readMyBatisPlaceholderStartBackward(sql, end);
        } else if (previous == '`') {
            start = readBacktickIdentifierStartBackward(sql, end);
            if (start >= 0) {
                start = extendQualifiedIdentifierStartBackward(sql, start);
            }
        } else if (previous == '"') {
            start = readDoubleQuotedIdentifierStartBackward(sql, end);
            if (start >= 0) {
                start = extendQualifiedIdentifierStartBackward(sql, start);
            }
        } else if (isArithmeticIdentifierPart(previous) || previous == '.') {
            start = end - 1;
            while (start > 0 && isOperandIdentifierChar(sql.charAt(start - 1))) {
                start--;
            }
        } else if (Character.isDigit(previous)) {
            start = end - 1;
            while (start > 0 && (Character.isDigit(sql.charAt(start - 1)) || sql.charAt(start - 1) == '.')) {
                start--;
            }
        } else {
            return null;
        }
        if (start < 0 || start >= end) {
            return null;
        }
        String text = sql.substring(start, end);
        return text.isBlank() ? null : new Operand(start, end, text);
    }

    private Operand readNullSafeRightOperand(String sql, int afterOperatorIndex) {
        int start = skipWhitespace(sql, afterOperatorIndex);
        if (start >= sql.length()) {
            return null;
        }
        int index = start;
        int parenthesisDepth = 0;
        int caseDepth = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                break;
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (startsMyBatisXmlTag(sql, index)) {
                break;
            } else if (current == '(') {
                parenthesisDepth++;
                index++;
            } else if (current == ')') {
                if (parenthesisDepth == 0 && caseDepth == 0) {
                    break;
                }
                parenthesisDepth = Math.max(0, parenthesisDepth - 1);
                index++;
            } else if (parenthesisDepth == 0 && startsKeyword(sql, index, "CASE")) {
                caseDepth++;
                index += "CASE".length();
            } else if (parenthesisDepth == 0 && caseDepth > 0 && startsKeyword(sql, index, "END")) {
                caseDepth--;
                index += "END".length();
            } else if (parenthesisDepth == 0 && caseDepth == 0
                    && (current == ',' || current == ';'
                    || startsKeyword(sql, index, "AND")
                    || startsKeyword(sql, index, "OR"))) {
                break;
            } else {
                index++;
            }
        }
        int end = skipWhitespaceBackward(sql, index);
        if (end <= start) {
            return null;
        }
        String text = sql.substring(start, end);
        return text.isBlank() ? null : new Operand(start, end, text);
    }

    private int readDoubleQuotedIdentifierStartBackward(String sql, int endExclusive) {
        int index = endExclusive - 2;
        while (index >= 0) {
            if (sql.charAt(index) == '"') {
                if (index > 0 && sql.charAt(index - 1) == '"') {
                    index -= 2;
                    continue;
                }
                return index;
            }
            index--;
        }
        return -1;
    }

    private String nullSafeEqualityPredicate(String left, String right) {
        return "(CASE WHEN (" + left + ") = (" + right + ")"
                + " OR ((" + left + ") IS NULL AND (" + right + ") IS NULL)"
                + " THEN 1 ELSE 0 END = 1)";
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
        } else if (isIdentifierStart(current)) {
            int cursor = start + 1;
            while (cursor < sql.length() && isIdentifierPart(sql.charAt(cursor))) {
                cursor++;
            }
            int openParen = skipWhitespace(sql, cursor);
            if (openParen < sql.length() && sql.charAt(openParen) == '(') {
                int close = findMatchingParen(sql, openParen);
                if (close < 0) {
                    return null;
                }
                end = close + 1;
            } else {
                end = readRegexpRightUntilBoundary(sql, start);
            }
        } else {
            end = readRegexpRightUntilBoundary(sql, start);
        }
        String text = sql.substring(start, end);
        return text.isBlank() ? null : new Operand(start, end, text);
    }

    private int readRegexpRightUntilBoundary(String sql, int start) {
        int end = start;
        while (end < sql.length() && !isRegexpRightBoundary(sql, end)) {
            end++;
        }
        return end;
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
                converted.append(conversion.convertedSql());
                if (!convertedAnonymousBlockAlreadyContainsSeparator(conversion, statement.separator())) {
                    converted.append(statement.separator());
                }
                changed = changed || conversion.changed();
            }
            return changed ? new GenericConversion(converted.toString(), true) : GenericConversion.unchanged(sql);
        }
        return convertSingleMysqlUpdateJoin(sql);
    }

    private boolean convertedAnonymousBlockAlreadyContainsSeparator(
            GenericConversion conversion,
            String separator
    ) {
        if (!conversion.changed() || !";".equals(separator)) {
            return false;
        }
        String converted = conversion.convertedSql().strip();
        return startsKeyword(converted, 0, "BEGIN") && converted.endsWith(";");
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
        if (containsTopLevelLeftJoin(sql.substring(updateIndex, setIndex))
                && isDamengNativeSingleTargetUpdateJoin(sql)) {
            return GenericConversion.unchanged(sql);
        }
        GenericConversion multiTarget = convertMultiTargetMysqlUpdateJoin(
                sql,
                updateIndex,
                target,
                joinSource,
                setClause,
                whereClause,
                statementEnd
        );
        if (multiTarget.changed()) {
            return multiTarget;
        }
        GenericConversion joinedTarget = convertSingleAssignedJoinedTableMysqlUpdateJoin(
                sql,
                updateIndex,
                target,
                joinSource,
                setClause,
                whereClause,
                setIndex,
                statementEnd
        );
        if (joinedTarget.changed()) {
            return joinedTarget;
        }
        GenericConversion singleTargetChain = convertSingleTargetMysqlUpdateJoinChain(
                sql,
                updateIndex,
                target,
                joinSource,
                setClause,
                whereClause,
                setIndex,
                statementEnd
        );
        if (singleTargetChain.changed()) {
            return singleTargetChain;
        }
        JoinSource splitJoin = splitJoinSource(joinSource);
        if (splitJoin.sourceSql().isBlank() || !splitJoin.sourceSql().startsWith("(")) {
            return GenericConversion.unchanged(sql);
        }
        if (updatesJoinedTableAlias(target, setClause)) {
            return GenericConversion.unchanged(sql);
        }

        if (findTopLevelKeyword(splitJoin.sourceSql(), "JOIN", 0) >= 0
                || findTopLevelKeyword(splitJoin.conditionSql(), "JOIN", 0) >= 0) {
            return GenericConversion.unchanged(sql);
        }
        String convertedSetClause = stripTargetAliasFromUpdateSetClause(target, setClause);
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
                .append(convertedSetClause)
                .append(" from ")
                .append(splitJoin.sourceSql());
        if (!whereParts.isEmpty()) {
            converted.append(" where ").append(String.join(" and ", whereParts));
        }
        converted.append(sql.substring(statementEnd));
        return new GenericConversion(converted.toString(), true);
    }

    private GenericConversion convertMysqlTargetOnlyOuterJoin(String sql) {
        List<StatementSegment> statements = splitTopLevelStatements(sql);
        if (statements.size() > 1) {
            StringBuilder converted = new StringBuilder(sql.length());
            boolean changed = false;
            for (StatementSegment statement : statements) {
                GenericConversion conversion = convertSingleTargetOnlyOuterJoin(statement.sql());
                converted.append(conversion.convertedSql()).append(statement.separator());
                changed = changed || conversion.changed();
            }
            return changed ? new GenericConversion(converted.toString(), true) : GenericConversion.unchanged(sql);
        }
        return convertSingleTargetOnlyOuterJoin(sql);
    }

    private GenericConversion convertUniqueSourceLeftJoinUpdate(
            String sql,
            Map<String, List<String>> tableKeyColumns
    ) {
        if (splitTopLevelStatements(sql).size() != 1) {
            return GenericConversion.unchanged(sql);
        }
        int updateIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, updateIndex, "UPDATE")) {
            return GenericConversion.unchanged(sql);
        }
        int joinIndex = findTopLevelKeyword(sql, "JOIN", updateIndex + "UPDATE".length());
        if (joinIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int setIndex = findTopLevelKeyword(sql, "SET", joinIndex + "JOIN".length());
        if (setIndex < 0
                || findTopLevelKeyword(sql, "JOIN", joinIndex + "JOIN".length()) >= 0) {
            return GenericConversion.unchanged(sql);
        }
        int joinTypeStart = joinTypeStart(sql, joinIndex);
        String joinType = sql.substring(joinTypeStart, joinIndex).strip();
        if (!Pattern.compile("(?is)^LEFT(?:\\s+OUTER)?$").matcher(joinType).matches()) {
            return GenericConversion.unchanged(sql);
        }

        String target = sql.substring(updateIndex + "UPDATE".length(), joinTypeStart).strip();
        String joinSource = sql.substring(joinIndex + "JOIN".length(), setIndex).strip();
        int whereIndex = findTopLevelKeyword(sql, "WHERE", setIndex + "SET".length());
        int statementEnd = stripTrailingSemicolon(sql);
        String setClause = sql.substring(
                setIndex + "SET".length(),
                whereIndex < 0 ? statementEnd : whereIndex
        ).strip();
        String whereClause = whereIndex < 0
                ? ""
                : sql.substring(whereIndex + "WHERE".length(), statementEnd).strip();
        UpdateJoinChain chain = updateJoinChain(target, joinSource, true);
        if (chain == null || chain.tables().size() != 2 || chain.conditions().size() != 1) {
            return GenericConversion.unchanged(sql);
        }
        UpdateJoinTable targetTable = chain.tables().get(0);
        UpdateJoinTable sourceTable = chain.tables().get(1);
        List<String> outerWherePredicates = new ArrayList<>();
        List<String> sourceWherePredicates = new ArrayList<>();
        if (!whereClause.isBlank()) {
            List<String> predicates = splitStrictTopLevelAndPredicates(whereClause);
            if (predicates.isEmpty()) {
                return GenericConversion.unchanged(sql);
            }
            for (String predicate : predicates) {
                if (referencesAliasOutsideIgnoredText(predicate, sourceTable.aliasKey())) {
                    sourceWherePredicates.add(predicate);
                } else {
                    outerWherePredicates.add(predicate);
                }
            }
        }
        List<String> sourceKeys = uniqueSourceKeyColumns(tableKeyColumns, sourceTable.tableSql());
        boolean uniqueSource = !sourceKeys.isEmpty()
                && joinConditionBindsUniqueSourceKey(
                        chain.conditions().get(0),
                        sourceTable.aliasKey(),
                        sourceKeys
                );
        List<String> sourceQueryPredicates = new ArrayList<>();
        sourceQueryPredicates.add(chain.conditions().get(0));
        sourceQueryPredicates.addAll(sourceWherePredicates);

        List<String> assignments = new ArrayList<>();
        boolean sourceDependentAssignment = false;
        for (TopLevelArgument assignment : splitTopLevelArguments(setClause)) {
            Matcher targetMatcher = UPDATE_SET_QUALIFIED_ASSIGNMENT.matcher(assignment.text());
            if (!targetMatcher.find()
                    || !normalizeIdentifierKey(targetMatcher.group("alias"))
                    .equals(targetTable.aliasKey())) {
                return GenericConversion.unchanged(sql);
            }
            String rightSide = assignmentRightSide(assignment.text()).strip();
            String convertedRightSide = rightSide;
            if (referencesAliasOutsideIgnoredText(rightSide, sourceTable.aliasKey())) {
                String presenceExpression = sourcePresenceExpression(
                        rightSide,
                        sourceTable.aliasKey(),
                        sourceKeys,
                        sourceTable.tableSql(),
                        sourceQueryPredicates
                );
                if (!presenceExpression.isBlank()) {
                    convertedRightSide = presenceExpression;
                } else {
                    if (!uniqueSource
                            || containsPatternOutsideIgnoredText(
                            rightSide,
                            Pattern.compile("(?is)\\bSELECT\\b")
                    )) {
                        return GenericConversion.unchanged(sql);
                    }
                    sourceDependentAssignment = true;
                    convertedRightSide = "(SELECT "
                            + rightSide
                            + " FROM "
                            + sourceTable.tableSql()
                            + " WHERE "
                            + String.join(" AND ", sourceQueryPredicates)
                            + ")";
                }
            }
            assignments.add(
                    updateSetColumnWithoutTargetAlias(targetMatcher.group("column"))
                            + " = "
                            + convertedRightSide
            );
        }
        if (assignments.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        if (sourceDependentAssignment && !uniqueSource) {
            return GenericConversion.unchanged(sql);
        }
        if (!sourceWherePredicates.isEmpty()) {
            outerWherePredicates.add(
                    "EXISTS (SELECT 1 FROM "
                            + sourceTable.tableSql()
                            + " WHERE "
                            + String.join(" AND ", sourceQueryPredicates)
                            + ")"
            );
        }

        StringBuilder converted = new StringBuilder(sql.length() + 64);
        converted.append(sql, 0, updateIndex)
                .append("update ")
                .append(targetTable.tableSql())
                .append(" set ")
                .append(String.join(", ", assignments));
        if (!outerWherePredicates.isEmpty()) {
            converted.append(" where ").append(String.join(" AND ", outerWherePredicates));
        }
        converted.append(sql.substring(statementEnd));
        return new GenericConversion(converted.toString(), true);
    }

    private String sourcePresenceExpression(
            String expression,
            String sourceAlias,
            List<String> sourceKeyColumns,
            String sourceTable,
            List<String> sourceQueryPredicates
    ) {
        FunctionCall ifCall = readOnlyFunctionCall(expression, "IF");
        if (ifCall == null) {
            return "";
        }
        List<TopLevelArgument> ifArguments = splitTopLevelArguments(ifCall.body());
        if (ifArguments.size() != 3) {
            return "";
        }
        String condition = ifArguments.get(0).text().strip();
        int equalsIndex = findTopLevelChar(condition, '=', 0);
        if (equalsIndex < 0
                || findTopLevelChar(condition, '=', equalsIndex + 1) >= 0) {
            return "";
        }
        String left = condition.substring(0, equalsIndex).strip();
        String right = condition.substring(equalsIndex + 1).strip();
        FunctionCall ifNullCall = readOnlyFunctionCall(left, "IFNULL");
        if (ifNullCall == null) {
            return "";
        }
        List<TopLevelArgument> ifNullArguments = splitTopLevelArguments(ifNullCall.body());
        if (ifNullArguments.size() != 2
                || !sameSqlToken(ifNullArguments.get(1).text(), right)) {
            return "";
        }
        String presenceColumn = qualifiedColumnForAlias(
                ifNullArguments.get(0).text(),
                sourceAlias
        );
        boolean provenNotNull = sourceKeyColumns.stream()
                .anyMatch(column -> normalizeIdentifierKey(column)
                        .equals(normalizeIdentifierKey(presenceColumn)));
        if (presenceColumn.isBlank()
                || !provenNotNull
                || referencesAliasOutsideIgnoredText(ifArguments.get(1).text(), sourceAlias)
                || referencesAliasOutsideIgnoredText(ifArguments.get(2).text(), sourceAlias)) {
            return "";
        }
        String missingValue = ifArguments.get(1).text().strip();
        String matchedValue = ifArguments.get(2).text().strip();
        return "CASE WHEN EXISTS (SELECT 1 FROM "
                + sourceTable
                + " WHERE "
                + String.join(" AND ", sourceQueryPredicates)
                + ") THEN "
                + matchedValue
                + " ELSE "
                + missingValue
                + " END";
    }

    private boolean sameSqlToken(String left, String right) {
        return left != null
                && right != null
                && left.replaceAll("\\s+", "").equalsIgnoreCase(right.replaceAll("\\s+", ""));
    }

    private List<String> uniqueSourceKeyColumns(
            Map<String, List<String>> tableKeyColumns,
            String tableSql
    ) {
        String trimmed = tableSql == null ? "" : tableSql.strip();
        if (trimmed.isBlank()) {
            return List.of();
        }
        if (trimmed.startsWith("(")) {
            return derivedGroupedSourceKeyColumns(trimmed);
        }
        String tableToken = Pattern.compile("\\s+").split(trimmed, 2)[0];
        String normalizedTable = normalizeIdentifierKey(tableLeaf(tableToken))
                .toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : tableKeyColumns.entrySet()) {
            String key = normalizeIdentifierKey(tableLeaf(entry.getKey()))
                    .toLowerCase(Locale.ROOT);
            if (key.equals(normalizedTable) && entry.getValue() != null) {
                return entry.getValue().stream()
                        .filter(column -> column != null && !column.isBlank())
                        .map(String::strip)
                        .toList();
            }
        }
        return List.of();
    }

    private List<String> derivedGroupedSourceKeyColumns(String tableSql) {
        int closeParen = findMatchingParen(tableSql, 0);
        if (closeParen < 0) {
            return List.of();
        }
        String query = tableSql.substring(1, closeParen).strip();
        if (!startsKeyword(query, 0, "SELECT")) {
            return List.of();
        }
        int groupIndex = findTopLevelKeyword(query, "GROUP", 0);
        if (groupIndex < 0) {
            return List.of();
        }
        int byIndex = skipWhitespace(query, groupIndex + "GROUP".length());
        if (!startsKeyword(query, byIndex, "BY")) {
            return List.of();
        }
        int groupStart = skipWhitespace(query, byIndex + "BY".length());
        int groupEnd = query.length();
        for (String clause : List.of("HAVING", "ORDER", "LIMIT", "FETCH")) {
            int clauseIndex = findTopLevelKeyword(query, clause, groupStart);
            if (clauseIndex >= 0) {
                groupEnd = Math.min(groupEnd, clauseIndex);
            }
        }
        String groupClause = query.substring(groupStart, groupEnd).strip();
        if (groupClause.isBlank()) {
            return List.of();
        }
        Pattern simpleColumn = Pattern.compile(
                "(?is)^\\s*(?:(?:" + DM_IDENTIFIER + ")\\s*\\.\\s*)?"
                        + "(?<column>" + DM_IDENTIFIER + ")\\s*$"
        );
        List<String> columns = new ArrayList<>();
        for (TopLevelArgument argument : splitTopLevelArguments(groupClause)) {
            Matcher matcher = simpleColumn.matcher(argument.text());
            if (!matcher.matches()) {
                return List.of();
            }
            columns.add(matcher.group("column"));
        }
        return List.copyOf(columns);
    }

    private boolean joinConditionBindsUniqueSourceKey(
            String condition,
            String sourceAlias,
            List<String> sourceKeyColumns
    ) {
        List<String> predicates = splitStrictTopLevelAndPredicates(condition);
        if (predicates.isEmpty()) {
            return false;
        }
        for (String keyColumn : sourceKeyColumns) {
            boolean bound = false;
            for (String predicate : predicates) {
                int equalsIndex = findTopLevelChar(predicate, '=', 0);
                if (equalsIndex < 0
                        || findTopLevelChar(predicate, '=', equalsIndex + 1) >= 0) {
                    continue;
                }
                String left = predicate.substring(0, equalsIndex).strip();
                String right = predicate.substring(equalsIndex + 1).strip();
                if (isQualifiedColumn(left, sourceAlias, keyColumn)
                        && !referencesAliasOutsideIgnoredText(right, sourceAlias)) {
                    bound = true;
                    break;
                }
                if (isQualifiedColumn(right, sourceAlias, keyColumn)
                        && !referencesAliasOutsideIgnoredText(left, sourceAlias)) {
                    bound = true;
                    break;
                }
            }
            if (!bound) {
                return false;
            }
        }
        return true;
    }

    private boolean isQualifiedColumn(String expression, String alias, String column) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*(?<alias>" + DM_IDENTIFIER + ")\\s*\\.\\s*"
                        + "(?<column>" + DM_IDENTIFIER + ")\\s*$"
        ).matcher(expression == null ? "" : expression);
        return matcher.matches()
                && normalizeIdentifierKey(matcher.group("alias")).equals(alias)
                && normalizeIdentifierKey(matcher.group("column"))
                .equals(normalizeIdentifierKey(column));
    }

    private GenericConversion convertSingleTargetOnlyOuterJoin(String sql) {
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
        int whereIndex = findTopLevelKeyword(sql, "WHERE", setIndex + "SET".length());
        int statementEnd = stripTrailingSemicolon(sql);
        String setClause = sql.substring(setIndex + "SET".length(), whereIndex < 0 ? statementEnd : whereIndex).trim();
        String whereClause = whereIndex < 0 ? "" : sql.substring(whereIndex + "WHERE".length(), statementEnd).trim();
        if (target.isBlank() || joinSource.isBlank() || setClause.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        return convertTargetOnlyOuterJoinToMerge(
                sql,
                updateIndex,
                target,
                joinSource,
                setClause,
                whereClause,
                setIndex,
                statementEnd
        );
    }

    private GenericConversion convertTargetOnlyOuterJoinToMerge(
            String sql,
            int updateIndex,
            String target,
            String joinSource,
            String setClause,
            String whereClause,
            int setIndex,
            int statementEnd
    ) {
        String updateJoinClause = sql.substring(updateIndex, setIndex);
        if (!containsTopLevelOuterOrCrossJoin(updateJoinClause)) {
            return GenericConversion.unchanged(sql);
        }
        UpdateJoinChain chain = updateJoinChain(target, joinSource, true);
        if (chain == null || chain.tables().size() < 2) {
            return GenericConversion.unchanged(sql);
        }

        String assignmentAlias = null;
        String assignmentAliasSql = null;
        for (TopLevelArgument assignment : splitTopLevelArguments(setClause)) {
            Matcher matcher = UPDATE_SET_QUALIFIED_ASSIGNMENT.matcher(assignment.text());
            if (!matcher.find()) {
                return GenericConversion.unchanged(sql);
            }
            String alias = normalizeIdentifierKey(matcher.group("alias"));
            if (assignmentAlias != null && !assignmentAlias.equals(alias)) {
                return GenericConversion.unchanged(sql);
            }
            assignmentAlias = alias;
            assignmentAliasSql = matcher.group("alias");
        }
        UpdateJoinTable updateTable = tableByAlias(chain.tables(), assignmentAlias);
        if (updateTable == null || updateTable.tableSql().startsWith("(") || assignmentAliasSql == null) {
            return GenericConversion.unchanged(sql);
        }
        for (TopLevelArgument assignment : splitTopLevelArguments(setClause)) {
            String rightSide = assignmentRightSide(assignment.text());
            for (UpdateJoinTable table : chain.tables()) {
                if (!table.aliasKey().equals(assignmentAlias)
                        && referencesAliasOutsideIgnoredText(rightSide, table.aliasKey())) {
                    return GenericConversion.unchanged(sql);
                }
            }
        }

        String originalJoinSql = sql.substring(
                updateIndex + "UPDATE".length(),
                setIndex
        ).strip();
        String sourceAlias = "dm_update_source";
        String rowIdAlias = "dm_target_rowid";
        StringBuilder converted = new StringBuilder(sql.length() + 160);
        converted.append(sql, 0, updateIndex)
                .append("MERGE INTO ")
                .append(updateTable.tableSql())
                .append(" USING (SELECT DISTINCT ")
                .append(assignmentAliasSql)
                .append(".ROWID AS ")
                .append(rowIdAlias)
                .append(" FROM ")
                .append(originalJoinSql);
        if (!whereClause.isBlank()) {
            converted.append(" WHERE ").append(whereClause);
        }
        converted.append(") ")
                .append(sourceAlias)
                .append(" ON (")
                .append(assignmentAliasSql)
                .append(".ROWID = ")
                .append(sourceAlias)
                .append(".")
                .append(rowIdAlias)
                .append(") WHEN MATCHED THEN UPDATE SET ")
                .append(setClause)
                .append(sql.substring(statementEnd));
        return new GenericConversion(converted.toString(), true);
    }

    private boolean referencesAliasOutsideIgnoredText(String expression, String aliasKey) {
        if (expression == null || expression.isBlank() || aliasKey == null || aliasKey.isBlank()) {
            return false;
        }
        int index = 0;
        while (index < expression.length()) {
            if (expression.charAt(index) == '\'') {
                index = skipSingleQuotedString(expression, index);
            } else if (startsMyBatisPlaceholder(expression, index)) {
                index = skipMyBatisPlaceholder(expression, index);
            } else if (startsLineComment(expression, index)) {
                index = skipUntilLineEnd(expression, index);
            } else if (startsBlockComment(expression, index)) {
                index = skipUntilBlockCommentEnd(expression, index);
            } else {
                IdentifierToken identifier = readIdentifierToken(expression, index);
                if (identifier == null) {
                    index++;
                    continue;
                }
                int dotIndex = skipWhitespace(expression, identifier.endIndex());
                if (normalizeIdentifierKey(unquoteIdentifier(identifier.text())).equals(aliasKey)
                        && dotIndex < expression.length()
                        && expression.charAt(dotIndex) == '.') {
                    return true;
                }
                index = identifier.endIndex();
            }
        }
        return false;
    }

    private GenericConversion convertSingleAssignedJoinedTableMysqlUpdateJoin(
            String sql,
            int updateIndex,
            String target,
            String joinSource,
            String setClause,
            String whereClause,
            int setIndex,
            int statementEnd
    ) {
        UpdateJoinChain chain = updateJoinChain(target, joinSource, true);
        if (chain == null
                || chain.tables().size() < 2
                || containsTopLevelOuterOrCrossJoin(sql.substring(updateIndex, setIndex))) {
            return GenericConversion.unchanged(sql);
        }
        String assignmentAlias = null;
        for (TopLevelArgument assignment : splitTopLevelArguments(setClause)) {
            Matcher matcher = UPDATE_SET_QUALIFIED_ASSIGNMENT.matcher(assignment.text());
            if (!matcher.find()) {
                return GenericConversion.unchanged(sql);
            }
            String alias = normalizeIdentifierKey(matcher.group("alias"));
            if (assignmentAlias != null && !assignmentAlias.equals(alias)) {
                return GenericConversion.unchanged(sql);
            }
            assignmentAlias = alias;
        }
        if (assignmentAlias == null || assignmentAlias.equals(chain.tables().get(0).aliasKey())) {
            return GenericConversion.unchanged(sql);
        }
        UpdateJoinTable updateTable = tableByAlias(chain.tables(), assignmentAlias);
        if (updateTable == null || updateTable.tableSql().startsWith("(")) {
            return GenericConversion.unchanged(sql);
        }
        String updateAlias = assignmentAlias;
        List<String> sources = chain.tables().stream()
                .filter(table -> !table.aliasKey().equals(updateAlias))
                .map(UpdateJoinTable::tableSql)
                .toList();
        if (sources.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        String convertedSetClause = stripTargetAliasFromUpdateSetClause(updateTable.tableSql(), setClause);
        List<String> whereParts = updateJoinPredicates(chain.conditions(), whereClause);
        StringBuilder converted = new StringBuilder(sql.length() + 32);
        converted.append(sql, 0, updateIndex)
                .append("update ")
                .append(updateTable.tableSql())
                .append(" set ")
                .append(convertedSetClause)
                .append(" from ")
                .append(String.join(", ", sources));
        if (!whereParts.isEmpty()) {
            converted.append(" where ").append(String.join(" and ", whereParts));
        }
        converted.append(sql.substring(statementEnd));
        return new GenericConversion(converted.toString(), true);
    }

    private GenericConversion convertSingleTargetMysqlUpdateJoinChain(
            String sql,
            int updateIndex,
            String target,
            String joinSource,
            String setClause,
            String whereClause,
            int setIndex,
            int statementEnd
    ) {
        UpdateJoinChain chain = updateJoinChain(target, joinSource, true);
        if (chain == null
                || chain.tables().size() < 2
                || updatesJoinedTableAlias(target, setClause)
                || containsTopLevelOuterOrCrossJoin(sql.substring(updateIndex, setIndex))) {
            return GenericConversion.unchanged(sql);
        }
        List<String> sources = chain.tables().subList(1, chain.tables().size()).stream()
                .map(UpdateJoinTable::tableSql)
                .toList();
        if (sources.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        String convertedSetClause = stripTargetAliasFromUpdateSetClause(target, setClause);
        List<String> whereParts = updateJoinPredicates(chain.conditions(), whereClause);
        StringBuilder converted = new StringBuilder(sql.length() + 32);
        converted.append(sql, 0, updateIndex)
                .append("update ")
                .append(target)
                .append(" set ")
                .append(convertedSetClause)
                .append(" from ")
                .append(String.join(", ", sources));
        if (!whereParts.isEmpty()) {
            converted.append(" where ").append(String.join(" and ", whereParts));
        }
        converted.append(sql.substring(statementEnd));
        return new GenericConversion(converted.toString(), true);
    }

    private boolean containsTopLevelOuterOrCrossJoin(String updateJoinClause) {
        int searchFrom = 0;
        while (searchFrom < updateJoinClause.length()) {
            int joinIndex = findTopLevelKeyword(updateJoinClause, "JOIN", searchFrom);
            if (joinIndex < 0) {
                return false;
            }
            int typeStart = joinTypeStart(updateJoinClause, joinIndex);
            String joinType = updateJoinClause.substring(typeStart, joinIndex).strip();
            if (Pattern.compile("(?is)^(?:LEFT|RIGHT|FULL|CROSS)(?:\\s+OUTER)?$")
                    .matcher(joinType)
                    .matches()) {
                return true;
            }
            searchFrom = joinIndex + "JOIN".length();
        }
        return false;
    }

    private String stripTargetAliasFromUpdateSetClause(String target, String setClause) {
        String targetAlias = updateTargetAlias(target);
        if (targetAlias.isBlank()) {
            return setClause;
        }
        StringBuilder converted = new StringBuilder(setClause.length());
        int lastCopiedIndex = 0;
        boolean changed = false;
        for (TopLevelArgument assignment : splitTopLevelArguments(setClause)) {
            Matcher matcher = UPDATE_SET_QUALIFIED_ASSIGNMENT.matcher(assignment.text());
            if (!matcher.find() || !normalizeIdentifierKey(matcher.group("alias")).equals(targetAlias)) {
                continue;
            }
            converted.append(setClause, lastCopiedIndex, assignment.startIndex());
            converted.append(assignment.text(), 0, matcher.start("alias"));
            converted.append(updateSetColumnWithoutTargetAlias(matcher.group("column")));
            converted.append(assignment.text().substring(matcher.end("column")));
            lastCopiedIndex = assignment.endIndex();
            changed = true;
        }
        if (!changed) {
            return setClause;
        }
        converted.append(setClause.substring(lastCopiedIndex));
        return converted.toString();
    }

    private String updateSetColumnWithoutTargetAlias(String column) {
        String unquoted = unquoteIdentifier(column);
        if ("DESC".equalsIgnoreCase(unquoted)) {
            return "\"DESC\"";
        }
        return column;
    }

    private GenericConversion convertMultiTargetMysqlUpdateJoin(
            String sql,
            int updateIndex,
            String target,
            String joinSource,
            String setClause,
            String whereClause,
            int statementEnd
    ) {
        UpdateJoinChain chain = updateJoinChain(target, joinSource);
        if (chain == null || chain.tables().size() < 2) {
            return GenericConversion.unchanged(sql);
        }
        Map<String, List<UpdateSetAssignment>> assignmentsByAlias = new LinkedHashMap<>();
        for (TopLevelArgument assignment : splitTopLevelArguments(setClause)) {
            Matcher matcher = UPDATE_SET_QUALIFIED_ASSIGNMENT.matcher(assignment.text());
            if (!matcher.find()) {
                return GenericConversion.unchanged(sql);
            }
            String aliasKey = normalizeIdentifierKey(matcher.group("alias"));
            if (tableByAlias(chain.tables(), aliasKey) == null) {
                return GenericConversion.unchanged(sql);
            }
            assignmentsByAlias
                    .computeIfAbsent(aliasKey, ignored -> new ArrayList<>())
                    .add(new UpdateSetAssignment(
                            aliasKey,
                            normalizeIdentifierKey(matcher.group("column")),
                            assignment.text().trim()
                    ));
        }
        if (assignmentsByAlias.size() < 2) {
            return GenericConversion.unchanged(sql);
        }
        if (hasCrossTargetAssignmentDependency(assignmentsByAlias)) {
            return GenericConversion.unchanged(sql);
        }
        List<Map.Entry<String, List<UpdateSetAssignment>>> orderedAssignments =
                new ArrayList<>(assignmentsByAlias.entrySet());
        String predicates = String.join(" and ", updateJoinPredicates(chain.conditions(), whereClause));
        long predicateUpdatingAliases = orderedAssignments.stream()
                .filter(entry -> updatesPredicateColumn(entry.getKey(), entry.getValue(), predicates))
                .count();
        if (predicateUpdatingAliases > 1) {
            return convertGuardedTwoTargetMysqlUpdateJoin(
                    sql,
                    updateIndex,
                    statementEnd,
                    chain,
                    assignmentsByAlias,
                    whereClause
            );
        }
        orderedAssignments.sort((left, right) -> Boolean.compare(
                updatesPredicateColumn(left.getKey(), left.getValue(), predicates),
                updatesPredicateColumn(right.getKey(), right.getValue(), predicates)
        ));

        StringBuilder converted = new StringBuilder(sql.length() + 32);
        converted.append(sql, 0, updateIndex).append("BEGIN\n");
        for (Map.Entry<String, List<UpdateSetAssignment>> entry : orderedAssignments) {
            UpdateJoinTable updateTable = tableByAlias(chain.tables(), entry.getKey());
            if (updateTable == null) {
                return GenericConversion.unchanged(sql);
            }
            List<String> sources = chain.tables().stream()
                    .filter(table -> !table.aliasKey().equals(entry.getKey()))
                    .map(UpdateJoinTable::tableSql)
                    .toList();
            if (sources.isEmpty()) {
                return GenericConversion.unchanged(sql);
            }
            converted.append("update ")
                    .append(updateTable.tableSql())
                    .append(" set ")
                    .append(String.join(", ", entry.getValue().stream().map(UpdateSetAssignment::text).toList()))
                    .append(" from ")
                    .append(String.join(", ", sources));
            List<String> whereParts = updateJoinPredicates(chain.conditions(), whereClause);
            if (!whereParts.isEmpty()) {
                converted.append(" where ").append(String.join(" and ", whereParts));
            }
            converted.append(";\n");
        }
        converted.append("END;");
        return new GenericConversion(converted.toString(), true);
    }

    private GenericConversion convertGuardedTwoTargetMysqlUpdateJoin(
            String sql,
            int updateIndex,
            int statementEnd,
            UpdateJoinChain chain,
            Map<String, List<UpdateSetAssignment>> assignmentsByAlias,
            String whereClause
    ) {
        if (chain.tables().size() != 2 || assignmentsByAlias.size() != 2 || chain.conditions().size() != 1) {
            return GenericConversion.unchanged(sql);
        }
        UpdateJoinTable primary = chain.tables().get(0);
        UpdateJoinTable secondary = chain.tables().get(1);
        List<UpdateSetAssignment> primaryAssignments = assignmentsByAlias.get(primary.aliasKey());
        List<UpdateSetAssignment> secondaryAssignments = assignmentsByAlias.get(secondary.aliasKey());
        if (primaryAssignments == null || secondaryAssignments == null) {
            return GenericConversion.unchanged(sql);
        }
        for (UpdateSetAssignment assignment : secondaryAssignments) {
            String rightSide = assignmentRightSide(assignment.text());
            if (referencesAlias(rightSide, primary.aliasKey())) {
                return GenericConversion.unchanged(sql);
            }
        }

        List<String> wherePredicates = splitStrictTopLevelAndPredicates(whereClause);
        List<String> joinPredicates = splitStrictTopLevelAndPredicates(chain.conditions().get(0));
        if (wherePredicates.isEmpty() || joinPredicates.isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        BoundAliasValue primaryBinding = boundAliasValue(wherePredicates, primary.aliasKey());
        if (primaryBinding == null || !isLikelyIdentityColumn(primaryBinding.columnKey())) {
            return GenericConversion.unchanged(sql);
        }
        AliasJoinBinding joinBinding = aliasJoinBinding(
                joinPredicates,
                primary.aliasKey(),
                primaryBinding.columnKey(),
                secondary.aliasKey()
        );
        if (joinBinding == null) {
            return GenericConversion.unchanged(sql);
        }

        List<String> secondaryPredicates = new ArrayList<>();
        secondaryPredicates.add(
                joinBinding.secondaryExpression() + " = " + primaryBinding.boundValue()
        );
        List<String> allPredicates = new ArrayList<>();
        allPredicates.addAll(joinPredicates);
        allPredicates.addAll(wherePredicates);
        for (String predicate : allPredicates) {
            if (predicate.equals(joinBinding.originalPredicate())
                    || predicate.equals(primaryBinding.originalPredicate())) {
                continue;
            }
            boolean referencesPrimary = referencesAlias(predicate, primary.aliasKey());
            boolean referencesSecondary = referencesAlias(predicate, secondary.aliasKey());
            if (referencesPrimary && referencesSecondary) {
                return GenericConversion.unchanged(sql);
            }
            if (referencesPrimary) {
                continue;
            }
            if (referencesSecondary || (!referencesPrimary && !referencesSecondary)) {
                secondaryPredicates.add(predicate);
            }
        }

        String originalPredicates = String.join(
                " and ",
                updateJoinPredicates(chain.conditions(), whereClause)
        );
        StringBuilder converted = new StringBuilder(sql.length() + 96);
        converted.append(sql, 0, updateIndex)
                .append("BEGIN\n")
                .append("update ")
                .append(primary.tableSql())
                .append(" set ")
                .append(String.join(", ", primaryAssignments.stream().map(UpdateSetAssignment::text).toList()))
                .append(" from ")
                .append(secondary.tableSql())
                .append(" where ")
                .append(originalPredicates)
                .append(";\n")
                .append("IF SQL%ROWCOUNT > 0 THEN\n")
                .append("update ")
                .append(secondary.tableSql())
                .append(" set ")
                .append(String.join(", ", secondaryAssignments.stream().map(UpdateSetAssignment::text).toList()))
                .append(" where ")
                .append(String.join(" and ", secondaryPredicates))
                .append(";\n")
                .append("END IF;\n")
                .append("END;")
                .append(trailingWhitespace(sql));
        return new GenericConversion(converted.toString(), true);
    }

    private String trailingWhitespace(String sql) {
        int start = sql.length();
        while (start > 0 && Character.isWhitespace(sql.charAt(start - 1))) {
            start--;
        }
        return sql.substring(start);
    }

    private String assignmentRightSide(String assignment) {
        int equals = assignment == null ? -1 : assignment.indexOf('=');
        return equals < 0 ? "" : assignment.substring(equals + 1);
    }

    private boolean hasCrossTargetAssignmentDependency(
            Map<String, List<UpdateSetAssignment>> assignmentsByAlias
    ) {
        Pattern qualifiedColumn = Pattern.compile(
                "(?is)(" + DM_IDENTIFIER + ")\\s*\\.\\s*(" + DM_IDENTIFIER + ")"
        );
        for (Map.Entry<String, List<UpdateSetAssignment>> entry : assignmentsByAlias.entrySet()) {
            for (UpdateSetAssignment assignment : entry.getValue()) {
                Matcher reference = qualifiedColumn.matcher(assignmentRightSide(assignment.text()));
                while (reference.find()) {
                    String referencedAlias = normalizeIdentifierKey(reference.group(1));
                    if (referencedAlias.equals(entry.getKey())) {
                        continue;
                    }
                    List<UpdateSetAssignment> referencedAssignments = assignmentsByAlias.get(referencedAlias);
                    if (referencedAssignments == null) {
                        continue;
                    }
                    String referencedColumn = normalizeIdentifierKey(reference.group(2));
                    if (referencedAssignments.stream()
                            .anyMatch(candidate -> candidate.columnKey().equals(referencedColumn))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<String> splitStrictTopLevelAndPredicates(String value) {
        if (value == null || value.isBlank()
                || findTopLevelKeyword(value, "OR", 0) >= 0
                || findTopLevelKeyword(value, "BETWEEN", 0) >= 0) {
            return List.of();
        }
        List<String> predicates = new ArrayList<>();
        String remaining = value.strip();
        while (!remaining.isBlank()) {
            int andIndex = findTopLevelKeyword(remaining, "AND", 0);
            String predicate = andIndex < 0 ? remaining : remaining.substring(0, andIndex);
            if (predicate.isBlank()) {
                return List.of();
            }
            predicates.add(predicate.strip());
            if (andIndex < 0) {
                break;
            }
            remaining = remaining.substring(andIndex + "AND".length()).strip();
        }
        return predicates;
    }

    private BoundAliasValue boundAliasValue(List<String> predicates, String aliasKey) {
        String identifier = DM_IDENTIFIER;
        String placeholder = "(?:#\\{[^}]+}|\\?)";
        Pattern columnFirst = Pattern.compile(
                "(?is)^\\s*(" + identifier + ")\\s*\\.\\s*(" + identifier + ")\\s*=\\s*("
                        + placeholder + ")\\s*$"
        );
        Pattern valueFirst = Pattern.compile(
                "(?is)^\\s*(" + placeholder + ")\\s*=\\s*(" + identifier + ")\\s*\\.\\s*("
                        + identifier + ")\\s*$"
        );
        BoundAliasValue found = null;
        for (String predicate : predicates) {
            Matcher columnMatcher = columnFirst.matcher(predicate);
            String alias;
            String column;
            String value;
            if (columnMatcher.matches()) {
                alias = columnMatcher.group(1);
                column = columnMatcher.group(2);
                value = columnMatcher.group(3);
            } else {
                Matcher valueMatcher = valueFirst.matcher(predicate);
                if (!valueMatcher.matches()) {
                    continue;
                }
                value = valueMatcher.group(1);
                alias = valueMatcher.group(2);
                column = valueMatcher.group(3);
            }
            if (!normalizeIdentifierKey(alias).equals(aliasKey)) {
                continue;
            }
            BoundAliasValue candidate = new BoundAliasValue(
                    normalizeIdentifierKey(column),
                    value.strip(),
                    predicate
            );
            if (found != null && !found.equals(candidate)) {
                return null;
            }
            found = candidate;
        }
        return found;
    }

    private AliasJoinBinding aliasJoinBinding(
            List<String> predicates,
            String primaryAlias,
            String primaryColumn,
            String secondaryAlias
    ) {
        String identifier = DM_IDENTIFIER;
        Pattern equality = Pattern.compile(
                "(?is)^\\s*(" + identifier + ")\\s*\\.\\s*(" + identifier + ")\\s*=\\s*("
                        + identifier + ")\\s*\\.\\s*(" + identifier + ")\\s*$"
        );
        AliasJoinBinding found = null;
        for (String predicate : predicates) {
            Matcher matcher = equality.matcher(predicate);
            if (!matcher.matches()) {
                continue;
            }
            String leftAlias = normalizeIdentifierKey(matcher.group(1));
            String leftColumn = normalizeIdentifierKey(matcher.group(2));
            String rightAlias = normalizeIdentifierKey(matcher.group(3));
            String rightColumn = normalizeIdentifierKey(matcher.group(4));
            String secondaryExpression;
            if (leftAlias.equals(primaryAlias)
                    && leftColumn.equals(primaryColumn)
                    && rightAlias.equals(secondaryAlias)) {
                secondaryExpression = matcher.group(3) + "." + matcher.group(4);
            } else if (rightAlias.equals(primaryAlias)
                    && rightColumn.equals(primaryColumn)
                    && leftAlias.equals(secondaryAlias)) {
                secondaryExpression = matcher.group(1) + "." + matcher.group(2);
            } else {
                continue;
            }
            AliasJoinBinding candidate = new AliasJoinBinding(secondaryExpression, predicate);
            if (found != null && !found.equals(candidate)) {
                return null;
            }
            found = candidate;
        }
        return found;
    }

    private boolean referencesAlias(String value, String aliasKey) {
        if (value == null || value.isBlank() || aliasKey == null || aliasKey.isBlank()) {
            return false;
        }
        return Pattern.compile(
                "(?i)(?<![A-Za-z0-9_$])" + Pattern.quote(aliasKey) + "\\s*\\."
        ).matcher(value).find();
    }

    private boolean isLikelyIdentityColumn(String columnKey) {
        return "ID".equals(columnKey) || columnKey.endsWith("_ID");
    }

    private UpdateJoinChain updateJoinChain(String target, String joinSource) {
        return updateJoinChain(target, joinSource, false);
    }

    private UpdateJoinChain updateJoinChain(String target, String joinSource, boolean allowDerivedTable) {
        List<UpdateJoinTable> tables = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        UpdateJoinTable targetTable = updateJoinTable(target);
        if (targetTable == null) {
            return null;
        }
        tables.add(targetTable);

        String remaining = joinSource == null ? "" : joinSource.strip();
        while (!remaining.isBlank()) {
            int onIndex = findTopLevelKeyword(remaining, "ON", 0);
            if (onIndex < 0) {
                return null;
            }
            UpdateJoinTable sourceTable = updateJoinTable(remaining.substring(0, onIndex).strip());
            if (sourceTable == null && allowDerivedTable) {
                sourceTable = derivedUpdateJoinTable(remaining.substring(0, onIndex).strip());
            }
            if (sourceTable == null) {
                return null;
            }
            tables.add(sourceTable);

            int conditionStart = onIndex + "ON".length();
            int nextJoinIndex = findTopLevelKeyword(remaining, "JOIN", conditionStart);
            int conditionEnd = nextJoinIndex < 0 ? remaining.length() : joinTypeStart(remaining, nextJoinIndex);
            String condition = remaining.substring(conditionStart, conditionEnd).strip();
            if (condition.isBlank()) {
                return null;
            }
            conditions.add(condition);
            if (nextJoinIndex < 0) {
                break;
            }
            remaining = remaining.substring(nextJoinIndex + "JOIN".length()).strip();
        }
        return new UpdateJoinChain(tables, conditions);
    }

    private UpdateJoinTable derivedUpdateJoinTable(String tableSql) {
        String trimmed = tableSql == null ? "" : tableSql.strip();
        if (!trimmed.startsWith("(")) {
            return null;
        }
        int closeParen = findMatchingParen(trimmed, 0);
        if (closeParen < 0) {
            return null;
        }
        String query = trimmed.substring(1, closeParen).stripLeading();
        if (!startsKeyword(query, 0, "SELECT") && !startsKeyword(query, 0, "WITH")) {
            return null;
        }
        String aliasText = trimmed.substring(closeParen + 1).strip();
        if (startsKeyword(aliasText, 0, "AS")) {
            aliasText = aliasText.substring("AS".length()).strip();
        }
        IdentifierToken alias = readIdentifierToken(aliasText, 0);
        if (alias == null || skipWhitespace(aliasText, alias.endIndex()) != aliasText.length()) {
            return null;
        }
        return new UpdateJoinTable(trimmed, normalizeIdentifierKey(alias.text()));
    }

    private UpdateJoinTable updateJoinTable(String tableSql) {
        String trimmed = tableSql == null ? "" : tableSql.strip();
        if (trimmed.isBlank() || trimmed.startsWith("(")) {
            return null;
        }
        List<String> parts = Pattern.compile("\\s+")
                .splitAsStream(trimmed)
                .filter(part -> !part.isBlank())
                .toList();
        if (parts.isEmpty()) {
            return null;
        }
        String alias = parts.size() >= 2 ? parts.get(parts.size() - 1) : tableLeaf(parts.get(0));
        if ("AS".equalsIgnoreCase(alias) && parts.size() >= 2) {
            alias = parts.get(parts.size() - 2);
        }
        if (isSqlClauseKeyword(alias)) {
            return null;
        }
        return new UpdateJoinTable(trimmed, normalizeIdentifierKey(alias));
    }

    private String tableLeaf(String tableName) {
        String unquoted = unquoteIdentifier(tableName == null ? "" : tableName.trim());
        int dot = unquoted.lastIndexOf('.');
        return dot >= 0 && dot + 1 < unquoted.length() ? unquoted.substring(dot + 1) : unquoted;
    }

    private UpdateJoinTable tableByAlias(List<UpdateJoinTable> tables, String aliasKey) {
        for (UpdateJoinTable table : tables) {
            if (table.aliasKey().equals(aliasKey)) {
                return table;
            }
        }
        return null;
    }

    private List<String> updateJoinPredicates(List<String> joinConditions, String whereClause) {
        List<String> predicates = new ArrayList<>();
        for (String condition : joinConditions) {
            if (condition != null && !condition.isBlank()) {
                predicates.add(condition);
            }
        }
        if (whereClause != null && !whereClause.isBlank()) {
            predicates.add(whereClause);
        }
        return predicates;
    }

    private boolean updatesPredicateColumn(
            String aliasKey,
            List<UpdateSetAssignment> assignments,
            String predicates
    ) {
        String normalizedPredicates = normalizeIdentifierKey(predicates).replaceAll("\\s+", "");
        for (UpdateSetAssignment assignment : assignments) {
            String reference = aliasKey + "." + assignment.columnKey();
            if (normalizedPredicates.contains(reference)) {
                return true;
            }
        }
        return false;
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
        if (!TABLE_ALIAS_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
        }
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
        Map<String, String> aliases = new LinkedHashMap<>();
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
            Map<String, String> aliases
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
                        String quotedAlias = alias.text().startsWith("`") || alias.text().startsWith("\"")
                                ? alias.text()
                                : quoteDamengIdentifier(unquotedAlias);
                        aliases.putIfAbsent(unquotedAlias.toUpperCase(Locale.ROOT), quotedAlias);
                    }
                    if (!alias.text().startsWith("\"")
                            && !alias.text().startsWith("`")
                            && isDamengKeywordRequiringQuotes(unquotedAlias)) {
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
            Map<String, String> aliases
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
                        && !identifier.text().startsWith("`")
                        && aliases.containsKey(unquoted.toUpperCase(Locale.ROOT))
                        && afterIdentifier < sql.length()
                        && sql.charAt(afterIdentifier) == '.') {
                    replacements.add(new TextReplacement(
                            index,
                            identifier.endIndex(),
                            aliases.get(unquoted.toUpperCase(Locale.ROOT))
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
                "CROSS",
                "APPLY",
                "CONNECT",
                "START",
                "GROUP",
                "ORDER",
                "HAVING",
                "LIMIT",
                "FETCH",
                "UNION",
                "WITH"
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

    private boolean containsUnresolvedOuterOrCrossUpdateJoin(String sql) {
        int updateIndex = leadingWhitespaceLength(sql);
        int joinIndex = findTopLevelKeyword(sql, "JOIN", updateIndex + "UPDATE".length());
        if (joinIndex < 0) {
            return false;
        }
        int setIndex = findTopLevelKeyword(sql, "SET", joinIndex + "JOIN".length());
        return setIndex > updateIndex
                && containsTopLevelOuterOrCrossJoin(sql.substring(updateIndex, setIndex));
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

    private GenericConversion convertMysqlDeleteOrderLimitOne(String sql) {
        int deleteIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, deleteIndex, "DELETE")) {
            return GenericConversion.unchanged(sql);
        }
        int fromIndex = skipWhitespace(sql, deleteIndex + "DELETE".length());
        if (!startsKeyword(sql, fromIndex, "FROM")) {
            return GenericConversion.unchanged(sql);
        }
        int tableStart = skipWhitespace(sql, fromIndex + "FROM".length());
        IdentifierToken table = readQualifiedIdentifierToken(sql, tableStart);
        if (table == null) {
            return GenericConversion.unchanged(sql);
        }
        int whereIndex = skipWhitespace(sql, table.endIndex());
        if (!startsKeyword(sql, whereIndex, "WHERE")) {
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

        String tableName = table.text();
        String whereClause = sql.substring(whereIndex + "WHERE".length(), orderIndex).trim();
        String orderClause = sql.substring(byIndex + "BY".length(), limitIndex).trim();
        if (whereClause.isBlank() || orderClause.isBlank()) {
            return GenericConversion.unchanged(sql);
        }

        StringBuilder converted = new StringBuilder(sql.length() + whereClause.length() + orderClause.length() + 80);
        converted.append(sql, 0, deleteIndex)
                .append("delete from ")
                .append(tableName)
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

    private GenericConversion convertMysqlUpdateLimit(String sql) {
        int updateIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, updateIndex, "UPDATE")) {
            return GenericConversion.unchanged(sql);
        }
        int setIndex = findTopLevelKeyword(sql, "SET", updateIndex + "UPDATE".length());
        if (setIndex < 0) {
            return GenericConversion.unchanged(sql);
        }
        int limitIndex = findTopLevelKeyword(sql, "LIMIT", setIndex + "SET".length());
        if (limitIndex < 0
                || findTopLevelKeyword(sql, "ORDER", setIndex + "SET".length()) >= 0) {
            return GenericConversion.unchanged(sql);
        }
        int statementEnd = stripTrailingSemicolon(sql);
        String limit = simpleLimitRowCount(sql.substring(limitIndex + "LIMIT".length(), statementEnd));
        if (limit.isBlank()) {
            return GenericConversion.unchanged(sql);
        }

        int tableStart = skipWhitespace(sql, updateIndex + "UPDATE".length());
        IdentifierToken table = readQualifiedIdentifierToken(sql, tableStart);
        if (table == null || skipWhitespace(sql, table.endIndex()) != setIndex) {
            return GenericConversion.unchanged(sql);
        }
        int whereIndex = findTopLevelKeyword(sql, "WHERE", setIndex + "SET".length());
        if (whereIndex >= limitIndex) {
            return GenericConversion.unchanged(sql);
        }
        String setClause = sql.substring(
                setIndex + "SET".length(),
                whereIndex < 0 ? limitIndex : whereIndex
        ).trim();
        String whereClause = whereIndex < 0
                ? ""
                : sql.substring(whereIndex + "WHERE".length(), limitIndex).trim();
        if (setClause.isBlank() || (whereIndex >= 0 && whereClause.isBlank())) {
            return GenericConversion.unchanged(sql);
        }

        String tableName = table.text();
        StringBuilder converted = new StringBuilder(sql.length() + whereClause.length() + 80);
        converted.append(sql, 0, updateIndex)
                .append("update ")
                .append(tableName)
                .append(" set ")
                .append(setClause)
                .append(" where ROWID in (select ROWID from ")
                .append(tableName);
        appendLimitedRowPredicate(converted, whereClause, limit);
        converted.append(")").append(sql.substring(statementEnd));
        return new GenericConversion(converted.toString(), true);
    }

    private GenericConversion convertMysqlDeleteLimit(String sql) {
        int deleteIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, deleteIndex, "DELETE")) {
            return GenericConversion.unchanged(sql);
        }
        int fromIndex = skipWhitespace(sql, deleteIndex + "DELETE".length());
        if (!startsKeyword(sql, fromIndex, "FROM")) {
            return GenericConversion.unchanged(sql);
        }
        int tableStart = skipWhitespace(sql, fromIndex + "FROM".length());
        IdentifierToken table = readQualifiedIdentifierToken(sql, tableStart);
        if (table == null) {
            return GenericConversion.unchanged(sql);
        }
        int limitIndex = findTopLevelKeyword(sql, "LIMIT", table.endIndex());
        if (limitIndex < 0
                || findTopLevelKeyword(sql, "ORDER", table.endIndex()) >= 0) {
            return GenericConversion.unchanged(sql);
        }
        int statementEnd = stripTrailingSemicolon(sql);
        String limit = simpleLimitRowCount(sql.substring(limitIndex + "LIMIT".length(), statementEnd));
        if (limit.isBlank()) {
            return GenericConversion.unchanged(sql);
        }
        int whereIndex = findTopLevelKeyword(sql, "WHERE", table.endIndex());
        int tableEnd = skipWhitespace(sql, table.endIndex());
        if ((whereIndex < 0 && tableEnd != limitIndex)
                || whereIndex >= limitIndex
                || (whereIndex >= 0 && tableEnd != whereIndex)) {
            return GenericConversion.unchanged(sql);
        }
        String whereClause = whereIndex < 0
                ? ""
                : sql.substring(whereIndex + "WHERE".length(), limitIndex).trim();
        if (whereIndex >= 0 && whereClause.isBlank()) {
            return GenericConversion.unchanged(sql);
        }

        String tableName = table.text();
        StringBuilder converted = new StringBuilder(sql.length() + whereClause.length() + 80);
        converted.append(sql, 0, deleteIndex)
                .append("delete from ")
                .append(tableName)
                .append(" where ROWID in (select ROWID from ")
                .append(tableName);
        appendLimitedRowPredicate(converted, whereClause, limit);
        converted.append(")").append(sql.substring(statementEnd));
        return new GenericConversion(converted.toString(), true);
    }

    private String simpleLimitRowCount(String limitTail) {
        Matcher matcher = Pattern.compile("(?is)^\\s*(?<count>" + TOKEN + ")\\s*$")
                .matcher(limitTail == null ? "" : limitTail);
        return matcher.matches() ? matcher.group("count").trim() : "";
    }

    private void appendLimitedRowPredicate(StringBuilder converted, String whereClause, String limit) {
        if (whereClause.isBlank()) {
            converted.append(" where ROWNUM <= ").append(limit);
        } else {
            converted.append(" where (")
                    .append(whereClause)
                    .append(") and ROWNUM <= ")
                    .append(limit);
        }
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
        if (!JSON_TABLE_JOIN_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
        }
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
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
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

    private GenericConversion removeSqlServerNoLockHints(String sql) {
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
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else {
                KeywordReplacement hint = readSqlServerNoLockHintRemoval(sql, index);
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

    private KeywordReplacement readSqlServerNoLockHintRemoval(String sql, int index) {
        if (!startsKeyword(sql, index, "WITH")) {
            return null;
        }
        int openParenIndex = skipWhitespace(sql, index + "WITH".length());
        if (openParenIndex >= sql.length() || sql.charAt(openParenIndex) != '(') {
            return null;
        }
        int noLockIndex = skipWhitespace(sql, openParenIndex + 1);
        if (!startsKeyword(sql, noLockIndex, "NOLOCK")) {
            return null;
        }
        int closeParenIndex = skipWhitespace(sql, noLockIndex + "NOLOCK".length());
        if (closeParenIndex >= sql.length() || sql.charAt(closeParenIndex) != ')') {
            return null;
        }
        return new KeywordReplacement(index, skipWhitespace(sql, closeParenIndex + 1));
    }

    private GenericConversion removeSqlServerDboSchemaQualifiers(String sql) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else {
                int qualifierEnd = sqlServerDboQualifierEnd(sql, index);
                if (qualifierEnd < 0) {
                    index++;
                } else {
                    replacements.add(new TextReplacement(index, qualifierEnd, ""));
                    index = qualifierEnd;
                }
            }
        }
        return applyTextReplacements(sql, replacements);
    }

    private int sqlServerDboQualifierEnd(String sql, int index) {
        int afterDbo;
        if (startsKeyword(sql, index, "DBO")) {
            afterDbo = index + "DBO".length();
        } else if (index + "[DBO]".length() <= sql.length()
                && sql.regionMatches(true, index, "[DBO]", 0, "[DBO]".length())) {
            afterDbo = index + "[DBO]".length();
        } else {
            return -1;
        }
        int dotIndex = skipWhitespace(sql, afterDbo);
        if (dotIndex >= sql.length() || sql.charAt(dotIndex) != '.') {
            return -1;
        }
        return skipWhitespace(sql, dotIndex + 1);
    }

    private GenericConversion convertSqlServerStringPlusOperators(String sql) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '+'
                    && (isSqlServerStringOperandBefore(sql, index)
                    || isSqlServerStringOperandAfter(sql, index + 1))) {
                replacements.add(new TextReplacement(index, index + 1, "||"));
                index++;
            } else {
                index++;
            }
        }
        return applyTextReplacements(sql, replacements);
    }

    private boolean isSqlServerStringOperandBefore(String sql, int beforeIndex) {
        int end = skipWhitespaceBackward(sql, beforeIndex);
        if (end <= 0) {
            return false;
        }
        if (sql.charAt(end - 1) == '\'') {
            return true;
        }
        if (sql.charAt(end - 1) != ')') {
            return false;
        }
        int openParenIndex = findMatchingOpenParenBackward(sql, end - 1);
        if (openParenIndex < 0) {
            return false;
        }
        int functionStart = readFunctionNameStartBeforeParen(sql, openParenIndex);
        return isSqlServerStringReturningFunction(
                sql.substring(functionStart, end)
        );
    }

    private boolean isSqlServerStringOperandAfter(String sql, int afterIndex) {
        int start = skipWhitespace(sql, afterIndex);
        if (start >= sql.length()) {
            return false;
        }
        if (sql.charAt(start) == '\'') {
            return true;
        }
        for (String functionName : List.of("CONVERT", "CAST", "CONCAT")) {
            if (!startsFunction(sql, start, functionName)) {
                continue;
            }
            FunctionCall functionCall = readFunctionCall(sql, start, functionName);
            return functionCall != null
                    && isSqlServerStringReturningFunction(
                    sql.substring(start, functionCall.endIndex())
            );
        }
        return false;
    }

    private boolean isSqlServerStringReturningFunction(String expression) {
        String trimmed = expression.trim();
        if (startsFunction(trimmed, 0, "CONCAT")) {
            FunctionCall functionCall = readFunctionCall(trimmed, 0, "CONCAT");
            return functionCall != null && functionCall.endIndex() == trimmed.length();
        }
        if (startsFunction(trimmed, 0, "CAST")) {
            FunctionCall functionCall = readFunctionCall(trimmed, 0, "CAST");
            return functionCall != null
                    && functionCall.endIndex() == trimmed.length()
                    && Pattern.compile(
                    "(?is)\\s+AS\\s+(?:N?CHAR|N?VARCHAR|VARCHAR2|TEXT|CLOB)\\b"
            ).matcher(functionCall.body()).find();
        }
        if (startsFunction(trimmed, 0, "CONVERT")) {
            FunctionCall functionCall = readFunctionCall(trimmed, 0, "CONVERT");
            if (functionCall == null || functionCall.endIndex() != trimmed.length()) {
                return false;
            }
            List<TopLevelArgument> arguments = splitTopLevelArguments(functionCall.body());
            return arguments.size() >= 2
                    && arguments.get(0).text().trim().matches(
                    "(?is)(?:N?CHAR|N?VARCHAR|VARCHAR2|TEXT|CLOB)(?:\\s*\\([^)]*\\))?"
            );
        }
        return false;
    }

    private GenericConversion convertSqlServerCharIndexFunctions(String sql) {
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
            } else if (startsFunction(sql, index, "CHARINDEX")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "CHARINDEX");
                String replacement = functionCall == null ? null : rewriteSqlServerCharIndex(functionCall);
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

    private String rewriteSqlServerCharIndex(FunctionCall functionCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(functionCall.body());
        if (arguments.size() != 2 && arguments.size() != 3) {
            return null;
        }
        String needle = arguments.get(0).text().trim();
        String haystack = arguments.get(1).text().trim();
        if (needle.isBlank() || haystack.isBlank()) {
            return null;
        }
        if (arguments.size() == 2) {
            return "INSTR(" + haystack + ", " + needle + ")";
        }
        String start = arguments.get(2).text().trim();
        return start.isBlank() ? null : "INSTR(" + haystack + ", " + needle + ", " + start + ")";
    }

    private GenericConversion convertSqlServerTopClauses(String sql) {
        List<TextReplacement> replacements = new ArrayList<>();
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
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
            } else if (startsKeyword(sql, index, "SELECT")) {
                SqlServerTopClause topClause = readSqlServerTopClause(sql, index, depth);
                if (topClause != null) {
                    replacements.add(new TextReplacement(
                            topClause.topStartIndex(),
                            topClause.topEndIndex(),
                            ""
                    ));
                    replacements.add(new TextReplacement(
                            topClause.scopeEndIndex(),
                            topClause.scopeEndIndex(),
                            " FETCH FIRST " + topClause.rowCount() + " ROWS ONLY"
                    ));
                }
                index += "SELECT".length();
            } else {
                index++;
            }
        }
        return applyTextReplacements(sql, replacements);
    }

    private SqlServerTopClause readSqlServerTopClause(String sql, int selectIndex, int selectDepth) {
        int cursor = skipWhitespace(sql, selectIndex + "SELECT".length());
        for (String modifier : List.of("ALL", "DISTINCT")) {
            if (startsKeyword(sql, cursor, modifier)) {
                cursor = skipWhitespace(sql, cursor + modifier.length());
                break;
            }
        }
        if (!startsKeyword(sql, cursor, "TOP")) {
            return null;
        }
        int topStartIndex = cursor;
        cursor = skipWhitespace(sql, cursor + "TOP".length());
        String rowCount;
        if (cursor < sql.length() && sql.charAt(cursor) == '(') {
            int closeParenIndex = findMatchingParen(sql, cursor);
            if (closeParenIndex < 0) {
                return null;
            }
            rowCount = sql.substring(cursor + 1, closeParenIndex).trim();
            cursor = closeParenIndex + 1;
        } else {
            int rowCountEnd = cursor;
            while (rowCountEnd < sql.length()
                    && !Character.isWhitespace(sql.charAt(rowCountEnd))
                    && sql.charAt(rowCountEnd) != ',') {
                rowCountEnd++;
            }
            rowCount = sql.substring(cursor, rowCountEnd).trim();
            cursor = rowCountEnd;
        }
        if (!rowCount.matches("(?is)(?:\\d+|#\\{[^}]+}|\\$\\{[^}]+})")) {
            return null;
        }
        int afterTopIndex = skipWhitespace(sql, cursor);
        if (startsKeyword(sql, afterTopIndex, "PERCENT")
                || startsKeyword(sql, afterTopIndex, "WITH")) {
            return null;
        }
        int scopeEndIndex = findSqlServerSelectScopeEnd(sql, afterTopIndex, selectDepth);
        if (scopeEndIndex < 0) {
            return null;
        }
        return new SqlServerTopClause(
                topStartIndex,
                afterTopIndex,
                skipWhitespaceBackward(sql, scopeEndIndex),
                rowCount
        );
    }

    private int findSqlServerSelectScopeEnd(String sql, int startIndex, int selectDepth) {
        int depth = selectDepth;
        int index = startIndex;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
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
                if (depth == selectDepth) {
                    return index;
                }
                depth--;
                index++;
            } else if (depth == selectDepth
                    && (current == ';'
                    || startsKeyword(sql, index, "UNION")
                    || startsKeyword(sql, index, "EXCEPT")
                    || startsKeyword(sql, index, "INTERSECT"))) {
                return index;
            } else {
                index++;
            }
        }
        return sql.length();
    }

    private KeywordReplacement readMysqlIndexHintRemoval(String sql, int index) {
        String hintKeyword = mysqlIndexHintKeyword(sql, index);
        if (hintKeyword == null) {
            return null;
        }
        int cursor = skipWhitespace(sql, index + hintKeyword.length());
        String indexKeyword = null;
        for (String candidate : List.of("INDEX", "KEY")) {
            if (startsKeyword(sql, cursor, candidate)) {
                indexKeyword = candidate;
                break;
            }
        }
        if (indexKeyword == null) {
            return null;
        }
        cursor = skipWhitespace(sql, cursor + indexKeyword.length());
        if (startsKeyword(sql, cursor, "FOR")) {
            cursor = skipWhitespace(sql, cursor + "FOR".length());
            if (startsKeyword(sql, cursor, "JOIN")) {
                cursor = skipWhitespace(sql, cursor + "JOIN".length());
            } else if (startsKeyword(sql, cursor, "ORDER")) {
                cursor = skipWhitespace(sql, cursor + "ORDER".length());
                if (!startsKeyword(sql, cursor, "BY")) {
                    return null;
                }
                cursor = skipWhitespace(sql, cursor + "BY".length());
            } else if (startsKeyword(sql, cursor, "GROUP")) {
                cursor = skipWhitespace(sql, cursor + "GROUP".length());
                if (!startsKeyword(sql, cursor, "BY")) {
                    return null;
                }
                cursor = skipWhitespace(sql, cursor + "BY".length());
            } else {
                return null;
            }
        }
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
        if ("OUTER".equals(upper)) {
            WordToken outerType = previousWord(sql, word.startIndex());
            if (outerType != null
                    && isOnlyWhitespace(sql, outerType.endIndex(), word.startIndex())
                    && Set.of("LEFT", "RIGHT", "FULL").contains(outerType.text().toUpperCase(Locale.ROOT))) {
                return outerType.startIndex();
            }
        }
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
        InsertValues insert = readInsertIgnoreSingleSource(sql);
        if (insert == null || normalizedKeyColumns(keyColumns).isEmpty()) {
            return GenericConversion.unchanged(sql);
        }
        String converted = mergeSql(insert, List.of(), keyColumns);
        return converted == null ? GenericConversion.unchanged(sql) : new GenericConversion(converted, true);
    }

    private GenericConversion convertInsertIgnoreWithConflictKeyGroupsInternal(
            String sql,
            List<List<String>> conflictKeyGroups
    ) {
        InsertValues insert = readInsertIgnoreSingleSource(sql);
        if (insert == null) {
            return GenericConversion.unchanged(sql);
        }
        String converted = mergeSqlWithConflictKeyGroups(insert, List.of(), conflictKeyGroups);
        return converted == null ? GenericConversion.unchanged(sql) : new GenericConversion(converted, true);
    }

    private InsertValues readInsertIgnoreSingleSource(String sql) {
        InsertValues valuesInsert = readInsertValues(sql, true);
        return valuesInsert == null ? readInsertIgnoreSelectWithoutFrom(sql) : valuesInsert;
    }

    private UpsertConversion convertOnDuplicateKeyUpdate(String sql, List<String> keyColumns) {
        if (!containsPatternOutsideIgnoredText(sql, ON_DUPLICATE_KEY_UPDATE_PATTERN)) {
            return UpsertConversion.unchanged(sql);
        }
        List<String> normalizedKeys = normalizedKeyColumns(keyColumns);
        OnDuplicateKeyInsert valuesInsert = readOnDuplicateKeyInsert(sql);
        OnDuplicateKeySelectInsert selectInsert = valuesInsert == null
                ? readOnDuplicateKeySelectInsert(sql)
                : null;
        String tableName = valuesInsert != null
                ? valuesInsert.tableName()
                : selectInsert == null ? readOnDuplicateKeyTargetTable(sql) : selectInsert.tableName();
        if (normalizedKeys.isEmpty()) {
            return UpsertConversion.manual(
                    sql,
                    MISSING_UPSERT_KEY_COLUMNS + ": ON DUPLICATE KEY UPDATE on table `"
                            + displayTableName(tableName)
                            + "` requires configured table-level keyColumns for a safe Dameng MERGE rewrite."
            );
        }
        if (valuesInsert == null && selectInsert == null) {
            return UpsertConversion.manual(
                    sql,
                    UNSUPPORTED_INSERT_SELECT_UPSERT + ": ON DUPLICATE KEY UPDATE on table `"
                            + displayTableName(tableName)
                            + "` has configured keyColumns " + keyColumns
                            + " but its INSERT source cannot be parsed as a supported single-row VALUES "
                            + "or INSERT ... SELECT statement."
            );
        }

        List<InsertColumn> insertColumns = valuesInsert != null
                ? valuesInsert.columns()
                : selectInsert.columns();
        List<String> missingKeys = missingKeyColumns(insertColumns, normalizedKeys, keyColumns);
        if (!missingKeys.isEmpty()) {
            return UpsertConversion.manual(
                    sql,
                    UPSERT_KEY_NOT_IN_INSERT_COLUMNS + ": table `" + displayTableName(tableName)
                            + "` has configured keyColumns " + keyColumns
                            + " but the INSERT column list does not contain " + missingKeys + "."
            );
        }

        String updateClause = valuesInsert != null
                ? valuesInsert.updateClause()
                : selectInsert.updateClause();
        List<UpdateAssignment> updateAssignments = readOnDuplicateKeyUpdateAssignments(
                updateClause,
                tableName
        );
        if (updateAssignments.isEmpty()) {
            return UpsertConversion.manual(
                    sql,
                    UNSAFE_UPSERT_UPDATE_ASSIGNMENT + ": ON DUPLICATE KEY UPDATE on table `"
                            + displayTableName(tableName)
                            + "` contains an assignment that cannot be mapped safely to Dameng MERGE."
            );
        }

        String converted = valuesInsert != null
                ? mergeSql(valuesInsert.toInsertValues(), updateAssignments, keyColumns)
                : mergeSelectCursorSql(selectInsert, updateAssignments, keyColumns);
        if (converted == null) {
            return UpsertConversion.manual(
                    sql,
                    valuesInsert == null
                            ? UNSUPPORTED_INSERT_SELECT_UPSERT + ": INSERT ... SELECT upsert on table `"
                                    + displayTableName(tableName)
                                    + "` with keyColumns " + keyColumns
                                    + " could not be converted without changing source-row semantics."
                            : UNSAFE_UPSERT_UPDATE_ASSIGNMENT + ": VALUES upsert on table `"
                                    + displayTableName(tableName)
                                    + "` could not be mapped safely to Dameng MERGE."
            );
        }
        return UpsertConversion.changed(
                converted,
                valuesInsert == null
                        ? MYSQL_INSERT_SELECT_ON_DUPLICATE_KEY_UPDATE_TO_DM_CURSOR_MERGE_RULE
                        : MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE
        );
    }

    private List<String> missingKeyColumns(
            List<InsertColumn> insertColumns,
            List<String> normalizedKeys,
            List<String> configuredKeys
    ) {
        List<String> missing = new ArrayList<>();
        for (int index = 0; index < normalizedKeys.size(); index++) {
            String normalizedKey = normalizedKeys.get(index);
            boolean present = insertColumns.stream()
                    .anyMatch(column -> column.name().key().equals(normalizedKey));
            if (!present) {
                missing.add(index < configuredKeys.size() ? configuredKeys.get(index) : normalizedKey);
            }
        }
        return List.copyOf(missing);
    }

    private String displayTableName(String tableName) {
        return tableName == null || tableName.isBlank() ? "<unknown>" : tableName;
    }

    private String mergeSql(InsertValues insert, List<UpdateAssignment> updateAssignments, List<String> keyColumns) {
        List<String> normalizedKeys = normalizedKeyColumns(keyColumns);
        if (normalizedKeys.isEmpty()) {
            return null;
        }
        return mergeSqlWithConflictKeyGroups(insert, updateAssignments, List.of(keyColumns));
    }

    private String mergeSqlWithConflictKeyGroups(
            InsertValues insert,
            List<UpdateAssignment> updateAssignments,
            List<List<String>> conflictKeyGroups
    ) {
        List<List<String>> normalizedGroups = new ArrayList<>();
        for (List<String> keyGroup : conflictKeyGroups == null ? List.<List<String>>of() : conflictKeyGroups) {
            List<String> normalizedGroup = normalizedKeyColumns(keyGroup);
            if (normalizedGroup.isEmpty()) {
                return null;
            }
            normalizedGroups.add(normalizedGroup);
        }
        if (normalizedGroups.isEmpty()) {
            return null;
        }
        List<List<InsertColumn>> matchColumnGroups = new ArrayList<>();
        for (List<String> normalizedGroup : normalizedGroups) {
            List<InsertColumn> matchColumns = new ArrayList<>();
            for (String keyColumn : normalizedGroup) {
                InsertColumn matchColumn = insert.columns().stream()
                        .filter(column -> column.name().key().equals(keyColumn))
                        .findFirst()
                        .orElse(null);
                if (matchColumn == null) {
                    return null;
                }
                matchColumns.add(matchColumn);
            }
            matchColumnGroups.add(matchColumns);
        }
        Set<String> normalizedKeys = normalizedGroups.stream()
                .flatMap(List::stream)
                .collect(java.util.stream.Collectors.toSet());
        List<UpdateAssignment> effectiveAssignments = updateAssignments.stream()
                .filter(assignment -> !assignment.noOp())
                .filter(assignment -> !normalizedKeys.contains(assignment.column().key()))
                .toList();
        if (effectiveAssignments.stream().anyMatch(assignment -> assignment.valuesReference()
                && insert.columns().stream()
                .noneMatch(column -> column.name().key().equals(identifierKey(assignment.sourceExpression()))))) {
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
        for (int groupIndex = 0; groupIndex < matchColumnGroups.size(); groupIndex++) {
            List<InsertColumn> matchColumns = matchColumnGroups.get(groupIndex);
            if (groupIndex > 0) {
                converted.append(" OR ");
            }
            if (matchColumnGroups.size() > 1) {
                converted.append("(");
            }
            for (int columnIndex = 0; columnIndex < matchColumns.size(); columnIndex++) {
                InsertColumn matchColumn = matchColumns.get(columnIndex);
                if (columnIndex > 0) {
                    converted.append(" AND ");
                }
                converted.append(qualifiedIdentifier("t", matchColumn.name()))
                        .append(" = ")
                        .append(qualifiedIdentifier("s", matchColumn.name()));
            }
            if (matchColumnGroups.size() > 1) {
                converted.append(")");
            }
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
                        .append(assignment.valuesReference()
                                ? "s." + dmIdentifier(assignment.sourceExpression())
                                : assignment.sourceExpression());
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

    private String mergeSelectCursorSql(
            OnDuplicateKeySelectInsert insert,
            List<UpdateAssignment> updateAssignments,
            List<String> keyColumns
    ) {
        List<String> normalizedKeys = normalizedKeyColumns(keyColumns);
        if (normalizedKeys.isEmpty() || insert.columns().isEmpty()) {
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
                .filter(assignment -> !assignment.noOp())
                .filter(assignment -> !normalizedKeys.contains(assignment.column().key()))
                .toList();
        if (effectiveAssignments.stream().anyMatch(assignment -> assignment.valuesReference()
                && insert.columns().stream()
                .noneMatch(column -> column.name().key().equals(identifierKey(assignment.sourceExpression()))))) {
            return null;
        }

        StringBuilder converted = new StringBuilder(insert.statementEnd() + 384);
        converted.append(insert.prefix())
                .append("DECLARE\n")
                .append("BEGIN\n")
                .append("    FOR dm_source IN (\n")
                .append("        SELECT ")
                .append(insert.selectModifier());
        for (int index = 0; index < insert.columns().size(); index++) {
            InsertColumn column = insert.columns().get(index);
            if (index > 0) {
                converted.append(", ");
            }
            converted.append(column.value())
                    .append(" AS ")
                    .append(dmIdentifier(column.name().text()));
        }
        if (!insert.sourceTail().isBlank()) {
            converted.append("\n").append(insert.sourceTail().strip());
        }
        converted.append("\n")
                .append("    ) LOOP\n")
                .append("        MERGE INTO ")
                .append(insert.tableName())
                .append(" t\n")
                .append("        USING (SELECT ");
        for (int index = 0; index < insert.columns().size(); index++) {
            InsertColumn column = insert.columns().get(index);
            if (index > 0) {
                converted.append(", ");
            }
            converted.append(qualifiedIdentifier("dm_source", column.name()))
                    .append(" AS ")
                    .append(dmIdentifier(column.name().text()));
        }
        converted.append(" FROM dual) s\n")
                .append("        ON (");
        for (int index = 0; index < matchColumns.size(); index++) {
            if (index > 0) {
                converted.append(" AND ");
            }
            converted.append(qualifiedIdentifier("t", matchColumns.get(index).name()))
                    .append(" = ")
                    .append(qualifiedIdentifier("s", matchColumns.get(index).name()));
        }
        converted.append(")\n");
        if (!effectiveAssignments.isEmpty()) {
            converted.append("        WHEN MATCHED THEN UPDATE SET ");
            for (int index = 0; index < effectiveAssignments.size(); index++) {
                UpdateAssignment assignment = effectiveAssignments.get(index);
                if (index > 0) {
                    converted.append(", ");
                }
                converted.append(qualifiedIdentifier("t", assignment.column()))
                        .append(" = ")
                        .append(assignment.valuesReference()
                                ? "s." + dmIdentifier(assignment.sourceExpression())
                                : assignment.sourceExpression());
            }
            converted.append("\n");
        }
        converted.append("        WHEN NOT MATCHED THEN INSERT (");
        for (int index = 0; index < insert.columns().size(); index++) {
            if (index > 0) {
                converted.append(", ");
            }
            converted.append(dmIdentifier(insert.columns().get(index).name().text()));
        }
        converted.append(") VALUES (");
        for (int index = 0; index < insert.columns().size(); index++) {
            if (index > 0) {
                converted.append(", ");
            }
            converted.append(qualifiedIdentifier("s", insert.columns().get(index).name()));
        }
        converted.append(");\n")
                .append("    END LOOP;\n")
                .append("END")
                .append(insert.suffix());
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

    private OnDuplicateKeySelectInsert readOnDuplicateKeySelectInsert(String sql) {
        int insertIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, insertIndex, "INSERT")) {
            return null;
        }
        int index = skipWhitespace(sql, insertIndex + "INSERT".length());
        if (startsKeyword(sql, index, "IGNORE")) {
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
        if (tableName.isBlank()
                || containsMyBatisPlaceholder(tableName)
                || containsWhitespaceOutsideQuotedText(tableName)) {
            return null;
        }
        int columnCloseIndex = findMatchingParen(sql, columnOpenIndex);
        if (columnCloseIndex < 0) {
            return null;
        }
        List<IdentifierName> columnNames = readInsertColumns(
                sql.substring(columnOpenIndex + 1, columnCloseIndex)
        );
        if (columnNames.isEmpty()) {
            return null;
        }

        int selectIndex = skipWhitespace(sql, columnCloseIndex + 1);
        if (!startsKeyword(sql, selectIndex, "SELECT")) {
            return null;
        }
        int onDuplicateIndex = findTopLevelOnDuplicateKeyUpdate(
                sql,
                selectIndex + "SELECT".length()
        );
        if (onDuplicateIndex < 0) {
            return null;
        }
        int statementEnd = stripTrailingSemicolon(sql);
        int updateClauseStart = skipOnDuplicateKeyUpdate(sql, onDuplicateIndex);
        if (updateClauseStart < 0 || updateClauseStart >= statementEnd) {
            return null;
        }

        int projectionStart = skipWhitespace(sql, selectIndex + "SELECT".length());
        String selectModifier = "";
        if (startsKeyword(sql, projectionStart, "DISTINCT")) {
            selectModifier = "DISTINCT ";
            projectionStart = skipWhitespace(sql, projectionStart + "DISTINCT".length());
        }
        int fromIndex = findTopLevelKeyword(sql, "FROM", projectionStart);
        if (fromIndex < 0 || fromIndex >= onDuplicateIndex) {
            return null;
        }
        int unionIndex = findTopLevelKeyword(sql, "UNION", projectionStart);
        if (unionIndex >= 0 && unionIndex < onDuplicateIndex) {
            return null;
        }
        String sourceTail = sql.substring(fromIndex, onDuplicateIndex).stripTrailing();
        int forIndex = findTopLevelKeyword(sourceTail, "FOR", 0);
        if (forIndex >= 0
                && startsKeyword(sourceTail, skipWhitespace(sourceTail, forIndex + "FOR".length()), "UPDATE")) {
            return null;
        }

        List<TopLevelArgument> projections = splitTopLevelArguments(
                sql.substring(projectionStart, fromIndex)
        );
        if (projections.size() != columnNames.size()) {
            return null;
        }
        List<InsertColumn> columns = new ArrayList<>();
        for (int projectionIndex = 0; projectionIndex < columnNames.size(); projectionIndex++) {
            String expression = selectProjectionExpression(projections.get(projectionIndex).text());
            if (expression == null || expression.isBlank()) {
                return null;
            }
            columns.add(new InsertColumn(columnNames.get(projectionIndex), expression));
        }
        return new OnDuplicateKeySelectInsert(
                insertIndex,
                statementEnd,
                sql.substring(0, insertIndex),
                sql.substring(statementEnd),
                tableName,
                columns,
                selectModifier,
                sourceTail,
                sql.substring(updateClauseStart, statementEnd)
        );
    }

    private int findTopLevelOnDuplicateKeyUpdate(String sql, int start) {
        int depth = 0;
        int index = Math.max(0, start);
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : sql.length();
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
            } else if (depth == 0 && startsKeyword(sql, index, "ON")) {
                int updateStart = skipOnDuplicateKeyUpdate(sql, index);
                if (updateStart >= 0) {
                    return index;
                }
                index += "ON".length();
            } else {
                index++;
            }
        }
        return -1;
    }

    private int skipOnDuplicateKeyUpdate(String sql, int onIndex) {
        if (!startsKeyword(sql, onIndex, "ON")) {
            return -1;
        }
        int index = skipWhitespace(sql, onIndex + "ON".length());
        if (!startsKeyword(sql, index, "DUPLICATE")) {
            return -1;
        }
        index = skipWhitespace(sql, index + "DUPLICATE".length());
        if (!startsKeyword(sql, index, "KEY")) {
            return -1;
        }
        index = skipWhitespace(sql, index + "KEY".length());
        if (!startsKeyword(sql, index, "UPDATE")) {
            return -1;
        }
        return skipWhitespace(sql, index + "UPDATE".length());
    }

    private String selectProjectionExpression(String projection) {
        String trimmed = projection == null ? "" : projection.trim();
        if (trimmed.isBlank()
                || "*".equals(trimmed)
                || Pattern.compile("(?is)^.+\\.\\s*\\*$").matcher(trimmed).matches()) {
            return null;
        }
        int aliasIndex = -1;
        int searchIndex = 0;
        while (searchIndex < trimmed.length()) {
            int next = findTopLevelKeyword(trimmed, "AS", searchIndex);
            if (next < 0) {
                break;
            }
            aliasIndex = next;
            searchIndex = next + "AS".length();
        }
        if (aliasIndex >= 0) {
            IdentifierName alias = readIdentifierName(
                    trimmed.substring(aliasIndex + "AS".length()),
                    false
            );
            if (alias == null) {
                return null;
            }
            String expression = trimmed.substring(0, aliasIndex).stripTrailing();
            return expression.isBlank() ? null : expression;
        }
        Matcher implicitAlias = Pattern.compile(
                "(?is)^(?<expression>.+\\S)\\s+(?<alias>`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)$"
        ).matcher(trimmed);
        if (implicitAlias.matches()
                && !Set.of("END", "NULL", "TRUE", "FALSE", "CURRENT_TIMESTAMP")
                .contains(implicitAlias.group("alias").toUpperCase(Locale.ROOT))
                && !implicitAlias.group("expression").matches("(?is).*[+\\-*/%<>=|]\\s*$")) {
            return implicitAlias.group("expression").stripTrailing();
        }
        return trimmed;
    }

    private String readOnDuplicateKeyTargetTable(String sql) {
        int insertIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, insertIndex, "INSERT")) {
            return "<unknown>";
        }
        int index = skipWhitespace(sql, insertIndex + "INSERT".length());
        if (startsKeyword(sql, index, "IGNORE")) {
            index = skipWhitespace(sql, index + "IGNORE".length());
        }
        if (!startsKeyword(sql, index, "INTO")) {
            return "<unknown>";
        }
        index = skipWhitespace(sql, index + "INTO".length());
        int columnOpenIndex = findTopLevelChar(sql, '(', index);
        return columnOpenIndex < 0 ? "<unknown>" : sql.substring(index, columnOpenIndex).trim();
    }

    private InsertValues readInsertIgnoreSelectWithoutFrom(String sql) {
        int insertIndex = leadingWhitespaceLength(sql);
        if (!startsKeyword(sql, insertIndex, "INSERT")) {
            return null;
        }
        int index = skipWhitespace(sql, insertIndex + "INSERT".length());
        if (!startsKeyword(sql, index, "IGNORE")) {
            return null;
        }
        index = skipWhitespace(sql, index + "IGNORE".length());
        if (!startsKeyword(sql, index, "INTO")) {
            return null;
        }
        index = skipWhitespace(sql, index + "INTO".length());
        int columnOpenIndex = findTopLevelChar(sql, '(', index);
        if (columnOpenIndex < 0) {
            return null;
        }
        String tableName = sql.substring(index, columnOpenIndex).trim();
        if (tableName.isBlank()
                || containsMyBatisPlaceholder(tableName)
                || containsWhitespaceOutsideQuotedText(tableName)) {
            return null;
        }
        int columnCloseIndex = findMatchingParen(sql, columnOpenIndex);
        if (columnCloseIndex < 0) {
            return null;
        }
        List<IdentifierName> columnNames =
                readInsertColumns(sql.substring(columnOpenIndex + 1, columnCloseIndex));
        if (columnNames.isEmpty()) {
            return null;
        }
        int selectIndex = skipWhitespace(sql, columnCloseIndex + 1);
        if (!startsKeyword(sql, selectIndex, "SELECT")) {
            return null;
        }
        int statementEnd = stripTrailingSemicolon(sql);
        int projectionStart = skipWhitespace(sql, selectIndex + "SELECT".length());
        if (projectionStart >= statementEnd
                || findTopLevelKeyword(sql.substring(0, statementEnd), "FROM", projectionStart) >= 0
                || findTopLevelKeyword(sql.substring(0, statementEnd), "UNION", projectionStart) >= 0) {
            return null;
        }
        List<TopLevelArgument> projections =
                splitTopLevelArguments(sql.substring(projectionStart, statementEnd));
        if (projections.size() != columnNames.size()) {
            return null;
        }
        List<InsertColumn> columns = new ArrayList<>();
        for (int projectionIndex = 0; projectionIndex < columnNames.size(); projectionIndex++) {
            String value = projections.get(projectionIndex).text().trim();
            if (value.isBlank()) {
                return null;
            }
            columns.add(new InsertColumn(columnNames.get(projectionIndex), value));
        }
        return new InsertValues(
                insertIndex,
                statementEnd,
                statementEnd,
                sql.substring(0, insertIndex),
                sql.substring(statementEnd),
                tableName,
                columns
        );
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

    private List<UpdateAssignment> readOnDuplicateKeyUpdateAssignments(
            String updateClause,
            String tableName
    ) {
        List<UpdateAssignment> assignments = new ArrayList<>();
        for (TopLevelArgument assignment : splitTopLevelArguments(updateClause)) {
            UpdateAssignment parsed = readOnDuplicateKeyUpdateAssignment(
                    assignment.text(),
                    tableName
            );
            if (parsed == null
                    || assignments.stream().anyMatch(existing -> existing.column().key().equals(parsed.column().key()))) {
                return List.of();
            }
            assignments.add(parsed);
        }
        return assignments;
    }

    private UpdateAssignment readOnDuplicateKeyUpdateAssignment(
            String assignment,
            String tableName
    ) {
        int equalsIndex = findTopLevelChar(assignment, '=', 0);
        if (equalsIndex < 0) {
            return null;
        }
        IdentifierName targetColumn = readIdentifierName(assignment.substring(0, equalsIndex), true);
        if (targetColumn == null) {
            return null;
        }

        String source = assignment.substring(equalsIndex + 1).trim();
        FunctionCall valuesCall = readOnlyFunctionCall(source, "VALUES");
        if (valuesCall != null) {
            List<TopLevelArgument> valuesArguments = splitTopLevelArguments(valuesCall.body());
            if (valuesArguments.size() != 1) {
                return null;
            }
            IdentifierName sourceColumn = readIdentifierName(valuesArguments.get(0).text(), false);
            if (sourceColumn == null || !sourceColumn.key().equals(targetColumn.key())) {
                return null;
            }
            return new UpdateAssignment(targetColumn, sourceColumn.text(), true, false);
        }

        if (isNoOpUpsertAssignment(source, targetColumn, tableName)) {
            return new UpdateAssignment(targetColumn, targetColumn.text(), false, true);
        }

        if (source.matches(
                "(?is)NULL|[-+]?\\d+(?:\\.\\d+)?|N?'(?:''|[^'])*'|"
                        + "NOW\\s*\\(\\s*\\)|CURRENT_TIMESTAMP(?:\\s*\\(\\s*\\))?"
        )) {
            return new UpdateAssignment(targetColumn, source, false, false);
        }

        Matcher selfArithmetic = Pattern.compile(
                "(?is)^(?<column>`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*"
                        + "(?<operator>[+-])\\s*(?<amount>\\d+(?:\\.\\d+)?)$"
        ).matcher(source);
        if (!selfArithmetic.matches()) {
            return null;
        }
        IdentifierName sourceColumn = readIdentifierName(selfArithmetic.group("column"), false);
        if (sourceColumn == null || !sourceColumn.key().equals(targetColumn.key())) {
            return null;
        }
        return new UpdateAssignment(
                targetColumn,
                "t." + dmIdentifier(sourceColumn.text())
                        + " " + selfArithmetic.group("operator")
                        + " " + selfArithmetic.group("amount"),
                false,
                false
        );
    }

    private boolean isNoOpUpsertAssignment(
            String source,
            IdentifierName targetColumn,
            String tableName
    ) {
        List<String> parts = identifierReferenceKeys(source);
        if (parts.isEmpty() || !parts.get(parts.size() - 1).equals(targetColumn.key())) {
            return false;
        }
        if (parts.size() == 1) {
            return true;
        }
        String tableKey = identifierKey(tableLeaf(tableName));
        return parts.size() == 2 && parts.get(0).equals(tableKey);
    }

    private List<String> identifierReferenceKeys(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        List<String> parts = new ArrayList<>();
        int index = 0;
        while (index < trimmed.length()) {
            IdentifierToken token = readIdentifierToken(trimmed, index);
            if (token == null) {
                return List.of();
            }
            parts.add(identifierKey(token.text()));
            index = skipWhitespace(trimmed, token.endIndex());
            if (index == trimmed.length()) {
                break;
            }
            if (trimmed.charAt(index) != '.') {
                return List.of();
            }
            index = skipWhitespace(trimmed, index + 1);
        }
        return List.copyOf(parts);
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
        return unquoteIdentifier(identifier).toLowerCase(Locale.ROOT);
    }

    private boolean containsWhitespaceOutsideQuotedText(String value) {
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '"') {
                index = skipDoubleQuotedText(value, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(value, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
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
        if (sql.indexOf('.') < 0
                && !SPECIAL_DAMENG_IDENTIFIER_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
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
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                if (!identifier.closed()) {
                    converted.append(sql, index, sql.length());
                    index = sql.length();
                } else {
                    converted.append(sql, index, identifier.nextIndex());
                    index = identifier.nextIndex();
                }
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
        if ("AUDIT".equals(upper)) {
            return quoteDamengIdentifier(identifier);
        }
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
                    String expression = functionCall.body().trim();
                    if (expression.isBlank()) {
                        converted.append(current);
                        index++;
                        continue;
                    }
                    converted.append("CASE WHEN ")
                            .append(expression)
                            .append(" IS NOT NULL THEN 1 ELSE 0 END");
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

    private GenericConversion convertBooleanNullProjection(String sql) {
        if (!MYSQL_BOOLEAN_NULL_PROJECTION_CANDIDATE_PATTERN.matcher(sql).find()) {
            return new GenericConversion(sql, false);
        }
        List<TextReplacement> replacements = new ArrayList<>();
        Map<Integer, Boolean> selectListByDepth = new LinkedHashMap<>();
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '(') {
                if (!Boolean.TRUE.equals(selectListByDepth.get(depth))) {
                    depth++;
                    index++;
                    continue;
                }
                int closeParenIndex = findMatchingParen(sql, index);
                if (closeParenIndex < 0) {
                    depth++;
                    index++;
                    continue;
                }
                Matcher matcher = MYSQL_BOOLEAN_NULL_PROJECTION_PATTERN.matcher(
                        sql.substring(index + 1, closeParenIndex)
                );
                int asIndex = skipWhitespace(sql, closeParenIndex + 1);
                if (matcher.matches() && startsKeyword(sql, asIndex, "AS")) {
                    int aliasIndex = skipWhitespace(sql, asIndex + "AS".length());
                    IdentifierToken alias = readIdentifierToken(sql, aliasIndex);
                    if (alias != null
                            && !isSqlClauseKeyword(alias.text())
                            && isSelectItemTerminator(sql, alias.endIndex())) {
                        String operator = matcher.group("not") == null ? "IS NULL" : "IS NOT NULL";
                        replacements.add(new TextReplacement(
                                index,
                                closeParenIndex + 1,
                                "CASE WHEN " + matcher.group("expression").trim() + " " + operator
                                        + " THEN 1 ELSE 0 END"
                        ));
                    }
                }
                index = closeParenIndex + 1;
            } else if (current == ')') {
                if (depth > 0) {
                    selectListByDepth.remove(depth);
                    depth--;
                }
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
        return applyTextReplacements(sql, replacements);
    }

    private boolean isSelectItemTerminator(String sql, int index) {
        int next = skipWhitespace(sql, index);
        return next >= sql.length()
                || sql.charAt(next) == ','
                || sql.charAt(next) == ')'
                || sql.charAt(next) == ';'
                || startsKeyword(sql, next, "FROM")
                || startsMyBatisXmlTag(sql, next);
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
        if (!COUNT_FUNCTION_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
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
                    appendFunctionReplacement(converted, replacement, sql, functionCall);
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
        if (!GROUP_CONCAT_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
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
            } else if (startsFunction(sql, index, "GROUP_CONCAT")) {
                FunctionCall functionCall = readFunctionCall(sql, index, "GROUP_CONCAT");
                String replacement = functionCall == null ? null : rewriteGroupConcat(functionCall);
                if (replacement == null) {
                    converted.append(current);
                    index++;
                } else {
                    appendFunctionReplacement(converted, replacement, sql, functionCall);
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

    private GenericConversion convertMysqlAncestorUserVariableTraversal(String sql) {
        Matcher matcher = MYSQL_ANCESTOR_USER_VARIABLE_TRAVERSAL_PATTERN.matcher(sql);
        if (!matcher.matches()
                || !sameIdentifier(matcher, "cursorRead", "cursorWrite", "seedCursor")
                || !sameIdentifier(matcher, "levelWrite", "levelRead")
                || !sameIdentifier(matcher, "walkIdAlias", "lookupWalkIdAlias", "walkIdAliasRef")
                || !sameIdentifier(matcher, "driverAlias", "driverAliasRef")
                || !sameIdentifier(matcher, "walkAlias", "walkAliasRef")
                || !sameIdentifier(
                        matcher,
                        "aggregateAlias",
                        "outputAlias",
                        "outputAliasRef",
                        "filterAlias"
                )
                || !sameIdentifier(
                        matcher,
                        "aggregateId",
                        "lookupId",
                        "outputId",
                        "filterId"
                )
                || !sameIdentifier(matcher, "parentColumn", "driverParent")
                || !sameIdentifier(matcher, "lookupTable", "driverTable", "outputTable")
                || !matcher.group("seed").equals(matcher.group("filterSeed"))) {
            return new GenericConversion(sql, false);
        }

        String table = matcher.group("lookupTable");
        String idColumn = matcher.group("lookupId");
        String parentColumn = matcher.group("parentColumn");
        String valueColumn = matcher.group("valueColumn");
        String seed = matcher.group("seed");
        String hierarchyAlias = "dm_hierarchy";
        String resultAlias = matcher.group("resultAlias");
        String converted = matcher.group("leading")
                + "SELECT LISTAGG(" + hierarchyAlias + "." + valueColumn + ", "
                + matcher.group("separator") + ") WITHIN GROUP (ORDER BY "
                + hierarchyAlias + "." + idColumn + ")"
                + (resultAlias == null ? "" : " AS " + resultAlias)
                + "\nFROM (\n"
                + "    SELECT " + idColumn + ", " + valueColumn + "\n"
                + "    FROM " + table + "\n"
                + "    START WITH " + idColumn + " = " + seed + "\n"
                + "    CONNECT BY NOCYCLE PRIOR " + parentColumn + " = " + idColumn + "\n"
                + ") " + hierarchyAlias + "\n"
                + "WHERE " + hierarchyAlias + "." + idColumn + " != " + seed
                + matcher.group("trailing");
        return new GenericConversion(converted, true);
    }

    private boolean sameIdentifier(Matcher matcher, String firstGroup, String... otherGroups) {
        String expected = normalizeQualifiedIdentifier(matcher.group(firstGroup));
        for (String group : otherGroups) {
            if (!expected.equals(normalizeQualifiedIdentifier(matcher.group(group)))) {
                return false;
            }
        }
        return true;
    }

    private String normalizeQualifiedIdentifier(String value) {
        return value == null
                ? ""
                : value.replaceAll("\\s+", "").replace("\"", "").toLowerCase(Locale.ROOT);
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

    private GenericConversion convertSingleArgumentConcat(String sql) {
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
                String replacement = functionCall == null ? null : rewriteSingleArgumentConcat(functionCall);
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

    private String rewriteSingleArgumentConcat(FunctionCall concatCall) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(concatCall.body());
        if (arguments.size() != 1) {
            return null;
        }
        String expression = arguments.get(0).text().trim();
        if (expression.isBlank() || expression.contains("${")) {
            return null;
        }
        GenericConversion nestedConversion = convertSingleArgumentConcat(expression);
        return nestedConversion.changed() ? nestedConversion.convertedSql().trim() : expression;
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

    private GenericConversion convertMysqlHelpTopicSplit(String sql) {
        Matcher joinMatcher = MYSQL_HELP_TOPIC_SPLIT_JOIN_PATTERN.matcher(sql);
        StringBuilder converted = new StringBuilder(sql.length());
        List<HelpTopicSplit> convertedSplits = new ArrayList<>();
        int appendFrom = 0;
        while (joinMatcher.find()) {
            String alias = joinMatcher.group("alias");
            String conditionAlias = joinMatcher.group("conditionAlias");
            String source = normalizedQualifiedIdentifier(joinMatcher.group("source"));
            String replaceSource = normalizedQualifiedIdentifier(joinMatcher.group("replaceSource"));
            Pattern splitExpressionPattern = mysqlHelpTopicSplitExpressionPattern(alias, source);
            if (!alias.equalsIgnoreCase(conditionAlias)
                    || !source.equalsIgnoreCase(replaceSource)
                    || !splitExpressionPattern.matcher(sql).find()) {
                continue;
            }

            converted.append(sql, appendFrom, joinMatcher.start());
            String comparison = "&lt;".equalsIgnoreCase(joinMatcher.group("lessThan"))
                    ? "&lt;="
                    : "<=";
            converted.append("CROSS APPLY (")
                    .append("SELECT LEVEL - 1 AS help_topic_id FROM dual CONNECT BY LEVEL ")
                    .append(comparison)
                    .append(" LENGTH(")
                    .append(source)
                    .append(") - LENGTH(REPLACE(")
                    .append(source)
                    .append(", ',', '')) + 1")
                    .append(") ")
                    .append(alias);
            appendFrom = joinMatcher.end();
            convertedSplits.add(new HelpTopicSplit(alias, source));
        }
        if (convertedSplits.isEmpty()) {
            return new GenericConversion(sql, false);
        }
        converted.append(sql, appendFrom, sql.length());

        String rewritten = converted.toString();
        for (HelpTopicSplit split : convertedSplits) {
            Matcher expressionMatcher = mysqlHelpTopicSplitExpressionPattern(
                    split.alias(),
                    split.source()
            ).matcher(rewritten);
            String replacement = "REGEXP_SUBSTR("
                    + split.source()
                    + ", '[^,]+', 1, "
                    + split.alias()
                    + ".help_topic_id + 1)";
            rewritten = expressionMatcher.replaceAll(Matcher.quoteReplacement(replacement));
        }
        return new GenericConversion(rewritten, !rewritten.equals(sql));
    }

    private Pattern mysqlHelpTopicSplitExpressionPattern(String alias, String source) {
        String sourcePattern = qualifiedIdentifierPattern(source);
        return Pattern.compile(
                "(?is)\\bSUBSTRING_INDEX\\s*\\(\\s*"
                        + "SUBSTRING_INDEX\\s*\\(\\s*"
                        + sourcePattern
                        + "\\s*,\\s*','\\s*,\\s*"
                        + Pattern.quote(alias)
                        + "\\s*\\.\\s*help_topic_id\\s*\\+\\s*1\\s*\\)"
                        + "\\s*,\\s*','\\s*,\\s*-\\s*1\\s*\\)"
        );
    }

    private String normalizedQualifiedIdentifier(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private String qualifiedIdentifierPattern(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length == 1) {
            return Pattern.quote(parts[0]);
        }
        return Pattern.quote(parts[0]) + "\\s*\\.\\s*" + Pattern.quote(parts[1]);
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

    private boolean containsPatternOutsideIgnoredText(String sql, Pattern pattern) {
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else {
                Matcher matcher = pattern.matcher(sql);
                matcher.region(index, sql.length());
                if (matcher.lookingAt()) {
                    return true;
                }
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

    private void appendFunctionReplacement(StringBuilder converted, String replacement, String sql, FunctionCall functionCall) {
        converted.append(replacement);
        if (functionCall.endIndex() < sql.length() && startsImplicitAlias(sql.charAt(functionCall.endIndex()))) {
            converted.append(' ');
        }
    }

    private boolean startsImplicitAlias(char value) {
        return value == '"'
                || value == '`'
                || isIdentifierStart(value);
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
            if (containsFunction(sql, functionName)) {
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
        if (sql.charAt(start) == '`') {
            BacktickIdentifier identifier = readBacktickIdentifier(sql, start);
            return identifier.closed() ? new IdentifierToken(sql.substring(start, identifier.nextIndex()), identifier.nextIndex()) : null;
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
        String candidate = sql.substring(start, end).toUpperCase(Locale.ROOT);
        if (Set.of(
                "AND",
                "AS",
                "BY",
                "ELSE",
                "IN",
                "NOT",
                "ON",
                "OR",
                "RETURN",
                "SELECT",
                "THEN",
                "WHEN",
                "WHERE"
        ).contains(candidate)) {
            return openParenIndex;
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

    private GenericConversion convertMysqlHashLineComments(String sql) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                index = identifier.closed() ? identifier.nextIndex() : index + 1;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (sql.startsWith("--", index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '#') {
                replacements.add(new TextReplacement(index, index + 1, "--"));
                index = skipUntilLineEnd(sql, index);
            } else {
                index++;
            }
        }
        return applyTextReplacements(sql, replacements);
    }

    private GenericConversion convertMysqlBitLiterals(String sql) {
        if (!MYSQL_BIT_LITERAL_CANDIDATE_PATTERN.matcher(sql).find()) {
            return GenericConversion.unchanged(sql);
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
            } else if (current == '`') {
                BacktickIdentifier identifier = readBacktickIdentifier(sql, index);
                int end = identifier.closed() ? identifier.nextIndex() : index + 1;
                converted.append(sql, index, end);
                index = end;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = appendMyBatisPlaceholder(sql, index, converted);
            } else if (startsLineComment(sql, index)) {
                index = appendUntilLineEnd(sql, index, converted);
            } else if (startsBlockComment(sql, index)) {
                index = appendUntilBlockCommentEnd(sql, index, converted);
            } else if ((current == 'b' || current == 'B')
                    && (index == 0 || !isIdentifierPart(sql.charAt(index - 1)))
                    && index + 2 < sql.length()
                    && sql.charAt(index + 1) == '\'') {
                int cursor = index + 2;
                while (cursor < sql.length()
                        && (sql.charAt(cursor) == '0' || sql.charAt(cursor) == '1')) {
                    cursor++;
                }
                if (cursor > index + 2
                        && cursor < sql.length()
                        && sql.charAt(cursor) == '\'') {
                    converted.append(new BigInteger(sql.substring(index + 2, cursor), 2));
                    index = cursor + 1;
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
        String lower = sql.stripLeading().toLowerCase(Locale.ROOT);
        if ((lower.startsWith("update") || lower.startsWith("delete"))
                && findTopLevelKeyword(sql, "LIMIT", 0) >= 0) {
            return LimitConversion.manual("LIMIT on non-SELECT DML requires manual confirmation for Dameng.");
        }
        return LimitConversion.none();
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

    private record HelpTopicSplit(String alias, String source) {
    }

    private record ArithmeticConversion(
            String convertedSql,
            boolean changed,
            String manualReviewReason,
            List<String> appliedRules
    ) {
        private ArithmeticConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }

        private static ArithmeticConversion manual(String sql) {
            return new ArithmeticConversion(sql, false, INTEGER_ARITHMETIC_MANUAL_REVIEW_REASON, List.of());
        }

        private static ArithmeticConversion unchanged(String sql) {
            return new ArithmeticConversion(sql, false, "", List.of());
        }
    }

    private record ArithmeticOperand(int startIndex, int endIndex, String text) {
    }

    private record IdentifierToken(String text, int endIndex) {
    }

    private record RegexpExpression(int startIndex, int endIndex, String replacement) {
    }

    private record JsonTableJoin(int rewriteStartIndex, int joinSourceEndIndex) {
    }

    private record KeywordReplacement(int startIndex, int endIndex) {
    }

    private record SqlServerTopClause(
            int topStartIndex,
            int topEndIndex,
            int scopeEndIndex,
            String rowCount
    ) {
    }

    private record TextReplacement(int startIndex, int endIndex, String replacement) {
    }

    private record NumericTypeRewrite(int endIndex, String replacement) {
    }

    private record CharacterSetClause(String name, int endIndex) {
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

    private record UpdateJoinTable(String tableSql, String aliasKey) {
    }

    private record UpdateJoinChain(List<UpdateJoinTable> tables, List<String> conditions) {
    }

    public record OuterJoinSourceKeyCandidate(String tableName, List<String> joinColumns) {
        public OuterJoinSourceKeyCandidate {
            tableName = tableName == null ? "" : tableName.strip();
            joinColumns = List.copyOf(joinColumns == null ? List.of() : joinColumns);
        }
    }

    private record UpdateSetAssignment(String aliasKey, String columnKey, String text) {
    }

    private record BoundAliasValue(String columnKey, String boundValue, String originalPredicate) {
    }

    private record AliasJoinBinding(String secondaryExpression, String originalPredicate) {
    }

    private record AssignmentParts(String columnKey, boolean simpleLiteralValue) {
    }

    private record StatementSegment(String sql, String separator) {
    }

    private record IdentifierName(String text, String key) {
    }

    private record AggregateAlias(String expression, String alias) {
    }

    private record InsertColumn(IdentifierName name, String value) {
    }

    private record UpdateAssignment(
            IdentifierName column,
            String sourceExpression,
            boolean valuesReference,
            boolean noOp
    ) {
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

        private String tableName() {
            return insertValues.tableName();
        }
    }

    private record OnDuplicateKeySelectInsert(
            int insertIndex,
            int statementEnd,
            String prefix,
            String suffix,
            String tableName,
            List<InsertColumn> columns,
            String selectModifier,
            String sourceTail,
            String updateClause
    ) {
        private OnDuplicateKeySelectInsert {
            columns = List.copyOf(columns == null ? List.of() : columns);
            selectModifier = selectModifier == null ? "" : selectModifier;
            sourceTail = sourceTail == null ? "" : sourceTail;
            updateClause = updateClause == null ? "" : updateClause;
        }
    }

    private record UpsertConversion(
            String convertedSql,
            boolean changed,
            String ruleName,
            String manualReviewReason
    ) {
        private static UpsertConversion unchanged(String sql) {
            return new UpsertConversion(sql, false, "", "");
        }

        private static UpsertConversion changed(String sql, String ruleName) {
            return new UpsertConversion(sql, true, ruleName, "");
        }

        private static UpsertConversion manual(String sql, String reason) {
            return new UpsertConversion(sql, false, "", reason);
        }
    }

    private record BacktickIdentifier(String value, int nextIndex, boolean closed) {
    }

    private record FunctionCall(int startIndex, int openParenIndex, int closeParenIndex, int endIndex, String body) {
    }

    private record DateExpression(int startIndex, int endIndex, String text) {
    }

    private record IntervalExpression(String amount, String unit, int endIndex) {
    }

    private record DateIntervalAddition(DateExpression leftExpression, IntervalExpression intervalExpression) {
    }

    private record NumericIfNullComparison(int ifNullStart, int ifNullEnd) {
    }

    private record TopLevelArgument(String text, int startIndex, int endIndex) {
    }

    private record IdentityPrimaryKeyColumn(String columnName, String definitionWithoutInlinePrimaryKey) {
    }

    private record UserVariableInitialization(String name) {
    }

    private record UserVariableAssignmentPosition(
            String name,
            int startIndex,
            int expressionStartIndex
    ) {
    }

    private record GroupConcatOrderBy(String orderBy, String separator) {
    }

    private record MetadataColumnFilter(String tableName, String tableSchema, String columnName) {
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
