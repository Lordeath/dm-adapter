package com.github.dmadapter.core;

import java.util.List;

public record SqlConversionResult(
        String originalSql,
        String convertedSql,
        boolean changed,
        boolean manualReviewRequired,
        String reason,
        List<String> appliedRules
) {
    public SqlConversionResult {
        appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
    }

    public static SqlConversionResult unchanged(String sql) {
        return new SqlConversionResult(sql, sql, false, false, "", List.of());
    }

    public static SqlConversionResult changed(String originalSql, String convertedSql, List<String> appliedRules) {
        return new SqlConversionResult(originalSql, convertedSql, true, false, "", appliedRules);
    }

    public static SqlConversionResult manualReview(String sql, String reason) {
        return new SqlConversionResult(sql, sql, false, true, reason, List.of());
    }

    public static SqlConversionResult changedWithManualReview(
            String originalSql,
            String convertedSql,
            List<String> appliedRules,
            String reason
    ) {
        return new SqlConversionResult(originalSql, convertedSql, true, true, reason, appliedRules);
    }
}
