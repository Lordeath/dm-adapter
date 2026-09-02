package com.github.dmadapter.cli;

import com.github.dmadapter.core.DmAdapterException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

class MavenCompilePreparer {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);
    private static final int OUTPUT_TAIL_LINES = 12;

    private final ApplicationModuleSelector applicationModuleSelector;
    private final ProcessStarter processStarter;
    private final Consumer<String> outputConsumer;
    private final String osName;
    private final Duration timeout;

    MavenCompilePreparer() {
        this(
                new ApplicationModuleSelector(),
                ProcessBuilder::start,
                line -> CliLogger.info("[mvn] " + line),
                System.getProperty("os.name", ""),
                DEFAULT_TIMEOUT
        );
    }

    MavenCompilePreparer(
            ApplicationModuleSelector applicationModuleSelector,
            ProcessStarter processStarter,
            Consumer<String> outputConsumer,
            String osName,
            Duration timeout
    ) {
        this.applicationModuleSelector = applicationModuleSelector == null
                ? new ApplicationModuleSelector()
                : applicationModuleSelector;
        this.processStarter = processStarter == null ? ProcessBuilder::start : processStarter;
        this.outputConsumer = outputConsumer == null ? line -> {
        } : outputConsumer;
        this.osName = osName == null ? "" : osName;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    List<String> prepare(Path projectRoot, Path configuredAppModule) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedRoot.resolve("pom.xml"))) {
            return List.of();
        }
        ApplicationModule module;
        try {
            module = configuredAppModule == null
                    ? new ApplicationModule(normalizedRoot, normalizedRoot.resolve("pom.xml"), null, "")
                    : applicationModuleSelector.select(normalizedRoot, configuredAppModule);
        } catch (DmAdapterException e) {
            return List.of("在注解 SQL 类扫描前跳过 Maven 编译：" + e.getMessage());
        }

        MavenCompileInvocation invocation = invocation(normalizedRoot, module.moduleRoot());
        Process process = null;
        try {
            publish("Running Maven compile before annotation SQL class scan: " + invocation.command());
            process = processStarter.start(new ProcessBuilder(invocation.command())
                    .directory(invocation.workingDirectory().toFile())
                    .redirectErrorStream(true));
            Process runningProcess = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(
                    () -> readOutput(runningProcess.getInputStream())
            );
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                destroyProcessTree(process);
                output.cancel(true);
                return List.of("注解 SQL 类扫描前的 Maven 编译在 "
                        + timeout.toSeconds() + " 秒后超时；从 class 文件提取注解 SQL 的结果可能不完整。");
            }
            String processOutput = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() == 0) {
                return List.of();
            }
            List<String> warnings = new ArrayList<>();
            warnings.add("注解 SQL 类扫描前的 Maven 编译退出码为 "
                    + process.exitValue() + "；从 class 文件提取注解 SQL 的结果可能不完整。");
            warnings.addAll(tail(processOutput, OUTPUT_TAIL_LINES));
            return warnings;
        } catch (IOException | ExecutionException | TimeoutException e) {
            return List.of("运行注解 SQL 类扫描前的 Maven 编译失败：" + message(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("注解 SQL 类扫描前的 Maven 编译被中断；从 class 文件提取注解 SQL 的结果可能不完整。");
        } finally {
            if (process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
        }
    }

    MavenCompileInvocation invocation(Path projectRoot, Path moduleRoot) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedModule = moduleRoot.toAbsolutePath().normalize();
        Path workingDirectory = normalizedRoot;
        List<String> command = new ArrayList<>();
        command.add(mavenExecutable(normalizedRoot));
        if (!normalizedModule.equals(normalizedRoot)) {
            if (rootPomListsModule(normalizedRoot, normalizedModule)) {
                command.add("-pl");
                command.add(normalizedRoot.relativize(normalizedModule).toString().replace('\\', '/'));
                command.add("-am");
            } else {
                workingDirectory = normalizedModule;
                command.set(0, mavenExecutable(normalizedModule));
            }
        }
        command.add("-DskipTests");
        command.add("-Dmaven.test.skip=true");
        command.add("-Dstyle.color=never");
        command.add("compile");
        return new MavenCompileInvocation(command, workingDirectory);
    }

    private String mavenExecutable(Path projectRoot) {
        Path unixWrapper = projectRoot.resolve("mvnw");
        Path windowsWrapper = projectRoot.resolve("mvnw.cmd");
        if (isWindows()) {
            if (Files.isRegularFile(windowsWrapper)) {
                return windowsWrapper.toString();
            }
            return "mvn.cmd";
        }
        if (Files.isRegularFile(unixWrapper)) {
            return unixWrapper.toString();
        }
        return "mvn";
    }

    private boolean rootPomListsModule(Path projectRoot, Path moduleRoot) {
        Path pom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(pom) || moduleRoot.equals(projectRoot)) {
            return false;
        }
        String relative = projectRoot.relativize(moduleRoot).toString().replace('\\', '/');
        try {
            String xml = Files.readString(pom, StandardCharsets.UTF_8).replace('\\', '/');
            return xml.contains("<module>" + relative + "</module>");
        } catch (IOException e) {
            return false;
        }
    }

    private String readOutput(InputStream inputStream) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                publish(line);
            }
            return output.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void publish(String line) {
        outputConsumer.accept(line);
    }

    private List<String> tail(String output, int limit) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        String[] lines = output.split("\\R");
        int start = Math.max(0, lines.length - limit);
        List<String> result = new ArrayList<>();
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                result.add("Maven compile output: " + lines[i]);
            }
        }
        return result;
    }

    private String message(Exception exception) {
        Throwable cause = exception;
        if (exception instanceof ExecutionException && exception.getCause() != null) {
            cause = exception.getCause();
        }
        if (cause instanceof UncheckedIOException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
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
        return osName.toLowerCase(Locale.ROOT).contains("win");
    }

    record MavenCompileInvocation(List<String> command, Path workingDirectory) {
        MavenCompileInvocation {
            command = List.copyOf(command == null ? List.of() : command);
        }
    }
}
