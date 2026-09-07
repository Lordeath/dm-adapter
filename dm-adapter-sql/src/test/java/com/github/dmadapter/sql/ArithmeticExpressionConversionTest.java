package com.github.dmadapter.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArithmeticExpressionConversionTest {
    private final MySqlToDmSqlConverter converter = new MySqlToDmSqlConverter();

    @Test
    void convertsDynamicAggregateNumeratorAndDenominatorAndIsIdempotent() {
        String numerator = "SUM(CASE WHEN active = 1 <if test=\"paidOnly\">AND paid = 1</if> "
                + "THEN amount ELSE 0 END)";
        String denominator = "SUM(CASE WHEN active = 1 <if test=\"paidOnly\">AND paid = 1</if> "
                + "THEN total ELSE 0 END)";
        String sql = "SELECT " + numerator + " / " + denominator + " AS ratio FROM sample_payment";

        var result = converter.convertArithmeticExpressions(sql);

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("SELECT CAST(" + numerator
                + " AS DECIMAL(38,10)) / NULLIF(CAST(" + denominator
                + " AS DECIMAL(38,10)), 0) AS ratio FROM sample_payment");
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE
        );
        var repeated = converter.convertArithmeticExpressions(result.convertedSql());
        assertThat(repeated.changed()).isFalse();
        assertThat(repeated.manualReviewRequired()).isFalse();
        assertThat(repeated.convertedSql()).isEqualTo(result.convertedSql());
    }

    @Test
    void convertsNestedDynamicDivisionsBeforeClearingArithmeticRisk() {
        String sum = "SUM(CASE WHEN active = 1 <if test=\"enabled\">AND paid = 1</if> "
                + "THEN amount ELSE 0 END)";
        var result = converter.convertArithmeticExpressions("SELECT (" + sum + " / 2) / 10000 FROM sample_payment");

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).contains("CAST((CAST(" + sum + " AS DECIMAL(38,10))")
                .contains("/ NULLIF(CAST(2 AS DECIMAL(38,10)), 0)")
                .contains("/ NULLIF(CAST(10000 AS DECIMAL(38,10)), 0)");
        assertThat(converter.convertArithmeticExpressions(result.convertedSql()).changed()).isFalse();
    }

    @Test
    void leavesUnprovenDynamicOperandStructureForManualReview() {
        List<String> operands = List.of(
                "SUM(<if test=\"enabled\">(</if>amount))",
                "SUM(<include refid=\"amountExpression\"/>)",
                "SUM(<foreach collection=\"values\" item=\"value\" separator=\"+\">#{value}</foreach>)",
                "SUM(CASE WHEN active = 1 <if test=\"enabled\">AND paid = 1</if> "
                        + "THEN ${amountExpression} ELSE 0 END)",
                "SUM((SELECT amount FROM sample_payment <if test=\"enabled\">WHERE paid = 1</if>))"
        );

        for (String operand : operands) {
            String sql = "SELECT " + operand + " / 10000 FROM sample_payment";
            var result = converter.convertArithmeticExpressions(sql);

            assertThat(result.manualReviewRequired()).as(sql).isTrue();
            assertThat(result.changed()).as(sql).isFalse();
            assertThat(result.convertedSql()).isEqualTo(sql);
            assertThat(result.reason()).isEqualTo(MySqlToDmSqlConverter.INTEGER_ARITHMETIC_MANUAL_REVIEW_REASON);
        }
    }

    @Test
    void ignoresParenthesesAndOperatorsInQuotedTextCommentsAndXmlAttributes() {
        String operand = "SUM(CASE WHEN note = ')' "
                + "<!-- ) / 9 --> <if test=\"marker == ')'\">AND paid = 1</if> "
                + "/* ) / 3 */ THEN amount ELSE 0 END)";
        var result = converter.convertArithmeticExpressions("SELECT " + operand + " / 10000 FROM sample_payment");

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("SELECT CAST(" + operand
                + " AS DECIMAL(38,10)) / NULLIF(CAST(10000 AS DECIMAL(38,10)), 0) FROM sample_payment");
    }
}
