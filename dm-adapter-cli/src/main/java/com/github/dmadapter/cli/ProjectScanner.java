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
            warnings.add("项目路径不是目录：" + context.projectRoot());
            return new ProjectScanResult(false, false, false, false, context.pomPath().toString(), List.of(), warnings);
        }

        PomAnalysis pomAnalysis = pomAnalyzer.analyze(context.pomPath(), context.dmDriverCoordinate());
        List<MapperXmlFile> mapperXmlFiles = mapperXmlScanner.scan(context.projectRoot());
        boolean myBatisXmlProject = !mapperXmlFiles.isEmpty();

        if (!pomAnalysis.mavenProject()) {
            warnings.add("项目根目录下未找到 pom.xml。");
        }
        if (pomAnalysis.mavenProject() && !pomAnalysis.springBootProject()) {
            warnings.add("未检测到 Spring Boot 依赖或父 POM。");
        }
        if (pomAnalysis.mavenProject() && !pomAnalysis.myBatisProject() && mapperXmlFiles.isEmpty()) {
            warnings.add("pom.xml 中未检测到 MyBatis 依赖。");
        }
        if (mapperXmlFiles.isEmpty()) {
            warnings.add("src/main/resources 下未检测到 MyBatis Mapper XML。");
        }
        if (pomAnalysis.mavenProject() && !pomAnalysis.hasDmJdbcDriver()) {
            warnings.add("未检测到达梦 JDBC 驱动依赖。");
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
