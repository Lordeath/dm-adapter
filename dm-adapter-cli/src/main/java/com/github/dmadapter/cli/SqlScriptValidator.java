package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlScriptValidationFailure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

class SqlScriptValidator implements SqlScriptMigrator.Validator {
    private static final int DEFAULT_STATEMENT_TIMEOUT_SECONDS = 180;
    private static final long DEFAULT_SLOW_OPERATION_LOG_MILLIS = 5_000L;
    private static final long SLOW_OPERATION_REPEAT_MILLIS = 30_000L;
    private static final int STATEMENT_PROGRESS_INTERVAL = 100;
    private static final String STATEMENT_TIMEOUT_PROPERTY = "dm.adapter.sqlScriptStatementTimeoutSeconds";
    private static final String STATEMENT_TIMEOUT_ENV = "DM_SQL_SCRIPT_VALIDATION_TIMEOUT_SECONDS";
    private static final String SLOW_OPERATION_LOG_PROPERTY = "dm.adapter.sqlScriptSlowOperationLogMillis";

    private final ConnectionProvider connectionProvider;
    private final Consumer<String> progressConsumer;

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
        this.connectionProvider = connectionProvider;
        this.progressConsumer = progressConsumer;
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

        int timeoutSeconds = statementTimeoutSeconds();
        progress("SQL script validation initialized: files=" + files.size()
                + ", timeoutSeconds=" + timeoutSeconds
                + ", slowOperationLogMillis=" + slowOperationLogMillis());
        ScheduledExecutorService diagnostics = diagnosticsExecutor();
        List<SqlScriptValidationFailure> failures = new ArrayList<>();
        List<SqlScriptFileValidation> fileValidations = new ArrayList<>();
        int successCount = 0;
        long connectionStartedAt = System.nanoTime();
        ScheduledFuture<?> connectionWarning = scheduleSlowOperation(
                diagnostics,
                "Opening Dameng validation connection",
                connectionStartedAt
        );
        try (Connection connection = connectionProvider.open(environment)) {
            cancel(connectionWarning);
            progress("Dameng validation connection opened: elapsedMs=" + elapsedMillis(connectionStartedAt));
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // DDL validation can still proceed on drivers that do not allow changing auto-commit.
            }
            for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
                SqlScriptMigrator.PlannedSqlScriptFile file = files.get(fileIndex);
                SqlScriptFileValidation fileValidation = validateFile(
                        connection,
                        file,
                        environment,
                        diagnostics,
                        fileIndex + 1,
                        files.size()
                );
                fileValidations.add(fileValidation);
                successCount += fileValidation.successCount();
                failures.addAll(fileValidation.failures());
            }
        } catch (Exception e) {
            cancel(connectionWarning);
            progress("Dameng SQL script validation stopped: elapsedMs=" + elapsedMillis(connectionStartedAt)
                    + ", errorType=" + e.getClass().getSimpleName());
            return SqlScriptValidationRun.notAttempted(
                    "Dameng SQL script validation connection failed: " + redact(safeMessage(e), environment),
                    List.of("Dameng SQL script validation was skipped because the connection could not be opened.")
            );
        } finally {
            if (diagnostics != null) {
                diagnostics.shutdownNow();
            }
        }

        String status = failures.isEmpty()
                ? "Dameng SQL script validation passed."
                : "Dameng SQL script validation completed with failed SQL statements.";
        return new SqlScriptValidationRun(true, status, successCount, failures.size(), fileValidations, failures, List.of());
    }

    private SqlScriptFileValidation validateFile(
            Connection connection,
            SqlScriptMigrator.PlannedSqlScriptFile file,
            DmValidationEnvironment environment,
            ScheduledExecutorService diagnostics,
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
        try {
            applySchema(connection, file.schema(), diagnostics, file.sourceDisplay());
        } catch (Exception e) {
            failures.add(new SqlScriptValidationFailure(
                    file.sourceDisplay(),
                    file.outputDisplay(),
                    file.schema(),
                    0,
                    classify(e),
                    compact(redact(safeMessage(e), environment)),
                    ""
            ));
            progress("SQL script schema selection failed [" + fileIndex + "/" + fileCount + "]: "
                    + file.sourceDisplay()
                    + ", elapsedMs=" + elapsedMillis(fileStartedAt)
                    + ", errorType=" + e.getClass().getSimpleName());
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
            attemptedCount++;
            long statementStartedAt = System.nanoTime();
            String statementDescription = "Slow SQL script statement still running: file="
                    + file.sourceDisplay()
                    + ", statement=" + statementIndex + "/" + file.statements().size()
                    + ", sqlType=" + sqlType(sql)
                    + ", chars=" + sql.length()
                    + ", timeoutSeconds=" + statementTimeoutSeconds();
            ScheduledFuture<?> slowWarning = scheduleSlowOperation(
                    diagnostics,
                    statementDescription,
                    statementStartedAt
            );
            try (Statement statement = connection.createStatement()) {
                configureStatement(statement);
                statement.execute(sql);
                successCount++;
            } catch (Exception e) {
                failures.add(new SqlScriptValidationFailure(
                        file.sourceDisplay(),
                        file.outputDisplay(),
                        file.schema(),
                        statementIndex,
                        classify(e),
                        compact(redact(safeMessage(e), environment)),
                        compact(sql)
                ));
                progress("SQL script statement failed: file=" + file.sourceDisplay()
                        + ", statement=" + statementIndex + "/" + file.statements().size()
                        + ", sqlType=" + sqlType(sql)
                        + ", chars=" + sql.length()
                        + ", elapsedMs=" + elapsedMillis(statementStartedAt)
                        + ", errorType=" + e.getClass().getSimpleName());
            } finally {
                cancel(slowWarning);
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

    private void applySchema(
            Connection connection,
            String schema,
            ScheduledExecutorService diagnostics,
            String sourceDisplay
    ) throws SQLException {
        if (schema == null || schema.isBlank()) {
            return;
        }
        long startedAt = System.nanoTime();
        ScheduledFuture<?> slowWarning = scheduleSlowOperation(
                diagnostics,
                "Slow schema selection still running: file=" + sourceDisplay,
                startedAt
        );
        try (Statement statement = connection.createStatement()) {
            configureStatement(statement);
            statement.execute("set schema " + quotedIdentifier(schema));
        } finally {
            cancel(slowWarning);
        }
    }

    private void configureStatement(Statement statement) {
        try {
            statement.setQueryTimeout(statementTimeoutSeconds());
        } catch (SQLException ignored) {
            // Statement timeouts are best-effort for driver compatibility.
        }
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

    private String classify(Exception e) {
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
        return "SQL_EXECUTION";
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

    interface ConnectionProvider {
        Connection open(DmValidationEnvironment environment) throws Exception;
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
