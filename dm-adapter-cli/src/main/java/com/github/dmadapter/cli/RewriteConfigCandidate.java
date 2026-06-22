package com.github.dmadapter.cli;

import java.util.List;

record RewriteConfigCandidate(
        String methodKey,
        String tableName,
        List<String> insertColumns
) {
    RewriteConfigCandidate {
        methodKey = methodKey == null ? "" : methodKey.trim();
        tableName = tableName == null ? "" : tableName.trim();
        insertColumns = List.copyOf(insertColumns == null ? List.of() : insertColumns);
    }
}
