package com.github.dmadapter.cli;

final class GitBatchException extends RuntimeException {
    private final String stage;

    GitBatchException(String stage, String message) {
        super(message);
        this.stage = stage == null ? "git" : stage;
    }

    GitBatchException(String stage, String message, Throwable cause) {
        super(message, cause);
        this.stage = stage == null ? "git" : stage;
    }

    String stage() {
        return stage;
    }
}
