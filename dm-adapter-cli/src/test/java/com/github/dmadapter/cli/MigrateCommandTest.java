package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlScriptValidationFailure;
import com.github.dmadapter.core.TargetLengthSemantics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrateCommandTest {
    @Test
    void explicitLengthSemanticsLimitsCapabilityLookupToOneAttempt() {
        assertThat(MigrateCommand.targetCapabilityReadAttempts(TargetLengthSemantics.BYTE)).isEqualTo(1);
        assertThat(MigrateCommand.targetCapabilityReadAttempts(TargetLengthSemantics.CHAR)).isEqualTo(1);
        assertThat(MigrateCommand.targetCapabilityReadAttempts(null)).isEqualTo(5);
    }

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

    @Test
    void metadataLookupRetriesTransientFailures() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        assertThat(MigrateCommand.runWithMetadataRetries(
                () -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("database is temporarily busy");
                    }
                    return "done";
                },
                5,
                0
        )).isEqualTo("done");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void metadataLookupStopsAfterConfiguredAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> MigrateCommand.runWithMetadataRetries(
                () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("database remains unavailable");
                },
                3,
                0
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("remains unavailable");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void mapperValidationContinuesAfterIndividualScriptTimeoutOrObjectStatusFailure() {
        assertThat(MigrateCommand.mapperValidationBlockedByScriptFailures(List.of(
                failure("20260205_system.sql", 8, "VALIDATION_TIMEOUT"),
                failure("20260205.sql", 12, "OBJECT_STATUS_VALIDATION_FAILED")
        ))).isFalse();
    }

    @Test
    void mapperValidationContinuesAfterSqlScriptTotalTimeout() {
        assertThat(MigrateCommand.mapperValidationBlockedByScriptFailures(List.of(
                failure("(validation)", 0, "VALIDATION_TIMEOUT")
        ))).isFalse();
    }

    @Test
    void mapperValidationStopsWhenSqlScriptSchemaPreflightFails() {
        assertThat(MigrateCommand.mapperValidationBlockedByScriptFailures(List.of(
                failure("(schema-preflight)", 0, "INVALID_SCHEMA")
        ))).isTrue();
    }

    private SqlScriptValidationFailure failure(String sourceFile, int statementIndex, String category) {
        return new SqlScriptValidationFailure(
                sourceFile,
                "",
                "sample-schema",
                statementIndex,
                category,
                "test failure",
                ""
        );
    }
}
