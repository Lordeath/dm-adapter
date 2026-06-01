package com.github.dmadapter.maven;

import java.nio.file.Path;
import java.util.List;

public record PomTargetSelection(
        List<Path> pomPaths,
        String reason,
        List<String> warnings
) {
    public PomTargetSelection {
        pomPaths = List.copyOf(pomPaths == null ? List.of() : pomPaths);
        reason = reason == null ? "" : reason;
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
