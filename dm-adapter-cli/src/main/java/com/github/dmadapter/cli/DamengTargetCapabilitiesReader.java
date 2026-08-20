package com.github.dmadapter.cli;

import com.github.dmadapter.core.DamengTargetCapabilities;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class DamengTargetCapabilitiesReader {
    private static final String CAPABILITY_SQL = """
            SELECT PARA_NAME, PARA_VALUE
            FROM V$DM_INI
            WHERE UPPER(PARA_NAME) IN (
                'COMPATIBLE_MODE',
                'CASE_SENSITIVE',
                'BLANK_PAD_MODE',
                'PL_SQL_STRIP'
            )
            """;

    DamengTargetCapabilities read(DmValidationEnvironment environment) throws Exception {
        if (environment == null || !environment.ready()) {
            return DamengTargetCapabilities.unknown();
        }
        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(
                environment.jdbcUrl(),
                environment.username(),
                environment.password()
        );
             PreparedStatement statement = connection.prepareStatement(CAPABILITY_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            Map<String, String> values = new LinkedHashMap<>();
            while (resultSet.next()) {
                String name = resultSet.getString(1);
                if (name != null) {
                    values.putIfAbsent(name.toUpperCase(Locale.ROOT), resultSet.getString(2));
                }
            }
            String compatibleMode = value(values, "COMPATIBLE_MODE");
            if (compatibleMode.isBlank()) {
                throw new IllegalStateException(
                        "Target capability query did not return COMPATIBLE_MODE."
                );
            }
            return new DamengTargetCapabilities(
                    compatibleMode,
                    value(values, "CASE_SENSITIVE"),
                    value(values, "BLANK_PAD_MODE"),
                    value(values, "PL_SQL_STRIP"),
                    "DATABASE"
            );
        }
    }

    private String value(Map<String, String> values, String name) {
        String value = values.get(name);
        return value == null ? "" : value.trim();
    }
}
