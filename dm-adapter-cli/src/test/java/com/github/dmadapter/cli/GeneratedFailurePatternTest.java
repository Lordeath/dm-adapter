package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedFailurePatternTest {
    @TempDir
    Path tempDir;

    @Test
    void distinguishesMissingSchemaColumnsFromOriginalInsertOmissions() throws Exception {
        Path source = tempDir.resolve("src/com/example/DmSqlValidationTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, generatedTestSource(), StandardCharsets.UTF_8);
        Path classes = tempDir.resolve("classes");
        compile(List.of(source), classes);

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader()
        )) {
            Class<?> validationClass = classLoader.loadClass("com.example.DmSqlValidationTest");
            Object validation = validationClass.getDeclaredConstructor().newInstance();
            String duplicateStatement = "Original mapper XML defines duplicate statement id "
                    + "'com.example.Mapper.listPage_COUNT'. MyBatis can register only one definition.";
            Object duplicateStatementRecord = failedRecord(validationClass, duplicateStatement);
            assertThat(category(validationClass, validation, duplicateStatementRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(failurePattern(validationClass, validation, duplicateStatementRecord))
                    .isEqualTo("ORIGINAL_MAPPER_DUPLICATE_STATEMENT_ID");

            Method throwableSummary = validationClass.getDeclaredMethod("throwableSummary", Throwable.class);
            throwableSummary.setAccessible(true);
            Throwable nestedFailure = new IllegalStateException(
                    "outer",
                    new IllegalArgumentException("inner")
            );
            assertThat((String) throwableSummary.invoke(validation, nestedFailure))
                    .contains("java.lang.IllegalStateException: outer")
                    .contains(" <- caused by java.lang.IllegalArgumentException: inner");

            Method defaultNameBasedString = validationClass.getDeclaredMethod(
                    "defaultNameBasedString",
                    String.class
            );
            defaultNameBasedString.setAccessible(true);
            assertThat(defaultNameBasedString.invoke(validation, "repairCanCustomerEvalHour")).isEqualTo("1");
            assertThat(defaultNameBasedString.invoke(validation, "dispatchingOvertimeMinute")).isEqualTo("1");

            Method shouldKeepConfiguredValue = validationClass.getDeclaredMethod(
                    "shouldKeepConfiguredValue",
                    String.class,
                    Object.class,
                    Object.class
            );
            shouldKeepConfiguredValue.setAccessible(true);
            assertThat(shouldKeepConfiguredValue.invoke(
                    validation,
                    "repairCanCustomerEvalHour",
                    "test",
                    "1"
            )).isEqualTo(false);

            assertThat(referencedTables(
                    validationClass,
                    validation,
                    "<update id=\"update\">update ns_bid_supplier_bid_info set deleteFlag = 1 where id = #{id}</update>"
            )).containsExactly("ns_bid_supplier_bid_info");
            assertThat(referencedTables(
                    validationClass,
                    validation,
                    "<insert id=\"insert\">insert into ns_bid_supplier_bid_info(id) values (#{id})</insert>"
            )).containsExactly("ns_bid_supplier_bid_info");
            assertThat(referencedTables(
                    validationClass,
                    validation,
                    "<select id=\"select\">select id from ns_bid_supplier_bid_info where id = #{id}</select>"
            )).containsExactly("ns_bid_supplier_bid_info");

            String missingColumn = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 无效的列名[welcomePage]
                    ### SQL: update ns_version_release set `welcomePage` = ? where `id` = ?
                    ### Cause: dm.jdbc.driver.DMException: 无效的列名[welcomePage]
                    """;
            assertThat(failurePattern(validationClass, validation, missingColumn))
                    .isEqualTo("TEST_SCHEMA_OBJECT");
            assertThat(shouldSuggestValidationArguments(
                    validationClass,
                    validation,
                    failedRecord(validationClass, missingColumn)
            )).isFalse();

            String havingSelectAlias = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error querying database. Cause: dm.jdbc.driver.DMException: 无效的列名[isFollowUp]
                    ### SQL: select t1.*,
                    IF(t2.houseId is not null, 1, 0) as isFollowUp
                    from arrears_house t1
                    left join follow_record t2 on t1.houseId = t2.houseId
                    having 1 = 1 and isFollowUp = ?
                    ### Cause: dm.jdbc.driver.DMException: 无效的列名[isFollowUp]
                    """;
            Object havingSelectAliasRecord = failedRecord(validationClass, havingSelectAlias);
            assertThat(failurePattern(validationClass, validation, havingSelectAliasRecord))
                    .isEqualTo("DAMENG_HAVING_SELECT_ALIAS");
            assertThat(category(validationClass, validation, havingSelectAliasRecord))
                    .isEqualTo("SQL_SYNTAX");

            String missingSchema = """
                    java.lang.IllegalStateException: Failed to set Dameng schema: newsee-association
                    Caused by: dm.jdbc.driver.DMException: 无效的模式名[newsee-association]
                    """;
            Object missingSchemaRecord = failedRecord(validationClass, missingSchema);
            assertThat(failurePattern(validationClass, validation, missingSchemaRecord))
                    .isEqualTo("TEST_SCHEMA_OBJECT");
            assertThat(category(validationClass, validation, missingSchemaRecord))
                    .isEqualTo("TEST_SCHEMA");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, missingSchemaRecord))
                    .isFalse();

            addDatabaseColumn(
                    validationClass,
                    validation,
                    "ns_bid_supplier_bid_info",
                    "biddingManagementId",
                    "BIGINT"
            );
            String misspelledSourceColumn = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 无效的列名[biddingManagement2Id]
                    ### SQL: update ns_bid_supplier_bid_info set `deleteFlag` = 1
                    where `biddingManagement2Id` = ?
                    ### Cause: dm.jdbc.driver.DMException: 无效的列名[biddingManagement2Id]
                    """;
            Object misspelledRecord = failedRecord(validationClass, misspelledSourceColumn);
            assertThat(failurePattern(validationClass, validation, misspelledRecord))
                    .isEqualTo("ORIGINAL_SQL_COLUMN_NAME_MISMATCH");
            assertThat(category(validationClass, validation, misspelledRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, misspelledRecord))
                    .isFalse();

            String omittedRequiredColumn = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 违反列[business_id]非空约束
                    ### SQL: insert into ns_system_selector_log
                    (user_id, enterprise_id, type, business_text)
                    values (?, ?, ?, ?)
                    ### Cause: dm.jdbc.driver.DMException: 违反列[business_id]非空约束
                    """;
            Object omittedRecord = failedRecord(validationClass, omittedRequiredColumn);
            assertThat(failurePattern(validationClass, validation, omittedRecord))
                    .isEqualTo("ORIGINAL_XML_REQUIRED_COLUMN_OMISSION");
            assertThat(category(validationClass, validation, omittedRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, omittedRecord))
                    .isFalse();

            String suppliedRequiredColumn = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 违反列[business_id]非空约束
                    ### SQL: insert into ns_system_selector_log
                    (user_id, business_id)
                    values (?, ?)
                    ### Cause: dm.jdbc.driver.DMException: 违反列[business_id]非空约束
                    """;
            assertThat(failurePattern(validationClass, validation, suppliedRequiredColumn))
                    .isEqualTo("TEST_DATA_OR_CONSTRAINT");
            assertThat(shouldSuggestValidationArguments(
                    validationClass,
                    validation,
                    failedRecord(validationClass, suppliedRequiredColumn)
            )).isTrue();

            String missingAnd = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: update ns_paid_in_audit
                    set account_actual_audit_status = ?
                    where enterprise_id = ?
                    accountActualAuditId in (?, ?)
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object missingAndRecord = failedRecord(validationClass, missingAnd);
            assertThat(failurePattern(validationClass, validation, missingAndRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, missingAndRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, missingAndRecord))
                    .isFalse();

            String duplicatedAnd = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error querying database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: select owner.ID from o2o_customer_owner owner
                    where owner.deleted = 0 and and owner.source = 3
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object duplicatedAndRecord = failedRecord(validationClass, duplicatedAnd);
            assertThat(failurePattern(validationClass, validation, duplicatedAndRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, duplicatedAndRecord))
                    .isEqualTo("ORIGINAL_SQL");

            String predicateTrailingComma = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error querying database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: select id from o2o_property_community_member
                    where owner_id = ? and community_id = ?,
                    and deleted = 0
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object predicateTrailingCommaRecord = failedRecord(validationClass, predicateTrailingComma);
            assertThat(failurePattern(validationClass, validation, predicateTrailingCommaRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, predicateTrailingCommaRecord))
                    .isEqualTo("ORIGINAL_SQL");

            String bareDynamicInsert = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: insert into o2o_flea_market
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object bareDynamicInsertRecord = failedRecord(validationClass, bareDynamicInsert);
            assertThat(failurePattern(validationClass, validation, bareDynamicInsertRecord))
                    .isEqualTo("BROKEN_DYNAMIC_SQL_OR_ARGS");
            assertThat(category(validationClass, validation, bareDynamicInsertRecord))
                    .isEqualTo("METHOD_ARGS_OR_BINDING");

            String bareDynamicUpdate = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: update o2o_property_houseinfo
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object bareDynamicUpdateRecord = failedRecord(validationClass, bareDynamicUpdate);
            assertThat(failurePattern(validationClass, validation, bareDynamicUpdateRecord))
                    .isEqualTo("BROKEN_DYNAMIC_SQL_OR_ARGS");
            assertThat(category(validationClass, validation, bareDynamicUpdateRecord))
                    .isEqualTo("METHOD_ARGS_OR_BINDING");

            String missingWhere = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error querying database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: select id, enterpriseId
                    from ns_integration_interface "and" deleteFlag = 0
                    and enterpriseId = ?
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object missingWhereRecord = failedRecord(validationClass, missingWhere);
            assertThat(failurePattern(validationClass, validation, missingWhereRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, missingWhereRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, missingWhereRecord))
                    .isFalse();

            String updateFrom = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: update from ns_meter_divide_area
                    "set" deleteFlag = 1
                    where ID in (?, ?)
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object updateFromRecord = failedRecord(validationClass, updateFrom);
            assertThat(failurePattern(validationClass, validation, updateFromRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, updateFromRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, updateFromRecord))
                    .isFalse();

            String updateWithoutSet = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: update ads_report_detailfilling where id = ?
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object updateWithoutSetRecord = failedRecord(validationClass, updateWithoutSet);
            assertThat(failurePattern(validationClass, validation, updateWithoutSetRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, updateWithoutSetRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, updateWithoutSetRecord))
                    .isFalse();

            String missingInsertColumnComma = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: insert into ns_document_center
                    (`enterpriseId`, `allowDepartment` `allowDepartmentName`, `allowPersonEdit`)
                    values (?, ?, ?, ?)
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object missingInsertColumnCommaRecord = failedRecord(
                    validationClass,
                    missingInsertColumnComma
            );
            assertThat(failurePattern(validationClass, validation, missingInsertColumnCommaRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, missingInsertColumnCommaRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(
                    validationClass,
                    validation,
                    missingInsertColumnCommaRecord
            )).isFalse();

            String invalidOgnlMethodCall = """
                    org.mybatis.spring.MyBatisSystemException:
                    nested exception is org.apache.ibatis.builder.BuilderException:
                    Error evaluating expression 'ownerUUIDList != null and ownerUUIDList().size > 0'.
                    Cause: org.apache.ibatis.ognl.MethodFailedException:
                    Method "ownerUUIDList" failed for object org.apache.ibatis.scripting.xmltags.DynamicContext$ContextMap
                    [java.lang.NoSuchMethodException:
                    org.apache.ibatis.scripting.xmltags.DynamicContext$ContextMap.ownerUUIDList()]
                    """;
            Object invalidOgnlMethodCallRecord = failedRecord(validationClass, invalidOgnlMethodCall);
            assertThat(failurePattern(validationClass, validation, invalidOgnlMethodCallRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, invalidOgnlMethodCallRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(
                    validationClass,
                    validation,
                    invalidOgnlMethodCallRecord
            )).isFalse();

            String nonIterableForeachParameter = """
                    org.apache.ibatis.builder.BuilderException:
                    Error evaluating expression 'precinctId'. Return value (1) was not iterable.
                    """;
            Object nonIterableForeachRecord = failedRecord(validationClass, nonIterableForeachParameter);
            assertThat(failurePattern(validationClass, validation, nonIterableForeachRecord))
                    .isEqualTo("NON_ITERABLE_COLLECTION_PARAMETER");
            assertThat(category(validationClass, validation, nonIterableForeachRecord))
                    .isEqualTo("METHOD_ARGS_OR_BINDING");
            assertThat(shouldSuggestValidationArguments(
                    validationClass,
                    validation,
                    nonIterableForeachRecord
            )).isFalse();

            String conflictingPaginationBranches = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error querying database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: select id from ns_sr_services order by update_datetime desc
                    limit ?, ? limit 0, ?
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object conflictingPaginationRecord = failedRecord(validationClass, conflictingPaginationBranches);
            assertThat(failurePattern(validationClass, validation, conflictingPaginationRecord))
                    .isEqualTo("DYNAMIC_BRANCH_PARAMETER_CONFLICT");
            assertThat(category(validationClass, validation, conflictingPaginationRecord))
                    .isEqualTo("METHOD_ARGS_OR_BINDING");
            assertThat(shouldSuggestValidationArguments(
                    validationClass,
                    validation,
                    conflictingPaginationRecord
            )).isFalse();

            String conflictingOrderBranches = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error querying database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: select id from ns_sr_services
                    order by update_datetime desc order by reception_date asc
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object conflictingOrderRecord = failedRecord(validationClass, conflictingOrderBranches);
            assertThat(failurePattern(validationClass, validation, conflictingOrderRecord))
                    .isEqualTo("DYNAMIC_BRANCH_PARAMETER_CONFLICT");
            assertThat(category(validationClass, validation, conflictingOrderRecord))
                    .isEqualTo("METHOD_ARGS_OR_BINDING");

            String conflictingDerivedTableAliases = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error querying database. Cause: dm.jdbc.driver.DMException:
                    Repetitive table name or alias [t]
                    ### SQL: select a.id from ns_sr_services a
                    left join (select service_id from ns_sr_services_log where op_stepname = 'void') t
                    on a.id = t.service_id
                    left join (select service_id from ns_sr_services_log where op_stepname = 'pending') t
                    on a.id = t.service_id
                    ### Cause: dm.jdbc.driver.DMException: Repetitive table name or alias [t]
                    """;
            Object conflictingAliasRecord = failedRecord(validationClass, conflictingDerivedTableAliases);
            assertThat(failurePattern(validationClass, validation, conflictingAliasRecord))
                    .isEqualTo("DYNAMIC_BRANCH_PARAMETER_CONFLICT");
            assertThat(category(validationClass, validation, conflictingAliasRecord))
                    .isEqualTo("METHOD_ARGS_OR_BINDING");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, conflictingAliasRecord))
                    .isFalse();

            String trailingSelectComma = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error querying database. Cause: dm.jdbc.driver.DMException: 语法分析出错
                    ### SQL: SELECT sum(ChargeSum) thisPeriodShould,
                    sum(tax) thisPeriodShouldTaxAmount,
                    FROM charge_account_change
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object trailingSelectCommaRecord = failedRecord(validationClass, trailingSelectComma);
            assertThat(failurePattern(validationClass, validation, trailingSelectCommaRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, trailingSelectCommaRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, trailingSelectCommaRecord))
                    .isFalse();

            String boundValueAsPredicate = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error querying database. Cause: dm.jdbc.driver.DMException: 查询使用值表达式作为过滤条件
                    ### SQL: select id from ns_finance_collection
                    where deleteFlag = 0 and ?
                    ### Cause: dm.jdbc.driver.DMException: 查询使用值表达式作为过滤条件
                    """;
            Object boundValueAsPredicateRecord = failedRecord(validationClass, boundValueAsPredicate);
            assertThat(failurePattern(validationClass, validation, boundValueAsPredicateRecord))
                    .isEqualTo("ORIGINAL_XML_SYNTAX_DEFECT");
            assertThat(category(validationClass, validation, boundValueAsPredicateRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(
                    validationClass,
                    validation,
                    boundValueAsPredicateRecord
            )).isFalse();

            String missingPojoProperty = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: org.apache.ibatis.reflection.ReflectionException:
                    There is no getter for property named 'zipCode' in 'class com.newsee.city.entity.base.Regions'
                    ### The error may exist in mapper-dm/base/RegionsMapper.xml
                    ### The error may involve com.newsee.city.dao.base.RegionsMapper.insert
                    ### SQL: insert into ns_city_regions(cityCode) values (?)
                    """;
            Object missingPojoPropertyRecord = failedRecord(validationClass, missingPojoProperty);
            assertThat(failurePattern(validationClass, validation, missingPojoPropertyRecord))
                    .isEqualTo("ORIGINAL_MAPPER_PROPERTY_NAME_MISMATCH");
            assertThat(category(validationClass, validation, missingPojoPropertyRecord))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(
                    validationClass,
                    validation,
                    missingPojoPropertyRecord
            )).isFalse();

            String genericObjectProperty = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    There is no getter for property named 'zipCode' in 'class java.lang.Object'
                    """;
            Object genericObjectPropertyRecord = failedRecord(validationClass, genericObjectProperty);
            assertThat(failurePattern(validationClass, validation, genericObjectPropertyRecord))
                    .isEqualTo("MAPPER_PROPERTY_NAME");
            assertThat(category(validationClass, validation, genericObjectPropertyRecord))
                    .isEqualTo("METHOD_ARGS_OR_BINDING");
            assertThat(shouldSuggestValidationArguments(
                    validationClass,
                    validation,
                    genericObjectPropertyRecord
            )).isTrue();
        }
    }

    @Test
    void classifiesDatabaseStatementTimeoutWithoutSuggestingDifferentArguments() throws Exception {
        Path source = tempDir.resolve("src/com/example/DmSqlValidationTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, generatedTestSource(), StandardCharsets.UTF_8);
        Path classes = tempDir.resolve("classes");
        compile(List.of(source), classes);

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader()
        )) {
            Class<?> validationClass = classLoader.loadClass("com.example.DmSqlValidationTest");
            Object validation = validationClass.getDeclaredConstructor().newInstance();
            String timeout = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 请求执行超时
                    ### SQL: INSERT INTO ns_backlog_executor (backlog_id, executor)
                    SELECT ?, ? WHERE NOT EXISTS (
                        SELECT 1 FROM ns_backlog_executor WHERE backlog_id = ? AND executor = ?
                    )
                    ### Cause: dm.jdbc.driver.DMException: 请求执行超时
                    """;
            Object record = failedRecord(validationClass, timeout);

            assertThat(failurePattern(validationClass, validation, record))
                    .isEqualTo("DATABASE_STATEMENT_TIMEOUT");
            assertThat(category(validationClass, validation, record))
                    .isEqualTo("TEST_DATABASE_RUNTIME");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, record))
                    .isFalse();
        }
    }

    @Test
    void classifiesInsertIgnoreWithoutUsableConflictKeyAsOriginalSql() throws Exception {
        Path source = tempDir.resolve("src/com/example/DmSqlValidationTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, generatedTestSource(), StandardCharsets.UTF_8);
        Path classes = tempDir.resolve("classes");
        compile(List.of(source), classes);
        Path rewriteConfig = tempDir.resolve("sql-rewrite.yml");
        Files.writeString(rewriteConfig, """
                upsertKeyResolutions:
                  methods:
                    "com.example.Mapper.method": "ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY"
                """);

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader()
        )) {
            Class<?> validationClass = classLoader.loadClass("com.example.DmSqlValidationTest");
            Object validation = validationClass.getDeclaredConstructor().newInstance();
            loadRewriteConfig(validationClass, validation, rewriteConfig);
            String insertIgnore = """
                    org.apache.ibatis.exceptions.PersistenceException:
                    ### Error updating database. Cause: dm.jdbc.driver.DMException: 第1行附近出现错误
                    ### SQL: insert ignore into ns_bank_file
                    (file_id, file_name) values (?, ?)
                    ### Cause: dm.jdbc.driver.DMException: 语法分析出错
                    """;
            Object record = failedRecord(validationClass, insertIgnore);

            assertThat(failurePattern(validationClass, validation, record))
                    .isEqualTo("ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY");
            assertThat(category(validationClass, validation, record))
                    .isEqualTo("ORIGINAL_SQL");
            assertThat(shouldSuggestValidationArguments(validationClass, validation, record))
                    .isFalse();
        }
    }

    @Test
    void correctsConfiguredMapValuesUsingDmlColumnMetadata() throws Exception {
        Path source = tempDir.resolve("src/com/example/DmSqlValidationTest.java");
        Path pojoSource = tempDir.resolve("src/com/example/SamplePayment.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, generatedTestSource(), StandardCharsets.UTF_8);
        Files.writeString(pojoSource, """
                package com.example;

                public class SamplePayment {
                    public String isMonthClosing;
                    public String roleIds;
                }
                """, StandardCharsets.UTF_8);
        Path classes = tempDir.resolve("classes");
        compile(List.of(source, pojoSource), classes);

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader()
        )) {
            Class<?> validationClass = classLoader.loadClass("com.example.DmSqlValidationTest");
            Object validation = validationClass.getDeclaredConstructor().newInstance();
            setDatabaseColumns(validationClass, validation);
            Object metadata = dynamicIdentifierMetadata(
                    validationClass,
                    validation,
                    """
                            <update id="dayClosingByIds">
                                update payment
                                set ClosingDay = #{closingDay},
                                    IsMonthClosing = #{isMonthClosing}
                                where id = #{id}
                            </update>
                            """
            );
            Object statement = mapperStatement(validationClass, metadata);
            Field dbColumnMetadata = validationClass.getDeclaredField("dbColumnMetadata");
            dbColumnMetadata.setAccessible(true);
            Object columnMetadata = dbColumnMetadata.get(validation);
            Method defaultColumnType = statement.getClass().getDeclaredMethod(
                    "defaultColumnType",
                    String.class,
                    columnMetadata.getClass()
            );
            defaultColumnType.setAccessible(true);
            assertThat(defaultColumnType.invoke(statement, "closingDay", columnMetadata)).isEqualTo("DATE");

            Object selectMetadata = dynamicIdentifierMetadata(
                    validationClass,
                    validation,
                    """
                            <select id="getDetailList">
                                select id
                                from Charge_CustomerChargeDetail
                                where AccountBook &gt;= #{accountBookStartDate}
                            </select>
                            """
            );
            Object selectStatement = mapperStatement(validationClass, selectMetadata);
            Method selectDefaultColumnType = selectStatement.getClass().getDeclaredMethod(
                    "defaultColumnType",
                    String.class,
                    columnMetadata.getClass()
            );
            selectDefaultColumnType.setAccessible(true);
            assertThat(selectDefaultColumnType.invoke(
                    selectStatement,
                    "accountBookStartDate",
                    columnMetadata
            )).isEqualTo("VARCHAR");
            Method selectDefaultParameterMap = validationClass.getDeclaredMethod(
                    "defaultParameterMap",
                    selectStatement.getClass()
            );
            selectDefaultParameterMap.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> selectDefaults = (Map<String, Object>) selectDefaultParameterMap.invoke(
                    validation,
                    selectStatement
            );
            assertThat(selectDefaults.get("accountBookStartDate")).isInstanceOf(String.class);

            Method incompatible = validationClass.getDeclaredMethod(
                    "configuredValueIncompatibleWithColumn",
                    String.class,
                    Object.class,
                    statement.getClass()
            );
            incompatible.setAccessible(true);
            assertThat(incompatible.invoke(validation, "closingDay", "1", statement)).isEqualTo(true);
            Method defaultParameterMap = validationClass.getDeclaredMethod(
                    "defaultParameterMap",
                    statement.getClass()
            );
            defaultParameterMap.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> defaults = (Map<String, Object>) defaultParameterMap.invoke(validation, statement);
            assertThat(String.valueOf(defaults.get("closingDay"))).startsWith("2024-01-01");
            Method configuredParameterMap = validationClass.getDeclaredMethod(
                    "configuredParameterMap",
                    statement.getClass(),
                    Map.class
            );
            configuredParameterMap.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) configuredParameterMap.invoke(
                    validation,
                    statement,
                    Map.of(
                            "closingDay", "1",
                            "isMonthClosing", "202401",
                            "id", 1
                    )
            );

            assertThat(String.valueOf(parameters.get("closingDay"))).startsWith("2024-01-01");
            assertThat(String.valueOf(parameters.get("isMonthClosing"))).hasSizeLessThanOrEqualTo(2);

            Class<?> paymentClass = classLoader.loadClass("com.example.SamplePayment");
            Method configuredPojoValue = validationClass.getDeclaredMethod(
                    "configuredPojoValue",
                    Class.class,
                    java.lang.reflect.Type.class,
                    Map.class,
                    statement.getClass()
            );
            configuredPojoValue.setAccessible(true);
            Object valueResult = configuredPojoValue.invoke(
                    validation,
                    paymentClass,
                    paymentClass,
                    Map.of("isMonthClosing", "202401"),
                    statement
            );
            Field valueField = valueResult.getClass().getDeclaredField("value");
            valueField.setAccessible(true);
            Object payment = valueField.get(valueResult);
            Field isMonthClosing = paymentClass.getDeclaredField("isMonthClosing");
            assertThat(String.valueOf(isMonthClosing.get(payment))).hasSizeLessThanOrEqualTo(2);

            Method convertConfiguredValue = validationClass.getDeclaredMethod(
                    "convertConfiguredValue",
                    Object.class,
                    Class.class,
                    java.lang.reflect.Type.class,
                    statement.getClass(),
                    String.class
            );
            convertConfiguredValue.setAccessible(true);
            Object nullPojoResult = convertConfiguredValue.invoke(
                    validation,
                    null,
                    paymentClass,
                    paymentClass,
                    statement,
                    ""
            );
            Object generatedPayment = valueField.get(nullPojoResult);
            assertThat(generatedPayment).isInstanceOf(paymentClass);

            Object branchMetadata = dynamicIdentifierMetadata(
                    validationClass,
                    validation,
                    """
                            <select id="byRole">
                                select a.id from payment a
                                where
                                <if test="roleIds == ''">
                                    a.user_id = #{userId}
                                </if>
                                <if test="roleIds != null and roleIds != ''">
                                    (a.user_id = #{userId} or a.role_id in (${roleIds}))
                                </if>
                                and a.delete_flag = 0
                            </select>
                            """
            );
            Object branchStatement = mapperStatement(validationClass, branchMetadata);
            Object configuredBranchPojo = configuredPojoValue.invoke(
                    validation,
                    paymentClass,
                    paymentClass,
                    java.util.Collections.singletonMap("roleIds", null),
                    branchStatement
            );
            Object branchPayment = valueField.get(configuredBranchPojo);
            Field roleIds = paymentClass.getDeclaredField("roleIds");
            assertThat(roleIds.get(branchPayment)).isEqualTo("");

            Path bomMapper = tempDir.resolve("bom-mapper.xml");
            Files.writeString(
                    bomMapper,
                    "\uFEFF<mapper namespace=\"com.example.Mapper\"><select id=\"one\">select 1</select></mapper>",
                    StandardCharsets.UTF_8
            );
            Method parseXml = validationClass.getDeclaredMethod("parseXml", Path.class);
            parseXml.setAccessible(true);
            Document document = (Document) parseXml.invoke(validation, bomMapper);
            assertThat(document.getDocumentElement().getAttribute("namespace")).isEqualTo("com.example.Mapper");
        }
    }

    private String failurePattern(Class<?> validationClass, Object validation, String message) throws Exception {
        return failurePattern(validationClass, validation, failedRecord(validationClass, message));
    }

    private String failurePattern(Class<?> validationClass, Object validation, Object record) throws Exception {
        Method method = validationClass.getDeclaredMethod("failurePattern", record.getClass());
        method.setAccessible(true);
        return (String) method.invoke(validation, record);
    }

    private String category(Class<?> validationClass, Object validation, Object record) throws Exception {
        Method method = validationClass.getDeclaredMethod("category", record.getClass());
        method.setAccessible(true);
        return (String) method.invoke(validation, record);
    }

    private boolean shouldSuggestValidationArguments(
            Class<?> validationClass,
            Object validation,
            Object record
    ) throws Exception {
        Method method = validationClass.getDeclaredMethod("shouldSuggestValidationArguments", record.getClass());
        method.setAccessible(true);
        return (boolean) method.invoke(validation, record);
    }

    private Object failedRecord(Class<?> validationClass, String message) throws Exception {
        Class<?> recordClass = Class.forName(
                validationClass.getName() + "$ValidationRecord",
                true,
                validationClass.getClassLoader()
        );
        Method failed = recordClass.getDeclaredMethod("failed", String.class, String.class, String.class);
        failed.setAccessible(true);
        return failed.invoke(null, "com.example.Mapper.method", "configured", message);
    }

    private void loadRewriteConfig(
            Class<?> validationClass,
            Object validation,
            Path rewriteConfig
    ) throws Exception {
        Class<?> configClass = Class.forName(
                validationClass.getName() + "$ValidationConfig",
                true,
                validationClass.getClassLoader()
        );
        Method load = configClass.getDeclaredMethod("load", Path.class, Path.class);
        load.setAccessible(true);
        Object config = load.invoke(null, tempDir.resolve("missing-validation.yml"), rewriteConfig);
        Field currentConfig = validationClass.getDeclaredField("currentConfig");
        currentConfig.setAccessible(true);
        currentConfig.set(validation, config);
    }

    private void addDatabaseColumn(
            Class<?> validationClass,
            Object validation,
            String table,
            String column,
            String type
    ) throws Exception {
        Class<?> metadataClass = Class.forName(
                validationClass.getName() + "$DbColumnMetadata",
                true,
                validationClass.getClassLoader()
        );
        var constructor = metadataClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object metadata = constructor.newInstance();
        Method addColumn = metadataClass.getDeclaredMethod(
                "addColumn",
                String.class,
                String.class,
                String.class
        );
        addColumn.setAccessible(true);
        addColumn.invoke(metadata, table, column, type);
        Field dbColumnMetadata = validationClass.getDeclaredField("dbColumnMetadata");
        dbColumnMetadata.setAccessible(true);
        dbColumnMetadata.set(validation, metadata);
    }

    private void setDatabaseColumns(Class<?> validationClass, Object validation) throws Exception {
        Class<?> metadataClass = Class.forName(
                validationClass.getName() + "$DbColumnMetadata",
                true,
                validationClass.getClassLoader()
        );
        var constructor = metadataClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object metadata = constructor.newInstance();
        Method addColumn = metadataClass.getDeclaredMethod(
                "addColumn",
                String.class,
                String.class,
                String.class,
                int.class,
                boolean.class
        );
        addColumn.setAccessible(true);
        addColumn.invoke(metadata, "payment", "ClosingDay", "DATE", 3, false);
        addColumn.invoke(metadata, "payment", "IsMonthClosing", "VARCHAR", 2, false);
        addColumn.invoke(metadata, "payment", "id", "BIGINT", 8, false);
        addColumn.invoke(metadata, "Charge_CustomerChargeDetail", "AccountBook", "VARCHAR", 20, false);
        addColumn.invoke(metadata, "other_table", "AccountBook", "DATE", 8, false);
        Field dbColumnMetadata = validationClass.getDeclaredField("dbColumnMetadata");
        dbColumnMetadata.setAccessible(true);
        dbColumnMetadata.set(validation, metadata);
    }

    private Object mapperStatement(Class<?> validationClass, Object metadata) throws Exception {
        Class<?> statementClass = Class.forName(
                validationClass.getName() + "$MapperStatement",
                true,
                validationClass.getClassLoader()
        );
        var constructor = statementClass.getDeclaredConstructor(
                String.class,
                String.class,
                List.class,
                metadata.getClass(),
                Set.class,
                Set.class,
                Set.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                "com.example.Mapper",
                "dayClosingByIds",
                List.of(),
                metadata,
                Set.of("closingDay", "isMonthClosing", "id"),
                Set.of("closingDay", "isMonthClosing", "id"),
                Set.of()
        );
    }

    @SuppressWarnings("unchecked")
    private Set<String> referencedTables(
            Class<?> validationClass,
            Object validation,
            String statementXml
    ) throws Exception {
        Object metadata = dynamicIdentifierMetadata(validationClass, validation, statementXml);
        Method referencedTableNames = metadata.getClass().getDeclaredMethod("referencedTableNames");
        referencedTableNames.setAccessible(true);
        return (Set<String>) referencedTableNames.invoke(metadata);
    }

    private Object dynamicIdentifierMetadata(
            Class<?> validationClass,
            Object validation,
            String statementXml
    ) throws Exception {
        Element statement = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(statementXml)))
                .getDocumentElement();
        Method metadataMethod = validationClass.getDeclaredMethod(
                "dynamicIdentifierMetadata",
                Element.class,
                Map.class,
                String.class
        );
        metadataMethod.setAccessible(true);
        return metadataMethod.invoke(validation, statement, Map.of(), "com.example.Mapper");
    }

    private String generatedTestSource() throws Exception {
        Field template = DmSqlValidationTestGenerator.class.getDeclaredField("TEST_TEMPLATE");
        template.setAccessible(true);
        return ((String) template.get(null)).replace("__PACKAGE_DECLARATION__", "package com.example;\n\n");
    }

    private void compile(List<Path> sources, Path outputDirectory) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler").isNotNull();
        Files.createDirectories(outputDirectory);
        List<String> arguments = new java.util.ArrayList<>();
        arguments.add("--release");
        arguments.add("8");
        arguments.add("-classpath");
        arguments.add(System.getProperty("java.class.path"));
        arguments.add("-d");
        arguments.add(outputDirectory.toString());
        sources.forEach(source -> arguments.add(source.toString()));
        assertThat(compiler.run(null, null, null, arguments.toArray(new String[0])))
                .as("generated validation source compilation")
                .isZero();
    }
}
