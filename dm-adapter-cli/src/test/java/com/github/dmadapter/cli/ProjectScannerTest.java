package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.DependencyCoordinate;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.maven.PomAnalysis;
import com.github.dmadapter.maven.PomAnalyzer;
import com.github.dmadapter.mybatis.MapperXmlScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void treatsDiscoveredMapperXmlAsMyBatisXmlUsageEvidence() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Path mapperDir = Files.createDirectories(tempDir.resolve("src/main/resources/mapper"));
        Files.writeString(mapperDir.resolve("UserMapper.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="findAll" resultType="map">select 1</select>
                </mapper>
                """);
        PomAnalyzer pomAnalyzer = new PomAnalyzer() {
            @Override
            public PomAnalysis analyze(Path pomPath, DependencyCoordinate dmDriverCoordinate) {
                return new PomAnalysis(true, false, false, false);
            }
        };
        ProjectScanner scanner = new ProjectScanner(pomAnalyzer, new MapperXmlScanner());

        ProjectScanResult result = scanner.scan(AdapterContext.builder(tempDir).build());

        assertThat(result.mapperXmlFiles()).hasSize(1);
        assertThat(result.myBatisProject()).isTrue();
        assertThat(result.warnings()).noneMatch(warning -> warning.contains("MyBatis dependency"));
    }
}
