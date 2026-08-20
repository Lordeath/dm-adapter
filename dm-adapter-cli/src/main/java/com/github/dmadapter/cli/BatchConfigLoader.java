package com.github.dmadapter.cli;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.dmadapter.core.DmAdapterException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BatchConfigLoader {
    private static final int SCHEMA_VERSION = 1;
    private static final int DEFAULT_RETENTION_DAYS = 30;
    private static final String DEFAULT_SQL_SOURCE = "sql/v2";
    private static final String DEFAULT_SQL_OUTPUT = "sql/v2-dm";

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    ResolvedBatchConfig load(Path configuredPath) {
        if (configuredPath == null) {
            throw new DmAdapterException("--config is required.");
        }
        Path configPath = configuredPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(configPath)) {
            throw new DmAdapterException("Batch config file does not exist: " + configPath);
        }
        BatchConfig config;
        try {
            config = mapper.readValue(configPath.toFile(), BatchConfig.class);
        } catch (Exception e) {
            throw new DmAdapterException("Could not parse batch YAML: " + safeMessage(e), e);
        }
        if (config == null) {
            throw new DmAdapterException("Batch config is empty: " + configPath);
        }
        if (config.schemaVersion() == null || config.schemaVersion() != SCHEMA_VERSION) {
            throw new DmAdapterException("Unsupported batch schemaVersion: " + config.schemaVersion());
        }
        Path baseDir = configPath.getParent() == null
                ? Path.of("").toAbsolutePath().normalize()
                : configPath.getParent();
        Path workspaceDir = absoluteConfigPath(baseDir, config.workspaceDir(), "workspaceDir");
        Path reportDir = absoluteConfigPath(baseDir, config.reportDir(), "reportDir");
        if (workspaceDir.startsWith(reportDir) || reportDir.startsWith(workspaceDir)) {
            throw new DmAdapterException("workspaceDir and reportDir must be separate, non-nested directories.");
        }
        int retentionDays = config.reportRetentionDays() == null
                ? DEFAULT_RETENTION_DAYS
                : config.reportRetentionDays();
        if (retentionDays < 0) {
            throw new DmAdapterException("reportRetentionDays must be zero or greater.");
        }

        BatchConfig.GitConfig git = required(config.git(), "git");
        String username = value(git.username());
        String password = value(git.password());
        if (username.isBlank() != password.isBlank()) {
            throw new DmAdapterException("git.username and git.password must be configured together.");
        }
        ResolvedBatchConfig.GitIdentity identity = new ResolvedBatchConfig.GitIdentity(
                requiredText(git.authorName(), "git.authorName"),
                requiredText(git.authorEmail(), "git.authorEmail"),
                requiredText(git.commitMessage(), "git.commitMessage")
        );

        List<BatchConfig.RepositoryConfig> configuredRepositories = config.repositories() == null
                ? List.of()
                : config.repositories();
        List<BatchConfig.RepositoryConfig> enabledRepositories = configuredRepositories.stream()
                .filter(repository -> repository != null && !Boolean.FALSE.equals(repository.enabled()))
                .toList();
        if (enabledRepositories.isEmpty()) {
            throw new DmAdapterException("At least one enabled repository is required.");
        }

        List<ResolvedBatchConfig.Repository> repositories = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (BatchConfig.RepositoryConfig repository : enabledRepositories) {
            String name = requiredText(repository.name(), "repositories[].name");
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new DmAdapterException("Unsafe repository name: " + name);
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new DmAdapterException("Duplicate repository name: " + name);
            }
            String url = requiredText(repository.url(), "repositories[" + name + "].url");
            validateUrl(url, !username.isBlank(), name);
            String branch = requiredText(repository.branch(), "repositories[" + name + "].branch");
            if (!Repository.isValidRefName("refs/heads/" + branch)) {
                throw new DmAdapterException("Invalid branch for repository " + name + ": " + branch);
            }
            Path projectSubdir = relativePath(
                    repository.projectSubdir() == null ? "." : repository.projectSubdir(),
                    "repositories[" + name + "].projectSubdir"
            );
            repositories.add(new ResolvedBatchConfig.Repository(
                    name,
                    url,
                    branch,
                    projectSubdir,
                    resolveMigration(config.migrationDefaults(), repository.migration(), name)
            ));
        }
        return new ResolvedBatchConfig(
                configPath,
                workspaceDir,
                reportDir,
                retentionDays,
                new ResolvedBatchConfig.Credentials(username, password),
                identity,
                List.copyOf(repositories)
        );
    }

    private ResolvedBatchConfig.Migration resolveMigration(
            BatchConfig.MigrationConfig defaults,
            BatchConfig.MigrationConfig override,
            String repository
    ) {
        String dmDriver = first(override == null ? null : override.dmDriver(), defaults == null ? null : defaults.dmDriver());
        String mapperDir = first(override == null ? null : override.mapperDir(), defaults == null ? null : defaults.mapperDir());
        String rewriteConfig = first(
                override == null ? null : override.rewriteConfig(),
                defaults == null ? null : defaults.rewriteConfig()
        );
        Boolean scriptsOnly = first(
                override == null ? null : override.sqlScriptsOnly(),
                defaults == null ? null : defaults.sqlScriptsOnly()
        );
        BatchConfig.SqlConfig defaultSql = defaults == null ? null : defaults.sql();
        BatchConfig.SqlConfig overrideSql = override == null ? null : override.sql();
        BatchSqlMode mode = first(
                overrideSql == null ? null : overrideSql.mode(),
                defaultSql == null ? null : defaultSql.mode(),
                BatchSqlMode.IF_PRESENT
        );
        Path sqlSource = relativePath(
                first(
                        overrideSql == null ? null : overrideSql.sourceDir(),
                        defaultSql == null ? null : defaultSql.sourceDir(),
                        DEFAULT_SQL_SOURCE
                ),
                "repositories[" + repository + "].migration.sql.sourceDir"
        );
        Path sqlOutput = relativePath(
                first(
                        overrideSql == null ? null : overrideSql.outputDir(),
                        defaultSql == null ? null : defaultSql.outputDir(),
                        DEFAULT_SQL_OUTPUT
                ),
                "repositories[" + repository + "].migration.sql.outputDir"
        );
        if (mode != BatchSqlMode.DISABLED && sqlSource.equals(sqlOutput)) {
            throw new DmAdapterException("SQL sourceDir and outputDir must differ for repository " + repository + ".");
        }
        List<String> configuredPreserve = overrideSql != null && overrideSql.preserveSql() != null
                ? overrideSql.preserveSql()
                : (defaultSql == null || defaultSql.preserveSql() == null ? List.of() : defaultSql.preserveSql());
        List<Path> preserveSql = configuredPreserve.stream()
                .map(path -> relativePath(path, "repositories[" + repository + "].migration.sql.preserveSql"))
                .toList();
        Map<String, MethodKeySettings> methodKeys = resolveMethodKeys(defaults, override, repository);
        return new ResolvedBatchConfig.Migration(
                value(dmDriver),
                optionalRelativePath(mapperDir, "repositories[" + repository + "].migration.mapperDir"),
                optionalRelativePath(rewriteConfig, "repositories[" + repository + "].migration.rewriteConfig"),
                Boolean.TRUE.equals(scriptsOnly),
                resolveTableKeyColumns(defaults, override, repository),
                methodKeys.entrySet().stream().filter(entry -> !entry.getValue().keyColumns().isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().keyColumns()
                        )),
                methodKeys.entrySet().stream().filter(entry -> !entry.getValue().conflictKeyGroups().isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey,
                                entry -> entry.getValue().conflictKeyGroups()
                        )),
                new ResolvedBatchConfig.Sql(mode, sqlSource, sqlOutput, preserveSql)
        );
    }

    private Map<String, MethodKeySettings> resolveMethodKeys(
            BatchConfig.MigrationConfig defaults,
            BatchConfig.MigrationConfig override,
            String repository
    ) {
        LinkedHashMap<String, MethodKeySettings> resolved = new LinkedHashMap<>();
        mergeMethodKeys(
                resolved,
                defaults == null ? null : defaults.upsertKeys(),
                "migrationDefaults.upsertKeys.methods"
        );
        mergeMethodKeys(
                resolved,
                override == null ? null : override.upsertKeys(),
                "repositories[" + repository + "].migration.upsertKeys.methods"
        );
        return Map.copyOf(resolved);
    }

    private void mergeMethodKeys(
            Map<String, MethodKeySettings> target,
            BatchConfig.UpsertKeysConfig upsertKeys,
            String field
    ) {
        if (upsertKeys == null || upsertKeys.methods() == null) {
            return;
        }
        upsertKeys.methods().forEach((configuredMethod, configuredKeys) -> {
            String method = requiredText(configuredMethod, field + " method name");
            List<String> keyColumns = validatedColumns(
                    configuredKeys == null ? null : configuredKeys.keyColumns(),
                    field + "[" + method + "].keyColumns",
                    true
            );
            List<List<String>> conflictKeyGroups = new ArrayList<>();
            List<List<String>> configuredGroups = configuredKeys == null
                    ? null
                    : configuredKeys.conflictKeyGroups();
            if (configuredGroups != null) {
                for (int index = 0; index < configuredGroups.size(); index++) {
                    List<String> group = validatedColumns(
                            configuredGroups.get(index),
                            field + "[" + method + "].conflictKeyGroups[" + index + "]",
                            false
                    );
                    conflictKeyGroups.add(group);
                }
            }
            if (keyColumns.isEmpty() && conflictKeyGroups.isEmpty()) {
                throw new DmAdapterException(
                        field + "[" + method + "] must configure keyColumns or conflictKeyGroups."
                );
            }
            target.put(method, new MethodKeySettings(keyColumns, List.copyOf(conflictKeyGroups)));
        });
    }

    private List<String> validatedColumns(List<String> configuredColumns, String field, boolean optional) {
        if (configuredColumns == null) {
            return List.of();
        }
        if (configuredColumns.isEmpty()) {
            if (optional) {
                return List.of();
            }
            throw new DmAdapterException(field + " must not be empty.");
        }
        List<String> columns = new ArrayList<>();
        Set<String> normalizedColumns = new LinkedHashSet<>();
        for (String configuredColumn : configuredColumns) {
            String column = requiredText(configuredColumn, field + "[]");
            if (!normalizedColumns.add(column.toLowerCase(Locale.ROOT))) {
                throw new DmAdapterException(field + " contains duplicate column: " + column);
            }
            columns.add(column);
        }
        return List.copyOf(columns);
    }

    private Map<String, List<String>> resolveTableKeyColumns(
            BatchConfig.MigrationConfig defaults,
            BatchConfig.MigrationConfig override,
            String repository
    ) {
        LinkedHashMap<String, List<String>> resolved = new LinkedHashMap<>();
        mergeTableKeyColumns(
                resolved,
                defaults == null ? null : defaults.upsertKeys(),
                "migrationDefaults.upsertKeys.tables"
        );
        mergeTableKeyColumns(
                resolved,
                override == null ? null : override.upsertKeys(),
                "repositories[" + repository + "].migration.upsertKeys.tables"
        );
        return Map.copyOf(resolved);
    }

    private void mergeTableKeyColumns(
            Map<String, List<String>> target,
            BatchConfig.UpsertKeysConfig upsertKeys,
            String field
    ) {
        if (upsertKeys == null || upsertKeys.tables() == null) {
            return;
        }
        upsertKeys.tables().forEach((configuredTable, configuredKeys) -> {
            String table = requiredText(configuredTable, field + " table name");
            String normalizedTable = normalizeTableName(table);
            List<String> configuredColumns = configuredKeys == null ? null : configuredKeys.keyColumns();
            if (configuredColumns == null || configuredColumns.isEmpty()) {
                throw new DmAdapterException(field + "[" + table + "].keyColumns must not be empty.");
            }
            List<String> columns = new ArrayList<>();
            Set<String> normalizedColumns = new LinkedHashSet<>();
            for (String configuredColumn : configuredColumns) {
                String column = requiredText(
                        configuredColumn,
                        field + "[" + table + "].keyColumns[]"
                );
                if (!normalizedColumns.add(column.toLowerCase(Locale.ROOT))) {
                    throw new DmAdapterException(
                            field + "[" + table + "].keyColumns contains duplicate column: " + column
                    );
                }
                columns.add(column);
            }
            target.put(normalizedTable, List.copyOf(columns));
        });
    }

    private String normalizeTableName(String tableName) {
        return tableName.trim()
                .replace("\"", "")
                .replace("`", "")
                .toLowerCase(Locale.ROOT);
    }

    private record MethodKeySettings(
            List<String> keyColumns,
            List<List<String>> conflictKeyGroups
    ) {
    }

    private void validateUrl(String url, boolean credentialsConfigured, String repository) {
        try {
            URIish uri = new URIish(url);
            if (uri.getUser() != null || uri.getPass() != null) {
                throw new DmAdapterException("Repository URL must not contain credentials: " + repository);
            }
            String scheme = value(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https", "file").contains(scheme)) {
                throw new DmAdapterException(
                        "Repository URL must use http, https, or file for repository " + repository + "."
                );
            }
            if (("http".equals(scheme) || "https".equals(scheme)) && !credentialsConfigured) {
                throw new DmAdapterException(
                        "git.username and git.password are required for HTTP repository " + repository + "."
                );
            }
        } catch (DmAdapterException e) {
            throw e;
        } catch (Exception e) {
            throw new DmAdapterException("Invalid repository URL for " + repository + ".", e);
        }
    }

    private Path absoluteConfigPath(Path baseDir, String configured, String field) {
        String value = requiredText(configured, field);
        try {
            Path path = Path.of(value);
            return (path.isAbsolute() ? path : baseDir.resolve(path)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new DmAdapterException("Invalid path for " + field + ".", e);
        }
    }

    private Path optionalRelativePath(String configured, String field) {
        return configured == null || configured.isBlank() ? null : relativePath(configured, field);
    }

    private Path relativePath(String configured, String field) {
        String value = requiredText(configured, field);
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()) {
                throw new DmAdapterException(field + " must be relative to the repository root.");
            }
            Path normalized = path.normalize();
            if (normalized.startsWith("..")) {
                throw new DmAdapterException(field + " must stay inside the repository.");
            }
            return normalized;
        } catch (InvalidPathException e) {
            throw new DmAdapterException("Invalid path for " + field + ".", e);
        }
    }

    private String requiredText(String value, String field) {
        String normalized = value(value);
        if (normalized.isBlank()) {
            throw new DmAdapterException(field + " is required.");
        }
        return normalized;
    }

    private <T> T required(T value, String field) {
        if (value == null) {
            throw new DmAdapterException(field + " is required.");
        }
        return value;
    }

    @SafeVarargs
    private final <T> T first(T... values) {
        for (T value : values) {
            if (value != null) {
                if (value instanceof String text && text.isBlank()) {
                    continue;
                }
                return value;
            }
        }
        return null;
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeMessage(Throwable failure) {
        Throwable current = failure;
        String message = "";
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message.isBlank() ? failure.getClass().getSimpleName() : message.replaceAll("\\s+", " ").trim();
    }
}
