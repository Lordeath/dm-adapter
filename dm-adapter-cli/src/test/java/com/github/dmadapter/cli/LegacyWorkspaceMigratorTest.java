package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyWorkspaceMigratorTest {
    @TempDir
    Path tempDir;

    @Test
    void copiesLegacyYamlFilesWithoutCopyingReports() throws Exception {
        Path project = tempDir.resolve("project");
        Path legacyDir = project.resolve(".dm-adapter");
        Path workspace = tempDir.resolve("tool/.dm-adapter/sample-hr-rest");
        Files.createDirectories(legacyDir);
        Files.writeString(legacyDir.resolve("sql-rewrite.yml"), "keyColumns: {}\n");
        Files.writeString(legacyDir.resolve("sql-validation.yml"), "schema: sample\n");
        Files.writeString(legacyDir.resolve("sql-validation-report.md"), "old report\n");

        var messages = new LegacyWorkspaceMigrator().migrateDefaults(project, workspace, true, true);

        assertThat(messages).hasSize(2);
        assertThat(Files.readString(workspace.resolve("sql-rewrite.yml"))).isEqualTo("keyColumns: {}\n");
        assertThat(Files.readString(workspace.resolve("sql-validation.yml"))).isEqualTo("schema: sample\n");
        assertThat(workspace.resolve("sql-validation-report.md")).doesNotExist();
        assertThat(legacyDir.resolve("sql-rewrite.yml")).exists();
    }

    @Test
    void doesNotOverwriteExistingWorkspaceConfig() throws Exception {
        Path project = tempDir.resolve("project");
        Path legacyDir = project.resolve(".dm-adapter");
        Path workspace = tempDir.resolve("tool/.dm-adapter/sample-hr-rest");
        Files.createDirectories(legacyDir);
        Files.createDirectories(workspace);
        Files.writeString(legacyDir.resolve("sql-rewrite.yml"), "legacy\n");
        Files.writeString(workspace.resolve("sql-rewrite.yml"), "current\n");

        var messages = new LegacyWorkspaceMigrator().migrateDefaults(project, workspace, true, false);

        assertThat(messages).isEmpty();
        assertThat(Files.readString(workspace.resolve("sql-rewrite.yml"))).isEqualTo("current\n");
    }
}
