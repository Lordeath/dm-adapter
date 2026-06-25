package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.DependencyCoordinate;
import com.github.dmadapter.core.DmAdapterException;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.maven.PomModifier;
import com.github.dmadapter.maven.PomTargetSelection;
import com.github.dmadapter.maven.PomTargetSelector;
import com.github.dmadapter.mybatis.MapperMigrator;
import com.github.dmadapter.mybatis.SqlRewriteConfig;
import com.github.dmadapter.mybatis.SqlRewriteConfigLoader;
import com.github.dmadapter.report.ReportPaths;
import com.github.dmadapter.report.ReportWriter;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Command(name = "migrate", description = "Create a low-intrusion Dameng migration plan or apply it.")
public class MigrateCommand implements Callable<Integer> {
    @Option(names = "--project", required = true, description = "Project root path.")
    private Path project;

    @Option(names = "--source-db", defaultValue = AdapterContext.DEFAULT_SOURCE_DB, description = "Source database. MVP supports mysql.")
    private String sourceDb;

    @Option(names = "--target-db", defaultValue = AdapterContext.DEFAULT_TARGET_DB, description = "Target database. MVP supports dm.")
    private String targetDb;

    @Option(names = "--dry-run", description = "Only generate a migration report without changing project files.")
    private boolean dryRun;

    @Option(names = "--dm-driver", description = "Dameng JDBC dependency coordinate: groupId:artifactId:version.")
    private String dmDriver;

    @Option(names = "--report-dir", description = "Report output directory. Defaults to <project>/.dm-adapter.")
    private Path reportDir;

    @Option(names = "--mapper-dir", description = "Target mapper directory. Defaults to src/main/resources/mapper-dm.")
    private Path mapperDir;

    @Option(names = "--rewrite-config", description = "SQL rewrite config path. Defaults to <project>/.dm-adapter/sql-rewrite.yml.")
    private Path rewriteConfig;

    @Option(names = "--generate-validation-test", description = "Generate the Dameng SQL validation test after migration.")
    private boolean generateValidationTest;

    @Option(names = "--app-module", description = "Application module path or Maven artifactId used for generated validation test placement; implies --generate-validation-test.")
    private Path appModule;

    @Option(names = "--config", description = "Validation config path; implies --generate-validation-test. Defaults to <project>/.dm-adapter/sql-validation.yml.")
    private Path config;

    @Option(names = "--schema", description = "Dameng schema to set before invoking mapper methods in the generated validation test; supports comma-separated fallback schemas; implies --generate-validation-test.")
    private String schema;

    private final ProjectScanner projectScanner = new ProjectScanner();
    private final PomModifier pomModifier = new PomModifier();
    private final PomTargetSelector pomTargetSelector = new PomTargetSelector();
    private final MapperMigrator mapperMigrator = new MapperMigrator();
    private final SqlRewriteConfigLoader sqlRewriteConfigLoader = new SqlRewriteConfigLoader();
    private final SqlRewriteConfigUpdater sqlRewriteConfigUpdater = new SqlRewriteConfigUpdater();
    private final DamengMetadataReader damengMetadataReader = new DamengMetadataReader();
    private final ReportWriter reportWriter = new ReportWriter();
    private final DmSqlValidationTestGenerator validationTestGenerator = new DmSqlValidationTestGenerator();
    private final ValidationTestRunner validationTestRunner = new ValidationTestRunner();

