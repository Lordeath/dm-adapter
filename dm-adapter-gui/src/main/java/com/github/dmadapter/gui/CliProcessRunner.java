package com.github.dmadapter.gui;

import com.github.dmadapter.cli.DmAdapterCli;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class CliProcessRunner implements AutoCloseable {
    private static final Set<String> MANAGED_ENVIRONMENT = Set.of(
            CliCommandBuilder.VALIDATION_ENABLED,
            CliCommandBuilder.JDBC_URL,
            CliCommandBuilder.DB_USERNAME,
            CliCommandBuilder.DB_PASSWORD
    );

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dm-adapter-cli-runner");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService cancellationExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dm-adapter-cli-canceller");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Process process;

    synchronized CompletableFuture<CliRunResult> run(
            CliInvocation invocation,
            Consumer<String> outputConsumer
    ) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有 CLI 任务正在运行。");
        }
        cancellationRequested.set(false);
        return CompletableFuture.supplyAsync(() -> execute(invocation, outputConsumer), executor);
    }

    boolean isRunning() {
        return running.get();
    }

    void cancel() {
        cancellationRequested.set(true);
        Process current = process;
        if (current == null || !current.isAlive()) {
            return;
        }
        terminate(current, false);
        cancellationExecutor.schedule(() -> {
            if (current.isAlive()) {
                terminate(current, true);
            }
        }, 2, TimeUnit.SECONDS);
    }

    private CliRunResult execute(CliInvocation invocation, Consumer<String> outputConsumer) {
        Consumer<String> safeConsumer = outputConsumer == null ? ignored -> { } : outputConsumer;
        Process started = null;
        try {
            if (cancellationRequested.get()) {
                return new CliRunResult(-1, true);
            }
            ProcessBuilder builder = new ProcessBuilder(command(invocation.arguments()));
            builder.redirectErrorStream(true);
            Map<String, String> environment = builder.environment();
            MANAGED_ENVIRONMENT.forEach(environment::remove);
            environment.putAll(invocation.environment());
            started = builder.start();
            process = started;
            if (cancellationRequested.get()) {
                terminate(started, false);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    started.getInputStream(), StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    safeConsumer.accept(line);
                }
            }
            int exitCode = started.waitFor();
            return new CliRunResult(exitCode, cancellationRequested.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (started != null) {
                terminate(started, true);
            }
            throw new IllegalStateException("CLI 任务等待被中断。", e);
        } catch (Exception e) {
            if (started != null && started.isAlive()) {
                terminate(started, true);
            }
            throw new IllegalStateException("无法启动或读取 CLI 进程：" + e.getMessage(), e);
        } finally {
            process = null;
            running.set(false);
        }
    }

    List<String> command(List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(absoluteClassPath());
        command.add(DmAdapterCli.class.getName());
        command.addAll(arguments);
        return command;
    }

    private Path javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize();
    }

    private String absoluteClassPath() {
        String[] entries = System.getProperty("java.class.path", "").split(
                java.util.regex.Pattern.quote(File.pathSeparator)
        );
        List<String> absolute = new ArrayList<>();
        for (String entry : entries) {
            if (!entry.isBlank()) {
                absolute.add(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
        return String.join(File.pathSeparator, absolute);
    }

    private void terminate(Process target, boolean forcibly) {
        List<ProcessHandle> descendants = target.descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            ProcessHandle descendant = descendants.get(index);
            if (forcibly) {
                descendant.destroyForcibly();
            } else {
                descendant.destroy();
            }
        }
        if (forcibly) {
            target.destroyForcibly();
        } else {
            target.destroy();
        }
    }

    @Override
    public void close() {
        cancel();
        executor.shutdownNow();
        cancellationExecutor.shutdownNow();
    }
}
