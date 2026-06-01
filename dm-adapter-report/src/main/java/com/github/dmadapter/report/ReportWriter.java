package com.github.dmadapter.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ReportWriter {
    public static final String SCAN_REPORT_MARKDOWN = "dm-adapter-scan-report.md";
    public static final String SCAN_REPORT_JSON = "dm-adapter-scan-report.json";
    public static final String MIGRATION_REPORT_MARKDOWN = "dm-adapter-report.md";
    public static final String MIGRATION_REPORT_JSON = "dm-adapter-report.json";

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
        Path markdownPath = reportDir.resolve(MIGRATION_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(MIGRATION_REPORT_JSON);
        Files.writeString(markdownPath, migrationMarkdown(report), StandardCharsets.UTF_8);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), report);
        return new ReportPaths(markdownPath, jsonPath);
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
}
