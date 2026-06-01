package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.MapperXmlFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class MapperXmlScanner {
    public List<MapperXmlFile> scan(Path projectRoot) {
        Path resourcesRoot = projectRoot.resolve("src/main/resources");
        if (!Files.isDirectory(resourcesRoot)) {
            return List.of();
        }

        List<MapperXmlFile> mapperXmlFiles = new ArrayList<>();
        try (Stream<Path> files = Files.walk(resourcesRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .filter(path -> !normalize(resourcesRoot.relativize(path)).startsWith("mapper-dm/"))
                    .filter(this::isMapperXml)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> mapperXmlFiles.add(new MapperXmlFile(
                            path.toAbsolutePath().normalize().toString(),
                            normalize(resourcesRoot.relativize(path))
                    )));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan mapper XML files under " + resourcesRoot, e);
        }
        return mapperXmlFiles;
    }

    private boolean isMapperXml(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (!content.contains("<mapper")) {
                return false;
            }
            return "mapper".equals(XmlSupport.parse(path).getDocumentElement().getTagName());
        } catch (Exception e) {
            return false;
        }
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
