package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.sql.SqlConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MapperMigrator {
    private final MapperXmlRewriter mapperXmlRewriter;
    private final Consumer<String> progressLogger;

    public MapperMigrator() {
        this(new MapperXmlRewriter(), message -> {
        });
    }

    public MapperMigrator(Consumer<String> progressLogger) {
        this(new MapperXmlRewriter(), progressLogger);
    }

    public MapperMigrator(MapperXmlRewriter mapperXmlRewriter) {
        this(mapperXmlRewriter, message -> {
        });
    }

    public MapperMigrator(MapperXmlRewriter mapperXmlRewriter, Consumer<String> progressLogger) {
        this.mapperXmlRewriter = mapperXmlRewriter;
        this.progressLogger = progressLogger == null ? message -> {
        } : progressLogger;
    }

    public MapperMigrationResult migrate(ProjectScanResult scanResult, AdapterContext context, SqlConverter sqlConverter) {
        return migrate(scanResult, context, sqlConverter, SqlRewriteConfig.empty());
    }

    public MapperMigrationResult migrate(
            ProjectScanResult scanResult,
            AdapterContext context,
            SqlConverter sqlConverter,
            SqlRewriteConfig rewriteConfig
    ) {
        List<FileChange> fileChanges = new ArrayList<>();
        List<SqlChange> automaticConversions = new ArrayList<>();
        List<SqlChange> manualReviewItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        List<MapperXmlFile> mapperXmlFiles = scanResult.mapperXmlFiles();
        int total = mapperXmlFiles.size();
        for (int i = 0; i < total; i++) {
            MapperXmlFile mapperXmlFile = mapperXmlFiles.get(i);
            progress("Mapper XML migration [" + (i + 1) + "/" + total + "]: " + displayPath(mapperXmlFile));
            Path source = Paths.get(mapperXmlFile.path());
            Path target = mapperTargetDir(context, mapperXmlFile).resolve(toMapperDmRelativePath(mapperXmlFile.resourcesRelativePath()));
            fileChanges.add(context.dryRun()
                    ? FileChange.planned(target.toString(), "CREATE", "计划复制 Mapper XML，来源：" + source)
                    : copyMapper(source, target));

            Path rewriteInput = context.dryRun() ? source : target;
            MapperRewriteResult rewriteResult = mapperXmlRewriter.rewrite(
                    rewriteInput,
                    target.toString(),
                    !context.dryRun(),
                    sqlConverter,
                    rewriteConfig
            );
            automaticConversions.addAll(rewriteResult.automaticConversions());
            manualReviewItems.addAll(rewriteResult.manualReviewItems());
            warnings.addAll(rewriteResult.warnings());
        }
        if (total > 0) {
            progress("Mapper XML migration finished. Files: " + total
                    + ", automatic conversions: " + automaticConversions.size()
                    + ", manual review: " + manualReviewItems.size());
        }

        return new MapperMigrationResult(fileChanges, automaticConversions, manualReviewItems, warnings);
    }

    private void progress(String message) {
        progressLogger.accept(message);
    }

    private String displayPath(MapperXmlFile mapperXmlFile) {
        if (!mapperXmlFile.resourcesRelativePath().isBlank()) {
            return mapperXmlFile.resourcesRelativePath();
        }
        return mapperXmlFile.path();
    }

    private Path mapperTargetDir(AdapterContext context, MapperXmlFile mapperXmlFile) {
        if (!mapperXmlFile.resourcesRoot().isBlank() && context.mapperTargetDir().equals(context.defaultMapperTargetDir())) {
            return Paths.get(mapperXmlFile.resourcesRoot()).resolve("mapper-dm");
        }
        return context.mapperTargetDir();
    }

    private FileChange copyMapper(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return FileChange.applied(target.toString(), "CREATE", "已复制 Mapper XML，来源：" + source);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy mapper XML from " + source + " to " + target, e);
        }
    }

    private Path toMapperDmRelativePath(String resourcesRelativePath) {
        String normalized = resourcesRelativePath.replace('\\', '/');
        if (normalized.startsWith("mapper/")) {
            return Paths.get(normalized.substring("mapper/".length()));
        }
        if (normalized.startsWith("mappers/")) {
            return Paths.get(normalized.substring("mappers/".length()));
        }
        return Paths.get(normalized);
    }
}
