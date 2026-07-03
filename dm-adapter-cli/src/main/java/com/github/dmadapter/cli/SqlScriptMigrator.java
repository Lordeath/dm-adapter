package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlConversionResult;
import com.github.dmadapter.core.SqlScriptFileResult;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptValidationFailure;
import com.github.dmadapter.sql.SqlConverter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class SqlScriptMigrator {
    static final String MYSQL_CREATE_DEFINER_REMOVAL_RULE = "MYSQL_CREATE_DEFINER_REMOVED";

    private static final Pattern CREATE_DEFINER_PATTERN = Pattern.compile(
            "(?is)^\\s*CREATE\\s+DEFINER\\s*=\\s*(?:`[^`]+`@`[^`]+`|'[^']+'@'[^']+'|\\S+)\\s+"
    );
    private static final Map<Pattern, String> MANUAL_REVIEW_PATTERNS = Map.of(
            Pattern.compile("(?is)\\bDECLARE\\s+.+?\\s+HANDLER\\s+FOR\\b"),
            "MySQL procedure HANDLER syntax needs manual confirmation for Dameng.",
            Pattern.compile("(?is)\\bSIGNAL\\s+SQLSTATE\\b"),
            "MySQL SIGNAL SQLSTATE handling needs manual confirmation for Dameng.",
            Pattern.compile("(?is)\\bPREPARE\\s+\\w+\\s+FROM\\b|\\bEXECUTE\\s+\\w+\\b|\\bDEALLOCATE\\s+PREPARE\\b"),
            "MySQL dynamic SQL in procedures needs manual confirmation for Dameng.",
            Pattern.compile("(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?TRIGGER\\b"),
            "Trigger syntax differs between MySQL and Dameng and needs manual confirmation."
    );

    private final SqlConverter converter;
    private final Validator validator;

    SqlScriptMigrator(SqlConverter converter, Validator validator) {
        this.converter = converter;
        this.validator = validator;
    }

    SqlScriptMigrationReport migrate(SqlScriptMigrationRequest request) throws IOException {
        Path projectRoot = request.projectRoot().toAbsolutePath().normalize();
        Path sqlRoot = resolvePath(projectRoot, request.sqlRoot());
        Path sqlRootOut = resolvePath(projectRoot, request.sqlRootOut());
        if (!Files.isDirectory(sqlRoot)) {
            throw new IllegalStateException("SQL script root does not exist or is not a directory: " + sqlRoot);
        }
        if (sqlRoot.equals(sqlRootOut)) {
            throw new IllegalStateException("--sql-root-out must be different from --sql-root.");
        }

        List<Path> sqlFiles = sqlFiles(sqlRoot);
        List<String> warnings = new ArrayList<>();
        String schema = primarySchema(request.schema(), "--schema", warnings);
        String systemSchema = primarySchema(request.systemSchema(), "--system-schema", warnings);
        List<SqlScriptManualReviewItem> manualReviewItems = new ArrayList<>();
        List<PlannedSqlScriptFile> plannedFiles = new ArrayList<>();
        int convertedFileCount = 0;

        for (Path sqlFile : sqlFiles) {
            PlannedSqlScriptFile plannedFile = planFile(
                    sqlRoot,
                    sqlRootOut,
                    sqlFile,
                    schema,
                    systemSchema,
                    request.dryRun(),
                    manualReviewItems,
                    warnings
            );
            plannedFiles.add(plannedFile);
            if (plannedFile.converted()) {
                convertedFileCount++;
            }
        }

        SqlScriptValidationRun validationRun = request.dryRun()
                ? SqlScriptValidationRun.notAttempted("Dry run; SQL script validation skipped.", List.of())
                : validator.validate(plannedFiles, request.validationEnvironment());
        warnings.addAll(validationRun.warnings());

        Function<String, SqlScriptFileValidation> validationByOutput = output -> validationRun.fileValidations().stream()
                .filter(validation -> validation.outputFile().equals(output))
                .findFirst()
                .orElse(new SqlScriptFileValidation(output, 0, List.of()));
        List<SqlScriptFileResult> fileResults = plannedFiles.stream()
                .map(file -> {
                    SqlScriptFileValidation validation = validationByOutput.apply(file.outputDisplay());
                    return new SqlScriptFileResult(
                            file.sourceDisplay(),
                            file.outputDisplay(),
                            file.schema(),
                            file.systemScript(),
                            file.written(),
                            file.converted(),
                            file.originalStatementCount(),
                            file.convertedStatementCount(),
                            file.manualReviewStatementCount(),
                            validation.successCount(),
                            validation.failureCount(),
                            file.appliedRules()
                    );
                })
                .toList();

        return new SqlScriptMigrationReport(
                projectRoot.toString(),
                sqlRoot.toString(),
                sqlRootOut.toString(),
                request.dryRun(),
                sqlFiles.size(),
                convertedFileCount,
                manualReviewItems.size(),
                validationRun.attempted(),
                validationRun.status(),
                validationRun.successCount(),
                validationRun.failureCount(),
                fileResults,
                manualReviewItems,
                validationRun.failures(),
                warnings
        );
    }

    private PlannedSqlScriptFile planFile(
            Path sqlRoot,
            Path sqlRootOut,
            Path sqlFile,
            String schema,
            String systemSchema,
            boolean dryRun,
            List<SqlScriptManualReviewItem> manualReviewItems,
            List<String> warnings
    ) throws IOException {
        Path relative = sqlRoot.relativize(sqlFile);
        Path outputFile = sqlRootOut.resolve(relative).normalize();
        boolean systemScript = isSystemScript(sqlFile);
        String targetSchema = systemScript ? systemSchema : schema;
        if (systemScript && targetSchema.isBlank()) {
            warnings.add("System SQL script has no --system-schema and will use the current connection schema: " + relative);
        }

        String originalContent = Files.readString(sqlFile, StandardCharsets.UTF_8);
        List<String> originalStatements = SqlScriptParser.statements(originalContent);
        List<String> convertedStatements = new ArrayList<>();
        LinkedHashSet<String> appliedRules = new LinkedHashSet<>();
        int convertedStatementCount = 0;
        int manualReviewStatementCount = 0;

        for (int i = 0; i < originalStatements.size(); i++) {
            String originalStatement = originalStatements.get(i);
            ScriptStatementConversion conversion = convertStatement(originalStatement);
            if (conversion.manualReviewRequired()) {
                manualReviewStatementCount++;
                manualReviewItems.add(new SqlScriptManualReviewItem(
                        relative.toString(),
                        outputFile.toString(),
                        i + 1,
                        conversion.reason(),
                        originalStatement,
                        conversion.convertedSql()
                ));
            }
            if (conversion.changed() && !conversion.manualReviewRequired()) {
                convertedStatementCount++;
                appliedRules.addAll(conversion.appliedRules());
            }
            convertedStatements.add(conversion.outputSql());
        }

        String convertedContent = originalStatements.isEmpty()
                ? originalContent
                : SqlScriptParser.scriptContent(convertedStatements);
        boolean converted = !convertedContent.equals(originalContent);
        boolean written = false;
        if (!dryRun) {
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, convertedContent, StandardCharsets.UTF_8);
            written = true;
        }

        return new PlannedSqlScriptFile(
                relative.toString(),
                outputFile.toString(),
                targetSchema,
                systemScript,
                written,
                converted,
                originalStatements.size(),
                convertedStatementCount,
                manualReviewStatementCount,
                List.copyOf(appliedRules),
                convertedStatements
        );
    }

    private ScriptStatementConversion convertStatement(String originalStatement) {
        SafeRuleConversion safeRuleConversion = applyScriptSafeRules(originalStatement);
        SqlConversionResult sqlConversion = converter.convert(safeRuleConversion.sql());
        List<String> rules = new ArrayList<>(safeRuleConversion.appliedRules());
        rules.addAll(sqlConversion.appliedRules());
        String convertedSql = sqlConversion.convertedSql();
        boolean changed = safeRuleConversion.changed() || sqlConversion.changed();
        String manualReason = manualReviewReason(convertedSql);
        if (sqlConversion.manualReviewRequired()) {
            manualReason = sqlConversion.reason();
        }
        if (!manualReason.isBlank()) {
            return new ScriptStatementConversion(
                    originalStatement,
                    convertedSql,
                    originalStatement,
                    changed,
                    true,
                    manualReason,
                    rules
            );
        }
        return new ScriptStatementConversion(
                originalStatement,
                convertedSql,
                convertedSql,
                changed,
                false,
                "",
                rules
        );
    }

    private SafeRuleConversion applyScriptSafeRules(String sql) {
        if (sql == null || sql.isBlank()) {
            return new SafeRuleConversion(sql == null ? "" : sql, false, List.of());
        }
        String converted = CREATE_DEFINER_PATTERN.matcher(sql).replaceFirst("CREATE ");
        if (converted.equals(sql)) {
            return new SafeRuleConversion(sql, false, List.of());
        }
        return new SafeRuleConversion(converted, true, List.of(MYSQL_CREATE_DEFINER_REMOVAL_RULE));
    }

    private String manualReviewReason(String sql) {
        for (Map.Entry<Pattern, String> entry : MANUAL_REVIEW_PATTERNS.entrySet()) {
            if (entry.getKey().matcher(sql).find()) {
                return entry.getValue();
            }
        }
        return "";
    }

    private Path resolvePath(Path projectRoot, Path path) {
        return path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : projectRoot.resolve(path).toAbsolutePath().normalize();
    }

    private List<Path> sqlFiles(Path sqlRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(sqlRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    private boolean isSystemScript(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith("_system.sql");
    }

    private String primarySchema(String value, String option, List<String> warnings) {
        List<String> schemas = DamengMetadataReader.splitSchemaList(value);
        if (schemas.isEmpty()) {
            return "";
        }
        if (schemas.size() > 1) {
            warnings.add("SQL script validation uses the first schema from " + option + ": " + schemas.get(0));
        }
        return schemas.get(0);
    }

    interface Validator {
        SqlScriptValidationRun validate(
                List<PlannedSqlScriptFile> files,
                DmValidationEnvironment environment
        );
    }

    record PlannedSqlScriptFile(
            String sourceDisplay,
            String outputDisplay,
            String schema,
            boolean systemScript,
            boolean written,
            boolean converted,
            int originalStatementCount,
            int convertedStatementCount,
            int manualReviewStatementCount,
            List<String> appliedRules,
            List<String> statements
    ) {
        PlannedSqlScriptFile {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
            statements = List.copyOf(statements == null ? List.of() : statements);
        }
    }

    private record SafeRuleConversion(String sql, boolean changed, List<String> appliedRules) {
        private SafeRuleConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }
    }

    private record ScriptStatementConversion(
            String originalSql,
            String convertedSql,
            String outputSql,
            boolean changed,
            boolean manualReviewRequired,
            String reason,
            List<String> appliedRules
    ) {
        private ScriptStatementConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }
    }
}