    @Override
    public Integer call() {
        try {
            AdapterContext context = buildContext();
            validateSupportedDatabases(context);
            validateValidationTestGeneration(context);
            ProjectScanResult scanResult = projectScanner.scan(context);

            List<FileChange> fileChanges = new ArrayList<>();
            List<String> warnings = new ArrayList<>(scanResult.warnings());
            if (!scanResult.mavenProject()) {
                warnings.add("Migration was not applied because the project root does not contain pom.xml.");
                writeReport(context, scanResult, fileChanges, List.of(), List.of(), warnings);
                return 2;
            }

            if (!scanResult.springBootProject()) {
                warnings.add("Spring Boot dependency was not detected; generated configuration may need manual integration.");
            }
            if (!scanResult.myBatisProject()) {
                warnings.add("MyBatis XML mapper usage was not fully detected; mapper migration may be incomplete.");
            }

            PomTargetSelection pomTargetSelection = pomTargetSelector.select(context.projectRoot(), scanResult.mapperXmlFiles());
            warnings.addAll(pomTargetSelection.warnings());
            if (pomTargetSelection.pomPaths().isEmpty()) {
                warnings.add("No pom.xml target was found for adding Dameng JDBC driver dependency.");
            }
            for (Path pomPath : pomTargetSelection.pomPaths()) {
                Optional<FileChange> pomChange = pomModifier.ensureDependency(
                        pomPath,
                        context.dmDriverCoordinate(),
                        context.dryRun()
                );
                pomChange.ifPresent(fileChanges::add);
            }

            Path rewriteConfigPath = rewriteConfigPath(context);
            SqlRewriteConfig loadedRewriteConfig = sqlRewriteConfigLoader.load(rewriteConfigPath);
            MySqlToDmSqlConverter sqlConverter = new MySqlToDmSqlConverter();
            MapperMigrationResult previewMigrationResult = mapperMigrator.migrate(
                    scanResult,
                    previewContext(context),
                    sqlConverter,
                    loadedRewriteConfig
            );
            List<RewriteConfigCandidate> rewriteConfigCandidates = rewriteConfigCandidates(previewMigrationResult);
            MetadataLookupResult metadataLookupResult = metadataForRewriteCandidates(context, rewriteConfigCandidates);
            warnings.addAll(metadataLookupResult.warnings());
            SqlRewriteConfigUpdate rewriteConfigUpdate = sqlRewriteConfigUpdater.update(
                    context,
                    rewriteConfigPath,
                    loadedRewriteConfig,
                    rewriteConfigCandidates,
                    metadataLookupResult.metadataByTable(),
                    metadataLookupResult.available()
            );
            rewriteConfigUpdate.fileChange().ifPresent(fileChanges::add);
            warnings.addAll(rewriteConfigUpdate.warnings());

            MapperMigrationResult mapperMigrationResult = mapperMigrator.migrate(
                    scanResult,
                    context,
                    sqlConverter,
                    rewriteConfigUpdate.rewriteConfig()
            );
            fileChanges.addAll(mapperMigrationResult.fileChanges());
            warnings.addAll(mapperMigrationResult.warnings());
            if (hasAesBase64Conversion(mapperMigrationResult)) {
                warnings.addAll(aesBase64ConversionWarnings());
            }

            ReportPaths reportPaths = writeReport(
                    context,
                    scanResult,
                    fileChanges,
                    mapperMigrationResult.automaticConversions(),
                    mapperMigrationResult.manualReviewItems(),
                    warnings
            );
            printMigrationSummary(context, scanResult, mapperMigrationResult, fileChanges, warnings, reportPaths);
            if (validationTestGenerationRequested()) {
                ValidationTestGenerationResult validationResult = validationTestGenerator.generate(
                        context.projectRoot(),
                        appModule,
                        mapperDir,
                        config,
                        schema
                );
                GenerateValidationTestCommand.printResult(validationResult);
                GenerateValidationTestCommand.printValidationRunResult(
                        validationTestRunner.runIfConfigured(validationResult, DmValidationEnvironment.fromSystem())
                );
            }
            return 0;
        } catch (Exception e) {
            CliLogger.error("Migration failed: " + e.getMessage());
            return 1;
        }
    }

    private AdapterContext buildContext() {
        return AdapterContext.builder(project)
                .sourceDb(sourceDb)
                .targetDb(targetDb)
                .dryRun(dryRun)
                .dmDriverCoordinate(DependencyCoordinate.parse(dmDriver))
                .reportDir(reportDir)
                .mapperTargetDir(mapperDir)
                .build();
    }

    private void validateSupportedDatabases(AdapterContext context) {
        if (!"mysql".equals(context.sourceDb())) {
            throw new DmAdapterException("MVP only supports --source-db mysql.");
        }
        if (!"dm".equals(context.targetDb())) {
            throw new DmAdapterException("MVP only supports --target-db dm.");
        }
    }

    private void validateValidationTestGeneration(AdapterContext context) {
        if (validationTestGenerationRequested() && context.dryRun()) {
            throw new DmAdapterException("Validation test generation cannot be used with --dry-run because it writes files.");
        }
    }

    private boolean validationTestGenerationRequested() {
        return generateValidationTest || appModule != null || config != null || (schema != null && !schema.isBlank());
    }

    private Path rewriteConfigPath(AdapterContext context) {
        return rewriteConfig == null
                ? context.projectRoot().resolve(".dm-adapter/sql-rewrite.yml")
                : (rewriteConfig.isAbsolute()
                        ? rewriteConfig.toAbsolutePath().normalize()
                        : context.projectRoot().resolve(rewriteConfig).toAbsolutePath().normalize());
    }

