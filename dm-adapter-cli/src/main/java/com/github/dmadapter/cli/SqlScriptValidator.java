package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlScriptValidationFailure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class SqlScriptValidator implements SqlScriptMigrator.Validator {
    private static final int DEFAULT_STATEMENT_TIMEOUT_SECONDS = 600;
    private static final int DEFAULT_CONNECTION_ATTEMPTS = 3;
    private static final long DEFAULT_CONNECTION_RETRY_DELAY_MILLIS = 2_000L;
    private static final long DEFAULT_SLOW_OPERATION_LOG_MILLIS = 5_000L;
    private static final long SLOW_OPERATION_REPEAT_MILLIS = 30_000L;
    private static final int CONNECTION_CLOSE_TIMEOUT_SECONDS = 5;
    private static final int STATEMENT_PROGRESS_INTERVAL = 100;
    private static final String STATEMENT_TIMEOUT_PROPERTY = "dm.adapter.sqlScriptStatementTimeoutSeconds";
    private static final String STATEMENT_TIMEOUT_ENV = "DM_SQL_SCRIPT_VALIDATION_TIMEOUT_SECONDS";
    private static final String CONNECTION_ATTEMPTS_PROPERTY = "dm.adapter.sqlScriptConnectionAttempts";
    private static final String CONNECTION_RETRY_DELAY_PROPERTY =
            "dm.adapter.sqlScriptConnectionRetryDelayMillis";
    private static final String SLOW_OPERATION_LOG_PROPERTY = "dm.adapter.sqlScriptSlowOperationLogMillis";
    private static final Pattern CREATE_ROUTINE_PATTERN = Pattern.compile(
            "(?is)\\bCREATE(?:\\s+OR\\s+REPLACE)?\\s+"
                    + "(PROCEDURE|FUNCTION|TRIGGER|VIEW)\\s+([`\"\\w.$-]+)"
    );
    private static final Pattern CALL_PATTERN = Pattern.compile("(?is)\\bCALL\\s+([`\"\\w.$-]+)");

    private final ConnectionProvider connectionProvider;
    private final Consumer<String> progressConsumer;
    private final int connectionAttempts;
    private final long connectionRetryDelayMillis;

    SqlScriptValidator() {
        this(defaultConnectionProvider(), null);
    }

    static SqlScriptValidator withProgress(Consumer<String> progressConsumer) {
        return new SqlScriptValidator(defaultConnectionProvider(), progressConsumer);
    }

    private static ConnectionProvider defaultConnectionProvider() {
        return environment -> {
            try {
                Class.forName("dm.jdbc.driver.DmDriver");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Dameng JDBC driver was not found on the CLI classpath.", e);
            }
            return DriverManager.getConnection(
                    environment.jdbcUrl(),
                    environment.username(),
                    environment.password()
            );
        };
    }

    SqlScriptValidator(ConnectionProvider connectionProvider) {
        this(connectionProvider, null);
    }

    SqlScriptValidator(ConnectionProvider connectionProvider, Consumer<String> progressConsumer) {
        this(
                connectionProvider,
                progressConsumer,
                Math.max(1, Integer.getInteger(CONNECTION_ATTEMPTS_PROPERTY, DEFAULT_CONNECTION_ATTEMPTS)),
                Math.max(0L, Long.getLong(
                        CONNECTION_RETRY_DELAY_PROPERTY,
                        DEFAULT_CONNECTION_RETRY_DELAY_MILLIS
                ))
        );
    }

    SqlScriptValidator(
            ConnectionProvider connectionProvider,
            Consumer<String> progressConsumer,
            int connectionAttempts,
            long connectionRetryDelayMillis
    ) {
        this.connectionProvider = connectionProvider;
        this.progressConsumer = progressConsumer;
        this.connectionAttempts = Math.max(1, connectionAttempts);
        this.connectionRetryDelayMillis = Math.max(0L, connectionRetryDelayMillis);
    }

    @Override
    public SqlScriptValidationRun validate(
            List<SqlScriptMigrator.PlannedSqlScriptFile> files,
            DmValidationEnvironment environment
    ) {
        if (files == null || files.isEmpty()) {
            return SqlScriptValidationRun.notAttempted("No SQL script files were found.", List.of());
        }
        if (environment == null || !environment.validationEnabled()) {
            return SqlScriptValidationRun.notAttempted("DM_SQL_VALIDATION is not true; SQL script validation skipped.", List.of());
        }
        if (!environment.ready()) {
            return SqlScriptValidationRun.notAttempted(
                    "DM_SQL_VALIDATION is true but required variables are missing: " + environment.missingVariables(),
                    List.of()
            );
        }
        if (environment.deadline().expired()) {
            return SqlScriptValidationRun.timedOut(List.of(), List.of());
        }

        int timeoutSeconds = statementTimeoutSeconds();
        progress("SQL script validation initialized: files=" + files.size()
                + ", timeoutSeconds=" + timeoutSeconds
                + ", slowOperationLogMillis=" + slowOperationLogMillis());
        ScheduledExecutorService diagnostics = diagnosticsExecutor();
        List<SqlScriptValidationFailure> failures = new ArrayList<>();
        List<SqlScriptFileValidation> fileValidations = new ArrayList<>();
        Map<String, Integer> failedCreatedObjects = new LinkedHashMap<>();
        Map<String, DdlLocation> recentObjectDdl = new LinkedHashMap<>();
        int successCount = 0;
        long connectionStartedAt = System.nanoTime();
        ScheduledFuture<?> connectionWarning = scheduleSlowOperation(
                diagnostics,
                "Opening Dameng validation connection",
                connectionStartedAt
        );
        ExecutorService statementExecutor = statementExecutor();
        Connection connection = null;
        try {
            connection = openConnection(environment);
            cancel(connectionWarning);
            progress("Dameng validation connection opened: elapsedMs=" + elapsedMillis(connectionStartedAt));
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // DDL validation can still proceed on drivers that do not allow changing auto-commit.
            }
            SqlScriptValidationFailure schemaFailure = preflightSchemas(
                    connection,
                    files,
                    environment,
                    diagnostics,
                    statementExecutor
            );
            if (schemaFailure != null) {
                failures.add(schemaFailure);
                return new SqlScriptValidationRun(
                        true,
                        "VALIDATION_TIMEOUT".equals(schemaFailure.category())
                                ? "Dameng SQL script validation timed out."
                                : "Dameng SQL script validation failed during schema preflight.",
                        0,
                        1,
                        List.of(),
                        failures,
                        List.of("No SQL script statements were executed because schema preflight failed.")
                );
            }
            for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
                if (environment.deadline().expired()) {
                    failures.add(timeoutFailure(files.get(fileIndex), environment));
                    break;
                }
                SqlScriptMigrator.PlannedSqlScriptFile file = files.get(fileIndex);
                SqlScriptFileValidation fileValidation = validateFile(
                        connection,
                        file,
                        environment,
                        diagnostics,
                        statementExecutor,
                        failedCreatedObjects,
                        recentObjectDdl,
                        fileIndex + 1,
                        files.size()
                );
                fileValidations.add(fileValidation);
                successCount += fileValidation.successCount();
                failures.addAll(fileValidation.failures());
                if (fileValidation.failures().stream()
                        .anyMatch(failure -> "VALIDATION_TIMEOUT".equals(failure.category()))) {
                    break;
                }
            }
        } catch (ValidationConnectionTimeoutException e) {
            cancel(connectionWarning);
            progress("Dameng SQL script validation connection timed out.");
            return SqlScriptValidationRun.timedOut(fileValidations, failures);
        } catch (Exception e) {
            cancel(connectionWarning);
            progress("Dameng SQL script validation stopped: elapsedMs=" + elapsedMillis(connectionStartedAt)
                    + ", errorType=" + e.getClass().getSimpleName());
            return SqlScriptValidationRun.notAttempted(
                    "Dameng SQL script validation connection failed: " + redact(safeMessage(e), environment),
                    List.of("Dameng SQL script validation was skipped because the connection could not be opened.")
            );
        } finally {
            closeConnection(connection);
            statementExecutor.shutdownNow();
            if (diagnostics != null) {
                diagnostics.shutdownNow();
            }
        }

        String status = failures.stream().anyMatch(failure -> "VALIDATION_TIMEOUT".equals(failure.category()))
                ? "Dameng SQL script validation timed out."
                : failures.isEmpty()
                ? "Dameng SQL script validation passed."
                : "Dameng SQL script validation completed with failed SQL statements.";
        return new SqlScriptValidationRun(true, status, successCount, failures.size(), fileValidations, failures, List.of());
    }

    private Connection openConnection(DmValidationEnvironment environment) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= connectionAttempts; attempt++) {
            try {
                return openConnectionOnce(environment);
            } catch (ValidationConnectionTimeoutException e) {
                throw e;
            } catch (Exception e) {
                lastFailure = e;
            }
            if (attempt < connectionAttempts && !environment.deadline().expired()) {
                progress("Dameng validation connection attempt " + attempt + "/" + connectionAttempts
                        + " failed; retrying. Cause: " + redact(safeMessage(lastFailure), environment));
                sleepBeforeConnectionRetry(environment);
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("Dameng validation connection failed without a reported cause.")
                : lastFailure;
    }

    private Connection openConnectionOnce(DmValidationEnvironment environment) throws Exception {
        long remainingSeconds = environment.deadline().remainingSeconds();
        if (remainingSeconds <= 0L) {
            throw new ValidationConnectionTimeoutException();
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dm-sql-validation-connection");
            thread.setDaemon(true);
            return thread;
        });
        Future<Connection> connection = executor.submit(() -> connectionProvider.open(environment));
        try {
            return connection.get(remainingSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            connection.cancel(true);
            throw new ValidationConnectionTimeoutException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private void sleepBeforeConnectionRetry(DmValidationEnvironment environment)
            throws ValidationConnectionTimeoutException {
        if (connectionRetryDelayMillis <= 0L) {
            return;
        }
        long remainingMillis = TimeUnit.SECONDS.toMillis(environment.deadline().remainingSeconds());
        if (remainingMillis <= 0L) {
            throw new ValidationConnectionTimeoutException();
        }
        try {
            Thread.sleep(Math.min(connectionRetryDelayMillis, remainingMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidationConnectionTimeoutException();
        }
    }

    private SqlScriptFileValidation validateFile(
            Connection connection,
            SqlScriptMigrator.PlannedSqlScriptFile file,
            DmValidationEnvironment environment,
            ScheduledExecutorService diagnostics,
            ExecutorService statementExecutor,
            Map<String, Integer> failedCreatedObjects,
            Map<String, DdlLocation> recentObjectDdl,
            int fileIndex,
            int fileCount
    ) {
        long fileStartedAt = System.nanoTime();
        List<SqlScriptValidationFailure> failures = new ArrayList<>();
        int successCount = 0;
        int executableCount = executableStatementCount(file);
        progress("Validating SQL script [" + fileIndex + "/" + fileCount + "]: "
                + file.sourceDisplay()
                + ", schema=" + file.schema()
                + ", statements=" + file.statements().size()
                + ", executable=" + executableCount
                + ", manualReview=" + file.manualReviewStatementIndexes().size());
        if (executableCount == 0) {
            progress("Validated SQL script [" + fileIndex + "/" + fileCount + "]: "
                    + file.sourceDisplay()
                    + ", attempted=0, succeeded=0, failed=0, elapsedMs="
                    + elapsedMillis(fileStartedAt));
            return new SqlScriptFileValidation(file.outputDisplay(), 0, List.of());
        }
        try {
            applySchema(
                    connection,
                    file.schema(),
                    environment,
                    diagnostics,
                    statementExecutor,
                    file.sourceDisplay()
            );
        } catch (Exception e) {
            boolean timedOut = e instanceof StatementValidationTimeoutException;
            failures.add(new SqlScriptValidationFailure(
                    file.sourceDisplay(),
                    file.outputDisplay(),
                    file.schema(),
                    0,
                    timedOut ? "VALIDATION_TIMEOUT" : "INVALID_SCHEMA",
                    compact(redact(
                            timedOut
                                    ? safeMessage(e)
                                    : "Invalid Dameng schema " + file.schema() + ": " + safeMessage(e),
                            environment
                    )),
                    ""
            ));
            return new SqlScriptFileValidation(file.outputDisplay(), 0, failures);
        }
        int attemptedCount = 0;
        for (int i = 0; i < file.statements().size(); i++) {
            String sql = file.statements().get(i);
            if (!SqlScriptParser.executable(sql)) {
                continue;
            }
            int statementIndex = i + 1;
            if (file.manualReviewStatementIndexes().contains(statementIndex)) {
                continue;
            }
            if (environment.deadline().expired()) {
                failures.add(timeoutFailure(file, environment));
                break;
            }
            attemptedCount++;
            long statementStartedAt = System.nanoTime();
            String statementDescription = "Slow SQL script statement still running: file="
                    + file.sourceDisplay()
                    + ", statement=" + statementIndex + "/" + file.statements().size()
                    + ", sqlType=" + sqlType(sql)
                    + ", chars=" + sql.length()
                    + ", timeoutSeconds=" + effectiveStatementTimeoutSeconds(environment);
            ScheduledFuture<?> slowWarning = scheduleSlowOperation(
                    diagnostics,
                    statementDescription,
                    statementStartedAt
            );
            CreatedObject createdObject = createdObject(sql);
            String alteredObject = alteredObject(sql);
            boolean statementTimedOut = false;
            try {
                executeStatement(statementExecutor, connection, sql, createdObject, environment);
                if (createdObject != null) {
                    failedCreatedObjects.remove(createdObject.key());
                }
                if (!alteredObject.isBlank()) {
                    recentObjectDdl.put(
                            alteredObject,
                            new DdlLocation(file.sourceDisplay(), statementIndex, compact(sql))
                    );
                }
                successCount++;
            } catch (Exception e) {
                statementTimedOut = e instanceof StatementValidationTimeoutException;
                String blockedObject = blockedObject(sql, failedCreatedObjects.keySet());
                String category = statementTimedOut
                        ? "VALIDATION_TIMEOUT"
                        : blockedObject.isBlank() ? classify(e, sql) : "BLOCKED_BY_PRIOR_FAILURE";
                String errorSummary = compact(redact(safeMessage(e), environment));
                if ("ORIGINAL_SQL".equals(category)) {
                    if (containsDuplicateColumnDefault(sql)) {
                        errorSummary = compact(
                                "Original SQL defines multiple DEFAULT clauses for one column; "
                                        + "fix the source SQL before migration. " + errorSummary
                        );
                    } else if (containsContradictoryColumnNullability(sql)) {
                        errorSummary = compact(
                                "Original SQL defines both NULL and NOT NULL for one column; "
                                        + "fix the source SQL before migration. " + errorSummary
                        );
                    }
                }
                if ("OBJECT_DEFINITION_CHANGED".equals(category)) {
                    errorSummary = objectDefinitionChangedSummary(errorSummary, e, recentObjectDdl);
                }
                if (!blockedObject.isBlank()) {
                    errorSummary = compact("Blocked by failed statement "
                            + failedCreatedObjects.get(blockedObject)
                            + " for object " + blockedObject + ": " + errorSummary);
                }
                failures.add(new SqlScriptValidationFailure(
                        file.sourceDisplay(),
                        file.outputDisplay(),
                        file.schema(),
                        statementIndex,
                        category,
                        errorSummary,
                        compact(sql)
                ));
                if (!statementTimedOut && createdObject != null) {
                    failedCreatedObjects.putIfAbsent(createdObject.key(), statementIndex);
                }
                progress("SQL script statement failed: file=" + file.sourceDisplay()
                        + ", statement=" + statementIndex + "/" + file.statements().size()
                        + ", sqlType=" + sqlType(sql)
                        + ", chars=" + sql.length()
                        + ", elapsedMs=" + elapsedMillis(statementStartedAt)
                        + ", errorType=" + e.getClass().getSimpleName());
            } finally {
                cancel(slowWarning);
            }
            if (statementTimedOut) {
                break;
            }
            if (attemptedCount % STATEMENT_PROGRESS_INTERVAL == 0) {
                progress("SQL script statement progress: file=" + file.sourceDisplay()
                        + ", attempted=" + attemptedCount + "/" + executableCount
                        + ", succeeded=" + successCount
                        + ", failed=" + failures.size()
                        + ", elapsedMs=" + elapsedMillis(fileStartedAt));
            }
        }
        progress("Validated SQL script [" + fileIndex + "/" + fileCount + "]: "
                + file.sourceDisplay()
                + ", attempted=" + attemptedCount
                + ", succeeded=" + successCount
                + ", failed=" + failures.size()
                + ", elapsedMs=" + elapsedMillis(fileStartedAt));
        return new SqlScriptFileValidation(file.outputDisplay(), successCount, failures);
    }

    private void executeStatement(
            ExecutorService executor,
            Connection connection,
            String sql,
            CreatedObject createdObject,
            DmValidationEnvironment environment
    ) throws Exception {
        int timeoutSeconds = effectiveStatementTimeoutSeconds(environment);
        AtomicReference<Statement> activeStatement = new AtomicReference<>();
        Future<Boolean> execution = executor.submit(() -> {
            try (Statement statement = connection.createStatement()) {
                activeStatement.set(statement);
                configureStatement(statement, environment);
                statement.execute(sql);
                validateCreatedObject(connection, statement, createdObject, environment);
                return true;
            } finally {
                activeStatement.set(null);
            }
        });
        try {
            execution.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            execution.cancel(true);
            cancelTimedOutStatement(connection, activeStatement.get());
            throw new StatementValidationTimeoutException(
                    "Dameng SQL statement exceeded the adapter hard timeout of "
                            + timeoutSeconds
                            + " seconds; cancellation was requested and validation stopped.",
                    e
            );
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            execution.cancel(true);
            cancelTimedOutStatement(connection, activeStatement.get());
            Thread.currentThread().interrupt();
            throw new StatementValidationTimeoutException(
                    "Dameng SQL statement validation was interrupted; cancellation was requested.",
                    e
            );
        }
    }

    private void validateCreatedObject(
            Connection connection,
            Statement statement,
            CreatedObject createdObject,
            DmValidationEnvironment environment
    ) throws SQLException {
        if (createdObject == null) {
            return;
        }
        String warnings = warningSummary(statement);
        CreatedObjectStatus objectStatus;
        try {
            objectStatus = createdObjectStatus(connection, createdObject, environment);
        } catch (SQLException e) {
            throw new ObjectStatusValidationException(
                    "Unable to validate " + createdObject.displayName()
                            + " in the active schema: " + safeMessage(e),
                    e
            );
        }
        if (objectStatus == null) {
            throw new ObjectStatusValidationException(
                    "Created object status was not found for " + createdObject.displayName()
                            + " in the active schema."
            );
        }
        if ("VALID".equalsIgnoreCase(objectStatus.status())) {
            return;
        }
        String message = "Created object " + createdObject.displayName()
                + " is " + objectStatus.status() + ".";
        if (!warnings.isBlank()) {
            message += " JDBC warning: " + warnings;
        }
        String compileDiagnostic = invalidCreatedObjectCompileDiagnostic(
                connection,
                createdObject,
                environment
        );
        if (!compileDiagnostic.isBlank()) {
            message += " Recompile diagnostic: " + compileDiagnostic;
        }
        throw new InvalidCreatedObjectException(message);
    }

    private void cancelTimedOutStatement(Connection connection, Statement statement) {
        if (statement != null) {
            startDaemon("dm-sql-validation-statement-cancel", () -> {
                try {
                    statement.cancel();
                } catch (SQLException ignored) {
                    // The hard timeout must return even if the driver cannot cancel the statement.
                }
            });
        }
        startDaemon("dm-sql-validation-connection-abort", () -> {
            try {
                connection.abort(command -> startDaemon("dm-sql-validation-abort-worker", command));
            } catch (SQLException | RuntimeException ignored) {
                // Closing the validation connection remains best-effort after a hard timeout.
            }
        });
    }

    private SqlScriptValidationFailure preflightSchemas(
            Connection connection,
            List<SqlScriptMigrator.PlannedSqlScriptFile> files,
            DmValidationEnvironment environment,
            ScheduledExecutorService diagnostics,
            ExecutorService statementExecutor
    ) {
        Set<String> schemas = new LinkedHashSet<>();
        for (SqlScriptMigrator.PlannedSqlScriptFile file : files) {
            if (file.schema() != null && !file.schema().isBlank()) {
                schemas.add(file.schema());
            }
        }
        for (String schema : schemas) {
            if (environment.deadline().expired()) {
                return new SqlScriptValidationFailure(
                        "(schema-preflight)", "", schema, 0, "VALIDATION_TIMEOUT",
                        "Dameng SQL validation exceeded the configured total timeout.", ""
                );
            }
            try {
                applySchema(
                        connection,
                        schema,
                        environment,
                        diagnostics,
                        statementExecutor,
                        "(schema-preflight)"
                );
            } catch (Exception e) {
                boolean timedOut = e instanceof StatementValidationTimeoutException;
                return new SqlScriptValidationFailure(
                        "(schema-preflight)",
                        "",
                        schema,
                        0,
                        timedOut ? "VALIDATION_TIMEOUT" : "INVALID_SCHEMA",
                        compact(redact(
                                timedOut
                                        ? safeMessage(e)
                                        : "Invalid Dameng schema " + schema + ": " + safeMessage(e),
                                environment
                        )),
                        ""
                );
            }
        }
        return null;
    }

    private SqlScriptValidationFailure timeoutFailure(
            SqlScriptMigrator.PlannedSqlScriptFile file,
            DmValidationEnvironment environment
    ) {
        return new SqlScriptValidationFailure(
                file.sourceDisplay(),
                file.outputDisplay(),
                file.schema(),
                0,
                "VALIDATION_TIMEOUT",
                "Dameng SQL validation exceeded the configured total timeout of "
                        + environment.totalTimeoutSeconds() + " seconds.",
                ""
        );
    }

    private void applySchema(
            Connection connection,
            String schema,
            DmValidationEnvironment environment,
            ScheduledExecutorService diagnostics,
            ExecutorService statementExecutor,
            String sourceDisplay
    ) throws Exception {
        if (schema == null || schema.isBlank()) {
            return;
        }
        long startedAt = System.nanoTime();
        ScheduledFuture<?> slowWarning = scheduleSlowOperation(
                diagnostics,
                "Slow schema selection still running: file=" + sourceDisplay,
                startedAt
        );
        try {
            executeStatement(
                    statementExecutor,
                    connection,
                    "set schema " + quotedIdentifier(schema),
                    null,
                    environment
            );
        } finally {
            cancel(slowWarning);
        }
    }

    private void configureStatement(Statement statement, DmValidationEnvironment environment) {
        try {
            statement.setQueryTimeout(effectiveStatementTimeoutSeconds(environment));
        } catch (SQLException ignored) {
            // Statement timeouts are best-effort for driver compatibility.
        }
    }

    private int effectiveStatementTimeoutSeconds(DmValidationEnvironment environment) {
        long remaining = environment == null ? statementTimeoutSeconds() : environment.deadline().remainingSeconds();
        return (int) Math.max(1L, Math.min(statementTimeoutSeconds(), Math.min(Integer.MAX_VALUE, remaining)));
    }

    private CreatedObject createdObject(String sql) {
        Matcher matcher = CREATE_ROUTINE_PATTERN.matcher(sqlWithoutComments(sql));
        if (!matcher.find()) {
            return null;
        }
        String type = matcher.group(1).toUpperCase(Locale.ROOT);
        String sqlReference = matcher.group(2);
        String reference = sqlReference.replace("`", "").replace("\"", "");
        int separator = reference.lastIndexOf('.');
        String owner = separator < 0 ? "" : reference.substring(0, separator);
        String name = separator < 0 ? reference : reference.substring(separator + 1);
        return name.isBlank() ? null : new CreatedObject(type, owner, name, sqlReference);
    }

    private CreatedObjectStatus createdObjectStatus(
            Connection connection,
            CreatedObject object,
            DmValidationEnvironment environment
    ) throws SQLException {
        String ownerPredicate = object.owner().isBlank()
                ? "OWNER = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)"
                : "UPPER(OWNER) = UPPER(?)";
        String sql = "SELECT STATUS FROM ALL_OBJECTS WHERE "
                + ownerPredicate
                + " AND UPPER(OBJECT_NAME) = UPPER(?)"
                + " AND OBJECT_TYPE = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            configureStatement(statement, environment);
            int parameterIndex = 1;
            if (!object.owner().isBlank()) {
                statement.setString(parameterIndex++, object.owner());
            }
            statement.setString(parameterIndex++, object.name());
            statement.setString(parameterIndex, object.type());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new CreatedObjectStatus(resultSet.getString(1));
            }
        }
    }

    private String warningSummary(Statement statement) throws SQLException {
        List<String> warnings = new ArrayList<>();
        SQLWarning warning = statement.getWarnings();
        while (warning != null && warnings.size() < 10) {
            String message = warning.getMessage();
            if (message != null && !message.isBlank()) {
                warnings.add(compact(message));
            }
            warning = warning.getNextWarning();
        }
        return String.join(" | ", warnings);
    }

    private String invalidCreatedObjectCompileDiagnostic(
            Connection connection,
            CreatedObject object,
            DmValidationEnvironment environment
    ) {
        if (!Set.of("PROCEDURE", "FUNCTION", "TRIGGER").contains(object.type())) {
            return "";
        }
        try (Statement statement = connection.createStatement()) {
            configureStatement(statement, environment);
            statement.execute("ALTER " + object.type() + " " + object.sqlReference() + " COMPILE");
            return warningSummary(statement);
        } catch (Exception e) {
            return safeMessage(e);
        }
    }

    private String blockedObject(String sql, Set<String> failedObjects) {
        Matcher matcher = CALL_PATTERN.matcher(sqlWithoutComments(sql));
        if (!matcher.find()) {
            return "";
        }
        String calledObject = normalizedObject(matcher.group(1));
        if (failedObjects.contains(calledObject)) {
            return calledObject;
        }
        int separator = calledObject.lastIndexOf('.');
        String simpleName = separator < 0 ? calledObject : calledObject.substring(separator + 1);
        return failedObjects.stream()
                .filter(object -> object.equals(simpleName) || object.endsWith("." + simpleName))
                .findFirst()
                .orElse("");
    }

    private String normalizedObject(String value) {
        return value == null ? "" : value.replace("`", "").replace("\"", "")
                .toUpperCase(Locale.ROOT);
    }

    private String alteredObject(String sql) {
        Matcher matcher = Pattern.compile(
                "(?is)\\b(?:ALTER|CREATE|DROP|TRUNCATE)\\s+TABLE\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?"
                        + "([`\"\\w.$-]+)"
        ).matcher(sqlWithoutComments(sql));
        return matcher.find() ? normalizedObject(matcher.group(1)) : "";
    }

    private String sqlWithoutComments(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(sql.length());
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean backtickQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    result.append(current);
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    result.append("  ");
                    blockComment = false;
                    index++;
                } else {
                    result.append(current == '\n' || current == '\r' ? current : ' ');
                }
                continue;
            }
            if (singleQuoted || doubleQuoted || backtickQuoted) {
                result.append(current);
                char quote = singleQuoted ? '\'' : doubleQuoted ? '"' : '`';
                if (current == '\\' && next != '\0' && !backtickQuoted) {
                    result.append(next);
                    index++;
                } else if (current == quote && next == quote) {
                    result.append(next);
                    index++;
                } else if (current == quote) {
                    singleQuoted = false;
                    doubleQuoted = false;
                    backtickQuoted = false;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                result.append("  ");
                lineComment = true;
                index++;
            } else if (current == '#') {
                result.append(' ');
                lineComment = true;
            } else if (current == '/' && next == '*') {
                result.append("  ");
                blockComment = true;
                index++;
            } else {
                result.append(current);
                if (current == '\'') {
                    singleQuoted = true;
                } else if (current == '"') {
                    doubleQuoted = true;
                } else if (current == '`') {
                    backtickQuoted = true;
                }
            }
        }
        return result.toString();
    }

    private String objectDefinitionChangedSummary(
            String summary,
            Exception exception,
            Map<String, DdlLocation> recentObjectDdl
    ) {
        String message = safeMessage(exception);
        Matcher object = Pattern.compile("(?is)(?:对象定义|object definition)\\s*\\[([^]]+)]").matcher(message);
        String objectName = object.find() ? normalizedObject(object.group(1)) : "";
        DdlLocation location = recentObjectDdl.get(objectName);
        if (location == null && !objectName.isBlank()) {
            location = recentObjectDdl.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith("." + objectName)
                            || objectName.endsWith("." + entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        String advice = "达梦错误 -7184：过程执行计划引用的对象定义已变化；"
                + "请检查过程创建后、调用前的 DDL，并重新编译过程。工具不会修改 PL_SQL_STRIP 或自动重试。";
        if (location == null) {
            return compact(summary + " | " + advice);
        }
        return compact(summary
                + " | 最近相关 DDL："
                + location.sourceFile()
                + " 第 "
                + location.statementIndex()
                + " 条 SQL："
                + location.sqlSummary()
                + " | "
                + advice);
    }

    static int statementTimeoutSeconds() {
        Integer propertyValue = Integer.getInteger(STATEMENT_TIMEOUT_PROPERTY);
        if (propertyValue != null && propertyValue > 0) {
            return propertyValue;
        }
        String envValue = System.getenv(STATEMENT_TIMEOUT_ENV);
        if (envValue != null && !envValue.isBlank()) {
            try {
                int parsed = Integer.parseInt(envValue.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Invalid environment values fall back to the default.
            }
        }
        return DEFAULT_STATEMENT_TIMEOUT_SECONDS;
    }

    private ScheduledExecutorService diagnosticsExecutor() {
        if (progressConsumer == null) {
            return null;
        }
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dm-sql-validation-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
    }

    private ExecutorService statementExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dm-sql-validation-statement");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void closeConnection(Connection connection) {
        if (connection == null) {
            return;
        }
        ExecutorService closer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dm-sql-validation-connection-close");
            thread.setDaemon(true);
            return thread;
        });
        Future<?> close = closer.submit(() -> {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Validation has already finished; connection cleanup is best-effort.
            }
        });
        try {
            close.get(CONNECTION_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException ignored) {
            close.cancel(true);
            progress("Dameng validation connection close did not finish within "
                    + CONNECTION_CLOSE_TIMEOUT_SECONDS + " seconds.");
        } catch (InterruptedException e) {
            close.cancel(true);
            Thread.currentThread().interrupt();
        } finally {
            closer.shutdownNow();
        }
    }

    private void startDaemon(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
    }

    private ScheduledFuture<?> scheduleSlowOperation(
            ScheduledExecutorService diagnostics,
            String description,
            long startedAt
    ) {
        if (diagnostics == null) {
            return null;
        }
        long thresholdMillis = slowOperationLogMillis();
        return diagnostics.scheduleAtFixedRate(
                () -> progress(description + ", elapsedMs=" + elapsedMillis(startedAt)),
                thresholdMillis,
                SLOW_OPERATION_REPEAT_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    private void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private int executableStatementCount(SqlScriptMigrator.PlannedSqlScriptFile file) {
        int count = 0;
        for (int i = 0; i < file.statements().size(); i++) {
            if (SqlScriptParser.executable(file.statements().get(i))
                    && !file.manualReviewStatementIndexes().contains(i + 1)) {
                count++;
            }
        }
        return count;
    }

    private String sqlType(String sql) {
        if (sql == null || sql.isBlank()) {
            return "UNKNOWN";
        }
        int offset = 0;
        while (offset < sql.length()) {
            while (offset < sql.length() && Character.isWhitespace(sql.charAt(offset))) {
                offset++;
            }
            if (offset + 1 < sql.length() && sql.startsWith("--", offset)) {
                int newline = sql.indexOf('\n', offset + 2);
                offset = newline < 0 ? sql.length() : newline + 1;
                continue;
            }
            if (offset < sql.length() && sql.charAt(offset) == '#') {
                int newline = sql.indexOf('\n', offset + 1);
                offset = newline < 0 ? sql.length() : newline + 1;
                continue;
            }
            if (offset + 1 < sql.length() && sql.startsWith("/*", offset)) {
                int end = sql.indexOf("*/", offset + 2);
                offset = end < 0 ? sql.length() : end + 2;
                continue;
            }
            break;
        }
        int end = offset;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        return end == offset ? "UNKNOWN" : sql.substring(offset, end).toUpperCase(Locale.ROOT);
    }

    private long slowOperationLogMillis() {
        Long configured = Long.getLong(SLOW_OPERATION_LOG_PROPERTY);
        return configured != null && configured > 0 ? configured : DEFAULT_SLOW_OPERATION_LOG_MILLIS;
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private void progress(String message) {
        if (progressConsumer == null) {
            return;
        }
        try {
            progressConsumer.accept(message);
        } catch (RuntimeException ignored) {
            // Progress logging must never stop SQL script validation.
        }
    }

    static String quotedIdentifier(String identifier) {
        String quote = Character.toString((char) 34);
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private String classify(Exception e, String sql) {
        if (e instanceof InvalidCreatedObjectException) {
            String message = safeMessage(e).toLowerCase(Locale.ROOT);
            if (message.contains("无效的表") || message.contains("无效的视图")
                    || message.contains("无效的列") || message.contains("无效的模式")) {
                return "TEST_SCHEMA_OBJECT";
            }
            if (message.contains("无效的函数") || message.contains("无法解析的成员访问表达式")) {
                return "TEST_SCHEMA_FUNCTION";
            }
            if (message.contains("语法") || message.contains("syntax")) {
                return "SQL_SYNTAX";
            }
            return "INVALID_DATABASE_OBJECT";
        }
        if (e instanceof ObjectStatusValidationException) {
            return "OBJECT_STATUS_VALIDATION_FAILED";
        }
        String message = safeMessage(e).toLowerCase(Locale.ROOT);
        if (damengErrorCode(e) == -7184
                || damengErrorCode(e) == 7184
                || message.contains("-7184")
                || message.contains("对象定义") && message.contains("版本")) {
            return "OBJECT_DEFINITION_CHANGED";
        }
        if (message.contains("无效的表") || message.contains("无效的视图")
                || message.contains("无效的列") || message.contains("无效的模式")) {
            return "TEST_SCHEMA_OBJECT";
        }
        if (message.contains("无效的函数") || message.contains("无法解析的成员访问表达式")) {
            return "TEST_SCHEMA_FUNCTION";
        }
        if (message.contains("语法") || message.contains("syntax")) {
            if (containsDuplicateColumnDefault(sql) || containsContradictoryColumnNullability(sql)) {
                return "ORIGINAL_SQL";
            }
            return "SQL_SYNTAX";
        }
        return "SQL_EXECUTION";
    }

    private boolean containsDuplicateColumnDefault(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String uncommentedSql = sqlWithoutComments(sql);
        Matcher createTable = Pattern.compile(
                "(?is)\\bCREATE\\s+(?:(?:GLOBAL\\s+)?TEMPORARY\\s+)?TABLE\\b"
        ).matcher(uncommentedSql);
        while (createTable.find()) {
            int openIndex = nextUnquotedCharacter(uncommentedSql, createTable.end(), '(');
            if (openIndex < 0) {
                continue;
            }
            int closeIndex = matchingParenthesis(uncommentedSql, openIndex);
            if (closeIndex < 0) {
                continue;
            }
            for (String definition : splitTopLevelComma(uncommentedSql.substring(openIndex + 1, closeIndex))) {
                if (keywordCount(definition, "DEFAULT") > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsContradictoryColumnNullability(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String uncommentedSql = sqlWithoutComments(sql);
        Matcher createTable = Pattern.compile(
                "(?is)\\bCREATE\\s+(?:(?:GLOBAL\\s+)?TEMPORARY\\s+)?TABLE\\b"
        ).matcher(uncommentedSql);
        while (createTable.find()) {
            int openIndex = nextUnquotedCharacter(uncommentedSql, createTable.end(), '(');
            if (openIndex < 0) {
                continue;
            }
            int closeIndex = matchingParenthesis(uncommentedSql, openIndex);
            if (closeIndex < 0) {
                continue;
            }
            for (String definition : splitTopLevelComma(uncommentedSql.substring(openIndex + 1, closeIndex))) {
                if (hasContradictoryNullability(definition)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasContradictoryNullability(String definition) {
        boolean explicitNull = false;
        boolean notNull = false;
        String previousWord = "";
        char quote = '\0';
        for (int index = 0; index < definition.length();) {
            char current = definition.charAt(index);
            if (quote != '\0') {
                if (current == quote) {
                    if (index + 1 < definition.length() && definition.charAt(index + 1) == quote) {
                        index += 2;
                        continue;
                    }
                    quote = '\0';
                }
                index++;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                index++;
                continue;
            }
            if (!isSqlWordCharacter(current)) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < definition.length() && isSqlWordCharacter(definition.charAt(end))) {
                end++;
            }
            String word = definition.substring(index, end).toUpperCase(Locale.ROOT);
            if ("NULL".equals(word)) {
                if ("NOT".equals(previousWord)) {
                    notNull = true;
                } else if (!"DEFAULT".equals(previousWord) && !"IS".equals(previousWord)) {
                    explicitNull = true;
                }
            }
            if (explicitNull && notNull) {
                return true;
            }
            previousWord = word;
            index = end;
        }
        return false;
    }

    private int nextUnquotedCharacter(String text, int start, char target) {
        char quote = '\0';
        for (int index = Math.max(0, start); index < text.length(); index++) {
            char current = text.charAt(index);
            if (quote != '\0') {
                if (current == quote) {
                    if (index + 1 < text.length() && text.charAt(index + 1) == quote) {
                        index++;
                    } else {
                        quote = '\0';
                    }
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == target) {
                return index;
            }
        }
        return -1;
    }

    private int matchingParenthesis(String text, int openIndex) {
        int depth = 0;
        char quote = '\0';
        for (int index = openIndex; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quote != '\0') {
                if (current == quote) {
                    if (index + 1 < text.length() && text.charAt(index + 1) == quote) {
                        index++;
                    } else {
                        quote = '\0';
                    }
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private List<String> splitTopLevelComma(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        char quote = '\0';
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quote != '\0') {
                if (current == quote) {
                    if (index + 1 < text.length() && text.charAt(index + 1) == quote) {
                        index++;
                    } else {
                        quote = '\0';
                    }
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
            } else if (current == ',' && depth == 0) {
                parts.add(text.substring(start, index));
                start = index + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private int keywordCount(String text, String keyword) {
        int count = 0;
        char quote = '\0';
        for (int index = 0; index < text.length();) {
            char current = text.charAt(index);
            if (quote != '\0') {
                if (current == quote) {
                    if (index + 1 < text.length() && text.charAt(index + 1) == quote) {
                        index += 2;
                        continue;
                    }
                    quote = '\0';
                }
                index++;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                index++;
                continue;
            }
            int end = index + keyword.length();
            if (end <= text.length()
                    && text.regionMatches(true, index, keyword, 0, keyword.length())
                    && (index == 0 || !isSqlWordCharacter(text.charAt(index - 1)))
                    && (end == text.length() || !isSqlWordCharacter(text.charAt(end)))) {
                count++;
                index = end;
                continue;
            }
            index++;
        }
        return count;
    }

    private boolean isSqlWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private int damengErrorCode(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException && sqlException.getErrorCode() != 0) {
                return sqlException.getErrorCode();
            }
            current = current.getCause();
        }
        return 0;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if ((message == null || message.isBlank()) && e.getCause() != null) {
            message = e.getCause().getMessage();
        }
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private String redact(String message, DmValidationEnvironment environment) {
        String redacted = message == null ? "" : message;
        if (environment == null) {
            return redacted;
        }
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

    private String compact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 240) {
            return compact;
        }
        return compact.substring(0, 237) + "...";
    }

    private record CreatedObject(String type, String owner, String name, String sqlReference) {
        String key() {
            String normalizedName = name.replace("`", "").replace("\"", "").toUpperCase(Locale.ROOT);
            if (owner == null || owner.isBlank()) {
                return normalizedName;
            }
            return owner.replace("`", "").replace("\"", "").toUpperCase(Locale.ROOT)
                    + "." + normalizedName;
        }

        String displayName() {
            return type + " " + (owner == null || owner.isBlank() ? "" : owner + ".") + name;
        }
    }

    private record CreatedObjectStatus(String status) {
    }

    private record DdlLocation(String sourceFile, int statementIndex, String sqlSummary) {
    }

    private static final class InvalidCreatedObjectException extends SQLException {
        private InvalidCreatedObjectException(String message) {
            super(message);
        }
    }

    private static final class ObjectStatusValidationException extends SQLException {
        private ObjectStatusValidationException(String message) {
            super(message);
        }

        private ObjectStatusValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class StatementValidationTimeoutException extends SQLException {
        private StatementValidationTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    interface ConnectionProvider {
        Connection open(DmValidationEnvironment environment) throws Exception;
    }

    private static final class ValidationConnectionTimeoutException extends Exception {
    }
}

record SqlScriptValidationRun(
        boolean attempted,
        String status,
        int successCount,
        int failureCount,
        List<SqlScriptFileValidation> fileValidations,
        List<SqlScriptValidationFailure> failures,
        List<String> warnings
) {
    SqlScriptValidationRun {
        fileValidations = List.copyOf(fileValidations == null ? List.of() : fileValidations);
        failures = List.copyOf(failures == null ? List.of() : failures);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    static SqlScriptValidationRun notAttempted(String status, List<String> warnings) {
        return new SqlScriptValidationRun(false, status, 0, 0, List.of(), List.of(), warnings);
    }

    static SqlScriptValidationRun timedOut(
            List<SqlScriptFileValidation> fileValidations,
            List<SqlScriptValidationFailure> existingFailures
    ) {
        List<SqlScriptValidationFailure> failures = new ArrayList<>(existingFailures == null ? List.of() : existingFailures);
        failures.add(new SqlScriptValidationFailure(
                "(validation)", "", "", 0, "VALIDATION_TIMEOUT",
                "Dameng SQL validation exceeded the configured total timeout.", ""
        ));
        int successes = (fileValidations == null ? List.<SqlScriptFileValidation>of() : fileValidations).stream()
                .mapToInt(SqlScriptFileValidation::successCount)
                .sum();
        return new SqlScriptValidationRun(
                true, "Dameng SQL script validation timed out.", successes, failures.size(),
                fileValidations, failures, List.of()
        );
    }
}

record SqlScriptFileValidation(
        String outputFile,
        int successCount,
        List<SqlScriptValidationFailure> failures
) {
    SqlScriptFileValidation {
        failures = List.copyOf(failures == null ? List.of() : failures);
    }

    int failureCount() {
        return failures.size();
    }
}
