package com.github.dmadapter.cli;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ValidationTestRunner {
    private static final int MAX_PATH_CANDIDATES = 20;
    private static final int REPORT_SECTION_ROW_LIMIT = 8;
    private static final String ADAPTER_DIR_PROPERTY = "dm.adapter.dir";
    private static final String PROJECT_ROOT_PROPERTY = "dm.adapter.projectRoot";
    private static final String CONFIG_PROPERTY = "dm.adapter.config";
    private static final String REWRITE_CONFIG_PROPERTY = "dm.adapter.rewriteConfig";
    private static final String PREVIOUS_MARKDOWN_REPORT = "sql-validation-report.previous.md";
    private static final String PREVIOUS_JSON_REPORT = "sql-validation-report.previous.json";
    private static final Pattern FAILED_COUNT_PATTERN =
            Pattern.compile("\"failed\"\\s*:\\s*(\\d+)");

    private final Map<String, String> processEnvironment;
    private final String osName;
    private final ProcessStarter processStarter;
    private final ShutdownHookRegistry shutdownHookRegistry;
    private final Consumer<String> mavenOutputConsumer;

    ValidationTestRunner() {
        this(System.getenv(), System.getProperty("os.name", ""), ProcessBuilder::start);
    }

    ValidationTestRunner(Map<String, String> processEnvironment, String osName, ProcessStarter processStarter) {
        this(processEnvironment, osName, processStarter, new RuntimeShutdownHookRegistry());
    }

    ValidationTestRunner(
            Map<String, String> processEnvironment,
            String osName,
            ProcessStarter processStarter,
            ShutdownHookRegistry shutdownHookRegistry
    ) {
        this(processEnvironment, osName, processStarter, shutdownHookRegistry, CliLogger::info);
    }

    ValidationTestRunner(
            Map<String, String> processEnvironment,
            String osName,
            ProcessStarter processStarter,
            ShutdownHookRegistry shutdownHookRegistry,
            Consumer<String> mavenOutputConsumer
    ) {
        this.processEnvironment = Map.copyOf(processEnvironment == null ? Map.of() : processEnvironment);
        this.osName = osName == null ? "" : osName;
        if (processStarter == null) {
            this.processStarter = ProcessBuilder::start;
        } else {
            this.processStarter = processStarter;
        }
        if (shutdownHookRegistry == null) {
            this.shutdownHookRegistry = new RuntimeShutdownHookRegistry();
        } else {
            this.shutdownHookRegistry = shutdownHookRegistry;
        }
        this.mavenOutputConsumer = mavenOutputConsumer == null ? line -> {
        } : mavenOutputConsumer;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    interface ShutdownHookRegistry {
        void add(Thread hook);

        boolean remove(Thread hook);
    }

    private record ProcessExecutionResult(
            List<String> command,
            Path workingDirectory,
            int exitCode,
            String output
    ) {
        private ProcessExecutionResult {
            command = List.copyOf(command);
            output = output == null ? "" : output;
        }
    }

    private static class RuntimeShutdownHookRegistry implements ShutdownHookRegistry {
        @Override
        public void add(Thread hook) {
            Runtime.getRuntime().addShutdownHook(hook);
        }

        @Override
        public boolean remove(Thread hook) {
            return Runtime.getRuntime().removeShutdownHook(hook);
        }
    }

    ValidationTestRunResult runIfConfigured(
            ValidationTestGenerationResult generationResult,
            DmValidationEnvironment environment
    ) {
        if (!environment.validationEnabled()) {
            return ValidationTestRunResult.skipped("DM_SQL_VALIDATION is not true; validation test was not executed.");
        }
        if (!environment.ready()) {
            return ValidationTestRunResult.skipped("DM_SQL_VALIDATION is true but required variables are missing: "
                    + environment.missingVariables());
        }

        List<String> command = mavenCommand(generationResult);
        Path workingDirectory = workingDirectory(generationResult);
        Path markdownReport = validationMarkdownReport(generationResult.workspaceDir());
        try {
            deleteStaleValidationReports(generationResult.workspaceDir());
        } catch (IOException e) {
            return new ValidationTestRunResult(
                    true,
                    1,
                    null,
                    List.of("Failed to remove stale validation report: " + e.getMessage()),
                    "Failed to prepare validation report files: " + e.getMessage()
            );
        }
        List<ProcessExecutionResult> executions = new ArrayList<>();
        List<String> currentCommand = command;
        Path currentWorkingDirectory = workingDirectory;
        try {
            ProcessExecutionResult mavenExecution = executeProcess(
                    command,
                    workingDirectory,
                    environment,
                    "Running Maven validation test: "
            );
            executions.add(mavenExecution);
            ProcessExecutionResult effectiveExecution = mavenExecution;
            if (mavenExecution.exitCode() == 0 && mavenTestsWereSkipped(mavenExecution.output())) {
                publishMavenOutput("Maven reported tests are skipped; running generated validation main directly.");
                Files.createDirectories(generationResult.workspaceDir());
                currentCommand = mavenClasspathCommand(generationResult);
                currentWorkingDirectory = workingDirectory;
                ProcessExecutionResult classpathExecution = executeProcess(
                        currentCommand,
                        currentWorkingDirectory,
                        environment,
                        "Preparing validation classpath with Maven: "
                );
                executions.add(classpathExecution);
                effectiveExecution = classpathExecution;
                if (classpathExecution.exitCode() == 0) {
                    currentCommand = javaValidationCommand(generationResult);
                    currentWorkingDirectory = generationResult.appModuleRoot();
                    ProcessExecutionResult javaExecution = executeProcess(
                            currentCommand,
                            currentWorkingDirectory,
                            environment,
                            "Running generated validation main: "
                    );
                    executions.add(javaExecution);
                    effectiveExecution = javaExecution;
                }
            }
            int exitCode = effectiveExecution.exitCode();
            int reportFailureCount = validationReportFailureCount(generationResult.workspaceDir());
            if (exitCode == 0 && reportFailureCount > 0) {
                exitCode = 1;
                publishMavenOutput(
                        "Generated validation report contains " + reportFailureCount
                                + " failure(s); treating the validation run as failed even though Maven returned success."
                );
            }
            String combinedOutput = combinedOutput(executions);
            return new ValidationTestRunResult(
                    true,
                    exitCode,
                    existingReportPath(markdownReport),
                    runDiagnostics(executions, combinedOutput, environment, generationResult.workspaceDir()),
                    exitCode == 0
                            ? "Dameng SQL validation test passed."
                            : "Dameng SQL validation test exited with code " + exitCode + "."
            );
        } catch (IOException e) {
            List<String> diagnostics = executions.isEmpty()
                    ? startFailureDiagnostics(generationResult, currentCommand, currentWorkingDirectory, e)
                    : runDiagnostics(
                            executions,
                            combinedOutput(executions) + e.getClass().getSimpleName() + ": " + e.getMessage(),
                            environment,
                            generationResult.workspaceDir()
                    );
            String message = executions.isEmpty()
                    ? "Failed to start Maven validation test: " + e.getMessage()
                    : "Failed to continue validation test run: " + e.getMessage();
            return new ValidationTestRunResult(
                    true,
                    1,
                    existingReportPath(markdownReport),
                    diagnostics,
                    message
            );
        } catch (ValidationProcessTimeoutException e) {
            return new ValidationTestRunResult(
                    true,
                    4,
                    existingReportPath(markdownReport),
                    runDiagnostics(executions, combinedOutput(executions), environment, generationResult.workspaceDir()),
                    "Dameng SQL validation timed out after " + environment.totalTimeoutSeconds() + " seconds.",
                    com.github.dmadapter.core.StageStatus.TIMEOUT
            );
        } catch (ExecutionException | TimeoutException e) {
            String readFailure = mavenOutputReadFailure(e);
            return new ValidationTestRunResult(
                    true,
                    1,
                    existingReportPath(markdownReport),
                    runDiagnostics(executions, combinedOutput(executions) + readFailure, environment, generationResult.workspaceDir()),
                    "Failed to read Maven validation output: " + readFailure
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ValidationTestRunResult(
                    true,
                    1,
                    existingReportPath(markdownReport),
                    runDiagnostics(executions, combinedOutput(executions), environment, generationResult.workspaceDir()),
                    "Maven validation test was interrupted."
            );
        }
    }

    private ProcessExecutionResult executeProcess(
            List<String> command,
            Path workingDirectory,
            DmValidationEnvironment environment,
            String description
    ) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Process process = null;
        Thread shutdownHook = null;
        boolean completed = false;
        try {
            publishMavenOutput(description + command);
            process = processStarter.start(processBuilder);
            shutdownHook = registerShutdownHook(process);
            Process runningProcess = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(
                    () -> readMavenOutput(runningProcess.getInputStream(), environment)
            );
            long remainingSeconds = environment.deadline().remainingSeconds();
            if (remainingSeconds <= 0L) {
                throw new ValidationProcessTimeoutException();
            }
            AtomicBoolean timedOut = new AtomicBoolean(false);
            ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dm-validation-timeout");
                thread.setDaemon(true);
                return thread;
            });
            ScheduledFuture<?> timeoutTask = timeoutExecutor.schedule(() -> {
                timedOut.set(true);
                destroyProcessTree(runningProcess);
            }, remainingSeconds, TimeUnit.SECONDS);
            int exitCode;
            try {
                exitCode = process.waitFor();
            } finally {
                timeoutTask.cancel(false);
                timeoutExecutor.shutdownNow();
            }
            if (timedOut.get()) {
                throw new ValidationProcessTimeoutException();
            }
            String processOutput = output.get(5, TimeUnit.SECONDS);
            completed = true;
            return new ProcessExecutionResult(command, workingDirectory, exitCode, processOutput);
        } finally {
            removeShutdownHook(shutdownHook);
            if (!completed && process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
        }
    }

    private void deleteStaleValidationReports(Path workspaceDir) throws IOException {
        rotateIfExists(
                validationMarkdownReport(workspaceDir),
                workspaceDir.resolve(PREVIOUS_MARKDOWN_REPORT)
        );
        rotateIfExists(
                workspaceDir.resolve("sql-validation-report.json"),
                workspaceDir.resolve(PREVIOUS_JSON_REPORT)
        );
        Files.deleteIfExists(validationClasspathFile(workspaceDir));
        Files.deleteIfExists(validationJavaArgsFile(workspaceDir));
    }

    private void rotateIfExists(Path current, Path previous) throws IOException {
        if (!Files.isRegularFile(current)) {
            return;
        }
        Files.move(current, previous, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private Path validationMarkdownReport(Path workspaceDir) {
        return workspaceDir.resolve("sql-validation-report.md");
    }

    private Path validationClasspathFile(Path workspaceDir) {
        return workspaceDir.resolve("sql-validation-classpath.txt");
    }

    private Path validationJavaArgsFile(Path workspaceDir) {
        return workspaceDir.resolve("sql-validation-java.args");
    }

    private Path existingReportPath(Path reportPath) {
        return Files.isRegularFile(reportPath) ? reportPath : null;
    }

    private int validationReportFailureCount(Path workspaceDir) {
        Path report = workspaceDir.resolve("sql-validation-report.json");
        if (!Files.isRegularFile(report)) {
            return 0;
        }
        try {
            Matcher matcher = FAILED_COUNT_PATTERN.matcher(Files.readString(report, StandardCharsets.UTF_8));
            if (!matcher.find()) {
                return 0;
            }
            return Integer.parseInt(matcher.group(1));
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private static final class ValidationProcessTimeoutException extends TimeoutException {
    }

    List<String> mavenCommand(ValidationTestGenerationResult generationResult) {
        Path projectRoot = generationResult.projectRoot();
        Path moduleRoot = generationResult.appModuleRoot();
        List<String> command = new ArrayList<>();
        command.add(mavenExecutable(projectRoot));
        if (!moduleRoot.equals(projectRoot) && rootPomListsModule(projectRoot, moduleRoot)) {
            command.add("-pl");
            command.add(projectRoot.relativize(moduleRoot).toString().replace('\\', '/'));
            command.add("-am");
        }
        addValidationSystemProperties(command, generationResult);
        command.add("-Dtest=" + validationMainClass(generationResult));
        command.add("-DskipTests=false");
        command.add("-Dmaven.test.skip=false");
        command.add("-Dsurefire.failIfNoSpecifiedTests=false");
        command.add("test");
        return command;
    }

    List<String> mavenClasspathCommand(ValidationTestGenerationResult generationResult) {
        Path projectRoot = generationResult.projectRoot();
        Path moduleRoot = generationResult.appModuleRoot();
        List<String> command = new ArrayList<>();
        command.add(mavenExecutable(projectRoot));
        if (!moduleRoot.equals(projectRoot) && rootPomListsModule(projectRoot, moduleRoot)) {
            command.add("-pl");
            command.add(projectRoot.relativize(moduleRoot).toString().replace('\\', '/'));
            command.add("-am");
        }
        command.add("-DskipTests=false");
        command.add("-Dmaven.test.skip=false");
        command.add("test-compile");
        command.add("dependency:build-classpath");
        command.add("-Dmdep.includeScope=test");
        command.add("-Dmdep.outputFile=" + validationClasspathFile(generationResult.workspaceDir()));
        return command;
    }

    private void addValidationSystemProperties(
            List<String> command,
            ValidationTestGenerationResult generationResult
    ) {
        command.add(systemPropertyArgument(ADAPTER_DIR_PROPERTY, generationResult.workspaceDir()));
        command.add(systemPropertyArgument(PROJECT_ROOT_PROPERTY, generationResult.projectRoot()));
        command.add(systemPropertyArgument(CONFIG_PROPERTY, generationResult.configPath()));
        command.add(systemPropertyArgument(REWRITE_CONFIG_PROPERTY, generationResult.rewriteConfigPath()));
    }

    private String systemPropertyArgument(String name, Path value) {
        return "-D" + name + "=" + value.toAbsolutePath().normalize();
    }

    List<String> javaValidationCommand(ValidationTestGenerationResult generationResult) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("@" + writeValidationJavaArgsFile(generationResult));
        return command;
    }

    private Path writeValidationJavaArgsFile(ValidationTestGenerationResult generationResult) throws IOException {
        Path argsFile = validationJavaArgsFile(generationResult.workspaceDir());
        Files.createDirectories(argsFile.getParent());
        List<String> args = List.of(
                systemPropertyArgument(ADAPTER_DIR_PROPERTY, generationResult.workspaceDir()),
                systemPropertyArgument(PROJECT_ROOT_PROPERTY, generationResult.projectRoot()),
                systemPropertyArgument(CONFIG_PROPERTY, generationResult.configPath()),
                systemPropertyArgument(REWRITE_CONFIG_PROPERTY, generationResult.rewriteConfigPath()),
                "-cp",
                validationRuntimeClasspath(generationResult),
                validationMainClass(generationResult)
        );
        Files.writeString(argsFile, javaArgumentFileContent(args), StandardCharsets.UTF_8);
        return argsFile;
    }

    private String javaArgumentFileContent(List<String> args) {
        StringBuilder content = new StringBuilder();
        for (String arg : args) {
            content.append(javaArgumentFileToken(arg)).append(System.lineSeparator());
        }
        return content.toString();
    }

    private String javaArgumentFileToken(String arg) {
        String value = arg == null ? "" : arg;
        if (!requiresJavaArgumentFileQuoting(value)) {
            return value;
        }
        StringBuilder token = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\' || current == '"') {
                token.append('\\');
            }
            if (current == '\r' || current == '\n') {
                token.append(' ');
            } else {
                token.append(current);
            }
        }
        return token.append('"').toString();
    }

    private boolean requiresJavaArgumentFileQuoting(String arg) {
        if (arg.isBlank() || arg.contains("#")) {
            return true;
        }
        for (int i = 0; i < arg.length(); i++) {
            if (Character.isWhitespace(arg.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String javaExecutable() {
        Path executable = Path.of(
                System.getProperty("java.home", ""),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        return Files.isRegularFile(executable) ? executable.toString() : "java";
    }

    private String validationRuntimeClasspath(ValidationTestGenerationResult generationResult) throws IOException {
        String separator = isWindows() ? ";" : File.pathSeparator;
        List<String> elements = new ArrayList<>();
        Path moduleRoot = generationResult.appModuleRoot();
        elements.add(moduleRoot.resolve("target/test-classes").toString());
        elements.add(moduleRoot.resolve("target/classes").toString());
        Path classpathFile = validationClasspathFile(generationResult.workspaceDir());
        if (Files.isRegularFile(classpathFile)) {
            String classpath = Files.readString(classpathFile, StandardCharsets.UTF_8).trim();
            if (!classpath.isBlank()) {
                elements.add(classpath);
            }
        }
        return String.join(separator, elements);
    }

    private String validationMainClass(ValidationTestGenerationResult generationResult) {
        Path testRoot = generationResult.appModuleRoot().resolve("src/test/java").toAbsolutePath().normalize();
        Path testPath = generationResult.testPath().toAbsolutePath().normalize();
        if (!testPath.startsWith(testRoot)) {
            return DmSqlValidationTestGenerator.TEST_CLASS_NAME;
        }
        String relative = testRoot.relativize(testPath).toString();
        if (relative.endsWith(".java")) {
            relative = relative.substring(0, relative.length() - ".java".length());
        }
        return relative.replace('\\', '.').replace('/', '.');
    }

    private boolean mavenTestsWereSkipped(String output) {
        return output != null && output.toLowerCase(Locale.ROOT).contains("tests are skipped");
    }

    private Thread registerShutdownHook(Process process) {
        Thread hook = new Thread(() -> destroyProcessTree(process), "dm-adapter-maven-validation-shutdown");
        try {
            shutdownHookRegistry.add(hook);
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
            shutdownHookRegistry.remove(shutdownHook);
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

    private String readMavenOutput(InputStream inputStream, DmValidationEnvironment environment) {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                publishMavenOutput(redact(line, environment));
            }
            return output.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void publishMavenOutput(String line) {
        mavenOutputConsumer.accept("[mvn] " + line);
    }

    private String mavenOutputReadFailure(Exception exception) {
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

    private String combinedOutput(List<ProcessExecutionResult> executions) {
        StringBuilder output = new StringBuilder();
        for (ProcessExecutionResult execution : executions) {
            output.append(execution.output());
            if (!execution.output().endsWith("\n")) {
                output.append('\n');
            }
        }
        return output.toString();
    }

    private List<String> runDiagnostics(
            List<ProcessExecutionResult> executions,
            String output,
            DmValidationEnvironment environment,
            Path workspaceDir
    ) {
        List<String> diagnostics = new ArrayList<>();
        if (executions.isEmpty()) {
            diagnostics.add("Maven command: <not started>");
        }
        for (int i = 0; i < executions.size(); i++) {
            ProcessExecutionResult execution = executions.get(i);
            diagnostics.add(commandLabel(i, execution.command()) + ": " + execution.command());
            diagnostics.add("Working directory: " + execution.workingDirectory());
        }
        diagnostics.addAll(validationReportSummary(workspaceDir));
        diagnostics.addAll(tail(redact(output, environment), 40));
        return diagnostics;
    }

    private String commandLabel(int index, List<String> command) {
        if (index == 0) {
            return "Maven command";
        }
        if (!command.isEmpty() && command.get(0).toLowerCase(Locale.ROOT).contains("java")) {
            return "Java validation command";
        }
        return "Maven fallback command";
    }

    private List<String> validationReportSummary(Path workspaceDir) {
        Path report = validationMarkdownReport(workspaceDir);
        if (!Files.isRegularFile(report)) {
            return List.of();
        }
        try {
            String markdown = Files.readString(report, StandardCharsets.UTF_8);
            List<String> summary = new ArrayList<>();
            summary.add("验证报告摘要:");
            appendCountLine(summary, markdown, "- 通过:", "- Passed:");
            appendCountLine(summary, markdown, "- 失败:", "- Failed:");
            appendCountLine(summary, markdown, "- 跳过:", "- Skipped:");
            appendReportSectionSummary(summary, markdown, "失败分类汇总", "Failure Categories");
            appendReportSectionSummary(summary, markdown, "失败模式汇总", "Failure Patterns");
            appendReportSectionSummary(summary, markdown, "库表对象缺失热点", "Schema Object Hotspots");
            return summary.size() == 1 ? List.of() : summary;
        } catch (IOException e) {
            return List.of("验证报告摘要不可用: " + e.getMessage());
        }
    }

    private void appendCountLine(List<String> summary, String markdown, String primaryPrefix, String legacyPrefix) {
        for (String line : markdown.split("\\R")) {
            if (line.startsWith(primaryPrefix)) {
                summary.add(line);
                return;
            }
            if (line.startsWith(legacyPrefix)) {
                summary.add(primaryPrefix + line.substring(legacyPrefix.length()));
                return;
            }
        }
    }

    private void appendReportSectionSummary(List<String> summary, String markdown, String heading, String legacyHeading) {
        List<String> lines = sectionLines(markdown, "## " + heading, "## " + legacyHeading);
        if (lines.isEmpty()) {
            return;
        }
        summary.add(heading + ":");
        int rowsInCurrentTable = 0;
        boolean omittedCurrentTableRows = false;
        for (String line : lines) {
            if (line.startsWith("### ")) {
                summary.add(reportSubheadingDisplay(line.substring("### ".length())) + ":");
                rowsInCurrentTable = 0;
                omittedCurrentTableRows = false;
                continue;
            }
            String compactRow = compactMarkdownTableRow(line);
            if (compactRow.isBlank()) {
                continue;
            }
            if (rowsInCurrentTable < REPORT_SECTION_ROW_LIMIT) {
                summary.add(compactRow);
            } else if (!omittedCurrentTableRows) {
                summary.add("- ...");
                omittedCurrentTableRows = true;
            }
            rowsInCurrentTable++;
        }
    }

    private String reportSubheadingDisplay(String heading) {
        return switch (heading) {
            case "Missing Tables/Views" -> "缺失表/视图";
            case "Missing Columns" -> "缺失字段";
            default -> heading;
        };
    }

    private List<String> sectionLines(String markdown, String... headings) {
        String[] lines = markdown.split("\\R");
        List<String> section = new ArrayList<>();
        boolean inSection = false;
        Set<String> targetHeadings = Set.of(headings);
        for (String line : lines) {
            if (targetHeadings.contains(line)) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("## ")) {
                break;
            }
            if (inSection) {
                section.add(line);
            }
        }
        return section;
    }

    private String compactMarkdownTableRow(String line) {
        if (!line.startsWith("|") || line.contains("---")) {
            return "";
        }
        List<String> cells = markdownTableCells(line);
        if (cells.size() < 2 || isTableHeader(cells.get(0))) {
            return "";
        }
        return "- " + cells.get(0) + ": " + cells.get(1);
    }

    private List<String> markdownTableCells(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|")) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private boolean isTableHeader(String value) {
        return "Category".equals(value)
                || "Pattern".equals(value)
                || "Object".equals(value)
                || "Column".equals(value)
                || "分类".equals(value)
                || "模式".equals(value)
                || "对象".equals(value)
                || "字段".equals(value);
    }

    private Path workingDirectory(ValidationTestGenerationResult generationResult) {
        Path projectRoot = generationResult.projectRoot();
        Path moduleRoot = generationResult.appModuleRoot();
        if (!moduleRoot.equals(projectRoot) && !rootPomListsModule(projectRoot, moduleRoot)) {
            return moduleRoot;
        }
        return projectRoot;
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

    private List<String> startFailureDiagnostics(
            ValidationTestGenerationResult generationResult,
            List<String> command,
            Path workingDirectory,
            IOException exception
    ) {
        Path projectRoot = generationResult.projectRoot();
        Path appModuleRoot = generationResult.appModuleRoot();
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("Maven validation diagnostics:");
        diagnostics.add("Start error: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        diagnostics.add("Maven command: " + command);
        diagnostics.add("Working directory: " + workingDirectory + " (exists=" + Files.isDirectory(workingDirectory) + ")");
        diagnostics.add("Project root: " + projectRoot + " (exists=" + Files.isDirectory(projectRoot) + ")");
        diagnostics.add("Application module root: " + appModuleRoot + " (exists=" + Files.isDirectory(appModuleRoot) + ")");
        diagnostics.add("dm-adapter workspace: " + generationResult.workspaceDir()
                + " (exists=" + Files.isDirectory(generationResult.workspaceDir()) + ")");
        diagnostics.add("Root pom.xml exists: " + Files.isRegularFile(projectRoot.resolve("pom.xml")));
        diagnostics.add("OS: " + osName + " " + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", ""));
        diagnostics.add("java.home: " + System.getProperty("java.home", ""));
        diagnostics.add("JAVA_HOME: " + placeholder(environmentValue("JAVA_HOME")));
        diagnostics.add("MAVEN_HOME: " + placeholder(environmentValue("MAVEN_HOME")));
        diagnostics.add("M2_HOME: " + placeholder(environmentValue("M2_HOME")));
        if (isWindows()) {
            diagnostics.add("PATHEXT: " + placeholder(environmentValue("PATHEXT")));
        }
        diagnostics.add("PATH entry count: " + pathEntries().size());
        diagnostics.add("Maven candidates on PATH: " + formattedMavenCandidatesOnPath());
        diagnostics.add("Project Maven wrappers: " + wrapperDiagnostics(projectRoot));
        if (isWindows()) {
            diagnostics.add("Windows note: Java starts commands directly; use mvn.cmd/mvnw.cmd rather than relying on PowerShell alias resolution.");
        }
        return diagnostics;
    }

    private String formattedMavenCandidatesOnPath() {
        List<String> candidates = mavenCandidatesOnPath();
        return candidates.isEmpty() ? "<none>" : candidates.toString();
    }

    private List<String> mavenCandidatesOnPath() {
        List<String> entries = pathEntries();
        if (entries.isEmpty()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        List<String> executableNames = mavenExecutableNames();
        for (String entry : entries) {
            try {
                Path directory = Path.of(entry);
                for (String executableName : executableNames) {
                    Path candidate = directory.resolve(executableName);
                    if (Files.isRegularFile(candidate)) {
                        candidates.add(candidate.toString());
                        if (candidates.size() >= MAX_PATH_CANDIDATES) {
                            return new ArrayList<>(candidates);
                        }
                    }
                }
            } catch (InvalidPathException e) {
                // Ignore malformed PATH entries while reporting the rest of the environment.
            }
        }
        return new ArrayList<>(candidates);
    }

    private List<String> mavenExecutableNames() {
        if (!isWindows()) {
            return List.of("mvn");
        }
        Set<String> names = new LinkedHashSet<>();
        names.add("mvn.cmd");
        names.add("mvn.bat");
        names.add("mvn.exe");
        names.add("mvn");
        String pathExt = environmentValue("PATHEXT");
        if (!pathExt.isBlank()) {
            for (String extension : pathExt.split(";")) {
                String normalized = extension.trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    continue;
                }
                if (!normalized.startsWith(".")) {
                    normalized = "." + normalized;
                }
                names.add("mvn" + normalized);
            }
        }
        return new ArrayList<>(names);
    }

    private List<String> pathEntries() {
        String path = environmentValue("PATH");
        if (path.isBlank()) {
            return List.of();
        }
        String separator = isWindows() ? ";" : File.pathSeparator;
        List<String> entries = new ArrayList<>();
        for (String entry : path.split(separator)) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    private String wrapperDiagnostics(Path projectRoot) {
        Path unixWrapper = projectRoot.resolve("mvnw");
        Path windowsWrapper = projectRoot.resolve("mvnw.cmd");
        return "[mvnw=" + Files.isRegularFile(unixWrapper) + ", mvnw.cmd=" + Files.isRegularFile(windowsWrapper) + "]";
    }

    private String environmentValue(String name) {
        String value = processEnvironment.get(name);
        if (value != null) {
            return value;
        }
        if (isWindows()) {
            for (Map.Entry<String, String> entry : processEnvironment.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
        }
        return "";
    }

    private String placeholder(String value) {
        return value == null || value.isBlank() ? "<empty>" : value;
    }

    private boolean isWindows() {
        return osName.toLowerCase(Locale.ROOT).contains("win");
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

    private String redact(String output, DmValidationEnvironment environment) {
        String redacted = output == null ? "" : output;
        redacted = redactValue(redacted, environment.jdbcUrl());
        redacted = redactValue(redacted, environment.username());
        redacted = redactValue(redacted, environment.password());
        return redacted;
    }

    private String redactValue(String text, String value) {
        if (value == null || value.isBlank()) {
            return text;
        }
        return text.replace(value, "******");
    }

    private List<String> tail(String output, int maxLines) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        String[] lines = output.strip().split("\\R");
        int start = Math.max(0, lines.length - maxLines);
        List<String> tail = new ArrayList<>();
        for (int i = start; i < lines.length; i++) {
            tail.add(lines[i]);
        }
        return tail;
    }
}
