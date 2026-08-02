package com.github.dmadapter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.DmAdapterSummary;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.OverallStatus;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptValidationFailure;
import com.github.dmadapter.core.StageStatus;
import com.github.dmadapter.core.SummaryIssue;
import com.github.dmadapter.core.SummaryStage;
import com.github.dmadapter.report.ReportPaths;
import com.github.dmadapter.report.ReportWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class ProjectSummaryTracker {
    static final String EXECUTION_MODE = "FULL_MUTATING_SHARED_DATABASE";
    private static final int SCHEMA_VERSION = 1;

    private final AdapterContext context;
    private final ReportWriter reportWriter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LinkedHashMap<String, SummaryStage> stages = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> manualReview = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> reports = new LinkedHashMap<>();
    private final List<SummaryIssue> issues = new ArrayList<>();
    private final List<String> nextActions = new ArrayList<>();
    private final Map<String, Long> stageStartedNanos = new LinkedHashMap<>();
    private final DmValidationEnvironment environment;
    private OverallStatus overallStatus = OverallStatus.RUNNING;

    ProjectSummaryTracker(
            AdapterContext context,
            ReportWriter reportWriter,
            boolean sqlScriptRequested,
            boolean mapperValidationRequested,
            DmValidationEnvironment environment
    ) throws IOException {
        this.context = context;
        this.reportWriter = reportWriter;
        this.environment = environment;
        startStage("migration", "项目迁移", true, "正在扫描并迁移项目。");
        stages.put("sqlScriptValidation", pendingStage(
                "SQL 脚本数据库验证",
                sqlScriptRequested && environment.validationEnabled(),
                sqlScriptRequested ? "等待 SQL 脚本迁移阶段。" : "未提供 --sql-root。"
        ));
        stages.put("mapperValidation", pendingStage(
                "Mapper 数据库验证",
                mapperValidationRequested && environment.validationEnabled(),
                mapperValidationRequested ? "等待生成并运行验证测试。" : "未请求生成验证测试。"
        ));
        write();
    }

    void migrationCompleted(MigrationReport report, ReportPaths paths) throws IOException {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        counts.put("fileChanges", (long) report.changedFiles().size());
        counts.put("automaticConversions", (long) report.autoConvertedSqlItems().size());
        counts.put("manualReviewRaw", (long) report.manualReviewSqlItems().size());
        completeStage(
                "migration",
                StageStatus.PASSED,
                counts,
                "项目迁移报告已生成。",
                fileName(paths.markdownPath())
        );
        reports.put("migrationMarkdown", fileName(paths.markdownPath()));
        reports.put("migrationJson", fileName(paths.jsonPath()));
        collectManualReviewCounts(report.autoConvertedSqlItems(), report.manualReviewSqlItems());
        write();
    }

    void skipMigration(String message) throws IOException {
        skipStage("migration", message);
        write();
    }

    void invalidProject(MigrationReport report, ReportPaths paths) throws IOException {
        migrationCompleted(report, paths);
        replaceStageStatus("migration", StageStatus.FAILED, "项目根目录不是 Maven 项目，未执行迁移。");
        issues.add(new SummaryIssue(
                "ERROR", "CONFIGURATION", "NOT_MAVEN_PROJECT", 1, 1, 0,
                "确认 --project 指向包含 pom.xml 的项目根目录。"
        ));
        nextActions.add("修正项目路径后重新运行 migrate。");
        overallStatus = OverallStatus.FAILED;
        write();
    }

    void startSqlScriptValidation(boolean requested) throws IOException {
        if (!requested || !environment.validationEnabled()) {
            skipStage(
                    "sqlScriptValidation",
                    requested
                            ? "DM_SQL_VALIDATION 未设为 true，未执行数据库验证。"
                            : "未请求 SQL 脚本迁移。"
            );
        } else if (!environment.ready()) {
            failStage(
                    "sqlScriptValidation",
                    "缺少数据库验证环境变量：" + environment.missingVariables(),
                    ""
            );
        } else {
            startStage("sqlScriptValidation", "SQL 脚本数据库验证", true,
                    "正在完整执行转换后的 SQL 文件；该模式会修改共享测试库且不自动回滚。");
        }
        write();
    }

    void sqlScriptCompleted(SqlScriptMigrationReport report, ReportPaths paths) throws IOException {
        reports.put("sqlScriptMarkdown", fileName(paths.markdownPath()));
        reports.put("sqlScriptJson", fileName(paths.jsonPath()));
        manualReview.put("sqlScriptManualReview", (long) report.manualReviewSqlCount());

        long blocked = report.validationFailures().stream()
                .filter(failure -> "BLOCKED_BY_PRIOR_FAILURE".equals(failure.category()))
                .count();
        long roots = report.validationFailures().size() - blocked;
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        counts.put("files", (long) report.scannedFileCount());
        counts.put("validationPassed", (long) report.validationSuccessCount());
        counts.put("validationFailed", (long) report.validationFailureCount());
        counts.put("rootFailures", roots);
        counts.put("blockedCalls", blocked);

        StageStatus status;
        if (!report.validationAttempted()) {
            boolean connectionOrConfigurationFailure = environment.validationEnabled()
                    && !context.dryRun()
                    && (!environment.ready()
                    || report.validationStatus().toLowerCase(Locale.ROOT).contains("connection failed"));
            status = connectionOrConfigurationFailure ? StageStatus.FAILED : StageStatus.SKIPPED;
        } else if (hasFailureCategory(report, "VALIDATION_TIMEOUT")) {
            status = StageStatus.TIMEOUT;
        } else if (roots > 0L) {
            status = StageStatus.FAILED;
        } else {
            status = StageStatus.PASSED;
        }
        completeStage(
                "sqlScriptValidation",
                status,
                counts,
                report.validationStatus(),
                fileName(paths.markdownPath())
        );
        if (status == StageStatus.FAILED
                && report.validationStatus().toLowerCase(Locale.ROOT).contains("connection failed")) {
            issues.add(new SummaryIssue(
                    "CRITICAL", "SQL_SCRIPT_VALIDATION", "DATABASE_CONNECTION", 1, 1, 0,
                    action("DATABASE_CONNECTION")
            ));
        }
        collectSqlScriptIssues(report.validationFailures());
        write();
    }

    void startMapperValidation(boolean requested) throws IOException {
        if (!requested || !environment.validationEnabled()) {
            skipStage(
                    "mapperValidation",
                    requested
                            ? "DM_SQL_VALIDATION 未设为 true，未执行 Mapper 数据库验证。"
                            : "未请求生成 Mapper 验证测试。"
            );
        } else if (!environment.ready()) {
            failStage(
                    "mapperValidation",
                    "缺少数据库验证环境变量：" + environment.missingVariables(),
                    ""
            );
        } else {
            startStage("mapperValidation", "Mapper 数据库验证", true, "正在执行 Mapper 数据库验证。");
        }
        write();
    }

    void skipMapperValidation(String message) throws IOException {
        skipStage("mapperValidation", message);
        write();
    }

    MapperValidationAssessment mapperCompleted(ValidationTestRunResult result) throws IOException {
        MapperValidationAssessment assessment = assessMapperValidation(result);
        if (result.reportPath() != null) {
            reports.put("mapperValidationMarkdown", fileName(result.reportPath()));
            reports.put("mapperValidationJson", "sql-validation-report.json");
        }
        completeStage(
                "mapperValidation",
                assessment.status(),
                assessment.counts(),
                result.message(),
                result.reportPath() == null ? "" : fileName(result.reportPath())
        );
        issues.addAll(assessment.issues());
        if (assessment.status() == StageStatus.TIMEOUT) {
            issues.add(new SummaryIssue(
                    "CRITICAL", "MAPPER_VALIDATION", "VALIDATION_TIMEOUT", 1, 1, 0,
                    action("VALIDATION_TIMEOUT")
            ));
        }
        write();
        return assessment;
    }

    void fail(Throwable error) {
        try {
            List<String> runningStages = stages.entrySet().stream()
                    .filter(entry -> entry.getValue().status() == StageStatus.RUNNING)
                    .map(Map.Entry::getKey)
                    .toList();
            for (String key : runningStages) {
                SummaryStage stage = stages.get(key);
                completeStage(key, StageStatus.FAILED, stage.counts(), safeMessage(error), stage.report());
            }
            overallStatus = OverallStatus.FAILED;
            nextActions.add("处理内部错误后重新运行；详细原因请查看命令行日志。"
                    + (safeMessage(error).isBlank() ? "" : " 原因：" + safeMessage(error)));
            write();
        } catch (IOException ignored) {
            // Preserve the original command failure when the summary itself cannot be updated.
        }
    }

    void finish(int exitCode) throws IOException {
        if (stages.values().stream().anyMatch(stage -> stage.status() == StageStatus.TIMEOUT)) {
            overallStatus = OverallStatus.TIMEOUT;
        } else if (exitCode == 1 || exitCode == 2 || stages.get("migration").status() == StageStatus.FAILED) {
            overallStatus = OverallStatus.FAILED;
        } else if (exitCode == 3 || exitCode == 4 || !issues.isEmpty()
                || manualReview.getOrDefault("uniqueStatements", 0L) > 0L
                || manualReview.getOrDefault("sqlScriptManualReview", 0L) > 0L) {
            overallStatus = exitCode == 4 ? OverallStatus.FAILED : OverallStatus.COMPLETED_WITH_ISSUES;
        } else {
            overallStatus = OverallStatus.PASSED;
        }
        if (exitCode == 1 && nextActions.isEmpty()) {
            nextActions.add("查看命令行诊断并修复工具或生成测试的内部执行错误后重试。");
        }
        populateNextActions();
        write();
    }

    private void collectManualReviewCounts(List<SqlChange> automatic, List<SqlChange> manual) {
        Set<String> automaticKeys = automatic.stream().map(this::sqlChangeKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> manualKeys = manual.stream().map(this::sqlChangeKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        long overlap = manualKeys.stream().filter(automaticKeys::contains).count();
        long genericDynamic = manual.stream()
                .filter(change -> {
                    String reason = change.reason() == null ? "" : change.reason().toLowerCase(Locale.ROOT);
                    return reason.contains("dynamic") || reason.contains("动态");
                })
                .map(this::sqlChangeKey)
                .distinct()
                .count();
        manualReview.put("rawItems", (long) manual.size());
        manualReview.put("uniqueStatements", (long) manualKeys.size());
        manualReview.put("overlapWithAutomatic", overlap);
        manualReview.put("genericDynamicStatements", genericDynamic);
    }

    private void collectSqlScriptIssues(List<SqlScriptValidationFailure> failures) {
        Map<String, Long> roots = failures.stream()
                .filter(failure -> !"BLOCKED_BY_PRIOR_FAILURE".equals(failure.category()))
                .collect(Collectors.groupingBy(
                        SqlScriptValidationFailure::category,
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        roots.forEach((category, count) -> issues.add(new SummaryIssue(
                severity(category), "SQL_SCRIPT_VALIDATION", category, count, count, 0, action(category)
        )));
        long blocked = failures.stream()
                .filter(failure -> "BLOCKED_BY_PRIOR_FAILURE".equals(failure.category()))
                .count();
        if (blocked > 0L) {
            issues.add(new SummaryIssue(
                    "INFO", "SQL_SCRIPT_VALIDATION", "BLOCKED_BY_PRIOR_FAILURE",
                    blocked, 0, blocked, "先修复前面的存储过程、函数、触发器或视图创建失败。"
            ));
        }
    }

    private MapperValidationAssessment assessMapperValidation(ValidationTestRunResult result) {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        List<SummaryIssue> mapperIssues = new ArrayList<>();
        boolean infrastructureFailure = result.attempted() && result.exitCode() != 0;
        boolean internalFailure = result.attempted() && result.exitCode() != 0;
        boolean validationFailure = false;
        StageStatus status = result.status();
        Path jsonPath = result.reportPath() == null
                ? null
                : result.reportPath().resolveSibling("sql-validation-report.json");
        if (jsonPath != null && Files.isRegularFile(jsonPath)) {
            try {
                JsonNode root = objectMapper.readTree(jsonPath.toFile());
                JsonNode summary = root.path("summary");
                counts.put("passed", summary.path("passed").asLong());
                counts.put("failed", summary.path("failed").asLong());
                counts.put("skipped", summary.path("skipped").asLong());
                validationFailure = summary.path("failed").asLong() > 0L;
                JsonNode patterns = root.path("failurePatterns");
                if (patterns.isArray()) {
                    patterns.forEach(entry -> {
                        String pattern = entry.path("name").asText();
                        long count = entry.path("count").asLong();
                        if (count > 0L) {
                            mapperIssues.add(new SummaryIssue(
                                    severity(pattern),
                                    "MAPPER_VALIDATION",
                                    pattern,
                                    count,
                                    count,
                                    0,
                                    action(pattern)
                            ));
                        }
                    });
                }
                infrastructureFailure = validationFailure
                        ? mapperIssues.stream().anyMatch(issue ->
                        "INVALID_SCHEMA".equals(issue.pattern())
                                || "DATABASE_CONNECTION".equals(issue.pattern()))
                        : result.exitCode() != 0;
                internalFailure = result.exitCode() != 0 && !validationFailure;
            } catch (IOException ignored) {
                infrastructureFailure = result.exitCode() != 0;
                internalFailure = result.exitCode() != 0;
            }
        }
        if (!result.attempted()) {
            status = environment.validationEnabled() && !environment.ready()
                    ? StageStatus.FAILED
                    : StageStatus.SKIPPED;
            infrastructureFailure = environment.validationEnabled() && !environment.ready();
            internalFailure = false;
        } else if (result.status() == StageStatus.TIMEOUT) {
            status = StageStatus.TIMEOUT;
            infrastructureFailure = true;
            internalFailure = false;
        } else if (result.exitCode() == 0 && !validationFailure) {
            status = StageStatus.PASSED;
            infrastructureFailure = false;
            internalFailure = false;
        } else {
            status = StageStatus.FAILED;
        }
        return new MapperValidationAssessment(
                status,
                validationFailure,
                infrastructureFailure,
                internalFailure,
                counts,
                mapperIssues
        );
    }

    private void startStage(String key, String name, boolean requested, String message) {
        stageStartedNanos.put(key, System.nanoTime());
        stages.put(key, new SummaryStage(
                name, StageStatus.RUNNING, requested, true, Instant.now().toString(), "",
                0L, Map.of(), message, ""
        ));
    }

    private SummaryStage pendingStage(String name, boolean requested, String message) {
        return new SummaryStage(
                name,
                requested ? StageStatus.NOT_REQUESTED : StageStatus.SKIPPED,
                requested,
                false,
                "",
                requested ? "" : Instant.now().toString(),
                0L,
                Map.of(),
                message,
                ""
        );
    }

    private void skipStage(String key, String message) {
        SummaryStage current = stages.get(key);
        stages.put(key, new SummaryStage(
                current.name(), StageStatus.SKIPPED, current.requested(), false,
                current.startedAt(), Instant.now().toString(), elapsed(key), current.counts(), message, current.report()
        ));
    }

    private void failStage(String key, String message, String report) {
        SummaryStage current = stages.get(key);
        stages.put(key, new SummaryStage(
                current.name(), StageStatus.FAILED, true, false,
                current.startedAt(), Instant.now().toString(), elapsed(key), current.counts(), message, report
        ));
        if (issues.stream().noneMatch(issue -> "VALIDATION_ENVIRONMENT".equals(issue.pattern()))) {
            issues.add(new SummaryIssue(
                    "ERROR", "CONFIGURATION", "VALIDATION_ENVIRONMENT", 1, 1, 0,
                    "补齐验证环境变量后重新运行。"
            ));
        }
    }

    private void completeStage(
            String key,
            StageStatus status,
            Map<String, Long> counts,
            String message,
            String report
    ) {
        SummaryStage current = stages.get(key);
        stages.put(key, new SummaryStage(
                current.name(), status, current.requested(),
                status == StageStatus.SKIPPED ? false : current.attempted(),
                current.startedAt(), Instant.now().toString(), elapsed(key), counts, message, report
        ));
    }

    private void replaceStageStatus(String key, StageStatus status, String message) {
        SummaryStage current = stages.get(key);
        stages.put(key, new SummaryStage(
                current.name(), status, current.requested(), current.attempted(), current.startedAt(),
                current.completedAt(), current.durationMillis(), current.counts(), message, current.report()
        ));
    }

    private long elapsed(String key) {
        Long started = stageStartedNanos.get(key);
        return started == null ? 0L : Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private void populateNextActions() {
        if (!nextActions.isEmpty()) {
            return;
        }
        if (issues.stream().anyMatch(issue -> "INVALID_SCHEMA".equals(issue.pattern()))) {
            nextActions.add("确认配置的达梦 schema 已创建且验证账号具有访问权限，然后重新运行。");
        }
        if (issues.stream().anyMatch(issue -> "DATABASE_CONNECTION".equals(issue.pattern()))) {
            nextActions.add("检查达梦连接地址、账号、密码和数据库可用性。");
        }
        if (issues.stream().anyMatch(issue -> "VALIDATION_TIMEOUT".equals(issue.pattern()))) {
            nextActions.add("检查慢 SQL 或按需增大 DM_SQL_VALIDATION_TOTAL_TIMEOUT_SECONDS 后重新运行。");
        }
        if (issues.stream().anyMatch(issue -> "OBJECT_STATUS_VALIDATION_FAILED".equals(issue.pattern()))) {
            nextActions.add("确认达梦验证账号可查询当前 schema 的 ALL_OBJECTS 后重新运行。");
        }
        if (issues.stream().anyMatch(issue -> "OBJECT_DEFINITION_CHANGED".equals(issue.pattern()))) {
            nextActions.add("检查 -7184 失败前最近修改依赖对象的 DDL，并在 CALL 前重新编译对应过程；"
                    + "不要由工具修改 PL_SQL_STRIP。");
        }
        if (issues.stream().anyMatch(issue -> "INDEX_NAME_DEFINITION_CONFLICT".equals(issue.pattern()))) {
            nextActions.add("检查同名索引的现有定义；仅在确认业务语义后重命名或替换，工具不会把不同定义视为等价。");
        }
        if (issues.stream().anyMatch(issue -> issue.rootCount() > 0L)) {
            nextActions.add("按主要问题中的根因优先级处理详细报告，再重新执行完整数据库验证。");
        }
        if (manualReview.getOrDefault("uniqueStatements", 0L) > 0L
                || manualReview.getOrDefault("sqlScriptManualReview", 0L) > 0L) {
            nextActions.add("逐项确认迁移报告中的人工确认 SQL；工具不会强行改写不确定 SQL。");
        }
        if (nextActions.isEmpty()) {
            nextActions.add("当前请求的迁移和验证均已通过，无需额外处理。");
        }
    }

    private boolean hasFailureCategory(SqlScriptMigrationReport report, String category) {
        return report.validationFailures().stream().anyMatch(failure -> category.equals(failure.category()));
    }

    private String sqlChangeKey(SqlChange change) {
        return (change.file() == null ? "" : change.file()) + "#"
                + (change.statementId() == null ? "" : change.statementId());
    }

    private String severity(String pattern) {
        return switch (pattern) {
            case "INVALID_SCHEMA", "DATABASE_CONNECTION", "VALIDATION_TIMEOUT",
                    "OBJECT_STATUS_VALIDATION_FAILED" -> "CRITICAL";
            case "BLOCKED_BY_PRIOR_FAILURE" -> "INFO";
            default -> "ERROR";
        };
    }

    private String action(String pattern) {
        return switch (pattern) {
            case "INVALID_SCHEMA" -> "修正 schema 配置或创建缺失 schema；前置检查通过前不会执行 SQL。";
            case "DATABASE_CONNECTION" -> "检查达梦连接参数、账号权限和数据库可用性。";
            case "VALIDATION_TIMEOUT" -> "检查慢 SQL，必要时调整总验证时限。";
            case "INVALID_DATABASE_OBJECT" -> "修复新建对象的编译错误，确保对象状态为 VALID。";
            case "OBJECT_STATUS_VALIDATION_FAILED" -> "授予验证账号查询当前 schema 对象状态的权限后重新运行。";
            case "OBJECT_DEFINITION_CHANGED" -> "根据报告中的最近相关 DDL 检查过程依赖并重新编译；"
                    + "复杂动态 DDL 改为人工确认。";
            case "INDEX_NAME_DEFINITION_CONFLICT" -> "检查同名索引的列、表达式、顺序和唯一性，"
                    + "确认后重命名或替换现有索引。";
            case "TEST_SCHEMA_OBJECT", "TEST_SCHEMA_FUNCTION" -> "先补齐测试库对象，再判断是否属于业务 SQL 问题。";
            default -> "查看详细报告中的首个失败样例并按迁移分类处理。";
        };
    }

    private String fileName(Path path) {
        return path == null ? "" : path.getFileName().toString();
    }

    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return "";
        }
        return redact(error.getMessage());
    }

    private String redact(String value) {
        String redacted = value == null ? "" : value;
        redacted = redactValue(redacted, environment.jdbcUrl());
        redacted = redactValue(redacted, environment.username());
        return redactValue(redacted, environment.password());
    }

    private String redactValue(String message, String value) {
        return value == null || value.isBlank() ? message : message.replace(value, "******");
    }

    private void write() throws IOException {
        issues.sort(Comparator.comparing(SummaryIssue::severity).thenComparing(SummaryIssue::pattern));
        reportWriter.writeSummary(new DmAdapterSummary(
                SCHEMA_VERSION,
                Instant.now().toString(),
                context.projectRoot().toString(),
                context.dryRun(),
                environment.validationEnabled() ? EXECUTION_MODE : "NOT_EXECUTED",
                overallStatus,
                stages,
                manualReview,
                issues.stream().limit(20).toList(),
                reports,
                nextActions
        ), context.reportDir());
    }
}

record MapperValidationAssessment(
        StageStatus status,
        boolean validationFailure,
        boolean infrastructureFailure,
        boolean internalFailure,
        Map<String, Long> counts,
        List<SummaryIssue> issues
) {
    MapperValidationAssessment {
        counts = Map.copyOf(counts == null ? Map.of() : counts);
        issues = List.copyOf(issues == null ? List.of() : issues);
    }

    boolean timedOut() {
        return status == StageStatus.TIMEOUT;
    }
}
