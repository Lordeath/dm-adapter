package com.github.dmadapter.cli;

import java.nio.file.Path;
import java.util.List;

record ValidationTestRunResult(
        boolean attempted,
        int exitCode,
        Path reportPath,
        List<String> outputTail,
        String message
) {
    ValidationTestRunResult {
        outputTail = List.copyOf(outputTail == null ? List.of() : outputTail);
        message = message == null ? "" : message;
    }

    static ValidationTestRunResult skipped(String message) {
        return new ValidationTestRunResult(false, 0, null, List.of(), message);
    }
}
