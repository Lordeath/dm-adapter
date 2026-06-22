package com.github.dmadapter.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class DmValidationConfigReader {
    private DmValidationConfigReader() {
    }

    static Optional<String> schema(Path projectRoot, Path configuredPath) {
        Path path = resolveProjectPath(projectRoot, configuredPath, DmSqlValidationTestGenerator.DEFAULT_CONFIG_PATH);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String withoutComment = stripComment(line);
                String trimmed = withoutComment.trim();
                if (trimmed.startsWith("schema:")) {
                    String value = scalar(trimmed.substring("schema:".length()));
                    if (!value.isBlank()) {
                        return Optional.of(value);
                    }
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read validation config: " + path, e);
        }
    }

    static Path resolveProjectPath(Path projectRoot, Path configuredPath, String defaultRelativePath) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (configuredPath == null) {
            return root.resolve(defaultRelativePath).toAbsolutePath().normalize();
        }
        return configuredPath.isAbsolute()
                ? configuredPath.toAbsolutePath().normalize()
                : root.resolve(configuredPath).toAbsolutePath().normalize();
    }

    private static String stripComment(String line) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (current == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (current == '#' && !singleQuoted && !doubleQuoted) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String scalar(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
