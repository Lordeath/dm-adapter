package com.github.dmadapter.cli;

import com.github.dmadapter.core.FileChange;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "generate-validation-test",
        description = "Generate a JUnit/MyBatis JDBC test for validating mapper XML SQL against Dameng."
)
public class GenerateValidationTestCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, description = "Project root path.")
    private Path project;

    @Option(names = "--app-module", description = "Application module path used for generated test placement, relative to project root or absolute.")
    private Path appModule;

    @Option(names = "--mapper-dir", description = "Mapper XML directory used for configuration template discovery.")
    private Path mapperDir;

    @Option(names = "--config", description = "Validation config path. Defaults to <project>/.dm-adapter/sql-validation.yml.")
    private Path config;

    @Option(names = "--schema", description = "Dameng schema to set before invoking mapper methods.")
    private String schema;

    private final DmSqlValidationTestGenerator generator = new DmSqlValidationTestGenerator();

    @Override
    public Integer call() {
        try {
            ValidationTestGenerationResult result = generator.generate(project, appModule, mapperDir, config, schema);
            printResult(result);
            return 0;
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
}
