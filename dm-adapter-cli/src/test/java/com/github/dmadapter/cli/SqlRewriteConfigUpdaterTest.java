package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.mybatis.SqlRewriteConfig;
import com.github.dmadapter.mybatis.SqlRewriteConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRewriteConfigUpdaterTest {
    @TempDir
    Path tempDir;

    private final SqlRewriteConfigUpdater updater = new SqlRewriteConfigUpdater();

    @Test
    void writesInferredMethodAndTableKeysFromPrimaryKeyMetadata() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.updateExtend",
                "user_extend",
                List.of("user_id", "key_name")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("user_extend", List.of(
                new TableConstraint("PK_USER_EXTEND", TableConstraint.ConstraintType.PRIMARY_KEY, List.of("user_id"))
        ));

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of("user_extend", metadata),
                true
        );

        assertThat(update.fileChange()).isPresent();
        assertThat(update.rewriteConfig().keyColumnsFor(candidate.methodKey(), candidate.tableName()))
                .containsExactly("user_id");
        assertThat(Files.readString(config))
                .contains("\"user_extend\":")
                .contains("\"com.example.UserMapper.updateExtend\":")
                .contains("keyColumns: [\"user_id\"]")
                .doesNotContain("DM_DB_PASSWORD")
                .doesNotContain("DM_JDBC_URL");
    }

    @Test
    void writesOnlyTableKeyForOuterJoinSourceMetadata() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.updateOrganizationName",
                "sample_organization",
                List.of("parent_id"),
                RewriteConfigCandidate.RewriteKind.OUTER_JOIN_SOURCE
        );
        TableKeyMetadata metadata = new TableKeyMetadata("sample_organization", List.of(
                new TableConstraint(
                        "PK_SAMPLE_ORGANIZATION",
                        TableConstraint.ConstraintType.PRIMARY_KEY,
                        List.of("id")
                )
        ));

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of("sample_organization", metadata),
                true
        );

        assertThat(update.rewriteConfig().tableKeyColumns())
                .containsEntry("sample_organization", List.of("id"));
        assertThat(update.rewriteConfig().methodKeyColumns()).isEmpty();
        assertThat(update.warnings())
                .containsExactly("Inferred source keyColumns [id] for outer UPDATE JOIN table "
                        + "sample_organization from primary key PK_SAMPLE_ORGANIZATION.");
        assertThat(Files.readString(config))
                .contains("\"sample_organization\":")
                .contains("keyColumns: [\"id\"]")
                .doesNotContain("\"com.example.UserMapper.updateOrganizationName\":");
    }

    @Test
    void preservesExistingMethodKeyColumnsWhenMetadataDiffers() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                upsertKeys:
                  tables:
                    "user_extend":
                      keyColumns: []
                  methods:
                    "com.example.UserMapper.updateExtend":
                      keyColumns: [tenant_id, user_account]
                """);
        SqlRewriteConfig loaded = new SqlRewriteConfig(
                Map.of(),
                Map.of("com.example.UserMapper.updateExtend", List.of("tenant_id", "user_account"))
        );
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.updateExtend",
                "user_extend",
                List.of("id", "tenant_id", "user_account")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("user_extend", List.of(
                new TableConstraint("PK_USER_EXTEND", TableConstraint.ConstraintType.PRIMARY_KEY, List.of("id"))
        ));

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                loaded,
                List.of(candidate),
                Map.of("user_extend", metadata),
                true
        );

        assertThat(update.rewriteConfig().keyColumnsFor(candidate.methodKey(), candidate.tableName()))
                .containsExactly("tenant_id", "user_account");
        assertThat(Files.readString(config))
                .contains("keyColumns: [\"tenant_id\", \"user_account\"]")
                .doesNotContain("keyColumns: [\"id\"]");
    }

    @Test
    void preservesValidationArgsWhenMaintainingRewriteConfig() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                upsertKeys:
                  tables:
                    "user_extend":
                      keyColumns: []
                  methods:
                    {}

                validationArgs:
                  methods:
                    "com.example.UserMapper.selectById":
                      params:
                        id: "1"

                validationIgnores:
                  missingTables:
                    - "ns_core_resourcecolumn_temp"
                  missingColumns:
                    - "organization_id"
                """);
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.updateExtend",
                "user_extend",
                List.of("user_id", "key_name")
        );
        TableKeyMetadata metadata = new TableKeyMetadata("user_extend", List.of(
                new TableConstraint("PK_USER_EXTEND", TableConstraint.ConstraintType.PRIMARY_KEY, List.of("user_id"))
        ));

        updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of("user_extend", metadata),
                true
        );

        assertThat(Files.readString(config))
                .contains("validationArgs:")
                .contains("\"com.example.UserMapper.selectById\":")
                .contains("id: \"1\"")
                .contains("validationIgnores:")
                .contains("missingTables:")
                .contains("- \"ns_core_resourcecolumn_temp\"")
                .contains("missingColumns:")
                .contains("- \"organization_id\"")
                .contains("\"com.example.UserMapper.updateExtend\":")
                .contains("keyColumns: [\"user_id\"]");
    }

    @Test
    void keepsEmptyKeyColumnsWhenMetadataIsUnavailable() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.insertIgnore",
                "role_perm",
                List.of("role_id", "perm_id")
        );

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of(),
                false
        );

        assertThat(update.warnings()).isEmpty();
        assertThat(update.rewriteConfig().keyColumnsFor(candidate.methodKey(), candidate.tableName())).isEmpty();
        assertThat(Files.readString(config))
                .contains("\"role_perm\":")
                .contains("\"com.example.UserMapper.insertIgnore\":")
                .contains("keyColumns: []")
                .contains("upsertKeyResolutions:")
                .contains("\"com.example.UserMapper.insertIgnore\": \"KEY_METADATA_UNAVAILABLE\"");
    }

    @Test
    void preservesIdentityInsertTablesAndMissingSchemasWhenMaintainingRewriteConfig() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                identityInsertTables:
                  - "ns_bill_openbill_interface_log_history"

                upsertKeys:
                  tables:
                    {}
                  methods:
                    {}

                validationIgnores:
                  missingSchemas:
                    - "sample-bill"
                """);
        SqlRewriteConfig loaded = new SqlRewriteConfigLoader().load(config);
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.UserMapper.insertIgnore",
                "role_perm",
                List.of("role_id", "perm_id")
        );

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                loaded,
                List.of(candidate),
                Map.of(),
                false
        );

        assertThat(update.rewriteConfig().requiresIdentityInsert("ns_bill_openbill_interface_log_history")).isTrue();
        assertThat(update.rewriteConfig().ignoredMissingSchemas()).contains("sample-bill");
        assertThat(Files.readString(config))
                .contains("identityInsertTables:")
                .contains("- \"ns_bill_openbill_interface_log_history\"")
                .contains("validationIgnores:")
                .contains("missingSchemas:")
                .contains("- \"sample-bill\"")
                .contains("\"role_perm\":");
    }

    @Test
    void learnsIdentityInsertTablesFromPreviousValidationReport() throws Exception {
        Path adapterDir = tempDir.resolve(".dm-adapter");
        Files.createDirectories(adapterDir);
        Files.writeString(adapterDir.resolve("sql-validation-report.json"), """
                {
                  "records": [
                    {
                      "status": "FAILED",
                      "summary": "仅当指定列列表，且SET IDENTITY_INSERT为ON时，才能对自增列赋值",
                      "message": "### SQL: INSERT INTO ns_bill_openbill_interface_log_history ( id, name ) values (?, ?) ### Cause: dm.jdbc.driver.DMException: 仅当指定列列表，且SET IDENTITY_INSERT为ON时，才能对自增列赋值"
                    }
                  ]
                }
                """);
        Path config = adapterDir.resolve("sql-rewrite.yml");

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(),
                Map.of(),
                false
        );

        assertThat(update.warnings())
                .containsExactly("Learned identityInsertTables entry ns_bill_openbill_interface_log_history "
                        + "from the previous Dameng validation failure.");
        assertThat(update.fileChange()).isPresent();
        assertThat(update.rewriteConfig().requiresIdentityInsert("ns_bill_openbill_interface_log_history")).isTrue();
        assertThat(Files.readString(config))
                .contains("identityInsertTables:")
                .contains("- \"ns_bill_openbill_interface_log_history\"");
    }

    @Test
    void doesNotTurnPreviousTypeMismatchFailuresIntoValidationIgnores() throws Exception {
        Path adapterDir = tempDir.resolve(".dm-adapter");
        Files.createDirectories(adapterDir);
        Files.writeString(adapterDir.resolve("sql-validation-report.json"), """
                {
                  "records": [
                    {
                      "status": "FAILED",
                      "failurePattern": "TEST_DATA_TYPE_MISMATCH",
                      "key": "com.example.WorkCheckRecordWxMapper.batchInsert"
                    }
                  ]
                }
                """);
        Path config = adapterDir.resolve("sql-rewrite.yml");
        Files.writeString(config, """
                validationIgnores:
                  missingTables:
                    - "legacy_table"
                """);

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(),
                Map.of(),
                false
        );

        assertThat(update.warnings()).isEmpty();
        assertThat(update.fileChange()).isEmpty();
        assertThat(Files.readString(config))
                .contains("validationIgnores:")
                .contains("missingTables:")
                .contains("- \"legacy_table\"")
                .doesNotContain("typeMismatchMethods");
    }

    @Test
    void doesNotInferKeyColumnsWhenInsertColumnsCannotBeParsed() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.RecentlyUsedMapper.insertOrUpdate",
                "ns_recently_used",
                List.of()
        );
        TableKeyMetadata metadata = new TableKeyMetadata("ns_recently_used", List.of(
                new TableConstraint("PK_RECENTLY_USED", TableConstraint.ConstraintType.PRIMARY_KEY, List.of("id"))
        ));

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of("ns_recently_used", metadata),
                true
        );

        assertThat(update.rewriteConfig().keyColumnsFor(candidate.methodKey(), candidate.tableName())).isEmpty();
        assertThat(update.warnings())
                .anySatisfy(warning -> assertThat(warning)
                        .contains("Could not determine INSERT columns")
                        .contains("com.example.RecentlyUsedMapper.insertOrUpdate"));
        assertThat(Files.readString(config))
                .contains("\"ns_recently_used\":")
                .contains("\"com.example.RecentlyUsedMapper.insertOrUpdate\":")
                .contains("keyColumns: []")
                .contains("\"com.example.RecentlyUsedMapper.insertOrUpdate\": \"MANUAL_KEY_COLUMNS_REQUIRED\"");
    }

    @Test
    void persistsPlainInsertResolutionWhenInsertIgnoreOnlyOmitsGeneratedKey() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.BankFileMapper.insertIgnore",
                "ns_bank_file",
                List.of("file_id", "file_name"),
                RewriteConfigCandidate.RewriteKind.INSERT_IGNORE
        );
        TableKeyMetadata metadata = new TableKeyMetadata(
                "ns_bank_file",
                List.of(new TableConstraint(
                        "PK_NS_BANK_FILE",
                        TableConstraint.ConstraintType.PRIMARY_KEY,
                        List.of("id")
                )),
                true,
                Set.of("id")
        );

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of("ns_bank_file", metadata),
                true
        );

        assertThat(update.rewriteConfig().keyColumnsFor(candidate.methodKey(), candidate.tableName())).isEmpty();
        assertThat(update.rewriteConfig().convertsInsertIgnoreToPlainInsert(candidate.methodKey())).isTrue();
        assertThat(update.warnings())
                .anySatisfy(warning -> assertThat(warning)
                        .contains("Resolved")
                        .contains("plain INSERT"));
        assertThat(Files.readString(config))
                .contains("upsertKeyResolutions:")
                .contains("\"com.example.BankFileMapper.insertIgnore\": "
                        + "\"INSERT_IGNORE_AS_PLAIN_INSERT\"");
    }
}
