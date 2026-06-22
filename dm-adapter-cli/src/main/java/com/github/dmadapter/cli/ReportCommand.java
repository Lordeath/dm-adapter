package com.github.dmadapter.cli;

import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.report.ReportReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "report", description = "Print the latest migration report summary.")
public class ReportCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, description = "Project root path.")
    private Path project;

    @Option(names = "--report-dir", description = "Report directory. Defaults to <project>/.dm-adapter.")
    private Path reportDir;

    @Override
    public Integer call() {
        try {
            Path actualReportDir = reportDir == null
                    ? project.toAbsolutePath().normalize().resolve(".dm-adapter")
                    : reportDir.toAbsolutePath().normalize();
            if (!Files.exists(actualReportDir.resolve("dm-adapter-report.json"))) {
                CliLogger.error("Migration report not found: " + actualReportDir.resolve("dm-adapter-report.json"));
                return 2;
            }
            MigrationReport report = new ReportReader().readMigrationReport(actualReportDir);
            CliLogger.info("Project: " + report.projectRoot());
            CliLogger.info("Dry run: " + report.dryRun());
            CliLogger.info("File changes: " + report.changedFiles().size());
            CliLogger.info("Automatic SQL conversions: " + report.autoConvertedSqlItems().size());
            CliLogger.info("Manual review SQL items: " + report.manualReviewSqlItems().size());
            CliLogger.info("Risk warnings: " + report.riskWarnings().size());
            return 0;
        } catch (Exception e) {
            CliLogger.error("Report failed: " + e.getMessage());
            return 1;
        }
    }
}
