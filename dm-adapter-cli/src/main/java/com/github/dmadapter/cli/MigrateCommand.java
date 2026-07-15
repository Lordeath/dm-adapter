package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.DependencyCoordinate;
import com.github.dmadapter.core.DmAdapterException;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.maven.PomModifier;
import com.github.dmadapter.maven.PomTargetSelection;
import com.github.dmadapter.maven.PomTargetSelector;
import com.github.dmadapter.mybatis.MapperAnnotationMigrator;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Command(name = "migrate", description = "Create a low-intrusion Dameng migration plan or apply it.")
public class MigrateCommand implements Callable<Integer> {
    private static final long DEFAULT_METADATA_READ_TIMEOUT_SECONDS = 12L;

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

    @Option(names = "--report-dir", description = "dm-adapter workspace directory. Defaults to <cwd>/.dm-adapter/<app-artifactId>.")
    private Path reportDir;

    @Option(names = "--mapper-dir", description = "Target mapper directory. Defaults to src/main/resources/mapper-dm.")
    private Path mapperDir;

    @Option(names = "--rewrite-config", description = "SQL rewrite config path. Defaults to <workspace>/sql-rewrite.yml.")
    private Path rewriteConfig;

    @Option(names = "--generate-validation-test", description = "Generate the Dameng SQL validation test after migration.")
    private boolean generateValidationTest;

    @Option(names = "--app-module", description = "Application module path or Maven artifactId used for generated validation test placement; implies --generate-validation-test.")
    private Path appModule;

    @Option(names = "--config", description = "Validation config path; implies --generate-validation-test. Defaults to <workspace>/sql-validation.yml.")
    private Path config;

    @Option(names = "--schema", description = "Dameng schema to set before invoking mapper methods in the generated validation test; supports comma-separated fallback schemas; implies --generate-validation-test.")
    private String schema;

    @Option(names = "--sql-root", description = "Root directory of MySQL SQL scripts or stored procedure scripts to migrate.")
    private Path sqlRoot;

    @Option(names = "--sql-root-out", description = "Output directory for converted Dameng SQL scripts.")
    private Path sqlRootOut;

    @Option(names = {"--system-schema", "--system_schema"}, description = "Dameng schema for validating *_system.sql scripts.")
    private String systemSchema;

    private final ProjectScanner projectScanner = new ProjectScanner();
    private final PomModifier pomModifier = new PomModifier();
    private final PomTargetSelector pomTargetSelector = new PomTargetSelector();
    private final MapperMigrator mapperMigrator = new MapperMigrator(
            message -> CliLogger.info("[mapper] " + message)
    );
    private final MapperAnnotationMigrator mapperAnnotationMigrator = new MapperAnnotationMigrator(
            message -> CliLogger.info("[annotation] " + message)
    );
    private final MapperJavaParamFixer mapperJavaParamFixer = new MapperJavaParamFixer();
    private final SqlRewriteConfigLoader sqlRewriteConfigLoader = new SqlRewriteConfigLoader();
    private final SqlRewriteConfigUpdater sqlRewriteConfigUpdater = new SqlRewriteConfigUpdater();
    private final DamengMetadataReader damengMetadataReader = new DamengMetadataReader();
    private final ProjectDdlKeyMetadataReader projectDdlKeyMetadataReader = new ProjectDdlKeyMetadataReader();
    private final MapperJdbcTypeAligner mapperJdbcTypeAligner = new MapperJdbcTypeAligner();
    private final MavenCompilePreparer mavenCompilePreparer = new MavenCompilePreparer();
    private final ReportWriter reportWriter = new ReportWriter();
    private final DmSqlValidationTestGenerator validationTestGenerator = new DmSqlValidationTestGenerator();
    private final ValidationTestRunner validationTestRunner = new ValidationTestRunner();
    private final SqlScriptMigrator sqlScriptMigrator = new SqlScriptMigrator(
            new MySqlToDmSqlConverter(),
            SqlScriptValidator.withProgress(message -> CliLogger.info("[sql-script] " + message)),
            message -> CliLogger.info("[sql-script] " + message)
    );
    private final AdapterWorkspaceResolver workspaceResolver = new AdapterWorkspaceResolver();
    private final LegacyWorkspaceMigrator legacyWorkspaceMigrator = new LegacyWorkspaceMigrator();

