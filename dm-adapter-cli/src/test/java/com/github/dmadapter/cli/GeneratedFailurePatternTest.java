package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
