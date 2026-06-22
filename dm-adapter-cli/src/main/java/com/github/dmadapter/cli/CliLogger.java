package com.github.dmadapter.cli;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class CliLogger {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CliLogger() {
    }

    static void info(String message) {
        System.out.println(timestamped(message));
    }

    static void error(String message) {
        System.err.println(timestamped(message));
    }

    private static String timestamped(String message) {
        return "[" + LocalDateTime.now().format(TIMESTAMP_FORMATTER) + "] " + message;
    }
}
