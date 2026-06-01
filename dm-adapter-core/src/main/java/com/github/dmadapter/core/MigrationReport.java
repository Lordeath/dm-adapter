package com.github.dmadapter.core;

import java.util.List;

public record MigrationReport(
        String projectRoot,
        String sourceDb,
        String targetDb,
        boolean dryRun,
        ProjectScanResult scanResult,
        List<FileChange> changedFiles,
        List<SqlChange> autoConvertedSqlItems,
        List<SqlChange> manualReviewSqlItems,
        List<String> riskWarnings
) {
    public MigrationReport {
        changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
        autoConvertedSqlItems = List.copyOf(autoConvertedSqlItems == null ? List.of() : autoConvertedSqlItems);
        manualReviewSqlItems = List.copyOf(manualReviewSqlItems == null ? List.of() : manualReviewSqlItems);
        riskWarnings = List.copyOf(riskWarnings == null ? List.of() : riskWarnings);
    }
}
