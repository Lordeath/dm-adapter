package com.github.dmadapter.cli;

import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlScriptValidationFailure;
import com.github.dmadapter.core.TargetLengthSemantics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrateCommandTest {
    @Test
    void metadataTimeoutScalesForLargeMapperSetsAndHonorsExplicitOverride() {
        String propertyName = "dm.adapter.metadataReadTimeoutSeconds";
        String original = System.getProperty(propertyName);
        try {
            System.clearProperty(propertyName);
            assertThat(MigrateCommand.metadataReadTimeoutSeconds(0)).isEqualTo(12L);
            assertThat(MigrateCommand.metadataReadTimeoutSeconds(122)).isEqualTo(134L);
            assertThat(MigrateCommand.metadataReadTimeoutSeconds(1_000)).isEqualTo(300L);

            System.setProperty(propertyName, "45");
            assertThat(MigrateCommand.metadataReadTimeoutSeconds(1_000)).isEqualTo(45L);
        } finally {
            if (original == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, original);
            }
        }
    }

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

    @Test
    void mapperValidationStopsWhenSqlScriptConnectionCannotBeOpened() {
        assertThat(MigrateCommand.mapperValidationBlockReason(
                false,
                "Dameng SQL script validation connection failed: network communication error",
                List.of()
        )).contains("未重复执行 Mapper 数据库验证");
    }

    @Test
    void mapperValidationContinuesWhenScriptValidationWasSkippedByConfiguration() {
        assertThat(MigrateCommand.mapperValidationBlockReason(
                false,
                "DM_SQL_VALIDATION is not true; SQL script validation skipped.",
                List.of()
        )).isEmpty();
    }

    @Test
    void completeProjectDdlHistoryOverridesStaleDatabaseKeyMetadata() {
        TableKeyMetadata databaseMetadata = new TableKeyMetadata("sample_table", List.of(
                new TableConstraint(
                        "PRIMARY",
                        TableConstraint.ConstraintType.PRIMARY_KEY,
                        List.of("id")
                ),
                new TableConstraint(
                        "uk_obsolete",
                        TableConstraint.ConstraintType.UNIQUE_KEY,
                        List.of("business_code")
                )
        ));
        TableKeyMetadata projectDdlMetadata = new TableKeyMetadata(
                "sample_table",
                List.of(new TableConstraint(
                        "PRIMARY",
                        TableConstraint.ConstraintType.PRIMARY_KEY,
                        List.of("id")
                )),
                true,
                Set.of("id")
        );

        Map<String, TableKeyMetadata> merged = new MigrateCommand()
                .mergeDatabaseAndProjectDdlMetadata(
                        Map.of("sample_table", databaseMetadata),
                        Map.of("sample_table", projectDdlMetadata)
                );

        assertThat(merged.get("sample_table").uniqueKeys()).isEmpty();
        assertThat(merged.get("sample_table").autoGeneratedColumns()).containsExactly("id");
    }

    @Test
    void configuredAutomaticInsertIgnoreRemainsMetadataCandidate() {
        SqlChange automaticConversion = new SqlChange(
                "JobMapper.xml",
                "com.example.JobMapper.insert",
                "insert ignore into sample_job(logical_name, version_no) "
                        + "values (#{logicalName}, #{version})",
                "merge into sample_job ...",
                List.of("MYSQL_INSERT_IGNORE_TO_DM_MERGE"),
                false,
                ""
        );
        MapperMigrationResult preview = new MapperMigrationResult(
                List.of(),
                List.of(automaticConversion),
                List.of(),
                List.of()
        );

        List<RewriteConfigCandidate> candidates = new MigrateCommand()
                .rewriteConfigCandidates(preview);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.methodKey()).isEqualTo("com.example.JobMapper.insert");
            assertThat(candidate.tableName()).isEqualTo("sample_job");
            assertThat(candidate.insertColumns()).containsExactly("logical_name", "version_no");
            assertThat(candidate.insertIgnore()).isTrue();
        });
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
