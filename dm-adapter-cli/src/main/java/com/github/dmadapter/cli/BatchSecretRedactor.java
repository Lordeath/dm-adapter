package com.github.dmadapter.cli;

final class BatchSecretRedactor {
    private final String username;
    private final String password;

    BatchSecretRedactor(ResolvedBatchConfig.Credentials credentials) {
        this.username = credentials == null ? "" : credentials.username();
        this.password = credentials == null ? "" : credentials.password();
    }

    String redact(String value) {
        String redacted = value == null ? "" : value;
        redacted = redactValue(redacted, password);
        redacted = redactValue(redacted, username);
        redacted = redacted.replaceAll("(?i)(https?://)[^/@\\s]+@", "$1******@");
        return redacted.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
    }

    String message(Throwable failure) {
        if (failure == null) {
            return "Unknown failure.";
        }
        Throwable current = failure;
        String message = "";
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return redact(message.isBlank() ? failure.getClass().getSimpleName() : message);
    }

    private String redactValue(String message, String value) {
        return value == null || value.isBlank() ? message : message.replace(value, "******");
    }
}
