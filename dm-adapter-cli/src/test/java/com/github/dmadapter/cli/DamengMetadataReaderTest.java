package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DamengMetadataReaderTest {
    @Test
    void splitSchemaListTrimsSkipsBlanksAndKeepsOrder() {
        assertThat(DamengMetadataReader.splitSchemaList(" example-charge-10, example-bill-10,,example-owner,example-bill-10 "))
                .containsExactly("example-charge-10", "example-bill-10", "example-owner");
    }
}
