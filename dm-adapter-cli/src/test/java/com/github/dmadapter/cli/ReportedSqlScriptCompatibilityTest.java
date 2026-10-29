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
