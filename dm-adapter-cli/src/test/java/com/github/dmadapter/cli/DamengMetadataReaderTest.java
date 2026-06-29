package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DamengMetadataReaderTest {
    @Test
    void splitSchemaListTrimsSkipsBlanksAndKeepsOrder() {
        assertThat(DamengMetadataReader.splitSchemaList(" example-charge-10, example-bill-10,,example-owner,example-bill-10 "))
                .containsExactly("example-charge-10", "example-bill-10", "example-owner");
    }

    @Test
    void columnTypeQueryUsesDamengDictionaryViews() {
        assertThat(DamengMetadataReader.columnTypeQuerySql(true))
                .contains("ALL_TAB_COLUMNS")
                .contains("OWNER = ?")
                .contains("TABLE_NAME = ?");
        assertThat(DamengMetadataReader.columnTypeQuerySql(false))
                .contains("USER_TAB_COLUMNS")
                .doesNotContain("OWNER = ?");
    }
}
