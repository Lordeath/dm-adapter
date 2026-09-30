package com.github.dmadapter.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GreatestUpsertTest {
    private final MySqlToDmSqlConverter converter = new MySqlToDmSqlConverter();

    @Test
    void mergesOldAndIncomingValuesInBothOrders() {
        for (String expression : List.of("GREATEST(flag, VALUES(flag))", "GREATEST(VALUES(flag), modes.flag)")) {
            var result = converter.convert("INSERT INTO modes(ver, flag) VALUES(1, 0) "
                    + "ON DUPLICATE KEY UPDATE flag = " + expression, List.of("ver"));
            assertThat(result.manualReviewRequired()).isFalse();
            assertThat(result.convertedSql()).contains("ON (t.ver = s.ver)",
                    expression.startsWith("GREATEST(flag")
                            ? "t.flag = GREATEST(t.flag, s.flag)" : "t.flag = GREATEST(s.flag, t.flag)",
                    "WHEN NOT MATCHED THEN INSERT (ver, flag) VALUES (s.ver, s.flag)");
        }
    }

    @Test
    void supportsSelectSourcesAndQuotedColumns() {
        var result = converter.convert("INSERT INTO modes(ver, `flag`) SELECT ver, flag FROM incoming "
                + "ON DUPLICATE KEY UPDATE `flag` = GREATEST(`flag`, VALUES(`flag`))", List.of("ver"));
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).contains("GREATEST(t.flag, s.flag)");
    }

    @Test
    void rejectsCrossColumnsExpressionsAndMissingIncomingColumn() {
        for (String expression : List.of("GREATEST(other, VALUES(flag))", "GREATEST(flag, VALUES(other))",
                "GREATEST(flag, VALUES(flag), 1)", "GREATEST(flag + 1, VALUES(flag))")) {
            var result = converter.convert("INSERT INTO modes(ver, flag) VALUES(1, 0) "
                    + "ON DUPLICATE KEY UPDATE flag = " + expression, List.of("ver"));
            assertThat(result.manualReviewRequired()).as(expression).isTrue();
        }
        var result = converter.convert("INSERT INTO modes(ver) VALUES(1) "
                + "ON DUPLICATE KEY UPDATE flag = GREATEST(flag, VALUES(flag))", List.of("ver"));
        assertThat(result.manualReviewRequired()).isTrue();
    }
}
