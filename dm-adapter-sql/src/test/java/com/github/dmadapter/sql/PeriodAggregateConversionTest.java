package com.github.dmadapter.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodAggregateConversionTest {
    private final MySqlToDmSqlConverter converter = new MySqlToDmSqlConverter();

    @Test
    void convertsMinMaxPeriodsWithoutChangingNullOrShortYearNormalization() {
        var result = converter.convert("SELECT IFNULL(PERIOD_DIFF(MAX(period_code), MIN(period_code)), 0) FROM sample_detail");

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).contains("IFNULL(", "CAST(MAX(period_code) AS DECIMAL(38, 0))",
                        "CAST(MIN(period_code) AS DECIMAL(38, 0))", "THEN 2000", "THEN 1900")
                .doesNotContain("PERIOD_DIFF", "COALESCE(MAX", "COALESCE(MIN");
        assertThat(converter.convert(result.convertedSql()).convertedSql()).isEqualTo(result.convertedSql());
    }

    @Test
    void neverRepeatsVolatileOrUnknownFunctionsInsidePeriodAggregateConditions() {
        for (String function : new String[]{"RAND()", "sample_function()", "sample_schema.CURDATE()",
                "\"sample_function\"()", "`sample_function`()", "sample_function /* sample comment */ ()",
                "sample_schema. /* sample comment */ CURDATE()"}) {
            String sql = "SELECT PERIOD_DIFF(MAX(CASE WHEN " + function
                    + " > 0 THEN period_code END), MIN(period_code)) FROM sample_detail";
            var result = converter.convert(sql);
            assertThat(result.manualReviewRequired()).as(function).isTrue();
            // Existing quote normalization may still run; the complete PERIOD_DIFF
            // expression must remain intact instead of repeating the function.
            assertThat(result.convertedSql()).as(function).isEqualTo(sql
                    .replace("\"sample_function\"", "'sample_function'"));
        }
    }
}
