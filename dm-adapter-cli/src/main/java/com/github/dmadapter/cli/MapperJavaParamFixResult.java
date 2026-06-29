package com.github.dmadapter.cli;

import com.github.dmadapter.core.FileChange;

import java.util.List;

record MapperJavaParamFixResult(List<FileChange> fileChanges, List<String> warnings) {
    MapperJavaParamFixResult {
        fileChanges = List.copyOf(fileChanges == null ? List.of() : fileChanges);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    static MapperJavaParamFixResult empty() {
        return new MapperJavaParamFixResult(List.of(), List.of());
    }
}
