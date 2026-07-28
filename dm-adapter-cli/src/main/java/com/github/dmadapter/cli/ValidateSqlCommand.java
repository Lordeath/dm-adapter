package com.github.dmadapter.cli;

import com.github.dmadapter.core.DamengTargetCapabilities;
import com.github.dmadapter.core.SqlScriptValidationReport;
import com.github.dmadapter.report.ReportPaths;
import com.github.dmadapter.report.ReportWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Command(
        name = "validate-sql",
        mixinStandardHelpOptions = true,
        description = "Execute only hash-verified Dameng SQL from a migration validation plan."
)
public class ValidateSqlCommand implements Callable<Integer> {
    private static final long CAPABILITY_TIMEOUT_SECONDS = 12L;

    @Option(names = "--project", required = true, description = "Project root path used by the migration plan.")
    private Path project;

    @Option(names = "--report-dir", description = "dm-adapter workspace directory.")
    private Path reportDir;

    @Option(
            names = "--plan",
            description = "Strict SQL validation plan. Defaults to <workspace>/"
                    + SqlScriptValidationPlanStore.DEFAULT_FILE_NAME + "."
    )
    private Path plan;

    private final AdapterWorkspaceResolver workspaceResolver = new AdapterWorkspaceResolver();
    private final SqlScriptValidationPlanStore planStore = new SqlScriptValidationPlanStore();
    private final DamengTargetCapabilitiesReader capabilitiesReader = new DamengTargetCapabilitiesReader();
    private final SqlScriptValidator validator =
            SqlScriptValidator.withProgress(message -> CliLogger.info("[validate-sql] " + message));
    private final ReportWriter reportWriter = new ReportWriter();

    @Override
    public Integer call() {
        Path actualReportDir = workspaceResolver.resolve(project, null, reportDir);
        Path actualPlan = plan == null
                ? actualReportDir.resolve(SqlScriptValidationPlanStore.DEFAULT_FILE_NAME)
                : plan.toAbsolutePath().normalize();
        DmValidationEnvironment environment = DmValidationEnvironment.fromSystem();
        try {
            if (!environment.validationEnabled()) {
                return writePreflightFailure(
                        actualReportDir,
                        actualPlan,
                        "DM_SQL_VALIDATION is not true; SQL script validation skipped.",
                        "Set DM_SQL_VALIDATION=true to acknowledge that validation mutates the target test database."
                );
            }
            if (!environment.ready()) {
                return writePreflightFailure(
                        actualReportDir,
                        actualPlan,
                        "DM_SQL_VALIDATION is true but required variables are missing: "
                                + environment.missingVariables(),
                        "No SQL statements were executed."
                );
            }

            SqlScriptValidationPlanStore.LoadedValidationPlan loadedPlan = planStore.load(actualPlan);
            Path expectedProject = Path.of(loadedPlan.plan().projectRoot()).toAbsolutePath().normalize();
            Path requestedProject = project.toAbsolutePath().normalize();
            if (!expectedProject.equals(requestedProject)) {
                return writePreflightFailure(
                        actualReportDir,
                        actualPlan,
                        "SQL validation plan project does not match --project.",
                        "Expected " + expectedProject + " but received " + requestedProject + "."
                );
            }
            DamengTargetCapabilities actualCapabilities;
            try {
                actualCapabilities = MigrateCommand.runWithMetadataTimeout(
                        () -> capabilitiesReader.read(environment),
                        CAPABILITY_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS,
                        "Dameng target capability lookup"
                );
            } catch (Exception capabilityFailure) {
                return writePreflightFailure(
                        actualReportDir,
                        actualPlan,
                        "Dameng target capability preflight failed before SQL execution.",
                        safeMessage(capabilityFailure, environment)
                );
            }
            planStore.verifyCapabilities(
                    loadedPlan.plan().targetCapabilities(),
                    actualCapabilities,
                    planStore.containsBackticks(loadedPlan)
            );

            SqlScriptValidationRun run = validator.validate(loadedPlan.files(), environment);
            List<String> warnings = new ArrayList<>(run.warnings());
            if (loadedPlan.manualReviewCount() > 0) {
                warnings.add("Skipped " + loadedPlan.manualReviewCount()
                        + " statements marked MANUAL_REVIEW by the migration plan.");
            }
            SqlScriptValidationReport report = new SqlScriptValidationReport(
                    actualPlan.toString(),
                    run.attempted(),
                    run.status(),
                    run.successCount(),
                    run.failureCount(),
                    loadedPlan.manualReviewCount(),
                    run.failures(),
                    warnings
            );
            ReportPaths paths = reportWriter.writeSqlScriptValidationReport(report, actualReportDir);
            CliLogger.info("SQL validation report written: " + paths.markdownPath());
            if (!run.attempted()) {
                return 4;
            }
            boolean infrastructureFailure = run.failures().stream().anyMatch(failure ->
                    "VALIDATION_TIMEOUT".equals(failure.category())
                            || "INVALID_SCHEMA".equals(failure.category())
                            || "OBJECT_STATUS_VALIDATION_FAILED".equals(failure.category()));
            if (infrastructureFailure) {
                return 4;
            }
            return run.failureCount() > 0 || loadedPlan.manualReviewCount() > 0 ? 3 : 0;
        } catch (IllegalArgumentException e) {
            return writePreflightFailure(
                    actualReportDir,
                    actualPlan,
                    "SQL validation preflight failed.",
                    e.getMessage()
            );
        } catch (Exception e) {
            CliLogger.error("SQL validation failed: " + safeMessage(e, environment));
            return 1;
        }
    }

    private int writePreflightFailure(
            Path actualReportDir,
            Path actualPlan,
            String status,
            String warning
    ) {
        try {
            ReportPaths paths = reportWriter.writeSqlScriptValidationReport(
                    new SqlScriptValidationReport(
                            actualPlan.toString(),
                            false,
                            status,
                            0,
                            0,
                            0,
                            List.of(),
                            List.of(warning == null ? "" : warning)
                    ),
                    actualReportDir
            );
            CliLogger.error(status);
            CliLogger.info("SQL validation report written: " + paths.markdownPath());
            return 4;
        } catch (Exception reportFailure) {
            CliLogger.error("SQL validation preflight failed and its report could not be written: "
                    + reportFailure.getMessage());
            return 1;
        }
    }

    private String safeMessage(Exception exception, DmValidationEnvironment environment) {
        String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        if (environment == null) {
            return message;
        }
        String redacted = message;
        if (!environment.jdbcUrl().isBlank()) {
            redacted = redacted.replace(environment.jdbcUrl(), "******");
        }
        if (!environment.username().isBlank()) {
            redacted = redacted.replace(environment.username(), "******");
        }
        if (!environment.password().isBlank()) {
            redacted = redacted.replace(environment.password(), "******");
        }
        return redacted;
    }
}
