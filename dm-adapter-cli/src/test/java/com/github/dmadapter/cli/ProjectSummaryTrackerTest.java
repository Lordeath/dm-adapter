package com.github.dmadapter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.report.ReportPaths;
import com.github.dmadapter.report.ReportWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSummaryTrackerTest {
    @TempDir
    Path tempDir;

    @Test
    void includesMapperAndSqlScriptManualReviewAsTopIssues() throws Exception {
        ReportWriter writer = new ReportWriter();
        AdapterContext context = AdapterContext.builder(tempDir).reportDir(tempDir.resolve("reports")).build();
        ProjectSummaryTracker tracker = new ProjectSummaryTracker(
                context,
                writer,
                true,
                false,
                DmValidationEnvironment.batchSilent()
        );
        MigrationReport migrationReport = new MigrationReport(
                tempDir.toString(),
                "mysql",
                "dm",
                false,
                new ProjectScanResult(true, true, true, false, "pom.xml", List.of(), List.of()),
                List.of(),
                List.of(),
                List.of(new SqlChange(
                        "UserMapper.xml",
                        "com.example.UserMapper.insertIgnore",
                        "insert ignore into users(id) values (1)",
                        "insert ignore into users(id) values (1)",
                        List.of(),
                        true,
                        "keyColumns are required"
                )),
                List.of()
        );
        ReportPaths migrationPaths = writer.writeMigrationReport(migrationReport, context.reportDir());
        tracker.migrationCompleted(migrationReport, migrationPaths);
        tracker.startSqlScriptValidation(true);

        List<SqlScriptManualReviewItem> sqlManual = List.of(
                new SqlScriptManualReviewItem(
                        "20260205_system.sql",
                        "20260205_system.sql",
                        1,
                        "SQL 包含超过 3000 字节的字符串",
                        "CREATE PROCEDURE demo() BEGIN NULL; END",
                        "CREATE PROCEDURE demo() BEGIN NULL; END"
                ),
                new SqlScriptManualReviewItem(
                        "20260205_system.sql",
                        "20260205_system.sql",
                        2,
                        "依赖需要人工确认的存储过程 `demo`；请先修正该存储过程后再执行这个 CALL。",
                        "CALL demo()",
                        "CALL demo()"
                )
        );
        SqlScriptMigrationReport sqlReport = new SqlScriptMigrationReport(
                tempDir.toString(),
                tempDir.resolve("sql/v2").toString(),
                tempDir.resolve("sql/v2-dm").toString(),
                false,
                1,
                1,
                sqlManual.size(),
                false,
                SqlScriptMigrator.BATCH_VALIDATION_STATUS,
                0,
                0,
                List.of(),
                sqlManual,
                List.of(),
                List.of()
        );
        ReportPaths sqlPaths = writer.writeSqlScriptMigrationReport(sqlReport, context.reportDir());
        tracker.sqlScriptCompleted(sqlReport, sqlPaths);
        tracker.finish(BatchExitCodes.MANUAL_REVIEW);

        JsonNode summary = new ObjectMapper().readTree(
                context.reportDir().resolve(ReportWriter.SUMMARY_JSON).toFile()
        );
        assertThat(summary.path("topIssues")).anySatisfy(issue -> {
            assertThat(issue.path("pattern").asText()).isEqualTo("MAPPER_MANUAL_REVIEW");
            assertThat(issue.path("rootCount").asLong()).isEqualTo(1L);
            assertThat(issue.path("blockedCount").asLong()).isZero();
        }).anySatisfy(issue -> {
            assertThat(issue.path("pattern").asText()).isEqualTo("SQL_SCRIPT_MANUAL_REVIEW");
            assertThat(issue.path("count").asLong()).isEqualTo(2L);
            assertThat(issue.path("rootCount").asLong()).isEqualTo(1L);
            assertThat(issue.path("blockedCount").asLong()).isEqualTo(1L);
        });
        assertThat(Files.readString(context.reportDir().resolve(ReportWriter.SUMMARY_MARKDOWN)))
                .contains("MAPPER_MANUAL_REVIEW")
                .contains("SQL_SCRIPT_MANUAL_REVIEW")
                .doesNotContain("没有发现需要处理的根因");
    }
}
