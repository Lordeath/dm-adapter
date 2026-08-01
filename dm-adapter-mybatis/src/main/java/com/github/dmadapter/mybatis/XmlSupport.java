package com.github.dmadapter.mybatis;

import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class XmlSupport {
    private XmlSupport() {
    }

    static Document parse(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return parse(input);
        }
    }

    static Document parse(String xml) throws Exception {
        try (InputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            return parse(input);
        }
    }

    private static Document parse(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        enableFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        enableFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        enableFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        enableFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(input);
    }

    private static void enableFeature(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception ignored) {
            // XML parser support varies by JDK distribution; unsupported hardening flags are skipped.
        }
    }
}
