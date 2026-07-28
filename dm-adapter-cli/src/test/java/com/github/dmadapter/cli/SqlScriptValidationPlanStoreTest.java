package com.github.dmadapter.cli;

import com.github.dmadapter.core.DamengTargetCapabilities;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.TargetLengthSemantics;
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
                        TargetLengthSemantics.CHAR,
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
    void rejectsBackticksWhenTargetIsNotMysqlCompatible() {
        SqlScriptValidationPlanStore store = new SqlScriptValidationPlanStore();

        assertThatThrownBy(() -> store.verifyCapabilities(
                DamengTargetCapabilities.offline(TargetLengthSemantics.CHAR),
                new DamengTargetCapabilities(
                        TargetLengthSemantics.CHAR,
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
                DamengTargetCapabilities.offline(TargetLengthSemantics.CHAR),
                List.of(file),
                List.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("output file is missing");
    }
}
