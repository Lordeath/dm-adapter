package com.github.dmadapter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.report.ReportWriter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BatchCommandTest {
    @TempDir
    Path tempDir;

    private final PersonIdent author = new PersonIdent("test", "test@example.invalid");

    @Test
    void clonesConvertsCommitsAndPushesMultipleRepositoriesUsingOnlyJGit() throws Exception {
        RemoteFixture first = createRemote("first", false);
        RemoteFixture second = createRemote("second", false);
        Path config = writeConfig(first, second);

        int exitCode = execute(config);

        assertThat(exitCode).isZero();
        assertThat(readRemoteFile(first.remote(), "src/main/resources/mapper-dm/UserMapper.xml"))
                .contains("select id from users");
        assertThat(readRemoteFile(second.remote(), "src/main/resources/mapper-dm/UserMapper.xml"))
                .contains("select id from users");
        assertThat(remoteMessage(first.remote())).isEqualTo("Convert MySQL SQL for Dameng");
        JsonNode summary = summaryJson();
        assertThat(summary.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(summary.path("repositoryCount").asInt()).isEqualTo(2);
        assertThat(summary.path("successCount").asInt()).isEqualTo(2);

        String firstHead = remoteHead(first.remote());
        assertThat(execute(config)).isZero();
        assertThat(remoteHead(first.remote())).isEqualTo(firstHead);
        assertThat(summaryJson().path("noChangesCount").asInt()).isEqualTo(2);
    }

    @Test
    void useStatementFailsItsRepositoryButRemainingRepositoriesContinue() throws Exception {
        RemoteFixture manual = createRemote("manual", true);
        RemoteFixture clean = createRemote("clean", false);
        String manualHead = remoteHead(manual.remote());
        Path config = writeConfig(manual, clean);

        int exitCode = execute(config);

        assertThat(exitCode).isEqualTo(BatchExitCodes.MANUAL_REVIEW);
        assertThat(remoteHead(manual.remote())).isEqualTo(manualHead);
        assertThat(remoteHead(clean.remote())).isNotEqualTo(clean.initialHead());
        JsonNode summary = summaryJson();
        assertThat(summary.path("failedCount").asInt()).isEqualTo(1);
        assertThat(summary.path("repositories").get(0).path("failureStage").asText())
                .isEqualTo("source-use-statement");
        assertThat(summary.path("repositories").get(0).path("message").asText())
                .contains("remove it from the MySQL source script");
    }

    @Test
    void batchSilentlySkipsDatabaseValidationForSqlScripts() throws Exception {
        RemoteFixture remote = createRemote("offline-sql", false);
        pushRemoteChange(remote, "sql/v2/20260807_system.sql", """
                DELIMITER $$
                CREATE PROCEDURE batch_insert()
                BEGIN
                    CALL addOrUpdate_dictionary();
                END$$
                DELIMITER ;
                """);

        int exitCode = execute(writeConfig(remote));

        assertThat(exitCode).isZero();
        Path repositoryReportDir = latestReportDir().resolve("offline-sql");
        JsonNode report = new ObjectMapper().readTree(
                repositoryReportDir.resolve("dm-adapter-sql-script-report.json").toFile()
        );
        assertThat(report.path("validationAttempted").asBoolean()).isFalse();
        assertThat(report.path("validationPlan").asText()).isEmpty();
        assertThat(report.path("warnings").toString())
                .doesNotContain("System SQL script has no --system-schema")
                .doesNotContain("外部存储过程依赖");
        assertThat(Files.readString(repositoryReportDir.resolve("dm-adapter-sql-script-report.md")))
                .contains("Batch 模式未请求达梦 SQL 脚本试执行")
                .doesNotContain("外部存储过程依赖");
        assertThat(repositoryReportDir.resolve("sql-script-validation-plan.json")).doesNotExist();
    }

    @Test
    void batchUsesGlobalTableKeyColumnsForSqlScriptInsertSelectUpsert() throws Exception {
        RemoteFixture remote = createRemote("configured-script-upsert", false);
        pushRemoteChange(remote, "sql/v2/20260910.sql", """
                DELIMITER $$
                CREATE PROCEDURE addOrUpdate_button()
                BEGIN
                    INSERT INTO ns_core_resourcebutton (
                        ENTERPRISE_ID,
                        JE_CORE_RESOURCEBUTTON_ID,
                        RESOURCEBUTTON_FUNCINFO_ID,
                        RESOURCEBUTTON_NAME
                    )
                    SELECT enterprise_id, v_button_id, v_func_id, v_button_name
                    FROM tmp_aoub_enterprise
                    ON DUPLICATE KEY UPDATE
                        ID = ns_core_resourcebutton.ID;
                END$$
                DELIMITER ;
                """);
        Path config = writeConfig(remote);
        Files.writeString(config, Files.readString(config).replace(
                "migrationDefaults:\n",
                """
                migrationDefaults:
                  upsertKeys:
                    tables:
                      "ns_core_resourcebutton":
                        keyColumns: [ENTERPRISE_ID, JE_CORE_RESOURCEBUTTON_ID, RESOURCEBUTTON_FUNCINFO_ID]
                """
        ));

        int exitCode = execute(config);

        assertThat(exitCode).isZero();
        assertThat(readRemoteFile(remote.remote(), "sql/v2-dm/20260910.sql"))
                .contains("FOR dm_source IN (")
                .contains("MERGE INTO ns_core_resourcebutton t")
                .contains("ON (t.ENTERPRISE_ID = s.ENTERPRISE_ID "
                        + "AND t.JE_CORE_RESOURCEBUTTON_ID = s.JE_CORE_RESOURCEBUTTON_ID "
                        + "AND t.RESOURCEBUTTON_FUNCINFO_ID = s.RESOURCEBUTTON_FUNCINFO_ID)")
                .doesNotContainIgnoringCase("ON DUPLICATE KEY UPDATE");
        Path reportDir = latestReportDir().resolve("configured-script-upsert");
        assertThat(Files.readString(reportDir.resolve("dm-adapter-sql-script-report.md")))
                .contains("需人工确认 SQL 数：`0`");
        assertThat(Files.readString(reportDir.resolve("sql-rewrite.yml")))
                .contains("\"ns_core_resourcebutton\":")
                .contains("keyColumns: [\"ENTERPRISE_ID\", \"JE_CORE_RESOURCEBUTTON_ID\", "
                        + "\"RESOURCEBUTTON_FUNCINFO_ID\"]");
    }

    @Test
    void batchUsesGlobalTableKeyColumnsForDynamicSchemaInsertIgnore() throws Exception {
        RemoteFixture remote = createRemote("configured-upsert", false);
        pushRemoteChange(remote, "src/main/resources/mapper/UserMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="insertExt">
                        insert ignore into ${schemaName}.charge_customerchargedetail_ext
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="id != null">
                                chargeDetailId,
                            </if>
                            <if test="receivingBusiness != null">
                                receivingBusiness,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="id != null">
                                #{id},
                            </if>
                            <if test="receivingBusiness != null">
                                #{receivingBusiness},
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """);
        Path config = writeConfig(remote);
        Files.writeString(config, Files.readString(config).replace(
                "migrationDefaults:\n",
                """
                migrationDefaults:
                  upsertKeys:
                    tables:
                      "${schemaName}.charge_customerchargedetail_ext":
                        keyColumns: [chargeDetailId]
                """
        ));

        int exitCode = execute(config);

        assertThat(exitCode).isZero();
        assertThat(readRemoteFile(remote.remote(), "src/main/resources/mapper-dm/UserMapper.xml"))
                .contains("MERGE INTO ${schemaName}.charge_customerchargedetail_ext t")
                .contains("ON (t.chargeDetailId = s.chargeDetailId)")
                .doesNotContainIgnoringCase("insert ignore");
        assertThat(Files.readString(latestReportDir().resolve("configured-upsert/sql-rewrite.yml")))
                .contains("\"${schemaname}.charge_customerchargedetail_ext\":")
                .contains("keyColumns: [\"chargeDetailId\"]");
    }

    @Test
    void batchUsesGlobalMethodKeyColumnsForDynamicBatchUpsert() throws Exception {
        RemoteFixture remote = createRemote("configured-method-upsert", false);
        pushRemoteChange(remote, "src/main/resources/mapper/CanalMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.CanalMapper">
                    <insert id="upsertCompressedSnapshot">
                        insert into ${tableName}
                        (
                        <foreach collection="fieldNames" item="field" separator=",">
                            ${field}
                        </foreach>
                        , canal_source
                        )
                        values
                        <foreach collection="rows" item="row" separator=",">
                            (
                            <foreach collection="fieldNames" item="field" separator=",">
                                #{row.${field}}
                            </foreach>
                            , #{canalSource}
                            )
                        </foreach>
                        on duplicate key update
                        canal_source = values(canal_source)
                    </insert>
                </mapper>
                """);
        Path config = writeConfig(remote);
        Files.writeString(config, Files.readString(config).replace(
                "migrationDefaults:\n",
                """
                migrationDefaults:
                  upsertKeys:
                    methods:
                      "com.example.CanalMapper.upsertCompressedSnapshot":
                        keyColumns: [pk]
                """
        ));

        int exitCode = execute(config);

        assertThat(exitCode).isZero();
        assertThat(readRemoteFile(remote.remote(), "src/main/resources/mapper-dm/CanalMapper.xml"))
                .contains("MERGE INTO ${tableName} t")
                .contains("ON (t.pk = s.pk)")
                .doesNotContainIgnoringCase("on duplicate key update");
        assertThat(Files.readString(latestReportDir().resolve("configured-method-upsert/sql-rewrite.yml")))
                .contains("\"com.example.CanalMapper.upsertCompressedSnapshot\":")
                .contains("keyColumns: [\"pk\"]");
    }

    @Test
    void recreatesMarkedCorruptCache() throws Exception {
        RemoteFixture remote = createRemote("recover", false);
        Path config = writeConfig(remote);
        assertThat(execute(config)).isZero();

        Path cache = tempDir.resolve("workspace/repositories/recover");
        Files.writeString(cache.resolve(".git/config"), "not valid config", StandardCharsets.UTF_8);
        assertThat(execute(config)).isZero();
        try (Git ignored = Git.open(cache.toFile())) {
            assertThat(cache.resolve("pom.xml")).isRegularFile();
        }

    }

    @Test
    void refusesToModifyAnUnmarkedCacheDirectory() throws Exception {
        RemoteFixture remote = createRemote("unmarked", false);
        Path unmarked = tempDir.resolve("workspace/repositories/unmarked");
        Files.createDirectories(unmarked.getParent());
        try (Git ignored = Git.cloneRepository()
                .setURI(remote.remote().toUri().toString())
                .setBranch("main")
                .setDirectory(unmarked.toFile())
                .call()) {
            // This valid Git checkout is deliberately not owned by dm-adapter.
        }
        Files.writeString(unmarked.resolve("keep.txt"), "keep");

        int exitCode = execute(writeConfig(remote));

        assertThat(exitCode).isEqualTo(BatchExitCodes.GIT_ERROR);
        assertThat(Files.readString(unmarked.resolve("keep.txt"))).isEqualTo("keep");
        assertThat(summaryJson().path("repositories").get(0).path("failureStage").asText())
                .isEqualTo("cache-safety");
    }

    @Test
    void retriesFromLatestRemoteHeadWhenBranchMovesDuringConversion() throws Exception {
        RemoteFixture remote = createRemote("racing", false);
        Path configPath = writeConfig(remote);
        ResolvedBatchConfig config = new BatchConfigLoader().load(configPath);
        AtomicBoolean advanced = new AtomicBoolean();
        JGitBatchRepositoryRunner runner = new JGitBatchRepositoryRunner(
                config.workspaceDir(),
                config.credentials(),
                config.gitIdentity(),
                new ReportWriter(),
                (repository, attempt) -> {
                    if (advanced.compareAndSet(false, true)) {
                        pushRemoteChange(remote, "concurrent.txt", "remote update");
                    }
                }
        );

        BatchRepositoryExecution execution = runner.run(
                config.repositories().get(0),
                tempDir.resolve("race-report")
        );

        assertThat(execution.exitCode()).isZero();
        assertThat(execution.report().attempts()).isEqualTo(2);
        assertThat(readRemoteFile(remote.remote(), "concurrent.txt")).isEqualTo("remote update");
        assertThat(readRemoteFile(remote.remote(), "src/main/resources/mapper-dm/UserMapper.xml"))
                .contains("select id from users");
    }

    @Test
    void retentionDeletesOnlyOldMarkedRunDirectories() throws Exception {
        Path reports = tempDir.resolve("retention");
        Path oldMarked = reports.resolve("old-marked");
        Path oldUnmarked = reports.resolve("old-unmarked");
        BatchReportRetention retention = new BatchReportRetention();
        retention.mark(oldMarked);
        Files.createDirectories(oldUnmarked);
        Files.writeString(oldUnmarked.resolve("keep.txt"), "keep");
        Files.setLastModifiedTime(
                oldMarked.resolve(BatchReportRetention.MARKER_FILE),
                java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(40L * 86_400L))
        );

        retention.clean(reports, 30);

        assertThat(oldMarked).doesNotExist();
        assertThat(oldUnmarked.resolve("keep.txt")).isRegularFile();
    }

    private int execute(Path config) {
        return new CommandLine(new DmAdapterCli()).execute("batch", "--config", config.toString());
    }

    private Path writeConfig(RemoteFixture... repositories) throws Exception {
        Path config = tempDir.resolve("batch.yml");
        StringBuilder yaml = new StringBuilder("""
                schemaVersion: 1
                workspaceDir: workspace
                reportDir: reports
                reportRetentionDays: 30
                git:
                  authorName: Batch Bot
                  authorEmail: batch@example.com
                  commitMessage: Convert MySQL SQL for Dameng
                migrationDefaults:
                  sql:
                    mode: IF_PRESENT
                    sourceDir: sql/v2
                    outputDir: sql/v2-dm
                repositories:
                """);
        for (RemoteFixture repository : repositories) {
            yaml.append("  - name: ").append(repository.name()).append("\n")
                    .append("    url: \"").append(repository.remote().toUri()).append("\"\n")
                    .append("    branch: main\n")
                    .append("    projectSubdir: .\n");
        }
        Files.writeString(config, yaml.toString());
        return config;
    }

    private RemoteFixture createRemote(String name, boolean includeUse) throws Exception {
        Path remote = tempDir.resolve(name + ".git");
        Path seed = tempDir.resolve(name + "-seed");
        try (Git ignored = Git.init().setBare(true).setInitialBranch("main").setDirectory(remote.toFile()).call()) {
            // Initialized for the test.
        }
        String initialHead;
        try (Git git = Git.init().setInitialBranch("main").setDirectory(seed.toFile()).call()) {
            writeProject(seed, includeUse);
            git.add().addFilepattern(".").setAll(true).call();
            initialHead = git.commit().setMessage("Initial project").setAuthor(author).setCommitter(author)
                    .call().getId().name();
            git.remoteAdd().setName("origin").setUri(new URIish(remote.toUri().toString())).call();
            git.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/main:refs/heads/main")).call();
        }
        return new RemoteFixture(name, remote, initialHead);
    }

    private void pushRemoteChange(RemoteFixture remote, String path, String content) throws Exception {
        Path clone = tempDir.resolve(remote.name() + "-concurrent");
        try (Git git = Git.cloneRepository()
                .setURI(remote.remote().toUri().toString())
                .setBranch("main")
                .setDirectory(clone.toFile())
                .call()) {
            Path target = clone.resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Concurrent update").setAuthor(author).setCommitter(author).call();
            git.push().setRemote("origin").setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
        }
    }

    private void writeProject(Path root, boolean includeUse) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.2</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>batch-demo</artifactId>
                    <version>1.0.0</version>
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
                    <select id="findAll">select id from users</select>
                </mapper>
                """);
        if (includeUse) {
            Path sql = root.resolve("sql/v2/20260807.sql");
            Files.createDirectories(sql.getParent());
            Files.writeString(sql, "USE sample_app;\nselect 1;\n");
        }
    }

    private JsonNode summaryJson() throws Exception {
        return new ObjectMapper().readTree(
                latestReportDir().resolve(ReportWriter.BATCH_RUN_REPORT_JSON).toFile()
        );
    }

    private Path latestReportDir() throws Exception {
        Path reports = tempDir.resolve("reports");
        try (var directories = Files.list(reports)) {
            return directories.filter(Files::isDirectory)
                    .max(Comparator.comparing(Path::getFileName))
                    .orElseThrow();
        }
    }

    private String remoteHead(Path remote) throws Exception {
        try (Repository repository = bareRepository(remote)) {
            return repository.resolve("refs/heads/main").name();
        }
    }

    private String remoteMessage(Path remote) throws Exception {
        try (Repository repository = bareRepository(remote);
             RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(repository.resolve("refs/heads/main")).getFullMessage();
        }
    }

    private String readRemoteFile(Path remote, String path) throws Exception {
        try (Repository repository = bareRepository(remote);
             RevWalk walk = new RevWalk(repository)) {
            var commit = walk.parseCommit(repository.resolve("refs/heads/main"));
            try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
                assertThat(tree).as("remote path %s", path).isNotNull();
                return new String(repository.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private Repository bareRepository(Path remote) throws Exception {
        return new FileRepositoryBuilder().setGitDir(remote.toFile()).setBare().build();
    }

    private record RemoteFixture(String name, Path remote, String initialHead) {
    }
}
