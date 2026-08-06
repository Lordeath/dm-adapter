package com.github.dmadapter.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

class GitCommandRunner {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_OUTPUT_CHARS = 64_000;

    GitResult run(Path directory, String... arguments) throws IOException, InterruptedException {
        return run(directory, DEFAULT_TIMEOUT, List.of(arguments));
    }

    GitResult run(Path directory, Duration timeout, List<String> arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add("git");
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GCM_INTERACTIVE", "Never");
        Process process = builder.start();
        FutureTask<byte[]> outputReader = new FutureTask<>(() -> process.getInputStream().readAllBytes());
        Thread outputThread = new Thread(outputReader, "dm-adapter-git-output");
        outputThread.setDaemon(true);
        outputThread.start();
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            throw new IOException("Git command timed out after " + timeout.toSeconds() + " seconds: "
                    + display(arguments));
        }
        String output;
        try {
            output = new String(outputReader.get(), StandardCharsets.UTF_8);
        } catch (ExecutionException e) {
            throw new IOException("Could not read Git command output: " + display(arguments), e.getCause());
        }
        if (output.length() > MAX_OUTPUT_CHARS) {
            output = output.substring(output.length() - MAX_OUTPUT_CHARS);
        }
        output = redactUrlCredentials(output);
        return new GitResult(process.exitValue(), output.strip(), List.copyOf(arguments));
    }

    GitResult requireSuccess(Path directory, String stage, String... arguments)
            throws IOException, InterruptedException {
        GitResult result = run(directory, arguments);
        if (!result.success()) {
            throw new GitBatchException(stage, "Git command failed: " + display(result.arguments())
                    + outputSuffix(result.output()));
        }
        return result;
    }

    private String outputSuffix(String output) {
        return output == null || output.isBlank() ? "" : ". Output: " + output.replaceAll("\\s+", " ");
    }

    private static String display(List<String> arguments) {
        return "git " + String.join(" ", arguments);
    }

    private static String redactUrlCredentials(String output) {
        if (output == null || output.isBlank()) {
            return output;
        }
        return output.replaceAll("(?i)(https?://)[^/@\\s]+@", "$1******@");
    }

    record GitResult(int exitCode, String output, List<String> arguments) {
        boolean success() {
            return exitCode == 0;
        }
    }
}
