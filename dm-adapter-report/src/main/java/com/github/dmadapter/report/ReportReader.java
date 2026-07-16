package com.github.dmadapter.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.DmAdapterSummary;

import java.io.IOException;
import java.nio.file.Path;

public class ReportReader {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public MigrationReport readMigrationReport(Path reportDir) throws IOException {
        return objectMapper.readValue(reportDir.resolve(ReportWriter.MIGRATION_REPORT_JSON).toFile(), MigrationReport.class);
    }

    public DmAdapterSummary readSummary(Path reportDir) throws IOException {
        return objectMapper.readValue(reportDir.resolve(ReportWriter.SUMMARY_JSON).toFile(), DmAdapterSummary.class);
    }
}
