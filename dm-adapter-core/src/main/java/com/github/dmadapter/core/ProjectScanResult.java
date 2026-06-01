package com.github.dmadapter.core;

import java.util.List;

public record ProjectScanResult(
        boolean mavenProject,
        boolean springBootProject,
        boolean myBatisProject,
        boolean hasDmJdbcDriver,
        String pomPath,
        List<MapperXmlFile> mapperXmlFiles,
        List<String> warnings
) {
    public ProjectScanResult {
        mapperXmlFiles = List.copyOf(mapperXmlFiles == null ? List.of() : mapperXmlFiles);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
