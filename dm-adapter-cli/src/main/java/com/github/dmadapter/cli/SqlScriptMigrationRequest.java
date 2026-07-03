package com.github.dmadapter.cli;

import java.nio.file.Path;

record SqlScriptMigrationRequest(
        Path projectRoot,
        Path sqlRoot,
        Path sqlRootOut,
        boolean dryRun,
        String schema,
        String systemSchema,
        DmValidationEnvironment validationEnvironment
) {
}
