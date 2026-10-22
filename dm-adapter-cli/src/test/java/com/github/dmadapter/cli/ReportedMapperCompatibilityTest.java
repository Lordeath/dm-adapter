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
        Configuration configuration = new Configuration();
        new XMLMapperBuilder(new StringReader(xml), configuration, "SampleRecordMapper.xml",
                configuration.getSqlFragments()).parse();
        return configuration.getMappedStatement(METHOD).getBoundSql(Map.of());
    }

    private Path output() {
        return root.resolve("src/main/resources/mapper-dm/SampleRecordMapper.xml");
    }
}
