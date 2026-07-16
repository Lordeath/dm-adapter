package com.github.dmadapter.cli;

import com.github.dmadapter.core.StageStatus;

import java.nio.file.Path;
import java.util.List;

record ValidationTestRunResult(
        boolean attempted,
        int exitCode,
        Path reportPath,
        List<String> outputTail,
        String message,
        StageStatus status
) {
    ValidationTestRunResult {
        outputTail = List.copyOf(outputTail == null ? List.of() : outputTail);
        message = message == null ? "" : message;
        status = status == null ? (exitCode == 0 ? StageStatus.PASSED : StageStatus.FAILED) : status;
    }

    ValidationTestRunResult(
            boolean attempted,
            int exitCode,
            Path reportPath,
            List<String> outputTail,
            String message
    ) {
        this(attempted, exitCode, reportPath, outputTail, message,
                attempted ? (exitCode == 0 ? StageStatus.PASSED : StageStatus.FAILED) : StageStatus.SKIPPED);
    }

    static ValidationTestRunResult skipped(String message) {
        return new ValidationTestRunResult(false, 0, null, List.of(), message, StageStatus.SKIPPED);
    }
}
