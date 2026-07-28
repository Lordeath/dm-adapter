package com.github.dmadapter.cli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DM_ADAPTER_RUN_INTEGRATION_TESTS", matches = "(?i)true")
class DamengSqlScriptIntegrationTest {
    @Test
    void createsCallsAndCleansIsolatedTargetSchemaProcedure() throws Exception {
        String jdbcUrl = requiredEnvironment("DM_JDBC_URL");
        String username = requiredEnvironment("DM_DB_USERNAME");
        String password = requiredEnvironment("DM_DB_PASSWORD");
        String schema = "newsee-system";
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String table = "DM_ADAPTER_IT_T_" + suffix;
        String procedure = "DM_ADAPTER_IT_P_" + suffix;

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                statement.execute("CREATE TABLE " + table + " (ID INT)");
                statement.execute("""
                        CREATE OR REPLACE PROCEDURE %s() AS
                            dm_adapter_schema VARCHAR(128)
                                := '%s';
                            dm_adapter_exists INT;
                        BEGIN
                            SELECT COUNT(*) INTO dm_adapter_exists
                            FROM ALL_TAB_COLUMNS
                            WHERE OWNER = dm_adapter_schema
                              AND UPPER(TABLE_NAME) = UPPER('%s')
                              AND UPPER(COLUMN_NAME) = UPPER('paramName');
                            IF dm_adapter_exists = 0 THEN
                                EXECUTE IMMEDIATE
                                    'ALTER TABLE %s ADD `paramName` VARCHAR(255) DEFAULT NULL';
                            END IF;
                        END
                        """.formatted(procedure, schema, table, table));
                statement.execute("CALL " + procedure + "()");
                try (PreparedStatement metadata = connection.prepareStatement("""
                        SELECT COLUMN_NAME
                        FROM ALL_TAB_COLUMNS
                        WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
                          AND UPPER(TABLE_NAME) = UPPER(?)
                          AND UPPER(COLUMN_NAME) = UPPER('paramName')
                        """)) {
                    metadata.setString(1, table);
                    try (ResultSet resultSet = metadata.executeQuery()) {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getString(1)).isEqualTo("paramName");
                    }
                }
            } finally {
                dropQuietly(statement, "DROP PROCEDURE IF EXISTS " + procedure);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private void dropQuietly(Statement statement, String sql) {
        try {
            statement.execute(sql);
        } catch (Exception ignored) {
            // The isolated integration object may not have been created.
        }
    }
}
