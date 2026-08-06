package com.github.dmadapter.cli;

import com.github.dmadapter.report.ReportWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitBatchRunnerTest {
    @TempDir
    Path tempDir;

    private Path remote;
    private Path repository;
    private Path reports;

    @BeforeEach
    void setUpRepository() throws Exception {
        remote = tempDir.resolve("remote.git");
        repository = tempDir.resolve("source");
        reports = tempDir.resolve("reports");
        git(tempDir, "init", "--bare", "--initial-branch=main", remote.toString());
        git(tempDir, "init", "--initial-branch=main", repository.toString());
        git(repository, "config", "user.name", "dm-adapter test");
        git(repository, "config", "user.email", "dm-adapter@example.invalid");
        writeCleanProject(repository);
        git(repository, "add", "--all");
        git(repository, "commit", "--message", "初始化测试项目");
        git(repository, "remote", "add", "origin", remote.toString());
        git(repository, "push", "--set-upstream", "origin", "main");
    }

    @Test
    void migratesLatestRemoteInWorktreeAndPushesExactCommitMessage() throws Exception {
        String originalHead = git(repository, "rev-parse", "HEAD");

        Path relativeProject = Path.of("").toAbsolutePath().normalize().relativize(repository);
        int firstExitCode = executeBatch(
                new GitCommandRunner(),
                "自动转换新增 MySQL 语句",
                relativeProject
        );

        assertThat(firstExitCode).isZero();
        assertThat(gitBare("log", "-1", "--pretty=%s", "main"))
                .isEqualTo("自动转换新增 MySQL 语句");
        assertThat(gitBare("show", "main:pom.xml")).contains("DmJdbcDriver18");
        assertThat(gitBare("show", "main:src/main/resources/mapper-dm/UserMapper.xml"))
                .contains("select id from users");
        assertThat(git(repository, "rev-parse", "HEAD")).isEqualTo(originalHead);
        assertThat(git(repository, "status", "--porcelain")).isBlank();
        assertThat(repository.resolve("src/main/resources/mapper-dm/UserMapper.xml")).doesNotExist();
        assertThat(Files.readString(reports.resolve(ReportWriter.BATCH_REPORT_JSON)))
                .contains("\"status\" : \"SUCCESS\"")
                .contains("\"attempts\" : 1");

        String pushedHead = gitBare("rev-parse", "main");
        int secondExitCode = executeBatchUsingCurrentUpstream("自动转换新增 MySQL 语句");

        assertThat(secondExitCode).isZero();
        assertThat(gitBare("rev-parse", "main")).isEqualTo(pushedHead);
        assertThat(Files.readString(reports.resolve(ReportWriter.BATCH_REPORT_JSON)))
                .contains("\"status\" : \"NO_CHANGES\"");
    }

    @Test
    void retriesFromNewRemoteHeadWhenBranchMovesDuringConversion() throws Exception {
        Path competitor = tempDir.resolve("competitor");
        git(tempDir, "clone", "--branch", "main", remote.toString(), competitor.toString());
        git(competitor, "config", "user.name", "remote writer");
        git(competitor, "config", "user.email", "remote-writer@example.invalid");
        GitCommandRunner movingRemoteRunner = new GitCommandRunner() {
            private int fetchCount;

            @Override
            GitResult run(Path directory, Duration timeout, List<String> arguments)
                    throws IOException, InterruptedException {
                if (!arguments.isEmpty() && "fetch".equals(arguments.get(0)) && ++fetchCount == 2) {
                    try {
                        Files.writeString(competitor.resolve("REMOTE_CHANGE.md"), "remote moved\n");
                        git(competitor, "add", "REMOTE_CHANGE.md");
                        git(competitor, "commit", "--message", "并发远端变更");
                        git(competitor, "push", "origin", "main");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    } catch (Exception e) {
                        throw new IOException("Could not simulate a concurrent remote push.", e);
                    }
                }
                return super.run(directory, timeout, arguments);
            }
        };

        int exitCode = executeBatch(movingRemoteRunner, "自动转换并重试");

        assertThat(exitCode).isZero();
        assertThat(gitBare("log", "-1", "--pretty=%s", "main")).isEqualTo("自动转换并重试");
        assertThat(gitBare("show", "main:REMOTE_CHANGE.md")).contains("remote moved");
        assertThat(Files.readString(reports.resolve(ReportWriter.BATCH_REPORT_JSON)))
                .contains("\"status\" : \"SUCCESS\"")
                .contains("\"attempts\" : 2");
    }

    @Test
    void refusesToCommitWhenMapperReportContainsManualReview() throws Exception {
        Files.writeString(repository.resolve("src/main/resources/mapper/UserMapper.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="saveProfile">
                        insert into user_profile (user_id, profile)
                        values (#{userId}, #{profile})
                        on duplicate key update profile = values(profile)
                    </insert>
                </mapper>
                """);
        git(repository, "add", "--all");
        git(repository, "commit", "--message", "新增需人工确认 SQL");
        git(repository, "push", "origin", "main");
        String before = gitBare("rev-parse", "main");

        int exitCode = executeBatch(new GitCommandRunner(), "不应提交");

        assertThat(exitCode).isEqualTo(GitBatchRunner.EXIT_MANUAL_REVIEW);
        assertThat(gitBare("rev-parse", "main")).isEqualTo(before);
        assertThat(Files.readString(reports.resolve(ReportWriter.BATCH_REPORT_JSON)))
                .contains("\"status\" : \"FAILED\"")
                .contains("\"failureStage\" : \"manual-review\"");
    }

    @Test
    void rejectsIncompatibleOptionsBeforeStartingGitWork() throws Exception {
        MigrateCommand command = parseCommand(
                "--batch",
                "--project", repository.toString(),
                "--dry-run",
                "--git-commit-message", "不会使用",
                "--git-remote", "origin",
                "--git-branch", "main",
                "--report-dir", reports.toString()
        );

        int exitCode = new GitBatchRunner(new GitCommandRunner(), new ReportWriter()).run(command);

        assertThat(exitCode).isEqualTo(GitBatchRunner.EXIT_ARGUMENT_ERROR);
        assertThat(gitBare("log", "-1", "--pretty=%s", "main")).isEqualTo("初始化测试项目");
    }

    @Test
    void requiresExplicitLengthSemanticsForBatchSqlScripts() {
        MigrateCommand command = parseCommand(
                "--batch",
                "--project", repository.toString(),
                "--git-commit-message", "不会使用",
                "--git-remote", "origin",
                "--git-branch", "main",
                "--sql-root", "sql/v2",
                "--sql-root-out", "sql/v2-dm",
                "--report-dir", reports.toString()
        );

        int exitCode = new GitBatchRunner(new GitCommandRunner(), new ReportWriter()).run(command);

        assertThat(exitCode).isEqualTo(GitBatchRunner.EXIT_ARGUMENT_ERROR);
    }

    @Test
    void convertsSqlScriptsOfflineWithoutDatabaseValidation() throws Exception {
        Path sql = repository.resolve("sql/v2/20260806_system.sql");
        Files.createDirectories(sql.getParent());
        Files.writeString(sql, "select \"ACTIVE\" from dual;\n");
        git(repository, "add", "--all");
        git(repository, "commit", "--message", "新增 MySQL 升级脚本");
        git(repository, "push", "origin", "main");
        MigrateCommand command = parseCommand(
                "--batch",
                "--project", repository.toString(),
                "--git-commit-message", "转换升级脚本",
                "--git-remote", "origin",
                "--git-branch", "main",
                "--sql-root", "sql/v2",
                "--sql-root-out", "sql/v2-dm",
                "--schema", "sample-system",
                "--system-schema", "sample-system",
                "--target-length-semantics", "CHAR",
                "--report-dir", reports.toString()
        );

        int exitCode = new GitBatchRunner(new GitCommandRunner(), new ReportWriter()).run(command);

        assertThat(exitCode).isZero();
        assertThat(gitBare("show", "main:sql/v2-dm/20260806_system.sql"))
                .contains("select 'ACTIVE' from dual;");
        assertThat(Files.readString(reports.resolve(ReportWriter.SQL_SCRIPT_REPORT_JSON)))
                .contains("\"validationAttempted\" : false")
                .contains("\"validationFailureCount\" : 0");
    }

    @Test
    void skipsSuccessfullyWhenAnotherBatchHoldsTheProjectLock() throws Exception {
        Path lockDirectory = repository.resolve(".git/dm-adapter-batch-locks");
        Files.createDirectories(lockDirectory);
        Path lockPath = lockDirectory.resolve(GitBatchRunner.batchLockId("origin", "main", "") + ".lock");

        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            int exitCode = executeBatch(new GitCommandRunner(), "不会执行");

            assertThat(exitCode).isZero();
        }
        assertThat(gitBare("log", "-1", "--pretty=%s", "main")).isEqualTo("初始化测试项目");
        assertThat(Files.readString(reports.resolve(ReportWriter.BATCH_REPORT_JSON)))
                .contains("\"status\" : \"SKIPPED_LOCKED\"");
    }

    private int executeBatch(GitCommandRunner runner, String commitMessage) {
        return executeBatch(runner, commitMessage, repository);
    }

    private int executeBatch(GitCommandRunner runner, String commitMessage, Path projectPath) {
        MigrateCommand command = parseCommand(
                "--batch",
                "--project", projectPath.toString(),
                "--git-commit-message", commitMessage,
                "--git-remote", "origin",
                "--git-branch", "main",
                "--report-dir", reports.toString()
        );
        return new GitBatchRunner(runner, new ReportWriter()).run(command);
    }

    private int executeBatchUsingCurrentUpstream(String commitMessage) {
        MigrateCommand command = parseCommand(
                "--batch",
                "--project", repository.toString(),
                "--git-commit-message", commitMessage,
                "--report-dir", reports.toString()
        );
        return new GitBatchRunner(new GitCommandRunner(), new ReportWriter()).run(command);
    }

    private MigrateCommand parseCommand(String... args) {
        MigrateCommand command = new MigrateCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }

    private void writeCleanProject(Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.2</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>batch-demo</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.mybatis.spring.boot</groupId>
                            <artifactId>mybatis-spring-boot-starter</artifactId>
                            <version>3.0.3</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Path mapper = root.resolve("src/main/resources/mapper/UserMapper.xml");
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUsers">select id from users</select>
                </mapper>
                """);
    }

    private String gitBare(String... arguments) throws Exception {
        String[] actual = new String[arguments.length + 2];
        actual[0] = "--git-dir";
        actual[1] = remote.toString();
        System.arraycopy(arguments, 0, actual, 2, arguments.length);
        return git(tempDir, actual);
    }

    private String git(Path directory, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new AssertionError("Git test command timed out: " + command);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            throw new AssertionError("Git test command failed: " + command + "\n" + output);
        }
        return output;
    }
}
