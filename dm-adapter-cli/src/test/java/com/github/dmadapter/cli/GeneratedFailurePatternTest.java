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
