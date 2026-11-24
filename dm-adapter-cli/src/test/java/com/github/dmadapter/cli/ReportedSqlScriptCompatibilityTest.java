package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline compatibility regressions using synthetic fixtures. */
class ReportedSqlScriptCompatibilityTest {
    @TempDir
    Path root;

    @Test
    void convertsColumnChangesOnlyWhenTheGuardAndDdlUseTheSameSchema() throws Exception {
        String original = """
                DELIMITER $$
                CREATE PROCEDURE sample_rename()
                BEGIN
                    IF EXISTS (SELECT COLUMN_NAME FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND TABLE_NAME = 'sample_table'
                          AND COLUMN_NAME = 'sampleValue') THEN
                        ALTER TABLE `%s`.sample_table CHANGE COLUMN SampleValue sample_value VARCHAR(100);
                    END IF;
                END$$
                DELIMITER ;
                CALL sample_rename();
                """;
        Converted supported = migrate(original.formatted("test-app"), "sample_change.sql");
        assertFullyConverted(supported);
        assertThat(supported.sql()).containsSubsequence("target already exists: sample_value",
                "MODIFY SampleValue VARCHAR(100 CHAR) NULL", "ALTER COLUMN SampleValue DROP DEFAULT",
                "COMMENT ON COLUMN sample_table.SampleValue", "RENAME COLUMN SampleValue TO sample_value");

        Converted foreign = migrate(original.formatted("other-schema"), "sample_change.sql");
        assertThat(foreign.report().manualReviewItems()).hasSize(2);
        assertThat(foreign.sql()).contains("ALTER TABLE `other-schema`.sample_table CHANGE COLUMN")
                .doesNotContain("RENAME COLUMN");
    }

    @Test
    void retainsGuardedColumnAltersWithUnmatchedTablesOrUnsupportedActions() throws Exception {
        for (String body : List.of(
                "ALTER TABLE sample_table CHANGE COLUMN old_value new_value DATETIME, ADD INDEX idx_value (new_value);",
                "ALTER TABLE sample_table CHANGE COLUMN old_value new_value BIGINT;\n"
                        + "ALTER TABLE sample_other CHANGE COLUMN old_name new_name VARCHAR(50);",
                "ALTER TABLE sample_table ADD COLUMN first_value INT, ADD COLUMN second_value INT, DROP COLUMN unrelated;")) {
            Converted converted = migrate(guardedColumnAlters(body), "sample_change.sql");
            assertThat(converted.report().manualReviewItems()).hasSize(2);
            assertThat(converted.sql()).contains(body).doesNotContain("RENAME COLUMN", "EXECUTE IMMEDIATE");
        }
    }

    @Test
    void keepsEachGroupOfColumnAltersUnderOneGuardInSourceOrder() throws Exception {
        Converted addDrop = migrate(guardedColumnAlters("""
                ALTER TABLE sample_table ADD COLUMN first_value VARCHAR(20) DEFAULT NULL COMMENT 'sample; first',
                    ADD COLUMN second_value INT NULL, DROP COLUMN old_value;
                """.strip()), "sample_change.sql");
        assertFullyConverted(addDrop);
        assertThat(addDrop.sql()).containsSubsequence("ADD (first_value VARCHAR(20 CHAR) DEFAULT NULL, second_value INT NULL)",
                "COMMENT ON COLUMN sample_table.first_value IS ''sample; first''", "DROP COLUMN old_value")
                .doesNotContain("UPDATE sample_table");

        Converted renameAdd = migrate(guardedColumnAlters("""
                ALTER TABLE sample_table CHANGE COLUMN old_value new_value DATETIME(0) NULL DEFAULT NULL,
                    CHANGE COLUMN old_label new_label VARCHAR(40), ADD COLUMN optional_value DATETIME(0) NULL;
                """.strip()), "sample_change.sql");
        assertFullyConverted(renameAdd);
        assertThat(renameAdd.sql()).containsSubsequence("RENAME COLUMN old_value TO new_value",
                "RENAME COLUMN old_label TO new_label", "ADD (optional_value DATETIME(0) NULL)");

        Converted repeatedRename = migrate(guardedColumnAlters("""
                ALTER TABLE sample_table CHANGE COLUMN old_value new_value BIGINT DEFAULT NULL;
                ALTER TABLE sample_table CHANGE COLUMN old_label new_label VARCHAR(40);
                """.strip()), "sample_change.sql");
        assertFullyConverted(repeatedRename);
        assertThat(repeatedRename.sql()).containsSubsequence("RENAME COLUMN old_value TO new_value",
                "RENAME COLUMN old_label TO new_label");
        for (Converted converted : List.of(addDrop, renameAdd, repeatedRename)) {
            assertThat(Pattern.compile("SELECT COUNT\\(\\*\\) INTO").matcher(converted.sql()).results().count()).isEqualTo(1);
        }
    }

