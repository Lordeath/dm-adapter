package com.github.dmadapter.maven;

import com.github.dmadapter.core.MapperXmlFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PomTargetSelectorTest {
    @TempDir
    Path tempDir;

    @Test
    void selectsSpringBootApplicationModuleBeforeMapperModuleAndRootPom() throws Exception {
        writePom("pom.xml");
        Path restPom = writePom("sample-system-rest/pom.xml");
        writePom("sample-system-base/pom.xml");
        writeSpringBootApplication("sample-system-rest/src/main/java/com/example/RestApplication.java");
        MapperXmlFile mapperXmlFile = writeMapperXmlFile("sample-system-base/src/main/resources/mapper/UserMapper.xml");

        PomTargetSelection selection = new PomTargetSelector().select(tempDir, List.of(mapperXmlFile));

        assertThat(selection.reason()).isEqualTo("Spring Boot application module");
        assertThat(selection.pomPaths()).containsExactly(restPom.toAbsolutePath().normalize());
    }

    @Test
    void fallsBackToMapperModuleWhenNoSpringBootApplicationClassExists() throws Exception {
        writePom("pom.xml");
        Path mapperPom = writePom("sample-system-base/pom.xml");
        MapperXmlFile mapperXmlFile = writeMapperXmlFile("sample-system-base/src/main/resources/mapper/UserMapper.xml");

        PomTargetSelection selection = new PomTargetSelector().select(tempDir, List.of(mapperXmlFile));

        assertThat(selection.reason()).isEqualTo("mapper XML module");
        assertThat(selection.pomPaths()).containsExactly(mapperPom.toAbsolutePath().normalize());
    }

    @Test
    void fallsBackToRootPomWhenNoModuleTargetExists() throws Exception {
        Path rootPom = writePom("pom.xml");

        PomTargetSelection selection = new PomTargetSelector().select(tempDir, List.of());

        assertThat(selection.reason()).isEqualTo("project root");
        assertThat(selection.pomPaths()).containsExactly(rootPom.toAbsolutePath().normalize());
        assertThat(selection.warnings()).hasSize(1);
    }

    private Path writePom(String relativePath) throws Exception {
        Path pom = tempDir.resolve(relativePath);
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                </project>
                """);
        return pom;
    }

    private void writeSpringBootApplication(String relativePath) throws Exception {
        Path javaFile = tempDir.resolve(relativePath);
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, """
                package com.example;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                class RestApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(RestApplication.class, args);
                    }
                }
                """);
    }

    private MapperXmlFile writeMapperXmlFile(String relativePath) throws Exception {
        Path mapper = tempDir.resolve(relativePath);
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUsers">select * from user</select>
                </mapper>
                """);
        Path resourcesRoot = tempDir.resolve("sample-system-base/src/main/resources");
        return new MapperXmlFile(
                mapper.toAbsolutePath().normalize().toString(),
                resourcesRoot.toAbsolutePath().normalize().toString(),
                "mapper/UserMapper.xml"
        );
    }
}
