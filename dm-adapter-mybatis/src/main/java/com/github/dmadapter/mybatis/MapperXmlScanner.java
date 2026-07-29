package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.MapperXmlFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class MapperXmlScanner {
    private final MapperLocationConfigScanner mapperLocationConfigScanner;

    public MapperXmlScanner() {
        this(new MapperLocationConfigScanner());
    }

    MapperXmlScanner(MapperLocationConfigScanner mapperLocationConfigScanner) {
        this.mapperLocationConfigScanner = mapperLocationConfigScanner;
    }

    public List<MapperXmlFile> scan(Path projectRoot) {
        List<Path> resourcesRoots = findResourcesRoots(projectRoot);
        if (resourcesRoots.isEmpty()) {
            return List.of();
        }

        List<String> configuredMapperLocations = mapperLocationConfigScanner.scan(projectRoot);
        if (!configuredMapperLocations.isEmpty()) {
            List<MapperLocationPattern> patterns = configuredMapperLocations.stream()
                    .map(MapperLocationPattern::from)
                    .toList();
            List<MapperXmlFile> configuredFiles = scanResources(
                    resourcesRoots,
                    relativePath -> patterns.stream().anyMatch(pattern -> pattern.matches(relativePath))
            );
            if (!configuredFiles.isEmpty()) {
                return configuredFiles;
            }
        }
        return scanResources(resourcesRoots, relativePath -> true);
    }

    private List<Path> findResourcesRoots(Path projectRoot) {
        if (!Files.isDirectory(projectRoot)) {
            return List.of();
        }
        List<Path> resourcesRoots = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> path.endsWith("src/main/resources"))
                    .filter(path -> !isBuildOrGitPath(projectRoot, path))
                    .sorted()
                    .forEach(path -> resourcesRoots.add(path.toAbsolutePath().normalize()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to find src/main/resources directories under " + projectRoot, e);
        }
        return resourcesRoots;
    }

    private List<MapperXmlFile> scanResources(List<Path> resourcesRoots, Predicate<String> relativePathPredicate) {
        List<MapperXmlFile> mapperXmlFiles = new ArrayList<>();
        for (Path resourcesRoot : resourcesRoots) {
            try (Stream<Path> files = Files.walk(resourcesRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".xml"))
                        .filter(path -> !normalize(resourcesRoot.relativize(path)).startsWith("mapper-dm/"))
                        .filter(path -> relativePathPredicate.test(normalize(resourcesRoot.relativize(path))))
                        .filter(this::isMapperXml)
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(path -> mapperXmlFiles.add(new MapperXmlFile(
                                path.toAbsolutePath().normalize().toString(),
                                resourcesRoot.toString(),
                                normalize(resourcesRoot.relativize(path))
                        )));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to scan mapper XML files under " + resourcesRoot, e);
            }
        }
        return mapperXmlFiles;
    }

    private boolean isBuildOrGitPath(Path projectRoot, Path path) {
        String relativePath = projectRoot.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
        return relativePath.startsWith("target/")
                || relativePath.contains("/target/")
                || relativePath.startsWith(".git/")
                || relativePath.contains("/.git/");
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
