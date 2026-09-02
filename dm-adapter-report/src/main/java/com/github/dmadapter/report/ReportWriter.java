package com.github.dmadapter.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.BatchRepositoryReport;
import com.github.dmadapter.core.BatchRunReport;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.DmAdapterSummary;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlScriptFileResult;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptValidationFailure;
import com.github.dmadapter.core.SqlScriptValidationReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class ReportWriter {
    public static final String SCAN_REPORT_MARKDOWN = "dm-adapter-scan-report.md";
    public static final String SCAN_REPORT_JSON = "dm-adapter-scan-report.json";
    public static final String MIGRATION_REPORT_MARKDOWN = "dm-adapter-report.md";
    public static final String MIGRATION_REPORT_JSON = "dm-adapter-report.json";
    public static final String SQL_SCRIPT_REPORT_MARKDOWN = "dm-adapter-sql-script-report.md";
    public static final String SQL_SCRIPT_REPORT_JSON = "dm-adapter-sql-script-report.json";
    public static final String SQL_SCRIPT_VALIDATION_REPORT_MARKDOWN = "sql-script-validation-report.md";
    public static final String SQL_SCRIPT_VALIDATION_REPORT_JSON = "sql-script-validation-report.json";
    public static final String SUMMARY_MARKDOWN = "dm-adapter-summary.md";
    public static final String SUMMARY_JSON = "dm-adapter-summary.json";
    public static final String BATCH_REPOSITORY_REPORT_MARKDOWN = "dm-adapter-batch-report.md";
    public static final String BATCH_REPOSITORY_REPORT_JSON = "dm-adapter-batch-report.json";
    public static final String BATCH_RUN_REPORT_MARKDOWN = "dm-adapter-batch-summary.md";
    public static final String BATCH_RUN_REPORT_JSON = "dm-adapter-batch-summary.json";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public ReportPaths writeScanReport(ProjectScanResult scanResult, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        Path markdownPath = reportDir.resolve(SCAN_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(SCAN_REPORT_JSON);
        Files.writeString(markdownPath, scanMarkdown(scanResult), StandardCharsets.UTF_8);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), scanResult);
        return new ReportPaths(markdownPath, jsonPath);
    }

    public ReportPaths writeMigrationReport(MigrationReport report, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        MigrationReport redactedReport = redactSensitiveSql(report);
        Path markdownPath = reportDir.resolve(MIGRATION_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(MIGRATION_REPORT_JSON);
        Files.writeString(markdownPath, migrationMarkdown(redactedReport), StandardCharsets.UTF_8);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), redactedReport);
        return new ReportPaths(markdownPath, jsonPath);
    }

    public ReportPaths writeSqlScriptMigrationReport(SqlScriptMigrationReport report, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        SqlScriptMigrationReport redactedReport = redactSensitiveSql(report);
        Path markdownPath = reportDir.resolve(SQL_SCRIPT_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(SQL_SCRIPT_REPORT_JSON);
        Files.writeString(markdownPath, sqlScriptMarkdown(redactedReport), StandardCharsets.UTF_8);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), redactedReport);
        return new ReportPaths(markdownPath, jsonPath);
    }

    public ReportPaths writeSqlScriptValidationReport(
            SqlScriptValidationReport report,
            Path reportDir
    ) throws IOException {
        Files.createDirectories(reportDir);
        Path markdownPath = reportDir.resolve(SQL_SCRIPT_VALIDATION_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(SQL_SCRIPT_VALIDATION_REPORT_JSON);
        writeStringAtomically(markdownPath, sqlScriptValidationMarkdown(report));
        writeJsonAtomically(jsonPath, report);
        return new ReportPaths(markdownPath, jsonPath);
    }

    public ReportPaths writeSummary(DmAdapterSummary summary, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        Path markdownPath = reportDir.resolve(SUMMARY_MARKDOWN);
        Path jsonPath = reportDir.resolve(SUMMARY_JSON);
        writeStringAtomically(markdownPath, summaryMarkdown(summary));
        writeJsonAtomically(jsonPath, summary);
        return new ReportPaths(markdownPath, jsonPath);
    }

    public ReportPaths writeBatchRepositoryReport(BatchRepositoryReport report, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        Path markdownPath = reportDir.resolve(BATCH_REPOSITORY_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(BATCH_REPOSITORY_REPORT_JSON);
        writeStringAtomically(markdownPath, batchRepositoryMarkdown(report));
        writeJsonAtomically(jsonPath, report);
        return new ReportPaths(markdownPath, jsonPath);
    }

    public ReportPaths writeBatchRunReport(BatchRunReport report, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        Path markdownPath = reportDir.resolve(BATCH_RUN_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(BATCH_RUN_REPORT_JSON);
        writeStringAtomically(markdownPath, batchRunMarkdown(report));
        writeJsonAtomically(jsonPath, report);
        return new ReportPaths(markdownPath, jsonPath);
    }

    private String batchRepositoryMarkdown(BatchRepositoryReport report) {
        StringBuilder markdown = new StringBuilder("# dm-adapter 批处理仓库报告\n\n");
        markdown.append("- 仓库：`").append(report.repository()).append("`\n");
        markdown.append("- 状态：`").append(statusText(report.status())).append("`\n");
        markdown.append("- 分支：`").append(report.branch()).append("`\n");
        markdown.append("- 基线提交：`").append(report.baseCommit()).append("`\n");
        markdown.append("- 推送提交：`").append(report.pushedCommit()).append("`\n");
        markdown.append("- 尝试次数：`").append(report.attempts()).append("`\n");
        if (!report.failureStage().isBlank()) {
            markdown.append("- 失败阶段：`").append(failureStageText(report.failureStage())).append("`\n");
        }
        if (!report.message().isBlank()) {
            markdown.append("- 结果：").append(reportText(report.message())).append("\n");
        }
        markdown.append("\n## 变更文件\n\n");
        if (report.changedFiles().isEmpty()) {
            markdown.append("无。\n");
        } else {
            report.changedFiles().forEach(path -> markdown.append("- `").append(path).append("`\n"));
        }
        markdown.append("\n## 详细报告\n\n");
        if (!report.migrationReport().isBlank()) {
            markdown.append("- [迁移报告](").append(report.migrationReport()).append(")\n");
        }
        if (!report.sqlScriptReport().isBlank()) {
            markdown.append("- [SQL 脚本报告](").append(report.sqlScriptReport()).append(")\n");
        }
        if (report.migrationReport().isBlank() && report.sqlScriptReport().isBlank()) {
            markdown.append("无。\n");
        }
        return markdown.toString();
    }

    private String batchRunMarkdown(BatchRunReport report) {
        StringBuilder markdown = new StringBuilder("# dm-adapter 批处理运行报告\n\n");
        markdown.append("- 运行编号：`").append(report.runId()).append("`\n");
        markdown.append("- 生成时间：`").append(report.generatedAt()).append("`\n");
        markdown.append("- 状态：`").append(statusText(report.status())).append("`\n");
        markdown.append("- 退出码：`").append(report.exitCode()).append("`\n");
        markdown.append("- 仓库数：`").append(report.repositoryCount()).append("`\n");
        markdown.append("- 成功数：`").append(report.successCount()).append("`\n");
        markdown.append("- 无变更数：`").append(report.noChangesCount()).append("`\n");
        markdown.append("- 失败数：`").append(report.failedCount()).append("`\n\n");
        markdown.append("| 仓库 | 状态 | 分支 | 尝试次数 | 结果 | 报告 |\n");
        markdown.append("| --- | --- | --- | ---: | --- | --- |\n");
        for (BatchRepositoryReport repository : report.repositories()) {
            markdown.append("| ").append(escapeTable(repository.repository()))
                    .append(" | `").append(statusText(repository.status()))
                    .append("` | `").append(repository.branch())
                    .append("` | ").append(repository.attempts())
                    .append(" | ").append(escapeTable(reportText(repository.message())))
                    .append(" | [详情](").append(repository.repository())
                    .append("/").append(BATCH_REPOSITORY_REPORT_MARKDOWN).append(") |\n");
        }
        return markdown.toString();
    }

    private void writeStringAtomically(Path path, String content) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        moveAtomically(temporary, path);
    }

    private void writeJsonAtomically(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        moveAtomically(temporary, path);
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String summaryMarkdown(DmAdapterSummary summary) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# dm-adapter 项目摘要\n\n");
        markdown.append("- 项目：`").append(summary.projectRoot()).append("`\n");
        markdown.append("- 生成时间：`").append(summary.generatedAt()).append("`\n");
        markdown.append("- 总体状态：`").append(statusText(summary.overallStatus().name())).append("`\n");
        markdown.append("- dry-run：`").append(yesNo(summary.dryRun())).append("`\n");
        markdown.append("- 数据库执行模式：`").append(executionModeText(summary.executionMode())).append("`\n\n");

        markdown.append("## 阶段状态\n\n");
        markdown.append("| 阶段 | 状态 | 已尝试 | 耗时(ms) | 结果 | 详细报告 |\n");
        markdown.append("| --- | --- | --- | ---: | --- | --- |\n");
        summary.stages().values().forEach(stage -> markdown.append("| ")
                .append(stage.name()).append(" | `").append(statusText(stage.status().name())).append("` | ")
                .append(yesNo(stage.attempted())).append(" | ").append(stage.durationMillis()).append(" | ")
                .append(escapeTable(reportText(stage.message()))).append(" | ")
                .append(stage.report().isBlank()
                        ? ""
                        : "[" + stage.report() + "](" + stage.report() + ")")
                .append(" |\n"));
        markdown.append("\n");

        if (!summary.manualReview().isEmpty()) {
            markdown.append("## 人工确认降噪\n\n");
            summary.manualReview().forEach((key, value) -> markdown.append("- ")
                    .append(manualReviewMetricText(key)).append("：`").append(value).append("`\n"));
            markdown.append("\n");
        }

        markdown.append("## 主要问题\n\n");
        if (summary.topIssues().isEmpty()) {
            markdown.append("没有发现需要处理的根因。\n\n");
        } else {
            markdown.append("| 级别 | 类别 | 模式 | 总数 | 根因 | 级联阻塞 | 建议 |\n");
            markdown.append("| --- | --- | --- | ---: | ---: | ---: | --- |\n");
            summary.topIssues().forEach(issue -> markdown.append("| ")
                    .append(severityText(issue.severity())).append(" | ").append(issue.category()).append(" | ")
                    .append(issue.pattern()).append(" | ").append(issue.count()).append(" | ")
                    .append(issue.rootCount()).append(" | ").append(issue.blockedCount()).append(" | ")
                    .append(escapeTable(issue.action())).append(" |\n"));
            markdown.append("\n");
        }

        if (!summary.nextActions().isEmpty()) {
            markdown.append("## 建议动作\n\n");
            summary.nextActions().forEach(action -> markdown.append("- ").append(action).append("\n"));
            markdown.append("\n");
        }
        return markdown.toString();
    }

    private String escapeTable(String value) {
        return value == null ? "" : value.replace("|", "\\|").replaceAll("[\\r\\n]+", " ");
    }

    private String scanMarkdown(ProjectScanResult scanResult) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# dm-adapter 扫描报告\n\n");
        appendScanSummary(markdown, scanResult);
        appendWarnings(markdown, scanResult.warnings());
        return markdown.toString();
    }

    private String migrationMarkdown(MigrationReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# dm-adapter 迁移报告\n\n");
        markdown.append("- 项目：`").append(report.projectRoot()).append("`\n");
        markdown.append("- 源数据库：`").append(report.sourceDb()).append("`\n");
        markdown.append("- 目标数据库：`").append(report.targetDb()).append("`\n");
        markdown.append("- dry-run：`").append(yesNo(report.dryRun())).append("`\n\n");

        appendScanSummary(markdown, report.scanResult());
        appendFileChanges(markdown, report.changedFiles());
        appendSqlChanges(markdown, "自动转换的 SQL", report.autoConvertedSqlItems());
        appendSqlChanges(markdown, "需人工确认的 SQL", report.manualReviewSqlItems());
        appendWarnings(markdown, report.riskWarnings());
        return markdown.toString();
    }

    private String sqlScriptMarkdown(SqlScriptMigrationReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 达梦 SQL 脚本转换报告\n\n");
        markdown.append("- 项目目录：`").append(report.projectRoot()).append("`\n");
        markdown.append("- 原始 SQL 目录：`").append(report.sqlRoot()).append("`\n");
        markdown.append("- 达梦 SQL 输出目录：`").append(report.sqlRootOut()).append("`\n");
        markdown.append("- dry-run 模式：`").append(yesNo(report.dryRun())).append("`\n");
        markdown.append("- 扫描 SQL 文件数：`").append(report.scannedFileCount()).append("`\n");
        markdown.append("- 已转换文件数：`").append(report.convertedFileCount()).append("`\n");
        markdown.append("- 需人工确认 SQL 数：`").append(report.manualReviewSqlCount()).append("`\n");
        if (!report.validationPlan().isBlank()) {
            markdown.append("- 严格验证清单：`").append(report.validationPlan()).append("`\n");
        }
        markdown.append("- 是否执行达梦试执行：`").append(yesNo(report.validationAttempted())).append("`\n");
        markdown.append("- 试执行状态：`").append(sqlScriptStatus(report.validationStatus())).append("`\n");
        markdown.append("- 试执行成功 SQL 数：`").append(report.validationSuccessCount()).append("`\n");
        markdown.append("- 试执行失败 SQL 数：`").append(report.validationFailureCount()).append("`\n\n");

        appendSqlScriptFiles(markdown, report.files());
        appendSqlScriptManualReviewItems(markdown, report.manualReviewItems());
        appendSqlScriptValidationFailures(markdown, report.validationFailures());
        appendSqlScriptWarnings(markdown, report.warnings());
        return markdown.toString();
    }

    private String sqlScriptValidationMarkdown(SqlScriptValidationReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# 达梦 SQL 脚本严格验证报告\n\n");
        markdown.append("- 验证清单：`").append(report.validationPlan()).append("`\n");
        markdown.append("- 是否连接并执行：`").append(yesNo(report.attempted())).append("`\n");
        markdown.append("- 状态：`").append(sqlScriptStatus(report.status())).append("`\n");
        markdown.append("- 成功 SQL 数：`").append(report.successCount()).append("`\n");
        markdown.append("- 失败 SQL 数：`").append(report.failureCount()).append("`\n");
        markdown.append("- 跳过人工确认 SQL 数：`").append(report.manualReviewSkippedCount()).append("`\n\n");
        appendSqlScriptValidationFailures(markdown, report.failures());
        appendSqlScriptWarnings(markdown, report.warnings());
        return markdown.toString();
    }

    private void appendScanSummary(StringBuilder markdown, ProjectScanResult scanResult) {
        markdown.append("## 扫描摘要\n\n");
        markdown.append("- Maven 项目：`").append(yesNo(scanResult.mavenProject())).append("`\n");
        markdown.append("- Spring Boot 项目：`").append(yesNo(scanResult.springBootProject())).append("`\n");
        markdown.append("- MyBatis 项目：`").append(yesNo(scanResult.myBatisProject())).append("`\n");
        markdown.append("- 已配置达梦 JDBC 驱动：`").append(yesNo(scanResult.hasDmJdbcDriver())).append("`\n");
        markdown.append("- Mapper XML 数量：`").append(scanResult.mapperXmlFiles().size()).append("`\n\n");
    }

    private void appendFileChanges(StringBuilder markdown, List<FileChange> fileChanges) {
        markdown.append("## 文件变更\n\n");
        if (fileChanges.isEmpty()) {
            markdown.append("没有文件变更。\n\n");
            return;
        }
        for (FileChange change : fileChanges) {
            markdown.append("- `").append(changeTypeText(change.changeType())).append("` `")
                    .append(change.path()).append("` - ")
                    .append(reportText(change.description()))
                    .append("（已应用：`").append(yesNo(change.applied())).append("`）\n");
        }
        markdown.append("\n");
    }

    private void appendSqlChanges(StringBuilder markdown, String title, List<SqlChange> sqlChanges) {
        markdown.append("## ").append(title).append("\n\n");
        if (sqlChanges.isEmpty()) {
            markdown.append("无。\n\n");
            return;
        }
        for (SqlChange sqlChange : sqlChanges) {
            markdown.append("- `").append(sqlChange.file()).append("` 语句 `")
                    .append(sqlChange.statementId()).append("`");
            if (!sqlChange.appliedRules().isEmpty()) {
                markdown.append("，规则 `").append(String.join(", ", sqlChange.appliedRules())).append("`");
            }
            if (sqlChange.manualReviewRequired()) {
                markdown.append("，原因：").append(sqlScriptReason(sqlChange.reason()));
            }
            markdown.append("\n");
            markdown.append("  - 原始 SQL：`").append(compact(sqlChange.originalSql())).append("`\n");
            markdown.append("  - 转换后 SQL：`").append(compact(sqlChange.convertedSql())).append("`\n");
        }
        markdown.append("\n");
    }

    private void appendSqlScriptFiles(StringBuilder markdown, List<SqlScriptFileResult> files) {
        markdown.append("## 脚本文件\n\n");
        if (files.isEmpty()) {
            markdown.append("没有扫描到 SQL 文件。\n\n");
            return;
        }
        markdown.append("| 原始文件 | 输出文件 | 执行 schema | 系统脚本 | SQL 语句数 | 自动转换数 | 人工确认数 | 试执行成功数 | 试执行失败数 |\n");
        markdown.append("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (SqlScriptFileResult file : files) {
            markdown.append("| `").append(file.sourceFile()).append("` | `")
                    .append(file.outputFile()).append("` | `")
                    .append(file.schema()).append("` | `")
                    .append(yesNo(file.systemScript())).append("` | ")
                    .append(file.statementCount()).append(" | ")
                    .append(file.convertedStatementCount()).append(" | ")
                    .append(file.manualReviewStatementCount()).append(" | ")
                    .append(file.validationSuccessCount()).append(" | ")
                    .append(file.validationFailureCount()).append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendSqlScriptManualReviewItems(
            StringBuilder markdown,
            List<SqlScriptManualReviewItem> manualReviewItems
    ) {
        markdown.append("## 需人工确认的 SQL\n\n");
        if (manualReviewItems.isEmpty()) {
            markdown.append("没有需要人工确认的 SQL。\n\n");
            return;
        }
        for (SqlScriptManualReviewItem item : manualReviewItems) {
            markdown.append("- `").append(item.sourceFile()).append("` 第 `")
                    .append(item.statementIndex()).append("` 条 SQL，原因：")
                    .append(sqlScriptReason(item.reason())).append("\n");
            markdown.append("  - 原始 SQL：`").append(compact(item.originalSql())).append("`\n");
            markdown.append("  - 转换后 SQL：`").append(compact(item.convertedSql())).append("`\n");
        }
        markdown.append("\n");
    }

    private void appendSqlScriptValidationFailures(
            StringBuilder markdown,
            List<SqlScriptValidationFailure> validationFailures
    ) {
        markdown.append("## 达梦试执行失败\n\n");
        if (validationFailures.isEmpty()) {
            markdown.append("没有试执行失败的 SQL。\n\n");
            return;
        }
        for (SqlScriptValidationFailure failure : validationFailures) {
            markdown.append("- `").append(failure.outputFile()).append("` 第 `")
                    .append(failure.statementIndex()).append("` 条 SQL，schema `")
                    .append(failure.schema()).append("`，分类 `")
                    .append(failure.category()).append("`\n");
            markdown.append("  - 错误摘要：").append(failure.errorSummary()).append("\n");
            markdown.append("  - 失败 SQL：`").append(failure.failedSqlSummary()).append("`\n");
        }
        markdown.append("\n");
    }

    private void appendSqlScriptWarnings(StringBuilder markdown, List<String> warnings) {
        markdown.append("## 风险提示\n\n");
        if (warnings.isEmpty()) {
            markdown.append("没有风险提示。\n");
            return;
        }
        for (String warning : warnings) {
            markdown.append("- ").append(reportText(warning)).append("\n");
        }
    }

    private void appendWarnings(StringBuilder markdown, List<String> warnings) {
        markdown.append("## 风险提示\n\n");
        if (warnings.isEmpty()) {
            markdown.append("没有风险提示。\n");
            return;
        }
        for (String warning : warnings) {
            markdown.append("- ").append(reportText(warning)).append("\n");
        }
    }

    private String compact(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String compact = sql.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 240) {
            return compact;
        }
        return compact.substring(0, 237) + "...";
    }

    private String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    private String statusText(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        return switch (status) {
            case "SUCCESS" -> "成功";
            case "PASSED" -> "通过";
            case "NO_CHANGES" -> "无变更";
            case "FAILED" -> "失败";
            case "COMPLETED_WITH_ISSUES" -> "完成但存在问题";
            case "RUNNING" -> "运行中";
            case "NOT_REQUESTED" -> "未请求";
            case "SKIPPED" -> "已跳过";
            case "TIMEOUT" -> "超时";
            default -> status;
        };
    }

    private String executionModeText(String executionMode) {
        if (executionMode == null || executionMode.isBlank()) {
            return "";
        }
        return switch (executionMode) {
            case "FULL_MUTATING_SHARED_DATABASE" -> "完整执行（会修改共享数据库）";
            case "NOT_EXECUTED" -> "未执行";
            default -> executionMode;
        };
    }

    private String failureStageText(String failureStage) {
        if (failureStage == null || failureStage.isBlank()) {
            return "";
        }
        return switch (failureStage) {
            case "manual-review" -> "人工确认";
            case "source-use-statement" -> "源脚本 USE 语句";
            case "migration" -> "项目迁移";
            case "remote-race" -> "远端分支并发更新";
            case "push" -> "推送";
            case "clone" -> "克隆";
            case "cache" -> "本地缓存";
            case "cache-safety" -> "缓存安全检查";
            case "fetch" -> "拉取远端更新";
            case "project" -> "项目检查";
            case "sql-source" -> "SQL 源目录检查";
            case "path-validation" -> "路径校验";
            case "jgit" -> "Git 操作";
            default -> failureStage;
        };
    }

    private String severityText(String severity) {
        if (severity == null || severity.isBlank()) {
            return "";
        }
        return switch (severity) {
            case "CRITICAL" -> "严重";
            case "ERROR" -> "错误";
            case "WARNING" -> "警告";
            case "INFO" -> "信息";
            default -> severity;
        };
    }

    private String manualReviewMetricText(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return switch (key) {
            case "rawItems" -> "原始条目数";
            case "uniqueStatements" -> "去重后 SQL 数";
            case "overlapWithAutomatic" -> "与自动转换重叠数";
            case "genericDynamicStatements" -> "泛化动态 SQL 数";
            case "sqlScriptManualReview" -> "SQL 脚本人工确认数";
            default -> key;
        };
    }

    private String changeTypeText(String changeType) {
        if (changeType == null || changeType.isBlank()) {
            return "";
        }
        return switch (changeType) {
            case "CREATE" -> "创建";
            case "UPDATE", "MODIFY" -> "修改";
            case "DELETE" -> "删除";
            default -> changeType;
        };
    }

    private String reportText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String status = sqlScriptStatus(text);
        if (!status.equals(text)) {
            return status;
        }
        if (text.equals("Migration contains manual-review items; no commit was created.")) {
            return "迁移结果包含需人工确认项，因此未创建提交。";
        }
        if (text.equals("Conversion changes were committed and pushed.")) {
            return "转换变更已提交并推送。";
        }
        if (text.equals("Remote head requires no additional Dameng adaptation changes.")) {
            return "远端最新版本无需额外的达梦适配变更。";
        }
        if (text.equals("Push result was uncertain, but the remote branch points at the created commit.")) {
            return "推送结果无法确认，但远端分支已指向本次创建的提交。";
        }
        if (text.equals("Remote branch moved during both conversion attempts; no commit was pushed.")) {
            return "两次转换期间远端分支均发生更新，因此未推送提交。";
        }
        if (text.equals("Batch attempts were exhausted.")) {
            return "批处理重试次数已用尽。";
        }
        if (text.equals("Source SQL contains USE <database>; remove it from the MySQL source script before batch conversion.")) {
            return "源 SQL 包含 USE <database>；请在批量转换前从 MySQL 源脚本中移除。";
        }
        if (text.startsWith("Offline migration failed with exit code ")) {
            return text.replace("Offline migration failed with exit code ", "离线迁移失败，退出码为 ");
        }
        if (text.startsWith("JGit push failed: ")) {
            return "JGit 推送失败：" + text.substring("JGit push failed: ".length());
        }
        if (text.startsWith("Copy mapper XML from ")) {
            return "计划复制 Mapper XML，来源：" + text.substring("Copy mapper XML from ".length());
        }
        if (text.startsWith("Copied mapper XML from ")) {
            return "已复制 Mapper XML，来源：" + text.substring("Copied mapper XML from ".length());
        }
        if (text.equals("Maintain SQL rewrite config keyColumns for upsert/insert-ignore rewrites")) {
            return "计划维护 SQL 重写配置中的 keyColumns，用于 upsert/insert-ignore 重写";
        }
        if (text.equals("Maintained SQL rewrite config keyColumns for upsert/insert-ignore rewrites")) {
            return "已维护 SQL 重写配置中的 keyColumns，用于 upsert/insert-ignore 重写";
        }
        if (text.startsWith("Add dependency ")) {
            return "计划添加依赖 " + text.substring("Add dependency ".length());
        }
        if (text.startsWith("Added dependency ")) {
            return "已添加依赖 " + text.substring("Added dependency ".length());
        }
        if (text.equals("Extract MyBatis annotation SQL to mapper XML")) {
            return "计划将 MyBatis 注解 SQL 提取到 Mapper XML";
        }
        if (text.equals("Extracted MyBatis annotation SQL to mapper XML")) {
            return "已将 MyBatis 注解 SQL 提取到 Mapper XML";
        }
        if (text.equals("Extract MyBatis annotation SQL to mapper-dm XML")) {
            return "计划将 MyBatis 注解 SQL 提取到 mapper-dm XML";
        }
        if (text.equals("Extracted MyBatis annotation SQL to mapper-dm XML")) {
            return "已将 MyBatis 注解 SQL 提取到 mapper-dm XML";
        }
        if (text.equals("Remove extracted MyBatis annotation SQL from Java mapper")) {
            return "计划从 Java Mapper 中移除已提取的 MyBatis 注解 SQL";
        }
        if (text.equals("Removed extracted MyBatis annotation SQL from Java mapper")) {
            return "已从 Java Mapper 中移除提取后的 MyBatis 注解 SQL";
        }
        if (text.equals("Fixed extracted MyBatis annotation SQL in mapper XML")) {
            return "已修复 Mapper XML 中提取出的 MyBatis 注解 SQL";
        }
        if (text.equals("Fixed Java mapper @Param annotations from mapper XML parameter names")) {
            return "已根据 Mapper XML 参数名修复 Java Mapper 的 @Param 注解";
        }
        if (text.equals("Generate Dameng SQL validation parameter configuration")) {
            return "生成达梦 SQL 验证参数配置";
        }
        if (text.equals("Generate framework-independent Dameng SQL validation runner")) {
            return "生成不依赖框架的达梦 SQL 验证运行器";
        }
        if (text.equals("DM_SQL_VALIDATION is not true; validation test was not executed.")) {
            return "DM_SQL_VALIDATION 未设为 true，因此未执行验证测试。";
        }
        if (text.equals("Dameng SQL validation test passed.")) {
            return "达梦 SQL 验证测试通过。";
        }
        if (text.startsWith("Dameng SQL validation test exited with code ")) {
            return text.replace("Dameng SQL validation test exited with code ", "达梦 SQL 验证测试退出，退出码为 ");
        }
        if (text.startsWith("Project path is not a directory: ")) {
            return "项目路径不是目录：" + text.substring("Project path is not a directory: ".length());
        }
        if (text.equals("pom.xml was not found at project root.")) {
            return "项目根目录下未找到 pom.xml。";
        }
        if (text.equals("Spring Boot dependency or parent was not detected.")) {
            return "未检测到 Spring Boot 依赖或父 POM。";
        }
        if (text.equals("MyBatis dependency was not detected in pom.xml.")) {
            return "pom.xml 中未检测到 MyBatis 依赖。";
        }
        if (text.equals("No MyBatis mapper XML files were detected under src/main/resources.")) {
            return "src/main/resources 下未检测到 MyBatis Mapper XML。";
        }
        if (text.equals("Dameng JDBC driver dependency was not detected.")) {
            return "未检测到达梦 JDBC 驱动依赖。";
        }
        if (text.equals("Migration was not applied because the project root does not contain pom.xml.")) {
            return "项目根目录不包含 pom.xml，因此未应用迁移。";
        }
        if (text.equals("Spring Boot dependency was not detected; generated configuration may need manual integration.")) {
            return "未检测到 Spring Boot 依赖；生成的配置可能需要人工接入。";
        }
        if (text.equals("MyBatis XML mapper usage was not fully detected; mapper migration may be incomplete.")) {
            return "未完整检测到 MyBatis XML Mapper 用法；Mapper 迁移结果可能不完整。";
        }
        if (text.equals("No pom.xml target was found for adding Dameng JDBC driver dependency.")) {
            return "未找到可添加达梦 JDBC 驱动依赖的 pom.xml。";
        }
        return text;
    }

    private String sqlScriptStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        if (status.equals("Dry run; SQL script validation skipped.")) {
            return "dry-run 模式；已跳过 SQL 脚本试执行。";
        }
        if (status.equals("No SQL script files were found.")) {
            return "未找到 SQL 脚本文件。";
        }
        if (status.equals("DM_SQL_VALIDATION is not true; SQL script validation skipped.")) {
            return "DM_SQL_VALIDATION 不是 true；已跳过 SQL 脚本试执行。";
        }
        if (status.equals("Batch mode; SQL script database validation was not requested.")) {
            return "Batch 模式未请求达梦 SQL 脚本试执行。";
        }
        if (status.startsWith("DM_SQL_VALIDATION is true but required variables are missing: ")) {
            return "DM_SQL_VALIDATION=true，但缺少必要环境变量："
                    + status.substring("DM_SQL_VALIDATION is true but required variables are missing: ".length());
        }
        if (status.equals("Dameng SQL script validation passed.")) {
            return "达梦 SQL 脚本试执行通过。";
        }
        if (status.equals("Dameng SQL script validation completed with failed SQL statements.")) {
            return "达梦 SQL 脚本试执行完成，但存在失败 SQL。";
        }
        if (status.startsWith("Dameng SQL script validation connection failed: ")) {
            return "达梦 SQL 脚本试执行连接失败："
                    + status.substring("Dameng SQL script validation connection failed: ".length());
        }
        if (status.equals("Dameng SQL script validation was skipped because the connection could not be opened.")) {
            return "达梦 SQL 脚本试执行因无法建立连接而跳过。";
        }
        if (status.startsWith("SQL script validation uses the first schema from ")) {
            return status
                    .replace("SQL script validation uses the first schema from ", "SQL 脚本试执行使用 ")
                    .replace(": ", " 的第一个 schema：");
        }
        if (status.startsWith("System SQL script has no --system-schema and will use the current connection schema: ")) {
            return "系统 SQL 脚本未指定 --system-schema，将使用当前连接 schema："
                    + status.substring("System SQL script has no --system-schema and will use the current connection schema: ".length());
        }
        return status;
    }

    private String sqlScriptReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        String dynamicMapperPrefix = "动态 Mapper SQL 存在尚未解决的兼容性风险。具体原因：";
        if (reason.startsWith(dynamicMapperPrefix)) {
            return dynamicMapperPrefix + sqlScriptReason(reason.substring(dynamicMapperPrefix.length()));
        }
        if (reason.startsWith("可疑字段长度修改")) {
            return reason;
        }
        if (reason.startsWith("整数算术表达式风险")) {
            return reason;
        }
        if (reason.startsWith("MySQL outer/cross UPDATE JOIN could not be converted safely:")) {
            return "MySQL 外连接/交叉连接 UPDATE JOIN 无法安全自动转换：达梦等价改写要求每个目标行最多只接收一个源行的值，"
                    + "但当前 SQL 无法证明该基数约束。请修正原始连接或去重逻辑，或提供真实的唯一性约束，不要任意选择源行。";
        }
        if (reason.equals("MySQL UPDATE JOIN shape could not be converted safely to Dameng UPDATE FROM.")) {
            return "当前 MySQL UPDATE JOIN 结构无法安全转换为达梦 UPDATE FROM。";
        }
        if (reason.equals("MySQL null-safe equality <=> could not be parsed safely for automatic Dameng rewrite.")) {
            return "无法安全解析 MySQL 空值安全等号 <=>，因此未执行达梦自动重写。";
        }
        if (reason.equals("ON DUPLICATE KEY UPDATE requires configured keyColumns for safe Dameng MERGE rewrite.")) {
            return "ON DUPLICATE KEY UPDATE 需要配置 keyColumns，才能安全重写为达梦 MERGE。";
        }
        if (reason.equals("INSERT IGNORE requires configured keyColumns for safe Dameng MERGE rewrite.")) {
            return "INSERT IGNORE 需要配置 keyColumns，才能安全重写为达梦 MERGE。";
        }
        if (reason.equals("REPLACE INTO has no safe automatic Dameng rewrite in MVP.")) {
            return "当前版本无法将 REPLACE INTO 安全地自动重写为达梦 SQL。";
        }
        if (reason.startsWith("Original ON DUPLICATE KEY UPDATE has no usable primary or unique conflict key ")) {
            return "根据项目或达梦元数据，原始 ON DUPLICATE KEY UPDATE 的插入列中没有可用的主键或唯一冲突键，"
                    + "其 UPDATE 分支按现有写法无法触发。请修正原始 SQL 或真实唯一约束，不要猜测 keyColumns。";
        }
        if (reason.startsWith("ON DUPLICATE KEY UPDATE could not be converted because authoritative primary ")) {
            return "由于无法从 --project、--sql-root 或达梦获取权威的主键/唯一键元数据，"
                    + "ON DUPLICATE KEY UPDATE 未自动转换。请提供真实 DDL、数据库连接或明确验证过的 keyColumns。";
        }
        if (reason.startsWith("ON DUPLICATE KEY UPDATE has more than one possible conflict key ")) {
            return "ON DUPLICATE KEY UPDATE 存在多个可能的冲突键，或无法消除歧义；请仅配置经过明确验证的 keyColumns。";
        }
        if (reason.equals("MySQL UPDATE JOIN is followed by MyBatis <where>; automatic text-segment rewrite would create duplicate WHERE.")) {
            return "MySQL UPDATE JOIN 后紧跟 MyBatis <where>；按文本片段自动重写会产生重复 WHERE。";
        }
        if (reason.startsWith("Dameng CREATE TABLE AS SELECT does not support JDBC bind parameters.")) {
            return "达梦 CREATE TABLE AS SELECT 不支持 JDBC 绑定参数。工具保留了原始 #{...} 绑定，"
                    + "因为替换为 ${...} 会引入 SQL 注入风险。请拆分为显式临时表 DDL 和参数化 INSERT ... SELECT，"
                    + "或将动态绑定简化为受支持的标量 <foreach>。";
        }
        if (reason.startsWith("SELECT uses resultType/automatic mapping and returns a Dameng special business column")) {
            return "SELECT 使用 resultType/自动映射，并以带前缀的物理列名返回达梦特殊业务列。"
                    + "达梦不接受原特殊名称作为结果别名；请改用显式 resultMap，并在 column 中填写物理 _column 名称。";
        }
        if (reason.startsWith("Result mapping contains a dynamic or unsupported database-column expression")) {
            return "结果映射包含带达梦特殊列名的动态或不支持的数据库列表达式。工具已保留映射属性；"
                    + "请只将数据库列一侧改为物理 _column 名称。";
        }
        if (reason.startsWith("The automatic rewrite produced malformed mapper XML")) {
            return "自动重写为该语句生成了格式错误的 Mapper XML；工具已保留原语句，并继续处理其他语句。";
        }
        if (reason.startsWith("Dynamic CREATE TABLE column COMMENT uses a double-quoted runtime value")) {
            return "动态 CREATE TABLE 列 COMMENT 使用双引号运行时值，无法安全重写为达梦语法。";
        }
        if (reason.startsWith("Dynamic CREATE TABLE trailing options still contain MyBatis nodes or placeholders")) {
            return "移除已支持的 MySQL 表选项后，动态 CREATE TABLE 尾部选项仍包含 MyBatis 节点或占位符，"
                    + "需要人工确认达梦写法。";
        }
        if (reason.equals("MySQL user variables such as @var require ROW_NUMBER, explicit variables, or procedure-level rewrite for Dameng.")) {
            return "MySQL 用户变量（如 @var）不能直接迁移到达梦；建议改为 ROW_NUMBER()、显式变量，或按存储过程语义人工重写。";
        }
        if (reason.equals("MySQL metadata SQL such as information_schema/database() requires manual Dameng rewrite.")) {
            return "MySQL 元数据查询（如 information_schema/database()）需要人工确认达梦等价写法。";
        }
        if (reason.equals("MySQL procedure HANDLER syntax needs manual confirmation for Dameng.")) {
            return "MySQL 存储过程 HANDLER 语法需要人工确认达梦等价写法。";
        }
        if (reason.equals("MySQL SIGNAL SQLSTATE handling needs manual confirmation for Dameng.")) {
            return "MySQL SIGNAL SQLSTATE 异常处理需要人工确认达梦等价写法。";
        }
        if (reason.equals("MySQL dynamic SQL in procedures needs manual confirmation for Dameng.")) {
            return "MySQL 存储过程动态 SQL 需要人工确认达梦等价写法。";
        }
        if (reason.equals("Trigger syntax differs between MySQL and Dameng and needs manual confirmation.")) {
            return "MySQL 与达梦触发器语法差异较大，需要人工确认。";
        }
        if (reason.equals("GROUP_CONCAT requires manual confirmation for Dameng aggregate syntax.")) {
            return "GROUP_CONCAT 聚合语法需要人工确认达梦等价写法。";
        }
        if (reason.equals("REGEXP requires manual confirmation because Dameng regular-expression syntax may differ from MySQL.")) {
            return "REGEXP 正则语法可能与 MySQL 不同，需要人工确认达梦等价写法。";
        }
        if (reason.equals("LIMIT on non-SELECT DML requires manual confirmation for Dameng.")) {
            return "非 SELECT DML 上的 LIMIT 需要人工确认达梦等价写法。";
        }
        String suffix = " requires manual confirmation because Dameng support or syntax may differ from MySQL.";
        if (reason.endsWith(suffix)) {
            return "`" + reason.substring(0, reason.length() - suffix.length())
                    + "` 需要人工确认，达梦支持情况或语法可能与 MySQL 不同。";
        }
        return reason;
    }

    private MigrationReport redactSensitiveSql(MigrationReport report) {
        return new MigrationReport(
                report.projectRoot(),
                report.sourceDb(),
                report.targetDb(),
                report.dryRun(),
                report.scanResult(),
                report.changedFiles(),
                redactSqlChanges(report.autoConvertedSqlItems()),
                redactSqlChanges(report.manualReviewSqlItems()),
                report.riskWarnings()
        );
    }

    private SqlScriptMigrationReport redactSensitiveSql(SqlScriptMigrationReport report) {
        return new SqlScriptMigrationReport(
                report.projectRoot(),
                report.sqlRoot(),
                report.sqlRootOut(),
                report.dryRun(),
                report.scannedFileCount(),
                report.convertedFileCount(),
                report.manualReviewSqlCount(),
                report.validationAttempted(),
                report.validationStatus(),
                report.validationSuccessCount(),
                report.validationFailureCount(),
                report.files(),
                redactManualReviewItems(report.manualReviewItems()),
                redactValidationFailures(report.validationFailures()),
                report.warnings(),
                report.validationPlan()
        );
    }

    private List<SqlChange> redactSqlChanges(List<SqlChange> sqlChanges) {
        return sqlChanges.stream()
                .map(sqlChange -> new SqlChange(
                        sqlChange.file(),
                        sqlChange.statementId(),
                        redactSql(sqlChange.originalSql()),
                        redactSql(sqlChange.convertedSql()),
                        sqlChange.appliedRules(),
                        sqlChange.manualReviewRequired(),
                        sqlChange.reason()
                ))
                .toList();
    }

    private List<SqlScriptManualReviewItem> redactManualReviewItems(List<SqlScriptManualReviewItem> items) {
        return items.stream()
                .map(item -> new SqlScriptManualReviewItem(
                        item.sourceFile(),
                        item.outputFile(),
                        item.statementIndex(),
                        item.reason(),
                        redactSql(item.originalSql()),
                        redactSql(item.convertedSql())
                ))
                .toList();
    }

    private List<SqlScriptValidationFailure> redactValidationFailures(List<SqlScriptValidationFailure> failures) {
        return failures.stream()
                .map(failure -> new SqlScriptValidationFailure(
                        failure.sourceFile(),
                        failure.outputFile(),
                        failure.schema(),
                        failure.statementIndex(),
                        failure.category(),
                        failure.errorSummary(),
                        redactSql(failure.failedSqlSummary())
                ))
                .toList();
    }

    private String redactSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        String redacted = redactFunctionArgument(sql, "AES_ENCRYPT", 1);
        redacted = redactFunctionArgument(redacted, "AES_DECRYPT", 1);
        redacted = redactFunctionArgument(redacted, "SF_ENCRYPT_CHAR", 2);
        redacted = redactFunctionArgument(redacted, "SF_DECRYPT_TO_CHAR", 2);
        return redacted;
    }

    private String redactFunctionArgument(String sql, String functionName, int argumentIndex) {
        StringBuilder redacted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                int end = skipSingleQuotedString(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (current == '"') {
                int end = skipDoubleQuotedText(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                int end = skipMyBatisPlaceholder(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (startsLineComment(sql, index)) {
                int end = skipUntilLineEnd(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (startsBlockComment(sql, index)) {
                int end = skipUntilBlockCommentEnd(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (startsFunction(sql, index, functionName)) {
                FunctionCall functionCall = readFunctionCall(sql, index, functionName);
                String replacement = functionCall == null
                        ? null
                        : redactFunctionCallArgument(sql, functionCall, argumentIndex);
                if (replacement == null) {
                    redacted.append(current);
                    index++;
                } else {
                    redacted.append(replacement);
                    index = functionCall.endIndex();
                    changed = true;
                }
            } else {
                redacted.append(current);
                index++;
            }
        }
        return changed ? redacted.toString() : sql;
    }

    private String redactFunctionCallArgument(String sql, FunctionCall functionCall, int argumentIndex) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(functionCall.body());
        if (argumentIndex >= arguments.size()) {
            return null;
        }
        TopLevelArgument argument = arguments.get(argumentIndex);
        if (!isStringLiteral(argument.text())) {
            return null;
        }
        String body = functionCall.body().substring(0, argument.startIndex())
                + "'******'"
                + functionCall.body().substring(argument.endIndex());
        return sql.substring(functionCall.startIndex(), functionCall.openParenIndex() + 1)
                + body
                + sql.substring(functionCall.closeParenIndex(), functionCall.endIndex());
    }

    private FunctionCall readFunctionCall(String sql, int functionNameStart, String functionName) {
        if (!startsFunction(sql, functionNameStart, functionName)) {
            return null;
        }
        int openParenIndex = functionNameStart + functionName.length();
        while (openParenIndex < sql.length() && Character.isWhitespace(sql.charAt(openParenIndex))) {
            openParenIndex++;
        }
        int closeParenIndex = findMatchingParen(sql, openParenIndex);
        if (closeParenIndex < 0) {
            return null;
        }
        return new FunctionCall(
                functionNameStart,
                openParenIndex,
                closeParenIndex,
                closeParenIndex + 1,
                sql.substring(openParenIndex + 1, closeParenIndex)
        );
    }

    private boolean startsFunction(String sql, int index, String functionName) {
        if (index > 0 && isIdentifierPart(sql.charAt(index - 1))) {
            return false;
        }
        if (index + functionName.length() > sql.length()
                || !sql.regionMatches(true, index, functionName, 0, functionName.length())) {
            return false;
        }
        int afterName = index + functionName.length();
        if (afterName < sql.length() && isIdentifierPart(sql.charAt(afterName))) {
            return false;
        }
        while (afterName < sql.length() && Character.isWhitespace(sql.charAt(afterName))) {
            afterName++;
        }
        return afterName < sql.length() && sql.charAt(afterName) == '(';
    }

    private int findMatchingParen(String sql, int openParenIndex) {
        int depth = 0;
        int index = openParenIndex;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
                index++;
            } else {
                index++;
            }
        }
        return -1;
    }

    private List<TopLevelArgument> splitTopLevelArguments(String body) {
        List<TopLevelArgument> arguments = new ArrayList<>();
        int depth = 0;
        int argumentStart = 0;
        int index = 0;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(body, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(body, index);
            } else if (startsMyBatisPlaceholder(body, index)) {
                index = skipMyBatisPlaceholder(body, index);
            } else if (startsLineComment(body, index)) {
                index = skipUntilLineEnd(body, index);
            } else if (startsBlockComment(body, index)) {
                index = skipUntilBlockCommentEnd(body, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (current == ',' && depth == 0) {
                arguments.add(new TopLevelArgument(body.substring(argumentStart, index), argumentStart, index));
                index++;
                argumentStart = index;
            } else {
                index++;
            }
        }
        arguments.add(new TopLevelArgument(body.substring(argumentStart), argumentStart, body.length()));
        return arguments;
    }

    private boolean isStringLiteral(String expression) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.charAt(0) == '\'') {
            return closedSingleQuotedStringEnd(trimmed, 0) == trimmed.length();
        }
        if (trimmed.charAt(0) == '"') {
            return closedDoubleQuotedTextEnd(trimmed, 0) == trimmed.length();
        }
        return false;
    }

    private int skipSingleQuotedString(String sql, int start) {
        int end = closedSingleQuotedStringEnd(sql, start);
        return end < 0 ? sql.length() : end;
    }

    private int closedSingleQuotedStringEnd(String sql, int start) {
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '\\' && index < sql.length()) {
                index++;
            } else if (current == '\'') {
                if (index < sql.length() && sql.charAt(index) == '\'') {
                    index++;
                } else {
                    return index;
                }
            }
        }
        return -1;
    }

    private int skipDoubleQuotedText(String sql, int start) {
        int end = closedDoubleQuotedTextEnd(sql, start);
        return end < 0 ? sql.length() : end;
    }

    private int closedDoubleQuotedTextEnd(String sql, int start) {
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '\\' && index < sql.length()) {
                index++;
            } else if (current == '"') {
                if (index < sql.length() && sql.charAt(index) == '"') {
                    index++;
                } else {
                    return index;
                }
            }
        }
        return -1;
    }

    private boolean startsMyBatisPlaceholder(String sql, int index) {
        return index + 1 < sql.length()
                && (sql.charAt(index) == '#' || sql.charAt(index) == '$')
                && sql.charAt(index + 1) == '{';
    }

    private int skipMyBatisPlaceholder(String sql, int start) {
        int end = sql.indexOf('}', start + 2);
        return end < 0 ? sql.length() : end + 1;
    }

    private boolean startsLineComment(String sql, int index) {
        return sql.startsWith("--", index)
                || (sql.charAt(index) == '#' && (index + 1 >= sql.length() || sql.charAt(index + 1) != '{'));
    }

    private int skipUntilLineEnd(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '\n') {
                break;
            }
        }
        return index;
    }

    private boolean startsBlockComment(String sql, int index) {
        return index + 1 < sql.length() && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*';
    }

    private int skipUntilBlockCommentEnd(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '*' && index < sql.length() && sql.charAt(index) == '/') {
                index++;
                break;
            }
        }
        return index;
    }

    private boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private record FunctionCall(int startIndex, int openParenIndex, int closeParenIndex, int endIndex, String body) {
    }

    private record TopLevelArgument(String text, int startIndex, int endIndex) {
    }
}
