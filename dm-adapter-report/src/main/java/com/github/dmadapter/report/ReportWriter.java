package com.github.dmadapter.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.BatchMigrationReport;
import com.github.dmadapter.core.DmAdapterSummary;
import com.github.dmadapter.core.FileChange;
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
    public static final String BATCH_REPORT_MARKDOWN = "dm-adapter-batch-report.md";
    public static final String BATCH_REPORT_JSON = "dm-adapter-batch-report.json";

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

    public ReportPaths writeBatchMigrationReport(BatchMigrationReport report, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        Path markdownPath = reportDir.resolve(BATCH_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(BATCH_REPORT_JSON);
        writeStringAtomically(markdownPath, batchMigrationMarkdown(report));
        writeJsonAtomically(jsonPath, report);
        return new ReportPaths(markdownPath, jsonPath);
    }

    private String batchMigrationMarkdown(BatchMigrationReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# dm-adapter Batch Report\n\n");
        markdown.append("- 状态：`").append(report.status()).append("`\n");
        markdown.append("- 生成时间：`").append(report.generatedAt()).append("`\n");
        markdown.append("- 项目：`").append(report.projectRoot()).append("`\n");
        markdown.append("- 远端分支：`").append(report.remote()).append("/")
                .append(report.branch()).append("`\n");
        markdown.append("- 基础提交：`").append(report.baseCommit()).append("`\n");
        markdown.append("- 推送提交：`").append(report.pushedCommit()).append("`\n");
        markdown.append("- 尝试次数：`").append(report.attempts()).append("`\n");
        if (!report.failureStage().isBlank()) {
            markdown.append("- 失败阶段：`").append(report.failureStage()).append("`\n");
        }
        if (!report.message().isBlank()) {
            markdown.append("- 结果：").append(report.message()).append("\n");
        }
        markdown.append("\n## 变更文件\n\n");
        if (report.changedFiles().isEmpty()) {
            markdown.append("无。\n");
        } else {
            report.changedFiles().forEach(path -> markdown.append("- `").append(path).append("`\n"));
        }
        markdown.append("\n## 详细报告\n\n");
        if (!report.migrationReport().isBlank()) {
            markdown.append("- [项目迁移报告](").append(report.migrationReport()).append(")\n");
        }
        if (!report.sqlScriptReport().isBlank()) {
            markdown.append("- [SQL 脚本迁移报告](").append(report.sqlScriptReport()).append(")\n");
        }
        if (report.migrationReport().isBlank() && report.sqlScriptReport().isBlank()) {
            markdown.append("无。\n");
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
        markdown.append("- 总体状态：`").append(summary.overallStatus()).append("`\n");
        markdown.append("- dry-run：`").append(summary.dryRun()).append("`\n");
        markdown.append("- 数据库执行模式：`").append(summary.executionMode()).append("`\n\n");

        markdown.append("## 阶段状态\n\n");
        markdown.append("| 阶段 | 状态 | 已尝试 | 耗时(ms) | 结果 | 详细报告 |\n");
        markdown.append("| --- | --- | --- | ---: | --- | --- |\n");
        summary.stages().values().forEach(stage -> markdown.append("| ")
                .append(stage.name()).append(" | `").append(stage.status()).append("` | ")
                .append(stage.attempted()).append(" | ").append(stage.durationMillis()).append(" | ")
                .append(escapeTable(stage.message())).append(" | ")
                .append(stage.report().isBlank()
                        ? ""
                        : "[" + stage.report() + "](" + stage.report() + ")")
                .append(" |\n"));
        markdown.append("\n");

        if (!summary.manualReview().isEmpty()) {
            markdown.append("## 人工确认降噪\n\n");
            summary.manualReview().forEach((key, value) -> markdown.append("- ")
                    .append(key).append("：`").append(value).append("`\n"));
            markdown.append("\n");
        }

        markdown.append("## 主要问题\n\n");
        if (summary.topIssues().isEmpty()) {
            markdown.append("没有发现需要处理的根因。\n\n");
        } else {
            markdown.append("| 级别 | 类别 | 模式 | 总数 | 根因 | 级联阻塞 | 建议 |\n");
            markdown.append("| --- | --- | --- | ---: | ---: | ---: | --- |\n");
            summary.topIssues().forEach(issue -> markdown.append("| ")
                    .append(issue.severity()).append(" | ").append(issue.category()).append(" | ")
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
        markdown.append("# dm-adapter Scan Report\n\n");
        appendScanSummary(markdown, scanResult);
        appendWarnings(markdown, scanResult.warnings());
        return markdown.toString();
    }

    private String migrationMarkdown(MigrationReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# dm-adapter Migration Report\n\n");
        markdown.append("- Project: `").append(report.projectRoot()).append("`\n");
        markdown.append("- Source DB: `").append(report.sourceDb()).append("`\n");
        markdown.append("- Target DB: `").append(report.targetDb()).append("`\n");
        markdown.append("- Dry run: `").append(report.dryRun()).append("`\n\n");

        appendScanSummary(markdown, report.scanResult());
        appendFileChanges(markdown, report.changedFiles());
        appendSqlChanges(markdown, "Automatic SQL Conversions", report.autoConvertedSqlItems());
        appendSqlChanges(markdown, "Manual Review SQL Items", report.manualReviewSqlItems());
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
        markdown.append("## Scan Summary\n\n");
        markdown.append("- Maven project: `").append(scanResult.mavenProject()).append("`\n");
        markdown.append("- Spring Boot project: `").append(scanResult.springBootProject()).append("`\n");
        markdown.append("- MyBatis project: `").append(scanResult.myBatisProject()).append("`\n");
        markdown.append("- Has Dameng JDBC driver: `").append(scanResult.hasDmJdbcDriver()).append("`\n");
        markdown.append("- Mapper XML count: `").append(scanResult.mapperXmlFiles().size()).append("`\n\n");
    }

    private void appendFileChanges(StringBuilder markdown, List<FileChange> fileChanges) {
        markdown.append("## File Changes\n\n");
        if (fileChanges.isEmpty()) {
            markdown.append("No file changes.\n\n");
            return;
        }
        for (FileChange change : fileChanges) {
            markdown.append("- `").append(change.changeType()).append("` `")
                    .append(change.path()).append("` - ")
                    .append(change.description())
                    .append(" (applied: `").append(change.applied()).append("`)\n");
        }
        markdown.append("\n");
    }

    private void appendSqlChanges(StringBuilder markdown, String title, List<SqlChange> sqlChanges) {
        markdown.append("## ").append(title).append("\n\n");
        if (sqlChanges.isEmpty()) {
            markdown.append("No items.\n\n");
            return;
        }
        for (SqlChange sqlChange : sqlChanges) {
            markdown.append("- `").append(sqlChange.file()).append("` statement `")
                    .append(sqlChange.statementId()).append("`");
            if (!sqlChange.appliedRules().isEmpty()) {
                markdown.append(" rules `").append(String.join(", ", sqlChange.appliedRules())).append("`");
            }
            if (sqlChange.manualReviewRequired()) {
                markdown.append(" reason: ").append(sqlChange.reason());
            }
            markdown.append("\n");
            markdown.append("  - Original: `").append(compact(sqlChange.originalSql())).append("`\n");
            markdown.append("  - Converted: `").append(compact(sqlChange.convertedSql())).append("`\n");
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
            markdown.append("- ").append(sqlScriptStatus(warning)).append("\n");
        }
    }

    private void appendWarnings(StringBuilder markdown, List<String> warnings) {
        markdown.append("## Risk Warnings\n\n");
        if (warnings.isEmpty()) {
            markdown.append("No warnings.\n");
            return;
        }
        for (String warning : warnings) {
            markdown.append("- ").append(warning).append("\n");
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
        if (reason.startsWith("可疑字段长度修改")) {
            return reason;
        }
        if (reason.startsWith("整数算术表达式风险")) {
            return reason;
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
