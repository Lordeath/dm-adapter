package com.github.dmadapter.core;

import java.util.List;

public record MapperMigrationResult(
        List<FileChange> fileChanges,
        List<SqlChange> automaticConversions,
        List<SqlChange> manualReviewItems,
        List<String> warnings
) {
    public MapperMigrationResult {
        fileChanges = List.copyOf(fileChanges == null ? List.of() : fileChanges);
        automaticConversions = List.copyOf(automaticConversions == null ? List.of() : automaticConversions);
        manualReviewItems = List.copyOf(manualReviewItems == null ? List.of() : manualReviewItems);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
