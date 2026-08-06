package com.github.dmadapter.cli;

import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.SqlScriptMigrationReport;

record OfflineMigrationRun(
        int exitCode,
        MigrationReport migrationReport,
        SqlScriptMigrationReport sqlScriptMigrationReport
) {
    boolean hasManualReview() {
        return (migrationReport != null && !migrationReport.manualReviewSqlItems().isEmpty())
                || (sqlScriptMigrationReport != null && sqlScriptMigrationReport.manualReviewSqlCount() > 0);
    }

    boolean containsUseStatement() {
        return sqlScriptMigrationReport != null && sqlScriptMigrationReport.manualReviewItems().stream()
                .anyMatch(item -> item.originalSql() != null
                        && item.originalSql().stripLeading().matches("(?is)^USE\\s+.*"));
    }
}
