package com.github.dmadapter.cli;

import java.nio.file.Path;
import java.util.function.Supplier;

class AdapterWorkspaceResolver {
    private final Supplier<Path> workingDirectory;

    AdapterWorkspaceResolver() {
        this(() -> Path.of(System.getProperty("user.dir", ".")));
    }

    AdapterWorkspaceResolver(Supplier<Path> workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    Path resolve(Path projectRoot, Path appModule, Path configuredReportDir) {
        if (configuredReportDir != null) {
            return configuredReportDir.toAbsolutePath().normalize();
        }
        return workingDirectory.get().toAbsolutePath().normalize();
    }
}
