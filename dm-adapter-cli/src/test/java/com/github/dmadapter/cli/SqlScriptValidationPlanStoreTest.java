package com.github.dmadapter.cli;

import com.github.dmadapter.core.DamengTargetCapabilities;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlScriptValidationPlanStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsLegacyLengthSemanticsValidationPlanFormat() throws Exception {
        Path plan = tempDir.resolve(SqlScriptValidationPlanStore.DEFAULT_FILE_NAME);
        Files.writeString(plan, """
                {
                  "formatVersion": 1,
                  "targetCapabilities": {
                    "lengthSemantics": "CHAR"
                  }
                }
                """);

        assertThatThrownBy(() -> new SqlScriptValidationPlanStore().load(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported SQL validation plan format version: 1");
    }

    @Test
    void roundTripsDmProcedureAfterCommentedMysqlProcedure() {
        List<String> statements = List.of(
                """
                        /*DROP PROCEDURE IF EXISTS ignored_proc;
                        DELIMITER $$
                        CREATE PROCEDURE ignored_proc()
                        BEGIN
                            SELECT 1;
                        END$$
                        DELIMITER ;
                        CALL ignored_proc();
                        DROP PROCEDURE IF EXISTS ignored_proc;*/

                        DROP PROCEDURE IF EXISTS active_proc
                        """,
                """
                        CREATE OR REPLACE PROCEDURE active_proc AS
                        BEGIN
                            NULL;
                        END
                        """,
                "CALL active_proc()"
        );

        String rendered = SqlScriptParser.scriptContent(statements);

        assertThat(SqlScriptParser.statements(rendered))
                .hasSize(3)
                .element(0).asString().contains("DROP PROCEDURE IF EXISTS active_proc");
        assertThat(SqlScriptParser.statements(rendered))
                .element(1).asString().startsWith("CREATE OR REPLACE PROCEDURE active_proc");
        assertThat(SqlScriptParser.statements(rendered))
                .element(2).asString().isEqualTo("CALL active_proc()");
    }

    @Test
    void writesLoadsAndRejectsTamperedSql() throws Exception {
        Path outputRoot = tempDir.resolve("sql-dm");
        Path output = outputRoot.resolve("demo.sql");
        Files.createDirectories(output.getParent());
        List<String> statements = List.of("SELECT 1", "CALL manual_proc()");
        Files.writeString(output, SqlScriptParser.scriptContent(statements));
        SqlScriptMigrator.PlannedSqlScriptFile file = new SqlScriptMigrator.PlannedSqlScriptFile(
                "demo.sql",
                output.toString(),
                "sample-system",
                true,
                true,
                true,
                2,
                1,
                1,
                Set.of(2),
                List.of("TEST_RULE"),
                statements
        );
        Path plan = tempDir.resolve(SqlScriptValidationPlanStore.DEFAULT_FILE_NAME);
        SqlScriptValidationPlanStore store = new SqlScriptValidationPlanStore();

        store.write(
                plan,
                tempDir,
                outputRoot,
                new DamengTargetCapabilities(
                        "4",
                        "0",
                        "0",
                        "0",
                        "TEST"
                ),
                List.of(file),
                List.of(new SqlScriptManualReviewItem(
                        "demo.sql",
                        output.toString(),
                        2,
                        "manual",
                        "CALL manual_proc()",
                        "CALL manual_proc()"
                ))
        );

        SqlScriptValidationPlanStore.LoadedValidationPlan loaded = store.load(plan);
        assertThat(loaded.manualReviewCount()).isEqualTo(1);
        assertThat(loaded.files()).singleElement().satisfies(loadedFile ->
                assertThat(loadedFile.manualReviewStatementIndexes()).containsExactly(2));

        Files.writeString(output, "SELECT 2;\nCALL manual_proc();\n");
        assertThatThrownBy(() -> store.load(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash does not match");
    }

    @Test
    void hashesStatementsAsParsedFromTheWrittenScript() throws Exception {
        Path outputRoot = tempDir.resolve("sql-dm");
        Path output = outputRoot.resolve("trailing-whitespace.sql");
        Files.createDirectories(output.getParent());
        List<String> migratedStatements = List.of("SELECT 1;\r\n    ");
        Files.writeString(output, SqlScriptParser.scriptContent(migratedStatements));
        SqlScriptMigrator.PlannedSqlScriptFile file = new SqlScriptMigrator.PlannedSqlScriptFile(
                "trailing-whitespace.sql",
                output.toString(),
                "sample-system",
                true,
                true,
                true,
                1,
                1,
                0,
                Set.of(),
                List.of("TEST_RULE"),
                migratedStatements
        );
        Path plan = tempDir.resolve(SqlScriptValidationPlanStore.DEFAULT_FILE_NAME);
        SqlScriptValidationPlanStore store = new SqlScriptValidationPlanStore();

        store.write(
                plan,
                tempDir,
                outputRoot,
                DamengTargetCapabilities.unknown(),
                List.of(file),
                List.of()
        );

        assertThat(store.load(plan).files())
                .singleElement()
                .satisfies(loadedFile ->
                        assertThat(loadedFile.statements()).containsExactly("SELECT 1"));
    }

    @Test
    void preservesConsumedStatementsAsExecutableDamengNoOps() throws Exception {
        Path outputRoot = tempDir.resolve("sql-dm");
        Path output = outputRoot.resolve("consumed-mysql-statements.sql");
        Files.createDirectories(output.getParent());
        List<String> migratedStatements = List.of(
                "-- DM_ADAPTER: MySQL script variable @db_name uses the current schema",
                "SELECT 1"
        );
        Files.writeString(output, SqlScriptParser.scriptContent(migratedStatements));
        SqlScriptMigrator.PlannedSqlScriptFile file = new SqlScriptMigrator.PlannedSqlScriptFile(
                "consumed-mysql-statements.sql",
                output.toString(),
                "sample-system",
                true,
                true,
                true,
                2,
                1,
                0,
                Set.of(),
                List.of("TEST_RULE"),
                migratedStatements
        );
        Path plan = tempDir.resolve(SqlScriptValidationPlanStore.DEFAULT_FILE_NAME);
        SqlScriptValidationPlanStore store = new SqlScriptValidationPlanStore();

        store.write(
                plan,
                tempDir,
                outputRoot,
                DamengTargetCapabilities.unknown(),
                List.of(file),
                List.of()
        );

        assertThat(Files.readString(output))
                .contains("-- DM_ADAPTER: MySQL script variable @db_name uses the current schema")
                .contains("BEGIN\n    NULL;\nEND;\n/");
        assertThat(store.load(plan).files())
                .singleElement()
                .satisfies(loadedFile -> {
                    assertThat(loadedFile.statements()).hasSize(2);
                    assertThat(loadedFile.statements().get(0))
                            .contains("-- DM_ADAPTER:")
                            .contains("BEGIN")
                            .contains("NULL;")
                            .contains("END;");
                });
    }

    @Test
    void rejectsBackticksWhenTargetIsNotMysqlCompatible() {
        SqlScriptValidationPlanStore store = new SqlScriptValidationPlanStore();

        assertThatThrownBy(() -> store.verifyCapabilities(
                DamengTargetCapabilities.unknown(),
                new DamengTargetCapabilities(
                        "0",
                        "0",
                        "0",
                        "0",
                        "TEST"
                ),
                true
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COMPATIBLE_MODE");
    }

    @Test
    void rejectsMissingOutputWhenWritingStrictPlan() {
        Path outputRoot = tempDir.resolve("sql-dm");
        Path missingOutput = outputRoot.resolve("missing.sql");
        SqlScriptMigrator.PlannedSqlScriptFile file = new SqlScriptMigrator.PlannedSqlScriptFile(
                "missing.sql",
                missingOutput.toString(),
                "sample-system",
                true,
                true,
                true,
                1,
                1,
                0,
                Set.of(),
                List.of(),
                List.of("SELECT 1")
        );

        assertThatThrownBy(() -> new SqlScriptValidationPlanStore().write(
                tempDir.resolve(SqlScriptValidationPlanStore.DEFAULT_FILE_NAME),
                tempDir,
                outputRoot,
                DamengTargetCapabilities.unknown(),
                List.of(file),
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output file is missing");
    }
}
