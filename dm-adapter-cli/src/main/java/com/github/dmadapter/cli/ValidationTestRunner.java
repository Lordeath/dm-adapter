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

    private final Map<String, String> processEnvironment;
    private final String osName;
    private final ProcessStarter processStarter;

    ValidationTestRunner() {
        this(System.getenv(), System.getProperty("os.name", ""), ProcessBuilder::start);
    }

    ValidationTestRunner(Map<String, String> processEnvironment, String osName, ProcessStarter processStarter) {
        this.processEnvironment = Map.copyOf(processEnvironment == null ? Map.of() : processEnvironment);
        this.osName = osName == null ? "" : osName;
        if (processStarter == null) {
            this.processStarter = ProcessBuilder::start;
        } else {
            this.processStarter = processStarter;
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder processBuilder) throws IOException;
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
        try {
            Process process = processStarter.start(processBuilder);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new ValidationTestRunResult(
                    true,
                    exitCode,
                    generationResult.projectRoot().resolve(".dm-adapter/sql-validation-report.md"),
                    tail(redact(output, environment), 40),
                    exitCode == 0
                            ? "Dameng SQL validation test passed."
                            : "Dameng SQL validation test exited with code " + exitCode + "."
            );
        } catch (IOException e) {
            List<String> diagnostics = startFailureDiagnostics(generationResult, command, workingDirectory, e);
            return new ValidationTestRunResult(
                    true,
                    1,
                    generationResult.projectRoot().resolve(".dm-adapter/sql-validation-report.md"),
                    diagnostics,
                    "Failed to start Maven validation test: " + e.getMessage()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ValidationTestRunResult(
                    true,
                    1,
                    generationResult.projectRoot().resolve(".dm-adapter/sql-validation-report.md"),
                    List.of(),
                    "Maven validation test was interrupted."
            );
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
        command.add("test");
        return command;
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
