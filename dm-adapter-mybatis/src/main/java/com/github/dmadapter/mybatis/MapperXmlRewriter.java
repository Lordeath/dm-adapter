package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlConversionResult;
import com.github.dmadapter.sql.SqlConverter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MapperXmlRewriter {
    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete");

    public MapperRewriteResult rewrite(Path inputPath, String reportPath, boolean writeChanges, SqlConverter sqlConverter) {
        List<SqlChange> automaticConversions = new ArrayList<>();
        List<SqlChange> manualReviewItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

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
                replaceChildrenWithText(document, statement, conversionResult.convertedSql());
                changed = true;
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

        if (changed && writeChanges) {
            writeDocument(document, inputPath);
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

    private void replaceChildrenWithText(Document document, Element statement, String sql) {
        while (statement.hasChildNodes()) {
            statement.removeChild(statement.getFirstChild());
        }
        statement.appendChild(document.createTextNode(sql));
    }

    private void writeDocument(Document document, Path path) {
        try {
            Files.createDirectories(path.getParent());
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(new DOMSource(document), new StreamResult(path.toFile()));
        } catch (IOException | TransformerException e) {
            throw new IllegalStateException("Failed to write mapper XML: " + path, e);
        }
    }
}
