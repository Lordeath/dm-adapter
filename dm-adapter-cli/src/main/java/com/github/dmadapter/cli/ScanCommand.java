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
            System.out.println("Scan completed.");
            System.out.println("Maven project: " + scanResult.mavenProject());
            System.out.println("Spring Boot project: " + scanResult.springBootProject());
            System.out.println("MyBatis XML project: " + scanResult.myBatisProject());
            System.out.println("Has Dameng JDBC driver: " + scanResult.hasDmJdbcDriver());
            System.out.println("Mapper XML files: " + scanResult.mapperXmlFiles().size());
            System.out.println("Report: " + reportPaths.markdownPath());
            return 0;
        } catch (Exception e) {
            System.err.println("Scan failed: " + e.getMessage());
            return 1;
        }
    }
}
