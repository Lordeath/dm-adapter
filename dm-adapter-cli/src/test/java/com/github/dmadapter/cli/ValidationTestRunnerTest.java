package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
                .contains(
                        "-Ddm.adapter.dir=" + tempDir.resolve(".dm-adapter"),
                        "-Ddm.adapter.projectRoot=" + tempDir,
                        "-DskipTests=false",
                        "-Dmaven.test.skip=false",
                        "-Dsurefire.failIfNoSpecifiedTests=false"
                )
                .doesNotContain("-Dmaven.test.skip.exec=false");
    }

    @Test
    void targetsGeneratedValidationTestByFullyQualifiedClassName() {
        ValidationTestGenerationResult result = generationResult(
                tempDir.resolve("src/test/java/com/example/system/DmSqlValidationTest.java")
        );

        assertThat(new ValidationTestRunner().mavenCommand(result))
                .contains("-Dtest=com.example.system.DmSqlValidationTest");
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
                        .contains("-DskipTests=false")
                        .contains("-Dmaven.test.skip=false")
                        .contains("-Dsurefire.failIfNoSpecifiedTests=false"))
                .anySatisfy(line -> assertThat(line).contains("Working directory: " + tempDir))
                .anySatisfy(line -> assertThat(line).contains("maven output"));
        assertThat(shutdownHooks.addedHook).isSameAs(shutdownHooks.removedHook);
    }

    @Test
    void runsGeneratedMainWhenSurefireSkipsTests() throws IOException {
        List<List<String>> commands = new ArrayList<>();
        List<Process> processes = new ArrayList<>();
        processes.add(processWithOutput("[INFO] Tests are skipped.\n", 0));
        processes.add(processWithOutput("classpath ready\n", 0, () -> {
            try {
                Files.createDirectories(tempDir.resolve(".dm-adapter"));
                Files.writeString(tempDir.resolve(".dm-adapter/sql-validation-classpath.txt"), "dependency with space.jar");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }));
        processes.add(processWithOutput("validation main output\n", 1));
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> {
                    commands.add(processBuilder.command());
                    return processes.remove(0);
                },
                new RecordingShutdownHookRegistry()
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

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(commands).hasSize(3);
        assertThat(commands.get(0))
                .contains("test")
                .contains("-DskipTests=false")
                .contains("-Dmaven.test.skip=false")
                .doesNotContain("-Dmaven.test.skip.exec=false");
        assertThat(commands.get(1))
                .contains("test-compile")
                .contains("dependency:build-classpath")
                .contains("-Dmdep.includeScope=test")
                .doesNotContain("-Dmaven.test.skip.exec=false");
        assertThat(commands.get(2)).hasSize(2);
        assertThat(commands.get(2).get(1))
                .startsWith("@")
                .endsWith("sql-validation-java.args");
        assertThat(commands.get(2))
                .noneSatisfy(argument -> assertThat(argument).contains("dependency with space.jar"));
        assertThat(Files.readString(tempDir.resolve(".dm-adapter/sql-validation-java.args")))
                .contains("-Ddm.adapter.dir=" + tempDir.resolve(".dm-adapter"))
                .contains("-Ddm.adapter.projectRoot=" + tempDir)
                .contains("-cp")
                .contains("dependency with space.jar")
                .contains("DmSqlValidationTest");
        assertThat(result.outputTail())
                .anySatisfy(line -> assertThat(line).contains("Maven fallback command: [mvn"))
                .anySatisfy(line -> assertThat(line).contains("Java validation command: ["))
                .anySatisfy(line -> assertThat(line).contains("validation main output"));
    }

    @Test
    void streamsMavenOutputBeforeProcessExitsAndRedactsSecrets() throws Exception {
        List<String> streamedLines = new ArrayList<>();
        CountDownLatch redactedLineStreamed = new CountDownLatch(1);
        StreamingProcess process = new StreamingProcess(
                "connecting jdbc:dm://localhost:5236 app_user secret\n",
                redactedLineStreamed
        );
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> process,
                new RecordingShutdownHookRegistry(),
                line -> {
                    streamedLines.add(line);
                    if (line.contains("connecting ****** ****** ******")) {
                        redactedLineStreamed.countDown();
                    }
                }
        );

        ValidationTestRunResult result = runner.runIfConfigured(
                generationResult(),
                DmValidationEnvironment.from(Map.of(
                        "DM_SQL_VALIDATION", "true",
                        "DM_JDBC_URL", "jdbc:dm://localhost:5236",
                        "DM_DB_USERNAME", "app_user",
                        "DM_DB_PASSWORD", "secret"
                ))
        );

        assertThat(result.message()).contains("passed");
        assertThat(process.outputObservedBeforeExit).isTrue();
        assertThat(streamedLines)
                .anySatisfy(line -> assertThat(line).contains("[mvn] Running Maven validation test: [mvn"))
                .anySatisfy(line -> assertThat(line).contains("[mvn] connecting ****** ****** ******"));
        assertThat(String.join("\n", streamedLines))
                .doesNotContain("jdbc:dm://localhost:5236")
                .doesNotContain("app_user")
                .doesNotContain("secret");
    }

    @Test
    void includesValidationReportSummaryInRunOutput() throws IOException {
        String reportContent = """
                # 达梦 SQL 验证报告

                - 通过: `790`
                - 失败: `451`
                - 跳过: `234`

                ## 失败分类汇总

                | 分类 | 数量 | 处理建议 |
                | --- | ---: | --- |
                | 测试库对象问题 (TEST_SCHEMA) | 336 | 对齐测试库。 |
                | SQL 语法问题 (SQL_SYNTAX) | 45 | 改写 SQL。 |

                ## 失败模式汇总

                | 模式 | 数量 |
                | --- | ---: |
                | 测试库缺少对象 (TEST_SCHEMA_OBJECT) | 336 |
                | MySQL JSON SQL (MYSQL_JSON_SQL) | 5 |

                ## 库表对象缺失热点

                ### 缺失表/视图

                | 对象 | 数量 |
                | --- | ---: |
                | ns_attendance_machine_management | 26 |

                ### 缺失字段

                | 字段 | 数量 |
                | --- | ---: |
                | user_name | 70 |
                """;
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> processWithOutput("maven output\n", 1, () -> {
                    try {
                        Files.createDirectories(tempDir.resolve(".dm-adapter"));
                        Files.writeString(tempDir.resolve(".dm-adapter/sql-validation-report.md"), reportContent);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }),
                new RecordingShutdownHookRegistry()
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

        assertThat(result.outputTail())
                .contains(
                        "验证报告摘要:",
                        "- 通过: `790`",
                        "- 失败: `451`",
                        "- 跳过: `234`",
                        "失败分类汇总:",
                        "- 测试库对象问题 (TEST_SCHEMA): 336",
                        "- SQL 语法问题 (SQL_SYNTAX): 45",
                        "失败模式汇总:",
                        "- 测试库缺少对象 (TEST_SCHEMA_OBJECT): 336",
                        "- MySQL JSON SQL (MYSQL_JSON_SQL): 5",
                        "库表对象缺失热点:",
                        "缺失表/视图:",
                        "- ns_attendance_machine_management: 26",
                        "缺失字段:",
                        "- user_name: 70"
                );
    }

    @Test
    void includesLegacyEnglishValidationReportSummaryInRunOutput() throws IOException {
        String reportContent = """
                # Dameng SQL Validation Report

                - Passed: `790`
                - Failed: `451`
                - Skipped: `234`

                ## Failure Categories

                | Category | Count | Hint |
                | --- | ---: | --- |
                | TEST_SCHEMA | 336 | Align schema. |

                ## Failure Patterns

                | Pattern | Count |
                | --- | ---: |
                | MYSQL_JSON_SQL | 5 |

                ## Schema Object Hotspots

                ### Missing Tables/Views

                | Object | Count |
                | --- | ---: |
                | ns_attendance_machine_management | 26 |
                """;
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> processWithOutput("maven output\n", 1, () -> {
                    try {
                        Files.createDirectories(tempDir.resolve(".dm-adapter"));
                        Files.writeString(tempDir.resolve(".dm-adapter/sql-validation-report.md"), reportContent);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }),
                new RecordingShutdownHookRegistry()
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

        assertThat(result.outputTail())
                .contains(
                        "验证报告摘要:",
                        "- 通过: `790`",
                        "- 失败: `451`",
                        "- 跳过: `234`",
                        "失败分类汇总:",
                        "- TEST_SCHEMA: 336",
                        "失败模式汇总:",
                        "- MYSQL_JSON_SQL: 5",
                        "库表对象缺失热点:",
                        "缺失表/视图:",
                        "- ns_attendance_machine_management: 26"
                );
    }

    @Test
    void rotatesStaleValidationReportBeforeRunningMaven() throws IOException {
        Files.createDirectories(tempDir.resolve(".dm-adapter"));
        Path staleReport = tempDir.resolve(".dm-adapter/sql-validation-report.md");
        Files.writeString(staleReport, """
                # Dameng SQL Validation Report

                - Passed: `1`
                - Failed: `2`
                - Skipped: `3`
                """);
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> processWithOutput("pom failure\n", 1),
                new RecordingShutdownHookRegistry()
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

        assertThat(Files.exists(staleReport)).isFalse();
        assertThat(Files.readString(tempDir.resolve(".dm-adapter/sql-validation-report.previous.md")))
                .contains("Passed: `1`")
                .contains("Failed: `2`");
        assertThat(result.reportPath()).isNull();
        assertThat(result.outputTail())
                .noneSatisfy(line -> assertThat(line).contains("Validation report summary"))
                .noneSatisfy(line -> assertThat(line).contains("验证报告摘要"))
                .anySatisfy(line -> assertThat(line).contains("pom failure"));
    }

    @Test
    void appliesConfiguredTotalValidationTimeoutAndPreservesTimeoutStatus() {
        BlockingProcess process = new BlockingProcess();
        ValidationTestRunner runner = new ValidationTestRunner(
                Map.of(),
                "Linux",
                processBuilder -> process,
                new RecordingShutdownHookRegistry()
        );
        DmValidationEnvironment environment = DmValidationEnvironment.from(Map.of(
                "DM_SQL_VALIDATION", "true",
                "DM_JDBC_URL", "jdbc:dm://localhost:5236",
                "DM_DB_USERNAME", "SYSDBA",
                "DM_DB_PASSWORD", "SYSDBA",
                "DM_SQL_VALIDATION_TOTAL_TIMEOUT_SECONDS", "1"
        ));

        ValidationTestRunResult result = runner.runIfConfigured(generationResult(), environment);

        assertThat(environment.totalTimeoutSeconds()).isEqualTo(1L);
        assertThat(result.status()).isEqualTo(com.github.dmadapter.core.StageStatus.TIMEOUT);
        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(process.destroyed).isTrue();
    }

    @Test
    void defaultsTotalValidationTimeoutToTwoHours() {
        assertThat(DmValidationEnvironment.from(Map.of()).totalTimeoutSeconds()).isEqualTo(7_200L);
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
        return generationResult(tempDir.resolve("src/test/java/DmSqlValidationTest.java"));
    }

    private ValidationTestGenerationResult generationResult(Path testPath) {
        return new ValidationTestGenerationResult(
                tempDir,
                tempDir,
                tempDir.resolve(".dm-adapter"),
                tempDir.resolve(".dm-adapter/sql-validation.yml"),
                tempDir.resolve(".dm-adapter/sql-rewrite.yml"),
                testPath,
                List.of(),
                List.of()
        );
    }

    private static Process processWithOutput(String output, int exitCode) {
        return new RecordingProcess(output, exitCode, false);
    }

    private static Process processWithOutput(String output, int exitCode, Runnable beforeExit) {
        return new RecordingProcess(output, exitCode, false, beforeExit);
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

    private static class StreamingProcess extends Process {
        private final PipedInputStream inputStream;
        private final PipedOutputStream outputStream;
        private final String output;
        private final CountDownLatch outputObserved;
        private boolean alive = true;
        private boolean outputObservedBeforeExit;

        private StreamingProcess(String output, CountDownLatch outputObserved) throws IOException {
            this.inputStream = new PipedInputStream();
            this.outputStream = new PipedOutputStream(inputStream);
            this.output = output;
            this.outputObserved = outputObserved;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                outputStream.write(output.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                outputObservedBeforeExit = outputObserved.await(2, TimeUnit.SECONDS);
                outputStream.close();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            alive = false;
            return 0;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
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

    private static class RecordingProcess extends Process {
        private final byte[] bytes;
        private final int exitCode;
        private final boolean interruptOnWait;
        private final Runnable beforeExit;
        private boolean alive = true;
        private int destroyForciblyCount;

        private RecordingProcess(String output, int exitCode, boolean interruptOnWait) {
            this(output, exitCode, interruptOnWait, () -> {
            });
        }

        private RecordingProcess(String output, int exitCode, boolean interruptOnWait, Runnable beforeExit) {
            this.bytes = output.getBytes(StandardCharsets.UTF_8);
            this.exitCode = exitCode;
            this.interruptOnWait = interruptOnWait;
            this.beforeExit = beforeExit == null ? () -> {
            } : beforeExit;
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
            beforeExit.run();
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

    private static class BlockingProcess extends Process {
        private boolean alive = true;
        private boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public synchronized int waitFor() throws InterruptedException {
            while (alive) {
                wait();
            }
            return 143;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return 143;
        }

        @Override
        public synchronized void destroy() {
            destroyForcibly();
        }

        @Override
        public synchronized Process destroyForcibly() {
            destroyed = true;
            alive = false;
            notifyAll();
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
