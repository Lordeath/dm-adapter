package com.github.dmadapter.mybatis;

import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapperAggregateRegressionTest {
    @TempDir
    Path root;

    @Test
    void convertsConditionalPeriodAggregatesWithLocalInclude() throws Exception {
        Migration migration = migrate("""
                <sql id="eligiblePeriod">
                    d.deleted = 0 AND d.amount &gt; 0
                    AND d.period_code REGEXP '^[0-9]{4}(0[1-9]|1[0-2])$'
                </sql>
                <select id="query" resultType="map">
                    SELECT IFNULL(PERIOD_DIFF(
                        MAX(CASE WHEN <include refid="eligiblePeriod"/> THEN d.period_code END),
                        MIN(CASE WHEN <include refid="eligiblePeriod"/> THEN d.period_code END)
                    ), 0) AS period_span
                    FROM sample_detail d WHERE d.tenant_id = #{tenantId}
                </select>
                """);

        assertThat(migration.result().manualReviewItems()).isEmpty();
        assertThat(migration.sql()).doesNotContain("PERIOD_DIFF(").contains("MAX(CASE WHEN", "MIN(CASE WHEN");
        BoundSql bound = bound(migration.output(), Map.of("tenantId", 7));
        assertThat(bound.getSql()).contains("REGEXP_LIKE", "d.deleted = 0", "d.amount > 0", "AS period_span");
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("tenantId");
    }

    @Test
    void convertsGroupConcatAcrossIfWithoutLosingEitherBranch() throws Exception {
        Migration migration = migrate("""
                <select id="query" resultType="map">
                    SELECT GROUP_CONCAT(DISTINCT CASE WHEN amount
                        <if test="includeExtra">+ IFNULL(extra_amount, 0)</if>
                        - IFNULL(paid_amount, 0) != 0 THEN period_code END) AS periods
                    FROM sample_detail WHERE tenant_id = #{tenantId}
                </select>
                """);

        assertThat(migration.result().manualReviewItems()).isEmpty();
        assertThat(migration.sql()).contains("LISTAGG(DISTINCT CASE WHEN").doesNotContain("GROUP_CONCAT(");
        for (boolean includeExtra : List.of(false, true)) {
            BoundSql bound = bound(migration.output(), Map.of("includeExtra", includeExtra, "tenantId", 7));
            assertThat(bound.getSql()).contains("THEN period_code END", "WITHIN GROUP");
            assertThat(bound.getSql().contains("extra_amount")).isEqualTo(includeExtra);
            assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("tenantId");
        }
    }

    @Test
    void expandsMultipleAggregateAliasesInsideChooseHavingWithoutMovingThemToWhere() throws Exception {
        Migration migration = migrate("""
                <select id="query" resultType="map">
                    SELECT category_id,
                    <choose>
                        <when test="includeExtra">
                            SUM(amount + extra_amount - paid_amount) AS total_amount,
                            SUM(amount + extra_amount) AS net_amount,
                        </when>
                        <otherwise>
                            SUM(amount - paid_amount) + SUM(extra_amount) AS total_amount,
                            SUM(amount) AS net_amount,
                        </otherwise>
                    </choose>
                    COUNT(*) AS item_count
                    FROM sample_detail WHERE tenant_id = #{tenantId}
                    GROUP BY category_id
                    <choose>
                        <when test="useTotal">HAVING total_amount != 0</when>
                        <otherwise>HAVING net_amount != 0</otherwise>
                    </choose>
                    ORDER BY category_id
                </select>
                """);

        assertThat(migration.result().manualReviewItems()).isEmpty();
        for (boolean includeExtra : List.of(false, true)) {
            for (boolean useTotal : List.of(false, true)) {
                BoundSql bound = bound(migration.output(), Map.of(
                        "includeExtra", includeExtra, "useTotal", useTotal, "tenantId", 7));
                String sql = bound.getSql().replaceAll("\\s+", " ");
                String expected = useTotal
                        ? (includeExtra ? "SUM(amount + extra_amount - paid_amount)" : "SUM(amount - paid_amount) + SUM(extra_amount)")
                        : (includeExtra ? "SUM(amount + extra_amount)" : "SUM(amount)");
                assertThat(sql.substring(sql.indexOf("HAVING"))).contains(expected).contains("!= 0")
                        .doesNotContain("total_amount", "net_amount");
                assertThat(sql.substring(sql.indexOf("WHERE"), sql.indexOf("GROUP BY")))
                        .isEqualTo("WHERE tenant_id = ? ");
                assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("tenantId");
            }
        }
    }

    @Test
    void retainsUnknownPeriodExpressionAndDynamicAggregateFragmentsForReview() throws Exception {
        Migration migration = migrate("""
                <select id="query" resultType="map">
                    SELECT PERIOD_DIFF(sample_period(period_code), #{period}) AS span,
                        GROUP_CONCAT(CASE WHEN enabled = 1
                            <if test="includeExtra">${expression}</if>
                            THEN period_code END) AS periods
                    FROM sample_detail
                </select>
                """);

        assertThat(migration.result().manualReviewItems()).isNotEmpty();
        assertThat(migration.sql()).contains("PERIOD_DIFF(sample_period", "${expression}");
        read(migration.output());
    }

    @Test
    void keepsIncompleteProjectionChooseAndItsHavingBranchForReview() throws Exception {
        Migration migration = migrate("""
                <select id="query" resultType="map">
                    SELECT category_id,
                    <choose><when test="includeExtra">SUM(amount) AS total_amount,</when></choose>
                    COUNT(*) AS item_count FROM sample_detail GROUP BY category_id
                    <choose><when test="useTotal">HAVING total_amount != 0</when>
                        <otherwise>HAVING COUNT(*) > 0</otherwise></choose>
                </select>
                """);

        assertThat(migration.result().manualReviewItems()).isNotEmpty().allSatisfy(item ->
                assertThat(item.reason()).doesNotContain("malformed mapper XML"));
        assertThat(migration.sql()).contains("HAVING total_amount != 0");
    }

    @Test
    void preservesQuotedTextCommentsAndParameterBindingsInsideDynamicAggregation() throws Exception {
        Migration migration = migrate("""
                <select id="query" resultType="map">
                    SELECT GROUP_CONCAT(DISTINCT CASE WHEN label != ') GROUP_CONCAT('
                        <!-- keep ) and PERIOD_DIFF( text -->
                        <if test="includeExtra and marker == &quot;)&quot;">AND amount &gt; #{threshold}</if>
                        THEN label END) AS labels
                    FROM sample_detail WHERE tenant_id = #{tenantId}
                </select>
                """);

        assertThat(migration.result().manualReviewItems()).isEmpty();
        assertThat(migration.sql()).contains("<!-- keep ) and PERIOD_DIFF( text -->", "') GROUP_CONCAT('");
        BoundSql bound = bound(migration.output(), Map.of("includeExtra", true, "marker", ")", "threshold", 4, "tenantId", 7));
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("threshold", "threshold", "tenantId");
        assertThat(bound.getSql()).contains("AND amount > ?");
        BoundSql excluded = bound(migration.output(), Map.of("includeExtra", false, "marker", ")", "tenantId", 7));
        assertThat(excluded.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("tenantId");
    }

    @Test
    void keepsCrossMapperIncludesAndUnbalancedDynamicExpressionsForReview() throws Exception {
        String xml = """
                <mapper namespace="com.example.SampleMapper">
                    <select id="query" resultType="map">
                        SELECT PERIOD_DIFF(MAX(CASE WHEN <include refid="com.example.OtherMapper.condition"/>
                            THEN period_code END), MIN(period_code)),
                            GROUP_CONCAT(<if test="enabled">COALESCE(</if>label<if test="enabled">, 'empty')</if>)
                        FROM sample_detail
                    </select>
                </mapper>
                """;
        Path input = root.resolve("SampleMapper.xml");
        Files.writeString(input, xml);
        MapperRewriteResult result = new MapperXmlRewriter().rewrite(input, input.toString(), false,
                new MySqlToDmSqlConverter());
        assertThat(result.manualReviewItems()).isNotEmpty();
        assertThat(Files.readString(input)).isEqualTo(xml);
    }

    private Migration migrate(String content) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.SampleMapper">
                %s
                </mapper>
                """.formatted(content);
        Path source = root.resolve("mapper/SampleMapper.xml");
        Path output = root.resolve("mapper-dm/SampleMapper.xml");
        Files.createDirectories(source.getParent());
        Files.createDirectories(output.getParent());
        Files.writeString(source, xml);
        Files.writeString(output, xml);
        read(source);
        MapperRewriteResult result = new MapperXmlRewriter().rewrite(output, output.toString(), true,
                new MySqlToDmSqlConverter());
        assertThat(Files.readString(source)).isEqualTo(xml);
        read(output);
        return new Migration(result, output, Files.readString(output));
    }

    private Configuration read(Path path) throws Exception {
        Configuration configuration = new Configuration();
        try (var input = Files.newInputStream(path)) {
            new XMLMapperBuilder(input, configuration, path.toString(), configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private BoundSql bound(Path path, Map<String, Object> args) throws Exception {
        return read(path).getMappedStatement("com.example.SampleMapper.query").getBoundSql(args);
    }

    private record Migration(MapperRewriteResult result, Path output, String sql) {
    }
}
