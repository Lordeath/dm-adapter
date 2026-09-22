package com.github.dmadapter.mybatis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicSqlStatementSplitterTest {
    @Test
    void preservesEveryCharacterAndKeepsXmlNodesWithTheirStatements() {
        String first = """
                <!-- ; --> INSERT INTO sample(id) SELECT id FROM source
                WHERE id &gt; #{id}<if test="name != ';'">AND name = #{name}</if>;
                """;
        String second = """
                -- ;
                UPDATE sample SET label = 'a;''b', `semi;colon` = &apos;c;d&apos;
                """;
        // The newline following the separator belongs to the next range.
        var split = DynamicSqlStatementSplitter.split(first + second);
        assertThat(split).hasSize(2);
        assertThat(split.get(0)).isEqualTo(first.stripTrailing());
        assertThat(split.get(1)).isEqualTo("\n" + second);
        assertThat(String.join("", split)).isEqualTo(first + second);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "'a;b'", "'a;''b'", "'a;\\'b'", "\"a;b\"", "`a;b`", "`a;``b`",
            "&apos;a;b&apos;", "&#39;a;b&#39;", "&quot;a;b&quot;", "'a&#59;b'",
            "'a&amp;b'", "(SELECT 'a;b')", "#{value,jdbcType=VARCHAR}"
    })
    void ignoresSeparatorsInsideSqlTokens(String expression) {
        String sql = "SELECT " + expression + " FROM sample; DELETE FROM sample;";
        assertThat(DynamicSqlStatementSplitter.split(sql))
                .containsExactly("SELECT " + expression + " FROM sample;", " DELETE FROM sample;");
    }

    @Test
    void preservesTrailingCommentsAndNonSemicolonForeach() {
        String first = "DELETE FROM sample <where>id IN <foreach collection=\"ids\" item=\"id\" "
                + "open=\"(\" close=\")\" separator=\",\">#{id}</foreach></where>;";
        String second = " /* ; */ SELECT 1; -- trailing ;\n<!-- trailing ; -->";
        assertThat(DynamicSqlStatementSplitter.split(first + second)).containsExactly(first, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "BEGIN UPDATE sample SET id=1; UPDATE sample SET id=2; END;",
            "DECLARE n INT; BEGIN UPDATE sample SET id=1; END;",
            "UPDATE sample SET id=1; CALL change_sample();",
            "<if test=\"enabled\">UPDATE sample SET id=1;</if> SELECT 1;",
            "UPDATE sample SET id=1<if test=\"enabled\">; SELECT 2</if>; SELECT 1;",
            "UPDATE sample SET id=1; <foreach collection=\"ids\" item=\"id\" separator=\";\">DELETE FROM sample</foreach>",
            "UPDATE sample <trim prefix=\"SET\" suffix=\";&#59;\">id=1</trim>; SELECT 1;",
            "UPDATE sample SET id=1; <include refid=\"unknown\"/>",
            "UPDATE ${table} SET id=1; SELECT 1;",
            "UPDATE sample SET id=1; SELECT <![CDATA['a;b']]>;",
            "UPDATE sample SET id=1&#59; SELECT 1;",
            "UPDATE sample SET id=1; SELECT 'unterminated;",
            "UPDATE sample SET id=1; SELECT (1;",
            "UPDATE sample SET id=1; SELECT 1);",
            "UPDATE sample SET id=1; /* unterminated;",
            "UPDATE sample SET id=1; SELECT 1<if test=\"enabled\">",
            "UPDATE sample SET id=1; SELECT 1<if test=\"enabled\"></where>",
            "UPDATE sample SET id=1;",
            "-- no SQL;"
    })
    void leavesUnknownBoundariesAndNonDmlOnTheOriginalPath(String body) {
        assertThat(DynamicSqlStatementSplitter.split(body)).isEmpty();
    }
}
