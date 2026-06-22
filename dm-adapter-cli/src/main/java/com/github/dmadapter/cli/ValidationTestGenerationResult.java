package com.github.dmadapter.cli;

import com.github.dmadapter.core.FileChange;

import java.nio.file.Path;
import java.util.List;

record ValidationTestGenerationResult(
        Path projectRoot,
        Path appModuleRoot,
        Path configPath,
        Path testPath,
        List<FileChange> fileChanges,
        List<String> warnings
) {
    ValidationTestGenerationResult {
        fileChanges = List.copyOf(fileChanges == null ? List.of() : fileChanges);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
