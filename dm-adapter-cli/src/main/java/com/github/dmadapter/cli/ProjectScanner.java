package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.maven.PomAnalysis;
import com.github.dmadapter.maven.PomAnalyzer;
import com.github.dmadapter.mybatis.MapperXmlScanner;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

class ProjectScanner {
    private final PomAnalyzer pomAnalyzer;
    private final MapperXmlScanner mapperXmlScanner;

    ProjectScanner() {
        this(new PomAnalyzer(line -> CliLogger.info("[mvn] " + line)), new MapperXmlScanner());
    }

    ProjectScanner(PomAnalyzer pomAnalyzer, MapperXmlScanner mapperXmlScanner) {
        this.pomAnalyzer = pomAnalyzer;
        this.mapperXmlScanner = mapperXmlScanner;
    }

    ProjectScanResult scan(AdapterContext context) {
        List<String> warnings = new ArrayList<>();
        if (!Files.isDirectory(context.projectRoot())) {
            warnings.add("Project path is not a directory: " + context.projectRoot());
            return new ProjectScanResult(false, false, false, false, context.pomPath().toString(), List.of(), warnings);
        }

        PomAnalysis pomAnalysis = pomAnalyzer.analyze(context.pomPath(), context.dmDriverCoordinate());
        List<MapperXmlFile> mapperXmlFiles = mapperXmlScanner.scan(context.projectRoot());
        boolean myBatisXmlProject = !mapperXmlFiles.isEmpty();

        if (!pomAnalysis.mavenProject()) {
            warnings.add("pom.xml was not found at project root.");
        }
        if (pomAnalysis.mavenProject() && !pomAnalysis.springBootProject()) {
            warnings.add("Spring Boot dependency or parent was not detected.");
        }
        if (pomAnalysis.mavenProject() && !pomAnalysis.myBatisProject() && mapperXmlFiles.isEmpty()) {
            warnings.add("MyBatis dependency was not detected in pom.xml.");
        }
        if (mapperXmlFiles.isEmpty()) {
            warnings.add("No MyBatis mapper XML files were detected under src/main/resources.");
        }
        if (pomAnalysis.mavenProject() && !pomAnalysis.hasDmJdbcDriver()) {
            warnings.add("Dameng JDBC driver dependency was not detected.");
        }

        return new ProjectScanResult(
                pomAnalysis.mavenProject(),
                pomAnalysis.springBootProject(),
                myBatisXmlProject,
                pomAnalysis.hasDmJdbcDriver(),
                context.pomPath().toString(),
                mapperXmlFiles,
                warnings
        );
    }
}
