package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptProcedureEffectAnalyzerTest {
    @Test
    void recognizesReadOnlyExpressionsUsedBySharedDefinitionsAndUpgradeScripts() {
        var effects = effects("""
                CREATE PROCEDURE seed(IN item_id INT)
                BEGIN
                    INSERT INTO menu(id, note) VALUES (item_id, CAST(item_id AS VARCHAR(30)));
                    UPDATE menu SET note = CASE WHEN id = 1 THEN 'a' ELSE ('b') END;
                    UPDATE menu SET id = TIMESTAMPDIFF(DAY, created_at, DATE_ADD(created_at, INTERVAL 1 DAY));
                    SELECT GROUP_CONCAT(id) FROM menu;
                    INSERT INTO audit(id) SELECT ROW_NUMBER() OVER (PARTITION BY tenant ORDER BY id) FROM menu;
                END
                """, "CALL seed(1)");
        assertThat(effects.known()).isTrue();
        assertThat(effects.mutationTargets()).containsExactlyInAnyOrder("menu", "audit");
    }

    @Test
    void recordsJoinedUpdateTargetsAndUnrelatedAssignments() {
        var effects = effects("""
                CREATE PROCEDURE seed()
                BEGIN
                    UPDATE menu a LEFT JOIN (SELECT id FROM archive) b ON a.id = b.id SET a.id = b.id;
                    UPDATE menu a, archive b SET b.id = a.id WHERE a.id = b.id;
                    SELECT MIN(id) INTO @scratch FROM menu;
                    SET @other = 2;
                END
                """, "CALL seed");
        assertThat(effects.known()).isTrue();
        assertThat(effects.mutationTargets()).containsExactlyInAnyOrder("menu", "archive");
        assertThat(effects.assignedVariables()).containsExactlyInAnyOrder("scratch", "other");
    }

    @Test
    void tracksStaticDdlTargetsAndMetadataWithoutTreatingQuotedTextAsCalls() {
        var effects = effects("""
                CREATE PROCEDURE seed()
                BEGIN
                    -- CALL invisible(); SET @tenant = 3;
                    ALTER TABLE other ADD note VARCHAR(20) DEFAULT 'CALL invisible();';
                    CREATE TABLE audit(id INT, parent_id INT, INDEX idx_parent(parent_id),
                        FOREIGN KEY(parent_id) REFERENCES other(id));
                    CREATE INDEX idx_other ON other(note);
                END
                """, "CALL seed()");
        assertThat(effects.known()).isTrue();
        assertThat(effects.mutationTargets()).contains("other", "audit", "columns", "tables", "statistics");
        assertThat(effects.assignedVariables()).isEmpty();
    }

    @Test
    void rejectsOpaqueEffectsAndOutputParameters() {
        for (String body : List.of(
                "SET @scratch = 1, @tenant = 2;",
                "SELECT @tenant := 2;",
                "EXECUTE IMMEDIATE 'UPDATE tenant SET id = 2';",
                "SET NAMES utf8;",
                "DECLARE c CURSOR FOR SELECT id FROM tenant;",
                "INSERT INTO audit(id) VALUES (app.unknown_effect());",
                "INSERT INTO audit(id) VALUES (`unknown_effect`());",
                "SELECT 1--unknown_effect();",
                "DROP PROCEDURE other;",
                "CALL seed();"
        )) {
            assertThat(effects("CREATE PROCEDURE seed() BEGIN " + body + " END", "CALL seed()").known())
                    .as(body).isFalse();
        }
        assertThat(effects("CREATE PROCEDURE seed(OUT result INT) BEGIN SET result = 2; END",
                "CALL seed(@tenant)").known()).isFalse();
        assertThat(effects("CREATE PROCEDURE seed(result INOUT INT) AS BEGIN result := 2; END",
                "CALL seed(@tenant)").known()).isFalse();
    }

    @Test
    void resolvesNestedCallsUsingTheDefinitionAtTheCallSite() {
        var analyzer = new ScriptProcedureEffectAnalyzer(List.of());
        var results = analyzer.analyze(List.of(
                "CREATE PROCEDURE leaf(IN id INT) BEGIN INSERT INTO audit(id) VALUES (id); END",
                "CREATE PROCEDURE wrapper() BEGIN CALL leaf(1); END",
                "CALL wrapper()",
                "DROP PROCEDURE leaf",
                "CREATE PROCEDURE leaf(IN id INT) BEGIN UPDATE tenant SET id = 2; END",
                "CALL wrapper()"
        ), "");
        assertThat(results.get(2).known()).isTrue();
        assertThat(results.get(2).mutationTargets()).containsExactly("audit");
        assertThat(results.get(5).mutationTargets()).containsExactly("tenant");
    }

    @Test
    void resolvesNestedCallsInTheDefiningSchemaAndRejectsMissingOrFutureDefinitions() {
        var analyzer = new ScriptProcedureEffectAnalyzer(List.of(
                "CREATE PROCEDURE alpha.leaf() BEGIN INSERT INTO audit(id) VALUES (1); END"));
        var results = analyzer.analyze(List.of(
                "CREATE PROCEDURE alpha.wrapper() BEGIN CALL leaf(); END",
                "CALL alpha.wrapper()",
                "CALL beta.leaf()",
                "CALL future()",
                "CREATE PROCEDURE future() BEGIN SELECT 1; END"
        ), "");
        assertThat(results.get(1).known()).isTrue();
        assertThat(results.get(2).known()).isFalse();
        assertThat(results.get(3).known()).isFalse();
    }

    @Test
    void retainsDropsAndOpaqueCallInvalidationAcrossFilesButKeepsDefinitionsAfterMissingCalls() {
        var analyzer = new ScriptProcedureEffectAnalyzer(List.of(
                "CREATE PROCEDURE shared() BEGIN SELECT 1; END"));
        analyzer.analyze(List.of("DROP PROCEDURE shared"), "", "application");
        var dropped = analyzer.analyze(List.of("CALL shared()"), "", "application").get(0);
        assertThat(dropped.known()).isFalse();
        assertThat(dropped.missingDefinition()).isFalse();
        assertThat(analyzer.analyze(List.of("CALL shared()"), "", "system").get(0).known()).isTrue();
        var missing = analyzer.analyze(List.of("CALL unknown()"), "", "system").get(0);
        assertThat(missing.missingDefinition()).isTrue();
        assertThat(analyzer.analyze(List.of("CALL shared()"), "", "system").get(0).known()).isTrue();
        analyzer.analyze(List.of(
                "CREATE PROCEDURE opaque() BEGIN EXECUTE IMMEDIATE 'DROP PROCEDURE shared'; END",
                "CALL opaque()"
        ), "", "system");
        assertThat(analyzer.analyze(List.of("CALL shared()"), "", "system").get(0).known()).isFalse();
    }

    @Test
    void defersDefinitionAnalysisUntilAFileContainsQueryVariables() {
        var analyzer = new ScriptProcedureEffectAnalyzer(List.of());

        assertThat(analyzer.analyzeForQueryVariables(List.of(
                "CREATE PROCEDURE shared() BEGIN UPDATE tenant SET id = 2; END"
        ), "", "application")).isEmpty();

        var results = analyzer.analyzeForQueryVariables(List.of(
                "SET @tenant = 1",
                "CALL shared()"
        ), "", "application");
        assertThat(results.get(1).known()).isTrue();
        assertThat(results.get(1).mutationTargets()).containsExactly("tenant");
    }

    @Test
    void rejectsUnknownCallArgumentsAndWrongArity() {
        String source = "CREATE PROCEDURE seed(IN id INT) BEGIN INSERT INTO audit(id) VALUES (id); END";
        for (String call : List.of("CALL seed()", "CALL seed(1, 2)", "CALL seed(unknown_effect())")) {
            assertThat(effects(source, call).known()).as(call).isFalse();
        }
    }

    private ScriptProcedureEffectAnalyzer.Effects effects(String definition, String call) {
        Map<Integer, ScriptProcedureEffectAnalyzer.Effects> results = new ScriptProcedureEffectAnalyzer(List.of())
                .analyze(List.of(definition, call), "");
        return results.get(1);
    }
}
