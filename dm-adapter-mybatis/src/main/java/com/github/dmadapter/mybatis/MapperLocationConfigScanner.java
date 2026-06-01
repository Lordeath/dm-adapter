package com.github.dmadapter.mybatis;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

final class MapperLocationConfigScanner {
    private static final Set<String> MAPPER_LOCATION_KEYS = Set.of(
            "mybatis.mapperLocations",
            "mybatis.mapper-locations",
            "mybatis.mapper_locations"
    );
    private static final Set<String> YAML_MAPPER_LOCATION_KEYS = Set.of(
            "mapperLocations",
            "mapper-locations",
            "mapper_locations"
    );

    List<String> scan(Path projectRoot) {
        if (!Files.isDirectory(projectRoot)) {
            return List.of();
        }
        List<String> mapperLocations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(projectRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(this::isApplicationConfig)
                    .filter(path -> !isBuildOrGitPath(projectRoot, path))
                    .sorted()
                    .forEach(path -> mapperLocations.addAll(readMapperLocations(path)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan Spring Boot configuration files under " + projectRoot, e);
        }
        return mapperLocations.stream().distinct().toList();
    }

    private boolean isApplicationConfig(Path path) {
        String name = path.getFileName().toString();
        return name.matches("application(?:-[A-Za-z0-9_.-]+)?\\.(properties|yml|yaml)");
    }

    private boolean isBuildOrGitPath(Path projectRoot, Path path) {
        String relativePath = projectRoot.relativize(path).toString().replace('\\', '/');
        return relativePath.startsWith("target/")
                || relativePath.contains("/target/")
                || relativePath.startsWith(".git/")
                || relativePath.contains("/.git/");
    }

    private List<String> readMapperLocations(Path path) {
        String name = path.getFileName().toString();
        if (name.endsWith(".properties")) {
            return readPropertiesMapperLocations(path);
        }
        return readYamlMapperLocations(path);
    }

    private List<String> readPropertiesMapperLocations(Path path) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return List.of();
        }

        List<String> mapperLocations = new ArrayList<>();
        for (String key : MAPPER_LOCATION_KEYS) {
            mapperLocations.addAll(splitLocations(properties.getProperty(key)));
        }
        return mapperLocations;
    }

    private List<String> readYamlMapperLocations(Path path) {
        List<String> mapperLocations = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }

        int myBatisIndent = -1;
        int listIndent = -1;
        for (String line : lines) {
            String withoutComment = stripYamlComment(line);
            if (withoutComment.isBlank()) {
                continue;
            }
            int indent = leadingSpaces(withoutComment);
            String trimmed = withoutComment.trim();

            if (listIndent >= 0 && indent > listIndent && trimmed.startsWith("- ")) {
                mapperLocations.addAll(splitLocations(trimmed.substring(2)));
                continue;
            }
            listIndent = -1;

            if (trimmed.startsWith("mybatis.")) {
                mapperLocations.addAll(readDirectYamlProperty(trimmed));
                continue;
            }
            if (indent == 0 && trimmed.equals("mybatis:")) {
                myBatisIndent = indent;
                continue;
            }
            if (myBatisIndent >= 0 && indent <= myBatisIndent) {
                myBatisIndent = -1;
            }
            if (myBatisIndent >= 0 && indent > myBatisIndent) {
                String key = yamlKey(trimmed);
                if (YAML_MAPPER_LOCATION_KEYS.contains(key)) {
                    String value = yamlValue(trimmed);
                    if (value.isBlank()) {
                        listIndent = indent;
                    } else {
                        mapperLocations.addAll(splitLocations(value));
                    }
                }
            }
        }
        return mapperLocations;
    }

    private List<String> readDirectYamlProperty(String trimmed) {
        String key = yamlKey(trimmed);
        if (MAPPER_LOCATION_KEYS.contains(key)) {
            return splitLocations(yamlValue(trimmed));
        }
        return List.of();
    }

    private String stripYamlComment(String line) {
        int index = line.indexOf('#');
        return index >= 0 ? line.substring(0, index) : line;
    }

    private int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private String yamlKey(String trimmed) {
        int index = trimmed.indexOf(':');
        return index >= 0 ? trimmed.substring(0, index).trim() : trimmed;
    }

    private String yamlValue(String trimmed) {
        int index = trimmed.indexOf(':');
        return index >= 0 ? trimmed.substring(index + 1).trim() : "";
    }

    private List<String> splitLocations(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = stripQuotes(value.trim());
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        List<String> locations = new ArrayList<>();
        for (String item : normalized.split(",")) {
            String location = stripQuotes(item.trim());
            if (!location.isBlank()) {
                locations.add(location);
            }
        }
        return locations;
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
