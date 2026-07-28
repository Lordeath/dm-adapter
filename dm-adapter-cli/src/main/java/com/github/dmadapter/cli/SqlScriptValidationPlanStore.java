package com.github.dmadapter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.DamengTargetCapabilities;
import com.github.dmadapter.core.SqlScriptManualReviewItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class SqlScriptValidationPlanStore {
    static final String DEFAULT_FILE_NAME = "sql-script-validation-plan.json";
    static final int FORMAT_VERSION = 1;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    Path write(
            Path planPath,
            Path projectRoot,
            Path sqlRootOut,
            DamengTargetCapabilities capabilities,
            List<SqlScriptMigrator.PlannedSqlScriptFile> files,
            List<SqlScriptManualReviewItem> manualReviewItems
    ) throws IOException {
        Path normalizedPlan = planPath.toAbsolutePath().normalize();
        Files.createDirectories(normalizedPlan.getParent());
        Map<String, String> manualReasons = manualReasons(manualReviewItems);
        List<ValidationPlanFile> planFiles = new ArrayList<>();
        for (SqlScriptMigrator.PlannedSqlScriptFile file : files) {
            Path output = Path.of(file.outputDisplay()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(output)) {
                throw new IllegalArgumentException(
                        "Cannot create a strict SQL validation plan because an output file is missing: " + output
                );
            }
            byte[] outputBytes = Files.readAllBytes(output);
            List<String> writtenStatements = SqlScriptParser.statements(
                    new String(outputBytes, StandardCharsets.UTF_8)
            );
            if (writtenStatements.size() != file.statements().size()) {
                throw new IllegalArgumentException(
                        "Cannot create a strict SQL validation plan because the written statement count "
                                + "does not match the migration result: " + output
                );
            }
            List<ValidationPlanStatement> statements = new ArrayList<>();
            for (int index = 0; index < writtenStatements.size(); index++) {
                int statementIndex = index + 1;
                boolean manual = file.manualReviewStatementIndexes().contains(statementIndex);
                statements.add(new ValidationPlanStatement(
                        statementIndex,
                        sha256(writtenStatements.get(index).getBytes(StandardCharsets.UTF_8)),
                        manual ? "MANUAL_REVIEW" : "EXECUTE",
                        manual
                                ? manualReasons.getOrDefault(
                                        reasonKey(file.outputDisplay(), statementIndex),
                                        "迁移阶段已标记为人工确认。"
                                )
                                : ""
                ));
            }
            planFiles.add(new ValidationPlanFile(
                    file.sourceDisplay(),
                    output.toString(),
                    file.schema(),
                    file.systemScript(),
                    sha256(outputBytes),
                    file.appliedRules(),
                    statements
            ));
        }
        SqlScriptValidationPlan plan = new SqlScriptValidationPlan(
                FORMAT_VERSION,
                Instant.now().toString(),
                projectRoot.toAbsolutePath().normalize().toString(),
                sqlRootOut.toAbsolutePath().normalize().toString(),
                capabilities == null ? DamengTargetCapabilities.unknown() : capabilities,
                planFiles
        );
        Path temporary = normalizedPlan.resolveSibling(normalizedPlan.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), plan);
        moveAtomically(temporary, normalizedPlan);
        return normalizedPlan;
    }

    LoadedValidationPlan load(Path planPath) throws IOException {
        Path normalizedPlan = planPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedPlan)) {
            throw new IllegalArgumentException("SQL validation plan does not exist: " + normalizedPlan);
        }
        SqlScriptValidationPlan plan = objectMapper.readValue(
                normalizedPlan.toFile(),
                SqlScriptValidationPlan.class
        );
        if (plan.formatVersion() != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported SQL validation plan format version: " + plan.formatVersion()
            );
        }
        Path outputRoot = Path.of(plan.sqlRootOut()).toAbsolutePath().normalize();
        List<SqlScriptMigrator.PlannedSqlScriptFile> files = new ArrayList<>();
        int manualCount = 0;
        LinkedHashSet<Path> uniqueOutputs = new LinkedHashSet<>();
        for (ValidationPlanFile file : plan.files()) {
            Path output = Path.of(file.outputFile()).toAbsolutePath().normalize();
            if (!output.startsWith(outputRoot)) {
                throw new IllegalArgumentException(
                        "Validation plan output escapes sqlRootOut: " + output
                );
            }
            if (!uniqueOutputs.add(output)) {
                throw new IllegalArgumentException(
                        "Validation plan contains a duplicate output file: " + output
                );
            }
            if (!Files.isRegularFile(output)) {
                throw new IllegalArgumentException("Planned SQL output does not exist: " + output);
            }
            String actualFileHash = sha256(Files.readAllBytes(output));
            if (!actualFileHash.equalsIgnoreCase(file.sha256())) {
                throw new IllegalArgumentException(
                        "Planned SQL output hash does not match; rerun migrate before validation: " + output
                );
            }
            List<String> statements = SqlScriptParser.statements(
                    Files.readString(output, StandardCharsets.UTF_8)
            );
            if (statements.size() != file.statements().size()) {
                throw new IllegalArgumentException(
                        "Planned SQL statement count does not match; rerun migrate before validation: " + output
                );
            }
            LinkedHashSet<Integer> manualIndexes = new LinkedHashSet<>();
            for (int index = 0; index < statements.size(); index++) {
                ValidationPlanStatement plannedStatement = file.statements().get(index);
                int statementIndex = index + 1;
                if (plannedStatement.index() != statementIndex) {
                    throw new IllegalArgumentException(
                            "Validation plan statement indexes are not contiguous for: " + output
                    );
                }
                String actualStatementHash = sha256(
                        statements.get(index).getBytes(StandardCharsets.UTF_8)
                );
                if (!actualStatementHash.equalsIgnoreCase(plannedStatement.sha256())) {
                    throw new IllegalArgumentException(
                            "Planned SQL statement hash does not match at index "
                                    + statementIndex + ": " + output
                    );
                }
                if ("MANUAL_REVIEW".equals(plannedStatement.disposition())) {
                    manualIndexes.add(statementIndex);
                    manualCount++;
                } else if (!"EXECUTE".equals(plannedStatement.disposition())) {
                    throw new IllegalArgumentException(
                            "Unsupported validation disposition "
                                    + plannedStatement.disposition() + " at " + output
                    );
                }
            }
            files.add(new SqlScriptMigrator.PlannedSqlScriptFile(
                    file.sourceFile(),
                    output.toString(),
                    file.schema(),
                    file.systemScript(),
                    true,
                    true,
                    statements.size(),
                    0,
                    manualIndexes.size(),
                    manualIndexes,
                    file.appliedRules(),
                    statements
            ));
        }
        return new LoadedValidationPlan(normalizedPlan, plan, files, manualCount);
    }

    void verifyCapabilities(
            DamengTargetCapabilities expected,
            DamengTargetCapabilities actual,
            boolean containsBackticks
    ) {
        if (actual == null || actual.lengthSemantics() == null) {
            throw new IllegalArgumentException("Could not read target database LENGTH_IN_CHAR.");
        }
        if (expected != null
                && expected.lengthSemantics() != null
                && expected.lengthSemantics() != actual.lengthSemantics()) {
            throw new IllegalArgumentException(
                    "Target database LENGTH_IN_CHAR no longer matches the migration plan."
            );
        }
        if (containsBackticks && !"4".equals(actual.compatibleMode())) {
            throw new IllegalArgumentException(
                    "Planned SQL contains MySQL backtick identifiers but target COMPATIBLE_MODE is "
                            + actual.compatibleMode() + ", expected 4."
            );
        }
        if (expected != null
                && !expected.compatibleMode().isBlank()
                && !expected.compatibleMode().equals(actual.compatibleMode())) {
            throw new IllegalArgumentException(
                    "Target database COMPATIBLE_MODE no longer matches the migration plan."
            );
        }
    }

    boolean containsBackticks(LoadedValidationPlan loadedPlan) {
        for (SqlScriptMigrator.PlannedSqlScriptFile file : loadedPlan.files()) {
            if (file.statements().stream().anyMatch(statement -> statement.indexOf('`') >= 0)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> manualReasons(List<SqlScriptManualReviewItem> items) {
        LinkedHashMap<String, String> reasons = new LinkedHashMap<>();
        for (SqlScriptManualReviewItem item : items == null ? List.<SqlScriptManualReviewItem>of() : items) {
            reasons.putIfAbsent(reasonKey(item.outputFile(), item.statementIndex()), item.reason());
        }
        return reasons;
    }

    private String reasonKey(String outputFile, int statementIndex) {
        return Path.of(outputFile).toAbsolutePath().normalize() + "#" + statementIndex;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record LoadedValidationPlan(
            Path path,
            SqlScriptValidationPlan plan,
            List<SqlScriptMigrator.PlannedSqlScriptFile> files,
            int manualReviewCount
    ) {
        LoadedValidationPlan {
            files = List.copyOf(files == null ? List.of() : files);
        }
    }

    record SqlScriptValidationPlan(
            int formatVersion,
            String generatedAt,
            String projectRoot,
            String sqlRootOut,
            DamengTargetCapabilities targetCapabilities,
            List<ValidationPlanFile> files
    ) {
        SqlScriptValidationPlan {
            files = List.copyOf(files == null ? List.of() : files);
        }
    }

    record ValidationPlanFile(
            String sourceFile,
            String outputFile,
            String schema,
            boolean systemScript,
            String sha256,
            List<String> appliedRules,
            List<ValidationPlanStatement> statements
    ) {
        ValidationPlanFile {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
            statements = List.copyOf(statements == null ? List.of() : statements);
        }
    }

    record ValidationPlanStatement(
            int index,
            String sha256,
            String disposition,
            String reason
    ) {
    }
}
