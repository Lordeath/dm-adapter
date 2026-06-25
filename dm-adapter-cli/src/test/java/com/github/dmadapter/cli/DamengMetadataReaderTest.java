package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DamengMetadataReaderTest {
    @Test
    void splitSchemaListTrimsSkipsBlanksAndKeepsOrder() {
        assertThat(DamengMetadataReader.splitSchemaList(" newsee-charge-10, newsee-bill-10,,newsee-owner,newsee-bill-10 "))
                .containsExactly("newsee-charge-10", "newsee-bill-10", "newsee-owner");
    }
}
