package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedSqlStaticInspectorTest {
    @Test
    void rejectsPhysicalLineBreakInsideStringLiteral() {
        assertThat(GeneratedSqlStaticInspector.manualReviewReason("SELECT 'line one\nline two'"))
                .contains("single-quoted literal crosses a physical line");
    }

    @Test
    void ignoresResidualTokensInsideCommentsAndStringLiterals() {
        assertThat(GeneratedSqlStaticInspector.manualReviewReason("""
                BEGIN
                    -- DELIMITER $$ ENGINE= AFTER USING BTREE @source
                    v_text := 'DELIMITER $$ ENGINE= AFTER USING BTREE @source';
                END
                """)).isEmpty();
    }

    @Test
    void rejectsResidualMysqlScriptTokensOutsideIgnoredText() {
        assertThat(GeneratedSqlStaticInspector.manualReviewReason("ALTER TABLE demo ADD value INT AFTER id"))
                .contains("AFTER");
        assertThat(GeneratedSqlStaticInspector.manualReviewReason("SET @source = 1"))
                .contains("@variable");
    }
}
