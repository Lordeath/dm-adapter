package com.github.dmadapter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptProcedureSourcesTest {
    @TempDir
    Path tempDir;

    @Test
    void usesExplicitSharedDefinitionsWithoutExecutingOrCopyingThem() throws Exception {
        Files.createDirectories(tempDir.resolve("sql"));
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId>
                <artifactId>script-probe</artifactId><version>1</version></project>
                """);
        String definitions = """
                CREATE OR REPLACE PROCEDURE shared_seed(item_id IN INT) AS
                    v_id INT;
                BEGIN
                    v_id := item_id;
                    INSERT INTO module_menu(id) VALUES (v_id);
                END;
                /
                """;
        Files.writeString(tempDir.resolve("shared.sql"), definitions);
        String script = """
                SET @tenant = (SELECT MIN(id) FROM organization);
                DELIMITER $$
                CREATE PROCEDURE seed_local()
                BEGIN
                    CALL shared_seed(1);
                    INSERT INTO module_menu(id) VALUES (@tenant);
                END$$
                DELIMITER ;
                CALL seed_local();
                INSERT INTO module_menu(id) VALUES (@tenant);
                """;
        Files.writeString(tempDir.resolve("sql/seed.sql"), script);
        Path reportDir = tempDir.resolve("reports");
        int exitCode = new CommandLine(new DmAdapterCli()).execute("migrate", "--project", tempDir.toString(),
                "--sql-root", "sql", "--sql-root-out", "output", "--sql-scripts-only", "--dry-run",
                "--sql-procedure-source", "shared.sql", "--report-dir", reportDir.toString());
        assertThat(exitCode).isZero();
        var report = new ObjectMapper().readTree(reportDir.resolve("dm-adapter-sql-script-report.json").toFile());
        assertThat(report.path("manualReviewSqlCount").asInt()).isZero();
        assertThat(report.path("validationAttempted").asBoolean()).isFalse();
        assertThat(Files.readString(tempDir.resolve("shared.sql"))).isEqualTo(definitions);
        assertThat(Files.readString(tempDir.resolve("sql/seed.sql"))).isEqualTo(script);
        assertThat(Files.exists(tempDir.resolve("output/shared.sql"))).isFalse();
    }
}
