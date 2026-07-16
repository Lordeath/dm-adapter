package com.github.dmadapter.cli;

import java.util.concurrent.TimeUnit;

final class ValidationDeadline {
    private final long timeoutNanos;
    private long startedAtNanos;

    ValidationDeadline(long timeoutSeconds) {
        this.timeoutNanos = TimeUnit.SECONDS.toNanos(Math.max(1L, timeoutSeconds));
    }

    synchronized long remainingSeconds() {
        if (startedAtNanos == 0L) {
            startedAtNanos = System.nanoTime();
        }
        long remaining = timeoutNanos - (System.nanoTime() - startedAtNanos);
        if (remaining <= 0L) {
            return 0L;
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(remaining - 1L) + 1L);
    }

    boolean expired() {
        return remainingSeconds() == 0L;
    }
}
