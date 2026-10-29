package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptCountSnapshotTest {
    @TempDir
    Path root;

    @Test
    void capturesCountOnceBeforeAnAdjacentInsertWhilePreservingEarlierUpdate() throws Exception {
        Converted result = migrate("""
                SET @sample_value = '101,102,103';
                UPDATE `sample_settings` SET setting_value=@sample_value, updated_at=NOW()
                WHERE tenant_id=7 AND setting_key='sample_feature';
                -- Capture after the update.
                SET @sample_count = (SELECT COUNT(1) FROM `sample_settings`
                    WHERE tenant_id=7 AND setting_key='sample_feature');
                -- Insert only when the captured count is zero.
                INSERT INTO `sample_settings` (tenant_id,setting_key,setting_value,updated_at)
                SELECT 7,'sample_feature',@sample_value,NOW()
                FROM (SELECT 1 AS i) d WHERE @sample_count=0;
                """);

        assertThat(result.report().manualReviewItems()).isEmpty();
        List<String> statements = SqlScriptParser.statements(result.sql());
        assertThat(statements).hasSize(4).allSatisfy(statement ->
                assertThat(GeneratedSqlStaticInspector.manualReviewReason(statement)).isEmpty());
        String block = statements.get(3);
        assertThat(block).contains("DECLARE", "BIGINT", "SELECT COUNT(1) INTO dm_adapter_count_snapshot",
                        "WHERE dm_adapter_count_snapshot=0", "'101,102,103'", "NOW()",
                        "-- Insert only when the captured count is zero.")
                .doesNotContain("@sample_count", "@sample_value", "CREATE PROCEDURE", "CASE WHEN COUNT");
        assertThat(block.split("COUNT\\(1\\)", -1)).hasSize(2);
        assertThat(block.indexOf("INTO dm_adapter_count_snapshot")).as(block)
                .isLessThan(block.indexOf("INSERT INTO"));
        assertThat(statements.get(1)).contains("UPDATE `sample_settings`", "setting_value='101,102,103'");
        assertThat(result.sql()).contains("-- Capture after the update.");
        assertThat(result.report().files()).singleElement().satisfies(file ->
                assertThat(file.appliedRules()).contains(SqlScriptMigrator.MYSQL_SCRIPT_USER_VARIABLE_SNAPSHOT_BLOCK_RULE));
    }

    @Test
    void retainsTheFullCountAndSupportsColonAssignment() throws Exception {
        Converted result = migrate("""
                SET @sample_count := (SELECT COUNT(*) FROM sample_settings WHERE tenant_id=7);
                INSERT INTO sample_settings(tenant_id,setting_key)
                SELECT 7,'sample_feature' FROM (SELECT 1 AS i) d WHERE @sample_count>1;
                """);
        assertThat(result.report().manualReviewItems()).isEmpty();
        assertThat(SqlScriptParser.statements(result.sql())).hasSize(2);
        assertThat(result.sql()).contains("SELECT COUNT(*) INTO dm_adapter_count_snapshot",
                        "WHERE dm_adapter_count_snapshot>1")
                .doesNotContain("CASE WHEN COUNT", "@sample_count");
    }

    @Test
    void retainsSnapshotsWithInterveningWritesOrLaterReferencesForReview() throws Exception {
        for (String sql : List.of(
                "SET @sample_count=(SELECT COUNT(1) FROM sample_settings); "
                        + "DELETE FROM sample_settings; "
                        + insert("@sample_count=0") + ";",
                "SET @sample_count=(SELECT COUNT(1) FROM sample_settings); "
                        + insert("@sample_count=0") + "; SELECT @sample_count;")) {
            assertRetainedForReview(migrate(sql));
        }
    }

    @Test
    void retainsUnknownParametersAndUnsupportedInsertSyntaxForReview() throws Exception {
        for (String sql : List.of(
                "SET @sample_count=(SELECT COUNT(1) FROM sample_settings); "
                        + insert("@sample_count=0 AND @missing_flag=1") + ";",
                "SET @sample_count=(SELECT COUNT(1) FROM sample_settings); "
                        + "INSERT IGNORE INTO sample_settings(tenant_id) SELECT 7 WHERE @sample_count=0;")) {
            assertRetainedForReview(migrate(sql));
        }
    }

    @Test
    void doesNotReuseAParameterAfterItsValueBecomesUnknown() throws Exception {
        for (String change : List.of("CALL sample_refresh();", "SET @sample_value=sample_function();",
                "UPDATE sample_settings SET setting_value=sample_function();",
                "UPDATE sample_settings SET updated_at=sample_schema.NOW();")) {
            Converted result = migrate("SET @sample_value='initial'; " + change
                    + " SET @sample_count=(SELECT COUNT(1) FROM sample_settings); "
                    + "INSERT INTO sample_settings(tenant_id,setting_key) "
                    + "SELECT 7,@sample_value FROM (SELECT 1 AS i) d WHERE @sample_count=0;");
            assertRetainedForReview(result);
        }
    }

    @Test
    void retainsGroupedLimitedCountsAndLocalNameCollisionsForReview() throws Exception {
        for (String query : List.of(
                "SELECT COUNT(1) FROM sample_settings GROUP BY tenant_id",
                "SELECT COUNT(1) FROM sample_settings LIMIT 0",
                "SELECT COUNT(DISTINCT tenant_id) FROM sample_settings")) {
            assertRetainedForReview(migrate("SET @sample_count=(" + query + "); " + insert("@sample_count=0") + ";"));
        }
        Converted collision = migrate("SET @sample_count=(SELECT COUNT(1) FROM sample_settings); "
                + "INSERT INTO sample_settings(dm_adapter_count_snapshot) "
                + "SELECT 7 FROM (SELECT 1 AS i) d WHERE @sample_count=0;");
        assertThat(collision.report().manualReviewItems()).isNotEmpty();
        assertThat(collision.sql()).contains("@sample_count").doesNotContain("INTO dm_adapter_count_snapshot FROM");
    }

    private String insert(String condition) {
        return "INSERT INTO sample_settings(tenant_id) SELECT 7 FROM (SELECT 1 AS i) d WHERE " + condition;
    }

    private void assertRetainedForReview(Converted result) {
        assertThat(result.report().manualReviewItems()).isNotEmpty();
        assertThat(result.sql()).contains("@sample_count").doesNotContain("dm_adapter_count_snapshot");
    }

    private Converted migrate(String sql) throws Exception {
        Path project = Files.createTempDirectory(root, "sample-");
        Path source = Files.createDirectories(project.resolve("sql"));
        Path output = project.resolve("sql-dm");
        Files.writeString(source.resolve("001.sql"), sql);
        SqlScriptMigrationReport report = new SqlScriptMigrator(new MySqlToDmSqlConverter(),
                (files, environment) -> SqlScriptValidationRun.notAttempted("offline regression", List.of()))
                .migrate(new SqlScriptMigrationRequest(project, source, output, false, "", "",
                        DmValidationEnvironment.from(Map.of())));
        assertThat(Files.readString(source.resolve("001.sql"))).isEqualTo(sql);
        assertThat(report.validationAttempted()).isFalse();
        return new Converted(report, Files.readString(output.resolve("001.sql")));
    }

    private record Converted(SqlScriptMigrationReport report, String sql) {
    }
}
