package com.github.dmadapter.cli;

import com.github.dmadapter.core.BatchRepositoryReport;
import com.github.dmadapter.core.BatchRunReport;
import com.github.dmadapter.core.DmAdapterException;
import com.github.dmadapter.report.ReportPaths;
import com.github.dmadapter.report.ReportWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;

@Command(
        name = "batch",
        mixinStandardHelpOptions = true,
        description = "Run unattended offline migrations for repositories defined in YAML."
)
public class BatchCommand implements Callable<Integer> {
    private static final DateTimeFormatter RUN_ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    @Option(names = "--config", required = true, description = "Batch YAML configuration file.")
    private Path configPath;

    private final BatchConfigLoader configLoader;
    private final BatchReportRetention reportRetention;
    private final ReportWriter reportWriter;

    public BatchCommand() {
        this(new BatchConfigLoader(), new BatchReportRetention(), new ReportWriter());
    }

    BatchCommand(
            BatchConfigLoader configLoader,
            BatchReportRetention reportRetention,
            ReportWriter reportWriter
    ) {
        this.configLoader = configLoader;
        this.reportRetention = reportRetention;
        this.reportWriter = reportWriter;
    }

    @Override
    public Integer call() {
        ResolvedBatchConfig config;
        try {
            config = configLoader.load(configPath);
        } catch (DmAdapterException e) {
            CliLogger.error("Batch configuration failed: " + e.getMessage());
            return BatchExitCodes.CONFIG_ERROR;
        }
        BatchSecretRedactor redactor = new BatchSecretRedactor(config.credentials());
        try {
            Files.createDirectories(config.workspaceDir());
            Files.createDirectories(config.reportDir());
            Path lockPath = config.workspaceDir().resolve("dm-adapter-batch.lock");
            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            )) {
                FileLock lock = tryLock(channel);
                if (lock == null) {
                    CliLogger.info("Another batch run is already using this workspace; this invocation was skipped.");
                    return BatchExitCodes.SUCCESS;
                }
                try (lock) {
                    return runLocked(config);
                }
            }
        } catch (Exception e) {
            CliLogger.error("Batch infrastructure failed: " + redactor.message(e));
            return BatchExitCodes.GIT_ERROR;
        }
    }

    private int runLocked(ResolvedBatchConfig config) throws Exception {
        reportRetention.clean(config.reportDir(), config.reportRetentionDays());
        String runId = RUN_ID_TIME.format(LocalDateTime.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
        Path runDir = config.reportDir().resolve(runId).toAbsolutePath().normalize();
        reportRetention.mark(runDir);
        CliLogger.info("Batch run started: " + runId);
        CliLogger.info("Repositories: " + config.repositories().size());
        CliLogger.info("Reports: " + runDir);

        JGitBatchRepositoryRunner runner = new JGitBatchRepositoryRunner(
                config.workspaceDir(),
                config.credentials(),
                config.gitIdentity(),
                reportWriter
        );
        List<BatchRepositoryExecution> executions = new ArrayList<>();
        for (ResolvedBatchConfig.Repository repository : config.repositories()) {
            CliLogger.info("[batch:" + repository.name() + "] processing branch " + repository.branch());
            BatchRepositoryExecution execution = runner.run(repository, runDir.resolve(repository.name()));
            executions.add(execution);
            CliLogger.info("[batch:" + repository.name() + "] "
                    + execution.report().status() + ": " + execution.report().message());
        }

        int exitCode = aggregateExitCode(executions);
        List<BatchRepositoryReport> reports = executions.stream().map(BatchRepositoryExecution::report).toList();
        int successCount = (int) reports.stream().filter(report -> "SUCCESS".equals(report.status())).count();
        int noChangesCount = (int) reports.stream().filter(report -> "NO_CHANGES".equals(report.status())).count();
        int failedCount = (int) reports.stream().filter(report -> "FAILED".equals(report.status())).count();
        BatchRunReport report = new BatchRunReport(
                1,
                runId,
                Instant.now().toString(),
                exitCode == 0 ? "SUCCESS" : "FAILED",
                exitCode,
                reports.size(),
                successCount,
                noChangesCount,
                failedCount,
                reports
        );
        ReportPaths paths = reportWriter.writeBatchRunReport(report, runDir);
        CliLogger.info("Batch completed with exit code " + exitCode + ".");
        CliLogger.info("Batch summary: " + paths.markdownPath());
        return exitCode;
    }

    private int aggregateExitCode(List<BatchRepositoryExecution> executions) {
        if (executions.stream().anyMatch(execution -> execution.exitCode() == BatchExitCodes.GIT_ERROR)) {
            return BatchExitCodes.GIT_ERROR;
        }
        if (executions.stream().anyMatch(execution -> execution.exitCode() == BatchExitCodes.MANUAL_REVIEW)) {
            return BatchExitCodes.MANUAL_REVIEW;
        }
        if (executions.stream().anyMatch(execution -> execution.exitCode() != BatchExitCodes.SUCCESS)) {
            return BatchExitCodes.INTERNAL_ERROR;
        }
        return BatchExitCodes.SUCCESS;
    }

    private FileLock tryLock(FileChannel channel) throws java.io.IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException ignored) {
            return null;
        }
    }
}
