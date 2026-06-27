package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DmAdapterCliTest {
    @TempDir
    Path tempDir;

    @Test
    void migrateDryRunWritesReportWithoutChangingProjectFiles() throws Exception {
        writeDemoProject();
        String pomBefore = Files.readString(tempDir.resolve("pom.xml"));

        int exitCode = new CommandLine(new DmAdapterCli()).execute("migrate", "--project", tempDir.toString(), "--dry-run");

        assertThat(exitCode).isZero();
        assertThat(Files.readString(tempDir.resolve("pom.xml"))).isEqualTo(pomBefore);
        assertThat(Files.exists(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"))).isFalse();
        assertThat(Files.exists(tempDir.resolve(".dm-adapter/dm-adapter-report.json"))).isTrue();
        assertThat(Files.readString(tempDir.resolve(".dm-adapter/dm-adapter-report.md")))
                .contains("Automatic SQL Conversions")
                .contains("Manual Review SQL Items");

        int reportExitCode = new CommandLine(new DmAdapterCli()).execute("report", "--project", tempDir.toString());

        assertThat(reportExitCode).isZero();
    }

    @Test
    void scanWritesScanReport() throws Exception {
        writeDemoProject();

        int exitCode = new CommandLine(new DmAdapterCli()).execute("scan", "--project", tempDir.toString());

        assertThat(exitCode).isZero();
        assertThat(Files.exists(tempDir.resolve(".dm-adapter/dm-adapter-scan-report.json"))).isTrue();
    }

    @Test
    void migrateAddsDmDriverToRootPomWithoutGeneratingApplicationDmConfig() throws Exception {
        writeDemoProject();

        int exitCode = new CommandLine(new DmAdapterCli()).execute("migrate", "--project", tempDir.toString());

        assertThat(exitCode).isZero();
        assertThat(Files.readString(tempDir.resolve("pom.xml")))
                .contains("<artifactId>DmJdbcDriver18</artifactId>");
        assertThat(Files.exists(tempDir.resolve("src/main/resources/application-dm.yml"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"))).isTrue();
    }

    @Test
    void migratePrintsMapperStructureWarnings() throws Exception {
        writeDemoProject();
        Files.writeString(tempDir.resolve("src/main/resources/mapper/UserMapper.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select resultType="string">
                        select NOW() from dual
                    </select>
                </mapper>
                """);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode;
        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            exitCode = new CommandLine(new DmAdapterCli()).execute("migrate", "--project", tempDir.toString());
        } finally {
            System.setOut(originalOut);
        }

        assertThat(exitCode).isZero();
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .contains("Warnings:")
                .contains("Mapper XML statement <select> is missing required id attribute")
                .contains("text-preserving rewrite");
        assertThat(Files.readString(tempDir.resolve(".dm-adapter/dm-adapter-report.md")))
                .contains("(missing id: <select>)")
                .contains("missing required id attribute");
    }

    @Test
    void migrateAddsDmDriverToSpringBootModulePomInsteadOfProjectRoot() throws Exception {
        writeMultiModuleProjectWithIndependentRootPom();
        String rootPomBefore = Files.readString(tempDir.resolve("pom.xml"));

        int exitCode = new CommandLine(new DmAdapterCli()).execute("migrate", "--project", tempDir.toString());

        assertThat(exitCode).isZero();
        assertThat(Files.readString(tempDir.resolve("pom.xml"))).isEqualTo(rootPomBefore);
        assertThat(Files.readString(tempDir.resolve("sample-system-rest/pom.xml")))
                .contains("<artifactId>DmJdbcDriver18</artifactId>");
        assertThat(Files.readString(tempDir.resolve("sample-system-base/pom.xml")))
                .doesNotContain("DmJdbcDriver");
        assertThat(Files.exists(tempDir.resolve("sample-system-base/src/main/resources/mapper-dm/UserMapper.xml"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("src/main/resources/application-dm.yml"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("sample-system-rest/src/main/resources/application-dm.yml"))).isFalse();
    }

    @Test
    void migrateGeneratesValidationTestWhenValidationOptionsArePresent() throws Exception {
        writeMultiModuleProjectWithIndependentRootPom();

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "migrate",
                "--project",
                tempDir.toString(),
                "--app-module",
                "sample-system-rest",
                "--schema",
                "newsee-system"
        );

        Path config = tempDir.resolve(".dm-adapter/sql-validation.yml");
        assertThat(exitCode).isZero();
        assertThat(Files.readString(tempDir.resolve("sample-system-rest/pom.xml")))
                .contains("<artifactId>DmJdbcDriver18</artifactId>");
        assertThat(Files.exists(tempDir.resolve("sample-system-base/src/main/resources/mapper-dm/UserMapper.xml"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("sample-system-rest/src/test/java/com/example/DmSqlValidationTest.java"))).isTrue();
        assertThat(Files.readString(config))
                .contains("schema: \"newsee-system\"")
                .contains("sample-system-base/src/main/resources/mapper-dm/**/*.xml")
                .doesNotContain("sample-system-base/src/main/resources/mapper/**/*.xml");
    }

    @Test
    void migrateDryRunRejectsValidationTestGeneration() throws Exception {
        writeDemoProject();

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "migrate",
                "--project",
                tempDir.toString(),
                "--dry-run",
                "--generate-validation-test"
        );

        assertThat(exitCode).isEqualTo(1);
        assertThat(Files.exists(tempDir.resolve(".dm-adapter/sql-validation.yml"))).isFalse();
        assertThat(Files.exists(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"))).isFalse();
    }

    @Test
    void migrateRewritesAesPasswordSqlAndRedactsReports() throws Exception {
        writeDemoProject();
        Files.writeString(tempDir.resolve("src/main/resources/mapper/UserMapper.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="selectPassword">
                        select AES_DECRYPT(FROM_BASE64(user_password), 'REAL_SECRET') from user
                    </select>
                    <update id="updatePassword">
                        update user
                        set user_password = TO_BASE64(AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR}, 'REAL_SECRET'))
                        where user_id = #{userId}
                    </update>
                </mapper>
                """);

        int exitCode = new CommandLine(new DmAdapterCli()).execute("migrate", "--project", tempDir.toString());

        assertThat(exitCode).isZero();
        String migratedMapper = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(migratedMapper)
                .contains("SF_DECRYPT_TO_CHAR(FROM_BASE64(user_password), 513, 'REAL_SECRET', NULL)")
                .contains("TO_BASE64(SF_ENCRYPT_CHAR(#{userPassword, jdbcType=VARCHAR}, 513, 'REAL_SECRET', NULL))");
        String markdown = Files.readString(tempDir.resolve(".dm-adapter/dm-adapter-report.md"));
        String json = Files.readString(tempDir.resolve(".dm-adapter/dm-adapter-report.json"));
        assertThat(markdown)
                .contains("AES128_ECB")
                .contains("RESET_REQUIRED")
                .contains("'******'")
                .doesNotContain("REAL_SECRET");
        assertThat(json)
                .contains("'******'")
                .doesNotContain("REAL_SECRET");
    }

    @Test
    void migrateWritesRewriteConfigTemplateForUnconfiguredUpsertAndInsertIgnore() throws Exception {
        writeDemoProject();
        Files.writeString(tempDir.resolve("src/main/resources/mapper/UserMapper.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="updateExtend">
                        INSERT INTO user_extend (user_id, key_name)
                        VALUES (#{userId}, #{keyName})
                        ON DUPLICATE KEY UPDATE key_name = VALUES(key_name)
                    </insert>
                    <insert id="insertRolePerm">
                        insert ignore into role_perm (role_id, perm_id)
                        values (#{roleId}, #{permId})
                    </insert>
                </mapper>
                """);

        int exitCode = new CommandLine(new DmAdapterCli()).execute("migrate", "--project", tempDir.toString());

        Path rewriteConfig = tempDir.resolve(".dm-adapter/sql-rewrite.yml");
        assertThat(exitCode).isZero();
        assertThat(Files.exists(rewriteConfig)).isTrue();
        assertThat(Files.readString(rewriteConfig))
                .contains("upsertKeys:")
                .contains("\"user_extend\":")
                .contains("\"role_perm\":")
                .contains("\"com.example.UserMapper.updateExtend\":")
                .contains("\"com.example.UserMapper.insertRolePerm\":")
                .contains("keyColumns: []");
        assertThat(Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml")))
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("insert ignore into role_perm")
                .doesNotContain("MERGE INTO");
        assertThat(Files.readString(tempDir.resolve(".dm-adapter/dm-adapter-report.md")))
                .contains("requires configured keyColumns")
                .contains("INSERT IGNORE requires configured keyColumns");
    }

    @Test
    void migrateUsesExplicitRewriteConfigForUpsertMerge() throws Exception {
        writeDemoProject();
        Files.writeString(tempDir.resolve("src/main/resources/mapper/UserMapper.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="updateExtend">
                        INSERT INTO user_extend (user_id, key_name)
                        VALUES (#{userId}, #{keyName})
                        ON DUPLICATE KEY UPDATE key_name = VALUES(key_name)
                    </insert>
                </mapper>
                """);
        Path rewriteConfig = tempDir.resolve("rewrite.yml");
        Files.writeString(rewriteConfig, """
                upsertKeys:
                  tables:
                    user_extend:
                      keyColumns: [user_id]
                  methods:
                """);

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "migrate",
                "--project",
                tempDir.toString(),
                "--rewrite-config",
                rewriteConfig.toString()
        );

        assertThat(exitCode).isZero();
        assertThat(Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml")))
                .contains("MERGE INTO user_extend t")
                .contains("ON (t.user_id = s.user_id)")
                .contains("WHEN MATCHED THEN UPDATE SET t.key_name = s.key_name")
                .doesNotContain("ON DUPLICATE KEY UPDATE");
        assertThat(Files.exists(tempDir.resolve(".dm-adapter/sql-rewrite.yml"))).isFalse();
    }

    @Test
    void generateValidationTestWritesConfigAndMyBatisJdbcTest() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString(),
                "--schema",
                "sample-system"
        );

        Path config = tempDir.resolve(".dm-adapter/sql-validation.yml");
        Path test = tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java");
        String generatedTestSource = Files.readString(test);
        assertThat(exitCode).isZero();
        assertThat(Files.readString(config))
                .contains("schema: \"sample-system\"")
                .contains("datasource:")
                .contains("url: ${DM_JDBC_URL}")
                .contains("mapperXmlLocations:")
                .contains("src/main/resources/mapper/**/*.xml")
                .contains("usageFilterEnabled: true")
                .contains("usageClassDirectories:")
                .contains("methods:")
                .contains("includedMethods:")
                .contains("excludedMethods:")
                .contains("com.example.UserMapper.selectUsers")
                .contains("com.example.UserMapper.selectByDate")
                .contains("com.example.UserMapper.updateByLevel")
                .contains("com.example.UserMapper.dayClosingByIds")
                .contains("com.example.UserMapper.getBatchOpenBillChargeItem")
                .contains("com.example.UserMapper.selectApprovalStateByPaymentIds")
                .contains("com.example.UserMapper.deleteByLogical")
                .contains("com.example.UserMapper.listPageWithScopedCollections")
                .contains("com.example.UserMapper.dynamicUpdateBatchCreateTemp");
        assertThat(generatedTestSource)
                .contains("package com.example;")
                .contains("public class DmSqlValidationTest")
                .contains("public static void main(String[] args) throws Exception")
                .contains("@Tag(\"dm-sql-validation\")")
                .contains("@EnabledIfEnvironmentVariable")
                .contains("SqlSessionFactory")
                .contains("UnpooledDataSource")
                .contains("[dm-sql-validation]")
                .contains("LOG_TIMESTAMP_FORMATTER")
                .contains("logProgress(index, total, record")
                .contains("达梦 SQL 验证报告")
                .contains("失败分类汇总")
                .contains("失败模式汇总")
                .contains("库表对象缺失热点")
                .contains("缺失表/视图")
                .contains("缺失字段")
                .contains("schemaIssueCounts")
                .contains("schemaObjectHotspots")
                .contains("missingTablesOrViews")
                .contains("建议后续处理")
                .contains("重新执行 dm-adapter migrate，然后再次运行本验证测试")
                .doesNotContain("Suggested Next Actions")
                .doesNotContain("Failure Categories")
                .doesNotContain("Failure Patterns")
                .doesNotContain("Schema Object Hotspots")
                .contains("使用情况过滤器")
                .contains("usageFilter")
                .contains("MapperUsageIndex")
                .contains("scanClassFile")
                .contains("MemberRef")
                .contains("No project class references mapper method")
                .contains("parameterSource")
                .contains("parameterSummary")
                .contains("parametersSummary")
                .contains("valueSummary")
                .contains("isSensitiveName")
                .contains("REWRITE_CONFIG_PATH")
                .contains("validationArgs")
                .contains("validationIgnores")
                .contains("validationIgnores.missingTables")
                .contains("validationIgnores.missingColumns")
                .contains("writeValidationArgsSuggestions")
                .contains("writeMissingTableIgnoreSuggestions")
                .contains("skipIgnoredMissingTable")
                .contains("skipIgnoredMissingColumn")
                .contains("ignored-missing-table")
                .contains("ignored-missing-column")
                .contains("isEmptyDynamicSqlFailure")
                .contains("动态 SQL 未生成")
                .contains("existingMissingTableIgnoreEntries")
                .contains("existingMissingColumnIgnoreEntries")
                .contains("#    - ")
                .contains("MethodArgumentConfig")
                .contains("configuredNamedParameters")
                .contains("configuredParameterMap")
                .contains("mergeConfiguredParameterMap")
                .contains("configuredParameterMap(mapperMethod.statement, configuredArgs.params)")
                .contains("configuredArgs.args.get(i)")
                .contains("effectiveParameterName")
                .contains("configuredCollectionElementDefault")
                .contains("mergeConfiguredCollectionElementMap")
                .contains("coerceConfiguredValueToDefaultType")
                .contains("isGeneratedPlaceholderValue")
                .contains("java.sql.Timestamp.valueOf(\"2024-01-01 00:00:00\")")
                .contains("convertConfiguredValue")
                .contains("suggestedArgumentConfig")
                .contains("Updated validation args in")
                .contains("参数")
                .contains("\\\"parameterSummary\\\"")
                .contains("\"unused\"")
                .contains("usageFilterEnabled")
                .contains("includedMethods")
                .contains("TEST_DATA_OR_SCHEMA")
                .contains("METHOD_ARGS_OR_BINDING")
                .contains("无法解析的成员访问表达式")
                .contains("summary")
                .contains("failurePattern")
                .contains("failurePatterns")
                .contains("INSERT_FOREACH_MISSING_VALUES")
                .contains("containsAnyPattern")
                .contains("resolveParameterVariants")
                .contains("setBranchParameterVariants")
                .contains("SetBranchParameterVariant")
                .contains("BranchCollector")
                .contains("dynamicIdentifierMetadata")
                .contains("DEFAULT_COLLECTION_PARAMETER_NAMES")
                .contains("\"primarykeylist\"")
                .contains("\"removeitemids\"")
                .contains("\"orders\"")
                .contains("\"ordernos\"")
                .contains("\"monthlist\"")
                .contains("metadata.addCollectionParameterName(collection, nonEmptyCollection)")
                .contains("metadata.addNonEmptyCollectionParameterName(collection)")
                .contains("shouldUseNonEmptyForeachCollection")
                .contains("followsInOperator")
                .contains("collectionParameterNames")
                .contains("nonEmptyCollectionParameter")
                .contains("defaultCollectionParameter")
                .contains("defaultMapCollectionParameter")
                .contains("defaultMapParameterValue")
                .contains("valueAtPath")
                .contains("collectionParameter")
                .contains("mapCollectionParameter")
                .contains("addMapCollectionParameterName")
                .contains("defaultNestedForeachCollectionValue")
                .contains("NestedCollection")
                .contains("loadColumnMetadata")
                .contains("DbColumnMetadata")
                .contains("inOperatorColumnReference")
                .contains("collectionColumnType")
                .contains("collectionElementColumnType")
                .contains("addCollectionDefaultColumnReference")
                .contains("defaultCollectionElementForColumnType")
                .contains("defaultSqlFragmentForColumnType")
                .contains("defaultSqlLiteralString")
                .contains("putDefaultParameterValue")
                .contains("isNumericSqlFragmentName")
                .contains("addValueColumnReferences")
                .contains("addDmlColumnReferences")
                .contains("addInsertColumnReferences")
                .contains("addStructuredInsertColumnReferences")
                .contains("addStructuredInsertPlaceholderColumnReferences")
                .contains("structuredInsertColumns")
                .contains("structuredInsertPlaceholderExpressions")
                .contains("structuredInsertForeachValues")
                .contains("structuredInsertValueExpressions")
                .contains("placeholderExpressions")
                .contains("hasAncestor")
                .contains("InsertForeachValues")
                .contains("collectionElementColumnReferences")
                .contains("onlyCollectionElementColumnReferences")
                .contains("onlyCollectionElementColumnReferenceEntry")
                .contains("addUpdateColumnReferences")
                .contains("metadata.addDefaultColumnReference")
                .contains("metadata.addValueExpressionName")
                .contains("defaultColumnType")
                .contains("defaultTypedColumnValue")
                .contains("defaultValueForColumnType")
                .contains("isDateTimeColumnType")
                .contains("defaultStringDateTimeForColumnType")
                .contains("mapParameterValue")
                .contains("defaultValueForDirectParameter")
                .contains("isDeletionFlagName")
                .contains("isNumericParameterName")
                .contains("isIdLikeParameterName")
                .contains("isDateLikeParameterName")
                .contains("isNumericTextParameterName")
                .contains("isCompactEnumStringName")
                .contains("normalizedName.startsWith(\"has\")")
                .contains("normalizedName.startsWith(\"sync\")")
                .contains("normalizedName.endsWith(\"period\")")
                .contains("normalizedName.endsWith(\"frequency\")")
                .contains("normalizedName.endsWith(\"source\")")
                .contains("normalizedName.endsWith(\"entity\")")
                .contains("normalizedName.contains(\"collectionorsettlement\")")
                .contains("normalizedName.contains(\"rewardorpunish\")")
                .contains("isOptionalSearchParameterName")
                .contains("isDynamicMapParameterName")
                .contains("isOptionalDynamicTableName")
                .contains("hasDefaultParameterMap")
                .contains("generatedKeyProperties")
                .contains("generatedKeyProperty")
                .contains("DynamicIdentifierMetadata")
                .contains("collectionElementDefault")
                .contains("dynamicIdentifierParameter")
                .contains("defaultValueForJdbcType")
                .contains("configuredValueWithStatementDefaults")
                .contains("inferredConfiguredPlaceholderDefault")
                .contains("isGeneratedNullPlaceholderValue")
                .contains("existing == null && isGeneratedNullPlaceholderValue(configuredValue)")
                .contains("contextualDefaultValue")
                .contains("adaptContextualDefaultValue")
                .contains("dateTimeContextualValue")
                .contains("defaultValue instanceof String && !(configuredValue instanceof String)")
                .contains("\"null\".equalsIgnoreCase(text)")
                .contains("\"syncpwd\".equals(normalizedName)")
                .contains("isDateTimeDefaultValue")
                .contains("Date.class.isAssignableFrom(targetType)")
                .contains("defaultNameBasedTypedValue")
                .contains("jdbcType(valueMatcher.group(2))")
                .contains("metadata.addDefaultValue(condition.parameterName, condition.defaultValue)")
                .contains("addSetAssignmentDefaults")
                .contains("looksLikeSetAssignment")
                .contains("defaultValueForSetParameter")
                .contains("numericNotEquals")
                .contains("statement.defaultValues()")
                .contains("defaultDynamicIdentifier")
                .contains("defaultDynamicSqlFragmentValue")
                .contains("defaultDynamicSqlParameterValue")
                .contains("isSqlSegmentParameterName")
                .contains("shouldUseNullDefault")
                .contains("\"extField\"")
                .contains("statement.dynamicIdentifierNames()")
                .contains("parameterExpressionName")
                .contains("collectionSqlFragmentDefault")
                .contains("addCollectionSqlFragmentDefault")
                .contains("valueExpressionName")
                .contains("collectionExpressionName")
                .contains("canonicalCollectionName")
                .contains("isEntrySetCollection")
                .contains("scalarConfiguredCollectionItem")
                .contains("scalarCollectionParameter")
                .contains("normalized.contains(\"mapupdate\")")
                .contains("mapperMethod.statement.mapCollectionParameter(parameterName)")
                .contains("value instanceof Map<?, ?>")
                .contains("configuredDateValue")
                .contains("configuredInstant")
                .contains("topLevelColon")
                .contains("sameNamedNestedCollectionValue")
                .contains("statementRequiredCollectionValue(entryPath, statement)")
                .contains("\"mainsearch\".equals(normalized)")
                .contains("statusDisplay")
                .contains("categoryDisplay")
                .contains("failurePatternDisplay")
                .contains("quotedIdentifier(currentConfig.primarySchema())")
                .contains("validationSchemas")
                .contains("invokeMapperMethodWithSchema")
                .contains("isPrimitiveNullReturnFailure")
                .contains("SQL 已执行，但 mapper 基本类型返回值收到 null")
                .contains("isSchemaObjectFailureRecord")
                .contains("schemaAttemptSummary")
                .contains("schemaLabel")
                .contains("SchemaAttempt")
                .contains("config.schemas()")
                .contains("splitAsStream(schema)")
                .contains("All configured schemas failed")
                .contains("isRawSqlInjectionName")
                .contains("recordKey(mapperMethod.key())")
                .contains("parameterName")
                .contains("defaultString")
                .contains("private static void writeString(Path path, String content, Charset charset)")
                .contains("private static <T> Set<T> copySet(Collection<? extends T> values)")
                .contains("unescapeYamlDoubleQuoted")
                .contains("current == 34 || current == 92")
                .contains("trimmed.charAt(0) == 34")
                .contains("isDoubleQuotedSqlIdentifier")
                .contains("isSimpleQualifiedIdentifier")
                .contains("normalized.endsWith(\"schema\")")
                .contains("return quotedIdentifier(stripped)")
                .contains("Object rawConfigured = normalizedParams.get(field.getName())")
                .contains("Cannot assign null to primitive field")
                .contains("DYNAMIC_IDENTIFIER_PARAMETER")
                .contains("DYNAMIC_SQL_FRAGMENT_PARAMETER")
                .contains("skipMissingDynamicSqlFragment(record)")
                .contains("skipGeneratedDynamicSqlOrArgs(record)")
                .contains("Dynamic SQL fragment parameter is missing or still uses a generated placeholder")
                .contains("Dynamic SQL still contains generated placeholder identifiers/fragments")
                .contains("generated-dynamic-sql-or-args")
                .contains("if (hasDynamicSqlFragmentParameterIssue(message))")
                .contains("hasGeneratedDynamicSqlOrArgumentIssue")
                .contains("(?im)^### SQL:\\\\s*(?:ID|test)\\\\s*$")
                .contains("(?i)### SQL:\\\\s*(?:ID|test)(?:\\\\s*###|\\\\s*$)")
                .contains("(?:from|join|into|update|table)\\\\s+'(?:ID|test|\\\\d{4}-\\\\d{2}-\\\\d{2})'")
                .contains("Tuple3{f0=null")
                .contains("Tuple4{f0=null")
                .doesNotContain("if (isAutoParameter(record) && hasDynamicSqlFragmentParameterIssue(message))")
                .contains("RAW_SQL_PARAMETER")
                .contains("MYSQL_UPDATE_JOIN")
                .contains("MYSQL_UPDATE_JOIN_MULTI_TARGET")
                .contains("INSERT_IGNORE")
                .contains("MYSQL_GROUP_CONCAT")
                .contains("MYSQL_DATE_SUB_INTERVAL")
                .contains("MYSQL_DATE_ADD_INTERVAL")
                .contains("MYSQL_MAKEDATE")
                .contains("MYSQL_SUBDATE")
                .contains("\\\\+\\\\s*interval")
                .contains("MYSQL_PERIOD_DIFF_YEARMONTH")
                .contains("MYSQL_COUNT_CONDITION_OR_NULL")
                .contains("MYSQL_COUNT_DISTINCT_IF")
                .contains("MYSQL_BARE_INTERVAL")
                .contains("MYSQL_NOT_ISNULL")
                .contains("MYSQL_BOOLEAN_OPERATOR")
                .contains("MYSQL_CONVERT_UNSIGNED")
                .contains("MYSQL_CONVERT_DECIMAL")
                .contains("MYSQL_CONVERT_GBK_ORDER")
                .contains("MYSQL_UPDATE_ORDER_LIMIT")
                .contains("MYSQL_SELECT_MODIFIER")
                .contains("MYSQL_INSERT_VALUE_KEYWORD")
                .contains("DAMENG_RESERVED_IDENTIFIER")
                .contains("where\\\\s+where")
                .contains("from\\\\s+where")
                .contains("ID\\\\s+select")
                .contains("MYSQL_INDEX_HINT")
                .contains("MYSQL_USER_VARIABLE")
                .contains("isMysqlMetadataSql")
                .contains("(?i)### SQL:[\\\\s\\\\S]*?\\\\bdescribe\\\\b")
                .contains("MYSQL_COLLATE_CLAUSE")
                .contains("hasMysqlCollateClause")
                .contains("MYSQL_JSON_TABLE_JOIN_WITHOUT_ON")
                .contains("hasJsonTableJoinWithoutCondition")
                .contains("MYSQL_IMPLICIT_CROSS_JOIN")
                .contains("hasMysqlImplicitCrossJoin")
                .contains("MYSQL_TEMPORARY_TABLE_AS_SELECT")
                .contains("DM_CTAS_BIND_PARAMETER")
                .contains("hasDamengCtasBindParameter")
                .contains("CREATE TABLE AS SELECT 中使用 JDBC 绑定参数")
                .contains("DAMENG_KEYWORD_TABLE_ALIAS")
                .contains("hasGeneratedDynamicIdentifierPlaceholder")
                .contains("hasMissingDynamicIdentifierIssue")
                .contains("\\\\b(?:from|join|update|into|table)\\\\s+(?:t|b|ID)\\\\b")
                .contains("if (hasMissingDynamicIdentifierIssue(message))")
                .contains("hasTestDataOrConstraintIssue")
                .contains("if (hasTestDataOrConstraintIssue(message))")
                .contains("hasBrokenDynamicSqlShape")
                .contains("isSchemaObjectFailure")
                .contains("Type handler was null")
                .contains("\\\\bcast\\\\s*\\\\(\\\\s*[\\\\[{]")
                .contains("__frch_[A-Za-z][A-Za-z0-9_]*_\\\\d+")
                .contains("matcher.start(\"join\")")
                .contains("MYSQL_JSON_SQL")
                .contains("GENERATED_SEARCH_PARAMETER")
                .contains("ORIGINAL_XML_SYNTAX_DEFECT")
                .contains("hasMysqlSubdate")
                .contains("hasUnresolvedFunctionObject")
                .contains("hasGeneratedSearchParameterIssue")
                .contains("hasOriginalXmlSyntaxDefect")
                .contains("hasUnbalancedSqlParentheses")
                .contains("containsTrailingCommaOutsideQuotes")
                .contains("parenthesisBalance")
                .contains("sqlFromMessage")
                .contains("\\\\bfrom\\\\s+(?:where|$)")
                .contains("\\\\bupdate\\\\b[\\\\s\\\\S]*?\\\\bset\\\\b[\\\\s\\\\S]*?(?:,\\\\s*)?\\\\?(?:\\\\s*,|\\\\s+where\\\\b)")
                .contains("\\\\s+(?:in\\\\s*\\\\(|=|<>|!=|>=|<=|>|<|like\\\\b|is\\\\b)")
                .contains("hasForeachItemBindingIssue")
                .contains("TEST_SCHEMA_FUNCTION")
                .contains("测试库缺少函数/自定义函数")
                .contains("先对齐达梦测试 schema 中缺失的表、视图、字段、函数")
                .contains("schemaIssueCounts(records, \"无效的列名\", \"无效的变量名\", \"无法解析的成员访问表达式\")")
                .contains("\\\\blike\\\\s+\\\\?\\\\s*'")
                .contains("\\\\(\\\\s*(?:is\\\\s+(?:not\\\\s+)?null")
                .contains("and[A-Za-z_][A-Za-z0-9_$]*")
                .contains("BROKEN_DYNAMIC_SQL_OR_ARGS")
                .contains("TEST_DATA_TYPE_MISMATCH")
                .contains("lower.contains(\"invalid comparison:\")")
                .contains("NumberFormatException")
                .contains("TEST_DATA_FOREIGN_KEY_CONSTRAINT")
                .contains("RETURN_TYPE_MISMATCH")
                .contains("MAPPER_PROPERTY_NAME")
                .contains("FOREACH_ITEM_BINDING")
                .contains("KEY_PROPERTY_PARAMETER_OBJECT_MISMATCH")
                .contains("No setter found for the keyProperty")
                .contains("INSERT_VALUES_ASSIGNMENT")
                .contains("列表不匹配")
                .contains("重复的列名")
                .contains("SET IDENTITY_INSERT")
                .contains("optionalSecret(resolvePlaceholders(config.datasource.password), \"datasource.password\")")
                .contains("references an unresolved placeholder")
                .contains("isMybatisPlusPageType")
                .contains("defaultMybatisPlusPage")
                .contains("isMybatisPlusWrapperType")
                .contains("defaultMybatisPlusWrapper")
                .contains("defaultProxyReturnValue")
                .contains("set schema")
                .contains("quotedIdentifier(schema)")
                .doesNotContain("@SpringBootTest")
                .doesNotContain("@ActiveProfiles")
                .doesNotContain("PlatformTransactionManager")
                .doesNotContain("RabbitTemplate");
        assertThat(generatedTestSource.indexOf("if (lower.contains(\"on duplicate key update\"))"))
                .isLessThan(generatedTestSource.indexOf("if (hasOriginalXmlSyntaxDefect(message))"));
        assertThat(generatedTestSource)
                .doesNotContain("return switch")
                .doesNotContain("try (var ")
                .doesNotContain("Path.of(")
                .doesNotContain("private record ")
                .doesNotContain("List.of(")
                .doesNotContain("Set.of(")
                .doesNotContain("Map.of(")
                .doesNotContain("List.copyOf(")
                .doesNotContain("Set.copyOf(")
                .doesNotContain("Map.copyOf(")
                .doesNotContain("Files.writeString(")
                .doesNotContain(".isBlank()")
                .doesNotContain(".stripLeading()")
                .doesNotContain(".canAccess(")
                .doesNotContain(" instanceof String name")
                .doesNotContain(" instanceof Map map")
                .doesNotContain(" instanceof Collection<?> collection")
                .doesNotContain(" instanceof ParameterizedType parameterizedType")
                .doesNotContain(" instanceof Class<?> clazz")
                .doesNotContain(" instanceof Element element")
                .doesNotContain(" instanceof Element child")
                .doesNotContain("singleValue(collectionElementDefaults)")
                .doesNotContain("singleValue(collectionScalarDefaults)")
                .doesNotContain("singleValue(collectionSqlFragmentDefaults)")
                .doesNotContain("singleValue(collectionColumnReferences)")
                .doesNotContain("singleValue(collectionElementColumnReferences)")
                .doesNotContainPattern("case\\s+[^:\\n]+->")
                .doesNotContainPattern("case\\s+[^:\\n]+,\\s+[^:\\n]+:");
    }

    @Test
    void generatedValidationTestBuildsTopLevelMapParametersFromStatementDefaults() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString()
        );

        String generatedTestSource = Files.readString(tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java"));
        assertThat(exitCode).isZero();
        assertThat(generatedTestSource)
                .contains("depth == 0 && statement != null && statement.hasDefaultParameterMap()")
                .contains("? defaultParameterMap(statement)")
                .contains("putDefaultParameterValue(value, valueName, collectionValue)")
                .contains("Map<String, Object> defaultValue = nestedProperty")
                .contains("? defaultMapCollectionValue(valueName, statement)")
                .contains(": defaultMapParameterValue(valueName, statement)");
    }

    @Test
    void generatedValidationTestKeepsNestedSetAssignmentDefaults() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString()
        );

        String generatedTestSource = Files.readString(tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java"));
        assertThat(exitCode).isZero();
        assertThat(generatedTestSource)
                .contains("metadata.addDefaultValue(expression, defaultValue);")
                .contains("metadata.addDefaultValue(valueMatcher.group(1), defaultValue);")
                .contains("metadata.addSetDefaultValue(propertyName);")
                .contains("metadata.addSetDefaultValue(valueMatcher.group(1));")
                .contains("|| isSetTrimElement(element)")
                .contains("private boolean isSetTrimElement(Element element)")
                .contains("Pattern.compile(\"(?is)(?:^|\\\\s)set\\\\s*$\")")
                .contains("private boolean shouldPreserveConfiguredNullDefault(")
                .contains("!statement.setDefaultValue(valueName) && !statement.hasSetDefaultUnder(valueName)")
                .contains("&& !statementRequiredDefaultValue(valueName, statement)")
                .contains("private boolean statementRequiredDefaultValue(String valueName, MapperStatement statement)")
                .contains("configuredValue == null && shouldPreserveConfiguredNullDefault(entryPath, existing, statement)")
                .contains("return dynamicIdentifierMetadata.hasSetDefaultUnder(valueName)");
    }

    @Test
    void generatedValidationTestNormalizesConfiguredValidationPlaceholders() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString()
        );

        String generatedTestSource = Files.readString(tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java"));
        assertThat(exitCode).isZero();
        assertThat(generatedTestSource)
                .contains("return normalizeValidationParameterMap(value);")
                .contains("normalizeSqlIsComparisonValue(value);")
                .contains("normalizeMutuallyExclusiveFlagValues(value);")
                .contains("\"IS\".equalsIgnoreCase(String.valueOf(value.get(comparisonKey)).trim())")
                .contains("value.put(fieldValueKey, \"NULL\");")
                .contains("normalized.startsWith(\"sum\")")
                .contains("value.put(beforeCurrentFlagKey, null);")
                .contains("isRegexpSqlFragmentName(normalizeName(valueName))")
                .contains("defaultTemporalString")
                .contains("defaultSqlFragmentForName")
                .contains("isMonthLikeParameterName")
                .contains("isYearLikeParameterName")
                .contains("hasTemporalRangeQualifier")
                .contains("isDayOfMonthParameterName")
                .contains("shouldKeepConfiguredCollectionValue")
                .contains("\"ID\".equalsIgnoreCase(text)")
                .contains("private Set<String> optionalDynamicSqlFragmentNames(String test)")
                .contains("optionalDynamicExpressions.contains(normalizeName(expression))")
                .contains("metadata.addDefaultValue(expression, null);")
                .contains("statement.hasDefaultValue(valueName)")
                .contains("&& \"ID\".equalsIgnoreCase(stripSqlLiteralQuotes(text))")
                .contains("return quoteSqlLiteral(\"1\");")
                .contains("ValidationRecord connectionFailure = databaseConnectionFailure(sqlSessionFactory);")
                .contains("private ValidationRecord databaseConnectionFailure(SqlSessionFactory sqlSessionFactory)")
                .contains("Checking database connection...")
                .contains("Failed to open Dameng validation connection.")
                .contains("isDatabaseConnectionFailure(message)")
                .contains("return \"DATABASE_CONNECTION\";")
                .contains("return \"数据库连接失败\";")
                .contains("private ValidationRecord skippedRegexpPlaceholderRecord(")
                .contains("isRegexpMemoryLimitFailure(summary)")
                .contains("hasGeneratedRegexpPlaceholderParameter(mapperMethod, parameters)")
                .contains("\"regexp-placeholder\"")
                .contains("record = skipMissingDynamicIdentifier(record);")
                .contains("ValidationRecord unsupportedReturnType = skipUnsupportedReturnType(mapperMethod);")
                .contains("record = skipExistingDdlObject(record);")
                .contains("private ValidationRecord skipMissingDynamicIdentifier(ValidationRecord record)")
                .contains("private ValidationRecord skipUnsupportedReturnType(MapperMethod mapperMethod)")
                .contains("\"unsupported-return-type\"")
                .contains("private ValidationRecord skipExistingDdlObject(ValidationRecord record)")
                .contains("\"existing-ddl-object\"")
                .contains("\"dynamic-identifier-parameter\"")
                .contains("configure a real identifier in .dm-adapter/sql-rewrite.yml validationArgs")
                .contains("private boolean hasRegexpOperatorIssue(String message)")
                .contains("lower.contains(\"regexp_like\")")
                .contains("lower.contains(\"正则表达式\")");
        assertThat(generatedTestSource)
                .contains("Map<String, Object> normalizedParams = normalizeValidationParameterMap(new LinkedHashMap<>(params));")
                .contains("!normalizedParams.containsKey(field.getName())")
                .contains("Object rawConfigured = normalizedParams.get(field.getName())");
    }

    @Test
    void generatedValidationTestKeepsScalarForeachCollectionsFromReusingObjectElementDefaults() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString()
        );

        String generatedTestSource = Files.readString(tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java"));
        assertThat(exitCode).isZero();
        assertThat(generatedTestSource)
                .contains("metadata.addCollectionScalarDefault(")
                .contains("defaultValueForDirectParameter(collection, jdbcType)")
                .contains("statement.scalarCollectionParameter(collectionName)")
                .contains("!statement.hasCollectionElementDefault(collectionName)")
                .contains("!statement.hasCollectionElementColumnReferences(collectionName)")
                .contains("return dynamicIdentifierMetadata.hasCollectionElementDefault(valueName)")
                .contains("return dynamicIdentifierMetadata.hasCollectionElementColumnReferences(valueName)")
                .contains("if (scalarCollectionParameter(valueName))")
                .contains("if (references == null && scalarCollectionParameter(collectionName))")
                .contains("return scalarConfiguredCollectionDefault(collectionName, statement)")
                .contains("statement.collectionScalarDefault(collectionName)")
                .contains("mergeConfiguredParameterMap(value, configuredParams, statement, \"\")")
                .contains("scalarConfiguredCollectionValue(entryPath, configuredValue, statement)")
                .contains("scalarCollectionElementType(nestedClass)")
                .contains("configuredScalarCollectionElement(")
                .contains("for (Object candidate : item.values())")
                .contains("normalizeConfiguredDynamicIdentifierValue(")
                .contains("String.class.equals(targetType) && rawValue instanceof String")
                .contains("statement == null ? null : statement.defaultValue(valueName)")
                .contains("field.getGenericType(),\n                            statement,\n                            field.getName()")
                .contains("configuredItem,\n                            statement,\n                            valueName")
                .contains("nestedCollection.directElement")
                .contains("defaultDirectNestedForeachCollectionValue(")
                .contains("addDirectNestedForeachBranchDefaults(element, item, elementDefault)")
                .contains("private void addDirectNestedForeachBranchDefaults(")
                .contains("directNestedForeachCollectionPath(")
                .contains("isCollectionOfCollections(existing)")
                .contains("firstCollectionElement(")
                .contains("firstMapElementDefault(")
                .contains("defaultElement = firstMapElementDefault(statement.collectionScalarDefault(valueName))")
                .contains("Map<String, Object> nestedDefault = firstMapElementDefault(values)")
                .contains("Map<String, Object> scalarDefault = firstMapElementDefault(")
                .contains("statement.collectionScalarDefault(collectionName)")
                .contains("configuredItem = mergeConfiguredCollectionElementMap(")
                .contains("Object nestedCollection = nestedConfiguredCollectionValue(")
                .contains("existingDefault,")
                .contains("configured,")
                .contains("field.getName()")
                .contains("private Object nestedConfiguredCollectionValue(")
                .contains("MapperStatement statement,\n            String pathPrefix")
                .contains("Collection.class.isAssignableFrom(nestedClass)")
                .contains("rawCollectionElementType(genericType)")
                .contains("configuredCollectionObjectItem(collectionName, item, statement)")
                .contains("knownCollectionObjectPropertyCount(item) >= 2")
                .contains("private int knownCollectionObjectPropertyCount(Map<?, ?> item)")
                .contains("isKnownCollectionObjectProperty(")
                .contains("if (scalarItem == MethodArgumentConfig.MISSING) {")
                .contains("return MethodArgumentConfig.MISSING;")
                .contains("isGeneratedDynamicIdentifierPlaceholder(")
                .contains("isLikelyDynamicIdentifierName(normalized)")
                .contains("configuredArgs.valueFor(parameterName, effectiveParameterName, i)")
                .contains("private Object valueFor(String parameterName, String effectiveParameterName, int index)")
                .contains("Object value = valueForName(effectiveParameterName)")
                .contains("String resolvedCollectionName = statement.scalarCollectionName(collectionName)")
                .contains("Object sqlFragmentDefault = statement.collectionSqlFragmentDefault(collectionName)")
                .contains("Character.isDigit(normalized.charAt(i))")
                .contains("collectionLike ? collectionParameterIndex : -1")
                .contains("collectionExpressionName(collectionIndex, fallbackName)")
                .contains("return new ArrayList<>(namedCollectionParameterNames).get(index);")
                .contains("scalarNames.addAll(collectionSqlFragmentDefaults.keySet())")
                .contains("shouldUsePojoCollectionElement(nestedClass)")
                .contains("rawValue instanceof Map<?, ?> && shouldUsePojoCollectionElement(targetType)")
                .contains("Object existingDefault = field.get(instance);")
                .contains("Object valueToConvert = coerced != configured")
                .contains("serializablePojo(value, depth)")
                .contains("return \"2024-01-01 00:00:00\";")
                .contains("normalized.contains(\"comparision\")")
                .contains("\"list\",")
                .contains("\"collection\",")
                .contains("\"array\",")
                .contains("private ValueResult configuredNullDefaultValue(")
                .contains("statementRequiredCollectionValue(valueName, statement)")
                .contains("String collectionValueName = requiredCollectionValueName(valueName, statement)")
                .contains("return defaultValue(collectionValueName, targetType, genericType, 0, statement);")
                .contains("if (rawValue == null) {")
                .contains("return defaultCollectionParameter(collectionValueName, statement);")
                .contains("boolean nestedProperty")
                .contains("field.getName(),")
                .contains("private Map<String, Object> defaultMapCollectionValue(")
                .contains("depth > 0 && statement != null && statement.mapCollectionParameter(valueName)")
                .contains("Object collectionScalarDefault = statement == null ? null : statement.collectionScalarDefault(valueName)")
                .contains("collectionScalarDefault instanceof Collection<?>")
                .contains("\"comparision\".equals(normalized)")
                .contains("\"relateformfiltermodelkey\".equals(normalized)")
                .contains("containsMetadataName(nonEmptyCollectionParameterNames, valueName)")
                .contains("matchingMetadataName(values, valueName)")
                .contains("isSyntheticMetadataName(valueName) && values.size() == 1")
                .contains("private String requiredCollectionValueName(String valueName, MapperStatement statement)")
                .contains("Set<String> collectionNames = statement.collectionParameterNames()")
                .contains("valueByMetadataName(collectionScalarDefaults, valueName)")
                .contains("normalized.contains(\"month\")")
                .contains("return \"202401\";");
    }

    @Test
    void generatedValidationTestScansDynamicMetadataFromSqlIncludes() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString()
        );

        String generatedTestSource = Files.readString(tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java"));
        assertThat(exitCode).isZero();
        assertThat(generatedTestSource)
                .contains("Map<String, Element> sqlFragments = sqlFragments(root, namespace);")
                .contains("private Map<String, Element> sqlFragments(Element mapperRoot, String namespace)")
                .contains("dynamicIdentifierMetadata(element, sqlFragments, namespace)")
                .contains("if (\"include\".equals(element.getTagName()))")
                .contains("sqlFragment(sqlFragments, namespace, element.getAttribute(\"refid\"))")
                .contains("currentIncludeStack.add(includeKey)")
                .contains("private Element sqlFragment(Map<String, Element> sqlFragments, String namespace, String refid)")
                .contains("private String sqlFragmentKey(String namespace, String refid)");
    }

    @Test
    void generatedValidationTestAvoidsDoubleQuotedDynamicSqlFragmentDefaults() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString()
        );

        String generatedTestSource = Files.readString(tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java"));
        assertThat(exitCode).isZero();
        assertThat(generatedTestSource)
                .contains("quoteAwareDynamicSqlFragmentDefault")
                .contains("dynamicPlaceholderInsideSqlLiteral(text, startIndex, endIndex)")
                .contains("return stripSqlLiteralQuotes(value);")
                .contains("previousNonWhitespaceIndex(text, startIndex - 1)")
                .contains("nextNonWhitespaceIndex(text, endIndex)")
                .contains("defaultText.equals(strippedDefault)")
                .contains("!configuredText.equals(strippedConfigured)")
                .contains("&& isGeneratedPlaceholderText(defaultText)")
                .contains("return strippedConfigured;");
    }

    @Test
    void generateValidationTestPreservesCommaSeparatedSchemas() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString(),
                "--schema",
                "newsee-charge-10,newsee-bill-10,newsee-owner"
        );

        assertThat(exitCode).isZero();
        assertThat(Files.readString(tempDir.resolve(".dm-adapter/sql-validation.yml")))
                .contains("schema: \"newsee-charge-10,newsee-bill-10,newsee-owner\"");
        assertThat(Files.readString(tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java")))
                .contains("primarySchema()")
                .contains("SCHEMA FALLBACK");
    }

    @Test
    void generateValidationTestUpdatesExistingGeneratedFiles() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");
        Path config = tempDir.resolve(".dm-adapter/sql-validation.yml");
        Path test = tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java");
        Files.createDirectories(config.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(config, "schema: \"custom\"\n");
        Files.writeString(test, "stale generated test");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString(),
                "--schema",
                "sample-system"
        );

        assertThat(exitCode).isZero();
        assertThat(Files.readString(config))
                .contains("schema: \"sample-system\"")
                .contains("mapperXmlLocations:")
                .doesNotContain("schema: \"custom\"");
        assertThat(Files.readString(test))
                .contains("package com.example;")
                .contains("@Tag(\"dm-sql-validation\")")
                .doesNotContain("stale generated test");
    }

    @Test
    void generateValidationTestPrintsTimestampedConsoleOutput() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode;
        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            exitCode = new CommandLine(new DmAdapterCli()).execute(
                    "generate-validation-test",
                    "--project",
                    tempDir.toString()
            );
        } finally {
            System.setOut(originalOut);
        }

        assertThat(exitCode).isZero();
        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .containsPattern("(?m)^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\] Dameng SQL validation test generation completed\\.$")
                .containsPattern("(?m)^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\] Config: .+sql-validation\\.yml$")
                .containsPattern("(?m)^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\] - CREATE .+DmSqlValidationTest\\.java$");
    }

    @Test
    void generateValidationTestPrefersMigratedMapperDmLocations() throws Exception {
        writeDemoProject();
        writeApplicationClass("src/main/java/com/example/DemoApplication.java", "com.example", "DemoApplication");

        int migrateExitCode = new CommandLine(new DmAdapterCli()).execute("migrate", "--project", tempDir.toString());
        int generateExitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString()
        );

        assertThat(migrateExitCode).isZero();
        assertThat(generateExitCode).isZero();
        assertThat(Files.readString(tempDir.resolve(".dm-adapter/sql-validation.yml")))
                .contains("src/main/resources/mapper-dm/**/*.xml")
                .doesNotContain("src/main/resources/mapper/**/*.xml");
    }

    @Test
    void generateValidationTestRequiresAppModuleWhenMultipleApplicationsExist() throws Exception {
        writeMultiModuleProjectWithIndependentRootPom();
        writeAdditionalAppModule("another-rest", "AnotherApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString()
        );

        assertThat(exitCode).isEqualTo(1);
        assertThat(Files.exists(tempDir.resolve(".dm-adapter/sql-validation.yml"))).isFalse();
    }

    @Test
    void generateValidationTestUsesExplicitAppModule() throws Exception {
        writeMultiModuleProjectWithIndependentRootPom();
        writeAdditionalAppModule("another-rest", "AnotherApplication");

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString(),
                "--app-module",
                "sample-system-rest"
        );

        assertThat(exitCode).isZero();
        assertThat(Files.exists(tempDir.resolve(".dm-adapter/sql-validation.yml"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("sample-system-rest/src/test/java/com/example/DmSqlValidationTest.java"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("another-rest/src/test/java/com/example/DmSqlValidationTest.java"))).isFalse();
    }

    @Test
    void generateValidationTestUsesRootPomWhenAppModuleMatchesArtifactId() throws Exception {
        writeDemoProject();

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString(),
                "--app-module",
                "demo"
        );

        assertThat(exitCode).isZero();
        assertThat(Files.exists(tempDir.resolve("src/test/java/com/example/DmSqlValidationTest.java"))).isTrue();
    }

    @Test
    void generateValidationTestUsesModuleWhenAppModuleMatchesArtifactIdInsteadOfDirectoryName() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample-root</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """);
        Path modulePom = tempDir.resolve("fastdfs-service/pom.xml");
        Files.createDirectories(modulePom.getParent());
        Files.writeString(modulePom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>newsee-fastdfs</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                </project>
                """);
        writeApplicationClass(
                "fastdfs-service/src/main/java/com/example/fastdfs/FastdfsApplication.java",
                "com.example.fastdfs",
                "FastdfsApplication"
        );
        Path mapper = tempDir.resolve("fastdfs-service/src/main/resources/mapper/FileMapper.xml");
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.fastdfs.dao.FileMapper">
                    <select id="selectFiles">
                        select id from files
                    </select>
                </mapper>
                """);

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString(),
                "--app-module",
                "newsee-fastdfs"
        );

        assertThat(exitCode).isZero();
        assertThat(Files.exists(tempDir.resolve("fastdfs-service/src/test/java/com/example/fastdfs/DmSqlValidationTest.java"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("newsee-fastdfs/src/test/java/com/example/fastdfs/DmSqlValidationTest.java"))).isFalse();
    }

    @Test
    void generateValidationTestFailsWhenAppModuleArtifactIdMatchesMultiplePoms() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample-root</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """);
        writeDuplicateArtifactModule("first-module", "duplicate-app");
        writeDuplicateArtifactModule("second-module", "duplicate-app");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode;
        try (PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setErr(capturedErr);
            exitCode = new CommandLine(new DmAdapterCli()).execute(
                    "generate-validation-test",
                    "--project",
                    tempDir.toString(),
                    "--app-module",
                    "duplicate-app"
            );
        } finally {
            System.setErr(originalErr);
        }

        assertThat(exitCode).isEqualTo(1);
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("Application module artifactId matched multiple pom.xml files for 'duplicate-app'")
                .contains("first-module/pom.xml")
                .contains("second-module/pom.xml")
                .contains("Pass an explicit module path.");
        assertThat(Files.exists(tempDir.resolve(".dm-adapter/sql-validation.yml"))).isFalse();
    }

    @Test
    void generateValidationTestInfersPackageFromMapperNamespaceForExplicitModuleWithoutApplicationClass() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample-root</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """);
        Path modulePom = tempDir.resolve("sample-system-rest/pom.xml");
        Files.createDirectories(modulePom.getParent());
        Files.writeString(modulePom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample-system-rest</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                </project>
                """);
        Path controller = tempDir.resolve("sample-system-rest/src/main/java/com/example/hr/controller/UserController.java");
        Path service = tempDir.resolve("sample-system-rest/src/main/java/com/example/hr/service/UserService.java");
        Path binarySource = tempDir.resolve("sample-system-rest/src/main/java/com/example/hr/HrApp.java");
        Path mapper = tempDir.resolve("sample-system-rest/src/main/resources/mapper-dm/UserMapper.xml");
        Files.createDirectories(controller.getParent());
        Files.createDirectories(service.getParent());
        Files.createDirectories(binarySource.getParent());
        Files.createDirectories(mapper.getParent());
        Files.write(binarySource, new byte[] {0, 1, 2, 3, 4, 5});
        Files.writeString(controller, "package com.example.hr.controller;\npublic class UserController {}\n");
        Files.writeString(service, "package com.example.hr.service;\npublic class UserService {}\n");
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.hr.dao.UserMapper">
                    <select id="selectUsers">
                        select id from users
                    </select>
                </mapper>
                """);

        int exitCode = new CommandLine(new DmAdapterCli()).execute(
                "generate-validation-test",
                "--project",
                tempDir.toString(),
                "--app-module",
                "sample-system-rest"
        );

        assertThat(exitCode).isZero();
        Path generatedTest = tempDir.resolve("sample-system-rest/src/test/java/com/example/hr/DmSqlValidationTest.java");
        assertThat(Files.exists(generatedTest)).isTrue();
        assertThat(Files.readString(generatedTest)).contains("package com.example.hr;");
    }

    private void writeDemoProject() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.2</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.mybatis.spring.boot</groupId>
                            <artifactId>mybatis-spring-boot-starter</artifactId>
                            <version>3.0.3</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Path mapper = tempDir.resolve("src/main/resources/mapper/UserMapper.xml");
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUsers">
                        select IFNULL(name, 'n/a') from user limit #{offset}, #{size}
                    </select>
                    <select id="selectByDate">
                        select DATE_FORMAT(created_at, '%Y-%m-%d') from user
                    </select>
                    <update id="updateByLevel">
                        update user_org
                        <if test="'primaryDepartment' == entryOrgLevel">
                            set primary_department_id = #{item.primaryDepartmentId}
                            where primary_department = #{item.primaryDepartment}
                        </if>
                        <if test="'secondaryDepartment' == entryOrgLevel">
                            set secondary_department_id = #{item.secondaryDepartmentId}
                            where secondary_department = #{item.secondaryDepartment}
                        </if>
                        and audit_status = 1
                    </update>
                    <update id="dayClosingByIds" parameterType="java.util.Map">
                        update payment
                        <set>
                            <if test="closingDay != null and isDayClosing != 0">
                                closing_day = #{closingDay},
                            </if>
                            <if test="isDayClosing == 0">
                                closing_day = null,
                            </if>
                        </set>
                        where id in
                        <foreach collection="ids" item="item" open="(" close=")" separator=",">
                            #{item}
                        </foreach>
                    </update>
                    <select id="getBatchOpenBillChargeItem">
                        select charge_item_id, charge_item
                        from payment
                        where order_no in
                        <foreach collection="orders" item="orderNo" open="(" close=")" separator=",">
                            #{orderNo}
                        </foreach>
                        group by charge_item_id
                    </select>
                    <select id="selectApprovalStateByPaymentIds">
                        select approval_state as approvalState, payment_ids as paymentIds
                        from ns_process_relevance_deposit_refund refund
                        where refund.precinct_id in
                        <foreach collection="precinctIds" item="item" open="(" close=")" separator=",">
                            #{item}
                        </foreach>
                        and refund.payment_ids in
                        <foreach collection="paymentIds" item="item" open="(" close=")" separator=",">
                            #{item}
                        </foreach>
                    </select>
                    <delete id="deleteByLogical" parameterType="com.alibaba.fastjson.JSONObject">
                        update bill_detail set is_delete = #{isDelete}
                        where id in
                        <foreach collection="ids" item="item" open="(" close=")" separator=",">
                            ${item}
                        </foreach>
                    </delete>
                    <select id="listPageWithScopedCollections" parameterType="com.newsee.common.vo.SearchVo">
                        select id
                        from user
                        <where>
                            delete_flag = 0
                            <if test="seeOrganizationIdList != null and seeOrganizationIdList.size() > 0">
                                and organization_id in
                                <foreach collection="seeOrganizationIdList" item="item" open="(" close=")" separator=",">
                                    #{item}
                                </foreach>
                            </if>
                            <if test="filterList != null">
                                <foreach collection="filterList" item="item">
                                    <if test="item.comparison != null and item.comparison == 'EQUAL'">
                                        and ${item.fieldName} = #{item.fieldValue}
                                    </if>
                                </foreach>
                            </if>
                        </where>
                    </select>
                    <update id="dynamicUpdateBatchCreateTemp">
                        create temporary table t_${tmpTableName} as
                        <foreach collection="list" item="item" separator=" union all ">
                            select
                            <foreach collection="item" item="field" separator=",">
                                #{field.fieldValue} AS ${field.fieldName}
                            </foreach>
                            from dual
                        </foreach>
                    </update>
                    <update id="dynamicUpdateWithConstFields">
                        update ${targetTable} t
                        set
                        <foreach collection="fieldNameList" item="fieldName" separator=",">
                            t.${fieldName} = #{fieldName}
                        </foreach>
                        where
                        <foreach collection="constFieldList" item="constFieldName" separator=" and ">
                            ${constFieldName.key}<if test="constFieldName.value != null">=#{constFieldName.value}</if>
                        </foreach>
                    </update>
                    <update id="updateOwnerIdByIdAndSplitOwnerId">
                        update owner_customer
                        <trim prefix="set" suffixOverrides=",">
                            owner_id = case owner_id
                            <foreach collection="list" item="item">
                                when #{item.splitOldOwnerId} then #{item.ownerId}
                            </foreach>
                            end,
                        </trim>
                        where owner_id in
                        <foreach collection="list" item="item" open="(" close=")" separator=",">
                            #{item.splitOldOwnerId}
                        </foreach>
                        and id in
                        <foreach collection="processTaskDetailIdList" item="item" open="(" close=")" separator=",">
                            #{item}
                        </foreach>
                    </update>
                </mapper>
                """);
    }

    private void writeMultiModuleProjectWithIndependentRootPom() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample-root</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <packaging>pom</packaging>
                </project>
                """);
        writeRestModule();
        writeBaseModule();
    }

    private void writeRestModule() throws Exception {
        Path restPom = tempDir.resolve("sample-system-rest/pom.xml");
        Files.createDirectories(restPom.getParent());
        Files.writeString(restPom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.2</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>sample-system-rest</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.mybatis.spring.boot</groupId>
                            <artifactId>mybatis-spring-boot-starter</artifactId>
                            <version>3.0.3</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        writeApplicationClass("sample-system-rest/src/main/java/com/example/RestApplication.java", "com.example", "RestApplication");

        Path properties = tempDir.resolve("sample-system-rest/src/main/resources/application.properties");
        Files.createDirectories(properties.getParent());
        Files.writeString(properties, "mybatis.mapperLocations=classpath*:/mapper/*.xml\n");
    }

    private void writeAdditionalAppModule(String moduleName, String className) throws Exception {
        Path pom = tempDir.resolve(moduleName + "/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.2</version>
                    </parent>
                    <groupId>com.example</groupId>
                    <artifactId>%s</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                </project>
                """.formatted(moduleName));
        writeApplicationClass(
                moduleName + "/src/main/java/com/example/" + className + ".java",
                "com.example",
                className
        );
    }

    private void writeDuplicateArtifactModule(String moduleName, String artifactId) throws Exception {
        Path pom = tempDir.resolve(moduleName + "/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>%s</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                </project>
                """.formatted(artifactId));
    }

    private void writeApplicationClass(String relativePath, String packageName, String className) throws Exception {
        Path app = tempDir.resolve(relativePath);
        Files.createDirectories(app.getParent());
        Files.writeString(app, """
                package %s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class %s {
                    public static void main(String[] args) {
                        SpringApplication.run(%s.class, args);
                    }
                }
                """.formatted(packageName, className, className));
    }

    private void writeBaseModule() throws Exception {
        Path basePom = tempDir.resolve("sample-system-base/pom.xml");
        Files.createDirectories(basePom.getParent());
        Files.writeString(basePom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>sample-system-base</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                </project>
                """);

        Path mapper = tempDir.resolve("sample-system-base/src/main/resources/mapper/UserMapper.xml");
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUsers">
                        select NOW() from dual
                    </select>
                </mapper>
                """);
    }
}
