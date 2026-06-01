package com.github.dmadapter.maven;

record DependencyTreeAnalysis(
        boolean springBootProject,
        boolean myBatisProject,
        boolean hasDmJdbcDriver
) {
    static DependencyTreeAnalysis empty() {
        return new DependencyTreeAnalysis(false, false, false);
    }
}
