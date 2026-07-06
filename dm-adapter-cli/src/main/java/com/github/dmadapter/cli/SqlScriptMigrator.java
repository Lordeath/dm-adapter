package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlConversionResult;
import com.github.dmadapter.core.SqlScriptFileResult;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptValidationFailure;
import com.github.dmadapter.sql.SqlConverter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class SqlScriptMigrator {
    static final String MYSQL_CREATE_DEFINER_REMOVAL_RULE = "MYSQL_CREATE_DEFINER_REMOVED";
    static final String MYSQL_CREATE_PROCEDURE_TO_DM_RULE = "MYSQL_CREATE_PROCEDURE_TO_DM";
    static final String MYSQL_SCRIPT_METADATA_TO_DM_RULE = "MYSQL_SCRIPT_METADATA_TO_DM";
    static final String MYSQL_SCRIPT_COMMENT_CLAUSE_REMOVAL_RULE = "MYSQL_SCRIPT_COMMENT_CLAUSE_REMOVED";
    static final String MYSQL_SCHEMA_SCOPED_INDEX_NAME_RULE = "MYSQL_SCHEMA_SCOPED_INDEX_NAME";
    static final String MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE_RULE =
            "MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE";

    private static final String SUSPICIOUS_LENGTH_MODIFY_REASON =
            "可疑字段长度修改：当前 SQL 把字段改为 varchar(%s)，但前置判断没有使用“字符类型且长度小于 %s”的安全加长条件；"
                    + "如果原字段是 TEXT/CLOB 或 varchar 长度已经大于目标值，执行 MODIFY 可能会缩短字段并导致数据截断。"
                    + "建议先修原始 SQL：仅当 DATA_TYPE/column_type 为 char 或 varchar，"
                    + "且 CHARACTER_MAXIMUM_LENGTH 小于目标长度时才执行 ALTER，"
                    + "例如 DATA_TYPE IN ('char','varchar') AND CHARACTER_MAXIMUM_LENGTH < %s；TEXT/CLOB 不要自动收窄。";

    private static final Pattern CREATE_DEFINER_PATTERN = Pattern.compile(
            "(?is)^\\s*CREATE\\s+DEFINER\\s*=\\s*(?:`[^`]+`@`[^`]+`|'[^']+'@'[^']+'|\\S+)\\s+"
    );
    private static final Pattern CREATE_PROCEDURE_BODY_PATTERN = Pattern.compile(
            "(?is)^(\\s*)CREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\s+(.+?)\\s+BEGIN\\b"
    );
    private static final Pattern LENGTH_EQUALITY_PATTERN = Pattern.compile(
            "(?is)\\b(?:CHARACTER_MAXIMUM_LENGTH|CHAR_LENGTH)\\s*=\\s*(\\d+)\\b"
    );
    private static final Pattern LENGTH_RANGE_PATTERN = Pattern.compile(
            "(?is)\\b(?:CHARACTER_MAXIMUM_LENGTH|CHAR_LENGTH)\\s*(<=|>=|<|>)\\s*(\\d+)\\b"
    );
    private static final Pattern COLUMN_TYPE_GUARD_PATTERN = Pattern.compile(
            "(?is)\\b(?:DATA_TYPE|COLUMN_TYPE)\\b"
    );
    private static final Pattern VARCHAR_MODIFY_PATTERN = Pattern.compile(
            "(?is)\\bALTER\\s+TABLE\\b(?:(?!;).)*?\\bMODIFY(?:\\s+COLUMN)?\\b(?:(?!;).)*?"
                    + "\\b(?:VAR)?CHAR\\s*\\(\\s*(\\d+)\\s*\\)"
    );
    private static final String SQL_IDENTIFIER_TOKEN = "`[^`]+`|\"[^\"]+\"|[^\\s(]+";
    private static final List<TextReplacement> SCRIPT_METADATA_REPLACEMENTS = List.of(
            new TextReplacement(
                    Pattern.compile("(?is)\\(\\s*select\\s+database\\s*\\(\\s*\\)\\s*\\)"),
                    "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\bdatabase\\s*\\(\\s*\\)"),
                    "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\binformation_schema\\s*\\.\\s*columns\\b"),
                    "ALL_TAB_COLUMNS"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\bselect\\s+column_name\\s+from\\s+information_schema\\s*\\.\\s*statistics\\b"),
                    "SELECT INDEX_NAME FROM ALL_INDEXES"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\binformation_schema\\s*\\.\\s*statistics\\b"),
                    "ALL_INDEXES"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\bselect\\s+column_name\\s+from\\s+all_indexes\\b"),
                    "SELECT INDEX_NAME FROM ALL_INDEXES"
            ),
            new TextReplacement(Pattern.compile("(?is)\\btable_schema\\b"), "OWNER"),
            new TextReplacement(Pattern.compile("(?is)\\btable_name\\b"), "TABLE_NAME"),
            new TextReplacement(Pattern.compile("(?is)\\bcolumn_name\\b"), "COLUMN_NAME"),
            new TextReplacement(Pattern.compile("(?is)\\bcolumn_type\\b"), "DATA_TYPE"),
            new TextReplacement(Pattern.compile("(?is)\\bindex_name\\b"), "INDEX_NAME"),
            new TextReplacement(Pattern.compile("(?is)\\bnumeric_scale\\b"), "DATA_SCALE"),
            new TextReplacement(Pattern.compile("(?is)\\bcharacter_maximum_length\\b"), "CHAR_LENGTH")
    );
    private static final Map<Pattern, String> MANUAL_REVIEW_PATTERNS = Map.of(
            Pattern.compile("(?is)\\bDECLARE\\s+.+?\\s+HANDLER\\s+FOR\\b"),
            "MySQL procedure HANDLER syntax needs manual confirmation for Dameng.",
            Pattern.compile("(?is)\\bSIGNAL\\s+SQLSTATE\\b"),
            "MySQL SIGNAL SQLSTATE handling needs manual confirmation for Dameng.",
            Pattern.compile(
                    "(?is)\\bPREPARE\\s+\\w+\\s+FROM\\b|\\bEXECUTE\\s+(?!IMMEDIATE\\b)\\w+\\b|\\bDEALLOCATE\\s+PREPARE\\b"
            ),
            "MySQL dynamic SQL in procedures needs manual confirmation for Dameng.",
            Pattern.compile("(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?TRIGGER\\b"),
            "Trigger syntax differs between MySQL and Dameng and needs manual confirmation."
    );

    private final SqlConverter converter;
    private final Validator validator;

    SqlScriptMigrator(SqlConverter converter, Validator validator) {
        this.converter = converter;
        this.validator = validator;
    }

    SqlScriptMigrationReport migrate(SqlScriptMigrationRequest request) throws IOException {
        Path projectRoot = request.projectRoot().toAbsolutePath().normalize();
        Path sqlRoot = resolvePath(projectRoot, request.sqlRoot());
        Path sqlRootOut = resolvePath(projectRoot, request.sqlRootOut());
        if (!Files.isDirectory(sqlRoot)) {
            throw new IllegalStateException("SQL script root does not exist or is not a directory: " + sqlRoot);
        }
        if (sqlRoot.equals(sqlRootOut)) {
            throw new IllegalStateException("--sql-root-out must be different from --sql-root.");
        }

        List<Path> sqlFiles = sqlFiles(sqlRoot);
        List<String> warnings = new ArrayList<>();
        String schema = primarySchema(request.schema(), "--schema", warnings);
        String systemSchema = primarySchema(request.systemSchema(), "--system-schema", warnings);
        List<SqlScriptManualReviewItem> manualReviewItems = new ArrayList<>();
        List<PlannedSqlScriptFile> plannedFiles = new ArrayList<>();
        int convertedFileCount = 0;

        for (Path sqlFile : sqlFiles) {
            PlannedSqlScriptFile plannedFile = planFile(
                    sqlRoot,
                    sqlRootOut,
                    sqlFile,
                    schema,
                    systemSchema,
                    request.dryRun(),
                    manualReviewItems,
                    warnings
            );
            plannedFiles.add(plannedFile);
            if (plannedFile.converted()) {
                convertedFileCount++;
            }
        }

        SqlScriptValidationRun validationRun = request.dryRun()
                ? SqlScriptValidationRun.notAttempted("Dry run; SQL script validation skipped.", List.of())
                : validator.validate(plannedFiles, request.validationEnvironment());
        warnings.addAll(validationRun.warnings());

        Function<String, SqlScriptFileValidation> validationByOutput = output -> validationRun.fileValidations().stream()
                .filter(validation -> validation.outputFile().equals(output))
                .findFirst()
                .orElse(new SqlScriptFileValidation(output, 0, List.of()));
        List<SqlScriptFileResult> fileResults = plannedFiles.stream()
                .map(file -> {
                    SqlScriptFileValidation validation = validationByOutput.apply(file.outputDisplay());
                    return new SqlScriptFileResult(
                            file.sourceDisplay(),
                            file.outputDisplay(),
                            file.schema(),
                            file.systemScript(),
                            file.written(),
                            file.converted(),
                            file.originalStatementCount(),
                            file.convertedStatementCount(),
                            file.manualReviewStatementCount(),
                            validation.successCount(),
                            validation.failureCount(),
                            file.appliedRules()
                    );
                })
                .toList();

        return new SqlScriptMigrationReport(
                projectRoot.toString(),
                sqlRoot.toString(),
                sqlRootOut.toString(),
                request.dryRun(),
                sqlFiles.size(),
                convertedFileCount,
                manualReviewItems.size(),
                validationRun.attempted(),
                validationRun.status(),
                validationRun.successCount(),
                validationRun.failureCount(),
                fileResults,
                manualReviewItems,
                validationRun.failures(),
                warnings
        );
    }

    private PlannedSqlScriptFile planFile(
            Path sqlRoot,
            Path sqlRootOut,
            Path sqlFile,
            String schema,
            String systemSchema,
            boolean dryRun,
            List<SqlScriptManualReviewItem> manualReviewItems,
            List<String> warnings
    ) throws IOException {
        Path relative = sqlRoot.relativize(sqlFile);
        Path outputFile = sqlRootOut.resolve(relative).normalize();
        boolean systemScript = isSystemScript(sqlFile);
        String targetSchema = systemScript ? systemSchema : schema;
        if (systemScript && targetSchema.isBlank()) {
            warnings.add("System SQL script has no --system-schema and will use the current connection schema: " + relative);
        }

        String originalContent = Files.readString(sqlFile, StandardCharsets.UTF_8);
        List<String> originalStatements = SqlScriptParser.statements(originalContent);
        List<String> convertedStatements = new ArrayList<>();
        LinkedHashSet<String> appliedRules = new LinkedHashSet<>();
        int convertedStatementCount = 0;
        int manualReviewStatementCount = 0;

        for (int i = 0; i < originalStatements.size(); i++) {
            String originalStatement = originalStatements.get(i);
            ScriptStatementConversion conversion = convertStatement(originalStatement);
            if (conversion.manualReviewRequired()) {
                manualReviewStatementCount++;
                manualReviewItems.add(new SqlScriptManualReviewItem(
                        relative.toString(),
                        outputFile.toString(),
                        i + 1,
                        conversion.reason(),
                        originalStatement,
                        conversion.convertedSql()
                ));
            }
            if (conversion.changed() && !conversion.manualReviewRequired()) {
                convertedStatementCount++;
                appliedRules.addAll(conversion.appliedRules());
            }
            convertedStatements.add(conversion.outputSql());
        }

        String convertedContent = originalStatements.isEmpty()
                ? originalContent
                : SqlScriptParser.scriptContent(convertedStatements);
        boolean converted = !convertedContent.equals(originalContent);
        boolean written = false;
        if (!dryRun) {
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, convertedContent, StandardCharsets.UTF_8);
            written = true;
        }

        return new PlannedSqlScriptFile(
                relative.toString(),
                outputFile.toString(),
                targetSchema,
                systemScript,
                written,
                converted,
                originalStatements.size(),
                convertedStatementCount,
                manualReviewStatementCount,
                List.copyOf(appliedRules),
                convertedStatements
        );
    }

    private ScriptStatementConversion convertStatement(String originalStatement) {
        LeadingSqlPrefix leadingSqlPrefix = splitLeadingSqlPrefix(originalStatement);
        String sqlBody = leadingSqlPrefix.body();
        SafeRuleConversion safeRuleConversion = applyScriptSafeRules(sqlBody);
        SqlConversionResult sqlConversion = converter.convert(safeRuleConversion.sql());
        List<String> rules = new ArrayList<>(safeRuleConversion.appliedRules());
        rules.addAll(sqlConversion.appliedRules());
        String convertedBody = sqlConversion.convertedSql();
        String convertedSql = leadingSqlPrefix.prefix() + convertedBody;
        boolean changed = !convertedSql.equals(originalStatement);
        String manualReason = manualReviewReason(convertedBody);
        if (sqlConversion.manualReviewRequired()) {
            manualReason = sqlConversion.reason();
        }
        if (!manualReason.isBlank()) {
            return new ScriptStatementConversion(
                    originalStatement,
                    convertedSql,
                    originalStatement,
                    changed,
                    true,
                    manualReason,
                    rules
            );
        }
        return new ScriptStatementConversion(
                originalStatement,
                convertedSql,
                convertedSql,
                changed,
                false,
                "",
                rules
        );
    }

    private SafeRuleConversion applyScriptSafeRules(String sql) {
        if (sql == null || sql.isBlank()) {
            return new SafeRuleConversion(sql == null ? "" : sql, false, List.of());
        }
        String converted = sql;
        List<String> rules = new ArrayList<>();

        String withoutDefiner = CREATE_DEFINER_PATTERN.matcher(converted).replaceFirst("CREATE ");
        if (!withoutDefiner.equals(converted)) {
            converted = withoutDefiner;
            rules.add(MYSQL_CREATE_DEFINER_REMOVAL_RULE);
        }

        String procedureSql = convertCreateProcedureHeader(converted);
        if (!procedureSql.equals(converted)) {
            converted = procedureSql;
            rules.add(MYSQL_CREATE_PROCEDURE_TO_DM_RULE);
        }

        String metadataSql = replaceOutsideIgnoredText(converted, SCRIPT_METADATA_REPLACEMENTS);
        if (!metadataSql.equals(converted)) {
            converted = metadataSql;
            rules.add(MYSQL_SCRIPT_METADATA_TO_DM_RULE);
        }

        String schemaScopedIndexSql = synchronizeSchemaScopedIndexNames(converted);
        if (!schemaScopedIndexSql.equals(converted)) {
            converted = schemaScopedIndexSql;
            rules.add(MYSQL_SCHEMA_SCOPED_INDEX_NAME_RULE);
        }

        String commentSql = removeMysqlCommentClauses(converted);
        if (!commentSql.equals(converted)) {
            converted = commentSql;
            rules.add(MYSQL_SCRIPT_COMMENT_CLAUSE_REMOVAL_RULE);
        }

        String procedureDdlSql = wrapProcedureDdlStatements(converted);
        if (!procedureDdlSql.equals(converted)) {
            converted = procedureDdlSql;
            rules.add(MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE_RULE);
        }

        return new SafeRuleConversion(converted, !rules.isEmpty(), rules);
    }

    private String convertCreateProcedureHeader(String sql) {
        Matcher matcher = CREATE_PROCEDURE_BODY_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return sql;
        }
        return matcher.replaceFirst(matcher.group(1) + "CREATE OR REPLACE PROCEDURE " + matcher.group(2) + " AS\nBEGIN");
    }

    private String replaceOutsideIgnoredText(String sql, List<TextReplacement> replacements) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                int end = skipSingleQuotedString(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (current == '"') {
                int end = skipDoubleQuotedText(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (current == '`') {
                int end = skipBacktickIdentifier(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (startsLineComment(sql, index)) {
                int end = skipUntilLineEnd(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (startsBlockComment(sql, index)) {
                int end = skipUntilBlockCommentEnd(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else {
                TextReplacement replacement = replacementAt(sql, index, replacements);
                if (replacement == null) {
                    converted.append(current);
                    index++;
                } else {
                    Matcher matcher = replacement.pattern().matcher(sql);
                    matcher.region(index, sql.length());
                    matcher.lookingAt();
                    converted.append(replacement.replacement());
                    index = matcher.end();
                    changed = true;
                }
            }
        }
        return changed ? converted.toString() : sql;
    }

    private String wrapProcedureDdlStatements(String sql) {
        int start = skipWhitespace(sql, 0);
        if (!startsKeyword(sql, start, "CREATE")) {
            return sql;
        }
        int cursor = skipWhitespace(sql, start + "CREATE".length());
        if (startsKeyword(sql, cursor, "OR")) {
            cursor = skipWhitespace(sql, cursor + "OR".length());
            if (!startsKeyword(sql, cursor, "REPLACE")) {
                return sql;
            }
            cursor = skipWhitespace(sql, cursor + "REPLACE".length());
        }
        if (!startsKeyword(sql, cursor, "PROCEDURE")) {
            return sql;
        }

        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                int end = skipSingleQuotedString(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (current == '"') {
                int end = skipDoubleQuotedText(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (current == '`') {
                int end = skipBacktickIdentifier(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (startsLineComment(sql, index)) {
                int end = skipUntilLineEnd(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (startsBlockComment(sql, index)) {
                int end = skipUntilBlockCommentEnd(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (startsProcedureDdl(sql, index)) {
                int end = findStatementTerminator(sql, index);
                List<String> ddlStatements = convertProcedureDdlStatements(sql.substring(index, end).strip());
                for (int i = 0; i < ddlStatements.size(); i++) {
                    if (i > 0) {
                        converted.append("\n");
                    }
                    converted.append("EXECUTE IMMEDIATE '")
                            .append(ddlStatements.get(i).replace("'", "''"))
                            .append("'");
                    if (i + 1 < ddlStatements.size()) {
                        converted.append(";");
                    }
                }
                if (end < sql.length() && sql.charAt(end) == ';') {
                    converted.append(';');
                    index = end + 1;
                } else {
                    index = end;
                }
                changed = true;
            } else {
                converted.append(current);
                index++;
            }
        }
        return changed ? converted.toString() : sql;
    }

    private List<String> convertProcedureDdlStatements(String ddl) {
        String converted = convertMysqlAlterTableAddIndex(ddl);
        converted = normalizeCreateIndexForDm(converted);
        converted = Pattern.compile("(?is)\\bMODIFY\\s+COLUMN\\b").matcher(converted).replaceAll("MODIFY");
        converted = replaceOutsideIgnoredText(converted, List.of(
                new TextReplacement(
                        Pattern.compile("(?is)\\s+CHARACTER\\s+SET\\s*=\\s*[A-Za-z0-9_]+"),
                        ""
                ),
                new TextReplacement(
                        Pattern.compile("(?is)\\s+CHARACTER\\s+SET\\s+[A-Za-z0-9_]+"),
                        ""
                ),
                new TextReplacement(
                        Pattern.compile("(?is)\\s+CHARSET\\s*=\\s*[A-Za-z0-9_]+"),
                        ""
                ),
                new TextReplacement(
                        Pattern.compile("(?is)\\s+COLLATE\\s+[A-Za-z0-9_]+"),
                        ""
                )
        ));
        return splitMultiModifyAlterTable(converted);
    }

    private List<String> splitMultiModifyAlterTable(String ddl) {
        Matcher matcher = Pattern.compile("(?is)^ALTER\\s+TABLE\\s+(?<table>\\S+)\\s+(?<body>.+)$").matcher(ddl);
        if (!matcher.matches()) {
            return List.of(ddl);
        }
        List<String> parts = splitTopLevelComma(matcher.group("body"));
        if (parts.size() <= 1 || parts.stream().anyMatch(part -> !startsKeyword(part.strip(), 0, "MODIFY"))) {
            return List.of(ddl);
        }
        String table = matcher.group("table");
        return parts.stream()
                .map(String::strip)
                .map(part -> "ALTER TABLE " + table + " " + part)
                .toList();
    }

    private List<String> splitTopLevelComma(String value) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(value, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(value, index);
            } else if (current == '`') {
                index = skipBacktickIdentifier(value, index);
            } else if (current == '(') {
                depth++;
                index++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
                index++;
            } else if (current == ',' && depth == 0) {
                parts.add(value.substring(start, index));
                start = index + 1;
                index++;
            } else {
                index++;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    private String convertMysqlAlterTableAddIndex(String ddl) {
        Matcher matcher = Pattern.compile(
                "(?is)^ALTER\\s+TABLE\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+ADD\\s+(?:INDEX|KEY)\\s+"
                        + "(?<index>" + SQL_IDENTIFIER_TOKEN + ")\\s*\\((?<columns>.*)\\)\\s*$"
        ).matcher(ddl);
        if (!matcher.matches()) {
            return ddl;
        }
        return "CREATE INDEX "
                + matcher.group("index")
                + " ON "
                + matcher.group("table")
                + " ("
                + matcher.group("columns").strip()
                + ")";
    }

    private String normalizeCreateIndexForDm(String ddl) {
        Matcher matcher = Pattern.compile(
                "(?is)^(?<prefix>CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+)"
                        + "(?<index>" + SQL_IDENTIFIER_TOKEN + ")(?<middle>\\s+ON\\s+)"
                        + "(?<table>" + SQL_IDENTIFIER_TOKEN + ")"
                        + "(?<open>\\s*\\()(?<columns>.*)(?<close>\\)\\s*)$"
        ).matcher(ddl);
        if (!matcher.matches()) {
            return ddl;
        }
        return matcher.group("prefix")
                + dmSchemaScopedIndexName(matcher.group("table"), matcher.group("index"))
                + matcher.group("middle")
                + matcher.group("table")
                + " ("
                + stripMysqlIndexPrefixLengths(matcher.group("columns").strip())
                + matcher.group("close");
    }

    private String stripMysqlIndexPrefixLengths(String columns) {
        List<String> parts = splitTopLevelComma(columns);
        List<String> converted = new ArrayList<>(parts.size());
        for (String part : parts) {
            converted.add(stripMysqlIndexPrefixLength(part.strip()));
        }
        return String.join(", ", converted);
    }

    private String stripMysqlIndexPrefixLength(String column) {
        Matcher matcher = Pattern.compile(
                "(?is)^(?<column>(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*))"
                        + "\\s*\\(\\s*\\d+\\s*\\)(?<order>\\s+(?:ASC|DESC))?\\s*$"
        ).matcher(column);
        if (!matcher.matches()) {
            return column;
        }
        return matcher.group("column") + (matcher.group("order") == null ? "" : matcher.group("order"));
    }

    private String synchronizeSchemaScopedIndexNames(String sql) {
        List<IndexRename> renames = findIndexRenames(sql);
        if (renames.isEmpty()) {
            return sql;
        }
        String converted = sql;
        for (IndexRename rename : renames) {
            if (!rename.oldIndexName().equals(rename.newIndexName())) {
                converted = replaceIndexExistenceCheck(converted, rename);
            }
        }
        return converted;
    }

    private List<IndexRename> findIndexRenames(String sql) {
        List<IndexRename> renames = new ArrayList<>();
        collectIndexRenames(
                sql,
                Pattern.compile(
                        "(?is)\\bALTER\\s+TABLE\\s+(?<table>`[^`]+`|\"[^\"]+\"|\\S+)\\s+"
                                + "ADD\\s+(?:INDEX|KEY)\\s+(?<index>`[^`]+`|\"[^\"]+\"|[^\\s(]+)\\s*\\("
                ),
                renames
        );
        collectIndexRenames(
                sql,
                Pattern.compile(
                        "(?is)\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?<index>`[^`]+`|\"[^\"]+\"|\\S+)\\s+"
                                + "ON\\s+(?<table>`[^`]+`|\"[^\"]+\"|[^\\s(]+)\\s*\\("
                ),
                renames
        );
        return renames;
    }

    private void collectIndexRenames(String sql, Pattern pattern, List<IndexRename> renames) {
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            String oldIndexName = unquoteIdentifier(lastIdentifierPart(matcher.group("index")));
            String tableName = unquoteIdentifier(lastIdentifierPart(matcher.group("table")));
            int openParen = matcher.end() - 1;
            int closeParen = findMatchingParen(sql, openParen);
            List<String> columns = closeParen > openParen
                    ? indexColumnNames(sql.substring(openParen + 1, closeParen))
                    : List.of();
            String newIndexName = dmSchemaScopedIndexName(matcher.group("table"), matcher.group("index"));
            renames.add(new IndexRename(tableName, oldIndexName, newIndexName, columns));
        }
    }

    private String replaceIndexExistenceCheck(String sql, IndexRename rename) {
        String tableName = Pattern.quote(rename.tableName());
        String oldIndexName = Pattern.quote(rename.oldIndexName());
        String newIndexName = rename.newIndexName().replace("'", "''");
        String converted = Pattern.compile(
                        "(?is)(TABLE_NAME\\s*=\\s*'" + tableName + "'(?:(?!\\bTHEN\\b).)*?INDEX_NAME\\s*=\\s*)'"
                                + oldIndexName + "'"
                )
                .matcher(sql)
                .replaceAll("$1'" + Matcher.quoteReplacement(newIndexName) + "'");
        converted = Pattern.compile(
                        "(?is)(INDEX_NAME\\s*=\\s*)'" + oldIndexName
                                + "'((?:(?!\\bTHEN\\b).)*?TABLE_NAME\\s*=\\s*'" + tableName + "')"
                )
                .matcher(converted)
                .replaceAll("$1'" + Matcher.quoteReplacement(newIndexName) + "'$2");
        return replaceIndexNameCheckWithColumnCheck(converted, rename);
    }

    private String replaceIndexNameCheckWithColumnCheck(String sql, IndexRename rename) {
        if (rename.columnNames().isEmpty()) {
            return sql;
        }
        Pattern pattern = Pattern.compile(
                "(?is)IF\\s+NOT\\s+EXISTS\\s*\\(\\s*SELECT(?:(?!\\bTHEN\\b).)*?"
                        + "\\s+FROM\\s+ALL_INDEXES\\s+WHERE(?:(?!\\bTHEN\\b).)*?\\)\\s*THEN"
        );
        Matcher matcher = pattern.matcher(sql);
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        while (matcher.find()) {
            String check = matcher.group();
            if (matchesIndexCheck(check, rename)) {
                matcher.appendReplacement(converted, Matcher.quoteReplacement(indexColumnExistenceCheck(rename)));
                changed = true;
            }
        }
        matcher.appendTail(converted);
        return changed ? converted.toString() : sql;
    }

    private boolean matchesIndexCheck(String check, IndexRename rename) {
        String tableName = Pattern.quote(rename.tableName());
        String oldIndexName = Pattern.quote(rename.oldIndexName());
        String newIndexName = Pattern.quote(rename.newIndexName());
        boolean tableMatches = Pattern.compile("(?is)\\bTABLE_NAME\\s*=\\s*'" + tableName + "'")
                .matcher(check)
                .find();
        boolean indexMatches = Pattern.compile("(?is)\\bINDEX_NAME\\s*=\\s*'(?:"
                        + oldIndexName + "|" + newIndexName + ")'")
                .matcher(check)
                .find();
        return tableMatches && indexMatches;
    }

    private String indexColumnExistenceCheck(IndexRename rename) {
        return "IF NOT EXISTS (\n"
                + "        SELECT 1\n"
                + "        FROM (\n"
                + "            SELECT INDEX_NAME\n"
                + "            FROM ALL_IND_COLUMNS\n"
                + "            WHERE INDEX_OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')\n"
                + "              AND TABLE_NAME = '" + rename.tableName().replace("'", "''") + "'\n"
                + "              AND COLUMN_NAME IN (" + sqlStringList(rename.columnNames()) + ")\n"
                + "            GROUP BY INDEX_NAME\n"
                + "            HAVING COUNT(DISTINCT COLUMN_NAME) = " + rename.columnNames().size() + "\n"
                + "        )\n"
                + "    ) THEN";
    }

    private String sqlStringList(List<String> values) {
        List<String> escaped = new ArrayList<>(values.size());
        for (String value : values) {
            escaped.add("'" + value.replace("'", "''") + "'");
        }
        return String.join(", ", escaped);
    }

    private List<String> indexColumnNames(String columns) {
        List<String> names = new ArrayList<>();
        for (String part : splitTopLevelComma(columns)) {
            String column = stripMysqlIndexPrefixLength(part.strip());
            column = Pattern.compile("(?is)\\s+(?:ASC|DESC)\\s*$").matcher(column).replaceFirst("");
            if (!isSimpleIdentifier(column)) {
                return List.of();
            }
            names.add(unquoteIdentifier(column));
        }
        return names;
    }

    private boolean isSimpleIdentifier(String value) {
        return Pattern.compile("(?is)^(?:`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)$")
                .matcher(value)
                .matches();
    }

    private int findMatchingParen(String value, int openParen) {
        if (openParen < 0 || openParen >= value.length() || value.charAt(openParen) != '(') {
            return -1;
        }
        int depth = 0;
        int index = openParen;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(value, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(value, index);
            } else if (current == '`') {
                index = skipBacktickIdentifier(value, index);
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

    private String dmSchemaScopedIndexName(String tableToken, String indexToken) {
        String tableName = normalizeIdentifierSegment(unquoteIdentifier(lastIdentifierPart(tableToken)));
        String indexName = normalizeIdentifierSegment(unquoteIdentifier(lastIdentifierPart(indexToken)));
        if (tableName.isBlank() || indexName.isBlank()) {
            return indexToken;
        }
        if (indexName.regionMatches(true, 0, tableName + "_", 0, tableName.length() + 1)) {
            return indexName;
        }
        String scopedName = tableName + "_" + indexName;
        if (!Character.isLetter(scopedName.charAt(0)) && scopedName.charAt(0) != '_') {
            scopedName = "idx_" + scopedName;
        }
        int maxIdentifierLength = 120;
        if (scopedName.length() <= maxIdentifierLength) {
            return scopedName;
        }
        String hash = Integer.toHexString(Objects.hash(tableName, indexName));
        int prefixLength = Math.max(1, maxIdentifierLength - hash.length() - 1);
        return scopedName.substring(0, prefixLength) + "_" + hash;
    }

    private String normalizeIdentifierSegment(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean previousUnderscore = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            boolean allowed = Character.isLetterOrDigit(current) || current == '_';
            if (allowed) {
                normalized.append(current);
                previousUnderscore = false;
            } else if (!previousUnderscore) {
                normalized.append('_');
                previousUnderscore = true;
            }
        }
        while (!normalized.isEmpty() && normalized.charAt(0) == '_') {
            normalized.deleteCharAt(0);
        }
        while (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == '_') {
            normalized.deleteCharAt(normalized.length() - 1);
        }
        return normalized.toString();
    }

    private String lastIdentifierPart(String token) {
        int dot = token.lastIndexOf('.');
        return dot < 0 ? token : token.substring(dot + 1);
    }

    private String unquoteIdentifier(String token) {
        if (token.length() >= 2
                && ((token.charAt(0) == '`' && token.charAt(token.length() - 1) == '`')
                || (token.charAt(0) == '"' && token.charAt(token.length() - 1) == '"')
                || (token.charAt(0) == '[' && token.charAt(token.length() - 1) == ']'))) {
            return token.substring(1, token.length() - 1);
        }
        return token;
    }

    private boolean startsProcedureDdl(String sql, int index) {
        if (startsKeyword(sql, index, "ALTER")) {
            return startsKeyword(sql, skipWhitespace(sql, index + "ALTER".length()), "TABLE");
        }
        if (startsKeyword(sql, index, "DROP")) {
            int cursor = skipWhitespace(sql, index + "DROP".length());
            return startsKeyword(sql, cursor, "TABLE") || startsKeyword(sql, cursor, "INDEX");
        }
        if (startsKeyword(sql, index, "TRUNCATE")) {
            return true;
        }
        if (!startsKeyword(sql, index, "CREATE")) {
            return false;
        }
        int cursor = skipWhitespace(sql, index + "CREATE".length());
        if (startsKeyword(sql, cursor, "UNIQUE")) {
            cursor = skipWhitespace(sql, cursor + "UNIQUE".length());
        } else if (startsKeyword(sql, cursor, "TEMPORARY")) {
            cursor = skipWhitespace(sql, cursor + "TEMPORARY".length());
        }
        return startsKeyword(sql, cursor, "INDEX") || startsKeyword(sql, cursor, "TABLE");
    }

    private int findStatementTerminator(String sql, int index) {
        int cursor = index;
        while (cursor < sql.length()) {
            char current = sql.charAt(cursor);
            if (current == '\'') {
                cursor = skipSingleQuotedString(sql, cursor);
            } else if (current == '"') {
                cursor = skipDoubleQuotedText(sql, cursor);
            } else if (current == '`') {
                cursor = skipBacktickIdentifier(sql, cursor);
            } else if (startsLineComment(sql, cursor)) {
                cursor = skipUntilLineEnd(sql, cursor);
            } else if (startsBlockComment(sql, cursor)) {
                cursor = skipUntilBlockCommentEnd(sql, cursor);
            } else if (current == ';') {
                return cursor;
            } else {
                cursor++;
            }
        }
        return sql.length();
    }

    private TextReplacement replacementAt(String sql, int index, List<TextReplacement> replacements) {
        for (TextReplacement replacement : replacements) {
            Matcher matcher = replacement.pattern().matcher(sql);
            matcher.region(index, sql.length());
            if (matcher.lookingAt()) {
                return replacement;
            }
        }
        return null;
    }

    private String removeMysqlCommentClauses(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                int end = skipSingleQuotedString(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (current == '"') {
                int end = skipDoubleQuotedText(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (current == '`') {
                int end = skipBacktickIdentifier(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (startsLineComment(sql, index)) {
                int end = skipUntilLineEnd(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (startsBlockComment(sql, index)) {
                int end = skipUntilBlockCommentEnd(sql, index);
                converted.append(sql, index, end);
                index = end;
            } else if (startsKeyword(sql, index, "COMMENT")) {
                int cursor = skipWhitespace(sql, index + "COMMENT".length());
                if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                    cursor = skipWhitespace(sql, cursor + 1);
                }
                if (cursor < sql.length() && (sql.charAt(cursor) == '\'' || sql.charAt(cursor) == '"')) {
                    trimTrailingWhitespace(converted);
                    index = sql.charAt(cursor) == '\''
                            ? skipSingleQuotedString(sql, cursor)
                            : skipDoubleQuotedText(sql, cursor);
                    changed = true;
                } else {
                    converted.append(current);
                    index++;
                }
            } else {
                converted.append(current);
                index++;
            }
        }
        return changed ? converted.toString() : sql;
    }

    private int skipWhitespace(String value, int index) {
        int cursor = index;
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private boolean startsKeyword(String value, int index, String keyword) {
        if (index < 0 || index + keyword.length() > value.length()) {
            return false;
        }
        if (!value.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        int before = index - 1;
        int after = index + keyword.length();
        return (before < 0 || !isIdentifierPart(value.charAt(before)))
                && (after >= value.length() || !isIdentifierPart(value.charAt(after)));
    }

    private boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private boolean startsLineComment(String value, int index) {
        return index + 1 < value.length() && value.charAt(index) == '-' && value.charAt(index + 1) == '-';
    }

    private boolean startsBlockComment(String value, int index) {
        return index + 1 < value.length() && value.charAt(index) == '/' && value.charAt(index + 1) == '*';
    }

    private int skipUntilLineEnd(String value, int index) {
        int cursor = index;
        while (cursor < value.length() && value.charAt(cursor) != '\n' && value.charAt(cursor) != '\r') {
            cursor++;
        }
        if (cursor < value.length() && value.charAt(cursor) == '\r') {
            cursor++;
            if (cursor < value.length() && value.charAt(cursor) == '\n') {
                cursor++;
            }
        } else if (cursor < value.length() && value.charAt(cursor) == '\n') {
            cursor++;
        }
        return cursor;
    }

    private int skipUntilBlockCommentEnd(String value, int index) {
        int cursor = index + 2;
        while (cursor + 1 < value.length()) {
            if (value.charAt(cursor) == '*' && value.charAt(cursor + 1) == '/') {
                return cursor + 2;
            }
            cursor++;
        }
        return value.length();
    }

    private int skipSingleQuotedString(String value, int index) {
        int cursor = index + 1;
        while (cursor < value.length()) {
            char current = value.charAt(cursor);
            if (current == '\\' && cursor + 1 < value.length()) {
                cursor += 2;
            } else if (current == '\'' && cursor + 1 < value.length() && value.charAt(cursor + 1) == '\'') {
                cursor += 2;
            } else if (current == '\'') {
                return cursor + 1;
            } else {
                cursor++;
            }
        }
        return value.length();
    }

    private int skipDoubleQuotedText(String value, int index) {
        int cursor = index + 1;
        while (cursor < value.length()) {
            char current = value.charAt(cursor);
            if (current == '\\' && cursor + 1 < value.length()) {
                cursor += 2;
            } else if (current == '"' && cursor + 1 < value.length() && value.charAt(cursor + 1) == '"') {
                cursor += 2;
            } else if (current == '"') {
                return cursor + 1;
            } else {
                cursor++;
            }
        }
        return value.length();
    }

    private int skipBacktickIdentifier(String value, int index) {
        int cursor = index + 1;
        while (cursor < value.length()) {
            if (value.charAt(cursor) == '`') {
                if (cursor + 1 < value.length() && value.charAt(cursor + 1) == '`') {
                    cursor += 2;
                } else {
                    return cursor + 1;
                }
            } else {
                cursor++;
            }
        }
        return value.length();
    }

    private void trimTrailingWhitespace(StringBuilder builder) {
        while (!builder.isEmpty() && Character.isWhitespace(builder.charAt(builder.length() - 1))) {
            builder.setLength(builder.length() - 1);
        }
    }

    private LeadingSqlPrefix splitLeadingSqlPrefix(String sql) {
        int cursor = 0;
        boolean moved;
        do {
            moved = false;
            int whitespaceEnd = skipWhitespace(sql, cursor);
            if (whitespaceEnd > cursor) {
                cursor = whitespaceEnd;
                moved = true;
            }
            if (startsLineComment(sql, cursor)) {
                cursor = skipUntilLineEnd(sql, cursor);
                moved = true;
            } else if (startsBlockComment(sql, cursor)) {
                cursor = skipUntilBlockCommentEnd(sql, cursor);
                moved = true;
            }
        } while (moved && cursor < sql.length());
        return new LeadingSqlPrefix(sql.substring(0, cursor), sql.substring(cursor));
    }

    private String manualReviewReason(String sql) {
        String suspiciousLengthModifyReason = suspiciousLengthModifyReason(sql);
        if (!suspiciousLengthModifyReason.isBlank()) {
            return suspiciousLengthModifyReason;
        }
        for (Map.Entry<Pattern, String> entry : MANUAL_REVIEW_PATTERNS.entrySet()) {
            if (entry.getKey().matcher(sql).find()) {
                return entry.getValue();
            }
        }
        return "";
    }

    private String suspiciousLengthModifyReason(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        Matcher modifyMatcher = VARCHAR_MODIFY_PATTERN.matcher(sql);
        while (modifyMatcher.find()) {
            String targetLength = modifyMatcher.group(1);
            String context = lengthModifyContext(sql, modifyMatcher.start(), modifyMatcher.end());
            if (hasLengthEqualityForTarget(context, targetLength)
                    && !hasSafeLengthIncreaseGuard(context, targetLength)) {
                return SUSPICIOUS_LENGTH_MODIFY_REASON.formatted(targetLength, targetLength, targetLength);
            }
        }
        return "";
    }

    private String lengthModifyContext(String sql, int modifyStart, int modifyEnd) {
        int contextStart = previousIfKeywordIndex(sql, modifyStart);
        if (contextStart < 0) {
            contextStart = 0;
        }
        return sql.substring(contextStart, modifyEnd);
    }

    private int previousIfKeywordIndex(String sql, int beforeIndex) {
        Matcher matcher = Pattern.compile("(?is)\\bIF\\b").matcher(sql);
        int previous = -1;
        while (matcher.find() && matcher.start() < beforeIndex) {
            previous = matcher.start();
        }
        return previous;
    }

    private boolean hasLengthEqualityForTarget(String sql, String targetLength) {
        Matcher matcher = LENGTH_EQUALITY_PATTERN.matcher(sql);
        while (matcher.find()) {
            if (matcher.group(1).equals(targetLength)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSafeLengthIncreaseGuard(String sql, String targetLength) {
        if (!COLUMN_TYPE_GUARD_PATTERN.matcher(sql).find()) {
            return false;
        }
        Matcher matcher = LENGTH_RANGE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String operator = matcher.group(1);
            String length = matcher.group(2);
            if (length.equals(targetLength) && ("<".equals(operator) || "<=".equals(operator))) {
                return true;
            }
        }
        return false;
    }

    private Path resolvePath(Path projectRoot, Path path) {
        return path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : projectRoot.resolve(path).toAbsolutePath().normalize();
    }

    private List<Path> sqlFiles(Path sqlRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(sqlRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    private boolean isSystemScript(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith("_system.sql");
    }

    private String primarySchema(String value, String option, List<String> warnings) {
        List<String> schemas = DamengMetadataReader.splitSchemaList(value);
        if (schemas.isEmpty()) {
            return "";
        }
        if (schemas.size() > 1) {
            warnings.add("SQL script validation uses the first schema from " + option + ": " + schemas.get(0));
        }
        return schemas.get(0);
    }

    interface Validator {
        SqlScriptValidationRun validate(
                List<PlannedSqlScriptFile> files,
                DmValidationEnvironment environment
        );
    }

    record PlannedSqlScriptFile(
            String sourceDisplay,
            String outputDisplay,
            String schema,
            boolean systemScript,
            boolean written,
            boolean converted,
            int originalStatementCount,
            int convertedStatementCount,
            int manualReviewStatementCount,
            List<String> appliedRules,
            List<String> statements
    ) {
        PlannedSqlScriptFile {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
            statements = List.copyOf(statements == null ? List.of() : statements);
        }
    }

    private record SafeRuleConversion(String sql, boolean changed, List<String> appliedRules) {
        private SafeRuleConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }
    }

    private record TextReplacement(Pattern pattern, String replacement) {
    }

    private record LeadingSqlPrefix(String prefix, String body) {
    }

    private record IndexRename(String tableName, String oldIndexName, String newIndexName, List<String> columnNames) {
        private IndexRename {
            columnNames = List.copyOf(columnNames == null ? List.of() : columnNames);
        }
    }

    private record ScriptStatementConversion(
            String originalSql,
            String convertedSql,
            String outputSql,
            boolean changed,
            boolean manualReviewRequired,
            String reason,
            List<String> appliedRules
    ) {
        private ScriptStatementConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }
    }
}
