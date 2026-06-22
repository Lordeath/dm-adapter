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

    private static class RecordingProcess extends Process {
        private final byte[] bytes;
        private final int exitCode;
        private boolean alive = true;

        private RecordingProcess(String output, int exitCode) {
            this.bytes = output.getBytes(StandardCharsets.UTF_8);
            this.exitCode = exitCode;
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
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
