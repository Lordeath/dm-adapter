package com.github.dmadapter.cli;

import com.github.dmadapter.core.DmAdapterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdapterWorkspaceResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void usesResolvedApplicationArtifactIdUnderWorkingDirectory() throws Exception {
        Path project = tempDir.resolve("business-project");
        Path module = project.resolve("service-module");
        writePom(project.resolve("pom.xml"), "business-parent");
        writePom(module.resolve("pom.xml"), "sample-hr-rest");

        AdapterWorkspaceResolver resolver = new AdapterWorkspaceResolver(
                new ApplicationModuleSelector(),
                () -> tempDir.resolve("dm-adapter")
        );

        assertThat(resolver.resolve(project, Path.of("service-module"), null))
                .isEqualTo(tempDir.resolve("dm-adapter/.dm-adapter/sample-hr-rest").toAbsolutePath().normalize());
    }

    @Test
    void discoversUniqueSpringBootApplicationArtifactId() throws Exception {
        Path project = tempDir.resolve("business-project");
        Path module = project.resolve("rest-module");
        writePom(project.resolve("pom.xml"), "business-parent");
        writePom(module.resolve("pom.xml"), "sample-hr-rest");
        Path application = module.resolve("src/main/java/com/example/Application.java");
        Files.createDirectories(application.getParent());
        Files.writeString(application, """
                package com.example;
                @SpringBootApplication
                public class Application {
                }
                """);

        AdapterWorkspaceResolver resolver = new AdapterWorkspaceResolver(
                new ApplicationModuleSelector(),
                () -> tempDir.resolve("dm-adapter")
        );

        assertThat(resolver.projectKey(project, null)).isEqualTo("sample-hr-rest");
    }

    @Test
    void fallsBackToRootArtifactIdWhenApplicationModuleIsNotUnique() throws Exception {
        Path project = tempDir.resolve("business-project");
        writePom(project.resolve("pom.xml"), "business-parent");

        AdapterWorkspaceResolver resolver = new AdapterWorkspaceResolver(
                new ApplicationModuleSelector(),
                () -> tempDir.resolve("dm-adapter")
        );

        assertThat(resolver.projectKey(project, null)).isEqualTo("business-parent");
    }

    @Test
    void fallsBackToProjectDirectoryNameWhenPomArtifactIdIsUnavailable() throws Exception {
        Path project = tempDir.resolve("business-project");
        Files.createDirectories(project);

        AdapterWorkspaceResolver resolver = new AdapterWorkspaceResolver(
                new ApplicationModuleSelector(),
                () -> tempDir.resolve("dm-adapter")
        );

        assertThat(resolver.resolve(project, null, null))
                .isEqualTo(tempDir.resolve("dm-adapter/.dm-adapter/business-project")
                        .toAbsolutePath().normalize());
    }

    @Test
    void treatsConfiguredReportDirAsFinalWorkspaceDirectory() throws Exception {
        Path project = tempDir.resolve("business-project");
        writePom(project.resolve("pom.xml"), "business-parent");
        Path configured = tempDir.resolve("custom-output");

        AdapterWorkspaceResolver resolver = new AdapterWorkspaceResolver(
                new ApplicationModuleSelector(),
                () -> tempDir.resolve("dm-adapter")
        );

        assertThat(resolver.resolve(project, null, configured))
                .isEqualTo(configured.toAbsolutePath().normalize());
    }

    @Test
    void rejectsUnsafeExplicitApplicationArtifactId() throws Exception {
        Path project = tempDir.resolve("business-project");
        Path module = project.resolve("rest-module");
        writePom(project.resolve("pom.xml"), "business-parent");
        writePom(module.resolve("pom.xml"), "../outside");
        AdapterWorkspaceResolver resolver = new AdapterWorkspaceResolver(
                new ApplicationModuleSelector(),
                () -> tempDir.resolve("dm-adapter")
        );

        assertThatThrownBy(() -> resolver.resolve(project, Path.of("rest-module"), null))
                .isInstanceOf(DmAdapterException.class)
                .hasMessageContaining("cannot be used as a workspace directory name");
    }

    private void writePom(Path path, String artifactId) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0</version>
                </project>
                """.formatted(artifactId));
    }
}
