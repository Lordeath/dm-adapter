package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlConversionResult;
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

    private static final Set<String> SQL_TEXT_TAGS = Set.of("select", "insert", "update", "delete", "sql");
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
    private static final Pattern FOREACH_TAG_PATTERN = Pattern.compile(
            "(?is)^\\s*(?<opening><foreach\\b[^>]*>)(?<body>[\\s\\S]*?)</foreach\\s*>\\s*$"
    );

    public MapperRewriteResult rewrite(Path inputPath, String reportPath, boolean writeChanges, SqlConverter sqlConverter) {
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

        String xml = null;
        boolean changed = false;
        for (Element statement : statementElements(document)) {
            String statementId = statement.getAttribute("id");
            String originalSql = statement.getTextContent();
            if (hasElementChild(statement)) {
                manualReviewItems.add(new SqlChange(
                        reportPath,
                        statementId,
                        originalSql,
                        originalSql,
                        List.of(),
                        true,
                        "Statement contains dynamic XML elements and requires manual confirmation."
                ));
                if (xml == null) {
                    xml = readXml(inputPath);
                }
                StatementBody statementBody = findStatementBody(xml, statement.getTagName(), statementId, 0);
                DynamicBodyConversion dynamicBodyConversion =
                        convertDynamicXmlTextSegments(statement.getTagName(), statementBody.rawBody(), sqlConverter);
                if (dynamicBodyConversion.changed()) {
                    replacements.add(StatementReplacement.dynamicBody(
                            statement.getTagName(),
                            statementId,
                            dynamicBodyConversion.convertedBody()
                    ));
                    automaticConversions.add(new SqlChange(
                            reportPath,
                            statementId,
                            dynamicBodyConversion.originalBody(),
                            dynamicBodyConversion.convertedBody(),
                            dynamicBodyConversion.appliedRules(),
                            false,
                            ""
                    ));
                }
                continue;
            }

            SqlConversionResult conversionResult = sqlConverter.convert(originalSql);
            if (conversionResult.manualReviewRequired()) {
                manualReviewItems.add(new SqlChange(
                        reportPath,
                        statementId,
                        conversionResult.originalSql(),
                        conversionResult.convertedSql(),
                        conversionResult.appliedRules(),
                        true,
                        conversionResult.reason()
                ));
            } else if (conversionResult.changed()) {
                replacements.add(StatementReplacement.staticSql(
                        statement.getTagName(),
                        statementId,
                        conversionResult.convertedSql()
                ));
                automaticConversions.add(new SqlChange(
                        reportPath,
                        statementId,
                        conversionResult.originalSql(),
                        conversionResult.convertedSql(),
                        conversionResult.appliedRules(),
                        false,
                        ""
                ));
            }
        }

        changed = !replacements.isEmpty();
        if (changed && writeChanges) {
            writeReplacements(inputPath, replacements);
        }
        return new MapperRewriteResult(automaticConversions, manualReviewItems, warnings);
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

    private DynamicBodyConversion convertDynamicXmlTextSegments(String statementTagName, String rawBody, SqlConverter sqlConverter) {
        StringBuilder convertedBody = new StringBuilder(rawBody.length());
        List<String> appliedRules = new ArrayList<>();
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
                TextSegmentConversion conversion = convertTextSegment(content, sqlConverter);
                convertedBody.append(toCdata(conversion.convertedText()));
                addAppliedRules(appliedRules, conversion.appliedRules());
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
                TextSegmentConversion conversion = convertTextSegment(text, sqlConverter);
                convertedBody.append(conversion.convertedText());
                addAppliedRules(appliedRules, conversion.appliedRules());
                changed = changed || conversion.changed();
                index = textEnd;
            }
        }
        String rewrittenBody = convertedBody.toString();
        DynamicBodyConversion structuralConversion = convertDynamicXmlStructure(statementTagName, rewrittenBody);
        if (structuralConversion.changed()) {
            rewrittenBody = structuralConversion.convertedBody();
            addAppliedRules(appliedRules, structuralConversion.appliedRules());
            changed = true;
        }
        return new DynamicBodyConversion(rawBody, changed ? rewrittenBody : rawBody, appliedRules, changed);
    }

    private DynamicBodyConversion convertDynamicXmlStructure(String statementTagName, String body) {
        if (!"insert".equals(statementTagName) && !"update".equals(statementTagName)) {
            return new DynamicBodyConversion(body, body, List.of(), false);
        }

        List<String> appliedRules = new ArrayList<>();
        String converted = body;

        String dynamicMerge = convertDynamicOnDuplicateKeyUpdate(converted);
        if (!dynamicMerge.equals(converted)) {
            appliedRules.add(MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
            converted = dynamicMerge;
        }

        if (!"insert".equals(statementTagName)) {
            return new DynamicBodyConversion(body, converted, appliedRules, !appliedRules.isEmpty());
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
        return new DynamicBodyConversion(body, converted, appliedRules, !appliedRules.isEmpty());
    }

    private String convertDynamicOnDuplicateKeyUpdate(String body) {
        IfWrapper wrapper = readWrappingIf(body);
        String candidate = wrapper == null ? body : wrapper.body();
        String converted = convertDynamicOnDuplicateKeyUpdateCore(candidate);
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

    private String convertDynamicOnDuplicateKeyUpdateCore(String body) {
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

    private TextSegmentConversion convertTextSegment(String text, SqlConverter sqlConverter) {
        SqlConversionResult conversionResult = sqlConverter.convert(text);
        if (conversionResult.manualReviewRequired() || !conversionResult.changed()) {
            return new TextSegmentConversion(text, List.of(), false);
        }
        return new TextSegmentConversion(
                conversionResult.convertedSql(),
                conversionResult.appliedRules(),
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
            boolean changed
    ) {
        DynamicBodyConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }
    }

    private record TextSegmentConversion(String convertedText, List<String> appliedRules, boolean changed) {
        TextSegmentConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }
    }
}
