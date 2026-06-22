package com.github.dmadapter.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class ValidationTestRunner {
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
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory(generationResult).toFile())
                .redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
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
            return new ValidationTestRunResult(
                    true,
                    1,
                    generationResult.projectRoot().resolve(".dm-adapter/sql-validation-report.md"),
                    List.of(),
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

    private List<String> mavenCommand(ValidationTestGenerationResult generationResult) {
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
        if (Files.isRegularFile(unixWrapper)) {
            return unixWrapper.toString();
        }
        Path windowsWrapper = projectRoot.resolve("mvnw.cmd");
        if (Files.isRegularFile(windowsWrapper)) {
            return windowsWrapper.toString();
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
