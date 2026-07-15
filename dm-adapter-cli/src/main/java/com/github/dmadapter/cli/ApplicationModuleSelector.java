package com.github.dmadapter.cli;

import com.github.dmadapter.core.DmAdapterException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class ApplicationModuleSelector {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");

    ApplicationModule select(Path projectRoot, Path configuredAppModule) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new DmAdapterException("Project path is not a directory: " + normalizedRoot);
        }

        if (configuredAppModule != null) {
            return selectExplicitModule(normalizedRoot, configuredAppModule);
        }

        List<ApplicationModule> modules = discoverApplicationModules(normalizedRoot);
        if (modules.isEmpty()) {
            throw new DmAdapterException("No Spring Boot application class was found under src/main/java.");
        }
        if (modules.size() > 1) {
            throw new DmAdapterException("Multiple Spring Boot application modules were found; pass --app-module. Candidates: "
                    + describe(normalizedRoot, modules));
        }
        return modules.get(0);
    }

    private ApplicationModule selectExplicitModule(Path projectRoot, Path configuredAppModule) {
        Path moduleRoot = configuredAppModule.isAbsolute()
                ? configuredAppModule.toAbsolutePath().normalize()
                : projectRoot.resolve(configuredAppModule).toAbsolutePath().normalize();
        Path pomPath = moduleRoot.resolve("pom.xml");
        if (Files.isRegularFile(pomPath)) {
            return new ApplicationModule(moduleRoot, pomPath, null, "");
        }
        return selectExplicitModuleByArtifactId(projectRoot, configuredAppModule.toString(), moduleRoot);
    }

    private ApplicationModule selectExplicitModuleByArtifactId(Path projectRoot, String artifactId, Path failedModuleRoot) {
        List<Path> matches = pomFiles(projectRoot).stream()
                .filter(pomPath -> artifactId.equals(PomArtifactIdReader.read(pomPath).orElse("")))
                .toList();
        if (matches.size() == 1) {
            Path pomPath = matches.get(0);
            return new ApplicationModule(pomPath.getParent(), pomPath, null, "");
        }
        if (matches.size() > 1) {
            throw new DmAdapterException("Application module artifactId matched multiple pom.xml files for '"
                    + artifactId + "': " + describePomPaths(projectRoot, matches)
                    + ". Pass an explicit module path.");
        }
        throw new DmAdapterException("Application module does not contain pom.xml: " + failedModuleRoot
                + "; no pom.xml with artifactId '" + artifactId + "' was found under " + projectRoot
                + ". Pass --app-module . to use the project root.");
    }

    private List<Path> pomFiles(Path projectRoot) {
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> !isBuildOrGitPath(projectRoot, path))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DmAdapterException("Failed to scan pom.xml files under " + projectRoot, e);
        }
    }

    private List<ApplicationModule> discoverApplicationModules(Path projectRoot) {
        Map<Path, List<Path>> classesByPom = new LinkedHashMap<>();
        for (Path applicationClass : findApplicationClasses(projectRoot)) {
            nearestPomAncestor(projectRoot, applicationClass)
                    .ifPresent(pomPath -> classesByPom.computeIfAbsent(pomPath, ignored -> new ArrayList<>()).add(applicationClass));
        }
        return classesByPom.entrySet().stream()
                .map(entry -> {
                    List<Path> applicationClasses = entry.getValue().stream().sorted().toList();
                    Path pomPath = entry.getKey();
                    return new ApplicationModule(
                            pomPath.getParent(),
                            pomPath,
                            applicationClasses.get(0),
                            packageName(applicationClasses.get(0))
                    );
                })
                .sorted(Comparator.comparing(module -> module.moduleRoot().toString()))
                .toList();
    }

    private List<Path> findApplicationClasses(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(path -> !isBuildOrGitPath(root, path))
                    .filter(this::looksLikeSpringBootApplication)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DmAdapterException("Failed to scan Spring Boot application classes under " + root, e);
        }
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
            String content = asciiView(Files.readAllBytes(javaFile));
            return content.contains("@SpringBootApplication") || content.contains("SpringApplication.run(");
        } catch (IOException e) {
            return false;
        }
    }

    private String packageName(Path javaFile) {
        try {
            Matcher matcher = PACKAGE_PATTERN.matcher(asciiView(Files.readAllBytes(javaFile)));
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "";
        } catch (IOException e) {
            throw new DmAdapterException("Failed to read Java source file: " + javaFile, e);
        }
    }

    private String asciiView(byte[] bytes) {
        StringBuilder content = new StringBuilder(bytes.length);
        for (byte sourceByte : bytes) {
            int value = sourceByte & 0xff;
            if (value == 0) {
                continue;
            }
            if (value == '\t' || value == '\n' || value == '\r' || (value >= 32 && value <= 126)) {
                content.append((char) value);
            } else {
                content.append(' ');
            }
        }
        return content.toString();
    }

    private boolean isBuildOrGitPath(Path root, Path path) {
        String relativePath = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return relativePath.startsWith("target/")
                || relativePath.contains("/target/")
                || relativePath.startsWith(".git/")
                || relativePath.contains("/.git/");
    }

    private String describe(Path projectRoot, List<ApplicationModule> modules) {
        return modules.stream()
                .map(module -> projectRoot.relativize(module.moduleRoot()).toString().replace('\\', '/'))
                .toList()
                .toString();
    }

    private String describePomPaths(Path projectRoot, List<Path> pomPaths) {
        return pomPaths.stream()
                .map(path -> projectRoot.relativize(path).toString().replace('\\', '/'))
                .toList()
                .toString();
    }
}
