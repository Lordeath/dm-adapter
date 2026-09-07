package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapperDynamicArithmeticTest {
    @TempDir
    Path tempDir;

    @Test
    void rewritesAggregateDivisionAcrossChooseWithoutChangingSourceOrBranches() throws Exception {
        String aggregate = """
                SUM(CASE WHEN d.account_book >= CONCAT(#{year}, '01')
                    <choose>
                        <when test="isPaidDate == true">
                            AND d.create_time &lt; #{endDate} - INTERVAL 7 DAY + INTERVAL 1 DAY
                        </when>
                        <otherwise>
                            AND d.operator_date &lt; #{endDate} - INTERVAL 7 DAY + INTERVAL 1 DAY
                        </otherwise>
                    </choose>
                    THEN d.paid_amount - IFNULL(d.delay_amount, 0) ELSE 0 END)
                """.strip();
        String sql = "SELECT SUM(CASE WHEN d.active = 1 THEN (d.paid_amount - IFNULL(d.delay_amount, 0))/10000 "
                + "ELSE 0 END) AS annual_amount,\n"
                + aggregate + "/10000 AS week_amount,\n"
                + aggregate + " / 10000 AS prior_amount FROM sample_payment d";

        Migration migration = migrate(sql);

        assertThat(migration.result().manualReviewItems()).isEmpty();
        assertThat(migration.result().automaticConversions()).singleElement().satisfies(item ->
                assertThat(item.appliedRules()).contains(
                        MySqlToDmSqlConverter.MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE,
                        MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE
                ));
        assertThat(migration.output()).contains("CAST(SUM(CASE WHEN d.account_book")
                .doesNotContain("END)/10000", "END) / 10000");
        assertThat(migration.output().split("NULLIF\\(CAST\\(10000 AS DECIMAL\\(38,10\\)\\), 0\\)", -1))
                .hasSize(4);

        Configuration configuration = readMapper(migration.path());
        for (boolean paidDate : List.of(true, false)) {
            var boundSql = configuration.getMappedStatement("com.example.PaymentMapper.amounts")
                    .getBoundSql(Map.of("isPaidDate", paidDate, "year", 2026, "endDate", "2026-09-07"));
            assertThat(boundSql.getSql())
                    .contains(paidDate ? "d.create_time" : "d.operator_date")
                    .doesNotContain(paidDate ? "d.operator_date" : "d.create_time")
                    .contains("DATEADD(DAY, 1, DATEADD(DAY, -7, ?))")
                    .contains("ELSE 0 END) AS DECIMAL(38,10)) / NULLIF(CAST(10000 AS DECIMAL(38,10)), 0)");
            assertThat(boundSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                    .containsExactly("year", "endDate", "year", "endDate");
        }
    }

    @Test
    void preservesCommentsAndCdataInDynamicAggregateDivision() throws Exception {
        String sql = """
                SELECT SUM(CASE WHEN d.note != ')'
                    <!-- keep ) / 42 <if test="ignored"> -->
                    <if test="marker == ')'">
                        AND d.amount <![CDATA[ < ]]> #{limit}
                    </if>
                    /* keep unmatched ) and / 3 */
                    THEN d.amount ELSE 0 END) / 10000 AS amount
                FROM sample_payment d
                """;

        Migration migration = migrate(sql);

        assertThat(migration.result().manualReviewItems()).isEmpty();
        assertThat(migration.output()).contains("CAST(SUM(CASE WHEN d.note != ')'")
                .contains("<!-- keep ) / 42 <if test=\"ignored\"> -->")
                .contains("<if test=\"marker == ')'\">")
                .contains("<![CDATA[ < ]]>")
                .contains("/* keep unmatched ) and / 3 */")
                .contains("END) AS DECIMAL(38,10)) / NULLIF(CAST(10000 AS DECIMAL(38,10)), 0)");
        readMapper(migration.path());
    }

    @Test
    void keepsUnknownDynamicDenominatorForManualReview() throws Exception {
        Migration migration = migrate("""
                SELECT SUM(CASE WHEN active = 1
                    <if test="enabled">AND paid = 1</if>
                    THEN amount ELSE 0 END) / ${divisor} AS amount
                FROM sample_payment
                """);

        assertThat(migration.result().manualReviewItems()).anySatisfy(item ->
                assertThat(item.reason()).contains("整数算术表达式风险"));
        assertThat(migration.output()).contains("END) / ${divisor}");
    }

    @Test
    void retainsManualReviewForOtherUnsafeDivisionInSameStatement() throws Exception {
        Migration migration = migrate("""
                SELECT SUM(CASE WHEN active = 1
                    <if test="enabled">AND paid = 1</if>
                    THEN amount ELSE 0 END) / 10000 AS amount,
                    '10'/4 AS unsafe_amount
                FROM sample_payment
                """);

        assertThat(migration.result().manualReviewItems()).anySatisfy(item ->
                assertThat(item.reason()).contains("整数算术表达式风险"));
        assertThat(migration.output()).contains("'10'/4");
    }

    private Migration migrate(String sql) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.PaymentMapper">
                    <select id="amounts" resultType="map">
                %s
                    </select>
                </mapper>
                """.formatted(sql);
        Path source = tempDir.resolve("src/main/resources/mapper/PaymentMapper.xml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, xml);
        ProjectScanResult scan = new ProjectScanResult(true, true, true, false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(source.toString(), "mapper/PaymentMapper.xml")), List.of());
        MapperMigrationResult result = new MapperMigrator().migrate(scan,
                AdapterContext.builder(tempDir).dryRun(false).build(), new MySqlToDmSqlConverter());
        Path output = tempDir.resolve("src/main/resources/mapper-dm/PaymentMapper.xml");
        assertThat(Files.readString(source)).isEqualTo(xml);
        return new Migration(result, output, Files.readString(output));
    }

    private Configuration readMapper(Path mapper) throws Exception {
        Configuration configuration = new Configuration();
        try (var input = Files.newInputStream(mapper)) {
            new XMLMapperBuilder(input, configuration, mapper.toString(), configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private record Migration(MapperMigrationResult result, Path path, String output) {
    }
}
