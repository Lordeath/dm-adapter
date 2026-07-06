package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
    void validatesOutputOnlySqlFilesFromOutputRootAfterGeneratedScripts() throws Exception {
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
                .containsExactly("20260423.sql", "00000000.sql", "00000000_system.sql");
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
                .contains("DROP PROCEDURE IF EXISTS `already_safe_proc`")
                .contains("DROP PROCEDURE IF EXISTS `missing_proc`")
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
                .contains("DROP PROCEDURE IF EXISTS `pro_AddColumn`");
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
                CREATE PROCEDURE demo_proc(IN input_json JSON, out row_count int)
                label_exit:BEGIN
                    DECLARE v_index INT DEFAULT 0;
                    DECLARE v_code, v_name varchar(64);
                    IF input_json IS NULL THEN
                        LEAVE label_exit;
                    END IF;
                    DROP TEMPORARY TABLE IF EXISTS tmp_demo_a,tmp_demo_b;
                    CREATE TEMPORARY TABLE IF NOT EXISTS tmp_demo_a SELECT 1 AS id;
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
                .contains("CREATE TABLE IF NOT EXISTS tmp_demo_a (id BIGINT, enterprise_id BIGINT, organization_id BIGINT, roleid VARCHAR(200), orderindex BIGINT);")
                .contains("CREATE TABLE IF NOT EXISTS tmp_demo_b (enterprise_id BIGINT, organization_id BIGINT, roleid VARCHAR(200), orderindex BIGINT);")
                .contains("CREATE OR REPLACE PROCEDURE demo_proc(input_json IN JSON, row_count OUT int) AS")
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
                .contains("NULL;")
                .doesNotContain("label_exit:BEGIN")
                .doesNotContain("LEAVE label_exit")
                .doesNotContain("TEMPORARY TABLE")
                .doesNotContain("EXECUTE IMMEDIATE 'DROP TABLE IF EXISTS tmp_demo_a'")
                .doesNotContain("EXECUTE IMMEDIATE 'CREATE TABLE tmp_demo_a");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_TEMP_TABLE_COMPILE_PLACEHOLDER_RULE));
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
                .contains("dm_has_menu_ver BIGINT;")
                .contains("dm_new_form_data VARCHAR(4000);")
                .contains("dm_roleId VARCHAR(4000);")
                .contains("SET dm_has_menu_ver = (")
                .contains("SET dm_new_form_data = '{\"name\":\"demo\"}';")
                .contains("SELECT ROLEID INTO dm_roleId FROM ns_core_role ORDER BY id ASC LIMIT 1;")
                .contains("IF dm_has_menu_ver > 0 AND dm_roleId IS NOT NULL THEN")
                .contains("VALUES(dm_new_form_data, dm_roleId)")
                .doesNotContain("@has_menu_ver")
                .doesNotContain("@new_form_data")
                .doesNotContain("@roleId");
        assertThat(report.files())
                .singleElement()
                .satisfies(file -> assertThat(file.appliedRules())
                        .contains(SqlScriptMigrator.MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL_RULE));
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
                .contains("INSERT INTO tmp_demo (id) SELECT id")
                .contains("RESOURCEBUTTON_CODE = dm_resource_code")
                .contains("RESOURCEBUTTON_FUNCINFO_ID = p_code")
                .doesNotContain("EXECUTE IMMEDIATE 'CREATE TABLE tmp_demo");
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
                        SELECT 1 FROM information_schema.TABLES
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
                .contains("CREATE OR REPLACE PROCEDURE add_col() AS")
                .contains("ALL_TAB_COLUMNS")
                .contains("ALL_TABLES")
                .contains("ALL_IND_COLUMNS")
                .contains("OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')")
                .contains("CHAR_LENGTH")
                .contains("DATA_SCALE")
                .contains("dm_adapter_exists INT;")
                .contains("SELECT COUNT(*) INTO dm_adapter_exists FROM (")
                .contains("IF dm_adapter_exists = 0 THEN")
                .contains("IF dm_adapter_exists_5 > 0 THEN")
                .contains("EXECUTE IMMEDIATE 'alter table demo add code varchar(128) null'")
                .contains("COLUMN_NAME IN ('code')")
                .contains("HAVING COUNT(DISTINCT COLUMN_NAME) = 1")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX demo_idx_demo_code ON demo (code)'")
                .contains("COLUMN_NAME IN ('title')")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX demo_idx_demo_title ON demo (title)'")
                .contains("COLUMN_NAME IN ('amount')")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX demo_amount ON demo (amount)'")
                .contains("EXECUTE IMMEDIATE 'alter table demo MODIFY code varchar(256)'")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo MODIFY amount decimal(14, 2) null';")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo MODIFY tax decimal(14, 2) null';")
                .doesNotContain("information_schema")
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
                                SqlScriptMigrator.MYSQL_SCHEMA_SCOPED_INDEX_NAME_RULE,
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
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE `demo` ADD COLUMN `useProperties` varchar(20) DEFAULT NULL'")
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
    void convertsCreateTableOptionsWhenStatementHasLeadingComments() throws Exception {
        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        write(sqlRoot.resolve("table.sql"), """
                -- business note
                CREATE TABLE IF NOT EXISTS demo_table (
                    `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
                    `code` varchar(64) CHARACTER SET utf8 DEFAULT NULL COMMENT 'code',
                    PRIMARY KEY (`id`) USING BTREE,
                    KEY `idx_demo_code` (`code`) USING BTREE
                ) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_0900_ai_ci;
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

        String converted = Files.readString(sqlRootOut.resolve("table.sql"));
        assertThat(report.manualReviewSqlCount()).isZero();
        assertThat(converted)
                .startsWith("-- business note")
                .contains("`id` bigint NOT NULL AUTO_INCREMENT")
                .contains("`code` varchar(192) DEFAULT NULL")
                .doesNotContain("KEY `idx_demo_code`")
                .doesNotContainIgnoringCase("USING BTREE")
                .doesNotContainIgnoringCase("ENGINE")
                .doesNotContainIgnoringCase("COMMENT");
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

    private SqlScriptMigrator migrator(SqlScriptMigrator.Validator validator) {
        return new SqlScriptMigrator(new MySqlToDmSqlConverter(), validator);
    }

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
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
                    .map(file -> new SqlScriptFileValidation(file.outputDisplay(), file.statements().size(), List.of()))
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
