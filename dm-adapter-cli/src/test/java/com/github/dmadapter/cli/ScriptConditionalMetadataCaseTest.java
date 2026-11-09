package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptConditionalMetadataCaseTest {
    @TempDir
    Path root;

    @Test
    void convertsRepeatedLowercaseGuardsWithIndependentChecksAndStatementMappings() throws Exception {
        List<String> tables = List.of("Sample_Record", "sample_detail", "sample_history");
        StringBuilder source = new StringBuilder();
        for (String table : tables) {
            source.append(sequence(table, "LOWER(TABLE_NAME) = '" + table.toLowerCase(Locale.ROOT) + "'"));
        }

        Converted converted = migrate(source.toString());

        assertFullyConverted(converted);
        List<String> statements = SqlScriptParser.statements(converted.sql());
        assertThat(statements).hasSize(12);
        for (int index = 0; index < tables.size(); index++) {
            String table = tables.get(index);
            String block = statements.get(index * 4 + 2);
            assertThat(block).contains("FROM ALL_TAB_COLUMNS")
                    .contains("OWNER = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)")
                    .contains("LOWER(TABLE_NAME) = '" + table.toLowerCase(Locale.ROOT) + "'")
                    .contains("UPPER(COLUMN_NAME) = UPPER('sampleTag')")
                    .contains("IF dm_adapter_guard_count > 0 THEN\n        NULL;\n    ELSE")
                    .contains("EXECUTE IMMEDIATE 'ALTER TABLE `" + table
                            + "` ADD `sampleTag` VARCHAR(100 CHAR) NULL'")
                    .contains("COMMENT ON COLUMN `" + table + "`.`sampleTag` IS ''示例标记（分类）''")
                    .contains("END IF;");
            assertThat(block.split("SELECT COUNT", -1)).hasSize(2);
            for (int offset : List.of(0, 1, 3)) {
                assertThat(statements.get(index * 4 + offset)).contains("NULL")
                        .doesNotContain("SELECT COUNT", "EXECUTE IMMEDIATE");
            }
        }
        assertThat(converted.sql()).doesNotContain("sample-target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "LOWER(TABLE_NAME) = 'Sample_Record'",
            "UPPER ( table_name ) = 'SAMPLE_RECORD'",
            "LOWER(COLUMN_NAME) = 'sampletag'",
            "UPPER ( column_name ) = 'SAMPLETAG'"
    })
    void preservesExplicitCaseFunctionsAndLiteralCase(String predicate) throws Exception {
        Converted converted = migrate(sequence("sample_record", predicate));

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains(predicate.replace("table_name", "TABLE_NAME")
                .replace("column_name", "COLUMN_NAME"));
    }

    @Test
    void preservesNotExistsDirectionAndDefaultValue() throws Exception {
        String source = sequence("sample_record", "LOWER(TABLE_NAME) = 'sample_record'")
                .replace("IF(EXISTS(", "IF(NOT EXISTS(")
                .replace("NULL COMMENT", "DEFAULT ''示例''''值'' COMMENT");

        Converted converted = migrate(source);

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains("IF dm_adapter_guard_count = 0 THEN\n        NULL;\n    ELSE")
                .contains("DEFAULT ''示例''''值''");
    }

    @Test
    void supportsCaseFunctionsInScalarCountGuards() throws Exception {
        String source = sequence("sample_record", "LOWER(TABLE_NAME) = 'sample_record'")
                .replace("EXISTS(\n    SELECT 1", "(\n    SELECT COUNT(*)")
                .replace("), 'SELECT 1'", ") = 0, 'SELECT 1'");

        Converted converted = migrate(source);

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains("LOWER(TABLE_NAME) = 'sample_record'")
                .contains("IF dm_adapter_guard_count = 0 THEN\n        NULL;\n    ELSE");
    }

    @Test
    void keepsUnsupportedExpressionsAndNonMetadataSourcesForReview() throws Exception {
        String source = sequence("sample_record", "LOWER(TABLE_NAME) = 'sample_record'");
        for (String unsafe : List.of(
                source.replace("LOWER(TABLE_NAME)", "sample_function(TABLE_NAME)"),
                source.replace("LOWER(TABLE_NAME)", "LOWER(CONCAT(TABLE_NAME, ''))"),
                source.replace("LOWER(TABLE_NAME)", "LOWER(DATA_TYPE)"),
                source.replace("LOWER(TABLE_NAME)", "LOWER(TABLE_SCHEMA)"),
                source.replace("'sample_record'", "LOWER('SAMPLE_RECORD')"),
                source.replace("'sample_record'", "1"),
                source.replace("'sample_record'", "DATABASE()"),
                source.replace("AND COLUMN_NAME", "OR COLUMN_NAME"),
                source.replace("'sampleTag'\n)", "'sampleTag' GROUP BY TABLE_NAME\n)"),
                source.replace("information_schema.COLUMNS", "sample_catalog"),
                source.replace("information_schema.COLUMNS", "information_schema.TABLES"))) {
            assertRetainedForReview(unsafe);
        }
    }

    @Test
    void stillRequiresCompleteSequencesWithoutInterleavingOrVariableEscape() throws Exception {
        String source = sequence("sample_record", "LOWER(TABLE_NAME) = 'sample_record'");
        for (String unsafe : List.of(
                source.replace("DEALLOCATE PREPARE sample_stmt;", ""),
                source.replace("EXECUTE sample_stmt;", "EXECUTE different_stmt;"),
                source.replace("PREPARE sample_stmt FROM", "UPDATE sample_record SET sample_id=1; PREPARE sample_stmt FROM"),
                source + "SELECT @sample_sql;",
                source.replace("示例标记", "示例\\n标记"))) {
            assertRetainedForReview(unsafe);
        }
    }

    private String sequence(String table, String predicate) {
        return """
                -- synthetic optional column upgrade
                SET @sample_sql = IF(EXISTS(
                    SELECT 1 FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND %s
                      AND COLUMN_NAME = 'sampleTag'
                ), 'SELECT 1', 'ALTER TABLE `%s` ADD COLUMN `sampleTag` VARCHAR(100) NULL COMMENT ''示例标记（分类）''');
                PREPARE sample_stmt FROM @sample_sql;
                EXECUTE sample_stmt;
                DEALLOCATE PREPARE sample_stmt;
                """.formatted(predicate, table);
    }

    private void assertRetainedForReview(String source) throws Exception {
        Converted converted = migrate(source);
        assertThat(converted.report().manualReviewItems()).as(source).isNotEmpty();
        assertThat(converted.sql()).contains("SET @sample_sql", "PREPARE sample_stmt")
                .doesNotContain("EXECUTE IMMEDIATE");
    }

    private void assertFullyConverted(Converted converted) {
        assertThat(converted.report().manualReviewItems()).isEmpty();
        assertThat(SqlScriptParser.statements(converted.sql())).allSatisfy(statement ->
                assertThat(GeneratedSqlStaticInspector.manualReviewReason(statement)).isEmpty());
        assertThat(converted.sql()).doesNotContain("@sample_sql", "information_schema", "PREPARE sample_stmt");
    }

    private Converted migrate(String sql) throws Exception {
        Path source = root.resolve("sql/v2/sample.sql");
        Path output = root.resolve("sql/v2-dm");
        Files.createDirectories(source.getParent());
        Files.writeString(source, sql);
        SqlScriptMigrationReport report = new SqlScriptMigrator(new MySqlToDmSqlConverter(),
                (files, environment) -> {
                    throw new AssertionError("Offline regression must not validate against a database");
                }).migrate(new SqlScriptMigrationRequest(root, source.getParent(), output, false,
                        "sample-target", "", DmValidationEnvironment.batchSilent()));
        assertThat(Files.readString(source)).isEqualTo(sql);
        assertThat(report.validationAttempted()).isFalse();
        return new Converted(report, Files.readString(output.resolve("sample.sql")));
    }

    private record Converted(SqlScriptMigrationReport report, String sql) {
    }
}
