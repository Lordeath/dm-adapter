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
                "validationIgnores:",
                "  missingTables:",
                "    - \"ns_core_resourcecolumn_temp\"",
                "#    - \"commented_table\"",
                "    - \"NEWSEE_OWNER.owner_house\""
        ));

        assertThat(config.ignoredMissingTables())
                .contains("ns_core_resourcecolumn_temp", "newsee_owner.owner_house")
                .doesNotContain("commented_table");
    }
}
