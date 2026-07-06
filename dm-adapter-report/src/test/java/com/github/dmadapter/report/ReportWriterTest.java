package com.github.dmadapter.report;

import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlScriptFileResult;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptValidationFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void migrationReportIncludesManualReviewReason() throws Exception {
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(),
                List.of()
        );
        SqlChange manualReviewItem = new SqlChange(
                "mapper/UserMapper.xml",
                "selectUsers",
                "select JSON_SET(profile, '$.name', #{name}) from user",
                "select JSON_SET(profile, '$.name', #{name}) from user",
                List.of(),
                true,
                "JSON_SET requires manual confirmation because Dameng support or syntax may differ from MySQL."
        );
        MigrationReport report = new MigrationReport(
                tempDir.toString(),
                "mysql",
                "dm",
                true,
                scanResult,
                List.of(),
                List.of(),
                List.of(manualReviewItem),
                List.of()
        );

        ReportPaths reportPaths = new ReportWriter().writeMigrationReport(report, tempDir);

        assertThat(Files.exists(reportPaths.jsonPath())).isTrue();
        assertThat(Files.readString(reportPaths.markdownPath()))
                .contains("Manual Review SQL Items")
                .contains("mapper/UserMapper.xml")
                .contains("JSON_SET requires manual confirmation");
    }

    @Test
    void migrationReportRedactsAesKeysInMarkdownAndJson() throws Exception {
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(),
                List.of()
        );
        SqlChange automaticConversion = new SqlChange(
                "mapper/UserMapper.xml",
                "updatePassword",
                "user_password = TO_BASE64(AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR}, 'REAL_SECRET'))",
                "user_password = TO_BASE64(SF_ENCRYPT_CHAR(#{userPassword, jdbcType=VARCHAR}, 513, 'REAL_SECRET', NULL))",
                List.of("MYSQL_AES_BASE64_TO_DM_AES128_ECB"),
                false,
                ""
        );
        MigrationReport report = new MigrationReport(
                tempDir.toString(),
                "mysql",
                "dm",
                true,
                scanResult,
                List.of(),
                List.of(automaticConversion),
                List.of(),
                List.of()
        );

        ReportPaths reportPaths = new ReportWriter().writeMigrationReport(report, tempDir);

        String markdown = Files.readString(reportPaths.markdownPath());
        String json = Files.readString(reportPaths.jsonPath());
        assertThat(markdown)
                .doesNotContain("REAL_SECRET")
                .contains("'******'");
        assertThat(json)
                .doesNotContain("REAL_SECRET")
                .contains("'******'");
    }

    @Test
    void sqlScriptMigrationReportUsesChineseMarkdown() throws Exception {
        SqlScriptMigrationReport report = new SqlScriptMigrationReport(
                tempDir.toString(),
                tempDir.resolve("sql/v2").toString(),
                tempDir.resolve("sql/v2-dm").toString(),
                false,
                1,
                1,
                2,
                true,
                "Dameng SQL script validation completed with failed SQL statements.",
                3,
                1,
                List.of(new SqlScriptFileResult(
                        "20260205.sql",
                        tempDir.resolve("sql/v2-dm/20260205.sql").toString(),
                        "sample-bill",
                        false,
                        true,
                        true,
                        4,
                        2,
                        1,
                        3,
                        1,
                        List.of("MYSQL_SCRIPT_METADATA_TO_DM_RULE")
                )),
                List.of(new SqlScriptManualReviewItem(
                        "20260205.sql",
                        tempDir.resolve("sql/v2-dm/20260205.sql").toString(),
                        2,
                        "可疑字段长度修改：建议补充 DATA_TYPE 和 CHARACTER_MAXIMUM_LENGTH < 1000 判断。",
                        "alter table demo modify details varchar(1000)",
                        "alter table demo modify details varchar(1000)"
                ), new SqlScriptManualReviewItem(
                        "20260205_system.sql",
                        tempDir.resolve("sql/v2-dm/20260205_system.sql").toString(),
                        9,
                        "MySQL user variables such as @var require ROW_NUMBER, explicit variables, or procedure-level rewrite for Dameng.",
                        "select @rownum := @rownum + 1",
                        "select @rownum := @rownum + 1"
                )),
                List.of(new SqlScriptValidationFailure(
                        "20260205.sql",
                        tempDir.resolve("sql/v2-dm/20260205.sql").toString(),
                        "sample-bill",
                        3,
                        "SQL_EXECUTION",
                        "String truncated",
                        "CALL modify_details()"
                )),
                List.of("DM_SQL_VALIDATION is not true; SQL script validation skipped.")
        );

        ReportPaths reportPaths = new ReportWriter().writeSqlScriptMigrationReport(report, tempDir);

        String markdown = Files.readString(reportPaths.markdownPath());
        assertThat(markdown)
                .contains("# 达梦 SQL 脚本转换报告")
                .contains("- 项目目录：")
                .contains("- 试执行状态：`达梦 SQL 脚本试执行完成，但存在失败 SQL。`")
                .contains("## 脚本文件")
                .contains("| 原始文件 | 输出文件 | 执行 schema | 系统脚本 | SQL 语句数 | 自动转换数 | 人工确认数 | 试执行成功数 | 试执行失败数 |")
                .contains("## 需人工确认的 SQL")
                .contains("原因：可疑字段长度修改")
                .contains("MySQL 用户变量（如 @var）不能直接迁移到达梦")
                .contains("原始 SQL")
                .contains("转换后 SQL")
                .contains("## 达梦试执行失败")
                .contains("错误摘要：String truncated")
                .contains("失败 SQL")
                .contains("## 风险提示")
                .contains("DM_SQL_VALIDATION 不是 true；已跳过 SQL 脚本试执行。")
                .doesNotContain("SQL Script Migration Report")
                .doesNotContain("Validation Failures");
    }
}
