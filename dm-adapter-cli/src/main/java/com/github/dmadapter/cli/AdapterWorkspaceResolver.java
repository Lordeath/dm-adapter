package com.github.dmadapter.cli;

import com.github.dmadapter.core.DmAdapterException;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

class AdapterWorkspaceResolver {
    private static final String ADAPTER_DIRECTORY = ".dm-adapter";

    private final ApplicationModuleSelector applicationModuleSelector;
    private final Supplier<Path> workingDirectory;

    AdapterWorkspaceResolver() {
        this(new ApplicationModuleSelector(), () -> Path.of(System.getProperty("user.dir", ".")));
    }

    AdapterWorkspaceResolver(
            ApplicationModuleSelector applicationModuleSelector,
            Supplier<Path> workingDirectory
    ) {
        this.applicationModuleSelector = applicationModuleSelector;
        this.workingDirectory = workingDirectory;
    }

    Path resolve(Path projectRoot, Path appModule, Path configuredReportDir) {
        if (configuredReportDir != null) {
            return configuredReportDir.toAbsolutePath().normalize();
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        String projectKey = projectKey(normalizedRoot, appModule);
        return workingDirectory.get().toAbsolutePath().normalize()
                .resolve(ADAPTER_DIRECTORY)
                .resolve(projectKey)
                .normalize();
    }

    String projectKey(Path projectRoot, Path appModule) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (appModule != null) {
            ApplicationModule selected = applicationModuleSelector.select(normalizedRoot, appModule);
            String artifactId = PomArtifactIdReader.read(selected.pomPath())
                    .orElseThrow(() -> new DmAdapterException(
                            "Application module pom.xml does not define artifactId: " + selected.pomPath()
                    ));
            return requireSafeKey(artifactId, selected.pomPath());
        }

        try {
            ApplicationModule selected = applicationModuleSelector.select(normalizedRoot, null);
            Optional<String> artifactId = PomArtifactIdReader.read(selected.pomPath());
            if (artifactId.isPresent() && isSafeKey(artifactId.get())) {
                return artifactId.get();
            }
        } catch (DmAdapterException ignored) {
            // scan/report must still work when no unique Spring Boot application module exists.
        }

        Optional<String> rootArtifactId = PomArtifactIdReader.read(normalizedRoot.resolve("pom.xml"));
        if (rootArtifactId.isPresent() && isSafeKey(rootArtifactId.get())) {
            return rootArtifactId.get();
        }
        Path fileName = normalizedRoot.getFileName();
        if (fileName == null || fileName.toString().isBlank()) {
            throw new DmAdapterException("Could not derive a workspace directory name from project: " + normalizedRoot);
        }
        return fileName.toString();
    }

    private String requireSafeKey(String value, Path pomPath) {
        if (!isSafeKey(value)) {
            throw new DmAdapterException("Maven artifactId cannot be used as a workspace directory name in "
                    + pomPath + ": " + value);
        }
        return value;
    }

    private boolean isSafeKey(String value) {
        return value != null
                && !value.isBlank()
                && !".".equals(value)
                && !"..".equals(value)
                && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }
}
