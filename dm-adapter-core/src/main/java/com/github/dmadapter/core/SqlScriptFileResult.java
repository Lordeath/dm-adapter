package com.github.dmadapter.core;

import java.util.List;

public record SqlScriptFileResult(
        String sourceFile,
        String outputFile,
        String schema,
        boolean systemScript,
        boolean written,
        boolean converted,
        int statementCount,
        int convertedStatementCount,
        int manualReviewStatementCount,
        int validationSuccessCount,
        int validationFailureCount,
        List<String> appliedRules
) {
    public SqlScriptFileResult {
        appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
    }
}
