package com.github.dmadapter.cli;

import com.github.dmadapter.core.DamengTargetCapabilities;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.TargetLengthSemantics;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class SqlScriptMigratorTest {
    @TempDir
    Path tempDir;

    @Test
    void migratesSqlRootToOutputRootAndRoutesSystemScriptsToSystemSchema() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260423.sql"), """
                DELIMITER $$
                CREATE DEFINER=`root`@`%` PROCEDURE bill_proc()
                BEGIN
                    select "ACTIVE" from dual;
                END$$
                DELIMITER ;
                """);
        write(sqlRoot.resolve("nested/20260423_system.sql"), """
                select "SYSTEM" from dual;
                """);
        RecordingValidator validator = new RecordingValidator();

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.scannedFileCount()).isEqualTo(2);
        assertThat(report.convertedFileCount()).isEqualTo(2);
        assertThat(report.validationSuccessCount()).isEqualTo(2);
        assertThat(report.validationFailureCount()).isZero();
        assertThat(Files.readString(sqlRoot.resolve("20260423.sql")))
                .contains("DELIMITER $$")
                .contains("DEFINER=`root`@`%`");
        assertThat(Files.readString(sqlRootOut.resolve("20260423.sql")))
                .doesNotContain("DELIMITER")
                .doesNotContain("DEFINER")
                .contains("CREATE OR REPLACE PROCEDURE bill_proc() AS")
                .contains("select 'ACTIVE' from dual;")
                .contains("END;\n/");
        assertThat(Files.readString(sqlRootOut.resolve("nested/20260423_system.sql")))
                .contains("select 'SYSTEM' from dual;");
        assertThat(validator.files)
                .extracting(SqlScriptMigrator.PlannedSqlScriptFile::schema)
                .containsExactly("sample-bill", "sample-system");
        assertThat(validator.files)
                .filteredOn(SqlScriptMigrator.PlannedSqlScriptFile::systemScript)
                .singleElement()
                .satisfies(file -> assertThat(file.outputDisplay().replace('\\', '/'))
                        .endsWith("nested/20260423_system.sql"));
    }

    @Test
    void convertsScriptLeftJoinUpdateWhenProjectDdlProvesSourceKeyUnique() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        Path projectRoot = tempDir.resolve("java/sample-app");
        Files.createDirectories(projectRoot);
        write(tempDir.resolve("sql/00_Create.sql"), """
                CREATE TABLE sample_header (
                    id BIGINT NOT NULL,
                    document_type VARCHAR(40),
                    PRIMARY KEY (id)
                );
                CREATE TABLE sample_detail (
                    id BIGINT NOT NULL,
                    header_id BIGINT,
                    document_type VARCHAR(40),
                    PRIMARY KEY (id)
                );
                """);
        write(sqlRoot.resolve("20260729.sql"), """
                UPDATE sample_detail detail
                LEFT JOIN sample_header header ON detail.header_id = header.id
                SET detail.document_type = header.document_type;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        projectRoot,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        DmValidationEnvironment.from(Map.of())
                )
        );

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("20260729.sql")))
                .contains("update sample_detail detail set document_type = "
                        + "(SELECT header.document_type FROM sample_header header "
                        + "WHERE detail.header_id = header.id)")
                .doesNotContain("LEFT JOIN");
        assertThat(report.files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules())
                        .contains(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE));
    }

    @Test
    void validatesOutputOnlySqlFilesFromOutputRootInRelativePathOrder() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260423.sql"), "select 1 from dual;\n");
        write(sqlRootOut.resolve("00000000.sql"), "select 0 from dual;\n");
        write(sqlRootOut.resolve("nested/00000000_system.sql"), "select 2 from dual;\n");
        RecordingValidator validator = new RecordingValidator();

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.scannedFileCount()).isEqualTo(1);
        assertThat(report.files()).hasSize(3);
        assertThat(report.validationSuccessCount()).isEqualTo(3);
        assertThat(validator.files)
                .extracting(file -> Path.of(file.outputDisplay()).getFileName().toString())
                .containsExactly("00000000.sql", "20260423.sql", "00000000_system.sql");
        assertThat(report.files())
                .filteredOn(file -> file.sourceFile().contains("(output-only)"))
                .hasSize(2)
                .allSatisfy(file -> {
                    assertThat(file.written()).isFalse();
                    assertThat(file.converted()).isFalse();
                });
        assertThat(validator.files)
                .filteredOn(file -> Path.of(file.outputDisplay()).getFileName().toString().equals("00000000.sql"))
                .singleElement()
                .satisfies(file -> assertThat(file.schema()).isEqualTo("sample-bill"));
        assertThat(validator.files)
                .filteredOn(SqlScriptMigrator.PlannedSqlScriptFile::systemScript)
                .singleElement()
                .satisfies(file -> assertThat(file.schema()).isEqualTo("sample-system"));
    }

    @Test
    void parserKeepsSlashTerminatedDamengProcedureAsSingleStatement() {
        String content = """
                CREATE OR REPLACE PROCEDURE demo_proc() AS
                BEGIN
                    EXECUTE IMMEDIATE 'select 1';
                END;
                /
                CALL demo_proc();
                DROP PROCEDURE IF EXISTS demo_proc;
                /
                """;

        List<String> statements = SqlScriptParser.statements(content);

        assertThat(statements).hasSize(3);
        assertThat(statements.get(0))
                .contains("CREATE OR REPLACE PROCEDURE demo_proc() AS")
                .contains("EXECUTE IMMEDIATE 'select 1'")
                .contains("END");
        assertThat(statements.get(1)).isEqualTo("CALL demo_proc()");
        assertThat(statements.get(2)).isEqualTo("DROP PROCEDURE IF EXISTS demo_proc");
        assertThat(statements).doesNotContain("/");
    }

    @Test
    void parserKeepsSlashTerminatedAnonymousBlockAsSingleStatement() {
        String content = """
                DECLARE
                    v CLOB;
                BEGIN
                    v := TO_CLOB('hello');
                    CALL demo_proc(v);
                END;
                /
                select 1 from dual;
                """;

        List<String> statements = SqlScriptParser.statements(content);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0))
                .contains("DECLARE")
                .contains("CALL demo_proc(v)")
                .contains("END");
        assertThat(statements.get(1)).isEqualTo("select 1 from dual");
    }

    @Test
    void dryRunDoesNotWriteOutputOrRunValidation() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260423.sql"), "select \"ACTIVE\" from dual;\n");
        FailingValidator validator = new FailingValidator();

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                true,
                "sample-bill",
                "sample-system",
                DmValidationEnvironment.from(Map.of(
                        "DM_SQL_VALIDATION", "true",
                        "DM_JDBC_URL", "jdbc:dm://example:5236",
                        "DM_DB_USERNAME", "SYSDBA",
                        "DM_DB_PASSWORD", "secret"
                ))
        ));

        assertThat(report.dryRun()).isTrue();
        assertThat(report.convertedFileCount()).isEqualTo(1);
        assertThat(report.validationAttempted()).isFalse();
        assertThat(report.validationStatus()).contains("Dry run");
        assertThat(Files.exists(sqlRootOut)).isFalse();
    }

    @Test
    void skipsValidationWhenEnvironmentVariablesAreMissing() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260423.sql"), "select 1 from dual;\n");

        SqlScriptMigrationReport report = new SqlScriptMigrator(
                new MySqlToDmSqlConverter(),
                new SqlScriptValidator()
        ).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "sample-system",
                DmValidationEnvironment.from(Map.of("DM_SQL_VALIDATION", "true"))
        ));

        assertThat(Files.exists(sqlRootOut.resolve("20260423.sql"))).isTrue();
        assertThat(report.validationAttempted()).isFalse();
        assertThat(report.validationFailureCount()).isZero();
        assertThat(report.validationStatus())
                .contains("DM_JDBC_URL")
                .contains("DM_DB_USERNAME")
                .contains("DM_DB_PASSWORD");
    }

    @Test
    void inlinesLiteralScriptVariablesAndIgnoresMysqlForeignKeyChecks() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205.sql"), """
                # 默认创建人和企业
                set @enterpriseID=107;
                set @adminUserName='超级管理员';
                insert into demo_config(enterprise_id, user_name)
                values (@enterpriseID, @adminUserName);
                SET FOREIGN_KEY_CHECKS = 1;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("20260205.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("-- DM_ADAPTER: MySQL script variable @enterpriseID was inlined as 107")
                .contains("-- DM_ADAPTER: MySQL script variable @adminUserName was inlined as '超级管理员'")
                .contains("values (107, '超级管理员')")
                .contains("-- DM_ADAPTER: ignored MySQL FOREIGN_KEY_CHECKS = 1")
                .doesNotContain("values (@")
                .doesNotContain("SET FOREIGN_KEY_CHECKS");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.MYSQL_SCRIPT_USER_VARIABLE_LITERAL_RULE,
                                SqlScriptMigrator.MYSQL_FOREIGN_KEY_CHECKS_NOOP_RULE
                        ));
    }

    @Test
    void ignoresMysqlUseWithoutEmbeddingSourceOrConfiguredSchema() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205.sql"), "USE tenant_alpha;");
        write(sqlRoot.resolve("20260205_system.sql"), "USE `tenant-beta`;");

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "tenant_alpha",
                "tenant-beta",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(List.of(
                Files.readString(sqlRootOut.resolve("20260205.sql")),
                Files.readString(sqlRootOut.resolve("20260205_system.sql"))
        )).allSatisfy(converted -> assertThat(converted)
                .contains("-- DM_ADAPTER: ignored MySQL USE; target schema is selected externally")
                .doesNotContain("tenant_alpha", "tenant-beta")
                .doesNotContainPattern("(?im)^\\s*(?:USE|SET\\s+SCHEMA)\\b"));
        assertThat(report.files())
                .allSatisfy(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_USE_SCHEMA_TO_DM_RULE));
    }

    @Test
    void retainsMysqlUseForManualMappingWhenTargetSchemaDiffers() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205.sql"), "USE source_alpha;");

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "target_beta",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(Files.readString(sqlRootOut.resolve("20260205.sql")))
                .contains("USE source_alpha")
                .doesNotContain("target_beta");
        assertThat(report.manualReviewSqlCount()).isOne();
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("does not match the configured target schema"));
    }

    @Test
    void ignoresMysqlSetNamesBecauseJdbcControlsClientEncoding() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205.sql"), """
                SET NAMES utf8mb4;
                SET NAMES 'utf8' COLLATE 'utf8_general_ci';
                SELECT 1;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-schema",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("20260205.sql"));
        assertThat(converted)
                .contains("-- DM_ADAPTER: ignored MySQL SET NAMES utf8mb4")
                .contains("-- DM_ADAPTER: ignored MySQL SET NAMES 'utf8'")
                .contains("BEGIN\n    NULL;\nEND;\n/")
                .contains("SELECT 1;")
                .doesNotContainPattern("(?im)^\\s*SET\\s+NAMES\\b");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SET_NAMES_NOOP_RULE));
    }

    @Test
    void removesMysqlNumericAttributesBeforeWrappingProcedureAlterDdl() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260423.sql"), """
                DELIMITER $$
                CREATE PROCEDURE add_direction()
                BEGIN
                    IF 1 = 1 THEN
                        ALTER TABLE ns_ipaas_interface
                            ADD COLUMN direction tinyint(1) unsigned zerofill DEFAULT '0';
                    END IF;
                END$$
                DELIMITER ;
                """);

        migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-center-pay",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(Files.readString(sqlRootOut.resolve("20260423.sql")))
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE ns_ipaas_interface")
                .contains("ADD direction tinyint DEFAULT ''0'''")
                .doesNotContainIgnoringCase("unsigned")
                .doesNotContainIgnoringCase("zerofill");
    }

    @Test
    void removesMysqlRoutineCharacteristicsFromProcedureHeader() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE alter_city_user()
                DETERMINISTIC
                READS SQL DATA
                SQL SECURITY DEFINER
                BEGIN
                    SELECT 1;
                END$$
                DELIMITER ;
                """);

        migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-schema",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("CREATE OR REPLACE PROCEDURE alter_city_user() AS")
                .doesNotContain("DETERMINISTIC")
                .doesNotContain("READS SQL DATA")
                .doesNotContain("SQL SECURITY DEFINER");
    }

    @Test
    void writesExecutableNoOpsForConsumedMysqlStatementsInStrictValidationPlan() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        Path validationPlan = tempDir.resolve(".dm-adapter/sql-script-validation-plan.json");
        write(sqlRoot.resolve("20260604.sql"), """
                SET @db_name = (SELECT database());
                SELECT 1;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-association",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.offline(TargetLengthSemantics.CHAR),
                        validationPlan
                )
        );

        String converted = Files.readString(sqlRootOut.resolve("20260604.sql"));
        assertThat(report.validationPlan()).isEqualTo(validationPlan.toAbsolutePath().normalize().toString());
        assertThat(converted)
                .contains("-- DM_ADAPTER: MySQL script variable @db_name uses "
                        + "SYS_CONTEXT('USERENV','CURRENT_SCHEMA') in converted metadata checks")
                .contains("BEGIN\n    NULL;\nEND;\n/")
                .contains("SELECT 1;");
        assertThat(SqlScriptParser.statements(converted)).hasSize(2);
    }

    @Test
    void convertsScriptDynamicColumnDdlToDamengAnonymousBlock() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260521.sql"), """
                set @db_name = database();

                set @ddl = (
                    select if(count(*) = 0,
                        'alter table sample_canal_config_item add column `targetDatabase` varchar(128) null comment ''跨库写入目标逻辑库，例如 DataCenter'' after `targetTableName`',
                        'do 0')
                    from information_schema.columns
                    where table_schema = @db_name
                      and table_name = 'sample_canal_config_item'
                      and column_name = 'targetDatabase'
                );
                prepare stmt from @ddl;
                execute stmt;
                deallocate prepare stmt;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-report",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("20260521.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("FROM ALL_TAB_COLUMNS")
                .contains("WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')")
                .contains("AND TABLE_NAME = 'sample_canal_config_item'")
                .contains("AND COLUMN_NAME = 'targetDatabase'")
                .contains("EXECUTE IMMEDIATE 'alter table sample_canal_config_item ADD `targetDatabase` varchar(128) null'")
                .contains("-- DM_ADAPTER: MySQL PREPARE stmt is handled by the previous EXECUTE IMMEDIATE block")
                .contains("-- DM_ADAPTER: MySQL EXECUTE stmt is handled by the previous EXECUTE IMMEDIATE block")
                .contains("-- DM_ADAPTER: MySQL DEALLOCATE PREPARE stmt is unnecessary after the previous EXECUTE IMMEDIATE block")
                .doesNotContain("set @ddl")
                .doesNotContain("prepare stmt")
                .doesNotContain("execute stmt")
                .doesNotContain("deallocate prepare stmt")
                .doesNotContain("comment ''跨库写入目标逻辑库")
                .doesNotContain(" after `targetTableName`");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SCRIPT_DYNAMIC_DDL_TO_EXECUTE_IMMEDIATE_RULE));
    }

    @Test
    void convertsMysqlScriptIndexExistencePrepareSequenceToDamengBlock() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260522.sql"), """
                SET @idx_mail_user_count_exists = (
                    SELECT COUNT(*)
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'sample_mail_user'
                      AND INDEX_NAME = 'idx_mail_user_count'
                );
                SET @idx_mail_user_count_sql = IF(
                    @idx_mail_user_count_exists = 0,
                    'ALTER TABLE `sample_mail_user` ADD INDEX `idx_mail_user_count` (`user_id`, `delete_flag`, `is_read`, `mail_id`)',
                    'SELECT 1'
                );
                PREPARE idx_mail_user_count_stmt FROM @idx_mail_user_count_sql;
                EXECUTE idx_mail_user_count_stmt;
                DEALLOCATE PREPARE idx_mail_user_count_stmt;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-office",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("20260522.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("FROM ALL_INDEXES")
                .contains("OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')")
                .contains("TABLE_NAME = UPPER('sample_mail_user')")
                .contains("INDEX_NAME = UPPER('idx_mail_user_count')")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX sample_mail_user_idx_mail_user_count"
                        + " ON `sample_mail_user` (`user_id`, `delete_flag`, `is_read`, `mail_id`)'")
                .contains("MySQL PREPARE idx_mail_user_count_stmt is handled")
                .contains("MySQL EXECUTE idx_mail_user_count_stmt is handled")
                .contains("MySQL DEALLOCATE PREPARE idx_mail_user_count_stmt is unnecessary")
                .doesNotContain("SET @idx_mail_user_count_exists")
                .doesNotContain("SET @idx_mail_user_count_sql")
                .doesNotContain("ALTER TABLE `sample_mail_user` ADD INDEX")
                .doesNotContain("PREPARE idx_mail_user_count_stmt FROM")
                .doesNotContain("EXECUTE idx_mail_user_count_stmt;");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SCRIPT_DYNAMIC_DDL_TO_EXECUTE_IMMEDIATE_RULE));
    }

    @Test
    void convertsCombinedReusableScriptIndexDdlAssignmentsForAddAndDrop() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260522.sql"), """
                SET @ddl := (
                    SELECT IF(
                        NOT EXISTS (
                            SELECT 1
                            FROM information_schema.STATISTICS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = 'tenant_alpha_event'
                              AND INDEX_NAME = 'idx_event_state'
                        ),
                        'ALTER TABLE `tenant_alpha_event` ADD INDEX `idx_event_state` (`state`, `event_id`)',
                        'SELECT 1'
                    )
                );
                PREPARE stmt FROM @ddl;
                EXECUTE stmt;
                DEALLOCATE PREPARE stmt;

                SET @ddl := (
                    SELECT IF(
                        EXISTS (
                            SELECT 1
                            FROM information_schema.STATISTICS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = 'tenant_beta_event'
                              AND INDEX_NAME = 'idx_obsolete'
                        ),
                        'ALTER TABLE `tenant_beta_event` DROP INDEX `idx_obsolete`',
                        'SELECT 1'
                    )
                );
                PREPARE stmt FROM @ddl;
                EXECUTE stmt;
                DEALLOCATE PREPARE stmt;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-office",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("20260522.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("TABLE_NAME = UPPER('tenant_alpha_event')")
                .contains("INDEX_NAME = UPPER('idx_event_state')")
                .contains("IF dm_existing_count = 0 THEN")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX tenant_alpha_event_idx_event_state"
                        + " ON `tenant_alpha_event` (`state`, `event_id`)'")
                .contains("TABLE_NAME = UPPER('tenant_beta_event')")
                .contains("INDEX_NAME = UPPER('idx_obsolete')")
                .contains("IF dm_existing_count > 0 THEN")
                .contains("EXECUTE IMMEDIATE 'DROP INDEX tenant_beta_event_idx_obsolete'")
                .doesNotContain("SET @ddl")
                .doesNotContain("PREPARE stmt FROM @ddl")
                .doesNotContain("EXECUTE stmt;");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SCRIPT_DYNAMIC_DDL_TO_EXECUTE_IMMEDIATE_RULE));
    }

    @Test
    void doesNotExecuteCombinedScriptIndexDdlWhenPrepareSequenceIsIncomplete() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                SET @ddl := (
                    SELECT IF(
                        NOT EXISTS (
                            SELECT 1
                            FROM information_schema.STATISTICS
                            WHERE TABLE_SCHEMA = DATABASE()
                              AND TABLE_NAME = 'tenant_gamma_event'
                              AND INDEX_NAME = 'idx_event_state'
                        ),
                        'ALTER TABLE `tenant_gamma_event` ADD INDEX `idx_event_state` (`state`)',
                        'SELECT 1'
                    )
                );
                SELECT @ddl;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isGreaterThan(0);
        assertThat(converted.sql())
                .contains("SET @ddl")
                .contains("ALTER TABLE `tenant_gamma_event` ADD INDEX `idx_event_state`")
                .doesNotContain("EXECUTE IMMEDIATE 'CREATE INDEX");
    }

    @Test
    void preservesQueryBackedScriptVariableSnapshotInAnonymousBlock() throws Exception {
        Path sqlRoot = tempDir.resolve("snapshot/sql/v2");
        Path sqlRootOut = tempDir.resolve("snapshot/sql/v2-dm");
        write(sqlRoot.resolve("seed.sql"), """
                -- Capture the state before any seed insert changes it.
                SET @tenant_entity_exists := (
                    SELECT EXISTS (
                        SELECT 1
                        FROM tenant_entity
                        WHERE entity_code = 'tenant'
                          AND delete_flag = 0
                    )
                );

                INSERT INTO tenant_entity(id, entity_code, delete_flag)
                SELECT 11, 'tenant', 0
                WHERE @tenant_entity_exists = 0
                  AND NOT EXISTS (SELECT 1 FROM tenant_entity WHERE id = 11);

                CREATE TABLE IF NOT EXISTS tenant_tag (
                    id BIGINT PRIMARY KEY,
                    tag_code VARCHAR(100)
                );

                INSERT INTO tenant_tag(id, tag_code)
                SELECT 21, 'active'
                WHERE @tenant_entity_exists = 0
                  AND NOT EXISTS (SELECT 1 FROM tenant_tag WHERE id = 21);

                INSERT INTO unrelated_audit(id) VALUES (31);
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir.resolve("snapshot"),
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "tenant_alpha",
                        "",
                        DmValidationEnvironment.from(Map.of())
                )
        );

        String output = Files.readString(sqlRootOut.resolve("seed.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(output)
                .contains("-- Capture the state before any seed insert changes it.")
                .contains("dm_tenant_entity_exists BIGINT;")
                .contains("SELECT CASE WHEN COUNT(*) > 0 THEN 1 ELSE 0 END INTO dm_tenant_entity_exists")
                .contains("WHERE dm_tenant_entity_exists = 0")
                .contains("EXECUTE IMMEDIATE 'CREATE TABLE IF NOT EXISTS tenant_tag")
                .contains("INSERT INTO unrelated_audit(id) VALUES (31);")
                .doesNotContain("@tenant_entity_exists")
                .doesNotContain("CREATE OR REPLACE PROCEDURE dm_adapter_snapshot_")
                .doesNotContain("CALL dm_adapter_snapshot_")
                .doesNotContain("DROP PROCEDURE IF EXISTS dm_adapter_snapshot_");
        assertThat(output.indexOf("INTO dm_tenant_entity_exists"))
                .isLessThan(output.indexOf("INSERT INTO tenant_entity"));
        assertThat(report.files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules()).contains(
                        SqlScriptMigrator.MYSQL_SCRIPT_USER_VARIABLE_SNAPSHOT_BLOCK_RULE,
                        SqlScriptMigrator.MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL_RULE,
                        SqlScriptMigrator.MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE_RULE,
                        SqlScriptMigrator.DM_TRANSIENT_PROCEDURE_TO_ANONYMOUS_BLOCK_RULE
                ));
    }

    @Test
    void inlinesStableSelectIntoScriptVariablesAcrossTransientProcedure() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                SELECT DISTINCT tenant_id INTO @tenantId
                FROM tenant_registry
                ORDER BY tenant_id ASC
                LIMIT 1;
                SELECT MIN(org_id) INTO @orgId
                FROM tenant_org
                WHERE tenant_id = @tenantId;
                DROP PROCEDURE IF EXISTS seed_tenant;
                DELIMITER $$
                CREATE PROCEDURE seed_tenant()
                BEGIN
                    INSERT INTO tenant_audit(tenant_id, org_id)
                    VALUES (@tenantId, @orgId);
                END$$
                DELIMITER ;
                CALL seed_tenant();
                DROP PROCEDURE IF EXISTS seed_tenant;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("query-backed script variable @tenantId was inlined from a stable scalar query")
                .contains("query-backed script variable @orgId was inlined from a stable scalar query")
                .contains("SELECT DISTINCT tenant_id FROM tenant_registry")
                .contains("ORDER BY tenant_id ASC\nLIMIT 1")
                .contains("SELECT MIN(org_id) FROM tenant_org")
                .contains("INSERT INTO tenant_audit(tenant_id, org_id)")
                .doesNotContain("INTO @tenantId")
                .doesNotContain("INTO @orgId")
                .doesNotContain("VALUES (@tenantId, @orgId)")
                .doesNotContain("CREATE OR REPLACE PROCEDURE seed_tenant")
                .doesNotContain("CALL seed_tenant()");
        assertThat(converted.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules()).contains(
                        SqlScriptMigrator.MYSQL_SCRIPT_QUERY_USER_VARIABLE_INLINE_RULE,
                        SqlScriptMigrator.DM_TRANSIENT_PROCEDURE_TO_ANONYMOUS_BLOCK_RULE
                ));
    }

    @Test
    void inlinesStableSetAndMultiColumnSelectIntoScriptVariables() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                SELECT tenant_id, org_id INTO @tenantId, @unusedOrgId
                FROM tenant_registry
                ORDER BY tenant_id ASC
                LIMIT 1;
                SET @orgId = (
                    SELECT MIN(org_id)
                    FROM tenant_org
                    WHERE tenant_id = @tenantId
                );
                SELECT role_id INTO @unusedRoleId
                FROM tenant_role
                WHERE tenant_id = @tenantId
                ORDER BY role_id
                LIMIT 1;
                INSERT INTO tenant_audit(tenant_id, org_id)
                VALUES (@tenantId, @orgId);
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("query-backed script variables @tenantId, @unusedOrgId")
                .contains("query-backed script variable @orgId was inlined")
                .contains("query-backed script variable @unusedRoleId was inlined")
                .contains("SELECT tenant_id FROM tenant_registry")
                .contains("SELECT MIN(org_id)")
                .contains("INSERT INTO tenant_audit(tenant_id, org_id)")
                .doesNotContain("INTO @")
                .doesNotContain("SET @")
                .doesNotContain("VALUES (@");
        assertThat(converted.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SCRIPT_QUERY_USER_VARIABLE_INLINE_RULE));
    }

    @Test
    void stopsStableQueryVariableInliningBeforeRoutineReassignment() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                SELECT id INTO @typeId
                FROM tenant_type
                WHERE type_code = 'base'
                LIMIT 1;
                INSERT INTO tenant_setting(tenant_id, type_id)
                SELECT tenant_id, @typeId FROM tenant;
                DELIMITER $$
                CREATE PROCEDURE seed_other_type()
                BEGIN
                    SELECT id INTO @typeId
                    FROM tenant_type
                    WHERE type_code = 'other'
                    LIMIT 1;
                    INSERT INTO tenant_other_setting(type_id) VALUES (@typeId);
                END$$
                DELIMITER ;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("SELECT tenant_id, (SELECT id FROM tenant_type")
                .contains("WHERE type_code = 'base'")
                .contains("SELECT id INTO dm_typeId")
                .contains("WHERE type_code = 'other'")
                .contains("VALUES (dm_typeId)")
                .doesNotContain("VALUES ((SELECT id FROM tenant_type");
    }

    @Test
    void keepsSelectIntoScriptVariableWhenItsSourceTableChanges() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                SELECT tenant_id INTO @tenantId
                FROM tenant_registry
                ORDER BY tenant_id ASC
                LIMIT 1;
                UPDATE tenant_registry SET active = 1 WHERE tenant_id = @tenantId;
                INSERT INTO tenant_audit(tenant_id) VALUES (@tenantId);
                """);

        assertThat(converted.report().manualReviewSqlCount()).isGreaterThan(0);
        assertThat(converted.sql())
                .contains("INTO @tenantId")
                .contains("UPDATE tenant_registry")
                .contains("VALUES (@tenantId)")
                .doesNotContain("query-backed script variable @tenantId was inlined");
        assertThat(converted.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules())
                        .doesNotContain(SqlScriptMigrator.MYSQL_SCRIPT_QUERY_USER_VARIABLE_INLINE_RULE));
    }

    @Test
    void inlinesSetQueryBackedScriptVariableAcrossStoredRoutine() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                SET @tenant_id := (
                    SELECT MIN(id) FROM tenant
                );
                CREATE PROCEDURE seed_tenant()
                BEGIN
                    INSERT INTO tenant_audit(tenant_id) VALUES (@tenant_id);
                END;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("query-backed script variable @tenant_id was inlined")
                .contains("VALUES ((SELECT MIN(id) FROM tenant))")
                .doesNotContain("SET @tenant_id");
        assertThat(converted.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SCRIPT_QUERY_USER_VARIABLE_INLINE_RULE)
                        .doesNotContain(SqlScriptMigrator.MYSQL_SCRIPT_USER_VARIABLE_SNAPSHOT_BLOCK_RULE));
    }

    @Test
    void doesNotExecuteMysqlScriptIndexDdlWhenPrepareSequenceIsIncomplete() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260523.sql"), """
                SET @idx_sample_exists = (
                    SELECT COUNT(*)
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'sample_account'
                      AND INDEX_NAME = 'idx_sample_account_name'
                );
                SET @idx_sample_sql = IF(
                    @idx_sample_exists = 0,
                    'ALTER TABLE `sample_account` ADD INDEX `idx_sample_account_name` (`account_name`)',
                    'SELECT 1'
                );
                SELECT @idx_sample_sql;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-incomplete",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("20260523.sql"));
        assertThat(report.manualReviewSqlCount()).isGreaterThan(0);
        assertThat(converted)
                .contains("@idx_sample_exists")
                .contains("@idx_sample_sql")
                .doesNotContain("FROM ALL_INDEXES")
                .doesNotContain("EXECUTE IMMEDIATE");
    }

    @Test
    void convertsMysqlBackslashEscapedSingleQuotesInsideSqlStringLiterals() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260521.sql"), """
                insert into ns_report_field_design(fieldExpression, canalFieldExpression)
                values ('charge.IsCheck=\\'审核通过\\'', 'charge.IsCheck=\\'审核通过\\'');
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-report",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("20260521.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("values ('charge.IsCheck=''审核通过'''")
                .contains("'charge.IsCheck=''审核通过''')")
                .doesNotContain("\\'审核通过\\'");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SQL_STRING_JSON_ESCAPE_TO_DM_RULE));
    }

    @Test
    void normalizesDropProcedureAndTemporaryTableSyntax() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205_system.sql"), "\uFEFF" + """
                -- file starts with UTF-8 BOM
                DROP PROCEDURE
                IF
                    EXISTS `already_safe_proc`;
                DROP PROCEDURE `missing_proc`;
                DROP PROCEDURE IF EXISTS IF EXISTS duplicated_proc;
                create TEMPORARY table if not exists tmp_enterprise_orgid
                SELECT enterprise_id, organization_id FROM ns_system_organization;
                alter table tmp_enterprise_orgid add index idx_1(enterprise_id, organization_id);
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("20260205_system.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .doesNotContain("\uFEFF")
                .doesNotContain("IF EXISTS IF")
                .contains("DROP PROCEDURE IF EXISTS already_safe_proc")
                .contains("DROP PROCEDURE IF EXISTS missing_proc")
                .contains("DROP PROCEDURE IF EXISTS duplicated_proc")
                .containsIgnoringCase("CREATE TABLE IF NOT EXISTS tmp_enterprise_orgid AS SELECT enterprise_id, organization_id FROM ns_system_organization")
                .contains("-- DM_ADAPTER: ignored MySQL temporary table index DDL")
                .doesNotContain("TEMPORARY")
                .doesNotContain("add index");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules()).contains(
                        SqlScriptMigrator.MYSQL_DROP_PROCEDURE_IF_EXISTS_RULE,
                        SqlScriptMigrator.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE,
                        SqlScriptMigrator.MYSQL_TEMPORARY_INDEX_NOOP_RULE
                ));
    }

    @Test
    void readsUtf16LittleEndianSqlScriptAndWritesUtf8Output() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        Path source = sqlRoot.resolve("20260813_system.sql");
        Files.createDirectories(source.getParent());
        byte[] content = """
                update ns_core_menu
                set menu_menusubname = 'budgetParameterSetting'
                where id = 1;
                """.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[content.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(content, 0, withBom, 2, content.length);
        Files.write(source, withBom);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-budget",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        byte[] output = Files.readAllBytes(sqlRootOut.resolve("20260813_system.sql"));
        assertThat(report.validationFailureCount()).isZero();
        assertThat(output[0]).isEqualTo((byte) 'u');
        assertThat(new String(output, StandardCharsets.UTF_8))
                .contains("update ns_core_menu")
                .contains("menu_menusubname = 'budgetParameterSetting'")
                .doesNotContain("\u0000");
    }

    @Test
    void keepsExistingDropProcedureIfExistsWhenSplitByCrLf() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205.sql"), """
                /* add parameter */
                DROP PROCEDURE
                IF
                    EXISTS `pro_AddColumn`;
                """
                .replace("\n", "\r\n"));

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("20260205.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .doesNotContain("IF EXISTS \nIF")
                .doesNotContain("IF EXISTS \r\nIF")
                .contains("DROP PROCEDURE IF EXISTS pro_AddColumn");
    }

    @Test
    void keepsUnsafeProcedureSqlForManualReview() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE unsafe_proc()
                BEGIN
                    PREPARE stmt FROM 'select 1';
                    EXECUTE stmt;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("dynamic SQL"));
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("PREPARE stmt FROM 'select 1'")
                .contains("EXECUTE stmt");
    }

    @Test
    void marksUnsafeIntegerArithmeticScriptSqlForManualReviewAndSkipsValidation() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        RecordingValidator validator = new RecordingValidator();
        write(sqlRoot.resolve("arithmetic.sql"), """
                select '10'/4 from dual;
                """);

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.validationSuccessCount()).isZero();
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.reason()).contains("整数算术表达式风险");
                    assertThat(item.originalSql()).contains("'10'/4");
                    assertThat(item.convertedSql()).contains("'10'/4");
                });
        assertThat(Files.readString(sqlRootOut.resolve("arithmetic.sql")))
                .contains("select '10'/4 from dual;");
        assertThat(validator.files)
                .singleElement()
                .satisfies(file -> assertThat(file.manualReviewStatementIndexes()).containsExactly(1));
    }

    @Test
    void reportsOriginalDanglingInsertValuesCommaForManualReviewAndSkipsDependentCall() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        RecordingValidator validator = new RecordingValidator();
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE insert_report_seed()
                BEGIN
                    IF 1 = 1 THEN
                        INSERT INTO ns_report_rule_param(menu_id, param_code)
                        VALUES ('report-demo', 'precinctId'),
                    END IF;
                END$$
                DELIMITER ;
                CALL insert_report_seed();
                """);

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-report",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(2);
        assertThat(report.manualReviewItems())
                .extracting(SqlScriptManualReviewItem::reason)
                .anySatisfy(reason -> assertThat(reason)
                        .contains("原始 SQL 语法缺陷")
                        .contains("最后一个值元组后面仍然是逗号"))
                .anySatisfy(reason -> assertThat(reason)
                        .contains("依赖需要人工确认的存储过程 `insert_report_seed`"));
        assertThat(validator.files)
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.manualReviewStatementIndexes()).containsExactlyInAnyOrder(1, 2);
                    assertThat(file.statements()).hasSize(2);
                });
        assertThat(report.validationSuccessCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("VALUES ('report-demo', 'precinctId'),")
                .contains("CALL insert_report_seed()");
    }

    @Test
    void reportsOriginalProcedureWithMissingEndIfAndSkipsDependentCall() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        RecordingValidator validator = new RecordingValidator();
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE create_budget_view()
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM information_schema.views
                        WHERE table_schema = database()
                          AND table_name = 'ns_budget_view'
                    ) THEN
                        CREATE VIEW ns_budget_view AS
                        SELECT IF(state = 1, '启用', '停用') AS state_name
                        FROM ns_budget;
                END$$
                DELIMITER ;
                CALL create_budget_view();
                """);

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-budget",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(2);
        assertThat(report.manualReviewItems())
                .extracting(SqlScriptManualReviewItem::reason)
                .anySatisfy(reason -> assertThat(reason)
                        .contains("原始 SQL 语法缺陷")
                        .contains("IF ... THEN 与 END IF 数量不匹配")
                        .contains("IF=1，END IF=0"))
                .anySatisfy(reason -> assertThat(reason)
                        .contains("依赖需要人工确认的存储过程 `create_budget_view`"));
        assertThat(validator.files)
                .singleElement()
                .satisfies(file -> assertThat(file.manualReviewStatementIndexes())
                        .containsExactlyInAnyOrder(1, 2));
        assertThat(report.validationSuccessCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("CREATE PROCEDURE create_budget_view()")
                .contains("SELECT IF(state = 1, '启用', '停用')")
                .doesNotContain("CREATE OR REPLACE PROCEDURE create_budget_view()");
    }

    @Test
    void convertsBalancedProcedureIfWhoseParenthesizedSubqueryIsFollowedByComparison() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        RecordingValidator validator = new RecordingValidator();
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE insert_log_match_data()
                BEGIN
                    IF (SELECT COUNT(0) FROM ns_quality_log_match) = 0 THEN
                        INSERT INTO ns_quality_log_match(table_name) VALUES ('schedule');
                    END IF;
                    IF (SELECT COUNT(0) FROM ns_quality_log_match WHERE table_name = 'cycle') != 0 THEN
                        UPDATE ns_quality_log_match SET table_name = 'updated'
                        WHERE table_name = 'cycle';
                    END IF;
                END$$
                DELIMITER ;
                CALL insert_log_match_data();
                """);

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-quality",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE insert_log_match_data()")
                .contains("IF (SELECT COUNT(0) FROM ns_quality_log_match) = 0 THEN")
                .contains("IF (SELECT COUNT(0) FROM ns_quality_log_match WHERE TABLE_NAME = 'cycle') != 0 THEN")
                .contains("CALL insert_log_match_data()");
        assertThat(validator.files)
                .singleElement()
                .satisfies(file -> assertThat(file.manualReviewStatementIndexes()).isEmpty());
    }

    @Test
    void clearsManualProcedureDependencyAfterSuccessfulRecreation() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        RecordingValidator validator = new RecordingValidator();
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE recreate_demo()
                BEGIN
                    INSERT INTO ns_demo(id) VALUES (1),
                END$$
                DELIMITER ;
                CALL recreate_demo();
                DROP PROCEDURE IF EXISTS recreate_demo;
                DELIMITER $$
                CREATE PROCEDURE recreate_demo()
                BEGIN
                    INSERT INTO ns_demo(id) VALUES (1);
                END$$
                DELIMITER ;
                CALL recreate_demo();
                """);

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(2);
        assertThat(report.manualReviewItems())
                .extracting(SqlScriptManualReviewItem::statementIndex)
                .containsExactly(1, 2);
        assertThat(validator.files)
                .singleElement()
                .satisfies(file -> {
                    assertThat(file.manualReviewStatementIndexes()).containsExactlyInAnyOrder(1, 2);
                    assertThat(file.statements().get(4)).contains("CALL recreate_demo()");
                });
    }

    @Test
    void reportsSuspiciousLengthModifyWhenCheckOnlyComparesTargetLengthWithoutTypeGuard() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE modify_details()
                BEGIN
                    IF NOT EXISTS(
                        SELECT CHARACTER_MAXIMUM_LENGTH
                        FROM information_schema.COLUMNS
                        WHERE table_schema = database()
                          AND table_name = 'ns_payment_order_log'
                          AND column_name = 'details'
                          AND CHARACTER_MAXIMUM_LENGTH = 1000
                    ) THEN
                        alter table `ns_payment_order_log` MODIFY COLUMN `details` varchar(1000) DEFAULT NULL COMMENT '关于行为的描述';
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("可疑字段长度修改")
                        .contains("varchar(1000)")
                        .contains("DATA_TYPE/column_type")
                        .contains("CHARACTER_MAXIMUM_LENGTH 小于目标长度"));
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("CREATE PROCEDURE modify_details()")
                .contains("CHARACTER_MAXIMUM_LENGTH = 1000")
                .doesNotContain("CREATE OR REPLACE PROCEDURE modify_details() AS");
    }

    @Test
    void keepsLengthExtensionWhenTypeAndLessThanGuardArePresent() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE modify_details()
                BEGIN
                    IF EXISTS(
                        SELECT 1
                        FROM information_schema.COLUMNS
                        WHERE table_schema = database()
                          AND table_name = 'ns_payment_order_log'
                          AND column_name = 'details'
                          AND LOWER(DATA_TYPE) IN ('char', 'varchar')
                          AND CHARACTER_MAXIMUM_LENGTH < 1000
                    ) THEN
                        alter table `ns_payment_order_log` MODIFY COLUMN `details` varchar(1000) DEFAULT NULL COMMENT '关于行为的描述';
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("CREATE OR REPLACE PROCEDURE modify_details() AS")
                .contains("dm_adapter_schema VARCHAR(128) := SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID);")
                .contains("LOWER(DATA_TYPE) IN ('char', 'varchar')")
                .contains("CHAR_LENGTH < 1000")
                .contains("EXECUTE IMMEDIATE 'alter table `ns_payment_order_log` MODIFY `details` varchar(1000) DEFAULT NULL'");
    }

    @Test
    void convertsMysqlProcedureParametersLabelsDeclarationsAndTemporaryDdl() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc(IN `input_json` JSON, out `row_count` int)
                label_exit:BEGIN
                    DECLARE v_index INT DEFAULT 0;
                    DECLARE v_code, v_name varchar(64);
                    IF input_json IS NULL THEN
                        LEAVE label_exit;
                    END IF;
                    DROP TEMPORARY TABLE IF EXISTS tmp_demo_a,tmp_demo_b;
                    CREATE TEMPORARY TABLE IF NOT EXISTS tmp_demo_a SELECT 1 AS id;
                    ALTER TABLE tmp_demo_a ADD COLUMN extra_name varchar(20);
                    CREATE TEMPORARY TABLE IF NOT EXISTS tmp_demo_b SELECT * FROM tmp_demo_a;
                    CREATE INDEX tmp_demo_idx ON tmp_demo_a(id);
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE TABLE IF NOT EXISTS tmp_demo_a (id BIGINT, extra_name VARCHAR(200));")
                .contains("CREATE TABLE IF NOT EXISTS tmp_demo_b (id BIGINT, extra_name VARCHAR(200));")
                .contains("CREATE OR REPLACE PROCEDURE demo_proc(input_json IN JSON, row_count OUT int) AS")
                .doesNotContain("`input_json`")
                .doesNotContain("`row_count`")
                .contains("""
                            v_index INT := 0;
                            v_code varchar(64);
                            v_name varchar(64);
                        BEGIN
                        """)
                .contains("RETURN;")
                .contains("DELETE FROM tmp_demo_a;")
                .contains("DELETE FROM tmp_demo_b;")
                .contains("INSERT INTO tmp_demo_a (id) SELECT 1 AS id;")
                .contains("INSERT INTO tmp_demo_b (id, extra_name) SELECT id, extra_name FROM tmp_demo_a;")
                .doesNotContain("NULL;")
                .doesNotContain("temporary table index DDL")
                .doesNotContain("tmp_demo_idx")
                .doesNotContain("DROP TABLE IF EXISTS tmp_demo_a;")
                .doesNotContain("DROP TABLE IF EXISTS tmp_demo_b;")
                .doesNotContain("label_exit:BEGIN")
                .doesNotContain("LEAVE label_exit")
                .doesNotContain("TEMPORARY TABLE")
                .doesNotContain("ADD COLUMN extra_name")
                .doesNotContain("EXECUTE IMMEDIATE 'DROP TABLE IF EXISTS tmp_demo_a'")
                .doesNotContain("EXECUTE IMMEDIATE 'CREATE TABLE tmp_demo_a");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_TEMP_TABLE_COMPILE_PLACEHOLDER_RULE));
    }

    @Test
    void convertsExplicitNonTmpProcedureTemporaryTablesToDmLocalTables() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                DELIMITER $$
                CREATE PROCEDURE calculate_formula(IN targetId BIGINT)
                BEGIN
                    DROP TEMPORARY TABLE IF EXISTS temp_target, spilt_target;
                    CREATE TEMPORARY TABLE IF NOT EXISTS temp_target (
                        id BIGINT(0) NOT NULL AUTO_INCREMENT,
                        targetId BIGINT,
                        PRIMARY KEY (`id`) USING BTREE
                    );
                    CREATE TEMPORARY TABLE IF NOT EXISTS spilt_target (
                        id BIGINT(0) NOT NULL AUTO_INCREMENT,
                        targetId VARCHAR(10),
                        PRIMARY KEY (`id`) USING BTREE
                    );
                    INSERT INTO temp_target(targetId) VALUES (targetId);
                    INSERT INTO spilt_target(targetId)
                    SELECT targetId FROM temp_target;
                    TRUNCATE TABLE spilt_target;
                    DROP TABLE temp_target;
                    DROP TABLE spilt_target;
                END$$
                DELIMITER ;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("CREATE TABLE #temp_target")
                .contains("CREATE TABLE #spilt_target")
                .contains("IDENTITY(1,1)")
                .contains("INSERT INTO #temp_target(targetId) VALUES (targetId)")
                .contains("INSERT INTO #spilt_target(targetId)")
                .contains("SELECT targetId FROM #temp_target")
                .contains("DELETE FROM #spilt_target")
                .doesNotContain("CREATE TEMPORARY TABLE")
                .doesNotContain("EXECUTE IMMEDIATE 'CREATE TABLE #")
                .doesNotContain("CAST(targetId AS VARCHAR")
                .doesNotContain("CREATE TABLE IF NOT EXISTS #temp_target");
        assertThat(converted.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_LOCAL_TEMPORARY_TABLE_TO_DM_RULE));
    }

    @Test
    void dynamicallyExecutesPermanentTableDmlAfterTruncateWithLocalTemporarySource() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE TABLE daily_result(id BIGINT, amount DECIMAL(18, 2));
                DELIMITER $$
                CREATE PROCEDURE rebuild_daily_result()
                BEGIN
                    CREATE TEMPORARY TABLE daily_result_bak AS
                    SELECT id, amount FROM source_result;
                    TRUNCATE TABLE daily_result;
                    INSERT INTO daily_result(id, amount)
                    SELECT id, amount FROM daily_result_bak;
                    DROP TABLE daily_result_bak;
                END$$
                DELIMITER ;
                """);

        assertThat(converted.report().manualReviewItems()).isEmpty();
        assertThat(converted.sql())
                .contains("CREATE TABLE #daily_result_bak AS")
                .contains("EXECUTE IMMEDIATE 'TRUNCATE TABLE daily_result'")
                .contains("EXECUTE IMMEDIATE 'INSERT INTO daily_result(id, amount)"
                        + System.lineSeparator()
                        + "    SELECT id, amount FROM #daily_result_bak'");
    }

    @Test
    void keepsExplainedNoopWhenOmittedTemporaryIndexWouldEmptyNestedBlock() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc(IN create_index int)
                BEGIN
                    CREATE TEMPORARY TABLE IF NOT EXISTS tmp_demo (id bigint);
                    IF create_index = 1 THEN
                        ALTER TABLE tmp_demo ADD INDEX idx_demo_id (id);
                    END IF;
                    SELECT COUNT(*) FROM tmp_demo;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("IF create_index = 1 THEN")
                .contains("NULL /* DM_ADAPTER: omitted MySQL temporary table index DDL */;")
                .contains("END IF;")
                .doesNotContain("ADD INDEX idx_demo_id");
    }

    @Test
    void qualifiesProcedureGroupByColumnsWhenJoinMakesNamesAmbiguous() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc()
                BEGIN
                    INSERT INTO tmp_enterprise_orgid_insert
                    SELECT DISTINCT x2.enterprise_id, x2.organization_id
                    FROM tmp_enterprise_orgid_2 x2
                    LEFT JOIN ns_core_resourcefield b
                      ON b.ENTERPRISE_ID = x2.enterprise_id
                     AND b.ORGANIZATION_ID = x2.organization_id
                    WHERE b.id IS NULL
                    GROUP BY ENTERPRISE_ID,ORGANIZATION_ID;

                    SELECT enterprise_id, organization_id
                    FROM tmp_enterprise_orgid
                    GROUP BY enterprise_id, organization_id;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(converted)
                .contains("INSERT INTO tmp_enterprise_orgid_insert (enterprise_id, organization_id)")
                .contains("GROUP BY x2.ENTERPRISE_ID,x2.ORGANIZATION_ID;")
                .contains("GROUP BY enterprise_id, organization_id;");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_GROUP_BY_ALIAS_RULE));
    }

    @Test
    void addsSysTimeToProcedureAuditInsertSelectWhenMissing() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc()
                BEGIN
                    INSERT INTO `ns_core_resourcefield` (`ID`,`NAME`,`SY_CREATETIME`)
                    SELECT x.id, x.name, now()
                    FROM tmp_form_field_insert x;

                    INSERT INTO ns_core_resourcetable (ID, NAME, SY_CREATETIME)
                    SELECT x.id, x.name, now()
                    FROM tmp_resource_table x;

                    INSERT INTO sample_already_done (ID, SY_CREATETIME, sys_time)
                    SELECT x.id, now(), now()
                    FROM tmp_done x;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(converted)
                .contains("INSERT INTO `ns_core_resourcefield` (`ID`,`NAME`,`SY_CREATETIME`,`sys_time`)")
                .contains(", now() FROM tmp_form_field_insert")
                .contains("INSERT INTO ns_core_resourcetable (ID, NAME, SY_CREATETIME)\n    SELECT x.id, x.name, now()")
                .contains("INSERT INTO sample_already_done (ID, SY_CREATETIME, sys_time)");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_MISSING_SYS_TIME_RULE));
    }

    @Test
    void convertsMysqlProcedureUserVariablesToLocalVariables() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc(IN input_json JSON)
                BEGIN
                    SET @has_menu_ver = (
                        SELECT COUNT(1)
                        FROM information_schema.COLUMNS
                        WHERE table_schema = database()
                          AND table_name = 'ns_menu_version'
                    );
                    SET @new_form_data = '{"name":"demo"}';
                    SELECT ROLEID INTO @roleId FROM ns_core_role ORDER BY id ASC LIMIT 1;
                    IF @has_menu_ver > 0 AND @roleId IS NOT NULL THEN
                        INSERT INTO ns_core_form(form_content, role_id) VALUES(@new_form_data, @roleId);
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE demo_proc(input_json IN JSON) AS")
                .contains("dm_adapter_schema VARCHAR(128) := SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID);")
                .contains("dm_has_menu_ver BIGINT;")
                .contains("dm_new_form_data VARCHAR(4000);")
                .contains("dm_roleId VARCHAR(4000);")
                .contains("dm_has_menu_ver := (")
                .contains("dm_new_form_data := '{\"name\":\"demo\"}';")
                .contains("SELECT ROLEID INTO dm_roleId FROM ns_core_role ORDER BY id ASC LIMIT 1;")
                .contains("IF dm_has_menu_ver > 0 AND dm_roleId IS NOT NULL THEN")
                .contains("VALUES(dm_new_form_data, dm_roleId)")
                .doesNotContain("@has_menu_ver")
                .doesNotContain("@new_form_data")
                .doesNotContain("@roleId");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_LOCAL_SET_TO_ASSIGNMENT_RULE
                        ));
    }

    @Test
    void usesClobForDynamicDmlAccumulatorUserVariablesInsideProcedure() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE batch_insert_demo(IN input_json JSON)
                BEGIN
                    SET @v_insert_sql = 'INSERT INTO tmp_demo(id, name) VALUES';
                    SET @v_insert_sql = CONCAT(@v_insert_sql, '(''1'', ''demo'')');
                    PREPARE stmt FROM @v_insert_sql;
                    EXECUTE stmt;
                    DEALLOCATE PREPARE stmt;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("dm_v_insert_sql CLOB;")
                .contains("dm_v_insert_sql := 'INSERT INTO tmp_demo(id, name) VALUES';")
                .contains("dm_v_insert_sql := CONCAT(dm_v_insert_sql, '(''1'', ''demo'')');")
                .contains("EXECUTE IMMEDIATE dm_v_insert_sql;")
                .doesNotContain("@v_insert_sql")
                .doesNotContain("PREPARE stmt");
    }

    @Test
    void convertsExactMysqlCursorNotFoundHandlerLoopToDamengLoop() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE add_hrParameter(IN p_key varchar(9999))
                BEGIN
                    DECLARE etrId BIGINT (20);
                    DECLARE STOP INT DEFAULT 0;
                    DECLARE ENTERPRISE CURSOR FOR SELECT DISTINCT ENTERPRISE_ID FROM `sample-system`.`ns_system_organization`;
                    DECLARE CONTINUE HANDLER FOR SQLSTATE '02000'SET STOP=1;
                    OPEN ENTERPRISE;
                    -- initialize the first cursor row
                    FETCH ENTERPRISE INTO etrId;
                    /* continue until the cursor is exhausted */
                    WHILE STOP<> 1 DO
                        SELECT count(1) INTO @count FROM ns_hr_parameter_setting WHERE paramKey = p_key;
                        IF @count < 1 THEN
                            INSERT INTO ns_hr_parameter_setting(enterpriseId, paramKey) VALUES (etrId, p_key);
                        END IF;
                        FETCH ENTERPRISE INTO etrId;
                    END WHILE;
                    CLOSE ENTERPRISE;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-hr",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("etrId BIGINT;")
                .contains("ENTERPRISE CURSOR FOR SELECT DISTINCT ENTERPRISE_ID FROM `sample-system`.`ns_system_organization`;")
                .contains("""
                            OPEN ENTERPRISE;
                            LOOP
                                FETCH ENTERPRISE INTO etrId;
                                EXIT WHEN ENTERPRISE%NOTFOUND;
                        """)
                .contains("SELECT count(1) INTO dm_count FROM ns_hr_parameter_setting WHERE paramKey = p_key;")
                .contains("IF dm_count < 1 THEN")
                .contains("END LOOP;")
                .contains("CLOSE ENTERPRISE;")
                .doesNotContain("DECLARE CONTINUE HANDLER")
                .doesNotContain("WHILE STOP")
                .doesNotContain("STOP INT")
                .doesNotContain("@count");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_CURSOR_HANDLER_TO_LOOP_RULE));
    }

    @Test
    void convertsNullSentinelCursorLoopAndPreservesOptionalSelectIntoSemantics() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE init_enums()
                BEGIN
                    DECLARE siteId BIGINT;
                    DECLARE enumId BIGINT;
                    DECLARE site_cursor CURSOR FOR SELECT id FROM ns_site WHERE deleted = 0;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET siteId = NULL;
                    OPEN site_cursor;
                    -- initialize the cursor
                    FETCH site_cursor INTO siteId;
                    -- iterate through every site
                    main_loop: LOOP
                        IF siteId IS NULL THEN
                            LEAVE main_loop;
                        END IF;
                        SET enumId = NULL;
                        SELECT id INTO enumId
                        FROM ns_enums
                        WHERE site_id = siteId AND enum_code = 'companyType'
                        LIMIT 1;
                        IF enumId IS NOT NULL THEN
                            INSERT INTO ns_enum_log(enum_id) VALUES (enumId);
                        END IF;
                        FETCH site_cursor INTO siteId;
                    END LOOP main_loop;
                    CLOSE site_cursor;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-association",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("site_cursor CURSOR FOR SELECT id FROM ns_site WHERE deleted = 0;")
                .contains("FETCH site_cursor INTO siteId;")
                .contains("EXIT WHEN site_cursor%NOTFOUND;")
                .contains("enumId := (SELECT id FROM ns_enums")
                .contains("WHERE site_id = siteId AND enum_code = 'companyType'")
                .contains("LIMIT 1);")
                .doesNotContain("DECLARE CONTINUE HANDLER")
                .doesNotContain("main_loop")
                .doesNotContain("IF siteId IS NULL")
                .doesNotContain("SELECT id INTO enumId");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.MYSQL_PROCEDURE_CURSOR_HANDLER_TO_LOOP_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_LOCAL_SET_TO_ASSIGNMENT_RULE
                        ));
    }

    @Test
    void keepsNullSentinelHandlerWhenOptionalSelectIntoIsNotNullInitialized() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE unsafe_null_sentinel()
                BEGIN
                    DECLARE siteId BIGINT;
                    DECLARE enumId BIGINT;
                    DECLARE site_cursor CURSOR FOR SELECT id FROM ns_site;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET siteId = NULL;
                    OPEN site_cursor;
                    FETCH site_cursor INTO siteId;
                    main_loop: LOOP
                        IF siteId IS NULL THEN
                            LEAVE main_loop;
                        END IF;
                        SELECT id INTO enumId FROM ns_enums WHERE site_id = siteId LIMIT 1;
                        FETCH site_cursor INTO siteId;
                    END LOOP main_loop;
                    CLOSE site_cursor;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-association",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("HANDLER"));
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("DECLARE CONTINUE HANDLER FOR NOT FOUND SET siteId = NULL")
                .doesNotContain("site_cursor%NOTFOUND");
    }

    @Test
    void movesMysqlProcedureDeclarationsAfterLeadingComments() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE initializeInspectItemNum()
                BEGIN
                    -- keep comment before declarations
                    DECLARE itemId3 bigint;
                    DECLARE STOP3 INT DEFAULT 0;
                    DECLARE ITEM3 CURSOR FOR SELECT id FROM ns_equip_inspect_item;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET STOP3=1;
                    OPEN ITEM3;
                    FETCH ITEM3 INTO itemId3;
                    WHILE STOP3<> 1 DO
                        INSERT INTO ns_equip_inspect_item_num(itemId) VALUES(itemId3);
                        FETCH ITEM3 INTO itemId3;
                    END WHILE;
                    CLOSE ITEM3;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-equip",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("""
                        CREATE OR REPLACE PROCEDURE initializeInspectItemNum() AS
                            itemId3 bigint;
                            ITEM3 CURSOR FOR SELECT id FROM ns_equip_inspect_item;
                        BEGIN
                            -- keep comment before declarations
                        """)
                .contains("EXIT WHEN ITEM3%NOTFOUND;")
                .doesNotContain("BEGIN\n    -- keep comment before declarations\n    DECLARE")
                .doesNotContain("DECLARE CONTINUE HANDLER")
                .doesNotContain("STOP3");
    }

    @Test
    void convertsMysqlAlterChangeWithSameColumnNameInsideProcedureDdl() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE updatePrecinctColumns()
                BEGIN
                    IF EXISTS (
                        SELECT NULL FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE table_name = 'ns_equip_inspect_template'
                          AND table_schema = 'sample-equip'
                          AND column_name = 'precinctID'
                    ) THEN
                        alter table ns_equip_inspect_template change precinctID precinctID text NOT NULL COMMENT '项目id集合';
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-equip",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE ns_equip_inspect_template MODIFY precinctID CLOB NOT NULL'")
                .doesNotContain(" change precinctID precinctID ")
                .doesNotContain(" COMMENT ");
    }

    @Test
    void renamesReservedObjectCursorInsideProcedure() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE insertVirtualRoom()
                BEGIN
                    DECLARE house_id1 BIGINT (32);
                    DECLARE STOP INT DEFAULT 0;
                    DECLARE object CURSOR FOR SELECT house_id FROM owner_house_precinct_info;
                    DECLARE CONTINUE HANDLER FOR SQLSTATE '02000'SET STOP=1;
                    OPEN object;
                    FETCH object INTO house_id1;
                    WHILE STOP = 0 DO
                        INSERT INTO ns_equip_room(precinctid) VALUES (house_id1);
                        FETCH object INTO house_id1;
                    END WHILE;
                    CLOSE object;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-equip",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("dm_object_cursor CURSOR FOR SELECT house_id FROM owner_house_precinct_info;")
                .contains("OPEN dm_object_cursor;")
                .contains("FETCH dm_object_cursor INTO house_id1;")
                .contains("EXIT WHEN dm_object_cursor%NOTFOUND;")
                .contains("CLOSE dm_object_cursor;")
                .doesNotContain(" object CURSOR")
                .doesNotContain("OPEN object")
                .doesNotContain("FETCH object")
                .doesNotContain("CLOSE object");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_RESERVED_CURSOR_RENAME_RULE));
    }

    @Test
    void keepsZeroFlagCursorHandlerWhenSelectIntoCanTriggerIt() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE unsafe_optional_lookup()
                BEGIN
                    DECLARE siteId BIGINT;
                    DECLARE enumId BIGINT;
                    DECLARE done INT DEFAULT 0;
                    DECLARE site_cursor CURSOR FOR SELECT id FROM ns_site;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
                    OPEN site_cursor;
                    FETCH site_cursor INTO siteId;
                    WHILE done = 0 DO
                        SELECT id INTO enumId FROM ns_enums WHERE site_id = siteId LIMIT 1;
                        FETCH site_cursor INTO siteId;
                    END WHILE;
                    CLOSE site_cursor;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-association",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("原始 SQL 逻辑缺陷")
                        .contains("SELECT ... INTO")
                        .contains("提前结束 WHILE"));
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1")
                .contains("WHILE done = 0 DO")
                .doesNotContain("site_cursor%NOTFOUND");
    }

    @Test
    void convertsZeroFlagCursorHandlerWhenAggregateSelectIntoAlwaysReturnsOneRow() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE safe_count_lookup()
                BEGIN
                    DECLARE siteId BIGINT;
                    DECLARE enumCount BIGINT;
                    DECLARE done INT DEFAULT 0;
                    DECLARE site_cursor CURSOR FOR SELECT id FROM ns_site;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
                    OPEN site_cursor;
                    FETCH site_cursor INTO siteId;
                    WHILE done = 0 DO
                        SELECT COUNT(*) INTO enumCount FROM ns_enums WHERE site_id = siteId;
                        FETCH site_cursor INTO siteId;
                    END WHILE;
                    CLOSE site_cursor;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-association",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("EXIT WHEN site_cursor%NOTFOUND;")
                .contains("SELECT COUNT(*) INTO enumCount")
                .doesNotContain("DECLARE CONTINUE HANDLER")
                .doesNotContain("WHILE done = 0");
    }

    @Test
    void doesNotMistakeInsertIntoAfterExistsQueryForSelectInto() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE initialize_component()
                BEGIN
                    DECLARE siteId BIGINT;
                    DECLARE done INT DEFAULT 0;
                    DECLARE site_cursor CURSOR FOR SELECT id FROM ns_site;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
                    OPEN site_cursor;
                    FETCH site_cursor INTO siteId;
                    WHILE done = 0 DO
                        IF NOT EXISTS (
                            SELECT 1 FROM ns_component
                            WHERE site_id = siteId AND component_code = 'home'
                        ) THEN
                            INSERT INTO ns_component(site_id, component_code)
                            VALUES (siteId, 'home');
                        END IF;
                        FETCH site_cursor INTO siteId;
                    END WHILE;
                    CLOSE site_cursor;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-association",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("EXIT WHEN site_cursor%NOTFOUND;")
                .contains("INSERT INTO ns_component(site_id, component_code)")
                .doesNotContain("DECLARE CONTINUE HANDLER")
                .doesNotContain("WHILE done = 0");
    }

    @Test
    void classifiesUpsertAsOriginalKeyConflictWhenInsertOmitsEveryKnownUniqueKey() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("upsert.sql"), """
                CREATE TABLE `ns_base_settings` (
                    `id` BIGINT NOT NULL AUTO_INCREMENT,
                    `siteId` BIGINT NOT NULL,
                    `cfgGroup` VARCHAR(64) NOT NULL,
                    `cfgValue` VARCHAR(255),
                    PRIMARY KEY (`id`),
                    KEY `idx_site_group` (`siteId`, `cfgGroup`)
                );

                INSERT INTO `ns_base_settings` (`siteId`, `cfgGroup`, `cfgValue`)
                VALUES (1, 'system', 'enabled')
                ON DUPLICATE KEY UPDATE `cfgValue` = VALUES(`cfgValue`);
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-association",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("原始 SQL/键元数据冲突")
                        .contains("`ns_base_settings`")
                        .contains("不包含任何完整冲突键")
                        .contains("不能猜测 keyColumns"));
        assertThat(Files.readString(sqlRootOut.resolve("upsert.sql")))
                .contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void classifiesProcedureUpsertAsOriginalKeyConflictWhenOnlyMatchingIndexIsNonUnique() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(tempDir.resolve("sql/00_Create_Association.sql"), """
                CREATE TABLE IF NOT EXISTS `ns_base_settings` (
                    `id` BIGINT NOT NULL AUTO_INCREMENT,
                    `siteId` BIGINT NOT NULL,
                    `cfgGroup` VARCHAR(64) NOT NULL,
                    `cfgValue` VARCHAR(255),
                    PRIMARY KEY (`id`),
                    KEY `index_site_id` (`siteId`, `cfgGroup`)
                );
                """);
        write(sqlRoot.resolve("20260618.sql"), """
                DELIMITER $$
                CREATE PROCEDURE init_site_information(IN targetSiteId BIGINT)
                BEGIN
                    INSERT INTO ns_base_settings
                        (cfgValue, cfgGroup, siteId)
                    VALUES
                        ('enabled', 'COMPANY_USER_EXCEL_MODEL', targetSiteId)
                    ON DUPLICATE KEY UPDATE cfgValue = VALUES(cfgValue);
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-association",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("原始 SQL/键元数据冲突")
                        .contains("`ns_base_settings`")
                        .contains("不包含任何完整冲突键"));
        assertThat(Files.readString(sqlRootOut.resolve("20260618.sql")))
                .contains("ON DUPLICATE KEY UPDATE cfgValue = VALUES(cfgValue)");
    }

    @Test
    void addsColumnListForInsertValuesWhenTableDefinitionIsKnown() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                CREATE TABLE IF NOT EXISTS `ns_equip_help_topic` (
                    `help_topic_id` int(10) UNSIGNED NOT NULL,
                    PRIMARY KEY (`help_topic_id`) USING BTREE
                ) ENGINE = InnoDB;

                DELIMITER $$
                CREATE PROCEDURE `insert_Init_ns_equip_help_topic` ()
                BEGIN
                    SET @i:=0;
                    WHILE @i<2000 DO
                        insert into `ns_equip_help_topic` VALUES(@i);
                        SET @i:=@i+1;
                    END WHILE;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-equip",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("insert into `ns_equip_help_topic` (help_topic_id) VALUES(dm_i);")
                .doesNotContain("insert into `ns_equip_help_topic` VALUES(dm_i);");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_INSERT_VALUES_COLUMN_LIST_RULE));
    }

    @Test
    void omitsNullAutoIncrementColumnForInsertValuesWhenTableDefinitionIsKnown() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE TABLE IF NOT EXISTS `sample_canal_config_item` (
                    `id` INT NOT NULL AUTO_INCREMENT,
                    `sourceTableName` VARCHAR(255) NOT NULL,
                    `sourceDatabase` VARCHAR(255) NOT NULL,
                    PRIMARY KEY (`id`)
                ) ENGINE = InnoDB;

                DELIMITER $$
                CREATE PROCEDURE addAll_sample_canal_config_item()
                BEGIN
                    INSERT INTO `sample_canal_config_item` VALUES (NULL, 'sample_table', 'Sample');
                    SET @configId = LAST_INSERT_ID();
                END$$
                DELIMITER ;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("INSERT INTO `sample_canal_config_item` (sourceTableName, sourceDatabase) VALUES ('sample_table', 'Sample');")
                .doesNotContain("INSERT INTO `sample_canal_config_item` (id, sourceTableName, sourceDatabase)");
        assertThat(converted.report().files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_INSERT_NULL_IDENTITY_VALUES_COLUMN_LIST_RULE));
    }

    @Test
    void omitsNullAutoIncrementColumnAfterScriptAlterTableAddColumn() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE TABLE sample_canal_config_field (
                    id INT NOT NULL AUTO_INCREMENT,
                    configId INT NOT NULL,
                    fieldName VARCHAR(255) NOT NULL,
                    PRIMARY KEY (id)
                );

                DELIMITER $$
                CREATE PROCEDURE add_sample_canal_config_field_column()
                BEGIN
                    ALTER TABLE sample_canal_config_field ADD COLUMN deleteFlag TINYINT DEFAULT 0;
                END$$
                DELIMITER ;

                DELIMITER $$
                CREATE PROCEDURE add_sample_canal_config_field()
                BEGIN
                    INSERT INTO sample_canal_config_field VALUES (NULL, 100, 'sampleName', 0);
                END$$
                DELIMITER ;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("INSERT INTO sample_canal_config_field (configId, fieldName, deleteFlag) VALUES (100, 'sampleName', 0);")
                .doesNotContain("sample_canal_config_field (id, configId, fieldName, deleteFlag)")
                .doesNotContain("INSERT INTO sample_canal_config_field VALUES (NULL");
        assertThat(converted.report().files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_INSERT_NULL_IDENTITY_VALUES_COLUMN_LIST_RULE));
    }

    @Test
    void omitsDefaultIdentityColumnForMultiRowInsertValues() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE TABLE sample_identity (
                    id BIGINT IDENTITY(1,1),
                    code VARCHAR(32),
                    name VARCHAR(64)
                );

                INSERT INTO sample_identity VALUES (DEFAULT, 'A', 'Alpha'), (default, 'B', 'Beta');
                """);

        assertThat(converted.sql())
                .contains("INSERT INTO sample_identity (code, name) VALUES ('A', 'Alpha'), ('B', 'Beta');")
                .doesNotContain("id, code, name");
        assertThat(converted.report().files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_INSERT_NULL_IDENTITY_VALUES_COLUMN_LIST_RULE));
    }

    @Test
    void wrapsExplicitIdentityValuesWithIdentityInsert() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE TABLE sample_identity (
                    id BIGINT IDENTITY(1,1),
                    code VARCHAR(32)
                );

                INSERT INTO sample_identity VALUES (7, 'A');
                """);

        assertThat(converted.sql())
                .contains("""
                        SET IDENTITY_INSERT sample_identity ON;
                        INSERT INTO sample_identity (id, code) VALUES (7, 'A');
                        SET IDENTITY_INSERT sample_identity OFF;
                        """)
                .doesNotContain("INSERT INTO sample_identity VALUES (7, 'A');")
                .doesNotContain("sample_identity (code) VALUES");
        assertThat(converted.report().files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_INSERT_EXPLICIT_IDENTITY_VALUES_COLUMN_LIST_RULE)
                        .doesNotContain(SqlScriptMigrator.MYSQL_INSERT_NULL_IDENTITY_VALUES_COLUMN_LIST_RULE)
                        .doesNotContain(SqlScriptMigrator.MYSQL_INSERT_VALUES_COLUMN_LIST_RULE));
    }

    @Test
    void wrapsExplicitAutoIncrementInsertAfterScriptAlterTableAddColumn() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE TABLE sample_seed_item (
                    id INT NOT NULL AUTO_INCREMENT,
                    code VARCHAR(32) NOT NULL,
                    name VARCHAR(64) NOT NULL,
                    PRIMARY KEY (id)
                );

                ALTER TABLE sample_seed_item ADD COLUMN deleteFlag TINYINT DEFAULT 0;

                DELIMITER $$
                CREATE PROCEDURE add_sample_seed_item()
                BEGIN
                    INSERT INTO sample_seed_item VALUES (2, 'A', 'Alpha');
                END$$
                DELIMITER ;
                """);

        assertThat(converted.sql())
                .contains("""
                        SET IDENTITY_INSERT sample_seed_item ON;
                        INSERT INTO sample_seed_item (id, code, name) VALUES (2, 'A', 'Alpha');
                        SET IDENTITY_INSERT sample_seed_item OFF;
                        """)
                .doesNotContain("INSERT INTO sample_seed_item VALUES (2, 'A', 'Alpha');")
                .doesNotContain("deleteFlag) VALUES");
        assertThat(converted.report().files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_INSERT_EXPLICIT_IDENTITY_VALUES_COLUMN_LIST_RULE));
    }

    @Test
    void keepsNullFirstValueWhenFirstColumnIsNotIdentity() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE TABLE sample_plain (
                    id BIGINT NOT NULL,
                    code VARCHAR(32)
                );

                INSERT INTO sample_plain VALUES (NULL, 'A');
                """);

        assertThat(converted.sql())
                .contains("INSERT INTO sample_plain (id, code) VALUES (NULL, 'A');");
        assertThat(converted.report().files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_INSERT_VALUES_COLUMN_LIST_RULE)
                        .doesNotContain(SqlScriptMigrator.MYSQL_INSERT_NULL_IDENTITY_VALUES_COLUMN_LIST_RULE));
    }

    @Test
    void keepsExistingInsertColumnListForIdentityTable() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE TABLE sample_identity (
                    id BIGINT IDENTITY(1,1),
                    code VARCHAR(32)
                );

                INSERT INTO sample_identity (code) VALUES ('A');
                """);

        assertThat(converted.sql())
                .contains("INSERT INTO sample_identity (code) VALUES ('A');")
                .doesNotContain("sample_identity (id, code)");
        assertThat(converted.report().files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .doesNotContain(SqlScriptMigrator.MYSQL_INSERT_NULL_IDENTITY_VALUES_COLUMN_LIST_RULE)
                        .doesNotContain(SqlScriptMigrator.MYSQL_INSERT_VALUES_COLUMN_LIST_RULE));
    }

    @Test
    void placesProcedureUserVariableDeclarationsBeforeCursorDeclarations() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE updateQualityData()
                BEGIN
                    DECLARE etrId BIGINT (20);
                    DECLARE STOP INT DEFAULT 0;
                    DECLARE ENTERPRISE CURSOR FOR SELECT DISTINCT enterpriseID FROM ns_quality_param_value union SELECT @enterpriseID;
                    DECLARE CONTINUE HANDLER FOR SQLSTATE '02000'SET STOP=1;
                    OPEN ENTERPRISE;
                    FETCH ENTERPRISE INTO etrId;
                    WHILE STOP<> 1 DO
                        INSERT INTO ns_quality_param_value(enterpriseID, createUserID) VALUES(etrId, @adminUserID);
                        FETCH ENTERPRISE INTO etrId;
                    END WHILE;
                    CLOSE ENTERPRISE;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-quality",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .containsSubsequence(
                        "dm_enterpriseID VARCHAR(4000);",
                        "dm_adminUserID VARCHAR(4000);",
                        "ENTERPRISE CURSOR FOR SELECT DISTINCT enterpriseID FROM ns_quality_param_value union SELECT dm_enterpriseID;"
                )
                .contains("EXIT WHEN ENTERPRISE%NOTFOUND;")
                .doesNotContain("DECLARE CONTINUE HANDLER");
    }

    @Test
    void convertsMysqlNotFoundHandlerLabelLoopsToDamengCursorLoops() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE init_equip_records()
                BEGIN
                    DECLARE etrId_outer BIGINT;
                    DECLARE recId_inner BIGINT;
                    DECLARE done INT DEFAULT FALSE;
                    DECLARE edone INT DEFAULT FALSE;
                    DECLARE _outerForEach CURSOR FOR SELECT DISTINCT ENTERPRISE_ID FROM `sample-system`.ns_system_organization;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
                    OPEN _outerForEach;
                    read_loop:
                    LOOP
                        FETCH _outerForEach INTO etrId_outer;
                        IF done = TRUE THEN
                            LEAVE read_loop;
                        END IF;
                        BEGIN
                            DECLARE _innerForEach CURSOR FOR SELECT id FROM ns_equip_custom_record WHERE enterprise_id = etrId_outer;
                            DECLARE CONTINUE HANDLER FOR NOT FOUND SET edone = TRUE;
                            OPEN _innerForEach;
                            inner_loop:
                            LOOP
                                FETCH _innerForEach INTO recId_inner;
                                IF edone=1 THEN
                                    LEAVE inner_loop;
                                END IF;
                                UPDATE ns_equip_custom_record SET enterprise_id = etrId_outer WHERE id = recId_inner;
                            END LOOP;
                            CLOSE _innerForEach;
                            SET edone = FALSE;
                        END;
                    END LOOP;
                    CLOSE _outerForEach;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-equip",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE init_equip_records() AS")
                .contains("_outerForEach CURSOR FOR SELECT DISTINCT ENTERPRISE_ID FROM `sample-system`.ns_system_organization;")
                .contains("EXIT WHEN _outerForEach%NOTFOUND;")
                .contains("DECLARE\n")
                .contains("_innerForEach CURSOR FOR SELECT id FROM ns_equip_custom_record WHERE enterprise_id = etrId_outer;")
                .contains("BEGIN")
                .contains("OPEN _innerForEach;")
                .contains("EXIT WHEN _innerForEach%NOTFOUND;")
                .doesNotContain("DECLARE CONTINUE HANDLER")
                .doesNotContain("done INT")
                .doesNotContain("edone INT")
                .doesNotContain("LEAVE read_loop")
                .doesNotContain("LEAVE inner_loop")
                .doesNotContain("SET edone");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_CURSOR_HANDLER_TO_LOOP_RULE));
    }

    @Test
    void convertsMysqlNotFoundHandlerLabelLoopWithElseBodyToDamengCursorLoop() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE updateCustomRecordAllData()
                BEGIN
                    DECLARE etrId_outer BIGINT (20);
                    DECLARE recId_inner BIGINT (20);
                    DECLARE done INT DEFAULT FALSE;
                    DECLARE edone INT DEFAULT FALSE;
                    DECLARE _outerForEach CURSOR FOR SELECT DISTINCT ENTERPRISE_ID FROM `sample-system`.ns_system_organization;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
                    OPEN _outerForEach;
                    read_loop:
                    LOOP
                        FETCH _outerForEach INTO etrId_outer;
                        IF done THEN
                            LEAVE read_loop;
                        END IF;
                        BEGIN
                            DECLARE _innerForEach CURSOR FOR SELECT id FROM ns_equip_custom_record WHERE enterpriseID = 0;
                            DECLARE CONTINUE HANDLER FOR NOT FOUND SET edone = 1;
                            OPEN _innerForEach;
                            inner_loop:
                            LOOP
                                FETCH _innerForEach INTO recId_inner;
                                IF edone THEN
                                    LEAVE inner_loop;
                                ELSE
                                    SELECT customerTypeid INTO @typeId FROM ns_equip_custom_record WHERE id = recId_inner;
                                    SELECT customerCode INTO @typeCode FROM ns_equip_custom_record WHERE id = recId_inner;
                                    IF NOT EXISTS (
                                        SELECT 1 FROM ns_equip_custom_record
                                        WHERE customerTypeid = @typeId AND customerCode = @typeCode and enterpriseID = etrId_outer
                                    ) THEN
                                        INSERT INTO ns_equip_custom_record(enterpriseID, customerTypeid, customerCode)
                                        SELECT etrId_outer, customerTypeid, customerCode
                                        FROM ns_equip_custom_record
                                        WHERE customerTypeid = @typeId AND customerCode = @typeCode AND enterpriseID = 0 LIMIT 1;
                                    END IF;
                                END IF;
                            END LOOP;
                            CLOSE _innerForEach;
                            SET edone = FALSE;
                        END;
                    END LOOP;
                    CLOSE _outerForEach;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-equip",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE updateCustomRecordAllData() AS")
                .contains("dm_typeId VARCHAR(4000);")
                .contains("dm_typeCode VARCHAR(4000);")
                .contains("EXIT WHEN _outerForEach%NOTFOUND;")
                .contains("EXIT WHEN _innerForEach%NOTFOUND;")
                .contains("SELECT customerTypeid INTO dm_typeId")
                .contains("SELECT customerCode INTO dm_typeCode")
                .contains("WHERE customerTypeid = dm_typeId AND customerCode = dm_typeCode")
                .doesNotContain("DECLARE CONTINUE HANDLER")
                .doesNotContain("IF done THEN")
                .doesNotContain("IF edone THEN")
                .doesNotContain("LEAVE read_loop")
                .doesNotContain("LEAVE inner_loop")
                .doesNotContain("SET edone")
                .doesNotContain("@typeId")
                .doesNotContain("@typeCode");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.MYSQL_PROCEDURE_CURSOR_HANDLER_TO_LOOP_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL_RULE
                        ));
    }

    @Test
    void leavesCursorHandlerForManualReviewWhenFlagHasBusinessUse() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE unsafe_cursor_flag()
                BEGIN
                    DECLARE recId BIGINT;
                    DECLARE done INT DEFAULT FALSE;
                    DECLARE c1 CURSOR FOR SELECT id FROM ns_demo;
                    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;
                    OPEN c1;
                    read_loop:
                    LOOP
                        FETCH c1 INTO recId;
                        IF done THEN
                            LEAVE read_loop;
                        END IF;
                        INSERT INTO ns_demo_log(id) VALUES (recId);
                    END LOOP;
                    CLOSE c1;
                    IF done THEN
                        INSERT INTO ns_demo_log(id) VALUES (-1);
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-equip",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("HANDLER"));
    }

    @Test
    void convertsMysqlDeleteAliasStarInsideProcedure() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE cleanup_role_perm()
                BEGIN
                    delete x.* from ns_core_role_perm x USE index(ns_core_role_perm_idx)
                    where x.perid in (select perid from ns_core_permission where funcid = 'demo');
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("DELETE FROM ns_core_role_perm x")
                .contains("where x.perid in")
                .doesNotContain("delete x.* from")
                .doesNotContain("USE index");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_DELETE_ALIAS_STAR_RULE));
    }

    @Test
    void convertsSimpleMysqlFunctionUserVariablesToDamengLocals() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("function.sql"), """
                DELIMITER $$
                CREATE FUNCTION get_number (param varchar(50))
                RETURNS varchar(30)
                READS SQL DATA
                BEGIN
                    SET @temp_length=0;
                    SET @temp_str='';
                    SET @temp_length=CHAR_LENGTH(param);
                    WHILE @temp_length > 0 DO
                        IF (ASCII(mid(param,@temp_length,1))>47 and ASCII(mid(param,@temp_length,1))<58 )THEN
                            SET @temp_str = concat(@temp_str,mid(param,@temp_length,1));
                        END IF;
                        SET @temp_length = @temp_length - 1;
                    END WHILE;
                    RETURN REVERSE(@temp_str);
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-warehouse",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("function.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE FUNCTION get_number (param varchar(50))")
                .contains("RETURN varchar(30)")
                .contains("dm_temp_length BIGINT;")
                .contains("dm_temp_str VARCHAR(4000);")
                .contains("dm_temp_length := 0;")
                .contains("dm_temp_str := '';")
                .contains("WHILE dm_temp_length > 0 LOOP")
                .contains("END LOOP;")
                .contains("RETURN REVERSE(dm_temp_str);")
                .doesNotContain("RETURNS")
                .doesNotContain("READS SQL DATA")
                .doesNotContain("@temp");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.MYSQL_CREATE_FUNCTION_TO_DM_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_LOCAL_SET_TO_ASSIGNMENT_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_CONTROL_FLOW_TO_DM_RULE
                        ));
    }

    @Test
    void leavesComplexMysqlHandlersForManualReview() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc()
                BEGIN
                    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION SET @failed = 1;
                    INSERT INTO demo(id) VALUES (1);
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-hr",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("HANDLER"));
    }

    @Test
    void removesLineCommentsInsideCallArgumentsOnly() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                -- keep this leading comment
                call addOrUpdate_menu_leaf('menu-id', -- PPPP_jeCoreMenuId
                    '菜单名称',
                    '-- keep string literal',
                    /* keep block comment */ 'icon-id');
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("-- keep this leading comment")
                .contains("'-- keep string literal'")
                .contains("/* keep block comment */ 'icon-id'")
                .doesNotContain("PPPP_jeCoreMenuId");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_CALL_ARGUMENT_LINE_COMMENT_REMOVAL_RULE));
    }

    @Test
    void convertsKnownLongClobCallArgumentsToAnonymousBlock() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        String longJson = "{\"payload\":\"" + "A".repeat(3500) + "\",\"resourcecolumnValue\":\"\\\\\\\"1\\\\\\\"\"}";
        write(sqlRoot.resolve("procedure.sql"), """
                CALL addAll_ns_report_management_20240314(
                    '报表', '', '8020', 'demo.ureport.xml', '0', 'menu-id', 5,
                    '%s',
                    'short content');
                """.formatted(longJson));
        RecordingValidator validator = new RecordingValidator();

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("DECLARE")
                .contains("dm_adapter_clob_arg_8 CLOB;")
                .contains("dm_adapter_clob_arg_8 := TO_CLOB('{\"payload\":\"")
                .contains("dm_adapter_clob_arg_8 := dm_adapter_clob_arg_8 || TO_CLOB('")
                .contains("\\\"1\\\"")
                .doesNotContain("\\\\\\\"1\\\\\\\"")
                .contains("CALL addAll_ns_report_management_20240314('报表', '', '8020', 'demo.ureport.xml', '0', 'menu-id', 5, dm_adapter_clob_arg_8, 'short content');")
                .contains("END;\n/");
        assertThat(validator.files)
                .singleElement()
                .satisfies(file -> assertThat(file.statements()).hasSize(1));
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.DM_LONG_CLOB_CALL_ARGUMENT_BLOCK_RULE));
    }

    @Test
    void doesNotConvertUnknownLongCallStringArgumentsToClobBlock() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        String longJson = "[{\"id\":\"" + "A".repeat(3500) + "\"}]";
        write(sqlRoot.resolve("procedure.sql"), """
                CALL batch_insert_ns_core_resourcecolumn('%s');
                """.formatted(longJson));

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(converted)
                .doesNotContain("DECLARE")
                .doesNotContain("TO_CLOB(")
                .contains(longJson);
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .doesNotContain(SqlScriptMigrator.DM_LONG_CLOB_CALL_ARGUMENT_BLOCK_RULE));
    }

    @Test
    void convertsNestedMysqlBeginLabelsInsideProcedure() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE report_procedure(IN salaryMonth DATE)
                BEGIN
                  report_procedure: BEGIN
                    IF salaryMonth IS NULL THEN
                        LEAVE report_procedure;
                    END IF;
                  END report_procedure;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-hr",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE report_procedure(salaryMonth IN DATE) AS")
                .contains("RETURN;")
                .doesNotContain("report_procedure: BEGIN")
                .doesNotContain("LEAVE report_procedure")
                .doesNotContain("END report_procedure");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_LOCAL_LABEL_TO_RETURN_RULE));
    }

    @Test
    void convertsClobEmptyStringChecksInsideProcedureToLobLengthChecks() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE update_report(IN payload LONGTEXT, IN title VARCHAR(100))
                BEGIN
                    DECLARE localContent TEXT DEFAULT '';
                    IF payload <> '' THEN
                        SET localContent = payload;
                    END IF;
                    IF localContent != '' THEN
                        SET localContent = CONCAT(localContent, 'x');
                    END IF;
                    IF title <> '' THEN
                        SET title = CONCAT(title, 'x');
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-hr",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("payload IS NOT NULL AND DBMS_LOB.GETLENGTH(payload) > 0")
                .contains("localContent IS NOT NULL AND DBMS_LOB.GETLENGTH(localContent) > 0")
                .contains("dm_title VARCHAR(100);")
                .contains("dm_title := title;")
                .contains("IF dm_title <> '' THEN")
                .contains("localContent := payload;")
                .contains("localContent := CONCAT(localContent, 'x');")
                .contains("dm_title := CONCAT(dm_title, 'x');");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.DM_PROCEDURE_CLOB_EMPTY_STRING_CHECK_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_ASSIGNED_IN_PARAM_TO_LOCAL_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_LOCAL_SET_TO_ASSIGNMENT_RULE
                        ));
    }

    @Test
    void convertsMysqlProcedureAddUniqueIndexDdlToCreateUniqueIndex() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE add_unique_index()
                BEGIN
                    IF NOT EXISTS (
                        SELECT INDEX_NAME FROM information_schema.STATISTICS
                        WHERE table_schema = database()
                          AND table_name = 'sample_table'
                          AND index_name = 'uk_user_date'
                    ) THEN
                        ALTER TABLE sample_table ADD UNIQUE INDEX uk_user_date (userId, workDate);
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-hr",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX sample_table_uk_user_date ON sample_table (userId, workDate)'")
                .doesNotContain("ADD UNIQUE INDEX");
    }

    @Test
    void convertsMysqlProcedureAddIndexUsingBtreeDdlToCreateIndex() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE add_card_id_index()
                BEGIN
                    IF NOT EXISTS (
                        SELECT INDEX_NAME FROM information_schema.STATISTICS
                        WHERE table_schema = database()
                          AND table_name = 'owner_car_month_card_info'
                          AND index_name = 'idx_card_id'
                    ) THEN
                        ALTER TABLE `owner_car_month_card_info` ADD INDEX `idx_card_id` (`card_id`) USING BTREE;
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-owner",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("FROM ALL_IND_COLUMNS")
                .contains("GROUP BY INDEX_NAME")
                .contains("UPPER(COLUMN_NAME) = UPPER('card_id')")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX owner_car_month_card_info_idx_card_id ON `owner_car_month_card_info` (`card_id`)'")
                .doesNotContain("INDEX_NAME = 'owner_car_month_card_info_idx_card_id'")
                .doesNotContain("ADD INDEX")
                .doesNotContainIgnoringCase("USING BTREE");
    }

    @Test
    void convertsMysqlPrefixIndexToDamengFunctionIndex() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE add_prefix_index()
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = database()
                          AND table_name = 'sample_dictionaryitem'
                          AND index_name = 'idx_item_code'
                    ) THEN
                        ALTER TABLE sample_dictionaryitem
                            ADD INDEX idx_item_code (dictionary_id, item_code(254));
                    END IF;
                END$$
                DELIMITER ;
                CALL add_prefix_index();
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewItems()).isEmpty();
        assertThat(report.validationSuccessCount()).isEqualTo(2);
        assertThat(report.validationFailureCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE add_prefix_index() AS")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX sample_dictionaryitem_idx_item_code"
                        + " ON sample_dictionaryitem (dictionary_id,"
                        + " CAST(SUBSTR(item_code, 1, 254) AS VARCHAR(254)))'")
                .doesNotContain("ADD INDEX")
                .doesNotContain("item_code(254)");
    }

    @Test
    void convertsMysqlPrefixIndexWhenColumnTypeGuardLimitsItToVarcharColumns() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE add_prefix_index()
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM information_schema.statistics
                        WHERE table_schema = database()
                          AND table_name = 'sample_dictionaryitem'
                          AND index_name = 'idx_item_code'
                    ) AND EXISTS (
                        SELECT 1 FROM information_schema.columns
                        WHERE table_schema = database()
                          AND table_name = 'sample_dictionaryitem'
                          AND column_name = 'item_code'
                          AND data_type IN ('char', 'varchar')
                    ) THEN
                        ALTER TABLE sample_dictionaryitem
                            ADD INDEX idx_item_code (dictionary_id, item_code(254));
                    END IF;
                END$$
                DELIMITER ;
                CALL add_prefix_index();
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewItems()).isEmpty();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE add_prefix_index() AS")
                .contains("dm_adapter_schema VARCHAR(128) := SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID);")
                .contains("dm_adapter_exists INT;")
                .contains("dm_adapter_exists_2 INT;")
                .contains("ALL_IND_COLUMNS")
                .contains("ALL_TAB_COLUMNS")
                .contains("UPPER(DATA_TYPE) IN ('CHAR', 'VARCHAR')")
                .contains("IF dm_adapter_exists = 0 AND dm_adapter_exists_2 > 0 THEN")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX sample_dictionaryitem_idx_item_code"
                        + " ON sample_dictionaryitem (dictionary_id,"
                        + " CAST(SUBSTR(item_code, 1, 254) AS VARCHAR(254)))'")
                .doesNotContain("information_schema")
                .doesNotContain("item_code(254)");
    }

    @Test
    void convertsSimpleMysqlDateEndTriggersToDamengSyntax() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("trigger.sql"), """
                DROP TRIGGER IF EXISTS trg_card_end_date_insert;
                DELIMITER //
                CREATE TRIGGER trg_card_end_date_insert
                    BEFORE INSERT ON owner_car_month_card_info
                    FOR EACH ROW
                BEGIN
                    IF NEW.card_end_date IS NOT NULL THEN
                        SET NEW.card_end_date = CONCAT(DATE(NEW.card_end_date), ' 23:59:59');
                    END IF;
                END //
                DELIMITER ;

                DROP TRIGGER IF EXISTS trg_card_end_date_update;
                DELIMITER //
                CREATE TRIGGER trg_card_end_date_update
                    BEFORE UPDATE ON owner_car_month_card_info
                    FOR EACH ROW
                BEGIN
                    IF NEW.card_end_date IS NOT NULL THEN
                        SET NEW.card_end_date = CONCAT(DATE(NEW.card_end_date), ' 23:59:59');
                    END IF;
                END //
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-owner",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("trigger.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE TRIGGER trg_card_end_date_insert")
                .contains("CREATE OR REPLACE TRIGGER trg_card_end_date_update")
                .contains("IF :NEW.card_end_date IS NOT NULL THEN")
                .contains(":NEW.card_end_date := TO_TIMESTAMP(TO_CHAR(:NEW.card_end_date, 'YYYY-MM-DD') || ' 23:59:59', 'YYYY-MM-DD HH24:MI:SS');")
                .contains("\n/")
                .doesNotContain("SET NEW.card_end_date")
                .doesNotContain("CONCAT(DATE(NEW.card_end_date)");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SIMPLE_DATE_END_TRIGGER_TO_DM_RULE));
    }

    @Test
    void convertsMysqlProcedureDropAndAddUniqueIndexAlterToSeparateDamengIndexDdl() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE fix_unique_index()
                BEGIN
                    IF EXISTS (
                        SELECT 1
                          FROM information_schema.statistics s
                         WHERE s.TABLE_SCHEMA = DATABASE()
                           AND s.TABLE_NAME = 'sample_table'
                           AND s.INDEX_NAME = 'uk_user_date'
                           AND s.NON_UNIQUE = 0
                         GROUP BY s.TABLE_SCHEMA, s.TABLE_NAME, s.INDEX_NAME
                        HAVING GROUP_CONCAT(s.COLUMN_NAME ORDER BY s.SEQ_IN_INDEX SEPARATOR ',') = 'user_id'
                    ) THEN
                        ALTER TABLE sample_table
                            DROP INDEX uk_user_date,
                            ADD UNIQUE KEY uk_user_date (user_id, work_date);
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("FROM ALL_INDEXES I")
                .contains("JOIN ALL_IND_COLUMNS C")
                .contains("C.COLUMN_POSITION AS SEQ_IN_INDEX")
                .contains("CASE WHEN I.UNIQUENESS = 'UNIQUE' THEN 0 ELSE 1 END AS NON_UNIQUE")
                .contains("HAVING LISTAGG(s.COLUMN_NAME, ',') WITHIN GROUP (ORDER BY s.SEQ_IN_INDEX) = 'user_id'")
                .contains("EXECUTE IMMEDIATE 'DROP INDEX sample_table_uk_user_date';")
                .contains("EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX sample_table_uk_user_date ON sample_table (user_id, work_date)'")
                .doesNotContain("DROP INDEX uk_user_date,")
                .doesNotContain("ADD UNIQUE KEY");
    }

    @Test
    void renamesProcedureWhenProcedureNameConflictsWithTableReference() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                DROP PROCEDURE IF EXISTS sample_dictionary_item$$
                CREATE PROCEDURE sample_dictionary_item()
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM sample_dictionary_item WHERE code = 'nation'
                    ) THEN
                        INSERT INTO sample_dictionary_item(code, name) VALUES ('nation', 'Nation');
                    END IF;
                END$$
                CALL sample_dictionary_item()$$
                DROP PROCEDURE IF EXISTS sample_dictionary_item$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("DECLARE")
                .contains("FROM sample_dictionary_item")
                .contains("INSERT INTO sample_dictionary_item")
                .doesNotContain("CREATE OR REPLACE PROCEDURE")
                .doesNotContain("CALL dm_adapter_proc_sample_dictionary_item")
                .doesNotContain("DROP PROCEDURE IF EXISTS");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.MYSQL_PROCEDURE_OBJECT_NAME_CONFLICT_RENAME_RULE,
                                SqlScriptMigrator.DM_TRANSIENT_PROCEDURE_TO_ANONYMOUS_BLOCK_RULE
                        ));
    }

    @Test
    void narrowsSystemMetadataScalarIdSubqueriesToDeterministicValue() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE ns_salary_query_UPDATE_SQL()
                BEGIN
                    INSERT INTO ns_core_form(resource_id, func_id)
                    SELECT (SELECT id from ns_core_resourcetable where JE_CORE_RESOURCETABLE_ID='r'
                                AND (enterprise_id, organization_id) in ((t.enterprise_id,t.organization_id))),
                           (SELECT `id` from `ns_core_funcinfo` where JE_CORE_FUNCINFO_ID='f'
                                AND (enterprise_id, organization_id) in ((t.enterprise_id,t.organization_id)))
                    FROM tmp_enterprise_orgid t
                    WHERE t.id IN (SELECT id from ns_core_funcinfo where enabled = 1);
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("(SELECT min(id) from ns_core_resourcetable where JE_CORE_RESOURCETABLE_ID='r'")
                .contains("(SELECT min(id) from `ns_core_funcinfo` where JE_CORE_FUNCINFO_ID='f'")
                .contains("IN (SELECT id from ns_core_funcinfo where enabled = 1)");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SYSTEM_METADATA_SCALAR_ID_TO_MIN_RULE));
    }

    @Test
    void convertsMysqlDynamicPrepareSignalAndMysqlNumericDdlTypesInsideProcedure() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc(IN p_payload longtext, OUT p_result mediumtext)
                BEGIN
                    DECLARE current_length INT DEFAULT 0;
                    DECLARE note TEXT;
                    SET SESSION group_concat_max_len=102400;
                    SET @sql = NULL;
                    IF current_length IS NULL THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'missing column';
                    END IF;
                    SET @sql = CONCAT('select 1');
                    PREPARE stmt FROM @sql;
                    EXECUTE stmt;
                    DEALLOCATE PREPARE stmt;
                    ALTER TABLE ns_workclass_set MODIFY `day` double(18, 4) NULL DEFAULT NULL;
                    SET p_result = p_payload;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-hr",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE demo_proc(p_payload IN CLOB, p_result OUT CLOB) AS")
                .contains("current_length INT := 0;")
                .contains("note CLOB;")
                .contains("NULL;")
                .contains("dm_sql VARCHAR(4000);")
                .contains("dm_sql := NULL;")
                .contains("dm_sql := 'select 1';")
                .contains("RAISE_APPLICATION_ERROR(-20000, 'missing column');")
                .contains("EXECUTE IMMEDIATE dm_sql;")
                .contains("p_result := p_payload;")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE ns_workclass_set MODIFY `day` DECIMAL(18, 4) NULL DEFAULT NULL';")
                .doesNotContain("group_concat_max_len")
                .doesNotContain("PREPARE stmt")
                .doesNotContain("SIGNAL SQLSTATE")
                .doesNotContain("@sql");
    }

    @Test
    void convertsProcedureDdlWithLocalVariablesToDynamicSqlExpression() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc(IN p_code varchar(64))
                label_exit:BEGIN
                    DECLARE enterpriseId bigint(20);
                    DECLARE `stop` int DEFAULT 0;
                    DECLARE enterprise_cursor CURSOR FOR SELECT 1;
                    DECLARE CONTINUE HANDLER FOR SQLSTATE '02000' SET `stop`=1;
                    SET @resource_code = p_code;
                    CREATE TEMPORARY TABLE IF NOT EXISTS tmp_demo
                    SELECT id
                    FROM ns_core_resourcebutton
                    WHERE RESOURCEBUTTON_CODE = @resource_code
                      AND RESOURCEBUTTON_FUNCINFO_ID = p_code;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("enterpriseId bigint;")
                .contains("stop int := 0;")
                .doesNotContain("DECLARE enterprise_cursor CURSOR")
                .doesNotContain("DECLARE CONTINUE HANDLER")
                .contains("dm_resource_code VARCHAR(4000);")
                .contains("dm_resource_code := p_code;")
                .contains("INSERT INTO tmp_demo (id) SELECT id")
                .contains("RESOURCEBUTTON_CODE = dm_resource_code")
                .contains("RESOURCEBUTTON_FUNCINFO_ID = p_code")
                .doesNotContain("EXECUTE IMMEDIATE 'CREATE TABLE tmp_demo");
    }

    @Test
    void castsJsonTextAssignmentWhenLocalProcedureVariableNeedsNativeType() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("json-numeric-variable.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_json_numeric(IN payload JSON)
                BEGIN
                    DECLARE v_order int;
                    DECLARE v_timestamp timestamp;
                    DECLARE v_date date;
                    DECLARE v_name varchar(255);
                    SET v_order = JSON_UNQUOTE(JSON_EXTRACT(payload, '$[0].syOrderindex'));
                    SET v_timestamp = JSON_UNQUOTE(JSON_EXTRACT(payload, '$[0].timestamp'));
                    SET v_date = JSON_UNQUOTE(JSON_EXTRACT(payload, '$[0].date'));
                    SET v_name = JSON_UNQUOTE(JSON_EXTRACT(payload, '$[0].name'));
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("json-numeric-variable.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("v_order int;")
                .contains("v_order := TO_NUMBER(NULLIF(NULLIF(TRIM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$[0].syOrderindex')) AS VARCHAR(4000))), ''), 'null'));")
                .contains("v_timestamp timestamp;")
                .contains("v_timestamp := TO_TIMESTAMP(NULLIF(NULLIF(TRIM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$[0].timestamp')) AS VARCHAR(4000))), ''), 'null'));")
                .contains("v_date date;")
                .contains("v_date := TO_DATE(NULLIF(NULLIF(TRIM(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$[0].date')) AS VARCHAR(4000))), ''), 'null'));")
                .contains("v_name varchar(255);")
                .contains("v_name := CAST(JSON_UNQUOTE(JSON_EXTRACT(payload, '$[0].name')) AS VARCHAR(4000));");
    }

    @Test
    void formatsProcedureJsonSetTimestampValuesForDamengJsonSerialization() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("json-timestamp.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_json_timestamp()
                BEGIN
                    DECLARE dm_log_json varchar(4000);
                    SET dm_log_json = JSON_SET(dm_log_json, '$.timestamp', CURRENT_TIMESTAMP(3));
                    SET dm_log_json = JSON_SET(dm_log_json, '$.done', 1, '$.finishedAt', NOW());
                    SET dm_log_json = 'JSON_SET(dm_log_json, ''$.timestamp'', CURRENT_TIMESTAMP(3))';
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("json-timestamp.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("dm_log_json := JSON_SET(dm_log_json, '$.timestamp', TO_CHAR(CURRENT_TIMESTAMP(3), 'YYYY-MM-DD HH24:MI:SS.FF3'));")
                .contains("dm_log_json := JSON_SET(dm_log_json, '$.done', 1, '$.finishedAt', TO_CHAR(NOW(), 'YYYY-MM-DD HH24:MI:SS.FF3'));")
                .contains("dm_log_json := 'JSON_SET(dm_log_json, ''$.timestamp'', CURRENT_TIMESTAMP(3))';");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_JSON_TIMESTAMP_TO_CHAR_RULE));
    }

    @Test
    void convertsMysqlQuoteCallsInsideProcedureToDmLiteralExpression() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("quote.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_quote()
                BEGIN
                    DECLARE v_name varchar(255);
                    DECLARE v_order int;
                    DECLARE dm_sql varchar(4000);
                    SET v_name = 'a';
                    SET v_order = 1;
                    SET dm_sql = CONCAT('insert into t values (', QUOTE(v_name), ',', QUOTE(v_order), ')');
                    SET dm_sql = 'QUOTE(v_name)';
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("quote.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CASE WHEN v_name IS NULL THEN 'NULL' ELSE '''' || REPLACE(CAST(v_name AS VARCHAR(4000)), '''', '''''') || '''' END")
                .contains("CASE WHEN v_order IS NULL THEN 'NULL' ELSE '''' || REPLACE(CAST(v_order AS VARCHAR(4000)), '''', '''''') || '''' END")
                .contains("dm_sql := 'QUOTE(v_name)';")
                .doesNotContain("QUOTE(v_name),")
                .doesNotContain("QUOTE(v_order)");
    }

    @Test
    void convertsMysqlDateAndTimeFunctionsInsideProcedureForDameng() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("date-time.sql"), """
                DELIMITER $$
                CREATE PROCEDURE fix_card_end_date_to_235959()
                BEGIN
                    UPDATE owner_car_month_card_info
                    SET card_end_date = CONCAT(DATE(card_end_date), ' 23:59:59')
                    WHERE card_end_date IS NOT NULL
                      AND TIME(card_end_date) != '23:59:59';
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-owner",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("date-time.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("card_end_date = TO_TIMESTAMP(TO_CHAR(card_end_date, 'YYYY-MM-DD') || ' 23:59:59', 'YYYY-MM-DD HH24:MI:SS')")
                .contains("TO_CHAR(card_end_date, 'HH24:MI:SS') != '23:59:59'")
                .doesNotContain("CONCAT(DATE(card_end_date)")
                .doesNotContain("TIME(card_end_date)");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_DATE_TIME_TO_DM_RULE));
    }

    @Test
    void collapsesMysqlDoubledJsonQuoteEscapesInsideSqlStrings() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc()
                BEGIN
                    CALL batch_insert('[{"resourcecolumnTactics":"[{\\\\"label\\\\":\\\\"V9\\\\"}]"}]');
                    CALL batch_insert_ns_core_resourcecolumn('[
                {\\\"id\\\":\\\"1\\\",\\\"resourcecolumnTactics\\\":\\\"{\\\\\\\"label\\\\\\\":\\\\\\\"金额\\\\\\\"}\\\"}
                ]');
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("[{\\\"label\\\":\\\"V9\\\"}]")
                .contains("{\"id\":\"1\",\"resourcecolumnTactics\":\"{\\\"label\\\":\\\"金额\\\"}\"}")
                .doesNotContain("[{\\\\\\\"label");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_SQL_STRING_JSON_ESCAPE_TO_DM_RULE));
    }

    @Test
    void convertsResourceColumnBatchMergeToInsertOnly() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE batch_insert_ns_core_resourcecolumn(input_json JSON)
                BEGIN
                    MERGE INTO ns_core_resourcecolumn c
                    USING (
                        SELECT 1 AS ENTERPRISE_ID,
                               2 AS ORGANIZATION_ID,
                               'col-1' AS JE_CORE_RESOURCECOLUMN_ID,
                               'demo' AS RESOURCECOLUMN_NAME
                    ) s
                    ON (c.ENTERPRISE_ID = s.ENTERPRISE_ID
                        AND c.ORGANIZATION_ID = s.ORGANIZATION_ID
                        AND c.JE_CORE_RESOURCECOLUMN_ID = s.JE_CORE_RESOURCECOLUMN_ID)
                    WHEN MATCHED THEN UPDATE SET
                        c.RESOURCECOLUMN_NAME = s.RESOURCECOLUMN_NAME,
                        c.sys_time = CURRENT_TIMESTAMP
                    WHEN NOT MATCHED THEN INSERT (
                        ENTERPRISE_ID, ORGANIZATION_ID, JE_CORE_RESOURCECOLUMN_ID, RESOURCECOLUMN_NAME, sys_time
                    ) VALUES (
                        s.ENTERPRISE_ID, s.ORGANIZATION_ID, s.JE_CORE_RESOURCECOLUMN_ID, s.RESOURCECOLUMN_NAME, CURRENT_TIMESTAMP
                    );
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("MERGE INTO ns_core_resourcecolumn c")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .doesNotContain("WHEN MATCHED THEN UPDATE SET")
                .doesNotContain("c.RESOURCECOLUMN_NAME = s.RESOURCECOLUMN_NAME");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.DM_RESOURCECOLUMN_INSERT_ONLY_MERGE_RULE));
    }

    @Test
    void keepsSelectUserVariableAccumulatorForManualReview() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_proc()
                BEGIN
                    SELECT @rn := @rn + 1 AS row_num, id
                    FROM ns_core_role;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("@var"));
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("SELECT @rn := @rn + 1 AS row_num")
                .doesNotContain("dm_rn");
    }

    @Test
    void convertsCommonProcedureMetadataChecksToDamengSyntax() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE add_col()
                BEGIN
                    IF NOT EXISTS (
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE table_schema = (select database()) AND table_name = 'demo' AND column_name = 'code'
                          AND CHARACTER_MAXIMUM_LENGTH < 128
                          AND NUMERIC_SCALE = 0
                    ) THEN
                        alter table demo add code varchar(128) null comment '编码';
                    END IF;
                    IF NOT EXISTS (
                        SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS
                        WHERE table_name = 'demo' AND table_schema = (SELECT DATABASE()) AND INDEX_NAME = 'idx_demo_code'
                    ) THEN
                        ALTER TABLE demo ADD INDEX idx_demo_code (code(32));
                    END IF;
                    IF NOT EXISTS (
                        SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS
                        WHERE table_name = 'demo' AND table_schema = (SELECT DATABASE()) AND INDEX_NAME = 'idx_demo_title'
                    ) THEN
                        CREATE INDEX idx_demo_title ON demo(title(20));
                    END IF;
                    IF NOT EXISTS (
                        SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.STATISTICS
                        WHERE table_name = 'demo' AND table_schema = (SELECT DATABASE()) AND INDEX_NAME = 'amount'
                    ) THEN
                        ALTER TABLE demo ADD INDEX (amount);
                    END IF;
                    IF EXISTS (
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE table_schema = (select database()) AND table_name = 'demo' AND column_name = 'code'
                          AND is_nullable = 'YES'
                    ) THEN
                        alter table demo modify column code varchar(256) character set utf8mb3 collate utf8mb3_general_ci;
                    END IF;
                    IF EXISTS (
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE table_schema = (select database()) AND table_name = 'demo' AND column_name = 'amount'
                    ) THEN
                        alter table demo modify column amount decimal(14, 2) null, modify column tax decimal(14, 2) null;
                    END IF;
                    IF EXISTS (
                        SELECT 1 FROM information_schema.`TABLES`
                        WHERE table_schema = database() AND table_name = 'demo'
                    ) THEN
                        SELECT 1;
                    END IF;
                END$$
                DELIMITER ;
                CALL add_col();
                DROP PROCEDURE IF EXISTS add_col;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("ALL_TAB_COLUMNS")
                .contains("ALL_TABLES")
                .contains("FROM ALL_IND_COLUMNS")
                .contains("CREATE OR REPLACE PROCEDURE add_col() AS")
                .contains("dm_adapter_schema VARCHAR(128) := SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID);")
                .contains("INDEX_OWNER = dm_adapter_schema")
                .contains("OWNER = dm_adapter_schema")
                .contains("CALL add_col()")
                .contains("HAVING COUNT(*) = 1")
                .contains("UPPER(COLUMN_NAME) = UPPER('code')")
                .contains("NULLABLE = 'YES'")
                .contains("CHAR_LENGTH")
                .contains("DATA_SCALE")
                .contains("dm_adapter_exists INT;")
                .contains("SELECT COUNT(*) INTO dm_adapter_exists FROM (")
                .contains("IF dm_adapter_exists = 0 THEN")
                .contains("IF dm_adapter_exists_5 > 0 THEN")
                .contains("EXECUTE IMMEDIATE 'alter table demo add code varchar(128) null'")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX demo_idx_demo_code"
                        + " ON demo (CAST(SUBSTR(code, 1, 32) AS VARCHAR(32)))'")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX demo_idx_demo_title"
                        + " ON demo (CAST(SUBSTR(title, 1, 20) AS VARCHAR(20)))'")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX demo_amount ON demo (amount)'")
                .contains("EXECUTE IMMEDIATE 'alter table demo MODIFY code varchar(256)'")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo MODIFY amount decimal(14, 2) null';")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo MODIFY tax decimal(14, 2) null';")
                .doesNotContain("information_schema")
                .doesNotContain("IS_NULLABLE")
                .doesNotContain("database()")
                .doesNotContain("NUMERIC_SCALE")
                .doesNotContain("INDEX_NAME = 'idx_demo_code'")
                .doesNotContain("code(32)")
                .doesNotContain("title(20)")
                .doesNotContain("IF NOT EXISTS (")
                .doesNotContain("IF EXISTS (")
                .doesNotContain("character set utf8mb3")
                .doesNotContain("collate utf8mb3_general_ci")
                .doesNotContain("comment '编码'");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.MYSQL_CREATE_PROCEDURE_TO_DM_RULE,
                                SqlScriptMigrator.MYSQL_SCRIPT_METADATA_TO_DM_RULE,
                                SqlScriptMigrator.MYSQL_SCRIPT_COMMENT_CLAUSE_REMOVAL_RULE,
                                SqlScriptMigrator.DM_METADATA_IDENTIFIER_CASE_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_IF_EXISTS_TO_COUNT_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE_RULE
                        ));
    }

    @Test
    void convertsProcedureViewSchemaAndConstraintMetadataChecksToDamengSyntax() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE rebuild_view_and_constraints()
                BEGIN
                    IF EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_NAME = N'v_demo') THEN
                        DROP VIEW v_demo;
                    END IF;
                    IF EXISTS(SELECT 1 FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = 'sample-system') THEN
                        CREATE VIEW v_demo AS SELECT id FROM demo;
                    END IF;
                    IF EXISTS(
                        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                        WHERE CONSTRAINT_SCHEMA = DATABASE()
                          AND TABLE_NAME = 'child'
                          AND CONSTRAINT_NAME = 'FK_CHILD'
                          AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                    ) THEN
                        ALTER TABLE child DROP FOREIGN KEY `FK_CHILD`;
                    END IF;
                    IF EXISTS(
                        SELECT NULL FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
                        WHERE TABLE_NAME = 'child'
                          AND REFERENCED_TABLE_NAME = 'parent'
                    ) THEN
                        ALTER TABLE child DROP FOREIGN KEY `FK_CHILD_PARENT`;
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE rebuild_view_and_constraints() AS")
                .contains("dm_adapter_schema VARCHAR(128) := SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID);")
                .contains("FROM (SELECT OWNER, OWNER AS TABLE_SCHEMA, VIEW_NAME AS TABLE_NAME FROM ALL_VIEWS)")
                .contains("FROM (SELECT USERNAME AS SCHEMA_NAME FROM ALL_USERS)")
                .contains("FROM (SELECT OWNER, OWNER AS CONSTRAINT_SCHEMA, TABLE_NAME, CONSTRAINT_NAME")
                .contains("FROM ALL_CONSTRAINTS)")
                .contains("FROM (SELECT C.OWNER, C.TABLE_NAME, C.CONSTRAINT_NAME, CC.COLUMN_NAME")
                .contains("RC.TABLE_NAME AS REFERENCED_TABLE_NAME")
                .contains("EXECUTE IMMEDIATE 'DROP VIEW v_demo'")
                .contains("EXECUTE IMMEDIATE 'CREATE VIEW v_demo AS SELECT id FROM demo'")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE child DROP CONSTRAINT FK_CHILD'")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE child DROP CONSTRAINT FK_CHILD_PARENT'")
                .doesNotContain("INFORMATION_SCHEMA")
                .doesNotContain("information_schema")
                .doesNotContain("DATABASE()")
                .doesNotContain("database()")
                .doesNotContain("DROP FOREIGN KEY");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.MYSQL_SCRIPT_METADATA_TO_DM_RULE,
                                SqlScriptMigrator.MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE_RULE
                        ));
    }

    @Test
    void normalizesProcedureDynamicDdlIdentifierTypeSpacing() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE fix_meter()
                BEGIN
                    IF EXISTS (
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE table_schema = database() AND table_name = 'demo' AND column_name = 'unitPrice'
                    ) THEN
                        ALTER TABLE `demo` MODIFY `unitPrice`decimal(20,8) NULL;
                        ALTER TABLE `demo` ADD COLUMN `useProperties`varchar(20) DEFAULT NULL;
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE `demo` MODIFY `unitPrice` decimal(20,8) NULL'")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE `demo` ADD `useProperties` varchar(20) DEFAULT NULL'")
                .doesNotContain("`unitPrice`decimal")
                .doesNotContain("`useProperties`varchar");
    }

    @Test
    void mapsMysqlColumnDefaultMetadataToDamengDataDefault() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                DELIMITER $$
                CREATE PROCEDURE clear_default()
                BEGIN
                    IF EXISTS (
                        SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE table_schema = database()
                          AND table_name = 'demo'
                          AND column_name = 'code'
                          AND column_default IS NOT NULL
                          AND numeric_precision = 20
                    ) THEN
                        ALTER TABLE demo ALTER COLUMN code SET DEFAULT NULL;
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("procedure.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("DATA_DEFAULT IS NOT NULL")
                .contains("DATA_PRECISION = 20")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo ALTER COLUMN code SET DEFAULT NULL'")
                .doesNotContain("column_default")
                .doesNotContain("numeric_precision");
    }

    @Test
    void convertsMysqlProcedureLoopSyntaxAndQuotedRoutineNamesForDameng() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("loop.sql"), """
                DELIMITER $$
                CREATE PROCEDURE `demo_loop`()
                BEGIN
                    DECLARE v_index INT DEFAULT 0;
                    WHILE v_index < 2 DO
                        IF v_index = 0 THEN
                            SET v_index = v_index + 1;
                        ELSEIF v_index = 1 THEN
                            SET v_index = v_index + 1;
                        END IF;
                    END WHILE;
                END$$
                DELIMITER ;
                CALL `demo_loop`();
                DROP PROCEDURE IF EXISTS `demo_loop`;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("loop.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE OR REPLACE PROCEDURE demo_loop() AS")
                .contains("WHILE v_index < 2 LOOP")
                .contains("ELSIF v_index = 1 THEN")
                .contains("END LOOP;")
                .contains("CALL demo_loop()")
                .contains("DROP PROCEDURE IF EXISTS demo_loop")
                .doesNotContain(" DO")
                .doesNotContain("END WHILE")
                .doesNotContain("ELSEIF")
                .doesNotContain("`demo_loop`");
    }

    @Test
    void convertsProcedureUserVariablesAssignedWithColonEquals() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("variables.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_variables()
                BEGIN
                    SET @target_ver := 2;
                    IF @target_ver > 1 THEN
                        SELECT @target_ver;
                    END IF;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("variables.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("dm_target_ver BIGINT;")
                .contains("dm_target_ver := 2;")
                .contains("IF dm_target_ver > 1 THEN")
                .contains("SELECT dm_target_ver;")
                .doesNotContain("@target_ver")
                .doesNotContain("SET dm_target_ver :=");
    }

    @Test
    void convertsTopLevelMysqlModifyColumnAfterClauseForDameng() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("alter.sql"), """
                ALTER TABLE `demo` MODIFY COLUMN `body` mediumtext NULL AFTER `id`;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("alter.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("ALTER TABLE `demo` MODIFY `body` CLOB NULL")
                .doesNotContainIgnoringCase("MODIFY COLUMN")
                .doesNotContainIgnoringCase("AFTER");
    }

    @Test
    void convertsTemporaryInsertIgnoreSelectToMergeWhenKeyColumnsAreKnown() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("insert-ignore.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_insert_ignore()
                BEGIN
                    CREATE TEMPORARY TABLE tmp_demo (
                        id BIGINT NOT NULL,
                        name VARCHAR(100),
                        PRIMARY KEY (id),
                        KEY idx_tmp_demo_name (name)
                    );
                    INSERT IGNORE INTO tmp_demo (id, name)
                    SELECT s.id, s.name AS name
                    FROM source_table s;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("insert-ignore.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE TABLE IF NOT EXISTS tmp_demo (id BIGINT, name VARCHAR(200));")
                .contains("DELETE FROM tmp_demo /* DM_ADAPTER_TMP_COLUMN tmp_demo id */")
                .contains("MERGE INTO tmp_demo t")
                .contains("SELECT s.id AS id, s.name AS name")
                .contains("ON (t.id = s.id)")
                .contains("WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)")
                .doesNotContain("INSERT IGNORE")
                .doesNotContain("DROP TABLE IF EXISTS tmp_demo;")
                .doesNotContain("EXECUTE IMMEDIATE 'CREATE TABLE tmp_demo")
                .doesNotContain("KEY idx_tmp_demo_name");
    }

    @Test
    void preservesExplicitTemporaryTableColumnsForCompilePlaceholders() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("temporary-table.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_temp_table()
                BEGIN
                    CREATE TEMPORARY TABLE tmp_menu_copy (
                        source_id BIGINT NOT NULL,
                        enterprise_id BIGINT NOT NULL,
                        organization_id BIGINT NOT NULL,
                        target_ver INT NOT NULL,
                        menu_id VARCHAR(100) NOT NULL,
                        PRIMARY KEY (source_id)
                    );
                    INSERT IGNORE INTO tmp_menu_copy (
                        source_id,
                        enterprise_id,
                        organization_id,
                        target_ver,
                        menu_id
                    )
                    SELECT src.id, src.enterprise_id, src.organization_id, 2, src.menu_id
                    FROM source_menu src;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("temporary-table.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE TABLE IF NOT EXISTS tmp_menu_copy (source_id BIGINT, enterprise_id BIGINT, organization_id BIGINT, target_ver BIGINT, menu_id VARCHAR(200));")
                .contains("DELETE FROM tmp_menu_copy /* DM_ADAPTER_TMP_COLUMN tmp_menu_copy source_id */")
                .contains("MERGE INTO tmp_menu_copy t")
                .contains("ON (t.source_id = s.source_id)")
                .doesNotContain("roleid VARCHAR(200)");
    }

    @Test
    void convertsLongDynamicViewProcedureWithSmallThreadStack() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("view.sql"), """
                DELIMITER $$
                CREATE PROCEDURE create_budget_plan_view()
                BEGIN
                    IF NOT EXISTS(
                        SELECT 1
                        FROM INFORMATION_SCHEMA.VIEWS
                        WHERE TABLE_NAME = N'ns_budget_plan_view'
                          AND TABLE_SCHEMA = DATABASE()
                    )
                    THEN
                        CREATE VIEW ns_budget_plan_view AS
                            SELECT
                                p.id,
                                p.budgetTitle,
                                p.departmentName,
                                p.templateName,
                                p.year,
                                p.remark,
                                p.financeNum,
                                p.tradeName,
                                p.processNum,
                                p.outBpmFlowUrl,
                                p.departmentId,
                                p.organizationShortName,
                                p.auditStatus,
                                p.createUserId,
                                p.createUserName,
                                p.createDateTime,
                                p.updateUserId,
                                p.updateUserName,
                                p.updateDateTime,
                                p.deleteFlag
                            FROM ns_budget_plan p
                            WHERE p.deleteFlag = 0
                            ORDER BY p.id;
                    END IF;
                END$$
                DELIMITER ;
                """);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(null, () -> {
            try {
                migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-budget",
                        "",
                        DmValidationEnvironment.from(Map.of())
                ));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "small-stack-sql-converter", 256 * 1024L);

        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(worker.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(Files.readString(sqlRootOut.resolve("view.sql")))
                .contains("EXECUTE IMMEDIATE 'CREATE VIEW ns_budget_plan_view AS")
                .contains("ORDER BY p.id'");
    }

    @Test
    void infersTemporaryTableColumnsAfterMultilineDistinctWithoutAliases() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("temporary-table-distinct.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_temp_table_distinct()
                BEGIN
                    DROP TEMPORARY TABLE IF EXISTS tmp_module_copy_top_menu;
                    DROP TEMPORARY TABLE IF EXISTS tmp_module_copy_all_menu;

                    CREATE TEMPORARY TABLE tmp_module_copy_top_menu AS
                    SELECT DISTINCT
                        mm.enterprise_id,
                        mm.menu_id
                    FROM ns_core_module_menu mm;

                    CREATE TEMPORARY TABLE tmp_module_copy_all_menu AS
                    SELECT DISTINCT
                        mm.enterprise_id,
                        mm.menu_id
                    FROM ns_core_module_menu mm;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("temporary-table-distinct.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE TABLE IF NOT EXISTS tmp_module_copy_top_menu"
                        + " (enterprise_id BIGINT, menu_id VARCHAR(200));")
                .contains("CREATE TABLE IF NOT EXISTS tmp_module_copy_all_menu"
                        + " (enterprise_id BIGINT, menu_id VARCHAR(200));")
                .contains("INSERT INTO tmp_module_copy_top_menu (enterprise_id, menu_id) SELECT DISTINCT")
                .contains("INSERT INTO tmp_module_copy_all_menu (enterprise_id, menu_id) SELECT DISTINCT")
                .doesNotContain("organization_id BIGINT")
                .doesNotContain("roleid VARCHAR(200)");
    }

    @Test
    void convertsDynamicTemporaryInsertIgnoreSqlStringToMerge() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("dynamic-insert-ignore.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_dynamic_insert_ignore()
                BEGIN
                    SET @sql_text = '';
                    CREATE TEMPORARY TABLE tmp_demo_dynamic (
                        id BIGINT NOT NULL PRIMARY KEY,
                        name VARCHAR(100)
                    );
                    SET @sql_text = '
                        INSERT IGNORE INTO tmp_demo_dynamic (id, name)
                        SELECT s.id, s.name
                        FROM source_table s';
                    PREPARE stmt FROM @sql_text;
                    EXECUTE stmt;
                    DEALLOCATE PREPARE stmt;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("dynamic-insert-ignore.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("EXECUTE IMMEDIATE dm_sql_text;")
                .contains("MERGE INTO tmp_demo_dynamic t")
                .contains("ON (t.id = s.id)")
                .doesNotContain("INSERT IGNORE")
                .doesNotContain("PREPARE stmt");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_DYNAMIC_INSERT_IGNORE_TO_MERGE_RULE));
    }

    @Test
    void convertsDynamicPrepareSequenceSeparatedByComments() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE execute_formula()
                BEGIN
                    SET @dynamic_sql = 'SELECT 1 FROM dual';
                    PREPARE formula_stmt FROM @dynamic_sql;
                    -- prepare completed
                    EXECUTE formula_stmt;
                    /* release the MySQL prepared statement */
                    DEALLOCATE PREPARE formula_stmt;
                END;
                /
                CALL execute_formula();
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("dm_dynamic_sql VARCHAR(4000)")
                .contains("EXECUTE IMMEDIATE dm_dynamic_sql")
                .doesNotContain("PREPARE formula_stmt")
                .doesNotContain("EXECUTE formula_stmt")
                .doesNotContain("DEALLOCATE PREPARE formula_stmt");
        assertThat(converted.report().files()).singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_DYNAMIC_PREPARE_TO_EXECUTE_IMMEDIATE_RULE));
    }

    @Test
    void convertsProcedureInsertIgnoreIntoBackupTableCreatedLikeSourceWhenIdIsAvailable() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("backup-insert-ignore.sql"), """
                DELIMITER $$
                CREATE PROCEDURE demo_backup_insert_ignore()
                BEGIN
                    CREATE TABLE IF NOT EXISTS ns_demo_bak_20260521 LIKE ns_demo;
                    INSERT IGNORE INTO ns_demo_bak_20260521 (ID, NAME)
                    SELECT d.ID, d.NAME
                    FROM ns_demo d;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("backup-insert-ignore.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .contains("CREATE TABLE IF NOT EXISTS ns_demo_bak_20260521 LIKE ns_demo")
                .contains("MERGE INTO ns_demo_bak_20260521 t")
                .contains("ON (t.ID = s.ID)")
                .doesNotContain("INSERT IGNORE");
        assertThat(converted.indexOf("CREATE TABLE IF NOT EXISTS ns_demo_bak_20260521 LIKE ns_demo"))
                .isLessThan(converted.indexOf("CREATE OR REPLACE PROCEDURE demo_backup_insert_ignore"));
        assertThat(converted)
                .contains("EXECUTE IMMEDIATE 'CREATE TABLE IF NOT EXISTS ns_demo_bak_20260521 LIKE ns_demo'");
    }

    @Test
    void marksMalformedLengthComparisonForManualReview() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("length.sql"), """
                DELIMITER $$
                CREATE PROCEDURE bad_length_guard()
                BEGIN
                    IF NOT EXISTS (
                        SELECT CHARACTER_MAXIMUM_LENGTH
                        FROM information_schema.columns
                        WHERE table_schema = database()
                          AND table_name = 'demo'
                          AND column_name = 'name'
                          AND CHARACTER_MAXIMUM_LENGTH>1=200
                    ) THEN
                        ALTER TABLE demo MODIFY COLUMN name varchar(200) NOT NULL DEFAULT '';
                    END IF;
                END$$
                DELIMITER ;
                CALL bad_length_guard();
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-system",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(2);
        assertThat(report.validationSuccessCount()).isZero();
        assertThat(report.validationFailureCount()).isZero();
        assertThat(report.manualReviewItems())
                .extracting(item -> item.reason())
                .anySatisfy(reason -> assertThat(reason).contains("链式比较"))
                .anySatisfy(reason -> assertThat(reason).contains("依赖需要人工确认的存储过程"));
    }

    @Test
    void convertsCreateTableOptionsWhenStatementHasLeadingComments() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("table.sql"), """
                -- business note
                CREATE TABLE IF NOT EXISTS demo_table (
                    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
                    `code` varchar(64) CHARACTER SET utf8 DEFAULT NULL COMMENT 'code',
                    PRIMARY KEY (`id`) USING BTREE,
                    UNIQUE KEY `uk_demo_code` (`code`) USING BTREE,
                    -- supports code-prefix lookup
                    KEY `idx_demo_code` (`code`(16)) USING BTREE COMMENT 'lookup'
                ) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_0900_ai_ci;
                """);

        RecordingValidator validator = new RecordingValidator();
        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        String converted = Files.readString(sqlRootOut.resolve("table.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(report.validationSuccessCount()).isEqualTo(3);
        assertThat(report.files().get(0).appliedRules())
                .contains(
                        SqlScriptMigrator.MYSQL_CREATE_TABLE_INLINE_INDEX_TO_DM_RULE,
                        SqlScriptMigrator.MYSQL_SCHEMA_SCOPED_INDEX_NAME_RULE,
                        SqlScriptMigrator.MYSQL_PREFIX_INDEX_TO_FUNCTION_INDEX_RULE
                )
                .doesNotContain(SqlScriptMigrator.MYSQL_PROCEDURE_TEMP_TABLE_COMPILE_PLACEHOLDER_RULE);
        assertThat(validator.files).singleElement().satisfies(file -> {
            assertThat(file.statements()).hasSize(3);
            assertThat(file.statements().get(0)).contains("CREATE TABLE IF NOT EXISTS demo_table");
            assertThat(file.statements().get(1))
                    .contains("EXECUTE IMMEDIATE 'CREATE UNIQUE INDEX demo_table_uk_demo_code"
                            + " ON demo_table (`code`)'");
            assertThat(file.statements().get(2))
                    .contains("EXECUTE IMMEDIATE 'CREATE INDEX demo_table_idx_demo_code"
                            + " ON demo_table (CAST(SUBSTR(`code`, 1, 16) AS VARCHAR(16)))'");
        });
        assertThat(converted)
                .startsWith("-- business note")
                .contains("`id` bigint NOT NULL IDENTITY(1,1)")
                .contains("`code` varchar(64) DEFAULT NULL")
                .contains("FROM ALL_INDEXES")
                .contains("INDEX_NAME = UPPER('demo_table_uk_demo_code')")
                .contains("INDEX_NAME = UPPER('demo_table_idx_demo_code')")
                .doesNotContain("KEY `idx_demo_code`")
                .doesNotContainIgnoringCase("USING BTREE")
                .doesNotContainIgnoringCase("ENGINE")
                .doesNotContainIgnoringCase("COMMENT");
    }

    @Test
    void retainsCreateTableWithUnsupportedInlineFulltextIndexForManualReview() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("fulltext.sql"), """
                CREATE TABLE sample_article (
                    id BIGINT NOT NULL,
                    body TEXT,
                    PRIMARY KEY (id),
                    FULLTEXT KEY ft_article_body (body)
                );
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isOne();
        assertThat(report.validationSuccessCount()).isZero();
        assertThat(report.manualReviewItems()).singleElement().satisfies(item ->
                assertThat(item.reason()).contains("FULLTEXT", "已保留原 SQL"));
        assertThat(Files.readString(sqlRootOut.resolve("fulltext.sql")))
                .contains("FULLTEXT KEY ft_article_body (body)");
    }

    @Test
    void validationConnectionFailureRedactsConnectionValues() {
        DmValidationEnvironment environment = DmValidationEnvironment.from(Map.of(
                "DM_SQL_VALIDATION", "true",
                "DM_JDBC_URL", "jdbc:dm://db-host:5236",
                "DM_DB_USERNAME", "APP_USER",
                "DM_DB_PASSWORD", "APP_SECRET"
        ));
        SqlScriptValidator validator = new SqlScriptValidator(env -> {
            throw new SQLException("Cannot connect jdbc:dm://db-host:5236 APP_USER APP_SECRET");
        });

        SqlScriptValidationRun result = validator.validate(List.of(new SqlScriptMigrator.PlannedSqlScriptFile(
                "20260423.sql",
                "20260423.sql",
                "sample-bill",
                false,
                true,
                false,
                1,
                0,
                0,
                Set.of(),
                List.of(),
                List.of("select 1 from dual")
        )), environment);

        assertThat(result.attempted()).isFalse();
        assertThat(result.status())
                .contains("******")
                .doesNotContain("jdbc:dm://db-host:5236")
                .doesNotContain("APP_USER")
                .doesNotContain("APP_SECRET");
    }

    @Test
    void classifiesObjectDefinitionVersionFailuresAndReportsRecentDdl() {
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("execute")) {
                String sql = (String) args[0];
                if (sql.startsWith("CALL")) {
                    throw new SQLException(
                            "对象定义[demo]被修改，版本检查失败 -7184: refresh_demo line 8",
                            "42000",
                            -7184
                    );
                }
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) ->
                method.getName().equals("createStatement")
                        ? statement
                        : defaultValue(method.getReturnType()));

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(plannedValidationFile(
                        "version.sql",
                        "",
                        List.of(
                                "ALTER TABLE demo ADD status INT",
                                "CALL refresh_demo()"
                        )
                )),
                validationEnvironment()
        );

        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.category()).isEqualTo("OBJECT_DEFINITION_CHANGED");
            assertThat(failure.errorSummary())
                    .contains("-7184")
                    .contains("最近相关 DDL")
                    .contains("第 1 条 SQL");
        });
    }

    @Test
    void classifiesDuplicateColumnDefaultsAsOriginalSql() {
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("execute")
                    && ((String) args[0]).contains("CREATE TABLE")) {
                throw new SQLException("第 3 行, 第 37 列[DEFAULT]附近出现错误: 语法分析出错");
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) ->
                method.getName().equals("createStatement")
                        ? statement
                        : defaultValue(method.getReturnType()));

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(plannedValidationFile(
                        "duplicate-default.sql",
                        "sample-bill",
                        List.of("""
                                CREATE TABLE demo (
                                    id BIGINT NOT NULL,
                                    status TINYINT DEFAULT NULL DEFAULT '0',
                                    note VARCHAR(64) DEFAULT 'DEFAULT text'
                                )
                                """)
                )),
                validationEnvironment()
        );

        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.category()).isEqualTo("ORIGINAL_SQL");
            assertThat(failure.errorSummary())
                    .contains("multiple DEFAULT clauses")
                    .contains("fix the source SQL");
        });
    }

    @Test
    void classifiesContradictoryColumnNullabilityAsOriginalSql() {
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("execute")
                    && ((String) args[0]).contains("CREATE TABLE")) {
                throw new SQLException("第 3 行, 第 65 列[NOT]附近出现错误: 语法分析出错");
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) ->
                method.getName().equals("createStatement")
                        ? statement
                        : defaultValue(method.getReturnType()));

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(plannedValidationFile(
                        "contradictory-nullability.sql",
                        "sample-city",
                        List.of("""
                                CREATE TABLE ns_city_station_message (
                                    id BIGINT NOT NULL,
                                    createTime datetime(0) NULL DEFAULT CURRENT_TIMESTAMP(0) NOT NULL,
                                    note VARCHAR(64) DEFAULT 'NULL and NOT NULL are text'
                                )
                                """)
                )),
                validationEnvironment()
        );

        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.category()).isEqualTo("ORIGINAL_SQL");
            assertThat(failure.errorSummary())
                    .contains("both NULL and NOT NULL")
                    .contains("fix the source SQL");
        });
    }

    @Test
    void schemaPreflightStopsAllSqlStatementsAndReportsOneRootFailure() {
        AtomicInteger businessStatementCount = new AtomicInteger();
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("execute")) {
                String sql = (String) args[0];
                if (sql.startsWith("set schema")) {
                    throw new SQLException("无效的模式名");
                }
                businessStatementCount.incrementAndGet();
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) ->
                method.getName().equals("createStatement")
                        ? statement
                        : defaultValue(method.getReturnType()));

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(plannedValidationFile(
                        "schema.sql",
                        "missing-schema",
                        List.of("select 1 from dual", "select 2 from dual")
                )),
                validationEnvironment()
        );

        assertThat(result.attempted()).isTrue();
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.category()).isEqualTo("INVALID_SCHEMA");
            assertThat(failure.statementIndex()).isZero();
        });
        assertThat(businessStatementCount).hasValue(0);
    }

    @Test
    void classifiesCallsToFailedRoutineAsBlockedFailures() {
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("execute")) {
                String sql = (String) args[0];
                if (!sql.startsWith("set schema")) {
                    throw new SQLException("routine validation failed");
                }
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) ->
                method.getName().equals("createStatement")
                        ? statement
                        : defaultValue(method.getReturnType()));

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(plannedValidationFile(
                        "routine.sql",
                        "sample-bill",
                        List.of(
                                "CREATE OR REPLACE PROCEDURE demo_proc AS BEGIN NULL; END;",
                                "CALL demo_proc()"
                        )
                )),
                validationEnvironment()
        );

        assertThat(result.failures())
                .extracting(com.github.dmadapter.core.SqlScriptValidationFailure::category)
                .containsExactly("SQL_EXECUTION", "BLOCKED_BY_PRIOR_FAILURE");
        assertThat(result.failures().get(1).errorSummary())
                .contains("Blocked by failed statement 1")
                .contains("DEMO_PROC");
    }

    @Test
    void skipsOnlyManualReviewStatementsAndValidatesRemainingStatements() {
        List<String> executedSql = new ArrayList<>();
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("execute")) {
                String sql = (String) args[0];
                if (!sql.startsWith("set schema")) {
                    executedSql.add(sql);
                }
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) ->
                method.getName().equals("createStatement")
                        ? statement
                        : defaultValue(method.getReturnType()));
        SqlScriptMigrator.PlannedSqlScriptFile file = new SqlScriptMigrator.PlannedSqlScriptFile(
                "partial-manual.sql",
                "partial-manual.sql",
                "sample-bill",
                false,
                true,
                false,
                2,
                0,
                1,
                Set.of(1),
                List.of(),
                List.of("select 'manual' from dual", "select 'validated' from dual")
        );

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(file),
                validationEnvironment()
        );

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();
        assertThat(result.failures()).noneSatisfy(failure ->
                assertThat(failure.category()).isEqualTo("MANUAL_REVIEW_REQUIRED"));
        assertThat(executedSql).containsExactly("select 'validated' from dual");
    }

    @Test
    void failsValidationWhenCreatedRoutineIsInvalidWithoutJdbcException() {
        AtomicInteger resultSetNextCount = new AtomicInteger();
        ResultSet resultSet = proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
            case "next" -> resultSetNextCount.getAndIncrement() == 0;
            case "getString" -> "INVALID";
            default -> defaultValue(method.getReturnType());
        });
        List<String> statusQueries = new ArrayList<>();
        PreparedStatement preparedStatement = proxy(PreparedStatement.class, (ignored, method, args) -> {
            if (method.getName().equals("executeQuery")) {
                return resultSet;
            }
            return defaultValue(method.getReturnType());
        });
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("execute")) {
                String sql = (String) args[0];
                if (sql.startsWith("CALL")) {
                    throw new SQLException("对象处于无效状态");
                }
                return false;
            }
            if (method.getName().equals("getWarnings")) {
                return new SQLWarning("创建的对象带有编译错误");
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) -> {
            if (method.getName().equals("createStatement")) {
                return statement;
            }
            if (method.getName().equals("prepareStatement")) {
                statusQueries.add((String) args[0]);
                return preparedStatement;
            }
            return defaultValue(method.getReturnType());
        });

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(plannedValidationFile(
                        "routine-invalid.sql",
                        "sample-system",
                        List.of(
                                "CREATE OR REPLACE PROCEDURE demo_proc AS BEGIN NULL; END;",
                                "CALL demo_proc()"
                        )
                )),
                validationEnvironment()
        );

        assertThat(result.successCount()).isZero();
        assertThat(result.failures())
                .extracting(com.github.dmadapter.core.SqlScriptValidationFailure::category)
                .containsExactly("INVALID_DATABASE_OBJECT", "BLOCKED_BY_PRIOR_FAILURE");
        assertThat(result.failures().get(0).errorSummary())
                .contains("PROCEDURE demo_proc is INVALID")
                .contains("创建的对象带有编译错误");
        assertThat(statusQueries).singleElement().satisfies(query -> assertThat(query)
                .contains("OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')")
                .doesNotContain("sample-system"));
    }

    @Test
    void recompilesInvalidRoutineToReportAndClassifyConcreteCompileError() {
        AtomicInteger resultSetNextCount = new AtomicInteger();
        ResultSet resultSet = proxy(ResultSet.class, (ignored, method, args) -> switch (method.getName()) {
            case "next" -> resultSetNextCount.getAndIncrement() == 0;
            case "getString" -> "INVALID";
            default -> defaultValue(method.getReturnType());
        });
        PreparedStatement preparedStatement = proxy(PreparedStatement.class, (ignored, method, args) ->
                method.getName().equals("executeQuery")
                        ? resultSet
                        : defaultValue(method.getReturnType()));
        List<String> executedSql = new ArrayList<>();
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("execute")) {
                String sql = (String) args[0];
                executedSql.add(sql);
                if (sql.startsWith("ALTER PROCEDURE")) {
                    throw new SQLException("第 7 行附近出现错误: 无效的表或视图名[missing_dictionary]");
                }
                return false;
            }
            if (method.getName().equals("getWarnings")) {
                return new SQLWarning("创建的对象带有编译错误");
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) -> {
            if (method.getName().equals("createStatement")) {
                return statement;
            }
            if (method.getName().equals("prepareStatement")) {
                return preparedStatement;
            }
            return defaultValue(method.getReturnType());
        });

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(plannedValidationFile(
                        "routine-compile-error.sql",
                        "sample-system",
                        List.of("CREATE OR REPLACE PROCEDURE demo_proc AS BEGIN NULL; END;")
                )),
                validationEnvironment()
        );

        assertThat(executedSql).contains("ALTER PROCEDURE demo_proc COMPILE");
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.category()).isEqualTo("TEST_SCHEMA_OBJECT");
            assertThat(failure.errorSummary())
                    .contains("Recompile diagnostic")
                    .contains("missing_dictionary");
        });
    }

    @Test
    void failsClosedWhenCreatedObjectStatusCannotBeQueried() {
        PreparedStatement preparedStatement = proxy(PreparedStatement.class, (ignored, method, args) -> {
            if (method.getName().equals("executeQuery")) {
                throw new SQLException("无权限查询 ALL_OBJECTS");
            }
            return defaultValue(method.getReturnType());
        });
        Statement statement = proxy(Statement.class, (ignored, method, args) ->
                defaultValue(method.getReturnType()));
        Connection connection = proxy(Connection.class, (ignored, method, args) -> {
            if (method.getName().equals("createStatement")) {
                return statement;
            }
            if (method.getName().equals("prepareStatement")) {
                return preparedStatement;
            }
            return defaultValue(method.getReturnType());
        });

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(plannedValidationFile(
                        "routine-status.sql",
                        "sample-system",
                        List.of("CREATE OR REPLACE FUNCTION demo_func RETURN INT AS BEGIN RETURN 1; END;")
                )),
                validationEnvironment()
        );

        assertThat(result.successCount()).isZero();
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.category()).isEqualTo("OBJECT_STATUS_VALIDATION_FAILED");
            assertThat(failure.errorSummary())
                    .contains("FUNCTION demo_func")
                    .contains("ALL_OBJECTS");
        });
    }

    @Test
    void ignoresRoutineDefinitionsAndCallsInsideCommentsDuringValidation() {
        AtomicInteger statusQueryCount = new AtomicInteger();
        Statement statement = proxy(Statement.class, (ignored, method, args) ->
                defaultValue(method.getReturnType()));
        Connection connection = proxy(Connection.class, (ignored, method, args) -> {
            if (method.getName().equals("createStatement")) {
                return statement;
            }
            if (method.getName().equals("prepareStatement")) {
                statusQueryCount.incrementAndGet();
            }
            return defaultValue(method.getReturnType());
        });

        SqlScriptValidationRun result = new SqlScriptValidator(env -> connection).validate(
                List.of(plannedValidationFile(
                        "commented-routine.sql",
                        "sample-bill",
                        List.of("""
                                /*
                                 * CREATE PROCEDURE ignored_proc()
                                 * BEGIN
                                 *     CALL ignored_dependency();
                                 * END;
                                 */
                                DROP PROCEDURE IF EXISTS active_proc
                                """)
                )),
                validationEnvironment()
        );

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();
        assertThat(statusQueryCount).hasValue(0);
    }

    @Test
    void stopsValidationAtAdapterHardTimeoutWhenJdbcDriverIgnoresInterrupts() {
        String property = "dm.adapter.sqlScriptStatementTimeoutSeconds";
        String previous = System.getProperty(property);
        CountDownLatch releaseStatement = new CountDownLatch(1);
        AtomicInteger businessStatementCount = new AtomicInteger();
        try {
            System.setProperty(property, "1");
            Statement statement = proxy(Statement.class, (ignored, method, args) -> {
                if (method.getName().equals("execute")) {
                    String sql = (String) args[0];
                    if (!sql.startsWith("set schema")) {
                        businessStatementCount.incrementAndGet();
                        while (releaseStatement.getCount() > 0) {
                            try {
                                releaseStatement.await(50, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ignoredInterrupt) {
                                // Simulate a JDBC driver that ignores thread interruption.
                            }
                        }
                    }
                }
                return defaultValue(method.getReturnType());
            });
            Connection connection = proxy(Connection.class, (ignored, method, args) ->
                    method.getName().equals("createStatement")
                            ? statement
                            : defaultValue(method.getReturnType()));

            SqlScriptValidationRun result = assertTimeout(
                    Duration.ofSeconds(3),
                    () -> new SqlScriptValidator(env -> connection).validate(
                            List.of(plannedValidationFile(
                                    "blocking.sql",
                                    "sample-bill",
                                    List.of("CALL blocking_proc()", "select 1 from dual")
                            )),
                            validationEnvironment()
                    )
            );

            assertThat(result.status()).contains("timed out");
            assertThat(result.successCount()).isZero();
            assertThat(result.failures()).singleElement().satisfies(failure -> {
                assertThat(failure.category()).isEqualTo("VALIDATION_TIMEOUT");
                assertThat(failure.statementIndex()).isEqualTo(1);
                assertThat(failure.errorSummary()).contains("hard timeout of 1 seconds");
            });
            assertThat(businessStatementCount).hasValue(1);
        } finally {
            releaseStatement.countDown();
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void hardTimeoutCoversJdbcStatementInitializationBeforeExecute() {
        String property = "dm.adapter.sqlScriptStatementTimeoutSeconds";
        String previous = System.getProperty(property);
        CountDownLatch releaseInitialization = new CountDownLatch(1);
        AtomicInteger timeoutConfigurationCount = new AtomicInteger();
        AtomicInteger businessStatementCount = new AtomicInteger();
        try {
            System.setProperty(property, "1");
            Statement statement = proxy(Statement.class, (ignored, method, args) -> {
                if (method.getName().equals("setQueryTimeout")
                        && timeoutConfigurationCount.incrementAndGet() == 3) {
                    while (releaseInitialization.getCount() > 0) {
                        try {
                            releaseInitialization.await(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignoredInterrupt) {
                            // Simulate a driver blocked before execute that ignores interruption.
                        }
                    }
                }
                if (method.getName().equals("execute")) {
                    String sql = (String) args[0];
                    if (!sql.startsWith("set schema")) {
                        businessStatementCount.incrementAndGet();
                    }
                }
                return defaultValue(method.getReturnType());
            });
            Connection connection = proxy(Connection.class, (ignored, method, args) ->
                    method.getName().equals("createStatement")
                            ? statement
                            : defaultValue(method.getReturnType()));

            SqlScriptValidationRun result = assertTimeout(
                    Duration.ofSeconds(3),
                    () -> new SqlScriptValidator(env -> connection).validate(
                            List.of(plannedValidationFile(
                                    "blocking-initialization.sql",
                                    "sample-bill",
                                    List.of("select 1 from dual", "select 2 from dual")
                            )),
                            validationEnvironment()
                    )
            );

            assertThat(result.status()).contains("timed out");
            assertThat(result.successCount()).isZero();
            assertThat(result.failures()).singleElement().satisfies(failure -> {
                assertThat(failure.category()).isEqualTo("VALIDATION_TIMEOUT");
                assertThat(failure.statementIndex()).isEqualTo(1);
                assertThat(failure.errorSummary()).contains("hard timeout of 1 seconds");
            });
            assertThat(timeoutConfigurationCount).hasValue(3);
            assertThat(businessStatementCount).hasValue(0);
        } finally {
            releaseInitialization.countDown();
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void classifiesBlockedSchemaPreflightAsValidationTimeout() {
        String property = "dm.adapter.sqlScriptStatementTimeoutSeconds";
        String previous = System.getProperty(property);
        CountDownLatch releaseSchemaSelection = new CountDownLatch(1);
        try {
            System.setProperty(property, "1");
            Statement statement = proxy(Statement.class, (ignored, method, args) -> {
                if (method.getName().equals("setQueryTimeout")) {
                    while (releaseSchemaSelection.getCount() > 0) {
                        try {
                            releaseSchemaSelection.await(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignoredInterrupt) {
                            // Simulate a driver blocked while preparing schema selection.
                        }
                    }
                }
                return defaultValue(method.getReturnType());
            });
            Connection connection = proxy(Connection.class, (ignored, method, args) ->
                    method.getName().equals("createStatement")
                            ? statement
                            : defaultValue(method.getReturnType()));

            SqlScriptValidationRun result = assertTimeout(
                    Duration.ofSeconds(3),
                    () -> new SqlScriptValidator(env -> connection).validate(
                            List.of(plannedValidationFile(
                                    "schema-timeout.sql",
                                    "sample-bill",
                                    List.of("select 1 from dual")
                            )),
                            validationEnvironment()
                    )
            );

            assertThat(result.status()).contains("timed out");
            assertThat(result.successCount()).isZero();
            assertThat(result.failures()).singleElement().satisfies(failure -> {
                assertThat(failure.sourceFile()).isEqualTo("(schema-preflight)");
                assertThat(failure.category()).isEqualTo("VALIDATION_TIMEOUT");
                assertThat(failure.errorSummary()).contains("hard timeout of 1 seconds");
            });
        } finally {
            releaseSchemaSelection.countDown();
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void convertsSqlExceptionContinueHandlerToStatementLevelDmBlocks() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE refresh_summary(target_month VARCHAR(10))
                BEGIN
                    DECLARE state_code CHAR(5) DEFAULT '00000';
                    DECLARE error_message TEXT;
                    DECLARE affected_rows INT;
                    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION
                    BEGIN
                        GET DIAGNOSTICS CONDITION 1
                            state_code = RETURNED_SQLSTATE, error_message = MESSAGE_TEXT;
                    END;
                    IF EXISTS (SELECT 1 FROM summary_result WHERE month_no = target_month) THEN
                        DELETE FROM summary_result WHERE month_no = target_month;
                    END IF;
                    INSERT INTO summary_result(month_no, amount)
                    SELECT target_month, SUM(amount)
                    FROM summary_source;
                    IF state_code = '00000' THEN
                        GET DIAGNOSTICS affected_rows = ROW_COUNT;
                        INSERT INTO migration_log(message)
                        VALUES (CONCAT('rows=', affected_rows));
                    ELSE
                        INSERT INTO migration_log(message) VALUES (error_message);
                    END IF;
                END;
                /
                CALL refresh_summary('2026-07');
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("state_code := 'HY000'")
                .contains("error_message := SQLERRM")
                .contains("affected_rows := SQL%ROWCOUNT")
                .contains("EXCEPTION")
                .contains("WHEN OTHERS THEN")
                .doesNotContain("DECLARE CONTINUE HANDLER")
                .doesNotContain("GET DIAGNOSTICS")
                .doesNotContain("RETURNED_SQLSTATE")
                .doesNotContain("MESSAGE_TEXT");
        assertThat(converted.sql().split("WHEN OTHERS THEN", -1).length - 1)
                .isGreaterThanOrEqualTo(5);
        assertThat(converted.report().files()).singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_SQL_EXCEPTION_HANDLER_TO_DM_BLOCK_RULE));
    }

    @Test
    void keepsSqlExceptionContinueHandlerWithProcedureCallForManualReview() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE refresh_summary()
                BEGIN
                    DECLARE state_code CHAR(5) DEFAULT '00000';
                    DECLARE error_message TEXT;
                    DECLARE CONTINUE HANDLER FOR SQLEXCEPTION
                    BEGIN
                        GET DIAGNOSTICS CONDITION 1
                            state_code = RETURNED_SQLSTATE, error_message = MESSAGE_TEXT;
                    END;
                    CALL refresh_summary_source();
                    INSERT INTO migration_log(message) VALUES (error_message);
                END;
                /
                """);

        assertThat(converted.report().manualReviewSqlCount()).isEqualTo(1);
        assertThat(converted.report().manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("HANDLER"));
        assertThat(converted.sql())
                .contains("DECLARE CONTINUE HANDLER FOR SQLEXCEPTION")
                .doesNotContain("state_code := 'HY000'");
    }

    @Test
    void sqlScriptValidationTimeoutCanBeOverriddenWithSystemProperty() {
        String property = "dm.adapter.sqlScriptStatementTimeoutSeconds";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "45");

            assertThat(SqlScriptValidator.statementTimeoutSeconds()).isEqualTo(45);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void reportsSqlScriptPlanningAndValidationProgress() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260423.sql"), "select 1 from dual;\n");
        List<String> progress = new ArrayList<>();

        new SqlScriptMigrator(
                new MySqlToDmSqlConverter(),
                new RecordingValidator(),
                progress::add
        ).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-bill",
                "",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(progress)
                .anySatisfy(message -> assertThat(message).contains("Discovered SQL script files: 1"))
                .anySatisfy(message -> assertThat(message).contains("Planning SQL script [1/1]"))
                .anySatisfy(message -> assertThat(message).contains("Planned SQL script [1/1]").contains("elapsedMs="))
                .anySatisfy(message -> assertThat(message).contains("Starting SQL script database validation"))
                .anySatisfy(message -> assertThat(message).contains("SQL script migration completed").contains("elapsedMs="));
    }

    @Test
    void parsesLargeMultilineStatementInLinearTime() {
        StringBuilder sql = new StringBuilder("insert into demo(id, content) values\n");
        int rowCount = 20_000;
        for (int i = 0; i < rowCount; i++) {
            sql.append('(')
                    .append(i)
                    .append(", 'abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789')")
                    .append(i + 1 == rowCount ? ";\n" : ",\n");
        }

        List<String> statements = assertTimeout(
                Duration.ofSeconds(5),
                () -> SqlScriptParser.statements(sql.toString())
        );

        assertThat(statements).singleElement().asString()
                .startsWith("insert into demo")
                .contains("(19999,");
    }

    @Test
    void rejectsLargeProcedureBeforeRunningConvertedTriggerRegex() {
        String largeProcedure = "CREATE OR REPLACE PROCEDURE demo AS BEGIN\n"
                + "    select 1 from dual;\n".repeat(40_000)
                + "END;";

        assertTimeout(
                Duration.ofSeconds(1),
                () -> assertThat(migrator(new RecordingValidator())
                        .isConvertedSimpleDateEndTrigger(largeProcedure))
                        .isFalse()
        );
    }

    @Test
    void parallelLargeProcedureConversionMatchesSequentialOutput() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        String filler = "    -- parallel conversion filler 012345678901234567890123456789\n".repeat(1_000);
        write(sqlRoot.resolve("procedures.sql"), """
                DELIMITER $$
                CREATE PROCEDURE first_proc()
                BEGIN
                %s
                    SELECT IFNULL(1, 0);
                END$$
                CREATE PROCEDURE second_proc()
                BEGIN
                %s
                    SELECT DATE_ADD(NOW(), INTERVAL 1 DAY);
                END$$
                CREATE PROCEDURE third_proc()
                BEGIN
                %s
                    SELECT 3;
                END$$
                DELIMITER ;
                """.formatted(filler, filler, filler));
        String property = "dm.adapter.sqlScriptConversionThreads";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "1");
            SqlScriptMigrationReport sequentialReport = migrateScriptRoot(
                    sqlRoot,
                    tempDir.resolve("sql/sequential")
            );
            System.setProperty(property, "3");
            SqlScriptMigrationReport parallelReport = migrateScriptRoot(
                    sqlRoot,
                    tempDir.resolve("sql/parallel")
            );

            assertThat(Files.readString(tempDir.resolve("sql/parallel/procedures.sql")))
                    .isEqualTo(Files.readString(tempDir.resolve("sql/sequential/procedures.sql")));
            assertThat(parallelReport.files().get(0).convertedStatementCount())
                    .isEqualTo(sequentialReport.files().get(0).convertedStatementCount());
            assertThat(parallelReport.files().get(0).manualReviewStatementCount())
                    .isEqualTo(sequentialReport.files().get(0).manualReviewStatementCount());
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void reportsSlowValidationStatementWithoutLoggingSqlText() {
        String property = "dm.adapter.sqlScriptSlowOperationLogMillis";
        String previous = System.getProperty(property);
        List<String> progress = new CopyOnWriteArrayList<>();
        String sensitiveSql = "select 'SENSITIVE_LITERAL' from dual";
        Statement statement = proxy(Statement.class, (ignored, method, args) -> {
            if (method.getName().equals("execute")) {
                String sql = (String) args[0];
                if (!sql.startsWith("set schema")) {
                    Thread.sleep(80L);
                }
                return true;
            }
            return defaultValue(method.getReturnType());
        });
        Connection connection = proxy(Connection.class, (ignored, method, args) -> {
            if (method.getName().equals("createStatement")) {
                return statement;
            }
            return defaultValue(method.getReturnType());
        });
        DmValidationEnvironment environment = DmValidationEnvironment.from(Map.of(
                "DM_SQL_VALIDATION", "true",
                "DM_JDBC_URL", "jdbc:dm://test-host:5236",
                "DM_DB_USERNAME", "TEST_USER",
                "DM_DB_PASSWORD", "TEST_PASSWORD"
        ));

        try {
            System.setProperty(property, "5");
            SqlScriptValidationRun result = new SqlScriptValidator(env -> connection, progress::add).validate(
                    List.of(new SqlScriptMigrator.PlannedSqlScriptFile(
                            "slow.sql",
                            "slow.sql",
                            "sample-bill",
                            false,
                            true,
                            false,
                            1,
                            0,
                            0,
                            Set.of(),
                            List.of(),
                            List.of(sensitiveSql)
                    )),
                    environment
            );

            assertThat(result.successCount()).isEqualTo(1);
            assertThat(progress)
                    .anySatisfy(message -> assertThat(message)
                            .contains("Slow SQL script statement still running")
                            .contains("file=slow.sql")
                            .contains("statement=1/1")
                            .contains("sqlType=SELECT"));
            assertThat(String.join("\n", progress)).doesNotContain("SENSITIVE_LITERAL");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private SqlScriptMigrator migrator(SqlScriptMigrator.Validator validator) {
        return new SqlScriptMigrator(new MySqlToDmSqlConverter(), validator);
    }

    private SqlScriptMigrator.PlannedSqlScriptFile plannedValidationFile(
            String file,
            String schema,
            List<String> statements
    ) {
        return new SqlScriptMigrator.PlannedSqlScriptFile(
                file,
                file,
                schema,
                false,
                true,
                false,
                statements.size(),
                0,
                0,
                Set.of(),
                List.of(),
                statements
        );
    }

    private DmValidationEnvironment validationEnvironment() {
        return DmValidationEnvironment.from(Map.of(
                "DM_SQL_VALIDATION", "true",
                "DM_JDBC_URL", "jdbc:dm://localhost:5236",
                "DM_DB_USERNAME", "SYSDBA",
                "DM_DB_PASSWORD", "SYSDBA"
        ));
    }

    @Test
    void preservesDefaultBaseScriptAndUsesItsRoutineForProcedureDependencyChecks() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("00000000.sql"), "select 'mysql base';\n");
        write(sqlRootOut.resolve("00000000.sql"), """
                CREATE OR REPLACE PROCEDURE log_sql_execution() AS
                BEGIN
                    NULL;
                END;
                /
                """);
        write(sqlRoot.resolve("20260205.sql"), """
                DELIMITER $$
                CREATE PROCEDURE batch_insert()
                BEGIN
                    CALL log_sql_execution();
                END$$
                DELIMITER ;
                """);
        RecordingValidator validator = new RecordingValidator();

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir, sqlRoot, sqlRootOut, false, "sample-app", "", DmValidationEnvironment.from(Map.of())
        ));

        assertThat(Files.readString(sqlRootOut.resolve("00000000.sql"))).contains("log_sql_execution");
        assertThat(report.scannedFileCount()).isEqualTo(2);
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(validator.files).extracting(SqlScriptMigrator.PlannedSqlScriptFile::sourceDisplay)
                .contains("(output-only) 00000000.sql", "20260205.sql");
    }

    @Test
    void usesPreservedDamengBaseScriptForDependencyChecksDuringDryRun() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("00000000.sql"), "select 'mysql base';\n");
        write(sqlRootOut.resolve("00000000.sql"), """
                CREATE OR REPLACE PROCEDURE batch_insert_ns_core_resourcecolumn(input_json IN JSON) AS
                BEGIN
                    NULL;
                END;
                /
                """);
        write(sqlRoot.resolve("20260205.sql"), """
                DELIMITER $$
                CREATE PROCEDURE seed_resourcecolumn()
                BEGIN
                    CALL batch_insert_ns_core_resourcecolumn('[]');
                END$$
                DELIMITER ;
                """);
        write(sqlRoot.resolve("20260507.sql"), """
                DELIMITER $$
                CREATE PROCEDURE batch_insert_ns_core_resourcecolumn(IN input_json JSON)
                BEGIN
                    SELECT 1 FROM dual;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new FailingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                true,
                "sample-system",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(report.warnings()).noneSatisfy(warning ->
                assertThat(warning).contains("batch_insert_ns_core_resourcecolumn"));
        assertThat(report.files()).extracting(com.github.dmadapter.core.SqlScriptFileResult::sourceFile)
                .contains("(preserved output) 00000000.sql");
    }

    @Test
    void treatsRoutineDependencyOutsideCurrentQueueAsExternalDependency() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205.sql"), """
                DELIMITER $$
                CREATE PROCEDURE batch_insert()
                BEGIN
                    CALL log_sql_execution();
                END$$
                DELIMITER ;
                """);
        RecordingValidator validator = new RecordingValidator();

        SqlScriptMigrationReport report = migrator(validator).migrate(new SqlScriptMigrationRequest(
                tempDir, sqlRoot, sqlRootOut, false, "sample-app", "", DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(report.warnings()).noneSatisfy(warning ->
                assertThat(warning).contains("log_sql_execution"));
        assertThat(validator.files).singleElement().satisfies(file ->
                assertThat(file.manualReviewStatementIndexes()).isEmpty());
    }

    @Test
    void warnsAboutExternalProcedureDependenciesWhenDatabaseValidationIsSkipped() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205_system.sql"), """
                DELIMITER $$
                CREATE PROCEDURE batch_insert()
                BEGIN
                    CALL log_sql_execution();
                    CALL addorupdate_dictionary();
                    CALL log_sql_execution();
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new FailingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                true,
                "sample-app",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("schema=sample-system")
                .contains("addorupdate_dictionary")
                .contains("log_sql_execution"));
    }

    @Test
    void warnsAboutExternalProcedureDependenciesWhenValidationConnectionFails() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205_system.sql"), """
                DELIMITER $$
                CREATE PROCEDURE batch_insert()
                BEGIN
                    CALL log_sql_execution();
                END$$
                DELIMITER ;
                """);
        SqlScriptMigrator.Validator unavailableValidator = (files, environment) ->
                SqlScriptValidationRun.notAttempted(
                        "Dameng SQL script validation connection failed.",
                        List.of("connection unavailable")
                );

        SqlScriptMigrationReport report = migrator(unavailableValidator).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-app",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("schema=sample-system")
                .contains("log_sql_execution"));
    }

    @Test
    void recompilesProcedureAfterSafeForwardDependencyInSameFile() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205.sql"), """
                DELIMITER $$
                CREATE PROCEDURE caller_proc()
                BEGIN
                    CALL later_proc();
                END$$
                CREATE PROCEDURE later_proc()
                BEGIN
                    SELECT 1 FROM dual;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir, sqlRoot, sqlRootOut, false, "sample-app", "", DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
        String output = Files.readString(sqlRootOut.resolve("20260205.sql"));
        assertThat(output)
                .contains("CREATE OR REPLACE PROCEDURE caller_proc() AS")
                .contains("CREATE OR REPLACE PROCEDURE later_proc() AS")
                .contains("ALTER PROCEDURE caller_proc COMPILE");
        assertThat(output.indexOf("CREATE OR REPLACE PROCEDURE later_proc() AS"))
                .isLessThan(output.indexOf("ALTER PROCEDURE caller_proc COMPILE"));
        assertThat(report.files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules()).contains(
                        SqlScriptMigrator.DM_PROCEDURE_RECOMPILE_AFTER_FORWARD_DEPENDENCY_RULE
                ));
    }

    @Test
    void doesNotUseProcedureDeclaredInAnotherSchemaToSatisfyDependency() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205.sql"), """
                DELIMITER $$
                CREATE PROCEDURE shared_proc()
                BEGIN
                    SELECT 1 FROM dual;
                END$$
                DELIMITER ;
                """);
        write(sqlRoot.resolve("20260205_system.sql"), """
                DELIMITER $$
                CREATE PROCEDURE system_caller()
                BEGIN
                    CALL shared_proc();
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new FailingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                true,
                "sample-app",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning)
                .contains("schema=sample-system")
                .contains("shared_proc"));
    }

    @Test
    void respectsSchemaQualifiedProcedureDependencyOrdering() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260204.sql"), """
                DELIMITER $$
                CREATE PROCEDURE app_caller()
                BEGIN
                    CALL `sample-system`.`shared_proc`();
                END$$
                DELIMITER ;
                """);
        write(sqlRoot.resolve("20260205_system.sql"), """
                DELIMITER $$
                CREATE PROCEDURE shared_proc()
                BEGIN
                    SELECT 1 FROM dual;
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-app",
                "sample-system",
                DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems()).singleElement().satisfies(item -> assertThat(item.reason())
                .contains("依赖顺序错误")
                .contains("sample-system.shared_proc"));
    }

    @Test
    void allowsRecursiveProcedureCalls() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("20260205.sql"), """
                DELIMITER $$
                CREATE PROCEDURE recursive_proc()
                BEGIN
                    CALL recursive_proc();
                END$$
                DELIMITER ;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir, sqlRoot, sqlRootOut, false, "sample-app", "", DmValidationEnvironment.from(Map.of())
        ));

        assertThat(report.manualReviewSqlCount()).isZero();
    }

    @Test
    void rewritesQuotedLengthEqualityToSafeExpansionAndKeepsSchemaLocal() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                DROP PROCEDURE IF EXISTS change_col_ns_contract_bpm_bpmurl;

                CREATE OR REPLACE PROCEDURE change_col_ns_contract_bpm_bpmurl() AS
                    dm_adapter_exists INT;
                BEGIN
                    SELECT COUNT(*) INTO dm_adapter_exists FROM (
                        SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS
                        WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
                          AND TABLE_NAME = 'ns_contract_bpm'
                          AND COLUMN_NAME = 'bpmUrl'
                          AND CHAR_LENGTH = '300'
                    ) dm_adapter_exists_check;
                    IF dm_adapter_exists = 0 THEN
                        EXECUTE IMMEDIATE 'ALTER TABLE ns_contract_bpm MODIFY bpmUrl varchar(300) DEFAULT NULL';
                    END IF;
                END;
                /

                CALL change_col_ns_contract_bpm_bpmurl();
                DROP PROCEDURE IF EXISTS change_col_ns_contract_bpm_bpmurl;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("DECLARE")
                .contains("dm_adapter_schema VARCHAR(128) := SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID);")
                .contains("OWNER = dm_adapter_schema")
                .contains("UPPER(TABLE_NAME) = UPPER('ns_contract_bpm')")
                .contains("UPPER(COLUMN_NAME) = UPPER('bpmUrl')")
                .contains("UPPER(DATA_TYPE) IN ('CHAR', 'VARCHAR', 'VARCHAR2') AND CHAR_LENGTH < 300")
                .contains("IF dm_adapter_exists > 0 THEN")
                .doesNotContain("CREATE OR REPLACE PROCEDURE")
                .doesNotContain("CALL change_col_ns_contract_bpm_bpmurl")
                .doesNotContain("DROP PROCEDURE IF EXISTS")
                .doesNotContain("dm_adapter_schema IN VARCHAR")
                .doesNotContain("SYS_CONTEXT")
                .doesNotContain("'sample-app'");
    }

    @Test
    void removesLegacyCurrentSchemaParameterFromLowCodeProcedure() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                DROP PROCEDURE IF EXISTS change_col_ns_contract_bpm_bpmurl;

                CREATE OR REPLACE PROCEDURE change_col_ns_contract_bpm_bpmurl (
                    dm_adapter_schema IN VARCHAR
                        DEFAULT SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
                ) AS
                    dm_adapter_exists INT;
                BEGIN
                    SELECT COUNT(*) INTO dm_adapter_exists FROM (
                        SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS
                        WHERE OWNER = dm_adapter_schema
                          AND UPPER(TABLE_NAME) = UPPER('ns_contract_bpm')
                          AND UPPER(COLUMN_NAME) = UPPER('bpmUrl')
                          AND CHAR_LENGTH = '300'
                    ) dm_adapter_exists_check;
                    IF dm_adapter_exists = 0 THEN
                        EXECUTE IMMEDIATE
                            'ALTER TABLE ns_contract_bpm MODIFY bpmUrl varchar(300) DEFAULT NULL';
                    END IF;
                END;
                /

                CALL change_col_ns_contract_bpm_bpmurl (
                    SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
                );
                DROP PROCEDURE IF EXISTS change_col_ns_contract_bpm_bpmurl;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("DECLARE")
                .contains("dm_adapter_schema VARCHAR(128) := SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID);")
                .doesNotContain("CREATE OR REPLACE PROCEDURE")
                .doesNotContain("CALL change_col_ns_contract_bpm_bpmurl")
                .doesNotContain("DROP PROCEDURE IF EXISTS")
                .doesNotContain("dm_adapter_schema IN VARCHAR")
                .doesNotContain("SYS_CONTEXT")
                .doesNotContain("'sample-app'");
    }

    @Test
    void resolvesMysqlProcedureMetadataSchemaAtRuntimeWithoutEmbeddingCliSchema() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                DROP PROCEDURE IF EXISTS add_column_organization_no;
                DELIMITER $$
                CREATE PROCEDURE `add_column_organization_no`()
                BEGIN
                    IF NOT EXISTS (
                        SELECT COLUMN_NAME
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE table_name = 'ns_system_organization'
                          AND table_schema = (SELECT DATABASE())
                          AND column_name = 'organization_no'
                    ) THEN
                        ALTER TABLE `ns_system_organization`
                            ADD COLUMN `organization_no` varchar(64) NULL COMMENT '组织编号';
                    END IF;
                END$$
                DELIMITER ;
                CALL `add_column_organization_no`();
                DROP PROCEDURE IF EXISTS add_column_organization_no;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("DECLARE")
                .contains("FROM SYS.SYSOBJECTS T")
                .contains("JOIN SYS.SYSCOLUMNS C ON C.ID = T.ID")
                .contains("T.SCHID = CURRENT_SCHID")
                .contains("T.NAME IN ('ns_system_organization', UPPER('ns_system_organization'))")
                .contains("C.NAME IN ('organization_no', UPPER('organization_no'))")
                .doesNotContain("CREATE OR REPLACE PROCEDURE")
                .doesNotContain("CALL add_column_organization_no")
                .doesNotContain("DROP PROCEDURE IF EXISTS")
                .doesNotContain("INFORMATION_SCHEMA")
                .doesNotContain("ALL_TAB_COLUMNS")
                .doesNotContain("DATABASE()")
                .doesNotContain("SYS_CONTEXT")
                .doesNotContain("dm_adapter_schema")
                .doesNotContain("'sample-app'");
    }

    @Test
    void usesExplicitCharacterSemanticsForUtf8mb4VarcharOnByteLengthTargets() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("table.sql"), """
                CREATE TABLE demo (
                    id BIGINT,
                    display_name VARCHAR(100)
                ) DEFAULT CHARSET=utf8mb4;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.offline(TargetLengthSemantics.BYTE),
                        null
                )
        );

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("table.sql")))
                .contains("VARCHAR(100 CHAR)")
                .doesNotContain("VARCHAR(400)");
    }

    @Test
    void byteLengthRewriteDoesNotChangeTypeTextInsideStringLiterals() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("table.sql"), """
                CREATE TABLE demo (
                    display_name VARCHAR(100) DEFAULT 'source VARCHAR(255)'
                ) DEFAULT CHARSET=utf8mb4;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.offline(TargetLengthSemantics.BYTE),
                        null
                )
        );

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("table.sql")))
                .contains("VARCHAR(100 CHAR)")
                .contains("'source VARCHAR(255)'")
                .doesNotContain("'source VARCHAR(255 CHAR)'");
    }

    @Test
    void usesExplicitCharacterSemanticsForUtf8VarcharOnByteLengthTargets() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("table.sql"), """
                CREATE TABLE demo (
                    display_name VARCHAR(100)
                ) DEFAULT CHARSET=utf8;
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.offline(TargetLengthSemantics.BYTE),
                        null
                )
        );

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("table.sql")))
                .contains("VARCHAR(100 CHAR)")
                .doesNotContain("VARCHAR(300)");
    }

    @Test
    void usesExplicitCharacterSemanticsWhenSourceCharsetIsUnknown() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("table.sql"), "CREATE TABLE demo (display_name VARCHAR(100));");

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.offline(TargetLengthSemantics.BYTE),
                        null
                )
        );

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("table.sql")))
                .contains("VARCHAR(100 CHAR)");
    }

    @Test
    void usesExplicitCharacterSemanticsForMixedUtf8CharsetsOnByteLengthTarget() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        String original = """
                CREATE TABLE demo (
                    legacy_name VARCHAR(100) CHARACTER SET utf8,
                    display_name VARCHAR(100) CHARACTER SET utf8mb4
                ) DEFAULT CHARSET=utf8;
                """;
        write(sqlRoot.resolve("table.sql"), original);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.offline(TargetLengthSemantics.BYTE),
                        null
                )
        );

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("table.sql")))
                .contains("legacy_name VARCHAR(100 CHAR)")
                .contains("display_name VARCHAR(100 CHAR)");
    }

    @Test
    void keepsExplicitNonUtf8CharsetDdlForManualReviewOnByteLengthTarget() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        String original = """
                CREATE TABLE demo (
                    legacy_name VARCHAR(100) CHARACTER SET 'latin1'
                ) DEFAULT CHARSET='latin1';
                """;
        write(sqlRoot.resolve("table.sql"), original);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.offline(TargetLengthSemantics.BYTE),
                        null
                )
        );

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems()).singleElement().satisfies(item ->
                assertThat(item.reason()).contains("非 UTF-8 源字符集").contains("latin1"));
        assertThat(Files.readString(sqlRootOut.resolve("table.sql"))).contains(original.strip());
    }

    @Test
    void addsCharacterSemanticsToCharAndDoesNotDuplicateExistingSemantics() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("table.sql"), """
                CREATE TABLE demo (
                    code CHAR(10),
                    display_name VARCHAR(100 CHAR)
                );
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.offline(TargetLengthSemantics.BYTE),
                        null
                )
        );

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("table.sql")))
                .contains("CHAR(10 CHAR)")
                .contains("VARCHAR(100 CHAR)")
                .doesNotContain("CHAR(10 CHAR CHAR)")
                .doesNotContain("VARCHAR(100 CHAR CHAR)");
    }

    @Test
    void usesCharacterLengthGuardForDynamicDdlOnByteLengthTarget() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), """
                CREATE OR REPLACE PROCEDURE change_demo() AS
                    dm_adapter_exists INT;
                BEGIN
                    SELECT COUNT(*) INTO dm_adapter_exists FROM (
                        SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS
                        WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
                          AND TABLE_NAME = 'demo'
                          AND COLUMN_NAME = 'display_name'
                          AND CHAR_LENGTH = '100'
                    ) dm_adapter_exists_check;
                    IF dm_adapter_exists = 0 THEN
                        EXECUTE IMMEDIATE
                            'ALTER TABLE demo MODIFY display_name VARCHAR(100) DEFAULT NULL';
                    END IF;
                END;
                /
                CALL change_demo();
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.offline(TargetLengthSemantics.BYTE),
                        null
                )
        );

        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(Files.readString(sqlRootOut.resolve("procedure.sql")))
                .contains("CHAR_LENGTH < 100")
                .contains("VARCHAR(100 CHAR)")
                .doesNotContain("DATA_LENGTH = 400");
    }

    @Test
    void preservesSourceCaseForBacktickAddedColumn() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                DROP PROCEDURE IF EXISTS add_clo_ns_wms_parameter_setting_paramName;
                CREATE PROCEDURE add_clo_ns_wms_parameter_setting_paramName()
                BEGIN
                    IF NOT EXISTS (
                        SELECT COLUMN_NAME
                        FROM information_schema.COLUMNS
                        WHERE table_schema = database()
                          AND table_name = 'ns_wms_parameter_setting'
                          AND column_name = 'paramName'
                    ) THEN
                        ALTER TABLE ns_wms_parameter_setting
                            ADD COLUMN `paramName` varchar(255) DEFAULT NULL;
                    END IF;
                END;
                /
                CALL add_clo_ns_wms_parameter_setting_paramName();
                DROP PROCEDURE IF EXISTS add_clo_ns_wms_parameter_setting_paramName;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("C.NAME IN ('paramName', UPPER('paramName'))")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE ns_wms_parameter_setting")
                .contains("ADD `paramName` varchar(255) DEFAULT NULL'")
                .doesNotContain("CREATE OR REPLACE PROCEDURE")
                .doesNotContain("CALL add_clo_ns_wms_parameter_setting_paramName")
                .doesNotContain("DROP PROCEDURE IF EXISTS")
                .doesNotContain("dm_adapter_schema IN VARCHAR");
    }

    @Test
    void keepsLengthSensitiveDdlForManualReviewWhenTargetSemanticsAreUnknown() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        String original = "CREATE TABLE demo (display_name VARCHAR(100)) DEFAULT CHARSET=utf8mb4;";
        write(sqlRoot.resolve("table.sql"), original);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir,
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "sample-app",
                        "",
                        List.of(),
                        DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.unknown(),
                        null
                )
        );

        assertThat(report.manualReviewSqlCount()).isEqualTo(1);
        assertThat(report.manualReviewItems()).singleElement().satisfies(item ->
                assertThat(item.reason()).contains("LENGTH_IN_CHAR 未知"));
        assertThat(Files.readString(sqlRootOut.resolve("table.sql"))).contains(original);
    }

    @Test
    void recompilesLocalProcedureWhenDependencyChangedBeforeCall() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE refresh_demo()
                BEGIN
                    SELECT COUNT(*) FROM demo;
                END;
                /
                ALTER TABLE demo ADD status INT;
                CALL refresh_demo();
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("ALTER TABLE demo ADD status INT")
                .contains("ALTER PROCEDURE refresh_demo COMPILE")
                .contains("CALL refresh_demo()");
        assertThat(converted.sql().indexOf("ALTER PROCEDURE refresh_demo COMPILE"))
                .isLessThan(converted.sql().indexOf("CALL refresh_demo()"));
    }

    @Test
    void keepsStaticDependencyBeforeFinalDdlWithoutManualReview() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE unsafe_demo()
                BEGIN
                    SELECT COUNT(*) FROM demo;
                    ALTER TABLE demo ADD status INT;
                END;
                /
                CALL unsafe_demo();
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("SELECT COUNT(*) FROM demo")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo ADD status INT'")
                .doesNotContain("EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM demo'");
    }

    @Test
    void rewritesStaticUpdateJoinAfterDynamicDdl() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE change_demo()
                BEGIN
                    ALTER TABLE demo ADD status VARCHAR(20);
                    UPDATE demo
                    INNER JOIN demo_source ON demo.id = demo_source.id
                    SET demo.status = 'ready';
                END;
                /
                CALL change_demo();
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo ADD status VARCHAR(20)'")
                .contains("EXECUTE IMMEDIATE 'update demo")
                .contains("from demo_source where demo.id = demo_source.id")
                .contains("set status = ''ready''")
                .doesNotContain("newsee-system.");
        assertThat(converted.report().files()).singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.DM_PROCEDURE_SAME_OBJECT_STATIC_SQL_TO_DYNAMIC_RULE));
    }

    @Test
    void rewritesSelectIntoAndInsertAfterConditionalCreate() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE add_menu_version()
                BEGIN
                    DECLARE dm_adapter_exists INT DEFAULT 0;
                    CREATE TABLE IF NOT EXISTS ns_menu_version (
                        ver INT PRIMARY KEY
                    );
                    SELECT COUNT(*) INTO dm_adapter_exists FROM (
                        SELECT 1 FROM ns_menu_version
                    ) dm_adapter_exists_check;
                    IF dm_adapter_exists = 0 THEN
                        INSERT INTO ns_menu_version(ver) VALUES (1);
                    END IF;
                END;
                /
                CALL add_menu_version();
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("EXECUTE IMMEDIATE 'CREATE TABLE IF NOT EXISTS ns_menu_version")
                .contains("EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM (")
                .contains("SELECT 1 FROM ns_menu_version")
                .contains("' INTO dm_adapter_exists")
                .contains("EXECUTE IMMEDIATE 'INSERT INTO ns_menu_version(ver) VALUES (1)'");
    }

    @Test
    void bindsProcedureInputsWhenRewritingPostDdlDml() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE change_demo(new_status VARCHAR(20), target_id BIGINT)
                BEGIN
                    ALTER TABLE demo ADD status VARCHAR(20);
                    UPDATE demo
                    SET status = new_status
                    WHERE id = target_id OR parent_id = target_id;
                END;
                /
                CALL change_demo('ready', 1);
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("EXECUTE IMMEDIATE 'UPDATE demo")
                .contains("SET status = ?")
                .contains("WHERE id = ? OR parent_id = ?' USING new_status, target_id, target_id")
                .doesNotContain("SET ? = ?");
    }

    @Test
    void bindsProcedureInputAfterSelectIntoWhenRewritingPostDdlQuery() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE count_demo(new_status VARCHAR(20))
                BEGIN
                    DECLARE matching_count BIGINT;
                    ALTER TABLE demo ADD status VARCHAR(20);
                    SELECT COUNT(*) INTO matching_count
                    FROM demo
                    WHERE status = new_status;
                END;
                /
                CALL count_demo('ready');
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("EXECUTE IMMEDIATE 'SELECT COUNT(*)")
                .contains("FROM demo")
                .contains("WHERE status = ?' INTO matching_count USING new_status")
                .doesNotContain("INTO ?")
                .doesNotContain("WHERE status = new_status");
    }

    @Test
    void rewritesTrailingSelectIntoAfterDynamicDdl() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE count_demo()
                BEGIN
                    DECLARE matching_count BIGINT;
                    CREATE TABLE demo_snapshot AS SELECT id, status FROM demo_source;
                    SELECT COUNT(*)
                    FROM (
                        SELECT status
                        FROM demo_snapshot
                        GROUP BY status
                    ) grouped_status
                    INTO matching_count;
                    DROP TABLE demo_snapshot;
                END;
                /
                CALL count_demo();
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("EXECUTE IMMEDIATE 'CREATE TABLE demo_snapshot AS SELECT id, status FROM demo_source'")
                .contains("EXECUTE IMMEDIATE 'SELECT COUNT(*)")
                .contains("FROM demo_snapshot")
                .contains("GROUP BY status")
                .contains(") grouped_status' INTO matching_count")
                .doesNotContain("grouped_status\n    INTO matching_count");
    }

    @Test
    void keepsLoopWithSameObjectDdlForManualReview() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE change_demo()
                BEGIN
                    WHILE 1 = 0 DO
                        ALTER TABLE demo ADD status INT;
                        UPDATE demo SET status = 1;
                    END WHILE;
                END;
                /
                CALL change_demo();
                """);

        assertThat(converted.report().manualReviewSqlCount()).isEqualTo(2);
        assertThat(converted.report().manualReviewItems())
                .extracting(SqlScriptManualReviewItem::reason)
                .anySatisfy(reason -> assertThat(reason).contains("循环"));
    }

    @Test
    void recompilesReaderAfterCallingRoutineThatChangesItsDependency() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE read_demo()
                BEGIN
                    SELECT COUNT(*) FROM demo;
                END;
                /
                CREATE PROCEDURE change_demo()
                BEGIN
                    EXECUTE IMMEDIATE 'ALTER TABLE demo ADD status INT';
                END;
                /
                CALL change_demo();
                CALL read_demo();
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("CALL change_demo()")
                .contains("ALTER PROCEDURE read_demo COMPILE")
                .contains("CALL read_demo()");
        assertThat(converted.sql().indexOf("CALL change_demo()"))
                .isLessThan(converted.sql().indexOf("ALTER PROCEDURE read_demo COMPILE"));
        assertThat(converted.sql().indexOf("ALTER PROCEDURE read_demo COMPILE"))
                .isLessThan(converted.sql().indexOf("CALL read_demo()"));
    }

    @Test
    void marksUnresolvedDynamicDdlRoutineForManualReview() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                CREATE PROCEDURE change_unknown(table_name VARCHAR)
                BEGIN
                    EXECUTE IMMEDIATE 'ALTER TABLE ' || table_name || ' ADD status INT';
                END;
                /
                CALL change_unknown('demo');
                """);

        assertThat(converted.report().manualReviewSqlCount()).isEqualTo(2);
        assertThat(converted.report().manualReviewItems())
                .extracting(SqlScriptManualReviewItem::reason)
                .anySatisfy(reason -> assertThat(reason).contains("动态 DDL 对象名无法静态解析"));
    }

    @Test
    void collapsesSingleUseDisposableProcedureIntoAnonymousBlock() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                /* demo_table 添加 status 字段 */
                DROP PROCEDURE IF EXISTS add_demo_status;
                DELIMITER $$
                CREATE PROCEDURE add_demo_status()
                BEGIN
                    IF NOT EXISTS (
                        SELECT COLUMN_NAME
                        FROM information_schema.COLUMNS
                        WHERE table_schema = (select database())
                          AND table_name = 'demo_table'
                          AND column_name = 'status'
                    ) THEN
                        ALTER TABLE demo_table ADD status varchar(20) DEFAULT NULL;
                    END IF;
                END$$
                DELIMITER ;
                CALL add_demo_status();
                DROP PROCEDURE IF EXISTS add_demo_status;
                SELECT 1 FROM dual;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("/* demo_table 添加 status 字段 */")
                .contains("DECLARE")
                .contains("FROM SYS.SYSOBJECTS T")
                .contains("JOIN SYS.SYSCOLUMNS C ON C.ID = T.ID")
                .contains("T.SCHID = CURRENT_SCHID")
                .contains("T.NAME IN ('demo_table', UPPER('demo_table'))")
                .contains("C.NAME IN ('status', UPPER('status'))")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo_table ADD status varchar(20) DEFAULT NULL'")
                .contains("SELECT 1 FROM dual")
                .doesNotContain("ALL_TAB_COLUMNS")
                .doesNotContain("dm_adapter_schema")
                .doesNotContain("CREATE OR REPLACE PROCEDURE add_demo_status")
                .doesNotContain("CALL add_demo_status")
                .doesNotContain("DROP PROCEDURE IF EXISTS add_demo_status");
        assertThat(SqlScriptParser.statements(converted.sql())).hasSize(2);
        assertThat(converted.report().files()).singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(
                                SqlScriptMigrator.DM_CURRENT_SCHEMA_COLUMN_GUARD_TO_SYSTEM_DICTIONARY_RULE,
                                SqlScriptMigrator.DM_TRANSIENT_PROCEDURE_TO_ANONYMOUS_BLOCK_RULE
                        ));
    }

    @Test
    void currentSchemaColumnGuardDoesNotEmbedConfiguredSchema() throws Exception {
        String script = """
                DROP PROCEDURE IF EXISTS add_demo_status;
                CREATE PROCEDURE add_demo_status()
                BEGIN
                    IF NOT EXISTS (
                        SELECT COLUMN_NAME
                        FROM information_schema.COLUMNS
                        WHERE table_schema = (select database())
                          AND table_name = 'demo_table'
                          AND column_name = 'status'
                    ) THEN
                        ALTER TABLE demo_table ADD status int;
                    END IF;
                END;
                /
                CALL add_demo_status();
                DROP PROCEDURE IF EXISTS add_demo_status;
                """;
        List<String> outputs = new ArrayList<>();
        for (String schema : List.of("tenant_alpha", "tenant_beta")) {
            Path projectRoot = tempDir.resolve(schema);
            Path sqlRoot = projectRoot.resolve("sql/v2");
            Path sqlRootOut = projectRoot.resolve("sql/v2-dm");
            write(sqlRoot.resolve("procedure.sql"), script);
            migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                    projectRoot,
                    sqlRoot,
                    sqlRootOut,
                    false,
                    schema,
                    "",
                    DmValidationEnvironment.from(Map.of())
            ));
            outputs.add(Files.readString(sqlRootOut.resolve("procedure.sql")));
        }

        assertThat(outputs).hasSize(2);
        assertThat(outputs.get(0)).isEqualTo(outputs.get(1));
        assertThat(outputs).allSatisfy(output -> assertThat(output)
                .contains("T.SCHID = CURRENT_SCHID")
                .contains("T.NAME IN ('demo_table', UPPER('demo_table'))")
                .contains("C.NAME IN ('status', UPPER('status'))")
                .doesNotContain("tenant_alpha")
                .doesNotContain("tenant_beta")
                .doesNotContain("ALL_TAB_COLUMNS"));
    }

    @Test
    void removesOnlyExplicitlyConfiguredSourceSchemaQualifiersWithoutEmbeddingSchema() throws Exception {
        List<String> outputs = new ArrayList<>();
        for (String schema : List.of("tenant_alpha", "tenant_beta")) {
            Path projectRoot = tempDir.resolve("qualified-" + schema);
            Path sqlRoot = projectRoot.resolve("sql/v2");
            Path sqlRootOut = projectRoot.resolve("sql/v2-dm");
            write(sqlRoot.resolve("procedure.sql"), """
                    DELIMITER $$
                    CREATE PROCEDURE seed_demo()
                    BEGIN
                        INSERT INTO `%1$s`.`demo`(`id`, `status`) VALUES (1, 'ready');
                        UPDATE `%1$s`.`demo` SET status = 'done' WHERE id = 1;
                        INSERT INTO audit_log(demo_id)
                        SELECT d.id FROM `%1$s`.`demo` d WHERE d.status = 'done';
                    END$$
                    DELIMITER ;
                    CALL seed_demo();
                    """.formatted(schema));

            SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                    new SqlScriptMigrationRequest(
                            projectRoot,
                            sqlRoot,
                            sqlRootOut,
                            false,
                            schema,
                            "",
                            DmValidationEnvironment.from(Map.of())
                    )
            );
            assertThat(report.manualReviewSqlCount()).isZero();
            assertThat(report.files()).singleElement().satisfies(file ->
                    assertThat(file.appliedRules())
                            .contains(SqlScriptMigrator.MYSQL_TARGET_SCHEMA_QUALIFIER_REMOVAL_RULE));
            outputs.add(Files.readString(sqlRootOut.resolve("procedure.sql")));
        }

        assertThat(outputs).hasSize(2);
        assertThat(outputs.get(0)).isEqualTo(outputs.get(1));
        assertThat(outputs).allSatisfy(output -> assertThat(output)
                .contains("INSERT INTO `demo`(`id`, `status`)")
                .contains("UPDATE `demo` SET status = 'done'")
                .contains("SELECT d.id FROM `demo` d")
                .doesNotContain("tenant_alpha")
                .doesNotContain("tenant_beta"));
    }

    @Test
    void convertsCompleteSqlPayloadStoredInInsertStringLiteral() throws Exception {
        Path sqlRoot = tempDir.resolve("embedded/sql/v2");
        Path sqlRootOut = tempDir.resolve("embedded/sql/v2-dm");
        write(sqlRoot.resolve("rules.sql"), """
                INSERT INTO tenant_rule (rule_id, rule_sql)
                SELECT 1,
                       'SELECT event_id FROM `tenant_alpha`.`tenant_event` WHERE created_at >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)'
                WHERE NOT EXISTS (
                    SELECT 1 FROM tenant_rule WHERE rule_id = 1
                );
                """);

        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(
                new SqlScriptMigrationRequest(
                        tempDir.resolve("embedded"),
                        sqlRoot,
                        sqlRootOut,
                        false,
                        "tenant_alpha",
                        "",
                        DmValidationEnvironment.from(Map.of())
                )
        );

        String output = Files.readString(sqlRootOut.resolve("rules.sql"));
        assertThat(output)
                .contains("'SELECT event_id FROM `tenant_event` "
                        + "WHERE created_at >= DATEADD(DAY, -30, CURDATE())'")
                .doesNotContain("tenant_alpha")
                .doesNotContain("DATE_SUB");
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(report.files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules()).contains(
                        SqlScriptMigrator.MYSQL_EMBEDDED_SQL_LITERAL_TO_DM_RULE,
                        SqlScriptMigrator.MYSQL_TARGET_SCHEMA_QUALIFIER_REMOVAL_RULE,
                        MySqlToDmSqlConverter.MYSQL_DATE_SUB_INTERVAL_RULE
                ));
    }

    @Test
    void keepsUnsafeEmbeddedSqlPayloadForManualReview() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                INSERT INTO tenant_rule (rule_id, rule_sql)
                SELECT 2,
                       'SELECT TIMESTAMPDIFF(SECOND, started_at, finished_at)/60 FROM tenant_event'
                WHERE NOT EXISTS (
                    SELECT 1 FROM tenant_rule WHERE rule_id = 2
                );
                """);

        assertThat(converted.report().manualReviewSqlCount()).isEqualTo(1);
        assertThat(converted.sql())
                .contains("TIMESTAMPDIFF(SECOND, started_at, finished_at)/60");
        assertThat(converted.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules())
                        .doesNotContain(SqlScriptMigrator.MYSQL_EMBEDDED_SQL_LITERAL_TO_DM_RULE));
    }

    @Test
    void keepsProcedureObjectWhenItIsCalledMoreThanOnce() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                DROP PROCEDURE IF EXISTS refresh_demo;
                DELIMITER $$
                CREATE PROCEDURE refresh_demo()
                BEGIN
                    UPDATE demo_table SET status = 1;
                END$$
                DELIMITER ;
                CALL refresh_demo();
                CALL refresh_demo();
                DROP PROCEDURE IF EXISTS refresh_demo;
                """);

        assertThat(converted.sql())
                .contains("CREATE OR REPLACE PROCEDURE refresh_demo() AS")
                .contains("CALL refresh_demo()")
                .contains("DROP PROCEDURE IF EXISTS refresh_demo");
        assertThat(converted.report().files()).singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .doesNotContain(SqlScriptMigrator.DM_TRANSIENT_PROCEDURE_TO_ANONYMOUS_BLOCK_RULE));
    }

    @Test
    void collapsesIndependentLifecyclesThatReuseTheSameProcedureName() throws Exception {
        ConvertedScript converted = migrateSingleScript("""
                DROP PROCEDURE IF EXISTS add_demo_column;
                CREATE PROCEDURE add_demo_column()
                BEGIN
                    ALTER TABLE first_demo ADD status int;
                END;
                /
                CALL add_demo_column();
                DROP PROCEDURE IF EXISTS add_demo_column;

                DROP PROCEDURE IF EXISTS add_demo_column;
                CREATE PROCEDURE add_demo_column()
                BEGIN
                    ALTER TABLE second_demo ADD status int;
                END;
                /
                CALL add_demo_column();
                DROP PROCEDURE IF EXISTS add_demo_column;
                """);

        assertThat(converted.report().manualReviewSqlCount()).isZero();
        assertThat(converted.sql())
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE first_demo ADD status int'")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE second_demo ADD status int'")
                .doesNotContain("CREATE OR REPLACE PROCEDURE")
                .doesNotContain("CALL add_demo_column")
                .doesNotContain("DROP PROCEDURE IF EXISTS");
        assertThat(SqlScriptParser.statements(converted.sql()))
                .hasSize(2)
                .allSatisfy(statement -> assertThat(statement).startsWith("BEGIN"));
    }

    private ConvertedScript migrateSingleScript(String content) throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("procedure.sql"), content);
        SqlScriptMigrationReport report = migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-app",
                "",
                DmValidationEnvironment.from(Map.of())
        ));
        return new ConvertedScript(report, Files.readString(sqlRootOut.resolve("procedure.sql")));
    }

    private SqlScriptMigrationReport migrateScriptRoot(Path sqlRoot, Path sqlRootOut) throws Exception {
        return migrator(new RecordingValidator()).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "sample-app",
                "",
                DmValidationEnvironment.from(Map.of())
        ));
    }

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }

    private record ConvertedScript(SqlScriptMigrationReport report, String sql) {
    }

    private static final class RecordingValidator implements SqlScriptMigrator.Validator {
        private final List<SqlScriptMigrator.PlannedSqlScriptFile> files = new ArrayList<>();

        @Override
        public SqlScriptValidationRun validate(
                List<SqlScriptMigrator.PlannedSqlScriptFile> files,
                DmValidationEnvironment environment
        ) {
            this.files.addAll(files);
            List<SqlScriptFileValidation> fileValidations = files.stream()
                    .map(file -> new SqlScriptFileValidation(file.outputDisplay(), executableStatementCount(file), List.of()))
                    .toList();
            int successCount = fileValidations.stream().mapToInt(SqlScriptFileValidation::successCount).sum();
            return new SqlScriptValidationRun(
                    true,
                    "ok",
                    successCount,
                    0,
                    fileValidations,
                    List.of(),
                    List.of()
            );
        }

        private int executableStatementCount(SqlScriptMigrator.PlannedSqlScriptFile file) {
            int count = 0;
            for (int i = 0; i < file.statements().size(); i++) {
                int statementIndex = i + 1;
                if (file.manualReviewStatementIndexes().contains(statementIndex)) {
                    continue;
                }
                if (SqlScriptParser.executable(file.statements().get(i))) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class FailingValidator implements SqlScriptMigrator.Validator {
        @Override
        public SqlScriptValidationRun validate(
                List<SqlScriptMigrator.PlannedSqlScriptFile> files,
                DmValidationEnvironment environment
        ) {
            throw new AssertionError("dry-run must not execute SQL script validation");
        }
    }
}
