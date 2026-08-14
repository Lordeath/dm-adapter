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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        Path projectRoot = pomPath.toAbsolutePath().normalize().getParent();
        PomSignals signals = analyzeDeclaredModules(
                pomPath.toAbsolutePath().normalize(),
                projectRoot,
                dmDriverCoordinate,
                new HashSet<>(),
                true
        );
        boolean springBoot = signals.springBoot();
        boolean myBatis = signals.myBatis();
        boolean dmDriver = signals.dmDriver();
        if (!springBoot || !myBatis) {
            DependencyTreeAnalysis dependencyTreeAnalysis = dependencyTreeInspector.analyze(
                    projectRoot,
                    dmDriverCoordinate
            );
            springBoot = springBoot || dependencyTreeAnalysis.springBootProject();
            myBatis = myBatis || dependencyTreeAnalysis.myBatisProject();
            dmDriver = dmDriver || dependencyTreeAnalysis.hasDmJdbcDriver();
        }
        return new PomAnalysis(true, springBoot, myBatis, dmDriver);
    }

    private PomSignals analyzeDeclaredModules(
            Path pomPath,
            Path projectRoot,
            DependencyCoordinate dmDriverCoordinate,
            Set<Path> visited,
            boolean required
    ) {
        Path realPom;
        try {
            realPom = pomPath.toRealPath();
            if (!realPom.startsWith(projectRoot.toRealPath()) || !visited.add(realPom)) {
                return PomSignals.empty();
            }
        } catch (IOException e) {
            return PomSignals.empty();
        }
        Model model;
        try {
            model = readModel(realPom);
        } catch (DmAdapterException e) {
            if (required) {
                throw e;
            }
            return PomSignals.empty();
        }
        List<Dependency> dependencies = model.getDependencies();
        PomSignals signals = new PomSignals(
                hasSpringBootParent(model.getParent())
                        || dependencies.stream().anyMatch(this::isSpringBootDependency),
                dependencies.stream().anyMatch(this::isMyBatisDependency),
                dependencies.stream().anyMatch(dependency -> isDmDriverDependency(dependency, dmDriverCoordinate))
        );
        for (String module : model.getModules()) {
            if (module == null || module.isBlank()) {
                continue;
            }
            Path modulePath = realPom.getParent().resolve(module.strip()).normalize();
            Path modulePom = Files.isRegularFile(modulePath) ? modulePath : modulePath.resolve("pom.xml");
            signals = signals.merge(analyzeDeclaredModules(
                    modulePom,
                    projectRoot,
                    dmDriverCoordinate,
                    visited,
                    false
            ));
        }
        return signals;
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

    private record PomSignals(boolean springBoot, boolean myBatis, boolean dmDriver) {
        private static PomSignals empty() {
            return new PomSignals(false, false, false);
        }

        private PomSignals merge(PomSignals other) {
            return new PomSignals(
                    springBoot || other.springBoot,
                    myBatis || other.myBatis,
                    dmDriver || other.dmDriver
            );
        }
    }
}
