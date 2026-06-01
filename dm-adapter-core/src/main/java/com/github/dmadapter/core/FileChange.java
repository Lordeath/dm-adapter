package com.github.dmadapter.core;

public record FileChange(
        String path,
        String changeType,
        String description,
        boolean applied
) {
    public static FileChange planned(String path, String changeType, String description) {
        return new FileChange(path, changeType, description, false);
    }

    public static FileChange applied(String path, String changeType, String description) {
        return new FileChange(path, changeType, description, true);
    }
}
