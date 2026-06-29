package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrateCommandTest {
    @Test
    void runWithMetadataTimeoutInterruptsSlowMetadataLookup() {
        assertThatThrownBy(() -> MigrateCommand.runWithMetadataTimeout(
                () -> {
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(5));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "done";
                },
                20,
                TimeUnit.MILLISECONDS,
                "metadata lookup"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadata lookup timed out");
    }

    @Test
    void runWithMetadataTimeoutReturnsFastMetadataLookup() throws Exception {
        assertThat(MigrateCommand.runWithMetadataTimeout(
                () -> "done",
                1,
                TimeUnit.SECONDS,
                "metadata lookup"
        )).isEqualTo("done");
    }
}
