package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlToDmSqlConverterTest {
    private final MySqlToDmSqlConverter converter = new MySqlToDmSqlConverter();

    @Test
    void convertsIfnullAndNow() {
        SqlConversionResult result = converter.convert("select IFNULL(name, 'n/a'), NOW() from user");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select NVL(name, 'n/a'), SYSDATE from user");
        assertThat(result.appliedRules()).containsExactly("IFNULL_TO_NVL", "NOW_TO_SYSDATE");
    }

    @Test
    void convertsDoubleQuotedStringLiterals() {
        SqlConversionResult result = converter.convert(
                "select * from user where status = \"ACTIVE\" and remark = \"Bob's \\\"note\\\"\""
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select * from user where status = 'ACTIVE' and remark = 'Bob''s \"note\"'");
        assertThat(result.appliedRules()).containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void convertsDoubleQuotedStringLiteralsBeforeOtherSafeRules() {
        SqlConversionResult result = converter.convert("select IFNULL(status, \"UNKNOWN\") from user");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select NVL(status, 'UNKNOWN') from user");
        assertThat(result.appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING", "IFNULL_TO_NVL");
    }

    @Test
    void doesNotConvertDoubleQuotesInsideSingleQuotedStringsOrComments() {
        SqlConversionResult result = converter.convert("""
                select '"ACTIVE"' as raw, "ACTIVE" as status -- "comment"
                from user /* "block" */
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select '"ACTIVE"' as raw, 'ACTIVE' as status -- "comment"
                from user /* "block" */
                """);
        assertThat(result.appliedRules()).containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void convertsDoubleQuotedStringLiteralsAfterMyBatisPlaceholders() {
        SqlConversionResult result = converter.convert("select * from user where id = #{id} and status = \"ACTIVE\"");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select * from user where id = #{id} and status = 'ACTIVE'");
        assertThat(result.appliedRules()).containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void convertsSimpleLimitWithOffset() {
        SqlConversionResult result = converter.convert("select * from user order by id limit 10, 20");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select * from user order by id OFFSET 10 ROWS FETCH NEXT 20 ROWS ONLY");
        assertThat(result.appliedRules()).containsExactly("LIMIT_OFFSET_TO_DM_FETCH");
    }

    @Test
    void convertsLimitWithMyBatisPlaceholders() {
        SqlConversionResult result = converter.convert("select * from user limit #{offset}, #{size}");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select * from user OFFSET #{offset} ROWS FETCH NEXT #{size} ROWS ONLY");
    }

    @Test
    void marksDateFormatForManualReview() {
        SqlConversionResult result = converter.convert("select DATE_FORMAT(created_at, '%Y-%m-%d') from user");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
        assertThat(result.reason()).contains("DATE_FORMAT");
    }

    @Test
    void marksBacktickIdentifiersForManualReview() {
        SqlConversionResult result = converter.convert("select `order`, `status` from `user`");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
        assertThat(result.reason()).contains("Backtick", "double-quoted identifiers", "case sensitivity");
    }

    @Test
    void marksMySqlSpecificFunctionsForManualReview() {
        List<String> functionNames = List.of(
                "DATE_ADD",
                "DATE_SUB",
                "STR_TO_DATE",
                "UNIX_TIMESTAMP",
                "FROM_UNIXTIME",
                "TIMESTAMPDIFF",
                "CONCAT_WS",
                "JSON_EXTRACT",
                "JSON_UNQUOTE",
                "JSON_SET"
        );

        for (String functionName : functionNames) {
            SqlConversionResult result = converter.convert("select " + functionName + "(created_at) from user");

            assertThat(result.manualReviewRequired())
                    .as(functionName + " should require manual review")
                    .isTrue();
            assertThat(result.changed()).isFalse();
            assertThat(result.convertedSql()).isEqualTo(result.originalSql());
            assertThat(result.reason()).contains(functionName);
        }
    }

    @Test
    void marksOnDuplicateKeyUpdateForManualReview() {
        SqlConversionResult result = converter.convert("""
                insert into user(id, name) values(1, 'a')
                on duplicate key update name = values(name)
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void marksAmbiguousLimitForManualReview() {
        SqlConversionResult result = converter.convert("select * from user limit ?, ?");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("LIMIT");
    }
}