    private String guardedColumnAlters(String body) {
        return """
                DELIMITER $$
                CREATE PROCEDURE sample_change_columns()
                BEGIN
                    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sample_table'
                          AND COLUMN_NAME = 'old_value') THEN
                        %s
                    END IF;
                END$$
                DELIMITER ;
                CALL sample_change_columns();
                """.formatted(body);
    }

    @Test
    void movesTheSingleMatchCountFilterOutsideEachDerivedQuery() throws Exception {
        Converted converted = migrate("""
                -- sample parent update
                UPDATE sample_relation
                SET parent_id = (SELECT p.dm_adapter_match_count FROM
                    (SELECT MIN(id) AS dm_adapter_match_count FROM sample_relation
                     WHERE kind = 'sample_parent' -- keep this filter
                     HAVING COUNT(*) = 1) AS p), note = 'sample HAVING COUNT(1) = 1'
                WHERE kind = 'sample_child'
                  AND EXISTS (SELECT 1 FROM
                    (SELECT MIN(id) AS id FROM sample_relation WHERE kind = 'sample_parent'
                     HAVING COUNT(1) = 1) q);
                """, "sample_parent.sql");

        assertFullyConverted(converted);
        assertThat(converted.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules()).contains(SqlScriptMigrator.MYSQL_UPDATE_SINGLE_MATCH_AGGREGATE_RULE));
        assertThat(converted.sql()).containsSubsequence("-- sample parent update",
                "MIN(id) AS dm_adapter_match_count, COUNT(*) AS dm_adapter_match_count_2",
                "-- keep this filter\n", ") p WHERE p.dm_adapter_match_count_2 = 1)",
                "note = 'sample HAVING COUNT(1) = 1'", "WHERE kind = 'sample_child'",
                "MIN(id) AS id, COUNT(1) AS dm_adapter_match_count", ") q WHERE q.dm_adapter_match_count = 1)");
        assertThat(Pattern.compile("HAVING COUNT").matcher(converted.sql()).results().count()).isEqualTo(1);
    }

    @Test
    void retainsTheWholeUpdateWhenOneMatchCountCannotBeSafelyRewritten() throws Exception {
        String original = """
                UPDATE sample_relation SET parent_id = (SELECT p.id FROM
                    (SELECT MIN(id) AS id FROM sample_relation HAVING COUNT(1) = 1) p)
                WHERE EXISTS (SELECT 1 FROM
                    (SELECT MIN(id) AS id FROM sample_relation HAVING COUNT(1) > 1) p);
                """;
        Converted converted = migrate(original, "sample_parent.sql");

        assertThat(converted.report().manualReviewItems()).singleElement().satisfies(item ->
                assertThat(item.reason()).contains("HAVING"));
        assertThat(SqlScriptParser.statements(converted.sql())).isEqualTo(SqlScriptParser.statements(original));
    }

    @Test
    void enablesIdentityInsertOnlyForASelectWritingTheKnownIdentityColumn() throws Exception {
        Converted converted = migrate("""
                CREATE TABLE sample_seed (id BIGINT IDENTITY(1,1), label VARCHAR(40), PRIMARY KEY (id));
                CREATE TABLE sample_plain (id BIGINT, label VARCHAR(40), PRIMARY KEY (id));
                INSERT INTO sample_seed (label, id) SELECT '样例', 1 FROM DUAL
                WHERE NOT EXISTS (SELECT 1 FROM sample_seed WHERE id = 1) -- sample seed guard
                ;
                INSERT INTO sample_seed (label) SELECT '生成' FROM DUAL;
                INSERT INTO sample_plain (id, label) SELECT 1, '普通' FROM DUAL;
                """, "sample_seed.sql");

        assertFullyConverted(converted);
        assertThat(converted.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules()).contains(SqlScriptMigrator.MYSQL_INSERT_EXPLICIT_IDENTITY_SELECT_RULE));
        List<String> statements = SqlScriptParser.statements(converted.sql());
        assertThat(statements).hasSize(5);
        assertThat(statements.get(2)).containsSubsequence("SET IDENTITY_INSERT sample_seed ON",
                "INSERT INTO sample_seed (label, id) SELECT '样例', 1 FROM DUAL",
                "WHERE NOT EXISTS (SELECT 1 FROM sample_seed WHERE id = 1) -- sample seed guard\n    ;",
                "SET IDENTITY_INSERT sample_seed OFF", "WHEN OTHERS THEN", "SET IDENTITY_INSERT sample_seed OFF", "RAISE;");
        assertThat(statements.subList(3, 5)).noneSatisfy(sql -> assertThat(sql).contains("IDENTITY_INSERT"));
    }

    @Test
    void retainsIdentitySelectWithUnresolvedProjectionForReview() throws Exception {
        String insert = "INSERT INTO sample_seed (id, label) SELECT source_id, label FROM sample_source";
        String insertZero = "INSERT INTO sample_seed (id, label) SELECT 0, 'sample text' FROM DUAL";
        Converted converted = migrate("""
                CREATE TABLE sample_seed (id BIGINT IDENTITY(1,1), label VARCHAR(40), PRIMARY KEY (id));
                """ + insert + ";\n" + insertZero + ";", "sample_seed.sql");

        assertThat(converted.report().manualReviewItems()).hasSize(2).allSatisfy(item ->
                assertThat(item.reason()).contains("INSERT SELECT", "IDENTITY"));
        assertThat(SqlScriptParser.statements(converted.sql())).endsWith(insert, insertZero);
    }

    @Test
    void convertsSingleConditionalInsertSelectAndPreservesItsVersionPredicate() throws Exception {
        String original = conditionalCopyScript("7");
        Converted converted = migrate(original, "001_system.sql");

        assertFullyConverted(converted);
        assertThat(SqlScriptParser.statements(converted.sql())).hasSize(4);
        assertThat(converted.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules()).contains(SqlScriptMigrator.MYSQL_SCRIPT_CONDITIONAL_INSERT_SELECT_RULE));
        assertThat(converted.sql())
                .contains("ALL_TAB_COLUMNS", "IF dm_adapter_guard_count > 0 THEN", "EXECUTE IMMEDIATE (",
                        "SELECT src.sample_id, 7, ''备注 @literal''", "WHERE src.ver = 1",
                        "NOT EXISTS", "dst.ver = 7", "ELSE\n        NULL;")
                .doesNotContain("PREPARE sample_stmt", "EXECUTE sample_stmt");
    }

    @Test
    void reportsTheEffectiveVersionVariableWithoutExecutingOrRewritingTheGroup() throws Exception {
        String original = "CALL sample_shared();\n" + conditionalCopyScript("@sample_ver");
        Converted converted = migrate(original, "001_system.sql");

        assertThat(converted.report().manualReviewItems()).hasSize(4).allSatisfy(item ->
                assertThat(item.reason()).contains("条件动态 INSERT", "@sample_ver", "共享过程")
                        .doesNotContain("ROW_NUMBER", "@literal"));
        assertThat(SqlScriptParser.statements(converted.sql()))
                .containsExactlyElementsOf(SqlScriptParser.statements(original));
    }

    @Test
    void readsTheConfiguredValueOnceInsideTheBranchAndBindsAllReferences() throws Exception {
        Path sqlRoot = Files.createDirectories(root.resolve("sql/v2"));
        Files.writeString(sqlRoot.resolve(ConditionalPreparedBindings.FILE_NAME),
                "001_system.sql|SAMPLE_SQL|SAMPLE_VER=sample_versions,version_no,1\n");
        String original = "CALL sample_shared();\n" + conditionalCopyScript("@sample_ver");
        Converted converted = migrate(original, "001_system.sql");

        assertFullyConverted(converted);
        assertThat(SqlScriptParser.statements(converted.sql())).hasSize(5);
        assertThat(converted.sql()).containsSubsequence(
                        "CALL sample_shared()", "IF dm_adapter_guard_count > 0 THEN",
                        "SELECT GREATEST(COALESCE(MAX(version_no), 1), 1) FROM sample_versions",
                        "INTO dm_adapter_prepared_value_1;",
                        "SELECT src.sample_id, ?, ''备注 @literal''",
                        "dst.ver = ?", "USING dm_adapter_prepared_value_1, dm_adapter_prepared_value_1",
                        "ELSE\n        NULL;")
                .doesNotContain("@sample_ver", "PREPARE sample_stmt");
        assertThat(Pattern.compile("MAX\\(version_no\\)").matcher(converted.sql()).results().count()).isEqualTo(1);

        assertThat(migrate(original, "002_system.sql").report().manualReviewSqlCount()).isEqualTo(4);
    }

    @Test
    void rejectsExecutableFragmentsInBindingConfiguration() throws Exception {
        Path sqlRoot = Files.createDirectories(root.resolve("sql/v2"));
        Files.writeString(sqlRoot.resolve(ConditionalPreparedBindings.FILE_NAME),
                "001.sql|sample_sql|sample_ver=sample_versions; SELECT 1,version_no,1\n");

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> migrate(conditionalCopyScript("@sample_ver"), "001.sql"))
                .withMessageContaining("无效的条件动态 SQL 绑定");
    }

    @Test
    void rejectsConditionalInsertSelectWithMoreThanOneStatementOrMismatchedProjection() throws Exception {
        for (String original : List.of(
                conditionalCopyScript("7").replace("dst.ver = 7)", "dst.ver = 7); DELETE FROM sample_menu"),
                conditionalCopyScript("7").replace("table_name = 'sample_menu'", "table_name = 'different_table'"),
                conditionalCopyScript("7").replace("SELECT src.sample_id, 7,", "SELECT src.sample_id,"))) {
            Converted converted = migrate(original, "001.sql");
            assertThat(converted.report().manualReviewSqlCount()).isPositive();
            assertThat(converted.sql()).contains("PREPARE sample_stmt");
        }
    }

    private String conditionalCopyScript(String version) {
        return """
                SET @sample_sql = IF(
                    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
                        AND table_name = 'sample_menu' AND column_name = 'ver'),
                    'INSERT INTO sample_menu (sample_id, ver, note)
                     SELECT src.sample_id, %1$s, ''备注 @literal'' FROM sample_menu src
                     WHERE src.ver = 1 AND NOT EXISTS (
                         SELECT 1 FROM sample_menu dst WHERE dst.sample_id = src.sample_id AND dst.ver = %1$s)',
                    'SELECT 1'
                );
                PREPARE sample_stmt FROM @sample_sql;
                EXECUTE sample_stmt;
                DEALLOCATE PREPARE sample_stmt;
                """.formatted(version);
    }

    @Test
    void assignsScalarSubqueryAtTheOuterSelect() throws Exception {
        Converted converted = migrate("""
                DELIMITER $$
                CREATE PROCEDURE sample_assign()
                BEGIN
                    DECLARE sample_value BIGINT;
                    SELECT COALESCE((SELECT value_no FROM sample_versions LIMIT 1), 1) INTO sample_value;
                    SELECT COALESCE((SELECT value_no FROM sample_versions LIMIT 1), 1)
                    FROM sample_records WHERE id = 1 INTO sample_value;
                END$$
                DELIMITER ;
                """, "001.sql");

        assertFullyConverted(converted);
        assertThat(converted.sql())
                .contains("COALESCE((SELECT value_no FROM sample_versions LIMIT 1), 1) INTO sample_value FROM DUAL")
                .contains("COALESCE((SELECT value_no FROM sample_versions LIMIT 1), 1) INTO sample_value FROM sample_records")
                .doesNotContain("SELECT value_no INTO");
    }

    @Test
    void preservesConditionalOwnedTemporaryCleanupAndRethrows() throws Exception {
        Converted converted = migrate(conditionalCleanup("sample_work"), "001.sql");

        assertFullyConverted(converted);
        assertThat(converted.sql())
                .containsPattern("(?is)EXCEPTION\\s+WHEN OTHERS THEN\\s+IF initialized = 1 THEN\\s+DELETE FROM sample_work;\\s+END IF;\\s+RAISE;")
                .containsPattern("(?is)INSERT INTO sample_work.+?;\\s+DELETE FROM sample_work;\\s+initialized := 0;")
                .doesNotContainIgnoringCase("ROLLBACK");
    }

    @Test
    void retainsHandlerWhenExceptionWouldDropAnUnownedTable() throws Exception {
        Converted converted = migrate(conditionalCleanup("sample_other"), "001.sql");

        assertThat(converted.report().manualReviewItems()).isNotEmpty();
        assertThat(converted.sql()).contains("DROP TEMPORARY TABLE IF EXISTS sample_other");
    }

    private String conditionalCleanup(String cleanupTable) {
        return """
                DELIMITER $$
                CREATE PROCEDURE sample_cleanup()
                BEGIN
                    DECLARE initialized TINYINT DEFAULT 0;
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION
                    BEGIN
                        IF initialized = 1 THEN
                            DROP TEMPORARY TABLE IF EXISTS %s;
                        END IF;
                        RESIGNAL;
                    END;
                    DROP TEMPORARY TABLE IF EXISTS sample_work;
                    CREATE TEMPORARY TABLE sample_work (sample_id BIGINT NOT NULL PRIMARY KEY);
                    SET initialized = 1;
                    INSERT INTO sample_work (sample_id) VALUES (1);
                    DROP TEMPORARY TABLE sample_work;
                    SET initialized = 0;
                END$$
                DELIMITER ;
                """.formatted(cleanupTable);
    }

    @Test
    void convertsCombinedTableAndColumnGuardsWithoutDroppingEitherCheck() throws Exception {
        Converted converted = migrate("""
                SET @sample_sql = IF(
                    EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema=DATABASE() AND table_name='sample_archive')
                    AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema=DATABASE() AND table_name='sample_archive' AND column_name='sample_note'),
                    'ALTER TABLE sample_archive ADD COLUMN sample_note VARCHAR(200) NULL DEFAULT '''' COMMENT ''示例备注''',
                    'DO 0');
                PREPARE sample_stmt FROM @sample_sql;
                EXECUTE sample_stmt;
                DEALLOCATE PREPARE sample_stmt;
                """, "001.sql");

        assertFullyConverted(converted);
        assertThat(SqlScriptParser.statements(converted.sql())).hasSize(4);
        assertThat(converted.sql()).contains("ALL_TABLES", "ALL_TAB_COLUMNS", "sample_archive", "sample_note")
                .contains("VARCHAR(200 CHAR)", "COMMENT ON COLUMN", "示例备注")
                .doesNotContain("information_schema", "test-app");
        assertThat(converted.sql()).containsPattern("(?is)IF\\s+.+>\\s*0\\s+AND\\s+.+?=\\s*0\\s+THEN")
                .containsPattern("(?is)THEN\\s+EXECUTE IMMEDIATE.+?ELSE\\s+NULL;");
    }

    @Test
    void keepsUnsupportedCompoundMetadataGuardsForReview() throws Exception {
        Converted converted = migrate("""
                SET @sample_sql = IF(
                    EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema=DATABASE() AND table_name='sample_archive' AND engine='InnoDB')
                    AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema=DATABASE() AND table_name='sample_archive' AND column_name='sample_note'),
                    'ALTER TABLE sample_archive ADD COLUMN sample_note VARCHAR(200) NULL', 'DO 0');
                PREPARE sample_stmt FROM @sample_sql;
                EXECUTE sample_stmt;
                DEALLOCATE PREPARE sample_stmt;
                """, "001.sql");

        assertThat(converted.report().manualReviewItems()).isNotEmpty();
        assertThat(converted.sql()).contains("InnoDB", "@sample_sql");
    }

    @Test
    void convertsConditionalColumnDdlWithoutLosingExistenceChecks() throws Exception {
        Converted converted = migrate(fixture("conditional-columns.sql"), "001.sql");

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains("sample_record_History", "categoryCode",
                "categoryLabel", "分类编码", "分类名称快照");
        assertThat(converted.sql()).containsIgnoringCase("IF")
                .containsIgnoringCase("ALTER TABLE").containsIgnoringCase("EXECUTE IMMEDIATE");
        assertThat(converted.sql()).doesNotContainIgnoringCase("information_schema");
        assertThat(converted.sql()).contains("VARCHAR(64 CHAR)", "VARCHAR(200 CHAR)", "COMMENT ON COLUMN")
                .containsPattern("(?is)IF\\s+\\w+\\s*>\\s*0\\s+THEN\\s+NULL;\\s+ELSE\\s+EXECUTE IMMEDIATE");
    }

    @Test
    void convertsConditionalDictionaryCallsWithoutOverwritingExistingOptions() throws Exception {
        Converted converted = migrate(fixture("conditional-calls.sql"), "002_system.sql");

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains("sampleRequiredOption", "不必填", "必填",
                "DICTIONARY_ID", "ITEM_CODE")
                .containsIgnoringCase("IF").containsIgnoringCase("THEN")
                .containsIgnoringCase("upsert_sample_item");
        assertThat(converted.sql()).containsPattern(
                "(?is)IF\\s+\\w+\\s*>\\s*0\\s+THEN\\s+NULL;\\s+ELSE\\s+CALL upsert_sample_item");
        assertThat(converted.report().files()).singleElement().satisfies(file -> {
            assertThat(file.systemScript()).isTrue();
            assertThat(file.schema()).isEqualTo("test-system");
        });
    }

    @Test
    void convertsConditionalDdlWithCountSubqueriesAndRepeatedPreparedStatementName() throws Exception {
        Converted converted = migrate(fixture("conditional-count-ddl.sql"), "001.sql");

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains("sample_records", "verified",
                "revision", "recordState", "ACTIVE", "INACTIVE")
                .containsIgnoringCase("IF").containsIgnoringCase("EXECUTE IMMEDIATE")
                .doesNotContainIgnoringCase("information_schema");
        assertThat(converted.sql()).contains("VARCHAR(36 CHAR)", "VARCHAR(16 CHAR)")
                .containsPattern("(?is)IF\\s+\\w+\\s*=\\s*0\\s+THEN\\s+EXECUTE IMMEDIATE");
    }

    @Test
    void treatsCountSubPartAsNullnessRatherThanReadingConcretePrefixLength() throws Exception {
        // COUNT ignores NULL; it does not require actual prefix lengths.
        Converted converted = migrate("""
                SELECT index_name FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='sample_settings' AND non_unique=0
                GROUP BY index_name HAVING COUNT(*)=2 AND COUNT(sub_part)=0
                  AND GROUP_CONCAT(column_name ORDER BY seq_in_index)
                      IN ('tenantId,settingGroup','settingGroup,tenantId');
                """, "001.sql");

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains("FROM ALL_IND_EXPRESSIONS E")
                .contains("THEN 1 ELSE NULL END AS SUB_PART")
                .containsIgnoringCase("COUNT(sub_part)=0")
                .contains("'tenantId,settingGroup'", "'settingGroup,tenantId'")
                .doesNotContainIgnoringCase("information_schema");
    }

    @Test
    void convertsSeedRollbackWithCommaSeparatedOwnedTemporaryTableCleanup() throws Exception {

        // All four tables have one explicit outer definition; business seed data is irrelevant.
        Converted converted = migrate(seedCleanupProcedure(true), "001.sql");

        assertFullyConverted(converted);
        assertRollbackCleanupAndRethrow(converted.sql());
    }

    @Test
    void separatelyWrittenCleanupIsAnEquivalentSupportedControl() throws Exception {
        Converted converted = migrate(seedCleanupProcedure(false), "001.sql");

        assertFullyConverted(converted);
        assertRollbackCleanupAndRethrow(converted.sql());
    }

    @Test
    void convertsTransactionHandlerWithoutTurningConditionalRollbackIntoUnconditionalRollback() throws Exception {

        Converted converted = migrate("""
                DELIMITER $$
                CREATE PROCEDURE sample_update_procedure()
                BEGIN
                    DECLARE transactionStarted INT DEFAULT 0;
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION
                    BEGIN
                        IF transactionStarted=1 THEN ROLLBACK; END IF;
                        RESIGNAL;
                    END;
                    START TRANSACTION;
                    SET transactionStarted=1;
                    UPDATE sample_options SET enabled='1'
                    WHERE option_id='sample-option';
                    COMMIT;
                    SET transactionStarted=0;
                END$$
                DELIMITER ;
                CALL sample_update_procedure();
                """, "002_system.sql");

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains("EXCEPTION", "WHEN OTHERS THEN", "RAISE;")
                .doesNotContain("DECLARE EXIT HANDLER", "RESIGNAL");
        String handler = converted.sql().substring(converted.sql().lastIndexOf("EXCEPTION"));
        assertThat(handler).containsPattern("(?is)IF\\s+transactionStarted\\s*=\\s*1\\s+THEN\\s+ROLLBACK;\\s*END IF;\\s*RAISE;");
        assertThat(converted.sql()).contains("option_id='sample-option'");
    }

    @Test
    void doesNotMislabelEngineMetadataPredicateAsCreateTableOption() {
        // ENGINE is a metadata column in this predicate.
        // This assertion concerns diagnostic classification, not DM support for information_schema.
        String reason = GeneratedSqlStaticInspector.manualReviewReason("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE()
                  AND TABLE_NAME IN ('sample_record','sample_record_recipient')
                  AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB'
                """);

        assertThat(reason).doesNotContain("ENGINE 表选项");
    }

    @Test
    void stillRejectsActualEngineTableOption() {
        assertThat(GeneratedSqlStaticInspector.manualReviewReason(
                "CREATE TABLE sample_record (id VARCHAR(36)) ENGINE=InnoDB"))
                .contains("ENGINE 表选项");
    }

    @Test
    void preservesExplicitlyConfiguredDmScriptInsteadOfRegeneratingIt() throws Exception {
        Path source = root.resolve("sql/v2/001.sql");
        Path output = root.resolve("sql/v2-dm/001.sql");
        String mysql = fixture("conditional-count-ddl.sql");
        String nativeDm = "BEGIN NULL; END;\n/\n";
        Files.createDirectories(source.getParent());
        Files.createDirectories(output.getParent());
        Files.writeString(source, mysql);
        Files.writeString(output, nativeDm);

        SqlScriptMigrationReport report = migrator().migrate(new SqlScriptMigrationRequest(
                root, source.getParent(), output.getParent(), false, "test-app", "test-system",
                List.of(Path.of("001.sql")), DmValidationEnvironment.from(Map.of())));

        assertThat(Files.readString(source)).isEqualTo(mysql);
        assertThat(Files.readString(output)).isEqualTo(nativeDm);
        assertThat(report.manualReviewItems()).isEmpty();
        assertThat(report.files()).singleElement().satisfies(file ->
                assertThat(file.sourceFile()).isEqualTo("(output-only) 001.sql"));
        assertThat(report.validationAttempted()).isFalse();
    }

    @Test
    void leavesIncompleteOrInterleavedPreparedSequencesForManualReview() throws Exception {
        String source = fixture("conditional-columns.sql");
        for (String unsafe : List.of(
                source.replace("DEALLOCATE PREPARE sample_ddl_stmt;", ""),
                source + "SELECT @sample_ddl_sql;",
                source.replace("EXECUTE sample_ddl_stmt;", "UPDATE accounts SET amount=0; EXECUTE sample_ddl_stmt;"),
                source + source.substring(0, source.indexOf("PREPARE sample_ddl_stmt FROM")),
                source.replace("PREPARE sample_ddl_stmt FROM", "PREPARE different_stmt FROM"))) {
            assertThat(migrate(unsafe, "unsafe.sql").report().manualReviewItems()).isNotEmpty();
        }
    }

    @Test
    void countsQualifiedQuotedSubPartButRejectsValueDependentAggregates() throws Exception {
        String query = "SELECT %s FROM information_schema.statistics s WHERE s.table_name='demo';";
        assertFullyConverted(migrate(query.formatted("COUNT(s.`SUB_PART`)"), "count.sql"));
        for (String expression : List.of("COUNT(DISTINCT sub_part)", "SUM(sub_part)", "COUNT(sub_part+1)")) {
            assertThat(migrate(query.formatted(expression), "unsafe.sql").report().manualReviewItems())
                    .extracting(SqlScriptManualReviewItem::reason)
                    .anySatisfy(reason -> assertThat(reason).contains("SUB_PART_UNSUPPORTED"));
        }
    }

    @Test
    void refusesCleanupWhenAnyTableIsNotOwnedByTheProcedure() throws Exception {
        String sql = seedCleanupProcedure(true).replace(
                "tmpMissingSeed,tmpBindingSeed,",
                "unknown_table,tmpBindingSeed,");
        assertThat(migrate(sql, "unsafe.sql").report().manualReviewItems()).isNotEmpty();
    }

    @Test
    void engineColumnExpressionsAreNotTableOptions() {
        for (String sql : List.of("UPDATE jobs SET engine='InnoDB' WHERE id=1",
                "CREATE TABLE copy AS SELECT engine FROM jobs WHERE engine='InnoDB'",
                "CREATE TABLE jobs (engine VARCHAR(20), CHECK (engine='InnoDB'))")) {
            assertThat(GeneratedSqlStaticInspector.manualReviewReason(sql)).isEmpty();
        }
        assertThat(GeneratedSqlStaticInspector.manualReviewReason("ALTER TABLE jobs ENGINE=InnoDB"))
                .contains("ENGINE 表选项");
    }

    @Test
    void engineMetadataStillRequiresAnExplicitDamengEquivalent() throws Exception {
        Converted converted = migrate("""
                SELECT COUNT(*) FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' AND ENGINE='InnoDB';
                """, "engine.sql");
        assertThat(converted.report().manualReviewItems()).singleElement().satisfies(item ->
                assertThat(item.reason()).contains("ENGINE").doesNotContain("ENGINE 表选项"));
        assertThat(converted.sql()).contains("information_schema.TABLES");
    }

    @Test
    void doesNotGuessBackslashDecodingInNestedPreparedSqlLiterals() throws Exception {
        String source = fixture("conditional-calls.sql").replace("不必填", "不\\n必填");
        assertThat(migrate(source, "escape_system.sql").report().manualReviewItems()).isNotEmpty();
    }

    private String seedCleanupProcedure(boolean combinedDrop) {
        String tables = "tmpMissingSeed,tmpBindingSeed,"
                + "tmpStepSeed,tmpSeed";
        String cleanup = combinedDrop ? "DROP TEMPORARY TABLE IF EXISTS " + tables + ";"
                : String.join("\n", List.of(tables.split(",")).stream()
                        .map(table -> "DROP TEMPORARY TABLE IF EXISTS " + table + ";").toList());
        return """
                DELIMITER $$
                CREATE PROCEDURE sample_seed_procedure()
                BEGIN
                    DECLARE EXIT HANDLER FOR SQLEXCEPTION
                    BEGIN
                        ROLLBACK;
                        %s
                        RESIGNAL;
                    END;
                    CREATE TEMPORARY TABLE tmpSeed (itemCode VARCHAR(64) PRIMARY KEY);
                    CREATE TEMPORARY TABLE tmpStepSeed (itemCode VARCHAR(64), stepNo INT);
                    CREATE TEMPORARY TABLE tmpBindingSeed (itemCode VARCHAR(64) PRIMARY KEY);
                    START TRANSACTION;
                    CREATE TEMPORARY TABLE tmpMissingSeed (itemCode VARCHAR(64) PRIMARY KEY, itemId BIGINT);
                    INSERT INTO tmpMissingSeed(itemCode,itemId) VALUES ('seed',1);
                    COMMIT;
                END$$
                DELIMITER ;
                CALL sample_seed_procedure();
                """.formatted(cleanup);
    }

    private void assertRollbackCleanupAndRethrow(String sql) {
        assertThat(sql).contains("EXCEPTION", "WHEN OTHERS THEN", "RAISE;")
                .doesNotContain("DECLARE EXIT HANDLER", "RESIGNAL");
        String handler = sql.substring(sql.lastIndexOf("EXCEPTION"));
        int rollback = handler.indexOf("ROLLBACK;");
        int rethrow = handler.indexOf("RAISE;");
        assertThat(rollback).isGreaterThanOrEqualTo(0).isLessThan(rethrow);
        for (String table : List.of("tmpMissingSeed", "tmpBindingSeed",
                "tmpStepSeed", "tmpSeed")) {
            assertThat(handler.indexOf("DELETE FROM " + table)).as("cleanup of %s", table)
                    .isGreaterThan(rollback).isLessThan(rethrow);
        }
    }

    private Converted migrate(String sql, String filename) throws Exception {
        Path source = root.resolve("sql/v2").resolve(filename);
        Path outputRoot = root.resolve("sql/v2-dm");
        Files.createDirectories(source.getParent());
        Files.writeString(source, sql);
        SqlScriptMigrationReport report = migrator().migrate(new SqlScriptMigrationRequest(
                root, source.getParent(), outputRoot, false, "test-app", "test-system",
                DmValidationEnvironment.from(Map.of())));
        assertThat(Files.readString(source)).isEqualTo(sql);
        assertThat(report.validationAttempted()).isFalse();
        return new Converted(report, Files.readString(outputRoot.resolve(filename)));
    }

    private SqlScriptMigrator migrator() {
        return new SqlScriptMigrator(new MySqlToDmSqlConverter(),
                (files, env) -> SqlScriptValidationRun.notAttempted("offline regression", List.of()));
    }

    private void assertFullyConverted(Converted converted) {
        assertThat(converted.report().manualReviewItems()).extracting(SqlScriptManualReviewItem::reason).isEmpty();
        assertThat(SqlScriptParser.statements(converted.sql())).isNotEmpty().allSatisfy(statement -> {
            assertThat(GeneratedSqlStaticInspector.manualReviewReason(statement)).isEmpty();
            assertThat(Pattern.compile("(?im)^\\s*(?:PREPARE|DEALLOCATE\\s+PREPARE)\\b")
                    .matcher(statement).find()).isFalse();
        });
    }

    static String fixture(String name) throws Exception {
        try (InputStream input = Objects.requireNonNull(ReportedSqlScriptCompatibilityTest.class
                .getResourceAsStream("/compatibility/" + name), name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Converted(SqlScriptMigrationReport report, String sql) {
    }
}
