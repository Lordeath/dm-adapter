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
        RecordingShutdownHookRegistry shutdownHooks = new RecordingShutdownHookRegistry();
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> processWithOutput("maven output\n", 1),
                shutdownHooks
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
        assertThat(shutdownHooks.addedHook).isSameAs(shutdownHooks.removedHook);
    }

    @Test
    void shutdownHookDestroysMavenProcess() {
        RecordingShutdownHookRegistry shutdownHooks = new RecordingShutdownHookRegistry();
        shutdownHooks.runHookWhenAdded = true;
        RecordingProcess process = new RecordingProcess("maven output\n", 143, false);
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> process,
                shutdownHooks
        );

        runner.runIfConfigured(
                generationResult(),
                DmValidationEnvironment.from(Map.of(
                        "DM_SQL_VALIDATION", "true",
                        "DM_JDBC_URL", "jdbc:dm://localhost:5236",
                        "DM_DB_USERNAME", "SYSDBA",
                        "DM_DB_PASSWORD", "SYSDBA"
                ))
        );

        assertThat(process.destroyForciblyCount).isEqualTo(1);
    }

    @Test
    void destroysMavenProcessWhenInterrupted() {
        RecordingShutdownHookRegistry shutdownHooks = new RecordingShutdownHookRegistry();
        RecordingProcess process = new RecordingProcess("", 1, true);
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> process,
                shutdownHooks
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

        assertThat(result.message()).contains("interrupted");
        assertThat(process.destroyForciblyCount).isEqualTo(1);
        assertThat(shutdownHooks.addedHook).isSameAs(shutdownHooks.removedHook);
        assertThat(Thread.interrupted()).isTrue();
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
        return new RecordingProcess(output, exitCode, false);
    }

    private static class RecordingShutdownHookRegistry implements ValidationTestRunner.ShutdownHookRegistry {
        private Thread addedHook;
        private Thread removedHook;
        private boolean runHookWhenAdded;

        @Override
        public void add(Thread hook) {
            addedHook = hook;
            if (runHookWhenAdded) {
                hook.run();
            }
        }

        @Override
        public boolean remove(Thread hook) {
            removedHook = hook;
            return true;
        }
    }

    private static class RecordingProcess extends Process {
        private final byte[] bytes;
        private final int exitCode;
        private final boolean interruptOnWait;
        private boolean alive = true;
        private int destroyForciblyCount;

        private RecordingProcess(String output, int exitCode, boolean interruptOnWait) {
            this.bytes = output.getBytes(StandardCharsets.UTF_8);
            this.exitCode = exitCode;
            this.interruptOnWait = interruptOnWait;
        }

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
        public int waitFor() throws InterruptedException {
            if (interruptOnWait) {
                throw new InterruptedException("interrupted");
            }
            alive = false;
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCount++;
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }
    }
}
