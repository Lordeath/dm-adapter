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
    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

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
                replacements.add(new StatementReplacement(statement.getTagName(), statementId, conversionResult.convertedSql()));
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

    private List<Element> statementElements(Document document) {
        List<Element> elements = new ArrayList<>();
        Element root = document.getDocumentElement();
        if (root == null) {
            return elements;
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && STATEMENT_TAGS.contains(element.getTagName())) {
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
                StatementBody statementBody = findStatementBody(xml, replacement, searchFrom);
                String rewrittenBody = rewrittenBody(statementBody.rawBody(), replacement.convertedSql());
                xml = xml.substring(0, statementBody.start()) + rewrittenBody + xml.substring(statementBody.end());
                searchFrom = statementBody.start() + rewrittenBody.length();
            }
            Files.writeString(path, xml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write mapper XML: " + path, e);
        }
    }

    private StatementBody findStatementBody(String xml, StatementReplacement replacement, int searchFrom) {
        if (replacement.statementId().isBlank()) {
            throw new IllegalStateException("Mapper statement id is required for text-preserving rewrite.");
        }

        String quotedTag = Pattern.quote(replacement.tagName());
        String quotedId = Pattern.quote(replacement.statementId());
        Pattern openingPattern = Pattern.compile(
                "(?s)<\\s*" + quotedTag + "\\b(?=[^>]*\\bid\\s*=\\s*(?:\"" + quotedId + "\"|'" + quotedId + "'))[^>]*>"
        );
        Matcher openingMatcher = openingPattern.matcher(xml);
        if (!openingMatcher.find(searchFrom)) {
            throw new IllegalStateException("Failed to locate mapper statement: " + replacement.statementId());
        }

        Pattern closingPattern = Pattern.compile("(?s)</\\s*" + quotedTag + "\\s*>");
        Matcher closingMatcher = closingPattern.matcher(xml);
        if (!closingMatcher.find(openingMatcher.end())) {
            throw new IllegalStateException("Failed to locate closing tag for mapper statement: " + replacement.statementId());
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

    private record StatementReplacement(String tagName, String statementId, String convertedSql) {
    }

    private record StatementBody(int start, int end, String rawBody) {
    }
}
