package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.mybatis.SqlRewriteConfig;
import com.github.dmadapter.mybatis.SqlRewriteConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    void batchTableKeyOverridesRewriteConfigAndWritesWarning() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                upsertKeys:
                  tables:
                    "${schemaName}.charge_customerchargedetail_ext":
                      keyColumns: [legacy_id]
                  methods:
                    {}
                """);
        SqlRewriteConfig loaded = new SqlRewriteConfigLoader().load(config);

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                loaded,
                List.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of("${schemaName}.charge_customerchargedetail_ext", List.of("chargeDetailId"))
        );

        assertThat(update.rewriteConfig().tableKeyColumns())
                .containsEntry("${schemaname}.charge_customerchargedetail_ext", List.of("chargeDetailId"));
        assertThat(update.warnings()).singleElement().asString()
                .contains("Batch upsert keyColumns override rewriteConfig")
                .contains("[legacy_id] -> [chargeDetailId]");
        assertThat(Files.readString(config))
                .contains("keyColumns: [\"chargeDetailId\"]")
                .doesNotContain("legacy_id");
    }

    @Test
    void batchMethodKeysReplaceBothPartsOfExistingMethodConfiguration() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                upsertKeys:
                  tables: {}
                  methods:
                    "com.example.CanalMapper.upsert":
                      keyColumns: [legacy_id]
                      conflictKeyGroups: [[legacy_id], [tenant_id, code]]
                    "com.example.CanalMapper.insertIgnore":
                      keyColumns: [legacy_id]
                """);
        SqlRewriteConfig loaded = new SqlRewriteConfigLoader().load(config);

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                loaded,
                List.of(),
                Map.of(),
                false,
                Map.of(),
                Map.of(),
                Map.of("com.example.CanalMapper.upsert", List.of("pk")),
                Map.of(
                        "com.example.CanalMapper.insertIgnore",
                        List.of(List.of("pk"), List.of("tenant_id", "code"))
                )
        );

        assertThat(update.rewriteConfig().methodKeyColumns())
                .containsEntry("com.example.CanalMapper.upsert", List.of("pk"))
                .doesNotContainKey("com.example.CanalMapper.insertIgnore");
        assertThat(update.rewriteConfig().methodConflictKeyGroups())
                .doesNotContainKey("com.example.CanalMapper.upsert")
                .containsEntry(
                        "com.example.CanalMapper.insertIgnore",
                        List.of(List.of("pk"), List.of("tenant_id", "code"))
                );
        assertThat(Files.readString(config))
                .contains("\"com.example.CanalMapper.upsert\":")
                .contains("keyColumns: [\"pk\"]")
                .contains("\"com.example.CanalMapper.insertIgnore\":")
                .contains("conflictKeyGroups:")
                .doesNotContain("legacy_id");
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
    void removesIdentityInsertTablesForAutoIncrementAndNonIdentityTargets() throws Exception {
        Path adapterDir = tempDir.resolve(".dm-adapter");
        Files.createDirectories(adapterDir);
        Path config = adapterDir.resolve("sql-rewrite.yml");
        Files.writeString(config, """
                identityInsertTables:
                  - "auto_table"
                  - "identity_table"
                  - "ordinary_table"

                upsertKeys:
                  tables:
                    {}
                  methods:
                    {}
                """);
        SqlRewriteConfig loaded = new SqlRewriteConfigLoader().load(config);
        Map<String, DamengMetadataReader.AutoIncrementKind> autoIncrementKinds = new LinkedHashMap<>();
        autoIncrementKinds.put("ordinary_table", DamengMetadataReader.AutoIncrementKind.NONE);
        autoIncrementKinds.put("identity_table", DamengMetadataReader.AutoIncrementKind.IDENTITY);
        autoIncrementKinds.put("auto_table", DamengMetadataReader.AutoIncrementKind.AUTO_INCREMENT);

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                loaded,
                List.of(),
                Map.of(),
                false,
                autoIncrementKinds
        );

        assertThat(update.fileChange()).isPresent();
        assertThat(update.rewriteConfig().requiresIdentityInsert("auto_table")).isFalse();
        assertThat(update.rewriteConfig().requiresIdentityInsert("ordinary_table")).isFalse();
        assertThat(update.rewriteConfig().requiresIdentityInsert("identity_table")).isTrue();
        assertThat(update.warnings())
                .containsExactly(
                        "Removed identityInsertTables entry auto_table because the target Dameng table "
                                + "uses AUTO_INCREMENT rather than IDENTITY.",
                        "Removed identityInsertTables entry ordinary_table because the target Dameng table "
                                + "does not contain an IDENTITY column."
                );
        assertThat(Files.readString(config))
                .contains("identityInsertTables:")
                .contains("- \"identity_table\"")
                .doesNotContain("auto_table")
                .doesNotContain("ordinary_table");
    }

    @Test
    void removesStaleIdentityInsertTablesFromChineseAndEnglishValidationFailures() throws Exception {
        Path adapterDir = tempDir.resolve(".dm-adapter");
        Files.createDirectories(adapterDir);
        Path config = adapterDir.resolve("sql-rewrite.yml");
        Files.writeString(config, """
                identityInsertTables:
                  - "owner_house_relationship"
                  - "owner_customer_precinct_relation"

                upsertKeys:
                  tables:
                    {}
                  methods:
                    {}
                """);
        Files.writeString(adapterDir.resolve("sql-validation-report.json"), """
                {
                  "records": [
                    {
                      "status": "FAILED",
                      "summary": "表[owner_house_relationship]不存在IDENTITY列",
                      "message": "### SQL: SET IDENTITY_INSERT owner_house_relationship ON"
                    },
                    {
                      "status": "FAILED",
                      "summary": "Table [owner_customer_precinct_relation] didn’t contains identity column",
                      "message": "### SQL: SET IDENTITY_INSERT owner_customer_precinct_relation ON"
                    }
                  ]
                }
                """);
        SqlRewriteConfig loaded = new SqlRewriteConfigLoader().load(config);

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                loaded,
                List.of(),
                Map.of(),
                false
        );

        assertThat(update.fileChange()).isPresent();
        assertThat(update.rewriteConfig().identityInsertTables()).isEmpty();
        assertThat(update.warnings()).containsExactly(
                "Removed identityInsertTables entry owner_house_relationship because the previous Dameng "
                        + "validation reported that the target table has no IDENTITY column.",
                "Removed identityInsertTables entry owner_customer_precinct_relation because the previous "
                        + "Dameng validation reported that the target table has no IDENTITY column."
        );
        assertThat(Files.readString(config))
                .doesNotContain("identityInsertTables:")
                .doesNotContain("owner_house_relationship")
                .doesNotContain("owner_customer_precinct_relation");
    }

    @Test
    void currentIdentityMetadataOverridesAStaleNoIdentityValidationFailure() throws Exception {
        Path adapterDir = tempDir.resolve(".dm-adapter");
        Files.createDirectories(adapterDir);
        Path config = adapterDir.resolve("sql-rewrite.yml");
        Files.writeString(config, """
                identityInsertTables:
                  - "current_identity_table"

                upsertKeys:
                  tables:
                    {}
                  methods:
                    {}
                """);
        Files.writeString(adapterDir.resolve("sql-validation-report.json"), """
                {
                  "records": [
                    {
                      "status": "FAILED",
                      "summary": "表[current_identity_table]不存在IDENTITY列",
                      "message": "### SQL: SET IDENTITY_INSERT current_identity_table ON"
                    }
                  ]
                }
                """);
        SqlRewriteConfig loaded = new SqlRewriteConfigLoader().load(config);

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                loaded,
                List.of(),
                Map.of(),
                false,
                Map.of("current_identity_table", DamengMetadataReader.AutoIncrementKind.IDENTITY)
        );

        assertThat(update.fileChange()).isEmpty();
        assertThat(update.warnings()).isEmpty();
        assertThat(update.rewriteConfig().requiresIdentityInsert("current_identity_table")).isTrue();
        assertThat(Files.readString(config)).contains("- \"current_identity_table\"");
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

    @Test
    void persistsAllReachableUniqueKeysForInsertIgnore() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.JobMapper.insert",
                "sample_job_config",
                List.of("logical_name", "version_no", "job_name"),
                RewriteConfigCandidate.RewriteKind.INSERT_IGNORE
        );
        TableKeyMetadata metadata = new TableKeyMetadata("sample_job_config", List.of(
                new TableConstraint(
                        "UK_LOGICAL_VERSION",
                        TableConstraint.ConstraintType.UNIQUE_KEY,
                        List.of("logical_name", "version_no")
                ),
                new TableConstraint(
                        "UK_JOB_NAME",
                        TableConstraint.ConstraintType.UNIQUE_KEY,
                        List.of("job_name")
                )
        ));

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                SqlRewriteConfig.empty(),
                List.of(candidate),
                Map.of("sample_job_config", metadata),
                true
        );

        assertThat(update.rewriteConfig().conflictKeyGroupsFor(candidate.methodKey()))
                .containsExactly(
                        List.of("logical_name", "version_no"),
                        List.of("job_name")
                );
        assertThat(update.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("conflictKeyGroups", candidate.methodKey()));
        assertThat(Files.readString(config))
                .contains("keyColumns: []")
                .contains("conflictKeyGroups: "
                        + "[[\"logical_name\", \"version_no\"], [\"job_name\"]]")
                .doesNotContain("MANUAL_KEY_COLUMNS_REQUIRED");
    }

    @Test
    void replacesStaleGeneratedConflictGroupsWhenLaterDdlDropsUniqueIndexes() throws Exception {
        Path config = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                upsertKeys:
                  tables:
                    "flink_table_config":
                      keyColumns: []
                  methods:
                    "com.example.FlinkMapper.insertTableStatus":
                      keyColumns: []
                      conflictKeyGroups: [["logical_table_name", "version"], ["table_name"]]
                """);
        RewriteConfigCandidate candidate = new RewriteConfigCandidate(
                "com.example.FlinkMapper.insertTableStatus",
                "flink_table_config",
                List.of("logical_table_name", "version", "table_name"),
                RewriteConfigCandidate.RewriteKind.INSERT_IGNORE
        );
        TableKeyMetadata metadata = new TableKeyMetadata(
                "flink_table_config",
                List.of(new TableConstraint(
                        "PRIMARY",
                        TableConstraint.ConstraintType.PRIMARY_KEY,
                        List.of("id")
                )),
                true,
                Set.of("id")
        );

        SqlRewriteConfigUpdate update = updater.update(
                AdapterContext.builder(tempDir).build(),
                config,
                new SqlRewriteConfigLoader().load(config),
                List.of(candidate),
                Map.of("flink_table_config", metadata),
                true
        );

        assertThat(update.rewriteConfig().conflictKeyGroupsFor(candidate.methodKey())).isEmpty();
        assertThat(update.rewriteConfig().convertsInsertIgnoreToPlainInsert(candidate.methodKey())).isTrue();
        assertThat(update.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("Discarded stale conflictKeyGroups"));
        assertThat(Files.readString(config))
                .doesNotContain("conflictKeyGroups:")
                .contains("\"com.example.FlinkMapper.insertTableStatus\": "
                        + "\"INSERT_IGNORE_AS_PLAIN_INSERT\"");
    }
}
