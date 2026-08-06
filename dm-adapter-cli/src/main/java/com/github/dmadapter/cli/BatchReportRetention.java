package com.github.dmadapter.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;

final class BatchReportRetention {
    static final String MARKER_FILE = ".dm-adapter-batch-run";
    static final String MARKER_CONTENT = "dm-adapter-batch-run-v1\n";

    void clean(Path reportRoot, int retentionDays) throws IOException {
        if (retentionDays <= 0 || !Files.isDirectory(reportRoot)) {
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try (var children = Files.list(reportRoot)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(child)) {
                    continue;
                }
                Path marker = child.resolve(MARKER_FILE);
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String content = Files.readString(marker, StandardCharsets.UTF_8);
                if (!MARKER_CONTENT.equals(content) || !Files.getLastModifiedTime(marker).toInstant().isBefore(cutoff)) {
                    continue;
                }
                deleteTree(child);
            }
        }
    }

    void mark(Path runDirectory) throws IOException {
        Files.createDirectories(runDirectory);
        Files.writeString(runDirectory.resolve(MARKER_FILE), MARKER_CONTENT, StandardCharsets.UTF_8);
    }

    private void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
