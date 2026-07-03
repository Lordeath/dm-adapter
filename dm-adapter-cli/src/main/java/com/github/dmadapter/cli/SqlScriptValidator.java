package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlScriptValidationFailure;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class SqlScriptValidator implements SqlScriptMigrator.Validator {
    private static final int DEFAULT_STATEMENT_TIMEOUT_SECONDS = 30;

    private final ConnectionProvider connectionProvider;

    SqlScriptValidator() {
        this(environment -> {
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
        });
    }

    SqlScriptValidator(ConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
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

        List<SqlScriptValidationFailure> failures = new ArrayList<>();
        List<SqlScriptFileValidation> fileValidations = new ArrayList<>();
        int successCount = 0;
        try (Connection connection = connectionProvider.open(environment)) {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // DDL validation can still proceed on drivers that do not allow changing auto-commit.
            }
            for (SqlScriptMigrator.PlannedSqlScriptFile file : files) {
                SqlScriptFileValidation fileValidation = validateFile(connection, file, environment);
                fileValidations.add(fileValidation);
                successCount += fileValidation.successCount();
                failures.addAll(fileValidation.failures());
            }
        } catch (Exception e) {
            return SqlScriptValidationRun.notAttempted(
                    "Dameng SQL script validation connection failed: " + redact(safeMessage(e), environment),
                    List.of("Dameng SQL script validation was skipped because the connection could not be opened.")
            );
        }

        String status = failures.isEmpty()
                ? "Dameng SQL script validation passed."
                : "Dameng SQL script validation completed with failed SQL statements.";
        return new SqlScriptValidationRun(true, status, successCount, failures.size(), fileValidations, failures, List.of());
    }

    private SqlScriptFileValidation validateFile(
            Connection connection,
            SqlScriptMigrator.PlannedSqlScriptFile file,
            DmValidationEnvironment environment
    ) {
        List<SqlScriptValidationFailure> failures = new ArrayList<>();
        int successCount = 0;
        try {
            applySchema(connection, file.schema());
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
            return new SqlScriptFileValidation(file.outputDisplay(), 0, failures);
        }

        for (int i = 0; i < file.statements().size(); i++) {
            String sql = file.statements().get(i);
            if (!SqlScriptParser.executable(sql)) {
                continue;
            }
            int statementIndex = i + 1;
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
            }
        }
        return new SqlScriptFileValidation(file.outputDisplay(), successCount, failures);
    }

    private void applySchema(Connection connection, String schema) throws SQLException {
        if (schema == null || schema.isBlank()) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            configureStatement(statement);
            statement.execute("set schema " + quotedIdentifier(schema));
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
        return Integer.getInteger(
                "dm.adapter.sqlScriptStatementTimeoutSeconds",
                DEFAULT_STATEMENT_TIMEOUT_SECONDS
        );
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
