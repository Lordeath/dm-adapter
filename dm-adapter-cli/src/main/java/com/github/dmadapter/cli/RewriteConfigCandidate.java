package com.github.dmadapter.cli;

import java.util.List;

record RewriteConfigCandidate(
        String methodKey,
        String tableName,
        List<String> insertColumns,
        RewriteKind rewriteKind
) {
    RewriteConfigCandidate(String methodKey, String tableName, List<String> insertColumns) {
        this(methodKey, tableName, insertColumns, RewriteKind.ON_DUPLICATE_KEY_UPDATE);
    }

    RewriteConfigCandidate {
        methodKey = methodKey == null ? "" : methodKey.trim();
        tableName = tableName == null ? "" : tableName.trim();
        insertColumns = List.copyOf(insertColumns == null ? List.of() : insertColumns);
        rewriteKind = rewriteKind == null ? RewriteKind.ON_DUPLICATE_KEY_UPDATE : rewriteKind;
    }

    boolean insertIgnore() {
        return rewriteKind == RewriteKind.INSERT_IGNORE;
    }

    boolean outerJoinSource() {
        return rewriteKind == RewriteKind.OUTER_JOIN_SOURCE;
    }

    enum RewriteKind {
        ON_DUPLICATE_KEY_UPDATE,
        INSERT_IGNORE,
        OUTER_JOIN_SOURCE
    }
}
