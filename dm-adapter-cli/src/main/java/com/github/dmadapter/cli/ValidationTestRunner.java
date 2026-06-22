package com.github.dmadapter.cli;

import java.io.File;
import java.io.IOException;
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

class ValidationTestRunner {
    private static final int MAX_PATH_CANDIDATES = 20;
    private static final int REPORT_SECTION_ROW_LIMIT = 8;

    private final Map<String, String> processEnvironment;
    private final String osName;
    private final ProcessStarter processStarter;
    private final ShutdownHookRegistry shutdownHookRegistry;

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
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }

    interface ShutdownHookRegistry {
        void add(Thread hook);

        boolean remove(Thread hook);
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
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        Process process = null;
        Thread shutdownHook = null;
        boolean completed = false;
        try {
            process = processStarter.start(processBuilder);
            shutdownHook = registerShutdownHook(process);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            completed = true;
            return new ValidationTestRunResult(
                    true,
                    exitCode,
                    generationResult.projectRoot().resolve(".dm-adapter/sql-validation-report.md"),
                    runDiagnostics(command, workingDirectory, output, environment, generationResult.projectRoot()),
                    exitCode == 0
                            ? "Dameng SQL validation test passed."
                            : "Dameng SQL validation test exited with code " + exitCode + "."
            );
        } catch (IOException e) {
            List<String> diagnostics = process == null
                    ? startFailureDiagnostics(generationResult, command, workingDirectory, e)
                    : runDiagnostics(
                            command,
                            workingDirectory,
                            e.getClass().getSimpleName() + ": " + e.getMessage(),
                            environment,
                            generationResult.projectRoot()
                    );
            String message = process == null
                    ? "Failed to start Maven validation test: " + e.getMessage()
                    : "Failed to read Maven validation output: " + e.getMessage();
            return new ValidationTestRunResult(
                    true,
                    1,
                    generationResult.projectRoot().resolve(".dm-adapter/sql-validation-report.md"),
                    diagnostics,
                    message
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ValidationTestRunResult(
                    true,
                    1,
                    generationResult.projectRoot().resolve(".dm-adapter/sql-validation-report.md"),
                    runDiagnostics(command, workingDirectory, "", environment, generationResult.projectRoot()),
                    "Maven validation test was interrupted."
            );
        } finally {
            removeShutdownHook(shutdownHook);
            if (!completed && process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
        }
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
        command.add("-Dtest=" + DmSqlValidationTestGenerator.TEST_CLASS_NAME);
        command.add("-Dsurefire.failIfNoSpecifiedTests=false");
        command.add("test");
        return command;
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

    private List<String> runDiagnostics(
            List<String> command,
            Path workingDirectory,
            String output,
            DmValidationEnvironment environment,
            Path projectRoot
    ) {
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("Maven command: " + command);
        diagnostics.add("Working directory: " + workingDirectory);
        diagnostics.addAll(validationReportSummary(projectRoot));
        diagnostics.addAll(tail(redact(output, environment), 40));
        return diagnostics;
    }

    private List<String> validationReportSummary(Path projectRoot) {
        Path report = projectRoot.resolve(".dm-adapter/sql-validation-report.md");
        if (!Files.isRegularFile(report)) {
            return List.of();
        }
        try {
            String markdown = Files.readString(report, StandardCharsets.UTF_8);
            List<String> summary = new ArrayList<>();
            summary.add("Validation report summary:");
            appendCountLine(summary, markdown, "- Passed:");
            appendCountLine(summary, markdown, "- Failed:");
            appendCountLine(summary, markdown, "- Skipped:");
            appendReportSectionSummary(summary, markdown, "Failure Categories");
            appendReportSectionSummary(summary, markdown, "Failure Patterns");
            appendReportSectionSummary(summary, markdown, "Schema Object Hotspots");
            return summary.size() == 1 ? List.of() : summary;
        } catch (IOException e) {
            return List.of("Validation report summary unavailable: " + e.getMessage());
        }
    }

    private void appendCountLine(List<String> summary, String markdown, String prefix) {
        for (String line : markdown.split("\\R")) {
            if (line.startsWith(prefix)) {
                summary.add(line);
                return;
            }
        }
    }

    private void appendReportSectionSummary(List<String> summary, String markdown, String heading) {
        List<String> lines = sectionLines(markdown, "## " + heading);
        if (lines.isEmpty()) {
            return;
        }
        summary.add(heading + ":");
        int rowsInCurrentTable = 0;
        boolean omittedCurrentTableRows = false;
        for (String line : lines) {
            if (line.startsWith("### ")) {
                summary.add(line.substring("### ".length()) + ":");
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

    private List<String> sectionLines(String markdown, String heading) {
        String[] lines = markdown.split("\\R");
        List<String> section = new ArrayList<>();
        boolean inSection = false;
        for (String line : lines) {
            if (line.equals(heading)) {
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
                || "Column".equals(value);
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
