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

    @Test
    void detectsSpringBootAndMyBatisFromDependencyTreeWhenPomDoesNotDeclareThemDirectly() throws Exception {
        Path pom = writePomWithCompanyStarterOnly();
        DependencyTreeInspector dependencyTreeInspector = (projectRoot, dmDriverCoordinate) ->
                new DependencyTreeParser().parse("""
                        [INFO] com.example:demo:jar:0.0.1-SNAPSHOT
                        [INFO] +- com.example:company-data-starter:jar:1.0.0:compile
                        [INFO] |  +- org.springframework.boot:spring-boot-starter:jar:3.3.2:compile
                        [INFO] |  \\- org.mybatis.spring.boot:mybatis-spring-boot-starter:jar:3.0.3:compile
                        [INFO] \\- com.dameng:DmJdbcDriver18:jar:8.1.3.140:compile
                        """, dmDriverCoordinate);

        PomAnalysis analysis = new PomAnalyzer(dependencyTreeInspector).analyze(pom, DependencyCoordinate.defaultDmDriver());

        assertThat(analysis.mavenProject()).isTrue();
        assertThat(analysis.springBootProject()).isTrue();
        assertThat(analysis.myBatisProject()).isTrue();
        assertThat(analysis.hasDmJdbcDriver()).isTrue();
    }

    @Test
    void detectsDependenciesDeclaredByReactorModules() throws Exception {
        Path childDir = Files.createDirectories(tempDir.resolve("application"));
        Path rootPom = tempDir.resolve("pom.xml");
        Files.writeString(rootPom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo-parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>application</module>
                    </modules>
                </project>
                """);
        Files.writeString(childDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>demo-parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>application</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                            <version>3.3.2</version>
                        </dependency>
                        <dependency>
                            <groupId>org.mybatis</groupId>
                            <artifactId>mybatis</artifactId>
                            <version>3.5.19</version>
                        </dependency>
                        <dependency>
                            <groupId>com.dameng</groupId>
                            <artifactId>DmJdbcDriver18</artifactId>
                            <version>8.1.3.140</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        DependencyTreeInspector dependencyTreeInspector = (projectRoot, dmDriverCoordinate) ->
                DependencyTreeAnalysis.empty();

        PomAnalysis analysis = new PomAnalyzer(dependencyTreeInspector)
                .analyze(rootPom, DependencyCoordinate.defaultDmDriver());

        assertThat(analysis.springBootProject()).isTrue();
        assertThat(analysis.myBatisProject()).isTrue();
        assertThat(analysis.hasDmJdbcDriver()).isTrue();
    }

    @Test
    void ignoresMissingOutOfProjectAndCyclicModules() throws Exception {
        Path projectDir = Files.createDirectories(tempDir.resolve("project"));
        Path childDir = Files.createDirectories(projectDir.resolve("child"));
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Path rootPom = projectDir.resolve("pom.xml");
        Files.writeString(rootPom, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module> child </module>
                        <module>missing</module>
                        <module>../outside</module>
                    </modules>
                </project>
                """);
        Files.writeString(childDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>child</artifactId>
                    <version>1.0.0</version>
                    <modules><module>..</module></modules>
                </project>
                """);
        Files.writeString(outsideDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>outside</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.mybatis</groupId>
                            <artifactId>mybatis</artifactId>
                            <version>3.5.19</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        DependencyTreeInspector dependencyTreeInspector = (projectRoot, dmDriverCoordinate) ->
                DependencyTreeAnalysis.empty();

        PomAnalysis analysis = new PomAnalyzer(dependencyTreeInspector)
                .analyze(rootPom, DependencyCoordinate.defaultDmDriver());

        assertThat(analysis.springBootProject()).isFalse();
        assertThat(analysis.myBatisProject()).isFalse();
        assertThat(analysis.hasDmJdbcDriver()).isFalse();
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

    private Path writePomWithCompanyStarterOnly() throws Exception {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>company-data-starter</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        return pom;
    }
}
