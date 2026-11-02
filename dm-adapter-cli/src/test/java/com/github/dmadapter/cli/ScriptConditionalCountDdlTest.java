package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptConditionalCountDdlTest {
    @TempDir
    Path root;

    @Test
    void convertsRepeatedCountSelectedDdlWithCommentsAndAllStatementMappings() throws Exception {
        List<String> types = List.of("varchar(100)", "int", "datetime", "varchar(100)", "varchar(255)", "text");
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < types.size(); index++) {
            source.append(sequence("sample_" + index, types.get(index)));
        }

        Converted converted = migrate(source.toString());

        assertFullyConverted(converted);
        List<String> statements = SqlScriptParser.statements(converted.sql());
        assertThat(statements).hasSize(24);
        for (int index = 0; index < types.size(); index++) {
            String block = statements.get(index * 4 + 2);
            assertThat(block).contains("SELECT COUNT(*) INTO dm_adapter_guard_count FROM ALL_TAB_COLUMNS")
                    .contains("OWNER = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)")
                    .contains("UPPER(TABLE_NAME) = UPPER('sample_table')")
                    .contains("UPPER(COLUMN_NAME) = UPPER('sample_" + index + "')")
                    .contains("IF dm_adapter_guard_count = 0 THEN")
                    .contains("EXECUTE IMMEDIATE 'ALTER TABLE `sample_table` ADD `sample_" + index + "`")
                    .contains("COMMENT ON COLUMN `sample_table`.`sample_" + index + "`")
                    .contains("示例字段", "ELSE\n        NULL;");
            assertThat(block.split("SELECT COUNT", -1)).hasSize(2);
            for (int offset : List.of(0, 1, 3)) {
                assertThat(statements.get(index * 4 + offset)).contains("NULL")
                        .doesNotContain("EXECUTE IMMEDIATE", "SELECT COUNT");
            }
        }
        assertThat(converted.sql()).contains("VARCHAR(100 CHAR)", "VARCHAR(255 CHAR)")
                .contains("ADD `sample_5` CLOB NULL")
                .containsIgnoringCase("ADD `sample_2` datetime NULL");
    }

    @Test
    void supportsColonAssignmentAndCountOneWithoutChangingBranchDirection() throws Exception {
        String source = sequence("sample_note", "varchar(100)")
                .replace("@sample_sql =", "@sample_sql :=")
                .replace("COUNT(*)", "COUNT(1)")
                .replace("NULL COMMENT", "DEFAULT ''示例''''值'' COMMENT");

        Converted converted = migrate(source);

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains("IF dm_adapter_guard_count = 0 THEN")
                .contains("DEFAULT ''示例''''值''")
                .contains("COMMENT ON COLUMN");
    }

    @Test
    void alsoConvertsTextColumnsInTheExistingDirectConditionalForm() throws Exception {
        Converted converted = migrate("""
                SET @sample_sql = IF((SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name='sample_table' AND column_name='sample_note')=0,
                    'ALTER TABLE sample_table ADD COLUMN sample_note TEXT NULL COMMENT ''示例备注''', 'SELECT 1');
                PREPARE sample_stmt FROM @sample_sql;
                EXECUTE sample_stmt;
                DEALLOCATE PREPARE sample_stmt;
                """);

        assertFullyConverted(converted);
        assertThat(converted.sql()).contains("ADD sample_note CLOB NULL", "COMMENT ON COLUMN sample_table.sample_note");
    }

    @Test
    void retainsQueriesWhoseScalarCountOrPredicateCannotBeProven() throws Exception {
        String source = sequence("sample_note", "varchar(100)");
        for (String unsafe : List.of(
                source.replace("COUNT(*)", "COUNT(DISTINCT column_name)"),
                source.replace("COUNT(*)", "COUNT(column_name)"),
                source.replace("COUNT(*) = 0", "COUNT(*) + sample_function() = 0"),
                source.replace("'sample_note');", "'sample_note' GROUP BY table_name);"),
                source.replace("'sample_note');", "'sample_note' HAVING COUNT(*) > 0);"),
                source.replace("'sample_note');", "'sample_note' LIMIT 0);"),
                source.replace("'sample_note');", "'sample_note' UNION SELECT 'SELECT 1');"),
                source.replace("AND column_name", "OR column_name"),
                source.replace("information_schema.columns", "information_schema.statistics"))) {
            assertRetainedForReview(unsafe);
        }
    }

    @Test
    void retainsIncompleteSequencesEscapingVariablesAndUnknownActions() throws Exception {
        String source = sequence("sample_note", "varchar(100)");
        for (String unsafe : List.of(
                source.replace("DEALLOCATE PREPARE sample_stmt;", ""),
                source.replace("EXECUTE sample_stmt;", "EXECUTE different_stmt;"),
                source.replace("PREPARE sample_stmt", "UPDATE sample_table SET sample_id=1; PREPARE sample_stmt"),
                source + "SELECT @sample_sql;",
                source.replace("'SELECT 1'", "CONCAT('SELECT ', @sample_parameter)"),
                source.replace("varchar(100) NULL", "varchar(100) NULL NOT NULL"),
                source.replace("示例字段", "示例\\n字段"))) {
            assertRetainedForReview(unsafe);
        }
    }

    private String sequence(String column, String type) {
        return """
                -- synthetic column upgrade
                SET @sample_sql = (SELECT IF(COUNT(*) = 0,
                    'ALTER TABLE `sample_table` ADD COLUMN `%s` %s NULL COMMENT ''示例字段''',
                    'SELECT 1') FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'sample_table' AND column_name = '%s');
                PREPARE sample_stmt FROM @sample_sql;
                -- execute only the chosen branch
                EXECUTE sample_stmt;
                DEALLOCATE PREPARE sample_stmt;
                """.formatted(column, type, column);
    }

    private void assertRetainedForReview(String source) throws Exception {
        Converted converted = migrate(source);
        assertThat(converted.report().manualReviewItems()).as(source).isNotEmpty();
        assertThat(converted.sql()).contains("SET @sample_sql", "PREPARE sample_stmt")
                .doesNotContain("EXECUTE IMMEDIATE");
    }

    private void assertFullyConverted(Converted converted) {
        assertThat(converted.report().manualReviewItems()).extracting(SqlScriptManualReviewItem::reason).isEmpty();
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
                (files, environment) -> SqlScriptValidationRun.notAttempted("offline regression", List.of()))
                .migrate(new SqlScriptMigrationRequest(root, source.getParent(), output, false, "", "",
                        DmValidationEnvironment.from(Map.of())));
        assertThat(Files.readString(source)).isEqualTo(sql);
        assertThat(report.validationAttempted()).isFalse();
        return new Converted(report, Files.readString(output.resolve("sample.sql")));
    }

    private record Converted(SqlScriptMigrationReport report, String sql) {
    }
}
