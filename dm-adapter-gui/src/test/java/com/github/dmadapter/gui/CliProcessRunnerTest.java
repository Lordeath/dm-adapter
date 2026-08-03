package com.github.dmadapter.gui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CliProcessRunnerTest {
    @Test
    void buildsChildJvmCommandForCliMainClass() {
        try (CliProcessRunner runner = new CliProcessRunner()) {
            List<String> command = runner.command(List.of("--help"));

            assertThat(command)
                    .containsSubsequence("-Dfile.encoding=UTF-8", "-cp")
                    .contains("com.github.dmadapter.cli.DmAdapterCli", "--help");
            assertThat(command.get(0)).endsWith(
                    System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java"
            );
        }
    }

    @Test
    void executesCliInChildJvm() throws Exception {
        StringBuilder output = new StringBuilder();
        CliInvocation invocation = new CliInvocation(
                List.of("--help"),
                Map.of("DM_SQL_VALIDATION", "false"),
                Path.of(".")
        );

        try (CliProcessRunner runner = new CliProcessRunner()) {
            CliRunResult result = runner.run(
                    invocation,
                    line -> output.append(line).append('\n')
            ).get(20, TimeUnit.SECONDS);

            assertThat(result.exitCode()).isZero();
            assertThat(result.cancelled()).isFalse();
            assertThat(output.toString()).contains("Usage: dm-adapter", "migrate", "validate-sql");
        }
    }
}
