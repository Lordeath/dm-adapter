package com.github.dmadapter.maven;

import com.github.dmadapter.core.DependencyCoordinate;
import com.github.dmadapter.core.FileChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PomAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void detectsSpringBootMyBatisAndMissingDmDriver() throws Exception {
        Path pom = writePomWithoutDmDriver();

        PomAnalysis analysis = new PomAnalyzer().analyze(pom, DependencyCoordinate.defaultDmDriver());

        assertThat(analysis.mavenProject()).isTrue();
        assertThat(analysis.springBootProject()).isTrue();
        assertThat(analysis.myBatisProject()).isTrue();
        assertThat(analysis.hasDmJdbcDriver()).isFalse();
    }

    @Test
    void addsDmDriverDependencyOnlyOnce() throws Exception {
        Path pom = writePomWithoutDmDriver();
        PomModifier modifier = new PomModifier();

        Optional<FileChange> firstChange = modifier.ensureDependency(pom, DependencyCoordinate.defaultDmDriver(), false);
        Optional<FileChange> secondChange = modifier.ensureDependency(pom, DependencyCoordinate.defaultDmDriver(), false);

        assertThat(firstChange).isPresent();
        assertThat(secondChange).isEmpty();
        PomAnalysis analysis = new PomAnalyzer().analyze(pom, DependencyCoordinate.defaultDmDriver());
        assertThat(analysis.hasDmJdbcDriver()).isTrue();
    }

    @Test
    void dryRunDoesNotModifyPom() throws Exception {
        Path pom = writePomWithoutDmDriver();
        String before = Files.readString(pom);

        Optional<FileChange> change = new PomModifier().ensureDependency(pom, DependencyCoordinate.defaultDmDriver(), true);

        assertThat(change).isPresent();
        assertThat(change.get().applied()).isFalse();
        assertThat(Files.readString(pom)).isEqualTo(before);
    }

    private Path writePomWithoutDmDriver() throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.2</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.mybatis.spring.boot</groupId>
                            <artifactId>mybatis-spring-boot-starter</artifactId>
                            <version>3.0.3</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        return pom;
    }
}
