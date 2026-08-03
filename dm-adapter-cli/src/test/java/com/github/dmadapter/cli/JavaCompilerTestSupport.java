package com.github.dmadapter.cli;

import javax.tools.JavaCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class JavaCompilerTestSupport {
    private JavaCompilerTestSupport() {
    }

    static String runtimeClasspath() {
        String surefireClasspath = System.getProperty("surefire.test.class.path");
        if (surefireClasspath != null && !surefireClasspath.isBlank()) {
            return surefireClasspath;
        }
        return System.getProperty("java.class.path", "");
    }

    static int compileJava8(
            JavaCompiler compiler,
            List<Path> sourceFiles,
            Path outputDirectory,
            String classpath
    ) throws IOException {
        Files.createDirectories(outputDirectory);
        List<String> arguments = new ArrayList<>();
        arguments.add("--release");
        arguments.add("8");
        arguments.add("-encoding");
        arguments.add("UTF-8");
        arguments.add("-classpath");
        arguments.add(classpath);
        arguments.add("-d");
        arguments.add(outputDirectory.toString());
        sourceFiles.forEach(source -> arguments.add(source.toString()));
        return compiler.run(null, null, null, arguments.toArray(new String[0]));
    }
}
