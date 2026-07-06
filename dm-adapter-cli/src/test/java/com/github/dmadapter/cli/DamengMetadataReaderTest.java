package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DamengMetadataReaderTest {
    @Test
    void splitSchemaListTrimsSkipsBlanksAndKeepsOrder() {
        assertThat(DamengMetadataReader.splitSchemaList(" sample-charge-10, sample-bill-10,,sample-owner,sample-bill-10 "))
                .containsExactly("sample-charge-10", "sample-bill-10", "sample-owner");
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
