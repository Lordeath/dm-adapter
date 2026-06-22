package com.github.dmadapter.report;

import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void migrationReportIncludesManualReviewReason() throws Exception {
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(),
                List.of()
        );
        SqlChange manualReviewItem = new SqlChange(
                "mapper/UserMapper.xml",
                "selectUsers",
                "select JSON_SET(profile, '$.name', #{name}) from user",
                "select JSON_SET(profile, '$.name', #{name}) from user",
                List.of(),
                true,
                "JSON_SET requires manual confirmation because Dameng support or syntax may differ from MySQL."
        );
        MigrationReport report = new MigrationReport(
                tempDir.toString(),
                "mysql",
                "dm",
                true,
                scanResult,
                List.of(),
                List.of(),
                List.of(manualReviewItem),
                List.of()
        );

        ReportPaths reportPaths = new ReportWriter().writeMigrationReport(report, tempDir);

        assertThat(Files.exists(reportPaths.jsonPath())).isTrue();
        assertThat(Files.readString(reportPaths.markdownPath()))
                .contains("Manual Review SQL Items")
                .contains("mapper/UserMapper.xml")
                .contains("JSON_SET requires manual confirmation");
    }

    @Test
    void migrationReportRedactsAesKeysInMarkdownAndJson() throws Exception {
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(),
                List.of()
        );
        SqlChange automaticConversion = new SqlChange(
                "mapper/UserMapper.xml",
                "updatePassword",
                "user_password = TO_BASE64(AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR}, 'REAL_SECRET'))",
                "user_password = TO_BASE64(SF_ENCRYPT_CHAR(#{userPassword, jdbcType=VARCHAR}, 513, 'REAL_SECRET', NULL))",
                List.of("MYSQL_AES_BASE64_TO_DM_AES128_ECB"),
                false,
                ""
        );
        MigrationReport report = new MigrationReport(
                tempDir.toString(),
                "mysql",
                "dm",
                true,
                scanResult,
                List.of(),
                List.of(automaticConversion),
                List.of(),
                List.of()
        );

        ReportPaths reportPaths = new ReportWriter().writeMigrationReport(report, tempDir);

        String markdown = Files.readString(reportPaths.markdownPath());
        String json = Files.readString(reportPaths.jsonPath());
        assertThat(markdown)
                .doesNotContain("REAL_SECRET")
                .contains("'******'");
        assertThat(json)
                .doesNotContain("REAL_SECRET")
                .contains("'******'");
    }
}
