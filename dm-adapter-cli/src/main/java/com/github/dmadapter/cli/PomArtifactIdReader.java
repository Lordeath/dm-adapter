package com.github.dmadapter.cli;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class PomArtifactIdReader {
    private PomArtifactIdReader() {
    }

    static Optional<String> read(Path pomPath) {
        if (pomPath == null || !Files.isRegularFile(pomPath)) {
            return Optional.empty();
        }
        try (InputStream inputStream = Files.newInputStream(pomPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(inputStream);
            Element root = document.getDocumentElement();
            if (root == null) {
                return Optional.empty();
            }
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element element && "artifactId".equals(element.getTagName())) {
                    String value = element.getTextContent();
                    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
