package com.github.dmadapter.cli;

import com.github.dmadapter.core.DmAdapterException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class LegacyWorkspaceMigrator {
    static final String REWRITE_CONFIG = "sql-rewrite.yml";
    static final String VALIDATION_CONFIG = "sql-validation.yml";

    List<String> migrateDefaults(
            Path projectRoot,
            Path workspaceDir,
            boolean migrateRewriteConfig,
            boolean migrateValidationConfig
    ) {
        List<String> messages = new ArrayList<>();
        if (migrateRewriteConfig) {
            copyIfMissing(projectRoot, workspaceDir, REWRITE_CONFIG).ifPresent(messages::add);
        }
        if (migrateValidationConfig) {
            copyIfMissing(projectRoot, workspaceDir, VALIDATION_CONFIG).ifPresent(messages::add);
        }
        return List.copyOf(messages);
    }

    private Optional<String> copyIfMissing(Path projectRoot, Path workspaceDir, String fileName) {
        Path source = projectRoot.toAbsolutePath().normalize().resolve(".dm-adapter").resolve(fileName);
        Path target = workspaceDir.toAbsolutePath().normalize().resolve(fileName);
        if (source.equals(target) || !Files.isRegularFile(source) || Files.exists(target)) {
            return Optional.empty();
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
            return Optional.of("Copied legacy dm-adapter config from " + source + " to " + target + ".");
        } catch (IOException e) {
            throw new DmAdapterException("Failed to copy legacy dm-adapter config from "
                    + source + " to " + target, e);
        }
    }
}
