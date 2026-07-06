package com.github.dmadapter.cli;

import com.github.dmadapter.core.SqlConversionResult;
import com.github.dmadapter.core.SqlScriptFileResult;
import com.github.dmadapter.core.SqlScriptManualReviewItem;
import com.github.dmadapter.core.SqlScriptMigrationReport;
import com.github.dmadapter.core.SqlScriptValidationFailure;
import com.github.dmadapter.sql.SqlConverter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    static final String MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL_RULE =
            "MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL";
    static final String MYSQL_PROCEDURE_TEMP_TABLE_COMPILE_PLACEHOLDER_RULE =
            "MYSQL_PROCEDURE_TEMP_TABLE_COMPILE_PLACEHOLDER";
    static final String MYSQL_SQL_STRING_JSON_ESCAPE_TO_DM_RULE =
            "MYSQL_SQL_STRING_JSON_ESCAPE_TO_DM";

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
            "(?is)^(\\s*)CREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\s+(.+?)"
                    + "(?:\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*:\\s*)?BEGIN\\b"
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
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final List<ProcedureTempTableColumn> PROCEDURE_TEMP_TABLE_DEFAULT_COLUMNS = List.of(
            new ProcedureTempTableColumn("enterprise_id", "BIGINT"),
            new ProcedureTempTableColumn("organization_id", "BIGINT"),
            new ProcedureTempTableColumn("roleid", "VARCHAR(200)"),
            new ProcedureTempTableColumn("orderindex", "BIGINT")
    );
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
                    Pattern.compile("(?is)\\binformation_schema\\s*\\.\\s*tables\\b"),
                    "ALL_TABLES"
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
        if (!request.dryRun()) {
            plannedFiles.addAll(outputOnlyPlannedFiles(sqlRootOut, plannedFiles, schema, systemSchema, warnings));
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

    private List<PlannedSqlScriptFile> outputOnlyPlannedFiles(
            Path sqlRootOut,
            List<PlannedSqlScriptFile> plannedFiles,
            String schema,
            String systemSchema,
            List<String> warnings
    ) throws IOException {
        if (!Files.isDirectory(sqlRootOut)) {
            return List.of();
        }
        LinkedHashSet<String> plannedOutputs = new LinkedHashSet<>();
        for (PlannedSqlScriptFile plannedFile : plannedFiles) {
            plannedOutputs.add(Path.of(plannedFile.outputDisplay()).toAbsolutePath().normalize().toString());
        }
        List<PlannedSqlScriptFile> outputOnlyFiles = new ArrayList<>();
        for (Path outputFile : sqlFiles(sqlRootOut)) {
            Path normalizedOutput = outputFile.toAbsolutePath().normalize();
            if (plannedOutputs.contains(normalizedOutput.toString())) {
                continue;
            }
            Path relative = sqlRootOut.relativize(outputFile);
            boolean systemScript = isSystemScript(outputFile);
            String targetSchema = systemScript ? systemSchema : schema;
            if (systemScript && targetSchema.isBlank()) {
                warnings.add("Output-only system SQL script has no --system-schema and will use the current connection schema: "
                        + relative);
            }
            String content = Files.readString(outputFile, StandardCharsets.UTF_8);
            List<String> statements = SqlScriptParser.statements(content);
            outputOnlyFiles.add(new PlannedSqlScriptFile(
                    "(output-only) " + relative,
                    outputFile.toString(),
                    targetSchema,
                    systemScript,
                    false,
                    false,
                    statements.size(),
                    0,
                    0,
                    List.of(),
                    statements
            ));
        }
        return outputOnlyFiles;
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
            List<String> outputStatements = expandConvertedOutputStatements(conversion.outputSql());
            if (outputStatements.size() > 1 && !conversion.manualReviewRequired()) {
                appliedRules.add(MYSQL_PROCEDURE_TEMP_TABLE_COMPILE_PLACEHOLDER_RULE);
            }
            convertedStatements.addAll(outputStatements);
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

    private List<String> expandConvertedOutputStatements(String outputSql) {
        if (outputSql == null || outputSql.isBlank()) {
            return List.of(outputSql == null ? "" : outputSql);
        }
        List<String> placeholders = procedureTempTableCompilePlaceholders(outputSql);
        if (placeholders.isEmpty()) {
            return List.of(outputSql);
        }
        List<String> statements = new ArrayList<>(placeholders.size() + 1);
        statements.addAll(placeholders);
        statements.add(outputSql);
        return statements;
    }

    private List<String> procedureTempTableCompilePlaceholders(String sql) {
        LeadingSqlPrefix leadingSqlPrefix = splitLeadingSqlPrefix(sql);
        if (!isCreateProcedureStatement(leadingSqlPrefix.body())) {
            return List.of();
        }
        LinkedHashMap<String, LinkedHashSet<String>> tableColumns =
                temporaryProcedureTableDefinitions(leadingSqlPrefix.body());
        if (tableColumns.isEmpty()) {
            return List.of();
        }
        return tableColumns.entrySet().stream()
                .map(entry -> "CREATE TABLE IF NOT EXISTS " + entry.getKey()
                        + " (" + procedureTempTableColumnDefinitions(entry.getValue()) + ")")
                .toList();
    }

    private LinkedHashMap<String, LinkedHashSet<String>> temporaryProcedureTableDefinitions(String sql) {
        LinkedHashMap<String, LinkedHashSet<String>> tableColumns = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("(?i)\\btmp_[A-Za-z0-9_]+\\b").matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group();
            if (Pattern.compile("(?i)_idx(?:_|$)").matcher(tableName).find()) {
                continue;
            }
            tableColumns.computeIfAbsent(tableName, ignored -> new LinkedHashSet<>());
        }
        collectCreateTableSelectColumns(sql, tableColumns);
        collectInsertSelectColumns(sql, tableColumns);
        for (LinkedHashSet<String> columns : tableColumns.values()) {
            for (ProcedureTempTableColumn defaultColumn : PROCEDURE_TEMP_TABLE_DEFAULT_COLUMNS) {
                columns.add(defaultColumn.name());
            }
        }
        return tableColumns;
    }

    private void collectCreateTableSelectColumns(
            String sql,
            LinkedHashMap<String, LinkedHashSet<String>> tableColumns
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)\\bCREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                        + "(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+(?:AS\\s+)?SELECT\\b"
        ).matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group("table").strip();
            if (!isProcedureTemporaryTableName(tableName)) {
                continue;
            }
            int statementEnd = findStatementTerminator(sql, matcher.start());
            String selectTail = sql.substring(matcher.end(), statementEnd);
            int fromIndex = topLevelKeywordIndex(selectTail, "FROM");
            String selectList = fromIndex < 0 ? selectTail : selectTail.substring(0, fromIndex);
            List<String> columns = selectListColumns(selectList);
            if (columns.isEmpty()) {
                continue;
            }
            LinkedHashSet<String> targetColumns = tableColumns.computeIfAbsent(tableName, ignored -> new LinkedHashSet<>());
            targetColumns.addAll(columns);
        }
    }

    private void collectInsertSelectColumns(
            String sql,
            LinkedHashMap<String, LinkedHashSet<String>> tableColumns
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)\\bINSERT\\s+INTO\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s*\\((?<columns>[^)]*)\\)\\s*SELECT\\b"
        ).matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group("table").strip();
            if (!isProcedureTemporaryTableName(tableName)) {
                continue;
            }
            LinkedHashSet<String> targetColumns = tableColumns.computeIfAbsent(tableName, ignored -> new LinkedHashSet<>());
            for (String column : splitTopLevelComma(matcher.group("columns"))) {
                String columnName = unquoteIdentifier(lastIdentifierPart(column.strip()));
                if (!columnName.isBlank()) {
                    targetColumns.add(columnName);
                }
            }
        }
    }

    private List<String> selectListColumns(String selectList) {
        List<String> columns = new ArrayList<>();
        for (String item : splitTopLevelComma(selectList)) {
            String column = selectItemColumnName(item.strip());
            if (column.isBlank()) {
                return List.of();
            }
            columns.add(column);
        }
        return columns;
    }

    private String selectItemColumnName(String item) {
        if (item.isBlank() || "*".equals(item) || item.endsWith(".*")) {
            return "";
        }
        Matcher aliasMatcher = Pattern.compile("(?is)^.+\\s+AS\\s+(" + SQL_IDENTIFIER_TOKEN + ")\\s*$").matcher(item);
        if (aliasMatcher.matches()) {
            return unquoteIdentifier(lastIdentifierPart(aliasMatcher.group(1).strip()));
        }
        Matcher implicitAliasMatcher = Pattern.compile(
                "(?is)^.+\\s+([A-Za-z_][A-Za-z0-9_$]*|`[^`]+`|\"[^\"]+\")\\s*$"
        ).matcher(item);
        if (implicitAliasMatcher.matches()
                && !endsWithReservedSelectWord(item.substring(0, implicitAliasMatcher.start(1)))) {
            return unquoteIdentifier(lastIdentifierPart(implicitAliasMatcher.group(1).strip()));
        }
        Matcher identifierMatcher = Pattern.compile(
                "(?is)^(?:" + SQL_IDENTIFIER_TOKEN + "\\s*\\.\\s*)?(" + SQL_IDENTIFIER_TOKEN + ")\\s*$"
        ).matcher(item);
        if (identifierMatcher.matches()) {
            return unquoteIdentifier(lastIdentifierPart(identifierMatcher.group(1).strip()));
        }
        return "";
    }

    private boolean endsWithReservedSelectWord(String value) {
        String stripped = value.stripTrailing();
        return Pattern.compile("(?is).+\\b(?:WHEN|THEN|ELSE|END|FROM|WHERE|AND|OR|ON|IN|IS|NOT|NULL)$")
                .matcher(stripped)
                .matches();
    }

    private String procedureTempTableColumnDefinitions(LinkedHashSet<String> columns) {
        List<String> definitions = new ArrayList<>();
        for (String column : columns) {
            definitions.add(column + " " + procedureTempTableColumnType(column));
        }
        return String.join(", ", definitions);
    }

    private String procedureTempTableColumnType(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        if (lower.equals("roleid")) {
            return "VARCHAR(200)";
        }
        if (lower.equals("enterprise_id")
                || lower.equals("organization_id")
                || lower.equals("id")
                || lower.equals("orderindex")
                || lower.endsWith("_id")
                || lower.endsWith("id")) {
            return "BIGINT";
        }
        if (lower.endsWith("code") || lower.endsWith("name")) {
            return "VARCHAR(200)";
        }
        return "VARCHAR(4000)";
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

        String unusedCursorHandlerSql = removeUnusedMysqlCursorHandlers(converted);
        if (!unusedCursorHandlerSql.equals(converted)) {
            converted = unusedCursorHandlerSql;
            rules.add(MYSQL_CREATE_PROCEDURE_TO_DM_RULE);
        }

        String procedureDeclarationSql = moveMysqlProcedureDeclarations(converted);
        if (!procedureDeclarationSql.equals(converted)) {
            converted = procedureDeclarationSql;
            rules.add(MYSQL_CREATE_PROCEDURE_TO_DM_RULE);
        }

        String procedureUserVariableSql = convertMysqlProcedureUserVariables(converted);
        if (!procedureUserVariableSql.equals(converted)) {
            converted = procedureUserVariableSql;
            rules.add(MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL_RULE);
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

        String jsonEscapeSql = normalizeMysqlJsonEscapesInSqlStringLiterals(converted);
        if (!jsonEscapeSql.equals(converted)) {
            converted = jsonEscapeSql;
            rules.add(MYSQL_SQL_STRING_JSON_ESCAPE_TO_DM_RULE);
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
        String signature = convertMysqlProcedureSignature(matcher.group(2).stripTrailing());
        String converted = matcher.replaceFirst(Matcher.quoteReplacement(
                matcher.group(1) + "CREATE OR REPLACE PROCEDURE " + signature + " AS\nBEGIN"
        ));
        String label = matcher.group(3);
        if (label != null && !label.isBlank()) {
            converted = replaceOutsideIgnoredText(converted, List.of(new TextReplacement(
                    Pattern.compile("(?is)\\bLEAVE\\s+" + Pattern.quote(label.strip()) + "\\s*;"),
                    "RETURN;"
            )));
        }
        return converted;
    }

    private String convertMysqlProcedureSignature(String signature) {
        int openParen = firstTopLevelParen(signature);
        if (openParen < 0) {
            return signature;
        }
        int closeParen = findMatchingParen(signature, openParen);
        if (closeParen <= openParen) {
            return signature;
        }
        String parameters = signature.substring(openParen + 1, closeParen);
        String convertedParameters = convertMysqlProcedureParameters(parameters);
        return signature.substring(0, openParen + 1)
                + convertedParameters
                + signature.substring(closeParen);
    }

    private String convertMysqlProcedureParameters(String parameters) {
        if (parameters == null || parameters.isBlank()) {
            return parameters == null ? "" : parameters;
        }
        List<String> converted = new ArrayList<>();
        for (String parameter : splitTopLevelComma(parameters)) {
            converted.add(convertMysqlProcedureParameter(parameter));
        }
        return String.join(", ", converted);
    }

    private String convertMysqlProcedureParameter(String parameter) {
        String stripped = parameter.strip();
        Matcher matcher = Pattern.compile(
                "(?is)^(INOUT|IN|OUT)\\s+(" + SQL_IDENTIFIER_TOKEN + ")\\s+(.+)$"
        ).matcher(stripped);
        if (!matcher.matches()) {
            return stripped;
        }
        return matcher.group(2) + " " + matcher.group(1).toUpperCase(Locale.ROOT) + " " + matcher.group(3).strip();
    }

    private String moveMysqlProcedureDeclarations(String sql) {
        int start = skipWhitespace(sql, 0);
        if (!startsKeyword(sql, start, "CREATE")) {
            return sql;
        }
        int beginIndex = firstProcedureBegin(sql);
        if (beginIndex < 0) {
            return sql;
        }
        int cursor = skipWhitespace(sql, beginIndex + "BEGIN".length());
        List<String> declarations = new ArrayList<>();
        int declarationsEnd = cursor;
        while (startsKeyword(sql, cursor, "DECLARE")) {
            int terminator = findStatementTerminator(sql, cursor);
            if (terminator >= sql.length()) {
                break;
            }
            String declaration = convertMysqlProcedureDeclaration(sql.substring(cursor, terminator).strip());
            if (declaration == null) {
                break;
            }
            declarations.add(declaration);
            declarationsEnd = terminator + 1;
            cursor = skipWhitespace(sql, declarationsEnd);
        }
        if (declarations.isEmpty()) {
            return sql;
        }
        StringBuilder converted = new StringBuilder(sql.length());
        converted.append(sql, 0, beginIndex);
        for (String declaration : declarations) {
            converted.append("    ").append(declaration).append(";\n");
        }
        converted.append("BEGIN");
        converted.append(sql.substring(declarationsEnd));
        return converted.toString();
    }

    private String removeUnusedMysqlCursorHandlers(String sql) {
        if (!isCreateProcedureStatement(sql) || containsKeywordOutsideIgnoredText(sql, "FETCH")) {
            return sql;
        }
        String converted = replaceOutsideIgnoredText(sql, List.of(
                new TextReplacement(
                        Pattern.compile(
                                "(?is)\\s*DECLARE\\s+"
                                        + "(?:" + SQL_IDENTIFIER_TOKEN + ")"
                                        + "\\s+CURSOR\\s+FOR\\s+[^;]+;"
                        ),
                        ""
                ),
                new TextReplacement(
                        Pattern.compile(
                                "(?is)\\s*DECLARE\\s+(?:CONTINUE|EXIT)\\s+HANDLER\\s+FOR\\s+SQLSTATE\\s+'02000'\\s+SET\\s+"
                                        + "(?:" + SQL_IDENTIFIER_TOKEN + ")"
                                        + "\\s*=\\s*1\\s*;"
                        ),
                        ""
                )
        ));
        return converted;
    }

    private boolean containsKeywordOutsideIgnoredText(String sql, String keyword) {
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                index = skipBacktickIdentifier(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (startsKeyword(sql, index, keyword)) {
                return true;
            } else {
                index++;
            }
        }
        return false;
    }

    private int firstProcedureBegin(String sql) {
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                index = skipBacktickIdentifier(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (startsKeyword(sql, index, "BEGIN")) {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private String convertMysqlProcedureDeclaration(String declaration) {
        if (Pattern.compile("(?is)^DECLARE\\s+(?:CONTINUE|EXIT)\\s+HANDLER\\b").matcher(declaration).find()) {
            return null;
        }
        Matcher cursorMatcher = Pattern.compile(
                "(?is)^DECLARE\\s+(" + SQL_IDENTIFIER_TOKEN + ")\\s+CURSOR\\s+FOR\\s+(.+)$"
        ).matcher(declaration);
        if (cursorMatcher.matches()) {
            return cursorMatcher.group(1) + " CURSOR FOR " + cursorMatcher.group(2).strip();
        }
        String body = Pattern.compile("(?is)^DECLARE\\s+").matcher(declaration).replaceFirst("");
        VariableDeclarationParts parts = parseMysqlVariableDeclaration(body);
        if (parts == null) {
            return null;
        }
        List<String> converted = new ArrayList<>();
        for (String name : parts.names()) {
            converted.add(unquoteIdentifier(name) + " "
                    + parts.type()
                    + (parts.defaultValue() == null ? "" : " := " + parts.defaultValue()));
        }
        return String.join(";\n    ", converted);
    }

    private VariableDeclarationParts parseMysqlVariableDeclaration(String body) {
        int cursor = 0;
        List<String> names = new ArrayList<>();
        while (cursor < body.length()) {
            cursor = skipWhitespace(body, cursor);
            int end = simpleIdentifierEnd(body, cursor);
            if (end <= cursor) {
                return null;
            }
            String name = body.substring(cursor, end);
            if (!isSimpleIdentifier(name)) {
                return null;
            }
            names.add(name);
            cursor = skipWhitespace(body, end);
            if (cursor < body.length() && body.charAt(cursor) == ',') {
                cursor++;
                continue;
            }
            break;
        }
        String typeAndDefault = body.substring(cursor).strip();
        if (typeAndDefault.isBlank()) {
            return null;
        }
        int defaultIndex = topLevelKeywordIndex(typeAndDefault, "DEFAULT");
        String type = defaultIndex < 0 ? typeAndDefault : typeAndDefault.substring(0, defaultIndex).strip();
        String defaultValue = defaultIndex < 0
                ? null
                : typeAndDefault.substring(defaultIndex + "DEFAULT".length()).strip();
        if (type.isBlank() || (defaultValue != null && defaultValue.isBlank())) {
            return null;
        }
        return new VariableDeclarationParts(names, type, defaultValue);
    }

    private String convertMysqlProcedureUserVariables(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        int beginIndex = firstProcedureBegin(sql);
        if (beginIndex < 0) {
            return sql;
        }
        List<UserVariableReference> references = mysqlUserVariableReferences(sql);
        if (references.isEmpty() || hasUnsafeUserVariableAssignment(sql, references)) {
            return sql;
        }

        LinkedHashSet<String> existingNames = procedureNamesInScope(sql, beginIndex);
        LinkedHashMap<String, String> localNamesByUserVariable = new LinkedHashMap<>();
        for (UserVariableReference reference : references) {
            localNamesByUserVariable.computeIfAbsent(
                    reference.name(),
                    name -> uniqueProcedureUserVariableName(name, existingNames)
            );
        }

        String replaced = replaceMysqlUserVariables(sql, references, localNamesByUserVariable);
        int replacedBeginIndex = firstProcedureBegin(replaced);
        if (replacedBeginIndex < 0) {
            return sql;
        }
        StringBuilder declarations = new StringBuilder();
        for (Map.Entry<String, String> entry : localNamesByUserVariable.entrySet()) {
            declarations.append("    ")
                    .append(entry.getValue())
                    .append(" ")
                    .append(inferProcedureUserVariableType(sql, entry.getKey()))
                    .append(";\n");
        }
        return replaced.substring(0, replacedBeginIndex)
                + declarations
                + replaced.substring(replacedBeginIndex);
    }

    private boolean isCreateProcedureStatement(String sql) {
        int start = skipWhitespace(sql, 0);
        if (!startsKeyword(sql, start, "CREATE")) {
            return false;
        }
        int cursor = skipWhitespace(sql, start + "CREATE".length());
        if (startsKeyword(sql, cursor, "OR")) {
            cursor = skipWhitespace(sql, cursor + "OR".length());
            if (!startsKeyword(sql, cursor, "REPLACE")) {
                return false;
            }
            cursor = skipWhitespace(sql, cursor + "REPLACE".length());
        }
        return startsKeyword(sql, cursor, "PROCEDURE");
    }

    private List<UserVariableReference> mysqlUserVariableReferences(String sql) {
        List<UserVariableReference> references = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                index = skipBacktickIdentifier(sql, index);
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (current == '@') {
                int next = index + 1;
                if (next < sql.length()
                        && sql.charAt(next) != '@'
                        && isUserVariableStart(sql.charAt(next))) {
                    int end = next + 1;
                    while (end < sql.length() && isUserVariablePart(sql.charAt(end))) {
                        end++;
                    }
                    references.add(new UserVariableReference(index, end, sql.substring(next, end)));
                    index = end;
                } else {
                    index++;
                }
            } else {
                index++;
            }
        }
        return references;
    }

    private boolean isUserVariableStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private boolean isUserVariablePart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    private boolean hasUnsafeUserVariableAssignment(String sql, List<UserVariableReference> references) {
        for (UserVariableReference reference : references) {
            int cursor = skipWhitespace(sql, reference.end());
            if (cursor + 1 < sql.length()
                    && sql.charAt(cursor) == ':'
                    && sql.charAt(cursor + 1) == '='
                    && !statementStartsWithKeyword(sql, reference.start(), "SET")) {
                return true;
            }
        }
        return false;
    }

    private boolean statementStartsWithKeyword(String sql, int beforeIndex, String keyword) {
        int statementStart = previousStatementStart(sql, beforeIndex);
        return startsKeyword(sql, skipWhitespace(sql, statementStart), keyword);
    }

    private int previousStatementStart(String sql, int beforeIndex) {
        int cursor = Math.min(beforeIndex, sql.length()) - 1;
        while (cursor >= 0) {
            char current = sql.charAt(cursor);
            if (current == '\'') {
                cursor--;
            } else if (current == ';') {
                return cursor + 1;
            } else {
                cursor--;
            }
        }
        return 0;
    }

    private LinkedHashSet<String> procedureNamesInScope(String sql, int beginIndex) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String headerAndDeclarations = sql.substring(0, beginIndex);
        collectProcedureParameterNames(headerAndDeclarations, names);
        Matcher declarationMatcher = Pattern.compile(
                "(?im)^\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s+[^\\n;]+;"
        ).matcher(headerAndDeclarations);
        while (declarationMatcher.find()) {
            names.add(declarationMatcher.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private void collectProcedureParameterNames(String headerAndDeclarations, LinkedHashSet<String> names) {
        int procedureIndex = keywordIndex(headerAndDeclarations, "PROCEDURE");
        if (procedureIndex < 0) {
            return;
        }
        int openParen = firstTopLevelParen(headerAndDeclarations.substring(procedureIndex + "PROCEDURE".length()));
        if (openParen < 0) {
            return;
        }
        openParen += procedureIndex + "PROCEDURE".length();
        int closeParen = findMatchingParen(headerAndDeclarations, openParen);
        if (closeParen <= openParen) {
            return;
        }
        for (String parameter : splitTopLevelComma(headerAndDeclarations.substring(openParen + 1, closeParen))) {
            String name = procedureParameterName(parameter);
            if (!name.isBlank()) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
    }

    private int keywordIndex(String value, String keyword) {
        int index = 0;
        while (index < value.length()) {
            if (startsKeyword(value, index, keyword)) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private String procedureParameterName(String parameter) {
        String stripped = parameter.strip();
        Matcher modeFirstMatcher = Pattern.compile("(?is)^(INOUT|IN|OUT)\\s+(" + SQL_IDENTIFIER_TOKEN + ")\\b.*$")
                .matcher(stripped);
        if (modeFirstMatcher.matches()) {
            return unquoteIdentifier(modeFirstMatcher.group(2));
        }
        Matcher nameFirstMatcher = Pattern.compile("(?is)^(" + SQL_IDENTIFIER_TOKEN + ")\\s+(INOUT|IN|OUT)\\b.*$")
                .matcher(stripped);
        if (nameFirstMatcher.matches()) {
            return unquoteIdentifier(nameFirstMatcher.group(1));
        }
        return "";
    }

    private String uniqueProcedureUserVariableName(String userVariableName, LinkedHashSet<String> existingNames) {
        String base = normalizeIdentifierSegment(userVariableName);
        if (base.isBlank() || !Character.isLetter(base.charAt(0))) {
            base = "var_" + base;
        }
        String candidate = "dm_" + base;
        int suffix = 2;
        while (existingNames.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = "dm_" + base + "_" + suffix;
            suffix++;
        }
        existingNames.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private String replaceMysqlUserVariables(
            String sql,
            List<UserVariableReference> references,
            Map<String, String> localNamesByUserVariable
    ) {
        StringBuilder converted = new StringBuilder(sql.length());
        int cursor = 0;
        for (UserVariableReference reference : references) {
            converted.append(sql, cursor, reference.start());
            converted.append(localNamesByUserVariable.get(reference.name()));
            cursor = reference.end();
        }
        converted.append(sql.substring(cursor));
        return converted.toString();
    }

    private String inferProcedureUserVariableType(String sql, String userVariableName) {
        if (hasLargeStringAssignment(sql, userVariableName)) {
            return "CLOB";
        }
        if (hasNumericUserVariableContext(sql, userVariableName)
                || hasNumericUserVariableName(userVariableName)) {
            return "BIGINT";
        }
        return "VARCHAR(4000)";
    }

    private boolean hasLargeStringAssignment(String sql, String userVariableName) {
        for (UserVariableReference reference : mysqlUserVariableReferences(sql)) {
            if (!reference.name().equals(userVariableName)) {
                continue;
            }
            int cursor = skipWhitespace(sql, reference.end());
            if (cursor + 1 < sql.length() && sql.charAt(cursor) == ':' && sql.charAt(cursor + 1) == '=') {
                cursor += 2;
            } else if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                cursor++;
            } else {
                continue;
            }
            cursor = skipWhitespace(sql, cursor);
            if (cursor < sql.length() && sql.charAt(cursor) == '\'') {
                int end = skipSingleQuotedString(sql, cursor);
                if (end - cursor > 3500) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasNumericUserVariableContext(String sql, String userVariableName) {
        for (UserVariableReference reference : mysqlUserVariableReferences(sql)) {
            if (!reference.name().equals(userVariableName)) {
                continue;
            }
            String statement = statementContaining(sql, reference.start());
            if (Pattern.compile("(?is)\\bCOUNT\\s*\\(").matcher(statement).find()
                    && Pattern.compile("(?is)\\bINTO\\s*@" + Pattern.quote(userVariableName) + "\\b")
                    .matcher(statement)
                    .find()) {
                return true;
            }
            if (isNumericAssignmentExpression(sql, reference)) {
                return true;
            }
            if (isArithmeticUserVariableUse(sql, reference) || isNumericComparison(sql, reference)) {
                return true;
            }
        }
        return false;
    }

    private String statementContaining(String sql, int index) {
        int start = previousStatementStart(sql, index);
        int end = findStatementTerminator(sql, index);
        return sql.substring(start, end);
    }

    private boolean isNumericAssignmentExpression(String sql, UserVariableReference reference) {
        int cursor = skipWhitespace(sql, reference.end());
        if (cursor + 1 < sql.length() && sql.charAt(cursor) == ':' && sql.charAt(cursor + 1) == '=') {
            cursor += 2;
        } else if (cursor < sql.length() && sql.charAt(cursor) == '=') {
            cursor++;
        } else {
            return false;
        }
        String expression = sql.substring(skipWhitespace(sql, cursor), findStatementTerminator(sql, cursor));
        return Pattern.compile("(?is)^\\(?\\s*SELECT\\s+COUNT\\s*\\(").matcher(expression).find()
                || Pattern.compile("(?is)^COUNT\\s*\\(").matcher(expression).find()
                || Pattern.compile("(?is)^[-+]?\\d+(?:\\.\\d+)?\\b").matcher(expression.strip()).find();
    }

    private boolean isArithmeticUserVariableUse(String sql, UserVariableReference reference) {
        int before = previousNonWhitespace(sql, reference.start() - 1);
        int after = skipWhitespace(sql, reference.end());
        return (before >= 0 && "+-*/%".indexOf(sql.charAt(before)) >= 0)
                || (after < sql.length() && "+-*/%".indexOf(sql.charAt(after)) >= 0);
    }

    private boolean isNumericComparison(String sql, UserVariableReference reference) {
        int cursor = skipWhitespace(sql, reference.end());
        if (cursor >= sql.length()) {
            return false;
        }
        if (sql.charAt(cursor) == '<' || sql.charAt(cursor) == '>') {
            return true;
        }
        if (sql.charAt(cursor) == '=' || sql.charAt(cursor) == '!') {
            cursor++;
            if (cursor < sql.length() && sql.charAt(cursor) == '=') {
                cursor++;
            }
            cursor = skipWhitespace(sql, cursor);
            return cursor < sql.length() && Character.isDigit(sql.charAt(cursor));
        }
        return false;
    }

    private int previousNonWhitespace(String value, int index) {
        int cursor = index;
        while (cursor >= 0 && Character.isWhitespace(value.charAt(cursor))) {
            cursor--;
        }
        return cursor;
    }

    private boolean hasNumericUserVariableName(String userVariableName) {
        String lower = userVariableName.toLowerCase(Locale.ROOT);
        return lower.startsWith("has_")
                || lower.startsWith("is_")
                || lower.endsWith("_count")
                || lower.endsWith("_num")
                || lower.endsWith("_ver")
                || lower.endsWith("_index")
                || lower.equals("count")
                || lower.equals("rc")
                || lower.contains("count")
                || lower.contains("orderindex")
                || lower.contains("script_menu_ver")
                || lower.contains("menu_ver");
    }

    private int simpleIdentifierEnd(String value, int index) {
        if (index >= value.length()) {
            return -1;
        }
        if (value.charAt(index) == '`') {
            return skipBacktickIdentifier(value, index);
        }
        if (value.charAt(index) == '"') {
            return skipDoubleQuotedText(value, index);
        }
        int cursor = index;
        while (cursor < value.length() && isIdentifierPart(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int topLevelKeywordIndex(String value, String keyword) {
        int index = 0;
        int depth = 0;
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
            } else if (depth == 0 && startsKeyword(value, index, keyword)) {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private int firstTopLevelParen(String value) {
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
                return index;
            } else {
                index++;
            }
        }
        return -1;
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

    private String normalizeMysqlJsonEscapesInSqlStringLiterals(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                SingleQuotedStringRewrite rewrite = normalizeMysqlJsonEscapesInSqlStringLiteral(sql, index);
                converted.append(rewrite.value());
                changed = changed || rewrite.changed();
                index = rewrite.endIndex();
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
                converted.append(current);
                index++;
            }
        }
        return changed ? converted.toString() : sql;
    }

    private SingleQuotedStringRewrite normalizeMysqlJsonEscapesInSqlStringLiteral(String sql, int start) {
        StringBuilder rawContent = new StringBuilder();
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\\') {
                rawContent.append(current);
                if (index + 1 < sql.length()) {
                    rawContent.append(sql.charAt(index + 1));
                    index += 2;
                } else {
                    index++;
                }
            } else if (current == '\'' && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                rawContent.append('\'');
                index += 2;
            } else if (current == '\'') {
                int end = index + 1;
                String raw = rawContent.toString();
                String decoded = decodeMysqlBackslashEscapedString(raw);
                if (!decoded.equals(raw) && isJsonText(decoded)) {
                    return new SingleQuotedStringRewrite(sqlStringLiteral(decoded), end, true);
                }
                return new SingleQuotedStringRewrite(sql.substring(start, end), end, false);
            } else {
                rawContent.append(current);
                index++;
            }
        }
        return new SingleQuotedStringRewrite(sql.substring(start), sql.length(), false);
    }

    private String decodeMysqlBackslashEscapedString(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                decoded.append(current);
                index++;
                continue;
            }
            char next = value.charAt(index + 1);
            switch (next) {
                case '0' -> decoded.append('\0');
                case 'b' -> decoded.append('\b');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'Z' -> decoded.append((char) 26);
                default -> decoded.append(next);
            }
            index += 2;
        }
        return decoded.toString();
    }

    private boolean isJsonText(String value) {
        String stripped = value.strip();
        if (stripped.isEmpty()
                || !(stripped.startsWith("{") || stripped.startsWith("["))
                || !(stripped.endsWith("}") || stripped.endsWith("]"))) {
            return false;
        }
        try {
            JSON_MAPPER.readTree(stripped);
            return true;
        } catch (IOException e) {
            return false;
        }
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

        Map<String, String> variableNames = procedureVariableNamesByLowercase(sql);
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
                List<ProcedureStatement> ddlStatements = convertProcedureDdlStatements(sql.substring(index, end).strip());
                for (int i = 0; i < ddlStatements.size(); i++) {
                    if (i > 0) {
                        converted.append("\n");
                    }
                    ProcedureStatement statement = ddlStatements.get(i);
                    if (statement.dynamic()) {
                        converted.append("EXECUTE IMMEDIATE ")
                                .append(dynamicSqlExpression(statement.sql(), variableNames));
                    } else {
                        converted.append(statement.sql());
                    }
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

    private Map<String, String> procedureVariableNamesByLowercase(String sql) {
        int beginIndex = firstProcedureBegin(sql);
        if (beginIndex < 0) {
            return Map.of();
        }
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        String headerAndDeclarations = sql.substring(0, beginIndex);
        collectProcedureParameterNames(headerAndDeclarations, names);
        Matcher declarationMatcher = Pattern.compile(
                "(?im)^\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s+[^\\n;]+;"
        ).matcher(headerAndDeclarations);
        while (declarationMatcher.find()) {
            String name = declarationMatcher.group(1);
            names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
        return names;
    }

    private void collectProcedureParameterNames(String headerAndDeclarations, LinkedHashMap<String, String> names) {
        int procedureIndex = keywordIndex(headerAndDeclarations, "PROCEDURE");
        if (procedureIndex < 0) {
            return;
        }
        int openParen = firstTopLevelParen(headerAndDeclarations.substring(procedureIndex + "PROCEDURE".length()));
        if (openParen < 0) {
            return;
        }
        openParen += procedureIndex + "PROCEDURE".length();
        int closeParen = findMatchingParen(headerAndDeclarations, openParen);
        if (closeParen <= openParen) {
            return;
        }
        for (String parameter : splitTopLevelComma(headerAndDeclarations.substring(openParen + 1, closeParen))) {
            String name = procedureParameterName(parameter);
            if (!name.isBlank()) {
                names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            }
        }
    }

    private String dynamicSqlExpression(String ddl, Map<String, String> variableNames) {
        if (variableNames.isEmpty()) {
            return sqlStringLiteral(ddl);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int index = 0;
        while (index < ddl.length()) {
            char current = ddl.charAt(index);
            if (current == '\'') {
                int end = skipSingleQuotedString(ddl, index);
                literal.append(ddl, index, end);
                index = end;
            } else if (current == '"') {
                int end = skipDoubleQuotedText(ddl, index);
                literal.append(ddl, index, end);
                index = end;
            } else if (current == '`') {
                int end = skipBacktickIdentifier(ddl, index);
                literal.append(ddl, index, end);
                index = end;
            } else if (startsLineComment(ddl, index)) {
                int end = skipUntilLineEnd(ddl, index);
                literal.append(ddl, index, end);
                index = end;
            } else if (startsBlockComment(ddl, index)) {
                int end = skipUntilBlockCommentEnd(ddl, index);
                literal.append(ddl, index, end);
                index = end;
            } else if (isUserVariableStart(current)) {
                int end = index + 1;
                while (end < ddl.length() && isUserVariablePart(ddl.charAt(end))) {
                    end++;
                }
                String token = ddl.substring(index, end);
                String variableName = variableNames.get(token.toLowerCase(Locale.ROOT));
                if (variableName == null) {
                    literal.append(token);
                } else {
                    addSqlLiteralPart(parts, literal);
                    parts.add(sqlQuotedVariableExpression(variableName));
                }
                index = end;
            } else {
                literal.append(current);
                index++;
            }
        }
        addSqlLiteralPart(parts, literal);
        if (parts.isEmpty()) {
            return sqlStringLiteral(ddl);
        }
        return String.join(" || ", parts);
    }

    private void addSqlLiteralPart(List<String> parts, StringBuilder literal) {
        if (!literal.isEmpty()) {
            parts.add(sqlStringLiteral(literal.toString()));
            literal.setLength(0);
        }
    }

    private String sqlStringLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String sqlQuotedVariableExpression(String variableName) {
        return "'''' || IFNULL(REPLACE(CAST(" + variableName + " AS VARCHAR(4000)), '''', ''''''), '') || ''''";
    }

    private List<ProcedureStatement> convertProcedureDdlStatements(String ddl) {
        String converted = removeMysqlTemporaryKeyword(ddl);
        List<ProcedureStatement> temporaryDropTables = convertTemporaryDropTableToDml(converted);
        if (!temporaryDropTables.isEmpty()) {
            return temporaryDropTables;
        }
        ProcedureStatement temporaryTruncateTable = convertTemporaryTruncateTableToDml(converted);
        if (temporaryTruncateTable != null) {
            return List.of(temporaryTruncateTable);
        }
        ProcedureStatement temporaryCreateTableSelect = convertTemporaryCreateTableSelectToInsert(converted);
        if (temporaryCreateTableSelect != null) {
            return List.of(temporaryCreateTableSelect);
        }
        List<String> dropTables = splitMysqlDropTables(converted);
        if (!dropTables.isEmpty()) {
            return dropTables.stream().map(ProcedureStatement::dynamicSql).toList();
        }
        converted = convertMysqlCreateTableSelect(converted);
        converted = convertMysqlAlterTableAddIndex(converted);
        ProcedureStatement temporaryIndexDdl = convertTemporaryIndexDdlToNoop(converted);
        if (temporaryIndexDdl != null) {
            return List.of(temporaryIndexDdl);
        }
        converted = normalizeCreateIndexForDm(converted);
        temporaryIndexDdl = convertTemporaryIndexDdlToNoop(converted);
        if (temporaryIndexDdl != null) {
            return List.of(temporaryIndexDdl);
        }
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
        return splitMultiModifyAlterTable(converted).stream().map(ProcedureStatement::dynamicSql).toList();
    }

    private List<ProcedureStatement> convertTemporaryDropTableToDml(String ddl) {
        Matcher matcher = Pattern.compile("(?is)^DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(.+)$").matcher(ddl.strip());
        if (!matcher.matches()) {
            return List.of();
        }
        List<String> tables = splitTopLevelComma(matcher.group(1));
        List<ProcedureStatement> statements = new ArrayList<>();
        for (String table : tables) {
            String tableName = table.strip();
            if (tableName.isBlank() || !isProcedureTemporaryTableName(tableName)) {
                return List.of();
            }
            statements.add(ProcedureStatement.directSql("DELETE FROM " + tableName));
        }
        return statements;
    }

    private ProcedureStatement convertTemporaryTruncateTableToDml(String ddl) {
        Matcher matcher = Pattern.compile("(?is)^TRUNCATE\\s+(?:TABLE\\s+)?(" + SQL_IDENTIFIER_TOKEN + ")\\s*$")
                .matcher(ddl.strip());
        if (!matcher.matches() || !isProcedureTemporaryTableName(matcher.group(1))) {
            return null;
        }
        return ProcedureStatement.directSql("DELETE FROM " + matcher.group(1).strip());
    }

    private ProcedureStatement convertTemporaryCreateTableSelectToInsert(String ddl) {
        Matcher matcher = Pattern.compile(
                "(?is)^(\\s*CREATE\\s+TABLE\\s+)(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + SQL_IDENTIFIER_TOKEN
                        + ")\\s+(?:AS\\s+)?SELECT\\b(.*)$"
        ).matcher(ddl);
        if (!matcher.matches() || !isProcedureTemporaryTableName(matcher.group(2))) {
            return null;
        }
        String selectTail = matcher.group(3);
        String columnList = insertColumnListForCreateTableSelect(selectTail);
        return ProcedureStatement.directSql("INSERT INTO "
                + matcher.group(2).strip()
                + columnList
                + " SELECT"
                + selectTail);
    }

    private String insertColumnListForCreateTableSelect(String selectTail) {
        int fromIndex = topLevelKeywordIndex(selectTail, "FROM");
        String selectList = fromIndex < 0 ? selectTail : selectTail.substring(0, fromIndex);
        List<String> columns = selectListColumns(selectList);
        if (columns.isEmpty()) {
            return "";
        }
        return " (" + String.join(", ", columns) + ")";
    }

    private ProcedureStatement convertTemporaryIndexDdlToNoop(String ddl) {
        Matcher createIndexMatcher = Pattern.compile(
                "(?is)^CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+\\S+\\s+ON\\s+(?<table>`[^`]+`|\"[^\"]+\"|[^\\s(]+).*$"
        ).matcher(ddl.strip());
        if (createIndexMatcher.matches() && isProcedureTemporaryTableName(createIndexMatcher.group("table"))) {
            return ProcedureStatement.directSql("NULL");
        }
        Matcher dropIndexMatcher = Pattern.compile(
                "(?is)^DROP\\s+INDEX\\s+\\S+\\s+ON\\s+(?<table>`[^`]+`|\"[^\"]+\"|[^\\s(]+)\\s*$"
        ).matcher(ddl.strip());
        if (dropIndexMatcher.matches() && isProcedureTemporaryTableName(dropIndexMatcher.group("table"))) {
            return ProcedureStatement.directSql("NULL");
        }
        return null;
    }

    private boolean isProcedureTemporaryTableName(String tableToken) {
        String tableName = unquoteIdentifier(lastIdentifierPart(tableToken.strip()));
        return tableName.toLowerCase(Locale.ROOT).startsWith("tmp_");
    }

    private String convertMysqlCreateTableSelect(String ddl) {
        Matcher matcher = Pattern.compile(
                "(?is)^(\\s*CREATE\\s+TABLE\\s+)(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + SQL_IDENTIFIER_TOKEN
                        + ")\\s+(?:AS\\s+)?SELECT\\b(.*)$"
        ).matcher(ddl);
        if (!matcher.matches()) {
            return ddl;
        }
        return matcher.group(1) + matcher.group(2) + " AS SELECT" + matcher.group(3);
    }

    private String removeMysqlTemporaryKeyword(String ddl) {
        return Pattern.compile("(?is)^(\\s*(?:CREATE|DROP)\\s+)TEMPORARY\\s+(TABLE\\b)")
                .matcher(ddl)
                .replaceFirst("$1$2");
    }

    private List<String> splitMysqlDropTables(String ddl) {
        Matcher matcher = Pattern.compile("(?is)^DROP\\s+TABLE\\s+IF\\s+EXISTS\\s+(.+)$").matcher(ddl.strip());
        if (!matcher.matches()) {
            return List.of();
        }
        List<String> tables = splitTopLevelComma(matcher.group(1));
        if (tables.size() <= 1) {
            return List.of();
        }
        return tables.stream()
                .map(String::strip)
                .filter(table -> !table.isBlank())
                .map(table -> "DROP TABLE IF EXISTS " + table)
                .toList();
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
            if (startsKeyword(sql, cursor, "TEMPORARY")) {
                cursor = skipWhitespace(sql, cursor + "TEMPORARY".length());
            }
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

    private record SingleQuotedStringRewrite(String value, int endIndex, boolean changed) {
    }

    private record ProcedureTempTableColumn(String name, String type) {
    }

    private record ProcedureStatement(String sql, boolean dynamic) {
        static ProcedureStatement dynamicSql(String sql) {
            return new ProcedureStatement(sql, true);
        }

        static ProcedureStatement directSql(String sql) {
            return new ProcedureStatement(sql, false);
        }
    }

    private record VariableDeclarationParts(List<String> names, String type, String defaultValue) {
        private VariableDeclarationParts {
            names = List.copyOf(names == null ? List.of() : names);
        }
    }

    private record UserVariableReference(int start, int end, String name) {
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
