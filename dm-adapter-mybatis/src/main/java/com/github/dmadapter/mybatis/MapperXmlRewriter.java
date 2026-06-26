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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MapperXmlRewriter {
    public static final String MYBATIS_BATCH_INSERT_ADD_VALUES_RULE = "MYBATIS_BATCH_INSERT_ADD_VALUES";
    public static final String MYBATIS_FOREACH_TRAILING_COMMA_RULE = "MYBATIS_FOREACH_TRAILING_COMMA";
    public static final String MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE =
            "MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE";
    public static final String MYBATIS_DYNAMIC_INSERT_IGNORE_TO_DM_MERGE_RULE =
            "MYBATIS_DYNAMIC_INSERT_IGNORE_TO_DM_MERGE";
    public static final String MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE =
            "MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM";
    public static final String MYBATIS_SQL_LINE_COMMENT_PLACEHOLDER_NEUTRALIZED_RULE =
            "MYBATIS_SQL_LINE_COMMENT_PLACEHOLDER_NEUTRALIZED";
    public static final String MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE =
            "MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION";
    public static final String MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE =
            "MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE";
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
    private static final Pattern FOREACH_BLOCK_PATTERN = Pattern.compile("(?is)<foreach\\b[^>]*>[\\s\\S]*?</foreach>");
    private static final Pattern TRAILING_COMMA_BEFORE_PAREN_PATTERN = Pattern.compile(",(\\s*\\))");
    private static final String DM_IDENTIFIER = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"[^\"]+\")";
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
    private static final Pattern FOREACH_TAG_PATTERN = Pattern.compile(
            "(?is)^\\s*(?<opening><foreach\\b[^>]*>)(?<body>[\\s\\S]*?)</foreach\\s*>\\s*$"
    );
    private static final Pattern TRIM_TAG_PATTERN = Pattern.compile(
            "(?is)^\\s*(?<opening><trim\\b[^>]*>)(?<body>[\\s\\S]*?)</trim\\s*>\\s*$"
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
        List<SqlChange> automaticConversions = new ArrayList<>();
        List<SqlChange> manualReviewItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<StatementReplacement> replacements = new ArrayList<>();

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
        String xml = null;
        boolean changed = false;
        for (Element statement : statementElements(document)) {
            String statementId = statement.getAttribute("id");
            String statementKey = statementKey(namespace, statementId);
            String originalSql = statement.getTextContent();
            if (statementId.isBlank()) {
                String reason = missingStatementIdReason(reportPath, statement.getTagName());
                manualReviewItems.add(new SqlChange(
                        reportPath,
                        "(missing id: <" + statement.getTagName() + ">)",
                        originalSql,
                        originalSql,
                        List.of(),
                        true,
                        reason
                ));
                warnings.add(reason);
                continue;
            }
            if (hasElementChild(statement)) {
                if (xml == null) {
                    xml = readXml(inputPath);
                }
                StatementBody statementBody = findStatementBody(xml, statement.getTagName(), statementId, 0);
                DynamicBodyConversion dynamicBodyConversion =
                        convertDynamicXmlTextSegments(
                                statement.getTagName(),
                                statementKey,
                                statementBody.rawBody(),
                                sqlConverter,
                                rewriteConfig
                        );
                manualReviewItems.add(new SqlChange(
                        reportPath,
                        statementKey,
                        dynamicBodyConversion.originalBody(),
                        dynamicBodyConversion.convertedBody(),
                        dynamicBodyConversion.appliedRules(),
                        true,
                        dynamicXmlManualReviewReason(dynamicBodyConversion.manualReviewReasons())
                ));
                if (dynamicBodyConversion.changed()) {
                    replacements.add(StatementReplacement.dynamicBody(
                            statement.getTagName(),
                            statementId,
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

            String tableName = extractInsertTableName(originalSql);
            String commentSafeSql = neutralizeMyBatisPlaceholdersInSqlLineComments(originalSql);
            List<String> staticRules = new ArrayList<>();
            if (!commentSafeSql.equals(originalSql)) {
                staticRules.add(MYBATIS_SQL_LINE_COMMENT_PLACEHOLDER_NEUTRALIZED_RULE);
            }
            SqlConversionResult conversionResult =
                    sqlConverter.convert(commentSafeSql, rewriteConfig.keyColumnsFor(statementKey, tableName));
            String convertedSql = conversionResult.changed()
                    ? conversionResult.convertedSql()
                    : commentSafeSql;
            addAppliedRules(staticRules, conversionResult.appliedRules());
            if (!convertedSql.equals(originalSql)) {
                replacements.add(StatementReplacement.staticSql(
                        statement.getTagName(),
                        statementId,
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

    private String missingStatementIdReason(String reportPath, String tagName) {
        return "Mapper XML statement <" + tagName + "> is missing required id attribute in "
                + reportPath
                + ". dm-adapter cannot safely locate this statement for text-preserving rewrite; add an id to the MyBatis statement or exclude this XML from mapper-locations if it is not a mapper.";
    }

    private String dynamicXmlManualReviewReason(List<String> manualReviewReasons) {
        String baseReason = "Statement contains dynamic XML elements and requires manual confirmation.";
        if (manualReviewReasons == null || manualReviewReasons.isEmpty()) {
            return baseReason;
        }
        return baseReason + " Additional SQL review: " + String.join("; ", manualReviewReasons);
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
                "(?is)\\binsert\\s+(?:ignore\\s+)?into\\s+("
                        + DM_IDENTIFIER
                        + "(?:\\s*\\.\\s*"
                        + DM_IDENTIFIER
                        + ")?)\\s*\\("
        ).matcher(sql);
        return matcher.find() ? matcher.group(1).trim() : "";
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
            int searchFrom = 0;
            for (StatementReplacement replacement : replacements) {
                StatementBody statementBody = findStatementBody(
                        xml,
                        replacement.tagName(),
                        replacement.statementId(),
                        searchFrom
                );
                String rewrittenBody = replacement.convertedBody() == null
                        ? rewrittenBody(statementBody.rawBody(), replacement.convertedSql())
                        : replacement.convertedBody();
                xml = xml.substring(0, statementBody.start()) + rewrittenBody + xml.substring(statementBody.end());
                searchFrom = statementBody.start() + rewrittenBody.length();
            }
            Files.writeString(path, xml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write mapper XML: " + path, e);
        }
    }

    private StatementBody findStatementBody(String xml, String tagName, String statementId, int searchFrom) {
        if (statementId.isBlank()) {
            throw new IllegalStateException("Mapper statement id is required for text-preserving rewrite.");
        }

        String quotedTag = Pattern.quote(tagName);
        String quotedId = Pattern.quote(statementId);
        Pattern openingPattern = Pattern.compile(
                "(?s)<\\s*" + quotedTag + "\\b(?=[^>]*\\bid\\s*=\\s*(?:\"" + quotedId + "\"|'" + quotedId + "'))[^>]*>"
        );
        Matcher openingMatcher = openingPattern.matcher(xml);
        if (!openingMatcher.find(searchFrom)) {
            throw new IllegalStateException("Failed to locate mapper statement: " + statementId);
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
            SqlRewriteConfig rewriteConfig
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
                rewriteConfig
        );
        if (structuralConversion.changed()) {
            rewrittenBody = structuralConversion.convertedBody();
            addAppliedRules(appliedRules, structuralConversion.appliedRules());
            addManualReviewReasons(manualReviewReasons, structuralConversion.manualReviewReasons());
            changed = true;
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
            SqlRewriteConfig rewriteConfig
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

        TextRewrite temporaryTableForeachLiteral = inlineTemporaryTableAsSelectForeachItemParameters(converted);
        if (temporaryTableForeachLiteral.changed()) {
            converted = temporaryTableForeachLiteral.text();
            appliedRules.add(MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_FOREACH_LITERAL_RULE);
        }

        if (!"insert".equals(statementTagName) && !"update".equals(statementTagName)) {
            return new DynamicBodyConversion(body, converted, appliedRules, manualReviewReasons, !appliedRules.isEmpty());
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
            DynamicBodyConversion dynamicUpdateJoinWithSet = convertDynamicUpdateJoinWithSetClause(
                    converted,
                    statementKey,
                    sqlConverter,
                    rewriteConfig
            );
            if (dynamicUpdateJoinWithSet.changed()) {
                addAppliedRules(appliedRules, dynamicUpdateJoinWithSet.appliedRules());
                addManualReviewReasons(manualReviewReasons, dynamicUpdateJoinWithSet.manualReviewReasons());
                converted = dynamicUpdateJoinWithSet.convertedBody();
            }
            String dynamicUpdateJoin = convertDynamicUpdateJoin(converted);
            if (!dynamicUpdateJoin.equals(converted)) {
                appliedRules.add(MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE);
                converted = dynamicUpdateJoin;
            }
            return new DynamicBodyConversion(body, converted, appliedRules, manualReviewReasons, !appliedRules.isEmpty());
        }

        String withMissingValues = addMissingBatchInsertValues(converted);
        if (!withMissingValues.equals(converted)) {
            appliedRules.add(MYBATIS_BATCH_INSERT_ADD_VALUES_RULE);
            converted = withMissingValues;
        }

        String withoutTrailingCommas = removeForeachTrailingCommas(converted);
        if (!withoutTrailingCommas.equals(converted)) {
            appliedRules.add(MYBATIS_FOREACH_TRAILING_COMMA_RULE);
        }
        converted = withoutTrailingCommas;
        return new DynamicBodyConversion(body, converted, appliedRules, manualReviewReasons, !appliedRules.isEmpty());
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
        int selectIndex = findTopLevelKeywordSkippingXml(body, "SELECT", index);
        if (selectIndex < 0) {
            return null;
        }
        return new TemporaryTableSelectStatement(createIndex, selectIndex, findStatementEndSkippingXml(body, selectIndex));
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
        if (scope.havingIndex() < 0 || scope.groupIndex() < 0) {
            return ScopeHavingConversion.unchanged(body);
        }
        Map<String, String> aggregateAliases = aggregateSelectAliases(
                body.substring(scope.selectIndex() + "SELECT".length(), scope.fromIndex())
        );
        int havingStart = scope.havingIndex() + "HAVING".length();
        String havingContent = body.substring(havingStart, scope.havingEnd());
        TextRewrite aliasRewrite = replaceAggregateAliases(havingContent, aggregateAliases);
        HavingRewrite havingRewrite = rewriteHavingContent(aliasRewrite.text(), aggregateAliases);
        boolean aliasChanged = aliasRewrite.changed();
        boolean movedConditions = !havingRewrite.movedConditions().isBlank();
        if (!aliasChanged && !movedConditions) {
            return ScopeHavingConversion.unchanged(body);
        }

        String converted = body;
        if (movedConditions) {
            String remainingHaving = havingRewrite.remainingHaving();
            if (remainingHaving.isBlank()) {
                converted = converted.substring(0, scope.havingIndex())
                        + converted.substring(scope.havingEnd());
            } else {
                converted = converted.substring(0, havingStart)
                        + remainingHaving
                        + converted.substring(scope.havingEnd());
            }
            converted = insertMovedHavingConditions(
                    converted,
                    scope,
                    havingRewrite.movedConditions()
            );
        } else {
            converted = converted.substring(0, havingStart)
                    + aliasRewrite.text()
                    + converted.substring(scope.havingEnd());
        }

        List<String> rules = new ArrayList<>();
        if (aliasChanged) {
            rules.add(MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        }
        if (movedConditions) {
            rules.add(MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        }
        return new ScopeHavingConversion(converted, rules, true);
    }

    private ScopeHavingConversion convertTrimHavingInScope(String body, SelectScope scope) {
        if (scope.groupIndex() < 0) {
            return ScopeHavingConversion.unchanged(body);
        }
        Map<String, String> aggregateAliases = aggregateSelectAliases(
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

    private HavingRewrite rewriteHavingContent(String havingContent, Map<String, String> aggregateAliases) {
        List<ConditionPart> parts = splitTopLevelAndConditions(havingContent);
        if (parts.isEmpty()) {
            return new HavingRewrite(havingContent, "", false);
        }
        List<String> kept = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        for (ConditionPart part : parts) {
            String condition = part.text();
            if (isMovableSimpleHavingCondition(condition, aggregateAliases)) {
                moved.add(condition);
            } else {
                kept.add(condition);
            }
        }
        if (moved.isEmpty()) {
            return new HavingRewrite(havingContent, "", false);
        }
        return new HavingRewrite(
                joinHavingConditions(kept, havingContent),
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

    private boolean isMovableSimpleHavingCondition(String condition, Map<String, String> aggregateAliases) {
        String normalized = stripXmlMarkup(condition);
        if (normalized.isBlank()) {
            return false;
        }
        if (normalized.contains("${")
                || containsAggregateFunction(normalized)
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
        return containsComparisonOperator(normalized);
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
        String indent = indentationOfFirstContentLine(originalHavingContent);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            String condition = removeLeadingBooleanConnector(conditions.get(i)).strip();
            if (condition.isBlank()) {
                continue;
            }
            if (joined.isEmpty()) {
                joined.append("\n").append(indent).append(condition);
            } else {
                joined.append("\n").append(indent).append("AND ").append(condition);
            }
        }
        return joined.toString();
    }

    private String joinMovedConditions(List<String> conditions, boolean prefixAnd) {
        StringBuilder joined = new StringBuilder();
        for (String condition : conditions) {
            String normalized = removeLeadingBooleanConnector(condition).strip();
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
                    + body.substring(whereBlock.closingStart());
        }

        int whereIndex = findTopLevelKeyword(currentView.text(), "WHERE", scope.fromIndex(), scope.groupIndex(), scope.depth());
        if (whereIndex >= 0) {
            String indent = indentationOfLastLine(body.substring(0, scope.groupIndex()));
            String insertion = "\n" + indent + indentBlock(joinMovedConditions(List.of(movedConditions), true), indent);
            return body.substring(0, scope.groupIndex())
                    + insertion
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

    private TextRewrite replaceAggregateAliases(String value, Map<String, String> aggregateAliases) {
        if (aggregateAliases.isEmpty()) {
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
                String expression = aggregateAliases.get(identifierKey(token.text()));
                char previous = previousNonWhitespace(value, index);
                if (expression != null && previous != '.' && previous != '(') {
                    replacements.add(new TextReplacement(index, token.endIndex(), "(" + expression + ")"));
                }
                index = token.endIndex();
            } else {
                index++;
            }
        }
        return applyTextReplacements(value, replacements);
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

    private Map<String, String> aggregateSelectAliases(String selectList) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String item : splitTopLevelComma(selectList)) {
            AggregateAlias aggregateAlias = aggregateSelectAlias(item);
            if (aggregateAlias != null) {
                aliases.putIfAbsent(identifierKey(aggregateAlias.alias()), aggregateAlias.expression());
            }
        }
        return aliases;
    }

    private AggregateAlias aggregateSelectAlias(String selectItem) {
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
                || isSqlClauseKeyword(alias)
                || !containsAggregateFunction(expression)) {
            return null;
        }
        return new AggregateAlias(expression, alias);
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
                    int havingIndex = groupIndex < 0
                            ? -1
                            : findTopLevelKeyword(view, "HAVING", groupIndex + "GROUP".length(), scopeEnd, depth);
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
                return body;
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
            if (!trimMatcher.matches()) {
                return body;
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
                    "",
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
                .toList();
        if (foreach == null || columns.isEmpty() || values.size() != columns.size()) {
            return null;
        }

        List<String> normalizedKeys = normalizeIdentifiers(keyColumns);
        List<Integer> keyIndexes = new ArrayList<>();
        for (String keyColumn : normalizedKeys) {
            int index = indexOfIdentifier(columns, keyColumn);
            if (index < 0) {
                return null;
            }
            keyIndexes.add(index);
        }

        List<String> updateColumns = updateClause.isBlank()
                ? List.of()
                : updateColumns(updateClause);
        if (updateColumns == null) {
            return null;
        }
        updateColumns = updateColumns.stream()
                .filter(column -> !normalizedKeys.contains(normalizeIdentifier(column)))
                .toList();

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
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                converted.append(", ");
            }
            converted.append(values.get(i))
                    .append(" AS ")
                    .append(dmIdentifier(columns.get(i)));
        }
        converted.append(" FROM dual\n")
                .append(childIndent)
                .append(") s\n")
                .append(childIndent)
                .append("ON (");
        for (int i = 0; i < keyIndexes.size(); i++) {
            if (i > 0) {
                converted.append(" AND ");
            }
            String keyColumn = columns.get(keyIndexes.get(i));
            converted.append("t.")
                    .append(dmIdentifier(keyColumn))
                    .append(" = s.")
                    .append(dmIdentifier(keyColumn));
        }
        converted.append(")\n");
        if (!updateColumns.isEmpty()) {
            converted.append(childIndent)
                    .append("WHEN MATCHED THEN UPDATE SET ");
            for (int i = 0; i < updateColumns.size(); i++) {
                if (i > 0) {
                    converted.append(", ");
                }
                converted.append("t.")
                        .append(dmIdentifier(updateColumns.get(i)))
                        .append(" = s.")
                        .append(dmIdentifier(updateColumns.get(i)));
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
                || !keyForeach.body().strip().equals("${" + keyForeach.item() + "}")
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
                "(?is)^\\s*\\$\\{"
                        + Pattern.quote(keyName)
                        + "}\\s*=\\s*VALUES\\s*\\(\\s*\\$\\{"
                        + Pattern.quote(keyName)
                        + "}\\s*\\)\\s*$"
        );
        return pattern.matcher(body).matches();
    }

    private List<String> splitTopLevelComma(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int index = 0;
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
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (current == ',' && depth == 0) {
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

    private List<String> updateColumns(String updateClause) {
        List<String> columns = new ArrayList<>();
        for (String assignment : splitTopLevelComma(updateClause)) {
            Matcher matcher = Pattern.compile(
                    "(?is)^\\s*(?<target>`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*values\\s*\\(\\s*(?<source>`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)\\s*\\)\\s*$"
            ).matcher(assignment);
            if (!matcher.matches()) {
                return null;
            }
            String target = matcher.group("target");
            String source = matcher.group("source");
            if (!normalizeIdentifier(target).equals(normalizeIdentifier(source))) {
                return null;
            }
            columns.add(target);
        }
        return columns;
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

    private String removeForeachTrailingCommas(String body) {
        Matcher matcher = FOREACH_BLOCK_PATTERN.matcher(body);
        StringBuilder converted = new StringBuilder(body.length());
        boolean changed = false;
        int index = 0;
        while (matcher.find()) {
            converted.append(body, index, matcher.start());
            String foreachBlock = matcher.group();
            String rewrittenBlock = TRAILING_COMMA_BEFORE_PAREN_PATTERN.matcher(foreachBlock).replaceAll("$1");
            converted.append(rewrittenBlock);
            changed = changed || !rewrittenBlock.equals(foreachBlock);
            index = matcher.end();
        }
        converted.append(body, index, body.length());
        return changed ? converted.toString() : body;
    }

    private TextSegmentConversion convertTextSegment(
            String text,
            String statementKey,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig,
            boolean followedByDynamicWhere
    ) {
        if (followedByDynamicWhere && isUpdateJoinWithoutWhere(text)) {
            return new TextSegmentConversion(
                    text,
                    List.of(),
                    List.of(DYNAMIC_UPDATE_JOIN_WITH_WHERE_REASON),
                    false
            );
        }
        if (isUpdateJoinWithoutWhere(text)) {
            return new TextSegmentConversion(text, List.of(), List.of(), false);
        }
        SqlConversionResult conversionResult =
                sqlConverter.convert(text, rewriteConfig.keyColumnsFor(statementKey, extractInsertTableName(text)));
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

    private record BatchInsertValues(
            String leading,
            String table,
            String columns,
            String foreach,
            String updates,
            String trailing
    ) {
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

    private record TrimBlock(int openingStart, int contentStart, int contentEnd, int closingEnd) {
    }

    private record MyBatisWhereBlock(int openingStart, int openingEnd, int closingStart, int closingEnd) {
    }

    private record HavingRewrite(String remainingHaving, String movedConditions, boolean changed) {
    }

    private record ConditionPart(String text) {
    }

    private record TextRewrite(String text, boolean changed) {
    }

    private record TextReplacement(int startIndex, int endIndex, String replacement) {
    }

    private record TemporaryTableSelectStatement(int createIndex, int selectIndex, int endIndex) {
    }

    private record DynamicJoinSource(String sourceSql, String conditionSql) {
    }

    private record AggregateAlias(String expression, String alias) {
    }

    private record IdentifierToken(String text, int endIndex) {
    }

    private record XmlTag(String name, boolean closing, boolean selfClosing, int endIndex) {
    }

    private record StatementReplacement(String tagName, String statementId, String convertedSql, String convertedBody) {
        private static StatementReplacement staticSql(String tagName, String statementId, String convertedSql) {
            return new StatementReplacement(tagName, statementId, convertedSql, null);
        }

        private static StatementReplacement dynamicBody(String tagName, String statementId, String convertedBody) {
            return new StatementReplacement(tagName, statementId, null, convertedBody);
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
