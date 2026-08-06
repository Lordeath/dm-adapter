package com.github.dmadapter.core;

import java.util.List;

public record BatchRunReport(
        int schemaVersion,
        String runId,
        String generatedAt,
        String status,
        int exitCode,
        int repositoryCount,
        int successCount,
        int noChangesCount,
        int failedCount,
        List<BatchRepositoryReport> repositories
) {
    public BatchRunReport {
        runId = value(runId);
        generatedAt = value(generatedAt);
        status = value(status);
        repositories = List.copyOf(repositories == null ? List.of() : repositories);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
