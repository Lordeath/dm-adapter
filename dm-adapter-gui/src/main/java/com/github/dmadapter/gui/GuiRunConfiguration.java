package com.github.dmadapter.gui;

import java.nio.file.Path;

record GuiRunConfiguration(
        Path project,
        Path reportDir,
        String appModule,
        Path mapperDir,
        Path rewriteConfig,
        Path validationConfig,
        String schema,
        Path sqlRoot,
        Path sqlRootOut,
        boolean sqlScriptsOnly,
        String systemSchema,
        String dmDriver,
        boolean generateValidationTest,
        boolean databaseValidation,
        String jdbcUrl,
        String username,
        String password
) {
    GuiRunConfiguration {
        appModule = trimmed(appModule);
        schema = trimmed(schema);
        systemSchema = trimmed(systemSchema);
        dmDriver = trimmed(dmDriver);
        jdbcUrl = trimmed(jdbcUrl);
        username = trimmed(username);
        password = password == null ? "" : password;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
