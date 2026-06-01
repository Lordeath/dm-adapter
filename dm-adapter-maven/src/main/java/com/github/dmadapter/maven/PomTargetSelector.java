package com.github.dmadapter.maven;

import com.github.dmadapter.core.MapperXmlFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class PomTargetSelector {
    public PomTargetSelection select(Path projectRoot, List<MapperXmlFile> mapperXmlFiles) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        List<String> warnings = new ArrayList<>();

        List<Path> applicationPoms = findSpringBootApplicationPoms(normalizedRoot);
        if (!applicationPoms.isEmpty()) {
            if (applicationPoms.size() > 1) {
                warnings.add("Multiple Spring Boot application module pom.xml files were detected; Dameng JDBC dependency will be checked in each one: "
                        + describe(normalizedRoot, applicationPoms));
            }
            return new PomTargetSelection(applicationPoms, "Spring Boot application module", warnings);
        }

        List<Path> mapperPoms = findMapperModulePoms(normalizedRoot, mapperXmlFiles);
        if (!mapperPoms.isEmpty()) {
            if (mapperPoms.size() > 1) {
                warnings.add("Multiple mapper module pom.xml files were detected; Dameng JDBC dependency will be checked in each one: "
                        + describe(normalizedRoot, mapperPoms));
            }
            return new PomTargetSelection(mapperPoms, "mapper XML module", warnings);
        }

        Path rootPom = normalizedRoot.resolve("pom.xml");
        if (Files.isRegularFile(rootPom)) {
            warnings.add("No Spring Boot application module or mapper module pom.xml was detected; falling back to project root pom.xml.");
            return new PomTargetSelection(List.of(rootPom), "project root", warnings);
        }
        return new PomTargetSelection(List.of(), "", warnings);
    }

    private List<Path> findSpringBootApplicationPoms(Path projectRoot) {
        if (!Files.isDirectory(projectRoot)) {
            return List.of();
        }
        Set<Path> pomPaths = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> isMainJavaPath(path))
                    .filter(path -> !isBuildOrGitPath(projectRoot, path))
                    .filter(this::looksLikeSpringBootApplication)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> nearestPomAncestor(projectRoot, path).ifPresent(pomPaths::add));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan Spring Boot application classes under " + projectRoot, e);
        }
        return pomPaths.stream().sorted().toList();
    }

    private List<Path> findMapperModulePoms(Path projectRoot, List<MapperXmlFile> mapperXmlFiles) {
        Set<Path> pomPaths = new LinkedHashSet<>();
        for (MapperXmlFile mapperXmlFile : mapperXmlFiles) {
            mapperSearchStart(mapperXmlFile)
                    .flatMap(path -> nearestPomAncestor(projectRoot, path))
                    .ifPresent(pomPaths::add);
        }
        return pomPaths.stream().sorted().toList();
    }

    private Optional<Path> mapperSearchStart(MapperXmlFile mapperXmlFile) {
        if (!mapperXmlFile.resourcesRoot().isBlank()) {
            return Optional.of(Path.of(mapperXmlFile.resourcesRoot()).toAbsolutePath().normalize());
        }
        if (!mapperXmlFile.path().isBlank()) {
            return Optional.of(Path.of(mapperXmlFile.path()).toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    private Optional<Path> nearestPomAncestor(Path projectRoot, Path path) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path current = path.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null && current.startsWith(root)) {
            Path pom = current.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                return Optional.of(pom.toAbsolutePath().normalize());
            }
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private boolean looksLikeSpringBootApplication(Path javaFile) {
        try {
            String content = Files.readString(javaFile, StandardCharsets.UTF_8);
            return content.contains("@SpringBootApplication") || content.contains("SpringApplication.run(");
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isMainJavaPath(Path path) {
        return path.toString().replace('\\', '/').contains("/src/main/java/");
    }

    private boolean isBuildOrGitPath(Path projectRoot, Path path) {
        String relativePath = projectRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return relativePath.startsWith("target/")
                || relativePath.contains("/target/")
                || relativePath.startsWith(".git/")
                || relativePath.contains("/.git/");
    }

    private String describe(Path projectRoot, List<Path> pomPaths) {
        return pomPaths.stream()
                .map(path -> projectRoot.relativize(path).toString().replace('\\', '/'))
                .toList()
                .toString();
    }
}
