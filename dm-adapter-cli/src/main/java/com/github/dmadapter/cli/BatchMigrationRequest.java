package com.github.dmadapter.cli;

import com.github.dmadapter.core.TargetLengthSemantics;

import java.nio.file.Path;
import java.util.List;

record BatchMigrationRequest(
        Path projectRoot,
        Path reportDir,
        String dmDriver,
        Path mapperDir,
        Path rewriteConfig,
        Path sqlRoot,
        Path sqlRootOut,
        List<Path> preservedSqlPaths,
        boolean sqlScriptsOnly,
        TargetLengthSemantics targetLengthSemantics
) {
    BatchMigrationRequest {
        preservedSqlPaths = List.copyOf(preservedSqlPaths == null ? List.of() : preservedSqlPaths);
    }
}
