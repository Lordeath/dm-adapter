package com.github.dmadapter.cli;

import com.github.dmadapter.core.BatchMigrationReport;
import com.github.dmadapter.core.DmAdapterException;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.report.ReportWriter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class GitBatchRunner {
    static final int EXIT_ARGUMENT_ERROR = 2;
    static final int EXIT_MANUAL_REVIEW = 3;
    static final int EXIT_GIT_FAILURE = 5;
    private static final int MAX_ATTEMPTS = 2;

    private final GitCommandRunner git;
    private final ReportWriter reportWriter;

    GitBatchRunner(GitCommandRunner git, ReportWriter reportWriter) {
        this.git = git;
        this.reportWriter = reportWriter;
    }

    int run(MigrateCommand command) {
        Path projectRoot = null;
        Path reportDir = null;
        RemoteBranch target = RemoteBranch.empty();
        try {
            command.validateBatchOptions();
            projectRoot = command.batchProjectRoot();
            if (!Files.isDirectory(projectRoot)) {
                throw new DmAdapterException("Batch project directory does not exist: " + projectRoot);
            }

            Path repositoryRoot = Path.of(git.requireSuccess(
                    projectRoot,
                    "repository-discovery",
                    "rev-parse",
                    "--show-toplevel"
            ).output()).toAbsolutePath().normalize();
            if (!projectRoot.startsWith(repositoryRoot)) {
                throw new GitBatchException(
                        "repository-discovery",
                        "Project is not inside the discovered Git repository: " + projectRoot
                );
            }
            Path gitCommonDirectory = resolveGitPath(
                    repositoryRoot,
                    git.requireSuccess(
                            repositoryRoot,
                            "repository-discovery",
                            "rev-parse",
                            "--git-common-dir"
                    ).output()
            );
            reportDir = command.batchReportDirectory(repositoryRoot, gitCommonDirectory);
            Files.createDirectories(reportDir);
            target = resolveTarget(command, repositoryRoot);
            validateTarget(repositoryRoot, target);

            Path lockDirectory = gitCommonDirectory.resolve("dm-adapter-batch-locks");
            Files.createDirectories(lockDirectory);
            String projectRelativePath = repositoryRoot.relativize(projectRoot).toString();
            Path lockPath = lockDirectory.resolve(batchLockId(
                    target.remote(),
                    target.branch(),
                    projectRelativePath
            ) + ".lock");
            try (FileChannel channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            )) {
                FileLock lock = tryLock(channel);
                if (lock == null) {
                    writeReport(
                            reportDir,
                            projectRoot,
                            target,
                            "",
                            "",
                            0,
                            List.of(),
                            "SKIPPED_LOCKED",
                            "lock",
                            "同一项目和远端分支已有 batch 任务运行，本次安全跳过。",
                            null
                    );
                    CliLogger.info("Batch skipped because another run holds the repository lock: " + lockPath);
                    return 0;
                }
                try (lock) {
                    return runLocked(command, repositoryRoot, projectRoot, reportDir, target);
                }
            }
        } catch (DmAdapterException e) {
            writeFailureIfPossible(
                    reportDir,
                    projectRoot,
                    target,
                    "argument-validation",
                    e.getMessage(),
                    EXIT_ARGUMENT_ERROR
            );
            return EXIT_ARGUMENT_ERROR;
        } catch (GitBatchException e) {
            writeFailureIfPossible(
                    reportDir,
                    projectRoot,
                    target,
                    e.stage(),
                    e.getMessage(),
                    EXIT_GIT_FAILURE
            );
            return EXIT_GIT_FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeFailureIfPossible(
                    reportDir,
                    projectRoot,
                    target,
                    "git-interrupted",
                    "Batch Git operation was interrupted.",
                    EXIT_GIT_FAILURE
            );
            return EXIT_GIT_FAILURE;
        } catch (Exception e) {
            writeFailureIfPossible(
                    reportDir,
                    projectRoot,
                    target,
                    "batch-infrastructure",
                    safeMessage(e),
                    EXIT_GIT_FAILURE
            );
            return EXIT_GIT_FAILURE;
        }
    }

    private int runLocked(
            MigrateCommand command,
            Path repositoryRoot,
            Path projectRoot,
            Path reportDir,
            RemoteBranch target
    ) throws IOException, InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            CliLogger.info("Batch attempt " + attempt + "/" + MAX_ATTEMPTS
                    + ": fetching " + target.remote() + "/" + target.branch() + "...");
            fetch(repositoryRoot, target);
            String baseCommit = remoteCommit(repositoryRoot, target);
            Path temporaryDirectory = Files.createTempDirectory("dm-adapter-batch-");
            Path worktreeRoot = temporaryDirectory.resolve("worktree");
            boolean worktreeAdded = false;
            OfflineMigrationRun offlineRun = null;
            List<String> changedFiles = List.of();
            try {
                git.requireSuccess(
                        repositoryRoot,
                        "worktree-create",
                        "worktree",
                        "add",
                        "--detach",
                        worktreeRoot.toString(),
                        baseCommit
                );
                worktreeAdded = true;
                offlineRun = command.runOfflineInWorktree(repositoryRoot, worktreeRoot, reportDir);
                boolean manualReview = hasManualReview(offlineRun);
                if (manualReview) {
                    writeReport(
                            reportDir,
                            projectRoot,
                            target,
                            baseCommit,
                            "",
                            attempt,
                            List.of(),
                            "FAILED",
                            "manual-review",
                            "转换报告包含需人工确认项，batch 已停止且未提交。",
                            offlineRun
                    );
                    CliLogger.error("Batch stopped because migration produced manual-review items.");
                    return EXIT_MANUAL_REVIEW;
                }
                if (offlineRun.exitCode() != 0) {
                    writeReport(
                            reportDir,
                            projectRoot,
                            target,
                            baseCommit,
                            "",
                            attempt,
                            List.of(),
                            "FAILED",
                            "migration",
                            "离线迁移失败，退出码=" + offlineRun.exitCode() + "，未提交。",
                            offlineRun
                    );
                    return 1;
                }

                GitCommandRunner.GitResult status = git.requireSuccess(
                        worktreeRoot,
                        "change-detection",
                        "status",
                        "--porcelain=v1",
                        "--untracked-files=all"
                );
                if (status.output().isBlank()) {
                    writeReport(
                            reportDir,
                            projectRoot,
                            target,
                            baseCommit,
                            "",
                            attempt,
                            List.of(),
                            "NO_CHANGES",
                            "",
                            "远端最新代码无需新增达梦适配变更。",
                            offlineRun
                    );
                    CliLogger.info("Batch completed without changes; no commit was created.");
                    return 0;
                }

                git.requireSuccess(worktreeRoot, "stage", "add", "--all");
                changedFiles = changedFiles(worktreeRoot);
                fetch(repositoryRoot, target);
                String latestCommit = remoteCommit(repositoryRoot, target);
                if (!baseCommit.equals(latestCommit)) {
                    if (attempt < MAX_ATTEMPTS) {
                        CliLogger.info("Remote branch moved during conversion; discarding the temporary worktree "
                                + "and retrying from " + latestCommit + ".");
                        continue;
                    }
                    writeReport(
                            reportDir,
                            projectRoot,
                            target,
                            baseCommit,
                            "",
                            attempt,
                            changedFiles,
                            "FAILED",
                            "remote-race",
                            "两次转换期间远端分支均发生更新，未提交。",
                            offlineRun
                    );
                    return EXIT_GIT_FAILURE;
                }

                git.requireSuccess(
                        worktreeRoot,
                        "commit",
                        "commit",
                        "--message",
                        command.batchGitCommitMessage()
                );
                String pushedCommit = git.requireSuccess(
                        worktreeRoot,
                        "commit",
                        "rev-parse",
                        "HEAD"
                ).output();
                GitCommandRunner.GitResult push = git.run(
                        worktreeRoot,
                        "push",
                        "--porcelain",
                        target.remote(),
                        "HEAD:refs/heads/" + target.branch()
                );
                if (push.success() || remotePointsAt(repositoryRoot, target, pushedCommit)) {
                    writeReport(
                            reportDir,
                            projectRoot,
                            target,
                            baseCommit,
                            pushedCommit,
                            attempt,
                            changedFiles,
                            "SUCCESS",
                            "",
                            "转换变更已提交并推送。",
                            offlineRun
                    );
                    CliLogger.info("Batch pushed commit " + pushedCommit + " to "
                            + target.remote() + "/" + target.branch() + ".");
                    return 0;
                }

                String remoteAfterFailure = remoteHead(repositoryRoot, target);
                if (attempt < MAX_ATTEMPTS
                        && !remoteAfterFailure.isBlank()
                        && !baseCommit.equals(remoteAfterFailure)) {
                    CliLogger.info("Push was rejected after the remote branch moved; retrying from the new head.");
                    continue;
                }
                writeReport(
                        reportDir,
                        projectRoot,
                        target,
                        baseCommit,
                        "",
                        attempt,
                        changedFiles,
                        "FAILED",
                        "push",
                        "Git push 失败且远端未指向本次提交：" + compact(push.output()),
                        offlineRun
                );
                return EXIT_GIT_FAILURE;
            } finally {
                cleanupWorktree(repositoryRoot, worktreeRoot, temporaryDirectory, worktreeAdded);
            }
        }
        throw new GitBatchException("remote-race", "Batch attempts were exhausted.");
    }

    private RemoteBranch resolveTarget(MigrateCommand command, Path repositoryRoot)
            throws IOException, InterruptedException {
        if (!command.batchGitRemote().isBlank()) {
            return new RemoteBranch(command.batchGitRemote(), command.batchGitBranch());
        }
        String localBranch = git.requireSuccess(
                repositoryRoot,
                "upstream-discovery",
                "symbolic-ref",
                "--quiet",
                "--short",
                "HEAD"
        ).output();
        GitCommandRunner.GitResult remoteResult = git.run(
                repositoryRoot,
                "config",
                "--get",
                "branch." + localBranch + ".remote"
        );
        GitCommandRunner.GitResult mergeResult = git.run(
                repositoryRoot,
                "config",
                "--get",
                "branch." + localBranch + ".merge"
        );
        if (!remoteResult.success() || !mergeResult.success()) {
            throw new DmAdapterException(
                    "Current branch has no upstream; pass --git-remote and --git-branch explicitly."
            );
        }
        String merge = mergeResult.output();
        String prefix = "refs/heads/";
        if (!merge.startsWith(prefix) || ".".equals(remoteResult.output())) {
            throw new DmAdapterException(
                    "Current branch upstream is not a remote branch; pass --git-remote and --git-branch explicitly."
            );
        }
        return new RemoteBranch(remoteResult.output(), merge.substring(prefix.length()));
    }

    private void validateTarget(Path repositoryRoot, RemoteBranch target)
            throws IOException, InterruptedException {
        if (!target.remote().matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) {
            throw new DmAdapterException("Unsafe or unsupported Git remote name: " + target.remote());
        }
        git.requireSuccess(
                repositoryRoot,
                "target-validation",
                "remote",
                "get-url",
                target.remote()
        );
        GitCommandRunner.GitResult branchCheck = git.run(
                repositoryRoot,
                "check-ref-format",
                "refs/heads/" + target.branch()
        );
        if (!branchCheck.success()) {
            throw new DmAdapterException("Invalid Git branch name: " + target.branch());
        }
    }

    private void fetch(Path repositoryRoot, RemoteBranch target) throws IOException, InterruptedException {
        git.requireSuccess(
                repositoryRoot,
                "fetch",
                "fetch",
                "--no-tags",
                "--prune",
                target.remote(),
                "+refs/heads/" + target.branch() + ":" + remoteTrackingRef(target)
        );
    }

    private String remoteCommit(Path repositoryRoot, RemoteBranch target)
            throws IOException, InterruptedException {
        return git.requireSuccess(
                repositoryRoot,
                "fetch",
                "rev-parse",
                remoteTrackingRef(target)
        ).output();
    }

    private boolean remotePointsAt(Path repositoryRoot, RemoteBranch target, String expectedCommit)
            throws IOException, InterruptedException {
        return expectedCommit.equals(remoteHead(repositoryRoot, target));
    }

    private String remoteHead(Path repositoryRoot, RemoteBranch target)
            throws IOException, InterruptedException {
        GitCommandRunner.GitResult result = git.run(
                repositoryRoot,
                "ls-remote",
                "--heads",
                target.remote(),
                "refs/heads/" + target.branch()
        );
        if (!result.success() || result.output().isBlank()) {
            return "";
        }
        int separator = result.output().indexOf('\t');
        return separator < 0 ? "" : result.output().substring(0, separator).trim();
    }

    private List<String> changedFiles(Path worktreeRoot) throws IOException, InterruptedException {
        String output = git.requireSuccess(
                worktreeRoot,
                "change-detection",
                "-c",
                "core.quotepath=false",
                "diff",
                "--cached",
                "--name-only",
                "-z"
        ).output();
        if (output.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String path : output.split("\\u0000")) {
            if (!path.isEmpty()) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    private boolean hasManualReview(OfflineMigrationRun run) {
        if (run == null) {
            return false;
        }
        MigrationReport migration = run.migrationReport();
        SqlScriptMigrationReport scripts = run.sqlScriptMigrationReport();
        return (migration != null && !migration.manualReviewSqlItems().isEmpty())
                || (scripts != null && scripts.manualReviewSqlCount() > 0);
    }

    private FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException ignored) {
            return null;
        }
    }

    private void cleanupWorktree(
            Path repositoryRoot,
            Path worktreeRoot,
            Path temporaryDirectory,
            boolean worktreeAdded
    ) {
        if (worktreeAdded) {
            try {
                GitCommandRunner.GitResult result = git.run(
                        repositoryRoot,
                        "worktree",
                        "remove",
                        "--force",
                        worktreeRoot.toString()
                );
                if (!result.success()) {
                    CliLogger.error("Could not remove temporary Git worktree: " + compact(result.output()));
                }
            } catch (Exception e) {
                CliLogger.error("Could not remove temporary Git worktree: " + safeMessage(e));
            }
        }
        try {
            if (Files.exists(temporaryDirectory)) {
                try (var paths = Files.walk(temporaryDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new TemporaryCleanupException(e);
                        }
                    });
                }
            }
        } catch (TemporaryCleanupException | IOException e) {
            CliLogger.error("Could not delete batch temporary directory: " + temporaryDirectory);
        }
    }

    private void writeFailureIfPossible(
            Path reportDir,
            Path projectRoot,
            RemoteBranch target,
            String stage,
            String message,
            int exitCode
    ) {
        CliLogger.error("Batch failed at " + stage + " (exit " + exitCode + "): " + message);
        if (reportDir == null || projectRoot == null) {
            return;
        }
        try {
            writeReport(
                    reportDir,
                    projectRoot,
                    target,
                    "",
                    "",
                    0,
                    List.of(),
                    "FAILED",
                    stage,
                    message,
                    null
            );
        } catch (Exception reportFailure) {
            CliLogger.error("Could not write batch failure report: " + safeMessage(reportFailure));
        }
    }

    private void writeReport(
            Path reportDir,
            Path projectRoot,
            RemoteBranch target,
            String baseCommit,
            String pushedCommit,
            int attempts,
            List<String> changedFiles,
            String status,
            String failureStage,
            String message,
            OfflineMigrationRun offlineRun
    ) throws IOException {
        String migrationReport = offlineRun != null && offlineRun.migrationReport() != null
                ? ReportWriter.MIGRATION_REPORT_MARKDOWN
                : "";
        String sqlScriptReport = offlineRun != null && offlineRun.sqlScriptMigrationReport() != null
                ? ReportWriter.SQL_SCRIPT_REPORT_MARKDOWN
                : "";
        reportWriter.writeBatchMigrationReport(new BatchMigrationReport(
                1,
                java.time.Instant.now().toString(),
                status,
                projectRoot.toString(),
                target.remote(),
                target.branch(),
                baseCommit,
                pushedCommit,
                attempts,
                changedFiles,
                failureStage,
                message,
                migrationReport,
                sqlScriptReport
        ), reportDir);
    }

    private Path resolveGitPath(Path repositoryRoot, String path) {
        Path parsed = Path.of(path);
        return (parsed.isAbsolute() ? parsed : repositoryRoot.resolve(parsed)).toAbsolutePath().normalize();
    }

    static String batchLockId(String remote, String branch, String projectRelativePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest((remote + "\n" + branch + "\n" + projectRelativePath)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private String remoteTrackingRef(RemoteBranch target) {
        return "refs/remotes/" + target.remote() + "/" + target.branch();
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "(no output)";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 2_000 ? compact : compact.substring(compact.length() - 2_000);
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().isBlank()) {
            return failure == null ? "Unknown failure." : failure.getClass().getSimpleName();
        }
        return compact(failure.getMessage());
    }

    private record RemoteBranch(String remote, String branch) {
        private RemoteBranch {
            remote = remote == null ? "" : remote;
            branch = branch == null ? "" : branch;
        }

        private static RemoteBranch empty() {
            return new RemoteBranch("", "");
        }
    }

    private static final class TemporaryCleanupException extends RuntimeException {
        private TemporaryCleanupException(IOException cause) {
            super(cause);
        }
    }
}
