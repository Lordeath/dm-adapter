package com.github.dmadapter.core;

import java.util.List;

public record SqlScriptValidationReport(
        String validationPlan,
        boolean attempted,
        String status,
        int successCount,
        int failureCount,
        int manualReviewSkippedCount,
        List<SqlScriptValidationFailure> failures,
        List<String> warnings
) {
    public SqlScriptValidationReport {
        validationPlan = validationPlan == null ? "" : validationPlan;
        status = status == null ? "" : status;
        failures = List.copyOf(failures == null ? List.of() : failures);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
