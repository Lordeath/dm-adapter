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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

class SqlScriptMigrator {
    private static final Path DEFAULT_PRESERVED_SQL_PATH = Path.of("00000000.sql");
    private static final int PARALLEL_PROCEDURE_MIN_CHARS = 50_000;
    private static final String CONVERSION_THREADS_PROPERTY = "dm.adapter.sqlScriptConversionThreads";
    static final String MYSQL_CREATE_DEFINER_REMOVAL_RULE = "MYSQL_CREATE_DEFINER_REMOVED";
    static final String MYSQL_CREATE_PROCEDURE_TO_DM_RULE = "MYSQL_CREATE_PROCEDURE_TO_DM";
    static final String MYSQL_CREATE_FUNCTION_TO_DM_RULE = "MYSQL_CREATE_FUNCTION_TO_DM";
    static final String MYSQL_SCRIPT_METADATA_TO_DM_RULE = "MYSQL_SCRIPT_METADATA_TO_DM";
    static final String MYSQL_SCRIPT_COMMENT_CLAUSE_REMOVAL_RULE = "MYSQL_SCRIPT_COMMENT_CLAUSE_REMOVED";
    static final String MYSQL_SCHEMA_SCOPED_INDEX_NAME_RULE = "MYSQL_SCHEMA_SCOPED_INDEX_NAME";
    static final String MYSQL_PREFIX_INDEX_MANUAL_REVIEW_RULE =
            "MYSQL_PREFIX_INDEX_MANUAL_REVIEW";
    static final String MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE_RULE =
            "MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE";
    static final String MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL_RULE =
            "MYSQL_PROCEDURE_USER_VARIABLE_TO_LOCAL";
    static final String MYSQL_PROCEDURE_LOCAL_SET_TO_ASSIGNMENT_RULE =
            "MYSQL_PROCEDURE_LOCAL_SET_TO_ASSIGNMENT";
    static final String MYSQL_PROCEDURE_JSON_TEXT_TYPE_RULE =
            "MYSQL_PROCEDURE_JSON_TEXT_TYPE";
    static final String DM_METADATA_IDENTIFIER_CASE_RULE = "DM_METADATA_IDENTIFIER_CASE";
    static final String DM_METADATA_OWNER_TARGET_SCHEMA_RULE = "DM_METADATA_OWNER_TARGET_SCHEMA";
    static final String MYSQL_PROCEDURE_IF_EXISTS_TO_COUNT_RULE =
            "MYSQL_PROCEDURE_IF_EXISTS_TO_COUNT";
    static final String MYSQL_PROCEDURE_TEMP_TABLE_COMPILE_PLACEHOLDER_RULE =
            "MYSQL_PROCEDURE_TEMP_TABLE_COMPILE_PLACEHOLDER";
    static final String MYSQL_SQL_STRING_JSON_ESCAPE_TO_DM_RULE =
            "MYSQL_SQL_STRING_JSON_ESCAPE_TO_DM";
    static final String MYSQL_FOREIGN_KEY_CHECKS_NOOP_RULE =
            "MYSQL_FOREIGN_KEY_CHECKS_NOOP";
    static final String MYSQL_SCRIPT_USER_VARIABLE_LITERAL_RULE =
            "MYSQL_SCRIPT_USER_VARIABLE_LITERAL";
    static final String MYSQL_SCRIPT_DYNAMIC_DDL_TO_EXECUTE_IMMEDIATE_RULE =
            "MYSQL_SCRIPT_DYNAMIC_DDL_TO_EXECUTE_IMMEDIATE";
    static final String MYSQL_DROP_PROCEDURE_IF_EXISTS_RULE =
            "MYSQL_DROP_PROCEDURE_IF_EXISTS";
    static final String MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE =
            "MYSQL_TEMPORARY_TABLE_AS_SELECT_TO_DM";
    static final String MYSQL_TEMPORARY_INDEX_NOOP_RULE =
            "MYSQL_TEMPORARY_INDEX_NOOP";
    static final String MYSQL_PROCEDURE_CURSOR_HANDLER_TO_LOOP_RULE =
            "MYSQL_PROCEDURE_CURSOR_HANDLER_TO_LOOP";
    static final String MYSQL_PROCEDURE_DYNAMIC_PREPARE_TO_EXECUTE_IMMEDIATE_RULE =
            "MYSQL_PROCEDURE_DYNAMIC_PREPARE_TO_EXECUTE_IMMEDIATE";
    static final String MYSQL_PROCEDURE_SIGNAL_TO_RAISE_APPLICATION_ERROR_RULE =
            "MYSQL_PROCEDURE_SIGNAL_TO_RAISE_APPLICATION_ERROR";
    static final String MYSQL_PROCEDURE_SESSION_SET_NOOP_RULE =
            "MYSQL_PROCEDURE_SESSION_SET_NOOP";
    static final String MYSQL_CALL_ARGUMENT_LINE_COMMENT_REMOVAL_RULE =
            "MYSQL_CALL_ARGUMENT_LINE_COMMENT_REMOVED";
    static final String MYSQL_SIMPLE_DATE_END_TRIGGER_TO_DM_RULE =
            "MYSQL_SIMPLE_DATE_END_TRIGGER_TO_DM";
    static final String MYSQL_PROCEDURE_LOCAL_LABEL_TO_RETURN_RULE =
            "MYSQL_PROCEDURE_LOCAL_LABEL_TO_RETURN";
    static final String MYSQL_PROCEDURE_OBJECT_NAME_CONFLICT_RENAME_RULE =
            "MYSQL_PROCEDURE_OBJECT_NAME_CONFLICT_RENAMED";
    static final String MYSQL_SYSTEM_METADATA_SCALAR_ID_TO_MIN_RULE =
            "MYSQL_SYSTEM_METADATA_SCALAR_ID_TO_MIN";
    static final String DM_LONG_CLOB_CALL_ARGUMENT_BLOCK_RULE =
            "DM_LONG_CLOB_CALL_ARGUMENT_BLOCK";
    static final String DM_PROCEDURE_CLOB_EMPTY_STRING_CHECK_RULE =
            "DM_PROCEDURE_CLOB_EMPTY_STRING_CHECK";
    static final String MYSQL_PROCEDURE_CONTROL_FLOW_TO_DM_RULE =
            "MYSQL_PROCEDURE_CONTROL_FLOW_TO_DM";
    static final String MYSQL_PROCEDURE_IDENTIFIER_TO_DM_RULE =
            "MYSQL_PROCEDURE_IDENTIFIER_TO_DM";
    static final String MYSQL_ALTER_MODIFY_COLUMN_TO_DM_RULE =
            "MYSQL_ALTER_MODIFY_COLUMN_TO_DM";
    static final String MYSQL_PROCEDURE_INSERT_IGNORE_TEMP_TO_MERGE_RULE =
            "MYSQL_PROCEDURE_INSERT_IGNORE_TEMP_TO_MERGE";
    static final String MYSQL_CREATE_TABLE_INLINE_KEY_REMOVAL_RULE =
            "MYSQL_CREATE_TABLE_INLINE_KEY_REMOVED";
    static final String MYSQL_PROCEDURE_ASSIGNED_IN_PARAM_TO_LOCAL_RULE =
            "MYSQL_PROCEDURE_ASSIGNED_IN_PARAM_TO_LOCAL";
    static final String MYSQL_PROCEDURE_DYNAMIC_INSERT_IGNORE_TO_MERGE_RULE =
            "MYSQL_PROCEDURE_DYNAMIC_INSERT_IGNORE_TO_MERGE";
    static final String MYSQL_PROCEDURE_QUOTE_TO_DM_RULE =
            "MYSQL_PROCEDURE_QUOTE_TO_DM";
    static final String MYSQL_PROCEDURE_JSON_TIMESTAMP_TO_CHAR_RULE =
            "MYSQL_PROCEDURE_JSON_TIMESTAMP_TO_CHAR";
    static final String MYSQL_PROCEDURE_DATE_TIME_TO_DM_RULE =
            "MYSQL_PROCEDURE_DATE_TIME_TO_DM";
    static final String MYSQL_PROCEDURE_GROUP_BY_ALIAS_RULE =
            "MYSQL_PROCEDURE_GROUP_BY_ALIAS";
    static final String MYSQL_PROCEDURE_MISSING_SYS_TIME_RULE =
            "MYSQL_PROCEDURE_MISSING_SYS_TIME";
    static final String MYSQL_PROCEDURE_DELETE_ALIAS_STAR_RULE =
            "MYSQL_PROCEDURE_DELETE_ALIAS_STAR_TO_DM";
    static final String MYSQL_PROCEDURE_RESERVED_CURSOR_RENAME_RULE =
            "MYSQL_PROCEDURE_RESERVED_CURSOR_RENAMED";
    static final String MYSQL_INSERT_VALUES_COLUMN_LIST_RULE =
            "MYSQL_INSERT_VALUES_COLUMN_LIST";
    static final String MYSQL_INSERT_NULL_IDENTITY_VALUES_COLUMN_LIST_RULE =
            "MYSQL_INSERT_NULL_IDENTITY_VALUES_COLUMN_LIST";
    static final String MYSQL_INSERT_EXPLICIT_IDENTITY_VALUES_COLUMN_LIST_RULE =
            "MYSQL_INSERT_EXPLICIT_IDENTITY_VALUES_COLUMN_LIST";
    static final String DM_RESOURCECOLUMN_INSERT_ONLY_MERGE_RULE =
            "DM_RESOURCECOLUMN_INSERT_ONLY_MERGE";
    static final String ORIGINAL_SQL_DANGLING_INSERT_VALUES_REASON =
            "原始 SQL 语法缺陷：INSERT ... VALUES 的最后一个值元组后面仍然是逗号，后续直接进入 END/END IF；"
                    + "这不是达梦语法转换问题。建议修原始脚本：把最后一个 values 元组后的逗号改成分号，"
                    + "或补齐缺失的 values 元组。";

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
    private static final Pattern MALFORMED_LENGTH_COMPARISON_PATTERN = Pattern.compile(
            "(?is)\\b(?:CHARACTER_MAXIMUM_LENGTH|CHAR_LENGTH)\\s*(?:<=|>=|<|>)\\s*\\d+\\s*=\\s*\\d+\\b"
    );
    private static final Pattern COLUMN_TYPE_GUARD_PATTERN = Pattern.compile(
            "(?is)\\b(?:DATA_TYPE|COLUMN_TYPE)\\b"
    );
    private static final Pattern VARCHAR_MODIFY_PATTERN = Pattern.compile(
            "(?is)\\bALTER\\s+TABLE\\b(?:(?!;).)*?\\bMODIFY(?:\\s+COLUMN)?\\b(?:(?!;).)*?"
                    + "\\b(?:VAR)?CHAR\\s*\\(\\s*(\\d+)\\s*\\)"
    );
    private static final Pattern SYSTEM_METADATA_SCALAR_ID_SUBQUERY_PATTERN = Pattern.compile(
            "(?is)\\(\\s*SELECT\\s+(?:`id`|\"id\"|id)\\s+from\\s+"
                    + "((?:`ns_core_(?:resourcetable|funcinfo|resourcetree)`)"
                    + "|(?:\"ns_core_(?:resourcetable|funcinfo|resourcetree)\")"
                    + "|(?:ns_core_(?:resourcetable|funcinfo|resourcetree)))"
                    + "(?=\\s|\\)|;)"
    );
    private static final String SQL_SIMPLE_IDENTIFIER_TOKEN = "`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*";
    private static final String SQL_IDENTIFIER_TOKEN = "`[^`]+`|\"[^\"]+\"|[^\\s(]+";
    private static final String SQL_STRING_LITERAL_TOKEN = "'(?:''|\\\\.|[^'])*'";
    private static final Pattern CONVERTED_SIMPLE_DATE_END_TRIGGER_PATTERN = Pattern.compile(
            "(?is)^\\s*CREATE\\s+OR\\s+REPLACE\\s+TRIGGER\\s+" + SQL_IDENTIFIER_TOKEN + "\\s+"
                    + "BEFORE\\s+(?:INSERT|UPDATE)\\s+ON\\s+" + SQL_IDENTIFIER_TOKEN + "\\s+"
                    + "FOR\\s+EACH\\s+ROW\\b"
    );
    private static final Pattern MYSQL_PREFIX_INDEX_DDL_PATTERN = Pattern.compile(
            "(?is)\\b(?:"
                    + "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:" + SQL_IDENTIFIER_TOKEN + ")\\s+ON\\s+"
                    + "(?:" + SQL_IDENTIFIER_TOKEN + ")"
                    + "|ALTER\\s+TABLE\\s+(?:" + SQL_IDENTIFIER_TOKEN
                    + ")\\s+ADD\\s+(?:UNIQUE\\s+)?(?:INDEX|KEY)\\b"
                    + ")(?:(?!;).)*?"
                    + "(?:" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*\\(\\s*(?<length>\\d+)\\s*\\)"
    );
    private static final Pattern INDEX_DDL_AFTER_CONVERSION_PATTERN = Pattern.compile(
            "(?is)\\bCREATE\\s+(?:UNIQUE\\s+)?INDEX\\b"
                    + "|\\bALTER\\s+TABLE\\b(?:(?!;).)*?\\bADD\\s+(?:UNIQUE\\s+)?(?:INDEX|KEY)\\b"
    );
    private static final Pattern MYSQL_PREFIX_INDEX_VARCHAR_GUARD_PATTERN = Pattern.compile(
            "(?is)\\b(?:UPPER\\s*\\(\\s*)?DATA_TYPE\\s*\\)?\\s+IN\\s*\\(\\s*'CHAR'\\s*,\\s*'VARCHAR'\\s*\\)"
                    + "|\\bDATA_TYPE\\s+IN\\s*\\(\\s*'char'\\s*,\\s*'varchar'\\s*\\)"
                    + "|\\bDATA_TYPE\\s+IN\\s*\\(\\s*'varchar'\\s*,\\s*'char'\\s*\\)"
    );
    private static final String MYSQL_PREFIX_INDEX_MANUAL_REVIEW_REASON =
            "MySQL 前缀索引长度较大（如 column(254)）时，无法可靠自动转换为达梦索引。"
                    + "如果目标字段是 TEXT/CLOB，达梦不能直接建普通索引；"
                    + "请按业务确认是否改字段类型、改为函数/虚拟列索引，或移除该索引。";
    private static final int MYSQL_LONG_PREFIX_INDEX_LENGTH_THRESHOLD = 128;
    private static final String SQL_WS_OR_COMMENT_TOKEN =
            "(?:(?:\\s+)|(?:--[^\\r\\n]*(?:\\r?\\n|\\r|$))|(?:#[^\\r\\n]*(?:\\r?\\n|\\r|$))|(?:/\\*.*?\\*/))*";
    private static final Pattern CLOB_EMPTY_STRING_COMPARISON_PATTERN = Pattern.compile(
            "(?is)(?<left>" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*(?:<>|!=)\\s*''"
    );
    private static final int DM_CLOB_CALL_LITERAL_THRESHOLD = 3000;
    private static final int DM_CLOB_CALL_LITERAL_CHUNK_BYTES = 900;
    private static final Map<String, Set<Integer>> DM_CALL_CLOB_ARGUMENT_INDEXES = Map.of(
            "addall_ns_report_management_20240314", Set.of(7, 8)
    );
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final List<ProcedureTempTableColumn> PROCEDURE_TEMP_TABLE_DEFAULT_COLUMNS = List.of(
            new ProcedureTempTableColumn("enterprise_id", "BIGINT"),
            new ProcedureTempTableColumn("organization_id", "BIGINT"),
            new ProcedureTempTableColumn("roleid", "VARCHAR(200)")
    );
    private static final Set<String> PROCEDURE_SYS_TIME_TABLES = Set.of(
            "ns_core_funcinfo",
            "ns_core_menu",
            "ns_core_resourcebutton",
            "ns_core_resourcecolumn",
            "ns_core_resourcefield"
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
                    "(SELECT I.OWNER, I.TABLE_NAME, I.INDEX_NAME, "
                            + "CASE WHEN I.UNIQUENESS = 'UNIQUE' THEN 0 ELSE 1 END AS NON_UNIQUE, "
                            + "C.COLUMN_NAME, C.COLUMN_POSITION AS SEQ_IN_INDEX "
                            + "FROM ALL_INDEXES I "
                            + "JOIN ALL_IND_COLUMNS C "
                            + "ON C.INDEX_OWNER = I.OWNER "
                            + "AND C.INDEX_NAME = I.INDEX_NAME "
                            + "AND C.TABLE_OWNER = I.TABLE_OWNER "
                            + "AND C.TABLE_NAME = I.TABLE_NAME)"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\binformation_schema\\s*\\.\\s*tables\\b"),
                    "ALL_TABLES"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\binformation_schema\\s*\\.\\s*views\\b"),
                    "(SELECT OWNER, OWNER AS TABLE_SCHEMA, VIEW_NAME AS TABLE_NAME FROM ALL_VIEWS)"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\binformation_schema\\s*\\.\\s*table_constraints\\b"),
                    "(SELECT OWNER, OWNER AS CONSTRAINT_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, "
                            + "CASE CONSTRAINT_TYPE "
                            + "WHEN 'R' THEN 'FOREIGN KEY' "
                            + "WHEN 'P' THEN 'PRIMARY KEY' "
                            + "WHEN 'U' THEN 'UNIQUE' "
                            + "ELSE CONSTRAINT_TYPE END AS CONSTRAINT_TYPE FROM ALL_CONSTRAINTS)"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\binformation_schema\\s*\\.\\s*schemata\\b"),
                    "(SELECT USERNAME AS SCHEMA_NAME FROM ALL_USERS)"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\binformation_schema\\s*\\.\\s*key_column_usage\\b"),
                    "(SELECT C.OWNER, C.TABLE_NAME, C.CONSTRAINT_NAME, CC.COLUMN_NAME, "
                            + "RC.TABLE_NAME AS REFERENCED_TABLE_NAME "
                            + "FROM ALL_CONSTRAINTS C "
                            + "LEFT JOIN ALL_CONS_COLUMNS CC "
                            + "ON CC.OWNER = C.OWNER AND CC.CONSTRAINT_NAME = C.CONSTRAINT_NAME "
                            + "LEFT JOIN ALL_CONSTRAINTS RC "
                            + "ON RC.OWNER = C.R_OWNER AND RC.CONSTRAINT_NAME = C.R_CONSTRAINT_NAME "
                            + "WHERE C.CONSTRAINT_TYPE = 'R')"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\bselect\\s+column_name\\s+from\\s+all_indexes\\b"),
                    "SELECT INDEX_NAME FROM ALL_INDEXES"
            ),
            new TextReplacement(Pattern.compile("(?is)\\btable_schema\\b"), "OWNER"),
            new TextReplacement(Pattern.compile("(?is)\\btable_name\\b"), "TABLE_NAME"),
            new TextReplacement(Pattern.compile("(?is)\\bcolumn_name\\b"), "COLUMN_NAME"),
            new TextReplacement(
                    Pattern.compile("(?is)\\bdata_type\\s+IN\\s*\\(\\s*'char'\\s*,\\s*'varchar'\\s*\\)"),
                    "UPPER(DATA_TYPE) IN ('CHAR', 'VARCHAR')"
            ),
            new TextReplacement(
                    Pattern.compile("(?is)\\bdata_type\\s+IN\\s*\\(\\s*'varchar'\\s*,\\s*'char'\\s*\\)"),
                    "UPPER(DATA_TYPE) IN ('VARCHAR', 'CHAR')"
            ),
            new TextReplacement(Pattern.compile("(?is)\\bdata_type\\b"), "DATA_TYPE"),
            new TextReplacement(Pattern.compile("(?is)\\bcolumn_type\\b"), "DATA_TYPE"),
            new TextReplacement(Pattern.compile("(?is)\\bcolumn_default\\b"), "DATA_DEFAULT"),
            new TextReplacement(Pattern.compile("(?is)\\bis_nullable\\b"), "NULLABLE"),
            new TextReplacement(Pattern.compile("(?is)\\bindex_name\\b"), "INDEX_NAME"),
            new TextReplacement(Pattern.compile("(?is)\\bnumeric_precision\\b"), "DATA_PRECISION"),
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
    private final Consumer<String> progressConsumer;

    SqlScriptMigrator(SqlConverter converter, Validator validator) {
        this(converter, validator, message -> {
        });
    }

    SqlScriptMigrator(
            SqlConverter converter,
            Validator validator,
            Consumer<String> progressConsumer
    ) {
        this.converter = converter;
        this.validator = validator;
        this.progressConsumer = progressConsumer == null ? message -> {
        } : progressConsumer;
    }

    SqlScriptMigrationReport migrate(SqlScriptMigrationRequest request) throws IOException {
        long migrationStartedAt = System.nanoTime();
        Path projectRoot = request.projectRoot().toAbsolutePath().normalize();
        Path sqlRoot = resolvePath(projectRoot, request.sqlRoot());
        Path sqlRootOut = resolvePath(projectRoot, request.sqlRootOut());
        if (!Files.isDirectory(sqlRoot)) {
            throw new IllegalStateException("SQL script root does not exist or is not a directory: " + sqlRoot);
        }
        if (sqlRoot.equals(sqlRootOut)) {
            throw new IllegalStateException("--sql-root-out must be different from --sql-root.");
        }

        List<String> warnings = new ArrayList<>();
        Set<Path> preservedPaths = preservedSqlPaths(request);
        List<Path> discoveredSqlFiles = sqlFiles(sqlRoot);
        List<Path> sqlFiles = discoveredSqlFiles.stream()
                .filter(file -> !preservedPaths.contains(sqlRoot.relativize(file).normalize()))
                .toList();
        for (Path preservedPath : preservedPaths) {
            Path sourceFile = sqlRoot.resolve(preservedPath);
            Path outputFile = sqlRootOut.resolve(preservedPath);
            if (Files.isRegularFile(sourceFile) && !Files.isRegularFile(outputFile)) {
                warnings.add("Preserved SQL source was not converted because no manual Dameng output exists: "
                        + preservedPath);
            }
        }
        long totalInputBytes = 0L;
        for (Path sqlFile : sqlFiles) {
            totalInputBytes += safeFileSize(sqlFile);
        }
        progress("Discovered SQL script files: " + discoveredSqlFiles.size()
                + ", convertedFiles=" + sqlFiles.size()
                + ", totalBytes=" + totalInputBytes
                + ", input=" + sqlRoot
                + ", output=" + sqlRootOut);
        String schema = primarySchema(request.schema(), "--schema", warnings);
        String systemSchema = primarySchema(request.systemSchema(), "--system-schema", warnings);
        List<SqlScriptManualReviewItem> manualReviewItems = new ArrayList<>();
        List<PlannedSqlScriptFile> plannedFiles = new ArrayList<>();
        int convertedFileCount = 0;

        for (int fileIndex = 0; fileIndex < sqlFiles.size(); fileIndex++) {
            Path sqlFile = sqlFiles.get(fileIndex);
            long fileStartedAt = System.nanoTime();
            Path relative = sqlRoot.relativize(sqlFile);
            progress("Planning SQL script [" + (fileIndex + 1) + "/" + sqlFiles.size() + "]: "
                    + relative + ", bytes=" + safeFileSize(sqlFile));
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
            progress("Planned SQL script [" + (fileIndex + 1) + "/" + sqlFiles.size() + "]: "
                    + relative
                    + ", statements=" + plannedFile.originalStatementCount()
                    + ", outputStatements=" + plannedFile.statements().size()
                    + ", convertedStatements=" + plannedFile.convertedStatementCount()
                    + ", manualReview=" + plannedFile.manualReviewStatementCount()
                    + ", elapsedMs=" + elapsedMillis(fileStartedAt));
        }
        if (!request.dryRun()) {
            long outputOnlyStartedAt = System.nanoTime();
            plannedFiles.addAll(outputOnlyPlannedFiles(sqlRootOut, plannedFiles, schema, systemSchema, warnings));
            progress("Output-only SQL script discovery completed: totalPlannedFiles=" + plannedFiles.size()
                    + ", elapsedMs=" + elapsedMillis(outputOnlyStartedAt));
        }
        plannedFiles = plannedFiles.stream()
                .sorted(Comparator.comparing(file -> plannedFileSortKey(sqlRootOut, file)))
                .toList();
        plannedFiles = markMissingProcedureDependencies(plannedFiles, manualReviewItems);

        long validationStartedAt = System.nanoTime();
        progress("Starting SQL script database validation: files=" + plannedFiles.size());
        SqlScriptValidationRun validationRun = request.dryRun()
                ? SqlScriptValidationRun.notAttempted("Dry run; SQL script validation skipped.", List.of())
                : validator.validate(plannedFiles, request.validationEnvironment());
        progress("SQL script database validation completed: attempted=" + validationRun.attempted()
                + ", succeeded=" + validationRun.successCount()
                + ", failed=" + validationRun.failureCount()
                + ", elapsedMs=" + elapsedMillis(validationStartedAt));
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

        SqlScriptMigrationReport report = new SqlScriptMigrationReport(
                projectRoot.toString(),
                sqlRoot.toString(),
                sqlRootOut.toString(),
                request.dryRun(),
                discoveredSqlFiles.size(),
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
        progress("SQL script migration completed: files=" + discoveredSqlFiles.size()
                + ", convertedFiles=" + convertedFileCount
                + ", elapsedMs=" + elapsedMillis(migrationStartedAt));
        return report;
    }

    private Set<Path> preservedSqlPaths(SqlScriptMigrationRequest request) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        paths.add(DEFAULT_PRESERVED_SQL_PATH);
        for (Path configuredPath : request.preservedSqlPaths()) {
            if (configuredPath == null || configuredPath.isAbsolute()) {
                throw new IllegalArgumentException("--preserve-sql must be a relative path inside --sql-root: "
                        + configuredPath);
            }
            Path normalized = configuredPath.normalize();
            if (normalized.startsWith("..") || normalized.toString().isBlank()) {
                throw new IllegalArgumentException("--preserve-sql must stay inside --sql-root: " + configuredPath);
            }
            paths.add(normalized);
        }
        return Set.copyOf(paths);
    }