    private List<RewriteConfigCandidate> rewriteConfigCandidates(MapperMigrationResult mapperMigrationResult) {
        List<RewriteConfigCandidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (com.github.dmadapter.core.SqlChange sqlChange : mapperMigrationResult.manualReviewItems()) {
            String sql = sqlChange.originalSql() == null ? "" : sqlChange.originalSql();
            String lower = sql.toLowerCase();
            String reason = sqlChange.reason() == null ? "" : sqlChange.reason().toLowerCase();
            boolean needsKeyColumns = lower.contains("on duplicate key update")
                    || lower.contains("insert ignore")
                    || reason.contains("on duplicate key update requires configured keycolumns")
                    || reason.contains("insert ignore requires configured keycolumns");
            if (!needsKeyColumns) {
                continue;
            }
            String tableName = extractInsertTableName(sql);
            if (tableName.isBlank()) {
                continue;
            }
            String methodKey = sqlChange.statementId();
            if (methodKey == null || methodKey.isBlank() || methodKey.startsWith("(")) {
                continue;
            }
            String key = methodKey + "|" + DamengMetadataReader.normalizeTableName(tableName);
            if (seen.add(key)) {
                candidates.add(new RewriteConfigCandidate(methodKey, tableName, extractInsertColumns(sql, tableName)));
            }
        }
        return candidates;
    }

    private String extractInsertTableName(String sql) {
        Matcher matcher = Pattern.compile(
                "(?is)\\binsert\\s+(?:ignore\\s+)?into\\s+([^\\s(<]+)"
        ).matcher(sql == null ? "" : sql);
        return matcher.find()
                ? matcher.group(1).replace("`", "").replace("\"", "").trim()
                : "";
    }

