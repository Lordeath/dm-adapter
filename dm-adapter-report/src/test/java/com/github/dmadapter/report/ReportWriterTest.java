package com.github.dmadapter.report;

import com.github.dmadapter.core.BatchRepositoryReport;
import com.github.dmadapter.core.BatchRunReport;
import com.github.dmadapter.core.DmAdapterSummary;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.OverallStatus;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlScriptFileResult;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptValidationFailure;
import com.github.dmadapter.core.SqlScriptValidationReport;
import com.github.dmadapter.core.StageStatus;
import com.github.dmadapter.core.SummaryIssue;
import com.github.dmadapter.core.SummaryStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesBatchRepositoryAndRunReports() throws Exception {
        BatchRepositoryReport repository = new BatchRepositoryReport(
                1,
                "2026-08-07T00:00:00Z",
                "service-a",
                "SUCCESS",
                "main",
                "base-commit",
                "pushed-commit",
                2,
                List.of("src/main/resources/mapper-dm/UserMapper.xml"),
                "",
                "Conversion changes were committed and pushed.",
                ReportWriter.MIGRATION_REPORT_MARKDOWN,
                ReportWriter.SQL_SCRIPT_REPORT_MARKDOWN
        );
        BatchRunReport run = new BatchRunReport(
                1,
                "run-1",
                "2026-08-07T00:00:00Z",
                "SUCCESS",
                0,
                1,
                1,
                0,
                0,
                List.of(repository)
        );

        ReportWriter writer = new ReportWriter();
        ReportPaths repositoryPaths = writer.writeBatchRepositoryReport(repository, tempDir.resolve("service-a"));
        ReportPaths runPaths = writer.writeBatchRunReport(run, tempDir);

        assertThat(Files.readString(repositoryPaths.markdownPath()))
                .contains("service-a")
                .contains("pushed-commit")
                .contains("mapper-dm/UserMapper.xml");
        assertThat(Files.readString(runPaths.markdownPath()))
                .contains("[details](service-a/dm-adapter-batch-report.md)");
        assertThat(Files.readString(runPaths.jsonPath()))
                .contains("\"exitCode\" : 0")
                .contains("\"pushedCommit\" : \"pushed-commit\"");
    }

    @Test
    void writesStandaloneSqlScriptValidationReport() throws Exception {
        SqlScriptValidationReport report = new SqlScriptValidationReport(
                tempDir.resolve("sql-script-validation-plan.json").toString(),
                true,
                "Dameng SQL script validation completed with failed SQL statements.",
                3,
                1,
                2,
                List.of(new SqlScriptValidationFailure(
                        "demo.sql",
                        "demo-dm.sql",
                        "sample-system",
                        4,
                        "OBJECT_DEFINITION_CHANGED",
                        "对象定义[demo]被修改，版本检查失败 -7184",
                        "CALL demo_proc()"
                )),
                List.of("Skipped 2 manual statements.")
        );

        ReportPaths paths = new ReportWriter().writeSqlScriptValidationReport(report, tempDir);

        assertThat(Files.readString(paths.markdownPath()))
                .contains("达梦 SQL 脚本严格验证报告")
                .contains("OBJECT_DEFINITION_CHANGED")
                .contains("-7184")
                .contains("跳过人工确认 SQL 数：`2`");
        assertThat(Files.readString(paths.jsonPath())).contains("\"manualReviewSkippedCount\" : 2");
    }

    @Test
    void writesReadableProjectSummaryAtomically() throws Exception {
        DmAdapterSummary summary = new DmAdapterSummary(
                1,
                "2026-07-16T12:00:00Z",
                tempDir.toString(),
                false,
                "FULL_MUTATING_SHARED_DATABASE",
                OverallStatus.COMPLETED_WITH_ISSUES,
                Map.of("sqlScriptValidation", new SummaryStage(
                        "SQL 脚本数据库验证",
                        StageStatus.FAILED,
                        true,
                        true,
                        "2026-07-16T11:00:00Z",
                        "2026-07-16T12:00:00Z",
                        3_600_000L,
                        Map.of("rootFailures", 1L, "blockedCalls", 3L),
                        "存在根因失败。",
                        "dm-adapter-sql-script-report.md"
                )),
                Map.of("rawItems", 5L, "uniqueStatements", 2L),
                List.of(new SummaryIssue(
                        "ERROR", "SQL_SCRIPT_VALIDATION", "SQL_EXECUTION", 1, 1, 0,
                        "查看详细报告。"
                )),
                Map.of("sqlScriptMarkdown", "dm-adapter-sql-script-report.md"),
                List.of("先修复根因。")
        );

        ReportPaths paths = new ReportWriter().writeSummary(summary, tempDir);

        assertThat(new ReportReader().readSummary(tempDir)).isEqualTo(summary);
        assertThat(Files.readString(paths.markdownPath()))
                .contains("FULL_MUTATING_SHARED_DATABASE")
                .contains("SQL_EXECUTION")
                .contains("级联阻塞");
        assertThat(tempDir.resolve(ReportWriter.SUMMARY_MARKDOWN + ".tmp")).doesNotExist();
        assertThat(tempDir.resolve(ReportWriter.SUMMARY_JSON + ".tmp")).doesNotExist();
    }

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
                "user_password = TO_BASE64(AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR}, 'REAL_SECRET'))",
                List.of("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING"),
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
                3,
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
                ), new SqlScriptManualReviewItem(
                        "20260206.sql",
                        tempDir.resolve("sql/v2-dm/20260206.sql").toString(),
                        1,
                        "整数算术表达式风险：MySQL `/` 会产生小数，达梦整数/整数可能截断；请确认是否需要在除法前 CAST 为 DECIMAL(38,10)，并用 NULLIF 处理分母 0。",
                        "select '10'/4 from dual",
                        "select '10'/4 from dual"
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
                .contains("整数算术表达式风险")
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
