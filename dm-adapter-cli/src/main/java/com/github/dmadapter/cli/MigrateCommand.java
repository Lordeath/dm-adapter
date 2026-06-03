package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.DependencyCoordinate;
import com.github.dmadapter.core.DmAdapterException;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.maven.PomModifier;
import com.github.dmadapter.maven.PomTargetSelection;
import com.github.dmadapter.maven.PomTargetSelector;
import com.github.dmadapter.mybatis.MapperMigrator;
import com.github.dmadapter.report.ReportPaths;
import com.github.dmadapter.report.ReportWriter;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(name = "migrate", description = "Create a low-intrusion Dameng migration plan or apply it.")
public class MigrateCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, description = "Project root path.")
    private Path project;

    @Option(names = "--source-db", defaultValue = AdapterContext.DEFAULT_SOURCE_DB, description = "Source database. MVP supports mysql.")
    private String sourceDb;

    @Option(names = "--target-db", defaultValue = AdapterContext.DEFAULT_TARGET_DB, description = "Target database. MVP supports dm.")
    private String targetDb;

    @Option(names = "--dry-run", description = "Only generate a migration report without changing project files.")
    private boolean dryRun;

    @Option(names = "--dm-driver", description = "Dameng JDBC dependency coordinate: groupId:artifactId:version.")
    private String dmDriver;

    @Option(names = "--report-dir", description = "Report output directory. Defaults to <project>/.dm-adapter.")
    private Path reportDir;

    @Option(names = "--mapper-dir", description = "Target mapper directory. Defaults to src/main/resources/mapper-dm.")
    private Path mapperDir;

    private final ProjectScanner projectScanner = new ProjectScanner();
    private final PomModifier pomModifier = new PomModifier();
    private final PomTargetSelector pomTargetSelector = new PomTargetSelector();
    private final MapperMigrator mapperMigrator = new MapperMigrator();
    private final ReportWriter reportWriter = new ReportWriter();

    @Override
    public Integer call() {
        try {
            AdapterContext context = buildContext();
            validateSupportedDatabases(context);
            ProjectScanResult scanResult = projectScanner.scan(context);

            List<FileChange> fileChanges = new ArrayList<>();
            List<String> warnings = new ArrayList<>(scanResult.warnings());
            if (!scanResult.mavenProject()) {
                warnings.add("Migration was not applied because the project root does not contain pom.xml.");
                writeReport(context, scanResult, fileChanges, List.of(), List.of(), warnings);
                return 2;
            }

            if (!scanResult.springBootProject()) {
                warnings.add("Spring Boot dependency was not detected; generated configuration may need manual integration.");
            }
            if (!scanResult.myBatisProject()) {
                warnings.add("MyBatis XML mapper usage was not fully detected; mapper migration may be incomplete.");
            }

            PomTargetSelection pomTargetSelection = pomTargetSelector.select(context.projectRoot(), scanResult.mapperXmlFiles());
            warnings.addAll(pomTargetSelection.warnings());
            if (pomTargetSelection.pomPaths().isEmpty()) {
                warnings.add("No pom.xml target was found for adding Dameng JDBC driver dependency.");
            }
            for (Path pomPath : pomTargetSelection.pomPaths()) {
                Optional<FileChange> pomChange = pomModifier.ensureDependency(
                        pomPath,
                        context.dmDriverCoordinate(),
                        context.dryRun()
                );
                pomChange.ifPresent(fileChanges::add);
            }

            MapperMigrationResult mapperMigrationResult = mapperMigrator.migrate(
                    scanResult,
                    context,
                    new MySqlToDmSqlConverter()
            );
            fileChanges.addAll(mapperMigrationResult.fileChanges());
            warnings.addAll(mapperMigrationResult.warnings());

            ReportPaths reportPaths = writeReport(
                    context,
                    scanResult,
                    fileChanges,
                    mapperMigrationResult.automaticConversions(),
                    mapperMigrationResult.manualReviewItems(),
                    warnings
            );
            printMigrationSummary(context, scanResult, mapperMigrationResult, fileChanges, reportPaths);
            return 0;
        } catch (Exception e) {
            System.err.println("Migration failed: " + e.getMessage());
            return 1;
        }
    }

    private AdapterContext buildContext() {
        return AdapterContext.builder(project)
                .sourceDb(sourceDb)
                .targetDb(targetDb)
                .dryRun(dryRun)
                .dmDriverCoordinate(DependencyCoordinate.parse(dmDriver))
                .reportDir(reportDir)
                .mapperTargetDir(mapperDir)
                .build();
    }

    private void validateSupportedDatabases(AdapterContext context) {
        if (!"mysql".equals(context.sourceDb())) {
            throw new DmAdapterException("MVP only supports --source-db mysql.");
        }
        if (!"dm".equals(context.targetDb())) {
            throw new DmAdapterException("MVP only supports --target-db dm.");
        }
    }

    private ReportPaths writeReport(
            AdapterContext context,
            ProjectScanResult scanResult,
            List<FileChange> fileChanges,
            List<com.github.dmadapter.core.SqlChange> automaticConversions,
            List<com.github.dmadapter.core.SqlChange> manualReviewItems,
            List<String> warnings
    ) throws Exception {
        MigrationReport report = new MigrationReport(
                context.projectRoot().toString(),
                context.sourceDb(),
                context.targetDb(),
                context.dryRun(),
                scanResult,
                fileChanges,
                automaticConversions,
                manualReviewItems,
                warnings
        );
        return reportWriter.writeMigrationReport(report, context.reportDir());
    }

    private void printMigrationSummary(
            AdapterContext context,
            ProjectScanResult scanResult,
            MapperMigrationResult mapperMigrationResult,
            List<FileChange> fileChanges,
            ReportPaths reportPaths
    ) {
        System.out.println("Migration " + (context.dryRun() ? "dry-run" : "completed") + ".");
        System.out.println("Maven project: " + scanResult.mavenProject());
        System.out.println("Mapper XML files: " + scanResult.mapperXmlFiles().size());
        System.out.println("File changes: " + fileChanges.size());
        System.out.println("Automatic SQL conversions: " + mapperMigrationResult.automaticConversions().size());
        System.out.println("Manual review SQL items: " + mapperMigrationResult.manualReviewItems().size());
        System.out.println("Report: " + reportPaths.markdownPath());
    }
}
