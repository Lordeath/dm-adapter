package com.github.dmadapter.core;

public enum TargetLengthSemantics {
    CHAR,
    BYTE;

    public static TargetLengthSemantics fromLengthInChar(String value) {
        if ("1".equals(value)) {
            return CHAR;
        }
        if ("0".equals(value)) {
            return BYTE;
        }
        throw new IllegalArgumentException("Unsupported Dameng LENGTH_IN_CHAR value: " + value);
    }
}
