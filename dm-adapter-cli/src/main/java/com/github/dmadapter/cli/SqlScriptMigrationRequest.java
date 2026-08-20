package com.github.dmadapter.cli;

import com.github.dmadapter.core.DamengTargetCapabilities;

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
        DmValidationEnvironment validationEnvironment,
        DamengTargetCapabilities targetCapabilities,
        Path validationPlan
) {
    SqlScriptMigrationRequest {
        preservedSqlPaths = List.copyOf(preservedSqlPaths == null ? List.of() : preservedSqlPaths);
        targetCapabilities = targetCapabilities == null
                ? DamengTargetCapabilities.unknown()
                : targetCapabilities;
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
        this(
                projectRoot,
                sqlRoot,
                sqlRootOut,
                dryRun,
                schema,
                systemSchema,
                List.of(),
                validationEnvironment,
                DamengTargetCapabilities.unknown(),
                null
        );
    }

    SqlScriptMigrationRequest(
            Path projectRoot,
            Path sqlRoot,
            Path sqlRootOut,
            boolean dryRun,
            String schema,
            String systemSchema,
            List<Path> preservedSqlPaths,
            DmValidationEnvironment validationEnvironment
    ) {
        this(
                projectRoot,
                sqlRoot,
                sqlRootOut,
                dryRun,
                schema,
                systemSchema,
                preservedSqlPaths,
                validationEnvironment,
                DamengTargetCapabilities.unknown(),
                null
        );
    }
}