    private long safeFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return -1L;
        }
    }

    private List<PlannedSqlScriptFile> markMissingProcedureDependencies(
            List<PlannedSqlScriptFile> plannedFiles,
            List<SqlScriptManualReviewItem> manualReviewItems
    ) {
        LinkedHashSet<String> availableProcedures = new LinkedHashSet<>();
        List<PlannedSqlScriptFile> result = new ArrayList<>(plannedFiles.size());
        for (PlannedSqlScriptFile file : plannedFiles) {
            LinkedHashSet<Integer> manualIndexes = new LinkedHashSet<>(file.manualReviewStatementIndexes());
            boolean fileAlreadyManual = !manualIndexes.isEmpty();
            for (int index = 0; index < file.statements().size(); index++) {
                String statement = file.statements().get(index);
                String createdProcedure = procedureNameFromCreateProcedure(statement);
                if (!createdProcedure.isBlank() && !fileAlreadyManual) {
                    for (String calledProcedure : calledProceduresInRoutine(statement)) {
                        if (calledProcedure.equalsIgnoreCase(createdProcedure)
                                || availableProcedures.contains(calledProcedure.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        int statementIndex = index + 1;
                        if (manualIndexes.add(statementIndex)) {
                            manualReviewItems.add(new SqlScriptManualReviewItem(
                                    file.sourceDisplay(),
                                    file.outputDisplay(),
                                    statementIndex,
                                    "缺少已在此前脚本中创建的存储过程依赖 `" + calledProcedure
                                            + "`；请在保留的达梦基础脚本中提供该过程后再验证。",
                                    statement,
                                    statement
                            ));
                        }
                    }
                    availableProcedures.add(createdProcedure.toLowerCase(Locale.ROOT));
                }
            }
            result.add(new PlannedSqlScriptFile(
                    file.sourceDisplay(),
                    file.outputDisplay(),
                    file.schema(),
                    file.systemScript(),
                    file.written(),
                    file.converted(),
                    file.originalStatementCount(),
                    file.convertedStatementCount(),
                    manualIndexes.size(),
                    manualIndexes,
                    file.appliedRules(),
                    file.statements()
            ));
        }
        return List.copyOf(result);
    }

    private List<String> calledProceduresInRoutine(String sql) {
        if (procedureNameFromCreateProcedure(sql).isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?is)\\bCALL\\s+(?<name>" + SQL_IDENTIFIER_TOKEN + ")\\s*\\(")
                .matcher(sql);
        while (matcher.find()) {
            names.add(unquoteIdentifier(lastIdentifierPart(matcher.group("name"))).toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private String sqlType(String sql) {
        String body = splitLeadingSqlPrefix(sql).body().stripLeading();
        int end = 0;
        while (end < body.length() && Character.isLetter(body.charAt(end))) {
            end++;
        }
        return end == 0 ? "UNKNOWN" : body.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private ScriptStatementConversion convertStatementWithProgress(
            Path relative,
            int statementIndex,
            int statementCount,
            String originalStatement,
            LinkedHashMap<String, String> scriptUserVariables,
            ScriptDynamicDdlState scriptDynamicDdlState,
            Map<String, LinkedHashSet<String>> scriptTableColumns,
            Map<String, String> scriptIdentityFirstColumns,
            Map<String, String> scriptProcedureRenames
    ) {
        long startedAt = System.nanoTime();
        boolean largeStatement = originalStatement.length() >= 100_000;
        if (largeStatement) {
            progress("Converting large SQL script statement: file=" + relative
                    + ", statement=" + statementIndex + "/" + statementCount
                    + ", sqlType=" + sqlType(originalStatement)
                    + ", chars=" + originalStatement.length());
        }
        ConversionTimings timings = new ConversionTimings();
        ScriptStatementConversion conversion = convertStatement(
                originalStatement,
                scriptUserVariables,
                scriptDynamicDdlState,
                scriptTableColumns,
                scriptIdentityFirstColumns,
                scriptProcedureRenames,
                timings
        );
        long elapsedMillis = elapsedMillis(startedAt);
        if (largeStatement || elapsedMillis >= 1_000L) {
            progress("Converted slow SQL script statement: file=" + relative
                    + ", statement=" + statementIndex + "/" + statementCount
                    + ", sqlType=" + sqlType(originalStatement)
                    + ", chars=" + originalStatement.length()
                    + ", elapsedMs=" + elapsedMillis
                    + ", prepareMs=" + nanosToMillis(timings.preparationNanos)
                    + ", safeRulesMs=" + nanosToMillis(timings.safeRulesNanos)
                    + ", genericConverterMs=" + nanosToMillis(timings.genericConverterNanos)
                    + ", postProcessMs=" + nanosToMillis(timings.postProcessNanos)
                    + ", originalSyntaxReviewMs=" + nanosToMillis(timings.originalSyntaxReviewNanos)
                    + ", prefixIndexReviewMs=" + nanosToMillis(timings.prefixIndexReviewNanos)
                    + ", generalManualReviewMs=" + nanosToMillis(timings.generalManualReviewNanos));
        }
        return conversion;
    }

    private boolean parallelSafeProcedureStatement(String statement) {
        return statement != null
                && statement.length() >= PARALLEL_PROCEDURE_MIN_CHARS
                && !procedureNameFromCreateProcedure(statement).isBlank();
    }

    private ExecutorService conversionExecutor(long statementCount) {
        if (statementCount <= 0) {
            return null;
        }
        int threadCount = conversionThreadCount(statementCount);
        if (threadCount <= 1) {
            return null;
        }
        return Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable, "dm-sql-script-converter");
            thread.setDaemon(true);
            return thread;
        });
    }

    private int conversionThreadCount(long statementCount) {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int defaultThreadCount = Math.max(1, Math.min(2, availableProcessors - 1));
        Integer configured = Integer.getInteger(CONVERSION_THREADS_PROPERTY);
        int requested = configured != null && configured > 0 ? configured : defaultThreadCount;
        return Math.max(1, Math.min(requested, Math.toIntExact(Math.min(statementCount, Integer.MAX_VALUE))));
    }

    private ScriptDynamicDdlState copyScriptDynamicDdlState(ScriptDynamicDdlState source) {
        ScriptDynamicDdlState copy = new ScriptDynamicDdlState();
        copy.currentSchemaVariables.addAll(source.currentSchemaVariables);
        copy.dynamicDdlVariables.addAll(source.dynamicDdlVariables);
        copy.preparedDynamicDdlStatements.addAll(source.preparedDynamicDdlStatements);
        return copy;
    }

    private LinkedHashMap<String, LinkedHashSet<String>> copyScriptTableColumns(
            Map<String, LinkedHashSet<String>> source
    ) {
        LinkedHashMap<String, LinkedHashSet<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
        }
        return copy;
    }

    private long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private synchronized void progress(String message) {
        try {
            progressConsumer.accept(message);
        } catch (RuntimeException ignored) {
            // Progress logging must never stop SQL script migration.
        }
    }

    private String plannedFileSortKey(Path sqlRootOut, PlannedSqlScriptFile file) {
        try {
            Path output = Path.of(file.outputDisplay()).toAbsolutePath().normalize();
            Path root = sqlRootOut.toAbsolutePath().normalize();
            if (output.startsWith(root)) {
                return root.relativize(output).toString().replace('\\', '/');
            }
        } catch (RuntimeException ignored) {
            // Fall back to display path below.
        }
        return file.outputDisplay().replace('\\', '/');
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
            String content = readSqlScriptContent(outputFile);
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
                    Set.of(),
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

        String originalContent = readSqlScriptContent(sqlFile);
        long parseStartedAt = System.nanoTime();
        List<String> originalStatements = SqlScriptParser.statements(originalContent);
        progress("Parsed SQL script: " + relative
                + ", statements=" + originalStatements.size()
                + ", elapsedMs=" + elapsedMillis(parseStartedAt));
        List<String> convertedStatements = new ArrayList<>();
        LinkedHashMap<String, String> scriptUserVariables = new LinkedHashMap<>();
        ScriptDynamicDdlState scriptDynamicDdlState = new ScriptDynamicDdlState();
        LinkedHashMap<String, LinkedHashSet<String>> scriptTableColumns = new LinkedHashMap<>();
        LinkedHashMap<String, String> scriptIdentityFirstColumns = new LinkedHashMap<>();
        long preparationStartedAt = System.nanoTime();
        LinkedHashMap<String, String> scriptProcedureRenames = procedureObjectNameConflictRenames(originalStatements);
        progress("Prepared SQL script conversion context: " + relative
                + ", procedureRenames=" + scriptProcedureRenames.size()
                + ", elapsedMs=" + elapsedMillis(preparationStartedAt));
        LinkedHashSet<String> manualReviewProcedureNames = new LinkedHashSet<>();
        LinkedHashSet<String> appliedRules = new LinkedHashSet<>();
        int convertedStatementCount = 0;
        int manualReviewStatementCount = 0;
        LinkedHashSet<Integer> manualReviewStatementIndexes = new LinkedHashSet<>();

        long conversionStartedAt = System.nanoTime();
        long parallelStatementCount = originalStatements.stream()
                .filter(this::parallelSafeProcedureStatement)
                .count();
        ExecutorService conversionExecutor = conversionExecutor(parallelStatementCount);
        if (conversionExecutor != null) {
            progress("Parallel SQL procedure conversion enabled: file=" + relative
                    + ", threads=" + conversionThreadCount(parallelStatementCount)
                    + ", statements=" + parallelStatementCount);
        }
        List<StatementConversionPlan> conversionPlans = new ArrayList<>(originalStatements.size());
        try {
            for (int i = 0; i < originalStatements.size(); i++) {
                String originalStatement = originalStatements.get(i);
                int statementIndex = i + 1;
                CompletableFuture<ScriptStatementConversion> conversion;
                if (conversionExecutor != null && parallelSafeProcedureStatement(originalStatement)) {
                    LinkedHashMap<String, String> userVariablesSnapshot = new LinkedHashMap<>(scriptUserVariables);
                    ScriptDynamicDdlState dynamicDdlStateSnapshot = copyScriptDynamicDdlState(scriptDynamicDdlState);
                    LinkedHashMap<String, LinkedHashSet<String>> tableColumnsSnapshot =
                            copyScriptTableColumns(scriptTableColumns);
                    LinkedHashMap<String, String> identityFirstColumnsSnapshot =
                            new LinkedHashMap<>(scriptIdentityFirstColumns);
                    conversion = CompletableFuture.supplyAsync(
                            () -> convertStatementWithProgress(
                                    relative,
                                    statementIndex,
                                    originalStatements.size(),
                                    originalStatement,
                                    userVariablesSnapshot,
                                    dynamicDdlStateSnapshot,
                                    tableColumnsSnapshot,
                                    identityFirstColumnsSnapshot,
                                    scriptProcedureRenames
                            ),
                            conversionExecutor
                    );
                } else {
                    conversion = CompletableFuture.completedFuture(convertStatementWithProgress(
                            relative,
                            statementIndex,
                            originalStatements.size(),
                            originalStatement,
                            scriptUserVariables,
                            scriptDynamicDdlState,
                            scriptTableColumns,
                            scriptIdentityFirstColumns,
                            scriptProcedureRenames
                    ));
                }
                conversionPlans.add(new StatementConversionPlan(statementIndex, originalStatement, conversion));
                collectScriptCreateTableDefinitionColumns(
                        originalStatement,
                        scriptTableColumns,
                        scriptIdentityFirstColumns
                );
                collectScriptAlterTableAddColumns(originalStatement, scriptTableColumns);
            }

            for (StatementConversionPlan plan : conversionPlans) {
                String originalStatement = plan.originalStatement();
                ScriptStatementConversion conversion = plan.conversion().join();
                List<String> outputStatements = expandConvertedOutputStatements(conversion.outputSql());
                String calledProcedureName = procedureNameFromCall(originalStatement);
                boolean dependencyManualReviewRequired = !calledProcedureName.isBlank()
                        && manualReviewProcedureNames.contains(calledProcedureName.toLowerCase(Locale.ROOT));
                boolean manualReviewRequired = conversion.manualReviewRequired() || dependencyManualReviewRequired;
                if (conversion.manualReviewRequired()) {
                    String procedureName = procedureNameFromCreateProcedure(originalStatement);
                    if (!procedureName.isBlank()) {
                        manualReviewProcedureNames.add(procedureName.toLowerCase(Locale.ROOT));
                    }
                }
                if (manualReviewRequired) {
                    manualReviewStatementCount++;
                    int firstOutputStatementIndex = convertedStatements.size() + 1;
                    for (int offset = 0; offset < outputStatements.size(); offset++) {
                        manualReviewStatementIndexes.add(firstOutputStatementIndex + offset);
                    }
                    String reason = conversion.manualReviewRequired()
                            ? conversion.reason()
                            : "依赖需要人工确认的存储过程 `" + calledProcedureName + "`；请先修正该存储过程后再执行这个 CALL。";
                    manualReviewItems.add(new SqlScriptManualReviewItem(
                            relative.toString(),
                            outputFile.toString(),
                            plan.statementIndex(),
                            reason,
                            originalStatement,
                            conversion.convertedSql()
                    ));
                }
                if (conversion.changed() && !manualReviewRequired) {
                    convertedStatementCount++;
                    appliedRules.addAll(conversion.appliedRules());
                }
                if (outputStatements.size() > 1 && !manualReviewRequired) {
                    appliedRules.add(MYSQL_PROCEDURE_TEMP_TABLE_COMPILE_PLACEHOLDER_RULE);
                }
                convertedStatements.addAll(outputStatements);
                if (plan.statementIndex() % 1_000 == 0) {
                    progress("SQL script conversion progress: file=" + relative
                            + ", statements=" + plan.statementIndex() + "/" + originalStatements.size()
                            + ", elapsedMs=" + elapsedMillis(conversionStartedAt));
                }
            }
        } finally {
            if (conversionExecutor != null) {
                conversionExecutor.shutdownNow();
            }
        }
        progress("Converted SQL script statements: " + relative
                + ", statements=" + originalStatements.size()
                + ", outputStatements=" + convertedStatements.size()
                + ", elapsedMs=" + elapsedMillis(conversionStartedAt));

        String convertedContent = originalStatements.isEmpty()
                ? originalContent
                : SqlScriptParser.scriptContent(convertedStatements);
        String schemaBoundContent = bindMetadataOwnerToTargetSchema(convertedContent, targetSchema);
        if (!schemaBoundContent.equals(convertedContent)) {
            convertedContent = schemaBoundContent;
            appliedRules.add(DM_METADATA_OWNER_TARGET_SCHEMA_RULE);
            convertedStatements = new ArrayList<>(SqlScriptParser.statements(convertedContent));
        }
        convertedContent = stripLeadingBom(convertedContent);
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
                manualReviewStatementIndexes,
                List.copyOf(appliedRules),
                convertedStatements
        );
    }

    private String readSqlScriptContent(Path sqlFile) throws IOException {
        return stripLeadingBom(Files.readString(sqlFile, StandardCharsets.UTF_8));
    }

    private String bindMetadataOwnerToTargetSchema(String sql, String targetSchema) {
        if (sql == null || sql.isBlank() || targetSchema == null || targetSchema.isBlank()) {
            return sql == null ? "" : sql;
        }
        String owner = sqlStringLiteral(targetSchema.trim());
        return Pattern.compile(
                        "(?is)\\b(?:[A-Za-z_][A-Za-z0-9_$]*\\.)?OWNER\\s*=\\s*"
                                + "SYS_CONTEXT\\s*\\(\\s*'USERENV'\\s*,\\s*'CURRENT_SCHEMA'\\s*\\)"
                )
                .matcher(sql)
                .replaceAll("OWNER = " + Matcher.quoteReplacement(owner));
    }

    private String stripLeadingBom(String content) {
        String stripped = content == null ? "" : content;
        while (stripped.startsWith("\uFEFF")) {
            stripped = stripped.substring(1);
        }
        while (stripped.startsWith("\u00EF\u00BB\u00BF")) {
            stripped = stripped.substring(3);
        }
        return stripped;
    }

    private LinkedHashMap<String, String> procedureObjectNameConflictRenames(List<String> statements) {
        LinkedHashMap<String, String> renames = new LinkedHashMap<>();
        LinkedHashSet<String> reservedNames = new LinkedHashSet<>();
        for (String statement : statements) {
            String procedureName = procedureNameFromCreateProcedure(statement);
            if (procedureName.isBlank()) {
                continue;
            }
            reservedNames.add(procedureName.toLowerCase(Locale.ROOT));
        }
        for (String statement : statements) {
            String procedureName = procedureNameFromCreateProcedure(statement);
            if (procedureName.isBlank()
                    || !containsSqlObjectReference(statement, procedureName)) {
                continue;
            }
            String renamed = uniqueRenamedProcedureName(procedureName, reservedNames);
            renames.put(procedureName.toLowerCase(Locale.ROOT), renamed);
        }
        return renames;
    }

    private String procedureNameFromCreateProcedure(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        LeadingSqlPrefix leadingSqlPrefix = splitLeadingSqlPrefix(sql);
        String body = CREATE_DEFINER_PATTERN.matcher(leadingSqlPrefix.body()).replaceFirst("CREATE ");
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\s+(?<name>" + SQL_IDENTIFIER_TOKEN + ")"
        ).matcher(body);
        if (!matcher.find()) {
            return "";
        }
        return unquoteIdentifier(lastIdentifierPart(matcher.group("name").strip()));
    }

    private String procedureNameFromCall(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        LeadingSqlPrefix leadingSqlPrefix = splitLeadingSqlPrefix(sql);
        String body = leadingSqlPrefix.body();
        int start = skipWhitespace(body, 0);
        if (!startsKeyword(body, start, "CALL")) {
            return "";
        }
        int procedureStart = skipWhitespace(body, start + "CALL".length());
        SqlIdentifierReference procedure = sqlIdentifierReferenceAt(body, procedureStart);
        if (procedure == null) {
            return "";
        }
        int openParen = skipWhitespace(body, procedure.end());
        if (openParen >= body.length() || body.charAt(openParen) != '(') {
            return "";
        }
        return unquoteIdentifier(lastIdentifierPart(procedure.token()));
    }

    private String uniqueRenamedProcedureName(String procedureName, LinkedHashSet<String> reservedNames) {
        String normalized = normalizeIdentifierSegment(procedureName);
        if (normalized.isBlank()) {
            normalized = "procedure";
        }
        String prefix = "dm_adapter_proc_";
        int maxIdentifierLength = 120;
        if (prefix.length() + normalized.length() > maxIdentifierLength) {
            normalized = normalized.substring(0, maxIdentifierLength - prefix.length());
        }
        String candidate = prefix + normalized;
        int suffix = 2;
        while (reservedNames.contains(candidate.toLowerCase(Locale.ROOT))) {
            String suffixText = "_" + suffix;
            int baseLength = Math.min(normalized.length(), maxIdentifierLength - prefix.length() - suffixText.length());
            candidate = prefix + normalized.substring(0, Math.max(1, baseLength)) + suffixText;
            suffix++;
        }
        reservedNames.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private boolean containsSqlObjectReference(String sql, String objectName) {
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
            } else if (startsKeyword(sql, index, "FROM")
                    || startsKeyword(sql, index, "JOIN")
                    || startsKeyword(sql, index, "UPDATE")) {
                int keywordEnd = index + sqlObjectReferenceKeywordLength(sql, index);
                SqlIdentifierReference reference = sqlIdentifierReferenceAt(sql, skipWhitespace(sql, keywordEnd));
                if (reference != null && sameIdentifier(lastIdentifierPart(reference.token()), objectName)) {
                    return true;
                }
                index = Math.max(index + 1, reference == null ? keywordEnd : reference.end());
            } else if (startsKeyword(sql, index, "INTO")) {
                int keywordEnd = index + "INTO".length();
                int cursor = skipWhitespace(sql, keywordEnd);
                if (startsKeyword(sql, cursor, "OUTFILE") || startsKeyword(sql, cursor, "DUMPFILE")) {
                    index++;
                    continue;
                }
                SqlIdentifierReference reference = sqlIdentifierReferenceAt(sql, cursor);
                if (reference != null && sameIdentifier(lastIdentifierPart(reference.token()), objectName)) {
                    return true;
                }
                index = Math.max(index + 1, reference == null ? keywordEnd : reference.end());
            } else if (startsKeyword(sql, index, "TABLE")) {
                int keywordEnd = index + "TABLE".length();
                int cursor = skipWhitespace(sql, keywordEnd);
                if (startsKeyword(sql, cursor, "IF")) {
                    cursor = skipWhitespace(sql, cursor + "IF".length());
                    if (startsKeyword(sql, cursor, "NOT")) {
                        cursor = skipWhitespace(sql, cursor + "NOT".length());
                    }
                    if (startsKeyword(sql, cursor, "EXISTS")) {
                        cursor = skipWhitespace(sql, cursor + "EXISTS".length());
                    }
                }
                SqlIdentifierReference reference = sqlIdentifierReferenceAt(sql, cursor);
                if (reference != null && sameIdentifier(lastIdentifierPart(reference.token()), objectName)) {
                    return true;
                }
                index = Math.max(index + 1, reference == null ? keywordEnd : reference.end());
            } else {
                index++;
            }
        }
        return false;
    }

    private int sqlObjectReferenceKeywordLength(String sql, int index) {
        if (startsKeyword(sql, index, "UPDATE")) {
            return "UPDATE".length();
        }
        if (startsKeyword(sql, index, "FROM")) {
            return "FROM".length();
        }
        return "JOIN".length();
    }

    private ProcedureReferenceRename renameScriptProcedureReferences(
            String sql,
            Map<String, String> procedureRenames
    ) {
        if (sql == null || sql.isBlank() || procedureRenames.isEmpty()) {
            return new ProcedureReferenceRename(sql == null ? "" : sql, false);
        }
        String converted = sql;
        boolean changed = false;
        for (Map.Entry<String, String> entry : procedureRenames.entrySet()) {
            ProcedureReferenceRename rename = renameScriptProcedureReference(
                    converted,
                    entry.getKey(),
                    entry.getValue()
            );
            converted = rename.sql();
            changed = changed || rename.changed();
        }
        return new ProcedureReferenceRename(converted, changed);
    }

    private ProcedureReferenceRename renameScriptProcedureReference(
            String sql,
            String oldNameLower,
            String newName
    ) {
        String converted = replaceOutsideIgnoredText(
                sql,
                Pattern.compile(
                        "(?is)(\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\s+)"
                                + identifierReferencePattern(oldNameLower)
                ),
                matcher -> matcher.group(1) + newName
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile(
                        "(?is)(\\bDROP\\s+PROCEDURE\\s+(?:IF\\s+EXISTS\\s+)?)"
                                + identifierReferencePattern(oldNameLower)
                ),
                matcher -> matcher.group(1) + newName
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile(
                        "(?is)(\\bCALL\\s+)"
                                + identifierReferencePattern(oldNameLower)
                                + "(\\s*\\()"
                ),
                matcher -> matcher.group(1) + newName + matcher.group(2)
        );
        return new ProcedureReferenceRename(converted, !converted.equals(sql));
    }

    private ScriptStatementConversion convertStatement(
            String originalStatement,
            LinkedHashMap<String, String> scriptUserVariables,
            ScriptDynamicDdlState scriptDynamicDdlState,
            Map<String, LinkedHashSet<String>> scriptTableColumns,
            Map<String, String> scriptIdentityFirstColumns,
            Map<String, String> scriptProcedureRenames,
            ConversionTimings timings
    ) {
        long preparationStartedAt = System.nanoTime();
        LeadingSqlPrefix leadingSqlPrefix = splitLeadingSqlPrefix(originalStatement);
        ScriptStatementConversion currentSchemaVariableConversion =
                convertScriptCurrentSchemaVariableAssignment(originalStatement, leadingSqlPrefix, scriptDynamicDdlState);
        if (currentSchemaVariableConversion != null) {
            return currentSchemaVariableConversion;
        }
        ScriptStatementConversion dynamicDdlAssignmentConversion =
                convertScriptDynamicDdlAssignment(originalStatement, leadingSqlPrefix, scriptDynamicDdlState);
        if (dynamicDdlAssignmentConversion != null) {
            return dynamicDdlAssignmentConversion;
        }
        ScriptStatementConversion dynamicDdlPrepareConversion =
                convertScriptDynamicDdlPrepareStatement(originalStatement, leadingSqlPrefix, scriptDynamicDdlState);
        if (dynamicDdlPrepareConversion != null) {
            return dynamicDdlPrepareConversion;
        }
        ScriptUserVariableAssignment userVariableAssignment =
                scriptUserVariableAssignment(leadingSqlPrefix.body());
        if (userVariableAssignment != null) {
            scriptUserVariables.put(userVariableAssignment.name().toLowerCase(Locale.ROOT), userVariableAssignment.literal());
            String convertedSql = leadingSqlPrefix.prefix()
                    + "-- DM_ADAPTER: MySQL script variable @"
                    + userVariableAssignment.name()
                    + " was inlined as "
                    + userVariableAssignment.literal();
            return new ScriptStatementConversion(
                    originalStatement,
                    convertedSql,
                    convertedSql,
                    true,
                    false,
                    "",
                    List.of(MYSQL_SCRIPT_USER_VARIABLE_LITERAL_RULE)
            );
        }
        ScriptUserVariableInline userVariableInline =
                inlineScriptUserVariables(leadingSqlPrefix.body(), scriptUserVariables);
        List<String> rules = new ArrayList<>();
        if (userVariableInline.changed()) {
            rules.add(MYSQL_SCRIPT_USER_VARIABLE_LITERAL_RULE);
        }
        String sqlBody = userVariableInline.sql();
        ProcedureReferenceRename procedureReferenceRename =
                renameScriptProcedureReferences(sqlBody, scriptProcedureRenames);
        if (procedureReferenceRename.changed()) {
            sqlBody = procedureReferenceRename.sql();
            rules.add(MYSQL_PROCEDURE_OBJECT_NAME_CONFLICT_RENAME_RULE);
        }
        timings.preparationNanos += System.nanoTime() - preparationStartedAt;
        long safeRulesStartedAt = System.nanoTime();
        SafeRuleConversion safeRuleConversion = applyScriptSafeRules(
                sqlBody,
                scriptTableColumns,
                scriptIdentityFirstColumns
        );
        timings.safeRulesNanos += System.nanoTime() - safeRulesStartedAt;
        long genericConverterStartedAt = System.nanoTime();
        SqlConversionResult sqlConversion = isConvertedSimpleDateEndTrigger(safeRuleConversion.sql())
                ? SqlConversionResult.unchanged(safeRuleConversion.sql())
                : converter.convert(safeRuleConversion.sql());
        timings.genericConverterNanos += System.nanoTime() - genericConverterStartedAt;
        long postProcessStartedAt = System.nanoTime();
        rules.addAll(safeRuleConversion.appliedRules());
        rules.addAll(sqlConversion.appliedRules());
        String convertedBody = sqlConversion.convertedSql();
        String normalizedDropProcedureSql = normalizeDuplicateDropProcedureIfExists(convertedBody);
        if (!normalizedDropProcedureSql.equals(convertedBody)) {
            convertedBody = normalizedDropProcedureSql;
            rules.add(MYSQL_DROP_PROCEDURE_IF_EXISTS_RULE);
        }
        String convertedSql = leadingSqlPrefix.prefix() + convertedBody;
        boolean changed = !convertedSql.equals(originalStatement);
        String manualReason;
        if (sqlConversion.manualReviewRequired()) {
            manualReason = sqlConversion.reason();
        } else {
            long manualCheckStartedAt = System.nanoTime();
            manualReason = originalSqlSyntaxManualReviewReason(sqlBody);
            timings.originalSyntaxReviewNanos += System.nanoTime() - manualCheckStartedAt;
            if (manualReason.isBlank()) {
                manualCheckStartedAt = System.nanoTime();
                manualReason = mysqlPrefixIndexManualReviewReason(sqlBody, convertedBody);
                timings.prefixIndexReviewNanos += System.nanoTime() - manualCheckStartedAt;
            }
            if (manualReason.isBlank()) {
                manualCheckStartedAt = System.nanoTime();
                manualReason = manualReviewReason(convertedBody);
                timings.generalManualReviewNanos += System.nanoTime() - manualCheckStartedAt;
            }
        }
        if (!manualReason.isBlank()) {
            timings.postProcessNanos += System.nanoTime() - postProcessStartedAt;
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
        timings.postProcessNanos += System.nanoTime() - postProcessStartedAt;
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

    private ScriptUserVariableAssignment scriptUserVariableAssignment(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?is)^\\s*SET\\s+@([A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*(.+?)\\s*$")
                .matcher(sql.strip());
        if (!matcher.matches()) {
            return null;
        }
        String literal = scriptUserVariableLiteral(matcher.group(2).strip());
        if (literal.isBlank()) {
            return null;
        }
        return new ScriptUserVariableAssignment(matcher.group(1), literal);
    }

    private String scriptUserVariableLiteral(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        String stripped = expression.strip();
        if (Pattern.compile("(?is)^[-+]?\\d+(?:\\.\\d+)?$").matcher(stripped).matches()
                || Pattern.compile("(?is)^(?:NULL|TRUE|FALSE)$").matcher(stripped).matches()) {
            return stripped;
        }
        if (stripped.startsWith("'") && skipSingleQuotedString(stripped, 0) == stripped.length()) {
            return stripped;
        }
        return "";
    }

    private ScriptStatementConversion convertScriptCurrentSchemaVariableAssignment(
            String originalStatement,
            LeadingSqlPrefix leadingSqlPrefix,
            ScriptDynamicDdlState scriptDynamicDdlState
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*SET\\s+@(?<name>[A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*"
                        + "(?:DATABASE\\s*\\(\\s*\\)|\\(\\s*SELECT\\s+DATABASE\\s*\\(\\s*\\)\\s*\\))\\s*$"
        ).matcher(leadingSqlPrefix.body().strip());
        if (!matcher.matches()) {
            return null;
        }
        String variableName = matcher.group("name");
        scriptDynamicDdlState.currentSchemaVariables.add(variableName.toLowerCase(Locale.ROOT));
        String convertedSql = leadingSqlPrefix.prefix()
                + "-- DM_ADAPTER: MySQL script variable @"
                + variableName
                + " uses SYS_CONTEXT('USERENV','CURRENT_SCHEMA') in converted metadata checks";
        return new ScriptStatementConversion(
                originalStatement,
                convertedSql,
                convertedSql,
                true,
                false,
                "",
                List.of(MYSQL_SCRIPT_DYNAMIC_DDL_TO_EXECUTE_IMMEDIATE_RULE)
        );
    }

    private ScriptStatementConversion convertScriptDynamicDdlAssignment(
            String originalStatement,
            LeadingSqlPrefix leadingSqlPrefix,
            ScriptDynamicDdlState scriptDynamicDdlState
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*SET\\s+@(?<name>[A-Za-z_][A-Za-z0-9_$]*)\\s*=\\s*\\(\\s*"
                        + "SELECT\\s+IF\\s*\\(\\s*COUNT\\s*\\(\\s*(?:\\*|1)\\s*\\)\\s*=\\s*0\\s*,\\s*"
                        + "(?<ddl>" + SQL_STRING_LITERAL_TOKEN + ")\\s*,\\s*"
                        + "(?<noop>" + SQL_STRING_LITERAL_TOKEN + ")\\s*\\)\\s*"
                        + "FROM\\s+information_schema\\s*\\.\\s*columns\\s+"
                        + "WHERE\\s+(?<where>.*?)\\s*\\)\\s*$"
        ).matcher(leadingSqlPrefix.body().strip());
        if (!matcher.matches()) {
            return null;
        }
        String noopSql = singleQuotedSqlLiteralValue(matcher.group("noop"));
        if (!noopSql.equalsIgnoreCase("do 0")) {
            return null;
        }
        String tableName = metadataPredicateLiteral(matcher.group("where"), "table_name");
        String columnName = metadataPredicateLiteral(matcher.group("where"), "column_name");
        String schemaPredicate = metadataSchemaPredicate(matcher.group("where"), scriptDynamicDdlState);
        if (tableName.isBlank() || columnName.isBlank() || schemaPredicate.isBlank()) {
            return null;
        }
        String ddl = normalizeMysqlDynamicDdlForDameng(singleQuotedSqlLiteralValue(matcher.group("ddl")));
        if (ddl.isBlank()) {
            return null;
        }
        String variableName = matcher.group("name");
        scriptDynamicDdlState.dynamicDdlVariables.add(variableName.toLowerCase(Locale.ROOT));
        String convertedSql = leadingSqlPrefix.prefix()
                + "DECLARE\n"
                + "    dm_existing_count INT;\n"
                + "BEGIN\n"
                + "    SELECT COUNT(*) INTO dm_existing_count\n"
                + "    FROM ALL_TAB_COLUMNS\n"
                + "    WHERE " + schemaPredicate + "\n"
                + "      AND TABLE_NAME = " + sqlStringLiteral(tableName) + "\n"
                + "      AND COLUMN_NAME = " + sqlStringLiteral(columnName) + ";\n\n"
                + "    IF dm_existing_count = 0 THEN\n"
                + "        EXECUTE IMMEDIATE " + sqlStringLiteral(ddl) + ";\n"
                + "    END IF;\n"
                + "END";
        return new ScriptStatementConversion(
                originalStatement,
                convertedSql,
                convertedSql,
                true,
                false,
                "",
                List.of(MYSQL_SCRIPT_DYNAMIC_DDL_TO_EXECUTE_IMMEDIATE_RULE)
        );
    }

    private ScriptStatementConversion convertScriptDynamicDdlPrepareStatement(
            String originalStatement,
            LeadingSqlPrefix leadingSqlPrefix,
            ScriptDynamicDdlState scriptDynamicDdlState
    ) {
        String body = leadingSqlPrefix.body().strip();
        Matcher prepare = Pattern.compile(
                "(?is)^\\s*PREPARE\\s+(?<statement>[A-Za-z_][A-Za-z0-9_$]*)\\s+FROM\\s+@(?<variable>[A-Za-z_][A-Za-z0-9_$]*)\\s*$"
        ).matcher(body);
        if (prepare.matches()) {
            String variableName = prepare.group("variable").toLowerCase(Locale.ROOT);
            if (!scriptDynamicDdlState.dynamicDdlVariables.contains(variableName)) {
                return null;
            }
            String statementName = prepare.group("statement");
            scriptDynamicDdlState.preparedDynamicDdlStatements.add(statementName.toLowerCase(Locale.ROOT));
            return dynamicDdlNoopConversion(
                    originalStatement,
                    leadingSqlPrefix,
                    "MySQL PREPARE " + statementName + " is handled by the previous EXECUTE IMMEDIATE block"
            );
        }
        Matcher execute = Pattern.compile(
                "(?is)^\\s*EXECUTE\\s+(?<statement>[A-Za-z_][A-Za-z0-9_$]*)\\s*$"
        ).matcher(body);
        if (execute.matches()) {
            String statementName = execute.group("statement");
            if (!scriptDynamicDdlState.preparedDynamicDdlStatements.contains(statementName.toLowerCase(Locale.ROOT))) {
                return null;
            }
            return dynamicDdlNoopConversion(
                    originalStatement,
                    leadingSqlPrefix,
                    "MySQL EXECUTE " + statementName + " is handled by the previous EXECUTE IMMEDIATE block"
            );
        }
        Matcher deallocate = Pattern.compile(
                "(?is)^\\s*DEALLOCATE\\s+PREPARE\\s+(?<statement>[A-Za-z_][A-Za-z0-9_$]*)\\s*$"
        ).matcher(body);
        if (!deallocate.matches()) {
            return null;
        }
        String statementName = deallocate.group("statement");
        if (!scriptDynamicDdlState.preparedDynamicDdlStatements.remove(statementName.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return dynamicDdlNoopConversion(
                originalStatement,
                leadingSqlPrefix,
                "MySQL DEALLOCATE PREPARE " + statementName
                        + " is unnecessary after the previous EXECUTE IMMEDIATE block"
        );
    }

    private ScriptStatementConversion dynamicDdlNoopConversion(
            String originalStatement,
            LeadingSqlPrefix leadingSqlPrefix,
            String message
    ) {
        String convertedSql = leadingSqlPrefix.prefix() + "-- DM_ADAPTER: " + message;
        return new ScriptStatementConversion(
                originalStatement,
                convertedSql,
                convertedSql,
                true,
                false,
                "",
                List.of(MYSQL_SCRIPT_DYNAMIC_DDL_TO_EXECUTE_IMMEDIATE_RULE)
        );
    }

    private String metadataPredicateLiteral(String whereClause, String columnName) {
        Matcher matcher = Pattern.compile(
                "(?is)\\b" + Pattern.quote(columnName) + "\\b\\s*=\\s*(?<value>" + SQL_STRING_LITERAL_TOKEN + ")"
        ).matcher(whereClause);
        if (!matcher.find()) {
            return "";
        }
        return singleQuotedSqlLiteralValue(matcher.group("value"));
    }

    private String metadataSchemaPredicate(String whereClause, ScriptDynamicDdlState scriptDynamicDdlState) {
        Matcher variableMatcher = Pattern.compile(
                "(?is)\\btable_schema\\b\\s*=\\s*@(?<name>[A-Za-z_][A-Za-z0-9_$]*)"
        ).matcher(whereClause);
        if (variableMatcher.find()) {
            String variableName = variableMatcher.group("name").toLowerCase(Locale.ROOT);
            if (scriptDynamicDdlState.currentSchemaVariables.contains(variableName)) {
                return "OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')";
            }
            return "";
        }
        if (Pattern.compile("(?is)\\btable_schema\\b\\s*=\\s*DATABASE\\s*\\(").matcher(whereClause).find()) {
            return "OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')";
        }
        Matcher literalMatcher = Pattern.compile(
                "(?is)\\btable_schema\\b\\s*=\\s*(?<value>" + SQL_STRING_LITERAL_TOKEN + ")"
        ).matcher(whereClause);
        if (!literalMatcher.find()) {
            return "";
        }
        return "OWNER = " + sqlStringLiteral(singleQuotedSqlLiteralValue(literalMatcher.group("value")));
    }

    private String normalizeMysqlDynamicDdlForDameng(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            return "";
        }
        String converted = ddl.strip();
        converted = normalizeMysqlDataTypes(converted);
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)\\bADD\\s+COLUMN\\b"),
                matcher -> "ADD"
        );
        converted = removeMysqlCommentClauses(converted);
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)\\s+AFTER\\s+(?:" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")(?=\\s*(?:,|$))"),
                matcher -> ""
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)\\s+FIRST(?=\\s*(?:,|$))"),
                matcher -> ""
        );
        return converted.strip();
    }

    private String normalizeDamengMetadataIdentifierComparisons(String sql) {
        if (sql == null || sql.isBlank()
                || !Pattern.compile("(?is)\\bALL_(?:TAB_COLUMNS|INDEXES|IND_COLUMNS)\\b").matcher(sql).find()) {
            return sql == null ? "" : sql;
        }
        return replaceOutsideIgnoredText(
                sql,
                Pattern.compile(
                        "(?is)\\b(TABLE_NAME|COLUMN_NAME|INDEX_NAME|INDEX_OWNER)\\b\\s*=\\s*(" + SQL_STRING_LITERAL_TOKEN + ")"
                ),
                matcher -> "UPPER(" + matcher.group(1) + ") = UPPER(" + matcher.group(2) + ")"
        );
    }

    private ScriptUserVariableInline inlineScriptUserVariables(
            String sql,
            LinkedHashMap<String, String> scriptUserVariables
    ) {
        if (sql == null || sql.isBlank() || scriptUserVariables == null || scriptUserVariables.isEmpty()) {
            return new ScriptUserVariableInline(sql == null ? "" : sql, false);
        }
        List<UserVariableReference> references = mysqlUserVariableReferences(sql);
        if (references.isEmpty()) {
            return new ScriptUserVariableInline(sql, false);
        }
        StringBuilder converted = new StringBuilder(sql.length());
        int cursor = 0;
        boolean changed = false;
        for (UserVariableReference reference : references) {
            String literal = scriptUserVariables.get(reference.name().toLowerCase(Locale.ROOT));
            if (literal == null) {
                continue;
            }
            converted.append(sql, cursor, reference.start());
            converted.append(literal);
            cursor = reference.end();
            changed = true;
        }
        if (!changed) {
            return new ScriptUserVariableInline(sql, false);
        }
        converted.append(sql, cursor, sql.length());
        return new ScriptUserVariableInline(converted.toString(), true);
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
        List<String> placeholders = new ArrayList<>(tableColumns.size());
        for (Map.Entry<String, LinkedHashSet<String>> entry : tableColumns.entrySet()) {
            placeholders.add("CREATE TABLE IF NOT EXISTS " + entry.getKey()
                    + " (" + procedureTempTableColumnDefinitions(entry.getValue()) + ")");
        }
        return placeholders;
    }

    private LinkedHashMap<String, LinkedHashSet<String>> temporaryProcedureTableDefinitions(String sql) {
        LinkedHashMap<String, LinkedHashSet<String>> tableColumns = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("(?i)\\btmp_[A-Za-z0-9_]+\\b").matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group();
            if (Pattern.compile("(?i)_idx(?:_|$)").matcher(tableName).find()) {
                continue;
            }
            temporaryTableColumnsFor(tableColumns, tableName);
        }
        collectCreateTableDefinitionColumns(sql, tableColumns);
        collectCreateTableSelectColumns(sql, tableColumns);
        collectTemporaryAlterTableAddColumns(sql, tableColumns);
        collectInsertSelectColumns(sql, tableColumns);
        collectMergeInsertColumns(sql, tableColumns);
        collectTemporaryColumnMarkers(sql, tableColumns);
        propagateCreateTableSelectStarColumns(sql, tableColumns);
        for (Map.Entry<String, LinkedHashSet<String>> entry : tableColumns.entrySet()) {
            if (shouldApplyDefaultTemporaryColumns(entry.getKey(), entry.getValue())) {
                for (ProcedureTempTableColumn defaultColumn : PROCEDURE_TEMP_TABLE_DEFAULT_COLUMNS) {
                    addColumnIfAbsentIgnoreCase(entry.getValue(), defaultColumn.name());
                }
            }
        }
        return tableColumns;
    }

    private void collectScriptCreateTableDefinitionColumns(
            String sql,
            LinkedHashMap<String, LinkedHashSet<String>> tableColumns,
            LinkedHashMap<String, String> identityFirstColumns
    ) {
        LeadingSqlPrefix leadingSqlPrefix = splitLeadingSqlPrefix(sql);
        String body = leadingSqlPrefix.body();
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*CREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                        + "(?<table>(?:" + SQL_IDENTIFIER_TOKEN + "))\\s*\\("
        ).matcher(body);
        if (!matcher.find()) {
            return;
        }
        int openParen = matcher.end() - 1;
        int closeParen = findMatchingParen(body, openParen);
        if (closeParen <= openParen) {
            return;
        }
        String tableName = matcher.group("table").strip();
        LinkedHashSet<String> targetColumns = temporaryTableColumnsFor(tableColumns, tableName);
        String firstColumnName = "";
        for (String part : splitTopLevelComma(body.substring(openParen + 1, closeParen))) {
            String definition = part.strip();
            String columnName = createTableColumnDefinitionName(definition);
            if (!columnName.isBlank()) {
                if (firstColumnName.isBlank()) {
                    firstColumnName = columnName;
                    if (isAutoGeneratedIdentityColumnDefinition(definition)) {
                        identityFirstColumns.put(normalizedTableKey(tableName), columnName);
                    }
                }
                addColumnIfAbsentIgnoreCase(targetColumns, columnName);
            }
        }
    }

    private void collectScriptAlterTableAddColumns(
            String sql,
            LinkedHashMap<String, LinkedHashSet<String>> tableColumns
    ) {
        if (tableColumns.isEmpty()) {
            return;
        }
        LeadingSqlPrefix leadingSqlPrefix = splitLeadingSqlPrefix(sql);
        Matcher matcher = Pattern.compile(
                "(?is)\\bALTER\\s+TABLE\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+ADD\\s+"
                        + "(?:COLUMN\\s+)?(?<definition>[^;]+)"
        ).matcher(leadingSqlPrefix.body());
        while (matcher.find()) {
            String tableName = matcher.group("table").strip();
            LinkedHashSet<String> targetColumns = temporaryTableColumns(tableColumns, tableName);
            if (targetColumns == null) {
                continue;
            }
            String columnName = createTableColumnDefinitionName(matcher.group("definition").strip());
            if (!columnName.isBlank()) {
                addColumnIfAbsentIgnoreCase(targetColumns, columnName);
            }
        }
    }

    private boolean shouldApplyDefaultTemporaryColumns(String tableName, LinkedHashSet<String> columns) {
        if (columns.isEmpty()) {
            return true;
        }
        String normalized = normalizedTableKey(tableName);
        return normalized.matches("tmp_enterprise_orgid(?:_\\d+|_insert|_order)?");
    }

    private void collectCreateTableDefinitionColumns(
            String sql,
            LinkedHashMap<String, LinkedHashSet<String>> tableColumns
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)\\bCREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                        + "(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s*\\("
        ).matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group("table").strip();
            if (!isProcedureTemporaryTableName(tableName)) {
                continue;
            }
            int openParen = matcher.end() - 1;
            int closeParen = findMatchingParen(sql, openParen);
            if (closeParen <= openParen) {
                continue;
            }
            LinkedHashSet<String> targetColumns = temporaryTableColumnsFor(tableColumns, tableName);
            for (String part : splitTopLevelComma(sql.substring(openParen + 1, closeParen))) {
                String columnName = createTableColumnDefinitionName(part.strip());
                if (!columnName.isBlank()) {
                    addColumnIfAbsentIgnoreCase(targetColumns, columnName);
                }
            }
        }
    }

    private String createTableColumnDefinitionName(String definition) {
        int cursor = skipWhitespace(definition, 0);
        if (cursor >= definition.length()
                || startsKeyword(definition, cursor, "PRIMARY")
                || startsKeyword(definition, cursor, "CONSTRAINT")
                || startsKeyword(definition, cursor, "KEY")
                || startsKeyword(definition, cursor, "INDEX")
                || startsKeyword(definition, cursor, "UNIQUE")
                || startsKeyword(definition, cursor, "FULLTEXT")
                || startsKeyword(definition, cursor, "SPATIAL")
                || startsKeyword(definition, cursor, "FOREIGN")
                || startsKeyword(definition, cursor, "CHECK")) {
            return "";
        }
        SqlIdentifierReference column = sqlIdentifierReferenceAt(definition, cursor);
        if (column == null) {
            return "";
        }
        return unquoteIdentifier(lastIdentifierPart(column.token()));
    }

    private boolean isAutoGeneratedIdentityColumnDefinition(String definition) {
        return Pattern.compile("(?is)\\bAUTO_INCREMENT\\b|\\bIDENTITY\\s*\\(")
                .matcher(definition)
                .find();
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
            LinkedHashSet<String> targetColumns = temporaryTableColumnsFor(tableColumns, tableName);
            addColumnsIfAbsentIgnoreCase(targetColumns, columns);
        }
    }

    private void collectTemporaryAlterTableAddColumns(
            String sql,
            LinkedHashMap<String, LinkedHashSet<String>> tableColumns
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)\\bALTER\\s+TABLE\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+ADD\\s+"
                        + "(?:COLUMN\\s+)?(?<definition>[^;]+)"
        ).matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group("table").strip();
            if (!isProcedureTemporaryTableName(tableName)) {
                continue;
            }
            String columnName = createTableColumnDefinitionName(matcher.group("definition").strip());
            if (!columnName.isBlank()) {
                addColumnIfAbsentIgnoreCase(temporaryTableColumnsFor(tableColumns, tableName), columnName);
            }
        }
    }

    private void collectTemporaryColumnMarkers(
            String sql,
            LinkedHashMap<String, LinkedHashSet<String>> tableColumns
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)/\\*\\s*DM_ADAPTER_TMP_COLUMN\\s+(?<table>[A-Za-z_][A-Za-z0-9_]*)\\s+"
                        + "(?<column>[A-Za-z_][A-Za-z0-9_]*)\\s*\\*/"
        ).matcher(sql);
        while (matcher.find()) {
            addColumnIfAbsentIgnoreCase(
                    temporaryTableColumnsFor(tableColumns, matcher.group("table")),
                    matcher.group("column")
            );
        }
    }

    private void propagateCreateTableSelectStarColumns(
            String sql,
            LinkedHashMap<String, LinkedHashSet<String>> tableColumns
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)\\bCREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                        + "(?<target>" + SQL_IDENTIFIER_TOKEN + ")\\s+(?:AS\\s+)?SELECT\\s+\\*\\s+FROM\\s+"
                        + "(?<source>" + SQL_IDENTIFIER_TOKEN + ")\\b"
        ).matcher(sql);
        boolean changed;
        do {
            changed = false;
            matcher.reset();
            while (matcher.find()) {
                if (!isProcedureTemporaryTableName(matcher.group("target"))
                        || !isProcedureTemporaryTableName(matcher.group("source"))) {
                    continue;
                }
                LinkedHashSet<String> targetColumns = temporaryTableColumnsFor(tableColumns, matcher.group("target"));
                LinkedHashSet<String> sourceColumns = temporaryTableColumns(tableColumns, matcher.group("source"));
                if (sourceColumns != null && addColumnsIfAbsentIgnoreCase(targetColumns, sourceColumns)) {
                    changed = true;
                }
            }
        } while (changed);
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
            LinkedHashSet<String> targetColumns = temporaryTableColumnsFor(tableColumns, tableName);
            for (String column : splitTopLevelComma(matcher.group("columns"))) {
                String columnName = unquoteIdentifier(lastIdentifierPart(column.strip()));
                if (!columnName.isBlank()) {
                    addColumnIfAbsentIgnoreCase(targetColumns, columnName);
                }
            }
        }
    }

    private void collectMergeInsertColumns(
            String sql,
            LinkedHashMap<String, LinkedHashSet<String>> tableColumns
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)\\bMERGE\\s+INTO\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\b"
                        + "(?:(?!\\bWHEN\\s+NOT\\s+MATCHED\\s+THEN\\s+INSERT\\b).)*"
                        + "\\bWHEN\\s+NOT\\s+MATCHED\\s+THEN\\s+INSERT\\s*\\((?<columns>[^)]*)\\)"
        ).matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group("table").strip();
            if (!isProcedureTemporaryTableName(tableName)) {
                continue;
            }
            LinkedHashSet<String> targetColumns = temporaryTableColumnsFor(tableColumns, tableName);
            for (String column : splitTopLevelComma(matcher.group("columns"))) {
                String columnName = unquoteIdentifier(lastIdentifierPart(column.strip()));
                if (!columnName.isBlank()) {
                    addColumnIfAbsentIgnoreCase(targetColumns, columnName);
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
        if (item.regionMatches(true, 0, "DISTINCT ", 0, "DISTINCT ".length())) {
            item = item.substring("DISTINCT ".length()).stripLeading();
        }
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

    private String addTemporaryInsertSelectColumnLists(
            String sql,
            Map<String, LinkedHashSet<String>> temporaryTableColumns
    ) {
        if (temporaryTableColumns.isEmpty()) {
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
            } else if (startsKeyword(sql, index, "INSERT")) {
                int end = findStatementTerminator(sql, index);
                String statement = sql.substring(index, end);
                String rewritten = addTemporaryInsertSelectColumnList(statement, temporaryTableColumns);
                converted.append(rewritten);
                changed = changed || !rewritten.equals(statement);
                index = end;
            } else {
                converted.append(current);
                index++;
            }
        }
        return changed ? converted.toString() : sql;
    }

    private String addTemporaryInsertSelectColumnList(
            String statement,
            Map<String, LinkedHashSet<String>> temporaryTableColumns
    ) {
        int start = skipWhitespace(statement, 0);
        if (!startsKeyword(statement, start, "INSERT")) {
            return statement;
        }
        int cursor = skipWhitespace(statement, start + "INSERT".length());
        if (!startsKeyword(statement, cursor, "INTO")) {
            return statement;
        }
        cursor = skipWhitespace(statement, cursor + "INTO".length());
        SqlIdentifierReference table = sqlIdentifierReferenceAt(statement, cursor);
        if (table == null || !isProcedureTemporaryTableName(table.token())) {
            return statement;
        }
        if (temporaryTableColumns(temporaryTableColumns, table.token()) == null) {
            return statement;
        }
        cursor = skipWhitespace(statement, table.end());
        if (cursor < statement.length() && statement.charAt(cursor) == '(') {
            return statement;
        }
        if (!startsKeyword(statement, cursor, "SELECT")) {
            return statement;
        }
        String selectTail = statement.substring(cursor + "SELECT".length());
        int fromIndex = topLevelKeywordIndex(selectTail, "FROM");
        if (fromIndex < 0) {
            return statement;
        }
        List<String> columns = selectListColumns(selectTail.substring(0, fromIndex));
        if (columns.isEmpty()) {
            return statement;
        }
        return statement.substring(0, table.end())
                + " (" + String.join(", ", columns) + ")"
                + statement.substring(table.end());
    }

    private InsertValuesColumnListRewrite addKnownInsertValuesColumnLists(
            String sql,
            Map<String, LinkedHashSet<String>> tableColumns,
            Map<String, String> identityFirstColumns
    ) {
        if (tableColumns == null || tableColumns.isEmpty()) {
            return InsertValuesColumnListRewrite.unchanged(sql);
        }
        StringBuilder converted = new StringBuilder(sql.length());
        LinkedHashSet<String> appliedRules = new LinkedHashSet<>();
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
            } else if (startsKeyword(sql, index, "INSERT")) {
                int end = findStatementTerminator(sql, index);
                String statement = sql.substring(index, end);
                InsertValuesColumnListRewrite rewritten =
                        addKnownInsertValuesColumnList(statement, tableColumns, identityFirstColumns);
                converted.append(rewritten.sql());
                appliedRules.addAll(rewritten.appliedRules());
                index = end;
            } else {
                converted.append(current);
                index++;
            }
        }
        return appliedRules.isEmpty()
                ? InsertValuesColumnListRewrite.unchanged(sql)
                : new InsertValuesColumnListRewrite(converted.toString(), List.copyOf(appliedRules));
    }

    private InsertValuesColumnListRewrite addKnownInsertValuesColumnList(
            String statement,
            Map<String, LinkedHashSet<String>> tableColumns,
            Map<String, String> identityFirstColumns
    ) {
        int start = skipWhitespace(statement, 0);
        if (!startsKeyword(statement, start, "INSERT")) {
            return InsertValuesColumnListRewrite.unchanged(statement);
        }
        int cursor = skipWhitespace(statement, start + "INSERT".length());
        if (!startsKeyword(statement, cursor, "INTO")) {
            return InsertValuesColumnListRewrite.unchanged(statement);
        }
        cursor = skipWhitespace(statement, cursor + "INTO".length());
        SqlIdentifierReference table = sqlIdentifierReferenceAt(statement, cursor);
        if (table == null) {
            return InsertValuesColumnListRewrite.unchanged(statement);
        }
        LinkedHashSet<String> columns = temporaryTableColumns(tableColumns, table.token());
        if (columns == null || columns.isEmpty()) {
            return InsertValuesColumnListRewrite.unchanged(statement);
        }
        cursor = skipWhitespace(statement, table.end());
        if (cursor < statement.length() && statement.charAt(cursor) == '(') {
            return InsertValuesColumnListRewrite.unchanged(statement);
        }
        boolean valueKeyword;
        if (startsKeyword(statement, cursor, "VALUES")) {
            cursor = skipWhitespace(statement, cursor + "VALUES".length());
            valueKeyword = true;
        } else if (startsKeyword(statement, cursor, "VALUE")) {
            cursor = skipWhitespace(statement, cursor + "VALUE".length());
            valueKeyword = true;
        } else {
            valueKeyword = false;
        }
        if (!valueKeyword) {
            return InsertValuesColumnListRewrite.unchanged(statement);
        }
        List<InsertValuesTuple> tuples = insertValuesTuples(statement, cursor);
        Integer valueCount = insertValuesColumnCount(tuples);
        if (valueCount == null) {
            return InsertValuesColumnListRewrite.unchanged(statement);
        }
        List<String> columnList = new ArrayList<>(columns);
        String identityFirstColumn = identityFirstColumn(identityFirstColumns, table.token());
        if (identityFirstColumn != null && identifiersEqual(columnList.get(0), identityFirstColumn)) {
            if (valueCount > columnList.size()) {
                return InsertValuesColumnListRewrite.unchanged(statement);
            }
            if (valueCount > 1 && allTuplesStartWithGeneratedIdentityPlaceholder(tuples)) {
                return new InsertValuesColumnListRewrite(
                        rewriteInsertValuesWithColumnList(
                                statement,
                                table.end(),
                                columnList.subList(1, valueCount),
                                tuples,
                                1
                        ),
                        List.of(MYSQL_INSERT_NULL_IDENTITY_VALUES_COLUMN_LIST_RULE)
                );
            }
            if (allTuplesStartWithExplicitIdentityValue(tuples)) {
                String rewritten = rewriteInsertValuesWithColumnList(
                        statement,
                        table.end(),
                        columnList.subList(0, valueCount),
                        tuples,
                        0
                );
                return new InsertValuesColumnListRewrite(
                        wrapExplicitIdentityInsert(table.token(), rewritten),
                        List.of(MYSQL_INSERT_EXPLICIT_IDENTITY_VALUES_COLUMN_LIST_RULE)
                );
            }
            return InsertValuesColumnListRewrite.unchanged(statement);
        }
        if (valueCount != columns.size()) {
            return InsertValuesColumnListRewrite.unchanged(statement);
        }
        String rewritten = statement.substring(0, table.end())
                + " (" + String.join(", ", columns) + ")"
                + statement.substring(table.end());
        return new InsertValuesColumnListRewrite(rewritten, List.of(MYSQL_INSERT_VALUES_COLUMN_LIST_RULE));
    }

    private String identityFirstColumn(Map<String, String> identityFirstColumns, String tableToken) {
        if (identityFirstColumns == null || identityFirstColumns.isEmpty()) {
            return null;
        }
        return identityFirstColumns.get(normalizedTableKey(tableToken));
    }

    private boolean identifiersEqual(String left, String right) {
        return normalizedIdentifierKey(left).equals(normalizedIdentifierKey(right));
    }

    private boolean allTuplesStartWithGeneratedIdentityPlaceholder(List<InsertValuesTuple> tuples) {
        if (tuples.isEmpty()) {
            return false;
        }
        for (InsertValuesTuple tuple : tuples) {
            if (tuple.values().isEmpty() || !isGeneratedIdentityPlaceholder(tuple.values().get(0))) {
                return false;
            }
        }
        return true;
    }

    private boolean allTuplesStartWithExplicitIdentityValue(List<InsertValuesTuple> tuples) {
        if (tuples.isEmpty()) {
            return false;
        }
        for (InsertValuesTuple tuple : tuples) {
            if (tuple.values().isEmpty() || isGeneratedIdentityPlaceholder(tuple.values().get(0))) {
                return false;
            }
        }
        return true;
    }

    private boolean isGeneratedIdentityPlaceholder(String value) {
        return Pattern.compile("(?is)^\\s*(?:NULL|DEFAULT)\\s*$")
                .matcher(value)
                .matches();
    }

    private String rewriteInsertValuesWithColumnList(
            String statement,
            int tableEnd,
            List<String> columns,
            List<InsertValuesTuple> tuples,
            int valueOffset
    ) {
        if (columns.isEmpty() || tuples.isEmpty()) {
            return statement;
        }
        StringBuilder rewritten = new StringBuilder(statement.length());
        rewritten.append(statement, 0, tableEnd)
                .append(" (")
                .append(String.join(", ", columns))
                .append(")");
        int cursor = tableEnd;
        for (InsertValuesTuple tuple : tuples) {
            rewritten.append(statement, cursor, tuple.openParen());
            rewritten.append("(")
                    .append(String.join(", ", tuple.values().subList(valueOffset, tuple.values().size()).stream()
                            .map(String::strip)
                            .toList()))
                    .append(")");
            cursor = tuple.closeParen() + 1;
        }
        rewritten.append(statement, cursor, statement.length());
        return rewritten.toString();
    }

    private String wrapExplicitIdentityInsert(String tableToken, String statement) {
        return "SET IDENTITY_INSERT " + tableToken + " ON;\n"
                + statement + ";\n"
                + "SET IDENTITY_INSERT " + tableToken + " OFF";
    }

    private Integer insertValuesColumnCount(String statement, int valuesIndex) {
        return insertValuesColumnCount(insertValuesTuples(statement, valuesIndex));
    }

    private Integer insertValuesColumnCount(List<InsertValuesTuple> tuples) {
        if (tuples.isEmpty()) {
            return null;
        }
        Integer columnCount = null;
        for (InsertValuesTuple tuple : tuples) {
            int currentCount = tuple.values().size();
            if (currentCount == 0 || (columnCount != null && columnCount != currentCount)) {
                return null;
            }
            columnCount = currentCount;
        }
        return columnCount;
    }

    private List<InsertValuesTuple> insertValuesTuples(String statement, int valuesIndex) {
        int cursor = skipWhitespace(statement, valuesIndex);
        List<InsertValuesTuple> tuples = new ArrayList<>();
        Integer columnCount = null;
        while (cursor < statement.length() && statement.charAt(cursor) == '(') {
            int closeParen = findMatchingParen(statement, cursor);
            if (closeParen <= cursor) {
                return List.of();
            }
            List<String> values = splitTopLevelComma(statement.substring(cursor + 1, closeParen));
            int currentCount = values.size();
            if (currentCount == 0 || (columnCount != null && columnCount != currentCount)) {
                return List.of();
            }
            columnCount = currentCount;
            tuples.add(new InsertValuesTuple(cursor, closeParen, values));
            cursor = skipWhitespace(statement, closeParen + 1);
            if (cursor < statement.length() && statement.charAt(cursor) == ',') {
                cursor = skipWhitespace(statement, cursor + 1);
            } else {
                break;
            }
        }
        return tuples;
    }

    private boolean endsWithReservedSelectWord(String value) {
        String stripped = value.stripTrailing();
        return Pattern.compile("(?is).+\\b(?:WHEN|THEN|ELSE|END|FROM|WHERE|AND|OR|ON|IN|IS|NOT|NULL)$")
                .matcher(stripped)
                .matches();
    }

    private String convertMysqlProcedureTemporaryInsertIgnore(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        Map<String, List<String>> keyColumnsByTable = temporaryTableKeyColumnsByLowercase(sql);
        if (keyColumnsByTable.isEmpty()) {
            return sql;
        }
        StringBuilder converted = new StringBuilder(sql.length());
        int cursor = 0;
        int index = firstProcedureBegin(sql);
        boolean changed = false;
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
            } else if (startsKeyword(sql, index, "INSERT")) {
                TemporaryInsertIgnoreSelect insert = readTemporaryInsertIgnoreSelect(sql, index, keyColumnsByTable);
                if (insert == null) {
                    index++;
                } else {
                    converted.append(sql, cursor, insert.start());
                    converted.append(insert.mergeSql());
                    cursor = insert.end();
                    index = insert.end();
                    changed = true;
                }
            } else {
                index++;
            }
        }
        if (!changed) {
            return sql;
        }
        converted.append(sql.substring(cursor));
        return converted.toString();
    }

    private Map<String, List<String>> temporaryTableKeyColumnsByLowercase(String sql) {
        LinkedHashMap<String, List<String>> keys = new LinkedHashMap<>();
        int index = firstProcedureBegin(sql);
        while (index >= 0 && index < sql.length()) {
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
            } else if (startsKeyword(sql, index, "CREATE")) {
                TemporaryTableKey tableKey = readTemporaryTableKey(sql, index);
                if (tableKey == null) {
                    tableKey = readProcedureCreateTableLikeKey(sql, index);
                }
                if (tableKey == null) {
                    index++;
                } else {
                    keys.putIfAbsent(tableKey.tableKey(), tableKey.keyColumns());
                    index = tableKey.end();
                }
            } else {
                index++;
            }
        }
        return keys;
    }

    private TemporaryTableKey readTemporaryTableKey(String sql, int createIndex) {
        int cursor = skipWhitespace(sql, createIndex + "CREATE".length());
        if (startsKeyword(sql, cursor, "TEMPORARY")) {
            cursor = skipWhitespace(sql, cursor + "TEMPORARY".length());
        }
        if (!startsKeyword(sql, cursor, "TABLE")) {
            return null;
        }
        cursor = skipWhitespace(sql, cursor + "TABLE".length());
        if (startsKeyword(sql, cursor, "IF")) {
            cursor = skipWhitespace(sql, cursor + "IF".length());
            if (!startsKeyword(sql, cursor, "NOT")) {
                return null;
            }
            cursor = skipWhitespace(sql, cursor + "NOT".length());
            if (!startsKeyword(sql, cursor, "EXISTS")) {
                return null;
            }
            cursor = skipWhitespace(sql, cursor + "EXISTS".length());
        }
        SqlIdentifierReference table = sqlIdentifierReferenceAt(sql, cursor);
        if (table == null) {
            return null;
        }
        int openParen = skipWhitespace(sql, table.end());
        if (openParen >= sql.length() || sql.charAt(openParen) != '(') {
            return null;
        }
        int closeParen = findMatchingParen(sql, openParen);
        if (closeParen <= openParen) {
            return null;
        }
        List<String> keyColumns = temporaryTableKeyColumns(sql.substring(openParen + 1, closeParen));
        if (keyColumns.isEmpty()) {
            return null;
        }
        return new TemporaryTableKey(normalizedTableKey(table.token()), keyColumns, closeParen + 1);
    }

    private TemporaryTableKey readProcedureCreateTableLikeKey(String sql, int createIndex) {
        Matcher matcher = Pattern.compile(
                "(?is)^CREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                        + "(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+LIKE\\s+(?<source>" + SQL_IDENTIFIER_TOKEN + ")\\b"
        ).matcher(sql.substring(createIndex).stripLeading());
        if (!matcher.find()) {
            return null;
        }
        String tableName = unquoteIdentifier(lastIdentifierPart(matcher.group("table").strip()));
        if (!tableName.toLowerCase(Locale.ROOT).contains("_bak_")) {
            return null;
        }
        return new TemporaryTableKey(
                normalizedTableKey(matcher.group("table")),
                List.of("ID"),
                createIndex + matcher.end()
        );
    }

    private List<String> temporaryTableKeyColumns(String body) {
        List<String> uniqueColumns = List.of();
        for (String part : splitTopLevelComma(body)) {
            String stripped = part.strip();
            List<String> primaryKeyColumns = temporaryTablePrimaryKeyColumns(stripped);
            if (!primaryKeyColumns.isEmpty()) {
                return primaryKeyColumns;
            }
            if (uniqueColumns.isEmpty()) {
                uniqueColumns = temporaryTableUniqueKeyColumns(stripped);
            }
        }
        return uniqueColumns;
    }

    private List<String> temporaryTablePrimaryKeyColumns(String definition) {
        int cursor = skipWhitespace(definition, 0);
        if (startsKeyword(definition, cursor, "PRIMARY")) {
            int openParen = findTopLevelChar(definition, '(', cursor + "PRIMARY".length());
            return indexColumnNamesInParentheses(definition, openParen);
        }
        SqlIdentifierReference column = sqlIdentifierReferenceAt(definition, cursor);
        if (column == null) {
            return List.of();
        }
        String attributes = definition.substring(column.end());
        return Pattern.compile("(?is)\\bPRIMARY\\s+KEY\\b").matcher(attributes).find()
                ? List.of(unquoteIdentifier(lastIdentifierPart(column.token())))
                : List.of();
    }

    private List<String> temporaryTableUniqueKeyColumns(String definition) {
        int cursor = skipWhitespace(definition, 0);
        if (!startsKeyword(definition, cursor, "UNIQUE")) {
            return List.of();
        }
        int openParen = findTopLevelChar(definition, '(', cursor + "UNIQUE".length());
        return indexColumnNamesInParentheses(definition, openParen);
    }

    private List<String> indexColumnNamesInParentheses(String definition, int openParen) {
        if (openParen < 0) {
            return List.of();
        }
        int closeParen = findMatchingParen(definition, openParen);
        if (closeParen <= openParen) {
            return List.of();
        }
        return indexColumnNames(definition.substring(openParen + 1, closeParen));
    }

    private TemporaryInsertIgnoreSelect readTemporaryInsertIgnoreSelect(
            String sql,
            int insertIndex,
            Map<String, List<String>> keyColumnsByTable
    ) {
        int cursor = skipWhitespace(sql, insertIndex + "INSERT".length());
        if (!startsKeyword(sql, cursor, "IGNORE")) {
            return null;
        }
        cursor = skipWhitespace(sql, cursor + "IGNORE".length());
        if (!startsKeyword(sql, cursor, "INTO")) {
            return null;
        }
        cursor = skipWhitespace(sql, cursor + "INTO".length());
        SqlIdentifierReference table = sqlIdentifierReferenceAt(sql, cursor);
        if (table == null) {
            return null;
        }
        List<String> keyColumns = keyColumnsByTable.get(normalizedTableKey(table.token()));
        if (keyColumns == null || keyColumns.isEmpty()) {
            return null;
        }
        int openParen = skipWhitespace(sql, table.end());
        if (openParen >= sql.length() || sql.charAt(openParen) != '(') {
            return null;
        }
        int closeParen = findMatchingParen(sql, openParen);
        if (closeParen <= openParen) {
            return null;
        }
        List<String> targetColumns = insertTargetColumns(sql.substring(openParen + 1, closeParen));
        if (targetColumns.isEmpty()) {
            return null;
        }
        cursor = skipWhitespace(sql, closeParen + 1);
        if (!startsKeyword(sql, cursor, "SELECT")) {
            return null;
        }
        int statementEnd = findStatementTerminator(sql, cursor);
        String selectBody = sql.substring(cursor + "SELECT".length(), statementEnd);
        int fromIndex = topLevelKeywordIndex(selectBody, "FROM");
        if (fromIndex < 0) {
            return null;
        }
        List<String> selectItems = splitTopLevelComma(selectBody.substring(0, fromIndex));
        if (selectItems.size() != targetColumns.size()) {
            return null;
        }
        String fromTail = selectBody.substring(fromIndex).strip();
        String mergeSql = temporaryInsertIgnoreMergeSql(table.token(), targetColumns, selectItems, fromTail, keyColumns);
        return mergeSql.isBlank() ? null : new TemporaryInsertIgnoreSelect(insertIndex, statementEnd, mergeSql);
    }

    private List<String> insertTargetColumns(String columns) {
        List<String> targetColumns = new ArrayList<>();
        for (String column : splitTopLevelComma(columns)) {
            String columnName = unquoteIdentifier(lastIdentifierPart(column.strip()));
            if (columnName.isBlank()) {
                return List.of();
            }
            targetColumns.add(columnName);
        }
        return targetColumns;
    }

    private String temporaryInsertIgnoreMergeSql(
            String tableToken,
            List<String> targetColumns,
            List<String> selectItems,
            String fromTail,
            List<String> keyColumns
    ) {
        List<String> normalizedTargetColumns = targetColumns.stream()
                .map(this::normalizedIdentifierKey)
                .toList();
        for (String keyColumn : keyColumns) {
            if (!normalizedTargetColumns.contains(normalizedIdentifierKey(keyColumn))) {
                return "";
            }
        }
        StringBuilder merge = new StringBuilder();
        merge.append("MERGE INTO ")
                .append(tableToken.strip())
                .append(" t\n")
                .append("USING (\n")
                .append("    SELECT ");
        for (int i = 0; i < targetColumns.size(); i++) {
            if (i > 0) {
                merge.append(", ");
            }
            merge.append(stripSelectItemAlias(selectItems.get(i).strip()))
                    .append(" AS ")
                    .append(dmSimpleIdentifier(targetColumns.get(i)));
        }
        merge.append("\n    ")
                .append(fromTail)
                .append("\n")
                .append(") s\n")
                .append("ON (");
        for (int i = 0; i < keyColumns.size(); i++) {
            if (i > 0) {
                merge.append(" AND ");
            }
            String column = dmSimpleIdentifier(keyColumns.get(i));
            merge.append("t.")
                    .append(column)
                    .append(" = s.")
                    .append(column);
        }
        merge.append(")\nWHEN NOT MATCHED THEN INSERT (");
        for (int i = 0; i < targetColumns.size(); i++) {
            if (i > 0) {
                merge.append(", ");
            }
            merge.append(dmSimpleIdentifier(targetColumns.get(i)));
        }
        merge.append(") VALUES (");
        for (int i = 0; i < targetColumns.size(); i++) {
            if (i > 0) {
                merge.append(", ");
            }
            merge.append("s.").append(dmSimpleIdentifier(targetColumns.get(i)));
        }
        merge.append(")");
        return merge.toString();
    }

    private String convertMysqlProcedureDynamicInsertIgnore(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        Map<String, List<String>> keyColumnsByTable = temporaryTableKeyColumnsByLowercase(sql);
        if (keyColumnsByTable.isEmpty()) {
            return sql;
        }
        StringBuilder converted = new StringBuilder(sql.length());
        int index = 0;
        boolean changed = false;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                SingleQuotedStringRewrite rewrite = convertDynamicInsertIgnoreStringLiteral(
                        sql,
                        index,
                        keyColumnsByTable
                );
                converted.append(rewrite.value());
                index = rewrite.endIndex();
                changed = changed || rewrite.changed();
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

    private SingleQuotedStringRewrite convertDynamicInsertIgnoreStringLiteral(
            String sql,
            int start,
            Map<String, List<String>> keyColumnsByTable
    ) {
        SingleQuotedStringContent literal = readSingleQuotedStringContent(sql, start);
        if (!literal.closed()) {
            return new SingleQuotedStringRewrite(sql.substring(start), sql.length(), false);
        }
        String decoded = decodeMysqlBackslashEscapedString(literal.rawContent());
        int insertIndex = keywordIndex(decoded, "INSERT");
        if (insertIndex < 0) {
            return new SingleQuotedStringRewrite(sql.substring(start, literal.endIndex()), literal.endIndex(), false);
        }
        TemporaryInsertIgnoreSelect insert = readTemporaryInsertIgnoreSelect(decoded, insertIndex, keyColumnsByTable);
        if (insert == null) {
            return new SingleQuotedStringRewrite(sql.substring(start, literal.endIndex()), literal.endIndex(), false);
        }
        String convertedSql = decoded.substring(0, insert.start())
                + insert.mergeSql()
                + decoded.substring(insert.end());
        return new SingleQuotedStringRewrite(sqlStringLiteral(convertedSql), literal.endIndex(), true);
    }

    private SingleQuotedStringContent readSingleQuotedStringContent(String sql, int start) {
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
                return new SingleQuotedStringContent(rawContent.toString(), index + 1, true);
            } else {
                rawContent.append(current);
                index++;
            }
        }
        return new SingleQuotedStringContent(rawContent.toString(), sql.length(), false);
    }

    private String stripSelectItemAlias(String item) {
        Matcher explicitAliasMatcher = Pattern.compile("(?is)^(.+?)\\s+AS\\s+(" + SQL_IDENTIFIER_TOKEN + ")\\s*$")
                .matcher(item);
        if (explicitAliasMatcher.matches()) {
            return explicitAliasMatcher.group(1).strip();
        }
        Matcher implicitAliasMatcher = Pattern.compile(
                "(?is)^(.+?)\\s+([A-Za-z_][A-Za-z0-9_$]*|`[^`]+`|\"[^\"]+\")\\s*$"
        ).matcher(item);
        if (implicitAliasMatcher.matches()
                && !endsWithReservedSelectWord(implicitAliasMatcher.group(1))) {
            return implicitAliasMatcher.group(1).strip();
        }
        return item;
    }

    private String dmSimpleIdentifier(String identifier) {
        String unquoted = unquoteIdentifier(lastIdentifierPart(identifier.strip()));
        if (Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*").matcher(unquoted).matches()) {
            return unquoted;
        }
        return "\"" + unquoted.replace("\"", "\"\"") + "\"";
    }

    private String normalizedTableKey(String tableToken) {
        return normalizedIdentifierKey(unquoteIdentifier(lastIdentifierPart(tableToken.strip())));
    }

    private String normalizedIdentifierKey(String identifier) {
        return unquoteIdentifier(lastIdentifierPart(identifier.strip())).toLowerCase(Locale.ROOT);
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
        if (lower.equals("menu_id")
                || lower.endsWith("_menu_id")
                || lower.equals("module_id")
                || lower.endsWith("_module_id")) {
            return "VARCHAR(200)";
        }
        if (lower.equals("enterprise_id")
                || lower.equals("organization_id")
                || lower.equals("id")
                || lower.equals("orderindex")
                || lower.equals("target_ver")
                || lower.endsWith("_id")
                || lower.endsWith("id")) {
            return "BIGINT";
        }
        if (lower.endsWith("code") || lower.endsWith("name")) {
            return "VARCHAR(200)";
        }
        return "VARCHAR(4000)";
    }

    private SafeRuleConversion applyScriptSafeRules(
            String sql,
            Map<String, LinkedHashSet<String>> scriptTableColumns,
            Map<String, String> scriptIdentityFirstColumns
    ) {
        if (sql == null || sql.isBlank()) {
            return new SafeRuleConversion(sql == null ? "" : sql, false, List.of());
        }
        String converted = sql;
        List<String> rules = new ArrayList<>();

        String foreignKeyChecksSql = convertMysqlForeignKeyChecksToNoop(converted);
        if (!foreignKeyChecksSql.equals(converted)) {
            converted = foreignKeyChecksSql;
            rules.add(MYSQL_FOREIGN_KEY_CHECKS_NOOP_RULE);
        }

        String dropProcedureSql = addDropProcedureIfExists(converted);
        if (!dropProcedureSql.equals(converted)) {
            converted = dropProcedureSql;
            rules.add(MYSQL_DROP_PROCEDURE_IF_EXISTS_RULE);
        }

        String temporaryTableAsSelectSql = convertTopLevelMysqlTemporaryTableAsSelect(converted);
        if (!temporaryTableAsSelectSql.equals(converted)) {
            converted = temporaryTableAsSelectSql;
            rules.add(MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE);
        }

        String temporaryIndexSql = convertTopLevelTemporaryIndexToNoop(converted);
        if (!temporaryIndexSql.equals(converted)) {
            converted = temporaryIndexSql;
            rules.add(MYSQL_TEMPORARY_INDEX_NOOP_RULE);
        }

        String alterModifySql = normalizeMysqlAlterModifySyntax(converted);
        if (!alterModifySql.equals(converted)) {
            converted = alterModifySql;
            rules.add(MYSQL_ALTER_MODIFY_COLUMN_TO_DM_RULE);
        }

        String alterChangeSql = normalizeMysqlAlterChangeSyntax(converted);
        if (!alterChangeSql.equals(converted)) {
            converted = alterChangeSql;
            rules.add(MYSQL_ALTER_MODIFY_COLUMN_TO_DM_RULE);
        }

        String callArgumentCommentSql = removeMysqlCallArgumentLineComments(converted);
        if (!callArgumentCommentSql.equals(converted)) {
            converted = callArgumentCommentSql;
            rules.add(MYSQL_CALL_ARGUMENT_LINE_COMMENT_REMOVAL_RULE);
        }

        String clobCallBlockSql = convertLongClobCallArgumentsToBlock(converted);
        if (!clobCallBlockSql.equals(converted)) {
            converted = clobCallBlockSql;
            rules.add(DM_LONG_CLOB_CALL_ARGUMENT_BLOCK_RULE);
        }

        String simpleDateEndTriggerSql = convertMysqlSimpleDateEndTrigger(converted);
        if (!simpleDateEndTriggerSql.equals(converted)) {
            converted = simpleDateEndTriggerSql;
            rules.add(MYSQL_SIMPLE_DATE_END_TRIGGER_TO_DM_RULE);
        }

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

        String functionSql = convertCreateFunctionHeader(converted);
        if (!functionSql.equals(converted)) {
            converted = functionSql;
            rules.add(MYSQL_CREATE_FUNCTION_TO_DM_RULE);
        }

        String procedureIdentifierSql = normalizeMysqlProcedureIdentifiers(converted);
        if (!procedureIdentifierSql.equals(converted)) {
            converted = procedureIdentifierSql;
            rules.add(MYSQL_PROCEDURE_IDENTIFIER_TO_DM_RULE);
        }

        String reservedCursorSql = renameReservedProcedureCursorNames(converted);
        if (!reservedCursorSql.equals(converted)) {
            converted = reservedCursorSql;
            rules.add(MYSQL_PROCEDURE_RESERVED_CURSOR_RENAME_RULE);
        }

        String localLabelSql = convertMysqlProcedureBeginLabels(converted);
        if (!localLabelSql.equals(converted)) {
            converted = localLabelSql;
            rules.add(MYSQL_PROCEDURE_LOCAL_LABEL_TO_RETURN_RULE);
        }

        String cursorHandlerLoopSql = convertMysqlCursorHandlerLoops(converted);
        if (!cursorHandlerLoopSql.equals(converted)) {
            converted = cursorHandlerLoopSql;
            rules.add(MYSQL_PROCEDURE_CURSOR_HANDLER_TO_LOOP_RULE);
        }

        String controlFlowSql = convertMysqlProcedureControlFlowSyntax(converted);
        if (!controlFlowSql.equals(converted)) {
            converted = controlFlowSql;
            rules.add(MYSQL_PROCEDURE_CONTROL_FLOW_TO_DM_RULE);
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

        String assignedInParameterSql = convertAssignedMysqlInParametersToLocals(converted);
        if (!assignedInParameterSql.equals(converted)) {
            converted = assignedInParameterSql;
            rules.add(MYSQL_PROCEDURE_ASSIGNED_IN_PARAM_TO_LOCAL_RULE);
        }

        String sessionSetSql = convertMysqlProcedureSessionSetToNoop(converted);
        if (!sessionSetSql.equals(converted)) {
            converted = sessionSetSql;
            rules.add(MYSQL_PROCEDURE_SESSION_SET_NOOP_RULE);
        }

        String dynamicPrepareSql = convertMysqlProcedureDynamicPrepare(converted);
        if (!dynamicPrepareSql.equals(converted)) {
            converted = dynamicPrepareSql;
            rules.add(MYSQL_PROCEDURE_DYNAMIC_PREPARE_TO_EXECUTE_IMMEDIATE_RULE);
        }

        String metadataSql = replaceOutsideIgnoredText(converted, SCRIPT_METADATA_REPLACEMENTS);
        if (!metadataSql.equals(converted)) {
            converted = metadataSql;
            rules.add(MYSQL_SCRIPT_METADATA_TO_DM_RULE);
        }

        String metadataIdentifierCaseSql = normalizeDamengMetadataIdentifierComparisons(converted);
        if (!metadataIdentifierCaseSql.equals(converted)) {
            converted = metadataIdentifierCaseSql;
            rules.add(DM_METADATA_IDENTIFIER_CASE_RULE);
        }

        String systemMetadataScalarIdSql = convertSystemMetadataScalarIdSubqueries(converted);
        if (!systemMetadataScalarIdSql.equals(converted)) {
            converted = systemMetadataScalarIdSql;
            rules.add(MYSQL_SYSTEM_METADATA_SCALAR_ID_TO_MIN_RULE);
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

        String procedureIfExistsSql = convertMysqlProcedureIfExistsConditions(converted);
        if (!procedureIfExistsSql.equals(converted)) {
            converted = procedureIfExistsSql;
            rules.add(MYSQL_PROCEDURE_IF_EXISTS_TO_COUNT_RULE);
        }

        String countGuardIndexSql = synchronizeSchemaScopedIndexNames(converted);
        if (!countGuardIndexSql.equals(converted)) {
            converted = countGuardIndexSql;
            rules.add(MYSQL_SCHEMA_SCOPED_INDEX_NAME_RULE);
        }

        String signalSql = convertMysqlProcedureSignal(converted);
        if (!signalSql.equals(converted)) {
            converted = signalSql;
            rules.add(MYSQL_PROCEDURE_SIGNAL_TO_RAISE_APPLICATION_ERROR_RULE);
        }

        String procedureLocalSetSql = convertMysqlProcedureLocalSetAssignments(converted);
        if (!procedureLocalSetSql.equals(converted)) {
            converted = procedureLocalSetSql;
            rules.add(MYSQL_PROCEDURE_LOCAL_SET_TO_ASSIGNMENT_RULE);
            if (procedureLocalSetSql.contains("CAST(JSON_UNQUOTE(JSON_EXTRACT")) {
                rules.add(MYSQL_PROCEDURE_JSON_TEXT_TYPE_RULE);
            }
        }

        String procedureQuoteSql = convertMysqlQuoteCallsInProcedure(converted);
        if (!procedureQuoteSql.equals(converted)) {
            converted = procedureQuoteSql;
            rules.add(MYSQL_PROCEDURE_QUOTE_TO_DM_RULE);
        }

        String procedureJsonTimestampSql = convertProcedureJsonTimestampValues(converted);
        if (!procedureJsonTimestampSql.equals(converted)) {
            converted = procedureJsonTimestampSql;
            rules.add(MYSQL_PROCEDURE_JSON_TIMESTAMP_TO_CHAR_RULE);
        }

        String procedureDateTimeSql = convertProcedureDateTimeFunctions(converted);
        if (!procedureDateTimeSql.equals(converted)) {
            converted = procedureDateTimeSql;
            rules.add(MYSQL_PROCEDURE_DATE_TIME_TO_DM_RULE);
        }

        String tempInsertIgnoreSql = convertMysqlProcedureTemporaryInsertIgnore(converted);
        if (!tempInsertIgnoreSql.equals(converted)) {
            converted = tempInsertIgnoreSql;
            rules.add(MYSQL_PROCEDURE_INSERT_IGNORE_TEMP_TO_MERGE_RULE);
        }

        String dynamicInsertIgnoreSql = convertMysqlProcedureDynamicInsertIgnore(converted);
        if (!dynamicInsertIgnoreSql.equals(converted)) {
            converted = dynamicInsertIgnoreSql;
            rules.add(MYSQL_PROCEDURE_DYNAMIC_INSERT_IGNORE_TO_MERGE_RULE);
        }

        String deleteAliasStarSql = convertMysqlProcedureDeleteAliasStar(converted);
        if (!deleteAliasStarSql.equals(converted)) {
            converted = deleteAliasStarSql;
            rules.add(MYSQL_PROCEDURE_DELETE_ALIAS_STAR_RULE);
        }

        String jsonEscapeSql = normalizeMysqlJsonEscapesInSqlStringLiterals(converted);
        if (!jsonEscapeSql.equals(converted)) {
            converted = jsonEscapeSql;
            rules.add(MYSQL_SQL_STRING_JSON_ESCAPE_TO_DM_RULE);
        }

        String clobEmptyStringSql = convertProcedureClobEmptyStringChecks(converted);
        if (!clobEmptyStringSql.equals(converted)) {
            converted = clobEmptyStringSql;
            rules.add(DM_PROCEDURE_CLOB_EMPTY_STRING_CHECK_RULE);
        }

        String procedureDdlSql = wrapProcedureDdlStatements(converted);
        if (!procedureDdlSql.equals(converted)) {
            converted = procedureDdlSql;
            rules.add(MYSQL_PROCEDURE_DDL_TO_EXECUTE_IMMEDIATE_RULE);
        }

        InsertValuesColumnListRewrite insertValuesColumnListSql = addKnownInsertValuesColumnLists(
                converted,
                scriptTableColumns,
                scriptIdentityFirstColumns
        );
        if (!insertValuesColumnListSql.appliedRules().isEmpty()) {
            converted = insertValuesColumnListSql.sql();
            rules.addAll(insertValuesColumnListSql.appliedRules());
        }

        String procedureSysTimeSql = addProcedureMissingSysTimeInsertValues(converted);
        if (!procedureSysTimeSql.equals(converted)) {
            converted = procedureSysTimeSql;
            rules.add(MYSQL_PROCEDURE_MISSING_SYS_TIME_RULE);
        }

        String resourceColumnInsertOnlySql = convertResourceColumnBatchMergeToInsertOnly(converted);
        if (!resourceColumnInsertOnlySql.equals(converted)) {
            converted = resourceColumnInsertOnlySql;
            rules.add(DM_RESOURCECOLUMN_INSERT_ONLY_MERGE_RULE);
        }

        String procedureGroupBySql = qualifyProcedureAmbiguousGroupByColumns(converted);
        if (!procedureGroupBySql.equals(converted)) {
            converted = procedureGroupBySql;
            rules.add(MYSQL_PROCEDURE_GROUP_BY_ALIAS_RULE);
        }

        return new SafeRuleConversion(converted, !rules.isEmpty(), rules);
    }

    private String convertMysqlSimpleDateEndTrigger(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*CREATE\\s+TRIGGER\\s+(?<name>" + SQL_IDENTIFIER_TOKEN + ")\\s+"
                        + "BEFORE\\s+(?<event>INSERT|UPDATE)\\s+ON\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+"
                        + "FOR\\s+EACH\\s+ROW\\s+BEGIN\\s+"
                        + "IF\\s+NEW\\.(?<checkColumn>" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s+IS\\s+NOT\\s+NULL\\s+THEN\\s+"
                        + "SET\\s+NEW\\.(?<targetColumn>" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*=\\s*"
                        + "CONCAT\\s*\\(\\s*DATE\\s*\\(\\s*NEW\\.(?<dateColumn>" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*\\)\\s*,\\s*"
                        + "'\\s*23:59:59'\\s*\\)\\s*;?\\s+"
                        + "END\\s+IF\\s*;?\\s+END\\s*;?\\s*$"
        ).matcher(sql);
        if (!matcher.matches()) {
            return sql;
        }
        String checkColumn = unquoteIdentifier(matcher.group("checkColumn"));
        String targetColumn = unquoteIdentifier(matcher.group("targetColumn"));
        String dateColumn = unquoteIdentifier(matcher.group("dateColumn"));
        if (checkColumn.isBlank()
                || !checkColumn.equalsIgnoreCase(targetColumn)
                || !checkColumn.equalsIgnoreCase(dateColumn)) {
            return sql;
        }
        String column = checkColumn;
        return "CREATE OR REPLACE TRIGGER "
                + matcher.group("name")
                + "\n    BEFORE "
                + matcher.group("event").toUpperCase(Locale.ROOT)
                + " ON "
                + matcher.group("table")
                + "\n    FOR EACH ROW\n"
                + "BEGIN\n"
                + "    IF :NEW." + column + " IS NOT NULL THEN\n"
                + "        :NEW." + column
                + " := TO_TIMESTAMP(TO_CHAR(:NEW." + column
                + ", 'YYYY-MM-DD') || ' 23:59:59', 'YYYY-MM-DD HH24:MI:SS');\n"
                + "    END IF;\n"
                + "END;";
    }

    boolean isConvertedSimpleDateEndTrigger(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        if (!startsWithKeywords(sql, "CREATE", "OR", "REPLACE", "TRIGGER")
                || !CONVERTED_SIMPLE_DATE_END_TRIGGER_PATTERN.matcher(sql).find()) {
            return false;
        }
        String normalized = sql.toUpperCase(Locale.ROOT);
        return normalized.contains(":NEW.")
                && normalized.contains(":=")
                && normalized.contains("TO_TIMESTAMP")
                && normalized.contains("TO_CHAR")
                && normalized.contains("23:59:59");
    }

    private boolean startsWithKeywords(String sql, String... keywords) {
        int cursor = skipWhitespace(sql, 0);
        for (String keyword : keywords) {
            if (!startsKeyword(sql, cursor, keyword)) {
                return false;
            }
            cursor = skipWhitespace(sql, cursor + keyword.length());
        }
        return true;
    }

    private String convertResourceColumnBatchMergeToInsertOnly(String sql) {
        if (sql == null || sql.isBlank()
                || !Pattern.compile(
                        "(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\s+`?batch_insert_ns_core_resourcecolumn`?\\b"
                ).matcher(sql).find()
                || !Pattern.compile("(?is)\\bMERGE\\s+INTO\\s+`?ns_core_resourcecolumn`?\\b").matcher(sql).find()) {
            return sql == null ? "" : sql;
        }
        return Pattern.compile(
                        "(?is)(\\bMERGE\\s+INTO\\s+`?ns_core_resourcecolumn`?\\s+[A-Za-z_][A-Za-z0-9_$]*\\b"
                                + "(?:(?!\\bWHEN\\s+MATCHED\\b).)*?)"
                                + "\\bWHEN\\s+MATCHED\\s+THEN\\s+UPDATE\\s+SET\\b"
                                + "(?:(?!\\bWHEN\\s+NOT\\s+MATCHED\\s+THEN\\s+INSERT\\b).)*?"
                                + "(\\bWHEN\\s+NOT\\s+MATCHED\\s+THEN\\s+INSERT\\b)"
                )
                .matcher(sql)
                .replaceAll("$1$2");
    }

    private String qualifyProcedureAmbiguousGroupByColumns(String sql) {
        if (sql == null || sql.isBlank()
                || !Pattern.compile("(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\b").matcher(sql).find()) {
            return sql == null ? "" : sql;
        }
        StringBuilder converted = new StringBuilder(sql.length());
        boolean changed = false;
        int index = 0;
        while (index < sql.length()) {
            int end = findStatementTerminator(sql, index);
            String statement = sql.substring(index, end);
            String rewritten = qualifyStatementAmbiguousGroupByColumns(statement);
            converted.append(rewritten);
            changed = changed || !rewritten.equals(statement);
            if (end < sql.length() && sql.charAt(end) == ';') {
                converted.append(';');
                index = end + 1;
            } else {
                index = end;
            }
        }
        return changed ? converted.toString() : sql;
    }

    private String addProcedureMissingSysTimeInsertValues(String sql) {
        if (sql == null || sql.isBlank()
                || !Pattern.compile("(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\b").matcher(sql).find()) {
            return sql == null ? "" : sql;
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
            } else if (startsKeyword(sql, index, "INSERT")) {
                int end = findStatementTerminator(sql, index);
                String statement = sql.substring(index, end);
                String rewritten = addStatementMissingSysTimeInsertValue(statement);
                converted.append(rewritten);
                changed = changed || !rewritten.equals(statement);
                index = end;
            } else {
                converted.append(current);
                index++;
            }
        }
        return changed ? converted.toString() : sql;
    }

    private String addStatementMissingSysTimeInsertValue(String statement) {
        int start = skipWhitespace(statement, 0);
        if (!startsKeyword(statement, start, "INSERT")) {
            return statement;
        }
        int cursor = skipWhitespace(statement, start + "INSERT".length());
        if (!startsKeyword(statement, cursor, "INTO")) {
            return statement;
        }
        cursor = skipWhitespace(statement, cursor + "INTO".length());
        SqlIdentifierReference table = sqlIdentifierReferenceAt(statement, cursor);
        if (table == null || isProcedureTemporaryTableName(table.token())) {
            return statement;
        }
        if (!procedureTableHasSysTime(table.token())) {
            return statement;
        }
        cursor = skipWhitespace(statement, table.end());
        if (cursor >= statement.length() || statement.charAt(cursor) != '(') {
            return statement;
        }
        int closeParen = findMatchingParen(statement, cursor);
        if (closeParen <= cursor) {
            return statement;
        }
        List<String> targetColumns = insertTargetColumns(statement.substring(cursor + 1, closeParen));
        if (targetColumns.isEmpty()
                || hasColumnIgnoreCase(targetColumns, "sys_time")
                || !hasColumnIgnoreCase(targetColumns, "SY_CREATETIME")) {
            return statement;
        }
        int selectIndex = skipWhitespace(statement, closeParen + 1);
        if (!startsKeyword(statement, selectIndex, "SELECT")) {
            return statement;
        }
        int selectListStart = selectIndex + "SELECT".length();
        String selectTail = statement.substring(selectListStart);
        int fromIndex = topLevelKeywordIndex(selectTail, "FROM");
        if (fromIndex < 0) {
            return statement;
        }
        fromIndex += selectListStart;
        List<String> selectItems = splitTopLevelComma(statement.substring(selectListStart, fromIndex));
        if (selectItems.size() != targetColumns.size()) {
            return statement;
        }
        return statement.substring(0, closeParen)
                + "," + sysTimeColumnToken(statement.substring(cursor + 1, closeParen))
                + statement.substring(closeParen, fromIndex)
                + ", now() "
                + statement.substring(fromIndex);
    }

    private boolean procedureTableHasSysTime(String tableToken) {
        String tableName = unquoteIdentifier(lastIdentifierPart(tableToken.strip()))
                .toLowerCase(Locale.ROOT);
        return PROCEDURE_SYS_TIME_TABLES.contains(tableName);
    }

    private boolean hasColumnIgnoreCase(List<String> columns, String expected) {
        for (String column : columns) {
            if (column.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    private String sysTimeColumnToken(String targetColumnList) {
        String stripped = targetColumnList.stripLeading();
        if (stripped.startsWith("`")) {
            return "`sys_time`";
        }
        if (stripped.startsWith("\"")) {
            return "\"sys_time\"";
        }
        return "sys_time";
    }

    private String qualifyStatementAmbiguousGroupByColumns(String statement) {
        int selectIndex = topLevelKeywordIndex(statement, "SELECT");
        if (selectIndex < 0) {
            return statement;
        }
        int fromIndex = topLevelKeywordIndex(statement.substring(selectIndex), "FROM");
        if (fromIndex < 0) {
            return statement;
        }
        fromIndex += selectIndex;
        int groupByIndex = topLevelKeywordIndex(statement.substring(fromIndex), "GROUP BY");
        if (groupByIndex < 0) {
            return statement;
        }
        groupByIndex += fromIndex;
        String fromClause = statement.substring(fromIndex, groupByIndex);
        if (!Pattern.compile("(?is)\\bJOIN\\b").matcher(fromClause).find()) {
            return statement;
        }

        Map<String, String> selectedAliases = selectedColumnAliases(
                statement.substring(selectIndex + "SELECT".length(), fromIndex)
        );
        if (selectedAliases.isEmpty()) {
            return statement;
        }

        int groupListStart = groupByIndex + "GROUP BY".length();
        int groupListEnd = topLevelGroupByListEnd(statement, groupListStart);
        String groupList = statement.substring(groupListStart, groupListEnd);
        List<String> rewrittenItems = new ArrayList<>();
        boolean changed = false;
        for (String item : splitTopLevelComma(groupList)) {
            String rewritten = qualifyGroupByItem(item, selectedAliases);
            rewrittenItems.add(rewritten);
            changed = changed || !rewritten.equals(item);
        }
        if (!changed) {
            return statement;
        }
        return statement.substring(0, groupListStart)
                + String.join(",", rewrittenItems)
                + statement.substring(groupListEnd);
    }

    private Map<String, String> selectedColumnAliases(String selectList) {
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        for (String item : splitTopLevelComma(selectList)) {
            String stripped = item.strip();
            if (stripped.regionMatches(true, 0, "DISTINCT ", 0, "DISTINCT ".length())) {
                stripped = stripped.substring("DISTINCT ".length()).stripLeading();
            }
            Matcher matcher = Pattern.compile(
                    "(?is)^(?<alias>[A-Za-z_][A-Za-z0-9_$]*)\\s*\\.\\s*(?<column>" + SQL_IDENTIFIER_TOKEN + ")\\b"
            ).matcher(stripped);
            if (!matcher.find()) {
                continue;
            }
            String column = unquoteIdentifier(lastIdentifierPart(matcher.group("column").strip()))
                    .toLowerCase(Locale.ROOT);
            String alias = matcher.group("alias").strip();
            String existing = aliases.putIfAbsent(column, alias);
            if (existing != null && !existing.equalsIgnoreCase(alias)) {
                aliases.put(column, "");
            }
        }
        aliases.entrySet().removeIf(entry -> entry.getValue().isBlank());
        return aliases;
    }

    private int topLevelGroupByListEnd(String statement, int groupListStart) {
        int end = statement.length();
        String tail = statement.substring(groupListStart);
        for (String keyword : List.of("HAVING", "ORDER BY", "LIMIT", "UNION")) {
            int index = topLevelKeywordIndex(tail, keyword);
            if (index >= 0) {
                end = Math.min(end, groupListStart + index);
            }
        }
        return end;
    }

    private String qualifyGroupByItem(String item, Map<String, String> selectedAliases) {
        String stripped = item.strip();
        if (stripped.isEmpty() || stripped.contains(".")) {
            return item;
        }
        SqlIdentifierReference reference = sqlIdentifierReferenceAt(stripped, 0);
        if (reference == null || reference.end() != stripped.length()) {
            return item;
        }
        String column = unquoteIdentifier(lastIdentifierPart(reference.token())).toLowerCase(Locale.ROOT);
        String alias = selectedAliases.get(column);
        if (alias == null || alias.isBlank()) {
            return item;
        }
        int leading = leadingWhitespaceLength(item);
        int trailingStart = trailingWhitespaceStart(item);
        return item.substring(0, leading)
                + alias + "." + item.substring(leading, trailingStart)
                + item.substring(trailingStart);
    }

    private int leadingWhitespaceLength(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private int trailingWhitespaceStart(String value) {
        int index = value.length();
        while (index > 0 && Character.isWhitespace(value.charAt(index - 1))) {
            index--;
        }
        return index;
    }

    private String convertMysqlForeignKeyChecksToNoop(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*SET\\s+(?:@@)?(?:(?:SESSION|GLOBAL)\\s*\\.\\s*)?FOREIGN_KEY_CHECKS\\s*=\\s*([01])\\s*$"
        ).matcher(sql);
        if (!matcher.matches()) {
            return sql;
        }
        return "-- DM_ADAPTER: ignored MySQL FOREIGN_KEY_CHECKS = " + matcher.group(1);
    }

    private String convertSystemMetadataScalarIdSubqueries(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        return replaceOutsideIgnoredText(sql, SYSTEM_METADATA_SCALAR_ID_SUBQUERY_PATTERN, matcher -> {
            if (!isScalarSystemMetadataIdSubqueryContext(sql, matcher.start())) {
                return matcher.group();
            }
            return "(SELECT min(id) from " + matcher.group(1);
        });
    }

    private boolean isScalarSystemMetadataIdSubqueryContext(String sql, int openParenIndex) {
        int cursor = openParenIndex - 1;
        while (cursor >= 0 && Character.isWhitespace(sql.charAt(cursor))) {
            cursor--;
        }
        if (cursor < 0) {
            return true;
        }
        int wordEnd = cursor + 1;
        while (cursor >= 0 && isIdentifierPart(sql.charAt(cursor))) {
            cursor--;
        }
        if (wordEnd == cursor + 1) {
            return true;
        }
        String previousWord = sql.substring(cursor + 1, wordEnd);
        return !Set.of("IN", "EXISTS", "FROM", "JOIN").contains(previousWord.toUpperCase(Locale.ROOT));
    }

    private String addDropProcedureIfExists(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        LeadingSqlPrefix leadingSqlPrefix = splitLeadingSqlPrefix(sql);
        String body = leadingSqlPrefix.body();
        int dropIndex = skipWhitespace(body, 0);
        if (!startsKeyword(body, dropIndex, "DROP")) {
            return sql;
        }
        int procedureIndex = skipWhitespace(body, dropIndex + "DROP".length());
        if (!startsKeyword(body, procedureIndex, "PROCEDURE")) {
            return sql;
        }
        int procedureEnd = procedureIndex + "PROCEDURE".length();
        int targetStart = skipWhitespace(body, procedureEnd);
        if (targetStart >= body.length()) {
            return sql;
        }
        if (startsKeyword(body, targetStart, "IF")) {
            int existsIndex = skipWhitespace(body, targetStart + "IF".length());
            if (startsKeyword(body, existsIndex, "EXISTS")) {
                int objectStart = skipWhitespace(body, existsIndex + "EXISTS".length());
                if (objectStart >= body.length()) {
                    return sql;
                }
                return leadingSqlPrefix.prefix()
                        + body.substring(0, procedureEnd)
                        + " IF EXISTS "
                        + body.substring(objectStart).stripTrailing();
            }
        }
        return leadingSqlPrefix.prefix()
                + body.substring(0, procedureEnd)
                + " IF EXISTS "
                + body.substring(targetStart).stripTrailing();
    }

    private String normalizeDuplicateDropProcedureIfExists(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        Matcher matcher = Pattern.compile(
                "(?is)^(\\s*DROP\\s+PROCEDURE\\s+IF\\s+EXISTS\\s+)IF\\s+EXISTS\\s+(.+?)\\s*$"
        ).matcher(sql);
        if (!matcher.matches()) {
            return sql;
        }
        return matcher.group(1) + matcher.group(2).stripTrailing();
    }

    private String normalizeMysqlAlterModifySyntax(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        int start = skipWhitespace(sql, 0);
        if (!startsKeyword(sql, start, "ALTER")) {
            return sql;
        }
        int cursor = skipWhitespace(sql, start + "ALTER".length());
        if (!startsKeyword(sql, cursor, "TABLE")) {
            return sql;
        }
        if (!containsKeywordOutsideIgnoredText(sql, "MODIFY")) {
            return sql;
        }
        String converted = replaceOutsideIgnoredText(
                sql,
                Pattern.compile("(?is)\\bMODIFY\\s+COLUMN\\b"),
                matcher -> "MODIFY"
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)\\s+AFTER\\s+(?:" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")(?=\\s*(?:,|$))"),
                matcher -> ""
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)\\s+FIRST(?=\\s*(?:,|$))"),
                matcher -> ""
        );
        return normalizeMysqlDataTypes(converted);
    }

    private String normalizeMysqlAlterChangeSyntax(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*ALTER\\s+TABLE\\s+(?<table>(?:" + SQL_IDENTIFIER_TOKEN + "))\\s+"
                        + "CHANGE(?:\\s+COLUMN)?\\s+(?<old>(?:" + SQL_SIMPLE_IDENTIFIER_TOKEN + "))\\s+"
                        + "(?<name>(?:" + SQL_SIMPLE_IDENTIFIER_TOKEN + "))\\s+(?<type>.+)$"
        ).matcher(sql);
        if (!matcher.matches() || !sameIdentifier(matcher.group("old"), matcher.group("name"))) {
            return sql;
        }
        return normalizeMysqlDataTypes(
                "ALTER TABLE " + matcher.group("table") + " MODIFY " + matcher.group("name") + " "
                        + matcher.group("type").strip()
        );
    }

    private String normalizeMysqlProcedureIdentifiers(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        String converted = replaceOutsideIgnoredText(
                sql,
                Pattern.compile("(?is)(\\b(?:CREATE\\s+(?:OR\\s+REPLACE\\s+)?|DROP\\s+)(?:PROCEDURE|FUNCTION)\\s+"
                        + "(?:IF\\s+EXISTS\\s+)?)(`([^`]+)`)"),
                matcher -> matcher.group(1) + dmRoutineIdentifier(matcher.group(3), matcher.group(2))
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)(\\bCALL\\s+)(`([^`]+)`)(?=\\s*\\()"),
                matcher -> matcher.group(1) + dmRoutineIdentifier(matcher.group(3), matcher.group(2))
        );
        return converted;
    }

    private String dmRoutineIdentifier(String identifier, String fallback) {
        if (identifier == null || identifier.isBlank()) {
            return fallback;
        }
        return Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*").matcher(identifier).matches()
                ? identifier
                : fallback;
    }

    private String removeMysqlCallArgumentLineComments(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        int start = skipWhitespace(sql, 0);
        if (!startsKeyword(sql, start, "CALL")) {
            return sql;
        }
        int procedureStart = skipWhitespace(sql, start + "CALL".length());
        SqlIdentifierReference procedure = sqlIdentifierReferenceAt(sql, procedureStart);
        if (procedure == null) {
            return sql;
        }
        int openParen = skipWhitespace(sql, procedure.end());
        if (openParen >= sql.length() || sql.charAt(openParen) != '(') {
            return sql;
        }
        int closeParen = findMatchingParen(sql, openParen);
        if (closeParen <= openParen) {
            return sql;
        }
        String arguments = sql.substring(openParen + 1, closeParen);
        String convertedArguments = removeLineCommentsOutsideText(arguments);
        if (convertedArguments.equals(arguments)) {
            return sql;
        }
        return sql.substring(0, openParen + 1)
                + convertedArguments
                + sql.substring(closeParen);
    }

    private String convertLongClobCallArgumentsToBlock(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        LeadingSqlPrefix leadingSqlPrefix = splitLeadingSqlPrefix(sql);
        String body = leadingSqlPrefix.body();
        int start = skipWhitespace(body, 0);
        if (!startsKeyword(body, start, "CALL")) {
            return sql;
        }
        int procedureStart = skipWhitespace(body, start + "CALL".length());
        SqlIdentifierReference procedure = sqlIdentifierReferenceAt(body, procedureStart);
        if (procedure == null) {
            return sql;
        }
        String procedureName = unquoteIdentifier(lastIdentifierPart(procedure.token())).toLowerCase(Locale.ROOT);
        Set<Integer> clobArgumentIndexes = DM_CALL_CLOB_ARGUMENT_INDEXES.get(procedureName);
        if (clobArgumentIndexes == null || clobArgumentIndexes.isEmpty()) {
            return sql;
        }
        int openParen = skipWhitespace(body, procedure.end());
        if (openParen >= body.length() || body.charAt(openParen) != '(') {
            return sql;
        }
        int closeParen = findMatchingParen(body, openParen);
        if (closeParen <= openParen || !body.substring(closeParen + 1).isBlank()) {
            return sql;
        }
        List<String> arguments = splitTopLevelComma(body.substring(openParen + 1, closeParen));
        List<String> convertedArguments = new ArrayList<>(arguments.size());
        List<ClobCallArgument> clobArguments = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            String variableName = "dm_adapter_clob_arg_" + (i + 1);
            if (clobArgumentIndexes.contains(i) && isLongSingleQuotedLiteral(argument)) {
                convertedArguments.add(variableName);
                clobArguments.add(new ClobCallArgument(variableName, decodeMysqlSingleQuotedLiteral(argument.strip())));
            } else {
                convertedArguments.add(argument.strip());
            }
        }
        if (clobArguments.isEmpty()) {
            return sql;
        }
        StringBuilder block = new StringBuilder(body.length() + 256);
        block.append(leadingSqlPrefix.prefix());
        block.append("DECLARE\n");
        for (ClobCallArgument argument : clobArguments) {
            block.append("    ").append(argument.variableName()).append(" CLOB;\n");
        }
        block.append("BEGIN\n");
        for (ClobCallArgument argument : clobArguments) {
            appendClobLiteralAssignments(block, argument.variableName(), argument.literal());
        }
        block.append("    CALL ")
                .append(body, procedureStart, procedure.end())
                .append("(")
                .append(String.join(", ", convertedArguments))
                .append(");\n");
        block.append("END;");
        return block.toString();
    }

    private boolean isLongSingleQuotedLiteral(String value) {
        String stripped = value == null ? "" : value.strip();
        return stripped.startsWith("'")
                && skipSingleQuotedString(stripped, 0) == stripped.length()
                && stripped.length() - 2 > DM_CLOB_CALL_LITERAL_THRESHOLD;
    }

    private void appendClobLiteralAssignments(StringBuilder block, String variableName, String literal) {
        List<String> chunks = splitTextByUtf8Bytes(literal, DM_CLOB_CALL_LITERAL_CHUNK_BYTES);
        for (int i = 0; i < chunks.size(); i++) {
            block.append("    ").append(variableName);
            if (i == 0) {
                block.append(" := TO_CLOB(");
            } else {
                block.append(" := ").append(variableName).append(" || TO_CLOB(");
            }
            block.append(sqlStringLiteral(chunks.get(i))).append(");\n");
        }
    }

    private String decodeMysqlSingleQuotedLiteral(String literal) {
        String stripped = literal == null ? "" : literal.strip();
        if (stripped.length() < 2 || !stripped.startsWith("'") || !stripped.endsWith("'")) {
            return stripped;
        }
        StringBuilder rawContent = new StringBuilder(stripped.length() - 2);
        int index = 1;
        while (index < stripped.length() - 1) {
            char current = stripped.charAt(index);
            if (current == '\\' && index + 1 < stripped.length() - 1) {
                rawContent.append(current).append(stripped.charAt(index + 1));
                index += 2;
            } else if (current == '\'' && index + 1 < stripped.length() - 1 && stripped.charAt(index + 1) == '\'') {
                rawContent.append('\'');
                index += 2;
            } else {
                rawContent.append(current);
                index++;
            }
        }
        return decodeMysqlBackslashEscapedString(rawContent.toString());
    }

    private List<String> splitTextByUtf8Bytes(String value, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder(Math.min(value.length(), maxBytes));
        int chunkBytes = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            String token = new String(Character.toChars(codePoint));
            int tokenBytes = token.getBytes(StandardCharsets.UTF_8).length;
            if (chunkBytes + tokenBytes > maxBytes && !chunk.isEmpty()) {
                chunks.add(chunk.toString());
                chunk.setLength(0);
                chunkBytes = 0;
            }
            chunk.append(token);
            chunkBytes += tokenBytes;
            index += Character.charCount(codePoint);
        }
        if (!chunk.isEmpty()) {
            chunks.add(chunk.toString());
        }
        return chunks;
    }

    private String removeLineCommentsOutsideText(String sql) {
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
                index = skipUntilLineEnd(sql, index);
                changed = true;
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

    private String convertTopLevelMysqlTemporaryTableAsSelect(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        Matcher matcher = Pattern.compile(
                "(?is)^(\\s*CREATE\\s+)(?:GLOBAL\\s+)?TEMPORARY\\s+TABLE\\s+"
                        + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+"
                        + "(?:AS\\s+)?SELECT\\b(?<select>.*)$"
        ).matcher(sql);
        if (!matcher.matches()) {
            return sql;
        }
        return matcher.group(1)
                + "TABLE IF NOT EXISTS "
                + matcher.group("table").strip()
                + " AS SELECT"
                + matcher.group("select");
    }

    private String convertTopLevelTemporaryIndexToNoop(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql == null ? "" : sql;
        }
        Matcher alterMatcher = Pattern.compile(
                "(?is)^\\s*ALTER\\s+TABLE\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+ADD\\s+(?:INDEX|KEY)\\b.*$"
        ).matcher(sql.strip());
        if (alterMatcher.matches() && isProcedureTemporaryTableName(alterMatcher.group("table"))) {
            return "-- DM_ADAPTER: ignored MySQL temporary table index DDL";
        }
        Matcher createMatcher = Pattern.compile(
                "(?is)^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+\\S+\\s+ON\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\b.*$"
        ).matcher(sql.strip());
        if (createMatcher.matches() && isProcedureTemporaryTableName(createMatcher.group("table"))) {
            return "-- DM_ADAPTER: ignored MySQL temporary table index DDL";
        }
        return sql;
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

    private String convertCreateFunctionHeader(String sql) {
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
        if (!startsKeyword(sql, cursor, "FUNCTION")) {
            return sql;
        }
        cursor = skipWhitespace(sql, cursor + "FUNCTION".length());
        int functionNameStart = cursor;
        SqlIdentifierReference functionName = sqlIdentifierReferenceAt(sql, cursor);
        if (functionName == null) {
            return sql;
        }
        int openParen = skipWhitespace(sql, functionName.end());
        if (openParen >= sql.length() || sql.charAt(openParen) != '(') {
            return sql;
        }
        int closeParen = findMatchingParen(sql, openParen);
        if (closeParen <= openParen) {
            return sql;
        }
        int returnsIndex = skipWhitespace(sql, closeParen + 1);
        if (!startsKeyword(sql, returnsIndex, "RETURNS")) {
            return sql;
        }
        int beginIndex = firstProcedureBegin(sql);
        if (beginIndex < 0 || beginIndex <= returnsIndex) {
            return sql;
        }
        String returnTypeAndOptions = sql.substring(
                skipWhitespace(sql, returnsIndex + "RETURNS".length()),
                beginIndex
        );
        String returnType = mysqlFunctionReturnType(returnTypeAndOptions);
        if (returnType.isBlank()) {
            return sql;
        }
        String signature = sql.substring(functionNameStart, closeParen + 1).stripTrailing();
        return sql.substring(0, start)
                + "CREATE OR REPLACE FUNCTION "
                + signature
                + "\nRETURN "
                + normalizeMysqlDataTypes(returnType)
                + "\nAS\nBEGIN"
                + sql.substring(beginIndex + "BEGIN".length());
    }

    private String mysqlFunctionReturnType(String returnTypeAndOptions) {
        String stripped = returnTypeAndOptions.strip();
        if (stripped.isBlank()) {
            return "";
        }
        int characteristicIndex = -1;
        for (String keyword : List.of(
                "DETERMINISTIC",
                "NOT DETERMINISTIC",
                "READS SQL DATA",
                "NO SQL",
                "CONTAINS SQL",
                "MODIFIES SQL DATA",
                "SQL SECURITY"
        )) {
            int index = topLevelKeywordIndex(stripped, keyword);
            if (index >= 0 && (characteristicIndex < 0 || index < characteristicIndex)) {
                characteristicIndex = index;
            }
        }
        return characteristicIndex < 0 ? stripped : stripped.substring(0, characteristicIndex).strip();
    }

    private String convertMysqlProcedureBeginLabels(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        LabelRemoval labelRemoval = removeMysqlBeginLabels(sql);
        if (!labelRemoval.changed()) {
            return sql;
        }
        String converted = labelRemoval.sql();
        for (String label : labelRemoval.labels()) {
            converted = replaceOutsideIgnoredText(converted, List.of(
                    new TextReplacement(
                            Pattern.compile("(?is)\\bLEAVE\\s+" + identifierReferencePattern(label) + "\\s*;"),
                            "RETURN;"
                    ),
                    new TextReplacement(
                            Pattern.compile("(?is)\\bEND\\s+" + identifierReferencePattern(label) + "\\s*;"),
                            "END;"
                    )
            ));
        }
        return converted;
    }

    private LabelRemoval removeMysqlBeginLabels(String sql) {
        StringBuilder converted = new StringBuilder(sql.length());
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        boolean changed = false;
        int index = 0;
        Pattern labelPattern = Pattern.compile("(?is)([A-Za-z_][A-Za-z0-9_$]*)\\s*:\\s*BEGIN\\b");
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
                Matcher matcher = labelPattern.matcher(sql);
                matcher.region(index, sql.length());
                if (matcher.lookingAt()) {
                    labels.add(matcher.group(1).toLowerCase(Locale.ROOT));
                    converted.append("BEGIN");
                    index = matcher.end();
                    changed = true;
                } else {
                    converted.append(current);
                    index++;
                }
            }
        }
        return new LabelRemoval(changed ? converted.toString() : sql, changed, labels);
    }

    private String renameReservedProcedureCursorNames(String sql) {
        if (!isCreateProcedureStatement(sql)
                || !Pattern.compile("(?is)\\bDECLARE\\s+object\\s+CURSOR\\s+FOR\\b").matcher(sql).find()) {
            return sql;
        }
        String converted = replaceOutsideIgnoredText(
                sql,
                Pattern.compile("(?is)(\\bDECLARE\\s+)object(\\s+CURSOR\\s+FOR\\b)"),
                matcher -> matcher.group(1) + "dm_object_cursor" + matcher.group(2)
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)(\\bOPEN\\s+)object(\\s*;)"),
                matcher -> matcher.group(1) + "dm_object_cursor" + matcher.group(2)
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)(\\bFETCH\\s+)object(\\s+INTO\\b)"),
                matcher -> matcher.group(1) + "dm_object_cursor" + matcher.group(2)
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)(\\bCLOSE\\s+)object(\\s*;)"),
                matcher -> matcher.group(1) + "dm_object_cursor" + matcher.group(2)
        );
        return replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)\\bobject\\s*%\\s*NOTFOUND\\b"),
                matcher -> "dm_object_cursor%NOTFOUND"
        );
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
        return matcher.group(2)
                + " "
                + matcher.group(1).toUpperCase(Locale.ROOT)
                + " "
                + normalizeMysqlDataTypes(matcher.group(3).strip());
    }

    private String convertMysqlProcedureControlFlowSyntax(String sql) {
        if (!isCreateRoutineStatement(sql)) {
            return sql;
        }
        String converted = replaceOutsideIgnoredText(
                sql,
                Pattern.compile("(?is)\\bELSEIF\\b"),
                matcher -> "ELSIF"
        );
        converted = replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)\\bEND\\s+WHILE\\b"),
                matcher -> "END LOOP"
        );
        return replaceOutsideIgnoredText(
                converted,
                Pattern.compile("(?is)\\bWHILE\\s+(.+?)\\s+DO\\b"),
                matcher -> "WHILE " + matcher.group(1).strip() + " LOOP"
        );
    }

    private String convertProcedureClobEmptyStringChecks(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        Set<String> clobVariables = procedureClobVariableNames(sql);
        if (clobVariables.isEmpty()) {
            return sql;
        }
        return replaceOutsideIgnoredText(sql, CLOB_EMPTY_STRING_COMPARISON_PATTERN, matcher -> {
            String identifier = matcher.group("left");
            String normalized = normalizedProcedureVariableName(identifier);
            if (!clobVariables.contains(normalized)) {
                return matcher.group();
            }
            return identifier + " IS NOT NULL AND DBMS_LOB.GETLENGTH(" + identifier + ") > 0";
        });
    }

    private Set<String> procedureClobVariableNames(String sql) {
        int beginIndex = firstProcedureBegin(sql);
        if (beginIndex < 0) {
            return Set.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String headerAndDeclarations = sql.substring(0, beginIndex);
        collectProcedureClobParameterNames(headerAndDeclarations, names);
        collectProcedureClobDeclarationNames(headerAndDeclarations, names);
        return names;
    }

    private void collectProcedureClobParameterNames(String headerAndDeclarations, LinkedHashSet<String> names) {
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
            ProcedureParameterParts parts = parseProcedureParameter(parameter);
            if (parts != null && isClobType(parts.type())) {
                names.add(normalizedProcedureVariableName(parts.name()));
            }
        }
    }

    private void collectProcedureClobDeclarationNames(String headerAndDeclarations, LinkedHashSet<String> names) {
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
        if (closeParen <= openParen || closeParen + 1 >= headerAndDeclarations.length()) {
            return;
        }
        int cursor = closeParen + 1;
        while (cursor < headerAndDeclarations.length()) {
            cursor = skipWhitespace(headerAndDeclarations, cursor);
            if (startsKeyword(headerAndDeclarations, cursor, "AS")) {
                cursor = skipWhitespace(headerAndDeclarations, cursor + "AS".length());
                continue;
            }
            int terminator = findStatementTerminator(headerAndDeclarations, cursor);
            if (terminator >= headerAndDeclarations.length()) {
                return;
            }
            String declaration = headerAndDeclarations.substring(cursor, terminator).strip();
            Matcher declarationMatcher = Pattern.compile(
                    "(?is)^(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s+(.+)$"
            ).matcher(declaration);
            if (declarationMatcher.matches()) {
                String type = declarationTypeBeforeDefault(declarationMatcher.group(2));
                if (isClobType(type)) {
                    names.add(normalizedProcedureVariableName(declarationMatcher.group(1)));
                }
            }
            cursor = terminator + 1;
        }
    }

    private ProcedureParameterParts parseProcedureParameter(String parameter) {
        String stripped = parameter.strip();
        Matcher modeFirstMatcher = Pattern.compile(
                "(?is)^(INOUT|IN|OUT)\\s+(" + SQL_IDENTIFIER_TOKEN + ")\\s+(.+)$"
        ).matcher(stripped);
        if (modeFirstMatcher.matches()) {
            return new ProcedureParameterParts(modeFirstMatcher.group(2), modeFirstMatcher.group(3));
        }
        Matcher nameFirstMatcher = Pattern.compile(
                "(?is)^(" + SQL_IDENTIFIER_TOKEN + ")\\s+(INOUT|IN|OUT)\\s+(.+)$"
        ).matcher(stripped);
        if (nameFirstMatcher.matches()) {
            return new ProcedureParameterParts(nameFirstMatcher.group(1), nameFirstMatcher.group(3));
        }
        Matcher implicitInMatcher = Pattern.compile(
                "(?is)^(" + SQL_IDENTIFIER_TOKEN + ")\\s+(.+)$"
        ).matcher(stripped);
        if (implicitInMatcher.matches()) {
            return new ProcedureParameterParts(implicitInMatcher.group(1), implicitInMatcher.group(2));
        }
        return null;
    }

    private String declarationTypeBeforeDefault(String declarationTail) {
        String stripped = declarationTail == null ? "" : declarationTail.strip();
        int assignmentIndex = topLevelAssignmentIndex(stripped);
        if (assignmentIndex >= 0) {
            stripped = stripped.substring(0, assignmentIndex).strip();
        }
        int defaultIndex = topLevelKeywordIndex(stripped, "DEFAULT");
        if (defaultIndex >= 0) {
            stripped = stripped.substring(0, defaultIndex).strip();
        }
        return stripped;
    }

    private int topLevelAssignmentIndex(String value) {
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
            } else if (depth == 0 && current == ':' && index + 1 < value.length() && value.charAt(index + 1) == '=') {
                return index;
            } else if (depth == 0 && current == '=') {
                return index;
            } else {
                index++;
            }
        }
        return -1;
    }

    private boolean isClobType(String type) {
        return Pattern.compile("(?is)\\bCLOB\\b").matcher(type == null ? "" : type).find();
    }

    private String normalizedProcedureVariableName(String identifier) {
        return unquoteIdentifier(lastIdentifierPart(identifier.strip())).toLowerCase(Locale.ROOT);
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
        int bodyStart = beginIndex + "BEGIN".length();
        int cursor = skipWhitespaceAndComments(sql, bodyStart);
        String leadingTrivia = sql.substring(bodyStart, cursor);
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
            cursor = skipWhitespaceAndComments(sql, declarationsEnd);
        }
        if (declarations.isEmpty()) {
            return moveMysqlNestedBlockDeclarations(sql, beginIndex + "BEGIN".length());
        }
        StringBuilder converted = new StringBuilder(sql.length());
        converted.append(sql, 0, beginIndex);
        for (String declaration : declarations) {
            converted.append("    ").append(declaration).append(";\n");
        }
        converted.append("BEGIN");
        converted.append(leadingTrivia);
        converted.append(sql.substring(declarationsEnd));
        return moveMysqlNestedBlockDeclarations(converted.toString(), beginIndex + "BEGIN".length());
    }

    private String moveMysqlNestedBlockDeclarations(String sql, int scanStart) {
        StringBuilder converted = new StringBuilder(sql.length());
        int cursor = 0;
        int index = Math.max(0, scanStart);
        boolean changed = false;
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
                NestedBlockDeclarations declarations = mysqlNestedBlockDeclarationsAt(sql, index);
                if (declarations == null) {
                    index += "BEGIN".length();
                    continue;
                }
                converted.append(sql, cursor, index);
                String indent = lineIndentBefore(sql, index);
                converted.append("DECLARE\n");
                for (String declaration : declarations.declarations()) {
                    converted.append(indent).append("    ").append(declaration).append(";\n");
                }
                converted.append(indent).append("BEGIN");
                converted.append(declarations.leadingTrivia());
                cursor = declarations.declarationsEnd();
                index = declarations.declarationsEnd();
                changed = true;
            } else {
                index++;
            }
        }
        if (!changed) {
            return sql;
        }
        converted.append(sql.substring(cursor));
        return converted.toString();
    }

    private NestedBlockDeclarations mysqlNestedBlockDeclarationsAt(String sql, int beginIndex) {
        int bodyStart = beginIndex + "BEGIN".length();
        int cursor = skipWhitespaceAndComments(sql, bodyStart);
        String leadingTrivia = sql.substring(bodyStart, cursor);
        List<String> declarations = new ArrayList<>();
        int declarationsEnd = cursor;
        while (startsKeyword(sql, cursor, "DECLARE")) {
            int terminator = findStatementTerminator(sql, cursor);
            if (terminator >= sql.length()) {
                return null;
            }
            String declaration = convertMysqlProcedureDeclaration(sql.substring(cursor, terminator).strip());
            if (declaration == null) {
                return null;
            }
            declarations.add(declaration);
            declarationsEnd = terminator + 1;
            cursor = skipWhitespaceAndComments(sql, declarationsEnd);
        }
        if (declarations.isEmpty()) {
            return null;
        }
        return new NestedBlockDeclarations(declarations, declarationsEnd, leadingTrivia);
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

    private String convertMysqlCursorHandlerLoops(String sql) {
        if (!isCreateProcedureStatement(sql)
                || !Pattern.compile("(?is)\\bDECLARE\\s+CONTINUE\\s+HANDLER\\s+FOR\\s+(?:SQLSTATE\\s+'02000'|NOT\\s+FOUND)")
                .matcher(sql)
                .find()) {
            return sql;
        }
        LinkedHashSet<String> flags = mysqlCursorNotFoundHandlerFlags(sql);
        if (flags.isEmpty()) {
            return sql;
        }

        String converted = sql;
        LinkedHashSet<String> convertedFlags = new LinkedHashSet<>();
        boolean changed = false;
        for (int i = 0; i < 20; i++) {
            CursorLoopConversion loopConversion = convertMysqlCursorLoops(converted, flags);
            if (!loopConversion.changed()) {
                break;
            }
            converted = loopConversion.sql();
            convertedFlags.addAll(loopConversion.convertedFlags());
            changed = true;
        }
        if (!changed || convertedFlags.isEmpty()) {
            return sql;
        }
        converted = Pattern.compile(
                        "(?is)\\s*DECLARE\\s+CONTINUE\\s+HANDLER\\s+FOR\\s+SQLSTATE\\s+'02000'\\s*SET\\s+("
                                + SQL_SIMPLE_IDENTIFIER_TOKEN
                                + ")\\s*=\\s*(?:1|TRUE)\\s*;"
                )
                .matcher(converted)
                .replaceAll(matchResult -> convertedFlags
                        .contains(unquoteIdentifier(matchResult.group(1)).toLowerCase(Locale.ROOT))
                        ? ""
                        : matchResult.group());
        converted = Pattern.compile(
                        "(?is)\\s*DECLARE\\s+CONTINUE\\s+HANDLER\\s+FOR\\s+NOT\\s+FOUND\\s*SET\\s+("
                                + SQL_SIMPLE_IDENTIFIER_TOKEN
                                + ")\\s*=\\s*(?:1|TRUE)\\s*;"
                )
                .matcher(converted)
                .replaceAll(matchResult -> convertedFlags
                        .contains(unquoteIdentifier(matchResult.group(1)).toLowerCase(Locale.ROOT))
                        ? ""
                        : matchResult.group());
        for (String flag : convertedFlags) {
            converted = Pattern.compile(
                            "(?is)\\s*DECLARE\\s+"
                                    + identifierReferencePattern(flag)
                                    + "\\s+(?:TINYINT(?:\\s*\\([^)]*\\))?|SMALLINT|INT(?:EGER)?(?:\\s*\\([^)]*\\))?|BIGINT(?:\\s*\\([^)]*\\))?|BOOLEAN|BOOL)"
                                    + "\\s+DEFAULT\\s+(?:0|FALSE)\\s*;"
                    )
                    .matcher(converted)
                    .replaceAll("");
            converted = Pattern.compile(
                            "(?is)\\s*(?:SET\\s+)?"
                                    + identifierReferencePattern(flag)
                                    + "\\s*(?:=|:=)\\s*(?:0|FALSE)\\s*;"
                    )
                    .matcher(converted)
                    .replaceAll("");
        }
        for (String flag : convertedFlags) {
            if (containsIdentifierReferenceOutsideIgnoredText(converted, flag)) {
                return sql;
            }
        }
        return converted;
    }

    private LinkedHashSet<String> mysqlCursorNotFoundHandlerFlags(String sql) {
        Matcher handlerMatcher = Pattern.compile(
                "(?is)\\bDECLARE\\s+CONTINUE\\s+HANDLER\\s+FOR\\s+"
                        + "(?:SQLSTATE\\s+'02000'|NOT\\s+FOUND)\\s*SET\\s+("
                        + SQL_SIMPLE_IDENTIFIER_TOKEN
                        + ")\\s*=\\s*(?:1|TRUE)\\s*;"
        ).matcher(sql);
        LinkedHashSet<String> flags = new LinkedHashSet<>();
        while (handlerMatcher.find()) {
            flags.add(unquoteIdentifier(handlerMatcher.group(1)).toLowerCase(Locale.ROOT));
        }
        return flags;
    }

    private CursorLoopConversion convertMysqlCursorLoops(String sql, LinkedHashSet<String> handlerFlags) {
        CursorLoopConversion whileLoops = convertMysqlCursorWhileLoops(sql, handlerFlags);
        CursorLoopConversion labelLoops = convertMysqlCursorLabelLoops(whileLoops.sql(), handlerFlags);
        if (!whileLoops.changed() && !labelLoops.changed()) {
            return new CursorLoopConversion(sql, false, Set.of());
        }
        LinkedHashSet<String> convertedFlags = new LinkedHashSet<>();
        convertedFlags.addAll(whileLoops.convertedFlags());
        convertedFlags.addAll(labelLoops.convertedFlags());
        return new CursorLoopConversion(labelLoops.sql(), true, convertedFlags);
    }

    private CursorLoopConversion convertMysqlCursorWhileLoops(String sql, LinkedHashSet<String> handlerFlags) {
        Matcher loopHeadMatcher = Pattern.compile(
                "(?is)\\bOPEN\\s+(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*;\\s*"
                        + "FETCH\\s+(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s+INTO\\s+([^;]+?)\\s*;\\s*"
                        + "WHILE\\s+(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*(?:<>|!=)\\s*(?:1|TRUE)\\s+DO\\b"
        ).matcher(sql);
        StringBuilder converted = new StringBuilder(sql.length());
        LinkedHashSet<String> convertedFlags = new LinkedHashSet<>();
        int cursor = 0;
        boolean changed = false;
        while (loopHeadMatcher.find(cursor)) {
            String cursorName = loopHeadMatcher.group(1).strip();
            String fetchedCursorName = loopHeadMatcher.group(2).strip();
            if (!sameIdentifier(cursorName, fetchedCursorName)) {
                cursor = loopHeadMatcher.end();
                continue;
            }
            String flag = unquoteIdentifier(loopHeadMatcher.group(4).strip()).toLowerCase(Locale.ROOT);
            if (!handlerFlags.contains(flag)) {
                cursor = loopHeadMatcher.end();
                continue;
            }
            Matcher loopTailMatcher = Pattern.compile(
                    "(?is)\\bFETCH\\s+"
                            + identifierReferencePattern(unquoteIdentifier(cursorName))
                            + "\\s+INTO\\s+(.+?)\\s*;\\s*END\\s+WHILE\\s*;"
                            + SQL_WS_OR_COMMENT_TOKEN
                            + "CLOSE\\s+"
                            + identifierReferencePattern(unquoteIdentifier(cursorName))
                            + "\\s*;"
            ).matcher(sql);
            loopTailMatcher.region(loopHeadMatcher.end(), sql.length());
            if (!loopTailMatcher.find()) {
                cursor = loopHeadMatcher.end();
                continue;
            }
            converted.append(sql, cursor, loopHeadMatcher.start());
            String fetchTargets = loopHeadMatcher.group(3).strip();
            String body = sql.substring(loopHeadMatcher.end(), loopTailMatcher.start());
            String indent = lineIndentBefore(sql, loopHeadMatcher.start());
            String innerIndent = indent + "    ";
            converted.append("OPEN ").append(cursorName).append(";\n")
                    .append(indent).append("LOOP\n")
                    .append(innerIndent).append("FETCH ").append(cursorName).append(" INTO ").append(fetchTargets).append(";\n")
                    .append(innerIndent).append("EXIT WHEN ").append(cursorName).append("%NOTFOUND;\n")
                    .append(body.stripLeading());
            if (!body.endsWith("\n") && !body.endsWith("\r")) {
                converted.append("\n");
            }
            converted.append(indent).append("END LOOP;\n")
                    .append(indent).append("CLOSE ").append(cursorName).append(";");
            convertedFlags.add(flag);
            cursor = loopTailMatcher.end();
            changed = true;
        }
        if (!changed) {
            return new CursorLoopConversion(sql, false, Set.of());
        }
        converted.append(sql.substring(cursor));
        return new CursorLoopConversion(converted.toString(), true, convertedFlags);
    }

    private CursorLoopConversion convertMysqlCursorLabelLoops(String sql, LinkedHashSet<String> handlerFlags) {
        Matcher loopHeadMatcher = Pattern.compile(
                "(?is)\\bOPEN\\s+(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*;\\s*"
                        + "(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*:\\s*LOOP"
                        + SQL_WS_OR_COMMENT_TOKEN
                        + "FETCH\\s+(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s+INTO\\s+([^;]+?)\\s*;\\s*"
                        + "IF\\s+(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*(?:=\\s*(?:1|TRUE))?\\s+THEN"
        ).matcher(sql);
        StringBuilder converted = new StringBuilder(sql.length());
        LinkedHashSet<String> convertedFlags = new LinkedHashSet<>();
        int cursor = 0;
        boolean changed = false;
        while (loopHeadMatcher.find(cursor)) {
            String cursorName = loopHeadMatcher.group(1).strip();
            String loopLabel = loopHeadMatcher.group(2).strip();
            String fetchedCursorName = loopHeadMatcher.group(3).strip();
            String flag = unquoteIdentifier(loopHeadMatcher.group(5).strip()).toLowerCase(Locale.ROOT);
            if (!sameIdentifier(cursorName, fetchedCursorName)
                    || !handlerFlags.contains(flag)) {
                cursor = loopHeadMatcher.end();
                continue;
            }
            CursorLeaveIfBlock leaveIfBlock = readCursorLeaveIfBlock(sql, loopHeadMatcher.end(), loopLabel);
            if (leaveIfBlock == null) {
                cursor = loopHeadMatcher.end();
                continue;
            }
            Matcher loopTailMatcher = Pattern.compile(
                    "(?is)\\bEND\\s+LOOP\\s*;"
                            + SQL_WS_OR_COMMENT_TOKEN
                            + "CLOSE\\s+"
                            + identifierReferencePattern(unquoteIdentifier(cursorName))
                            + "\\s*;"
            ).matcher(sql);
            loopTailMatcher.region(leaveIfBlock.endIfEnd(), sql.length());
            if (!loopTailMatcher.find()) {
                cursor = loopHeadMatcher.end();
                continue;
            }
            converted.append(sql, cursor, loopHeadMatcher.start());
            String fetchTargets = loopHeadMatcher.group(4).strip();
            String body = leaveIfBlock.loopBody() + sql.substring(leaveIfBlock.endIfEnd(), loopTailMatcher.start());
            String indent = lineIndentBefore(sql, loopHeadMatcher.start());
            String innerIndent = indent + "    ";
            converted.append("OPEN ").append(cursorName).append(";\n")
                    .append(indent).append("LOOP\n")
                    .append(innerIndent).append("FETCH ").append(cursorName).append(" INTO ").append(fetchTargets).append(";\n")
                    .append(innerIndent).append("EXIT WHEN ").append(cursorName).append("%NOTFOUND;\n")
                    .append(body.stripLeading());
            if (!body.endsWith("\n") && !body.endsWith("\r")) {
                converted.append("\n");
            }
            converted.append(indent).append("END LOOP;\n")
                    .append(indent).append("CLOSE ").append(cursorName).append(";");
            convertedFlags.add(flag);
            cursor = loopTailMatcher.end();
            changed = true;
        }
        if (!changed) {
            return new CursorLoopConversion(sql, false, Set.of());
        }
        converted.append(sql.substring(cursor));
        return new CursorLoopConversion(converted.toString(), true, convertedFlags);
    }

    private CursorLeaveIfBlock readCursorLeaveIfBlock(String sql, int afterThen, String expectedLabel) {
        int leaveIndex = skipWhitespaceAndComments(sql, afterThen);
        if (!startsKeyword(sql, leaveIndex, "LEAVE")) {
            return null;
        }
        int labelStart = skipWhitespace(sql, leaveIndex + "LEAVE".length());
        SqlIdentifierReference leaveLabel = sqlIdentifierReferenceAt(sql, labelStart);
        if (leaveLabel == null || !sameIdentifier(leaveLabel.token(), expectedLabel)) {
            return null;
        }
        int semicolon = skipWhitespace(sql, leaveLabel.end());
        if (semicolon >= sql.length() || sql.charAt(semicolon) != ';') {
            return null;
        }
        int index = skipWhitespace(sql, semicolon + 1);
        int depth = 1;
        int elseStart = -1;
        int elseBodyStart = -1;
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
            } else if (startsKeyword(sql, index, "END")) {
                int ifIndex = skipWhitespace(sql, index + "END".length());
                if (startsKeyword(sql, ifIndex, "IF")) {
                    depth--;
                    if (depth == 0) {
                        int statementEnd = findStatementTerminator(sql, index);
                        if (statementEnd >= sql.length()) {
                            return null;
                        }
                        int endIfEnd = statementEnd;
                        if (endIfEnd < sql.length() && sql.charAt(endIfEnd) == ';') {
                            endIfEnd++;
                        }
                        String loopBody = elseStart < 0 ? "" : sql.substring(elseBodyStart, index);
                        return new CursorLeaveIfBlock(endIfEnd, loopBody);
                    }
                    index = ifIndex + "IF".length();
                } else {
                    index++;
                }
            } else if (depth == 1 && startsKeyword(sql, index, "ELSE")) {
                elseStart = index;
                elseBodyStart = skipWhitespace(sql, index + "ELSE".length());
                index = elseBodyStart;
            } else if (startsKeyword(sql, index, "IF")) {
                depth++;
                index += "IF".length();
            } else {
                index++;
            }
        }
        return null;
    }

    private boolean sameIdentifier(String left, String right) {
        return unquoteIdentifier(left.strip()).equalsIgnoreCase(unquoteIdentifier(right.strip()));
    }

    private String identifierReferencePattern(String identifier) {
        String unquoted = Pattern.quote(unquoteIdentifier(identifier));
        return "(?:`" + unquoted + "`|\"" + unquoted + "\"|(?<![A-Za-z0-9_$])" + unquoted + "(?![A-Za-z0-9_$]))";
    }

    private String convertMysqlProcedureSessionSetToNoop(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        return replaceOutsideIgnoredText(sql, List.of(new TextReplacement(
                Pattern.compile("(?is)\\bSET\\s+(?:SESSION|GLOBAL)\\s+group_concat_max_len\\s*=\\s*[^;]+;"),
                "NULL;"
        )));
    }

    private String convertAssignedMysqlInParametersToLocals(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        int beginIndex = firstProcedureBegin(sql);
        if (beginIndex < 0) {
            return sql;
        }
        List<ProcedureParameter> parameters = procedureParameters(sql, beginIndex);
        if (parameters.isEmpty()) {
            return sql;
        }
        LinkedHashSet<String> assignedInParameterNames = new LinkedHashSet<>();
        for (ProcedureParameter parameter : parameters) {
            if (parameter.mode().isBlank() || !"IN".equalsIgnoreCase(parameter.mode())) {
                continue;
            }
            if (isProcedureIdentifierAssigned(sql, beginIndex, parameter.name())) {
                assignedInParameterNames.add(normalizedProcedureVariableName(parameter.name()));
            }
        }
        if (assignedInParameterNames.isEmpty()) {
            return sql;
        }

        LinkedHashSet<String> existingNames = procedureNamesInScope(sql, beginIndex);
        LinkedHashMap<String, ProcedureAssignedParameter> assignedParameters = new LinkedHashMap<>();
        for (ProcedureParameter parameter : parameters) {
            String normalized = normalizedProcedureVariableName(parameter.name());
            if (!assignedInParameterNames.contains(normalized)) {
                continue;
            }
            String localName = uniqueProcedureLocalName("dm_" + normalizeIdentifierSegment(parameter.name()), existingNames);
            assignedParameters.put(normalized, new ProcedureAssignedParameter(parameter, localName));
        }
        if (assignedParameters.isEmpty()) {
            return sql;
        }

        String body = sql.substring(beginIndex);
        String replacedBody = replaceProcedureAssignedParameterReferences(body, assignedParameters);
        int replacedBeginIndex = firstProcedureBegin(replacedBody);
        if (replacedBeginIndex < 0) {
            return sql;
        }
        StringBuilder declarations = new StringBuilder();
        StringBuilder initializers = new StringBuilder();
        for (ProcedureAssignedParameter assignedParameter : assignedParameters.values()) {
            declarations.append("    ")
                    .append(assignedParameter.localName())
                    .append(" ")
                    .append(assignedParameter.parameter().type())
                    .append(";\n");
            initializers.append("\n    ")
                    .append(assignedParameter.localName())
                    .append(" := ")
                    .append(assignedParameter.parameter().name())
                    .append(";");
        }
        return sql.substring(0, beginIndex)
                + declarations
                + replacedBody.substring(0, replacedBeginIndex + "BEGIN".length())
                + initializers
                + replacedBody.substring(replacedBeginIndex + "BEGIN".length());
    }

    private List<ProcedureParameter> procedureParameters(String sql, int beginIndex) {
        int procedureIndex = keywordIndex(sql.substring(0, beginIndex), "PROCEDURE");
        if (procedureIndex < 0) {
            return List.of();
        }
        int openParen = firstTopLevelParen(sql.substring(procedureIndex + "PROCEDURE".length(), beginIndex));
        if (openParen < 0) {
            return List.of();
        }
        openParen += procedureIndex + "PROCEDURE".length();
        int closeParen = findMatchingParen(sql, openParen);
        if (closeParen <= openParen || closeParen > beginIndex) {
            return List.of();
        }
        List<ProcedureParameter> parameters = new ArrayList<>();
        for (String rawParameter : splitTopLevelComma(sql.substring(openParen + 1, closeParen))) {
            ProcedureParameter parameter = parseProcedureParameterWithMode(rawParameter);
            if (parameter != null) {
                parameters.add(parameter);
            }
        }
        return parameters;
    }

    private ProcedureParameter parseProcedureParameterWithMode(String parameter) {
        String stripped = parameter.strip();
        Matcher nameFirstMatcher = Pattern.compile(
                "(?is)^(" + SQL_IDENTIFIER_TOKEN + ")\\s+(INOUT|IN|OUT)\\s+(.+)$"
        ).matcher(stripped);
        if (nameFirstMatcher.matches()) {
            return new ProcedureParameter(
                    unquoteIdentifier(nameFirstMatcher.group(1)),
                    nameFirstMatcher.group(2).toUpperCase(Locale.ROOT),
                    nameFirstMatcher.group(3).strip()
            );
        }
        Matcher modeFirstMatcher = Pattern.compile(
                "(?is)^(INOUT|IN|OUT)\\s+(" + SQL_IDENTIFIER_TOKEN + ")\\s+(.+)$"
        ).matcher(stripped);
        if (modeFirstMatcher.matches()) {
            return new ProcedureParameter(
                    unquoteIdentifier(modeFirstMatcher.group(2)),
                    modeFirstMatcher.group(1).toUpperCase(Locale.ROOT),
                    modeFirstMatcher.group(3).strip()
            );
        }
        Matcher implicitInMatcher = Pattern.compile(
                "(?is)^(" + SQL_IDENTIFIER_TOKEN + ")\\s+(.+)$"
        ).matcher(stripped);
        if (implicitInMatcher.matches()) {
            return new ProcedureParameter(
                    unquoteIdentifier(implicitInMatcher.group(1)),
                    "IN",
                    implicitInMatcher.group(2).strip()
            );
        }
        return null;
    }

    private boolean isProcedureIdentifierAssigned(String sql, int beginIndex, String identifier) {
        int index = beginIndex + "BEGIN".length();
        String referencePattern = identifierReferencePattern(identifier);
        Pattern setPattern = Pattern.compile("(?is)\\bSET\\s+" + referencePattern + "\\s*(?::=|=)");
        Pattern assignmentPattern = Pattern.compile("(?is)" + referencePattern + "\\s*:=");
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
            } else {
                Matcher setMatcher = setPattern.matcher(sql);
                setMatcher.region(index, sql.length());
                if (setMatcher.lookingAt()) {
                    return true;
                }
                Matcher assignmentMatcher = assignmentPattern.matcher(sql);
                assignmentMatcher.region(index, sql.length());
                if (assignmentMatcher.lookingAt() && isStatementAssignmentContext(sql, index)) {
                    return true;
                }
                index++;
            }
        }
        return false;
    }

    private boolean isStatementAssignmentContext(String sql, int index) {
        int statementStart = previousStatementStart(sql, index);
        return skipWhitespace(sql, statementStart) == index;
    }

    private String replaceProcedureAssignedParameterReferences(
            String body,
            Map<String, ProcedureAssignedParameter> assignedParameters
    ) {
        StringBuilder converted = new StringBuilder(body.length());
        int index = 0;
        while (index < body.length()) {
            char current = body.charAt(index);
            if (current == '\'') {
                int end = skipSingleQuotedString(body, index);
                converted.append(body, index, end);
                index = end;
            } else if (current == '"') {
                int end = skipDoubleQuotedText(body, index);
                converted.append(body, index, end);
                index = end;
            } else if (current == '`') {
                int end = skipBacktickIdentifier(body, index);
                converted.append(body, index, end);
                index = end;
            } else if (startsLineComment(body, index)) {
                int end = skipUntilLineEnd(body, index);
                converted.append(body, index, end);
                index = end;
            } else if (startsBlockComment(body, index)) {
                int end = skipUntilBlockCommentEnd(body, index);
                converted.append(body, index, end);
                index = end;
            } else {
                if (!isSqlIdentifierStart(body, index)) {
                    converted.append(current);
                    index++;
                    continue;
                }
                SqlIdentifierReference reference = sqlIdentifierReferenceAt(body, index);
                if (reference == null) {
                    converted.append(current);
                    index++;
                    continue;
                }
                ProcedureAssignedParameter assignedParameter =
                        assignedParameters.get(normalizedProcedureVariableName(reference.token()));
                if (assignedParameter == null || isQualifiedIdentifierPart(body, index, reference.end())) {
                    converted.append(body, index, reference.end());
                } else {
                    converted.append(assignedParameter.localName());
                }
                index = reference.end();
            }
        }
        return converted.toString();
    }

    private boolean isSqlIdentifierStart(String sql, int index) {
        if (index >= sql.length()) {
            return false;
        }
        char current = sql.charAt(index);
        return current == '`'
                || current == '"'
                || Character.isLetter(current)
                || current == '_';
    }

    private boolean isQualifiedIdentifierPart(String sql, int start, int end) {
        int before = start - 1;
        while (before >= 0 && Character.isWhitespace(sql.charAt(before))) {
            before--;
        }
        int after = skipWhitespace(sql, end);
        return (before >= 0 && sql.charAt(before) == '.')
                || (after < sql.length() && sql.charAt(after) == '.');
    }

    private String convertMysqlProcedureDynamicPrepare(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        return Pattern.compile(
                        "(?is)\\bPREPARE\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s+FROM\\s+(@?[A-Za-z_][A-Za-z0-9_$]*)\\s*;\\s*"
                                + "EXECUTE\\s+\\1\\s*;\\s*"
                                + "DEALLOCATE\\s+PREPARE\\s+\\1\\s*;"
                )
                .matcher(sql)
                .replaceAll("EXECUTE IMMEDIATE $2;");
    }

    private String convertMysqlProcedureSignal(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        String converted = Pattern.compile(
                        "(?is)\\bSIGNAL\\s+SQLSTATE\\s+'45000'\\s+SET\\s+MESSAGE_TEXT\\s*=\\s*([^;]+)"
                )
                .matcher(sql)
                .replaceAll("RAISE_APPLICATION_ERROR(-20000, $1)");
        return Pattern.compile("(?is)\\bSIGNAL\\s+SQLSTATE\\s+'45000'\\s*;")
                .matcher(converted)
                .replaceAll("RAISE_APPLICATION_ERROR(-20000, 'SQLSTATE 45000');");
    }

    private String convertMysqlProcedureDeleteAliasStar(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        return replaceOutsideIgnoredText(
                sql,
                Pattern.compile(
                        "(?is)\\bDELETE\\s+(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s*\\.\\s*\\*\\s+FROM\\s+"
                                + "(" + SQL_IDENTIFIER_TOKEN + ")\\s+(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")"
                                + "(?:\\s+(?:USE|FORCE|IGNORE)\\s+INDEX\\s*\\([^)]*\\))?"
                ),
                matcher -> {
                    String deleteAlias = matcher.group(1);
                    String table = matcher.group(2);
                    String tableAlias = matcher.group(3);
                    if (!sameIdentifier(deleteAlias, tableAlias)) {
                        return matcher.group();
                    }
                    return "DELETE FROM " + table + " " + tableAlias;
                }
        );
    }

    private String convertMysqlProcedureLocalSetAssignments(String sql) {
        if (!isCreateRoutineStatement(sql)) {
            return sql;
        }
        int beginIndex = firstProcedureBegin(sql);
        if (beginIndex < 0) {
            return sql;
        }
        Map<String, String> variableNames = procedureVariableNamesByLowercase(sql);
        if (variableNames.isEmpty()) {
            return sql;
        }
        Map<String, String> variableTypes = procedureVariableTypesByLowercase(sql);

        StringBuilder converted = new StringBuilder(sql.length());
        int cursor = 0;
        int index = beginIndex + "BEGIN".length();
        boolean changed = false;
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
            } else if (startsKeyword(sql, index, "SET")) {
                ProcedureSetAssignment assignment = procedureLocalSetAssignmentAt(sql, index, variableNames);
                if (assignment == null) {
                    index++;
                } else {
                    String target = sql.substring(assignment.targetStart(), assignment.targetEnd());
                    String expression = sql.substring(assignment.expressionStart(), assignment.statementEnd());
                    converted.append(sql, cursor, index);
                    converted.append(target)
                            .append(" := ")
                            .append(rewriteProcedureLocalSetExpression(target, expression, variableTypes));
                    if (assignment.statementEnd() < sql.length() && sql.charAt(assignment.statementEnd()) == ';') {
                        converted.append(';');
                        cursor = assignment.statementEnd() + 1;
                    } else {
                        cursor = assignment.statementEnd();
                    }
                    index = cursor;
                    changed = true;
                }
            } else {
                index++;
            }
        }
        if (!changed) {
            return sql;
        }
        converted.append(sql.substring(cursor));
        return converted.toString();
    }

    private ProcedureSetAssignment procedureLocalSetAssignmentAt(
            String sql,
            int setIndex,
            Map<String, String> variableNames
    ) {
        int targetStart = skipWhitespace(sql, setIndex + "SET".length());
        if (targetStart >= sql.length()
                || sql.charAt(targetStart) == '@'
                || startsKeyword(sql, targetStart, "SESSION")
                || startsKeyword(sql, targetStart, "GLOBAL")) {
            return null;
        }
        int targetEnd = simpleIdentifierEnd(sql, targetStart);
        if (targetEnd <= targetStart) {
            return null;
        }
        int operatorIndex = skipWhitespace(sql, targetEnd);
        String normalizedTarget = normalizedProcedureVariableName(sql.substring(targetStart, targetEnd));
        if (operatorIndex >= sql.length()
                || (!variableNames.containsKey(normalizedTarget) && !normalizedTarget.startsWith("dm_"))) {
            return null;
        }
        int operatorEnd;
        if (sql.charAt(operatorIndex) == ':'
                && operatorIndex + 1 < sql.length()
                && sql.charAt(operatorIndex + 1) == '=') {
            operatorEnd = operatorIndex + 2;
        } else if (sql.charAt(operatorIndex) == '='
                && (operatorIndex + 1 >= sql.length() || sql.charAt(operatorIndex + 1) != '=')) {
            operatorEnd = operatorIndex + 1;
        } else {
            return null;
        }
        int expressionStart = skipWhitespace(sql, operatorEnd);
        int statementEnd = findStatementTerminator(sql, expressionStart);
        String expression = sql.substring(expressionStart, statementEnd).strip();
        if (expression.isBlank() || splitTopLevelComma(expression).size() > 1) {
            return null;
        }
        return new ProcedureSetAssignment(targetStart, targetEnd, expressionStart, statementEnd);
    }

    private String rewriteProcedureLocalSetExpression(
            String target,
            String expression,
            Map<String, String> variableTypes
    ) {
        String variableType = variableTypes.get(normalizedProcedureVariableName(target));
        String textExpression = castJsonTextExtractions(expression);
        if (textExpression.equals(expression) && !isJsonTextExtractionExpression(expression)) {
            return expression;
        }
        String stripped = textExpression.strip();
        String normalizedText = "NULLIF(NULLIF(TRIM(" + stripped + "), ''), 'null')";
        if (isNumericProcedureType(variableType)) {
            return "TO_NUMBER(" + (isJsonTextExtractionExpression(expression) ? normalizedText : stripped) + ")";
        }
        if (isTimestampProcedureType(variableType)) {
            return "TO_TIMESTAMP(" + (isJsonTextExtractionExpression(expression) ? normalizedText : stripped) + ")";
        }
        if (isDateProcedureType(variableType)) {
            return "TO_DATE(" + (isJsonTextExtractionExpression(expression) ? normalizedText : stripped) + ")";
        }
        return textExpression;
    }

    private String castJsonTextExtractions(String expression) {
        StringBuilder converted = new StringBuilder(expression.length() + 32);
        int cursor = 0;
        int index = 0;
        boolean changed = false;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(expression, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(expression, index);
            } else if (startsKeyword(expression, index, "JSON_UNQUOTE")) {
                int openParen = skipWhitespace(expression, index + "JSON_UNQUOTE".length());
                if (openParen < expression.length() && expression.charAt(openParen) == '(') {
                    int closeParen = findMatchingParen(expression, openParen);
                    String body = closeParen > openParen
                            ? expression.substring(openParen + 1, closeParen).strip()
                            : "";
                    if (closeParen > openParen && startsKeyword(body, 0, "JSON_EXTRACT")) {
                        converted.append(expression, cursor, index)
                                .append("CAST(")
                                .append(expression, index, closeParen + 1)
                                .append(" AS VARCHAR(4000))");
                        cursor = closeParen + 1;
                        index = cursor;
                        changed = true;
                        continue;
                    }
                }
                index++;
            } else {
                index++;
            }
        }
        if (!changed) {
            return expression;
        }
        converted.append(expression.substring(cursor));
        return converted.toString();
    }

    private boolean isJsonTextExtractionExpression(String expression) {
        return Pattern.compile("(?is)^JSON_UNQUOTE\\s*\\(\\s*JSON_EXTRACT\\s*\\(.+\\)\\s*\\)$")
                .matcher(expression.strip())
                .matches();
    }

    private boolean isNumericProcedureType(String type) {
        return type != null
                && Pattern.compile(
                        "(?is)\\b(?:TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT|DECIMAL|NUMERIC|NUMBER|DOUBLE|FLOAT|REAL)\\b"
                ).matcher(type).find();
    }

    private boolean isTimestampProcedureType(String type) {
        return type != null
                && Pattern.compile("(?is)\\b(?:TIMESTAMP|DATETIME)\\b")
                .matcher(type)
                .find();
    }

    private boolean isDateProcedureType(String type) {
        return type != null
                && Pattern.compile("(?is)\\bDATE\\b")
                .matcher(type)
                .find()
                && !isTimestampProcedureType(type);
    }

    private String convertMysqlQuoteCallsInProcedure(String sql) {
        if (sql == null || sql.isBlank()
                || !Pattern.compile("(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\b").matcher(sql).find()) {
            return sql == null ? "" : sql;
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
            } else if (startsKeyword(sql, index, "QUOTE") && !isSchemaQualifiedFunctionName(sql, index)) {
                int openParen = skipWhitespace(sql, index + "QUOTE".length());
                if (openParen < sql.length() && sql.charAt(openParen) == '(') {
                    int closeParen = findMatchingParen(sql, openParen);
                    if (closeParen > openParen) {
                        List<String> arguments = splitTopLevelComma(sql.substring(openParen + 1, closeParen));
                        if (arguments.size() == 1 && !arguments.get(0).isBlank()) {
                            converted.append(dmQuotedSqlLiteralExpression(arguments.get(0).strip()));
                            index = closeParen + 1;
                            changed = true;
                        } else {
                            converted.append(current);
                            index++;
                        }
                    } else {
                        converted.append(current);
                        index++;
                    }
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

    private String dmQuotedSqlLiteralExpression(String expression) {
        return "CASE WHEN " + expression + " IS NULL THEN 'NULL' ELSE '''' || REPLACE(CAST("
                + expression
                + " AS VARCHAR(4000)), '''', '''''') || '''' END";
    }

    private String convertProcedureJsonTimestampValues(String sql) {
        if (sql == null || sql.isBlank()
                || !Pattern.compile("(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\b").matcher(sql).find()) {
            return sql == null ? "" : sql;
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
            } else if (startsKeyword(sql, index, "JSON_SET") && !isSchemaQualifiedFunctionName(sql, index)) {
                int openParen = skipWhitespace(sql, index + "JSON_SET".length());
                if (openParen < sql.length() && sql.charAt(openParen) == '(') {
                    int closeParen = findMatchingParen(sql, openParen);
                    if (closeParen > openParen) {
                        List<String> arguments = splitTopLevelComma(sql.substring(openParen + 1, closeParen));
                        List<String> convertedArguments = new ArrayList<>(arguments.size());
                        boolean callChanged = false;
                        for (int i = 0; i < arguments.size(); i++) {
                            String argument = arguments.get(i).strip();
                            if (i >= 2 && i % 2 == 0) {
                                String rewritten = dmJsonTimestampValueExpression(argument);
                                convertedArguments.add(rewritten);
                                callChanged = callChanged || !rewritten.equals(argument);
                            } else {
                                convertedArguments.add(argument);
                            }
                        }
                        if (callChanged) {
                            converted.append("JSON_SET(")
                                    .append(String.join(", ", convertedArguments))
                                    .append(")");
                            index = closeParen + 1;
                            changed = true;
                        } else {
                            converted.append(sql, index, closeParen + 1);
                            index = closeParen + 1;
                        }
                    } else {
                        converted.append(current);
                        index++;
                    }
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

    private String dmJsonTimestampValueExpression(String expression) {
        if (!Pattern.compile("(?is)^(?:CURRENT_TIMESTAMP(?:\\s*\\(\\s*\\d*\\s*\\))?|NOW\\s*\\(\\s*\\))$")
                .matcher(expression)
                .matches()) {
            return expression;
        }
        return "TO_CHAR(" + expression + ", 'YYYY-MM-DD HH24:MI:SS.FF3')";
    }

    private String convertProcedureDateTimeFunctions(String sql) {
        if (sql == null || sql.isBlank()
                || !Pattern.compile("(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\b").matcher(sql).find()) {
            return sql == null ? "" : sql;
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
            } else if (startsKeyword(sql, index, "CONCAT") && !isSchemaQualifiedFunctionName(sql, index)) {
                int openParen = skipWhitespace(sql, index + "CONCAT".length());
                if (openParen < sql.length() && sql.charAt(openParen) == '(') {
                    int closeParen = findMatchingParen(sql, openParen);
                    if (closeParen > openParen) {
                        String originalCall = sql.substring(index, closeParen + 1);
                        String rewritten = convertMysqlDateConcatCall(originalCall);
                        converted.append(rewritten);
                        changed = changed || !rewritten.equals(originalCall);
                        index = closeParen + 1;
                    } else {
                        converted.append(current);
                        index++;
                    }
                } else {
                    converted.append(current);
                    index++;
                }
            } else if (startsKeyword(sql, index, "TIME") && !isSchemaQualifiedFunctionName(sql, index)) {
                int openParen = skipWhitespace(sql, index + "TIME".length());
                if (openParen < sql.length() && sql.charAt(openParen) == '(') {
                    int closeParen = findMatchingParen(sql, openParen);
                    if (closeParen > openParen) {
                        List<String> arguments = splitTopLevelComma(sql.substring(openParen + 1, closeParen));
                        if (arguments.size() == 1 && !arguments.get(0).isBlank()) {
                            converted.append("TO_CHAR(")
                                    .append(arguments.get(0).strip())
                                    .append(", 'HH24:MI:SS')");
                            index = closeParen + 1;
                            changed = true;
                        } else {
                            converted.append(sql, index, closeParen + 1);
                            index = closeParen + 1;
                        }
                    } else {
                        converted.append(current);
                        index++;
                    }
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

    private String convertMysqlDateConcatCall(String call) {
        Matcher matcher = Pattern.compile("(?is)^\\s*CONCAT\\s*\\(").matcher(call);
        if (!matcher.find()) {
            return call;
        }
        int openParen = call.indexOf('(', matcher.start());
        int closeParen = findMatchingParen(call, openParen);
        if (closeParen <= openParen || skipWhitespace(call, closeParen + 1) != call.length()) {
            return call;
        }
        List<String> arguments = splitTopLevelComma(call.substring(openParen + 1, closeParen));
        if (arguments.size() != 2) {
            return call;
        }
        String dateArgument = singleFunctionArgument(arguments.get(0), "DATE");
        if (dateArgument.isBlank()) {
            return call;
        }
        String timeLiteral = singleQuotedSqlLiteralValue(arguments.get(1));
        if (!Pattern.compile("^\\s+\\d{2}:\\d{2}:\\d{2}$").matcher(timeLiteral).matches()) {
            return call;
        }
        return "TO_TIMESTAMP(TO_CHAR(" + dateArgument + ", 'YYYY-MM-DD') || "
                + sqlStringLiteral(timeLiteral)
                + ", 'YYYY-MM-DD HH24:MI:SS')";
    }

    private String singleFunctionArgument(String expression, String functionName) {
        String stripped = expression.strip();
        if (!startsKeyword(stripped, 0, functionName)) {
            return "";
        }
        int openParen = skipWhitespace(stripped, functionName.length());
        if (openParen >= stripped.length() || stripped.charAt(openParen) != '(') {
            return "";
        }
        int closeParen = findMatchingParen(stripped, openParen);
        if (closeParen <= openParen || skipWhitespace(stripped, closeParen + 1) != stripped.length()) {
            return "";
        }
        List<String> arguments = splitTopLevelComma(stripped.substring(openParen + 1, closeParen));
        if (arguments.size() != 1 || arguments.get(0).isBlank()) {
            return "";
        }
        return arguments.get(0).strip();
    }

    private String singleQuotedSqlLiteralValue(String expression) {
        String stripped = expression.strip();
        if (stripped.isEmpty() || stripped.charAt(0) != '\'' || skipSingleQuotedString(stripped, 0) != stripped.length()) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        int index = 1;
        while (index < stripped.length() - 1) {
            char current = stripped.charAt(index);
            if (current == '\\' && index + 1 < stripped.length() - 1) {
                value.append(stripped.charAt(index + 1));
                index += 2;
            } else if (current == '\'' && index + 1 < stripped.length() - 1 && stripped.charAt(index + 1) == '\'') {
                value.append('\'');
                index += 2;
            } else {
                value.append(current);
                index++;
            }
        }
        return value.toString();
    }

    private boolean isSchemaQualifiedFunctionName(String value, int index) {
        int previous = previousNonWhitespace(value, index - 1);
        return previous >= 0 && value.charAt(previous) == '.';
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

    private boolean containsIdentifierReferenceOutsideIgnoredText(String sql, String identifier) {
        String normalized = unquoteIdentifier(identifier).toLowerCase(Locale.ROOT);
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(sql, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(sql, index);
            } else if (current == '`') {
                int end = skipBacktickIdentifier(sql, index);
                String quotedIdentifier = sql.substring(index + 1, Math.max(index + 1, end - 1));
                if (quotedIdentifier.equalsIgnoreCase(normalized)) {
                    return true;
                }
                index = end;
            } else if (startsLineComment(sql, index)) {
                index = skipUntilLineEnd(sql, index);
            } else if (startsBlockComment(sql, index)) {
                index = skipUntilBlockCommentEnd(sql, index);
            } else if (Character.isLetter(current) || current == '_') {
                int end = index + 1;
                while (end < sql.length() && isIdentifierPart(sql.charAt(end))) {
                    end++;
                }
                if (sql.substring(index, end).equalsIgnoreCase(normalized)) {
                    return true;
                }
                index = end;
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
                    + normalizeMysqlDataTypes(parts.type())
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
        if (!isCreateRoutineStatement(sql)) {
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
        int declarationInsertIndex = procedureUserVariableDeclarationInsertIndex(replaced, replacedBeginIndex);
        return replaced.substring(0, declarationInsertIndex)
                + declarations
                + replaced.substring(declarationInsertIndex);
    }

    private int procedureUserVariableDeclarationInsertIndex(String sql, int beginIndex) {
        Matcher cursorMatcher = Pattern.compile(
                "(?im)^\\s*(?:" + SQL_IDENTIFIER_TOKEN + ")\\s+CURSOR\\s+FOR\\b"
        ).matcher(sql);
        cursorMatcher.region(0, beginIndex);
        if (cursorMatcher.find()) {
            return cursorMatcher.start();
        }
        return beginIndex;
    }

    private String convertMysqlProcedureIfExistsConditions(String sql) {
        if (!isCreateProcedureStatement(sql)) {
            return sql;
        }
        int beginIndex = firstProcedureBegin(sql);
        if (beginIndex < 0) {
            return sql;
        }
        LinkedHashSet<String> existingNames = procedureNamesInScope(sql, beginIndex);
        List<ProcedureExistsCondition> conditions = new ArrayList<>();
        StringBuilder converted = new StringBuilder(sql.length());
        int cursor = 0;
        int index = beginIndex + "BEGIN".length();
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
            } else if (startsKeyword(sql, index, "IF")) {
                ProcedureExistsCondition condition = parseMysqlProcedureIfExistsCondition(sql, index, existingNames);
                if (condition == null) {
                    index++;
                } else {
                    converted.append(sql, cursor, condition.start());
                    converted.append(condition.replacement());
                    cursor = condition.end();
                    index = condition.end();
                    conditions.add(condition);
                }
            } else {
                index++;
            }
        }
        if (conditions.isEmpty()) {
            return sql;
        }
        converted.append(sql.substring(cursor));
        int convertedBeginIndex = firstProcedureBegin(converted.toString());
        if (convertedBeginIndex < 0) {
            return sql;
        }
        StringBuilder declarations = new StringBuilder();
        for (ProcedureExistsCondition condition : conditions) {
            for (String variableName : condition.variableNames()) {
                declarations.append("    ").append(variableName).append(" INT;\n");
            }
        }
        return converted.substring(0, convertedBeginIndex)
                + declarations
                + converted.substring(convertedBeginIndex);
    }

    private ProcedureExistsCondition parseMysqlProcedureIfExistsCondition(
            String sql,
            int ifIndex,
            LinkedHashSet<String> existingNames
    ) {
        int cursor = skipWhitespace(sql, ifIndex + "IF".length());
        boolean negated = false;
        if (startsKeyword(sql, cursor, "NOT")) {
            negated = true;
            cursor = skipWhitespace(sql, cursor + "NOT".length());
        }
        if (!startsKeyword(sql, cursor, "EXISTS")) {
            return null;
        }
        cursor = skipWhitespace(sql, cursor + "EXISTS".length());
        if (cursor >= sql.length() || sql.charAt(cursor) != '(') {
            return null;
        }
        int closeParen = findMatchingParen(sql, cursor);
        if (closeParen <= cursor) {
            return null;
        }
        List<ProcedureExistsTerm> terms = new ArrayList<>();
        while (true) {
            String existsSelect = sql.substring(cursor + 1, closeParen).strip();
            if (!startsKeyword(existsSelect, skipWhitespace(existsSelect, 0), "SELECT")) {
                return null;
            }
            String variableName = uniqueProcedureLocalName("dm_adapter_exists", existingNames);
            terms.add(new ProcedureExistsTerm(existsSelect, negated, variableName));
            int nextIndex = skipWhitespace(sql, closeParen + 1);
            if (!startsKeyword(sql, nextIndex, "AND")) {
                cursor = nextIndex;
                break;
            }
            cursor = skipWhitespace(sql, nextIndex + "AND".length());
            negated = false;
            if (startsKeyword(sql, cursor, "NOT")) {
                negated = true;
                cursor = skipWhitespace(sql, cursor + "NOT".length());
            }
            if (!startsKeyword(sql, cursor, "EXISTS")) {
                return null;
            }
            cursor = skipWhitespace(sql, cursor + "EXISTS".length());
            if (cursor >= sql.length() || sql.charAt(cursor) != '(') {
                return null;
            }
            closeParen = findMatchingParen(sql, cursor);
            if (closeParen <= cursor) {
                return null;
            }
        }
        int thenIndex = skipWhitespace(sql, cursor);
        if (!startsKeyword(sql, thenIndex, "THEN")) {
            return null;
        }
        String indent = lineIndentBefore(sql, ifIndex);
        StringBuilder replacement = new StringBuilder();
        List<String> checks = new ArrayList<>();
        List<String> variableNames = new ArrayList<>();
        for (ProcedureExistsTerm term : terms) {
            replacement.append("SELECT COUNT(*) INTO ")
                    .append(term.variableName())
                    .append(" FROM (\n")
                    .append(term.existsSelect())
                    .append("\n) dm_adapter_exists_check;\n")
                    .append(indent);
            checks.add(term.variableName() + (term.negated() ? " = 0" : " > 0"));
            variableNames.add(term.variableName());
        }
        replacement.append("IF ")
                .append(String.join(" AND ", checks))
                .append(" THEN");
        return new ProcedureExistsCondition(
                ifIndex,
                thenIndex + "THEN".length(),
                variableNames,
                replacement.toString()
        );
    }

    private String uniqueProcedureLocalName(String base, LinkedHashSet<String> existingNames) {
        String candidate = base;
        int suffix = 1;
        while (existingNames.contains(candidate.toLowerCase(Locale.ROOT))) {
            suffix++;
            candidate = base + "_" + suffix;
        }
        existingNames.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    private String lineIndentBefore(String value, int index) {
        int cursor = Math.min(index, value.length()) - 1;
        while (cursor >= 0 && value.charAt(cursor) != '\n' && value.charAt(cursor) != '\r') {
            cursor--;
        }
        String indent = value.substring(cursor + 1, Math.min(index, value.length()));
        for (int i = 0; i < indent.length(); i++) {
            char current = indent.charAt(i);
            if (current != ' ' && current != '\t') {
                return "";
            }
        }
        return indent;
    }

    private boolean isCreateProcedureStatement(String sql) {
        return isCreateRoutineStatement(sql, "PROCEDURE");
    }

    private boolean isCreateFunctionStatement(String sql) {
        return isCreateRoutineStatement(sql, "FUNCTION");
    }

    private boolean isCreateRoutineStatement(String sql) {
        return isCreateProcedureStatement(sql) || isCreateFunctionStatement(sql);
    }

    private boolean isCreateRoutineStatement(String sql, String routineKind) {
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
        return startsKeyword(sql, cursor, routineKind);
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
                    && !statementStartsWithKeyword(sql, reference.start(), "SET")
                    && !previousWordIsKeyword(sql, reference.start(), "SET")) {
                return true;
            }
        }
        return false;
    }

    private boolean previousWordIsKeyword(String sql, int beforeIndex, String keyword) {
        int cursor = Math.min(beforeIndex, sql.length()) - 1;
        while (cursor >= 0 && Character.isWhitespace(sql.charAt(cursor))) {
            cursor--;
        }
        int end = cursor + 1;
        while (cursor >= 0 && (Character.isLetterOrDigit(sql.charAt(cursor)) || sql.charAt(cursor) == '_')) {
            cursor--;
        }
        if (end <= cursor + 1) {
            return false;
        }
        return sql.substring(cursor + 1, end).equalsIgnoreCase(keyword);
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
        RoutineKeyword routine = routineKeyword(headerAndDeclarations);
        if (routine == null) {
            return;
        }
        int openParen = firstTopLevelParen(headerAndDeclarations.substring(routine.index() + routine.keyword().length()));
        if (openParen < 0) {
            return;
        }
        openParen += routine.index() + routine.keyword().length();
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

    private RoutineKeyword routineKeyword(String value) {
        int procedureIndex = keywordIndex(value, "PROCEDURE");
        int functionIndex = keywordIndex(value, "FUNCTION");
        if (procedureIndex < 0 && functionIndex < 0) {
            return null;
        }
        if (functionIndex < 0 || (procedureIndex >= 0 && procedureIndex < functionIndex)) {
            return new RoutineKeyword(procedureIndex, "PROCEDURE");
        }
        return new RoutineKeyword(functionIndex, "FUNCTION");
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
        Matcher implicitMatcher = Pattern.compile("(?is)^(" + SQL_IDENTIFIER_TOKEN + ")\\s+.+$")
                .matcher(stripped);
        if (implicitMatcher.matches()) {
            return unquoteIdentifier(implicitMatcher.group(1));
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
        if (hasLargeStringAssignment(sql, userVariableName)
                || hasDynamicDmlAccumulatorAssignment(sql, userVariableName)) {
            return "CLOB";
        }
        if (hasNumericUserVariableContext(sql, userVariableName)
                || hasNumericUserVariableName(userVariableName)) {
            return "BIGINT";
        }
        return "VARCHAR(4000)";
    }

    private boolean hasDynamicDmlAccumulatorAssignment(String sql, String userVariableName) {
        if (!isDynamicDmlAccumulatorName(userVariableName)) {
            return false;
        }
        for (UserVariableReference reference : mysqlUserVariableReferences(sql)) {
            if (!reference.name().equals(userVariableName)) {
                continue;
            }
            String literal = assignedStringLiteral(sql, reference);
            if (!literal.isBlank()
                    && Pattern.compile("(?is)^\\s*(?:INSERT|UPDATE|DELETE|MERGE)\\b").matcher(literal).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isDynamicDmlAccumulatorName(String userVariableName) {
        String normalized = userVariableName.toLowerCase(Locale.ROOT);
        return normalized.endsWith("insert_sql")
                || normalized.endsWith("update_sql")
                || normalized.endsWith("delete_sql")
                || normalized.endsWith("merge_sql");
    }

    private boolean hasLargeStringAssignment(String sql, String userVariableName) {
        for (UserVariableReference reference : mysqlUserVariableReferences(sql)) {
            if (!reference.name().equals(userVariableName)) {
                continue;
            }
            String literal = assignedStringLiteral(sql, reference);
            if (literal.length() > 3500) {
                return true;
            }
        }
        return false;
    }

    private String assignedStringLiteral(String sql, UserVariableReference reference) {
        int cursor = skipWhitespace(sql, reference.end());
        if (cursor + 1 < sql.length() && sql.charAt(cursor) == ':' && sql.charAt(cursor + 1) == '=') {
            cursor += 2;
        } else if (cursor < sql.length() && sql.charAt(cursor) == '=') {
            cursor++;
        } else {
            return "";
        }
        cursor = skipWhitespace(sql, cursor);
        if (cursor >= sql.length() || sql.charAt(cursor) != '\'') {
            return "";
        }
        int end = skipSingleQuotedString(sql, cursor);
        if (end <= cursor + 1) {
            return "";
        }
        return sql.substring(cursor + 1, end - 1);
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

    private SqlIdentifierReference sqlIdentifierReferenceAt(String value, int index) {
        int cursor = skipWhitespace(value, index);
        int start = cursor;
        int end = cursor;
        boolean matched = false;
        while (cursor < value.length()) {
            int partEnd = simpleIdentifierEnd(value, cursor);
            if (partEnd <= cursor) {
                break;
            }
            matched = true;
            end = partEnd;
            cursor = skipWhitespace(value, partEnd);
            if (cursor < value.length() && value.charAt(cursor) == '.') {
                cursor = skipWhitespace(value, cursor + 1);
                end = cursor;
                continue;
            }
            break;
        }
        if (!matched) {
            return null;
        }
        return new SqlIdentifierReference(value.substring(start, end), end);
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

    private int findTopLevelChar(String value, char target, int start) {
        int index = Math.max(0, start);
        int depth = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\'') {
                index = skipSingleQuotedString(value, index);
            } else if (current == '"') {
                index = skipDoubleQuotedText(value, index);
            } else if (current == '`') {
                index = skipBacktickIdentifier(value, index);
            } else if (startsLineComment(value, index)) {
                index = skipUntilLineEnd(value, index);
            } else if (startsBlockComment(value, index)) {
                index = skipUntilBlockCommentEnd(value, index);
            } else if (current == '(') {
                if (current == target && depth == 0) {
                    return index;
                }
                depth++;
                index++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
                index++;
            } else if (current == target && depth == 0) {
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
                String normalizedSingleQuotes = normalizeMysqlBackslashEscapedSingleQuotes(raw);
                if (!normalizedSingleQuotes.equals(raw)) {
                    return new SingleQuotedStringRewrite(sqlStringLiteral(normalizedSingleQuotes), end, true);
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

    private String normalizeMysqlBackslashEscapedSingleQuotes(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean changed = false;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\\' && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                normalized.append('\'');
                index += 2;
                changed = true;
            } else {
                normalized.append(current);
                index++;
            }
        }
        return changed ? normalized.toString() : value;
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
        LinkedHashMap<String, LinkedHashSet<String>> temporaryTableColumns = temporaryProcedureTableDefinitions(sql);
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
                List<ProcedureStatement> ddlStatements =
                        convertProcedureDdlStatements(sql.substring(index, end).strip(), temporaryTableColumns);
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
        String convertedSql = changed ? converted.toString() : sql;
        return addTemporaryInsertSelectColumnLists(convertedSql, temporaryTableColumns);
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

    private Map<String, String> procedureVariableTypesByLowercase(String sql) {
        int beginIndex = firstProcedureBegin(sql);
        if (beginIndex < 0) {
            return Map.of();
        }
        LinkedHashMap<String, String> types = new LinkedHashMap<>();
        String headerAndDeclarations = sql.substring(0, beginIndex);
        collectProcedureParameterTypes(headerAndDeclarations, types);
        Matcher declarationMatcher = Pattern.compile(
                "(?im)^\\s*(" + SQL_SIMPLE_IDENTIFIER_TOKEN + ")\\s+([^\\n;]+);"
        ).matcher(headerAndDeclarations);
        while (declarationMatcher.find()) {
            String name = declarationMatcher.group(1);
            String type = declarationTypeBeforeDefault(declarationMatcher.group(2));
            if (!type.isBlank()) {
                types.putIfAbsent(normalizedProcedureVariableName(name), type);
            }
        }
        return types;
    }

    private void collectProcedureParameterTypes(String headerAndDeclarations, LinkedHashMap<String, String> types) {
        RoutineKeyword routine = routineKeyword(headerAndDeclarations);
        if (routine == null) {
            return;
        }
        int openParen = firstTopLevelParen(headerAndDeclarations.substring(routine.index() + routine.keyword().length()));
        if (openParen < 0) {
            return;
        }
        openParen += routine.index() + routine.keyword().length();
        int closeParen = findMatchingParen(headerAndDeclarations, openParen);
        if (closeParen <= openParen) {
            return;
        }
        for (String parameter : splitTopLevelComma(headerAndDeclarations.substring(openParen + 1, closeParen))) {
            ProcedureParameterParts parts = parseProcedureParameter(parameter);
            if (parts != null) {
                String type = declarationTypeBeforeDefault(parts.type());
                if (!type.isBlank()) {
                    types.putIfAbsent(normalizedProcedureVariableName(parts.name()), type);
                }
            }
        }
    }

    private void collectProcedureParameterNames(String headerAndDeclarations, LinkedHashMap<String, String> names) {
        RoutineKeyword routine = routineKeyword(headerAndDeclarations);
        if (routine == null) {
            return;
        }
        int openParen = firstTopLevelParen(headerAndDeclarations.substring(routine.index() + routine.keyword().length()));
        if (openParen < 0) {
            return;
        }
        openParen += routine.index() + routine.keyword().length();
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

    private List<ProcedureStatement> convertProcedureDdlStatements(
            String ddl,
            Map<String, LinkedHashSet<String>> temporaryTableColumns
    ) {
        String converted = removeMysqlTemporaryKeyword(ddl);
        List<ProcedureStatement> temporaryDropTables = convertTemporaryDropTableToDml(converted);
        if (!temporaryDropTables.isEmpty()) {
            return temporaryDropTables;
        }
        ProcedureStatement temporaryTruncateTable = convertTemporaryTruncateTableToDml(converted);
        if (temporaryTruncateTable != null) {
            return List.of(temporaryTruncateTable);
        }
        ProcedureStatement temporaryCreateTableDefinition = convertTemporaryCreateTableDefinitionToDml(converted);
        if (temporaryCreateTableDefinition != null) {
            return List.of(temporaryCreateTableDefinition);
        }
        ProcedureStatement temporaryCreateTableSelect =
                convertTemporaryCreateTableSelectToInsert(converted, temporaryTableColumns);
        if (temporaryCreateTableSelect != null) {
            return List.of(temporaryCreateTableSelect);
        }
        ProcedureStatement temporaryAddColumn = convertTemporaryAlterTableAddColumnToNoop(converted);
        if (temporaryAddColumn != null) {
            return List.of(temporaryAddColumn);
        }
        List<String> indexAlterStatements = splitMysqlAlterTableDropAndAddIndexes(converted);
        if (!indexAlterStatements.isEmpty()) {
            return indexAlterStatements.stream().map(ProcedureStatement::dynamicSql).toList();
        }
        List<String> dropTables = splitMysqlDropTables(converted);
        if (!dropTables.isEmpty()) {
            return dropTables.stream().map(ProcedureStatement::dynamicSql).toList();
        }
        converted = convertMysqlCreateTableSelect(converted);
        converted = convertMysqlAlterTableAddIndex(converted);
        converted = convertMysqlAlterTableDropForeignKey(converted);
        ProcedureStatement temporaryIndexDdl = convertTemporaryIndexDdlToNoop(converted);
        if (temporaryIndexDdl != null) {
            return List.of(temporaryIndexDdl);
        }
        converted = normalizeCreateIndexForDm(converted);
        temporaryIndexDdl = convertTemporaryIndexDdlToNoop(converted);
        if (temporaryIndexDdl != null) {
            return List.of(temporaryIndexDdl);
        }
        converted = normalizeMysqlAlterModifySyntax(converted);
        converted = normalizeMysqlAlterChangeSyntax(converted);
        String withoutInlineKeys = removeMysqlCreateTableInlineKeyDefinitions(converted);
        if (!withoutInlineKeys.equals(converted)) {
            converted = withoutInlineKeys;
        }
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
        converted = normalizeMysqlDataTypes(converted);
        converted = normalizeMysqlDynamicDdlForDameng(converted);
        converted = convertProcedureCreateTableDdlSyntax(converted);
        converted = normalizeProcedureDynamicDdlSpacing(converted);
        converted = convertProcedureCreateViewDdlSyntax(converted);
        return splitMultiModifyAlterTable(converted).stream().map(ProcedureStatement::dynamicSql).toList();
    }

    private String normalizeMysqlDataTypes(String sql) {
        String converted = Pattern.compile("(?is)\\bDOUBLE\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
                .matcher(sql)
                .replaceAll("DECIMAL($1, $2)");
        converted = Pattern.compile("(?is)\\bFLOAT\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
                .matcher(converted)
                .replaceAll("DECIMAL($1, $2)");
        converted = Pattern.compile("(?is)\\b(TINYTEXT|MEDIUMTEXT|LONGTEXT|TEXT)\\b")
                .matcher(converted)
                .replaceAll("CLOB");
        return Pattern.compile("(?is)\\b(TINYINT|SMALLINT|MEDIUMINT|INT|INTEGER|BIGINT)\\s*\\(\\s*\\d+\\s*\\)")
                .matcher(converted)
                .replaceAll("$1");
    }

    private String removeMysqlCreateTableInlineKeyDefinitions(String ddl) {
        int start = skipWhitespace(ddl, 0);
        if (!startsKeyword(ddl, start, "CREATE")) {
            return ddl;
        }
        int cursor = skipWhitespace(ddl, start + "CREATE".length());
        if (startsKeyword(ddl, cursor, "TEMPORARY")) {
            cursor = skipWhitespace(ddl, cursor + "TEMPORARY".length());
        }
        if (!startsKeyword(ddl, cursor, "TABLE")) {
            return ddl;
        }
        int openParen = findTopLevelChar(ddl, '(', cursor + "TABLE".length());
        if (openParen < 0) {
            return ddl;
        }
        int closeParen = findMatchingParen(ddl, openParen);
        if (closeParen <= openParen) {
            return ddl;
        }
        List<String> parts = splitTopLevelComma(ddl.substring(openParen + 1, closeParen));
        List<String> convertedParts = new ArrayList<>(parts.size());
        boolean changed = false;
        for (String part : parts) {
            String stripped = part.stripLeading();
            if (isMysqlCreateTableInlineSecondaryKey(stripped)) {
                changed = true;
                continue;
            }
            String withoutUsingBtree = replaceOutsideIgnoredText(
                    part,
                    Pattern.compile("(?is)\\s+USING\\s+BTREE\\b"),
                    matcher -> ""
            );
            if (!withoutUsingBtree.equals(part)) {
                changed = true;
            }
            convertedParts.add(withoutUsingBtree);
        }
        if (!changed) {
            return ddl;
        }
        return ddl.substring(0, openParen + 1)
                + String.join(",", convertedParts)
                + ddl.substring(closeParen);
    }

    private boolean isMysqlCreateTableInlineSecondaryKey(String part) {
        int cursor = skipWhitespace(part, 0);
        if (startsKeyword(part, cursor, "KEY") || startsKeyword(part, cursor, "INDEX")) {
            return true;
        }
        if (startsKeyword(part, cursor, "UNIQUE")) {
            cursor = skipWhitespace(part, cursor + "UNIQUE".length());
            return startsKeyword(part, cursor, "KEY") || startsKeyword(part, cursor, "INDEX");
        }
        if (startsKeyword(part, cursor, "FULLTEXT") || startsKeyword(part, cursor, "SPATIAL")) {
            cursor = skipWhitespace(part, cursor + (startsKeyword(part, cursor, "FULLTEXT")
                    ? "FULLTEXT".length()
                    : "SPATIAL".length()));
            return startsKeyword(part, cursor, "KEY") || startsKeyword(part, cursor, "INDEX");
        }
        return false;
    }

    private String convertProcedureCreateTableDdlSyntax(String ddl) {
        int start = skipWhitespace(ddl, 0);
        if (!startsKeyword(ddl, start, "CREATE")) {
            return ddl;
        }
        int cursor = skipWhitespace(ddl, start + "CREATE".length());
        if (startsKeyword(ddl, cursor, "TEMPORARY")) {
            cursor = skipWhitespace(ddl, cursor + "TEMPORARY".length());
        }
        if (!startsKeyword(ddl, cursor, "TABLE")) {
            return ddl;
        }
        SqlConversionResult conversion = converter.convert(ddl);
        return conversion.convertedSql();
    }

    private String convertProcedureCreateViewDdlSyntax(String ddl) {
        int start = skipWhitespace(ddl, 0);
        if (!startsKeyword(ddl, start, "CREATE")) {
            return ddl;
        }
        int cursor = skipWhitespace(ddl, start + "CREATE".length());
        if (startsKeyword(ddl, cursor, "OR")) {
            cursor = skipWhitespace(ddl, cursor + "OR".length());
            if (!startsKeyword(ddl, cursor, "REPLACE")) {
                return ddl;
            }
            cursor = skipWhitespace(ddl, cursor + "REPLACE".length());
        }
        if (!startsKeyword(ddl, cursor, "VIEW")) {
            return ddl;
        }
        SqlConversionResult conversion = converter.convert(ddl);
        return conversion.convertedSql();
    }

    private String convertMysqlAlterTableDropForeignKey(String ddl) {
        Matcher matcher = Pattern.compile(
                "(?is)^(\\s*ALTER\\s+TABLE\\s+(?:" + SQL_IDENTIFIER_TOKEN + ")\\s+DROP\\s+)"
                        + "FOREIGN\\s+KEY\\s+(" + SQL_IDENTIFIER_TOKEN + ")\\s*$"
        ).matcher(ddl);
        if (!matcher.matches()) {
            return ddl;
        }
        return matcher.group(1) + "CONSTRAINT " + unquoteIdentifier(matcher.group(2).strip());
    }

    private String normalizeProcedureDynamicDdlSpacing(String ddl) {
        Pattern missingSpaceBeforeTypePattern = Pattern.compile(
                "(?is)(`[^`]+`|\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)"
                        + "(?=(?:BIGINT|INT|INTEGER|DECIMAL|NUMERIC|NUMBER|VARCHAR2?|CHAR|TEXT|CLOB|DATE|DATETIME|TIMESTAMP)\\s*\\()"
        );
        StringBuilder converted = new StringBuilder(ddl.length());
        boolean changed = false;
        int index = 0;
        while (index < ddl.length()) {
            char current = ddl.charAt(index);
            if (current == '\'') {
                int end = skipSingleQuotedString(ddl, index);
                converted.append(ddl, index, end);
                index = end;
            } else if (current == '"') {
                int end = skipDoubleQuotedText(ddl, index);
                converted.append(ddl, index, end);
                index = end;
            } else if (current == '`') {
                Matcher matcher = missingSpaceBeforeTypePattern.matcher(ddl);
                matcher.region(index, ddl.length());
                if (matcher.lookingAt()) {
                    int typeKeywordEnd = typeKeywordPrefixSplitEnd(matcher.group(1), ddl, matcher.end(1));
                    if (typeKeywordEnd > matcher.end(1)) {
                        converted.append(ddl, index, typeKeywordEnd);
                        index = typeKeywordEnd;
                    } else {
                        converted.append(matcher.group(1)).append(" ");
                        index = matcher.end(1);
                        changed = true;
                    }
                } else {
                    int end = skipBacktickIdentifier(ddl, index);
                    converted.append(ddl, index, end);
                    index = end;
                }
            } else if (startsLineComment(ddl, index)) {
                int end = skipUntilLineEnd(ddl, index);
                converted.append(ddl, index, end);
                index = end;
            } else if (startsBlockComment(ddl, index)) {
                int end = skipUntilBlockCommentEnd(ddl, index);
                converted.append(ddl, index, end);
                index = end;
            } else {
                Matcher matcher = missingSpaceBeforeTypePattern.matcher(ddl);
                matcher.region(index, ddl.length());
                if (matcher.lookingAt()) {
                    int typeKeywordEnd = typeKeywordPrefixSplitEnd(matcher.group(1), ddl, matcher.end(1));
                    if (typeKeywordEnd > matcher.end(1)) {
                        converted.append(ddl, index, typeKeywordEnd);
                        index = typeKeywordEnd;
                    } else {
                        converted.append(matcher.group(1)).append(" ");
                        index = matcher.end(1);
                        changed = true;
                    }
                } else {
                    converted.append(current);
                    index++;
                }
            }
        }
        return changed ? converted.toString() : ddl;
    }

    private int typeKeywordPrefixSplitEnd(String token, String value, int suffixStart) {
        String lowerToken = token.toLowerCase(Locale.ROOT);
        String lowerSuffix = value.substring(suffixStart, Math.min(value.length(), suffixStart + 8))
                .toLowerCase(Locale.ROOT);
        if (lowerToken.equals("var") && lowerSuffix.startsWith("char")) {
            return suffixStart + "char".length();
        }
        if (lowerToken.endsWith("_")) {
            for (String typeKeyword : List.of("bigint", "int", "integer", "decimal", "numeric", "number",
                    "varchar", "varchar2", "char", "text", "clob", "date", "datetime", "timestamp")) {
                if (lowerSuffix.startsWith(typeKeyword)) {
                    return suffixStart + typeKeyword.length();
                }
            }
        }
        return -1;
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

    private ProcedureStatement convertTemporaryCreateTableDefinitionToDml(String ddl) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?<table>"
                        + SQL_IDENTIFIER_TOKEN + ")\\s*\\("
        ).matcher(ddl);
        if (!matcher.find() || !isProcedureTemporaryTableName(matcher.group("table"))) {
            return null;
        }
        int openParen = matcher.end() - 1;
        int closeParen = findMatchingParen(ddl, openParen);
        StringBuilder statement = new StringBuilder("DELETE FROM ")
                .append(matcher.group("table").strip());
        if (closeParen > openParen) {
            String tableName = normalizeIdentifierSegment(unquoteIdentifier(lastIdentifierPart(matcher.group("table"))));
            for (String part : splitTopLevelComma(ddl.substring(openParen + 1, closeParen))) {
                String columnName = createTableColumnDefinitionName(part.strip());
                if (!columnName.isBlank()) {
                    statement.append(" /* DM_ADAPTER_TMP_COLUMN ")
                            .append(tableName)
                            .append(" ")
                            .append(normalizeIdentifierSegment(columnName))
                            .append(" */");
                }
            }
        }
        return ProcedureStatement.directSql(statement.toString());
    }

    private ProcedureStatement convertTemporaryAlterTableAddColumnToNoop(String ddl) {
        Matcher matcher = Pattern.compile(
                "(?is)^ALTER\\s+TABLE\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+ADD\\s+"
                        + "(?:COLUMN\\s+)?(?<definition>.+)$"
        ).matcher(ddl.strip());
        if (!matcher.matches() || !isProcedureTemporaryTableName(matcher.group("table"))) {
            return null;
        }
        String columnName = createTableColumnDefinitionName(matcher.group("definition").strip());
        if (columnName.isBlank()) {
            return null;
        }
        String tableName = normalizeIdentifierSegment(unquoteIdentifier(lastIdentifierPart(matcher.group("table"))));
        String normalizedColumn = normalizeIdentifierSegment(columnName);
        return ProcedureStatement.directSql("NULL /* DM_ADAPTER_TMP_COLUMN "
                + tableName + " " + normalizedColumn + " */");
    }

    private ProcedureStatement convertTemporaryCreateTableSelectToInsert(
            String ddl,
            Map<String, LinkedHashSet<String>> temporaryTableColumns
    ) {
        Matcher matcher = Pattern.compile(
                "(?is)^(\\s*CREATE\\s+TABLE\\s+)(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + SQL_IDENTIFIER_TOKEN
                        + ")\\s+(?:AS\\s+)?SELECT\\b(.*)$"
        ).matcher(ddl);
        if (!matcher.matches() || !isProcedureTemporaryTableName(matcher.group(2))) {
            return null;
        }
        String selectTail = matcher.group(3);
        TemporaryCreateTableSelectRewrite rewrite = temporaryCreateTableSelectRewrite(
                matcher.group(2),
                selectTail,
                temporaryTableColumns
        );
        return ProcedureStatement.directSql("INSERT INTO "
                + matcher.group(2).strip()
                + rewrite.columnList()
                + " SELECT"
                + rewrite.selectTail());
    }

    private TemporaryCreateTableSelectRewrite temporaryCreateTableSelectRewrite(
            String targetTable,
            String selectTail,
            Map<String, LinkedHashSet<String>> temporaryTableColumns
    ) {
        int fromIndex = topLevelKeywordIndex(selectTail, "FROM");
        String selectList = fromIndex < 0 ? selectTail : selectTail.substring(0, fromIndex);
        List<String> columns = selectListColumns(selectList);
        if (columns.isEmpty() && selectList.strip().equals("*")) {
            columns = temporarySelectStarColumns(targetTable, selectTail, temporaryTableColumns);
            if (!columns.isEmpty()) {
                String sourceTable = singleSelectStarSourceTable(selectTail);
                if (!sourceTable.isBlank()) {
                    String rewrittenSelectTail = " " + String.join(", ", columns) + " FROM " + sourceTable;
                    return new TemporaryCreateTableSelectRewrite(
                            " (" + String.join(", ", columns) + ")",
                            rewrittenSelectTail
                    );
                }
            }
        }
        if (columns.isEmpty()) {
            return new TemporaryCreateTableSelectRewrite("", selectTail);
        }
        return new TemporaryCreateTableSelectRewrite(" (" + String.join(", ", columns) + ")", selectTail);
    }

    private List<String> temporarySelectStarColumns(
            String targetTable,
            String selectTail,
            Map<String, LinkedHashSet<String>> temporaryTableColumns
    ) {
        String sourceTable = singleSelectStarSourceTable(selectTail);
        if (sourceTable.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> targetColumns = temporaryTableColumns(temporaryTableColumns, targetTable);
        LinkedHashSet<String> sourceColumns = temporaryTableColumns(temporaryTableColumns, sourceTable);
        if (targetColumns == null || sourceColumns == null) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        for (String column : targetColumns) {
            if (sourceColumns.contains(column)) {
                columns.add(column);
            }
        }
        return columns;
    }

    private LinkedHashSet<String> temporaryTableColumnsFor(
            LinkedHashMap<String, LinkedHashSet<String>> temporaryTableColumns,
            String table
    ) {
        LinkedHashSet<String> existing = temporaryTableColumns(temporaryTableColumns, table);
        if (existing != null) {
            return existing;
        }
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        temporaryTableColumns.put(table.strip(), columns);
        return columns;
    }

    private LinkedHashSet<String> temporaryTableColumns(
            Map<String, LinkedHashSet<String>> temporaryTableColumns,
            String table
    ) {
        String normalized = normalizedTableKey(table);
        for (Map.Entry<String, LinkedHashSet<String>> entry : temporaryTableColumns.entrySet()) {
            if (normalizedTableKey(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean addColumnsIfAbsentIgnoreCase(LinkedHashSet<String> columns, Iterable<String> candidates) {
        boolean changed = false;
        for (String candidate : candidates) {
            changed |= addColumnIfAbsentIgnoreCase(columns, candidate);
        }
        return changed;
    }

    private boolean addColumnIfAbsentIgnoreCase(LinkedHashSet<String> columns, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        for (String existing : columns) {
            if (existing.equalsIgnoreCase(candidate)) {
                return false;
            }
        }
        return columns.add(candidate);
    }

    private String singleSelectStarSourceTable(String selectTail) {
        Matcher matcher = Pattern.compile(
                "(?is)^\\s*\\*\\s+FROM\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s*$"
        ).matcher(selectTail);
        if (!matcher.matches()) {
            return "";
        }
        return matcher.group("table");
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

    private List<String> splitMysqlAlterTableDropAndAddIndexes(String ddl) {
        Matcher matcher = Pattern.compile("(?is)^ALTER\\s+TABLE\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+(?<body>.+)$")
                .matcher(ddl.strip());
        if (!matcher.matches()) {
            return List.of();
        }
        String table = matcher.group("table");
        List<String> parts = splitTopLevelComma(matcher.group("body"));
        if (parts.size() <= 1) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        for (String part : parts) {
            String converted = convertMysqlAlterTableIndexPart(table, part.strip());
            if (converted.isBlank()) {
                return List.of();
            }
            statements.add(converted);
        }
        return statements;
    }

    private String convertMysqlAlterTableIndexPart(String table, String part) {
        Matcher dropIndex = Pattern.compile("(?is)^DROP\\s+INDEX\\s+(?<index>" + SQL_IDENTIFIER_TOKEN + ")\\s*$")
                .matcher(part);
        if (dropIndex.matches()) {
            return "DROP INDEX " + dmSchemaScopedIndexName(table, dropIndex.group("index"));
        }
        Matcher addIndex = Pattern.compile(
                "(?is)^ADD\\s+(?<unique>UNIQUE\\s+)?(?:INDEX|KEY)\\s+"
                        + "(?:(?<index>" + SQL_IDENTIFIER_TOKEN + ")\\s*)?\\((?<columns>.*)\\)"
                        + "\\s*(?:USING\\s+BTREE\\s*)?$"
        ).matcher(part);
        if (!addIndex.matches()) {
            return "";
        }
        String index = addIndex.group("index");
        if (index == null || index.isBlank()) {
            List<String> columns = indexColumnNames(addIndex.group("columns"));
            if (columns.isEmpty()) {
                return "";
            }
            index = columns.get(0);
        }
        return (addIndex.group("unique") == null ? "CREATE INDEX " : "CREATE UNIQUE INDEX ")
                + dmSchemaScopedIndexName(table, index)
                + " ON "
                + table
                + " ("
                + stripMysqlIndexPrefixLengths(addIndex.group("columns").strip())
                + ")";
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
                "(?is)^ALTER\\s+TABLE\\s+(?<table>" + SQL_IDENTIFIER_TOKEN + ")\\s+ADD\\s+"
                        + "(?<unique>UNIQUE\\s+)?(?:INDEX|KEY)\\s+"
                        + "(?:(?<index>" + SQL_IDENTIFIER_TOKEN + ")\\s*)?\\((?<columns>.*)\\)"
                        + "\\s*(?:USING\\s+BTREE\\s*)?$"
        ).matcher(ddl);
        if (!matcher.matches()) {
            return ddl;
        }
        String index = matcher.group("index");
        if (index == null || index.isBlank()) {
            List<String> columns = indexColumnNames(matcher.group("columns"));
            if (columns.isEmpty()) {
                return ddl;
            }
            index = columns.get(0);
        }
        return (matcher.group("unique") == null ? "CREATE INDEX " : "CREATE UNIQUE INDEX ")
                + index
                + " ON "
                + matcher.group("table")
                + " ("
                + stripMysqlIndexPrefixLengths(matcher.group("columns").strip())
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
                                + "ADD\\s+(?:UNIQUE\\s+)?(?:INDEX|KEY)\\s+"
                                + "(?<index>`[^`]+`|\"[^\"]+\"|[^\\s(]+)\\s*\\("
                ),
                renames
        );
        collectAnonymousAlterTableIndexRenames(sql, renames);
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

    private void collectAnonymousAlterTableIndexRenames(String sql, List<IndexRename> renames) {
        Matcher matcher = Pattern.compile(
                "(?is)\\bALTER\\s+TABLE\\s+(?<table>`[^`]+`|\"[^\"]+\"|\\S+)\\s+"
                        + "ADD\\s+(?:UNIQUE\\s+)?(?:INDEX|KEY)\\s*\\("
        ).matcher(sql);
        while (matcher.find()) {
            int openParen = matcher.end() - 1;
            int closeParen = findMatchingParen(sql, openParen);
            if (closeParen <= openParen) {
                continue;
            }
            List<String> columns = indexColumnNames(sql.substring(openParen + 1, closeParen));
            if (columns.isEmpty()) {
                continue;
            }
            String tableName = unquoteIdentifier(lastIdentifierPart(matcher.group("table")));
            String oldIndexName = columns.get(0);
            String newIndexName = dmSchemaScopedIndexName(matcher.group("table"), oldIndexName);
            renames.add(new IndexRename(tableName, oldIndexName, newIndexName, columns));
        }
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
        converted = Pattern.compile(
                        "(?is)(UPPER\\s*\\(\\s*TABLE_NAME\\s*\\)\\s*=\\s*UPPER\\s*\\(\\s*'"
                                + tableName + "'\\s*\\)(?:(?!\\bTHEN\\b).)*?"
                                + "UPPER\\s*\\(\\s*INDEX_NAME\\s*\\)\\s*=\\s*UPPER\\s*\\(\\s*)'"
                                + oldIndexName + "'"
                )
                .matcher(converted)
                .replaceAll("$1'" + Matcher.quoteReplacement(newIndexName) + "'");
        converted = Pattern.compile(
                        "(?is)(UPPER\\s*\\(\\s*INDEX_NAME\\s*\\)\\s*=\\s*UPPER\\s*\\(\\s*)'"
                                + oldIndexName + "'((?:(?!\\bTHEN\\b).)*?"
                                + "UPPER\\s*\\(\\s*TABLE_NAME\\s*\\)\\s*=\\s*UPPER\\s*\\(\\s*'"
                                + tableName + "'\\s*\\))"
                )
                .matcher(converted)
                .replaceAll("$1'" + Matcher.quoteReplacement(newIndexName) + "'$2");
        return replaceIndexNameCheckWithColumnCheck(converted, rename);
    }

    private String replaceIndexNameCheckWithColumnCheck(String sql, IndexRename rename) {
        if (rename.columnNames().isEmpty()) {
            return sql;
        }
        Pattern pattern = Pattern.compile("(?is)\\bIF\\s+NOT\\s+EXISTS\\s*\\(");
        Matcher matcher = pattern.matcher(sql);
        StringBuilder converted = new StringBuilder(sql.length());
        int cursor = 0;
        boolean changed = false;
        while (matcher.find()) {
            int openParen = matcher.end() - 1;
            int closeParen = findMatchingParen(sql, openParen);
            if (closeParen <= openParen) {
                continue;
            }
            int thenIndex = skipWhitespace(sql, closeParen + 1);
            if (!startsKeyword(sql, thenIndex, "THEN")) {
                continue;
            }
            int statementEnd = thenIndex + "THEN".length();
            String check = sql.substring(matcher.start(), statementEnd);
            if (matchesIndexCheck(check, rename)) {
                converted.append(sql, cursor, matcher.start());
                converted.append(indexColumnExistenceCheck(rename));
                cursor = statementEnd;
                changed = true;
            }
        }
        converted.append(sql, cursor, sql.length());
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
        StringBuilder columnPositionChecks = new StringBuilder();
        List<String> columnNames = rename.columnNames();
        for (int i = 0; i < columnNames.size(); i++) {
            columnPositionChecks.append("\n")
                    .append("               AND MAX(CASE WHEN COLUMN_POSITION = ")
                    .append(i + 1)
                    .append(" AND COLUMN_NAME = '")
                    .append(columnNames.get(i).replace("'", "''"))
                    .append("' THEN 1 ELSE 0 END) = 1");
        }
        return "IF NOT EXISTS (\n"
                + "        SELECT 1\n"
                + "        FROM (\n"
                + "            SELECT INDEX_NAME\n"
                + "            FROM ALL_IND_COLUMNS\n"
                + "            WHERE INDEX_OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')\n"
                + "              AND TABLE_NAME = '" + rename.tableName().replace("'", "''") + "'\n"
                + "            GROUP BY INDEX_NAME\n"
                + "            HAVING COUNT(*) = " + columnNames.size() + columnPositionChecks + "\n"
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
            return startsKeyword(sql, cursor, "TABLE")
                    || startsKeyword(sql, cursor, "INDEX")
                    || startsKeyword(sql, cursor, "VIEW");
        }
        if (startsKeyword(sql, index, "TRUNCATE")) {
            return true;
        }
        if (!startsKeyword(sql, index, "CREATE")) {
            return false;
        }
        int cursor = skipWhitespace(sql, index + "CREATE".length());
        if (startsKeyword(sql, cursor, "OR")) {
            cursor = skipWhitespace(sql, cursor + "OR".length());
            if (!startsKeyword(sql, cursor, "REPLACE")) {
                return false;
            }
            cursor = skipWhitespace(sql, cursor + "REPLACE".length());
        }
        if (startsKeyword(sql, cursor, "UNIQUE")) {
            cursor = skipWhitespace(sql, cursor + "UNIQUE".length());
        } else if (startsKeyword(sql, cursor, "TEMPORARY")) {
            cursor = skipWhitespace(sql, cursor + "TEMPORARY".length());
        }
        return startsKeyword(sql, cursor, "INDEX")
                || startsKeyword(sql, cursor, "TABLE")
                || startsKeyword(sql, cursor, "VIEW");
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

    private String replaceOutsideIgnoredText(String sql, Pattern pattern, Function<Matcher, String> replacement) {
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
                Matcher matcher = pattern.matcher(sql);
                matcher.region(index, sql.length());
                if (matcher.lookingAt()) {
                    converted.append(replacement.apply(matcher));
                    index = matcher.end();
                    changed = true;
                } else {
                    converted.append(current);
                    index++;
                }
            }
        }
        return changed ? converted.toString() : sql;
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
        while (cursor < value.length() && isSqlWhitespace(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int skipWhitespaceAndComments(String value, int index) {
        int cursor = Math.max(0, index);
        boolean advanced;
        do {
            advanced = false;
            int whitespaceEnd = skipWhitespace(value, cursor);
            if (whitespaceEnd != cursor) {
                cursor = whitespaceEnd;
                advanced = true;
            }
            if (startsLineComment(value, cursor)) {
                cursor = skipUntilLineEnd(value, cursor);
                advanced = true;
            } else if (startsBlockComment(value, cursor)) {
                cursor = skipUntilBlockCommentEnd(value, cursor);
                advanced = true;
            }
        } while (advanced);
        return cursor;
    }

    private boolean isSqlWhitespace(char value) {
        return Character.isWhitespace(value) || value == '\uFEFF';
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
        return (index + 1 < value.length() && value.charAt(index) == '-' && value.charAt(index + 1) == '-')
                || (index < value.length() && value.charAt(index) == '#');
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
        if (isConvertedSimpleDateEndTrigger(sql)) {
            return "";
        }
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

    private String originalSqlSyntaxManualReviewReason(String sql) {
        if (hasDanglingInsertValuesCommaBeforeBlockEnd(sql)) {
            return ORIGINAL_SQL_DANGLING_INSERT_VALUES_REASON;
        }
        return "";
    }

    private boolean hasDanglingInsertValuesCommaBeforeBlockEnd(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        return Pattern.compile(
                        "(?is)\\bINSERT\\s+INTO\\b[^;]*\\bVALUES\\b[^;]*\\)\\s*,\\s*(?:END\\s+IF|END)\\b"
                )
                .matcher(sql)
                .find();
    }

    private String mysqlPrefixIndexManualReviewReason(String originalSql, String convertedSql) {
        if (originalSql == null || convertedSql == null) {
            return "";
        }
        Matcher matcher = MYSQL_PREFIX_INDEX_DDL_PATTERN.matcher(originalSql);
        boolean longPrefixIndex = false;
        while (matcher.find()) {
            try {
                if (Integer.parseInt(matcher.group("length")) >= MYSQL_LONG_PREFIX_INDEX_LENGTH_THRESHOLD) {
                    longPrefixIndex = true;
                    break;
                }
            } catch (NumberFormatException ignored) {
                return MYSQL_PREFIX_INDEX_MANUAL_REVIEW_REASON;
            }
        }
        if (!longPrefixIndex) {
            return "";
        }
        if (MYSQL_PREFIX_INDEX_VARCHAR_GUARD_PATTERN.matcher(originalSql).find()
                && !MYSQL_PREFIX_INDEX_DDL_PATTERN.matcher(convertedSql).find()) {
            return "";
        }
        if (!INDEX_DDL_AFTER_CONVERSION_PATTERN.matcher(convertedSql).find()) {
            return "";
        }
        return MYSQL_PREFIX_INDEX_MANUAL_REVIEW_REASON;
    }

    private String suspiciousLengthModifyReason(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        if (MALFORMED_LENGTH_COMPARISON_PATTERN.matcher(sql).find()) {
            return "可疑字段长度判断：检测到类似 CHARACTER_MAXIMUM_LENGTH>1=200 的链式比较。"
                    + "这在 MySQL 中也容易产生非预期布尔比较，转换到达梦后可能直接语法错误。"
                    + "建议先修原始 SQL，明确写成 DATA_TYPE/COLUMN_TYPE 为 char/varchar 且长度小于目标值的条件；"
                    + "TEXT/CLOB 或已大于目标长度的字段不要自动 MODIFY。";
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
        int adapterCountStart = previousAdapterExistsCountSelectIndex(sql, contextStart);
        if (adapterCountStart >= 0) {
            contextStart = adapterCountStart;
        }
        if (contextStart < 0) {
            contextStart = 0;
        }
        return sql.substring(contextStart, modifyEnd);
    }

    private int previousAdapterExistsCountSelectIndex(String sql, int ifIndex) {
        if (ifIndex < 0) {
            return -1;
        }
        int windowStart = Math.max(0, ifIndex - 4000);
        String prefix = sql.substring(windowStart, ifIndex);
        Matcher matcher = Pattern.compile(
                "(?is)SELECT\\s+COUNT\\s*\\(\\s*\\*\\s*\\)\\s+INTO\\s+dm_adapter_exists(?:_\\d+)?\\s+FROM\\s*\\("
        ).matcher(prefix);
        int previous = -1;
        while (matcher.find()) {
            previous = windowStart + matcher.start();
        }
        return previous;
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
            Set<Integer> manualReviewStatementIndexes,
            List<String> appliedRules,
            List<String> statements
    ) {
        PlannedSqlScriptFile {
            manualReviewStatementIndexes = Set.copyOf(
                    manualReviewStatementIndexes == null ? Set.of() : manualReviewStatementIndexes
            );
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
            statements = List.copyOf(statements == null ? List.of() : statements);
        }
    }

    private record SafeRuleConversion(String sql, boolean changed, List<String> appliedRules) {
        private SafeRuleConversion {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }
    }

    private record InsertValuesColumnListRewrite(String sql, List<String> appliedRules) {
        private InsertValuesColumnListRewrite {
            appliedRules = List.copyOf(appliedRules == null ? List.of() : appliedRules);
        }

        static InsertValuesColumnListRewrite unchanged(String sql) {
            return new InsertValuesColumnListRewrite(sql, List.of());
        }
    }

    private record InsertValuesTuple(int openParen, int closeParen, List<String> values) {
        private InsertValuesTuple {
            values = List.copyOf(values == null ? List.of() : values);
        }
    }

    private record ClobCallArgument(String variableName, String literal) {
    }

    private record ProcedureParameterParts(String name, String type) {
    }

    private record ProcedureParameter(String name, String mode, String type) {
    }

    private record ProcedureAssignedParameter(ProcedureParameter parameter, String localName) {
    }

    private record RoutineKeyword(int index, String keyword) {
    }

    private record TextReplacement(Pattern pattern, String replacement) {
    }

    private record LeadingSqlPrefix(String prefix, String body) {
    }

    private record ScriptUserVariableAssignment(String name, String literal) {
    }

    private record ScriptUserVariableInline(String sql, boolean changed) {
    }

    private record StatementConversionPlan(
            int statementIndex,
            String originalStatement,
            CompletableFuture<ScriptStatementConversion> conversion
    ) {
    }

    private static final class ConversionTimings {
        private long preparationNanos;
        private long safeRulesNanos;
        private long genericConverterNanos;
        private long postProcessNanos;
        private long originalSyntaxReviewNanos;
        private long prefixIndexReviewNanos;
        private long generalManualReviewNanos;
    }

    private static final class ScriptDynamicDdlState {
        private final LinkedHashSet<String> currentSchemaVariables = new LinkedHashSet<>();
        private final LinkedHashSet<String> dynamicDdlVariables = new LinkedHashSet<>();
        private final LinkedHashSet<String> preparedDynamicDdlStatements = new LinkedHashSet<>();
    }

    private record ProcedureExistsCondition(int start, int end, List<String> variableNames, String replacement) {
    }

    private record ProcedureExistsTerm(String existsSelect, boolean negated, String variableName) {
    }

    private record CursorLoopConversion(String sql, boolean changed, Set<String> convertedFlags) {
        private CursorLoopConversion {
            convertedFlags = Set.copyOf(convertedFlags == null ? Set.of() : convertedFlags);
        }
    }

    private record CursorLeaveIfBlock(int endIfEnd, String loopBody) {
    }

    private record NestedBlockDeclarations(List<String> declarations, int declarationsEnd, String leadingTrivia) {
        private NestedBlockDeclarations {
            declarations = List.copyOf(declarations == null ? List.of() : declarations);
            leadingTrivia = leadingTrivia == null ? "" : leadingTrivia;
        }
    }

    private record ProcedureReferenceRename(String sql, boolean changed) {
    }

    private record TemporaryTableKey(String tableKey, List<String> keyColumns, int end) {
        private TemporaryTableKey {
            keyColumns = List.copyOf(keyColumns == null ? List.of() : keyColumns);
        }
    }

    private record TemporaryInsertIgnoreSelect(int start, int end, String mergeSql) {
    }

    private record TemporaryCreateTableSelectRewrite(String columnList, String selectTail) {
    }

    private record ProcedureSetAssignment(int targetStart, int targetEnd, int expressionStart, int statementEnd) {
    }

    private record LabelRemoval(String sql, boolean changed, Set<String> labels) {
        private LabelRemoval {
            labels = Set.copyOf(labels == null ? Set.of() : labels);
        }
    }

    private record SqlIdentifierReference(String token, int end) {
    }

    private record SingleQuotedStringRewrite(String value, int endIndex, boolean changed) {
    }

    private record SingleQuotedStringContent(String rawContent, int endIndex, boolean closed) {
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
