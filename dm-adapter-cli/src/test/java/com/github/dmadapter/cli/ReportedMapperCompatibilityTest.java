package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.mybatis.MapperMigrator;
import com.github.dmadapter.mybatis.MapperXmlScanner;
import com.github.dmadapter.mybatis.SqlRewriteConfig;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline compatibility regressions using synthetic fixtures. */
class ReportedMapperCompatibilityTest {
    private static final String METHOD =
            "com.example.mapper.SampleRecordMapper.upsertRecord";
    private static final List<String> COLUMNS = List.of("id", "tenantId", "title", "sourceType",
            "categoryName", "authorName", "content", "status", "audience", "publishedAt",
            "expiresAt", "referenceId", "referenceStatus", "referenceKey", "createdBy",
            "creatorName", "createdAt", "updatedAt", "deleted");

    @TempDir
    Path root;

    @Test
    void preservesConflictKeyAssignmentAndExistingStatusAfterAVerifiedDuplicate() throws Exception {
        String xml = mapper("""
                <insert id="upsertRecord">
                    INSERT INTO sample_record (unique_code, hit_count, result_text)
                    VALUES (#{code}, #{hits}, #{result})
                    ON DUPLICATE KEY UPDATE
                        unique_code = VALUES(unique_code), hit_count = hit_count + 1,
                        result_text = CASE WHEN state_code IN ('DONE', 'IGNORED') THEN result_text ELSE NULL END
                </insert>
                """);
        MapperMigrationResult preview = migrate(xml,
                new SqlRewriteConfig(Map.of(), Map.of(METHOD, List.of("unique_code"))));
        assertThat(preview.manualReviewItems()).hasSize(1);
        List<RewriteConfigCandidate> candidates = new MigrateCommand().rewriteConfigCandidates(preview);
        TableConstraint primary = new TableConstraint("sample_pk", TableConstraint.ConstraintType.PRIMARY_KEY,
                List.of("generated_id"));
        TableConstraint business = new TableConstraint("sample_uk", TableConstraint.ConstraintType.UNIQUE_KEY,
                List.of("unique_code"));
        var context = AdapterContext.builder(root).dryRun(true).build();
        Path cfg = root.resolve("sample-rewrite.yml");
        var metadata = new TableKeyMetadata("sample_record", List.of(primary, business), true,
                Set.of("generated_id"));
        var update = new SqlRewriteConfigUpdater().update(context, cfg, SqlRewriteConfig.empty(),
                candidates, Map.of("sample_record", metadata), true);
        assertThat(update.rewriteConfig().verifiedSingleConflictKeys()).containsKey(METHOD);
        MapperMigrationResult result = migrate(xml, update.rewriteConfig());
        assertThat(result.manualReviewItems()).isEmpty();
        BoundSql sql = boundSql(Files.readString(output()));
        assertThat(sql.getSql()).containsIgnoringCase("WHEN DUP_VAL_ON_INDEX")
                .containsIgnoringCase("unique_code = ?")
                .containsIgnoringCase("CASE WHEN t.state_code IN")
                .containsIgnoringCase("SQL%ROWCOUNT <> 1");
        assertThat(sql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("code", "hits", "result", "code", "code");

        TableConstraint other = new TableConstraint("sample_other", TableConstraint.ConstraintType.UNIQUE_KEY,
                List.of("result_text"));
        metadata = new TableKeyMetadata("sample_record", List.of(primary, business, other), true,
                Set.of("generated_id"));
        update = new SqlRewriteConfigUpdater().update(context, cfg, SqlRewriteConfig.empty(),
                candidates, Map.of("sample_record", metadata), true);
        assertThat(update.rewriteConfig().verifiedSingleConflictKeys()).isEmpty();
        assertThat(migrate(xml, update.rewriteConfig()).manualReviewItems()).hasSize(1);
    }

    @Test
    void rendersOrderedBatchWithTheOldOrFirstNonNullValue() throws Exception {
        String xml = mapper("""
                <insert id="upsertRecord">
                    INSERT INTO sample_record (sample_id, created_at, hit_count) VALUES
                    <foreach collection="rows" item="row" separator=",">
                        (#{row.id}, #{row.createdAt,jdbcType=TIMESTAMP}, #{row.hits})
                    </foreach>
                    ON DUPLICATE KEY UPDATE
                        created_at = IFNULL(created_at, VALUES(created_at)), hit_count = VALUES(hit_count)
                </insert>
                """);
        MapperMigrationResult result = migrate(xml,
                new SqlRewriteConfig(Map.of(), Map.of(METHOD, List.of("sample_id"))));
        assertThat(result.manualReviewItems()).isEmpty();
        var row = Map.of("id", 1, "hits", 2, "createdAt", java.sql.Timestamp.valueOf("2000-01-01 10:00:00"));
        BoundSql sql = boundSql(Files.readString(output()), Map.of("rows", List.of(row, row)));
        assertThat(sql.getSql().trim()).startsWith("BEGIN").endsWith("END;")
                .containsIgnoringCase("IFNULL(t.created_at, s.created_at)")
                .doesNotContainIgnoringCase("ON DUPLICATE KEY UPDATE");
        assertThat(Pattern.compile("(?i)MERGE INTO").matcher(sql.getSql()).results().count()).isEqualTo(2);
        assertThat(sql.getParameterMappings()).hasSize(6);
    }

    private String mapper(String statement) {
        return """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.mapper.SampleRecordMapper">
                %s
                </mapper>
                """.formatted(statement);
    }

    @Test
    void extractsUpsertCandidateColumnsFromStaticIncludes() throws Exception {
        String original = ReportedSqlScriptCompatibilityTest.fixture("SampleRecordMapper.xml");
        // MyBatis itself resolves both fragments and binds all 19 values: this is valid input.
        assertThat(boundSql(original).getParameterMappings()).hasSize(COLUMNS.size());
        MapperMigrationResult preview = migrate(original, SqlRewriteConfig.empty());

        List<RewriteConfigCandidate> candidates = new MigrateCommand().rewriteConfigCandidates(preview);

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.methodKey()).isEqualTo(METHOD);
            assertThat(candidate.tableName()).isEqualTo("sample_record");
            assertThat(candidate.insertColumns()).containsExactlyElementsOf(COLUMNS);
        });
    }

    @Test
    void convertsStaticIncludesWhenConflictKeyIsExplicitlyConfigured() throws Exception {
        String original = ReportedSqlScriptCompatibilityTest.fixture("SampleRecordMapper.xml");
        // Isolate include conversion from business-key ambiguity; do not infer an arbitrary key.
        MapperMigrationResult result = migrate(original,
                new SqlRewriteConfig(Map.of(), Map.of(METHOD, List.of("id"))));

        assertThat(result.manualReviewItems()).extracting(change -> change.reason()).isEmpty();
        String sql = boundSql(Files.readString(output())).getSql().replaceAll("\\s+", " ");
        assertThat(sql).containsIgnoringCase("MERGE INTO sample_record")
                .containsIgnoringCase("WHEN MATCHED THEN UPDATE")
                .containsIgnoringCase("WHEN NOT MATCHED THEN INSERT")
                .doesNotContainIgnoringCase("ON DUPLICATE KEY UPDATE");
        assertThat(Pattern.compile("(?i)\\bid\\s*=").matcher(sql).find()).isTrue();
        assertThat(sql).contains("title", "content", "authorName", "expiresAt", "updatedAt");
    }

    @Test
    void equivalentInlineColumnsAndValuesAreAlreadySupportedWithTheSameKey() throws Exception {
        String original = ReportedSqlScriptCompatibilityTest.fixture("SampleRecordMapper.xml");
        String inline = original;
        for (String id : List.of("insertColumns", "insertValues")) {
            var fragment = Pattern.compile("(?s)<sql id=\"" + id + "\">(.*?)</sql>").matcher(original);
            assertThat(fragment.find()).isTrue();
            inline = inline.replace("<include refid=\"" + id + "\"/>", fragment.group(1));
        }
        assertThat(boundSql(original).getSql().replaceAll("\\s+", " ").trim())
                .isEqualTo(boundSql(inline).getSql().replaceAll("\\s+", " ").trim());

        MapperMigrationResult result = migrate(inline,
                new SqlRewriteConfig(Map.of(), Map.of(METHOD, List.of("id"))));

        assertThat(result.manualReviewItems()).extracting(change -> change.reason()).isEmpty();
        assertThat(boundSql(Files.readString(output())).getSql())
                .containsIgnoringCase("MERGE INTO sample_record")
                .doesNotContainIgnoringCase("ON DUPLICATE KEY UPDATE");
        assertThat(new MigrateCommand().rewriteConfigCandidates(result)).singleElement()
                .satisfies(candidate -> assertThat(candidate.insertColumns()).containsExactlyElementsOf(COLUMNS));
    }

    @Test
    void resolvesNestedLocalQualifiedIncludesWithoutChangingBindings() throws Exception {
        String original = ReportedSqlScriptCompatibilityTest.fixture("SampleRecordMapper.xml")
                .replace("<sql id=\"insertColumns\">", "<sql id=\"insertColumns\"><include refid=\"com.example.mapper.SampleRecordMapper.actualColumns\"/></sql><sql id=\"actualColumns\">");
        assertThat(boundSql(original).getParameterMappings()).hasSize(COLUMNS.size());
        MapperMigrationResult result = migrate(original,
                new SqlRewriteConfig(Map.of(), Map.of(METHOD, List.of("id"))));
        assertThat(result.manualReviewItems()).isEmpty();
        BoundSql converted = boundSql(Files.readString(output()));
        assertThat(converted.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactlyElementsOf(COLUMNS);
        assertThat(converted.getSql()).containsIgnoringCase("MERGE INTO");
    }

    @Test
    void leavesUnresolvableOrParameterizedIncludesForManualReview() throws Exception {
        String original = ReportedSqlScriptCompatibilityTest.fixture("SampleRecordMapper.xml");
        for (String xml : List.of(
                original.replace("refid=\"insertColumns\"", "refid=\"other.Mapper.columns\""),
                original.replace("<sql id=\"insertColumns\">", "<sql id=\"insertColumns\"><include refid=\"insertColumns\"/>"),
                original.replace("<include refid=\"insertColumns\"/>", "<include refid=\"insertColumns\"><property name=\"alias\" value=\"x\"/></include>"),
                original.replace("<sql id=\"insertColumns\">", "<sql id=\"insertColumns\" databaseId=\"mysql\">"))) {
            MapperMigrationResult result = migrate(xml,
                    new SqlRewriteConfig(Map.of(), Map.of(METHOD, List.of("id"))));
            assertThat(result.manualReviewItems()).isNotEmpty();
            assertThat(Files.readString(output())).doesNotContain("MERGE INTO");
        }
    }

    private MapperMigrationResult migrate(String xml, SqlRewriteConfig config) throws Exception {
        Path source = root.resolve("src/main/resources/mapper/SampleRecordMapper.xml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, xml);
        var scan = new ProjectScanResult(true, true, true, true, root.resolve("pom.xml").toString(),
                new MapperXmlScanner().scan(root), List.of());
        var result = new MapperMigrator().migrate(scan, AdapterContext.builder(root).dryRun(false).build(),
                new MySqlToDmSqlConverter(), config);
        assertThat(Files.readString(source)).isEqualTo(xml);
        return result;
    }

    private BoundSql boundSql(String xml) {
        return boundSql(xml, Map.of());
    }

    private BoundSql boundSql(String xml, Map<String, ?> args) {
        Configuration configuration = new Configuration();
        new XMLMapperBuilder(new StringReader(xml), configuration, "SampleRecordMapper.xml",
                configuration.getSqlFragments()).parse();
        return configuration.getMappedStatement(METHOD).getBoundSql(args);
    }

    private Path output() {
        return root.resolve("src/main/resources/mapper-dm/SampleRecordMapper.xml");
    }
}
