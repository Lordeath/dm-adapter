package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterWorkspaceResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void usesCurrentWorkingDirectoryWhenReportDirIsNotConfigured() {
        Path project = tempDir.resolve("business-project");

        AdapterWorkspaceResolver resolver = new AdapterWorkspaceResolver(() -> tempDir.resolve("dm-adapter"));

        assertThat(resolver.resolve(project, Path.of("service-module"), null))
                .isEqualTo(tempDir.resolve("dm-adapter").toAbsolutePath().normalize());
    }

    @Test
    void treatsConfiguredReportDirAsFinalWorkspaceDirectory() {
        Path project = tempDir.resolve("business-project");
        Path configured = tempDir.resolve("custom-output");

        AdapterWorkspaceResolver resolver = new AdapterWorkspaceResolver(() -> tempDir.resolve("dm-adapter"));

        assertThat(resolver.resolve(project, null, configured))
                .isEqualTo(configured.toAbsolutePath().normalize());
    }
}
