package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedSqlStaticInspectorTest {
    @Test
    void rejectsPhysicalLineBreakInsideStringLiteral() {
        assertThat(GeneratedSqlStaticInspector.manualReviewReason("SELECT 'line one\nline two'"))
                .contains("单引号字符串跨越了物理行")
                .doesNotContain("single-quoted literal crosses a physical line");
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
                .contains("生成的 ALTER TABLE 仍包含 AFTER");
        assertThat(GeneratedSqlStaticInspector.manualReviewReason("SET @source = 1"))
                .contains("脚本级 @变量");
    }

    @Test
    void reportsRoutineStructureFailuresInChinese() {
        assertThat(GeneratedSqlStaticInspector.manualReviewReason("""
                CREATE OR REPLACE PROCEDURE demo AS
                BEGIN
                    SELECT 1;
                """))
                .contains("没有以 END 结束")
                .doesNotContain("not closed by END");
        assertThat(GeneratedSqlStaticInspector.manualReviewReason("""
                CREATE OR REPLACE PROCEDURE demo AS
                BEGIN
                    SELECT 1;
                END $$
                """))
                .contains("MySQL 存储过程分隔符")
                .doesNotContain("MySQL routine delimiter");
    }
}
