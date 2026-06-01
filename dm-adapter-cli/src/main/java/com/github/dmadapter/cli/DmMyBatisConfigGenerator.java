package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.FileChange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

class DmMyBatisConfigGenerator {
    ConfigGenerationResult generate(AdapterContext context) {
        Path configPath = context.mainResourcesRoot().resolve("application-dm.yml");
        if (Files.exists(configPath)) {
            return new ConfigGenerationResult(
                    Optional.empty(),
                    List.of("Skipped application-dm.yml generation because it already exists: " + configPath)
            );
        }

        if (context.dryRun()) {
            return new ConfigGenerationResult(
                    Optional.of(FileChange.planned(configPath.toString(), "CREATE", "Generate Dameng profile MyBatis mapper configuration")),
                    List.of()
            );
        }

        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, configContent(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to generate Dameng MyBatis configuration: " + configPath, e);
        }
        return new ConfigGenerationResult(
                Optional.of(FileChange.applied(configPath.toString(), "CREATE", "Generated Dameng profile MyBatis mapper configuration")),
                List.of()
        );
    }

    private String configContent() {
        return """
                spring:
                  datasource:
                    driver-class-name: dm.jdbc.driver.DmDriver
                mybatis:
                  mapper-locations: classpath*:mapper-dm/**/*.xml
                """;
    }
}
