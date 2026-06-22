package com.github.dmadapter.maven;

import com.github.dmadapter.core.DependencyCoordinate;
import com.github.dmadapter.core.DmAdapterException;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class PomAnalyzer {
    private final DependencyTreeInspector dependencyTreeInspector;

    public PomAnalyzer() {
        this(new MavenDependencyTreeInspector());
    }

    public PomAnalyzer(Consumer<String> mavenOutputConsumer) {
        this(new MavenDependencyTreeInspector(mavenOutputConsumer));
    }

    PomAnalyzer(DependencyTreeInspector dependencyTreeInspector) {
        this.dependencyTreeInspector = dependencyTreeInspector;
    }

    public PomAnalysis analyze(Path pomPath, DependencyCoordinate dmDriverCoordinate) {
        if (!Files.isRegularFile(pomPath)) {
            return new PomAnalysis(false, false, false, false);
        }
        Model model = readModel(pomPath);
        List<Dependency> dependencies = model.getDependencies();
        boolean springBoot = hasSpringBootParent(model.getParent()) || dependencies.stream().anyMatch(this::isSpringBootDependency);
        boolean myBatis = dependencies.stream().anyMatch(this::isMyBatisDependency);
        boolean dmDriver = dependencies.stream().anyMatch(dependency -> isDmDriverDependency(dependency, dmDriverCoordinate));
        if (!springBoot || !myBatis) {
            DependencyTreeAnalysis dependencyTreeAnalysis = dependencyTreeInspector.analyze(pomPath.getParent(), dmDriverCoordinate);
            springBoot = springBoot || dependencyTreeAnalysis.springBootProject();
            myBatis = myBatis || dependencyTreeAnalysis.myBatisProject();
            dmDriver = dmDriver || dependencyTreeAnalysis.hasDmJdbcDriver();
        }
        return new PomAnalysis(true, springBoot, myBatis, dmDriver);
    }

    Model readModel(Path pomPath) {
        try (Reader reader = Files.newBufferedReader(pomPath, StandardCharsets.UTF_8)) {
            return new MavenXpp3Reader().read(reader);
        } catch (IOException | XmlPullParserException e) {
            throw new DmAdapterException("Failed to read pom.xml: " + pomPath, e);
        }
    }

    boolean containsDependency(Path pomPath, DependencyCoordinate coordinate) {
        Model model = readModel(pomPath);
        return model.getDependencies().stream()
                .anyMatch(dependency -> coordinate.matches(dependency.getGroupId(), dependency.getArtifactId()));
    }

    private boolean hasSpringBootParent(Parent parent) {
        return parent != null
                && "org.springframework.boot".equals(parent.getGroupId())
                && "spring-boot-starter-parent".equals(parent.getArtifactId());
    }

    private boolean isSpringBootDependency(Dependency dependency) {
        return "org.springframework.boot".equals(dependency.getGroupId())
                && dependency.getArtifactId() != null
                && dependency.getArtifactId().startsWith("spring-boot-");
    }

    private boolean isMyBatisDependency(Dependency dependency) {
        String groupId = value(dependency.getGroupId()).toLowerCase(Locale.ROOT);
        String artifactId = value(dependency.getArtifactId()).toLowerCase(Locale.ROOT);
        return groupId.startsWith("org.mybatis") || artifactId.contains("mybatis");
    }

    private boolean isDmDriverDependency(Dependency dependency, DependencyCoordinate configuredCoordinate) {
        if (configuredCoordinate.matches(dependency.getGroupId(), dependency.getArtifactId())) {
            return true;
        }
        String groupId = value(dependency.getGroupId()).toLowerCase(Locale.ROOT);
        String artifactId = value(dependency.getArtifactId()).toLowerCase(Locale.ROOT);
        return groupId.equals("com.dameng") && artifactId.startsWith("dmjdbcdriver");
    }

    private String value(String value) {
        return value == null ? "" : value;
    }
}
