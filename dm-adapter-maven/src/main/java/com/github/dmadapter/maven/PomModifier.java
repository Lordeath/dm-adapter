package com.github.dmadapter.maven;

import com.github.dmadapter.core.DependencyCoordinate;
import com.github.dmadapter.core.DmAdapterException;
import com.github.dmadapter.core.FileChange;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class PomModifier {
    private final PomAnalyzer pomAnalyzer;

    public PomModifier() {
        this(new PomAnalyzer());
    }

    public PomModifier(PomAnalyzer pomAnalyzer) {
        this.pomAnalyzer = pomAnalyzer;
    }

    public Optional<FileChange> ensureDependency(Path pomPath, DependencyCoordinate coordinate, boolean dryRun) {
        if (!Files.isRegularFile(pomPath)) {
            throw new DmAdapterException("Cannot modify missing pom.xml: " + pomPath);
        }
        if (pomAnalyzer.containsDependency(pomPath, coordinate)) {
            return Optional.empty();
        }
        if (dryRun) {
            return Optional.of(FileChange.planned(
                    pomPath.toString(),
                    "MODIFY",
                    "Add dependency " + coordinate.toGav()
            ));
        }

        Model model = pomAnalyzer.readModel(pomPath);
        Dependency dependency = new Dependency();
        dependency.setGroupId(coordinate.groupId());
        dependency.setArtifactId(coordinate.artifactId());
        dependency.setVersion(coordinate.version());
        model.addDependency(dependency);
        writeModel(pomPath, model);

        return Optional.of(FileChange.applied(
                pomPath.toString(),
                "MODIFY",
                "Added dependency " + coordinate.toGav()
        ));
    }

    private void writeModel(Path pomPath, Model model) {
        try (Writer writer = Files.newBufferedWriter(pomPath, StandardCharsets.UTF_8)) {
            new MavenXpp3Writer().write(writer, model);
        } catch (IOException e) {
            throw new DmAdapterException("Failed to write pom.xml: " + pomPath, e);
        }
    }
}
