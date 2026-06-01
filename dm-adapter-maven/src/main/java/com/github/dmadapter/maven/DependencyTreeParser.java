package com.github.dmadapter.maven;

import com.github.dmadapter.core.DependencyCoordinate;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DependencyTreeParser {
    private static final Pattern COORDINATE_PATTERN = Pattern.compile("([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):");
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B\\[[;\\d]*m");

    DependencyTreeAnalysis parse(String output, DependencyCoordinate dmDriverCoordinate) {
        if (output == null || output.isBlank()) {
            return DependencyTreeAnalysis.empty();
        }

        boolean springBoot = false;
        boolean myBatis = false;
        boolean dmDriver = false;
        for (String line : output.split("\\R")) {
            Matcher matcher = COORDINATE_PATTERN.matcher(stripAnsi(line));
            while (matcher.find()) {
                String groupId = matcher.group(1);
                String artifactId = matcher.group(2);
                springBoot = springBoot || isSpringBootDependency(groupId, artifactId);
                myBatis = myBatis || isMyBatisDependency(groupId, artifactId);
                dmDriver = dmDriver || isDmDriverDependency(groupId, artifactId, dmDriverCoordinate);
            }
        }
        return new DependencyTreeAnalysis(springBoot, myBatis, dmDriver);
    }

    private boolean isSpringBootDependency(String groupId, String artifactId) {
        return "org.springframework.boot".equals(groupId)
                && artifactId != null
                && artifactId.startsWith("spring-boot");
    }

    private boolean isMyBatisDependency(String groupId, String artifactId) {
        String normalizedGroupId = value(groupId).toLowerCase(Locale.ROOT);
        String normalizedArtifactId = value(artifactId).toLowerCase(Locale.ROOT);
        return normalizedGroupId.startsWith("org.mybatis") || normalizedArtifactId.contains("mybatis");
    }

    private boolean isDmDriverDependency(String groupId, String artifactId, DependencyCoordinate configuredCoordinate) {
        if (configuredCoordinate.matches(groupId, artifactId)) {
            return true;
        }
        String normalizedGroupId = value(groupId).toLowerCase(Locale.ROOT);
        String normalizedArtifactId = value(artifactId).toLowerCase(Locale.ROOT);
        return normalizedGroupId.equals("com.dameng") && normalizedArtifactId.startsWith("dmjdbcdriver");
    }

    private String stripAnsi(String line) {
        return ANSI_PATTERN.matcher(line).replaceAll("");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
