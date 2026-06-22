package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.DependencyCoordinate;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.report.ReportPaths;
import com.github.dmadapter.report.ReportWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "scan", description = "Scan a Maven Spring Boot MyBatis project.")
public class ScanCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, description = "Project root path.")
    private Path project;

    @Option(names = "--dm-driver", description = "Dameng JDBC dependency coordinate: groupId:artifactId:version.")
    private String dmDriver;

    @Option(names = "--report-dir", description = "Report output directory. Defaults to <project>/.dm-adapter.")
    private Path reportDir;

    private final ProjectScanner projectScanner = new ProjectScanner();
    private final ReportWriter reportWriter = new ReportWriter();

    @Override
    public Integer call() {
        try {
            AdapterContext context = AdapterContext.builder(project)
                    .dmDriverCoordinate(DependencyCoordinate.parse(dmDriver))
                    .reportDir(reportDir)
                    .dryRun(true)
                    .build();
            ProjectScanResult scanResult = projectScanner.scan(context);
            ReportPaths reportPaths = reportWriter.writeScanReport(scanResult, context.reportDir());
            CliLogger.info("Scan completed.");
            CliLogger.info("Maven project: " + scanResult.mavenProject());
            CliLogger.info("Spring Boot project: " + scanResult.springBootProject());
            CliLogger.info("MyBatis XML project: " + scanResult.myBatisProject());
            CliLogger.info("Has Dameng JDBC driver: " + scanResult.hasDmJdbcDriver());
            CliLogger.info("Mapper XML files: " + scanResult.mapperXmlFiles().size());
            CliLogger.info("Report: " + reportPaths.markdownPath());
            return 0;
        } catch (Exception e) {
            CliLogger.error("Scan failed: " + e.getMessage());
            return 1;
        }
    }
}
