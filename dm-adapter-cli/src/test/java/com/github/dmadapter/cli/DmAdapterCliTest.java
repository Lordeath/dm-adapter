package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DmAdapterCliTest {
    @TempDir
    Path tempDir;

    @Test
    void migrateDryRunWritesReportWithoutChangingProjectFiles() throws Exception {
        writeDemoProject();
        String pomBefore = Files.readString(tempDir.resolve("pom.xml"));

        int exitCode = new CommandLine(new DmAdapterCli()).execute("migrate", "--project", tempDir.toString(), "--dry-run");

        assertThat(exitCode).isZero();
        assertThat(Files.readString(tempDir.resolve("pom.xml"))).isEqualTo(pomBefore);
        assertThat(Files.exists(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"))).isFalse();
        assertThat(Files.exists(tempDir.resolve(".dm-adapter/dm-adapter-report.json"))).isTrue();
        assertThat(Files.readString(tempDir.resolve(".dm-adapter/dm-adapter-report.md")))
                .contains("Automatic SQL Conversions")
                .contains("Manual Review SQL Items");

        int reportExitCode = new CommandLine(new DmAdapterCli()).execute("report", "--project", tempDir.toString());

        assertThat(reportExitCode).isZero();
    }

    @Test
    void scanWritesScanReport() throws Exception {
        writeDemoProject();

        int exitCode = new CommandLine(new DmAdapterCli()).execute("scan", "--project", tempDir.toString());

        assertThat(exitCode).isZero();
        assertThat(Files.exists(tempDir.resolve(".dm-adapter/dm-adapter-scan-report.json"))).isTrue();
    }

    private void writeDemoProject() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
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
        Path mapper = tempDir.resolve("src/main/resources/mapper/UserMapper.xml");
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUsers">
                        select IFNULL(name, 'n/a') from user limit #{offset}, #{size}
                    </select>
                    <select id="selectByDate">
                        select DATE_FORMAT(created_at, '%Y-%m-%d') from user
                    </select>
                </mapper>
                """);
    }
}
