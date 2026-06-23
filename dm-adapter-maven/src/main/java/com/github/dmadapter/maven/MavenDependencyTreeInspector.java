package com.github.dmadapter.maven;

import com.github.dmadapter.core.DependencyCoordinate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

final class MavenDependencyTreeInspector implements DependencyTreeInspector {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final DependencyTreeParser parser = new DependencyTreeParser();
    private final ProcessStarter processStarter;
    private final Consumer<String> outputConsumer;

    MavenDependencyTreeInspector() {
        this(ProcessBuilder::start, line -> {
        });
    }

    MavenDependencyTreeInspector(Consumer<String> outputConsumer) {
        this(ProcessBuilder::start, outputConsumer);
    }

    MavenDependencyTreeInspector(ProcessStarter processStarter, Consumer<String> outputConsumer) {
        this.processStarter = processStarter == null ? ProcessBuilder::start : processStarter;
        this.outputConsumer = outputConsumer == null ? line -> {
        } : outputConsumer;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    @Override
    public DependencyTreeAnalysis analyze(Path projectRoot, DependencyCoordinate dmDriverCoordinate) {
        Process process = null;
        try {
            List<String> command = command(projectRoot);
            outputConsumer.accept("Running Maven dependency tree: " + command);
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(projectRoot.toFile());
            processBuilder.redirectErrorStream(true);
            process = processStarter.start(processBuilder);
            Thread shutdownHook = registerShutdownHook(process);
            Process runningProcess = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readOutput(runningProcess.getInputStream()));
            try {
                boolean completed = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (!completed) {
                    outputConsumer.accept("Maven dependency tree timed out after " + TIMEOUT.toSeconds()
                            + " seconds; continuing with POM-only detection.");
                    destroyProcessTree(process);
                    output.cancel(true);
                    return DependencyTreeAnalysis.empty();
                }
                String mavenOutput = output.get(1, TimeUnit.SECONDS);
                if (process.exitValue() != 0) {
                    return DependencyTreeAnalysis.empty();
                }
                return parser.parse(mavenOutput, dmDriverCoordinate);
            } finally {
                removeShutdownHook(shutdownHook);
            }
        } catch (IOException | ExecutionException | TimeoutException e) {
            return DependencyTreeAnalysis.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DependencyTreeAnalysis.empty();
        } finally {
            if (process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
        }
    }

    private List<String> command(Path projectRoot) {
        List<String> command = new ArrayList<>();
        Path wrapper = projectRoot.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper)) {
            command.add(isWindows() ? wrapper.toString() : "./mvnw");
        } else {
            command.add(isWindows() ? "mvn.cmd" : "mvn");
        }
        command.add("-DskipTests");
        command.add("-Dstyle.color=never");
        command.add("dependency:tree");
        command.add("-DoutputType=text");
        return command;
    }

    private Thread registerShutdownHook(Process process) {
        Thread hook = new Thread(() -> destroyProcessTree(process), "dm-adapter-maven-dependency-tree-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            return hook;
        } catch (IllegalStateException e) {
            destroyProcessTree(process);
            return null;
        }
    }

    private void removeShutdownHook(Thread shutdownHook) {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // JVM shutdown is already in progress; the hook will handle process cleanup.
        }
    }

    private void destroyProcessTree(Process process) {
        try {
            ProcessHandle handle = process.toHandle();
            List<ProcessHandle> processes = new ArrayList<>(handle.descendants().toList());
            processes.add(handle);
            destroyProcessHandles(processes, false);
            destroyProcessHandles(processes, true);
        } catch (RuntimeException e) {
            destroyProcess(process);
        }
    }

    private void destroyProcessHandles(List<ProcessHandle> processes, boolean forcibly) {
        for (int i = processes.size() - 1; i >= 0; i--) {
            ProcessHandle process = processes.get(i);
            try {
                if (!process.isAlive()) {
                    continue;
                }
                if (forcibly) {
                    process.destroyForcibly();
                } else {
                    process.destroy();
                }
            } catch (RuntimeException e) {
                // Continue killing the rest of the Maven process tree.
            }
        }
    }

    private void destroyProcess(Process process) {
        try {
            process.destroyForcibly();
        } catch (RuntimeException e) {
            // Nothing else can be done if the JVM cannot destroy this process.
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String readOutput(InputStream inputStream) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                outputConsumer.accept(line);
            }
            return output.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
