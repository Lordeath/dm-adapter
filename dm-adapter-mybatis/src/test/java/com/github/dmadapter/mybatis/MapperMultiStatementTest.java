package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MapperMultiStatementTest {
    private static final String NAMESPACE = "com.newsee.system.dao.NsSystemOrganizationToWechatMapper";
    private static final String SAFE_UPDATE = """
            UPDATE sample_target t
            LEFT JOIN sample_source s ON t.source_id = s.id
            SET t.label = s.label
            WHERE 1=1 <if test="enterpriseId != null">AND t.enterprise_id = #{enterpriseId}</if>;
            """;
    private static final String UNSAFE_UPDATE = """
            UPDATE sample_target t
            LEFT JOIN sample_source s ON t.source_id = s.id
            SET t.label = s.label, s.previous_label = t.label
            WHERE 1=1 <if test="enterpriseId != null">AND t.enterprise_id = #{enterpriseId}</if>;
            """;

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realMethodMatchesSeparatelyMigratedStatementsAndPreservesBindings(boolean withEnterprise) throws Exception {
        String original;
        try (var input = getClass().getResourceAsStream("/mapper/GenerateToWechatMapper.xml")) {
            original = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Migration migration = migrate(original);
        assertThat(migration.result().manualReviewItems()).isEmpty();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("enterpriseId", withEnterprise ? 17 : null);
        BoundSql before = boundSql(original, parameters);
        BoundSql after = boundSql(migration.xml(), parameters);
        assertThat(after.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactlyElementsOf(before.getParameterMappings().stream()
                        .map(ParameterMapping::getProperty).toList())
                .hasSize(withEnterprise ? 3 : 0);

        String body = original.substring(original.indexOf("<update id=\"generateToWechat\">")
                + "<update id=\"generateToWechat\">".length(), original.indexOf("</update>"));
        // This fixture has exactly four literal SQL separators, outside strings and XML nodes.
        List<String> separatelyConverted = new ArrayList<>();
        for (String statement : body.split(";")) {
            if (statement.isBlank()) {
                continue;
            }
            Migration isolated = migrate(mapper(statement + ";"));
            assertThat(isolated.result().manualReviewItems()).isEmpty();
            separatelyConverted.add(normalize(boundSql(isolated.xml(), parameters).getSql()));
        }
        assertThat(separatelyConverted).hasSize(4);
        assertThat(normalize(after.getSql())).isEqualTo(String.join(" ", separatelyConverted));
        assertThat(migration.xml())
                .contains("<!--通过 organization_id / organization_parent_id 自关联，修正企微维度的 parentid -->")
                .contains("chat.parentid = IFNULL(pchat.dept_id, 1)")
                .contains("ROW_NUMBER() OVER (PARTITION BY `level`,`parentid` ORDER BY organization_ordercolumn desc)");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsUnsafeUpdateReviewRegardlessOfStatementOrder(boolean unsafeFirst) throws Exception {
        String body = unsafeFirst ? UNSAFE_UPDATE + SAFE_UPDATE : SAFE_UPDATE + UNSAFE_UPDATE;
        Migration migration = migrate(mapper(body));
        assertThat(migration.result().manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("UPDATE JOIN"));
        assertThat(migration.xml()).contains("SET t.label = s.label, s.previous_label = t.label");
    }

    @Test
    void safeUpdateDoesNotClearUnrelatedRisk() throws Exception {
        Migration migration = migrate(mapper(SAFE_UPDATE + """
                INSERT INTO sample_log(id, label) VALUES (#{id}, #{label})
                ON DUPLICATE KEY UPDATE label = VALUES(label);
                """));
        assertThat(migration.result().manualReviewItems()).singleElement().satisfies(item ->
                assertThat(item.reason()).contains("ON DUPLICATE KEY UPDATE").doesNotContain("UPDATE JOIN"));
    }

    @Test
    void convertedInnerJoinDoesNotClearAnotherStatementsUnsafeOuterJoin() throws Exception {
        String inner = """
                UPDATE sample_target t JOIN sample_source s ON t.source_id = s.id
                SET t.label = s.label
                <where><if test="enterpriseId != null">t.enterprise_id = #{enterpriseId}</if></where>;
                """;
        Migration migration = migrate(mapper(inner + UNSAFE_UPDATE));
        assertThat(migration.result().manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("UPDATE JOIN"));
        assertThat(migration.xml()).contains("SET t.label = s.label, s.previous_label = t.label");
        boundSql(migration.xml(), Map.of("enterpriseId", 17));
    }

    @ParameterizedTest
    @ValueSource(strings = {"label", "unknown.label"})
    void doesNotGuessTheUpdatedTableInAnotherStatement(String target) throws Exception {
        String uncertain = SAFE_UPDATE.replace("SET t.label", "SET " + target);
        Migration migration = migrate(mapper(SAFE_UPDATE + uncertain));
        assertThat(migration.result().manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.reason()).contains("UPDATE JOIN"));
        assertThat(migration.xml()).contains("SET " + target + " = s.label");
    }

    @Test
    void migratesStatementsWithLiteralCommentAndXmlEntitySemicolons() throws Exception {
        String statement = SAFE_UPDATE.replace("WHERE 1=1", "WHERE t.id &gt; 0 AND t.note = 'a;''b'")
                .replace("enterpriseId != null", "enterpriseId != null and ';' != ''");
        String original = mapper("<!-- leading ; -->" + statement + "/* separator ; */\n" + statement);
        Migration migration = migrate(original);
        assertThat(migration.result().manualReviewItems()).isEmpty();
        BoundSql before = boundSql(original, Map.of("enterpriseId", 17));
        BoundSql after = boundSql(migration.xml(), Map.of("enterpriseId", 17));
        assertThat(normalize(after.getSql())).isEqualTo(normalize(before.getSql()));
        assertThat(after.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("enterpriseId", "enterpriseId");
    }

    private Migration migrate(String xml) throws Exception {
        Path project = Files.createTempDirectory(tempDir, "project-");
        Path source = project.resolve("src/main/resources/mapper/WechatMapper.xml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, xml);
        ProjectScanResult scan = new ProjectScanResult(true, true, true, false,
                project.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(source.toString(), "mapper/WechatMapper.xml")), List.of());
        MapperMigrationResult result = new MapperMigrator().migrate(scan,
                AdapterContext.builder(project).dryRun(false).build(), new MySqlToDmSqlConverter());
        assertThat(Files.readString(source)).isEqualTo(xml);
        return new Migration(result, Files.readString(project.resolve("src/main/resources/mapper-dm/WechatMapper.xml")));
    }

    private String mapper(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s"><update id="generateToWechat">%s</update></mapper>
                """.formatted(NAMESPACE, body);
    }

    private BoundSql boundSql(String xml, Map<String, Object> parameters) {
        Configuration configuration = new Configuration();
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                configuration, "WechatMapper.xml", configuration.getSqlFragments()).parse();
        return configuration.getMappedStatement(NAMESPACE + ".generateToWechat").getBoundSql(parameters);
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private record Migration(MapperMigrationResult result, String xml) {
    }
}
