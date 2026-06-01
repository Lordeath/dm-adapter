package com.github.dmadapter.maven;

public record PomAnalysis(
        boolean mavenProject,
        boolean springBootProject,
        boolean myBatisProject,
        boolean hasDmJdbcDriver
) {
}
