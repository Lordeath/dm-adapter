package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;
import org.junit.jupiter.api.Test;

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
