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
import java.util.List;
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
    private static final Pattern FOREACH_TAG_PATTERN = Pattern.compile(
            "(?is)^\\s*(?<opening><foreach\\b[^>]*>)(?<body>[\\s\\S]*?)</foreach\\s*>\\s*$"
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
            SqlConversionResult conversionResult =
                    sqlConverter.convert(originalSql, rewriteConfig.keyColumnsFor(statementKey, tableName));
            if (conversionResult.changed()) {
                replacements.add(StatementReplacement.staticSql(
                        statement.getTagName(),
                        statementId,
                        conversionResult.convertedSql()
                ));
                automaticConversions.add(new SqlChange(
                        reportPath,
                        statementKey,
                        conversionResult.originalSql(),
                        conversionResult.convertedSql(),
                        conversionResult.appliedRules(),
                        false,
                        ""
                ));
            }
            if (conversionResult.manualReviewRequired()) {
                manualReviewItems.add(new SqlChange(
                        reportPath,
                        statementKey,
                        conversionResult.originalSql(),
                        conversionResult.convertedSql(),
                        conversionResult.appliedRules(),
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
                        rewriteConfig
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
                int tagEnd = rawBody.indexOf('>', index + 1);
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
                TextSegmentConversion conversion = convertTextSegment(text, statementKey, sqlConverter, rewriteConfig);
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
        if (!"insert".equals(statementTagName) && !"update".equals(statementTagName)) {
            return new DynamicBodyConversion(body, body, List.of(), List.of(), false);
        }

        List<String> appliedRules = new ArrayList<>();
        String converted = body;

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
            String dynamicUpdateJoin = convertDynamicUpdateJoin(converted);
            if (!dynamicUpdateJoin.equals(converted)) {
                appliedRules.add(MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE);
                converted = dynamicUpdateJoin;
            }
            return new DynamicBodyConversion(body, converted, appliedRules, List.of(), !appliedRules.isEmpty());
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
        return new DynamicBodyConversion(body, converted, appliedRules, List.of(), !appliedRules.isEmpty());
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
        SqlConversionResult conversion = sqlConverter.convert(foreach.body(), keyColumns);
        if (conversion.manualReviewRequired() || !conversion.changed()) {
            return body;
        }
        return foreach.withBody(conversion.convertedSql()).toXml();
    }

    private String convertBatchOnDuplicateKeyUpdate(String body, String statementKey, SqlRewriteConfig rewriteConfig) {
        IfWrapper wrapper = readWrappingIf(body);
        String candidate = wrapper == null ? body : wrapper.body();
        Matcher matcher = BATCH_ON_DUPLICATE_KEY_UPDATE_PATTERN.matcher(candidate);
        if (!matcher.matches()) {
            return body;
        }
        List<String> keyColumns = rewriteConfig.keyColumnsFor(statementKey, matcher.group("table"));
        if (keyColumns.isEmpty()) {
            return body;
        }
        String converted = convertBatchInsertValuesToMerge(
                matcher.group("leading"),
                matcher.group("table"),
                matcher.group("columns"),
                matcher.group("foreach"),
                matcher.group("updates"),
                keyColumns,
                matcher.group("trailing")
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
        if (!matcher.matches()) {
            return body;
        }
        List<String> keyColumns = rewriteConfig.keyColumnsFor(statementKey, matcher.group("table"));
        if (keyColumns.isEmpty()) {
            return body;
        }
        String converted = convertBatchInsertValuesToMerge(
                matcher.group("leading"),
                matcher.group("table"),
                matcher.group("columns"),
                matcher.group("foreach"),
                "",
                keyColumns,
                matcher.group("trailing")
        );
        if (converted == null || converted.equals(candidate)) {
            return body;
        }
        return wrapper == null ? converted : wrapper.wrap(converted);
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
                int tagEnd = value.indexOf('>', index + 1);
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
                int tagEnd = value.indexOf('>', index + 1);
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
            SqlRewriteConfig rewriteConfig
    ) {
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