    private List<String> extractInsertColumns(String sql, String tableName) {
        if (sql == null || sql.isBlank() || tableName == null || tableName.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile(
                "(?is)\\binsert\\s+(?:ignore\\s+)?into\\s+"
                        + Pattern.quote(tableName)
                        + "\\s*(?<tail>[\\s\\S]*)"
        ).matcher(sql);
        if (!matcher.find()) {
            return List.of();
        }
        String tail = matcher.group("tail");
        String columnList = parenthesizedColumnList(tail).orElseGet(() -> looseColumnList(tail));
        if (columnList.isBlank()) {
            return List.of();
        }
        return splitColumns(columnList);
    }

    private Optional<String> parenthesizedColumnList(String text) {
        int open = text.indexOf('(');
        if (open < 0) {
            return Optional.empty();
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return Optional.of(text.substring(open + 1, i));
                }
            }
        }
        return Optional.empty();
    }

    private String looseColumnList(String text) {
        Matcher valuesMatcher = Pattern.compile("(?is)\\bvalues\\b").matcher(text);
        if (!valuesMatcher.find()) {
            return "";
        }
        return text.substring(0, valuesMatcher.start());
    }

    private List<String> splitColumns(String columnList) {
        List<String> columns = new ArrayList<>();
        for (String part : columnList.split(",")) {
            String column = part.replace("`", "").replace("\"", "").trim();
            if (column.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                columns.add(column);
            }
        }
        return columns;
    }

    private AdapterContext previewContext(AdapterContext context) {
        return AdapterContext.builder(context.projectRoot())
                .sourceDb(context.sourceDb())
                .targetDb(context.targetDb())
                .dryRun(true)
                .dmDriverCoordinate(context.dmDriverCoordinate())
                .reportDir(context.reportDir())
                .mapperTargetDir(context.mapperTargetDir())
                .build();
    }

    private MetadataLookupResult metadataForRewriteCandidates(
            AdapterContext context,
            List<RewriteConfigCandidate> candidates
    ) {
        if (candidates.isEmpty()) {
            return new MetadataLookupResult(Map.of(), false, List.of());
        }
        DmValidationEnvironment environment = DmValidationEnvironment.fromSystem();
        if (!environment.validationEnabled()) {
            return new MetadataLookupResult(Map.of(), false, List.of());
        }
        if (!environment.ready()) {
            return new MetadataLookupResult(
                    Map.of(),
                    false,
                    List.of("DM_SQL_VALIDATION is true but metadata inference was skipped because required variables are missing: "
                            + environment.missingVariables())
            );
        }
        try {
            Optional<String> configuredSchema = configuredSchema(context);
            Map<String, TableKeyMetadata> metadata = damengMetadataReader.readTableKeys(
                    environment,
                    configuredSchema,
                    candidates.stream().map(RewriteConfigCandidate::tableName).distinct().toList()
            );
            return new MetadataLookupResult(metadata, true, List.of());
        } catch (Exception e) {
            return new MetadataLookupResult(
                    Map.of(),
                    false,
                    List.of("Dameng metadata inference was skipped: " + redact(e.getMessage(), environment))
            );
        }
    }

    private Optional<String> configuredSchema(AdapterContext context) {
        if (schema != null && !schema.isBlank()) {
            return Optional.of(schema.trim());
        }
        return DmValidationConfigReader.schema(context.projectRoot(), config);
    }

    private String redact(String message, DmValidationEnvironment environment) {
        String redacted = message == null ? "" : message;
        redacted = redactValue(redacted, environment.jdbcUrl());
        redacted = redactValue(redacted, environment.username());
        redacted = redactValue(redacted, environment.password());
        return redacted;
    }

    private String redactValue(String message, String value) {
        if (value == null || value.isBlank()) {
            return message;
        }
        return message.replace(value, "******");
    }

    private ReportPaths writeReport(
            AdapterContext context,
            ProjectScanResult scanResult,
            List<FileChange> fileChanges,
            List<com.github.dmadapter.core.SqlChange> automaticConversions,
            List<com.github.dmadapter.core.SqlChange> manualReviewItems,
            List<String> warnings
    ) throws Exception {
        MigrationReport report = new MigrationReport(
                context.projectRoot().toString(),
                context.sourceDb(),
                context.targetDb(),
                context.dryRun(),
                scanResult,
                fileChanges,
                automaticConversions,
                manualReviewItems,
                warnings
        );
        return reportWriter.writeMigrationReport(report, context.reportDir());
    }

    private boolean hasAesBase64Conversion(MapperMigrationResult mapperMigrationResult) {
        return mapperMigrationResult.automaticConversions().stream()
                .flatMap(sqlChange -> sqlChange.appliedRules().stream())
                .anyMatch(MySqlToDmSqlConverter.MYSQL_AES_BASE64_TO_DM_AES128_ECB_RULE::equals);
    }

    private List<String> aesBase64ConversionWarnings() {
        return List.of(
                "AES password SQL was rewritten to Dameng AES128_ECB with cipher ID 513. Verify the target database with: SELECT CYT_ID, CYT_NAME FROM V$CIPHERS WHERE CYT_NAME = 'AES128_ECB';",
                "AES128_ECB rewrite keeps existing SQL-layer encryption behavior for compatibility; it is not a password-storage security upgrade, and old MySQL ciphertext is not guaranteed to decrypt on Dameng.",
                "Legacy password handling template (manual, not executed): UPDATE user_table SET user_password = TO_BASE64(SF_ENCRYPT_CHAR('RESET_REQUIRED', 513, '<AES_KEY>', NULL)) WHERE user_password IS NOT NULL;"
        );
    }

    private void printMigrationSummary(
            AdapterContext context,
            ProjectScanResult scanResult,
            MapperMigrationResult mapperMigrationResult,
            List<FileChange> fileChanges,
            List<String> warnings,
            ReportPaths reportPaths
    ) {
        CliLogger.info("Migration " + (context.dryRun() ? "dry-run" : "completed") + ".");
        CliLogger.info("Maven project: " + scanResult.mavenProject());
        CliLogger.info("Mapper XML files: " + scanResult.mapperXmlFiles().size());
        CliLogger.info("File changes: " + fileChanges.size());
        CliLogger.info("Automatic SQL conversions: " + mapperMigrationResult.automaticConversions().size());
        CliLogger.info("Manual review SQL items: " + mapperMigrationResult.manualReviewItems().size());
        CliLogger.info("Report: " + reportPaths.markdownPath());
        if (!warnings.isEmpty()) {
            CliLogger.info("Warnings:");
            warnings.forEach(warning -> CliLogger.info("- " + warning));
        }
    }

    private record MetadataLookupResult(
            Map<String, TableKeyMetadata> metadataByTable,
            boolean available,
            List<String> warnings
    ) {
        private MetadataLookupResult {
            metadataByTable = Map.copyOf(metadataByTable == null ? Map.of() : metadataByTable);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }
}
