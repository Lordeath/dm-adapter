package com.github.dmadapter.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.MigrationReport;

import java.io.IOException;
import java.nio.file.Path;

public class ReportReader {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public MigrationReport readMigrationReport(Path reportDir) throws IOException {
        return objectMapper.readValue(reportDir.resolve(ReportWriter.MIGRATION_REPORT_JSON).toFile(), MigrationReport.class);
    }
}
