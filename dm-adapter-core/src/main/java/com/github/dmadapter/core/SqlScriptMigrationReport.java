package com.github.dmadapter.core;

import java.util.List;

public record SqlScriptMigrationReport(
        String projectRoot,
        String sqlRoot,
        String sqlRootOut,
        boolean dryRun,
        int scannedFileCount,
        int convertedFileCount,
        int manualReviewSqlCount,
        boolean validationAttempted,
        String validationStatus,
        int validationSuccessCount,
        int validationFailureCount,
        List<SqlScriptFileResult> files,
        List<SqlScriptManualReviewItem> manualReviewItems,
        List<SqlScriptValidationFailure> validationFailures,
        List<String> warnings
) {
    public SqlScriptMigrationReport {
        files = List.copyOf(files == null ? List.of() : files);
        manualReviewItems = List.copyOf(manualReviewItems == null ? List.of() : manualReviewItems);
        validationFailures = List.copyOf(validationFailures == null ? List.of() : validationFailures);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
