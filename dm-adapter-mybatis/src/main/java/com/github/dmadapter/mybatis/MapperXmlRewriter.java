package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlConversionResult;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import com.github.dmadapter.sql.SqlConverter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MapperXmlRewriter {
    public static final String MYBATIS_BATCH_INSERT_ADD_VALUES_RULE = "MYBATIS_BATCH_INSERT_ADD_VALUES";
    public static final String MYBATIS_FOREACH_TRAILING_COMMA_RULE = "MYBATIS_FOREACH_TRAILING_COMMA";
    public static final String MYBATIS_DYNAMIC_SET_MISSING_COMMA_RULE = "MYBATIS_DYNAMIC_SET_MISSING_COMMA";
    public static final String MYBATIS_DYNAMIC_SET_DUPLICATE_ASSIGNMENT_RULE =
            "MYBATIS_DYNAMIC_SET_DUPLICATE_ASSIGNMENT";
    public static final String MYBATIS_DYNAMIC_SET_TRIM_BLOCKS_MERGED_RULE =
            "MYBATIS_DYNAMIC_SET_TRIM_BLOCKS_MERGED";
    public static final String MYBATIS_DYNAMIC_WHERE_MISSING_AND_RULE =
            "MYBATIS_DYNAMIC_WHERE_MISSING_AND";
    public static final String MYBATIS_DYNAMIC_INSERT_TRIM_MISSING_COMMA_RULE =
            "MYBATIS_DYNAMIC_INSERT_TRIM_MISSING_COMMA";
    public static final String MYBATIS_FOREACH_TUPLE_MISSING_COMMA_RULE =
            "MYBATIS_FOREACH_TUPLE_MISSING_COMMA";
    public static final String MYBATIS_STATIC_WHERE_MISSING_AND_RULE =
            "MYBATIS_STATIC_WHERE_MISSING_AND";
    public static final String MYBATIS_BATCH_INSERT_LIST_ITEM_REFERENCE_RULE =
            "MYBATIS_BATCH_INSERT_LIST_ITEM_REFERENCE";
    public static final String MYBATIS_BATCH_GENERATED_KEY_CONDITIONAL_RULE =
            "MYBATIS_BATCH_GENERATED_KEY_CONDITIONAL";
    public static final String MYBATIS_IDENTITY_INSERT_REPLACE_NULL_RULE =
            "MYBATIS_IDENTITY_INSERT_REPLACE_NULL";
    public static final String MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE =
            "MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE";
    public static final String MYBATIS_DYNAMIC_INSERT_IGNORE_TO_DM_MERGE_RULE =
            "MYBATIS_DYNAMIC_INSERT_IGNORE_TO_DM_MERGE";
    public static final String MYBATIS_INSERT_IGNORE_AS_PLAIN_INSERT_RULE =
            "MYBATIS_INSERT_IGNORE_AS_PLAIN_INSERT";
    public static final String MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE =
            "MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM";
    public static final String MYBATIS_DYNAMIC_UPDATE_JOIN_SET_TARGET_QUALIFIED_RULE =
            "MYBATIS_DYNAMIC_UPDATE_JOIN_SET_TARGET_QUALIFIED";
    public static final String MYBATIS_DYNAMIC_SET_PROPERTY_COLUMN_RULE =
            "MYBATIS_DYNAMIC_SET_PROPERTY_COLUMN";
    public static final String MYBATIS_SQL_LINE_COMMENT_PLACEHOLDER_NEUTRALIZED_RULE =
            "MYBATIS_SQL_LINE_COMMENT_PLACEHOLDER_NEUTRALIZED";
    public static final String MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE =
            "MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION";
    public static final String MYBATIS_DYNAMIC_HAVING_DYNAMIC_AGGREGATE_ALIAS_TO_EXPRESSION_RULE =
            "MYBATIS_DYNAMIC_HAVING_DYNAMIC_AGGREGATE_ALIAS_TO_EXPRESSION";
    public static final String MYBATIS_DYNAMIC_HAVING_SELECT_ALIAS_TO_EXPRESSION_RULE =
            "MYBATIS_DYNAMIC_HAVING_SELECT_ALIAS_TO_EXPRESSION";
    public static final String MYBATIS_DYNAMIC_GROUP_BY_SELECT_ALIAS_TO_EXPRESSION_RULE =
            "MYBATIS_DYNAMIC_GROUP_BY_SELECT_ALIAS_TO_EXPRESSION";
    public static final String MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE =
            "MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE";
    public static final String MYBATIS_DYNAMIC_DAMENG_KEYWORD_ALIAS_REFERENCE_RULE =
            "MYBATIS_DYNAMIC_DAMENG_KEYWORD_ALIAS_REFERENCE";
    public static final String MYBATIS_DYNAMIC_TEMPORARY_TABLE_BIND_SELECT_TO_INSERT_RULE =
            "MYBATIS_DYNAMIC_TEMPORARY_TABLE_BIND_SELECT_TO_INSERT";
    public static final String DAMENG_BLOCK_DUPLICATE_SEMICOLON_REMOVED_RULE =
            "DAMENG_BLOCK_DUPLICATE_SEMICOLON_REMOVED";
    public static final String DAMENG_BLOCK_TRAILING_DYNAMIC_PREDICATE_ATTACHED_RULE =
            "DAMENG_BLOCK_TRAILING_DYNAMIC_PREDICATE_ATTACHED";
    private static final String DYNAMIC_UPDATE_JOIN_WITH_WHERE_REASON =
            "MySQL UPDATE JOIN is followed by MyBatis <where>; automatic text-segment rewrite would create duplicate WHERE.";

    private static final Set<String> SQL_TEXT_TAGS = Set.of("select", "insert", "update", "delete", "sql");
    private static final Set<String> DAMENG_IDENTIFIER_QUOTES = Set.of(
            "INDEX",
            "KEY",
            "STATE",
            "TYPE",
            "USER",
            "VERIFY"
    );
    private static final Pattern INSERT_TRIM_THEN_FOREACH_PATTERN = Pattern.compile(
            "(?is)(\\binsert\\s+into\\b[\\s\\S]*?</trim>)(\\s*)(<foreach\\b)"
    );
    private static final Pattern LEADING_INSERT_IGNORE_PATTERN = Pattern.compile(
            "(?is)^(?<insert>\\s*insert)\\s+ignore(?<into>\\s+into\\b)"
    );
    private static final Pattern INSERT_TRIM_VALUES_FOREACH_PATTERN = Pattern.compile(
            "(?is)(?<prefix>\\binsert\\s+into\\b[\\s\\S]*?<trim\\b[^>]*>[\\s\\S]*?</trim>\\s*values\\s*)"
                    + "(?<foreach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)"
    );
    private static final Pattern INSERT_VALUES_FOREACH_PATTERN = Pattern.compile(
            "(?is)(?<prefix>\\binsert\\s+into\\b[\\s\\S]*?\\bvalues\\s*)"
                    + "(?<foreach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)"
    );
    private static final Pattern FOREACH_BLOCK_PATTERN = Pattern.compile("(?is)<foreach\\b[^>]*>[\\s\\S]*?</foreach>");
    private static final Pattern FOREACH_WITH_BODY_PATTERN = Pattern.compile(
            "(?is)^(?<opening><foreach\\b[^>]*>)(?<body>[\\s\\S]*?)(?<closing></foreach\\s*>)$"
    );
    private static final Pattern ADJACENT_MYBATIS_PLACEHOLDER_LINES_PATTERN = Pattern.compile(
            "([#$]\\{[^}]+})([ \\t]*)(\\r\\n|\\r|\\n)(\\s*)(?=[#$]\\{)"
    );
    private static final Pattern IF_OPENING_TAG_PATTERN = Pattern.compile(
            "(?is)<if\\b[^>]*\\btest\\s*=\\s*([\"'])(.*?)\\1[^>]*>"
    );
    private static final Pattern IF_TEST_ATTRIBUTE_PATTERN = Pattern.compile(
            "(?is)(<if\\b[^>]*\\btest\\s*=\\s*)([\"'])(.*?)(\\2)([^>]*>)"
    );
    private static final Pattern SIMPLE_NULL_TEST_PATTERN = Pattern.compile(
            "(?is)^\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*!=\\s*null\\s*$"
    );
    private static final Pattern MYBATIS_SIMPLE_PARAMETER_PATTERN = Pattern.compile(
            "([#$]\\{\\s*)([A-Za-z_][A-Za-z0-9_$]*)(\\s*(?:,[^}]*)?)\\}"
    );
    private static final Pattern IF_BODY_TRAILING_COMMA_PATTERN = Pattern.compile("(?is),\\s*</if\\s*>");
    private static final Pattern SET_ASSIGNMENT_START_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)"
                    + "(?:\\s*\\.\\s*(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*))?\\s*="
    );
    private static final Pattern BARE_WHERE_LINE_PATTERN = Pattern.compile("(?is)^\\s*where\\s*$");
    private static final Pattern WHERE_CONNECTOR_LINE_PATTERN = Pattern.compile("(?is)^\\s*(?:and|or)\\b");
    private static final Pattern WHERE_TRAILING_CONNECTOR_PATTERN = Pattern.compile("(?is).*\\b(?:and|or)\\s*$");
    private static final Pattern WHERE_TRAILING_OPEN_CONNECTOR_PATTERN = Pattern.compile(
            "(?is).*\\b(?:and|or)\\s*\\(\\s*$"
    );
    private static final Pattern WHERE_CLAUSE_BOUNDARY_LINE_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:group\\s+by|order\\s+by|having\\b|limit\\b|offset\\b|fetch\\b|union\\b|for\\b)"
    );
    private static final Pattern WHERE_PREDICATE_START_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)"
                    + "(?:\\s*\\.\\s*(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*))*\\s*"
                    + "(?:=|<>|!=|>=|<=|>|<|in\\b|like\\b|between\\b|is\\b)"
    );
    private static final Pattern WHERE_FUNCTION_PREDICATE_START_PATTERN = Pattern.compile(
            "(?is)^\\s*[A-Za-z_][A-Za-z0-9_$]*\\s*\\([^)]*\\)\\s*"
                    + "(?:=|<>|!=|>=|<=|>|<|in\\b|like\\b|between\\b|is\\b)"
    );
    private static final Pattern TRAILING_COMMA_BEFORE_PAREN_PATTERN = Pattern.compile(",(\\s*\\))");
    private static final String DM_IDENTIFIER = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\"|`[^`]+`|\\$\\{[^}]+})";
    private static final Pattern GENERATED_KEY_BATCH_INSERT_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*insert\\s+into\\s+"
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")?\\s*)"
                    + "(?<columnsTrim><trim\\b[^>]*>[\\s\\S]*?</trim>)"
                    + "(?<valuesKeyword>\\s*values\\s*)"
                    + "(?<foreach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)"
                    + "(?<trailing>;?\\s*)$"
    );
    private static final Pattern WRAPPING_IF_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)(?<opening><if\\b[^>]*>)(?<body>[\\s\\S]*)(?<closing></if\\s*>)(?<trailing>\\s*)$"
    );
    private static final Pattern DYNAMIC_ON_DUPLICATE_KEY_UPDATE_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)insert\\s+into\\s+"
                    + "(?<table>"
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")?"
                    + ")\\s*\\(\\s*"
                    + "(?<fixedColumn>"
                    + DM_IDENTIFIER
                    + ")\\s*"
                    + "(?<keyForeach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)\\s*"
                    + "\\)\\s*values\\s*\\(\\s*"
                    + "(?<fixedValue>#\\{[^}]+})\\s*"
                    + "(?<valueForeach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)\\s*"
                    + "\\)\\s*on\\s+duplicate\\s+key\\s+update\\s*"
                    + "(?<updateForeach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)"
                    + "(?<trailing>;?\\s*)$"
    );
    private static final Pattern DYNAMIC_UPDATE_JOIN_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)update\\s+"
                    + "(?<target>[\\s\\S]+?)\\s+"
                    + "(?:(?:inner|left|right)\\s+)?join\\s+"
                    + "(?<joinSource>[\\s\\S]+?)\\s+on\\s+"
                    + "(?<joinCondition>[\\s\\S]+?)"
                    + "(?<setBlocks>(?:\\s*<if\\b[^>]*>\\s*set\\b[\\s\\S]*?</if\\s*>)+)"
                    + "\\s*where\\b"
                    + "(?<whereClause>[\\s\\S]*?)"
                    + "(?<trailing>\\s*)$"
    );
    private static final Pattern BATCH_ON_DUPLICATE_KEY_UPDATE_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)insert\\s+into\\s+"
                    + "(?<table>"
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")?"
                    + ")\\s*\\((?<columns>[\\s\\S]*?)\\)\\s*values\\s*"
                    + "(?<foreach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)\\s*"
                    + "on\\s+duplicate\\s+key\\s+update\\s*"
                    + "(?<updates>[\\s\\S]*?)(?<trailing>;?\\s*)$"
    );
    private static final Pattern BATCH_ON_DUPLICATE_KEY_UPDATE_TRIM_COLUMNS_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)insert\\s+into\\s+"
                    + "(?<table>"
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")?"
                    + ")\\s*(?<columnsTrim><trim\\b[^>]*>[\\s\\S]*?</trim>)\\s*values\\s*"
                    + "(?<foreach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)\\s*"
                    + "on\\s+duplicate\\s+key\\s+update\\s*"
                    + "(?<updates>[\\s\\S]*?)(?<trailing>;?\\s*)$"
    );
    private static final Pattern BATCH_INSERT_IGNORE_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)insert\\s+ignore\\s+into\\s+"
                    + "(?<table>"
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")?"
                    + ")\\s*\\((?<columns>[\\s\\S]*?)\\)\\s*values\\s*"
                    + "(?<foreach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)"
                    + "(?<trailing>;?\\s*)$"
    );
    private static final Pattern BATCH_INSERT_IGNORE_TRIM_COLUMNS_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)insert\\s+ignore\\s+into\\s+"
                    + "(?<table>"
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")?"
                    + ")\\s*(?<columnsTrim><trim\\b[^>]*>[\\s\\S]*?</trim>)\\s*values\\s*"
                    + "(?<foreach><foreach\\b[^>]*>[\\s\\S]*?</foreach>)"
                    + "(?<trailing>;?\\s*)$"
    );
    private static final Pattern INSERT_IGNORE_TRIM_COLUMNS_VALUES_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)insert\\s+ignore\\s+into\\s+"
                    + "(?<table>"
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")?"
                    + ")\\s*(?<columnsTrim><trim\\b[^>]*>[\\s\\S]*?</trim>)\\s*"
                    + "(?<valuesTrim><trim\\b[^>]*>[\\s\\S]*?</trim>)"
                    + "(?<trailing>;?\\s*)$"
    );
    private static final Pattern ON_DUPLICATE_TRIM_COLUMNS_VALUES_PATTERN = Pattern.compile(
            "(?is)^(?<leading>\\s*)insert\\s+into\\s+"
                    + "(?<table>"
                    + DM_IDENTIFIER
                    + "(?:\\s*\\.\\s*"
                    + DM_IDENTIFIER
                    + ")?"
                    + ")\\s*(?<columnsTrim><trim\\b[^>]*>[\\s\\S]*?</trim>)\\s*"
                    + "(?<valuesTrim><trim\\b[^>]*>[\\s\\S]*?</trim>)\\s*"
                    + "on\\s+duplicate\\s+key\\s+update\\s*"
                    + "(?<updates>[\\s\\S]*?)(?<trailing>;?\\s*)$"
    );
    private static final Pattern DYNAMIC_TEMPORARY_TABLE_BIND_SELECT_PATTERN = Pattern.compile(
            "(?is)(?<prefix>CREATE\\s+GLOBAL\\s+TEMPORARY\\s+TABLE\\s+"
                    + "(?<table>[^\\s;]+)"
                    + "\\s+ON\\s+COMMIT\\s+PRESERVE\\s+ROWS\\s+AS\\s*)"
                    + "(?<outerOpen><foreach\\b(?=[^>]*\\bcollection\\s*=\\s*\"list\")"
                    + "(?=[^>]*\\bitem\\s*=\\s*\"item\")[^>]*>)\\s*"
                    + "select\\s*"
                    + "(?<inner><foreach\\b(?=[^>]*\\bcollection\\s*=\\s*\"item\")"
                    + "(?=[^>]*\\bitem\\s*=\\s*\"field\")[^>]*>\\s*"
                    + "#\\{\\s*field\\.fieldValue\\s*(?:,[^}]*)?}\\s+AS\\s+"
                    + "\\$\\{\\s*field\\.fieldName\\s*}\\s*</foreach>)\\s*"
                    + "from\\s+dual\\s*</foreach>"
    );
    private static final Pattern FOREACH_TAG_PATTERN = Pattern.compile(
            "(?is)^\\s*(?<opening><foreach\\b[^>]*>)(?<body>[\\s\\S]*?)</foreach\\s*>\\s*$"
    );
    private static final Pattern TRIM_TAG_PATTERN = Pattern.compile(
            "(?is)^\\s*(?<opening><trim\\b[^>]*>)(?<body>[\\s\\S]*?)</trim\\s*>\\s*$"
    );
    private static final Pattern IF_BLOCK_PATTERN = Pattern.compile(
            "(?is)(?<opening><if\\b[^>]*>)(?<body>[\\s\\S]*?)</if\\s*>"
    );
    private static final Pattern SET_ASSIGNMENT_COLUMN_PATTERN = Pattern.compile(
            "(?is)^\\s*((?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)"
                    + "(?:\\s*\\.\\s*(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*))?)\\s*="
    );
    private static final Pattern SIMPLE_MYBATIS_PARAMETER_PATTERN = Pattern.compile(
            "(?is)^#\\{\\s*([A-Za-z_][A-Za-z0-9_$.]*)\\s*(?:,[^}]*)?}$"
    );
    private static final Pattern DYNAMIC_XML_TAG_PATTERN = Pattern.compile(
            "(?is)<\\s*/?\\s*(if|foreach|choose|when|otherwise|trim|where|set)\\b"
    );
    private static final Set<String> AGGREGATE_FUNCTIONS = Set.of(
            "AVG",
            "COUNT",
            "GROUP_CONCAT",
            "LISTAGG",
            "MAX",
            "MIN",
            "SUM"
    );

    public MapperRewriteResult rewrite(Path inputPath, String reportPath, boolean writeChanges, SqlConverter sqlConverter) {
        return rewrite(inputPath, reportPath, writeChanges, sqlConverter, SqlRewriteConfig.empty());
    }

    public MapperRewriteResult rewrite(
            Path inputPath,
            String reportPath,
            boolean writeChanges,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        return rewrite(inputPath, reportPath, writeChanges, sqlConverter, rewriteConfig, Set.of());
    }

    public MapperRewriteResult rewrite(
            Path inputPath,
            String reportPath,
            boolean writeChanges,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig,
            Set<String> statementKeysToRewrite
    ) {
        List<SqlChange> automaticConversions = new ArrayList<>();
        List<SqlChange> manualReviewItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<StatementReplacement> replacements = new ArrayList<>();
        Set<String> restrictedStatementKeys = statementKeysToRewrite == null
                ? Set.of()
                : statementKeysToRewrite;
        boolean restricted = !restrictedStatementKeys.isEmpty();
        Map<String, Integer> statementOccurrences = new LinkedHashMap<>();

        Document document;
        try {
            document = XmlSupport.parse(inputPath);
        } catch (Exception e) {
            manualReviewItems.add(new SqlChange(
                    reportPath,
                    "(file)",
                    "",
                    "",
                    List.of(),
                    true,
                    "Mapper XML could not be parsed safely: " + e.getMessage()
            ));
            return new MapperRewriteResult(automaticConversions, manualReviewItems, warnings);
        }

        String namespace = document.getDocumentElement() == null
                ? ""
                : document.getDocumentElement().getAttribute("namespace");
        Map<String, String> resultMapColumnByProperty = resultMapColumnByProperty(document);
        String xml = null;
        boolean changed = false;
        for (Element statement : statementElements(document)) {
            String tagName = statement.getTagName();
            String statementId = statement.getAttribute("id");
            int occurrenceIndex = statementOccurrenceIndex(statementOccurrences, tagName, statementId);
            String statementKey = statementKey(namespace, statementId);
            String originalSql = statement.getTextContent();
            if (statementId.isBlank()) {
                if (restricted) {
                    continue;
                }
                String reason = missingStatementIdReason(reportPath, tagName);
                manualReviewItems.add(new SqlChange(
                        reportPath,
                        "(missing id: <" + tagName + ">)",
                        originalSql,
                        originalSql,
                        List.of(),
                        true,
                        reason
                ));
                warnings.add(reason);
                continue;
            }
            if (restricted && !restrictedStatementKeys.contains(statementKey)) {
                continue;
            }
            if (hasElementChild(statement)) {
                if (xml == null) {
                    xml = readXml(inputPath);
                }
                StatementBody statementBody = findStatementBody(xml, tagName, statementId, occurrenceIndex);
                DynamicBodyConversion dynamicBodyConversion =
                        convertDynamicXmlTextSegments(
                                tagName,
                                statementKey,
                                statementBody.rawBody(),
                                sqlConverter,
                                rewriteConfig,
                                resultMapColumnByProperty,
                                "true".equalsIgnoreCase(statement.getAttribute("useGeneratedKeys")),
                                statement.getAttribute("keyProperty"),
                                statement.getAttribute("keyColumn")
                        );
                if (!dynamicBodyConversion.manualReviewReasons().isEmpty()) {
                    manualReviewItems.add(new SqlChange(
                            reportPath,
                            statementKey,
                            dynamicBodyConversion.originalBody(),
                            dynamicBodyConversion.convertedBody(),
                            dynamicBodyConversion.appliedRules(),
                            true,
                            dynamicXmlManualReviewReason(dynamicBodyConversion.manualReviewReasons())
                    ));
                }
                if (dynamicBodyConversion.changed()) {
                    replacements.add(StatementReplacement.dynamicBody(
                            tagName,
                            statementId,
                            occurrenceIndex,
                            dynamicBodyConversion.convertedBody()
                    ));
                    automaticConversions.add(new SqlChange(
                            reportPath,
                            statementKey,
                            dynamicBodyConversion.originalBody(),
                            dynamicBodyConversion.convertedBody(),
                            dynamicBodyConversion.appliedRules(),
                            false,
                            ""
                    ));
                }
                continue;
            }

            String tableName = extractInsertTableNameLenient(originalSql);
            String commentSafeSql = neutralizeMyBatisPlaceholdersInSqlLineComments(originalSql);
            List<String> staticRules = new ArrayList<>();
            if (!commentSafeSql.equals(originalSql)) {
                staticRules.add(MYBATIS_SQL_LINE_COMMENT_PLACEHOLDER_NEUTRALIZED_RULE);
            }
            TextRewrite plainInsert = convertConfiguredInsertIgnoreToPlainInsert(
                    commentSafeSql,
                    statementKey,
                    rewriteConfig
            );
            if (plainInsert.changed()) {
                commentSafeSql = plainInsert.text();
                staticRules.add(MYBATIS_INSERT_IGNORE_AS_PLAIN_INSERT_RULE);
            }
            SqlConversionResult conversionResult =
                    sqlConverter.convert(commentSafeSql, rewriteConfig.keyColumnsFor(statementKey, tableName));
            String convertedSql = conversionResult.changed()
                    ? conversionResult.convertedSql()
                    : commentSafeSql;
            addAppliedRules(staticRules, conversionResult.appliedRules());
            if ("insert".equals(tagName)
                    && !"true".equalsIgnoreCase(statement.getAttribute("useGeneratedKeys"))
                    && rewriteConfig.requiresIdentityInsert(tableName)) {
                TextRewrite identityInsert = wrapIdentityInsert(convertedSql, tableName);
                if (identityInsert.changed()) {
                    convertedSql = identityInsert.text();
                    staticRules.add(MYBATIS_IDENTITY_INSERT_REPLACE_NULL_RULE);
                }
            }
            TextRewrite duplicateBlockSemicolon = removeDuplicateDamengBlockSemicolons(convertedSql);
            if (duplicateBlockSemicolon.changed()) {
                convertedSql = duplicateBlockSemicolon.text();
                staticRules.add(DAMENG_BLOCK_DUPLICATE_SEMICOLON_REMOVED_RULE);
            }
            if (!convertedSql.equals(originalSql)) {
                replacements.add(StatementReplacement.staticSql(
                        tagName,
                        statementId,
                        occurrenceIndex,
                        convertedSql
                ));
                automaticConversions.add(new SqlChange(
                        reportPath,
                        statementKey,
                        originalSql,
                        convertedSql,
                        staticRules,
                        false,
                        ""
                ));
            }
            if (conversionResult.manualReviewRequired()) {
                manualReviewItems.add(new SqlChange(
                        reportPath,
                        statementKey,
                        originalSql,
                        convertedSql,
                        staticRules,
                        true,
                        conversionResult.reason()
                ));
            }
        }

        changed = !replacements.isEmpty();
        if (changed && writeChanges) {
            writeReplacements(inputPath, replacements);
        }
        return new MapperRewriteResult(automaticConversions, manualReviewItems, warnings);
    }

    private int statementOccurrenceIndex(Map<String, Integer> occurrences, String tagName, String statementId) {
        String key = tagName + "\u0000" + statementId;
        int occurrenceIndex = occurrences.getOrDefault(key, 0);
        occurrences.put(key, occurrenceIndex + 1);
        return occurrenceIndex;
    }

    private String missingStatementIdReason(String reportPath, String tagName) {
        return "Mapper XML statement <" + tagName + "> is missing required id attribute in "
                + reportPath
                + ". dm-adapter cannot safely locate this statement for text-preserving rewrite; add an id to the MyBatis statement or exclude this XML from mapper-locations if it is not a mapper.";
    }

    private String dynamicXmlManualReviewReason(List<String> manualReviewReasons) {
        return "Statement contains dynamic XML elements with unresolved compatibility risks. "
                + "Additional SQL review: " + String.join("; ", manualReviewReasons);
    }

    private String statementKey(String namespace, String statementId) {
        if (statementId == null || statementId.isBlank()) {
            return statementId;
        }
        return namespace == null || namespace.isBlank() ? statementId : namespace + "." + statementId;
    }

    private String extractInsertTableName(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile(
                "(?is)\\binsert\\s+(?:ignore\\s+)?(?:into\\s+)?("
                        + DM_IDENTIFIER
                        + "(?:\\s*\\.\\s*"
                        + DM_IDENTIFIER
                        + ")?)\\s*\\("
        ).matcher(sql);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String extractInsertTableNameLenient(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?is)\\binsert\\b").matcher(sql);
        if (!matcher.find()) {
            return "";
        }
        int index = skipWhitespace(sql, matcher.end());
        if (isKeywordAt(sql, index, "IGNORE")) {
            index = skipWhitespace(sql, index + "IGNORE".length());
        }
        if (isKeywordAt(sql, index, "INTO")) {
            index = skipWhitespace(sql, index + "INTO".length());
        }
        int tableEnd = readRelationEnd(sql, index);
        if (tableEnd <= index) {
            return "";
        }
        return sql.substring(index, tableEnd).trim();
    }

    private String readXml(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read mapper XML: " + path, e);
        }
    }

    private List<Element> statementElements(Document document) {
        List<Element> elements = new ArrayList<>();
        Element root = document.getDocumentElement();
        if (root == null) {
            return elements;
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && SQL_TEXT_TAGS.contains(element.getTagName())) {
                elements.add(element);
            }
        }
        return elements;
    }

    private Map<String, String> resultMapColumnByProperty(Document document) {
        Map<String, String> columnsByProperty = new LinkedHashMap<>();
        Set<String> ambiguousProperties = new LinkedHashSet<>();
        NodeList resultMaps = document.getElementsByTagName("resultMap");
        for (int i = 0; i < resultMaps.getLength(); i++) {
            Node resultMap = resultMaps.item(i);
            NodeList children = resultMap.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (!(child instanceof Element element)
                        || (!"id".equals(element.getTagName()) && !"result".equals(element.getTagName()))) {
                    continue;
                }
                String property = element.getAttribute("property");
                String column = element.getAttribute("column");
                if (!isSimpleBareIdentifier(property) || !isSimpleBareIdentifier(column)) {
                    continue;
                }
                String normalizedProperty = normalizeIdentifier(property);
                String existingColumn = columnsByProperty.get(normalizedProperty);
                if (existingColumn == null) {
                    columnsByProperty.put(normalizedProperty, column.trim());
                    continue;
                }
                if (!normalizeIdentifier(existingColumn).equals(normalizeIdentifier(column))) {
                    columnsByProperty.remove(normalizedProperty);
                    ambiguousProperties.add(normalizedProperty);
                }
            }
        }
        ambiguousProperties.forEach(columnsByProperty::remove);
        return columnsByProperty;
    }

    private boolean hasElementChild(Element statement) {
        NodeList children = statement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private void writeReplacements(Path path, List<StatementReplacement> replacements) {
        try {
            Files.createDirectories(path.getParent());
            String xml = Files.readString(path, StandardCharsets.UTF_8);
            for (StatementReplacement replacement : replacements) {
                StatementBody statementBody = findStatementBody(
                        xml,
                        replacement.tagName(),
                        replacement.statementId(),
                        replacement.occurrenceIndex()
                );
                String rewrittenBody = replacement.convertedBody() == null
                        ? rewrittenBody(statementBody.rawBody(), replacement.convertedSql())
                        : replacement.convertedBody();
                xml = xml.substring(0, statementBody.start()) + rewrittenBody + xml.substring(statementBody.end());
            }
            Files.writeString(path, xml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write mapper XML: " + path, e);
        }
    }

    private StatementBody findStatementBody(String xml, String tagName, String statementId, int occurrenceIndex) {
        if (statementId.isBlank()) {
            throw new IllegalStateException("Mapper statement id is required for text-preserving rewrite.");
        }
        if (occurrenceIndex < 0) {
            throw new IllegalStateException("Failed to locate mapper statement: " + statementId);
        }

        String quotedTag = Pattern.quote(tagName);
        String quotedId = Pattern.quote(statementId);
        Pattern openingPattern = Pattern.compile(
                "(?s)<\\s*" + quotedTag + "\\b(?=[^>]*\\bid\\s*=\\s*(?:\"" + quotedId + "\"|'" + quotedId + "'))[^>]*>"
        );
        Matcher openingMatcher = openingPattern.matcher(xml);
        for (int currentOccurrence = 0; currentOccurrence <= occurrenceIndex; currentOccurrence++) {
            if (!openingMatcher.find()) {
                throw new IllegalStateException("Failed to locate mapper statement: " + statementId);
            }
        }

        Pattern closingPattern = Pattern.compile("(?s)</\\s*" + quotedTag + "\\s*>");
        Matcher closingMatcher = closingPattern.matcher(xml);
        if (!closingMatcher.find(openingMatcher.end())) {
            throw new IllegalStateException("Failed to locate closing tag for mapper statement: " + statementId);
        }

        return new StatementBody(
                openingMatcher.end(),
                closingMatcher.start(),
                xml.substring(openingMatcher.end(), closingMatcher.start())
        );
    }

    private String rewrittenBody(String rawBody, String convertedSql) {
        int leadingEnd = 0;
        while (leadingEnd < rawBody.length() && Character.isWhitespace(rawBody.charAt(leadingEnd))) {
            leadingEnd++;
        }

        int trailingStart = rawBody.length();
        while (trailingStart > leadingEnd && Character.isWhitespace(rawBody.charAt(trailingStart - 1))) {
            trailingStart--;
        }

        String leadingWhitespace = rawBody.substring(0, leadingEnd);
        String trailingWhitespace = rawBody.substring(trailingStart);
        String convertedCore = convertedSql.strip();
        return leadingWhitespace + serializeSqlText(convertedCore, rawBody) + trailingWhitespace;
    }

    private String serializeSqlText(String sql, String rawBody) {
        if (rawBody.contains("<![CDATA[")) {
            return "<![CDATA[" + sql.replace("]]>", "]]]]><![CDATA[>") + "]]>";
        }
        return escapeXmlText(sql);
    }

    private DynamicBodyConversion convertDynamicXmlTextSegments(
            String statementTagName,
            String statementKey,
            String rawBody,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig,
            Map<String, String> resultMapColumnByProperty,
            boolean useGeneratedKeys,
            String generatedKeyProperty,
            String generatedKeyColumn
    ) {
        StringBuilder convertedBody = new StringBuilder(rawBody.length());
        List<String> appliedRules = new ArrayList<>();
        List<String> manualReviewReasons = new ArrayList<>();
        boolean changed = false;
        int index = 0;
        while (index < rawBody.length()) {
            if (rawBody.startsWith("<![CDATA[", index)) {
                int cdataEnd = rawBody.indexOf("]]>", index + "<![CDATA[".length());
                if (cdataEnd < 0) {
                    convertedBody.append(rawBody, index, rawBody.length());
                    break;
                }
                String content = rawBody.substring(index + "<![CDATA[".length(), cdataEnd);
                TextSegmentConversion conversion = convertTextSegment(
                        content,
                        statementKey,
                        sqlConverter,
                        rewriteConfig,
                        isDynamicWhereTag(nextTagName(rawBody, cdataEnd + "]]>".length()))
                );
                convertedBody.append(toCdata(conversion.convertedText()));
                addAppliedRules(appliedRules, conversion.appliedRules());
                addManualReviewReasons(manualReviewReasons, conversion.manualReviewReasons());
                changed = changed || conversion.changed();
                index = cdataEnd + "]]>".length();
            } else if (rawBody.startsWith("<!--", index)) {
                int commentEnd = rawBody.indexOf("-->", index + "<!--".length());
                if (commentEnd < 0) {
                    convertedBody.append(rawBody, index, rawBody.length());
                    break;
                }
                convertedBody.append(rawBody, index, commentEnd + "-->".length());
                index = commentEnd + "-->".length();
            } else if (rawBody.charAt(index) == '<') {
                int tagEnd = findXmlTagEnd(rawBody, index);
                if (tagEnd < 0) {
                    convertedBody.append(rawBody, index, rawBody.length());
                    break;
                }
                convertedBody.append(rawBody, index, tagEnd + 1);
                index = tagEnd + 1;
            } else {
                int nextTag = rawBody.indexOf('<', index);
                int textEnd = nextTag < 0 ? rawBody.length() : nextTag;
                String text = rawBody.substring(index, textEnd);
                TextSegmentConversion conversion = convertTextSegment(
                        text,
                        statementKey,
                        sqlConverter,
                        rewriteConfig,
                        isDynamicWhereTag(nextTagName(rawBody, textEnd))
                );
                convertedBody.append(conversion.convertedText());
                addAppliedRules(appliedRules, conversion.appliedRules());
                addManualReviewReasons(manualReviewReasons, conversion.manualReviewReasons());
                changed = changed || conversion.changed();
                index = textEnd;
            }
        }
        String rewrittenBody = convertedBody.toString();
        DynamicBodyConversion structuralConversion = convertDynamicXmlStructure(
                statementTagName,
                statementKey,
                rewrittenBody,
                sqlConverter,
                rewriteConfig,
                resultMapColumnByProperty,
                useGeneratedKeys,
                generatedKeyProperty,
                generatedKeyColumn
        );
        if (structuralConversion.changed()) {
            rewrittenBody = structuralConversion.convertedBody();
            addAppliedRules(appliedRules, structuralConversion.appliedRules());
            addManualReviewReasons(manualReviewReasons, structuralConversion.manualReviewReasons());
            changed = true;
        }
        if (appliedRules.contains(MySqlToDmSqlConverter.MYSQL_UNUSED_USER_VARIABLE_SELECT_ITEM_RULE)
                && !containsMysqlUserVariable(rewrittenBody)) {
            manualReviewReasons.removeIf(this::isMysqlUserVariableManualReviewReason);
        }
        if (appliedRules.contains(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE)
                && !containsMysqlMetadataSql(rewrittenBody)) {
            manualReviewReasons.removeIf(this::isMysqlMetadataManualReviewReason);
        }
        if (!containsMysqlOnDuplicateKeyUpdate(rewrittenBody)) {
            manualReviewReasons.removeIf(this::isMysqlOnDuplicateKeyUpdateManualReviewReason);
        }
        if (!containsMysqlInsertIgnore(rewrittenBody)) {
            manualReviewReasons.removeIf(this::isMysqlInsertIgnoreManualReviewReason);
        }
        if (appliedRules.contains(MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE)) {
            manualReviewReasons.removeIf(DYNAMIC_UPDATE_JOIN_WITH_WHERE_REASON::equals);
        }
        return new DynamicBodyConversion(
                rawBody,
                changed ? rewrittenBody : rawBody,
                appliedRules,
                manualReviewReasons,
                changed
        );
    }

    private DynamicBodyConversion convertDynamicXmlStructure(
            String statementTagName,
            String statementKey,
            String body,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig,
            Map<String, String> resultMapColumnByProperty,
            boolean useGeneratedKeys,
            String generatedKeyProperty,
            String generatedKeyColumn
    ) {
        List<String> appliedRules = new ArrayList<>();
        List<String> manualReviewReasons = new ArrayList<>();
        String converted = body;

        String commentSafe = neutralizeMyBatisPlaceholdersInSqlLineComments(converted);
        if (!commentSafe.equals(converted)) {
            converted = commentSafe;
            appliedRules.add(MYBATIS_SQL_LINE_COMMENT_PLACEHOLDER_NEUTRALIZED_RULE);
        }

        DynamicHavingConversion havingConversion = convertDynamicHavingClauses(converted);
        if (havingConversion.changed()) {
            converted = havingConversion.convertedBody();
            addAppliedRules(appliedRules, havingConversion.appliedRules());
        }

        TextRewrite unusedUserVariableSelectItem = removeUnusedUserVariableSelectItems(converted);
        if (unusedUserVariableSelectItem.changed()) {
            converted = unusedUserVariableSelectItem.text();
            appliedRules.add(MySqlToDmSqlConverter.MYSQL_UNUSED_USER_VARIABLE_SELECT_ITEM_RULE);
        }

        TextRewrite staticWhereAnd = addMissingStaticWhereAnd(converted);
        if (staticWhereAnd.changed()) {
            converted = staticWhereAnd.text();
            appliedRules.add(MYBATIS_STATIC_WHERE_MISSING_AND_RULE);
        }
        TextRewrite dynamicWhereAnd = addMissingDynamicWhereAnd(converted);
        if (dynamicWhereAnd.changed()) {
            converted = dynamicWhereAnd.text();
            appliedRules.add(MYBATIS_DYNAMIC_WHERE_MISSING_AND_RULE);
        }
        TextRewrite keywordAliasReferences = quoteDynamicQuotedAliasReferences(converted);
        if (keywordAliasReferences.changed()) {
            converted = keywordAliasReferences.text();
            appliedRules.add(MYBATIS_DYNAMIC_DAMENG_KEYWORD_ALIAS_REFERENCE_RULE);
        }

        TextRewrite informationSchemaColumns = convertDynamicInformationSchemaColumns(converted);
        if (informationSchemaColumns.changed()) {
            converted = informationSchemaColumns.text();
            appliedRules.add(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
        }

        if (!"insert".equals(statementTagName) && !"update".equals(statementTagName)) {
            return new DynamicBodyConversion(body, converted, appliedRules, manualReviewReasons, !appliedRules.isEmpty());
        }

        TextRewrite temporaryTableAsSelect = convertDynamicTemporaryTableAsSelectPrefix(converted);
        if (temporaryTableAsSelect.changed()) {
            appliedRules.add(MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE);
            converted = temporaryTableAsSelect.text();
        }
        TextRewrite dynamicTemporaryTableBindSelect = splitTemporaryTableBindSelect(converted);
        if (dynamicTemporaryTableBindSelect.changed()) {
            appliedRules.add(MYBATIS_DYNAMIC_TEMPORARY_TABLE_BIND_SELECT_TO_INSERT_RULE);
            converted = dynamicTemporaryTableBindSelect.text();
        }

        String foreachMerge = convertForeachOnDuplicateKeyUpdate(converted, statementKey, sqlConverter, rewriteConfig);
        if (!foreachMerge.equals(converted)) {
            appliedRules.add(MySqlToDmSqlConverter.MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
            converted = foreachMerge;
        }

        String batchMerge = convertBatchOnDuplicateKeyUpdate(converted, statementKey, rewriteConfig);
        if (!batchMerge.equals(converted)) {
            appliedRules.add(MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
            converted = batchMerge;
        }

        String insertIgnoreMerge = convertBatchInsertIgnore(converted, statementKey, rewriteConfig);
        if (!insertIgnoreMerge.equals(converted)) {
            appliedRules.add(MYBATIS_DYNAMIC_INSERT_IGNORE_TO_DM_MERGE_RULE);
            converted = insertIgnoreMerge;
        }

        String dynamicMerge = convertDynamicOnDuplicateKeyUpdate(converted, statementKey, rewriteConfig);
        if (!dynamicMerge.equals(converted)) {
            appliedRules.add(MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
            converted = dynamicMerge;
        }

        if (!"insert".equals(statementTagName)) {
            DynamicBodyConversion dynamicWhereUpdateJoin = convertDynamicUpdateJoinWithWhereTag(
                    converted,
                    statementKey,
                    sqlConverter,
                    rewriteConfig
            );
            if (dynamicWhereUpdateJoin.changed()) {
                converted = dynamicWhereUpdateJoin.convertedBody();
                addAppliedRules(appliedRules, dynamicWhereUpdateJoin.appliedRules());
                addManualReviewReasons(manualReviewReasons, dynamicWhereUpdateJoin.manualReviewReasons());
            }
            TextRewrite mergedSetTrims = mergeConsecutiveDynamicSetTrimBlocks(converted);
            if (mergedSetTrims.changed()) {
                appliedRules.add(MYBATIS_DYNAMIC_SET_TRIM_BLOCKS_MERGED_RULE);
                converted = mergedSetTrims.text();
            }
            TextRewrite propertyColumnTargets = normalizeDynamicSetPropertyColumns(
                    converted,
                    resultMapColumnByProperty
            );
            if (propertyColumnTargets.changed()) {
                appliedRules.add(MYBATIS_DYNAMIC_SET_PROPERTY_COLUMN_RULE);
                converted = propertyColumnTargets.text();
            }
            TextRewrite qualifiedUpdateJoinSetTargets = qualifyDynamicUpdateJoinSetTargets(converted);
            if (qualifiedUpdateJoinSetTargets.changed()) {
                appliedRules.add(MYBATIS_DYNAMIC_UPDATE_JOIN_SET_TARGET_QUALIFIED_RULE);
                converted = qualifiedUpdateJoinSetTargets.text();
            }
            TextRewrite dynamicSetCommas = addMissingDynamicSetCommas(converted);
            if (dynamicSetCommas.changed()) {
                appliedRules.add(MYBATIS_DYNAMIC_SET_MISSING_COMMA_RULE);
                converted = dynamicSetCommas.text();
            }
            TextRewrite duplicateSetAssignments = removeDuplicateDynamicSetAssignments(converted);
            if (duplicateSetAssignments.changed()) {
                appliedRules.add(MYBATIS_DYNAMIC_SET_DUPLICATE_ASSIGNMENT_RULE);
                converted = duplicateSetAssignments.text();
            }
            DynamicBodyConversion dynamicUpdateOrderLimitOne = convertDynamicUpdateOrderLimitOneWithSetClause(converted);
            if (dynamicUpdateOrderLimitOne.changed()) {
                addAppliedRules(appliedRules, dynamicUpdateOrderLimitOne.appliedRules());
                converted = dynamicUpdateOrderLimitOne.convertedBody();
            }
            TextRewrite trailingBlockPredicates = attachTrailingDynamicPredicatesToDamengBlock(converted);
            if (trailingBlockPredicates.changed()) {
                appliedRules.add(DAMENG_BLOCK_TRAILING_DYNAMIC_PREDICATE_ATTACHED_RULE);
                converted = trailingBlockPredicates.text();
            }
            TextRewrite duplicateBlockSemicolon = removeDuplicateDamengBlockSemicolons(converted);
            if (duplicateBlockSemicolon.changed()) {
                appliedRules.add(DAMENG_BLOCK_DUPLICATE_SEMICOLON_REMOVED_RULE);
                converted = duplicateBlockSemicolon.text();
            }
            return new DynamicBodyConversion(body, converted, appliedRules, manualReviewReasons, !appliedRules.isEmpty());
        }

        String withMissingValues = addMissingBatchInsertValues(converted);
        if (!withMissingValues.equals(converted)) {
            appliedRules.add(MYBATIS_BATCH_INSERT_ADD_VALUES_RULE);
            converted = withMissingValues;
        }

        TextRewrite qualifiedBatchInsertListItems = qualifyBatchInsertListItemReferences(converted);
        if (qualifiedBatchInsertListItems.changed()) {
            appliedRules.add(MYBATIS_BATCH_INSERT_LIST_ITEM_REFERENCE_RULE);
            converted = qualifiedBatchInsertListItems.text();
        }

        TextRewrite dynamicInsertTrimCommas = addMissingDynamicInsertTrimCommas(converted);
        if (dynamicInsertTrimCommas.changed()) {
            appliedRules.add(MYBATIS_DYNAMIC_INSERT_TRIM_MISSING_COMMA_RULE);
            converted = dynamicInsertTrimCommas.text();
        }

        TextRewrite foreachTupleCommas = addMissingForeachTupleCommas(converted);
        if (foreachTupleCommas.changed()) {
            appliedRules.add(MYBATIS_FOREACH_TUPLE_MISSING_COMMA_RULE);
            converted = foreachTupleCommas.text();
        }

        String withoutTrailingCommas = removeForeachTrailingCommas(converted);
        if (!withoutTrailingCommas.equals(converted)) {
            appliedRules.add(MYBATIS_FOREACH_TRAILING_COMMA_RULE);
        }
        converted = withoutTrailingCommas;

        String insertTable = extractInsertTableNameLenient(converted);
        boolean generatedKeyBatchChanged = false;
        if (useGeneratedKeys && generatedKeyProperty != null && !generatedKeyProperty.isBlank()) {
            TextRewrite generatedKeyBatch = conditionalizeGeneratedKeyBatchInsert(
                    converted,
                    generatedKeyProperty,
                    generatedKeyColumn,
                    insertTable
            );
            if (generatedKeyBatch.changed()) {
                appliedRules.add(MYBATIS_BATCH_GENERATED_KEY_CONDITIONAL_RULE);
                appliedRules.add(MYBATIS_IDENTITY_INSERT_REPLACE_NULL_RULE);
                converted = collapseAdjacentDuplicateStandalonePlaceholders(generatedKeyBatch.text());
                generatedKeyBatchChanged = true;
            }
        }
        if (!generatedKeyBatchChanged
                && !useGeneratedKeys
                && rewriteConfig.requiresIdentityInsert(insertTable)) {
            TextRewrite identityInsert = wrapIdentityInsert(converted, insertTable);
            if (identityInsert.changed()) {
                appliedRules.add(MYBATIS_IDENTITY_INSERT_REPLACE_NULL_RULE);
                converted = identityInsert.text();
            }
        }
        TextRewrite duplicateBlockSemicolon = removeDuplicateDamengBlockSemicolons(converted);
        if (duplicateBlockSemicolon.changed()) {
            appliedRules.add(DAMENG_BLOCK_DUPLICATE_SEMICOLON_REMOVED_RULE);
            converted = duplicateBlockSemicolon.text();
        }
        return new DynamicBodyConversion(body, converted, appliedRules, manualReviewReasons, !appliedRules.isEmpty());
    }

    private TextRewrite removeDuplicateDamengBlockSemicolons(String body) {
        String converted = Pattern.compile("(?is)\\bEND;\\s*;").matcher(body).replaceAll("END;");
        return new TextRewrite(converted, !converted.equals(body));
    }

    private TextRewrite attachTrailingDynamicPredicatesToDamengBlock(String body) {
        Matcher matcher = Pattern.compile(
                "(?is)^(?<leading>\\s*BEGIN\\b)(?<block>[\\s\\S]*?)(?<end>\\bEND;)\\s*(?<tail>\\S[\\s\\S]*)$"
        ).matcher(body);
        if (!matcher.matches()) {
            return new TextRewrite(body, false);
        }
        String tail = matcher.group("tail");
        if (!isDamengBlockTrailingPredicateTail(tail)) {
            return new TextRewrite(body, false);
        }
        List<String> statements = splitDamengBlockStatements(matcher.group("block"));
        if (statements.size() < 2 || statements.stream().anyMatch(statement -> !isDamengBlockUpdateStatement(statement))) {
            return new TextRewrite(body, false);
        }

        String tailWithoutTrailingWhitespace = tail.stripTrailing();
        StringBuilder converted = new StringBuilder(body.length() + tail.length() * statements.size());
        converted.append(matcher.group("leading"));
        for (String statement : statements) {
            String withoutSemicolon = stripTrailingStatementSemicolon(statement).stripTrailing();
            converted.append(withoutSemicolon);
            if (!startsWithLineBreak(tailWithoutTrailingWhitespace)) {
                converted.append("\n");
            }
            converted.append(tailWithoutTrailingWhitespace).append(";\n");
        }
        converted.append(matcher.group("end"));
        return new TextRewrite(converted.toString(), true);
    }

    private boolean isDamengBlockTrailingPredicateTail(String tail) {
        String stripped = tail == null ? "" : tail.stripLeading();
        if (stripped.isBlank()) {
            return false;
        }
        String lower = stripped.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("<if")
                || lower.startsWith("<choose")
                || lower.startsWith("<foreach")
                || lower.startsWith("<trim")
                || lower.startsWith("and ")
                || lower.startsWith("or "))) {
            return false;
        }
        return findTopLevelKeywordSkippingXml(stripped, "UPDATE", 0) < 0
                && findTopLevelKeywordSkippingXml(stripped, "INSERT", 0) < 0
                && findTopLevelKeywordSkippingXml(stripped, "DELETE", 0) < 0
                && findTopLevelKeywordSkippingXml(stripped, "SELECT", 0) < 0;
    }

    private List<String> splitDamengBlockStatements(String block) {
        List<String> statements = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int index = 0;
        while (index < block.length()) {
            char current = block.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(block, index, current);
            } else if (startsMyBatisPlaceholder(block, index)) {
                index = skipMyBatisPlaceholder(block, index);
            } else if (current == '<') {
                int tagEnd = findXmlTagEnd(block, index);
                index = tagEnd < 0 ? block.length() : tagEnd + 1;
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (current == ';' && depth == 0) {
                statements.add(block.substring(start, index + 1));
                index++;
                start = index;
            } else {
                index++;
            }
        }
        if (!block.substring(start).isBlank()) {
            return List.of();
        }
        return statements;
    }

    private boolean isDamengBlockUpdateStatement(String statement) {
        String stripped = statement == null ? "" : statement.stripLeading();
        return isKeywordAt(stripped, 0, "UPDATE")
                && stripped.stripTrailing().endsWith(";")
                && findTopLevelKeywordSkippingXml(stripped, "WHERE", 0) >= 0;
    }

    private String stripTrailingStatementSemicolon(String statement) {
        int index = statement.length() - 1;
        while (index >= 0 && Character.isWhitespace(statement.charAt(index))) {
            index--;
        }
        if (index >= 0 && statement.charAt(index) == ';') {
            return statement.substring(0, index) + statement.substring(index + 1);
        }
        return statement;
    }

    private boolean startsWithLineBreak(String value) {
        return value.startsWith("\n") || value.startsWith("\r");
    }

    private TextRewrite quoteDynamicQuotedAliasReferences(String body) {
        Map<String, String> aliases = collectQuotedRelationAliases(body);
        if (aliases.isEmpty()) {
            return new TextRewrite(body, false);
        }
        List<TextReplacement> replacements = new ArrayList<>();
        collectQuotedAliasReferenceReplacements(body, aliases, replacements);
        return applyTextReplacements(body, replacements);
    }

    private Map<String, String> collectQuotedRelationAliases(String body) {
        Map<String, String> aliases = new LinkedHashMap<>();
        int index = 0;
        while (index < body.length()) {
            if (body.startsWith("<!--", index)) {
                int end = body.indexOf("-->", index + "<!--".length());
                index = end < 0 ? body.length() : end + "-->".length();
            } else if (body.startsWith("<![CDATA[", index)) {
                int end = body.indexOf("]]>", index + "<![CDATA[".length());
                if (end < 0) {
                    index = body.length();
                } else {
                    collectQuotedRelationAliasesInSqlText(
                            body.substring(index + "<![CDATA[".length(), end),
                            aliases
                    );
                    index = end + "]]>".length();
                }
            } else if (body.charAt(index) == '<') {
                int tagEnd = findXmlTagEnd(body, index);
                index = tagEnd < 0 ? body.length() : tagEnd + 1;
            } else {
                int nextTag = body.indexOf('<', index);
                int textEnd = nextTag < 0 ? body.length() : nextTag;
                collectQuotedRelationAliasesInSqlText(body.substring(index, textEnd), aliases);
                index = textEnd;
            }
        }
        return aliases;
    }

    private void collectQuotedRelationAliasesInSqlText(String text, Map<String, String> aliases) {
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(text, index, current);
            } else if (startsMyBatisPlaceholder(text, index)) {
                index = skipMyBatisPlaceholder(text, index);
            } else if (text.startsWith("--", index)) {
                int end = text.indexOf('\n', index + 2);
                index = end < 0 ? text.length() : end;
            } else if (text.startsWith("/*", index)) {
                int end = text.indexOf("*/", index + 2);
                index = end < 0 ? text.length() : end + 2;
            } else if (isKeywordAt(text, index, "FROM") || isKeywordAt(text, index, "JOIN")) {
                String keyword = isKeywordAt(text, index, "FROM") ? "FROM" : "JOIN";
                int relationEnd = readRelationEnd(text, skipWhitespace(text, index + keyword.length()));
                if (relationEnd < 0) {
                    index += keyword.length();
                    continue;
                }
                int aliasStart = skipWhitespace(text, relationEnd);
                if (isKeywordAt(text, aliasStart, "AS")) {
                    aliasStart = skipWhitespace(text, aliasStart + "AS".length());
                }
                IdentifierToken alias = readIdentifierToken(text, aliasStart);
                if (alias != null && alias.text().startsWith("\"") && !isSqlClauseKeyword(alias.text())) {
                    String unquoted = unquoteIdentifier(alias.text());
                    aliases.putIfAbsent(unquoted.toUpperCase(Locale.ROOT), unquoted);
                    index = alias.endIndex();
                } else {
                    index = relationEnd;
                }
            } else {
                index++;
            }
        }
    }

    private void collectQuotedAliasReferenceReplacements(
            String body,
            Map<String, String> aliases,
            List<TextReplacement> replacements
    ) {
        int index = 0;
        while (index < body.length()) {
            if (body.startsWith("<!--", index)) {
                int end = body.indexOf("-->", index + "<!--".length());
                index = end < 0 ? body.length() : end + "-->".length();
            } else if (body.startsWith("<![CDATA[", index)) {
                int contentStart = index + "<![CDATA[".length();
                int end = body.indexOf("]]>", contentStart);
                if (end < 0) {
                    index = body.length();
                } else {
                    collectQuotedAliasReferenceReplacementsInSqlText(
                            body.substring(contentStart, end),
                            contentStart,
                            aliases,
                            replacements
                    );
                    index = end + "]]>".length();
                }
            } else if (body.charAt(index) == '<') {
                int tagEnd = findXmlTagEnd(body, index);
                index = tagEnd < 0 ? body.length() : tagEnd + 1;
            } else {
                int nextTag = body.indexOf('<', index);
                int textEnd = nextTag < 0 ? body.length() : nextTag;
                collectQuotedAliasReferenceReplacementsInSqlText(
                        body.substring(index, textEnd),
                        index,
                        aliases,
                        replacements
                );
                index = textEnd;
            }
        }
    }

    private void collectQuotedAliasReferenceReplacementsInSqlText(
            String text,
            int baseOffset,
            Map<String, String> aliases,
            List<TextReplacement> replacements
    ) {
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(text, index, current);
            } else if (startsMyBatisPlaceholder(text, index)) {
                index = skipMyBatisPlaceholder(text, index);
            } else if (text.startsWith("--", index)) {
                int end = text.indexOf('\n', index + 2);
                index = end < 0 ? text.length() : end;
            } else if (text.startsWith("/*", index)) {
                int end = text.indexOf("*/", index + 2);
                index = end < 0 ? text.length() : end + 2;
            } else {
                IdentifierToken identifier = readIdentifierToken(text, index);
                if (identifier == null || identifier.text().startsWith("\"") || identifier.text().startsWith("`")) {
                    index++;
                    continue;
                }
                String alias = aliases.get(identifier.text().toUpperCase(Locale.ROOT));
                int afterIdentifier = skipWhitespace(text, identifier.endIndex());
                if (alias != null && afterIdentifier < text.length() && text.charAt(afterIdentifier) == '.') {
                    replacements.add(new TextReplacement(
                            baseOffset + index,
                            baseOffset + identifier.endIndex(),
                            quoteDynamicAliasIdentifier(alias)
                    ));
                }
                index = identifier.endIndex();
            }
        }
    }

    private int readRelationEnd(String text, int start) {
        if (start >= text.length()) {
            return -1;
        }
        if (text.charAt(start) == '(') {
            return skipParenthesizedSql(text, start);
        }
        if (startsMyBatisPlaceholder(text, start)) {
            return skipMyBatisPlaceholder(text, start);
        }
        IdentifierToken identifier = readIdentifierToken(text, start);
        if (identifier == null) {
            return -1;
        }
        int end = identifier.endIndex();
        int index = skipWhitespace(text, end);
        while (index < text.length() && text.charAt(index) == '.') {
            int partStart = skipWhitespace(text, index + 1);
            IdentifierToken part = readIdentifierToken(text, partStart);
            if (part == null) {
                break;
            }
            end = part.endIndex();
            index = skipWhitespace(text, end);
        }
        return end;
    }

    private int skipParenthesizedSql(String text, int start) {
        int depth = 1;
        int index = start + 1;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(text, index, current);
            } else if (startsMyBatisPlaceholder(text, index)) {
                index = skipMyBatisPlaceholder(text, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth--;
                index++;
                if (depth == 0) {
                    return index;
                }
            } else {
                index++;
            }
        }
        return -1;
    }

    private TextRewrite convertDynamicTemporaryTableAsSelectPrefix(String body) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (body.startsWith("<!--", index)) {
                int end = body.indexOf("-->", index + "<!--".length());
                index = end < 0 ? body.length() : end + "-->".length();
            } else if (body.startsWith("<![CDATA[", index)) {
                int end = body.indexOf("]]>", index + "<![CDATA[".length());
                index = end < 0 ? body.length() : end + "]]>".length();
            } else if (body.startsWith("--", index)) {
                int end = body.indexOf('\n', index + 2);
                index = end < 0 ? body.length() : end;
            } else if (body.startsWith("/*", index)) {
                int end = body.indexOf("*/", index + 2);
                index = end < 0 ? body.length() : end + 2;
            } else if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(body, index, current);
            } else if (startsMyBatisPlaceholder(body, index)) {
                index = skipMyBatisPlaceholder(body, index);
            } else if (current == '<') {
                XmlTag tag = readXmlTag(body, index);
                index = tag == null ? index + 1 : tag.endIndex();
            } else if (isKeywordAt(body, index, "CREATE")) {
                TemporaryTableSelectStatement statement = readTemporaryTableAsSelectStatement(body, index);
                if (statement == null) {
                    index++;
                } else {
                    String replacement = "CREATE GLOBAL TEMPORARY TABLE "
                            + statement.tableName()
                            + " ON COMMIT PRESERVE ROWS AS";
                    if (!body.substring(statement.createIndex(), statement.prefixEndIndex()).equals(replacement)) {
                        replacements.add(new TextReplacement(
                                statement.createIndex(),
                                statement.prefixEndIndex(),
                                replacement
                        ));
                    }
                    index = statement.endIndex();
                }
            } else {
                index++;
            }
        }
        return applyTextReplacements(body, replacements);
    }

    private TextRewrite convertDynamicInformationSchemaColumns(String body) {
        String expression = "(?:#\\{[^}]+}|\\$\\{[^}]+}|\\?|database\\s*\\(\\s*\\)"
                + "|\\(\\s*select\\s+database\\s*\\(\\s*\\)\\s*\\)"
                + "|'[^']*'|\"[^\"]*\"|`[^`]*`|[^\\s<]+)";
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*select\\s*"
                        + "(?<include><include\\b[^>]*(?:/>|>\\s*</include\\s*>))\\s*"
                        + "from\\s+information_schema\\s*\\.\\s*columns\\s*"
                        + "where\\s+table_schema\\s*=\\s*(?<schema>"
                        + expression
                        + ")\\s*(?:<!--[\\s\\S]*?-->\\s*)*"
                        + "and\\s+table_name\\s*=\\s*(?<table>"
                        + expression
                        + ")\\s*(?:<!--[\\s\\S]*?-->\\s*)*;?\\s*$"
        ).matcher(body);
        if (!matcher.matches()) {
            return new TextRewrite(body, false);
        }
        String refId = defaultString(xmlAttribute(matcher.group("include"), "refid"));
        if (!"Column_List".equalsIgnoreCase(refId.trim())) {
            return new TextRewrite(body, false);
        }
        String leading = body.substring(0, leadingWhitespaceLength(body));
        int trailingStart = trimTrailingWhitespaceIndex(body, 0, body.length());
        String trailing = body.substring(trailingStart);
        String converted = leading
                + "SELECT\n"
                + "    c.OWNER AS TABLE_SCHEMA,\n"
                + "    c.TABLE_NAME,\n"
                + "    c.COLUMN_NAME,\n"
                + "    c.DATA_TYPE,\n"
                + "    cc.COMMENTS AS COLUMN_COMMENT,\n"
                + "    c.DATA_DEFAULT AS COLUMN_DEFAULT,\n"
                + "    NULL AS CHARACTER_SET_NAME,\n"
                + "    CASE c.NULLABLE WHEN 'Y' THEN 'YES' ELSE 'NO' END AS IS_NULLABLE\n"
                + "FROM ALL_TAB_COLUMNS c\n"
                + "LEFT JOIN ALL_COL_COMMENTS cc\n"
                + "    ON cc.OWNER = c.OWNER\n"
                + "    AND cc.TABLE_NAME = c.TABLE_NAME\n"
                + "    AND cc.COLUMN_NAME = c.COLUMN_NAME\n"
                + "WHERE c.OWNER = "
                + damengMetadataExpression(matcher.group("schema"))
                + "\n"
                + "    AND c.TABLE_NAME = "
                + damengMetadataExpression(matcher.group("table"))
                + "\n"
                + "ORDER BY c.COLUMN_ID"
                + trailing;
        return new TextRewrite(converted, true);
    }

    private String damengMetadataExpression(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        String normalized = trimmed.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if ("database()".equals(normalized) || "(selectdatabase())".equals(normalized)) {
            return "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')";
        }
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            return "UPPER('" + trimmed.replace("'", "''") + "')";
        }
        return "UPPER(REPLACE(" + trimmed + ", '\"', ''))";
    }

    private TextRewrite splitTemporaryTableBindSelect(String body) {
        Matcher matcher = DYNAMIC_TEMPORARY_TABLE_BIND_SELECT_PATTERN.matcher(body);
        if (!matcher.find()) {
            return new TextRewrite(body, false);
        }
        String trailing = body.substring(matcher.end()).strip();
        if (!body.substring(0, matcher.start()).isBlank() || !(trailing.isEmpty() || ";".equals(trailing))) {
            return new TextRewrite(body, false);
        }
        String tableName = matcher.group("table");
        String inner = matcher.group("inner");
        String dynamicInsertSelectList = inner
                .replaceAll("(?is)#\\{\\s*field\\.fieldValue\\s*(?:,[^}]*)?}", "?");
        String bindValueList = inner
                .replaceAll("(?is)#\\{\\s*field\\.fieldValue\\s*(?:,[^}]*)?}\\s+AS\\s+\\$\\{\\s*field\\.fieldName\\s*}", "#{field.fieldValue}");
        String baseIndent = indentationOfLastLine(body.substring(0, matcher.start()));
        String statementIndent = baseIndent + "    ";
        String replacement = "BEGIN\n"
                + statementIndent + "EXECUTE IMMEDIATE 'CREATE GLOBAL TEMPORARY TABLE "
                + tableName
                + "\n"
                + statementIndent + "(\n"
                + statementIndent + "<foreach collection=\"list[0]\" item=\"field\" separator=\",\">\n"
                + statementIndent + "    ${field.fieldName} VARCHAR(4000)\n"
                + statementIndent + "</foreach>\n"
                + statementIndent + ") ON COMMIT PRESERVE ROWS';\n"
                + statementIndent + "<foreach collection=\"list\" item=\"item\" separator=\";\">\n"
                + statementIndent + "    EXECUTE IMMEDIATE 'insert into "
                + tableName
                + "\n"
                + statementIndent + "    select\n"
                + statementIndent + "    "
                + dynamicInsertSelectList
                + "\n"
                + statementIndent + "    from dual' USING\n"
                + statementIndent + "    "
                + bindValueList
                + "\n"
                + statementIndent + "</foreach>;\n"
                + baseIndent + "END;";
        return new TextRewrite(
                body.substring(0, matcher.start()) + replacement + body.substring(matcher.end()),
                true
        );
    }

    private DynamicHavingConversion convertDynamicHavingClauses(String body) {
        String converted = body;
        List<String> appliedRules = new ArrayList<>();
        boolean changed = false;
        int guard = 0;
        while (guard < 100) {
            ScopeHavingConversion scopeConversion = convertFirstDynamicHavingScope(converted);
            if (!scopeConversion.changed()) {
                break;
            }
            converted = scopeConversion.convertedBody();
            addAppliedRules(appliedRules, scopeConversion.appliedRules());
            changed = true;
            guard++;
        }
        return new DynamicHavingConversion(body, converted, appliedRules, changed);
    }

    private TextRewrite removeUnusedUserVariableSelectItems(String body) {
        SqlView view = sqlView(body);
        List<TextReplacement> replacements = new ArrayList<>();
        for (SelectScope scope : selectScopes(view.text())) {
            if (previousNonWhitespace(view.text(), scope.selectIndex()) != '(') {
                continue;
            }
            int selectListStart = scope.selectIndex() + "SELECT".length();
            List<SelectListPart> items = splitTopLevelSelectListParts(
                    body,
                    view.text(),
                    selectListStart,
                    scope.fromIndex(),
                    scope.depth()
            );
            if (items.size() < 2) {
                continue;
            }
            List<SelectListPart> keptItems = new ArrayList<>();
            boolean removed = false;
            for (SelectListPart item : items) {
                UserVariableInitialization initialization = userVariableInitializationSelectItem(item.text());
                if (initialization != null
                        && !containsUserVariableReference(view.text(), initialization.name(), item.startIndex(), item.endIndex())) {
                    removed = true;
                } else {
                    keptItems.add(item);
                }
            }
            if (!removed || keptItems.isEmpty()) {
                continue;
            }
            replacements.add(new TextReplacement(
                    selectListStart,
                    scope.fromIndex(),
                    rebuildSelectList(body.substring(selectListStart, scope.fromIndex()), keptItems, selectListStart)
            ));
        }
        replacements.sort((left, right) -> Integer.compare(left.startIndex(), right.startIndex()));
        return applyTextReplacements(body, replacements);
    }

    private List<SelectListPart> splitTopLevelSelectListParts(
            String body,
            String view,
            int start,
            int end,
            int targetDepth
    ) {
        List<SelectListPart> parts = new ArrayList<>();
        int depth = depthAt(view, start);
        int partStart = start;
        int index = start;
        while (index < end) {
            char current = view.charAt(index);
            if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (current == ',' && depth == targetDepth) {
                parts.add(new SelectListPart(body.substring(partStart, index), partStart, index));
                index++;
                partStart = index;
            } else {
                index++;
            }
        }
        parts.add(new SelectListPart(body.substring(partStart, end), partStart, end));
        return parts;
    }

    private String rebuildSelectList(String originalSelectList, List<SelectListPart> keptItems, int selectListStart) {
        StringBuilder rebuilt = new StringBuilder(originalSelectList.length());
        String leadingWhitespace = leadingWhitespace(originalSelectList);
        SelectListPart first = keptItems.get(0);
        if (first.startIndex() > selectListStart) {
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

    private boolean containsUserVariableReference(String view, String variableName, int excludedStart, int excludedEnd) {
        int index = 0;
        while (index < view.length()) {
            if (index >= excludedStart && index < excludedEnd) {
                index = excludedEnd;
                continue;
            }
            if (view.charAt(index) == '@') {
                if (index + 1 < view.length() && view.charAt(index + 1) == '@') {
                    index += 2;
                    continue;
                }
                int nameStart = index + 1;
                int nameEnd = nameStart;
                while (nameEnd < view.length() && isUserVariableNamePart(view.charAt(nameEnd))) {
                    nameEnd++;
                }
                if (nameEnd > nameStart
                        && view.substring(nameStart, nameEnd).equalsIgnoreCase(variableName)) {
                    return true;
                }
                index = nameEnd > nameStart ? nameEnd : index + 1;
            } else {
                index++;
            }
        }
        return false;
    }

    private boolean containsMysqlUserVariable(String body) {
        String view = sqlView(body == null ? "" : body).text();
        int index = 0;
        while (index < view.length()) {
            if (view.charAt(index) == '@') {
                if (index + 1 < view.length() && view.charAt(index + 1) == '@') {
                    index += 2;
                    continue;
                }
                int nameStart = index + 1;
                if (nameStart < view.length() && isUserVariableNamePart(view.charAt(nameStart))) {
                    return true;
                }
            }
            index++;
        }
        return false;
    }

    private boolean isMysqlUserVariableManualReviewReason(String reason) {
        return reason != null
                && (reason.contains("MySQL user variables") || reason.contains("@var"));
    }

    private boolean containsMysqlMetadataSql(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.toUpperCase(Locale.ROOT).contains("INFORMATION_SCHEMA")
                || Pattern.compile("(?i)\\bdatabase\\s*\\(").matcher(value).find();
    }

    private boolean isMysqlMetadataManualReviewReason(String reason) {
        return reason != null
                && (reason.contains("MySQL metadata SQL")
                || reason.contains("information_schema")
                || reason.contains("database()"));
    }

    private boolean containsMysqlOnDuplicateKeyUpdate(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return Pattern.compile("(?is)\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b")
                .matcher(sqlView(value).text())
                .find();
    }

    private boolean isMysqlOnDuplicateKeyUpdateManualReviewReason(String reason) {
        return reason != null && reason.contains("ON DUPLICATE KEY UPDATE");
    }

    private boolean containsMysqlInsertIgnore(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return Pattern.compile("(?is)\\bINSERT\\s+IGNORE\\b")
                .matcher(sqlView(value).text())
                .find();
    }

    private boolean isMysqlInsertIgnoreManualReviewReason(String reason) {
        return reason != null && reason.contains("INSERT IGNORE");
    }

    private boolean isUserVariableNamePart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private String leadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(0, index);
    }

    private TextRewrite inlineTemporaryTableAsSelectForeachItemParameters(String body) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (body.startsWith("<!--", index)) {
                int end = body.indexOf("-->", index + "<!--".length());
                index = end < 0 ? body.length() : end + "-->".length();
            } else if (body.startsWith("<![CDATA[", index)) {
                int end = body.indexOf("]]>", index + "<![CDATA[".length());
                index = end < 0 ? body.length() : end + "]]>".length();
            } else if (body.startsWith("--", index)) {
                int end = body.indexOf('\n', index + 2);
                index = end < 0 ? body.length() : end;
            } else if (body.startsWith("/*", index)) {
                int end = body.indexOf("*/", index + 2);
                index = end < 0 ? body.length() : end + 2;
            } else if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(body, index, current);
            } else if (startsMyBatisPlaceholder(body, index)) {
                index = skipMyBatisPlaceholder(body, index);
            } else if (current == '<') {
                XmlTag tag = readXmlTag(body, index);
                index = tag == null ? index + 1 : tag.endIndex();
            } else if (isKeywordAt(body, index, "CREATE")) {
                TemporaryTableSelectStatement statement = readTemporaryTableAsSelectStatement(body, index);
                if (statement == null) {
                    index++;
                } else {
                    addTemporaryTableForeachLiteralReplacements(
                            body,
                            statement.selectIndex(),
                            statement.endIndex(),
                            replacements
                    );
                    index = statement.endIndex();
                }
            } else {
                index++;
            }
        }
        return applyTextReplacements(body, replacements);
    }

    private TemporaryTableSelectStatement readTemporaryTableAsSelectStatement(String body, int createIndex) {
        int index = skipWhitespace(body, createIndex + "CREATE".length());
        if (isKeywordAt(body, index, "GLOBAL")) {
            index = skipWhitespace(body, index + "GLOBAL".length());
        }
        if (!isKeywordAt(body, index, "TEMPORARY")) {
            return null;
        }
        index = skipWhitespace(body, index + "TEMPORARY".length());
        if (!isKeywordAt(body, index, "TABLE")) {
            return null;
        }
        int tableNameStart = skipWhitespace(body, index + "TABLE".length());
        int tableNameEnd = readCreateTableNameEnd(body, tableNameStart);
        if (tableNameEnd <= tableNameStart) {
            return null;
        }
        index = skipWhitespace(body, tableNameEnd);
        if (index < body.length() && body.charAt(index) == '(') {
            return null;
        }
        int prefixEndIndex = tableNameEnd;
        int searchStart = index;
        int onCommitEnd = readOnCommitRowsClauseEnd(body, index);
        if (onCommitEnd > index) {
            prefixEndIndex = onCommitEnd;
            searchStart = skipWhitespace(body, onCommitEnd);
        }
        if (isKeywordAt(body, searchStart, "AS")) {
            prefixEndIndex = searchStart + "AS".length();
            searchStart = skipWhitespace(body, prefixEndIndex);
        }
        int selectIndex = findTopLevelKeywordSkippingXml(body, "SELECT", searchStart);
        if (selectIndex < 0) {
            return null;
        }
        String tableName = body.substring(tableNameStart, tableNameEnd).trim();
        return new TemporaryTableSelectStatement(
                createIndex,
                prefixEndIndex,
                selectIndex,
                findStatementEndSkippingXml(body, selectIndex),
                tableName
        );
    }

    private int readOnCommitRowsClauseEnd(String body, int start) {
        int index = skipWhitespace(body, start);
        if (!isKeywordAt(body, index, "ON")) {
            return -1;
        }
        index = skipWhitespace(body, index + "ON".length());
        if (!isKeywordAt(body, index, "COMMIT")) {
            return -1;
        }
        index = skipWhitespace(body, index + "COMMIT".length());
        if (isKeywordAt(body, index, "PRESERVE")) {
            index = skipWhitespace(body, index + "PRESERVE".length());
        } else if (isKeywordAt(body, index, "DELETE")) {
            index = skipWhitespace(body, index + "DELETE".length());
        } else {
            return -1;
        }
        if (!isKeywordAt(body, index, "ROWS")) {
            return -1;
        }
        return index + "ROWS".length();
    }

    private int readCreateTableNameEnd(String body, int start) {
        int index = start;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (Character.isWhitespace(current) || current == '(' || current == ';' || current == '<') {
                break;
            }
            if (startsMyBatisPlaceholder(body, index)) {
                index = skipMyBatisPlaceholder(body, index);
            } else if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(body, index, current);
            } else {
                index++;
            }
        }
        return index;
    }

    private int findStatementEndSkippingXml(String body, int start) {
        int index = start;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (body.startsWith("<!--", index)) {
                int end = body.indexOf("-->", index + "<!--".length());
                index = end < 0 ? body.length() : end + "-->".length();
            } else if (body.startsWith("<![CDATA[", index)) {
                int end = body.indexOf("]]>", index + "<![CDATA[".length());
                index = end < 0 ? body.length() : end + "]]>".length();
            } else if (body.startsWith("--", index)) {
                int end = body.indexOf('\n', index + 2);
                index = end < 0 ? body.length() : end;
            } else if (body.startsWith("/*", index)) {
                int end = body.indexOf("*/", index + 2);
                index = end < 0 ? body.length() : end + 2;
            } else if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(body, index, current);
            } else if (startsMyBatisPlaceholder(body, index)) {
                index = skipMyBatisPlaceholder(body, index);
            } else if (current == '<') {
                XmlTag tag = readXmlTag(body, index);
                index = tag == null ? index + 1 : tag.endIndex();
            } else if (current == ';') {
                return index + 1;
            } else {
                index++;
            }
        }
        return body.length();
    }

    private void addTemporaryTableForeachLiteralReplacements(
            String body,
            int start,
            int end,
            List<TextReplacement> replacements
    ) {
        int index = start;
        while (index >= 0 && index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                return;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "foreach".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "foreach", end);
                if (closingStart < 0) {
                    index = tag.endIndex();
                    continue;
                }
                String item = xmlAttribute(body.substring(tagStart, tag.endIndex()), "item");
                if (item != null && !item.isBlank()) {
                    addScalarForeachItemLiteralReplacements(
                            body,
                            tag.endIndex(),
                            closingStart,
                            item.trim(),
                            replacements
                    );
                }
                int closingEnd = body.indexOf('>', closingStart + 1);
                index = closingEnd < 0 ? closingStart + 1 : closingEnd + 1;
            } else {
                index = tag.endIndex();
            }
        }
    }

    private void addScalarForeachItemLiteralReplacements(
            String body,
            int start,
            int end,
            String item,
            List<TextReplacement> replacements
    ) {
        int index = start;
        while (index < end) {
            char current = body.charAt(index);
            if (body.startsWith("<!--", index)) {
                int commentEnd = body.indexOf("-->", index + "<!--".length());
                index = commentEnd < 0 ? end : Math.min(end, commentEnd + "-->".length());
            } else if (body.startsWith("<![CDATA[", index)) {
                int cdataEnd = body.indexOf("]]>", index + "<![CDATA[".length());
                index = cdataEnd < 0 ? end : Math.min(end, cdataEnd + "]]>".length());
            } else if (current == '\'' || current == '"' || current == '`') {
                index = Math.min(end, skipQuoted(body, index, current));
            } else if (current == '<') {
                XmlTag tag = readXmlTag(body, index);
                index = tag == null ? index + 1 : Math.min(end, tag.endIndex());
            } else if (current == '#' && startsMyBatisPlaceholder(body, index)) {
                int placeholderEnd = skipMyBatisPlaceholder(body, index);
                if (item.equals(myBatisPlaceholderProperty(body, index, placeholderEnd))) {
                    replacements.add(new TextReplacement(index, placeholderEnd, "${" + item + "}"));
                }
                index = placeholderEnd;
            } else {
                index++;
            }
        }
    }

    private String myBatisPlaceholderProperty(String body, int start, int end) {
        String content = body.substring(start + 2, end - 1).trim();
        int commaIndex = content.indexOf(',');
        if (commaIndex >= 0) {
            content = content.substring(0, commaIndex).trim();
        }
        return content;
    }

    private ScopeHavingConversion convertFirstDynamicHavingScope(String body) {
        SqlView view = sqlView(body);
        List<SelectScope> scopes = selectScopes(view.text());
        for (int i = scopes.size() - 1; i >= 0; i--) {
            SelectScope scope = scopes.get(i);
            ScopeHavingConversion havingConversion = convertRegularHavingInScope(body, view.text(), scope);
            if (havingConversion.changed()) {
                return havingConversion;
            }
            ScopeHavingConversion trimConversion = convertTrimHavingInScope(body, scope);
            if (trimConversion.changed()) {
                return trimConversion;
            }
        }
        return ScopeHavingConversion.unchanged(body);
    }

    private ScopeHavingConversion convertRegularHavingInScope(String body, String view, SelectScope scope) {
        if (scope.havingIndex() < 0) {
            return ScopeHavingConversion.unchanged(body);
        }
        if (scope.groupIndex() < 0) {
            return convertUngroupedHavingInScope(body, view, scope);
        }
        String selectList = body.substring(scope.selectIndex() + "SELECT".length(), scope.fromIndex());
        Map<String, SelectAlias> aggregateAliases = aggregateSelectAliases(selectList);
        Map<String, DynamicAggregateAlias> dynamicAggregateAliases = dynamicAggregateSelectAliases(selectList);
        Map<String, SelectAlias> selectAliases = nonAggregateSelectAliases(selectList);
        int havingStart = scope.havingIndex() + "HAVING".length();
        String havingContent = body.substring(havingStart, scope.havingEnd());
        int groupStart = scope.groupIndex() + "GROUP BY".length();
        String groupByContent = body.substring(groupStart, scope.havingIndex());
        DynamicHavingAliasRewrite dynamicAliasRewrite = rewriteDynamicAggregateHavingAlias(
                body,
                scope,
                havingContent,
                aggregateAliases,
                dynamicAggregateAliases
        );
        if (dynamicAliasRewrite.changed()) {
            return new ScopeHavingConversion(
                    body.substring(0, scope.havingIndex())
                            + dynamicAliasRewrite.text()
                            + body.substring(scope.havingEnd()),
                    dynamicAliasRewrite.appliedRules(),
                    true
            );
        }
        TextRewrite aggregateAliasRewrite = replaceAggregateAliases(havingContent, aggregateAliases);
        HavingRewrite havingRewrite = rewriteHavingContent(
                havingContent,
                aggregateAliasRewrite.text(),
                aggregateAliases,
                dynamicAggregateAliases.keySet(),
                selectAliases
        );
        TextRewrite selectAliasRewrite = replaceSelectAliases(havingRewrite.remainingHaving(), selectAliases);
        boolean aggregateAliasChanged = aggregateAliasRewrite.changed();
        boolean selectAliasChanged = selectAliasRewrite.changed();
        Map<String, SelectAlias> havingSelectAliases = selectAliasChanged
                ? referencedAliases(havingRewrite.remainingHaving(), selectAliases, true)
                : Map.of();
        TextRewrite groupByAliasRewrite = selectAliasChanged
                ? replaceSelectAliases(body.substring(groupStart, scope.havingIndex()), havingSelectAliases)
                : new TextRewrite(body.substring(groupStart, scope.havingIndex()), false);
        boolean groupByAliasChanged = groupByAliasRewrite.changed();
        boolean movedConditions = !havingRewrite.movedConditions().isBlank();
        if (!aggregateAliasChanged && !selectAliasChanged && !groupByAliasChanged && !movedConditions) {
            return ScopeHavingConversion.unchanged(body);
        }

        String converted = body;
        if (movedConditions) {
            String remainingHaving = selectAliasRewrite.text();
            if (remainingHaving.isBlank()) {
                converted = converted.substring(0, groupStart)
                        + groupByAliasRewrite.text()
                        + converted.substring(scope.havingEnd());
            } else {
                converted = converted.substring(0, groupStart)
                        + groupByAliasRewrite.text()
                        + converted.substring(scope.havingIndex(), havingStart)
                        + remainingHaving
                        + converted.substring(scope.havingEnd());
            }
            converted = insertMovedHavingConditions(
                    converted,
                    scope,
                    havingRewrite.movedConditions()
            );
        } else {
            converted = converted.substring(0, groupStart)
                    + groupByAliasRewrite.text()
                    + converted.substring(scope.havingIndex(), havingStart)
                    + selectAliasRewrite.text()
                    + converted.substring(scope.havingEnd());
        }

        List<String> rules = new ArrayList<>();
        if (aggregateAliasChanged) {
            rules.add(MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        }
        if (selectAliasChanged) {
            rules.add(MYBATIS_DYNAMIC_HAVING_SELECT_ALIAS_TO_EXPRESSION_RULE);
        }
        if (groupByAliasChanged) {
            rules.add(MYBATIS_DYNAMIC_GROUP_BY_SELECT_ALIAS_TO_EXPRESSION_RULE);
        }
        if (movedConditions) {
            rules.add(MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        }
        return new ScopeHavingConversion(converted, rules, true);
    }

    private ScopeHavingConversion convertUngroupedHavingInScope(
            String body,
            String view,
            SelectScope scope
    ) {
        String selectList = body.substring(scope.selectIndex() + "SELECT".length(), scope.fromIndex());
        Map<String, SelectAlias> aggregateAliases = aggregateSelectAliases(selectList);
        Map<String, DynamicAggregateAlias> dynamicAggregateAliases = dynamicAggregateSelectAliases(selectList);
        Map<String, SelectAlias> selectAliases = nonAggregateSelectAliases(selectList);
        int havingStart = scope.havingIndex() + "HAVING".length();
        String havingContent = body.substring(havingStart, scope.havingEnd());
        TextRewrite aggregateAliasRewrite = replaceAggregateAliases(havingContent, aggregateAliases);
        TextRewrite selectAliasRewrite = replaceSelectAliases(aggregateAliasRewrite.text(), selectAliases);

        boolean aggregateQuery = !aggregateAliases.isEmpty()
                || !dynamicAggregateAliases.isEmpty()
                || containsAggregateFunction(havingContent);
        if (aggregateQuery) {
            if (!aggregateAliasRewrite.changed() && !selectAliasRewrite.changed()) {
                return ScopeHavingConversion.unchanged(body);
            }
            List<String> rules = new ArrayList<>();
            if (aggregateAliasRewrite.changed()) {
                rules.add(MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
            }
            if (selectAliasRewrite.changed()) {
                rules.add(MYBATIS_DYNAMIC_HAVING_SELECT_ALIAS_TO_EXPRESSION_RULE);
            }
            return new ScopeHavingConversion(
                    body.substring(0, havingStart)
                            + selectAliasRewrite.text()
                            + body.substring(scope.havingEnd()),
                    rules,
                    true
            );
        }

        MyBatisWhereBlock whereBlock = findMyBatisWhereBlock(
                body,
                scope.fromIndex(),
                scope.havingIndex()
        );
        List<String> rules = new ArrayList<>();
        if (selectAliasRewrite.changed()) {
            rules.add(MYBATIS_DYNAMIC_HAVING_SELECT_ALIAS_TO_EXPRESSION_RULE);
        }
        rules.add(MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        if (whereBlock != null) {
            String closingIndent = indentationOfLastLine(body.substring(0, whereBlock.closingStart()));
            String conditionIndent = closingIndent + "    ";
            String condition = "and " + removeLeadingBooleanConnector(
                    selectAliasRewrite.text().strip()
            );
            String insertion = "\n"
                    + conditionIndent
                    + indentBlock(condition, conditionIndent)
                    + "\n"
                    + closingIndent;
            return new ScopeHavingConversion(
                    body.substring(0, whereBlock.closingStart())
                            + insertion
                            + body.substring(whereBlock.closingStart(), scope.havingIndex())
                            + body.substring(scope.havingEnd()),
                    rules,
                    true
            );
        }
        int whereIndex = findTopLevelKeyword(
                view,
                "WHERE",
                scope.fromIndex() + "FROM".length(),
                scope.havingIndex(),
                scope.depth()
        );
        String connector = whereIndex >= 0 ? "AND" : "WHERE";
        return new ScopeHavingConversion(
                body.substring(0, scope.havingIndex())
                        + connector
                        + selectAliasRewrite.text()
                        + body.substring(scope.havingEnd()),
                rules,
                true
        );
    }

    private ScopeHavingConversion convertTrimHavingInScope(String body, SelectScope scope) {
        if (scope.groupIndex() < 0) {
            return ScopeHavingConversion.unchanged(body);
        }
        Map<String, SelectAlias> aggregateAliases = aggregateSelectAliases(
                body.substring(scope.selectIndex() + "SELECT".length(), scope.fromIndex())
        );
        if (aggregateAliases.isEmpty()) {
            return ScopeHavingConversion.unchanged(body);
        }
        TrimBlock trimBlock = firstHavingTrimBlock(body, scope.groupIndex(), scope.scopeEnd());
        if (trimBlock == null) {
            return ScopeHavingConversion.unchanged(body);
        }
        String content = body.substring(trimBlock.contentStart(), trimBlock.contentEnd());
        TextRewrite aliasRewrite = replaceAggregateAliases(content, aggregateAliases);
        if (!aliasRewrite.changed()) {
            return ScopeHavingConversion.unchanged(body);
        }
        String converted = body.substring(0, trimBlock.contentStart())
                + aliasRewrite.text()
                + body.substring(trimBlock.contentEnd());
        return new ScopeHavingConversion(
                converted,
                List.of(MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE),
                true
        );
    }

    private HavingRewrite rewriteHavingContent(
            String originalHavingContent,
            String rewrittenHavingContent,
            Map<String, SelectAlias> aggregateAliases,
            Set<String> dynamicAggregateAliases,
            Map<String, SelectAlias> selectAliases
    ) {
        List<ConditionPart> rewrittenParts = splitTopLevelAndConditions(rewrittenHavingContent);
        if (rewrittenParts.isEmpty()) {
            return new HavingRewrite(rewrittenHavingContent, "", false);
        }
        List<ConditionPart> originalParts = splitTopLevelAndConditions(originalHavingContent);
        boolean alignedParts = originalParts.size() == rewrittenParts.size();
        List<String> kept = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        for (int i = 0; i < rewrittenParts.size(); i++) {
            String rewrittenCondition = rewrittenParts.get(i).text();
            String conditionForMoveCheck = alignedParts ? originalParts.get(i).text() : rewrittenCondition;
            if (isMovableSimpleHavingCondition(
                    conditionForMoveCheck,
                    aggregateAliases,
                    dynamicAggregateAliases,
                    selectAliases
            )) {
                moved.add(rewrittenCondition);
            } else {
                kept.add(rewrittenCondition);
            }
        }
        if (moved.isEmpty()) {
            return new HavingRewrite(rewrittenHavingContent, "", false);
        }
        return new HavingRewrite(
                joinHavingConditions(kept, rewrittenHavingContent),
                joinMovedConditions(moved, false),
                true
        );
    }

    private List<ConditionPart> splitTopLevelAndConditions(String value) {
        List<ConditionPart> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        int xmlDepth = 0;
        int index = 0;
        while (index < value.length()) {
            if (value.startsWith("<!--", index)) {
                int end = value.indexOf("-->", index + "<!--".length());
                index = end < 0 ? value.length() : end + "-->".length();
            } else if (value.startsWith("<![CDATA[", index)) {
                int end = value.indexOf("]]>", index + "<![CDATA[".length());
                index = end < 0 ? value.length() : end + "]]>".length();
            } else if (value.charAt(index) == '<') {
                XmlTag tag = readXmlTag(value, index);
                if (tag == null) {
                    index++;
                } else {
                    if (!tag.selfClosing() && !"include".equalsIgnoreCase(tag.name())) {
                        xmlDepth += tag.closing() ? -1 : 1;
                        if (xmlDepth < 0) {
                            xmlDepth = 0;
                        }
                    }
                    index = tag.endIndex();
                }
            } else if (value.charAt(index) == '\'' || value.charAt(index) == '"') {
                index = skipQuoted(value, index, value.charAt(index));
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (value.charAt(index) == '(') {
                depth++;
                index++;
            } else if (value.charAt(index) == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (depth == 0 && xmlDepth == 0 && isKeywordAt(value, index, "AND")) {
                parts.add(new ConditionPart(value.substring(start, index)));
                index += "AND".length();
                start = index;
            } else {
                index++;
            }
        }
        parts.add(new ConditionPart(value.substring(start)));
        return parts;
    }

    private boolean isMovableSimpleHavingCondition(
            String condition,
            Map<String, SelectAlias> aggregateAliases,
            Set<String> dynamicAggregateAliases,
            Map<String, SelectAlias> selectAliases
    ) {
        String normalized = stripXmlMarkup(condition);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.contains("${")
                || containsAggregateFunction(normalized)
                || containsSqlFunctionCall(normalized)
                || containsKeyword(normalized, "OR")
                || containsKeyword(normalized, "SELECT")
                || containsKeyword(normalized, "EXISTS")) {
            return false;
        }
        for (String alias : aggregateAliases.keySet()) {
            if (containsKeyword(normalized, alias)) {
                return false;
            }
        }
        for (String alias : dynamicAggregateAliases) {
            if (containsKeyword(normalized, alias)) {
                return false;
            }
        }
        for (String alias : selectAliases.keySet()) {
            if (containsKeyword(normalized, alias)) {
                return false;
            }
        }
        if (containsExpandedSelectAliasExpression(normalized, selectAliases)) {
            return false;
        }
        if (!containsComparisonOperator(normalized)) {
            return false;
        }
        Set<String> conditionColumns = conditionColumnReferences(normalized);
        return !conditionColumns.isEmpty();
    }

    private DynamicHavingAliasRewrite rewriteDynamicAggregateHavingAlias(
            String body,
            SelectScope scope,
            String havingContent,
            Map<String, SelectAlias> aggregateAliases,
            Map<String, DynamicAggregateAlias> dynamicAggregateAliases
    ) {
        if (dynamicAggregateAliases.isEmpty()) {
            return DynamicHavingAliasRewrite.unchanged();
        }
        Map<String, DynamicAggregateAlias> referenced = referencedDynamicAggregateAliases(
                havingContent,
                dynamicAggregateAliases
        );
        if (referenced.size() != 1) {
            return DynamicHavingAliasRewrite.unchanged();
        }
        DynamicAggregateAlias dynamicAlias = referenced.values().iterator().next();
        String staticRewrittenHaving = replaceAggregateAliases(havingContent, aggregateAliases).text();
        String baseIndent = indentationOfLastLine(body.substring(0, scope.havingIndex()));
        String branchIndent = baseIndent + "    ";
        String conditionIndent = branchIndent + "    ";
        String trailingWhitespace = trailingWhitespace(havingContent);
        StringBuilder converted = new StringBuilder();
        converted.append("<choose>");
        for (DynamicAggregateAliasBranch branch : dynamicAlias.branches()) {
            TextRewrite branchRewrite = replaceAliases(
                    staticRewrittenHaving,
                    Map.of(identifierKey(dynamicAlias.alias()), new SelectAlias(branch.expression(), dynamicAlias.alias())),
                    false
            );
            if (!branchRewrite.changed()) {
                return DynamicHavingAliasRewrite.unchanged();
            }
            converted.append("\n")
                    .append(branchIndent)
                    .append(branch.openingTag().strip());
            converted.append("\n")
                    .append(conditionIndent)
                    .append(formatHavingCondition(branchRewrite.text(), conditionIndent));
            converted.append("\n")
                    .append(branchIndent)
                    .append(branch.closingTag().strip());
        }
        converted.append("\n").append(baseIndent).append("</choose>").append(trailingWhitespace);
        List<String> rules = new ArrayList<>();
        if (!staticRewrittenHaving.equals(havingContent)) {
            rules.add(MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        }
        rules.add(MYBATIS_DYNAMIC_HAVING_DYNAMIC_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        return new DynamicHavingAliasRewrite(converted.toString(), rules, true);
    }

    private String trailingWhitespace(String value) {
        int index = value.length();
        while (index > 0 && Character.isWhitespace(value.charAt(index - 1))) {
            index--;
        }
        return value.substring(index);
    }

    private String formatHavingCondition(String condition, String indent) {
        String stripped = stripTrailingSqlTerminator(condition.strip());
        if (stripped.isBlank()) {
            return "HAVING";
        }
        String[] lines = stripped.split("\\R", -1);
        StringBuilder formatted = new StringBuilder("HAVING ").append(lines[0].strip());
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            formatted.append("\n")
                    .append(indent)
                    .append(lines[i].strip());
        }
        return formatted.toString();
    }

    private Map<String, DynamicAggregateAlias> referencedDynamicAggregateAliases(
            String value,
            Map<String, DynamicAggregateAlias> aliases
    ) {
        if (aliases.isEmpty()) {
            return Map.of();
        }
        Map<String, DynamicAggregateAlias> referenced = new LinkedHashMap<>();
        int index = 0;
        while (index < value.length()) {
            if (value.startsWith("<!--", index)) {
                int end = value.indexOf("-->", index + "<!--".length());
                index = end < 0 ? value.length() : end + "-->".length();
            } else if (value.startsWith("<![CDATA[", index)) {
                int end = value.indexOf("]]>", index + "<![CDATA[".length());
                index = end < 0 ? value.length() : end + "]]>".length();
            } else if (value.charAt(index) == '<') {
                XmlTag tag = readXmlTag(value, index);
                index = tag == null ? index + 1 : tag.endIndex();
            } else if (value.charAt(index) == '\'' || value.charAt(index) == '"') {
                index = skipQuoted(value, index, value.charAt(index));
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (isIdentifierStart(value.charAt(index))) {
                IdentifierToken token = readIdentifierToken(value, index);
                if (token == null) {
                    index++;
                    continue;
                }
                DynamicAggregateAlias alias = aliases.get(identifierKey(token.text()));
                if (alias != null) {
                    referenced.putIfAbsent(identifierKey(token.text()), alias);
                }
                index = token.endIndex();
            } else {
                index++;
            }
        }
        return referenced;
    }

    private boolean containsExpandedSelectAliasExpression(String condition, Map<String, SelectAlias> selectAliases) {
        if (selectAliases.isEmpty()) {
            return false;
        }
        String comparableCondition = compactSqlForAliasComparison(condition);
        for (SelectAlias selectAlias : selectAliases.values()) {
            String expression = selectAlias.expression();
            if (!isComputedSelectAliasExpression(expression) || containsAggregateFunction(expression)) {
                continue;
            }
            String comparableExpression = compactSqlForAliasComparison(expression);
            if (!comparableExpression.isBlank() && comparableCondition.contains(comparableExpression)) {
                return true;
            }
        }
        return false;
    }

    private boolean isComputedSelectAliasExpression(String expression) {
        String view = sqlView(expression == null ? "" : expression).text();
        return view.contains("(")
                || view.contains("+")
                || view.contains("-")
                || view.contains("*")
                || view.contains("/")
                || view.contains("||")
                || containsKeyword(view, "CASE")
                || containsKeyword(view, "WHEN");
    }

    private String compactSqlForAliasComparison(String value) {
        return sqlView(value == null ? "" : value).text()
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
    }

    private String stripXmlMarkup(String value) {
        StringBuilder stripped = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            if (value.startsWith("<!--", index)) {
                int end = value.indexOf("-->", index + "<!--".length());
                index = end < 0 ? value.length() : end + "-->".length();
            } else if (value.startsWith("<![CDATA[", index)) {
                int end = value.indexOf("]]>", index + "<![CDATA[".length());
                if (end < 0) {
                    stripped.append(value, index + "<![CDATA[".length(), value.length());
                    index = value.length();
                } else {
                    stripped.append(value, index + "<![CDATA[".length(), end);
                    index = end + "]]>".length();
                }
            } else if (value.charAt(index) == '<') {
                int end = findXmlTagEnd(value, index);
                index = end < 0 ? value.length() : end + 1;
                stripped.append(' ');
            } else {
                stripped.append(value.charAt(index));
                index++;
            }
        }
        return stripped.toString();
    }

    private boolean containsComparisonOperator(String value) {
        String view = sqlView(value).text();
        if (view.contains("=") || view.contains(">") || view.contains("<")) {
            return true;
        }
        return containsKeyword(view, "IN")
                || containsKeyword(view, "LIKE")
                || containsKeyword(view, "IS");
    }

    private String joinHavingConditions(List<String> conditions, String originalHavingContent) {
        if (conditions.isEmpty()) {
            return "";
        }
        List<String> normalizedConditions = conditions.stream()
                .map(condition -> removeLeadingBooleanConnector(condition).strip())
                .filter(condition -> !condition.isBlank())
                .toList();
        if (normalizedConditions.isEmpty()) {
            return "";
        }
        if (firstHavingConditionStartsOnSameLine(originalHavingContent)) {
            return " " + String.join(" AND ", normalizedConditions);
        }
        String indent = indentationOfFirstContentLine(originalHavingContent);
        StringBuilder joined = new StringBuilder();
        for (String condition : normalizedConditions) {
            if (joined.isEmpty()) {
                joined.append("\n").append(indent).append(condition);
            } else {
                joined.append("\n").append(indent).append("AND ").append(condition);
            }
        }
        return joined.toString();
    }

    private boolean firstHavingConditionStartsOnSameLine(String value) {
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r' || current == '\n') {
                return false;
            }
            if (!Character.isWhitespace(current)) {
                return true;
            }
            index++;
        }
        return false;
    }

    private String joinMovedConditions(List<String> conditions, boolean prefixAnd) {
        StringBuilder joined = new StringBuilder();
        for (String condition : conditions) {
            String normalized = stripTrailingSqlTerminator(removeLeadingBooleanConnector(condition).strip());
            if (normalized.isBlank()) {
                continue;
            }
            if (!joined.isEmpty()) {
                joined.append("\nand ");
            } else if (prefixAnd) {
                joined.append("and ");
            }
            joined.append(normalized);
        }
        return joined.toString();
    }

    private String stripTrailingSqlTerminator(String value) {
        String stripped = value.stripTrailing();
        if (stripped.endsWith(";")) {
            return stripped.substring(0, stripped.length() - 1).stripTrailing();
        }
        return stripped;
    }

    private String insertMovedHavingConditions(
            String body,
            SelectScope originalScope,
            String movedConditions
    ) {
        SqlView currentView = sqlView(body);
        List<SelectScope> scopes = selectScopes(currentView.text());
        SelectScope scope = matchingScope(scopes, originalScope.selectIndex());
        if (scope == null || scope.groupIndex() < 0) {
            return body;
        }
        MyBatisWhereBlock whereBlock = findMyBatisWhereBlock(body, scope.fromIndex(), scope.groupIndex());
        if (whereBlock != null) {
            String indent = indentationOfLastLine(body.substring(0, whereBlock.closingStart()));
            String insertion = "\n" + indent + indentBlock(joinMovedConditions(List.of(movedConditions), true), indent);
            return body.substring(0, whereBlock.closingStart())
                    + insertion
                    + "\n"
                    + indent
                    + body.substring(whereBlock.closingStart());
        }

        int whereIndex = findTopLevelKeyword(currentView.text(), "WHERE", scope.fromIndex(), scope.groupIndex(), scope.depth());
        if (whereIndex >= 0) {
            String indent = indentationOfLastLine(body.substring(0, scope.groupIndex()));
            String insertion = "\n" + indent + indentBlock(joinMovedConditions(List.of(movedConditions), true), indent);
            return body.substring(0, scope.groupIndex())
                    + insertion
                    + "\n"
                    + indent
                    + body.substring(scope.groupIndex());
        }

        String groupIndent = indentationOfLastLine(body.substring(0, scope.groupIndex()));
        String conditionIndent = groupIndent + "    ";
        String insertion = "\n"
                + groupIndent
                + "WHERE\n"
                + conditionIndent
                + indentBlock(removeLeadingBooleanConnector(movedConditions).strip(), conditionIndent);
        return body.substring(0, scope.groupIndex()) + insertion + "\n" + groupIndent + body.substring(scope.groupIndex());
    }

    private SelectScope matchingScope(List<SelectScope> scopes, int originalSelectIndex) {
        for (SelectScope scope : scopes) {
            if (scope.selectIndex() == originalSelectIndex) {
                return scope;
            }
        }
        return null;
    }

    private String indentBlock(String block, String indent) {
        String[] lines = block.split("\\R", -1);
        StringBuilder indented = new StringBuilder(block.length() + lines.length * indent.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                indented.append("\n").append(indent);
            }
            indented.append(lines[i].stripTrailing());
        }
        return indented.toString();
    }

    private String indentationOfFirstContentLine(String value) {
        String[] lines = value.split("\\R", -1);
        for (String line : lines) {
            if (!line.isBlank()) {
                return indentationOfLine(line);
            }
        }
        return indentationOfLastLine(value);
    }

    private String indentationOfLine(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return line.substring(0, index);
    }

    private String removeLeadingBooleanConnector(String value) {
        String stripped = value == null ? "" : value.stripLeading();
        if (startsWithKeyword(stripped, "AND")) {
            return stripped.substring("AND".length()).stripLeading();
        }
        if (startsWithKeyword(stripped, "OR")) {
            return stripped.substring("OR".length()).stripLeading();
        }
        return stripped;
    }

    private MyBatisWhereBlock findMyBatisWhereBlock(String body, int start, int end) {
        int index = start;
        while (index >= 0 && index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                return null;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && "where".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "where", end);
                if (closingStart < 0) {
                    return null;
                }
                int closingEnd = body.indexOf('>', closingStart + 1);
                return new MyBatisWhereBlock(tagStart, tag.endIndex(), closingStart, closingEnd < 0 ? closingStart : closingEnd + 1);
            }
            index = tag.endIndex();
        }
        return null;
    }

    private int findClosingTag(String body, int start, String tagName, int end) {
        int index = start;
        int depth = 1;
        while (index >= 0 && index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                return -1;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (tagName.equalsIgnoreCase(tag.name())) {
                if (tag.closing()) {
                    depth--;
                    if (depth == 0) {
                        return tagStart;
                    }
                } else if (!tag.selfClosing()) {
                    depth++;
                }
            }
            index = tag.endIndex();
        }
        return -1;
    }

    private TrimBlock firstHavingTrimBlock(String body, int start, int end) {
        int index = start;
        while (index >= 0 && index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                return null;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && "trim".equalsIgnoreCase(tag.name())) {
                String prefix = xmlAttribute(body.substring(tagStart, tag.endIndex()), "prefix");
                if ("HAVING".equalsIgnoreCase(prefix)) {
                    int closingStart = findClosingTag(body, tag.endIndex(), "trim", end);
                    if (closingStart < 0) {
                        return null;
                    }
                    int closingEnd = body.indexOf('>', closingStart + 1);
                    return new TrimBlock(tagStart, tag.endIndex(), closingStart, closingEnd < 0 ? closingStart : closingEnd + 1);
                }
            }
            index = tag.endIndex();
        }
        return null;
    }

    private TextRewrite replaceAggregateAliases(String value, Map<String, SelectAlias> aggregateAliases) {
        return replaceAliases(value, aggregateAliases, false);
    }

    private TextRewrite replaceSelectAliases(String value, Map<String, SelectAlias> selectAliases) {
        return replaceAliases(value, selectAliases, true);
    }

    private Map<String, SelectAlias> referencedAliases(
            String value,
            Map<String, SelectAlias> aliases,
            boolean allowFunctionArgumentExactMatch
    ) {
        if (aliases.isEmpty()) {
            return Map.of();
        }
        Map<String, SelectAlias> referenced = new LinkedHashMap<>();
        int index = 0;
        while (index < value.length()) {
            if (value.startsWith("<!--", index)) {
                int end = value.indexOf("-->", index + "<!--".length());
                index = end < 0 ? value.length() : end + "-->".length();
            } else if (value.startsWith("<![CDATA[", index)) {
                int end = value.indexOf("]]>", index + "<![CDATA[".length());
                index = end < 0 ? value.length() : end + "]]>".length();
            } else if (value.charAt(index) == '<') {
                XmlTag tag = readXmlTag(value, index);
                index = tag == null ? index + 1 : tag.endIndex();
            } else if (value.charAt(index) == '\'' || value.charAt(index) == '"') {
                index = skipQuoted(value, index, value.charAt(index));
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (isIdentifierStart(value.charAt(index))) {
                IdentifierToken token = readIdentifierToken(value, index);
                if (token == null) {
                    index++;
                    continue;
                }
                String key = identifierKey(token.text());
                SelectAlias alias = aliases.get(key);
                if (alias != null && shouldReplaceAliasToken(value, index, token, alias,
                        allowFunctionArgumentExactMatch)) {
                    referenced.putIfAbsent(key, alias);
                }
                index = token.endIndex();
            } else {
                index++;
            }
        }
        return referenced;
    }

    private TextRewrite replaceAliases(
            String value,
            Map<String, SelectAlias> aliases,
            boolean allowFunctionArgumentExactMatch
    ) {
        if (aliases.isEmpty()) {
            return new TextRewrite(value, false);
        }
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < value.length()) {
            if (value.startsWith("<!--", index)) {
                int end = value.indexOf("-->", index + "<!--".length());
                index = end < 0 ? value.length() : end + "-->".length();
            } else if (value.startsWith("<![CDATA[", index)) {
                int end = value.indexOf("]]>", index + "<![CDATA[".length());
                index = end < 0 ? value.length() : end + "]]>".length();
            } else if (value.charAt(index) == '<') {
                XmlTag tag = readXmlTag(value, index);
                index = tag == null ? index + 1 : tag.endIndex();
            } else if (value.charAt(index) == '\'' || value.charAt(index) == '"') {
                index = skipQuoted(value, index, value.charAt(index));
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (isIdentifierStart(value.charAt(index))) {
                IdentifierToken token = readIdentifierToken(value, index);
                if (token == null) {
                    index++;
                    continue;
                }
                SelectAlias alias = aliases.get(identifierKey(token.text()));
                if (alias != null && shouldReplaceAliasToken(value, index, token, alias,
                        allowFunctionArgumentExactMatch)) {
                    replacements.add(new TextReplacement(index, token.endIndex(), "(" + alias.expression() + ")"));
                }
                index = token.endIndex();
            } else {
                index++;
            }
        }
        return applyTextReplacements(value, replacements);
    }

    private boolean shouldReplaceAliasToken(
            String value,
            int index,
            IdentifierToken token,
            SelectAlias alias,
            boolean allowFunctionArgumentExactMatch
    ) {
        char previous = previousNonWhitespace(value, index);
        char next = nextNonWhitespace(value, token.endIndex());
        if (previous == '.' || next == '(') {
            return false;
        }
        return previous != '(' || (allowFunctionArgumentExactMatch && token.text().equals(alias.alias()));
    }

    private TextRewrite applyTextReplacements(String value, List<TextReplacement> replacements) {
        if (replacements.isEmpty()) {
            return new TextRewrite(value, false);
        }
        StringBuilder converted = new StringBuilder(value.length());
        int index = 0;
        for (TextReplacement replacement : replacements) {
            converted.append(value, index, replacement.startIndex());
            converted.append(replacement.replacement());
            index = replacement.endIndex();
        }
        converted.append(value, index, value.length());
        return new TextRewrite(converted.toString(), true);
    }

    private Map<String, SelectAlias> aggregateSelectAliases(String selectList) {
        Map<String, SelectAlias> aliases = new LinkedHashMap<>();
        for (String item : splitTopLevelComma(selectList)) {
            SelectAlias selectAlias = selectAlias(item);
            if (selectAlias != null && containsAggregateFunction(selectAlias.expression())) {
                aliases.putIfAbsent(identifierKey(selectAlias.alias()), selectAlias);
            }
        }
        return aliases;
    }

    private Map<String, DynamicAggregateAlias> dynamicAggregateSelectAliases(String selectList) {
        Map<String, DynamicAggregateAlias> aliases = new LinkedHashMap<>();
        for (String item : splitTopLevelComma(selectList)) {
            DynamicAggregateAlias alias = dynamicAggregateSelectAlias(item);
            if (alias != null) {
                aliases.putIfAbsent(identifierKey(alias.alias()), alias);
            }
        }
        addTopLevelDynamicAggregateChooseAliases(selectList, aliases);
        return aliases;
    }

    private void addTopLevelDynamicAggregateChooseAliases(
            String selectList,
            Map<String, DynamicAggregateAlias> aliases
    ) {
        int index = 0;
        while (index < selectList.length()) {
            int chooseStart = selectList.indexOf("<choose", index);
            if (chooseStart < 0) {
                return;
            }
            XmlTag chooseTag = readXmlTag(selectList, chooseStart);
            if (chooseTag == null || chooseTag.closing() || chooseTag.selfClosing()
                    || !"choose".equalsIgnoreCase(chooseTag.name())) {
                index = chooseStart + "<choose".length();
                continue;
            }
            int chooseClosingStart = findClosingTag(selectList, chooseTag.endIndex(), "choose", selectList.length());
            if (chooseClosingStart < 0) {
                return;
            }
            int chooseEnd = closingTagEnd(selectList, chooseClosingStart);
            DynamicAggregateAlias alias = dynamicAggregateSelectAlias(selectList.substring(chooseStart, chooseEnd));
            if (alias != null) {
                aliases.putIfAbsent(identifierKey(alias.alias()), alias);
            }
            index = chooseEnd;
        }
    }

    private DynamicAggregateAlias dynamicAggregateSelectAlias(String selectItem) {
        String value = selectItem == null ? "" : selectItem;
        int chooseStart = firstNonWhitespaceIndex(value);
        if (chooseStart < 0 || chooseStart >= value.length() || value.charAt(chooseStart) != '<') {
            return null;
        }
        XmlTag chooseTag = readXmlTag(value, chooseStart);
        if (chooseTag == null || chooseTag.closing() || chooseTag.selfClosing()
                || !"choose".equalsIgnoreCase(chooseTag.name())) {
            return null;
        }
        int chooseClosingStart = findClosingTag(value, chooseTag.endIndex(), "choose", value.length());
        if (chooseClosingStart < 0 || !value.substring(0, chooseStart).isBlank()
                || !value.substring(closingTagEnd(value, chooseClosingStart)).isBlank()) {
            return null;
        }
        List<DynamicAggregateAliasBranch> branches = new ArrayList<>();
        String alias = "";
        int index = chooseTag.endIndex();
        while (index < chooseClosingStart) {
            int nextTagStart = nextXmlTagStart(value, index, chooseClosingStart);
            if (nextTagStart < 0) {
                if (!value.substring(index, chooseClosingStart).isBlank()) {
                    return null;
                }
                break;
            }
            if (!value.substring(index, nextTagStart).isBlank()) {
                return null;
            }
            XmlTag branchTag = readXmlTag(value, nextTagStart);
            if (branchTag == null || branchTag.closing() || branchTag.selfClosing()
                    || (!"when".equalsIgnoreCase(branchTag.name())
                    && !"otherwise".equalsIgnoreCase(branchTag.name()))) {
                return null;
            }
            int branchClosingStart = findClosingTag(value, branchTag.endIndex(), branchTag.name(), chooseClosingStart);
            if (branchClosingStart < 0) {
                return null;
            }
            String branchBody = value.substring(branchTag.endIndex(), branchClosingStart);
            SelectAlias branchAlias = selectAliasAllowingSqlFragments(branchBody);
            if (branchAlias == null || !containsAggregateFunction(branchAlias.expression())) {
                return null;
            }
            if (alias.isBlank()) {
                alias = branchAlias.alias();
            } else if (!identifierKey(alias).equals(identifierKey(branchAlias.alias()))) {
                return null;
            }
            branches.add(new DynamicAggregateAliasBranch(
                    value.substring(nextTagStart, branchTag.endIndex()),
                    branchAlias.expression(),
                    value.substring(branchClosingStart, closingTagEnd(value, branchClosingStart))
            ));
            index = closingTagEnd(value, branchClosingStart);
        }
        if (alias.isBlank() || branches.isEmpty()) {
            return null;
        }
        return new DynamicAggregateAlias(alias, branches);
    }

    private int firstNonWhitespaceIndex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private int nextXmlTagStart(String value, int start, int end) {
        int index = start;
        while (index < end) {
            int tagStart = value.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                return -1;
            }
            if (value.startsWith("<!--", tagStart)) {
                int commentEnd = value.indexOf("-->", tagStart + "<!--".length());
                if (commentEnd < 0 || commentEnd >= end) {
                    return -1;
                }
                if (!value.substring(start, tagStart).isBlank()) {
                    return tagStart;
                }
                start = commentEnd + "-->".length();
                index = start;
            } else {
                return tagStart;
            }
        }
        return -1;
    }

    private int closingTagEnd(String value, int closingStart) {
        int tagEnd = findXmlTagEnd(value, closingStart);
        return tagEnd < 0 ? closingStart : tagEnd + 1;
    }

    private Map<String, SelectAlias> nonAggregateSelectAliases(String selectList) {
        Map<String, SelectAlias> aliases = new LinkedHashMap<>();
        for (String item : splitTopLevelComma(selectList)) {
            SelectAlias selectAlias = selectAlias(item);
            if (selectAlias != null && !containsAggregateFunction(selectAlias.expression())) {
                aliases.putIfAbsent(identifierKey(selectAlias.alias()), selectAlias);
            }
        }
        return aliases;
    }

    private SelectAlias selectAlias(String selectItem) {
        String trimmed = selectItem == null ? "" : selectItem.trim();
        if (trimmed.isBlank() || trimmed.contains("<")) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?is)^(.+?)\\s+(?:AS\\s+)?(" + DM_IDENTIFIER + ")\\s*$")
                .matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        String expression = matcher.group(1).trim();
        String alias = matcher.group(2).trim();
        if (expression.isBlank()
                || alias.isBlank()
                || isSqlClauseKeyword(alias)) {
            return null;
        }
        return new SelectAlias(expression, alias);
    }

    private SelectAlias selectAliasAllowingSqlFragments(String selectItem) {
        String trimmed = stripTrailingSelectItemComma(selectItem == null ? "" : selectItem.trim());
        if (trimmed.isBlank() || containsUnsupportedAliasXmlTag(trimmed)) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?is)^(.+?)\\s+(?:AS\\s+)?(" + DM_IDENTIFIER + ")\\s*$")
                .matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        String expression = matcher.group(1).trim();
        String alias = matcher.group(2).trim();
        if (expression.isBlank()
                || alias.isBlank()
                || isSqlClauseKeyword(alias)) {
            return null;
        }
        return new SelectAlias(expression, alias);
    }

    private String stripTrailingSelectItemComma(String value) {
        String trimmed = value == null ? "" : value.stripTrailing();
        if (trimmed.endsWith(",")) {
            return trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return trimmed;
    }

    private boolean containsUnsupportedAliasXmlTag(String value) {
        int index = 0;
        while (index < value.length()) {
            if (value.startsWith("<!--", index)) {
                int end = value.indexOf("-->", index + "<!--".length());
                index = end < 0 ? value.length() : end + "-->".length();
            } else if (value.startsWith("<![CDATA[", index)) {
                int end = value.indexOf("]]>", index + "<![CDATA[".length());
                index = end < 0 ? value.length() : end + "]]>".length();
            } else if (value.charAt(index) == '<') {
                XmlTag tag = readXmlTag(value, index);
                if (tag == null || !"include".equalsIgnoreCase(tag.name()) || !tag.selfClosing()) {
                    return true;
                }
                index = tag.endIndex();
            } else if (value.charAt(index) == '\'' || value.charAt(index) == '"') {
                index = skipQuoted(value, index, value.charAt(index));
            } else {
                index++;
            }
        }
        return false;
    }

    private boolean containsAggregateFunction(String value) {
        int index = 0;
        while (index < value.length()) {
            if (value.charAt(index) == '\'' || value.charAt(index) == '"') {
                index = skipQuoted(value, index, value.charAt(index));
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (isIdentifierStart(value.charAt(index))) {
                IdentifierToken token = readIdentifierToken(value, index);
                if (token == null) {
                    index++;
                    continue;
                }
                int afterToken = skipWhitespace(value, token.endIndex());
                if (afterToken < value.length()
                        && value.charAt(afterToken) == '('
                        && AGGREGATE_FUNCTIONS.contains(unquoteIdentifier(token.text()).toUpperCase())) {
                    return true;
                }
                index = token.endIndex();
            } else {
                index++;
            }
        }
        return false;
    }

    private boolean containsSqlFunctionCall(String value) {
        int index = 0;
        while (index < value.length()) {
            if (value.charAt(index) == '\'' || value.charAt(index) == '"') {
                index = skipQuoted(value, index, value.charAt(index));
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (isIdentifierStart(value.charAt(index))) {
                IdentifierToken token = readIdentifierToken(value, index);
                if (token == null) {
                    index++;
                    continue;
                }
                int afterToken = skipWhitespace(value, token.endIndex());
                if (afterToken < value.length() && value.charAt(afterToken) == '(') {
                    return true;
                }
                index = token.endIndex();
            } else {
                index++;
            }
        }
        return false;
    }

    private Set<String> conditionColumnReferences(String condition) {
        Set<String> references = new LinkedHashSet<>();
        int index = 0;
        while (index < condition.length()) {
            if (condition.startsWith("<!--", index)) {
                int end = condition.indexOf("-->", index + "<!--".length());
                index = end < 0 ? condition.length() : end + "-->".length();
            } else if (condition.startsWith("<![CDATA[", index)) {
                int end = condition.indexOf("]]>", index + "<![CDATA[".length());
                index = end < 0 ? condition.length() : end + "]]>".length();
            } else if (condition.charAt(index) == '<') {
                XmlTag tag = readXmlTag(condition, index);
                index = tag == null ? index + 1 : tag.endIndex();
            } else if (condition.charAt(index) == '\'' || condition.charAt(index) == '"') {
                index = skipQuoted(condition, index, condition.charAt(index));
            } else if (startsMyBatisPlaceholder(condition, index)) {
                index = skipMyBatisPlaceholder(condition, index);
            } else if (isIdentifierStart(condition.charAt(index))) {
                IdentifierToken token = readIdentifierToken(condition, index);
                if (token == null) {
                    index++;
                    continue;
                }
                if (isHavingReferenceKeyword(token.text())) {
                    index = token.endIndex();
                    continue;
                }
                int afterToken = skipWhitespace(condition, token.endIndex());
                if (afterToken < condition.length() && condition.charAt(afterToken) == '(') {
                    index = token.endIndex();
                    continue;
                }
                List<String> parts = new ArrayList<>();
                parts.add(identifierKey(token.text()));
                index = afterToken;
                while (index < condition.length() && condition.charAt(index) == '.') {
                    index = skipWhitespace(condition, index + 1);
                    IdentifierToken part = readIdentifierToken(condition, index);
                    if (part == null) {
                        break;
                    }
                    parts.add(identifierKey(part.text()));
                    index = skipWhitespace(condition, part.endIndex());
                }
                references.add(String.join(".", parts));
            } else {
                index++;
            }
        }
        return references;
    }

    private boolean isHavingReferenceKeyword(String value) {
        return Set.of(
                "AND",
                "OR",
                "NOT",
                "NULL",
                "IS",
                "IN",
                "LIKE",
                "BETWEEN",
                "TRUE",
                "FALSE",
                "CASE",
                "WHEN",
                "THEN",
                "ELSE",
                "END"
        ).contains(unquoteIdentifier(value).toUpperCase());
    }

    private List<SelectScope> selectScopes(String view) {
        List<SelectScope> scopes = new ArrayList<>();
        int depth = 0;
        int index = 0;
        while (index < view.length()) {
            char current = view.charAt(index);
            if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (isKeywordAt(view, index, "SELECT")) {
                int fromIndex = findTopLevelKeyword(view, "FROM", index + "SELECT".length(), view.length(), depth);
                if (fromIndex >= 0) {
                    int scopeEnd = findSelectEnd(view, fromIndex + "FROM".length(), depth);
                    int groupIndex = findTopLevelGroupBy(view, fromIndex + "FROM".length(), scopeEnd, depth);
                    int havingSearchStart = groupIndex < 0
                            ? fromIndex + "FROM".length()
                            : groupIndex + "GROUP BY".length();
                    int havingIndex = findTopLevelKeyword(view, "HAVING", havingSearchStart, scopeEnd, depth);
                    int havingEnd = havingIndex < 0
                            ? -1
                            : findClauseEnd(view, havingIndex + "HAVING".length(), scopeEnd, depth);
                    scopes.add(new SelectScope(index, fromIndex, groupIndex, havingIndex, havingEnd, scopeEnd, depth));
                }
                index += "SELECT".length();
            } else {
                index++;
            }
        }
        return scopes;
    }

    private int findSelectEnd(String view, int start, int targetDepth) {
        int depth = depthAt(view, start);
        int index = start;
        while (index < view.length()) {
            char current = view.charAt(index);
            if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth == targetDepth) {
                    return index;
                }
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (depth == targetDepth && isKeywordAt(view, index, "UNION")) {
                return index;
            } else {
                index++;
            }
        }
        return view.length();
    }

    private int findClauseEnd(String view, int start, int end, int targetDepth) {
        int depth = depthAt(view, start);
        int index = start;
        while (index < end) {
            char current = view.charAt(index);
            if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth == targetDepth) {
                    return index;
                }
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (depth == targetDepth
                    && (isKeywordAt(view, index, "ORDER")
                    || isKeywordAt(view, index, "LIMIT")
                    || isKeywordAt(view, index, "OFFSET")
                    || isKeywordAt(view, index, "FETCH")
                    || isKeywordAt(view, index, "UNION"))) {
                return index;
            } else {
                index++;
            }
        }
        return end;
    }

    private int findTopLevelKeyword(String view, String keyword, int start, int end, int targetDepth) {
        int depth = depthAt(view, start);
        int index = start;
        while (index < end) {
            char current = view.charAt(index);
            if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (depth == targetDepth && isKeywordAt(view, index, keyword)) {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private int findTopLevelGroupBy(String view, int start, int end, int targetDepth) {
        int depth = depthAt(view, start);
        int index = start;
        while (index < end) {
            char current = view.charAt(index);
            if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (depth == targetDepth && isKeywordAt(view, index, "GROUP")) {
                int afterGroup = skipWhitespace(view, index + "GROUP".length());
                if (isKeywordAt(view, afterGroup, "BY")) {
                    return index;
                }
                index += "GROUP".length();
            } else {
                index++;
            }
        }
        return -1;
    }

    private int depthAt(String view, int end) {
        int depth = 0;
        int index = 0;
        while (index < end && index < view.length()) {
            char current = view.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')' && depth > 0) {
                depth--;
            }
            index++;
        }
        return depth;
    }

    private SqlView sqlView(String value) {
        char[] chars = value.toCharArray();
        int index = 0;
        while (index < chars.length) {
            if (value.startsWith("<!--", index)) {
                int end = value.indexOf("-->", index + "<!--".length());
                int next = end < 0 ? chars.length : end + "-->".length();
                maskRange(chars, index, next);
                index = next;
            } else if (value.startsWith("<![CDATA[", index)) {
                int contentStart = index + "<![CDATA[".length();
                int end = value.indexOf("]]>", contentStart);
                maskRange(chars, index, contentStart);
                if (end < 0) {
                    index = chars.length;
                } else {
                    maskRange(chars, end, end + "]]>".length());
                    index = end + "]]>".length();
                }
            } else if (value.startsWith("--", index)) {
                int end = value.indexOf('\n', index + 2);
                int next = end < 0 ? chars.length : end;
                maskRange(chars, index, next);
                index = next;
            } else if (value.startsWith("/*", index)) {
                int end = value.indexOf("*/", index + 2);
                int next = end < 0 ? chars.length : end + 2;
                maskRange(chars, index, next);
                index = next;
            } else if (startsMyBatisPlaceholder(value, index)) {
                int next = skipMyBatisPlaceholder(value, index);
                maskRange(chars, index, next);
                index = next;
            } else if (chars[index] == '\'' || chars[index] == '"') {
                int next = skipQuoted(value, index, chars[index]);
                maskRange(chars, index, next);
                index = next;
            } else if (chars[index] == '<') {
                XmlTag tag = readXmlTag(value, index);
                if (tag == null) {
                    index++;
                } else {
                    maskRange(chars, index, tag.endIndex());
                    index = tag.endIndex();
                }
            } else {
                index++;
            }
        }
        return new SqlView(new String(chars));
    }

    private void maskRange(char[] chars, int start, int end) {
        for (int i = start; i < end && i < chars.length; i++) {
            if (chars[i] != '\n' && chars[i] != '\r') {
                chars[i] = ' ';
            }
        }
    }

    private XmlTag readXmlTag(String value, int start) {
        if (start >= value.length() || value.charAt(start) != '<') {
            return null;
        }
        if (value.startsWith("<!--", start) || value.startsWith("<![CDATA[", start)) {
            return null;
        }
        int end = findXmlTagEnd(value, start);
        if (end < 0) {
            return null;
        }
        int nameStart = start + 1;
        boolean closing = false;
        if (nameStart < end && value.charAt(nameStart) == '/') {
            closing = true;
            nameStart++;
        }
        while (nameStart < end && Character.isWhitespace(value.charAt(nameStart))) {
            nameStart++;
        }
        int nameEnd = nameStart;
        while (nameEnd < end) {
            char current = value.charAt(nameEnd);
            if (!Character.isLetterOrDigit(current) && current != '_' && current != '-') {
                break;
            }
            nameEnd++;
        }
        if (nameEnd == nameStart) {
            return null;
        }
        String tagText = value.substring(start, end + 1).stripTrailing();
        return new XmlTag(
                value.substring(nameStart, nameEnd),
                closing,
                tagText.endsWith("/>"),
                end + 1
        );
    }

    private int findXmlTagEnd(String value, int start) {
        char quote = '\0';
        int index = start + 1;
        while (index < value.length()) {
            char current = value.charAt(index);
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

    private boolean containsKeyword(String value, String keyword) {
        int index = 0;
        while (index < value.length()) {
            if (value.charAt(index) == '\'' || value.charAt(index) == '"') {
                index = skipQuoted(value, index, value.charAt(index));
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (isKeywordAt(value, index, keyword)) {
                return true;
            } else {
                index++;
            }
        }
        return false;
    }

    private boolean startsWithKeyword(String value, String keyword) {
        return isKeywordAt(value, 0, keyword);
    }

    private boolean isKeywordAt(String value, int index, String keyword) {
        if (index < 0 || index + keyword.length() > value.length()) {
            return false;
        }
        if (!value.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        int before = index - 1;
        int after = index + keyword.length();
        return (before < 0 || !isIdentifierPart(value.charAt(before)))
                && (after >= value.length() || !isIdentifierPart(value.charAt(after)));
    }

    private IdentifierToken readIdentifierToken(String value, int start) {
        if (start >= value.length()) {
            return null;
        }
        char current = value.charAt(start);
        if (current == '"' || current == '`') {
            int end = skipQuoted(value, start, current);
            if (end <= start + 1 || end > value.length()) {
                return null;
            }
            return new IdentifierToken(value.substring(start, end), end);
        }
        if (!isIdentifierStart(current)) {
            return null;
        }
        int index = start + 1;
        while (index < value.length() && isIdentifierPart(value.charAt(index))) {
            index++;
        }
        return new IdentifierToken(value.substring(start, index), index);
    }

    private boolean isIdentifierStart(char current) {
        return Character.isLetter(current) || current == '_';
    }

    private boolean isIdentifierPart(char current) {
        return Character.isLetterOrDigit(current) || current == '_' || current == '$';
    }

    private char previousNonWhitespace(String value, int index) {
        int previous = index - 1;
        while (previous >= 0 && Character.isWhitespace(value.charAt(previous))) {
            previous--;
        }
        return previous < 0 ? '\0' : value.charAt(previous);
    }

    private char nextNonWhitespace(String value, int index) {
        int next = index;
        while (next < value.length() && Character.isWhitespace(value.charAt(next))) {
            next++;
        }
        return next >= value.length() ? '\0' : value.charAt(next);
    }

    private int skipWhitespace(String value, int index) {
        int current = index;
        while (current < value.length() && Character.isWhitespace(value.charAt(current))) {
            current++;
        }
        return current;
    }

    private String identifierKey(String identifier) {
        return unquoteIdentifier(identifier).toLowerCase();
    }

    private boolean isSqlClauseKeyword(String value) {
        return Set.of(
                "FROM",
                "WHERE",
                "GROUP",
                "HAVING",
                "ORDER",
                "LIMIT",
                "OFFSET",
                "FETCH",
                "UNION",
                "JOIN",
                "ON"
        ).contains(unquoteIdentifier(value).toUpperCase());
    }

    private String convertForeachOnDuplicateKeyUpdate(
            String body,
            String statementKey,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        ForeachBlock foreach = readForeach(body);
        if (foreach == null || !";".equals(foreach.separator())) {
            return body;
        }
        String tableName = extractInsertTableName(foreach.body());
        List<String> keyColumns = rewriteConfig.keyColumnsFor(statementKey, tableName);
        if (keyColumns.isEmpty()) {
            return body;
        }
        SqlConversionResult conversion = sqlConverter.convert(
                restoreDoubleQuotedIdentifiersForSqlConverter(foreach.body()),
                keyColumns
        );
        if (conversion.manualReviewRequired() || !conversion.changed()) {
            return body;
        }
        return foreach.withBody(conversion.convertedSql()).toXml();
    }

    private String restoreDoubleQuotedIdentifiersForSqlConverter(String sql) {
        StringBuilder restored = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                int next = appendQuoted(sql, index, restored, '\'');
                index = next;
            } else if (current == '"') {
                QuotedText quoted = readQuotedText(sql, index, '"');
                if (!quoted.closed()) {
                    restored.append(sql, index, sql.length());
                    index = sql.length();
                } else {
                    restored.append('`').append(quoted.value().replace("`", "``")).append('`');
                    index = quoted.nextIndex();
                    changed = true;
                }
            } else {
                restored.append(current);
                index++;
            }
        }
        return changed ? restored.toString() : sql;
    }

    private String convertBatchOnDuplicateKeyUpdate(String body, String statementKey, SqlRewriteConfig rewriteConfig) {
        IfWrapper wrapper = readWrappingIf(body);
        String candidate = wrapper == null ? body : wrapper.body();
        Matcher matcher = BATCH_ON_DUPLICATE_KEY_UPDATE_PATTERN.matcher(candidate);
        BatchInsertValues batchInsertValues;
        if (matcher.matches()) {
            batchInsertValues = new BatchInsertValues(
                    matcher.group("leading"),
                    matcher.group("table"),
                    matcher.group("columns"),
                    matcher.group("foreach"),
                    matcher.group("updates"),
                    matcher.group("trailing")
            );
        } else {
            Matcher trimMatcher = BATCH_ON_DUPLICATE_KEY_UPDATE_TRIM_COLUMNS_PATTERN.matcher(candidate);
            if (!trimMatcher.matches()) {
                Matcher trimValuesMatcher = ON_DUPLICATE_TRIM_COLUMNS_VALUES_PATTERN.matcher(candidate);
                if (!trimValuesMatcher.matches()) {
                    return body;
                }
                String columns = trimColumnList(trimValuesMatcher.group("columnsTrim"));
                String values = trimValuesList(trimValuesMatcher.group("valuesTrim"));
                if (columns == null || values == null) {
                    return body;
                }
                List<String> keyColumns = rewriteConfig.keyColumnsFor(statementKey, trimValuesMatcher.group("table"));
                if (keyColumns.isEmpty()) {
                    return body;
                }
                String converted = convertConditionalTrimOnDuplicateKeyUpdateToMerge(
                        trimValuesMatcher.group("leading"),
                        trimValuesMatcher.group("table"),
                        columns,
                        values,
                        trimValuesMatcher.group("updates"),
                        keyColumns,
                        trimValuesMatcher.group("trailing")
                );
                if (converted == null || converted.equals(candidate)) {
                    return body;
                }
                return wrapper == null ? converted : wrapper.wrap(converted);
            }
            String columns = trimColumnList(trimMatcher.group("columnsTrim"));
            if (columns == null) {
                return body;
            }
            batchInsertValues = new BatchInsertValues(
                    trimMatcher.group("leading"),
                    trimMatcher.group("table"),
                    columns,
                    trimMatcher.group("foreach"),
                    trimMatcher.group("updates"),
                    trimMatcher.group("trailing")
            );
        }
        List<String> keyColumns = rewriteConfig.keyColumnsFor(statementKey, batchInsertValues.table());
        if (keyColumns.isEmpty()) {
            return body;
        }
        String converted = convertBatchInsertValuesToMerge(
                batchInsertValues.leading(),
                batchInsertValues.table(),
                batchInsertValues.columns(),
                batchInsertValues.foreach(),
                batchInsertValues.updates(),
                keyColumns,
                batchInsertValues.trailing()
        );
        if (converted == null || converted.equals(candidate)) {
            return body;
        }
        return wrapper == null ? converted : wrapper.wrap(converted);
    }

    private String convertBatchInsertIgnore(String body, String statementKey, SqlRewriteConfig rewriteConfig) {
        IfWrapper wrapper = readWrappingIf(body);
        String candidate = wrapper == null ? body : wrapper.body();
        Matcher matcher = BATCH_INSERT_IGNORE_PATTERN.matcher(candidate);
        BatchInsertValues batchInsertValues;
        if (matcher.matches()) {
            batchInsertValues = new BatchInsertValues(
                    matcher.group("leading"),
                    matcher.group("table"),
                    matcher.group("columns"),
                    matcher.group("foreach"),
                    "",
                    matcher.group("trailing")
            );
        } else {
            Matcher trimMatcher = BATCH_INSERT_IGNORE_TRIM_COLUMNS_PATTERN.matcher(candidate);
            if (trimMatcher.matches()) {
                String columns = trimColumnList(trimMatcher.group("columnsTrim"));
                if (columns == null) {
                    return body;
                }
                batchInsertValues = new BatchInsertValues(
                        trimMatcher.group("leading"),
                        trimMatcher.group("table"),
                        columns,
                        trimMatcher.group("foreach"),
                        "",
                        trimMatcher.group("trailing")
                );
            } else {
                Matcher trimValuesMatcher = INSERT_IGNORE_TRIM_COLUMNS_VALUES_PATTERN.matcher(candidate);
                if (!trimValuesMatcher.matches()) {
                    return body;
                }
                String columns = trimColumnList(trimValuesMatcher.group("columnsTrim"));
                String values = trimValuesList(trimValuesMatcher.group("valuesTrim"));
                if (columns == null || values == null) {
                    return body;
                }
                List<String> keyColumns = rewriteConfig.keyColumnsFor(statementKey, trimValuesMatcher.group("table"));
                if (keyColumns.isEmpty()) {
                    return body;
                }
                String converted = convertConditionalTrimInsertIgnoreToMerge(
                        trimValuesMatcher.group("leading"),
                        trimValuesMatcher.group("table"),
                        columns,
                        values,
                        keyColumns,
                        trimValuesMatcher.group("trailing")
                );
                if (converted == null || converted.equals(candidate)) {
                    return body;
                }
                return wrapper == null ? converted : wrapper.wrap(converted);
            }
        }
        List<String> keyColumns = rewriteConfig.keyColumnsFor(statementKey, batchInsertValues.table());
        if (keyColumns.isEmpty()) {
            return body;
        }
        String converted = convertBatchInsertValuesToMerge(
                batchInsertValues.leading(),
                batchInsertValues.table(),
                batchInsertValues.columns(),
                batchInsertValues.foreach(),
                batchInsertValues.updates(),
                keyColumns,
                batchInsertValues.trailing()
        );
        if (converted == null || converted.equals(candidate)) {
            return body;
        }
        return wrapper == null ? converted : wrapper.wrap(converted);
    }

    private String trimColumnList(String trimXml) {
        Matcher matcher = TRIM_TAG_PATTERN.matcher(trimXml);
        if (!matcher.matches()) {
            return null;
        }
        String openingTag = matcher.group("opening");
        if (!"(".equals(defaultString(xmlAttribute(openingTag, "prefix")).trim())
                || !")".equals(defaultString(xmlAttribute(openingTag, "suffix")).trim())) {
            return null;
        }
        return matcher.group("body");
    }

    private String trimValuesList(String trimXml) {
        Matcher matcher = TRIM_TAG_PATTERN.matcher(trimXml);
        if (!matcher.matches()) {
            return null;
        }
        String openingTag = matcher.group("opening");
        String prefix = defaultString(xmlAttribute(openingTag, "prefix")).trim().replaceAll("\\s+", " ");
        if (!"(".equals(prefix) && !"values (".equalsIgnoreCase(prefix)) {
            return null;
        }
        if (!")".equals(defaultString(xmlAttribute(openingTag, "suffix")).trim())) {
            return null;
        }
        return matcher.group("body");
    }

    private String trimUpdateList(String trimXml) {
        Matcher matcher = TRIM_TAG_PATTERN.matcher(trimXml == null ? "" : trimXml.trim());
        if (!matcher.matches()) {
            return trimXml;
        }
        String openingTag = matcher.group("opening");
        String suffixOverrides = defaultString(xmlAttribute(openingTag, "suffixOverrides")).trim();
        if (!",".equals(suffixOverrides)) {
            return null;
        }
        return matcher.group("body");
    }

    private String neutralizeMyBatisPlaceholdersInSqlLineComments(String body) {
        StringBuilder converted = new StringBuilder(body.length());
        boolean changed = false;
        int lineStart = 0;
        while (lineStart < body.length()) {
            int lineEnd = body.indexOf('\n', lineStart);
            boolean hasNewline = lineEnd >= 0;
            if (!hasNewline) {
                lineEnd = body.length();
            }
            String line = body.substring(lineStart, lineEnd);
            int commentStart = sqlLineCommentStart(line);
            if (commentStart >= 0) {
                String comment = line.substring(commentStart);
                String neutralized = comment
                        .replace("#{", "# {")
                        .replace("${", "$ {");
                if (!neutralized.equals(comment)) {
                    line = line.substring(0, commentStart) + neutralized;
                    changed = true;
                }
            }
            converted.append(line);
            if (hasNewline) {
                converted.append('\n');
            }
            lineStart = lineEnd + 1;
        }
        return changed ? converted.toString() : body;
    }

    private int sqlLineCommentStart(String line) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < line.length() - 1; i++) {
            char current = line.charAt(i);
            if (current == '\'' && !inDoubleQuote) {
                if (inSingleQuote && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
                continue;
            }
            if (current == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (!inSingleQuote
                    && !inDoubleQuote
                    && current == '-'
                    && line.charAt(i + 1) == '-'
                    && (i + 2 == line.length() || Character.isWhitespace(line.charAt(i + 2)))) {
                return i;
            }
        }
        return -1;
    }

    private String convertBatchInsertValuesToMerge(
            String leading,
            String table,
            String columnList,
            String foreachXml,
            String updateClause,
            List<String> keyColumns,
            String trailing
    ) {
        List<String> columns = splitTopLevelComma(columnList).stream()
                .map(String::trim)
                .filter(column -> !column.isBlank())
                .toList();
        ForeachBlock foreach = readForeach(foreachXml);
        String tupleBody = foreach == null ? "" : outerParenthesizedContent(foreach.body());
        List<String> values = splitTopLevelComma(tupleBody).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::collapseDuplicateStandalonePlaceholderValue)
                .toList();
        if (foreach == null || columns.isEmpty() || values.size() != columns.size()) {
            return null;
        }

        List<String> normalizedKeys = normalizeIdentifiers(keyColumns);
        List<MergeSourceColumn> sourceColumns = new ArrayList<>();
        Map<String, MergeSourceColumn> sourceColumnsByName = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            MergeSourceColumn sourceColumn = new MergeSourceColumn(columns.get(i), values.get(i));
            sourceColumns.add(sourceColumn);
            sourceColumnsByName.put(normalizeIdentifier(sourceColumn.name()), sourceColumn);
        }

        List<BatchUpdateAssignment> updateAssignments = updateClause.isBlank()
                ? List.of()
                : updateAssignments(updateClause);
        if (updateAssignments == null) {
            return null;
        }
        updateAssignments = updateAssignments.stream()
                .filter(assignment -> !isNoOpSelfAssignment(assignment))
                .toList();

        Map<String, BatchUpdateAssignment> assignmentsByTarget = new LinkedHashMap<>();
        for (BatchUpdateAssignment assignment : updateAssignments) {
            assignmentsByTarget.put(normalizeIdentifier(assignment.target()), assignment);
        }
        List<MergeSourceColumn> keySourceColumns = new ArrayList<>();
        for (int i = 0; i < normalizedKeys.size(); i++) {
            String keyColumn = normalizedKeys.get(i);
            MergeSourceColumn sourceColumn = sourceColumnsByName.get(keyColumn);
            if (sourceColumn == null) {
                BatchUpdateAssignment assignment = assignmentsByTarget.get(keyColumn);
                if (assignment == null || assignment.valuesReference()) {
                    return null;
                }
                sourceColumn = new MergeSourceColumn(keyColumns.get(i), assignment.sourceExpression());
                sourceColumns.add(sourceColumn);
                sourceColumnsByName.put(keyColumn, sourceColumn);
            }
            keySourceColumns.add(sourceColumn);
        }

        updateAssignments = updateAssignments.stream()
                .filter(assignment -> !normalizedKeys.contains(normalizeIdentifier(assignment.target())))
                .toList();
        for (BatchUpdateAssignment assignment : updateAssignments) {
            if (assignment.valuesReference()
                    && !sourceColumnsByName.containsKey(normalizeIdentifier(assignment.sourceExpression()))) {
                return null;
            }
        }

        String baseIndent = indentationOfLastLine(leading);
        String childIndent = baseIndent + "    ";
        String nestedIndent = childIndent + "    ";
        StringBuilder converted = new StringBuilder();
        converted.append(leading)
                .append(foreach.openingWithSeparator(";"))
                .append("\n")
                .append(childIndent)
                .append("MERGE INTO ")
                .append(table)
                .append(" t\n")
                .append(childIndent)
                .append("USING (\n")
                .append(nestedIndent)
                .append("SELECT ");
        for (int i = 0; i < sourceColumns.size(); i++) {
            if (i > 0) {
                converted.append(", ");
            }
            MergeSourceColumn sourceColumn = sourceColumns.get(i);
            converted.append(sourceColumn.expression())
                    .append(" AS ")
                    .append(dmIdentifier(sourceColumn.name()));
        }
        converted.append(" FROM dual\n")
                .append(childIndent)
                .append(") s\n")
                .append(childIndent)
                .append("ON (");
        for (int i = 0; i < keySourceColumns.size(); i++) {
            if (i > 0) {
                converted.append(" AND ");
            }
            String keyColumn = keySourceColumns.get(i).name();
            converted.append("t.")
                    .append(dmIdentifier(keyColumn))
                    .append(" = s.")
                    .append(dmIdentifier(keyColumn));
        }
        converted.append(")\n");
        if (!updateAssignments.isEmpty()) {
            converted.append(childIndent)
                    .append("WHEN MATCHED THEN UPDATE SET ");
            for (int i = 0; i < updateAssignments.size(); i++) {
                if (i > 0) {
                    converted.append(", ");
                }
                BatchUpdateAssignment assignment = updateAssignments.get(i);
                converted.append("t.")
                        .append(dmIdentifier(assignment.target()))
                        .append(" = ");
                if (assignment.valuesReference()) {
                    converted.append("s.")
                            .append(dmIdentifier(assignment.sourceExpression()));
                } else {
                    converted.append(assignment.sourceExpression());
                }
            }
            converted.append("\n");
        }
        converted.append(childIndent)
                .append("WHEN NOT MATCHED THEN INSERT (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                converted.append(", ");
            }
            converted.append(dmIdentifier(columns.get(i)));
        }
        converted.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                converted.append(", ");
            }
            converted.append("s.").append(dmIdentifier(columns.get(i)));
        }
        converted.append(")\n")
                .append(baseIndent)
                .append("</foreach>")
                .append(trailing);
        return converted.toString();
    }

    private String convertConditionalTrimInsertIgnoreToMerge(
            String leading,
            String table,
            String columnList,
            String valueList,
            List<String> keyColumns,
            String trailing
    ) {
        List<ConditionalTrimItem> columns = conditionalTrimItems(columnList);
        List<ConditionalTrimItem> values = conditionalTrimItems(valueList);
        if (columns.isEmpty() || columns.size() != values.size()) {
            return null;
        }
        List<String> normalizedKeys = normalizeIdentifiers(keyColumns);
        List<String> columnNames = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            ConditionalTrimItem column = columns.get(i);
            ConditionalTrimItem value = values.get(i);
            if (!column.test().equals(value.test())) {
                return null;
            }
            String columnName = stripTrailingComma(column.content()).trim();
            String valueExpression = stripTrailingComma(value.content()).trim();
            if (columnName.isBlank() || valueExpression.isBlank()) {
                return null;
            }
            columnNames.add(columnName);
        }
        List<Integer> keyIndexes = new ArrayList<>();
        for (String keyColumn : normalizedKeys) {
            int index = indexOfIdentifier(columnNames, keyColumn);
            if (index < 0) {
                return null;
            }
            keyIndexes.add(index);
        }

        String baseIndent = indentationOfLastLine(leading);
        String childIndent = baseIndent + "    ";
        String nestedIndent = childIndent + "    ";
        StringBuilder converted = new StringBuilder();
        converted.append(leading)
                .append("MERGE INTO ")
                .append(table)
                .append(" t\n")
                .append(baseIndent)
                .append("USING (\n")
                .append(childIndent)
                .append("SELECT\n")
                .append(nestedIndent)
                .append("<trim suffixOverrides=\",\">\n");
        for (int i = 0; i < columns.size(); i++) {
            String columnName = columnNames.get(i);
            String valueExpression = stripTrailingComma(values.get(i).content()).trim();
            converted.append(nestedIndent)
                    .append("    ")
                    .append(columns.get(i).opening())
                    .append("\n")
                    .append(nestedIndent)
                    .append("        ")
                    .append(valueExpression)
                    .append(" AS ")
                    .append(dmIdentifier(columnName))
                    .append(",\n")
                    .append(nestedIndent)
                    .append("    </if>\n");
        }
        converted.append(nestedIndent)
                .append("</trim>\n")
                .append(childIndent)
                .append("FROM dual\n")
                .append(baseIndent)
                .append(") s\n")
                .append(baseIndent)
                .append("ON (");
        for (int i = 0; i < keyIndexes.size(); i++) {
            if (i > 0) {
                converted.append(" AND ");
            }
            String keyColumn = columnNames.get(keyIndexes.get(i));
            converted.append("t.")
                    .append(dmIdentifier(keyColumn))
                    .append(" = s.")
                    .append(dmIdentifier(keyColumn));
        }
        converted.append(")\n")
                .append(baseIndent)
                .append("WHEN NOT MATCHED THEN INSERT\n")
                .append(baseIndent)
                .append("<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");
        for (int i = 0; i < columns.size(); i++) {
            String columnName = columnNames.get(i);
            converted.append(childIndent)
                    .append(columns.get(i).opening())
                    .append("\n")
                    .append(nestedIndent)
                    .append(dmIdentifier(columnName))
                    .append(",\n")
                    .append(childIndent)
                    .append("</if>\n");
        }
        converted.append(baseIndent)
                .append("</trim>\n")
                .append(baseIndent)
                .append("VALUES\n")
                .append(baseIndent)
                .append("<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");
        for (int i = 0; i < columns.size(); i++) {
            String columnName = columnNames.get(i);
            converted.append(childIndent)
                    .append(columns.get(i).opening())
                    .append("\n")
                    .append(nestedIndent)
                    .append("s.")
                    .append(dmIdentifier(columnName))
                    .append(",\n")
                    .append(childIndent)
                    .append("</if>\n");
        }
        converted.append(baseIndent)
                .append("</trim>")
                .append(trailing);
        return converted.toString();
    }

    private String convertConditionalTrimOnDuplicateKeyUpdateToMerge(
            String leading,
            String table,
            String columnList,
            String valueList,
            String updateClause,
            List<String> keyColumns,
            String trailing
    ) {
        String updateBody = trimUpdateList(updateClause);
        if (updateBody == null) {
            return null;
        }
        List<ConditionalTrimItem> columns = conditionalTrimItems(columnList);
        List<ConditionalTrimItem> values = conditionalTrimItems(valueList);
        List<ConditionalTrimItem> updates = conditionalTrimItems(updateBody);
        if (columns.isEmpty() || columns.size() != values.size()) {
            return null;
        }
        List<String> normalizedKeys = normalizeIdentifiers(keyColumns);
        List<String> columnNames = new ArrayList<>();
        Map<String, String> columnTestsByName = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            ConditionalTrimItem column = columns.get(i);
            ConditionalTrimItem value = values.get(i);
            if (!column.test().equals(value.test())) {
                return null;
            }
            String columnName = stripTrailingComma(column.content()).trim();
            String valueExpression = stripTrailingComma(value.content()).trim();
            if (columnName.isBlank() || valueExpression.isBlank()) {
                return null;
            }
            columnNames.add(columnName);
            columnTestsByName.put(normalizeIdentifier(columnName), column.test());
        }
        List<Integer> keyIndexes = new ArrayList<>();
        for (String keyColumn : normalizedKeys) {
            int index = indexOfIdentifier(columnNames, keyColumn);
            if (index < 0) {
                return null;
            }
            keyIndexes.add(index);
        }

        List<ConditionalUpdateAssignment> updateAssignments = new ArrayList<>();
        if (updates.isEmpty()) {
            List<BatchUpdateAssignment> parsed = updateAssignments(updateBody);
            if (parsed == null
                    || parsed.isEmpty()
                    || parsed.stream().anyMatch(assignment -> !isNoOpSelfAssignment(assignment))) {
                return null;
            }
        } else {
            for (ConditionalTrimItem update : updates) {
                List<BatchUpdateAssignment> parsed = updateAssignments(stripTrailingComma(update.content()).trim());
                if (parsed == null || parsed.size() != 1) {
                    return null;
                }
                BatchUpdateAssignment assignment = parsed.get(0);
                String normalizedTarget = normalizeIdentifier(assignment.target());
                if (normalizedKeys.contains(normalizedTarget) || isNoOpSelfAssignment(assignment)) {
                    continue;
                }
                if (assignment.valuesReference()) {
                    String normalizedSource = normalizeIdentifier(assignment.sourceExpression());
                    if (!columnTestsByName.containsKey(normalizedSource)
                            || !columnTestsByName.get(normalizedSource).equals(update.test())) {
                        return null;
                    }
                }
                updateAssignments.add(new ConditionalUpdateAssignment(update.opening(), assignment));
            }
        }

        String baseIndent = indentationOfLastLine(leading);
        String childIndent = baseIndent + "    ";
        String nestedIndent = childIndent + "    ";
        StringBuilder converted = new StringBuilder();
        converted.append(leading)
                .append("MERGE INTO ")
                .append(table)
                .append(" t\n")
                .append(baseIndent)
                .append("USING (\n")
                .append(childIndent)
                .append("SELECT\n")
                .append(nestedIndent)
                .append("<trim suffixOverrides=\",\">\n");
        for (int i = 0; i < columns.size(); i++) {
            String columnName = columnNames.get(i);
            String valueExpression = stripTrailingComma(values.get(i).content()).trim();
            converted.append(nestedIndent)
                    .append("    ")
                    .append(columns.get(i).opening())
                    .append("\n")
                    .append(nestedIndent)
                    .append("        ")
                    .append(valueExpression)
                    .append(" AS ")
                    .append(dmIdentifier(columnName))
                    .append(",\n")
                    .append(nestedIndent)
                    .append("    </if>\n");
        }
        converted.append(nestedIndent)
                .append("</trim>\n")
                .append(childIndent)
                .append("FROM dual\n")
                .append(baseIndent)
                .append(") s\n")
                .append(baseIndent)
                .append("ON (");
        for (int i = 0; i < keyIndexes.size(); i++) {
            if (i > 0) {
                converted.append(" AND ");
            }
            String keyColumn = columnNames.get(keyIndexes.get(i));
            converted.append("t.")
                    .append(dmIdentifier(keyColumn))
                    .append(" = s.")
                    .append(dmIdentifier(keyColumn));
        }
        converted.append(")\n");
        if (!updateAssignments.isEmpty()) {
            converted.append(baseIndent)
                    .append("WHEN MATCHED THEN UPDATE SET\n")
                    .append(baseIndent)
                    .append("<trim suffixOverrides=\",\">\n");
            for (ConditionalUpdateAssignment update : updateAssignments) {
                BatchUpdateAssignment assignment = update.assignment();
                converted.append(childIndent)
                        .append(update.opening())
                        .append("\n")
                        .append(nestedIndent)
                        .append("t.")
                        .append(dmIdentifier(assignment.target()))
                        .append(" = ");
                if (assignment.valuesReference()) {
                    converted.append("s.")
                            .append(dmIdentifier(assignment.sourceExpression()));
                } else {
                    converted.append(assignment.sourceExpression());
                }
                converted.append(",\n")
                        .append(childIndent)
                        .append("</if>\n");
            }
            converted.append(baseIndent)
                    .append("</trim>\n");
        }
        converted.append(baseIndent)
                .append("WHEN NOT MATCHED THEN INSERT\n")
                .append(baseIndent)
                .append("<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");
        for (int i = 0; i < columns.size(); i++) {
            String columnName = columnNames.get(i);
            converted.append(childIndent)
                    .append(columns.get(i).opening())
                    .append("\n")
                    .append(nestedIndent)
                    .append(dmIdentifier(columnName))
                    .append(",\n")
                    .append(childIndent)
                    .append("</if>\n");
        }
        converted.append(baseIndent)
                .append("</trim>\n")
                .append(baseIndent)
                .append("VALUES\n")
                .append(baseIndent)
                .append("<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">\n");
        for (int i = 0; i < columns.size(); i++) {
            String columnName = columnNames.get(i);
            converted.append(childIndent)
                    .append(columns.get(i).opening())
                    .append("\n")
                    .append(nestedIndent)
                    .append("s.")
                    .append(dmIdentifier(columnName))
                    .append(",\n")
                    .append(childIndent)
                    .append("</if>\n");
        }
        converted.append(baseIndent)
                .append("</trim>")
                .append(trailing);
        return converted.toString();
    }

    private String convertDynamicOnDuplicateKeyUpdate(
            String body,
            String statementKey,
            SqlRewriteConfig rewriteConfig
    ) {
        IfWrapper wrapper = readWrappingIf(body);
        String candidate = wrapper == null ? body : wrapper.body();
        String converted = convertDynamicOnDuplicateKeyUpdateCore(candidate, statementKey, rewriteConfig);
        if (converted.equals(candidate)) {
            return body;
        }
        return wrapper == null ? converted : wrapper.wrap(converted);
    }

    private IfWrapper readWrappingIf(String body) {
        Matcher matcher = WRAPPING_IF_PATTERN.matcher(body);
        if (!matcher.matches()) {
            return null;
        }
        return new IfWrapper(
                matcher.group("leading"),
                matcher.group("opening"),
                matcher.group("body"),
                matcher.group("closing"),
                matcher.group("trailing")
        );
    }

    private String convertDynamicOnDuplicateKeyUpdateCore(
            String body,
            String statementKey,
            SqlRewriteConfig rewriteConfig
    ) {
        Matcher matcher = DYNAMIC_ON_DUPLICATE_KEY_UPDATE_PATTERN.matcher(body);
        if (!matcher.matches()) {
            return body;
        }

        ForeachBlock keyForeach = readForeach(matcher.group("keyForeach"));
        ForeachBlock valueForeach = readForeach(matcher.group("valueForeach"));
        ForeachBlock updateForeach = readForeach(matcher.group("updateForeach"));
        if (keyForeach == null || valueForeach == null || updateForeach == null) {
            return body;
        }

        String mapName = collectionBase(keyForeach.collection(), ".keys");
        if (mapName == null
                || !keyForeach.item().equals(updateForeach.index())
                || !valueForeach.item().equals(updateForeach.item())
                || !keyForeach.item().matches("[A-Za-z_][A-Za-z0-9_]*")
                || !valueForeach.item().matches("[A-Za-z_][A-Za-z0-9_]*")
                || !keyForeach.open().equals(",")
                || !valueForeach.open().equals(",")
                || !keyForeach.separator().equals(",")
                || !valueForeach.separator().equals(",")
                || !updateForeach.separator().equals(",")
                || !valueForeach.collection().equals(mapName + ".values")
                || !updateForeach.collection().equals(mapName)
                || !isDynamicColumnBody(keyForeach.body(), keyForeach.item())
                || !valueForeach.body().strip().equals("#{" + valueForeach.item() + "}")
                || !isDynamicValuesUpdateBody(updateForeach.body(), keyForeach.item())) {
            return body;
        }

        String leading = matcher.group("leading");
        String baseIndent = indentationOfLastLine(leading);
        String childIndent = baseIndent + "    ";
        String fixedColumn = matcher.group("fixedColumn").trim();
        String fixedValue = matcher.group("fixedValue").trim();
        String table = matcher.group("table").trim();
        List<String> keyColumns = rewriteConfig.keyColumnsFor(statementKey, table);
        if (keyColumns.size() != 1 || !normalizeIdentifier(fixedColumn).equals(normalizeIdentifier(keyColumns.get(0)))) {
            return body;
        }

        StringBuilder converted = new StringBuilder(body.length() + 256);
        converted.append(leading)
                .append("MERGE INTO ")
                .append(table)
                .append(" t\n")
                .append(baseIndent)
                .append("USING (\n")
                .append(childIndent)
                .append("SELECT ")
                .append(fixedValue)
                .append(" AS ")
                .append(fixedColumn)
                .append("\n")
                .append(baseIndent)
                .append("<foreach collection=\"")
                .append(mapName)
                .append("\" index=\"")
                .append(keyForeach.item())
                .append("\" item=\"")
                .append(valueForeach.item())
                .append("\">\n")
                .append(childIndent)
                .append(", #{")
                .append(valueForeach.item())
                .append("} AS ${")
                .append(keyForeach.item())
                .append("}\n")
                .append(baseIndent)
                .append("</foreach>\n")
                .append(childIndent)
                .append("FROM dual\n")
                .append(baseIndent)
                .append(") s\n")
                .append(baseIndent)
                .append("ON (t.")
                .append(fixedColumn)
                .append(" = s.")
                .append(fixedColumn)
                .append(")\n")
                .append(baseIndent)
                .append("WHEN MATCHED THEN UPDATE SET\n")
                .append(baseIndent)
                .append("<foreach collection=\"")
                .append(mapName)
                .append("\" index=\"")
                .append(keyForeach.item())
                .append("\" item=\"")
                .append(valueForeach.item())
                .append("\" separator=\",\">\n")
                .append(childIndent)
                .append("t.${")
                .append(keyForeach.item())
                .append("} = s.${")
                .append(keyForeach.item())
                .append("}\n")
                .append(baseIndent)
                .append("</foreach>\n")
                .append(baseIndent)
                .append("WHEN NOT MATCHED THEN INSERT (")
                .append(fixedColumn)
                .append("\n")
                .append(baseIndent)
                .append("<foreach collection=\"")
                .append(mapName)
                .append(".keys\" item=\"")
                .append(keyForeach.item())
                .append("\" open=\",\" separator=\",\">\n")
                .append(childIndent)
                .append("${")
                .append(keyForeach.item())
                .append("}\n")
                .append(baseIndent)
                .append("</foreach>\n")
                .append(baseIndent)
                .append(") VALUES (s.")
                .append(fixedColumn)
                .append("\n")
                .append(baseIndent)
                .append("<foreach collection=\"")
                .append(mapName)
                .append(".keys\" item=\"")
                .append(keyForeach.item())
                .append("\" open=\",\" separator=\",\">\n")
                .append(childIndent)
                .append("s.${")
                .append(keyForeach.item())
                .append("}\n")
                .append(baseIndent)
                .append("</foreach>\n")
                .append(baseIndent)
                .append(")")
                .append(matcher.group("trailing"));
        return converted.toString();
    }

    private String convertDynamicUpdateJoin(String body) {
        Matcher matcher = DYNAMIC_UPDATE_JOIN_PATTERN.matcher(body);
        if (!matcher.matches()) {
            return body;
        }

        String target = matcher.group("target").strip();
        String joinSource = matcher.group("joinSource").strip();
        String joinCondition = matcher.group("joinCondition").strip();
        String setBlocks = matcher.group("setBlocks");
        String whereClause = matcher.group("whereClause").strip();
        if (target.isBlank()
                || joinSource.isBlank()
                || joinCondition.isBlank()
                || whereClause.isBlank()
                || containsJoinKeyword(target)
                || containsJoinKeyword(joinSource)
                || containsJoinKeyword(joinCondition)
                || setBlocksContainMultipleSetStatements(setBlocks)) {
            return body;
        }

        String leading = matcher.group("leading");
        String baseIndent = indentationOfLastLine(leading);
        String childIndent = baseIndent + "    ";
        StringBuilder converted = new StringBuilder(body.length() + 64);
        converted.append(leading)
                .append("update ")
                .append(target)
                .append(setBlocks)
                .append("\n")
                .append(baseIndent)
                .append("from ")
                .append(joinSource)
                .append("\n")
                .append(baseIndent)
                .append("where\n")
                .append(childIndent)
                .append(joinCondition)
                .append("\n")
                .append(childIndent)
                .append("and ")
                .append(whereClause)
                .append(matcher.group("trailing"));
        return converted.toString();
    }

    private DynamicBodyConversion convertDynamicUpdateJoinWithSetClause(
            String body,
            String statementKey,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        int statementEnd = body.length();
        while (statementEnd > 0 && Character.isWhitespace(body.charAt(statementEnd - 1))) {
            statementEnd--;
        }
        String trailing = body.substring(statementEnd);
        if (statementEnd > 0 && body.charAt(statementEnd - 1) == ';') {
            statementEnd--;
            trailing = body.substring(statementEnd);
        }
        String statement = body.substring(0, statementEnd);
        int updateIndex = leadingWhitespaceLength(statement);
        if (!isKeywordAt(statement, updateIndex, "UPDATE")) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }
        int joinIndex = findTopLevelKeywordSkippingXml(statement, "JOIN", updateIndex + "UPDATE".length());
        if (joinIndex < 0) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }
        int setIndex = findTopLevelKeywordSkippingXml(statement, "SET", joinIndex + "JOIN".length());
        if (setIndex < 0) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }
        int whereIndex = findTopLevelKeywordSkippingXml(statement, "WHERE", setIndex + "SET".length());
        if (whereIndex < 0) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        int joinTypeStart = dynamicJoinTypeStart(statement, joinIndex);
        String leading = statement.substring(0, updateIndex);
        String target = statement.substring(updateIndex + "UPDATE".length(), joinTypeStart).strip();
        String joinSourceWithCondition = statement.substring(joinIndex + "JOIN".length(), setIndex).strip();
        String setClause = statement.substring(setIndex + "SET".length(), whereIndex).strip();
        String whereClause = statement.substring(whereIndex + "WHERE".length()).strip();
        DynamicJoinSource splitJoin = splitDynamicJoinSource(joinSourceWithCondition);
        if (target.isBlank()
                || splitJoin == null
                || splitJoin.sourceSql().isBlank()
                || splitJoin.conditionSql().isBlank()
                || setClause.isBlank()
                || whereClause.isBlank()
                || containsJoinKeyword(target)
                || containsTopLevelJoinKeyword(splitJoin.sourceSql())
                || containsTopLevelJoinKeyword(splitJoin.conditionSql())
                || findTopLevelKeywordSkippingXml(setClause, "SET", 0) >= 0) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        TextSegmentConversion targetConversion = convertSqlTextWithXmlTags(
                target,
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        TextSegmentConversion sourceConversion = convertSqlTextWithXmlTags(
                splitJoin.sourceSql(),
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        TextSegmentConversion conditionConversion = convertSqlTextWithXmlTags(
                splitJoin.conditionSql(),
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        TextSegmentConversion setConversion = convertSqlTextWithXmlTags(
                setClause,
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        TextSegmentConversion whereConversion = convertSqlTextWithXmlTags(
                whereClause,
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        List<String> appliedRules = new ArrayList<>();
        List<String> manualReviewReasons = new ArrayList<>();
        appliedRules.add(MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, targetConversion);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, sourceConversion);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, conditionConversion);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, setConversion);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, whereConversion);

        String baseIndent = indentationOfLastLine(leading);
        String childIndent = baseIndent + "    ";
        StringBuilder converted = new StringBuilder(body.length() + 64);
        converted.append(leading)
                .append("update ")
                .append(targetConversion.convertedText().strip())
                .append(" set ")
                .append(setConversion.convertedText().strip())
                .append("\n")
                .append(baseIndent)
                .append("from ")
                .append(sourceConversion.convertedText().strip())
                .append("\n")
                .append(baseIndent)
                .append("where\n")
                .append(childIndent)
                .append(conditionConversion.convertedText().strip())
                .append("\n")
                .append(childIndent)
                .append("and ")
                .append(whereConversion.convertedText().strip())
                .append(trailing);
        return new DynamicBodyConversion(
                body,
                converted.toString(),
                appliedRules,
                manualReviewReasons,
                true
        );
    }

    private DynamicBodyConversion convertDynamicUpdateJoinWithWhereTag(
            String body,
            String statementKey,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        MyBatisWhereBlock whereBlock = findMyBatisWhereBlock(body, 0, body.length());
        if (whereBlock == null || !body.substring(whereBlock.closingEnd()).strip().matches(";?")) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }
        String whereClause = body.substring(whereBlock.openingEnd(), whereBlock.closingStart()).strip();
        if (whereClause.isBlank() || whereClause.startsWith("<")) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        String statement = body.substring(0, whereBlock.openingStart());
        int updateIndex = leadingWhitespaceLength(statement);
        if (!isKeywordAt(statement, updateIndex, "UPDATE")) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }
        int joinIndex = findTopLevelKeywordSkippingXml(statement, "JOIN", updateIndex + "UPDATE".length());
        if (joinIndex < 0) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }
        int setIndex = findTopLevelKeywordSkippingXml(statement, "SET", joinIndex + "JOIN".length());
        if (setIndex < 0) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        int joinTypeStart = dynamicJoinTypeStart(statement, joinIndex);
        String leading = statement.substring(0, updateIndex);
        String target = statement.substring(updateIndex + "UPDATE".length(), joinTypeStart).strip();
        String joinSourceWithCondition = statement.substring(joinIndex + "JOIN".length(), setIndex).strip();
        String setClause = statement.substring(setIndex + "SET".length()).strip();
        DynamicJoinSource splitJoin = splitDynamicJoinSource(joinSourceWithCondition);
        if (target.isBlank()
                || splitJoin == null
                || splitJoin.sourceSql().isBlank()
                || splitJoin.conditionSql().isBlank()
                || setClause.isBlank()
                || containsJoinKeyword(target)
                || containsTopLevelJoinKeyword(splitJoin.sourceSql())
                || containsTopLevelJoinKeyword(splitJoin.conditionSql())
                || containsXmlMarkup(setClause)
                || findTopLevelKeywordSkippingXml(setClause, "SET", 0) >= 0) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        TextSegmentConversion targetConversion = convertSqlTextWithXmlTags(
                target,
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        TextSegmentConversion sourceConversion = convertSqlTextWithXmlTags(
                splitJoin.sourceSql(),
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        TextSegmentConversion conditionConversion = convertSqlTextWithXmlTags(
                splitJoin.conditionSql(),
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        TextSegmentConversion setConversion = convertSqlTextWithXmlTags(
                stripDynamicUpdateTargetAlias(target, setClause),
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        TextSegmentConversion whereConversion = convertSqlTextWithXmlTags(
                removeLeadingBooleanConnector(whereClause),
                statementKey,
                sqlConverter,
                rewriteConfig
        );
        List<String> appliedRules = new ArrayList<>();
        List<String> manualReviewReasons = new ArrayList<>();
        appliedRules.add(MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, targetConversion);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, sourceConversion);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, conditionConversion);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, setConversion);
        addSqlFragmentConversion(appliedRules, manualReviewReasons, whereConversion);

        String baseIndent = indentationOfLastLine(leading);
        String childIndent = baseIndent + "    ";
        String convertedWhere = body.substring(whereBlock.openingStart(), whereBlock.openingEnd())
                + "\n"
                + childIndent
                + indentBlock(conditionConversion.convertedText().strip(), childIndent)
                + "\n"
                + childIndent
                + "and "
                + indentBlock(whereConversion.convertedText().strip(), childIndent)
                + "\n"
                + baseIndent
                + body.substring(whereBlock.closingStart(), whereBlock.closingEnd());
        String converted = leading
                + "update "
                + targetConversion.convertedText().strip()
                + " set "
                + setConversion.convertedText().strip()
                + "\n"
                + baseIndent
                + "from "
                + sourceConversion.convertedText().strip()
                + "\n"
                + baseIndent
                + convertedWhere
                + body.substring(whereBlock.closingEnd());
        return new DynamicBodyConversion(
                body,
                converted,
                appliedRules,
                manualReviewReasons,
                true
        );
    }

    private String stripDynamicUpdateTargetAlias(String target, String setClause) {
        String targetAlias = updateTargetAlias(target);
        if (targetAlias.isBlank()) {
            return setClause;
        }
        Pattern qualifiedAssignment = Pattern.compile(
                "(?is)(?<![A-Za-z0-9_$])"
                        + Pattern.quote(targetAlias)
                        + "\\s*\\.\\s*(?<column>"
                        + DM_IDENTIFIER
                        + ")(?=\\s*=)"
        );
        Matcher matcher = qualifiedAssignment.matcher(setClause);
        StringBuilder converted = new StringBuilder(setClause.length());
        while (matcher.find()) {
            matcher.appendReplacement(converted, Matcher.quoteReplacement(matcher.group("column")));
        }
        matcher.appendTail(converted);
        return converted.toString();
    }

    private DynamicBodyConversion convertDynamicUpdateOrderLimitOneWithSetClause(String body) {
        int statementEnd = body.length();
        while (statementEnd > 0 && Character.isWhitespace(body.charAt(statementEnd - 1))) {
            statementEnd--;
        }
        String trailing = body.substring(statementEnd);
        if (statementEnd > 0 && body.charAt(statementEnd - 1) == ';') {
            statementEnd--;
            trailing = body.substring(statementEnd);
        }

        String statement = body.substring(0, statementEnd);
        int updateIndex = leadingWhitespaceLength(statement);
        if (!isKeywordAt(statement, updateIndex, "UPDATE")) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        TrimBlock setBlock = findMyBatisSetBlock(statement, updateIndex + "UPDATE".length(), statement.length());
        if (setBlock == null) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        String tableName = statement.substring(updateIndex + "UPDATE".length(), setBlock.openingStart()).strip();
        if (!isSimpleQualifiedIdentifierExpression(tableName)) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        String tail = statement.substring(setBlock.closingEnd());
        int whereIndex = findTopLevelKeywordSkippingXml(tail, "WHERE", 0);
        if (whereIndex < 0 || !tail.substring(0, whereIndex).isBlank()) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }
        int orderIndex = findTopLevelKeywordSkippingXml(tail, "ORDER", whereIndex + "WHERE".length());
        if (orderIndex < 0) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }
        int byIndex = skipWhitespace(tail, orderIndex + "ORDER".length());
        if (!isKeywordAt(tail, byIndex, "BY")) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }
        int limitIndex = findTopLevelKeywordSkippingXml(tail, "LIMIT", byIndex + "BY".length());
        if (limitIndex < 0 || !isOnlyLimitOne(tail.substring(limitIndex + "LIMIT".length()))) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        String whereClause = tail.substring(whereIndex + "WHERE".length(), orderIndex).strip();
        String orderClause = tail.substring(byIndex + "BY".length(), limitIndex).strip();
        if (whereClause.isBlank()
                || orderClause.isBlank()
                || containsXmlMarkup(whereClause)
                || containsXmlMarkup(orderClause)) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        String converted = statement.substring(0, setBlock.closingEnd())
                + tail.substring(0, whereIndex)
                + "where ROWID in (select rid from (select ROWID rid from "
                + tableName
                + " where "
                + whereClause
                + " order by "
                + orderClause
                + ") where ROWNUM &lt;= 1)"
                + trailing;
        return new DynamicBodyConversion(
                body,
                converted,
                List.of(MySqlToDmSqlConverter.MYSQL_UPDATE_ORDER_LIMIT_ONE_RULE),
                List.of(),
                true
        );
    }

    private TextSegmentConversion convertSqlTextWithXmlTags(
            String value,
            String statementKey,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        StringBuilder converted = new StringBuilder(value.length());
        List<String> appliedRules = new ArrayList<>();
        List<String> manualReviewReasons = new ArrayList<>();
        boolean changed = false;
        int index = 0;
        while (index < value.length()) {
            if (value.startsWith("<![CDATA[", index)) {
                int cdataEnd = value.indexOf("]]>", index + "<![CDATA[".length());
                if (cdataEnd < 0) {
                    converted.append(value, index, value.length());
                    break;
                }
                String content = value.substring(index + "<![CDATA[".length(), cdataEnd);
                TextSegmentConversion conversion = convertTextSegment(
                        content,
                        statementKey,
                        sqlConverter,
                        rewriteConfig,
                        false
                );
                converted.append(toCdata(conversion.convertedText()));
                addAppliedRules(appliedRules, conversion.appliedRules());
                addManualReviewReasons(manualReviewReasons, conversion.manualReviewReasons());
                changed = changed || conversion.changed();
                index = cdataEnd + "]]>".length();
            } else if (value.startsWith("<!--", index)) {
                int commentEnd = value.indexOf("-->", index + "<!--".length());
                if (commentEnd < 0) {
                    converted.append(value, index, value.length());
                    break;
                }
                converted.append(value, index, commentEnd + "-->".length());
                index = commentEnd + "-->".length();
            } else if (value.charAt(index) == '<') {
                int tagEnd = findXmlTagEnd(value, index);
                if (tagEnd < 0) {
                    converted.append(value, index, value.length());
                    break;
                }
                converted.append(value, index, tagEnd + 1);
                index = tagEnd + 1;
            } else {
                int nextTag = value.indexOf('<', index);
                int textEnd = nextTag < 0 ? value.length() : nextTag;
                String text = value.substring(index, textEnd);
                TextSegmentConversion conversion = convertTextSegment(
                        text,
                        statementKey,
                        sqlConverter,
                        rewriteConfig,
                        false
                );
                converted.append(conversion.convertedText());
                addAppliedRules(appliedRules, conversion.appliedRules());
                addManualReviewReasons(manualReviewReasons, conversion.manualReviewReasons());
                changed = changed || conversion.changed();
                index = textEnd;
            }
        }
        return new TextSegmentConversion(
                changed ? converted.toString() : value,
                appliedRules,
                manualReviewReasons,
                changed
        );
    }

    private void addSqlFragmentConversion(
            List<String> appliedRules,
            List<String> manualReviewReasons,
            TextSegmentConversion conversion
    ) {
        addAppliedRules(appliedRules, conversion.appliedRules());
        addManualReviewReasons(manualReviewReasons, conversion.manualReviewReasons());
    }

    private int leadingWhitespaceLength(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private int dynamicJoinTypeStart(String value, int joinIndex) {
        int cursor = joinIndex - 1;
        while (cursor >= 0 && Character.isWhitespace(value.charAt(cursor))) {
            cursor--;
        }
        int end = cursor + 1;
        while (cursor >= 0 && isIdentifierPart(value.charAt(cursor))) {
            cursor--;
        }
        int start = cursor + 1;
        if (start >= end) {
            return joinIndex;
        }
        String word = value.substring(start, end).toUpperCase(Locale.ROOT);
        if (!Set.of("INNER", "LEFT", "RIGHT", "FULL", "CROSS").contains(word)) {
            return joinIndex;
        }
        int outerCursor = start - 1;
        while (outerCursor >= 0 && Character.isWhitespace(value.charAt(outerCursor))) {
            outerCursor--;
        }
        int outerEnd = outerCursor + 1;
        while (outerCursor >= 0 && isIdentifierPart(value.charAt(outerCursor))) {
            outerCursor--;
        }
        int outerStart = outerCursor + 1;
        if (outerStart < outerEnd && "OUTER".equalsIgnoreCase(value.substring(outerStart, outerEnd))) {
            return outerStart;
        }
        return start;
    }

    private DynamicJoinSource splitDynamicJoinSource(String joinSource) {
        int onIndex = findTopLevelKeywordSkippingXml(joinSource, "ON", 0);
        if (onIndex < 0) {
            return null;
        }
        String source = joinSource.substring(0, onIndex).strip();
        String condition = joinSource.substring(onIndex + "ON".length()).strip();
        return new DynamicJoinSource(source, condition);
    }

    private boolean containsTopLevelJoinKeyword(String value) {
        return findTopLevelKeywordSkippingXml(value, "JOIN", 0) >= 0;
    }

    private TrimBlock findMyBatisSetBlock(String body, int start, int end) {
        int index = Math.max(0, start);
        while (index >= 0 && index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                return null;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "set".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "set", end);
                if (closingStart < 0) {
                    return null;
                }
                int closingEnd = body.indexOf('>', closingStart + 1);
                return new TrimBlock(
                        tagStart,
                        tag.endIndex(),
                        closingStart,
                        closingEnd < 0 ? closingStart : closingEnd + 1
                );
            }
            index = tag.endIndex();
        }
        return null;
    }

    private int findTopLevelKeywordSkippingXml(String value, String keyword, int start) {
        int depth = 0;
        int index = Math.max(0, start);
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(value, index, current);
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (current == '<') {
                int tagEnd = findXmlTagEnd(value, index);
                index = tagEnd < 0 ? value.length() : tagEnd + 1;
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (depth == 0 && isKeywordAt(value, index, keyword)) {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private boolean isOnlyLimitOne(String tail) {
        return tail != null && tail.strip().equals("1");
    }

    private boolean containsXmlMarkup(String value) {
        return value != null && value.indexOf('<') >= 0;
    }

    private boolean isSimpleQualifiedIdentifierExpression(String value) {
        int index = skipWhitespace(value, 0);
        if (index >= value.length()) {
            return false;
        }
        int partEnd = readIdentifierOrVariableEnd(value, index);
        if (partEnd <= index) {
            return false;
        }
        index = skipWhitespace(value, partEnd);
        while (index < value.length() && value.charAt(index) == '.') {
            index = skipWhitespace(value, index + 1);
            int nextPartEnd = readIdentifierOrVariableEnd(value, index);
            if (nextPartEnd <= index) {
                return false;
            }
            index = skipWhitespace(value, nextPartEnd);
        }
        return index == value.length();
    }

    private int readIdentifierOrVariableEnd(String value, int start) {
        if (start < value.length()
                && value.charAt(start) == '$'
                && start + 1 < value.length()
                && value.charAt(start + 1) == '{') {
            return skipMyBatisPlaceholder(value, start);
        }
        IdentifierToken token = readIdentifierToken(value, start);
        return token == null ? -1 : token.endIndex();
    }

    private boolean containsJoinKeyword(String value) {
        return Pattern.compile("\\bjoin\\b", Pattern.CASE_INSENSITIVE).matcher(value).find();
    }

    private boolean setBlocksContainMultipleSetStatements(String setBlocks) {
        Matcher matcher = Pattern.compile("\\bset\\b", Pattern.CASE_INSENSITIVE).matcher(setBlocks);
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count > 1 && !setBlocks.substring(0, matcher.start()).contains("</if")) {
                return true;
            }
        }
        return false;
    }

    private ForeachBlock readForeach(String block) {
        Matcher matcher = FOREACH_TAG_PATTERN.matcher(block);
        if (!matcher.matches()) {
            return null;
        }
        String openingTag = matcher.group("opening");
        String collection = xmlAttribute(openingTag, "collection");
        String item = xmlAttribute(openingTag, "item");
        if (collection == null || item == null) {
            return null;
        }
        return new ForeachBlock(
                collection,
                item,
                defaultString(xmlAttribute(openingTag, "index")),
                defaultString(xmlAttribute(openingTag, "open")),
                defaultString(xmlAttribute(openingTag, "separator")),
                matcher.group("body")
        );
    }

    private String xmlAttribute(String openingTag, String attributeName) {
        Pattern pattern = Pattern.compile(
                "(?is)\\b" + Pattern.quote(attributeName) + "\\s*=\\s*([\"'])(.*?)\\1"
        );
        Matcher matcher = pattern.matcher(openingTag);
        return matcher.find() ? matcher.group(2) : null;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String collectionBase(String collection, String suffix) {
        if (!collection.endsWith(suffix)) {
            return null;
        }
        String base = collection.substring(0, collection.length() - suffix.length());
        return base.matches("[A-Za-z_][A-Za-z0-9_.]*") ? base : null;
    }

    private boolean isDynamicValuesUpdateBody(String body, String keyName) {
        Pattern pattern = Pattern.compile(
                "(?is)^\\s*[`\"]?\\$\\{"
                        + Pattern.quote(keyName)
                        + "}[`\"]?\\s*=\\s*VALUES\\s*\\(\\s*[`\"]?\\$\\{"
                        + Pattern.quote(keyName)
                        + "}[`\"]?\\s*\\)\\s*$"
        );
        return pattern.matcher(body).matches();
    }

    private boolean isDynamicColumnBody(String body, String keyName) {
        Pattern pattern = Pattern.compile(
                "(?is)^\\s*[`\"]?\\$\\{"
                        + Pattern.quote(keyName)
                        + "}[`\"]?\\s*$"
        );
        return pattern.matcher(body).matches();
    }

    private List<String> splitTopLevelComma(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int xmlDepth = 0;
        int start = 0;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\'' || current == '"') {
                index = skipQuoted(value, index, current);
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (current == '<') {
                XmlTag tag = readXmlTag(value, index);
                if (tag == null) {
                    int tagEnd = findXmlTagEnd(value, index);
                    index = tagEnd < 0 ? value.length() : tagEnd + 1;
                } else {
                    if (!tag.selfClosing() && !"include".equalsIgnoreCase(tag.name())) {
                        xmlDepth += tag.closing() ? -1 : 1;
                        if (xmlDepth < 0) {
                            xmlDepth = 0;
                        }
                    }
                    index = tag.endIndex();
                }
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (current == ',' && depth == 0 && xmlDepth == 0) {
                parts.add(value.substring(start, index));
                index++;
                start = index;
            } else {
                index++;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    private String outerParenthesizedContent(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("(")) {
            return "";
        }
        int closeIndex = findMatchingParen(trimmed, 0);
        if (closeIndex != trimmed.length() - 1) {
            return "";
        }
        return trimmed.substring(1, closeIndex);
    }

    private int findMatchingParen(String value, int openIndex) {
        int depth = 0;
        int index = openIndex;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\'' || current == '"') {
                index = skipQuoted(value, index, current);
            } else if (startsMyBatisPlaceholder(value, index)) {
                index = skipMyBatisPlaceholder(value, index);
            } else if (current == '<') {
                int tagEnd = findXmlTagEnd(value, index);
                index = tagEnd < 0 ? value.length() : tagEnd + 1;
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

    private int appendQuoted(String value, int start, StringBuilder target, char quote) {
        QuotedText quoted = readQuotedText(value, start, quote);
        if (!quoted.closed()) {
            target.append(value, start, value.length());
            return value.length();
        }
        target.append(value, start, quoted.nextIndex());
        return quoted.nextIndex();
    }

    private QuotedText readQuotedText(String value, int start, char quote) {
        StringBuilder content = new StringBuilder();
        int index = start + 1;
        while (index < value.length()) {
            char current = value.charAt(index);
            index++;
            if (current == quote) {
                if (index < value.length() && value.charAt(index) == quote) {
                    content.append(current);
                    index++;
                } else {
                    return new QuotedText(content.toString(), index, true);
                }
            } else {
                content.append(current);
            }
        }
        return new QuotedText("", start, false);
    }

    private int skipQuoted(String value, int start, char quote) {
        int index = start + 1;
        while (index < value.length()) {
            char current = value.charAt(index);
            index++;
            if (current == quote) {
                if (index < value.length() && value.charAt(index) == quote) {
                    index++;
                } else {
                    break;
                }
            }
        }
        return index;
    }

    private boolean startsMyBatisPlaceholder(String value, int index) {
        return index + 1 < value.length()
                && (value.charAt(index) == '#' || value.charAt(index) == '$')
                && value.charAt(index + 1) == '{';
    }

    private int skipMyBatisPlaceholder(String value, int start) {
        int index = start + 2;
        while (index < value.length() && value.charAt(index) != '}') {
            index++;
        }
        return index < value.length() ? index + 1 : value.length();
    }

    private List<BatchUpdateAssignment> updateAssignments(String updateClause) {
        List<BatchUpdateAssignment> assignments = new ArrayList<>();
        for (String assignment : splitTopLevelComma(updateClause)) {
            Matcher valuesMatcher = Pattern.compile(
                    "(?is)^\\s*(?<target>`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*values\\s*\\(\\s*(?<source>`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*\\)\\s*$"
            ).matcher(assignment);
            if (valuesMatcher.matches()) {
                String target = valuesMatcher.group("target");
                String source = valuesMatcher.group("source");
                if (!normalizeIdentifier(target).equals(normalizeIdentifier(source))) {
                    return null;
                }
                assignments.add(new BatchUpdateAssignment(target, source, true));
                continue;
            }

            Matcher constantMatcher = Pattern.compile(
                    "(?is)^\\s*(?<target>`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*"
                            + "(?<constant>NULL|[-+]?\\d+(?:\\.\\d+)?|N?'(?:''|[^'])*'|"
                            + "NOW\\s*\\(\\s*\\)|CURRENT_TIMESTAMP(?:\\s*\\(\\s*\\))?)\\s*$"
            ).matcher(assignment);
            if (constantMatcher.matches()) {
                assignments.add(new BatchUpdateAssignment(
                        constantMatcher.group("target"),
                        constantMatcher.group("constant").trim(),
                        false
                ));
                continue;
            }

            Matcher selfAssignmentMatcher = Pattern.compile(
                    "(?is)^\\s*(?<target>`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*(?<source>`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*$"
            ).matcher(assignment);
            if (!selfAssignmentMatcher.matches()
                    || !normalizeIdentifier(selfAssignmentMatcher.group("target"))
                    .equals(normalizeIdentifier(selfAssignmentMatcher.group("source")))) {
                return null;
            }
            assignments.add(new BatchUpdateAssignment(
                    selfAssignmentMatcher.group("target"),
                    selfAssignmentMatcher.group("source"),
                    false
            ));
        }
        return assignments;
    }

    private boolean isNoOpSelfAssignment(BatchUpdateAssignment assignment) {
        return !assignment.valuesReference()
                && normalizeIdentifier(assignment.target())
                .equals(normalizeIdentifier(assignment.sourceExpression()))
                && !assignment.sourceExpression().matches("(?is)NULL|[-+]?\\d+(?:\\.\\d+)?|N?'(?:''|[^'])*'");
    }

    private List<String> normalizeIdentifiers(List<String> identifiers) {
        return identifiers.stream()
                .map(this::normalizeIdentifier)
                .toList();
    }

    private int indexOfIdentifier(List<String> identifiers, String normalizedIdentifier) {
        for (int i = 0; i < identifiers.size(); i++) {
            if (normalizeIdentifier(identifiers.get(i)).equals(normalizedIdentifier)) {
                return i;
            }
        }
        return -1;
    }

    private String normalizeIdentifier(String identifier) {
        String unquoted = unquoteIdentifier(identifier);
        int dotIndex = unquoted.lastIndexOf('.');
        if (dotIndex >= 0) {
            unquoted = unquoted.substring(dotIndex + 1);
        }
        return unquoted.toLowerCase();
    }

    private String dmIdentifier(String identifier) {
        String unquoted = unquoteIdentifier(identifier);
        if (unquoted.contains("${") || unquoted.contains("#{")) {
            return unquoted;
        }
        if (unquoted.matches("[A-Za-z_][A-Za-z0-9_$]*")
                && !DAMENG_IDENTIFIER_QUOTES.contains(unquoted.toUpperCase())) {
            return unquoted;
        }
        return "\"" + unquoted.replace("\"", "\"\"") + "\"";
    }

    private String quoteDynamicAliasIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String unquoteIdentifier(String identifier) {
        String trimmed = identifier == null ? "" : identifier.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("\"\"", "\"");
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("`") && trimmed.endsWith("`")) {
            return trimmed.substring(1, trimmed.length() - 1).replace("``", "`");
        }
        return trimmed;
    }

    private String indentationOfLastLine(String whitespace) {
        int newlineIndex = Math.max(whitespace.lastIndexOf('\n'), whitespace.lastIndexOf('\r'));
        return newlineIndex < 0 ? whitespace : whitespace.substring(newlineIndex + 1);
    }

    private String addMissingBatchInsertValues(String body) {
        Matcher matcher = INSERT_TRIM_THEN_FOREACH_PATTERN.matcher(body);
        StringBuffer converted = new StringBuffer(body.length());
        boolean changed = false;
        while (matcher.find()) {
            String whitespace = matcher.group(2);
            String separator = whitespace.isEmpty() ? " " : whitespace;
            matcher.appendReplacement(
                    converted,
                    Matcher.quoteReplacement(matcher.group(1) + separator + "values" + separator + matcher.group(3))
            );
            changed = true;
        }
        matcher.appendTail(converted);
        return changed ? converted.toString() : body;
    }

    private TextRewrite conditionalizeGeneratedKeyBatchInsert(
            String body,
            String generatedKeyProperty,
            String generatedKeyColumn,
            String tableName
    ) {
        Matcher matcher = GENERATED_KEY_BATCH_INSERT_PATTERN.matcher(body);
        if (!matcher.matches()
                || generatedKeyProperty.contains(",")
                || (generatedKeyColumn != null && generatedKeyColumn.contains(","))
                || tableName == null
                || tableName.isBlank()) {
            return new TextRewrite(body, false);
        }
        String property = leafProperty(generatedKeyProperty);
        ForeachBlock foreach = readForeach(matcher.group("foreach"));
        String columnsBody = trimColumnList(matcher.group("columnsTrim"));
        String tupleBody = foreach == null ? "" : outerParenthesizedContent(foreach.body());
        List<String> columns = splitTopLevelComma(columnsBody).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        List<String> values = splitTopLevelComma(tupleBody).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        if (foreach == null
                || !isSimplePropertyPath(foreach.collection())
                || !isSimplePropertyPath(foreach.item())
                || property.isBlank()
                || columns.size() < 2
                || columns.size() != values.size()) {
            return new TextRewrite(body, false);
        }

        String configuredColumn = generatedKeyColumn == null ? "" : generatedKeyColumn.trim();
        String expectedColumn = configuredColumn.isBlank() ? property : configuredColumn;
        if (!sameGeneratedKeyColumn(columns.get(0), expectedColumn, property)) {
            return new TextRewrite(body, false);
        }
        String parameterName = simpleMyBatisParameterName(values.get(0));
        if (!parameterName.equals(foreach.item() + "." + property)) {
            return new TextRewrite(body, false);
        }

        String explicitKeyFlag = "_dmAdapterHasExplicit"
                + Character.toUpperCase(property.charAt(0))
                + property.substring(1);
        String flagExpression = foreach.collection() + " != null and !"
                + foreach.collection() + ".isEmpty() and "
                + foreach.collection() + ".{? #this." + property + " != null}.size() > 0";
        String condition = explicitKeyFlag;
        String columnsTrim = conditionalGeneratedKeyColumns(
                matcher.group("columnsTrim"),
                columns,
                condition
        );
        String foreachXml = conditionalGeneratedKeyValues(
                matcher.group("foreach"),
                foreach,
                values,
                condition
        );
        if (columnsTrim == null || foreachXml == null) {
            return new TextRewrite(body, false);
        }
        String converted = "<bind name=\"" + explicitKeyFlag + "\" value=\"" + flagExpression + "\"/>\n"
                + "<if test=\"" + explicitKeyFlag + "\">\n"
                + "    SET IDENTITY_INSERT " + tableName + " ON WITH REPLACE NULL;\n"
                + "</if>\n"
                + matcher.group("leading")
                + columnsTrim
                + matcher.group("valuesKeyword")
                + foreachXml
                + "\n<if test=\"" + explicitKeyFlag + "\">\n"
                + "    ; SET IDENTITY_INSERT " + tableName + " OFF\n"
                + "</if>"
                + matcher.group("trailing");
        return new TextRewrite(converted, !converted.equals(body));
    }

    private String collapseDuplicateStandalonePlaceholderValue(String value) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*(?<placeholder>[#$]\\{[^}]+})\\s+\\k<placeholder>\\s*$"
        ).matcher(value);
        return matcher.matches() ? matcher.group("placeholder") : value;
    }

    private String collapseAdjacentDuplicateStandalonePlaceholders(String value) {
        return Pattern.compile(
                "(?is)(?<placeholder>[#$]\\{[^}]+})(?<space>[ \\t]*(?:\\r\\n|\\r|\\n)[ \\t]*)"
                        + "\\k<placeholder>"
        ).matcher(value).replaceAll("${placeholder}${space}");
    }

    private TextRewrite wrapIdentityInsert(String sql, String tableName) {
        if (sql == null
                || sql.isBlank()
                || tableName == null
                || tableName.isBlank()
                || Pattern.compile("(?is)\\bSET\\s+IDENTITY_INSERT\\b").matcher(sql).find()) {
            return new TextRewrite(sql, false);
        }
        int leadingEnd = 0;
        while (leadingEnd < sql.length() && Character.isWhitespace(sql.charAt(leadingEnd))) {
            leadingEnd++;
        }
        int trailingStart = sql.length();
        while (trailingStart > leadingEnd && Character.isWhitespace(sql.charAt(trailingStart - 1))) {
            trailingStart--;
        }
        String core = sql.substring(leadingEnd, trailingStart).stripTrailing();
        if (core.endsWith(";")) {
            core = core.substring(0, core.length() - 1).stripTrailing();
        }
        String converted = sql.substring(0, leadingEnd)
                + "SET IDENTITY_INSERT " + tableName + " ON WITH REPLACE NULL;\n"
                + core
                + ";\nSET IDENTITY_INSERT " + tableName + " OFF"
                + sql.substring(trailingStart);
        return new TextRewrite(converted, !converted.equals(sql));
    }

    private String conditionalGeneratedKeyColumns(
            String trimXml,
            List<String> columns,
            String condition
    ) {
        Matcher trimMatcher = TRIM_TAG_PATTERN.matcher(trimXml);
        if (!trimMatcher.matches()) {
            return null;
        }
        String body = trimMatcher.group("body");
        String itemIndent = firstContentIndent(body);
        String closingIndent = trailingIndent(body);
        StringBuilder converted = new StringBuilder(trimXml.length() + 120);
        converted.append(trimMatcher.group("opening"))
                .append("\n")
                .append(itemIndent)
                .append("<if test=\"")
                .append(condition)
                .append("\">\n")
                .append(itemIndent)
                .append("    ")
                .append(columns.get(0))
                .append(",\n")
                .append(itemIndent)
                .append("</if>\n");
        for (int i = 1; i < columns.size(); i++) {
            converted.append(itemIndent)
                    .append(columns.get(i))
                    .append(",\n");
        }
        converted.append(closingIndent).append("</trim>");
        return converted.toString();
    }

    private String conditionalGeneratedKeyValues(
            String foreachXml,
            ForeachBlock foreach,
            List<String> values,
            String condition
    ) {
        String rawForeachBody = foreach.body();
        String trimmed = rawForeachBody.trim();
        if (!trimmed.startsWith("(")) {
            return null;
        }
        int rawOpen = rawForeachBody.indexOf('(');
        int rawClose = findMatchingParen(rawForeachBody, rawOpen);
        if (rawOpen < 0 || rawClose < 0) {
            return null;
        }
        String inside = rawForeachBody.substring(rawOpen + 1, rawClose);
        String itemIndent = firstContentIndent(inside);
        String closingIndent = trailingIndent(inside);
        StringBuilder convertedInside = new StringBuilder(inside.length() + 120);
        convertedInside.append("\n")
                .append(itemIndent)
                .append("<if test=\"")
                .append(condition)
                .append("\">\n")
                .append(itemIndent)
                .append("    ")
                .append(values.get(0))
                .append(",\n")
                .append(itemIndent)
                .append("</if>\n");
        for (int i = 1; i < values.size(); i++) {
            convertedInside.append(itemIndent)
                    .append(values.get(i));
            if (i + 1 < values.size()) {
                convertedInside.append(",");
            }
            convertedInside.append("\n");
        }
        convertedInside.append(closingIndent);
        String convertedBody = rawForeachBody.substring(0, rawOpen + 1)
                + convertedInside
                + rawForeachBody.substring(rawClose);
        return foreach.withBody(convertedBody).toXml();
    }

    private String firstContentIndent(String body) {
        int first = 0;
        while (first < body.length() && Character.isWhitespace(body.charAt(first))) {
            first++;
        }
        int newline = first <= 0 ? -1 : Math.max(
                body.lastIndexOf('\n', first - 1),
                body.lastIndexOf('\r', first - 1)
        );
        return newline < 0 ? "    " : body.substring(newline + 1, first);
    }

    private String trailingIndent(String body) {
        int last = body.length();
        while (last > 0 && Character.isWhitespace(body.charAt(last - 1))) {
            last--;
        }
        int newline = Math.max(body.lastIndexOf('\n', last), body.lastIndexOf('\r', last));
        return newline < 0 ? "" : body.substring(newline + 1);
    }

    private String leafProperty(String property) {
        String trimmed = property == null ? "" : property.trim();
        int dot = trimmed.lastIndexOf('.');
        return dot < 0 ? trimmed : trimmed.substring(dot + 1);
    }

    private boolean isSimplePropertyPath(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_.]*");
    }

    private boolean sameGeneratedKeyColumn(String column, String expectedColumn, String property) {
        String normalizedColumn = normalizeIdentifier(column);
        return normalizedColumn.equals(normalizeIdentifier(expectedColumn))
                || normalizedColumn.equals(camelToSnake(property));
    }

    private String camelToSnake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private TextRewrite qualifyBatchInsertListItemReferences(String body) {
        Matcher matcher = INSERT_TRIM_VALUES_FOREACH_PATTERN.matcher(body);
        StringBuffer converted = new StringBuffer(body.length());
        boolean changed = false;
        while (matcher.find()) {
            String prefix = matcher.group("prefix");
            String foreachBlock = matcher.group("foreach");
            PreservedForeach preservedForeach = readPreservedForeach(foreachBlock);
            if (preservedForeach == null || !isIndexableBatchCollectionName(preservedForeach.collection())) {
                continue;
            }

            Set<String> columnProperties = simpleColumnIfProperties(prefix);
            if (columnProperties.isEmpty()) {
                continue;
            }

            TextRewrite foreachRewrite = qualifyForeachItemReferences(
                    preservedForeach.body(),
                    preservedForeach.item(),
                    preservedForeach.index(),
                    columnProperties
            );
            if (!foreachRewrite.changed()) {
                continue;
            }

            TextRewrite prefixRewrite = qualifyBatchInsertColumnTests(
                    prefix,
                    preservedForeach.collection(),
                    columnProperties
            );
            String replacement = prefixRewrite.text()
                    + preservedForeach.openingTag()
                    + foreachRewrite.text()
                    + preservedForeach.closingTag();
            matcher.appendReplacement(converted, Matcher.quoteReplacement(replacement));
            changed = true;
        }
        matcher.appendTail(converted);
        return new TextRewrite(changed ? converted.toString() : body, changed);
    }

    private TextRewrite addMissingForeachTupleCommas(String body) {
        Matcher matcher = INSERT_VALUES_FOREACH_PATTERN.matcher(body);
        StringBuffer converted = new StringBuffer(body.length());
        boolean changed = false;
        while (matcher.find()) {
            PreservedForeach preservedForeach = readPreservedForeach(matcher.group("foreach"));
            if (preservedForeach == null) {
                continue;
            }
            TextRewrite tuple = addMissingForeachTupleBodyCommas(preservedForeach.body());
            if (!tuple.changed()) {
                continue;
            }
            String replacement = matcher.group("prefix")
                    + preservedForeach.openingTag()
                    + tuple.text()
                    + preservedForeach.closingTag();
            matcher.appendReplacement(converted, Matcher.quoteReplacement(replacement));
            changed = true;
        }
        matcher.appendTail(converted);
        return new TextRewrite(changed ? converted.toString() : body, changed);
    }

    private TextRewrite addMissingForeachTupleBodyCommas(String foreachBody) {
        ParenthesizedBody parenthesizedBody = outerParenthesizedBody(foreachBody);
        if (parenthesizedBody == null) {
            return new TextRewrite(foreachBody, false);
        }
        String rewritten = ADJACENT_MYBATIS_PLACEHOLDER_LINES_PATTERN
                .matcher(parenthesizedBody.content())
                .replaceAll("$1,$2$3$4");
        if (rewritten.equals(parenthesizedBody.content())) {
            return new TextRewrite(foreachBody, false);
        }
        return new TextRewrite(
                parenthesizedBody.leading() + "(" + rewritten + ")" + parenthesizedBody.trailing(),
                true
        );
    }

    private PreservedForeach readPreservedForeach(String block) {
        Matcher matcher = FOREACH_WITH_BODY_PATTERN.matcher(block);
        if (!matcher.matches()) {
            return null;
        }
        String openingTag = matcher.group("opening");
        String collection = xmlAttribute(openingTag, "collection");
        String item = xmlAttribute(openingTag, "item");
        if (collection == null || item == null || item.isBlank()) {
            return null;
        }
        return new PreservedForeach(
                openingTag,
                matcher.group("body"),
                matcher.group("closing"),
                collection,
                item,
                defaultString(xmlAttribute(openingTag, "index"))
        );
    }

    private boolean isIndexableBatchCollectionName(String collection) {
        if (collection == null || !collection.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
            return false;
        }
        String lower = collection.toLowerCase(Locale.ROOT);
        return "list".equals(lower) || "array".equals(lower) || lower.endsWith("list");
    }

    private Set<String> simpleColumnIfProperties(String prefix) {
        Set<String> properties = new LinkedHashSet<>();
        Matcher matcher = IF_OPENING_TAG_PATTERN.matcher(prefix);
        while (matcher.find()) {
            String property = simpleNullTestProperty(matcher.group(2));
            if (!property.isBlank()) {
                properties.add(property);
            }
        }
        return properties;
    }

    private TextRewrite qualifyForeachItemReferences(
            String body,
            String item,
            String index,
            Set<String> propertyNames
    ) {
        TextRewrite parameterRewrite = qualifyMyBatisSimpleParameters(body, item, index, propertyNames);
        TextRewrite testRewrite = qualifyIfTestAttributes(parameterRewrite.text(), propertyNames, item + ".");
        boolean changed = parameterRewrite.changed() || testRewrite.changed();
        return new TextRewrite(changed ? testRewrite.text() : body, changed);
    }

    private TextRewrite qualifyMyBatisSimpleParameters(
            String value,
            String item,
            String index,
            Set<String> propertyNames
    ) {
        Matcher matcher = MYBATIS_SIMPLE_PARAMETER_PATTERN.matcher(value);
        StringBuffer converted = new StringBuffer(value.length());
        boolean changed = false;
        while (matcher.find()) {
            String name = matcher.group(2);
            if (!propertyNames.contains(name) || name.equals(item) || name.equals(index)) {
                continue;
            }
            matcher.appendReplacement(
                    converted,
                    Matcher.quoteReplacement(matcher.group(1) + item + "." + name + matcher.group(3) + "}")
            );
            changed = true;
        }
        matcher.appendTail(converted);
        return new TextRewrite(changed ? converted.toString() : value, changed);
    }

    private TextRewrite qualifyIfTestAttributes(String value, Set<String> propertyNames, String qualifier) {
        Matcher matcher = IF_TEST_ATTRIBUTE_PATTERN.matcher(value);
        StringBuffer converted = new StringBuffer(value.length());
        boolean changed = false;
        while (matcher.find()) {
            TextRewrite testRewrite = qualifyBarePropertyReferences(matcher.group(3), propertyNames, qualifier);
            if (!testRewrite.changed()) {
                continue;
            }
            matcher.appendReplacement(
                    converted,
                    Matcher.quoteReplacement(
                            matcher.group(1)
                                    + matcher.group(2)
                                    + testRewrite.text()
                                    + matcher.group(4)
                                    + matcher.group(5)
                    )
            );
            changed = true;
        }
        matcher.appendTail(converted);
        return new TextRewrite(changed ? converted.toString() : value, changed);
    }

    private TextRewrite qualifyBatchInsertColumnTests(
            String prefix,
            String collection,
            Set<String> propertyNames
    ) {
        Matcher matcher = IF_TEST_ATTRIBUTE_PATTERN.matcher(prefix);
        StringBuffer converted = new StringBuffer(prefix.length());
        boolean changed = false;
        while (matcher.find()) {
            String property = simpleNullTestProperty(matcher.group(3));
            if (property.isBlank() || !propertyNames.contains(property)) {
                continue;
            }
            String replacementTest = collection
                    + " != null and "
                    + collection
                    + ".size() &gt; 0 and "
                    + collection
                    + "[0]."
                    + property
                    + " != null";
            matcher.appendReplacement(
                    converted,
                    Matcher.quoteReplacement(
                            matcher.group(1)
                                    + matcher.group(2)
                                    + replacementTest
                                    + matcher.group(4)
                                    + matcher.group(5)
                    )
            );
            changed = true;
        }
        matcher.appendTail(converted);
        return new TextRewrite(changed ? converted.toString() : prefix, changed);
    }

    private String simpleNullTestProperty(String expression) {
        Matcher matcher = SIMPLE_NULL_TEST_PATTERN.matcher(expression == null ? "" : expression);
        return matcher.matches() ? matcher.group(1) : "";
    }

    private List<ConditionalTrimItem> conditionalTrimItems(String trimBody) {
        if (trimBody == null || trimBody.isBlank()) {
            return List.of();
        }
        Matcher matcher = IF_BLOCK_PATTERN.matcher(trimBody);
        List<ConditionalTrimItem> items = new ArrayList<>();
        int previousEnd = 0;
        while (matcher.find()) {
            if (!trimBody.substring(previousEnd, matcher.start()).isBlank()) {
                return List.of();
            }
            String opening = matcher.group("opening");
            String test = defaultString(xmlAttribute(opening, "test")).trim();
            String content = matcher.group("body");
            if (test.isBlank() || stripTrailingComma(content).trim().isBlank()) {
                return List.of();
            }
            items.add(new ConditionalTrimItem(opening, test, content));
            previousEnd = matcher.end();
        }
        if (!trimBody.substring(previousEnd).isBlank()) {
            return List.of();
        }
        return items;
    }

    private String stripTrailingComma(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith(",")) {
            return trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private TextRewrite addMissingStaticWhereAnd(String body) {
        List<TextReplacement> replacements = new ArrayList<>();
        boolean inBareWhereClause = false;
        boolean previousPredicateNeedsConnector = false;

        int lineStart = 0;
        while (lineStart < body.length()) {
            int lineEnd = body.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = body.length();
            }
            int contentEnd = lineEnd;
            if (contentEnd > lineStart && body.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = body.substring(lineStart, contentEnd);
            String stripped = line.strip();
            if (isIgnorableSqlLine(stripped)) {
                lineStart = nextLineStart(body, lineEnd);
                continue;
            }
            if (BARE_WHERE_LINE_PATTERN.matcher(line).matches()) {
                inBareWhereClause = true;
                previousPredicateNeedsConnector = false;
                lineStart = nextLineStart(body, lineEnd);
                continue;
            }
            if (!inBareWhereClause) {
                lineStart = nextLineStart(body, lineEnd);
                continue;
            }
            if (WHERE_CLAUSE_BOUNDARY_LINE_PATTERN.matcher(line).find()) {
                inBareWhereClause = false;
                previousPredicateNeedsConnector = false;
                lineStart = nextLineStart(body, lineEnd);
                continue;
            }
            if (WHERE_CONNECTOR_LINE_PATTERN.matcher(line).find()) {
                previousPredicateNeedsConnector = staticWherePredicateNeedsConnector(line);
                lineStart = nextLineStart(body, lineEnd);
                continue;
            }
            boolean currentPredicate = isStaticWherePredicateLine(line);
            if (previousPredicateNeedsConnector
                    && currentPredicate
                    && isFollowedByForeachTag(body, nextLineStart(body, lineEnd))) {
                int insertionIndex = firstNonWhitespaceIndex(body, lineStart, contentEnd);
                replacements.add(new TextReplacement(insertionIndex, insertionIndex, "and "));
            }
            previousPredicateNeedsConnector = staticWherePredicateNeedsConnector(line);
            lineStart = nextLineStart(body, lineEnd);
        }
        return applyTextReplacements(body, replacements);
    }

    private boolean isIgnorableSqlLine(String stripped) {
        return stripped.isEmpty()
                || stripped.startsWith("--")
                || stripped.startsWith("/*")
                || stripped.startsWith("<!--");
    }

    private boolean staticWherePredicateNeedsConnector(String line) {
        String stripped = line.stripLeading();
        return isStaticWherePredicateLine(line)
                && !WHERE_TRAILING_OPEN_CONNECTOR_PATTERN.matcher(stripped).matches();
    }

    private boolean isStaticWherePredicateLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String stripped = line.stripLeading();
        if (stripped.startsWith("<")
                || stripped.startsWith(")")
                || WHERE_TRAILING_CONNECTOR_PATTERN.matcher(stripped).matches()
                || stripped.contains("<![CDATA[")) {
            return false;
        }
        return WHERE_PREDICATE_START_PATTERN.matcher(stripped).find()
                || WHERE_FUNCTION_PREDICATE_START_PATTERN.matcher(stripped).find();
    }

    private boolean isFollowedByForeachTag(String body, int start) {
        int lineStart = start;
        while (lineStart < body.length()) {
            int lineEnd = body.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = body.length();
            }
            int contentEnd = lineEnd;
            if (contentEnd > lineStart && body.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String stripped = body.substring(lineStart, contentEnd).strip();
            if (isIgnorableSqlLine(stripped)) {
                lineStart = nextLineStart(body, lineEnd);
                continue;
            }
            return stripped.regionMatches(true, 0, "<foreach", 0, "<foreach".length());
        }
        return false;
    }

    private TextRewrite addMissingDynamicWhereAnd(String body) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < body.length()) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "where".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "where", body.length());
                if (closingStart < 0) {
                    index = tag.endIndex();
                    continue;
                }
                addMissingDynamicWhereAndInRange(body, tag.endIndex(), closingStart, replacements);
                int closingEnd = body.indexOf('>', closingStart + 1);
                index = closingEnd < 0 ? closingStart + 1 : closingEnd + 1;
            } else {
                index = tag.endIndex();
            }
        }
        return applyTextReplacements(body, replacements);
    }

    private void addMissingDynamicWhereAndInRange(
            String body,
            int start,
            int end,
            List<TextReplacement> replacements
    ) {
        String lastMeaningfulLine = "";
        int index = start;
        while (index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                lastMeaningfulLine = addMissingDynamicWhereAndInText(
                        body,
                        index,
                        end,
                        lastMeaningfulLine
                );
                return;
            }
            if (tagStart > index) {
                lastMeaningfulLine = addMissingDynamicWhereAndInText(
                        body,
                        index,
                        tagStart,
                        lastMeaningfulLine
                );
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "if".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "if", end);
                if (closingStart >= 0) {
                    int closingEnd = body.indexOf('>', closingStart + 1);
                    if (closingEnd >= 0 && closingEnd + 1 <= end) {
                        String ifBody = body.substring(tag.endIndex(), closingStart);
                        String firstLine = firstMeaningfulSqlLine(ifBody);
                        if (isDynamicWherePredicateFragment(ifBody)
                                && !startsWithWhereConnector(firstLine)
                                && !isAfterOpenWhereConnector(lastMeaningfulLine)) {
                            int insertionIndex = firstNonWhitespaceIndex(body, tag.endIndex(), closingStart);
                            replacements.add(new TextReplacement(insertionIndex, insertionIndex, "and "));
                            lastMeaningfulLine = "and " + firstLine;
                        } else if (!firstLine.isBlank()) {
                            lastMeaningfulLine = firstLine;
                        }
                        index = closingEnd + 1;
                        continue;
                    }
                }
            } else if (!tag.closing() && !tag.selfClosing() && isNestedDynamicWhereContainer(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), tag.name(), end);
                if (closingStart >= 0) {
                    int closingEnd = body.indexOf('>', closingStart + 1);
                    index = closingEnd < 0 ? closingStart + 1 : closingEnd + 1;
                    continue;
                }
            }
            index = tag.endIndex();
        }
    }

    private String addMissingDynamicWhereAndInText(
            String body,
            int start,
            int end,
            String lastMeaningfulLine
    ) {
        int lineStart = start;
        String previousLine = lastMeaningfulLine;
        while (lineStart < end) {
            int lineEnd = body.indexOf('\n', lineStart);
            if (lineEnd < 0 || lineEnd >= end) {
                lineEnd = end;
            }
            int contentEnd = lineEnd;
            if (contentEnd > lineStart && body.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            String line = body.substring(lineStart, contentEnd);
            String stripped = line.strip();
            if (!isIgnorableSqlLine(stripped)) {
                previousLine = stripped;
            }
            lineStart = lineEnd < end ? lineEnd + 1 : end;
        }
        return previousLine;
    }

    private boolean isDynamicWherePredicateFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return false;
        }
        if (DYNAMIC_XML_TAG_PATTERN.matcher(fragment).find()) {
            return false;
        }
        String firstLine = firstMeaningfulSqlLine(fragment);
        return !firstLine.isBlank()
                && (startsWithWhereConnector(firstLine) || isStaticWherePredicateLine(firstLine));
    }

    private boolean isNestedDynamicWhereContainer(String tagName) {
        if (tagName == null) {
            return false;
        }
        String normalized = tagName.toLowerCase(Locale.ROOT);
        return "foreach".equals(normalized)
                || "choose".equals(normalized)
                || "when".equals(normalized)
                || "otherwise".equals(normalized)
                || "trim".equals(normalized);
    }

    private String firstMeaningfulSqlLine(String fragment) {
        int lineStart = 0;
        while (lineStart < fragment.length()) {
            int lineEnd = fragment.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = fragment.length();
            }
            String line = fragment.substring(lineStart, lineEnd).strip();
            if (!isIgnorableSqlLine(line)) {
                return line;
            }
            lineStart = lineEnd < fragment.length() ? lineEnd + 1 : fragment.length();
        }
        return "";
    }

    private boolean startsWithWhereConnector(String value) {
        return WHERE_CONNECTOR_LINE_PATTERN.matcher(value == null ? "" : value).find();
    }

    private boolean isAfterOpenWhereConnector(String previousLine) {
        if (previousLine == null || previousLine.isBlank()) {
            return false;
        }
        return WHERE_TRAILING_OPEN_CONNECTOR_PATTERN.matcher(previousLine).matches()
                || WHERE_TRAILING_CONNECTOR_PATTERN.matcher(previousLine).matches();
    }

    private int nextLineStart(String value, int lineEnd) {
        return lineEnd < value.length() ? lineEnd + 1 : value.length();
    }

    private int firstNonWhitespaceIndex(String value, int start, int end) {
        int index = start;
        while (index < end && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private TextRewrite addMissingDynamicSetCommas(String body) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < body.length()) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "set".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "set", body.length());
                if (closingStart < 0) {
                    index = tag.endIndex();
                    continue;
                }
                addMissingSetCommasInRange(body, tag.endIndex(), closingStart, replacements);
                int closingEnd = body.indexOf('>', closingStart + 1);
                index = closingEnd < 0 ? closingStart + 1 : closingEnd + 1;
            } else {
                index = tag.endIndex();
            }
        }
        return applyTextReplacements(body, replacements);
    }

    private TextRewrite mergeConsecutiveDynamicSetTrimBlocks(String body) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < body.length()) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!isSetTrimOpening(body, tagStart, tag)) {
                index = tag.endIndex();
                continue;
            }

            int currentClosingStart = findClosingTag(body, tag.endIndex(), "trim", body.length());
            if (currentClosingStart < 0) {
                index = tag.endIndex();
                continue;
            }
            int currentClosingEnd = xmlTagEnd(body, currentClosingStart);
            while (currentClosingEnd > currentClosingStart) {
                int nextStart = skipWhitespace(body, currentClosingEnd);
                XmlTag nextTag = nextStart < body.length() && body.charAt(nextStart) == '<'
                        ? readXmlTag(body, nextStart)
                        : null;
                if (!isSetTrimOpening(body, nextStart, nextTag)) {
                    break;
                }
                replacements.add(new TextReplacement(
                        currentClosingStart,
                        nextTag.endIndex(),
                        body.substring(currentClosingEnd, nextStart)
                ));
                int nextClosingStart = findClosingTag(body, nextTag.endIndex(), "trim", body.length());
                if (nextClosingStart < 0) {
                    break;
                }
                currentClosingStart = nextClosingStart;
                currentClosingEnd = xmlTagEnd(body, currentClosingStart);
            }
            index = currentClosingEnd > currentClosingStart ? currentClosingEnd : tag.endIndex();
        }
        replacements.sort((left, right) -> Integer.compare(left.startIndex(), right.startIndex()));
        return applyTextReplacements(body, replacements);
    }

    private TextRewrite normalizeDynamicSetPropertyColumns(
            String body,
            Map<String, String> resultMapColumnByProperty
    ) {
        if (resultMapColumnByProperty == null || resultMapColumnByProperty.isEmpty()) {
            return new TextRewrite(body, false);
        }
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < body.length()) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "set".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "set", body.length());
                if (closingStart < 0) {
                    index = tag.endIndex();
                    continue;
                }
                normalizeSetPropertyColumnsInRange(
                        body,
                        tag.endIndex(),
                        closingStart,
                        resultMapColumnByProperty,
                        replacements
                );
                int closingEnd = body.indexOf('>', closingStart + 1);
                index = closingEnd < 0 ? closingStart + 1 : closingEnd + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && isSetTrimOpening(body, tagStart, tag)) {
                int closingStart = findClosingTag(body, tag.endIndex(), "trim", body.length());
                if (closingStart < 0) {
                    index = tag.endIndex();
                    continue;
                }
                normalizeSetPropertyColumnsInRange(
                        body,
                        tag.endIndex(),
                        closingStart,
                        resultMapColumnByProperty,
                        replacements
                );
                int closingEnd = body.indexOf('>', closingStart + 1);
                index = closingEnd < 0 ? closingStart + 1 : closingEnd + 1;
                continue;
            }
            index = tag.endIndex();
        }
        replacements.sort((left, right) -> Integer.compare(left.startIndex(), right.startIndex()));
        return applyTextReplacements(body, replacements);
    }

    private void normalizeSetPropertyColumnsInRange(
            String body,
            int start,
            int end,
            Map<String, String> resultMapColumnByProperty,
            List<TextReplacement> replacements
    ) {
        int index = start;
        while (index < end) {
            if (body.startsWith("<![CDATA[", index)) {
                int cdataEnd = body.indexOf("]]>", index + "<![CDATA[".length());
                if (cdataEnd < 0 || cdataEnd > end) {
                    return;
                }
                normalizeSetPropertyColumnsFromText(
                        body,
                        index + "<![CDATA[".length(),
                        cdataEnd,
                        resultMapColumnByProperty,
                        replacements
                );
                index = cdataEnd + "]]>".length();
                continue;
            }
            if (body.startsWith("<!--", index)) {
                int commentEnd = body.indexOf("-->", index + "<!--".length());
                if (commentEnd < 0 || commentEnd + "-->".length() > end) {
                    return;
                }
                index = commentEnd + "-->".length();
                continue;
            }
            int tagStart = body.indexOf('<', index);
            int textEnd = tagStart < 0 || tagStart > end ? end : tagStart;
            if (textEnd > index) {
                normalizeSetPropertyColumnsFromText(
                        body,
                        index,
                        textEnd,
                        resultMapColumnByProperty,
                        replacements
                );
            }
            if (tagStart < 0 || tagStart >= end) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            index = tag == null ? tagStart + 1 : tag.endIndex();
        }
    }

    private void normalizeSetPropertyColumnsFromText(
            String body,
            int start,
            int end,
            Map<String, String> resultMapColumnByProperty,
            List<TextReplacement> replacements
    ) {
        int lineStart = start;
        while (lineStart < end) {
            int lineEnd = body.indexOf('\n', lineStart);
            if (lineEnd < 0 || lineEnd > end) {
                lineEnd = end;
            }
            int contentEnd = lineEnd;
            if (contentEnd > lineStart && body.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            TextRewrite rewrite = normalizeSetPropertyColumnExpression(
                    body.substring(lineStart, contentEnd),
                    resultMapColumnByProperty
            );
            if (rewrite.changed()) {
                replacements.add(new TextReplacement(lineStart, contentEnd, rewrite.text()));
            }
            lineStart = lineEnd < end ? lineEnd + 1 : end;
        }
    }

    private TextRewrite normalizeSetPropertyColumnExpression(
            String value,
            Map<String, String> resultMapColumnByProperty
    ) {
        int tokenStart = leadingWhitespaceLength(value);
        IdentifierToken property = readIdentifierToken(value, tokenStart);
        if (property == null) {
            return new TextRewrite(value, false);
        }
        int afterProperty = skipWhitespace(value, property.endIndex());
        if (afterProperty < value.length() && value.charAt(afterProperty) == '.') {
            return new TextRewrite(value, false);
        }
        if (afterProperty >= value.length() || value.charAt(afterProperty) != '=') {
            return new TextRewrite(value, false);
        }
        String normalizedProperty = normalizeIdentifier(property.text());
        if (isQuotedIdentifier(property.text())
                || isMappedResultColumn(resultMapColumnByProperty, normalizedProperty)) {
            return new TextRewrite(value, false);
        }
        String column = resultMapColumnByProperty.get(normalizedProperty);
        if (column == null || normalizeIdentifier(column).equals(normalizedProperty)) {
            return new TextRewrite(value, false);
        }
        return new TextRewrite(
                value.substring(0, tokenStart) + column + value.substring(property.endIndex()),
                true
        );
    }

    private boolean isQuotedIdentifier(String identifier) {
        String trimmed = identifier == null ? "" : identifier.trim();
        return (trimmed.startsWith("`") && trimmed.endsWith("`"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\""));
    }

    private boolean isMappedResultColumn(Map<String, String> resultMapColumnByProperty, String normalizedIdentifier) {
        for (String column : resultMapColumnByProperty.values()) {
            if (normalizeIdentifier(column).equals(normalizedIdentifier)) {
                return true;
            }
        }
        return false;
    }

    private TextRewrite qualifyDynamicUpdateJoinSetTargets(String body) {
        int statementEnd = body.length();
        while (statementEnd > 0 && Character.isWhitespace(body.charAt(statementEnd - 1))) {
            statementEnd--;
        }
        if (statementEnd > 0 && body.charAt(statementEnd - 1) == ';') {
            statementEnd--;
        }
        String statement = body.substring(0, statementEnd);
        int updateIndex = leadingWhitespaceLength(statement);
        if (!isKeywordAt(statement, updateIndex, "UPDATE")) {
            return new TextRewrite(body, false);
        }

        int joinIndex = findTopLevelKeywordSkippingXml(statement, "JOIN", updateIndex + "UPDATE".length());
        if (joinIndex < 0) {
            return new TextRewrite(body, false);
        }
        TrimBlock setBlock = findMyBatisDynamicSetBlock(statement, joinIndex + "JOIN".length(), statement.length());
        if (setBlock == null || setBlock.openingStart() <= joinIndex) {
            return new TextRewrite(body, false);
        }

        int joinTypeStart = dynamicJoinTypeStart(statement, joinIndex);
        String target = statement.substring(updateIndex + "UPDATE".length(), joinTypeStart).strip();
        String targetAlias = updateTargetAlias(target);
        if (targetAlias.isBlank() || !isSimpleBareIdentifier(targetAlias) || containsJoinKeyword(target)) {
            return new TextRewrite(body, false);
        }

        List<TextReplacement> replacements = new ArrayList<>();
        qualifySetTargetsInRange(body, setBlock.contentStart(), setBlock.contentEnd(), targetAlias, replacements);
        replacements.sort((left, right) -> Integer.compare(left.startIndex(), right.startIndex()));
        return applyTextReplacements(body, replacements);
    }

    private TrimBlock findMyBatisDynamicSetBlock(String body, int start, int end) {
        int index = Math.max(0, start);
        while (index >= 0 && index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                return null;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing()) {
                if ("set".equalsIgnoreCase(tag.name())) {
                    int closingStart = findClosingTag(body, tag.endIndex(), "set", end);
                    if (closingStart < 0) {
                        return null;
                    }
                    int closingEnd = body.indexOf('>', closingStart + 1);
                    return new TrimBlock(
                            tagStart,
                            tag.endIndex(),
                            closingStart,
                            closingEnd < 0 ? closingStart : closingEnd + 1
                    );
                }
                if (isSetTrimOpening(body, tagStart, tag)) {
                    int closingStart = findClosingTag(body, tag.endIndex(), "trim", end);
                    if (closingStart < 0) {
                        return null;
                    }
                    int closingEnd = body.indexOf('>', closingStart + 1);
                    return new TrimBlock(
                            tagStart,
                            tag.endIndex(),
                            closingStart,
                            closingEnd < 0 ? closingStart : closingEnd + 1
                    );
                }
            }
            index = tag.endIndex();
        }
        return null;
    }

    private String updateTargetAlias(String target) {
        String stripped = target == null ? "" : target.strip();
        if (stripped.isBlank() || containsXmlMarkup(stripped)) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?is)^.+?\\s+(?:AS\\s+)?(" + DM_IDENTIFIER + ")\\s*$")
                .matcher(stripped);
        if (!matcher.matches()) {
            return "";
        }
        String alias = matcher.group(1).trim();
        return isSqlClauseKeyword(alias) ? "" : alias;
    }

    private boolean isSimpleBareIdentifier(String value) {
        IdentifierToken token = readIdentifierToken(value, 0);
        return token != null && token.endIndex() == value.length() && !value.contains("${");
    }

    private void qualifySetTargetsInRange(
            String body,
            int start,
            int end,
            String targetAlias,
            List<TextReplacement> replacements
    ) {
        int index = start;
        while (index < end) {
            int tagStart = body.indexOf('<', index);
            int textEnd = tagStart < 0 || tagStart > end ? end : tagStart;
            addSetTargetQualification(body, index, textEnd, targetAlias, replacements);
            if (tagStart < 0 || tagStart >= end) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "trim".equalsIgnoreCase(tag.name())) {
                addTrimPrefixSetTargetQualification(body, tagStart, tag.endIndex(), targetAlias, replacements);
            }
            index = tag.endIndex();
        }
    }

    private void addTrimPrefixSetTargetQualification(
            String body,
            int tagStart,
            int tagEnd,
            String targetAlias,
            List<TextReplacement> replacements
    ) {
        String openingTag = body.substring(tagStart, tagEnd);
        Matcher matcher = Pattern.compile("(?is)\\bprefix\\s*=\\s*([\"'])(.*?)\\1").matcher(openingTag);
        if (!matcher.find()) {
            return;
        }
        String prefix = matcher.group(2);
        TextRewrite qualified = qualifySetTargetExpression(prefix, targetAlias);
        if (qualified.changed()) {
            replacements.add(new TextReplacement(
                    tagStart + matcher.start(2),
                    tagStart + matcher.end(2),
                    qualified.text()
            ));
        }
    }

    private void addSetTargetQualification(
            String body,
            int start,
            int end,
            String targetAlias,
            List<TextReplacement> replacements
    ) {
        String text = body.substring(start, end);
        TextRewrite qualified = qualifySetTargetExpression(text, targetAlias);
        if (qualified.changed()) {
            replacements.add(new TextReplacement(start, end, qualified.text()));
        }
    }

    private TextRewrite qualifySetTargetExpression(String value, String targetAlias) {
        int tokenStart = leadingWhitespaceLength(value);
        IdentifierToken column = readIdentifierToken(value, tokenStart);
        if (column == null) {
            return new TextRewrite(value, false);
        }
        int afterColumn = skipWhitespace(value, column.endIndex());
        if (afterColumn < value.length() && value.charAt(afterColumn) == '.') {
            return new TextRewrite(value, false);
        }
        if (afterColumn >= value.length() || value.charAt(afterColumn) != '=') {
            return new TextRewrite(value, false);
        }
        return new TextRewrite(
                value.substring(0, tokenStart)
                        + targetAlias
                        + "."
                        + value.substring(tokenStart),
                true
        );
    }

    private boolean isSetTrimOpening(String body, int tagStart, XmlTag tag) {
        if (tag == null
                || tag.closing()
                || tag.selfClosing()
                || !"trim".equalsIgnoreCase(tag.name())) {
            return false;
        }
        String openingTag = body.substring(tagStart, tag.endIndex());
        Matcher matcher = Pattern.compile("(?is)\\bprefix\\s*=\\s*([\"'])(.*?)\\1").matcher(openingTag);
        return matcher.find() && "set".equalsIgnoreCase(matcher.group(2).trim());
    }

    private int xmlTagEnd(String body, int tagStart) {
        int end = body.indexOf('>', tagStart);
        return end < 0 ? tagStart : end + 1;
    }

    private TextRewrite removeDuplicateDynamicSetAssignments(String body) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < body.length()) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "set".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "set", body.length());
                if (closingStart < 0) {
                    index = tag.endIndex();
                    continue;
                }
                removeDuplicateSetAssignmentsInRange(body, tag.endIndex(), closingStart, replacements);
                int closingEnd = body.indexOf('>', closingStart + 1);
                index = closingEnd < 0 ? closingStart + 1 : closingEnd + 1;
            } else {
                index = tag.endIndex();
            }
        }
        replacements.sort((left, right) -> Integer.compare(left.startIndex(), right.startIndex()));
        return applyTextReplacements(body, replacements);
    }

    private void removeDuplicateSetAssignmentsInRange(
            String body,
            int start,
            int end,
            List<TextReplacement> replacements
    ) {
        Map<String, List<SetAssignment>> seenAssignments = new LinkedHashMap<>();
        Map<SetAssignment, List<String>> conditionGuards = new LinkedHashMap<>();
        Set<SetAssignment> removedAssignments = new LinkedHashSet<>();
        int index = start;
        while (index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                removeDuplicateSetAssignmentsFromText(
                        body,
                        index,
                        end,
                        seenAssignments,
                        replacements,
                        conditionGuards,
                        removedAssignments
                );
                addSetAssignmentConditionGuardReplacements(conditionGuards, removedAssignments, replacements);
                return;
            }
            if (tagStart > index) {
                removeDuplicateSetAssignmentsFromText(
                        body,
                        index,
                        tagStart,
                        seenAssignments,
                        replacements,
                        conditionGuards,
                        removedAssignments
                );
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "if".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "if", end);
                if (closingStart >= 0) {
                    int closingEnd = body.indexOf('>', closingStart + 1);
                    if (closingEnd >= 0 && closingEnd + 1 <= end) {
                        String ifBody = body.substring(tag.endIndex(), closingStart);
                        IfTestAttribute condition = readIfTestAttribute(body, tagStart, tag.endIndex());
                        SetAssignment assignment = readSetAssignment(ifBody, condition, tagStart, closingEnd + 1);
                        deduplicateSetAssignment(
                                assignment,
                                seenAssignments,
                                replacements,
                                conditionGuards,
                                removedAssignments
                        );
                        index = closingEnd + 1;
                        continue;
                    }
                }
            }
            index = tag.endIndex();
        }
        addSetAssignmentConditionGuardReplacements(conditionGuards, removedAssignments, replacements);
    }

    private void removeDuplicateSetAssignmentsFromText(
            String body,
            int start,
            int end,
            Map<String, List<SetAssignment>> seenAssignments,
            List<TextReplacement> replacements,
            Map<SetAssignment, List<String>> conditionGuards,
            Set<SetAssignment> removedAssignments
    ) {
        int lineStart = start;
        while (lineStart < end) {
            int lineEnd = body.indexOf('\n', lineStart);
            if (lineEnd < 0 || lineEnd >= end) {
                lineEnd = end;
            }
            int contentEnd = lineEnd;
            if (contentEnd > lineStart && body.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            int replacementEnd = lineEnd < end && lineEnd < body.length() && body.charAt(lineEnd) == '\n'
                    ? lineEnd + 1
                    : lineEnd;
            String line = body.substring(lineStart, contentEnd);
            SetAssignment assignment = readSetAssignment(line, null, lineStart, replacementEnd);
            deduplicateSetAssignment(
                    assignment,
                    seenAssignments,
                    replacements,
                    conditionGuards,
                    removedAssignments
            );
            lineStart = lineEnd < end ? lineEnd + 1 : end;
        }
    }

    private IfTestAttribute readIfTestAttribute(String body, int tagStart, int tagEnd) {
        Matcher matcher = IF_OPENING_TAG_PATTERN.matcher(body.substring(tagStart, tagEnd));
        if (!matcher.find()) {
            return null;
        }
        return new IfTestAttribute(
                matcher.group(2),
                normalizeExpressionForComparison(matcher.group(2)),
                tagStart + matcher.start(2),
                tagStart + matcher.end(2)
        );
    }

    private SetAssignment readSetAssignment(
            String fragment,
            IfTestAttribute condition,
            int startIndex,
            int endIndex
    ) {
        if (fragment == null || fragment.isBlank()) {
            return null;
        }
        if (DYNAMIC_XML_TAG_PATTERN.matcher(fragment).find() || hasMultipleSetAssignmentLines(fragment)) {
            return null;
        }
        Matcher matcher = SET_ASSIGNMENT_COLUMN_PATTERN.matcher(fragment);
        if (!matcher.find()) {
            return null;
        }
        String rhs = stripTrailingComma(fragment.substring(matcher.end()));
        if (rhs.isBlank()) {
            return null;
        }
        return new SetAssignment(
                normalizeSetAssignmentColumn(matcher.group(1)),
                normalizeExpressionForComparison(rhs),
                condition == null ? "" : condition.normalized(),
                condition == null ? "" : condition.raw(),
                condition == null ? -1 : condition.startIndex(),
                condition == null ? -1 : condition.endIndex(),
                startIndex,
                endIndex
        );
    }

    private boolean hasMultipleSetAssignmentLines(String fragment) {
        int assignmentLines = 0;
        int lineStart = 0;
        while (lineStart < fragment.length()) {
            int lineEnd = fragment.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = fragment.length();
            }
            String line = fragment.substring(lineStart, lineEnd);
            if (SET_ASSIGNMENT_COLUMN_PATTERN.matcher(line).find()) {
                assignmentLines++;
                if (assignmentLines > 1) {
                    return true;
                }
            }
            lineStart = lineEnd < fragment.length() ? lineEnd + 1 : fragment.length();
        }
        return false;
    }

    private String normalizeSetAssignmentColumn(String column) {
        if (column == null) {
            return "";
        }
        return column.replace("`", "")
                .replace("\"", "")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeExpressionForComparison(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(expression.length());
        boolean inSingleQuotedString = false;
        boolean inDoubleQuotedString = false;
        for (int i = 0; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (current == '\'' && !inDoubleQuotedString) {
                inSingleQuotedString = !inSingleQuotedString;
                normalized.append(current);
            } else if (current == '"' && !inSingleQuotedString) {
                inDoubleQuotedString = !inDoubleQuotedString;
                normalized.append(current);
            } else if (Character.isWhitespace(current) && !inSingleQuotedString && !inDoubleQuotedString) {
                continue;
            } else {
                normalized.append(current);
            }
        }
        return normalized.toString();
    }

    private void deduplicateSetAssignment(
            SetAssignment assignment,
            Map<String, List<SetAssignment>> seenAssignments,
            List<TextReplacement> replacements,
            Map<SetAssignment, List<String>> conditionGuards,
            Set<SetAssignment> removedAssignments
    ) {
        if (assignment == null) {
            return;
        }
        List<SetAssignment> seenForColumn = seenAssignments.get(assignment.column());
        if (seenForColumn == null) {
            rememberSetAssignment(assignment, seenAssignments);
            return;
        }
        List<SetAssignment> duplicates = seenForColumn.stream()
                .filter(previous -> sameSetAssignmentValue(previous, assignment)
                        && setAssignmentConditionsCanOverlap(previous.condition(), assignment.condition()))
                .toList();
        if (duplicates.isEmpty()) {
            addOverlappingSetAssignmentGuards(assignment, seenForColumn, conditionGuards);
            rememberSetAssignment(assignment, seenAssignments);
            return;
        }
        if (assignment.condition().isBlank()
                && duplicates.stream().noneMatch(previous -> previous.condition().isBlank())) {
            duplicates.forEach(previous -> replacements.add(new TextReplacement(
                    previous.startIndex(),
                    previous.endIndex(),
                    ""
            )));
            removedAssignments.addAll(duplicates);
            seenForColumn.removeAll(duplicates);
            rememberSetAssignment(assignment, seenAssignments);
            return;
        }
        replacements.add(new TextReplacement(assignment.startIndex(), assignment.endIndex(), ""));
        removedAssignments.add(assignment);
    }

    private void addOverlappingSetAssignmentGuards(
            SetAssignment assignment,
            List<SetAssignment> seenForColumn,
            Map<SetAssignment, List<String>> conditionGuards
    ) {
        String exclusion = simpleNonNullConditionExclusion(assignment.condition());
        if (exclusion.isBlank()) {
            return;
        }
        for (SetAssignment previous : seenForColumn) {
            if (sameSetAssignmentValue(previous, assignment)
                    || previous.conditionStartIndex() < 0
                    || !setAssignmentConditionsMayOverlap(previous.condition(), assignment.condition())) {
                continue;
            }
            List<String> guards = conditionGuards.computeIfAbsent(previous, ignored -> new ArrayList<>());
            if (!guards.contains(exclusion)) {
                guards.add(exclusion);
            }
        }
    }

    private void addSetAssignmentConditionGuardReplacements(
            Map<SetAssignment, List<String>> conditionGuards,
            Set<SetAssignment> removedAssignments,
            List<TextReplacement> replacements
    ) {
        for (Map.Entry<SetAssignment, List<String>> entry : conditionGuards.entrySet()) {
            SetAssignment assignment = entry.getKey();
            if (removedAssignments.contains(assignment) || entry.getValue().isEmpty()) {
                continue;
            }
            String guardedCondition = "(" + assignment.rawCondition().strip() + ") and "
                    + String.join(" and ", entry.getValue());
            replacements.add(new TextReplacement(
                    assignment.conditionStartIndex(),
                    assignment.conditionEndIndex(),
                    guardedCondition
            ));
        }
    }

    private boolean setAssignmentConditionsCanOverlap(String left, String right) {
        return left.isBlank() || right.isBlank() || left.equals(right);
    }

    private boolean setAssignmentConditionsMayOverlap(String left, String right) {
        if (left.isBlank() || right.isBlank() || left.equals(right)) {
            return true;
        }
        String leftNonNull = simpleNonNullConditionProperty(left);
        String rightNull = simpleNullConditionProperty(right);
        if (!leftNonNull.isBlank() && leftNonNull.equals(rightNull)) {
            return false;
        }
        String leftNull = simpleNullConditionProperty(left);
        String rightNonNull = simpleNonNullConditionProperty(right);
        return leftNull.isBlank() || !leftNull.equals(rightNonNull);
    }

    private String simpleNonNullConditionExclusion(String condition) {
        String property = simpleNonNullConditionProperty(condition);
        return property.isBlank() ? "" : property + " == null";
    }

    private String simpleNonNullConditionProperty(String condition) {
        return simpleConditionProperty(condition, "!=null", "null!=");
    }

    private String simpleNullConditionProperty(String condition) {
        return simpleConditionProperty(condition, "==null", "null==");
    }

    private String simpleConditionProperty(String condition, String suffixOperator, String prefixOperator) {
        if (condition == null || condition.isBlank()) {
            return "";
        }
        Matcher suffix = Pattern.compile("(?is)^([A-Za-z_][A-Za-z0-9_$.]*)" + Pattern.quote(suffixOperator) + "$")
                .matcher(condition);
        if (suffix.matches()) {
            return suffix.group(1);
        }
        Matcher prefix = Pattern.compile("(?is)^" + Pattern.quote(prefixOperator) + "([A-Za-z_][A-Za-z0-9_$.]*)$")
                .matcher(condition);
        return prefix.matches() ? prefix.group(1) : "";
    }

    private boolean sameSetAssignmentValue(SetAssignment left, SetAssignment right) {
        if (left.valueExpression().equals(right.valueExpression())) {
            return true;
        }
        return !left.condition().isBlank()
                && right.condition().isBlank()
                && sameSimpleMyBatisParameter(left.valueExpression(), right.valueExpression());
    }

    private boolean sameSimpleMyBatisParameter(String left, String right) {
        String leftParameter = simpleMyBatisParameterName(left);
        return !leftParameter.isBlank() && leftParameter.equals(simpleMyBatisParameterName(right));
    }

    private String simpleMyBatisParameterName(String expression) {
        Matcher matcher = SIMPLE_MYBATIS_PARAMETER_PATTERN.matcher(expression);
        return matcher.matches() ? matcher.group(1) : "";
    }

    private void rememberSetAssignment(SetAssignment assignment, Map<String, List<SetAssignment>> seenAssignments) {
        if (assignment != null) {
            seenAssignments.computeIfAbsent(assignment.column(), ignored -> new ArrayList<>()).add(assignment);
        }
    }

    private TextRewrite addMissingDynamicInsertTrimCommas(String body) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < body.length()) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing()
                    && !tag.selfClosing()
                    && "trim".equalsIgnoreCase(tag.name())
                    && isCommaSeparatedInsertTrim(body.substring(tagStart, tag.endIndex()))) {
                int closingStart = findClosingTag(body, tag.endIndex(), "trim", body.length());
                if (closingStart < 0) {
                    index = tag.endIndex();
                    continue;
                }
                addMissingTrimCommasInRange(body, tag.endIndex(), closingStart, replacements);
                int closingEnd = body.indexOf('>', closingStart + 1);
                index = closingEnd < 0 ? closingStart + 1 : closingEnd + 1;
            } else {
                index = tag.endIndex();
            }
        }
        return applyTextReplacements(body, replacements);
    }

    private boolean isCommaSeparatedInsertTrim(String openingTag) {
        String suffixOverrides = defaultString(xmlAttribute(openingTag, "suffixOverrides"));
        if (!suffixOverrides.contains(",")) {
            return false;
        }
        String prefix = defaultString(xmlAttribute(openingTag, "prefix")).trim().replaceAll("\\s+", " ");
        return "(".equals(prefix) || "values (".equalsIgnoreCase(prefix);
    }

    private void addMissingTrimCommasInRange(
            String body,
            int start,
            int end,
            List<TextReplacement> replacements
    ) {
        int index = start;
        while (index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "if".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "if", end);
                if (closingStart < 0) {
                    index = tag.endIndex();
                    continue;
                }
                int closingEnd = body.indexOf('>', closingStart + 1);
                int ifEnd = closingEnd < 0 ? closingStart : closingEnd + 1;
                int insertionIndex = missingTrimCommaInsertionIndex(body, tag.endIndex(), closingStart, ifEnd, end);
                if (insertionIndex >= 0) {
                    replacements.add(new TextReplacement(insertionIndex, insertionIndex, ","));
                }
                index = tag.endIndex();
            } else {
                index = tag.endIndex();
            }
        }
    }

    private int missingTrimCommaInsertionIndex(String body, int ifBodyStart, int ifBodyEnd, int afterIfEnd, int scopeEnd) {
        String ifBody = body.substring(ifBodyStart, ifBodyEnd);
        if (!isCommaSeparatedTrimItemFragment(ifBody)
                || !hasFollowingCommaSeparatedTrimItem(body, afterIfEnd, scopeEnd)
                || followingCommaSeparatedTrimItemStartsWithComma(body, afterIfEnd, scopeEnd)) {
            return -1;
        }
        int insertionIndex = trimTrailingWhitespaceIndex(body, ifBodyStart, ifBodyEnd);
        if (insertionIndex <= ifBodyStart || body.charAt(insertionIndex - 1) == ',') {
            return -1;
        }
        return insertionIndex;
    }

    private boolean followingCommaSeparatedTrimItemStartsWithComma(String body, int start, int end) {
        int index = start;
        while (index < end) {
            index = skipWhitespaceAndXmlComments(body, index, end);
            if (index >= end) {
                return false;
            }
            if (body.charAt(index) == '<') {
                XmlTag tag = readXmlTag(body, index);
                if (tag == null || tag.closing() || tag.selfClosing() || !"if".equalsIgnoreCase(tag.name())) {
                    return false;
                }
                int closingStart = findClosingTag(body, tag.endIndex(), "if", end);
                return closingStart >= 0
                        && trimItemStartsWithComma(body.substring(tag.endIndex(), closingStart));
            }
            int nextTag = body.indexOf('<', index);
            int textEnd = nextTag < 0 ? end : Math.min(nextTag, end);
            String text = body.substring(index, textEnd);
            if (!text.isBlank()) {
                return trimItemStartsWithComma(text);
            }
            index = textEnd;
        }
        return false;
    }

    private boolean trimItemStartsWithComma(String fragment) {
        String stripped = fragment == null ? "" : fragment.stripLeading();
        if (stripped.startsWith("<![CDATA[")) {
            int cdataEnd = stripped.indexOf("]]>");
            if (cdataEnd > "<![CDATA[".length()) {
                stripped = stripped.substring("<![CDATA[".length(), cdataEnd).stripLeading();
            }
        }
        return stripped.startsWith(",");
    }

    private boolean hasFollowingCommaSeparatedTrimItem(String body, int start, int end) {
        int index = start;
        while (index < end) {
            index = skipWhitespaceAndXmlComments(body, index, end);
            if (index >= end) {
                return false;
            }
            char current = body.charAt(index);
            if (current == '<') {
                XmlTag tag = readXmlTag(body, index);
                if (tag == null || tag.closing()) {
                    return false;
                }
                if (!tag.selfClosing() && "if".equalsIgnoreCase(tag.name())) {
                    int closingStart = findClosingTag(body, tag.endIndex(), "if", end);
                    return closingStart >= 0
                            && isCommaSeparatedTrimItemFragment(body.substring(tag.endIndex(), closingStart));
                }
                return false;
            }
            int nextTag = body.indexOf('<', index);
            int textEnd = nextTag < 0 ? end : Math.min(nextTag, end);
            String text = body.substring(index, textEnd);
            if (text.isBlank()) {
                index = textEnd;
                continue;
            }
            return isCommaSeparatedTrimItemFragment(text);
        }
        return false;
    }

    private boolean isCommaSeparatedTrimItemFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return false;
        }
        String stripped = fragment.stripLeading();
        if (stripped.startsWith("<![CDATA[")) {
            int cdataEnd = stripped.indexOf("]]>");
            if (cdataEnd > "<![CDATA[".length()) {
                stripped = stripped.substring("<![CDATA[".length(), cdataEnd).stripLeading();
            }
        }
        return !stripped.isBlank() && stripped.charAt(0) != '<';
    }

    private void addMissingSetCommasInRange(
            String body,
            int start,
            int end,
            List<TextReplacement> replacements
    ) {
        int index = start;
        while (index < end) {
            int tagStart = body.indexOf('<', index);
            if (tagStart < 0 || tagStart >= end) {
                break;
            }
            XmlTag tag = readXmlTag(body, tagStart);
            if (tag == null) {
                index = tagStart + 1;
                continue;
            }
            if (!tag.closing() && !tag.selfClosing() && "if".equalsIgnoreCase(tag.name())) {
                int closingStart = findClosingTag(body, tag.endIndex(), "if", end);
                if (closingStart < 0) {
                    index = tag.endIndex();
                    continue;
                }
                int closingEnd = body.indexOf('>', closingStart + 1);
                int ifEnd = closingEnd < 0 ? closingStart : closingEnd + 1;
                int insertionIndex = missingSetCommaInsertionIndex(body, tag.endIndex(), closingStart, ifEnd, end);
                if (insertionIndex >= 0) {
                    replacements.add(new TextReplacement(insertionIndex, insertionIndex, ","));
                }
                index = tag.endIndex();
            } else {
                index = tag.endIndex();
            }
        }
    }

    private int missingSetCommaInsertionIndex(String body, int ifBodyStart, int ifBodyEnd, int afterIfEnd, int scopeEnd) {
        String ifBody = body.substring(ifBodyStart, ifBodyEnd);
        if (!isSetAssignmentFragment(ifBody) || !hasFollowingSetAssignment(body, afterIfEnd, scopeEnd)) {
            return -1;
        }
        int insertionIndex = trimTrailingWhitespaceIndex(body, ifBodyStart, ifBodyEnd);
        if (insertionIndex <= ifBodyStart || body.charAt(insertionIndex - 1) == ',') {
            return -1;
        }
        return insertionIndex;
    }

    private boolean hasFollowingSetAssignment(String body, int start, int end) {
        int index = start;
        while (index < end) {
            index = skipWhitespaceAndXmlComments(body, index, end);
            if (index >= end) {
                return false;
            }
            char current = body.charAt(index);
            if (current == '<') {
                XmlTag tag = readXmlTag(body, index);
                if (tag == null) {
                    return false;
                }
                if (tag.closing()) {
                    return false;
                }
                if (!tag.selfClosing() && "if".equalsIgnoreCase(tag.name())) {
                    int closingStart = findClosingTag(body, tag.endIndex(), "if", end);
                    return closingStart >= 0 && isSetAssignmentFragment(body.substring(tag.endIndex(), closingStart));
                }
                return false;
            }
            int nextTag = body.indexOf('<', index);
            int textEnd = nextTag < 0 ? end : Math.min(nextTag, end);
            String text = body.substring(index, textEnd);
            if (text.isBlank()) {
                index = textEnd;
                continue;
            }
            return isSetAssignmentFragment(text);
        }
        return false;
    }

    private boolean isSetAssignmentFragment(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return false;
        }
        String stripped = fragment.stripLeading();
        if (stripped.startsWith("<![CDATA[")) {
            int cdataEnd = stripped.indexOf("]]>");
            if (cdataEnd > "<![CDATA[".length()) {
                stripped = stripped.substring("<![CDATA[".length(), cdataEnd).stripLeading();
            }
        }
        return SET_ASSIGNMENT_START_PATTERN.matcher(stripped).find();
    }

    private int trimTrailingWhitespaceIndex(String value, int start, int end) {
        int index = end;
        while (index > start && Character.isWhitespace(value.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private int skipWhitespaceAndXmlComments(String value, int start, int end) {
        int index = start;
        while (index < end) {
            while (index < end && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
            if (index < end && value.startsWith("<!--", index)) {
                int commentEnd = value.indexOf("-->", index + "<!--".length());
                if (commentEnd < 0 || commentEnd + "-->".length() > end) {
                    return end;
                }
                index = commentEnd + "-->".length();
            } else {
                return index;
            }
        }
        return index;
    }

    private TextRewrite qualifyBarePropertyReferences(
            String expression,
            Set<String> propertyNames,
            String qualifier
    ) {
        List<TextReplacement> replacements = new ArrayList<>();
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (current == '\'' || current == '"') {
                index = skipQuoted(expression, index, current);
            } else if (isIdentifierStart(current)) {
                IdentifierToken token = readIdentifierToken(expression, index);
                if (token == null) {
                    index++;
                    continue;
                }
                char previous = previousNonWhitespace(expression, index);
                char next = nextNonWhitespace(expression, token.endIndex());
                if (propertyNames.contains(token.text())
                        && previous != '.'
                        && next != '.'
                        && next != '(') {
                    replacements.add(new TextReplacement(index, token.endIndex(), qualifier + token.text()));
                }
                index = token.endIndex();
            } else {
                index++;
            }
        }
        return applyTextReplacements(expression, replacements);
    }

    private String removeForeachTrailingCommas(String body) {
        Matcher matcher = FOREACH_BLOCK_PATTERN.matcher(body);
        StringBuilder converted = new StringBuilder(body.length());
        boolean changed = false;
        int index = 0;
        while (matcher.find()) {
            converted.append(body, index, matcher.start());
            String foreachBlock = matcher.group();
            boolean blockChanged = false;
            PreservedForeach preservedForeach = readPreservedForeach(foreachBlock);
            if (preservedForeach != null) {
                TextRewrite trimWrappedBody = wrapDynamicForeachTupleWithTrim(preservedForeach.body());
                if (trimWrappedBody.changed()) {
                    foreachBlock = preservedForeach.openingTag()
                            + trimWrappedBody.text()
                            + preservedForeach.closingTag();
                    blockChanged = true;
                }
            }
            String rewrittenBlock = TRAILING_COMMA_BEFORE_PAREN_PATTERN.matcher(foreachBlock).replaceAll("$1");
            converted.append(rewrittenBlock);
            changed = changed || blockChanged || !rewrittenBlock.equals(foreachBlock);
            index = matcher.end();
        }
        converted.append(body, index, body.length());
        return changed ? converted.toString() : body;
    }

    private TextRewrite wrapDynamicForeachTupleWithTrim(String body) {
        ParenthesizedBody parenthesizedBody = outerParenthesizedBody(body);
        if (parenthesizedBody == null
                || !parenthesizedBody.content().contains("<if")
                || !IF_BODY_TRAILING_COMMA_PATTERN.matcher(parenthesizedBody.content()).find()
                || parenthesizedBody.content().stripLeading().startsWith("<trim")) {
            return new TextRewrite(body, false);
        }
        return new TextRewrite(
                parenthesizedBody.leading()
                        + "<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">"
                        + parenthesizedBody.content()
                        + "</trim>"
                        + parenthesizedBody.trailing(),
                true
        );
    }

    private ParenthesizedBody outerParenthesizedBody(String value) {
        if (value == null) {
            return null;
        }
        int openIndex = 0;
        while (openIndex < value.length() && Character.isWhitespace(value.charAt(openIndex))) {
            openIndex++;
        }
        if (openIndex >= value.length() || value.charAt(openIndex) != '(') {
            return null;
        }
        int closeIndex = findMatchingParen(value, openIndex);
        if (closeIndex < 0) {
            return null;
        }
        int trailingIndex = closeIndex + 1;
        while (trailingIndex < value.length() && Character.isWhitespace(value.charAt(trailingIndex))) {
            trailingIndex++;
        }
        if (trailingIndex != value.length()) {
            return null;
        }
        return new ParenthesizedBody(
                value.substring(0, openIndex),
                value.substring(openIndex + 1, closeIndex),
                value.substring(closeIndex + 1)
        );
    }

    private TextSegmentConversion convertTextSegment(
            String text,
            String statementKey,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig,
            boolean followedByDynamicWhere
    ) {
        if (isUpdateJoinWithoutWhere(text)) {
            if (!followedByDynamicWhere) {
                return convertUpdateJoinPrefixTextSegment(text, sqlConverter);
            }
            return new TextSegmentConversion(
                    text,
                    List.of(),
                    List.of(DYNAMIC_UPDATE_JOIN_WITH_WHERE_REASON),
                    false
            );
        }
        return convertPlainTextSegment(text, statementKey, sqlConverter, rewriteConfig);
    }

    private TextSegmentConversion convertUpdateJoinPrefixTextSegment(String text, SqlConverter sqlConverter) {
        if (!(sqlConverter instanceof MySqlToDmSqlConverter mySqlToDmSqlConverter)) {
            return new TextSegmentConversion(text, List.of(), List.of(), false);
        }
        SqlConversionResult conversionResult = mySqlToDmSqlConverter.convertDynamicTextSegmentSafeRules(text);
        List<String> manualReviewReasons = conversionResult.manualReviewRequired()
                ? List.of(conversionResult.reason())
                : List.of();
        if (!conversionResult.changed()) {
            return new TextSegmentConversion(text, List.of(), manualReviewReasons, false);
        }
        return new TextSegmentConversion(
                conversionResult.convertedSql(),
                conversionResult.appliedRules(),
                manualReviewReasons,
                true
        );
    }

    private TextSegmentConversion convertPlainTextSegment(
            String text,
            String statementKey,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        TextRewrite plainInsert = convertConfiguredInsertIgnoreToPlainInsert(
                text,
                statementKey,
                rewriteConfig
        );
        SqlConversionResult conversionResult =
                sqlConverter.convert(
                        plainInsert.text(),
                        rewriteConfig.keyColumnsFor(statementKey, extractInsertTableName(plainInsert.text()))
                );
        List<String> manualReviewReasons = conversionResult.manualReviewRequired()
                ? List.of(conversionResult.reason())
                : List.of();
        if (!plainInsert.changed() && !conversionResult.changed()) {
            return new TextSegmentConversion(text, List.of(), manualReviewReasons, false);
        }
        List<String> appliedRules = new ArrayList<>();
        if (plainInsert.changed()) {
            appliedRules.add(MYBATIS_INSERT_IGNORE_AS_PLAIN_INSERT_RULE);
        }
        addAppliedRules(appliedRules, conversionResult.appliedRules());
        return new TextSegmentConversion(
                conversionResult.changed() ? conversionResult.convertedSql() : plainInsert.text(),
                appliedRules,
                manualReviewReasons,
                true
        );
    }

    private TextRewrite convertConfiguredInsertIgnoreToPlainInsert(
            String sql,
            String statementKey,
            SqlRewriteConfig rewriteConfig
    ) {
        if (!rewriteConfig.convertsInsertIgnoreToPlainInsert(statementKey)) {
            return new TextRewrite(sql, false);
        }
        Matcher matcher = LEADING_INSERT_IGNORE_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return new TextRewrite(sql, false);
        }
        String converted = matcher.replaceFirst("${insert}${into}");
        return new TextRewrite(converted, !converted.equals(sql));
    }

    private String nextTagName(String rawBody, int startIndex) {
        int index = startIndex;
        while (index < rawBody.length()) {
            while (index < rawBody.length() && Character.isWhitespace(rawBody.charAt(index))) {
                index++;
            }
            if (rawBody.startsWith("<!--", index)) {
                int commentEnd = rawBody.indexOf("-->", index + "<!--".length());
                if (commentEnd < 0) {
                    return "";
                }
                index = commentEnd + "-->".length();
                continue;
            }
            if (index >= rawBody.length() || rawBody.charAt(index) != '<') {
                return "";
            }
            if (index + 1 < rawBody.length() && rawBody.charAt(index + 1) == '/') {
                return "";
            }
            int nameStart = index + 1;
            int nameEnd = nameStart;
            while (nameEnd < rawBody.length()) {
                char current = rawBody.charAt(nameEnd);
                if (!Character.isLetterOrDigit(current) && current != '_' && current != '-') {
                    break;
                }
                nameEnd++;
            }
            return rawBody.substring(nameStart, nameEnd).toLowerCase();
        }
        return "";
    }

    private boolean isDynamicWhereTag(String tagName) {
        return "where".equals(tagName);
    }

    private boolean isUpdateJoinWithoutWhere(String text) {
        int updateIndex = leadingWhitespaceLength(text);
        if (!isKeywordAt(text, updateIndex, "UPDATE")) {
            return false;
        }
        int joinIndex = findTopLevelKeywordSkippingXml(text, "JOIN", updateIndex + "UPDATE".length());
        if (joinIndex < 0) {
            return false;
        }
        int setIndex = findTopLevelKeywordSkippingXml(text, "SET", joinIndex + "JOIN".length());
        return setIndex >= 0 && findTopLevelKeywordSkippingXml(text, "WHERE", setIndex + "SET".length()) < 0;
    }

    private void addAppliedRules(List<String> appliedRules, List<String> rulesToAdd) {
        for (String rule : rulesToAdd) {
            if (!appliedRules.contains(rule)) {
                appliedRules.add(rule);
            }
        }
    }

    private void addManualReviewReasons(List<String> reasons, List<String> reasonsToAdd) {
        for (String reason : reasonsToAdd) {
            if (reason != null && !reason.isBlank() && !reasons.contains(reason)) {
                reasons.add(reason);
            }
        }
    }

    private String toCdata(String text) {
        return "<![CDATA[" + text.replace("]]>", "]]]]><![CDATA[>") + "]]>";
    }

    private String escapeXmlText(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '&') {
                escaped.append("&amp;");
            } else if (ch == '<') {
                escaped.append("&lt;");
            } else if (ch == '>') {
                escaped.append("&gt;");
            } else {
                escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private record IfWrapper(String leading, String openingTag, String body, String closingTag, String trailing) {
        private String wrap(String convertedBody) {
            return leading + openingTag + convertedBody + closingTag + trailing;
        }
    }

    private record ForeachBlock(
            String collection,
            String item,
            String index,
            String open,
            String separator,
            String body
    ) {
        private ForeachBlock withBody(String convertedBody) {
            return new ForeachBlock(collection, item, index, open, separator, convertedBody);
        }

        private String openingWithSeparator(String newSeparator) {
            StringBuilder opening = new StringBuilder("<foreach collection=\"")
                    .append(collection)
                    .append("\" item=\"")
                    .append(item)
                    .append("\"");
            if (!index.isBlank()) {
                opening.append(" index=\"").append(index).append("\"");
            }
            if (!open.isBlank()) {
                opening.append(" open=\"").append(open).append("\"");
            }
            opening.append(" separator=\"").append(newSeparator).append("\">");
            return opening.toString();
        }

        private String toXml() {
            return openingWithSeparator(separator) + body + "</foreach>";
        }
    }

    private record PreservedForeach(
            String openingTag,
            String body,
            String closingTag,
            String collection,
            String item,
            String index
    ) {
    }

    private record ParenthesizedBody(String leading, String content, String trailing) {
    }

    private record BatchInsertValues(
            String leading,
            String table,
            String columns,
            String foreach,
            String updates,
            String trailing
    ) {
    }

    private record MergeSourceColumn(String name, String expression) {
    }

    private record BatchUpdateAssignment(String target, String sourceExpression, boolean valuesReference) {
    }

    private record ConditionalTrimItem(String opening, String test, String content) {
    }

    private record ConditionalUpdateAssignment(String opening, BatchUpdateAssignment assignment) {
    }

    private record SetAssignment(
            String column,
            String valueExpression,
            String condition,
            String rawCondition,
            int conditionStartIndex,
            int conditionEndIndex,
            int startIndex,
            int endIndex
    ) {
    }

    private record IfTestAttribute(String raw, String normalized, int startIndex, int endIndex) {
    }

    private record DynamicHavingConversion(
            String originalBody,
            String convertedBody,
            List<String> appliedRules,
            boolean changed
    ) {
        DynamicHavingConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }
    }

    private record ScopeHavingConversion(String convertedBody, List<String> appliedRules, boolean changed) {
        ScopeHavingConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }

        private static ScopeHavingConversion unchanged(String body) {
            return new ScopeHavingConversion(body, List.of(), false);
        }
    }

    private record SelectScope(
            int selectIndex,
            int fromIndex,
            int groupIndex,
            int havingIndex,
            int havingEnd,
            int scopeEnd,
            int depth
    ) {
    }

    private record SqlView(String text) {
    }

    private record SelectListPart(String text, int startIndex, int endIndex) {
    }

    private record UserVariableInitialization(String name) {
    }

    private record TrimBlock(int openingStart, int contentStart, int contentEnd, int closingEnd) {
    }

    private record MyBatisWhereBlock(int openingStart, int openingEnd, int closingStart, int closingEnd) {
    }

    private record HavingRewrite(String remainingHaving, String movedConditions, boolean changed) {
    }

    private record DynamicHavingAliasRewrite(String text, List<String> appliedRules, boolean changed) {
        DynamicHavingAliasRewrite {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }

        private static DynamicHavingAliasRewrite unchanged() {
            return new DynamicHavingAliasRewrite("", List.of(), false);
        }
    }

    private record ConditionPart(String text) {
    }

    private record TextRewrite(String text, boolean changed) {
    }

    private record TextReplacement(int startIndex, int endIndex, String replacement) {
    }

    private record TemporaryTableSelectStatement(
            int createIndex,
            int prefixEndIndex,
            int selectIndex,
            int endIndex,
            String tableName
    ) {
    }

    private record DynamicJoinSource(String sourceSql, String conditionSql) {
    }

    private record SelectAlias(String expression, String alias) {
    }

    private record DynamicAggregateAlias(String alias, List<DynamicAggregateAliasBranch> branches) {
        DynamicAggregateAlias {
            branches = List.copyOf(branches == null ? List.of() : branches);
        }
    }

    private record DynamicAggregateAliasBranch(String openingTag, String expression, String closingTag) {
    }

    private record IdentifierToken(String text, int endIndex) {
    }

    private record XmlTag(String name, boolean closing, boolean selfClosing, int endIndex) {
    }

    private record StatementReplacement(
            String tagName,
            String statementId,
            int occurrenceIndex,
            String convertedSql,
            String convertedBody
    ) {
        private static StatementReplacement staticSql(
                String tagName,
                String statementId,
                int occurrenceIndex,
                String convertedSql
        ) {
            return new StatementReplacement(tagName, statementId, occurrenceIndex, convertedSql, null);
        }

        private static StatementReplacement dynamicBody(
                String tagName,
                String statementId,
                int occurrenceIndex,
                String convertedBody
        ) {
            return new StatementReplacement(tagName, statementId, occurrenceIndex, null, convertedBody);
        }
    }

    private record StatementBody(int start, int end, String rawBody) {
    }

    private record DynamicBodyConversion(
            String originalBody,
            String convertedBody,
            List<String> appliedRules,
            List<String> manualReviewReasons,
            boolean changed
    ) {
        DynamicBodyConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
            manualReviewReasons = List.copyOf(manualReviewReasons == null ? List.of() : manualReviewReasons);
        }
    }

    private record QuotedText(String value, int nextIndex, boolean closed) {
    }

    private record TextSegmentConversion(
            String convertedText,
            List<String> appliedRules,
            List<String> manualReviewReasons,
            boolean changed
    ) {
        TextSegmentConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
            manualReviewReasons = List.copyOf(manualReviewReasons == null ? List.of() : manualReviewReasons);
        }
    }
}
