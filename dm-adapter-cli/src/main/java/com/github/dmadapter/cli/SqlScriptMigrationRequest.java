package com.github.dmadapter.cli;

import java.nio.file.Path;
import java.util.List;

record SqlScriptMigrationRequest(
        Path projectRoot,
        Path sqlRoot,
        Path sqlRootOut,
        boolean dryRun,
        String schema,
        String systemSchema,
        List<Path> preservedSqlPaths,
        DmValidationEnvironment validationEnvironment
) {
    SqlScriptMigrationRequest {
        preservedSqlPaths = List.copyOf(preservedSqlPaths == null ? List.of() : preservedSqlPaths);
    }

    SqlScriptMigrationRequest(
            Path projectRoot,
            Path sqlRoot,
            Path sqlRootOut,
            boolean dryRun,
            String schema,
            String systemSchema,
            DmValidationEnvironment validationEnvironment
    ) {
        this(projectRoot, sqlRoot, sqlRootOut, dryRun, schema, systemSchema, List.of(), validationEnvironment);
    }
}
