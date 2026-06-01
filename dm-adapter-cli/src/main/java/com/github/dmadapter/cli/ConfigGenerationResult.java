package com.github.dmadapter.cli;

import com.github.dmadapter.core.FileChange;

import java.util.List;
import java.util.Optional;

record ConfigGenerationResult(Optional<FileChange> fileChange, List<String> warnings) {
    ConfigGenerationResult {
        fileChange = fileChange == null ? Optional.empty() : fileChange;
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
