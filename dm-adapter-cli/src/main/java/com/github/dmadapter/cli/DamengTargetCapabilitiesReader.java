package com.github.dmadapter.cli;

import com.github.dmadapter.core.DamengTargetCapabilities;
import com.github.dmadapter.core.TargetLengthSemantics;

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
                'LENGTH_IN_CHAR',
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
            String lengthInChar = value(values, "LENGTH_IN_CHAR");
            String compatibleMode = value(values, "COMPATIBLE_MODE");
            if (lengthInChar.isBlank() || compatibleMode.isBlank()) {
                throw new IllegalStateException(
                        "Target capability query did not return LENGTH_IN_CHAR and COMPATIBLE_MODE."
                );
            }
            TargetLengthSemantics semantics = lengthInChar.isBlank()
                    ? null
                    : TargetLengthSemantics.fromLengthInChar(lengthInChar);
            return new DamengTargetCapabilities(
                    semantics,
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
