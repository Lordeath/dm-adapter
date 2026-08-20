package com.github.dmadapter.cli;

import com.github.dmadapter.core.BatchRepositoryReport;
import com.github.dmadapter.report.ReportWriter;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

final class JGitBatchRepositoryRunner {
    private static final String REMOTE = "origin";
    private static final String CACHE_MARKER_VERSION = "dm-adapter-cache-v1";
    private static final int TRANSPORT_TIMEOUT_SECONDS = 300;
    private static final int MAX_ATTEMPTS = 2;

    private final Path cacheRoot;
    private final Path markerRoot;
    private final ResolvedBatchConfig.GitIdentity identity;
    private final CredentialsProvider credentialsProvider;
    private final BatchSecretRedactor redactor;
    private final ReportWriter reportWriter;
    private final RemoteRecheckHook remoteRecheckHook;

    JGitBatchRepositoryRunner(
            Path workspaceDir,
            ResolvedBatchConfig.Credentials credentials,
            ResolvedBatchConfig.GitIdentity identity,
            ReportWriter reportWriter
    ) {
        this(workspaceDir, credentials, identity, reportWriter, (repository, attempt) -> {
        });
    }

    JGitBatchRepositoryRunner(
            Path workspaceDir,
            ResolvedBatchConfig.Credentials credentials,
            ResolvedBatchConfig.GitIdentity identity,
            ReportWriter reportWriter,
            RemoteRecheckHook remoteRecheckHook
    ) {
        this.cacheRoot = workspaceDir.resolve("repositories").toAbsolutePath().normalize();
        this.markerRoot = workspaceDir.resolve("cache-markers").toAbsolutePath().normalize();
        this.identity = identity;
        this.credentialsProvider = credentials.configured()
                ? new UsernamePasswordCredentialsProvider(credentials.username(), credentials.password())
                : null;
        this.redactor = new BatchSecretRedactor(credentials);
        this.reportWriter = reportWriter;
        this.remoteRecheckHook = remoteRecheckHook;
    }

