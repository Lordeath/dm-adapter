package com.github.dmadapter.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SummaryStage(
        String name,
        StageStatus status,
        boolean requested,
        boolean attempted,
        String startedAt,
        String completedAt,
        long durationMillis,
        Map<String, Long> counts,
        String message,
        String report
) {
    public SummaryStage {
        name = name == null ? "" : name;
        status = status == null ? StageStatus.NOT_REQUESTED : status;
        startedAt = startedAt == null ? "" : startedAt;
        completedAt = completedAt == null ? "" : completedAt;
        counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts == null ? Map.of() : counts));
        message = message == null ? "" : message;
        report = report == null ? "" : report;
    }
}
