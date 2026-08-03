package com.github.dmadapter.cli;

import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedActualParameterTypeIndexTest {
    @TempDir
    Path tempDir;

    @Test
    void indexesProductionMapperCallsOnceAndIgnoresTestSources() throws Exception {
        Path mainSource = tempDir.resolve("src/main/java/com/example");
        Path testSource = tempDir.resolve("src/test/java/com/example");
        writeSource(mainSource, "BaseQuery.java", "public class BaseQuery {}\n");
        writeSource(mainSource, "UserQuery.java", "public class UserQuery extends BaseQuery {}\n");
        writeSource(mainSource, "OtherQuery.java", "public class OtherQuery extends BaseQuery {}\n");
        writeSource(mainSource, "UserMapper.java", "public interface UserMapper { void find(BaseQuery query); }\n");
        writeSource(mainSource, "OtherMapper.java", "public interface OtherMapper { void find(BaseQuery query); }\n");
        writeSource(mainSource, "TestOnlyMapper.java", "public interface TestOnlyMapper { void find(BaseQuery query); }\n");
        writeSource(mainSource, "Caller.java", """
                public class Caller {
                    private UserMapper userMapper;
                    private OtherMapper otherMapper;

                    public void call() {
                        UserQuery userQuery = new UserQuery();
                        OtherQuery otherQuery = new OtherQuery();
                        userMapper.find(userQuery);
                        otherMapper.find(otherQuery);
                    }
                }
                """);
        writeSource(testSource, "TestOnlyCaller.java", """
                public class TestOnlyCaller {
                    private TestOnlyMapper testOnlyMapper;

                    public void call() {
                        UserQuery testQuery = new UserQuery();
                        testOnlyMapper.find(testQuery);
                    }
                }
                """);

        Path fixtureClasses = tempDir.resolve("fixture-classes");
        List<Path> fixtureSources = new ArrayList<>();
        try (var paths = Files.walk(mainSource)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(fixtureSources::add);
        }
        compile(fixtureSources, fixtureClasses, JavaCompilerTestSupport.runtimeClasspath());

        Path generatedSource = tempDir.resolve("generated/com/example/DmSqlValidationTest.java");
        Files.createDirectories(generatedSource.getParent());
        Files.writeString(generatedSource, generatedTestSource(), StandardCharsets.UTF_8);
        Path generatedClasses = tempDir.resolve("generated-classes");
        compile(
                List.of(generatedSource),
                generatedClasses,
                JavaCompilerTestSupport.runtimeClasspath() + File.pathSeparator + fixtureClasses
        );

        try (URLClassLoader classLoader = new URLClassLoader(
                new URL[] { generatedClasses.toUri().toURL(), fixtureClasses.toUri().toURL() },
                getClass().getClassLoader()
        )) {
            Class<?> validationClass = classLoader.loadClass("com.example.DmSqlValidationTest");
            Class<?> baseQuery = classLoader.loadClass("com.example.BaseQuery");
            List<Object> mapperMethods = List.of(
                    mapperMethod(validationClass, classLoader, "UserMapper", baseQuery),
                    mapperMethod(validationClass, classLoader, "OtherMapper", baseQuery),
                    mapperMethod(validationClass, classLoader, "TestOnlyMapper", baseQuery)
            );

            Class<?> indexClass = nestedClass(validationClass, "ActualParameterTypeIndex");
            Method build = indexClass.getDeclaredMethod("build", Path.class, List.class);
            build.setAccessible(true);
            Object index = build.invoke(null, tempDir, mapperMethods);
            Method actualType = indexClass.getDeclaredMethod("actualType", String.class, int.class, Class.class);
            actualType.setAccessible(true);

            assertThat(((Class<?>) actualType.invoke(index, "com.example.UserMapper.find", 0, baseQuery)).getName())
                    .isEqualTo("com.example.UserQuery");
            assertThat(((Class<?>) actualType.invoke(index, "com.example.OtherMapper.find", 0, baseQuery)).getName())
                    .isEqualTo("com.example.OtherQuery");
            assertThat(actualType.invoke(index, "com.example.TestOnlyMapper.find", 0, baseQuery))
                    .isSameAs(baseQuery);
        }
    }

    private Object mapperMethod(
            Class<?> validationClass,
            ClassLoader classLoader,
            String mapperSimpleName,
            Class<?> parameterType
    ) throws Exception {
        Class<?> metadataClass = nestedClass(validationClass, "DynamicIdentifierMetadata");
        Constructor<?> metadataConstructor = metadataClass.getDeclaredConstructor();
        metadataConstructor.setAccessible(true);
        Object metadata = metadataConstructor.newInstance();

        Class<?> statementClass = nestedClass(validationClass, "MapperStatement");
        Constructor<?> statementConstructor = statementClass.getDeclaredConstructor(
                String.class,
                String.class,
                List.class,
                metadataClass,
                Set.class,
                Set.class,
                Set.class
        );
        statementConstructor.setAccessible(true);
        Object statement = statementConstructor.newInstance(
                "com.example." + mapperSimpleName,
                "find",
                Collections.emptyList(),
                metadata,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet()
        );

        Class<?> mapperInterface = classLoader.loadClass("com.example." + mapperSimpleName);
        Method method = mapperInterface.getMethod("find", parameterType);
        Class<?> mapperMethodClass = nestedClass(validationClass, "MapperMethod");
        Constructor<?> mapperMethodConstructor = mapperMethodClass.getDeclaredConstructor(
                statementClass,
                Class.class,
                Method.class,
                SqlCommandType.class,
                Class.class
        );
        mapperMethodConstructor.setAccessible(true);
        return mapperMethodConstructor.newInstance(
                statement,
                mapperInterface,
                method,
                SqlCommandType.SELECT,
                parameterType
        );
    }

    private Class<?> nestedClass(Class<?> outerClass, String simpleName) throws ClassNotFoundException {
        return Class.forName(outerClass.getName() + "$" + simpleName, true, outerClass.getClassLoader());
    }

    private String generatedTestSource() throws Exception {
        Field template = DmSqlValidationTestGenerator.class.getDeclaredField("TEST_TEMPLATE");
        template.setAccessible(true);
        return ((String) template.get(null)).replace("__PACKAGE_DECLARATION__", "package com.example;\n\n");
    }

    private void writeSource(Path directory, String fileName, String body) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve(fileName),
                "package com.example;\n" + body,
                StandardCharsets.UTF_8
        );
    }

    private void compile(List<Path> sourceFiles, Path outputDirectory, String classpath) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler").isNotNull();
        assertThat(JavaCompilerTestSupport.compileJava8(compiler, sourceFiles, outputDirectory, classpath))
                .as("generated validation source compilation")
                .isZero();
    }
}
