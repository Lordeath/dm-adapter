package com.github.dmadapter.cli;

import com.github.dmadapter.core.DamengTargetCapabilities;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.mybatis.SqlRewriteConfig;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ProcedureRepairMigrationTest {
    @TempDir
    Path root;

    private SqlScriptMigrationReport migrate(String sql) throws Exception {
        return migrate(sql, SqlRewriteConfig.empty());
    }

    private SqlScriptMigrationReport migrate(String sql, SqlRewriteConfig config) throws Exception {
        Files.createDirectories(root.resolve("sql"));
        Files.writeString(root.resolve("sql/test.sql"), sql);
        var report = new SqlScriptMigrator(new MySqlToDmSqlConverter(),
                (files, env) -> SqlScriptValidationRun.notAttempted("offline test", List.of()))
                .migrate(new SqlScriptMigrationRequest(root, root.resolve("sql"), root.resolve("out"),
                        false, "", "", List.of(), DmValidationEnvironment.from(Map.of()),
                        DamengTargetCapabilities.unknown(), null, config));
        assertThat(Files.readString(root.resolve("sql/test.sql"))).isEqualTo(sql);
        return report;
    }

    private String output() throws Exception {
        return Files.readString(root.resolve("out/test.sql"));
    }

    @Test
    void rollsBackCleansOwnedTemporaryTablesAndRethrowsBeforeUnblockingCall() throws Exception {
        var report = migrate("""
                DELIMITER $$
                CREATE PROCEDURE repair(IN p_apply INT)
                BEGIN
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION
                    BEGIN
                        -- rollback before cleanup
                        ROLLBACK;
                        DROP TEMPORARY TABLE IF EXISTS tmp_modes;
                        DROP TEMPORARY TABLE IF EXISTS tmp_items;
                        RESIGNAL;
                    END;
                    IF p_apply IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'invalid'; END IF;
                    START TRANSACTION;
                    DROP TEMPORARY TABLE IF EXISTS tmp_modes;
                    CREATE TEMPORARY TABLE tmp_modes(ver INT PRIMARY KEY, flag INT NOT NULL);
                    CREATE TEMPORARY TABLE tmp_items(id INT, UNIQUE KEY uk_id(id));
                    INSERT INTO tmp_modes(ver, flag) VALUES(1, 0)
                        ON DUPLICATE KEY UPDATE flag = GREATEST(flag, VALUES(flag));
                    IF p_apply = 1 THEN COMMIT; ELSE ROLLBACK; END IF;
                    DROP TEMPORARY TABLE IF EXISTS tmp_modes;
                    DROP TEMPORARY TABLE IF EXISTS tmp_items;
                END$$
                DELIMITER ;
                CALL repair(0);
                """);
        assertThat(report.manualReviewItems()).isEmpty();
        assertThat(output()).contains("EXCEPTION", "WHEN OTHERS THEN", "RAISE;", "CALL repair(0)",
                "GREATEST(t.flag, s.flag)", "-- rollback before cleanup", "UNIQUE (id)")
                .doesNotContain("DECLARE EXIT HANDLER", "RESIGNAL", "ON DUPLICATE KEY UPDATE", "DROP TEMPORARY");
        String handler = output().substring(output().lastIndexOf("EXCEPTION"));
        assertThat(handler.indexOf("ROLLBACK;")).isLessThan(handler.indexOf("DELETE FROM tmp_modes"));
        assertThat(handler.indexOf("DELETE FROM tmp_items")).isLessThan(handler.indexOf("RAISE;"));
    }

    @Test
    void refusesUnownedConditionalOrNontrivialHandlerCleanup() throws Exception {
        for (String cleanup : List.of("DROP TEMPORARY TABLE IF EXISTS unknown_table;",
                "DROP TABLE IF EXISTS tmp_modes;", "DELETE FROM tmp_modes;",
                "COMMIT;", "ROLLBACK TO SAVEPOINT s;")) {
            var report = migrate("""
                    CREATE PROCEDURE repair()
                    BEGIN
                        DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; %s RESIGNAL; END;
                        CREATE TEMPORARY TABLE tmp_modes(ver INT PRIMARY KEY);
                    END;
                    /
                    CALL repair();
                    """.formatted(cleanup));
            assertThat(report.manualReviewItems()).as(cleanup).hasSize(2);
        }
    }

    @Test
    void infersCompoundTemporaryKeysAndRejectsConflictingConfiguration() throws Exception {
        String sql = """
                CREATE PROCEDURE repair()
                BEGIN
                    CREATE TEMPORARY TABLE tmp_modes(tenant INT, ver INT, flag INT, UNIQUE KEY uk(tenant, ver));
                    INSERT INTO tmp_modes(tenant, ver, flag) VALUES(1, 2, 0)
                        ON DUPLICATE KEY UPDATE flag = GREATEST(flag, VALUES(flag));
                END;
                /
                """;
        assertThat(migrate(sql).manualReviewItems()).isEmpty();
        assertThat(output()).contains("ON (t.tenant = s.tenant AND t.ver = s.ver)");
        var conflict = migrate(sql, new SqlRewriteConfig(Map.of("tmp_modes", List.of("ver")), Map.of()));
        assertThat(conflict.manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("keyColumns"));
    }

    @Test
    void preservesUniqueConstraintsWithExplicitColumnDirections() throws Exception {
        for (String constraint : List.of("UNIQUE KEY uk(tenant ASC, ver DESC)",
                "CONSTRAINT uk UNIQUE KEY(tenant ASC, ver DESC)")) {
            assertThat(migrate("""
                    CREATE PROCEDURE repair()
                    BEGIN
                        CREATE TEMPORARY TABLE tmp_modes(tenant INT, ver INT, flag INT, %s);
                        INSERT INTO tmp_modes(tenant, ver, flag) VALUES(1, 2, 0)
                            ON DUPLICATE KEY UPDATE flag = VALUES(flag);
                    END;
                    /
                    """.formatted(constraint)).manualReviewItems()).as(constraint).isEmpty();
            assertThat(output()).contains("UNIQUE (tenant, ver)", "ON (t.tenant = s.tenant AND t.ver = s.ver)");
        }
    }

    @Test
    void invalidatesLocalKeysWhenAlterUsesQuotedTableName() throws Exception {
        for (String table : List.of("`tmp_modes`", "\"tmp_modes\"")) {
            var report = migrate("""
                    CREATE PROCEDURE repair()
                    BEGIN
                        CREATE TEMPORARY TABLE %s(ver INT PRIMARY KEY, flag INT);
                        ALTER TABLE %s DROP PRIMARY KEY;
                        INSERT INTO %s(ver, flag) VALUES(1, 0) ON DUPLICATE KEY UPDATE flag = VALUES(flag);
                    END;
                    /
                    """.formatted(table, table, table));
            assertThat(report.manualReviewItems()).as(table).singleElement()
                    .satisfies(item -> assertThat(item.reason()).contains("TEMPORARY_UPSERT_KEY_CONFLICT"));
        }
    }

    @Test
    void infersLongParametersFromSignaturesAndInvalidatesAfterRecreation() throws Exception {
        String json = "[{\"value\":\"" + "中文😀".repeat(400) + "\"}]";
        var report = migrate("""
                CREATE PROCEDURE custom_repair(IN payload JSON, IN apply_flag INT)
                BEGIN SELECT JSON_LENGTH(payload); END;
                /
                CALL custom_repair('%s', 0);
                DROP PROCEDURE custom_repair;
                CREATE PROCEDURE custom_repair(IN payload VARCHAR(100), IN apply_flag INT)
                BEGIN SELECT payload; END;
                /
                CALL custom_repair('%s', 0);
                """.formatted(json, json));
        assertThat(report.manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("3000"));
        assertThat(output()).contains("CLOB;", "TO_CLOB(", "CALL custom_repair(dm_adapter_clob_arg_1, 0)");
    }

    @Test
    void retainsDependencyWarningForWrappedLongCalls() throws Exception {
        String json = "[\"" + "A".repeat(3500) + "\"]";
        var report = migrate("""
                CREATE PROCEDURE unsafe_repair(IN payload JSON)
                BEGIN
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN COMMIT; RESIGNAL; END;
                    SELECT JSON_LENGTH(payload);
                END;
                /
                CALL unsafe_repair('%s');
                """.formatted(json));
        assertThat(report.manualReviewItems()).hasSize(2);
        assertThat(report.manualReviewItems().get(1).reason()).contains("unsafe_repair", "达梦适配");
    }

    @Test
    void tracksTemporaryKeysByLifetimeAndDoesNotLeakAcrossProcedures() throws Exception {
        var report = migrate("""
                CREATE PROCEDURE first_repair()
                BEGIN
                    CREATE TEMPORARY TABLE tmp_modes(ver INT, flag INT, PRIMARY KEY(ver));
                    INSERT INTO tmp_modes(ver, flag) VALUES(1, 0) ON DUPLICATE KEY UPDATE flag = VALUES(flag);
                    DROP TEMPORARY TABLE tmp_modes;
                    CREATE TEMPORARY TABLE tmp_modes(ver INT, flag INT, PRIMARY KEY(ver));
                    INSERT INTO tmp_modes(ver, flag) VALUES(2, 0) ON DUPLICATE KEY UPDATE flag = VALUES(flag);
                END;
                /
                CREATE PROCEDURE second_repair()
                BEGIN
                    INSERT INTO tmp_modes(ver, flag) VALUES(1, 0) ON DUPLICATE KEY UPDATE flag = VALUES(flag);
                END;
                /
                """);
        assertThat(report.manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.originalSql()).contains("second_repair"));
        assertThat(output()).contains("ON (t.ver = s.ver)");
    }

    @Test
    void rejectsAmbiguousPrefixConditionalAndInvalidatedTemporaryKeys() throws Exception {
        for (String setup : List.of(
                "CREATE TEMPORARY TABLE tmp_modes(ver INT PRIMARY KEY, flag INT UNIQUE);",
                "CREATE TEMPORARY TABLE tmp_modes(ver VARCHAR(100), flag INT, UNIQUE KEY uk(ver(10)));",
                "IF 1=1 THEN CREATE TEMPORARY TABLE tmp_modes(ver INT PRIMARY KEY, flag INT); END IF;",
                "CREATE TEMPORARY TABLE tmp_modes(ver INT PRIMARY KEY, flag INT); DROP TEMPORARY TABLE tmp_modes;",
                "CREATE TEMPORARY TABLE tmp_modes(ver INT PRIMARY KEY, flag INT); DROP TEMPORARY TABLE tmp_modes; "
                        + "CREATE TEMPORARY TABLE tmp_modes(ver INT, flag INT PRIMARY KEY);",
                "CREATE TEMPORARY TABLE tmp_modes(ver INT PRIMARY KEY, flag INT); ALTER TABLE tmp_modes DROP PRIMARY KEY;")) {
            var report = migrate("CREATE PROCEDURE repair() BEGIN " + setup
                    + " INSERT INTO tmp_modes(ver, flag) VALUES(1, 0) ON DUPLICATE KEY UPDATE flag = VALUES(flag); END;\n/\n");
            assertThat(report.manualReviewItems()).as(setup).hasSize(1);
        }
    }

    @Test
    void cleansNonPrefixedOwnedTablesButRejectsConditionalAndNestedHandlers() throws Exception {
        String procedure = """
                CREATE PROCEDURE repair()
                BEGIN
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN
                        ROLLBACK; DROP /* keep comment */ TEMPORARY TABLE IF EXISTS work_rows; RESIGNAL;
                    END;
                    CREATE TEMPORARY TABLE work_rows(id INT PRIMARY KEY);
                    INSERT INTO work_rows VALUES(1);
                END;
                /
                """;
        assertThat(migrate(procedure).manualReviewItems()).isEmpty();
        assertThat(output()).contains("DELETE FROM work_rows", "/* keep comment */", "RAISE;");
        assertThat(migrate(procedure.replace("CREATE TEMPORARY TABLE work_rows(id INT PRIMARY KEY);",
                "IF 1=1 THEN CREATE TEMPORARY TABLE work_rows(id INT PRIMARY KEY); END IF;"))
                .manualReviewItems()).hasSize(1);
        assertThat(migrate(procedure.replace("BEGIN\n    DECLARE", "BEGIN\n BEGIN\n    DECLARE")
                .replace("END;\n/", "END;\nEND;\n/"))
                .manualReviewItems()).hasSize(1);
    }

    @Test
    void reconstructsUnicodeJsonQuotesBackslashesAndNewlinesExactly() throws Exception {
        String value = "中文😀\\path\\\"quote\" O'Brien\r\n\t${defaultRoutePath}".repeat(90);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("value", value));
        String literal = json.replace("\\", "\\\\").replace("'", "''");
        assertThat(migrate("""
                CREATE PROCEDURE custom_repair(IN payload JSON, IN other_value LONGTEXT)
                BEGIN SELECT JSON_LENGTH(payload); END;
                /
                CALL custom_repair('%s', '%s');
                """.formatted(literal, literal)).manualReviewItems()).isEmpty();
        for (int parameter = 1; parameter <= 2; parameter++) {
            assertThat(reconstructClobArgument(output(), parameter)).isEqualTo(json);
        }
    }

    private String reconstructClobArgument(String sql, int parameter) {
        StringBuilder value = new StringBuilder();
        Pattern token = Pattern.compile("'((?:''|[^'])*)'|CHR\\((\\d+)\\)");
        for (String line : sql.lines().toList()) {
            if (!line.stripLeading().startsWith("dm_adapter_clob_arg_" + parameter + " :=")) {
                continue;
            }
            String expression = line.substring(line.indexOf("TO_CLOB(") + 8);
            var matcher = token.matcher(expression);
            StringBuilder chunk = new StringBuilder();
            while (matcher.find()) {
                chunk.append(matcher.group(1) != null ? matcher.group(1).replace("''", "'")
                        : Character.toString(Integer.parseInt(matcher.group(2))));
            }
            assertThat(chunk.toString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(900);
            value.append(chunk);
        }
        return value.toString();
    }

    @Test
    void respectsByteThresholdParameterModesAndUnknownSignatures() throws Exception {
        for (String type : List.of("IN payload JSON", "IN payload CLOB", "IN payload TEXT", "IN payload LONGTEXT")) {
            for (int length : List.of(3000, 3001)) {
                String value = "A".repeat(length);
                assertThat(migrate("CREATE PROCEDURE repair(" + type + ") BEGIN SELECT 1; END;\n/\n"
                        + "CALL repair('" + value + "');").manualReviewItems()).isEmpty();
                assertThat(output().contains("dm_adapter_clob_arg_1 CLOB;")).isEqualTo(length > 3000);
            }
        }
        for (String signature : List.of("OUT payload CLOB", "INOUT payload JSON", "IN payload VARCHAR(8000)")) {
            assertThat(migrate("CREATE PROCEDURE repair(" + signature + ") BEGIN SELECT 1; END;\n/\n"
                    + "CALL repair('" + "A".repeat(3001) + "');").manualReviewItems()).hasSize(1);
        }
        assertThat(migrate("CALL unknown_repair('" + "A".repeat(3001) + "');").manualReviewItems()).hasSize(1);
    }

    @Test
    void sharesSignaturesAcrossFilesWithinSchemaButIsolatesSystemScripts() throws Exception {
        Files.createDirectories(root.resolve("sql"));
        Files.writeString(root.resolve("sql/01.sql"), """
                CREATE PROCEDURE alpha.repair(IN payload JSON) BEGIN SELECT 1; END;
                /
                """);
        String value = "A".repeat(3001);
        var report = migrate("CALL alpha.repair('" + value + "');\nCALL beta.repair('" + value + "');");
        assertThat(report.manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.originalSql()).contains("beta.repair"));
        Files.writeString(root.resolve("sql/02_system.sql"), "CALL alpha.repair('" + value + "');");
        assertThat(migrate("CALL alpha.repair('" + value + "');").manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.sourceFile()).isEqualTo("02_system.sql"));
    }

    @Test
    void droppedDefinitionsOverrideLegacyLongArgumentWhitelist() throws Exception {
        String value = "A".repeat(3001);
        var report = migrate("DROP PROCEDURE IF EXISTS batch_insert_ns_core_dictionary;\n"
                + "CALL batch_insert_ns_core_dictionary('" + value + "');");
        assertThat(report.manualReviewItems()).isNotEmpty();
        assertThat(output()).doesNotContain("dm_adapter_clob_arg_1 CLOB;");
    }

    @Test
    void propagatesCrossFileAndIndirectDependenciesIntoLongCallBlocks() throws Exception {
        Files.createDirectories(root.resolve("sql"));
        Files.writeString(root.resolve("sql/01.sql"), """
                CREATE PROCEDURE broken_repair()
                BEGIN
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN COMMIT; RESIGNAL; END;
                    SELECT 1;
                END;
                /
                CREATE PROCEDURE custom_repair(IN payload JSON)
                BEGIN CALL broken_repair(); END;
                /
                """);
        var report = migrate("CALL custom_repair('" + "A".repeat(3500) + "');");
        assertThat(report.manualReviewItems()).anySatisfy(item -> {
            assertThat(item.sourceFile()).isEqualTo("test.sql");
            assertThat(item.reason()).contains("custom_repair", "达梦适配");
        });
        assertThat(output()).contains("dm_adapter_clob_arg_1 CLOB;");
    }
}
