package com.github.dmadapter.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MigrationReport;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.core.SqlChange;
import com.github.dmadapter.core.SqlScriptFileResult;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptValidationFailure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ReportWriter {
    public static final String SCAN_REPORT_MARKDOWN = "dm-adapter-scan-report.md";
    public static final String SCAN_REPORT_JSON = "dm-adapter-scan-report.json";
    public static final String MIGRATION_REPORT_MARKDOWN = "dm-adapter-report.md";
    public static final String MIGRATION_REPORT_JSON = "dm-adapter-report.json";
    public static final String SQL_SCRIPT_REPORT_MARKDOWN = "dm-adapter-sql-script-report.md";
    public static final String SQL_SCRIPT_REPORT_JSON = "dm-adapter-sql-script-report.json";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public ReportPaths writeScanReport(ProjectScanResult scanResult, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        Path markdownPath = reportDir.resolve(SCAN_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(SCAN_REPORT_JSON);
        Files.writeString(markdownPath, scanMarkdown(scanResult), StandardCharsets.UTF_8);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), scanResult);
        return new ReportPaths(markdownPath, jsonPath);
    }

    public ReportPaths writeMigrationReport(MigrationReport report, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        MigrationReport redactedReport = redactSensitiveSql(report);
        Path markdownPath = reportDir.resolve(MIGRATION_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(MIGRATION_REPORT_JSON);
        Files.writeString(markdownPath, migrationMarkdown(redactedReport), StandardCharsets.UTF_8);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), redactedReport);
        return new ReportPaths(markdownPath, jsonPath);
    }

    public ReportPaths writeSqlScriptMigrationReport(SqlScriptMigrationReport report, Path reportDir) throws IOException {
        Files.createDirectories(reportDir);
        SqlScriptMigrationReport redactedReport = redactSensitiveSql(report);
        Path markdownPath = reportDir.resolve(SQL_SCRIPT_REPORT_MARKDOWN);
        Path jsonPath = reportDir.resolve(SQL_SCRIPT_REPORT_JSON);
        Files.writeString(markdownPath, sqlScriptMarkdown(redactedReport), StandardCharsets.UTF_8);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), redactedReport);
        return new ReportPaths(markdownPath, jsonPath);
    }

    private String scanMarkdown(ProjectScanResult scanResult) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# dm-adapter Scan Report\n\n");
        appendScanSummary(markdown, scanResult);
        appendWarnings(markdown, scanResult.warnings());
        return markdown.toString();
    }

    private String migrationMarkdown(MigrationReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# dm-adapter Migration Report\n\n");
        markdown.append("- Project: `").append(report.projectRoot()).append("`\n");
        markdown.append("- Source DB: `").append(report.sourceDb()).append("`\n");
        markdown.append("- Target DB: `").append(report.targetDb()).append("`\n");
        markdown.append("- Dry run: `").append(report.dryRun()).append("`\n\n");

        appendScanSummary(markdown, report.scanResult());
        appendFileChanges(markdown, report.changedFiles());
        appendSqlChanges(markdown, "Automatic SQL Conversions", report.autoConvertedSqlItems());
        appendSqlChanges(markdown, "Manual Review SQL Items", report.manualReviewSqlItems());
        appendWarnings(markdown, report.riskWarnings());
        return markdown.toString();
    }

    private String sqlScriptMarkdown(SqlScriptMigrationReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# dm-adapter SQL Script Migration Report\n\n");
        markdown.append("- Project: `").append(report.projectRoot()).append("`\n");
        markdown.append("- SQL root: `").append(report.sqlRoot()).append("`\n");
        markdown.append("- SQL root out: `").append(report.sqlRootOut()).append("`\n");
        markdown.append("- Dry run: `").append(report.dryRun()).append("`\n");
        markdown.append("- Scanned SQL files: `").append(report.scannedFileCount()).append("`\n");
        markdown.append("- Converted files: `").append(report.convertedFileCount()).append("`\n");
        markdown.append("- Manual review SQL items: `").append(report.manualReviewSqlCount()).append("`\n");
        markdown.append("- Validation attempted: `").append(report.validationAttempted()).append("`\n");
        markdown.append("- Validation status: `").append(report.validationStatus()).append("`\n");
        markdown.append("- Validation success SQL count: `").append(report.validationSuccessCount()).append("`\n");
        markdown.append("- Validation failed SQL count: `").append(report.validationFailureCount()).append("`\n\n");

        appendSqlScriptFiles(markdown, report.files());
        appendSqlScriptManualReviewItems(markdown, report.manualReviewItems());
        appendSqlScriptValidationFailures(markdown, report.validationFailures());
        appendWarnings(markdown, report.warnings());
        return markdown.toString();
    }

    private void appendScanSummary(StringBuilder markdown, ProjectScanResult scanResult) {
        markdown.append("## Scan Summary\n\n");
        markdown.append("- Maven project: `").append(scanResult.mavenProject()).append("`\n");
        markdown.append("- Spring Boot project: `").append(scanResult.springBootProject()).append("`\n");
        markdown.append("- MyBatis project: `").append(scanResult.myBatisProject()).append("`\n");
        markdown.append("- Has Dameng JDBC driver: `").append(scanResult.hasDmJdbcDriver()).append("`\n");
        markdown.append("- Mapper XML count: `").append(scanResult.mapperXmlFiles().size()).append("`\n\n");
    }

    private void appendFileChanges(StringBuilder markdown, List<FileChange> fileChanges) {
        markdown.append("## File Changes\n\n");
        if (fileChanges.isEmpty()) {
            markdown.append("No file changes.\n\n");
            return;
        }
        for (FileChange change : fileChanges) {
            markdown.append("- `").append(change.changeType()).append("` `")
                    .append(change.path()).append("` - ")
                    .append(change.description())
                    .append(" (applied: `").append(change.applied()).append("`)\n");
        }
        markdown.append("\n");
    }

    private void appendSqlChanges(StringBuilder markdown, String title, List<SqlChange> sqlChanges) {
        markdown.append("## ").append(title).append("\n\n");
        if (sqlChanges.isEmpty()) {
            markdown.append("No items.\n\n");
            return;
        }
        for (SqlChange sqlChange : sqlChanges) {
            markdown.append("- `").append(sqlChange.file()).append("` statement `")
                    .append(sqlChange.statementId()).append("`");
            if (!sqlChange.appliedRules().isEmpty()) {
                markdown.append(" rules `").append(String.join(", ", sqlChange.appliedRules())).append("`");
            }
            if (sqlChange.manualReviewRequired()) {
                markdown.append(" reason: ").append(sqlChange.reason());
            }
            markdown.append("\n");
            markdown.append("  - Original: `").append(compact(sqlChange.originalSql())).append("`\n");
            markdown.append("  - Converted: `").append(compact(sqlChange.convertedSql())).append("`\n");
        }
        markdown.append("\n");
    }

    private void appendSqlScriptFiles(StringBuilder markdown, List<SqlScriptFileResult> files) {
        markdown.append("## Script Files\n\n");
        if (files.isEmpty()) {
            markdown.append("No files.\n\n");
            return;
        }
        markdown.append("| Source | Output | Schema | System | Statements | Converted | Manual Review | Validation OK | Validation Failed |\n");
        markdown.append("| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (SqlScriptFileResult file : files) {
            markdown.append("| `").append(file.sourceFile()).append("` | `")
                    .append(file.outputFile()).append("` | `")
                    .append(file.schema()).append("` | `")
                    .append(file.systemScript()).append("` | ")
                    .append(file.statementCount()).append(" | ")
                    .append(file.convertedStatementCount()).append(" | ")
                    .append(file.manualReviewStatementCount()).append(" | ")
                    .append(file.validationSuccessCount()).append(" | ")
                    .append(file.validationFailureCount()).append(" |\n");
        }
        markdown.append("\n");
    }

    private void appendSqlScriptManualReviewItems(
            StringBuilder markdown,
            List<SqlScriptManualReviewItem> manualReviewItems
    ) {
        markdown.append("## Manual Review SQL Items\n\n");
        if (manualReviewItems.isEmpty()) {
            markdown.append("No items.\n\n");
            return;
        }
        for (SqlScriptManualReviewItem item : manualReviewItems) {
            markdown.append("- `").append(item.sourceFile()).append("` statement `")
                    .append(item.statementIndex()).append("` reason: ")
                    .append(item.reason()).append("\n");
            markdown.append("  - Original: `").append(compact(item.originalSql())).append("`\n");
            markdown.append("  - Converted: `").append(compact(item.convertedSql())).append("`\n");
        }
        markdown.append("\n");
    }

    private void appendSqlScriptValidationFailures(
            StringBuilder markdown,
            List<SqlScriptValidationFailure> validationFailures
    ) {
        markdown.append("## Validation Failures\n\n");
        if (validationFailures.isEmpty()) {
            markdown.append("No failures.\n\n");
            return;
        }
        for (SqlScriptValidationFailure failure : validationFailures) {
            markdown.append("- `").append(failure.outputFile()).append("` statement `")
                    .append(failure.statementIndex()).append("` schema `")
                    .append(failure.schema()).append("` category `")
                    .append(failure.category()).append("`\n");
            markdown.append("  - Error: ").append(failure.errorSummary()).append("\n");
            markdown.append("  - SQL: `").append(failure.failedSqlSummary()).append("`\n");
        }
        markdown.append("\n");
    }

    private void appendWarnings(StringBuilder markdown, List<String> warnings) {
        markdown.append("## Risk Warnings\n\n");
        if (warnings.isEmpty()) {
            markdown.append("No warnings.\n");
            return;
        }
        for (String warning : warnings) {
            markdown.append("- ").append(warning).append("\n");
        }
    }

    private String compact(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String compact = sql.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 240) {
            return compact;
        }
        return compact.substring(0, 237) + "...";
    }

    private MigrationReport redactSensitiveSql(MigrationReport report) {
        return new MigrationReport(
                report.projectRoot(),
                report.sourceDb(),
                report.targetDb(),
                report.dryRun(),
                report.scanResult(),
                report.changedFiles(),
                redactSqlChanges(report.autoConvertedSqlItems()),
                redactSqlChanges(report.manualReviewSqlItems()),
                report.riskWarnings()
        );
    }

    private SqlScriptMigrationReport redactSensitiveSql(SqlScriptMigrationReport report) {
        return new SqlScriptMigrationReport(
                report.projectRoot(),
                report.sqlRoot(),
                report.sqlRootOut(),
                report.dryRun(),
                report.scannedFileCount(),
                report.convertedFileCount(),
                report.manualReviewSqlCount(),
                report.validationAttempted(),
                report.validationStatus(),
                report.validationSuccessCount(),
                report.validationFailureCount(),
                report.files(),
                redactManualReviewItems(report.manualReviewItems()),
                redactValidationFailures(report.validationFailures()),
                report.warnings()
        );
    }

    private List<SqlChange> redactSqlChanges(List<SqlChange> sqlChanges) {
        return sqlChanges.stream()
                .map(sqlChange -> new SqlChange(
                        sqlChange.file(),
                        sqlChange.statementId(),
                        redactSql(sqlChange.originalSql()),
                        redactSql(sqlChange.convertedSql()),
                        sqlChange.appliedRules(),
                        sqlChange.manualReviewRequired(),
                        sqlChange.reason()
                ))
                .toList();
    }

    private List<SqlScriptManualReviewItem> redactManualReviewItems(List<SqlScriptManualReviewItem> items) {
        return items.stream()
                .map(item -> new SqlScriptManualReviewItem(
                        item.sourceFile(),
                        item.outputFile(),
                        item.statementIndex(),
                        item.reason(),
                        redactSql(item.originalSql()),
                        redactSql(item.convertedSql())
                ))
                .toList();
    }

    private List<SqlScriptValidationFailure> redactValidationFailures(List<SqlScriptValidationFailure> failures) {
        return failures.stream()
                .map(failure -> new SqlScriptValidationFailure(
                        failure.sourceFile(),
                        failure.outputFile(),
                        failure.schema(),
                        failure.statementIndex(),
                        failure.category(),
                        failure.errorSummary(),
                        redactSql(failure.failedSqlSummary())
                ))
                .toList();
    }

    private String redactSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        String redacted = redactFunctionArgument(sql, "AES_ENCRYPT", 1);
        redacted = redactFunctionArgument(redacted, "AES_DECRYPT", 1);
        redacted = redactFunctionArgument(redacted, "SF_ENCRYPT_CHAR", 2);
        redacted = redactFunctionArgument(redacted, "SF_DECRYPT_TO_CHAR", 2);
        return redacted;
    }

    private String redactFunctionArgument(String sql, String functionName, int argumentIndex) {
        StringBuilder redacted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                int end = skipSingleQuotedString(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (current == '"') {
                int end = skipDoubleQuotedText(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (startsMyBatisPlaceholder(sql, index)) {
                int end = skipMyBatisPlaceholder(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (startsLineComment(sql, index)) {
                int end = skipUntilLineEnd(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (startsBlockComment(sql, index)) {
                int end = skipUntilBlockCommentEnd(sql, index);
                redacted.append(sql, index, end);
                index = end;
            } else if (startsFunction(sql, index, functionName)) {
                FunctionCall functionCall = readFunctionCall(sql, index, functionName);
                String replacement = functionCall == null
                        ? null
                        : redactFunctionCallArgument(sql, functionCall, argumentIndex);
                if (replacement == null) {
                    redacted.append(current);
                    index++;
                } else {
                    redacted.append(replacement);
                    index = functionCall.endIndex();
                    changed = true;
                }
            } else {
                redacted.append(current);
                index++;
            }
        }
        return changed ? redacted.toString() : sql;
    }

    private String redactFunctionCallArgument(String sql, FunctionCall functionCall, int argumentIndex) {
        List<TopLevelArgument> arguments = splitTopLevelArguments(functionCall.body());
        if (argumentIndex >= arguments.size()) {
            return null;
        }
        TopLevelArgument argument = arguments.get(argumentIndex);
        if (!isStringLiteral(argument.text())) {
            return null;
        }
        String body = functionCall.body().substring(0, argument.startIndex())
                + "'******'"
                + functionCall.body().substring(argument.endIndex());
        return sql.substring(functionCall.startIndex(), functionCall.openParenIndex() + 1)
                + body
                + sql.substring(functionCall.closeParenIndex(), functionCall.endIndex());
    }

    private FunctionCall readFunctionCall(String sql, int functionNameStart, String functionName) {
        if (!startsFunction(sql, functionNameStart, functionName)) {
            return null;
        }
        int openParenIndex = functionNameStart + functionName.length();
        while (openParenIndex < sql.length() && Character.isWhitespace(sql.charAt(openParenIndex))) {
            openParenIndex++;
        }
        int closeParenIndex = findMatchingParen(sql, openParenIndex);
        if (closeParenIndex < 0) {
            return null;
        }
        return new FunctionCall(
                functionNameStart,
                openParenIndex,
                closeParenIndex,
                closeParenIndex + 1,
                sql.substring(openParenIndex + 1, closeParenIndex)
        );
    }

    private boolean startsFunction(String sql, int index, String functionName) {
        if (index > 0 && isIdentifierPart(sql.charAt(index - 1))) {
            return false;
        }
        if (index + functionName.length() > sql.length()
                || !sql.regionMatches(true, index, functionName, 0, functionName.length())) {
            return false;
        }
        int afterName = index + functionName.length();
        if (afterName < sql.length() && isIdentifierPart(sql.charAt(afterName))) {
            return false;
        }
        while (afterName < sql.length() && Character.isWhitespace(sql.charAt(afterName))) {
            afterName++;
        }
        return afterName < sql.length() && sql.charAt(afterName) == '(';
    }

    private int findMatchingParen(String sql, int openParenIndex) {
        int depth = 0;
        int index = openParenIndex;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (startsMyBatisPlaceholder(sql, index)) {
                index = skipMyBatisPlaceholder(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
                index++;
            } else {
                index++;
            }
        }
        return -1;
    }

    private List<TopLevelArgument> splitTopLevelArguments(String body) {
        List<TopLevelArgument> arguments = new ArrayList<>();
        int depth = 0;
        int argumentStart = 0;
        int index = 0;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(body, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(body, index);
            } else if (startsMyBatisPlaceholder(body, index)) {
                index = skipMyBatisPlaceholder(body, index);
            } else if (startsLineComment(body, index)) {
                index = skipUntilLineEnd(body, index);
            } else if (startsBlockComment(body, index)) {
                index = skipUntilBlockCommentEnd(body, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                if (depth > 0) {
                    depth--;
                }
                index++;
            } else if (current == ',' && depth == 0) {
                arguments.add(new TopLevelArgument(body.substring(argumentStart, index), argumentStart, index));
                index++;
                argumentStart = index;
            } else {
                index++;
            }
        }
        arguments.add(new TopLevelArgument(body.substring(argumentStart), argumentStart, body.length()));
        return arguments;
    }

    private boolean isStringLiteral(String expression) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.charAt(0) == '\'') {
            return closedSingleQuotedStringEnd(trimmed, 0) == trimmed.length();
        }
        if (trimmed.charAt(0) == '"') {
            return closedDoubleQuotedTextEnd(trimmed, 0) == trimmed.length();
        }
        return false;
    }

    private int skipSingleQuotedString(String sql, int start) {
        int end = closedSingleQuotedStringEnd(sql, start);
        return end < 0 ? sql.length() : end;
    }

    private int closedSingleQuotedStringEnd(String sql, int start) {
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '\\' && index < sql.length()) {
                index++;
            } else if (current == '\'') {
                if (index < sql.length() && sql.charAt(index) == '\'') {
                    index++;
                } else {
                    return index;
                }
            }
        }
        return -1;
    }

    private int skipDoubleQuotedText(String sql, int start) {
        int end = closedDoubleQuotedTextEnd(sql, start);
        return end < 0 ? sql.length() : end;
    }

    private int closedDoubleQuotedTextEnd(String sql, int start) {
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '\\' && index < sql.length()) {
                index++;
            } else if (current == '"') {
                if (index < sql.length() && sql.charAt(index) == '"') {
                    index++;
                } else {
                    return index;
                }
            }
        }
        return -1;
    }

    private boolean startsMyBatisPlaceholder(String sql, int index) {
        return index + 1 < sql.length()
                && (sql.charAt(index) == '#' || sql.charAt(index) == '$')
                && sql.charAt(index + 1) == '{';
    }

    private int skipMyBatisPlaceholder(String sql, int start) {
        int end = sql.indexOf('}', start + 2);
        return end < 0 ? sql.length() : end + 1;
    }

    private boolean startsLineComment(String sql, int index) {
        return sql.startsWith("--", index)
                || (sql.charAt(index) == '#' && (index + 1 >= sql.length() || sql.charAt(index + 1) != '{'));
    }

    private int skipUntilLineEnd(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '\n') {
                break;
            }
        }
        return index;
    }

    private boolean startsBlockComment(String sql, int index) {
        return index + 1 < sql.length() && sql.charAt(index) == '/' && sql.charAt(index + 1) == '*';
    }

    private int skipUntilBlockCommentEnd(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            index++;
            if (current == '*' && index < sql.length() && sql.charAt(index) == '/') {
                index++;
                break;
            }
        }
        return index;
    }

    private boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private record FunctionCall(int startIndex, int openParenIndex, int closeParenIndex, int endIndex, String body) {
    }

    private record TopLevelArgument(String text, int startIndex, int endIndex) {
    }
}
