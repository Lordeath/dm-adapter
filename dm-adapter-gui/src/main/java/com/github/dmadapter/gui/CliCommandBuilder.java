package com.github.dmadapter.gui;

import com.github.dmadapter.cli.AdapterWorkspaceResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CliCommandBuilder {
    static final String VALIDATION_ENABLED = "DM_SQL_VALIDATION";
    static final String JDBC_URL = "DM_JDBC_URL";
    static final String DB_USERNAME = "DM_DB_USERNAME";
    static final String DB_PASSWORD = "DM_DB_PASSWORD";

    private final AdapterWorkspaceResolver workspaceResolver = new AdapterWorkspaceResolver();

    CliInvocation build(GuiOperation operation, GuiRunConfiguration configuration) {
        validate(operation, configuration);
        List<String> arguments = new ArrayList<>();
        arguments.add(operation == GuiOperation.SCAN ? "scan" : "migrate");
        addPath(arguments, "--project", configuration.project());
        addPath(arguments, "--report-dir", configuration.reportDir());
        addValue(arguments, "--app-module", configuration.appModule());
        addValue(arguments, "--dm-driver", configuration.dmDriver());

        if (operation != GuiOperation.SCAN) {
            addMigrationArguments(arguments, operation, configuration);
        }

        boolean databaseValidation = operation != GuiOperation.SCAN && configuration.databaseValidation();
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(VALIDATION_ENABLED, Boolean.toString(databaseValidation));
        if (databaseValidation) {
            environment.put(JDBC_URL, configuration.jdbcUrl());
            environment.put(DB_USERNAME, configuration.username());
            environment.put(DB_PASSWORD, configuration.password());
        }
        return new CliInvocation(arguments, environment, resolveReportDir(configuration));
    }

    private void addMigrationArguments(
            List<String> arguments,
            GuiOperation operation,
            GuiRunConfiguration configuration
    ) {
        if (operation == GuiOperation.DRY_RUN) {
            arguments.add("--dry-run");
        }
        addPath(arguments, "--mapper-dir", configuration.mapperDir());
        addPath(arguments, "--rewrite-config", configuration.rewriteConfig());
        addPath(arguments, "--sql-root", configuration.sqlRoot());
        addPath(arguments, "--sql-root-out", configuration.sqlRootOut());
        addValue(arguments, "--system-schema", configuration.systemSchema());
        if (configuration.sqlScriptsOnly()) {
            arguments.add("--sql-scripts-only");
        }

        boolean hasSqlScripts = configuration.sqlRoot() != null;
        if (operation == GuiOperation.MIGRATE || hasSqlScripts) {
            addValue(arguments, "--schema", configuration.schema());
        }
        if (operation == GuiOperation.MIGRATE) {
            addPath(arguments, "--config", configuration.validationConfig());
            if (configuration.generateValidationTest()) {
                arguments.add("--generate-validation-test");
            }
        }
    }

    private void validate(GuiOperation operation, GuiRunConfiguration configuration) {
        if (operation == null) {
            throw new IllegalArgumentException("请选择要执行的操作。");
        }
        if (configuration == null || configuration.project() == null) {
            throw new IllegalArgumentException("请选择项目根目录。");
        }
        if (operation == GuiOperation.SCAN) {
            return;
        }
        boolean hasSqlRoot = configuration.sqlRoot() != null;
        boolean hasSqlRootOut = configuration.sqlRootOut() != null;
        if (hasSqlRoot != hasSqlRootOut) {
            throw new IllegalArgumentException("SQL 源目录和达梦 SQL 输出目录必须同时填写。");
        }
        if (configuration.sqlScriptsOnly() && !hasSqlRoot) {
            throw new IllegalArgumentException("仅迁移 SQL 脚本时必须填写 SQL 源目录和输出目录。");
        }
        if (configuration.databaseValidation()) {
            if (configuration.jdbcUrl().isBlank()
                    || configuration.username().isBlank()
                    || configuration.password().isBlank()) {
                throw new IllegalArgumentException("启用数据库验证后必须填写 JDBC URL、用户名和密码。");
            }
        }
    }

    private void addPath(List<String> arguments, String option, Path value) {
        if (value != null) {
            addValue(arguments, option, value.toAbsolutePath().normalize().toString());
        }
    }

    private void addValue(List<String> arguments, String option, String value) {
        if (value != null && !value.isBlank()) {
            arguments.add(option);
            arguments.add(value);
        }
    }

    Path resolveReportDir(GuiRunConfiguration configuration) {
        Path appModule = configuration.appModule().isBlank() ? null : Path.of(configuration.appModule());
        return workspaceResolver.resolve(configuration.project(), appModule, configuration.reportDir());
    }
}
