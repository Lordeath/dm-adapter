package com.github.dmadapter.mybatis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRewriteConfigLoaderTest {
    @Test
    void parsesMethodsSectionAfterTablesSection() {
        SqlRewriteConfig config = new SqlRewriteConfigLoader().parse(List.of(
                "upsertKeys:",
                "  tables:",
                "    \"user_extend\":",
                "      keyColumns: [user_id]",
                "  methods:",
                "    \"com.example.UserMapper.updateExtend\":",
                "      keyColumns: [tenant_id, user_account]"
        ));

        assertThat(config.keyColumnsFor("com.example.UserMapper.updateExtend", "user_extend"))
                .containsExactly("tenant_id", "user_account");
        assertThat(config.keyColumnsFor("com.example.UserMapper.other", "user_extend"))
                .containsExactly("user_id");
    }

    @Test
    void resolvesQualifiedTableThroughUniqueLeafName() {
        SqlRewriteConfig config = new SqlRewriteConfigLoader().parse(List.of(
                "upsertKeys:",
                "  tables:",
                "    \"ns_core_resourcebutton\":",
                "      keyColumns: [ENTERPRISE_ID, JE_CORE_RESOURCEBUTTON_ID, RESOURCEBUTTON_FUNCINFO_ID]"
        ));

        assertThat(config.keyColumnsFor("", "SAMPLE_SYSTEM.`ns_core_resourcebutton`"))
                .containsExactly(
                        "ENTERPRISE_ID",
                        "JE_CORE_RESOURCEBUTTON_ID",
                        "RESOURCEBUTTON_FUNCINFO_ID"
                );
    }

    @Test
    void doesNotGuessLeafTableKeyWhenMultipleSchemasMatch() {
        SqlRewriteConfig config = new SqlRewriteConfigLoader().parse(List.of(
                "upsertKeys:",
                "  tables:",
                "    \"schema_a.shared_table\":",
                "      keyColumns: [id]",
                "    \"schema_b.shared_table\":",
                "      keyColumns: [tenant_id, id]"
        ));

        assertThat(config.keyColumnsFor("", "shared_table")).isEmpty();
        assertThat(config.keyColumnsFor("", "schema_b.shared_table"))
                .containsExactly("tenant_id", "id");
    }

    @Test
    void parsesValidationMissingTableIgnoresAndSkipsComments() {
        SqlRewriteConfig config = new SqlRewriteConfigLoader().parse(List.of(
                "identityInsertTables:",
                "  - \"ns_equip_area_class\"",
                "identityInsertTables: [\"SAMPLE_OWNER.owner_house\"]",
                "validationIgnores:",
                "  missingTables:",
                "    - \"ns_core_resourcecolumn_temp\"",
                "#    - \"commented_table\"",
                "    - \"SAMPLE_OWNER.owner_house\"",
                "  missingColumns: [\"organization_id\", \"D.fullPath\"]",
                "  missingSchemas: [\"SAMPLE-QUARTZ\"]",
                "  missingTables: [\"b\"]",
                "  missingColumns:",
                "#    - \"commented_column\"",
                "    - \"accountType\"",
                "  missingSchemas:",
                "#    - \"commented_schema\"",
                "    - \"SAMPLE-SCHEDULER\""
        ));

        assertThat(config.ignoredMissingTables())
                .contains("ns_core_resourcecolumn_temp", "sample_owner.owner_house", "b")
                .doesNotContain("commented_table");
        assertThat(config.ignoredMissingColumns())
                .contains("organization_id", "d.fullpath", "accounttype")
                .doesNotContain("commented_column");
        assertThat(config.ignoredMissingSchemas())
                .contains("sample-quartz", "sample-scheduler")
                .doesNotContain("commented_schema");
        assertThat(config.requiresIdentityInsert("ns_equip_area_class")).isTrue();
        assertThat(config.requiresIdentityInsert("owner_house")).isTrue();
        assertThat(config.requiresIdentityInsert("other_table")).isFalse();
    }

    @Test
    void parsesInsertIgnorePlainInsertResolution() {
        SqlRewriteConfig config = new SqlRewriteConfigLoader().parse(List.of(
                "upsertKeys:",
                "  tables:",
                "    {}",
                "  methods:",
                "    {}",
                "upsertKeyResolutions:",
                "  methods:",
                "    \"com.example.BankFileMapper.insert\": \"INSERT_IGNORE_AS_PLAIN_INSERT\""
        ));

        assertThat(config.convertsInsertIgnoreToPlainInsert("com.example.BankFileMapper.insert")).isTrue();
        assertThat(config.convertsInsertIgnoreToPlainInsert("com.example.BankFileMapper.other")).isFalse();
        assertThat(config.upsertKeyResolutionFor("com.example.BankFileMapper.insert"))
                .isEqualTo("INSERT_IGNORE_AS_PLAIN_INSERT");
        assertThat(config.upsertKeyResolutionFor("com.example.BankFileMapper.other")).isEmpty();
    }

    @Test
    void parsesMultipleInsertIgnoreConflictKeyGroups() {
        SqlRewriteConfig config = new SqlRewriteConfigLoader().parse(List.of(
                "upsertKeys:",
                "  tables:",
                "    {}",
                "  methods:",
                "    \"com.example.JobMapper.insert\":",
                "      keyColumns: []",
                "      conflictKeyGroups: [[logical_name, version_no], [job_name]]"
        ));

        assertThat(config.conflictKeyGroupsFor("com.example.JobMapper.insert"))
                .containsExactly(
                        List.of("logical_name", "version_no"),
                        List.of("job_name")
                );
    }

    @Test
    void resolvesUpsertManualReviewReasonFromMetadataDiagnosis() {
        SqlRewriteConfig config = new SqlRewriteConfigLoader().parse(List.of(
                "upsertKeyResolutions:",
                "  methods:",
                "    \"com.example.ScoreRuleMapper.upsert\": \"ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY\"",
                "    \"com.example.UnknownMapper.upsert\": \"KEY_METADATA_UNAVAILABLE\""
        ));

        assertThat(config.resolveUpsertManualReviewReason(
                "com.example.ScoreRuleMapper.upsert",
                "ON DUPLICATE KEY UPDATE requires configured keyColumns."
        )).contains("UPDATE branch cannot be reached")
                .contains("do not guess keyColumns");
        assertThat(config.resolveUpsertManualReviewReason(
                "com.example.UnknownMapper.upsert",
                "ON DUPLICATE KEY UPDATE requires configured keyColumns."
        )).contains("metadata was unavailable")
                .contains("--sql-root");
    }
}
