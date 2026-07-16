package com.github.dmadapter.core;

public record SummaryIssue(
        String severity,
        String category,
        String pattern,
        long count,
        long rootCount,
        long blockedCount,
        String action
) {
    public SummaryIssue {
        severity = severity == null ? "INFO" : severity;
        category = category == null ? "" : category;
        pattern = pattern == null ? "" : pattern;
        action = action == null ? "" : action;
    }
}
