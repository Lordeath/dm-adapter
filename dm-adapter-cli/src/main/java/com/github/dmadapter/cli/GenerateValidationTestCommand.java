package com.github.dmadapter.cli;

import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.StageStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "generate-validation-test",
        description = "Generate a JUnit/MyBatis JDBC test for validating mapper XML SQL against Dameng."
)
public class GenerateValidationTestCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, description = "Project root path.")
    private Path project;

    @Option(names = "--app-module", description = "Application module path or Maven artifactId used for generated test placement.")
    private Path appModule;

    @Option(names = "--mapper-dir", description = "Mapper XML directory used for configuration template discovery.")
    private Path mapperDir;

    @Option(names = "--config", description = "Validation config path. Defaults to <workspace>/sql-validation.yml.")
    private Path config;

    @Option(names = "--report-dir", description = "dm-adapter workspace directory. Defaults to <cwd>/.dm-adapter/<app-artifactId>.")
    private Path reportDir;

    @Option(names = "--schema", description = "Dameng schema to set before invoking mapper methods. Supports comma-separated fallback schemas.")
    private String schema;

    private final DmSqlValidationTestGenerator generator = new DmSqlValidationTestGenerator();
    private final ValidationTestRunner validationTestRunner = new ValidationTestRunner();
    private final AdapterWorkspaceResolver workspaceResolver = new AdapterWorkspaceResolver();
    private final LegacyWorkspaceMigrator legacyWorkspaceMigrator = new LegacyWorkspaceMigrator();

    @Override
    public Integer call() {
        try {
            Path workspaceDir = workspaceResolver.resolve(project, appModule, reportDir);
            List<String> workspaceWarnings = legacyWorkspaceMigrator.migrateDefaults(
                    project,
                    workspaceDir,
                    true,
                    config == null
            );
            CliLogger.info("dm-adapter workspace: " + workspaceDir);
            ValidationTestGenerationResult result = generator.generate(
                    project,
                    appModule,
                    mapperDir,
                    config,
                    schema,
                    workspaceDir,
                    workspaceDir.resolve(LegacyWorkspaceMigrator.REWRITE_CONFIG),
                    workspaceWarnings
            );
            printResult(result);
            DmValidationEnvironment environment = DmValidationEnvironment.fromSystem();
            ValidationTestRunResult runResult = validationTestRunner.runIfConfigured(result, environment);
            printValidationRunResult(runResult);
            return validationExitCode(runResult, environment);
        } catch (Exception e) {
            CliLogger.error("Validation test generation failed: " + e.getMessage());
            return 1;
        }
    }

    static void printResult(ValidationTestGenerationResult result) {
        CliLogger.info("Dameng SQL validation test generation completed.");
        CliLogger.info("Config: " + result.configPath());
        CliLogger.info("Test: " + result.testPath());
        CliLogger.info("File changes: " + result.fileChanges().size());
        for (FileChange fileChange : result.fileChanges()) {
            CliLogger.info("- " + fileChange.changeType() + " " + fileChange.path());
        }
        for (String warning : result.warnings()) {
            CliLogger.info("Warning: " + warning);
        }
    }

    static void printValidationRunResult(ValidationTestRunResult result) {
        if (!result.attempted()) {
            CliLogger.info(result.message());
            return;
        }
        CliLogger.info(result.message());
        if (result.reportPath() != null) {
            CliLogger.info("Validation report: " + result.reportPath());
        }
        if (!result.outputTail().isEmpty()) {
            CliLogger.info("Validation Maven diagnostics/output:");
            for (String line : result.outputTail()) {
                CliLogger.info(line);
            }
        }
    }

    private int validationExitCode(
            ValidationTestRunResult result,
            DmValidationEnvironment environment
    ) {
        if (!environment.validationEnabled()) {
            return 0;
        }
        if (!environment.ready() || result.status() == StageStatus.TIMEOUT) {
            return 4;
        }
        if (result.exitCode() == 0) {
            return 0;
        }
        if (result.reportPath() == null) {
            return 1;
        }
        Path jsonPath = result.reportPath().resolveSibling("sql-validation-report.json");
        try {
            JsonNode root = new ObjectMapper().readTree(jsonPath.toFile());
            for (JsonNode pattern : root.path("failurePatterns")) {
                String name = pattern.path("name").asText();
                if ("INVALID_SCHEMA".equals(name) || "DATABASE_CONNECTION".equals(name)) {
                    return 4;
                }
            }
            return root.path("summary").path("failed").asLong() > 0L ? 3 : 1;
        } catch (Exception ignored) {
            return 1;
        }
    }
}
