package com.github.dmadapter.cli;

import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.SqlScriptMigrationReport;

record OfflineMigrationRun(
        int exitCode,
        MigrationReport migrationReport,
        SqlScriptMigrationReport sqlScriptMigrationReport
) {
}
