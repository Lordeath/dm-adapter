package com.github.dmadapter.core;

import java.nio.file.Path;
import java.util.Objects;

public record AdapterContext(
        Path projectRoot,
        String sourceDb,
        String targetDb,
        boolean dryRun,
        DependencyCoordinate dmDriverCoordinate,
        Path reportDir,
        Path mapperTargetDir
) {
    public static final String DEFAULT_SOURCE_DB = "mysql";
    public static final String DEFAULT_TARGET_DB = "dm";

    public AdapterContext {
        Objects.requireNonNull(projectRoot, "projectRoot");
        projectRoot = projectRoot.toAbsolutePath().normalize();
        sourceDb = normalizeDb(sourceDb, DEFAULT_SOURCE_DB);
        targetDb = normalizeDb(targetDb, DEFAULT_TARGET_DB);
        dmDriverCoordinate = dmDriverCoordinate == null
                ? DependencyCoordinate.defaultDmDriver()
                : dmDriverCoordinate;
        reportDir = reportDir == null
                ? projectRoot.resolve(".dm-adapter")
                : reportDir.toAbsolutePath().normalize();
        mapperTargetDir = mapperTargetDir == null
                ? projectRoot.resolve("src/main/resources/mapper-dm")
                : mapperTargetDir.toAbsolutePath().normalize();
    }

    public static AdapterContext of(Path projectRoot) {
        return builder(projectRoot).build();
    }

    public static Builder builder(Path projectRoot) {
        return new Builder(projectRoot);
    }

    public Path pomPath() {
        return projectRoot.resolve("pom.xml");
    }

    public Path mainResourcesRoot() {
        return projectRoot.resolve("src/main/resources");
    }

    private static String normalizeDb(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim().toLowerCase();
    }

    public static final class Builder {
        private final Path projectRoot;
        private String sourceDb = DEFAULT_SOURCE_DB;
        private String targetDb = DEFAULT_TARGET_DB;
        private boolean dryRun;
        private DependencyCoordinate dmDriverCoordinate = DependencyCoordinate.defaultDmDriver();
        private Path reportDir;
        private Path mapperTargetDir;

        private Builder(Path projectRoot) {
            this.projectRoot = projectRoot;
        }

        public Builder sourceDb(String sourceDb) {
            this.sourceDb = sourceDb;
            return this;
        }

        public Builder targetDb(String targetDb) {
            this.targetDb = targetDb;
            return this;
        }

        public Builder dryRun(boolean dryRun) {
            this.dryRun = dryRun;
            return this;
        }

        public Builder dmDriverCoordinate(DependencyCoordinate dmDriverCoordinate) {
            this.dmDriverCoordinate = dmDriverCoordinate;
            return this;
        }

        public Builder reportDir(Path reportDir) {
            this.reportDir = reportDir;
            return this;
        }

        public Builder mapperTargetDir(Path mapperTargetDir) {
            this.mapperTargetDir = mapperTargetDir;
            return this;
        }

        public AdapterContext build() {
            return new AdapterContext(
                    projectRoot,
                    sourceDb,
                    targetDb,
                    dryRun,
                    dmDriverCoordinate,
                    reportDir,
                    mapperTargetDir
            );
        }
    }
}
