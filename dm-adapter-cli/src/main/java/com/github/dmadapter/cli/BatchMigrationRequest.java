package com.github.dmadapter.cli;

import com.github.dmadapter.core.TargetLengthSemantics;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        TargetLengthSemantics targetLengthSemantics,
        Map<String, List<String>> tableKeyColumns
) {
    BatchMigrationRequest {
        preservedSqlPaths = List.copyOf(preservedSqlPaths == null ? List.of() : preservedSqlPaths);
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
        if (tableKeyColumns != null) {
            tableKeyColumns.forEach((table, columns) -> copied.put(
                    table,
                    List.copyOf(columns == null ? List.of() : columns)
            ));
        }
        tableKeyColumns = Collections.unmodifiableMap(copied);
    }
}
