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

public class MapperMigrator {
    private final MapperXmlRewriter mapperXmlRewriter;

    public MapperMigrator() {
        this(new MapperXmlRewriter());
    }

    public MapperMigrator(MapperXmlRewriter mapperXmlRewriter) {
        this.mapperXmlRewriter = mapperXmlRewriter;
    }

    public MapperMigrationResult migrate(ProjectScanResult scanResult, AdapterContext context, SqlConverter sqlConverter) {
        List<FileChange> fileChanges = new ArrayList<>();
        List<SqlChange> automaticConversions = new ArrayList<>();
        List<SqlChange> manualReviewItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (MapperXmlFile mapperXmlFile : scanResult.mapperXmlFiles()) {
            Path source = Paths.get(mapperXmlFile.path());
            Path target = context.mapperTargetDir().resolve(toMapperDmRelativePath(mapperXmlFile.resourcesRelativePath()));
            fileChanges.add(context.dryRun()
                    ? FileChange.planned(target.toString(), "CREATE", "Copy mapper XML from " + source)
                    : copyMapper(source, target));

            Path rewriteInput = context.dryRun() ? source : target;
            MapperRewriteResult rewriteResult = mapperXmlRewriter.rewrite(
                    rewriteInput,
                    target.toString(),
                    !context.dryRun(),
                    sqlConverter
            );
            automaticConversions.addAll(rewriteResult.automaticConversions());
            manualReviewItems.addAll(rewriteResult.manualReviewItems());
            warnings.addAll(rewriteResult.warnings());
        }

        return new MapperMigrationResult(fileChanges, automaticConversions, manualReviewItems, warnings);
    }

    private FileChange copyMapper(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return FileChange.applied(target.toString(), "CREATE", "Copied mapper XML from " + source);
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
