package com.github.dmadapter.cli;

import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.assertj.core.groups.Tuple;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@EnabledIfEnvironmentVariable(named = "DM_ADAPTER_RUN_INTEGRATION_TESTS", matches = "(?i)true")
class DamengSqlScriptIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void convertedRollbackHandlerCommitsSuccessAndRethrowsAfterRollingBackAllChanges() throws Exception {
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String table = "DM_HANDLER_T_" + suffix;
        String procedure = "DM_HANDLER_P_" + suffix;
        Path source = tempDir.resolve("sql/v2");
        Path output = tempDir.resolve("sql/v2-dm");
        Files.createDirectories(source);
        Files.writeString(source.resolve("handler.sql"), """
                CREATE PROCEDURE %s(IN p_fail INT)
                proc: BEGIN
                    DECLARE v_count INT DEFAULT 0;
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION
                    BEGIN
                        ROLLBACK;
                        RESIGNAL;
                    END;
                    SELECT COUNT(*) INTO v_count FROM %s;
                    IF v_count > 0 THEN
                        START TRANSACTION;
                        UPDATE %s SET value = 20 WHERE id = 1;
                        UPDATE %s SET value = 30 WHERE id = 2;
                        IF p_fail = 1 THEN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'rollback integration failure';
                        END IF;
                        IF p_fail = 2 THEN
                            INSERT INTO %s (id, value) VALUES (1, 99);
                        END IF;
                        COMMIT;
                    END IF;
                END;
                /
                """.formatted(procedure, table, table, table, table));
        var report = new SqlScriptMigrator(new MySqlToDmSqlConverter(),
                (files, environment) -> SqlScriptValidationRun.notAttempted("integration fixture", List.of()))
                .migrate(new SqlScriptMigrationRequest(tempDir, source, output, false, "", "",
                        DmValidationEnvironment.from(Map.of())));
        assertThat(report.manualReviewItems()).isEmpty();

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(requiredEnvironment("DM_JDBC_URL"),
                requiredEnvironment("DM_DB_USERNAME"), requiredEnvironment("DM_DB_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA") + "\"");
            try {
                statement.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY, value INT)");
                statement.execute("INSERT INTO " + table + " VALUES (1, 10), (2, 10)");
                connection.commit();
                executeStatements(statement, SqlScriptParser.statements(
                        Files.readString(output.resolve("handler.sql"))));
                statement.execute("ALTER PROCEDURE " + procedure + " COMPILE");
                connection.setAutoCommit(false);
                statement.execute("INSERT INTO " + table + " VALUES (99, 10)");
                assertThatThrownBy(() -> statement.execute("CALL " + procedure + "(1)"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("rollback integration failure")
                        .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(-20000));
                // START TRANSACTION commits preceding caller work in MySQL as well.
                try (ResultSet rows = statement.executeQuery("SELECT value FROM " + table + " ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(10);
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(10);
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(10);
                    assertThat(rows.next()).isFalse();
                }
                assertThatThrownBy(() -> statement.execute("CALL " + procedure + "(2)"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(-6602));
                try (ResultSet rows = statement.executeQuery("SELECT SUM(value) FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(30);
                }
                statement.execute("CALL " + procedure + "(0)");
                connection.rollback();
                try (ResultSet rows = statement.executeQuery("SELECT SUM(value) FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(60);
                }
            } finally {
                connection.rollback();
                dropQuietly(statement, "DROP PROCEDURE IF EXISTS " + procedure);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

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

    @Test
    void executesGuardedMultiColumnAddBeforeBuildingDependentIndex() throws Exception {
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String table = "SAMPLE_QUEUE_" + suffix;
        String addColumns = "SAMPLE_ADD_COLUMNS_" + suffix;
        String addIndex = "SAMPLE_ADD_INDEX_" + suffix;
        List<String> converted = migrateIntegrationFixture("queue.sql", """
                DROP PROCEDURE IF EXISTS %2$s;
                DELIMITER $$
                CREATE PROCEDURE %2$s()
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM information_schema.COLUMNS
                        WHERE table_schema = (SELECT DATABASE()) AND table_name = '%1$s'
                          AND column_name = 'sample_status'
                    ) THEN
                        ALTER TABLE %1$s
                            ADD COLUMN sample_status VARCHAR(20) DEFAULT NULL COMMENT '样例状态',
                            ADD COLUMN sample_content TEXT COMMENT '样例内容',
                            ADD COLUMN sample_available_at DATETIME DEFAULT NULL COMMENT '可用时间',
                            ADD COLUMN sample_attempts INT DEFAULT 0 COMMENT '尝试次数',
                            ADD COLUMN sample_expires_at DATETIME DEFAULT NULL COMMENT '失效时间',
                            ADD COLUMN sample_source VARCHAR(16) DEFAULT NULL COMMENT '样例来源';
                    END IF;
                END$$
                DELIMITER ;
                CALL %2$s();
                DROP PROCEDURE IF EXISTS %2$s;

                DROP PROCEDURE IF EXISTS %3$s;
                DELIMITER $$
                CREATE PROCEDURE %3$s()
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM information_schema.STATISTICS
                        WHERE table_schema = (SELECT DATABASE()) AND table_name = '%1$s'
                          AND index_name = 'idx_sample_lookup'
                    ) THEN
                        ALTER TABLE %1$s ADD INDEX idx_sample_lookup
                            (`sample_status`, `sample_available_at`, `sample_expires_at`, `id`);
                    END IF;
                END$$
                DELIMITER ;
                CALL %3$s();
                DROP PROCEDURE IF EXISTS %3$s;
                """.formatted(table, addColumns, addIndex), schema);

        try (Connection connection = integrationConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                statement.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY)");
                statement.execute("INSERT INTO " + table + " (id) VALUES (17)");
                executeStatements(statement, converted);
                executeStatements(statement, converted);

                try (ResultSet columns = statement.executeQuery("""
                        SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS
                        WHERE OWNER = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID) AND UPPER(TABLE_NAME) = '%s'
                        ORDER BY COLUMN_ID
                        """.formatted(table))) {
                    List<String> names = new ArrayList<>();
                    while (columns.next()) {
                        names.add(columns.getString(1).toLowerCase(Locale.ROOT));
                    }
                    assertThat(names).containsExactly("id", "sample_status", "sample_content", "sample_available_at",
                            "sample_attempts", "sample_expires_at", "sample_source");
                }
                try (ResultSet columns = statement.executeQuery("""
                        SELECT COLUMN_NAME FROM ALL_IND_COLUMNS
                        WHERE INDEX_OWNER = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)
                          AND UPPER(INDEX_NAME) = '%s_IDX_SAMPLE_LOOKUP'
                        ORDER BY COLUMN_POSITION
                        """.formatted(table))) {
                    List<String> names = new ArrayList<>();
                    while (columns.next()) {
                        names.add(columns.getString(1).toLowerCase(Locale.ROOT));
                    }
                    assertThat(names).containsExactly("sample_status", "sample_available_at", "sample_expires_at", "id");
                }
                statement.execute("INSERT INTO " + table + " (id) VALUES (18)");
                try (ResultSet rows = statement.executeQuery("SELECT id, sample_attempts FROM " + table + " ORDER BY id")) {
                    List<Tuple> values = new ArrayList<>();
                    while (rows.next()) {
                        values.add(tuple(rows.getInt(1), rows.getObject(2)));
                    }
                    assertThat(values).containsExactly(tuple(17, 0), tuple(18, 0));
                }
            } finally {
                dropQuietly(statement, "DROP PROCEDURE IF EXISTS " + addIndex);
                dropQuietly(statement, "DROP PROCEDURE IF EXISTS " + addColumns);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void executesGuardedUtf8HexSeedInsertTwiceWithoutChangingDecodedValues() throws Exception {
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String table = "SAMPLE_LABEL_" + suffix;
        String procedure = "SAMPLE_SEED_LABELS_" + suffix;
        List<String> converted = migrateIntegrationFixture("labels.sql", """
                DROP PROCEDURE IF EXISTS %2$s;
                DELIMITER $$
                CREATE PROCEDURE %2$s()
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM %1$s LIMIT 1) THEN
                        INSERT INTO %1$s (sample_code, sample_name, sample_parent, sample_kind) VALUES
                            ('S001', CONVERT(0xe6a0b7e4be8be5b882 USING utf8mb4), NULL, 'SAMPLE'),
                            ('S002', CONVERT(0xe6bc94e7a4bae58cba USING utf8mb4), 'S001', 'SAMPLE'),
                            ('S003', CONVERT(0x274126425c USING utf8mb4), 'S001', 'SAMPLE');
                    END IF;
                END$$
                DELIMITER ;
                CALL %2$s();
                DROP PROCEDURE IF EXISTS %2$s;
                """.formatted(table, procedure), schema);

        try (Connection connection = integrationConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                statement.execute("CREATE TABLE " + table + " (sample_code VARCHAR(10) PRIMARY KEY, "
                        + "sample_name VARCHAR(40 CHAR), sample_parent VARCHAR(10), sample_kind VARCHAR(10))");
                executeStatements(statement, converted);
                executeStatements(statement, converted);
                try (ResultSet rows = statement.executeQuery("SELECT sample_code, sample_name, sample_parent, sample_kind"
                        + " FROM " + table + " ORDER BY sample_code")) {
                    List<Tuple> values = new ArrayList<>();
                    while (rows.next()) {
                        values.add(tuple(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                    }
                    assertThat(values).containsExactly(tuple("S001", "样例市", null, "SAMPLE"),
                            tuple("S002", "演示区", "S001", "SAMPLE"), tuple("S003", "'A&B\\", "S001", "SAMPLE"));
                }
            } finally {
                dropQuietly(statement, "DROP PROCEDURE IF EXISTS " + procedure);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void updatesChildOnlyWhenExactlyOneParentMatches() throws Exception {
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String table = "SAMPLE_RELATION_" + Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        List<String> converted = migrateIntegrationFixture("relation.sql", """
                UPDATE %1$s
                SET parent_id = (SELECT p.id FROM
                    (SELECT MIN(id) AS id FROM %1$s WHERE kind = 'sample_parent' AND active = 1
                     HAVING COUNT(1) = 1) p),
                    note = 'sample updated'
                WHERE kind = 'sample_child' AND tenant_id = 7
                  AND EXISTS (SELECT 1 FROM
                    (SELECT MIN(id) AS id FROM %1$s WHERE kind = 'sample_parent' AND active = 1
                     HAVING COUNT(1) = 1) p);
                """.formatted(table), schema);

        try (Connection connection = integrationConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                statement.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY, parent_id INT, "
                        + "kind VARCHAR(40 CHAR), active INT, tenant_id INT, note VARCHAR(40 CHAR))");
                statement.execute("INSERT INTO " + table + " VALUES (1, 99, 'sample_child', 1, 7, 'sample old'),"
                        + " (2, 99, 'sample_child', 1, 8, 'sample unrelated'),"
                        + " (3, NULL, 'sample_parent', 0, 7, NULL)");
                executeStatements(statement, converted);
                assertThat(childRelations(statement, table)).containsExactly(
                        tuple(1, 99, "sample old"), tuple(2, 99, "sample unrelated"));

                statement.execute("INSERT INTO " + table + " VALUES (4, NULL, 'sample_parent', 1, 7, NULL)");
                executeStatements(statement, converted);
                executeStatements(statement, converted);
                assertThat(childRelations(statement, table)).containsExactly(
                        tuple(1, 4, "sample updated"), tuple(2, 99, "sample unrelated"));

                statement.execute("UPDATE " + table + " SET parent_id = 99, note = 'sample old' WHERE id = 1");
                statement.execute("INSERT INTO " + table + " VALUES (5, NULL, 'sample_parent', 1, 7, NULL)");
                executeStatements(statement, converted);
                assertThat(childRelations(statement, table)).containsExactly(
                        tuple(1, 99, "sample old"), tuple(2, 99, "sample unrelated"));
            } finally {
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void insertsGuardedExplicitIdentitySelectsAndRestoresSessionState() throws Exception {
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String first = "SAMPLE_TREE_A_" + suffix;
        String second = "SAMPLE_TREE_B_" + suffix;
        String seed = """
                CREATE TABLE IF NOT EXISTS %1$s (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    label VARCHAR(40) NOT NULL UNIQUE,
                    PRIMARY KEY (id)
                );
                INSERT INTO %1$s (label, id)
                SELECT '样例', 1 FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM %1$s WHERE id = 1);
                """;
        List<String> converted = migrateIntegrationFixture("trees.sql", seed.formatted(first) + seed.formatted(second), schema);

        try (Connection connection = integrationConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                executeStatements(statement, converted);
                executeStatements(statement, converted);
                for (String table : List.of(first, second)) {
                    try (ResultSet rows = statement.executeQuery("SELECT id, label FROM " + table)) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getLong(1)).isEqualTo(1);
                        assertThat(rows.getString(2)).isEqualTo("样例");
                        assertThat(rows.next()).isFalse();
                    }
                }

                statement.execute("DELETE FROM " + first + " WHERE id = 1");
                statement.execute("SET IDENTITY_INSERT " + first + " ON");
                statement.execute("INSERT INTO " + first + " (label, id) VALUES ('样例', 2)");
                statement.execute("SET IDENTITY_INSERT " + first + " OFF");
                String firstSeed = converted.get(1);
                assertThatThrownBy(() -> statement.execute(firstSeed))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(-6602));
                assertThatThrownBy(() -> statement.execute("INSERT INTO " + first + " (label, id) VALUES ('other', 70)"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(error -> assertThat(((SQLException) error).getErrorCode()).isEqualTo(-2723));
            } finally {
                dropQuietly(statement, "SET IDENTITY_INSERT " + first + " OFF");
                dropQuietly(statement, "SET IDENTITY_INSERT " + second + " OFF");
                dropQuietly(statement, "DROP TABLE IF EXISTS " + first);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + second);
            }
        }
    }

    @Test
    void migratesGuardedColumnRenamesAndKeepsDataOnRetryOrConflict() throws Exception {
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String table = "SAMPLE_RENAME_" + suffix;
        String procedure = "SAMPLE_CHANGE_" + suffix;
        List<String> converted = migrateIntegrationFixture("rename_system.sql", """
                DELIMITER $$
                CREATE PROCEDURE %3$s()
                BEGIN
                    IF EXISTS (SELECT NULL FROM information_schema.COLUMNS
                        WHERE table_name = '%2$s' AND table_schema = (SELECT DATABASE())
                          AND column_name = 'old_label') THEN
                        IF NOT EXISTS (SELECT COLUMN_NAME FROM information_schema.COLUMNS
                            WHERE table_schema = DATABASE() AND table_name = '%2$s'
                              AND column_name = 'new_label')THEN
                            ALTER TABLE %2$s CHANGE COLUMN old_label new_label VARCHAR(30)
                                DEFAULT NULL COMMENT '样例名称' AFTER id;
                        END IF;
                    END IF;
                    IF EXISTS (SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE table_schema = (SELECT DATABASE()) AND table_name = '%2$s'
                          AND column_name = 'old_note') THEN
                        ALTER TABLE `%1$s`.%2$s CHANGE old_note new_note TEXT;
                    END IF;
                    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                        WHERE table_schema = DATABASE() AND table_name = '%2$s'
                          AND column_name = 'old_date') THEN
                        ALTER TABLE %2$s CHANGE old_date new_date TIMESTAMP;
                    END IF;
                END$$
                DELIMITER ;
                CALL %3$s();
                DROP PROCEDURE IF EXISTS %3$s;
                """.formatted(schema, table, procedure), schema);

        try (Connection connection = integrationConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                statement.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY, old_label VARCHAR(20 CHAR) NOT NULL,"
                        + " old_note CLOB, old_date TIMESTAMP DEFAULT TIMESTAMP '2020-01-01 00:00:00')");
                statement.execute("INSERT INTO " + table + " (id, old_label, old_note) VALUES (1, '样例', '旧内容')");
                executeStatements(statement, converted);
                executeStatements(statement, converted);
                statement.execute("INSERT INTO " + table + " (id) VALUES (2)");
                try (ResultSet rows = statement.executeQuery("SELECT new_label, new_note, new_date FROM "
                        + table + " ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("样例");
                    assertThat(rows.getString(2)).isEqualTo("旧内容");
                    assertThat(rows.getTimestamp(3)).isNotNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isNull();
                    assertThat(rows.getTimestamp(3)).isNull();
                }

                statement.execute("ALTER TABLE " + table + " ADD old_date TIMESTAMP DEFAULT TIMESTAMP '2021-01-01 00:00:00'");
                assertThatThrownBy(() -> executeStatements(statement, converted))
                        .isInstanceOf(SQLException.class).hasMessageContaining("new_date");
                statement.execute("INSERT INTO " + table + " (id) VALUES (3)");
                try (ResultSet rows = statement.executeQuery("SELECT old_date FROM " + table + " WHERE id = 3")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getTimestamp(1)).isNotNull();
                }
            } finally {
                dropQuietly(statement, "DROP PROCEDURE IF EXISTS " + procedure);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    @Test
    void executesGroupedColumnChangesUnderTheirOriginalGuards() throws Exception {
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String header = "SAMPLE_HEADER_" + suffix;
        String schedule = "SAMPLE_SCHEDULE_" + suffix;
        String receipt = "SAMPLE_RECEIPT_" + suffix;
        String procedure = "SAMPLE_GROUP_DDL_" + suffix;
        List<String> converted = migrateIntegrationFixture("columns.sql", """
                DELIMITER $$
                CREATE PROCEDURE %4$s()
                BEGIN
                    IF EXISTS (SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE table_schema = (SELECT DATABASE())
                        AND table_name = '%1$s' AND column_name = 'sample_text') THEN
                        ALTER TABLE %1$s ADD COLUMN `sample_value` VARCHAR(255) DEFAULT NULL COMMENT '样例值',
                            ADD COLUMN `sample_name` VARCHAR(255) DEFAULT NULL COMMENT '样例名', DROP COLUMN sample_text;
                    END IF;
                    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE table_schema = (SELECT DATABASE())
                        AND table_name = '%2$s' AND column_name = 'old_first') THEN
                        ALTER TABLE %2$s CHANGE COLUMN old_first start_first DATETIME(0) NULL DEFAULT NULL COMMENT '样例开始' AFTER id,
                            CHANGE COLUMN old_second start_second DATETIME(0) NULL DEFAULT NULL COMMENT '样例开始二' AFTER start_first,
                            ADD COLUMN end_first DATETIME(0) NULL COMMENT '样例结束' AFTER start_second,
                            ADD COLUMN end_second DATETIME(0) NULL COMMENT '样例结束二' AFTER end_first;
                    END IF;
                    IF EXISTS (SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE table_schema = (SELECT DATABASE())
                        AND table_name = '%3$s' AND column_name = 'old_key') THEN
                        ALTER TABLE %3$s CHANGE COLUMN old_key new_key BIGINT DEFAULT NULL COMMENT '样例编号';
                        ALTER TABLE %3$s CHANGE COLUMN old_name new_name VARCHAR(50) DEFAULT NULL COMMENT '样例名称';
                        ALTER TABLE %3$s CHANGE COLUMN old_time new_time TIMESTAMP DEFAULT NULL COMMENT '样例时间';
                    END IF;
                END$$
                DELIMITER ;
                CALL %4$s();
                DROP PROCEDURE IF EXISTS %4$s;
                """.formatted(header, schedule, receipt, procedure), schema);
        assertThat(String.join("\n", converted).split("SELECT COUNT\\(\\*\\) INTO", -1)).hasSize(4);

        try (Connection connection = integrationConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                statement.execute("CREATE TABLE " + header + " (id INT, sample_text VARCHAR(255 CHAR))");
                statement.execute("INSERT INTO " + header + " VALUES (1, '旧内容')");
                statement.execute("CREATE TABLE " + schedule + " (id INT, old_first TIMESTAMP, old_second TIMESTAMP)");
                statement.execute("INSERT INTO " + schedule + " VALUES (1, TIMESTAMP '2020-01-01 09:00:00',"
                        + " TIMESTAMP '2020-01-02 09:00:00')");
                statement.execute("CREATE TABLE " + receipt + " (new_key BIGINT, old_name VARCHAR(50 CHAR), old_time TIMESTAMP)");
                statement.execute("INSERT INTO " + receipt + " VALUES (10, '样例', TIMESTAMP '2020-01-03 09:00:00')");
                executeStatements(statement, converted);
                try (ResultSet skipped = statement.executeQuery("SELECT old_name, old_time FROM " + receipt)) {
                    assertThat(skipped.next()).isTrue();
                    assertThat(skipped.getString(1)).isEqualTo("样例");
                    assertThat(skipped.getTimestamp(2)).isNotNull();
                }
                statement.execute("ALTER TABLE " + receipt + " RENAME COLUMN new_key TO old_key");
                executeStatements(statement, converted);
                executeStatements(statement, converted);

                try (ResultSet rows = statement.executeQuery("SELECT * FROM " + header)) {
                    assertThat(rows.getMetaData().getColumnCount()).isEqualTo(3);
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("sample_value")).isNull();
                    assertThat(rows.getString("sample_name")).isNull();
                }
                try (ResultSet rows = statement.executeQuery("SELECT * FROM " + schedule)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getDate("start_first").toLocalDate().toString()).isEqualTo("2020-01-01");
                    assertThat(rows.getDate("start_second").toLocalDate().toString()).isEqualTo("2020-01-02");
                    assertThat(rows.getTimestamp("end_first")).isNull();
                    assertThat(rows.getTimestamp("end_second")).isNull();
                }
                try (ResultSet rows = statement.executeQuery("SELECT * FROM " + receipt)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("new_key")).isEqualTo(10);
                    assertThat(rows.getString("new_name")).isEqualTo("样例");
                    assertThat(rows.getDate("new_time").toLocalDate().toString()).isEqualTo("2020-01-03");
                }
            } finally {
                dropQuietly(statement, "DROP PROCEDURE IF EXISTS " + procedure);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + receipt);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + schedule);
                dropQuietly(statement, "DROP TABLE IF EXISTS " + header);
            }
        }
    }

    @Test
    void executesMixedIdentityValuesTwiceWithExplicitAndGeneratedIds() throws Exception {
        String jdbcUrl = requiredEnvironment("DM_JDBC_URL");
        String username = requiredEnvironment("DM_DB_USERNAME");
        String password = requiredEnvironment("DM_DB_PASSWORD");
        String schema = requiredEnvironment("DM_ADAPTER_INTEGRATION_SCHEMA");
        String suffix = Long.toHexString(System.nanoTime()).toUpperCase(Locale.ROOT);
        String table = "DM_ADAPTER_IT_IDENT_" + suffix;

        Path sqlRoot = tempDir.resolve("identity-sql/v2");
        Path sqlRootOut = tempDir.resolve("identity-sql/v2-dm");
        Files.createDirectories(sqlRoot);
        Files.writeString(sqlRoot.resolve("identity.sql"), """
                CREATE TABLE IF NOT EXISTS %s (
                    CODE VARCHAR(32),
                    ID BIGINT AUTO_INCREMENT,
                    NAME VARCHAR(64),
                    PRIMARY KEY (ID)
                );
                DELETE FROM %s;
                INSERT INTO %s (CODE, ID, NAME)
                VALUES ('generated', NULL, 'Generated'), ('explicit', 7, 'Explicit');
                """.formatted(table, table, table));
        new SqlScriptMigrator(
                new MySqlToDmSqlConverter(),
                (files, environment) -> SqlScriptValidationRun.notAttempted("integration fixture", List.of())
        ).migrate(new SqlScriptMigrationRequest(
                tempDir,
                sqlRoot,
                sqlRootOut,
                false,
                "dm-identity-integration",
                schema,
                DmValidationEnvironment.from(Map.of())
        ));
        List<String> convertedStatements = SqlScriptParser.statements(
                Files.readString(sqlRootOut.resolve("identity.sql"))
        ).stream().filter(SqlScriptParser::executable).toList();
        assertThat(convertedStatements)
                .anySatisfy(sql -> assertThat(sql)
                        .contains("SET IDENTITY_INSERT " + table + " ON WITH REPLACE NULL")
                        .contains("WHEN OTHERS THEN")
                        .contains("RAISE;"));

        Class.forName("dm.jdbc.driver.DmDriver");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET SCHEMA \"" + schema + "\"");
            try {
                executeStatements(statement, convertedStatements);
                executeStatements(statement, convertedStatements);

                try (ResultSet resultSet = statement.executeQuery(
                        "SELECT COUNT(*), SUM(CASE WHEN ID = 7 THEN 1 ELSE 0 END), "
                                + "SUM(CASE WHEN CODE = 'generated' AND ID IS NOT NULL THEN 1 ELSE 0 END) "
                                + "FROM " + table
                )) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getInt(1)).isEqualTo(2);
                    assertThat(resultSet.getInt(2)).isEqualTo(1);
                    assertThat(resultSet.getInt(3)).isEqualTo(1);
                }
            } finally {
                dropQuietly(statement, "SET IDENTITY_INSERT " + table + " OFF");
                dropQuietly(statement, "DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private List<Tuple> childRelations(Statement statement, String table) throws SQLException {
        try (ResultSet rows = statement.executeQuery("SELECT id, parent_id, note FROM " + table
                + " WHERE kind = 'sample_child' ORDER BY id")) {
            List<Tuple> relations = new ArrayList<>();
            while (rows.next()) {
                relations.add(tuple(rows.getInt(1), rows.getObject(2), rows.getString(3)));
            }
            return relations;
        }
    }

    private List<String> migrateIntegrationFixture(String filename, String sql, String schema) throws Exception {
        Path source = Files.createDirectories(tempDir.resolve("sql/v2"));
        Path output = tempDir.resolve("sql/v2-dm");
        Files.writeString(source.resolve(filename), sql);
        var report = new SqlScriptMigrator(new MySqlToDmSqlConverter(),
                (files, environment) -> SqlScriptValidationRun.notAttempted("integration fixture", List.of()))
                .migrate(new SqlScriptMigrationRequest(tempDir, source, output, false, "", schema,
                        DmValidationEnvironment.from(Map.of())));
        assertThat(report.manualReviewItems()).isEmpty();
        return SqlScriptParser.statements(Files.readString(output.resolve(filename)))
                .stream().filter(SqlScriptParser::executable).toList();
    }

    private Connection integrationConnection() throws Exception {
        Class.forName("dm.jdbc.driver.DmDriver");
        return DriverManager.getConnection(requiredEnvironment("DM_JDBC_URL"),
                requiredEnvironment("DM_DB_USERNAME"), requiredEnvironment("DM_DB_PASSWORD"));
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
