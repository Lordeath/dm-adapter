package com.github.dmadapter.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DmValidationEnvironment {
    private static final String ENABLED = "DM_SQL_VALIDATION";
    private static final String JDBC_URL = "DM_JDBC_URL";
    private static final String USERNAME = "DM_DB_USERNAME";
    private static final String PASSWORD = "DM_DB_PASSWORD";
    private static final String TOTAL_TIMEOUT_SECONDS = "DM_SQL_VALIDATION_TOTAL_TIMEOUT_SECONDS";
    private static final long DEFAULT_TOTAL_TIMEOUT_SECONDS = 7_200L;

    private final boolean enabled;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final List<String> missingVariables;
    private final long totalTimeoutSeconds;
    private final ValidationDeadline deadline;

    private DmValidationEnvironment(
            boolean enabled,
            String jdbcUrl,
            String username,
            String password,
            List<String> missingVariables,
            long totalTimeoutSeconds
    ) {
        this.enabled = enabled;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.missingVariables = List.copyOf(missingVariables);
        this.totalTimeoutSeconds = totalTimeoutSeconds;
        this.deadline = new ValidationDeadline(totalTimeoutSeconds);
    }

    static DmValidationEnvironment fromSystem() {
        return from(System.getenv());
    }

    static DmValidationEnvironment from(Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        boolean enabled = "true".equalsIgnoreCase(value(env, ENABLED));
        List<String> missing = new ArrayList<>();
        String jdbcUrl = value(env, JDBC_URL);
        String username = value(env, USERNAME);
        String password = value(env, PASSWORD);
        if (enabled) {
            if (jdbcUrl.isBlank()) {
                missing.add(JDBC_URL);
            }
            if (username.isBlank()) {
                missing.add(USERNAME);
            }
            if (password.isBlank()) {
                missing.add(PASSWORD);
            }
        }
        long timeoutSeconds = positiveLong(value(env, TOTAL_TIMEOUT_SECONDS), DEFAULT_TOTAL_TIMEOUT_SECONDS);
        return new DmValidationEnvironment(enabled, jdbcUrl, username, password, missing, timeoutSeconds);
    }

    boolean validationEnabled() {
        return enabled;
    }

    boolean ready() {
        return enabled && missingVariables.isEmpty();
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }

    List<String> missingVariables() {
        return missingVariables;
    }

    long totalTimeoutSeconds() {
        return totalTimeoutSeconds;
    }

    ValidationDeadline deadline() {
        return deadline;
    }

    private static long positiveLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String value(Map<String, String> environment, String name) {
        String value = environment.get(name);
        return value == null ? "" : value.trim();
    }
}
