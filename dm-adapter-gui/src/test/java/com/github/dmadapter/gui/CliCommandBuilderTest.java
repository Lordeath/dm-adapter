package com.github.dmadapter.gui;

import com.github.dmadapter.core.TargetLengthSemantics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliCommandBuilderTest {
    @TempDir
    Path tempDir;

    private final CliCommandBuilder builder = new CliCommandBuilder();

    @Test
    void buildsScanInvocationWithExplicitWorkspace() {
        GuiRunConfiguration configuration = configuration();

        CliInvocation invocation = builder.build(GuiOperation.SCAN, configuration);

        assertThat(invocation.arguments()).containsExactly(
                "scan",
                "--project", tempDir.toAbsolutePath().normalize().toString(),
                "--report-dir", tempDir.resolve("workspace").toAbsolutePath().normalize().toString(),
                "--app-module", "demo-app"
        );
        assertThat(invocation.environment()).containsEntry("DM_SQL_VALIDATION", "false");
    }

    @Test
    void scanIgnoresIncompleteMigrationOnlyOptions() {
        GuiRunConfiguration configuration = new GuiRunConfiguration(
                tempDir,
                tempDir.resolve("workspace"),
                "",
                null,
                null,
                null,
                "",
                tempDir.resolve("sql"),
                null,
                false,
                "",
                null,
                "",
                false,
                true,
                "",
                "",
                ""
        );

        CliInvocation invocation = builder.build(GuiOperation.SCAN, configuration);

        assertThat(invocation.arguments()).doesNotContain("--sql-root");
        assertThat(invocation.environment()).containsOnlyKeys("DM_SQL_VALIDATION")
                .containsEntry("DM_SQL_VALIDATION", "false");
    }

    @Test
    void buildsMigrationInvocationAndKeepsPasswordOutOfArguments() {
        Path sqlRoot = tempDir.resolve("sql");
        Path sqlRootOut = tempDir.resolve("sql-dm");
        GuiRunConfiguration configuration = new GuiRunConfiguration(
                tempDir,
                tempDir.resolve("workspace"),
                "demo-app",
                null,
                null,
                null,
                "APP",
                sqlRoot,
                sqlRootOut,
                true,
                "SYSTEM_APP",
                TargetLengthSemantics.BYTE,
                "",
                true,
                true,
                "jdbc:dm://127.0.0.1:5236",
                "tester",
                "secret-value"
        );

        CliInvocation invocation = builder.build(GuiOperation.MIGRATE, configuration);

        assertThat(invocation.arguments())
                .contains("migrate", "--sql-root", sqlRoot.toAbsolutePath().normalize().toString())
                .contains("--sql-root-out", sqlRootOut.toAbsolutePath().normalize().toString())
                .contains("--schema", "APP", "--system-schema", "SYSTEM_APP")
                .contains("--target-length-semantics", "BYTE")
                .contains("--sql-scripts-only", "--generate-validation-test")
                .doesNotContain("secret-value", "tester", "jdbc:dm://127.0.0.1:5236");
        assertThat(invocation.environment())
                .containsEntry("DM_SQL_VALIDATION", "true")
                .containsEntry("DM_JDBC_URL", "jdbc:dm://127.0.0.1:5236")
                .containsEntry("DM_DB_USERNAME", "tester")
                .containsEntry("DM_DB_PASSWORD", "secret-value");
    }

    @Test
    void rejectsIncompleteSqlDirectoryPair() {
        GuiRunConfiguration configuration = new GuiRunConfiguration(
                tempDir,
                tempDir.resolve("workspace"),
                "",
                null,
                null,
                null,
                "",
                tempDir.resolve("sql"),
                null,
                false,
                "",
                null,
                "",
                false,
                false,
                "",
                "",
                ""
        );

        assertThatThrownBy(() -> builder.build(GuiOperation.DRY_RUN, configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须同时填写");
    }

    @Test
    void dryRunDoesNotRequestValidationTestGeneration() {
        GuiRunConfiguration configuration = new GuiRunConfiguration(
                tempDir,
                tempDir.resolve("workspace"),
                "",
                null,
                null,
                null,
                "APP",
                null,
                null,
                false,
                "",
                null,
                "",
                true,
                false,
                "",
                "",
                ""
        );

        CliInvocation invocation = builder.build(GuiOperation.DRY_RUN, configuration);

        assertThat(invocation.arguments())
                .contains("migrate", "--dry-run")
                .doesNotContain("--generate-validation-test", "--schema");
    }

    private GuiRunConfiguration configuration() {
        return new GuiRunConfiguration(
                tempDir,
                tempDir.resolve("workspace"),
                "demo-app",
                null,
                null,
                null,
                "",
                null,
                null,
                false,
                "",
                null,
                "",
                false,
                false,
                "",
                "",
                ""
        );
    }
}
