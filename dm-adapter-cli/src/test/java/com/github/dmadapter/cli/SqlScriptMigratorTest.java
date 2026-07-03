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
                "newsee-bill",
                "newsee-system",
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
                .contains("CREATE PROCEDURE bill_proc()")
                .contains("select 'ACTIVE' from dual;");
        assertThat(Files.readString(sqlRootOut.resolve("nested/20260423_system.sql")))
                .contains("select 'SYSTEM' from dual;");
        assertThat(validator.files)
                .extracting(SqlScriptMigrator.PlannedSqlScriptFile::schema)
                .containsExactly("newsee-bill", "newsee-system");
        assertThat(validator.files)
                .filteredOn(SqlScriptMigrator.PlannedSqlScriptFile::systemScript)
                .singleElement()
                .satisfies(file -> assertThat(file.outputDisplay()).endsWith("nested/20260423_system.sql"));
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
                "newsee-bill",
                "newsee-system",
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
                "newsee-bill",
                "newsee-system",
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
                "newsee-bill",
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
                "newsee-bill",
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
