package com.github.dmadapter.core;

import java.util.List;

public record SqlChange(
        String file,
        String statementId,
        String originalSql,
        String convertedSql,
        List<String> appliedRules,
        boolean manualReviewRequired,
        String reason
) {
    public SqlChange {
        appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
    }
}
