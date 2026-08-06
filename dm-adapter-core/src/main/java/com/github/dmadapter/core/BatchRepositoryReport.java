package com.github.dmadapter.core;

import java.util.List;

public record BatchRepositoryReport(
        int schemaVersion,
        String generatedAt,
        String repository,
        String status,
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
    public BatchRepositoryReport {
        generatedAt = value(generatedAt);
        repository = value(repository);
        status = value(status);
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
