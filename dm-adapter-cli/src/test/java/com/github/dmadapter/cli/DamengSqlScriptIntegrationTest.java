package com.github.dmadapter.cli;

import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "DM_ADAPTER_RUN_INTEGRATION_TESTS", matches = "(?i)true")
class DamengSqlScriptIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void executesAnonymousConditionalDdlBlock() throws Exception {
        String jdbcUrl = requiredEnvironment("DM_JDBC_URL");
        String username = requiredEnvironment("DM_DB_USERNAME");
        String password = requiredEnvironment("DM_DB_PASSWORD");
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String table = "DM_ADAPTER_IT_B_" + suffix;

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                statement.execute("CREATE TABLE " + table + " (ID INT)");
                statement.execute("""
                        DECLARE
                            dm_adapter_exists INT;
                        BEGIN
                            SELECT COUNT(*) INTO dm_adapter_exists
                            FROM (
                                SELECT 1
                                FROM SYS.SYSOBJECTS T
                                JOIN SYS.SYSCOLUMNS C ON C.ID = T.ID
                                WHERE T.SCHID = CURRENT_SCHID
                                  AND T.SUBTYPE$ = 'UTAB'
                                  AND T.NAME IN ('%s', UPPER('%s'))
                                  AND C.NAME IN ('status', UPPER('status'))
                            ) dm_adapter_exists_check;
                            IF dm_adapter_exists = 0 THEN
                                EXECUTE IMMEDIATE
                                    'ALTER TABLE %s ADD status VARCHAR(20 CHAR) DEFAULT NULL';
                            END IF;
                        END
                        """.formatted(table, table, table));
                try (PreparedStatement metadata = connection.prepareStatement("""
                        SELECT COUNT(*)
                        FROM ALL_TAB_COLUMNS
                        WHERE OWNER = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)
                          AND UPPER(TABLE_NAME) = UPPER(?)
                          AND UPPER(COLUMN_NAME) = UPPER('status')
                        """)) {
                    metadata.setString(1, table);
                    try (ResultSet resultSet = metadata.executeQuery()) {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getInt(1)).isEqualTo(1);
                    }
                }
            } finally {
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void createsCallsAndCleansIsolatedTargetSchemaProcedure() throws Exception {
        String jdbcUrl = requiredEnvironment("DM_JDBC_URL");
        String username = requiredEnvironment("DM_DB_USERNAME");
        String password = requiredEnvironment("DM_DB_PASSWORD");
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
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
                            dm_adapter_schema VARCHAR(128) :=
                                SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID);
                            dm_adapter_exists INT;
                        BEGIN
                            SELECT COUNT(*) INTO dm_adapter_exists
                            FROM ALL_TAB_COLUMNS
                            WHERE OWNER = dm_adapter_schema
                              AND UPPER(TABLE_NAME) = UPPER('%s')
                              AND UPPER(COLUMN_NAME) = UPPER('paramName');
                            IF dm_adapter_exists = 0 THEN
                                EXECUTE IMMEDIATE
                                    'ALTER TABLE %s ADD `paramName` VARCHAR(10 CHAR) DEFAULT NULL';
                            END IF;
                        END
                        """.formatted(procedure, table, table));
                statement.execute("CALL " + procedure + "()");
                try (PreparedStatement metadata = connection.prepareStatement("""
                        SELECT COLUMN_NAME, CHAR_LENGTH, CHAR_USED
                        FROM ALL_TAB_COLUMNS
                        WHERE OWNER = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)
                          AND UPPER(TABLE_NAME) = UPPER(?)
                          AND UPPER(COLUMN_NAME) = UPPER('paramName')
                        """)) {
                    metadata.setString(1, table);
                    try (ResultSet resultSet = metadata.executeQuery()) {
                        assertThat(resultSet.next()).isTrue();
                        assertThat(resultSet.getString(1)).isEqualTo("paramName");
                        assertThat(resultSet.getInt(2)).isEqualTo(10);
                        assertThat(resultSet.getString(3)).isEqualTo("C");
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + table + " (ID, `paramName`) VALUES (?, ?)"
                )) {
                    insert.setInt(1, 1);
                    insert.setString(2, "审核通过审核通过审核");
                    assertThat(insert.executeUpdate()).isEqualTo(1);

                    insert.setInt(1, 2);
                    insert.setString(2, "😀😀😀😀😀😀😀😀😀😀");
                    assertThat(insert.executeUpdate()).isEqualTo(1);

                    insert.setInt(1, 3);
                    insert.setString(2, "😀😀😀😀😀😀😀😀😀😀😀");
                    assertThatThrownBy(insert::executeUpdate).isInstanceOf(Exception.class);
                }
            } finally {
                dropQuietly(statement, "DROP PROCEDURE IF EXISTS " + procedure);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void skipsDifferentlyNamedEquivalentIndexOnRepeatedExecutionButNotSameNameConflict() throws Exception {
        String jdbcUrl = requiredEnvironment("DM_JDBC_URL");
        String username = requiredEnvironment("DM_DB_USERNAME");
        String password = requiredEnvironment("DM_DB_PASSWORD");
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String table = "DM_ADAPTER_IT_I_" + suffix;
        String existingIndex = "DM_ADAPTER_IT_OLD_" + suffix;
        String existingFunctionIndex = "DM_ADAPTER_IT_FUN_" + suffix;
        String existingMixedIndex = "DM_ADAPTER_IT_MIX_" + suffix;
        String targetIndex = table + "_idx_code_tenant";
        String targetFunctionIndex = table + "_idx_code_prefix";
        String targetMixedIndex = table + "_idx_mixed";

        Path sqlRoot = tempDir.resolve("sql/v2");
        Path sqlRootOut = tempDir.resolve("sql/v2-dm");
        Files.createDirectories(sqlRoot);
        Files.writeString(sqlRoot.resolve("index.sql"), """
                CREATE TABLE IF NOT EXISTS %s (
                    ID INT,
                    CODE VARCHAR(64),
                    TENANT_ID INT,
                    LABEL VARCHAR(64),
                    KEY idx_code_tenant (CODE ASC, TENANT_ID DESC),
                    KEY idx_code_prefix (CODE(16)),
                    KEY idx_mixed (CODE ASC, TENANT_ID DESC, LABEL(16) DESC)
                );
                """.formatted(table));
        new SqlScriptMigrator(
                new MySqlToDmSqlConverter(),
                (files, environment) -> SqlScriptValidationRun.notAttempted("integration fixture", List.of())
        ).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "dm-index-integration",
                schema,
                DmValidationEnvironment.from(Map.of())
        ));
        List<String> convertedStatements = SqlScriptParser.statements(
                Files.readString(sqlRootOut.resolve("index.sql"))
        ).stream().filter(SqlScriptParser::executable).toList();
        assertThat(convertedStatements).hasSize(4);
        assertThat(convertedStatements.get(3))
                .contains("E.COLUMN_POSITION = 3")
                .contains("CAST(SUBSTR(LABEL,1,16)ASVARCHAR(16))DESC")
                .doesNotContain("ABS(C.COLUMN_POSITION)");

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                statement.execute("CREATE TABLE " + table
                        + " (ID INT, CODE VARCHAR(64), TENANT_ID INT, LABEL VARCHAR(64))");
                statement.execute("CREATE INDEX " + existingIndex
                        + " ON " + table + " (CODE ASC, TENANT_ID DESC)");
                statement.execute("CREATE INDEX " + existingFunctionIndex
                        + " ON " + table + " (CAST(SUBSTR(CODE, 1, 16) AS VARCHAR(16)))");
                statement.execute("CREATE INDEX " + existingMixedIndex
                        + " ON " + table
                        + " (CODE ASC, TENANT_ID DESC, CAST(SUBSTR(LABEL, 1, 16) AS VARCHAR(16)) DESC)");

                executeStatements(statement, convertedStatements);
                executeStatements(statement, convertedStatements);

                assertThat(indexCount(connection, schema, table, existingIndex)).isEqualTo(1);
                assertThat(indexCount(connection, schema, table, existingFunctionIndex)).isEqualTo(1);
                assertThat(indexCount(connection, schema, table, existingMixedIndex)).isEqualTo(1);
                assertThat(indexCount(connection, schema, table, targetIndex)).isZero();
                assertThat(indexCount(connection, schema, table, targetFunctionIndex)).isZero();
                assertThat(indexCount(connection, schema, table, targetMixedIndex)).isZero();

                statement.execute("DROP INDEX " + existingIndex);
                statement.execute("CREATE INDEX " + targetIndex
                        + " ON " + table + " (TENANT_ID DESC, CODE ASC)");
                assertThatThrownBy(() -> statement.execute(convertedStatements.get(1)))
                        .isInstanceOf(Exception.class);
            } finally {
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void executesConvertedMultiIndexProcedureTwice() throws Exception {
        String jdbcUrl = requiredEnvironment("DM_JDBC_URL");
        String username = requiredEnvironment("DM_DB_USERNAME");
        String password = requiredEnvironment("DM_DB_PASSWORD");
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String table = "DM_ADAPTER_IT_PIDX_" + suffix;
        String procedure = "DM_ADAPTER_IT_ADD_IDX_" + suffix;
        String codeIndex = table + "_idx_code";
        String tenantIndex = table + "_idx_tenant";

        Path sqlRoot = tempDir.resolve("procedure-sql/v2");
        Path sqlRootOut = tempDir.resolve("procedure-sql/v2-dm");
        Files.createDirectories(sqlRoot);
        Files.writeString(sqlRoot.resolve("index-procedure.sql"), """
                DROP PROCEDURE IF EXISTS %s;
                DELIMITER $$
                CREATE PROCEDURE %s()
                BEGIN
                    IF NOT EXISTS (
                        SELECT INDEX_NAME
                        FROM information_schema.STATISTICS
                        WHERE table_schema = database()
                          AND table_name = '%s'
                          AND index_name IN ('idx_code', 'idx_tenant')
                    ) THEN
                        ALTER TABLE %s ADD INDEX idx_code (CODE);
                        ALTER TABLE %s ADD INDEX idx_tenant (TENANT_ID DESC);
                    END IF;
                END$$
                DELIMITER ;
                CALL %s();
                DROP PROCEDURE IF EXISTS %s;
                """.formatted(procedure, procedure, table, table, table, procedure, procedure));
        new SqlScriptMigrator(
                new MySqlToDmSqlConverter(),
                (files, environment) -> SqlScriptValidationRun.notAttempted("integration fixture", List.of())
        ).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "dm-index-procedure-integration",
                schema,
                DmValidationEnvironment.from(Map.of())
        ));
        List<String> convertedStatements = SqlScriptParser.statements(
                Files.readString(sqlRootOut.resolve("index-procedure.sql"))
        ).stream().filter(SqlScriptParser::executable).toList();
        assertThat(convertedStatements)
                .singleElement()
                .satisfies(sql -> assertThat(sql)
                        .contains("UPPER(C.COLUMN_NAME) = UPPER('CODE')")
                        .contains("UPPER(C.COLUMN_NAME) = UPPER('TENANT_ID')"));

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                statement.execute("CREATE TABLE " + table + " (ID INT, CODE VARCHAR(64), TENANT_ID INT)");
                executeStatements(statement, convertedStatements);
                executeStatements(statement, convertedStatements);

                assertThat(indexCount(connection, schema, table, codeIndex)).isEqualTo(1);
                assertThat(indexCount(connection, schema, table, tenantIndex)).isEqualTo(1);
            } finally {
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
                dropQuietly(statement, "DROP PROCEDURE IF EXISTS " + procedure);
            }
        }
    }

    private void executeStatements(Statement statement, List<String> statements) throws Exception {
        for (String sql : statements) {
            statement.execute(sql);
        }
    }

    private int indexCount(Connection connection, String schema, String table, String index) throws Exception {
        try (PreparedStatement metadata = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM ALL_INDEXES
                WHERE UPPER(OWNER) = UPPER(?)
                  AND UPPER(TABLE_NAME) = UPPER(?)
                  AND UPPER(INDEX_NAME) = UPPER(?)
                """)) {
            metadata.setString(1, schema);
            metadata.setString(2, table);
            metadata.setString(3, index);
            try (ResultSet resultSet = metadata.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getInt(1);
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