    @Override
    public Integer call() {
        try {
            AdapterContext context = buildContext();
            CliLogger.info("Migration started. Project: " + context.projectRoot());
            CliLogger.info("dm-adapter workspace: " + context.reportDir());
            validateSupportedDatabases(context);
            validateValidationTestGeneration(context);
            validateSqlScriptMigrationOptions();
            List<String> workspaceWarnings = legacyWorkspaceMigrator.migrateDefaults(
                    context.projectRoot(),
                    context.reportDir(),
                    rewriteConfig == null,
                    config == null
            );
            CliLogger.info("Scanning project...");
            ProjectScanResult scanResult = projectScanner.scan(context);
            CliLogger.info("Project scan completed. Maven project: " + scanResult.mavenProject()
                    + ", mapper XML files: " + scanResult.mapperXmlFiles().size());

            List<FileChange> fileChanges = new ArrayList<>();
            List<String> warnings = new ArrayList<>(scanResult.warnings());
            warnings.addAll(workspaceWarnings);
            if (!scanResult.mavenProject()) {
                warnings.add("Migration was not applied because the project root does not contain pom.xml.");
                CliLogger.info("Writing migration report...");
                writeReport(context, scanResult, fileChanges, List.of(), List.of(), warnings);
                return 2;
            }

            if (!scanResult.springBootProject()) {
                warnings.add("Spring Boot dependency was not detected; generated configuration may need manual integration.");
            }
            if (!scanResult.myBatisProject()) {
                warnings.add("MyBatis XML mapper usage was not fully detected; mapper migration may be incomplete.");
            }

            CliLogger.info("Selecting Maven POM targets...");
            PomTargetSelection pomTargetSelection = pomTargetSelector.select(context.projectRoot(), scanResult.mapperXmlFiles());
            CliLogger.info("Maven POM target selection completed. Target pom.xml files: "
                    + pomTargetSelection.pomPaths().size());
            warnings.addAll(pomTargetSelection.warnings());
            if (pomTargetSelection.pomPaths().isEmpty()) {
                warnings.add("No pom.xml target was found for adding Dameng JDBC driver dependency.");
            }
            for (Path pomPath : pomTargetSelection.pomPaths()) {
                CliLogger.info("Ensuring Dameng JDBC dependency in " + pomPath + "...");
                Optional<FileChange> pomChange = pomModifier.ensureDependency(
                        pomPath,
                        context.dmDriverCoordinate(),
                        context.dryRun()
                );
                pomChange.ifPresent(fileChanges::add);
            }

            Path rewriteConfigPath = rewriteConfigPath(context);
            CliLogger.info("Loading SQL rewrite config: " + rewriteConfigPath);
            SqlRewriteConfig loadedRewriteConfig = sqlRewriteConfigLoader.load(rewriteConfigPath);
            MySqlToDmSqlConverter sqlConverter = new MySqlToDmSqlConverter();
            CliLogger.info("Previewing mapper XML migration for rewrite candidates...");
            MapperMigrationResult previewMigrationResult = mapperMigrator.migrate(
                    scanResult,
                    previewContext(context),
                    sqlConverter,
                    loadedRewriteConfig
            );
            CliLogger.info("Preview mapper XML migration completed. " + migrationResultSummary(previewMigrationResult));
            List<RewriteConfigCandidate> rewriteConfigCandidates = rewriteConfigCandidates(previewMigrationResult);
            CliLogger.info("Rewrite config metadata candidates: " + rewriteConfigCandidates.size());
            if (!rewriteConfigCandidates.isEmpty()) {
                CliLogger.info("Resolving metadata for rewrite config candidates...");
            }
            MetadataLookupResult metadataLookupResult = metadataForRewriteCandidates(context, rewriteConfigCandidates);
            if (!rewriteConfigCandidates.isEmpty()) {
                CliLogger.info("Metadata resolution completed. Available: " + metadataLookupResult.available()
                        + ", tables: " + metadataLookupResult.metadataByTable().size());
            }
            warnings.addAll(metadataLookupResult.warnings());
            CliLogger.info("Updating SQL rewrite config...");
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

            CliLogger.info("Migrating mapper XML files...");
            MapperMigrationResult mapperMigrationResult = mapperMigrator.migrate(
                    scanResult,
                    context,
                    sqlConverter,
                    rewriteConfigUpdate.rewriteConfig()
            );
            CliLogger.info("Mapper XML migration completed. " + migrationResultSummary(mapperMigrationResult));
            fileChanges.addAll(mapperMigrationResult.fileChanges());
            warnings.addAll(mapperMigrationResult.warnings());
            if (shouldPrepareAnnotationClassScan(context)) {
                CliLogger.info("Preparing annotation SQL class scan with Maven compile...");
                warnings.addAll(mavenCompilePreparer.prepare(context.projectRoot(), appModule));
                CliLogger.info("Maven compile preparation completed.");
            }
            CliLogger.info("Migrating annotation SQL...");
            MapperMigrationResult annotationMigrationResult = mapperAnnotationMigrator.migrate(
                    scanResult,
                    context,
                    sqlConverter,
                    rewriteConfigUpdate.rewriteConfig()
            );
            CliLogger.info("Annotation SQL migration completed. " + migrationResultSummary(annotationMigrationResult));
            fileChanges.addAll(annotationMigrationResult.fileChanges());
            warnings.addAll(annotationMigrationResult.warnings());
            MapperMigrationResult combinedMigrationResult = combine(mapperMigrationResult, annotationMigrationResult);
            CliLogger.info("Fixing mapper Java parameter names...");
            MapperJavaParamFixResult javaParamFixResult = mapperJavaParamFixer.fix(scanResult, context);
            CliLogger.info("Mapper Java parameter name fix completed. File changes: "
                    + javaParamFixResult.fileChanges().size());
            fileChanges.addAll(javaParamFixResult.fileChanges());
            warnings.addAll(javaParamFixResult.warnings());
            CliLogger.info("Aligning mapper jdbcType declarations...");
            MapperJdbcTypeAlignmentResult jdbcTypeAlignmentResult = alignMapperJdbcTypes(context, scanResult);
            CliLogger.info("Mapper jdbcType alignment completed. File changes: "
                    + jdbcTypeAlignmentResult.fileChanges().size());
            fileChanges.addAll(jdbcTypeAlignmentResult.fileChanges());
            warnings.addAll(jdbcTypeAlignmentResult.warnings());
            CliLogger.info("Writing migration report...");
            ReportPaths reportPaths = writeReport(
                    context,
                    scanResult,
                    fileChanges,
                    combinedMigrationResult.automaticConversions(),
                    combinedMigrationResult.manualReviewItems(),
                    warnings
            );
            CliLogger.info("Migration report written: " + reportPaths.markdownPath());
            if (sqlScriptMigrationRequested()) {
                CliLogger.info("Migrating SQL scripts...");
            } else {
                CliLogger.info("SQL script migration skipped: --sql-root not provided.");
            }
            SqlScriptReportResult sqlScriptReportResult = migrateSqlScripts(context);
            if (sqlScriptReportResult != null) {
                CliLogger.info("SQL script migration report written: "
                        + sqlScriptReportResult.reportPaths().markdownPath());
            }
            printMigrationSummary(context, scanResult, combinedMigrationResult, fileChanges, warnings, reportPaths);
            printSqlScriptSummary(sqlScriptReportResult);
            if (validationTestGenerationRequested()) {
                CliLogger.info("Generating Dameng SQL validation test...");
                ValidationTestGenerationResult validationResult = validationTestGenerator.generate(
                        context.projectRoot(),
                        appModule,
                        mapperDir,
                        config,
                        schema,
                        context.reportDir(),
                        rewriteConfigPath(context),
                        List.of()
                );
                GenerateValidationTestCommand.printResult(validationResult);
                CliLogger.info("Running Dameng SQL validation test if configured...");
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
        Path workspaceDir = workspaceResolver.resolve(project, appModule, reportDir);
        return AdapterContext.builder(project)
                .sourceDb(sourceDb)
                .targetDb(targetDb)
                .dryRun(dryRun)
                .dmDriverCoordinate(DependencyCoordinate.parse(dmDriver))
                .reportDir(workspaceDir)
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

    private void validateSqlScriptMigrationOptions() {
        if (sqlRoot == null && sqlRootOut == null) {
            return;
        }
        if (sqlRoot == null) {
            throw new DmAdapterException("--sql-root is required when --sql-root-out is provided.");
        }
        if (sqlRootOut == null) {
            throw new DmAdapterException("--sql-root-out is required when --sql-root is provided.");
        }
    }

    private boolean validationTestGenerationRequested() {
        return generateValidationTest
                || appModule != null
                || config != null
                || ((schema != null && !schema.isBlank()) && !sqlScriptMigrationRequested());
    }

    private boolean shouldPrepareAnnotationClassScan(AdapterContext context) {
        return !context.dryRun() && validationTestGenerationRequested();
    }

    private Path rewriteConfigPath(AdapterContext context) {
        return rewriteConfig == null
                ? context.reportDir().resolve(LegacyWorkspaceMigrator.REWRITE_CONFIG)
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
            String tableName = InsertColumnExtractor.tableName(sql);
            if (tableName.isBlank()) {
                continue;
            }
            String methodKey = sqlChange.statementId();
            if (methodKey == null || methodKey.isBlank() || methodKey.startsWith("(")) {
                continue;
            }
            String key = methodKey + "|" + DamengMetadataReader.normalizeTableName(tableName);
            if (seen.add(key)) {
                candidates.add(new RewriteConfigCandidate(methodKey, tableName, InsertColumnExtractor.columns(sql, tableName)));
            }
        }
        return candidates;
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

    private MapperMigrationResult combine(MapperMigrationResult first, MapperMigrationResult second) {
        List<com.github.dmadapter.core.SqlChange> automaticConversions = new ArrayList<>(first.automaticConversions());
        automaticConversions.addAll(second.automaticConversions());
        List<com.github.dmadapter.core.SqlChange> manualReviewItems = new ArrayList<>(first.manualReviewItems());
        manualReviewItems.addAll(second.manualReviewItems());
        List<FileChange> fileChanges = new ArrayList<>(first.fileChanges());
        fileChanges.addAll(second.fileChanges());
        List<String> warnings = new ArrayList<>(first.warnings());
        warnings.addAll(second.warnings());
        return new MapperMigrationResult(fileChanges, automaticConversions, manualReviewItems, warnings);
    }

    private String migrationResultSummary(MapperMigrationResult result) {
        return "file changes: " + result.fileChanges().size()
                + ", automatic conversions: " + result.automaticConversions().size()
                + ", manual review: " + result.manualReviewItems().size()
                + ", warnings: " + result.warnings().size();
    }

    private MetadataLookupResult metadataForRewriteCandidates(
            AdapterContext context,
            List<RewriteConfigCandidate> candidates
    ) {
        if (candidates.isEmpty()) {
            return new MetadataLookupResult(Map.of(), false, List.of());
        }
        List<String> warnings = new ArrayList<>();
        Map<String, TableKeyMetadata> ddlMetadata = projectDdlMetadataForRewriteCandidates(context, candidates, warnings);
        DmValidationEnvironment environment = DmValidationEnvironment.fromSystem();
        if (!environment.validationEnabled()) {
            return new MetadataLookupResult(ddlMetadata, !ddlMetadata.isEmpty(), warnings);
        }
        if (!environment.ready()) {
            warnings.add("DM_SQL_VALIDATION is true but metadata inference was skipped because required variables are missing: "
                    + environment.missingVariables());
            return new MetadataLookupResult(
                    ddlMetadata,
                    !ddlMetadata.isEmpty(),
                    warnings
            );
        }
        try {
            Optional<String> configuredSchema = configuredSchema(context);
            Map<String, TableKeyMetadata> dmMetadata = runWithMetadataTimeout(
                    () -> damengMetadataReader.readTableKeys(
                            environment,
                            configuredSchema,
                            candidates.stream().map(RewriteConfigCandidate::tableName).distinct().toList()
                    ),
                    metadataReadTimeoutSeconds(),
                    TimeUnit.SECONDS,
                    "Dameng metadata inference"
            );
            Map<String, TableKeyMetadata> metadata = mergeMetadata(dmMetadata, ddlMetadata);
            return new MetadataLookupResult(metadata, !metadata.isEmpty(), warnings);
        } catch (Exception e) {
            warnings.add("Dameng metadata inference was skipped: " + redact(e.getMessage(), environment));
            return new MetadataLookupResult(
                    ddlMetadata,
                    !ddlMetadata.isEmpty(),
                    warnings
            );
        }
    }

    private Map<String, TableKeyMetadata> projectDdlMetadataForRewriteCandidates(
            AdapterContext context,
            List<RewriteConfigCandidate> candidates,
            List<String> warnings
    ) {
        try {
            return projectDdlKeyMetadataReader.readTableKeys(
                    context.projectRoot(),
                    candidates.stream().map(RewriteConfigCandidate::tableName).distinct().toList()
            );
        } catch (Exception e) {
            warnings.add("Project DDL metadata inference was skipped: " + e.getMessage());
            return Map.of();
        }
    }

    private Map<String, TableKeyMetadata> mergeMetadata(
            Map<String, TableKeyMetadata> primary,
            Map<String, TableKeyMetadata> secondary
    ) {
        Map<String, TableKeyMetadata> merged = new LinkedHashMap<>();
        if (primary != null) {
            primary.forEach((table, metadata) -> merged.put(
                    DamengMetadataReader.normalizeTableName(table),
                    metadata
            ));
        }
        if (secondary != null) {
            secondary.forEach((table, metadata) -> {
                String normalizedTable = DamengMetadataReader.normalizeTableName(table);
                merged.merge(normalizedTable, metadata, this::mergeMetadata);
            });
        }
        return merged;
    }

    private TableKeyMetadata mergeMetadata(TableKeyMetadata primary, TableKeyMetadata secondary) {
        List<TableConstraint> constraints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addConstraints(constraints, seen, primary.constraints());
        addConstraints(constraints, seen, secondary.constraints());
        return new TableKeyMetadata(primary.tableName().isBlank() ? secondary.tableName() : primary.tableName(), constraints);
    }

    private void addConstraints(List<TableConstraint> constraints, Set<String> seen, List<TableConstraint> additions) {
        for (TableConstraint constraint : additions) {
            String key = constraint.type() + ":" + constraint.columns().stream()
                    .map(DamengMetadataReader::normalizeIdentifier)
                    .toList();
            if (seen.add(key)) {
                constraints.add(constraint);
            }
        }
    }

    private MapperJdbcTypeAlignmentResult alignMapperJdbcTypes(AdapterContext context, ProjectScanResult scanResult) {
        if (context.dryRun()) {
            return MapperJdbcTypeAlignmentResult.empty();
        }
        DmValidationEnvironment environment = DmValidationEnvironment.fromSystem();
        if (!environment.validationEnabled()) {
            return MapperJdbcTypeAlignmentResult.empty();
        }
        if (!environment.ready()) {
            return new MapperJdbcTypeAlignmentResult(
                    List.of(),
                    List.of("DM_SQL_VALIDATION is true but mapper jdbcType alignment was skipped because required variables are missing: "
                            + environment.missingVariables())
            );
        }
        Set<String> tableNames = mapperJdbcTypeAligner.referencedTables(scanResult, context);
        if (tableNames.isEmpty()) {
            return MapperJdbcTypeAlignmentResult.empty();
        }
        try {
            Map<String, Map<String, String>> columnTypes = runWithMetadataTimeout(
                    () -> damengMetadataReader.readColumnTypes(
                            environment,
                            configuredSchema(context),
                            tableNames
                    ),
                    metadataReadTimeoutSeconds(),
                    TimeUnit.SECONDS,
                    "Dameng mapper jdbcType metadata lookup"
            );
            MapperJdbcTypeAlignmentResult result = mapperJdbcTypeAligner.align(scanResult, context, columnTypes);
            if (result.fileChanges().isEmpty()) {
                return result;
            }
            List<String> warnings = new ArrayList<>(result.warnings());
            warnings.add("Aligned MyBatis jdbcType declarations in mapper-dm with Dameng column metadata.");
            return new MapperJdbcTypeAlignmentResult(result.fileChanges(), warnings);
        } catch (Exception e) {
            return new MapperJdbcTypeAlignmentResult(
                    List.of(),
                    List.of("Dameng mapper jdbcType alignment was skipped: " + redact(e.getMessage(), environment))
            );
        }
    }

    static long metadataReadTimeoutSeconds() {
        return Long.getLong("dm.adapter.metadataReadTimeoutSeconds", DEFAULT_METADATA_READ_TIMEOUT_SECONDS);
    }

    static <T> T runWithMetadataTimeout(
            Callable<T> task,
            long timeout,
            TimeUnit unit,
            String operation
    ) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dm-adapter-dameng-metadata");
            thread.setDaemon(true);
            return thread;
        });
        Future<T> future = executor.submit(task);
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException(operation + " timed out after " + timeout + " " + unit + ".", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private Optional<String> configuredSchema(AdapterContext context) {
        if (schema != null && !schema.isBlank()) {
            return Optional.of(schema.trim());
        }
        Path configPath = config == null
                ? context.reportDir().resolve(LegacyWorkspaceMigrator.VALIDATION_CONFIG)
                : config;
        return DmValidationConfigReader.schema(context.projectRoot(), configPath);
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

    private boolean sqlScriptMigrationRequested() {
        return sqlRoot != null;
    }

    private SqlScriptReportResult migrateSqlScripts(AdapterContext context) throws Exception {
        if (!sqlScriptMigrationRequested()) {
            return null;
        }
        SqlScriptMigrationReport report = sqlScriptMigrator.migrate(new SqlScriptMigrationRequest(
                context.projectRoot(),
                sqlRoot,
                sqlRootOut,
                context.dryRun(),
                configuredSchema(context).orElse(""),
                systemSchema,
                DmValidationEnvironment.fromSystem()
        ));
        ReportPaths reportPaths = reportWriter.writeSqlScriptMigrationReport(report, context.reportDir());
        return new SqlScriptReportResult(report, reportPaths);
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

    private void printSqlScriptSummary(SqlScriptReportResult result) {
        if (result == null) {
            return;
        }
        SqlScriptMigrationReport report = result.report();
        CliLogger.info("SQL script migration " + (report.dryRun() ? "dry-run" : "completed") + ".");
        CliLogger.info("SQL script files: " + report.scannedFileCount());
        CliLogger.info("SQL script converted files: " + report.convertedFileCount());
        CliLogger.info("SQL script manual review SQL items: " + report.manualReviewSqlCount());
        CliLogger.info("SQL script validation success SQL count: " + report.validationSuccessCount());
        CliLogger.info("SQL script validation failed SQL count: " + report.validationFailureCount());
        CliLogger.info("SQL script report: " + result.reportPaths().markdownPath());
        if (!report.warnings().isEmpty()) {
            CliLogger.info("SQL script warnings:");
            report.warnings().forEach(warning -> CliLogger.info("- " + warning));
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

    private record SqlScriptReportResult(SqlScriptMigrationReport report, ReportPaths reportPaths) {
    }
}
