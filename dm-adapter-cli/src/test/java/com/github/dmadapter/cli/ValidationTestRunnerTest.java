package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationTestRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void skipsRunWhenValidationFlagIsNotEnabled() {
        ValidationTestRunResult result = new ValidationTestRunner().runIfConfigured(
                generationResult(),
                DmValidationEnvironment.from(Map.of())
        );

        assertThat(result.attempted()).isFalse();
        assertThat(result.message()).contains("DM_SQL_VALIDATION is not true");
    }

    @Test
    void skipsRunWhenRequiredConnectionVariablesAreMissing() {
        ValidationTestRunResult result = new ValidationTestRunner().runIfConfigured(
                generationResult(),
                DmValidationEnvironment.from(Map.of("DM_SQL_VALIDATION", "true"))
        );

        assertThat(result.attempted()).isFalse();
        assertThat(result.message())
                .contains("DM_JDBC_URL")
                .contains("DM_DB_USERNAME")
                .contains("DM_DB_PASSWORD");
    }

    private ValidationTestGenerationResult generationResult() {
        return new ValidationTestGenerationResult(
                tempDir,
                tempDir,
                tempDir.resolve(".dm-adapter/sql-validation.yml"),
                tempDir.resolve("src/test/java/DmSqlValidationTest.java"),
                List.of(),
                List.of()
        );
    }
}
