package com.github.dmadapter.core;

public class DmAdapterException extends RuntimeException {
    public DmAdapterException(String message) {
        super(message);
    }

    public DmAdapterException(String message, Throwable cause) {
        super(message, cause);
    }
}
