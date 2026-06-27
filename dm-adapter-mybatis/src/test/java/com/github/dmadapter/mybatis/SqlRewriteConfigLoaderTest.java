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
    void parsesValidationMissingTableIgnoresAndSkipsComments() {
        SqlRewriteConfig config = new SqlRewriteConfigLoader().parse(List.of(
                "identityInsertTables:",
                "  - \"ns_equip_area_class\"",
                "identityInsertTables: [\"NEWSEE_OWNER.owner_house\"]",
                "validationIgnores:",
                "  missingTables:",
                "    - \"ns_core_resourcecolumn_temp\"",
                "#    - \"commented_table\"",
                "    - \"NEWSEE_OWNER.owner_house\"",
                "  missingColumns: [\"organization_id\", \"D.fullPath\"]",
                "  missingSchemas: [\"NEWSEE-QUARTZ\"]",
                "  missingTables: [\"b\"]",
                "  missingColumns:",
                "#    - \"commented_column\"",
                "    - \"accountType\"",
                "  missingSchemas:",
                "#    - \"commented_schema\"",
                "    - \"NEWSEE-SCHEDULER\""
        ));

        assertThat(config.ignoredMissingTables())
                .contains("ns_core_resourcecolumn_temp", "newsee_owner.owner_house", "b")
                .doesNotContain("commented_table");
        assertThat(config.ignoredMissingColumns())
                .contains("organization_id", "d.fullpath", "accounttype")
                .doesNotContain("commented_column");
        assertThat(config.ignoredMissingSchemas())
                .contains("newsee-quartz", "newsee-scheduler")
                .doesNotContain("commented_schema");
        assertThat(config.requiresIdentityInsert("ns_equip_area_class")).isTrue();
        assertThat(config.requiresIdentityInsert("owner_house")).isTrue();
        assertThat(config.requiresIdentityInsert("other_table")).isFalse();
    }
}
