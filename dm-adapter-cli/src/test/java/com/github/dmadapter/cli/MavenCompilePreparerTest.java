package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenCompilePreparerTest {
    @TempDir
    Path tempDir;

    @Test
    void compilesSelectedReactorModuleBeforeClassAnnotationScan() throws Exception {
        writePom(tempDir.resolve("pom.xml"), "root", "<modules><module>app</module></modules>");
        writePom(tempDir.resolve("app/pom.xml"), "newsee-bill-rest", "");
        List<List<String>> commands = new ArrayList<>();
        List<Path> workingDirectories = new ArrayList<>();
        MavenCompilePreparer preparer = preparer(commands, workingDirectories, processWithOutput("ok\n", 0), "Linux");

        List<String> warnings = preparer.prepare(tempDir, Path.of("newsee-bill-rest"));

        assertThat(warnings).isEmpty();
        assertThat(workingDirectories).containsExactly(tempDir.toAbsolutePath().normalize());
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0))
                .contains("mvn", "-pl", "app", "-am", "-DskipTests", "-Dmaven.test.skip=true", "compile");
    }

    @Test
    void compilesStandaloneModuleFromModuleDirectory() throws Exception {
        writePom(tempDir.resolve("pom.xml"), "root", "");
        writePom(tempDir.resolve("app/pom.xml"), "newsee-bill-rest", "");
        List<List<String>> commands = new ArrayList<>();
        List<Path> workingDirectories = new ArrayList<>();
        MavenCompilePreparer preparer = preparer(commands, workingDirectories, processWithOutput("ok\n", 0), "Linux");

        List<String> warnings = preparer.prepare(tempDir, Path.of("newsee-bill-rest"));

        assertThat(warnings).isEmpty();
        assertThat(workingDirectories).containsExactly(tempDir.resolve("app").toAbsolutePath().normalize());
        assertThat(commands.get(0))
                .contains("mvn", "-DskipTests", "-Dmaven.test.skip=true", "compile")
                .doesNotContain("-pl", "-am");
    }

    @Test
    void reportsCompileFailureAsWarning() throws Exception {
        writePom(tempDir.resolve("pom.xml"), "root", "");
        List<List<String>> commands = new ArrayList<>();
        List<Path> workingDirectories = new ArrayList<>();
        MavenCompilePreparer preparer = preparer(commands, workingDirectories, processWithOutput("compile failed\n", 1), "Linux");

        List<String> warnings = preparer.prepare(tempDir, null);

        assertThat(commands).hasSize(1);
        assertThat(warnings)
                .anySatisfy(warning -> assertThat(warning).contains("exited with code 1"))
                .anySatisfy(warning -> assertThat(warning).contains("compile failed"));
    }

    @Test
    void usesWindowsMavenCommandName() throws Exception {
        writePom(tempDir.resolve("pom.xml"), "root", "");
        List<List<String>> commands = new ArrayList<>();
        MavenCompilePreparer preparer = preparer(commands, new ArrayList<>(), processWithOutput("ok\n", 0), "Windows 11");

        preparer.prepare(tempDir, null);

        assertThat(commands.get(0).get(0)).isEqualTo("mvn.cmd");
    }

    private MavenCompilePreparer preparer(
            List<List<String>> commands,
            List<Path> workingDirectories,
            Process process,
            String osName
    ) {
        return new MavenCompilePreparer(
                new ApplicationModuleSelector(),
                processBuilder -> {
                    commands.add(List.copyOf(processBuilder.command()));
                    workingDirectories.add(processBuilder.directory().toPath().toAbsolutePath().normalize());
                    return process;
                },
                line -> {
                },
                osName,
                Duration.ofSeconds(5)
        );
    }

    private void writePom(Path path, String artifactId, String extraBody) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    %s
                </project>
                """.formatted(artifactId, extraBody), StandardCharsets.UTF_8);
    }

    private Process processWithOutput(String output, int exitCode) {
        return new Process() {
            private final ByteArrayInputStream inputStream = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));

            @Override
            public OutputStream getOutputStream() {
                return OutputStream.nullOutputStream();
            }

            @Override
            public InputStream getInputStream() {
                return inputStream;
            }

            @Override
            public InputStream getErrorStream() {
                return InputStream.nullInputStream();
            }

            @Override
            public int waitFor() {
                return exitCode;
            }

            @Override
            public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
                return true;
            }

            @Override
            public int exitValue() {
                return exitCode;
            }

            @Override
            public void destroy() {
            }

            @Override
            public Process destroyForcibly() {
                return this;
            }

            @Override
            public boolean isAlive() {
                return false;
            }
        };
    }
}
