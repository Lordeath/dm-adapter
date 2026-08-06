package com.github.dmadapter.cli;

final class BatchRepositoryFailure extends RuntimeException {
    private final int exitCode;
    private final String stage;

    BatchRepositoryFailure(int exitCode, String stage, String message) {
        super(message);
        this.exitCode = exitCode;
        this.stage = stage;
    }

    BatchRepositoryFailure(int exitCode, String stage, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
        this.stage = stage;
    }

    int exitCode() {
        return exitCode;
    }

    String stage() {
        return stage;
    }
}
