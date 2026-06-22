package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    @Test
    void usesWindowsMavenCommandName() {
        ValidationTestRunner runner = new ValidationTestRunner(Map.of(), "Windows 11", ProcessBuilder::start);

        assertThat(runner.mavenCommand(generationResult()).get(0)).isEqualTo("mvn.cmd");
    }

    @Test
    void prefersWindowsMavenWrapperOnWindows() throws IOException {
        Files.createFile(tempDir.resolve("mvnw.cmd"));
        ValidationTestRunner runner = new ValidationTestRunner(Map.of(), "Windows 11", ProcessBuilder::start);

        assertThat(runner.mavenCommand(generationResult()).get(0))
                .isEqualTo(tempDir.resolve("mvnw.cmd").toString());
    }

    @Test
    void allowsReactorModulesWithoutGeneratedValidationTest() {
        assertThat(new ValidationTestRunner().mavenCommand(generationResult()))
                .contains("-Dsurefire.failIfNoSpecifiedTests=false");
    }

    @Test
    void includesMavenCommandAndWorkingDirectoryInRunOutput() {
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> processWithOutput("maven output\n", 1)
        );

        ValidationTestRunResult result = runner.runIfConfigured(
                generationResult(),
                DmValidationEnvironment.from(Map.of(
                        "DM_SQL_VALIDATION", "true",
                        "DM_JDBC_URL", "jdbc:dm://localhost:5236",
                        "DM_DB_USERNAME", "SYSDBA",
                        "DM_DB_PASSWORD", "SYSDBA"
                ))
        );

        assertThat(result.attempted()).isTrue();
        assertThat(result.outputTail())
                .anySatisfy(line -> assertThat(line)
                        .contains("Maven command: [mvn")
                        .contains("-Dsurefire.failIfNoSpecifiedTests=false"))
                .anySatisfy(line -> assertThat(line).contains("Working directory: " + tempDir))
                .anySatisfy(line -> assertThat(line).contains("maven output"));
    }

    @Test
    void includesDiagnosticsWhenMavenProcessCannotStart() {
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(
                        "Path", "",
                        "PATHEXT", ".COM;.EXE;.BAT;.CMD"
                ),
                "Windows 11",
                processBuilder -> {
                    throw new IOException("boom");
                }
        );

        ValidationTestRunResult result = runner.runIfConfigured(
                generationResult(),
                DmValidationEnvironment.from(Map.of(
                        "DM_SQL_VALIDATION", "true",
                        "DM_JDBC_URL", "jdbc:dm://localhost:5236",
                        "DM_DB_USERNAME", "SYSDBA",
                        "DM_DB_PASSWORD", "SYSDBA"
                ))
        );

        assertThat(result.attempted()).isTrue();
        assertThat(result.message()).contains("Failed to start Maven validation test");
        assertThat(result.outputTail())
                .anySatisfy(line -> assertThat(line).contains("Maven validation diagnostics"))
                .anySatisfy(line -> assertThat(line).contains("Start error: IOException: boom"))
                .anySatisfy(line -> assertThat(line).contains("Maven command: [mvn.cmd"))
                .anySatisfy(line -> assertThat(line).contains("PATHEXT: .COM;.EXE;.BAT;.CMD"))
                .anySatisfy(line -> assertThat(line).contains("Maven candidates on PATH: <none>"))
                .anySatisfy(line -> assertThat(line).contains("Windows note:"));
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

    private static Process processWithOutput(String output, int exitCode) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        return new Process() {
            @Override
            public OutputStream getOutputStream() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public InputStream getErrorStream() {
                return InputStream.nullInputStream();
            }

            @Override
            public int waitFor() {
                return exitCode;
            }

            @Override
            public int exitValue() {
                return exitCode;
            }

            @Override
            public void destroy() {
            }
        };
    }
}
