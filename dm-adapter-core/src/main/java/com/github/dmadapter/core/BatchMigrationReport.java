package com.github.dmadapter.core;

import java.util.List;

public record BatchMigrationReport(
        int schemaVersion,
        String generatedAt,
        String status,
        String projectRoot,
        String remote,
        String branch,
        String baseCommit,
        String pushedCommit,
        int attempts,
        List<String> changedFiles,
        String failureStage,
        String message,
        String migrationReport,
        String sqlScriptReport
) {
    public BatchMigrationReport {
        generatedAt = value(generatedAt);
        status = value(status);
        projectRoot = value(projectRoot);
        remote = value(remote);
        branch = value(branch);
        baseCommit = value(baseCommit);
        pushedCommit = value(pushedCommit);
        changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
        failureStage = value(failureStage);
        message = value(message);
        migrationReport = value(migrationReport);
        sqlScriptReport = value(sqlScriptReport);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
