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
                .contains("ALL_IND_COLUMNS")
                .contains("OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')")
                .contains("CHAR_LENGTH")
                .contains("DATA_SCALE")
                .contains("EXECUTE IMMEDIATE 'alter table demo add code varchar(128) null'")
                .contains("COLUMN_NAME IN ('code')")
                .contains("HAVING COUNT(DISTINCT COLUMN_NAME) = 1")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX demo_idx_demo_code ON demo (code)'")
                .contains("COLUMN_NAME IN ('title')")
                .contains("EXECUTE IMMEDIATE 'CREATE INDEX demo_idx_demo_title ON demo (title)'")
                .contains("EXECUTE IMMEDIATE 'alter table demo MODIFY code varchar(256)'")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo MODIFY amount decimal(14, 2) null';")
                .contains("EXECUTE IMMEDIATE 'ALTER TABLE demo MODIFY tax decimal(14, 2) null';")
                .doesNotContain("information_schema")
                .doesNotContain("database()")
                .doesNotContain("NUMERIC_SCALE")
                .doesNotContain("INDEX_NAME = 'idx_demo_code'")
                .doesNotContain("code(32)")
                .doesNotContain("title(20)")
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
                                SqlScriptMigrator.MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE_RULE
                        ));
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
