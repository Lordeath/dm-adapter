package com.github.dmadapter.core;

import java.util.Objects;

public record DependencyCoordinate(String groupId, String artifactId, String version) {
    private static final DependencyCoordinate DEFAULT_DM_DRIVER =
            new DependencyCoordinate("com.dameng", "DmJdbcDriver18", "8.1.3.140");

    public DependencyCoordinate {
        groupId = requirePart(groupId, "groupId");
        artifactId = requirePart(artifactId, "artifactId");
        version = requirePart(version, "version");
    }

    public static DependencyCoordinate defaultDmDriver() {
        return DEFAULT_DM_DRIVER;
    }

    public static DependencyCoordinate parse(String value) {
        if (value == null || value.isBlank()) {
            return defaultDmDriver();
        }
        String[] parts = value.trim().split(":");
        if (parts.length != 3) {
            throw new DmAdapterException("Dependency coordinate must be groupId:artifactId:version: " + value);
        }
        return new DependencyCoordinate(parts[0], parts[1], parts[2]);
    }

    public boolean matches(String groupId, String artifactId) {
        return Objects.equals(this.groupId, groupId) && Objects.equals(this.artifactId, artifactId);
    }

    public String toGav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    private static String requirePart(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DmAdapterException("Dependency " + name + " must not be blank");
        }
        return value.trim();
    }
}
