package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.DependencyCoordinate;
import com.github.dmadapter.core.DmAdapterException;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptValidationFailure;
import com.github.dmadapter.core.DamengTargetCapabilities;
import com.github.dmadapter.core.TargetLengthSemantics;
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
    private static final int DEFAULT_METADATA_READ_ATTEMPTS = 5;
    private static final long DEFAULT_METADATA_RETRY_DELAY_MILLIS = 5_000L;
    private static final long MAX_METADATA_RETRY_DELAY_MILLIS = 30_000L;

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

    @Option(names = "--preserve-sql", description = "Relative SQL path to preserve in --sql-root-out instead of converting; repeat for multiple files. Top-level 00000000.sql is always preserved.")
    private List<Path> preservedSqlPaths = new ArrayList<>();

    @Option(names = "--sql-scripts-only", description = "Only migrate SQL scripts; do not scan or modify Maven, mapper, or application files.")
    private boolean sqlScriptsOnly;

    @Option(names = {"--system-schema", "--system_schema"}, description = "Dameng schema for validating *_system.sql scripts.")
    private String systemSchema;

    @Option(
            names = "--target-length-semantics",
            description = "Dameng character length semantics for offline SQL migration: ${COMPLETION-CANDIDATES}."
    )
    private TargetLengthSemantics targetLengthSemantics;

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
    private final DamengTargetCapabilitiesReader targetCapabilitiesReader = new DamengTargetCapabilitiesReader();

    @Override
    public Integer call() {
        ProjectSummaryTracker summaryTracker = null;
        try {
            AdapterContext context = buildContext();
            DmValidationEnvironment validationEnvironment = DmValidationEnvironment.fromSystem();
            summaryTracker = new ProjectSummaryTracker(
                    context,
                    reportWriter,
                    sqlScriptMigrationRequested(),
                    validationTestGenerationRequested(),
                    validationEnvironment
            );
            CliLogger.info("Migration started. Project: " + context.projectRoot());
            CliLogger.info("dm-adapter workspace: " + context.reportDir());
            validateSupportedDatabases(context);
            validateValidationTestGeneration(context);
            validateSqlScriptMigrationOptions();
            if (sqlScriptsOnly) {
                if (!sqlScriptMigrationRequested()) {
                    throw new DmAdapterException("--sql-scripts-only requires --sql-root and --sql-root-out.");
                }
                SqlScriptReportResult sqlScriptResult = migrateSqlScripts(context, validationEnvironment);
                CliLogger.info("SQL script migration report written: " + sqlScriptResult.reportPaths().markdownPath());
                printSqlScriptSummary(sqlScriptResult);
                return sqlScriptOnlyExitCode(context, validationEnvironment, sqlScriptResult);
            }
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
                MigrationReportResult reportResult = writeReport(
                        context, scanResult, fileChanges, List.of(), List.of(), warnings
                );
                summaryTracker.invalidProject(reportResult.report(), reportResult.reportPaths());
                summaryTracker.finish(2);
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
            MigrationReportResult migrationReportResult = writeReport(
                    context,
                    scanResult,
                    fileChanges,
                    combinedMigrationResult.automaticConversions(),
                    combinedMigrationResult.manualReviewItems(),
                    warnings
            );
            ReportPaths reportPaths = migrationReportResult.reportPaths();
            CliLogger.info("Migration report written: " + reportPaths.markdownPath());
            summaryTracker.migrationCompleted(migrationReportResult.report(), reportPaths);
            if (sqlScriptMigrationRequested()) {
                CliLogger.info("Migrating SQL scripts...");
            } else {
                CliLogger.info("SQL script migration skipped: --sql-root not provided.");
            }
            summaryTracker.startSqlScriptValidation(sqlScriptMigrationRequested());
            SqlScriptReportResult sqlScriptReportResult = migrateSqlScripts(context, validationEnvironment);
            if (sqlScriptReportResult != null) {
                CliLogger.info("SQL script migration report written: "
                        + sqlScriptReportResult.reportPaths().markdownPath());
                summaryTracker.sqlScriptCompleted(
                        sqlScriptReportResult.report(),
                        sqlScriptReportResult.reportPaths()
                );
            }
            printMigrationSummary(context, scanResult, combinedMigrationResult, fileChanges, warnings, reportPaths);
            printSqlScriptSummary(sqlScriptReportResult);
            ValidationTestRunResult validationRunResult = null;
            MapperValidationAssessment mapperAssessment = null;
            String mapperValidationBlockReason = mapperValidationBlockReason(sqlScriptReportResult);
            boolean mapperValidationBlocked = !mapperValidationBlockReason.isBlank();
            if (mapperValidationBlocked) {
                summaryTracker.skipMapperValidation(mapperValidationBlockReason);
            } else {
                summaryTracker.startMapperValidation(validationTestGenerationRequested());
            }
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
                if (mapperValidationBlocked) {
                    CliLogger.info("Mapper database validation skipped: " + mapperValidationBlockReason);
                } else {
                    CliLogger.info("Running Dameng SQL validation test if configured...");
                    validationRunResult = validationTestRunner.runIfConfigured(validationResult, validationEnvironment);
                    GenerateValidationTestCommand.printValidationRunResult(validationRunResult);
                    mapperAssessment = summaryTracker.mapperCompleted(validationRunResult);
                }
            }
            int exitCode = validationExitCode(
                    context,
                    validationEnvironment,
                    sqlScriptReportResult,
                    validationRunResult,
                    mapperAssessment
            );
            summaryTracker.finish(exitCode);
            CliLogger.info("Project summary: " + context.reportDir().resolve(ReportWriter.SUMMARY_MARKDOWN));
            return exitCode;
        } catch (Exception e) {
            if (summaryTracker != null) {
                summaryTracker.fail(e);
            }
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

    List<RewriteConfigCandidate> rewriteConfigCandidates(MapperMigrationResult mapperMigrationResult) {
        List<RewriteConfigCandidate> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        MySqlToDmSqlConverter candidateExtractor = new MySqlToDmSqlConverter();
        List<com.github.dmadapter.core.SqlChange> candidateChanges = new ArrayList<>(
                mapperMigrationResult.manualReviewItems()
        );
        candidateChanges.addAll(mapperMigrationResult.automaticConversions());
        for (com.github.dmadapter.core.SqlChange sqlChange : candidateChanges) {
            String sql = sqlChange.originalSql() == null ? "" : sqlChange.originalSql();
            String lower = sql.toLowerCase();
            String reason = sqlChange.reason() == null ? "" : sqlChange.reason().toLowerCase();
            String methodKey = sqlChange.statementId();
            if (methodKey != null && !methodKey.isBlank() && !methodKey.startsWith("(")) {
                candidateExtractor.outerJoinSourceKeyCandidate(sql).ifPresent(sourceCandidate -> {
                    String key = methodKey
                            + "|outer|"
                            + DamengMetadataReader.normalizeTableName(sourceCandidate.tableName());
                    if (seen.add(key)) {
                        candidates.add(new RewriteConfigCandidate(
                                methodKey,
                                sourceCandidate.tableName(),
                                sourceCandidate.joinColumns(),
                                RewriteConfigCandidate.RewriteKind.OUTER_JOIN_SOURCE
                        ));
                    }
                });
            }
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
            if (methodKey == null || methodKey.isBlank() || methodKey.startsWith("(")) {
                continue;
            }
            String key = methodKey + "|upsert|" + DamengMetadataReader.normalizeTableName(tableName);
            if (seen.add(key)) {
                RewriteConfigCandidate.RewriteKind rewriteKind = lower.contains("insert ignore")
                        ? RewriteConfigCandidate.RewriteKind.INSERT_IGNORE
                        : RewriteConfigCandidate.RewriteKind.ON_DUPLICATE_KEY_UPDATE;
                candidates.add(new RewriteConfigCandidate(
                        methodKey,
                        tableName,
                        InsertColumnExtractor.columns(sql, tableName),
                        rewriteKind
                ));
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
            Map<String, TableKeyMetadata> metadata = mergeDatabaseAndProjectDdlMetadata(
                    dmMetadata,
                    ddlMetadata
            );
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
            List<String> tableNames = candidates.stream()
                    .map(RewriteConfigCandidate::tableName)
                    .distinct()
                    .toList();
            Map<String, TableKeyMetadata> metadata = projectDdlKeyMetadataReader.readTableKeys(
                    context.projectRoot(),
                    tableNames
            );
            Path resolvedSqlRoot = resolvedSqlRoot(context);
            Path sqlMetadataRoot = resolvedSqlRoot == null || resolvedSqlRoot.getParent() == null
                    ? resolvedSqlRoot
                    : resolvedSqlRoot.getParent();
            if (sqlMetadataRoot == null || sqlMetadataRoot.startsWith(context.projectRoot())) {
                return metadata;
            }
            return mergeMetadata(
                    projectDdlKeyMetadataReader.readTableKeys(sqlMetadataRoot, tableNames),
                    metadata
            );
        } catch (Exception e) {
            warnings.add("Project DDL metadata inference was skipped: " + e.getMessage());
            return Map.of();
        }
    }

    private Path resolvedSqlRoot(AdapterContext context) {
        if (sqlRoot == null) {
            return null;
        }
        return (sqlRoot.isAbsolute() ? sqlRoot : context.projectRoot().resolve(sqlRoot))
                .toAbsolutePath()
                .normalize();
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

    Map<String, TableKeyMetadata> mergeDatabaseAndProjectDdlMetadata(
            Map<String, TableKeyMetadata> databaseMetadata,
            Map<String, TableKeyMetadata> projectDdlMetadata
    ) {
        Map<String, TableKeyMetadata> merged = new LinkedHashMap<>();
        if (databaseMetadata != null) {
            databaseMetadata.forEach((table, metadata) -> merged.put(
                    DamengMetadataReader.normalizeTableName(table),
                    metadata
            ));
        }
        if (projectDdlMetadata != null) {
            projectDdlMetadata.forEach((table, metadata) -> {
                String normalizedTable = DamengMetadataReader.normalizeTableName(table);
                if (metadata.tableFound()) {
                    merged.put(normalizedTable, metadata);
                } else {
                    merged.merge(normalizedTable, metadata, this::mergeMetadata);
                }
            });
        }
        return merged;
    }

    private TableKeyMetadata mergeMetadata(TableKeyMetadata primary, TableKeyMetadata secondary) {
        List<TableConstraint> constraints = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addConstraints(constraints, seen, primary.constraints());
        addConstraints(constraints, seen, secondary.constraints());
        Set<String> autoGeneratedColumns = new LinkedHashSet<>(primary.autoGeneratedColumns());
        autoGeneratedColumns.addAll(secondary.autoGeneratedColumns());
        return new TableKeyMetadata(
                primary.tableName().isBlank() ? secondary.tableName() : primary.tableName(),
                constraints,
                primary.tableFound() || secondary.tableFound(),
                autoGeneratedColumns
        );
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

    private MigrationReportResult writeReport(
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
        return new MigrationReportResult(report, reportWriter.writeMigrationReport(report, context.reportDir()));
    }

    private boolean sqlScriptMigrationRequested() {
        return sqlRoot != null;
    }

    private SqlScriptReportResult migrateSqlScripts(
            AdapterContext context,
            DmValidationEnvironment validationEnvironment
    ) throws Exception {
        if (!sqlScriptMigrationRequested()) {
            return null;
        }
        DamengTargetCapabilities targetCapabilities = resolveTargetCapabilities(validationEnvironment);
        SqlScriptMigrationReport report = sqlScriptMigrator.migrate(new SqlScriptMigrationRequest(
                context.projectRoot(),
                sqlRoot,
                sqlRootOut,
                context.dryRun(),
                configuredSchema(context).orElse(""),
                systemSchema,
                preservedSqlPaths,
                validationEnvironment,
                targetCapabilities,
                context.reportDir().resolve(SqlScriptValidationPlanStore.DEFAULT_FILE_NAME)
        ));
        ReportPaths reportPaths = reportWriter.writeSqlScriptMigrationReport(report, context.reportDir());
        return new SqlScriptReportResult(report, reportPaths);
    }

    private DamengTargetCapabilities resolveTargetCapabilities(DmValidationEnvironment environment) {
        DamengTargetCapabilities detected = DamengTargetCapabilities.unknown();
        if (environment != null && environment.ready()) {
            try {
                detected = runWithMetadataRetries(
                        () -> runWithMetadataTimeout(
                                () -> targetCapabilitiesReader.read(environment),
                                DEFAULT_METADATA_READ_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS,
                                "Dameng target capability lookup"
                        ),
                        targetCapabilityReadAttempts(targetLengthSemantics),
                        DEFAULT_METADATA_RETRY_DELAY_MILLIS
                );
            } catch (Exception e) {
                if (targetLengthSemantics == null) {
                    throw new DmAdapterException(
                            "Dameng target capability preflight failed before SQL execution. "
                                    + "Verify the validation connection and V$DM_INI read permission, "
                                    + "or explicitly pass --target-length-semantics. "
                                    + "Cause: " + redactedMetadataFailure(e, environment),
                            e
                    );
                }
                CliLogger.info(
                        "Dameng target capability lookup failed; continuing with explicit "
                                + "--target-length-semantics="
                                + targetLengthSemantics
                                + ". Other target capabilities remain unknown. Cause: "
                                + redactedMetadataFailure(e, environment)
                );
                detected = DamengTargetCapabilities.offline(targetLengthSemantics);
            }
        }
        if (detected.lengthSemantics() != null
                && targetLengthSemantics != null
                && detected.lengthSemantics() != targetLengthSemantics) {
            throw new DmAdapterException(
                    "--target-length-semantics=" + targetLengthSemantics
                            + " conflicts with target database LENGTH_IN_CHAR="
                            + (detected.lengthSemantics() == TargetLengthSemantics.CHAR ? "1" : "0")
            );
        }
        if (detected.lengthSemantics() != null) {
            return detected;
        }
        if (targetLengthSemantics != null) {
            return DamengTargetCapabilities.offline(targetLengthSemantics);
        }
        return detected;
    }

    static int targetCapabilityReadAttempts(TargetLengthSemantics explicitLengthSemantics) {
        return explicitLengthSemantics == null ? DEFAULT_METADATA_READ_ATTEMPTS : 1;
    }

    static <T> T runWithMetadataRetries(
            Callable<T> task,
            int maxAttempts,
            long initialDelayMillis
    ) throws Exception {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive.");
        }
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return task.call();
            } catch (Exception e) {
                lastFailure = e;
                if (attempt >= maxAttempts) {
                    throw e;
                }
                long delayMillis = Math.min(
                        MAX_METADATA_RETRY_DELAY_MILLIS,
                        Math.max(0L, initialDelayMillis)
                );
                for (int backoff = 1; backoff < attempt && delayMillis < MAX_METADATA_RETRY_DELAY_MILLIS; backoff++) {
                    delayMillis = Math.min(MAX_METADATA_RETRY_DELAY_MILLIS, delayMillis * 2);
                }
                CliLogger.info("Dameng target capability lookup failed; retrying in "
                        + delayMillis + " ms (" + attempt + "/" + maxAttempts + ").");
                if (delayMillis > 0) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(delayMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Dameng target capability lookup failed.")
                : lastFailure;
    }

    private String redactedMetadataFailure(Exception failure, DmValidationEnvironment environment) {
        Throwable current = failure;
        String message = "";
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        if (message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        if (environment != null) {
            for (String sensitive : List.of(
                    environment.password(),
                    environment.username(),
                    environment.jdbcUrl()
            )) {
                if (sensitive != null && !sensitive.isBlank()) {
                    message = message.replace(sensitive, "******");
                }
            }
        }
        return message.replaceAll("\\s+", " ").trim();
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

    private int validationExitCode(
            AdapterContext context,
            DmValidationEnvironment environment,
            SqlScriptReportResult sqlScriptResult,
            ValidationTestRunResult mapperResult,
            MapperValidationAssessment mapperAssessment
    ) {
        if (sqlScriptResult != null && sqlScriptResult.report().manualReviewSqlCount() > 0) {
            return 3;
        }
        boolean scriptValidationRequested = sqlScriptMigrationRequested() && !context.dryRun()
                && environment.validationEnabled();
        boolean mapperValidationRequested = validationTestGenerationRequested()
                && environment.validationEnabled();
        if ((scriptValidationRequested || mapperValidationRequested) && !environment.ready()) {
            return 4;
        }
        if (sqlScriptResult != null && scriptValidationRequested) {
            SqlScriptMigrationReport report = sqlScriptResult.report();
            boolean timeoutOrPreflight = report.validationFailures().stream().anyMatch(failure ->
                    "VALIDATION_TIMEOUT".equals(failure.category())
                            || "INVALID_SCHEMA".equals(failure.category())
                            || "OBJECT_STATUS_VALIDATION_FAILED".equals(failure.category()));
            boolean connectionFailure = !report.validationAttempted()
                    && report.validationStatus().toLowerCase(java.util.Locale.ROOT).contains("connection failed");
            if (timeoutOrPreflight || connectionFailure) {
                return 4;
            }
            boolean rootFailure = report.validationFailures().stream().anyMatch(failure ->
                    !"BLOCKED_BY_PRIOR_FAILURE".equals(failure.category()));
            if (rootFailure) {
                return 3;
            }
        }
        if (mapperResult != null && mapperValidationRequested) {
            if (mapperAssessment != null && mapperAssessment.internalFailure()) {
                return 1;
            }
            if (mapperAssessment == null || mapperAssessment.timedOut()
                    || mapperAssessment.infrastructureFailure()) {
                return 4;
            }
            if (mapperAssessment.validationFailure() || mapperResult.exitCode() != 0) {
                return 3;
            }
        }
        return 0;
    }

    private int sqlScriptOnlyExitCode(
            AdapterContext context,
            DmValidationEnvironment environment,
            SqlScriptReportResult sqlScriptResult
    ) {
        return validationExitCode(context, environment, sqlScriptResult, null, null);
    }

    private String mapperValidationBlockReason(SqlScriptReportResult result) {
        if (result == null) {
            return "";
        }
        SqlScriptMigrationReport report = result.report();
        return mapperValidationBlockReason(
                report.validationAttempted(),
                report.validationStatus(),
                report.validationFailures()
        );
    }

    static String mapperValidationBlockReason(
            boolean validationAttempted,
            String validationStatus,
            List<SqlScriptValidationFailure> failures
    ) {
        if (mapperValidationBlockedByScriptFailures(failures)) {
            return "SQL 脚本验证的 schema 前置检查失败，未执行 Mapper 数据库验证。";
        }
        if (!validationAttempted
                && validationStatus != null
                && validationStatus.toLowerCase(java.util.Locale.ROOT).contains("connection failed")) {
            return "SQL 脚本验证无法建立达梦连接，未重复执行 Mapper 数据库验证。";
        }
        return "";
    }

    static boolean mapperValidationBlockedByScriptFailures(List<SqlScriptValidationFailure> failures) {
        return failures != null && failures.stream().anyMatch(failure ->
                failure != null && "(schema-preflight)".equals(failure.sourceFile()));
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

    private record MigrationReportResult(MigrationReport report, ReportPaths reportPaths) {
    }
}
