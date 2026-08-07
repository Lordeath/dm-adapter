package com.github.dmadapter.cli;

import com.github.dmadapter.core.DmAdapterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BatchConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsDefaultsAndRepositoryOverridesWithoutExposingPassword() throws Exception {
        Path config = tempDir.resolve("batch.yml");
        Files.writeString(config, """
                schemaVersion: 1
                workspaceDir: workspace
                reportDir: reports
                git:
                  username: batch-user
                  password: secret-value
                  authorName: Batch Bot
                  authorEmail: batch@example.com
                  commitMessage: Convert SQL
                migrationDefaults:
                  sql:
                    mode: IF_PRESENT
                    sourceDir: sql/v2
                    outputDir: sql/v2-dm
                    targetLengthSemantics: CHAR
                repositories:
                  - name: service-a
                    url: https://git.example.com/group/service-a.git
                    branch: main
                    projectSubdir: java/service-a
                    migration:
                      sql:
                        mode: DISABLED
                """);

        ResolvedBatchConfig loaded = new BatchConfigLoader().load(config);

        assertThat(loaded.workspaceDir()).isEqualTo(tempDir.resolve("workspace").toAbsolutePath().normalize());
        assertThat(loaded.reportDir()).isEqualTo(tempDir.resolve("reports").toAbsolutePath().normalize());
        assertThat(loaded.reportRetentionDays()).isEqualTo(30);
        assertThat(loaded.repositories()).singleElement().satisfies(repository -> {
            assertThat(repository.name()).isEqualTo("service-a");
            assertThat(repository.branch()).isEqualTo("main");
            assertThat(repository.projectSubdir()).isEqualTo(Path.of("java/service-a"));
            assertThat(repository.migration().sql().mode()).isEqualTo(BatchSqlMode.DISABLED);
        });
        assertThat(loaded.credentials().toString()).doesNotContain("secret-value").contains("******");
    }

    @Test
    void rejectsUnknownFieldsAndDuplicateRepositoryNames() throws Exception {
        Path unknown = tempDir.resolve("unknown.yml");
        Files.writeString(unknown, baseConfig("""
                  - name: service-a
                    url: file:///tmp/service-a.git
                    branch: main
                    unsupported: true
                """));

        assertThatThrownBy(() -> new BatchConfigLoader().load(unknown))
                .isInstanceOf(DmAdapterException.class)
                .hasMessageContaining("Could not parse batch YAML");

        Path duplicate = tempDir.resolve("duplicate.yml");
        Files.writeString(duplicate, baseConfig("""
                  - name: service-a
                    url: file:///tmp/service-a.git
                    branch: main
                  - name: SERVICE-A
                    url: file:///tmp/service-b.git
                    branch: main
                """));

        assertThatThrownBy(() -> new BatchConfigLoader().load(duplicate))
                .isInstanceOf(DmAdapterException.class)
                .hasMessageContaining("Duplicate repository name");
    }

    @Test
    void rejectsDuplicateYamlKeys() throws Exception {
        Path duplicateKey = tempDir.resolve("duplicate-key.yml");
        Files.writeString(duplicateKey, baseConfig("""
                  - name: service-a
                    url: file:///tmp/service-a.git
                    url: file:///tmp/service-b.git
                    branch: main
                """));

        assertThatThrownBy(() -> new BatchConfigLoader().load(duplicateKey))
                .isInstanceOf(DmAdapterException.class)
                .hasMessageContaining("Could not parse batch YAML");
    }

    @Test
    void rejectsUnsafePathsAndMissingHttpCredentials() throws Exception {
        Path unsafe = tempDir.resolve("unsafe.yml");
        Files.writeString(unsafe, baseConfig("""
                  - name: service-a
                    url: file:///tmp/service-a.git
                    branch: main
                    projectSubdir: ../outside
                """));

        assertThatThrownBy(() -> new BatchConfigLoader().load(unsafe))
                .isInstanceOf(DmAdapterException.class)
                .hasMessageContaining("must stay inside the repository");

        Path credentials = tempDir.resolve("credentials.yml");
        Files.writeString(credentials, baseConfig("""
                  - name: service-a
                    url: https://git.example.com/group/service-a.git
                    branch: main
                """));

        assertThatThrownBy(() -> new BatchConfigLoader().load(credentials))
                .isInstanceOf(DmAdapterException.class)
                .hasMessageContaining("git.username and git.password are required");
    }

    @Test
    void redactsCredentialsFromTransportMessages() {
        BatchSecretRedactor redactor = new BatchSecretRedactor(
                new ResolvedBatchConfig.Credentials("batch-user", "secret-token")
        );

        assertThat(redactor.redact(
                "Authentication failed for https://batch-user:secret-token@git.example.com/repository.git"
        )).isEqualTo("Authentication failed for https://******@git.example.com/repository.git");
    }

    @Test
    void mergesGlobalAndRepositoryTableKeyColumns() throws Exception {
        Path config = tempDir.resolve("upsert-keys.yml");
        Files.writeString(config, """
                schemaVersion: 1
                workspaceDir: workspace
                reportDir: reports
                git:
                  authorName: Batch Bot
                  authorEmail: batch@example.com
                  commitMessage: Convert SQL
                migrationDefaults:
                  upsertKeys:
                    tables:
                      "${schemaName}.charge_customerchargedetail_ext":
                        keyColumns: [chargeDetailId]
                      shared_table:
                        keyColumns: [default_id]
                  sql:
                    mode: IF_PRESENT
                    targetLengthSemantics: CHAR
                repositories:
                  - name: bill
                    url: file:///tmp/bill.git
                    branch: main
                    migration:
                      upsertKeys:
                        tables:
                          shared_table:
                            keyColumns: [bill_id]
                """);

        ResolvedBatchConfig loaded = new BatchConfigLoader().load(config);

        assertThat(loaded.repositories()).singleElement().satisfies(repository ->
                assertThat(repository.migration().tableKeyColumns())
                        .containsEntry("${schemaname}.charge_customerchargedetail_ext", List.of("chargeDetailId"))
                        .containsEntry("shared_table", List.of("bill_id"))
        );
    }

    @Test
    void rejectsEmptyOrDuplicateTableKeyColumns() throws Exception {
        Path empty = tempDir.resolve("empty-upsert-keys.yml");
        Files.writeString(empty, baseConfigWithMigration("""
                    upsertKeys:
                      tables:
                        sample_table:
                          keyColumns: []
                """));

        assertThatThrownBy(() -> new BatchConfigLoader().load(empty))
                .isInstanceOf(DmAdapterException.class)
                .hasMessageContaining("keyColumns must not be empty");

        Path duplicate = tempDir.resolve("duplicate-upsert-keys.yml");
        Files.writeString(duplicate, baseConfigWithMigration("""
                    upsertKeys:
                      tables:
                        sample_table:
                          keyColumns: [id, ID]
                """));

        assertThatThrownBy(() -> new BatchConfigLoader().load(duplicate))
                .isInstanceOf(DmAdapterException.class)
                .hasMessageContaining("contains duplicate column");
    }

    private String baseConfig(String repositories) {
        return """
                schemaVersion: 1
                workspaceDir: workspace
                reportDir: reports
                git:
                  authorName: Batch Bot
                  authorEmail: batch@example.com
                  commitMessage: Convert SQL
                migrationDefaults:
                  sql:
                    mode: IF_PRESENT
                    targetLengthSemantics: CHAR
                repositories:
                """ + repositories;
    }

    private String baseConfigWithMigration(String migration) {
        return """
                schemaVersion: 1
                workspaceDir: workspace
                reportDir: reports
                git:
                  authorName: Batch Bot
                  authorEmail: batch@example.com
                  commitMessage: Convert SQL
                migrationDefaults:
                """ + migration.strip().indent(2) + """
                  sql:
                    mode: IF_PRESENT
                    targetLengthSemantics: CHAR
                repositories:
                  - name: service-a
                    url: file:///tmp/service-a.git
                    branch: main
                """;
    }
}