    BatchRepositoryExecution run(ResolvedBatchConfig.Repository configured, Path reportDir) {
        String baseCommit = "";
        String pushedCommit = "";
        int attempts = 0;
        List<String> changedFiles = List.of();
        try {
            Files.createDirectories(cacheRoot);
            Files.createDirectories(markerRoot);
            Path cacheDir = ensureCache(configured);
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                attempts = attempt;
                CliLogger.info("[batch:" + configured.name() + "] attempt " + attempt + "/" + MAX_ATTEMPTS);
                try (Git git = Git.open(cacheDir.toFile())) {
                    ObjectId base = prepareLatestBranch(git, configured);
                    baseCommit = base.name();
                    OfflineMigrationRun migration = migrate(configured, cacheDir, reportDir);
                    if (migration.hasManualReview()) {
                        resetAndClean(git, remoteRef(configured));
                        String message = migration.containsUseStatement()
                                ? "Source SQL contains USE <database>; remove it from the MySQL source script before batch conversion."
                                : "Migration contains manual-review items; no commit was created.";
                        return execution(
                                configured,
                                reportDir,
                                BatchExitCodes.MANUAL_REVIEW,
                                "FAILED",
                                baseCommit,
                                "",
                                attempts,
                                List.of(),
                                migration.containsUseStatement() ? "source-use-statement" : "manual-review",
                                message,
                                migration
                        );
                    }
                    if (migration.exitCode() != 0) {
                        resetAndClean(git, remoteRef(configured));
                        return execution(
                                configured,
                                reportDir,
                                BatchExitCodes.INTERNAL_ERROR,
                                "FAILED",
                                baseCommit,
                                "",
                                attempts,
                                List.of(),
                                "migration",
                                "Offline migration failed with exit code " + migration.exitCode() + ".",
                                migration
                        );
                    }
                    if (git.status().call().isClean()) {
                        return execution(
                                configured,
                                reportDir,
                                BatchExitCodes.SUCCESS,
                                "NO_CHANGES",
                                baseCommit,
                                "",
                                attempts,
                                List.of(),
                                "",
                                "Remote head requires no additional Dameng adaptation changes.",
                                migration
                        );
                    }

                    git.add().addFilepattern(".").setAll(true).call();
                    changedFiles = changedFiles(git);
                    remoteRecheckHook.beforeRemoteRecheck(configured, attempt);
                    fetch(git, configured);
                    ObjectId latest = requireRemoteCommit(git.getRepository(), configured);
                    if (!base.equals(latest)) {
                        if (attempt < MAX_ATTEMPTS) {
                            CliLogger.info("[batch:" + configured.name()
                                    + "] remote moved during conversion; retrying from " + latest.name());
                            continue;
                        }
                        resetAndClean(git, remoteRef(configured));
                        return execution(
                                configured,
                                reportDir,
                                BatchExitCodes.GIT_ERROR,
                                "FAILED",
                                baseCommit,
                                "",
                                attempts,
                                changedFiles,
                                "remote-race",
                                "Remote branch moved during both conversion attempts; no commit was pushed.",
                                migration
                        );
                    }

                    PersonIdent person = new PersonIdent(identity.authorName(), identity.authorEmail());
                    pushedCommit = git.commit()
                            .setMessage(identity.commitMessage())
                            .setAuthor(person)
                            .setCommitter(person)
                            .call()
                            .getId()
                            .name();
                    PushOutcome push = push(git, configured, pushedCommit);
                    if (push.success()) {
                        return execution(
                                configured,
                                reportDir,
                                BatchExitCodes.SUCCESS,
                                "SUCCESS",
                                baseCommit,
                                pushedCommit,
                                attempts,
                                changedFiles,
                                "",
                                "Conversion changes were committed and pushed.",
                                migration
                        );
                    }
                    String remoteHead = remoteHead(configured);
                    if (pushedCommit.equals(remoteHead)) {
                        return execution(
                                configured,
                                reportDir,
                                BatchExitCodes.SUCCESS,
                                "SUCCESS",
                                baseCommit,
                                pushedCommit,
                                attempts,
                                changedFiles,
                                "",
                                "Push result was uncertain, but the remote branch points at the created commit.",
                                migration
                        );
                    }
                    if (attempt < MAX_ATTEMPTS && !remoteHead.isBlank() && !baseCommit.equals(remoteHead)) {
                        CliLogger.info("[batch:" + configured.name()
                                + "] push raced with a remote update; retrying from " + remoteHead);
                        continue;
                    }
                    resetAndClean(git, remoteRef(configured));
                    return execution(
                            configured,
                            reportDir,
                            BatchExitCodes.GIT_ERROR,
                            "FAILED",
                            baseCommit,
                            "",
                            attempts,
                            changedFiles,
                            "push",
                            "JGit push failed: " + redactor.redact(push.message()),
                            migration
                    );
                }
            }
            throw new BatchRepositoryFailure(
                    BatchExitCodes.GIT_ERROR,
                    "remote-race",
                    "Batch attempts were exhausted."
            );
        } catch (BatchRepositoryFailure failure) {
            cleanCacheQuietly(configured);
            return execution(
                    configured,
                    reportDir,
                    failure.exitCode(),
                    "FAILED",
                    baseCommit,
                    "",
                    attempts,
                    changedFiles,
                    failure.stage(),
                    redactor.message(failure),
                    null
            );
        } catch (Exception failure) {
            cleanCacheQuietly(configured);
            return execution(
                    configured,
                    reportDir,
                    BatchExitCodes.GIT_ERROR,
                    "FAILED",
                    baseCommit,
                    "",
                    attempts,
                    changedFiles,
                    "jgit",
                    redactor.message(failure),
                    null
            );
        }
    }

    private Path ensureCache(ResolvedBatchConfig.Repository configured) {
        Path cacheDir = cacheRoot.resolve(configured.name()).toAbsolutePath().normalize();
        Path marker = markerRoot.resolve(configured.name() + ".marker").toAbsolutePath().normalize();
        assertManagedPath(cacheDir, cacheRoot, configured.name());
        assertManagedPath(marker, markerRoot, configured.name());
        String expectedMarker = cacheMarker(configured);
        try {
            boolean cacheExists = Files.exists(cacheDir, LinkOption.NOFOLLOW_LINKS);
            String markerContent = Files.isRegularFile(marker)
                    ? Files.readString(marker, StandardCharsets.UTF_8)
                    : "";
            boolean markerOwned = markerContent.startsWith(CACHE_MARKER_VERSION + "\n");
            boolean markerMatches = expectedMarker.equals(markerContent);
            if (cacheExists && Files.isSymbolicLink(cacheDir)) {
                throw new BatchRepositoryFailure(
                        BatchExitCodes.GIT_ERROR,
                        "cache-safety",
                        "Managed cache path is a symbolic link and will not be modified: " + cacheDir
                );
            }
            if (cacheExists && !markerOwned) {
                throw new BatchRepositoryFailure(
                        BatchExitCodes.GIT_ERROR,
                        "cache-safety",
                        "Cache directory lacks the expected dm-adapter ownership marker: " + cacheDir
                );
            }
            if (cacheExists && !markerMatches) {
                deleteTree(cacheDir);
                cacheExists = false;
            }
            if (cacheExists) {
                try (Git git = Git.open(cacheDir.toFile())) {
                    String actualUrl = git.getRepository().getConfig().getString("remote", REMOTE, "url");
                    if (configured.url().equals(actualUrl)) {
                        return cacheDir;
                    }
                } catch (Exception ignored) {
                    // A marked, CLI-owned cache is safe to recreate below.
                }
                deleteTree(cacheDir);
            }
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, expectedMarker, StandardCharsets.UTF_8);
            try {
                var clone = Git.cloneRepository()
                        .setURI(configured.url())
                        .setDirectory(cacheDir.toFile())
                        .setRemote(REMOTE)
                        .setBranch("refs/heads/" + configured.branch())
                        .setBranchesToClone(List.of("refs/heads/" + configured.branch()))
                        .setNoTags()
                        .setTimeout(TRANSPORT_TIMEOUT_SECONDS);
                configure(clone);
                try (Git ignored = clone.call()) {
                    return cacheDir;
                }
            } catch (Exception e) {
                if (Files.exists(cacheDir, LinkOption.NOFOLLOW_LINKS)) {
                    deleteTree(cacheDir);
                }
                Files.deleteIfExists(marker);
                throw new BatchRepositoryFailure(
                        BatchExitCodes.GIT_ERROR,
                        "clone",
                        "Could not clone repository " + configured.name() + ": " + redactor.message(e),
                        e
                );
            }
        } catch (BatchRepositoryFailure e) {
            throw e;
        } catch (Exception e) {
            throw new BatchRepositoryFailure(
                    BatchExitCodes.GIT_ERROR,
                    "cache",
                    "Could not prepare managed cache for " + configured.name() + ": " + redactor.message(e),
                    e
            );
        }
    }

    private ObjectId prepareLatestBranch(Git git, ResolvedBatchConfig.Repository configured) throws Exception {
        fetch(git, configured);
        String remoteRef = remoteRef(configured);
        ObjectId remoteCommit = requireRemoteCommit(git.getRepository(), configured);
        Repository repository = git.getRepository();
        if (repository.resolve("HEAD") != null) {
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call();
            git.clean().setCleanDirectories(true).setForce(true).setIgnore(false).call();
        }
        Ref localBranch = repository.findRef("refs/heads/" + configured.branch());
        if (localBranch == null) {
            git.checkout()
                    .setCreateBranch(true)
                    .setName(configured.branch())
                    .setStartPoint(remoteRef)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                    .call();
        } else {
            git.checkout().setName(configured.branch()).setForced(true).call();
        }
        resetAndClean(git, remoteRef);
        return remoteCommit;
    }

    private void fetch(Git git, ResolvedBatchConfig.Repository configured) throws Exception {
        var fetch = git.fetch()
                .setRemote(REMOTE)
                .setRefSpecs(new RefSpec("+refs/heads/" + configured.branch() + ":" + remoteRef(configured)))
                .setRemoveDeletedRefs(true)
                .setTagOpt(TagOpt.NO_TAGS)
                .setTimeout(TRANSPORT_TIMEOUT_SECONDS);
        configure(fetch);
        fetch.call();
    }

    private ObjectId requireRemoteCommit(Repository repository, ResolvedBatchConfig.Repository configured)
            throws IOException {
        ObjectId commit = repository.resolve(remoteRef(configured) + "^{commit}");
        if (commit == null) {
            throw new BatchRepositoryFailure(
                    BatchExitCodes.GIT_ERROR,
                    "fetch",
                    "Remote branch does not exist: " + configured.branch()
            );
        }
        return commit;
    }

    private OfflineMigrationRun migrate(
            ResolvedBatchConfig.Repository configured,
            Path cacheDir,
            Path reportDir
    ) {
        ResolvedBatchConfig.Migration migration = configured.migration();
        Path projectRoot = inside(cacheDir, migrationPath(cacheDir, configured.projectSubdir()), "projectSubdir");
        if (!Files.isRegularFile(projectRoot.resolve("pom.xml"))) {
            throw new BatchRepositoryFailure(
                    BatchExitCodes.INTERNAL_ERROR,
                    "project",
                    "Maven pom.xml does not exist at configured projectSubdir: " + configured.projectSubdir()
            );
        }
        Path mapperDir = migration.mapperDir() == null
                ? null
                : inside(cacheDir, cacheDir.resolve(migration.mapperDir()).normalize(), "mapperDir");
        Path rewriteConfig = migration.rewriteConfig() == null
                ? null
                : inside(cacheDir, cacheDir.resolve(migration.rewriteConfig()).normalize(), "rewriteConfig");
        ResolvedBatchConfig.Sql sql = migration.sql();
        Path sqlRoot = null;
        Path sqlRootOut = null;
        if (sql.mode() != BatchSqlMode.DISABLED) {
            Path candidate = inside(cacheDir, cacheDir.resolve(sql.sourceDir()).normalize(), "sql.sourceDir");
            if (Files.isDirectory(candidate)) {
                sqlRoot = candidate;
                sqlRootOut = inside(cacheDir, cacheDir.resolve(sql.outputDir()).normalize(), "sql.outputDir");
            } else if (sql.mode() == BatchSqlMode.REQUIRED) {
                throw new BatchRepositoryFailure(
                        BatchExitCodes.INTERNAL_ERROR,
                        "sql-source",
                        "Required SQL source directory does not exist: " + sql.sourceDir()
                );
            }
        }
        if (migration.sqlScriptsOnly() && sqlRoot == null) {
            throw new BatchRepositoryFailure(
                    BatchExitCodes.INTERNAL_ERROR,
                    "sql-source",
                    "sqlScriptsOnly requires an available SQL source directory."
            );
        }
        return MigrateCommand.runOffline(new BatchMigrationRequest(
                projectRoot,
                reportDir,
                migration.dmDriver(),
                mapperDir,
                rewriteConfig,
                sqlRoot,
                sqlRootOut,
                sql.preserveSql(),
                migration.sqlScriptsOnly(),
                migration.tableKeyColumns(),
                migration.methodKeyColumns(),
                migration.methodConflictKeyGroups()
        ));
    }

    private Path migrationPath(Path cacheDir, Path configured) {
        return cacheDir.resolve(configured).normalize();
    }

    private Path inside(Path root, Path path, String field) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(normalizedRoot)) {
            throw new BatchRepositoryFailure(
                    BatchExitCodes.INTERNAL_ERROR,
                    "path-validation",
                    field + " escapes the managed repository."
            );
        }
        return normalized;
    }

    private List<String> changedFiles(Git git) throws Exception {
        List<String> paths = new ArrayList<>();
        for (DiffEntry entry : git.diff().setCached(true).setShowNameAndStatusOnly(true).call()) {
            String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                    ? entry.getOldPath()
                    : entry.getNewPath();
            if (path != null && !DiffEntry.DEV_NULL.equals(path) && !paths.contains(path)) {
                paths.add(path);
            }
        }
        paths.sort(String::compareTo);
        return List.copyOf(paths);
    }

    private PushOutcome push(Git git, ResolvedBatchConfig.Repository configured, String commit) {
        try {
            var push = git.push()
                    .setRemote(REMOTE)
                    .setRefSpecs(new RefSpec("HEAD:refs/heads/" + configured.branch()))
                    .setForce(false)
                    .setTimeout(TRANSPORT_TIMEOUT_SECONDS);
            configure(push);
            StringBuilder messages = new StringBuilder();
            boolean success = true;
            boolean targetSeen = false;
            for (var result : push.call()) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    if (!("refs/heads/" + configured.branch()).equals(update.getRemoteName())) {
                        continue;
                    }
                    targetSeen = true;
                    RemoteRefUpdate.Status status = update.getStatus();
                    success &= Set.of(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE).contains(status);
                    if (messages.length() > 0) {
                        messages.append("; ");
                    }
                    messages.append(status);
                    if (update.getMessage() != null && !update.getMessage().isBlank()) {
                        messages.append(": ").append(update.getMessage());
                    }
                }
            }
            return new PushOutcome(targetSeen && success, messages.toString());
        } catch (Exception e) {
            if (commit.equals(remoteHead(configured))) {
                return new PushOutcome(true, "Remote verification confirmed the pushed commit.");
            }
            return new PushOutcome(false, redactor.message(e));
        }
    }

    private String remoteHead(ResolvedBatchConfig.Repository configured) {
        try {
            var command = Git.lsRemoteRepository()
                    .setRemote(configured.url())
                    .setHeads(true)
                    .setTags(false)
                    .setTimeout(TRANSPORT_TIMEOUT_SECONDS);
            configure(command);
            Ref ref = command.callAsMap().get("refs/heads/" + configured.branch());
            return ref == null || ref.getObjectId() == null ? "" : ref.getObjectId().name();
        } catch (Exception e) {
            return "";
        }
    }

    private void resetAndClean(Git git, String ref) throws Exception {
        git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(ref).call();
        git.clean().setCleanDirectories(true).setForce(true).setIgnore(false).call();
    }

    private void cleanCacheQuietly(ResolvedBatchConfig.Repository configured) {
        Path cacheDir = cacheRoot.resolve(configured.name()).toAbsolutePath().normalize();
        Path marker = markerRoot.resolve(configured.name() + ".marker").toAbsolutePath().normalize();
        if (!Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(cacheDir)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (!cacheMarker(configured).equals(Files.readString(marker, StandardCharsets.UTF_8))) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        try (Git git = Git.open(cacheDir.toFile())) {
            ObjectId remote = git.getRepository().resolve(remoteRef(configured));
            if (remote != null) {
                resetAndClean(git, remoteRef(configured));
            }
        } catch (Exception e) {
            CliLogger.error("[batch:" + configured.name() + "] could not clean cache: " + redactor.message(e));
        }
    }

    private BatchRepositoryExecution execution(
            ResolvedBatchConfig.Repository configured,
            Path reportDir,
            int exitCode,
            String status,
            String baseCommit,
            String pushedCommit,
            int attempts,
            List<String> changedFiles,
            String failureStage,
            String message,
            OfflineMigrationRun migration
    ) {
        BatchRepositoryReport report = new BatchRepositoryReport(
                1,
                Instant.now().toString(),
                configured.name(),
                status,
                configured.branch(),
                baseCommit,
                pushedCommit,
                attempts,
                changedFiles,
                failureStage,
                redactor.redact(message),
                migration != null && migration.migrationReport() != null
                        ? ReportWriter.MIGRATION_REPORT_MARKDOWN
                        : "",
                migration != null && migration.sqlScriptMigrationReport() != null
                        ? ReportWriter.SQL_SCRIPT_REPORT_MARKDOWN
                        : ""
        );
        try {
            reportWriter.writeBatchRepositoryReport(report, reportDir);
        } catch (IOException e) {
            CliLogger.error("[batch:" + configured.name() + "] could not write report: " + redactor.message(e));
        }
        return new BatchRepositoryExecution(exitCode, report);
    }

    private <C extends TransportCommand<C, ?>> void configure(C command) {
        if (credentialsProvider != null) {
            command.setCredentialsProvider(credentialsProvider);
        }
    }

    private String remoteRef(ResolvedBatchConfig.Repository configured) {
        return "refs/remotes/" + REMOTE + "/" + configured.branch();
    }

    private String cacheMarker(ResolvedBatchConfig.Repository configured) {
        return CACHE_MARKER_VERSION + "\n" + sha256(configured.name() + "\n" + configured.url()) + "\n";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private void assertManagedPath(Path target, Path root, String name) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(normalizedRoot) || !normalized.startsWith(normalizedRoot)) {
            throw new BatchRepositoryFailure(
                    BatchExitCodes.GIT_ERROR,
                    "cache-safety",
                    "Unsafe managed cache path for repository " + name + "."
            );
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Refusing to delete symbolic-link cache: " + root);
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record PushOutcome(boolean success, String message) {
    }

    @FunctionalInterface
    interface RemoteRecheckHook {
        void beforeRemoteRecheck(ResolvedBatchConfig.Repository repository, int attempt) throws Exception;
    }
}
