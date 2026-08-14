package com.github.dmadapter.maven;

import com.github.dmadapter.core.DependencyCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MavenDependencyTreeInspectorTest {
    @TempDir
    Path tempDir;

    @Test
    void streamsDependencyTreeOutputAndParsesResult() {
        List<String> streamedLines = new ArrayList<>();
        MavenDependencyTreeInspector inspector = new MavenDependencyTreeInspector(
                processBuilder -> new RecordingProcess("""
                        [INFO] com.example:demo:jar:0.0.1-SNAPSHOT
                        [INFO] +- org.springframework.boot:spring-boot-starter:jar:3.3.2:compile
                        [INFO] +- org.mybatis.spring.boot:mybatis-spring-boot-starter:jar:3.0.3:compile
                        [INFO] \\- com.dameng:DmJdbcDriver18:jar:8.1.3.140:compile
                        """, 0),
                streamedLines::add
        );

        DependencyTreeAnalysis analysis = inspector.analyze(tempDir, DependencyCoordinate.defaultDmDriver());

        assertThat(analysis.springBootProject()).isTrue();
        assertThat(analysis.myBatisProject()).isTrue();
        assertThat(analysis.hasDmJdbcDriver()).isTrue();
        assertThat(streamedLines)
                .anySatisfy(line -> assertThat(line).contains("Running Maven dependency tree:"))
                .anySatisfy(line -> assertThat(line).contains("mybatis-spring-boot-starter"));
    }

    @Test
    void usesMvnCmdOnWindows() {
        String originalOsName = System.getProperty("os.name");
        List<List<String>> commands = new ArrayList<>();
        try {
            System.setProperty("os.name", "Windows 11");
            MavenDependencyTreeInspector inspector = new MavenDependencyTreeInspector(
                    processBuilder -> {
                        commands.add(processBuilder.command());
                        return new RecordingProcess("", 1);
                    },
                    line -> {
                    }
            );

            inspector.analyze(tempDir, DependencyCoordinate.defaultDmDriver());

            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).get(0)).isEqualTo("mvn.cmd");
        } finally {
            if (originalOsName == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOsName);
            }
        }
    }

    @Test
    void killsDependencyTreeProcessWhenTimeout() {
        List<String> streamedLines = new ArrayList<>();
        RecordingProcess process = new RecordingProcess("""
                [INFO] com.example:demo:jar:0.0.1-SNAPSHOT
                [INFO] +- org.springframework.boot:spring-boot-starter:jar:3.3.2:compile
                [INFO] +- org.mybatis.spring.boot:mybatis-spring-boot-starter:jar:3.0.3:compile
                [INFO] \\- com.dameng:DmJdbcDriver18:jar:8.1.3.140:compile
                """, 0, false);
        MavenDependencyTreeInspector inspector = new MavenDependencyTreeInspector(
                processBuilder -> process,
                streamedLines::add
        );

        DependencyTreeAnalysis analysis = inspector.analyze(tempDir, DependencyCoordinate.defaultDmDriver());

        assertThat(analysis.springBootProject()).isTrue();
        assertThat(analysis.myBatisProject()).isTrue();
        assertThat(analysis.hasDmJdbcDriver()).isTrue();
        assertThat(process.destroyForciblyCount).isGreaterThan(0);
        assertThat(streamedLines)
                .anySatisfy(line -> assertThat(line).contains("Maven dependency tree timed out"));
    }

    @Test
    void retainsDependenciesFromOutputWhenMavenExitsWithFailure() {
        List<String> streamedLines = new ArrayList<>();
        MavenDependencyTreeInspector inspector = new MavenDependencyTreeInspector(
                processBuilder -> new RecordingProcess("""
                        [INFO] com.example:demo:jar:0.0.1-SNAPSHOT
                        [INFO] +- org.springframework.boot:spring-boot-starter:jar:3.3.2:compile
                        [INFO] +- org.mybatis:mybatis:jar:3.5.19:compile
                        [INFO] \\- com.dameng:DmJdbcDriver18:jar:8.1.3.140:compile
                        [ERROR] A later reactor module could not be resolved.
                        """, 1),
                streamedLines::add
        );

        DependencyTreeAnalysis analysis = inspector.analyze(tempDir, DependencyCoordinate.defaultDmDriver());

        assertThat(analysis.springBootProject()).isTrue();
        assertThat(analysis.myBatisProject()).isTrue();
        assertThat(analysis.hasDmJdbcDriver()).isTrue();
        assertThat(streamedLines)
                .anySatisfy(line -> assertThat(line).contains("exited with code 1"));
    }

    private static class RecordingProcess extends Process {
        private final byte[] bytes;
        private final int exitCode;
        private final boolean completeOnTimedWait;
        private boolean alive = true;
        private int destroyForciblyCount;

        private RecordingProcess(String output, int exitCode) {
            this(output, exitCode, true);
        }

        private RecordingProcess(String output, int exitCode, boolean completeOnTimedWait) {
            this.bytes = output.getBytes(StandardCharsets.UTF_8);
            this.exitCode = exitCode;
            this.completeOnTimedWait = completeOnTimedWait;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            alive = false;
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            if (!completeOnTimedWait) {
                return false;
            }
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            destroyForciblyCount++;
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }
    }
}
