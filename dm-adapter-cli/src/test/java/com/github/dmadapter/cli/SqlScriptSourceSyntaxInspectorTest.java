package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptSourceSyntaxInspectorTest {
    @Test
    void detectsDuplicateDefaultAndContradictoryNullabilityInCreateAndAlterTable() {
        assertThat(SqlScriptSourceSyntaxInspector.ddlManualReviewReason("""
                CREATE TABLE demo (
                    id BIGINT,
                    status INT DEFAULT 0 DEFAULT 1
                )
                """)).startsWith(SqlScriptSourceSyntaxInspector.DUPLICATE_DEFAULT_CODE);

        assertThat(SqlScriptSourceSyntaxInspector.ddlManualReviewReason("""
                ALTER TABLE demo MODIFY COLUMN status INT NULL NOT NULL
                """)).startsWith(SqlScriptSourceSyntaxInspector.CONTRADICTORY_NULLABILITY_CODE);
    }

    @Test
    void ignoresKeywordsInsideCommentsStringsAndDefaultNull() {
        assertThat(SqlScriptSourceSyntaxInspector.ddlManualReviewReason("""
                CREATE TABLE demo (
                    note VARCHAR(100) DEFAULT 'DEFAULT NULL NOT NULL',
                    status INT NOT NULL DEFAULT NULL,
                    -- DEFAULT NULL
                    value_text VARCHAR(100) NULL
                )
                """)).isEmpty();

        assertThat(SqlScriptSourceSyntaxInspector.ddlManualReviewReason(
                "CREATE TABLE demo_copy AS SELECT COALESCE(status, 0) FROM demo"
        )).isEmpty();
    }

    @Test
    void inspectsQuotedAlterTableNames() {
        assertThat(SqlScriptSourceSyntaxInspector.ddlManualReviewReason("""
                ALTER TABLE `demo` ADD COLUMN status INT DEFAULT 0 DEFAULT 1
                """)).startsWith(SqlScriptSourceSyntaxInspector.DUPLICATE_DEFAULT_CODE);
    }

    @Test
    void detectsExplicitAndKnownBareInsertValueCountMismatch() {
        assertThat(SqlScriptSourceSyntaxInspector.insertValueCountManualReviewReason(
                "INSERT INTO demo(id, name) VALUES (1), (2, 'ok')",
                Map.of()
        )).startsWith(SqlScriptSourceSyntaxInspector.INSERT_VALUE_COUNT_CODE);

        assertThat(SqlScriptSourceSyntaxInspector.insertValueCountManualReviewReason(
                "INSERT INTO demo VALUES (1, 'ok')",
                Map.of("demo", new LinkedHashSet<>(java.util.List.of("id", "name", "status")))
        )).startsWith(SqlScriptSourceSyntaxInspector.INSERT_VALUE_COUNT_CODE);
    }
}
