package com.github.dmadapter.cli;

import com.github.dmadapter.core.FileChange;

import java.util.List;

record MapperJdbcTypeAlignmentResult(
        List<FileChange> fileChanges,
        List<String> warnings
) {
    MapperJdbcTypeAlignmentResult {
        fileChanges = List.copyOf(fileChanges == null ? List.of() : fileChanges);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    static MapperJdbcTypeAlignmentResult empty() {
        return new MapperJdbcTypeAlignmentResult(List.of(), List.of());
    }
}
