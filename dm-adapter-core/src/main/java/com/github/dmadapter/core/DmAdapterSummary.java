package com.github.dmadapter.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DmAdapterSummary(
        int schemaVersion,
        String generatedAt,
        String projectRoot,
        boolean dryRun,
        String executionMode,
        OverallStatus overallStatus,
        Map<String, SummaryStage> stages,
        Map<String, Long> manualReview,
        List<SummaryIssue> topIssues,
        Map<String, String> reports,
        List<String> nextActions
) {
    public DmAdapterSummary {
        generatedAt = generatedAt == null ? "" : generatedAt;
        projectRoot = projectRoot == null ? "" : projectRoot;
        executionMode = executionMode == null ? "" : executionMode;
        overallStatus = overallStatus == null ? OverallStatus.RUNNING : overallStatus;
        stages = Collections.unmodifiableMap(new LinkedHashMap<>(stages == null ? Map.of() : stages));
        manualReview = Collections.unmodifiableMap(new LinkedHashMap<>(manualReview == null ? Map.of() : manualReview));
        topIssues = List.copyOf(topIssues == null ? List.of() : topIssues);
        reports = Collections.unmodifiableMap(new LinkedHashMap<>(reports == null ? Map.of() : reports));
        nextActions = List.copyOf(nextActions == null ? List.of() : nextActions);
    }
}
