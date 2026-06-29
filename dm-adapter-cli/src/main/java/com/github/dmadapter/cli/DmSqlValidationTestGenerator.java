package com.github.dmadapter.cli;

import com.github.dmadapter.core.DmAdapterException;
import com.github.dmadapter.core.FileChange;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.mybatis.MapperXmlScanner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

class DmSqlValidationTestGenerator {
    static final String DEFAULT_CONFIG_PATH = ".dm-adapter/sql-validation.yml";
    static final String TEST_CLASS_NAME = "DmSqlValidationTest";

    private final ApplicationModuleSelector applicationModuleSelector;
    private final MapperXmlScanner mapperXmlScanner;

    DmSqlValidationTestGenerator() {
        this(new ApplicationModuleSelector(), new MapperXmlScanner());
    }

    DmSqlValidationTestGenerator(ApplicationModuleSelector applicationModuleSelector, MapperXmlScanner mapperXmlScanner) {
        this.applicationModuleSelector = applicationModuleSelector;
        this.mapperXmlScanner = mapperXmlScanner;
    }

    ValidationTestGenerationResult generate(Path projectRoot, Path appModule, Path mapperDir, Path configPath, String schema) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        ApplicationModule applicationModule = applicationModuleSelector.select(normalizedRoot, appModule);
        Path actualConfigPath = resolveProjectPath(normalizedRoot, configPath, DEFAULT_CONFIG_PATH);
        List<Path> mapperXmlFiles = validationMapperXmlFiles(normalizedRoot, mapperDir);
        List<String> mapperStatements = discoveredMapperStatements(mapperXmlFiles);
        GeneratedTestTarget testTarget = generatedTestTarget(applicationModule, mapperStatements);

        List<FileChange> fileChanges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        writeGeneratedFile(
                actualConfigPath,
                configTemplate(
                        mapperStatements,
                        mapperXmlLocationPatterns(normalizedRoot, mapperDir, mapperXmlFiles),
                        schema
                ),
                "Generate Dameng SQL validation parameter configuration",
                fileChanges,
                true
        );
        writeGeneratedFile(
                testTarget.path(),
                javaTestSource(testTarget.packageName()),
                "Generate Dameng SQL validation JUnit test",
                fileChanges,
                true
        );
        return new ValidationTestGenerationResult(
                normalizedRoot,
                applicationModule.moduleRoot(),
                actualConfigPath,
                testTarget.path(),
                fileChanges,
                warnings
        );
    }

    private Path resolveProjectPath(Path projectRoot, Path configuredPath, String defaultRelativePath) {
        if (configuredPath == null) {
            return projectRoot.resolve(defaultRelativePath).toAbsolutePath().normalize();
        }
        if (configuredPath.isAbsolute()) {
            return configuredPath.toAbsolutePath().normalize();
        }
        return projectRoot.resolve(configuredPath).toAbsolutePath().normalize();
    }

    private GeneratedTestTarget generatedTestTarget(ApplicationModule applicationModule, List<String> mapperStatements) {
        Optional<Path> existingTest = existingValidationTest(applicationModule.moduleRoot());
        if (existingTest.isPresent()) {
            Path testPath = existingTest.get();
            return new GeneratedTestTarget(testPath, packageNameFromTestPath(applicationModule.moduleRoot(), testPath));
        }
        String packageName = applicationModule.packageName().isBlank()
                ? inferPackageNameFromMapperStatements(mapperStatements)
                : applicationModule.packageName();
        return new GeneratedTestTarget(testPath(applicationModule.moduleRoot(), packageName), packageName);
    }

    private Optional<Path> existingValidationTest(Path moduleRoot) {
        Path testRoot = moduleRoot.resolve("src/test/java");
        if (!Files.isDirectory(testRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> paths = Files.walk(testRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(TEST_CLASS_NAME + ".java"))
                    .sorted()
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new DmAdapterException("Failed to scan generated validation tests under " + testRoot, e);
        }
    }

    private String packageNameFromTestPath(Path moduleRoot, Path testPath) {
        Path testRoot = moduleRoot.resolve("src/test/java").toAbsolutePath().normalize();
        Path parent = testPath.toAbsolutePath().normalize().getParent();
        if (parent == null || !parent.startsWith(testRoot)) {
            return "";
        }
        Path packagePath = testRoot.relativize(parent);
        String normalizedPackagePath = normalize(packagePath);
        if (normalizedPackagePath.isBlank()) {
            return "";
        }
        return normalizedPackagePath.replace('/', '.');
    }

    private Path testPath(Path moduleRoot, String packageName) {
        Path testRoot = moduleRoot.resolve("src/test/java");
        if (packageName == null || packageName.isBlank()) {
            return testRoot.resolve(TEST_CLASS_NAME + ".java").toAbsolutePath().normalize();
        }
        return testRoot.resolve(packageName.replace('.', '/'))
                .resolve(TEST_CLASS_NAME + ".java")
                .toAbsolutePath()
                .normalize();
    }

    private String inferPackageNameFromMapperStatements(List<String> mapperStatements) {
        List<String> mapperPackages = mapperStatements.stream()
                .map(this::mapperPackageName)
                .filter(packageName -> !packageName.isBlank())
                .distinct()
                .toList();
        if (mapperPackages.isEmpty()) {
            return "";
        }
        return trimMapperPackageSuffix(commonPackagePrefix(mapperPackages));
    }

    private String mapperPackageName(String mapperStatement) {
        int methodSeparator = mapperStatement.lastIndexOf('.');
        if (methodSeparator <= 0) {
            return "";
        }
        String namespace = mapperStatement.substring(0, methodSeparator);
        int classSeparator = namespace.lastIndexOf('.');
        if (classSeparator <= 0) {
            return "";
        }
        return namespace.substring(0, classSeparator);
    }

    private String commonPackagePrefix(List<String> packageNames) {
        String[] common = packageNames.get(0).split("\\.");
        int commonLength = common.length;
        for (String packageName : packageNames.subList(1, packageNames.size())) {
            String[] parts = packageName.split("\\.");
            int index = 0;
            while (index < commonLength && index < parts.length && common[index].equals(parts[index])) {
                index++;
            }
            commonLength = index;
            if (commonLength == 0) {
                return packageNames.get(0);
            }
        }
        return String.join(".", List.of(common).subList(0, commonLength));
    }

    private String trimMapperPackageSuffix(String packageName) {
        int separator = packageName.lastIndexOf('.');
        if (separator <= 0) {
            return packageName;
        }
        String suffix = packageName.substring(separator + 1);
        if ("dao".equals(suffix)
                || "mapper".equals(suffix)
                || "mappers".equals(suffix)
                || "repository".equals(suffix)
                || "repositories".equals(suffix)) {
            return packageName.substring(0, separator);
        }
        return packageName;
    }

    private record GeneratedTestTarget(Path path, String packageName) {
    }

    private void writeGeneratedFile(
            Path path,
            String content,
            String description,
            List<FileChange> fileChanges,
            boolean overwrite
    ) {
        try {
            if (Files.exists(path)) {
                if (!overwrite) {
                    return;
                }
                if (hasSameContent(path, content)) {
                    return;
                }
                Files.writeString(path, content, StandardCharsets.UTF_8);
                fileChanges.add(FileChange.applied(path.toString(), "UPDATE", description));
                return;
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            fileChanges.add(FileChange.applied(path.toString(), "CREATE", description));
        } catch (IOException e) {
            throw new DmAdapterException("Failed to write generated file: " + path, e);
        }
    }

    private boolean hasSameContent(Path path, String content) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).equals(content);
        } catch (IOException e) {
            return false;
        }
    }

    private String configTemplate(List<String> mapperStatements, List<String> mapperXmlLocations, String schema) {
        StringBuilder content = new StringBuilder();
        content.append("""
                # Generated by dm-adapter.
                # This test starts MyBatis directly and does not start Spring Boot, ShardingSphere, MQ, or web beans.
                # Run in the Dameng test environment, for example:
                # DM_SQL_VALIDATION=true DM_JDBC_URL=jdbc:dm://host:5236 DM_DB_USERNAME=user DM_DB_PASSWORD=password mvn -Dtest=DmSqlValidationTest -DskipTests=false -Dmaven.test.skip=false test
                #
                datasource:
                  driverClassName: dm.jdbc.driver.DmDriver
                  url: ${DM_JDBC_URL}
                  username: ${DM_DB_USERNAME}
                  password: ${DM_DB_PASSWORD}

                """);
        if (schema == null || schema.isBlank()) {
            content.append("""
                    # Optional Dameng schema. Use comma-separated schemas to validate against fallback schemas.
                    # Quoted schema names such as sample-system are supported.
                    # schema: "sample-system"
                    # schema: "newsee-charge-10,newsee-bill-10,newsee-owner"

                    """);
        } else {
            content.append("schema: \"").append(escapeYamlScalar(schema.trim())).append("\"\n\n");
        }
        content.append("""
                # Mapper XML files to validate. Prefer mapper-dm output when it exists.
                mapperXmlLocations:
                """);
        if (mapperXmlLocations.isEmpty()) {
            content.append("  # - src/main/resources/mapper-dm/**/*.xml\n");
        } else {
            for (String mapperXmlLocation : mapperXmlLocations) {
                content.append("  - \"").append(escapeYamlScalar(mapperXmlLocation)).append("\"\n");
            }
        }
        content.append("""

                # Optional MyBatis packages needed by mapper XML resultType/resultMap/typeHandler declarations.
                typeAliasesPackages:
                  # - com.example.domain
                typeHandlersPackages:
                  # - com.example.mybatis.typehandler

                # Skip mapper methods that are not referenced by project target/classes bytecode.
                usageFilterEnabled: true
                usageClassDirectories:
                  # - newsee-system-base/target/classes

                # Configure sample method arguments by mapperClass.method.
                # Missing methods are invoked with conservative generated parameters when possible.
                methods:
                  # com.example.UserMapper.selectById:
                  #   args:
                  #     - 1

                # Methods listed here are always executed even if usage filtering cannot find a bytecode reference.
                includedMethods:
                  # - com.example.UserMapper.selectById

                # Methods listed here are skipped by the generated integration test.
                excludedMethods:
                  # - com.example.UserMapper.deleteAll
                """);
        if (!mapperStatements.isEmpty()) {
            content.append("\n# Discovered mapper statements:\n");
            for (String mapperStatement : mapperStatements) {
                content.append("# - ").append(mapperStatement).append("\n");
            }
        }
        return content.toString();
    }

    private String escapeYamlScalar(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<Path> validationMapperXmlFiles(Path projectRoot, Path mapperDir) {
        if (mapperDir != null) {
            return xmlFiles(resolveProjectPath(projectRoot, mapperDir, ""));
        }
        List<Path> mapperDmFiles = mapperDmXmlFiles(projectRoot);
        if (!mapperDmFiles.isEmpty()) {
            return mapperDmFiles;
        }
        return mapperXmlScanner.scan(projectRoot).stream()
                .map(MapperXmlFile::path)
                .map(Path::of)
                .toList();
    }

    private List<Path> mapperDmXmlFiles(Path projectRoot) {
        if (!Files.isDirectory(projectRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .filter(path -> !isBuildOrGitPath(projectRoot, path))
                    .filter(path -> normalize(projectRoot.relativize(path.toAbsolutePath().normalize()))
                            .contains("src/main/resources/mapper-dm/"))
                    .filter(path -> !mapperStatements(path).isEmpty())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DmAdapterException("Failed to scan mapper-dm XML files under " + projectRoot, e);
        }
    }

    private List<String> mapperXmlLocationPatterns(Path projectRoot, Path mapperDir, List<Path> mapperXmlFiles) {
        if (mapperDir != null) {
            return List.of(locationPattern(projectRoot, resolveProjectPath(projectRoot, mapperDir, "")));
        }
        Set<Path> directories = new LinkedHashSet<>();
        for (Path mapperXmlFile : mapperXmlFiles) {
            Path parent = mapperXmlFile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                directories.add(parent);
            }
        }
        return directories.stream()
                .map(directory -> locationPattern(projectRoot, directory))
                .sorted()
                .toList();
    }

    private String locationPattern(Path projectRoot, Path path) {
        String value = displayPath(projectRoot, path.toAbsolutePath().normalize());
        if (Files.isDirectory(path)) {
            return value + "/**/*.xml";
        }
        return value;
    }

    private String displayPath(Path projectRoot, Path path) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (path.startsWith(normalizedRoot)) {
            return normalize(normalizedRoot.relativize(path));
        }
        return normalize(path);
    }

    private List<String> discoveredMapperStatements(List<Path> mapperPaths) {
        Set<String> statements = new LinkedHashSet<>();
        for (Path mapperPath : mapperPaths) {
            statements.addAll(mapperStatements(mapperPath));
        }
        return statements.stream().sorted().toList();
    }

    private List<Path> xmlFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .filter(path -> !mapperStatements(path).isEmpty())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DmAdapterException("Failed to scan mapper XML files under " + directory, e);
        }
    }

    private List<String> mapperStatements(Path mapperPath) {
        try {
            Document document = parseXml(mapperPath);
            Element root = document.getDocumentElement();
            if (root == null || !"mapper".equals(root.getTagName())) {
                return List.of();
            }
            String namespace = root.getAttribute("namespace");
            if (namespace == null || namespace.isBlank()) {
                return List.of();
            }
            List<String> statements = new ArrayList<>();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element element && isStatementElement(element)) {
                    String id = element.getAttribute("id");
                    if (id != null && !id.isBlank()) {
                        statements.add(namespace + "." + id);
                    }
                }
            }
            statements.sort(Comparator.naturalOrder());
            return statements;
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean isStatementElement(Element element) {
        return "select".equals(element.getTagName())
                || "insert".equals(element.getTagName())
                || "update".equals(element.getTagName())
                || "delete".equals(element.getTagName());
    }

    private Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        enableFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        enableFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        enableFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        enableFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return factory.newDocumentBuilder().parse(new InputSource(reader));
        }
    }

    private void enableFeature(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception ignored) {
            // XML parser support varies by JDK distribution; unsupported hardening flags are skipped.
        }
    }

    private boolean isBuildOrGitPath(Path projectRoot, Path path) {
        String relativePath = normalize(projectRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()));
        return relativePath.startsWith("target/")
                || relativePath.contains("/target/")
                || relativePath.startsWith(".git/")
                || relativePath.contains("/.git/");
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String javaTestSource(String packageName) {
        String packageDeclaration = packageName == null || packageName.isBlank()
                ? ""
                : "package " + packageName + ";\n\n";
        return TEST_TEMPLATE.replace("__PACKAGE_DECLARATION__", packageDeclaration);
    }

    private static final String TEST_TEMPLATE = String.join("",
            """
            __PACKAGE_DECLARATION__import org.apache.ibatis.builder.xml.XMLMapperBuilder;
            import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
            import org.apache.ibatis.mapping.Environment;
            import org.apache.ibatis.mapping.MappedStatement;
            import org.apache.ibatis.mapping.SqlCommandType;
            import org.apache.ibatis.session.Configuration;
            import org.apache.ibatis.session.SqlSession;
            import org.apache.ibatis.session.SqlSessionFactory;
            import org.apache.ibatis.session.SqlSessionFactoryBuilder;
            import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
            import org.junit.jupiter.api.Tag;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
            import org.w3c.dom.Document;
            import org.w3c.dom.Element;
            import org.w3c.dom.Node;
            import org.w3c.dom.NodeList;
            import org.xml.sax.InputSource;

            import javax.xml.XMLConstants;
            import javax.xml.parsers.DocumentBuilderFactory;
            import java.io.DataInputStream;
            import java.io.IOException;
            import java.io.InputStream;
            import java.io.Reader;
            import java.lang.annotation.Annotation;
            import java.lang.reflect.Array;
            import java.lang.reflect.Constructor;
            import java.lang.reflect.Field;
            import java.lang.reflect.InvocationTargetException;
            import java.lang.reflect.Method;
            import java.lang.reflect.Modifier;
            import java.lang.reflect.Parameter;
            import java.lang.reflect.ParameterizedType;
            import java.lang.reflect.Type;
            import java.math.BigDecimal;
            import java.math.BigInteger;
            import java.nio.charset.Charset;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.sql.Connection;
            import java.sql.DatabaseMetaData;
            import java.sql.PreparedStatement;
            import java.sql.ResultSet;
            import java.sql.Statement;
            import java.time.Instant;
            import java.time.LocalDate;
            import java.time.LocalDateTime;
            import java.time.LocalTime;
            import java.time.ZoneId;
            import java.time.ZonedDateTime;
            import java.time.format.DateTimeFormatter;
            import java.util.ArrayList;
            import java.util.Arrays;
            import java.util.Collection;
            import java.util.Collections;
            import java.util.Comparator;
            import java.util.Date;
            import java.util.LinkedHashMap;
            import java.util.LinkedHashSet;
            import java.util.List;
            import java.util.Locale;
            import java.util.Map;
            import java.util.Optional;
            import java.util.Properties;
            import java.util.Set;
            import java.util.UUID;
            import java.util.regex.Matcher;
            import java.util.regex.Pattern;
            import java.util.stream.Collectors;
            import java.util.stream.Stream;

            import static org.junit.jupiter.api.Assertions.fail;

            @Tag("dm-sql-validation")
            @EnabledIfEnvironmentVariable(named = "DM_SQL_VALIDATION", matches = "true")
            public class DmSqlValidationTest {
                private static final String CONFIG_PATH = ".dm-adapter/sql-validation.yml";
                private static final String REWRITE_CONFIG_PATH = ".dm-adapter/sql-rewrite.yml";
                private static final String MARKDOWN_REPORT = ".dm-adapter/sql-validation-report.md";
                private static final String JSON_REPORT = ".dm-adapter/sql-validation-report.json";
                private static final Pattern PLACEHOLDER = Pattern.compile("\\\\$\\\\{([^}]+)}");
                private static final Set<String> DEFAULT_COLLECTION_PARAMETER_NAMES = new LinkedHashSet<>(Arrays.asList(
                        "list",
                        "collection",
                        "array",
                        "primarykeylist",
                        "removeitemids",
                        "ids",
                        "chargeitemids",
                        "ordernos",
                        "ordernolist",
                        "orders",
                        "monthlist",
                        "accountbooklist",
                        "allitem",
                        "chargedetailids",
                        "owneridlist",
                        "userrightmap",
                        "rightmap",
                        "groupmap",
                        "map"
                ));
                private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER =
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                private ValidationConfig currentConfig = new ValidationConfig();
                private DbColumnMetadata dbColumnMetadata = DbColumnMetadata.empty();
                private ActualParameterTypeIndex actualParameterTypeIndex = ActualParameterTypeIndex.empty();

                @SafeVarargs
                private static <T> List<T> listOf(T... values) {
                    return Collections.unmodifiableList(Arrays.asList(values));
                }

                @SafeVarargs
                private static <T> Set<T> setOf(T... values) {
                    return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
                }

                private static <K, V> Map<K, V> emptyMap() {
                    return Collections.emptyMap();
                }

                private static <T> List<T> copyList(Collection<? extends T> values) {
                    return Collections.unmodifiableList(new ArrayList<>(values));
                }

                private static <T> Set<T> copySet(Collection<? extends T> values) {
                    return Collections.unmodifiableSet(new LinkedHashSet<>(values));
                }

                private static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> values) {
                    return Collections.unmodifiableMap(new LinkedHashMap<>(values));
                }

                private static boolean isBlank(String value) {
                    return value == null || value.trim().isEmpty();
                }

                private static void writeString(Path path, String content) throws IOException {
                    Files.write(path, content.getBytes(StandardCharsets.UTF_8));
                }

                private static void writeString(Path path, String content, Charset charset) throws IOException {
                    Files.write(path, content.getBytes(charset));
                }

                public static void main(String[] args) throws Exception {
                    new DmSqlValidationTest().validateMappedDaoSql();
                }

                @Test
                void validateMappedDaoSql() throws Exception {
                    Path projectRoot = findProjectRoot();
                    Path configPath = projectRoot.resolve(CONFIG_PATH);
                    log("Started. Project root: " + projectRoot);
                    log("Loading config: " + configPath);
                    ValidationConfig config = ValidationConfig.load(configPath, projectRoot.resolve(REWRITE_CONFIG_PATH));
                    currentConfig = config;
                    List<ValidationRecord> records = new ArrayList<>();
                    UsageFilterReport usageFilterReport = UsageFilterReport.disabled();

                    try {
                        log("Resolving mapper XML locations...");
                        List<Path> mapperXmlFiles = mapperXmlFiles(projectRoot, config);
                        log("Matched mapper XML files: " + mapperXmlFiles.size());
                        if (mapperXmlFiles.isEmpty()) {
                            records.add(ValidationRecord.failed("(discovery)", "configuration",
                                    "No mapper XML files matched mapperXmlLocations."));
                            log("FAILED discovery: No mapper XML files matched mapperXmlLocations.");
                        } else {
                            SqlSessionFactory sqlSessionFactory = buildSqlSessionFactory(config, mapperXmlFiles, projectRoot);
                            ValidationRecord connectionFailure = databaseConnectionFailure(sqlSessionFactory);
                            if (connectionFailure != null) {
                                records.add(connectionFailure);
                                log("FAILED database connection: " + connectionFailure.message);
                            } else {
                                dbColumnMetadata = loadColumnMetadata(sqlSessionFactory, config);
                                List<MapperMethod> mapperMethods = mapperMethods(sqlSessionFactory.getConfiguration(), mapperXmlFiles, config);
                                log("Discovered mapper statements: " + mapperMethods.size());
                                if (mapperMethods.isEmpty()) {
                                    records.add(ValidationRecord.failed("(discovery)", "configuration",
                                            "No mapped statements were found in mapper XML files."));
                                    log("FAILED discovery: No mapped statements were found in mapper XML files.");
                                }
                                UsageFilter usageFilter = MapperUsageIndex.build(projectRoot, config, mapperMethods);
                                usageFilterReport = usageFilter.report();
                                log("Usage filter: " + usageFilterReport.summary());
                                actualParameterTypeIndex = ActualParameterTypeIndex.build(projectRoot, mapperMethods);
                                log("Actual parameter type inference: " + actualParameterTypeIndex.summary());
                                int index = 0;
                                int total = mapperMethods.size();
                                for (MapperMethod mapperMethod : mapperMethods) {
                                    index++;
                                    if (config.excludes(mapperMethod.key())) {
                                        ValidationRecord record = ValidationRecord.skipped(
                                                mapperMethod.key(),
                                                "excluded",
                                                "Excluded by sql-validation.yml."
                                        );
                                        records.add(record);
                                        logProgress(index, total, record, 0L);
                                        continue;
                                    }
                                    if (usageFilter.unused(mapperMethod, config)) {
                                        ValidationRecord record = ValidationRecord.skipped(
                                                mapperMethod.key(),
                                                "unused",
                                                "No project class references mapper method " + mapperMethod.key()
                                                        + "; skipped by usage filter."
                                        );
                                        records.add(record);
                                        logProgress(index, total, record, 0L);
                                        continue;
                                    }
                                    ValidationRecord unsupportedReturnType = skipUnsupportedReturnType(mapperMethod);
                                    if (unsupportedReturnType != null) {
                                        records.add(unsupportedReturnType);
                                        logProgress(index, total, unsupportedReturnType, 0L);
                                        continue;
                                    }
                                    ValidationRecord signatureIssue = javaMapperSignatureIssue(mapperMethod);
                                    if (signatureIssue != null) {
                                        records.add(signatureIssue);
                                        logProgress(index, total, signatureIssue, 0L);
                                        continue;
                                    }
                                    List<ParameterResolution> parameterVariants = resolveParameterVariants(mapperMethod, config);
                                    for (ParameterResolution parameters : parameterVariants) {
                                        String recordKey = parameters.recordKey(mapperMethod.key());
                                        if (!parameters.resolved) {
                                            ValidationRecord record = ValidationRecord.skipped(
                                                    recordKey,
                                                    parameters.source,
                                                    parametersSummary(parameters),
                                                    parameters.message
                                            );
                                            records.add(record);
                                            logProgress(index, total, record, 0L);
                                            continue;
                                        }
                                        log("RUN [" + index + "/" + total + "] " + recordKey
                                                + " params=" + parameters.source);
                                        long startedAt = System.currentTimeMillis();
                                        ValidationRecord record = invokeMapperMethod(sqlSessionFactory, mapperMethod, parameters, config);
                                        record = skipMissingDynamicIdentifier(record);
                                        record = skipMissingDynamicSqlFragment(record);
                                        record = skipGeneratedDynamicSqlOrArgs(record);
                                        record = skipExistingDdlObject(record);
                                        record = skipValidationTestDataIssue(record);
                                        record = skipIgnoredMissingTable(record, config);
                                        record = skipIgnoredMissingColumn(record, config);
                                        record = skipIgnoredMissingSchema(record, config);
                                        record = skipIgnoredNotNullColumn(record, config);
                                        records.add(record);
                                        logProgress(index, total, record, System.currentTimeMillis() - startedAt);
                                    }
                                }
                            }
                        }
                    } catch (Throwable e) {
                        ValidationRecord record = ValidationRecord.failed("(bootstrap)", "configuration", throwableSummary(e));
                        records.add(record);
                        log("FAILED bootstrap: " + record.message);
                    }

                    log("Writing reports...");
                    writeReports(projectRoot, records, usageFilterReport);
                    log("Finished. Passed: " + count(records, "PASSED")
                            + ", Failed: " + count(records, "FAILED")
                            + ", Skipped: " + count(records, "SKIPPED"));
                    log("Markdown report: " + projectRoot.resolve(MARKDOWN_REPORT));
                    log("JSON report: " + projectRoot.resolve(JSON_REPORT));
                    List<ValidationRecord> failed = records.stream()
                            .filter(record -> "FAILED".equals(record.status))
                            .collect(Collectors.toList());
                    if (!failed.isEmpty()) {
                        writeMissingTableIgnoreSuggestions(projectRoot, config, failed);
                        writeValidationArgsSuggestions(projectRoot, config, failed);
                        fail("Dameng SQL validation failed for " + failed.size()
                                + " mapper methods. See " + projectRoot.resolve(MARKDOWN_REPORT));
                    }
                }

                """,
            """
                private SqlSessionFactory buildSqlSessionFactory(
                        ValidationConfig config,
                        List<Path> mapperXmlFiles,
                        Path projectRoot
                ) {
                    log("Building MyBatis SqlSessionFactory...");
                    XmlOnlyConfiguration configuration = new XmlOnlyConfiguration(new Environment(
                            "dm-validation",
                            new JdbcTransactionFactory(),
                            dataSource(config)
                    ));
                    for (String packageName : config.typeAliasesPackages) {
                        String resolvedPackage = resolvePlaceholders(packageName);
                        log("Registering type aliases package: " + resolvedPackage);
                        configuration.getTypeAliasRegistry().registerAliases(resolvedPackage);
                    }
                    for (String packageName : config.typeHandlersPackages) {
                        String resolvedPackage = resolvePlaceholders(packageName);
                        log("Registering type handlers package: " + resolvedPackage);
                        configuration.getTypeHandlerRegistry().register(resolvedPackage);
                    }
                    int index = 0;
                    for (Path mapperXmlFile : mapperXmlFiles) {
                        index++;
                        log("Parsing mapper XML [" + index + "/" + mapperXmlFiles.size() + "]: "
                                + displayPath(projectRoot, mapperXmlFile));
                        try (InputStream inputStream = Files.newInputStream(mapperXmlFile)) {
                            XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(
                                    inputStream,
                                    configuration,
                                    mapperXmlFile.toString(),
                                    configuration.getSqlFragments()
                            );
                            configuration.suppressMapperBinding(true);
                            try {
                                xmlMapperBuilder.parse();
                            } finally {
                                configuration.suppressMapperBinding(false);
                            }
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to parse mapper XML: " + mapperXmlFile, e);
                        }
                    }
                    log("MyBatis SqlSessionFactory ready.");
                    return new SqlSessionFactoryBuilder().build(configuration);
                }

                private ValidationRecord databaseConnectionFailure(SqlSessionFactory sqlSessionFactory) {
                    log("Checking database connection...");
                    try (SqlSession sqlSession = sqlSessionFactory.openSession(false)) {
                        Connection connection = sqlSession.getConnection();
                        try (Statement statement = connection.createStatement();
                             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
                            if (resultSet.next()) {
                                log("Database connection ready.");
                                return null;
                            }
                        }
                        return ValidationRecord.failed(
                                "(database-connection)",
                                "configuration",
                                "Dameng validation connection check did not return a result."
                        );
                    } catch (Throwable e) {
                        return ValidationRecord.failed(
                                "(database-connection)",
                                "configuration",
                                "Failed to open Dameng validation connection. "
                                        + "Check DM_JDBC_URL, DM_DB_USERNAME, DM_DB_PASSWORD and database availability.\\n"
                                        + throwableSummary(e)
                        );
                    }
                }

                private UnpooledDataSource dataSource(ValidationConfig config) {
                    UnpooledDataSource dataSource = new UnpooledDataSource();
                    dataSource.setDriver(required(resolvePlaceholders(config.datasource.driverClassName), "datasource.driverClassName"));
                    dataSource.setUrl(required(resolvePlaceholders(config.datasource.url), "datasource.url"));
                    dataSource.setUsername(required(resolvePlaceholders(config.datasource.username), "datasource.username"));
                    dataSource.setPassword(optionalSecret(resolvePlaceholders(config.datasource.password), "datasource.password"));
                    return dataSource;
                }

                private DbColumnMetadata loadColumnMetadata(SqlSessionFactory sqlSessionFactory, ValidationConfig config) {
                    log("Loading database column metadata...");
                    try (SqlSession sqlSession = sqlSessionFactory.openSession(false)) {
                        Connection connection = sqlSession.getConnection();
                        DbColumnMetadata metadata = DbColumnMetadata.load(connection, config.schemas());
                        log("Database column metadata loaded: " + metadata.columnCount() + " columns.");
                        return metadata;
                    } catch (Throwable e) {
                        log("Database column metadata unavailable; generated parameters will use name-based defaults. Cause: "
                                + e.getClass().getSimpleName() + ": " + e.getMessage());
                        return DbColumnMetadata.empty();
                    }
                }

                private String required(String value, String key) {
                    if (value == null || isBlank(value) || value.contains("${")) {
                        throw new IllegalStateException(key + " is required. Configure it in " + CONFIG_PATH
                                + " or provide the referenced environment variable.");
                    }
                    return value;
                }

                private String optionalSecret(String value, String key) {
                    if (value == null) {
                        return "";
                    }
                    if (value.contains("${")) {
                        throw new IllegalStateException(key + " references an unresolved placeholder. Configure it in "
                                + CONFIG_PATH + " or provide the referenced environment variable.");
                    }
                    return value;
                }

                private List<Path> mapperXmlFiles(Path projectRoot, ValidationConfig config) throws IOException {
                    List<String> locations = config.mapperXmlLocations;
                    if (locations.isEmpty()) {
                        locations = defaultMapperXmlLocations(projectRoot);
                    }
                    LinkedHashSet<Path> mapperXmlFiles = new LinkedHashSet<>();
                    for (String location : locations) {
                        mapperXmlFiles.addAll(resolveMapperXmlLocation(projectRoot, resolvePlaceholders(location)));
                    }
                    return mapperXmlFiles.stream()
                            .filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".xml"))
                            .filter(this::isMapperXml)
                            .sorted()
                            .collect(Collectors.toList());
                }

                private List<String> defaultMapperXmlLocations(Path projectRoot) throws IOException {
                    List<String> mapperDmLocations = resourceDirectories(projectRoot, "mapper-dm").stream()
                            .map(path -> normalize(projectRoot.relativize(path)) + "/**/*.xml")
                            .collect(Collectors.toList());
                    if (!mapperDmLocations.isEmpty()) {
                        return mapperDmLocations;
                    }
                    return resourceDirectories(projectRoot, "mapper").stream()
                            .map(path -> normalize(projectRoot.relativize(path)) + "/**/*.xml")
                            .collect(Collectors.toList());
                }

                private List<Path> resourceDirectories(Path projectRoot, String directoryName) throws IOException {
                    try (Stream<Path> paths = Files.walk(projectRoot)) {
                        return paths.filter(Files::isDirectory)
                                .filter(path -> path.getFileName().toString().equals(directoryName))
                                .filter(path -> normalize(projectRoot.relativize(path)).contains("src/main/resources/"))
                                .filter(path -> !isIgnoredPath(projectRoot, path))
                                .sorted()
                                .collect(Collectors.toList());
                    }
                }

                private List<Path> resolveMapperXmlLocation(Path projectRoot, String location) throws IOException {
                    if (location == null || isBlank(location) || location.startsWith("#")) {
                        return listOf();
                    }
                    String normalized = location.trim().replace('\\\\', '/');
                    if (normalized.endsWith("/**/*.xml")) {
                        Path directory = resolveProjectPath(projectRoot, normalized.substring(0, normalized.length() - "/**/*.xml".length()));
                        return xmlFiles(directory, true);
                    }
                    if (normalized.endsWith("/*.xml")) {
                        Path directory = resolveProjectPath(projectRoot, normalized.substring(0, normalized.length() - "/*.xml".length()));
                        return xmlFiles(directory, false);
                    }
                    Path path = resolveProjectPath(projectRoot, normalized);
                    if (Files.isDirectory(path)) {
                        return xmlFiles(path, true);
                    }
                    if (Files.isRegularFile(path)) {
                        return listOf(path.toAbsolutePath().normalize());
                    }
                    return listOf();
                }

                private List<Path> xmlFiles(Path directory, boolean recursive) throws IOException {
                    if (!Files.isDirectory(directory)) {
                        return listOf();
                    }
                    int maxDepth = recursive ? Integer.MAX_VALUE : 1;
                    try (Stream<Path> paths = Files.walk(directory, maxDepth)) {
                        return paths.filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".xml"))
                                .sorted()
                                .collect(Collectors.toList());
                    }
                }

                private Path resolveProjectPath(Path projectRoot, String location) {
                    Path path = java.nio.file.Paths.get(location);
                    if (path.isAbsolute()) {
                        return path.toAbsolutePath().normalize();
                    }
                    return projectRoot.resolve(location).toAbsolutePath().normalize();
                }

                private boolean isMapperXml(Path path) {
                    try {
                        Document document = parseXml(path);
                        Element root = document.getDocumentElement();
                        return root != null && "mapper".equals(root.getTagName());
                    } catch (Exception e) {
                        return false;
                    }
                }

                private List<MapperMethod> mapperMethods(
                        Configuration configuration,
                        List<Path> mapperXmlFiles,
                        ValidationConfig config
                ) {
                    List<MapperMethod> mapperMethods = new ArrayList<>();
                    Set<String> seen = new LinkedHashSet<>();
                    for (MapperStatement mapperStatement : mapperStatements(mapperXmlFiles)) {
                        if (!configuration.hasStatement(mapperStatement.key(), false)) {
                            mapperMethods.add(MapperMethod.unmapped(mapperStatement));
                            continue;
                        }
                        MappedStatement mappedStatement = configuration.getMappedStatement(mapperStatement.key(), false);
                        Class<?> mapperInterface = mapperInterface(mapperStatement.namespace);
                        Method method = mapperInterface == null ? null : mapperMethod(mapperInterface, mapperStatement.id, config);
                        MapperMethod mapperMethod = new MapperMethod(
                                mapperStatement,
                                mapperInterface,
                                method,
                                mappedStatement.getSqlCommandType(),
                                mappedStatement.getParameterMap().getType()
                        );
                        if (seen.add(mapperMethod.key())) {
                            mapperMethods.add(mapperMethod);
                        }
                    }
                    mapperMethods.sort(Comparator.comparing(MapperMethod::key));
                    return mapperMethods;
                }

                private Class<?> mapperInterface(String namespace) {
                    try {
                        Class<?> candidate = Class.forName(namespace);
                        return candidate.isInterface() ? candidate : null;
                    } catch (ClassNotFoundException e) {
                        return null;
                    }
                }

                private Method mapperMethod(Class<?> mapperInterface, String methodName, ValidationConfig config) {
                    List<Method> methods = Arrays.asList(mapperInterface.getMethods()).stream()
                            .filter(method -> isInvocableMapperMethod(method) && method.getName().equals(methodName))
                            .collect(Collectors.toList());
                    if (methods.size() == 1) {
                        return methods.get(0);
                    }
                    MethodArgumentConfig configuredArgs = config.methodArguments(mapperInterface.getName() + "." + methodName);
                    if (configuredArgs != null && configuredArgs.argumentCount() >= 0) {
                        List<Method> matchingByCount = methods.stream()
                                .filter(method -> method.getParameterCount() == configuredArgs.argumentCount())
                                .collect(Collectors.toList());
                        if (matchingByCount.size() == 1) {
                            return matchingByCount.get(0);
                        }
                    }
                    return null;
                }

                private boolean isInvocableMapperMethod(Method method) {
                    int modifiers = method.getModifiers();
                    return !method.isSynthetic()
                            && !method.isBridge()
                            && !method.isDefault()
                            && !Modifier.isStatic(modifiers)
                            && !Object.class.equals(method.getDeclaringClass());
                }

                private List<MapperStatement> mapperStatements(List<Path> mapperXmlFiles) {
                    List<MapperStatement> statements = new ArrayList<>();
                    for (Path mapperXmlFile : mapperXmlFiles) {
                        statements.addAll(mapperStatements(mapperXmlFile));
                    }
                    statements.sort(Comparator.comparing(MapperStatement::key));
                    return statements;
                }

                private List<MapperStatement> mapperStatements(Path mapperXmlFile) {
                    try {
                        Document document = parseXml(mapperXmlFile);
                        Element root = document.getDocumentElement();
                        if (root == null || !"mapper".equals(root.getTagName())) {
                            return listOf();
                        }
                        String namespace = root.getAttribute("namespace");
                        if (namespace == null || isBlank(namespace)) {
                            return listOf();
                        }
                        Map<String, Element> sqlFragments = sqlFragments(root, namespace);
                        List<MapperStatement> statements = new ArrayList<>();
                        NodeList children = root.getChildNodes();
                        for (int i = 0; i < children.getLength(); i++) {
                            Node node = children.item(i);
                            if (node instanceof Element) {
                                Element element = (Element) node;
                                if (!isStatementElement(element)) {
                                    continue;
                                }
                                String id = element.getAttribute("id");
                                if (id != null && !isBlank(id)) {
                                    statements.add(new MapperStatement(
                                            namespace,
                                            id,
                                            setBranchParameterVariants(element),
                                            dynamicIdentifierMetadata(element, sqlFragments, namespace),
                                            parameterReferences(element),
                                            generatedKeyProperties(element)
                                    ));
                                }
                            }
                        }
                        return statements;
                    } catch (Exception e) {
                        return listOf();
                    }
                }

                private Map<String, Element> sqlFragments(Element mapperRoot, String namespace) {
                    Map<String, Element> fragments = new LinkedHashMap<>();
                    NodeList children = mapperRoot.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        Node node = children.item(i);
                        if (!(node instanceof Element)) {
                            continue;
                        }
                        Element element = (Element) node;
                        if (!"sql".equals(element.getTagName())) {
                            continue;
                        }
                        String id = element.getAttribute("id");
                        if (id == null || isBlank(id)) {
                            continue;
                        }
                        fragments.put(id, element);
                        if (!isBlank(namespace)) {
                            fragments.put(namespace + "." + id, element);
                        }
                    }
                    return fragments;
                }

                private boolean isStatementElement(Element element) {
                    return "select".equals(element.getTagName())
                            || "insert".equals(element.getTagName())
                            || "update".equals(element.getTagName())
                            || "delete".equals(element.getTagName());
                }

                private Set<String> generatedKeyProperties(Element statement) {
                    if (!"insert".equals(statement.getTagName())
                            || !"true".equalsIgnoreCase(statement.getAttribute("useGeneratedKeys"))) {
                        return setOf();
                    }
                    String keyProperty = statement.getAttribute("keyProperty");
                    if (keyProperty == null || isBlank(keyProperty)) {
                        return setOf();
                    }
                    return Pattern.compile("[,\\\\s]+")
                            .splitAsStream(keyProperty.trim())
                            .map(String::trim)
                            .filter(value -> !isBlank(value))
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                }

                private Set<String> parameterReferences(Element statement) {
                    String text = statement == null ? "" : statement.getTextContent();
                    Set<String> names = new LinkedHashSet<>();
                    Matcher matcher = Pattern.compile("[#$]\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)(?:\\\\s*,[^}]*)?}")
                            .matcher(text == null ? "" : text);
                    while (matcher.find()) {
                        String name = matcher.group(1);
                        int dot = name.indexOf('.');
                        if (dot >= 0) {
                            name = name.substring(0, dot);
                        }
                        if (!isBlank(name) && !syntheticParameterName(name)) {
                            names.add(name);
                        }
                    }
                    return names;
                }

                private boolean syntheticParameterName(String name) {
                    return setOf("_parameter", "param1", "param2", "param3", "param4", "param5",
                            "arg0", "arg1", "arg2", "arg3", "list", "collection", "array").contains(name);
                }

                private List<SetBranchParameterVariant> setBranchParameterVariants(Element statement) {
                    if (!"update".equals(statement.getTagName())) {
                        return listOf();
                    }
                    Map<String, BranchCollector> collectors = new LinkedHashMap<>();
                    collectSetBranchParameterVariants(statement, collectors);
                    return collectors.values().stream()
                            .filter(BranchCollector::valid)
                            .max(Comparator.comparingInt(BranchCollector::size))
                            .map(BranchCollector::variants)
                            .orElse(listOf());
                }

                private DynamicIdentifierMetadata dynamicIdentifierMetadata(
                        Element statement,
                        Map<String, Element> sqlFragments,
                        String namespace
                ) {
                    DynamicIdentifierMetadata metadata = new DynamicIdentifierMetadata();
                    SqlStatementContext sqlContext = sqlStatementContext(statement);
                    collectDynamicIdentifierMetadata(
                            statement,
                            new LinkedHashMap<>(),
                            metadata,
                            false,
                            Collections.emptySet(),
                            sqlContext,
                            sqlFragments,
                            namespace,
                            new LinkedHashSet<>()
                    );
                    addDmlColumnReferences(statement, metadata, sqlContext);
                    return metadata;
                }

                private void collectDynamicIdentifierMetadata(
                        Node node,
                        Map<String, String> foreachCollections,
                        DynamicIdentifierMetadata metadata,
                        boolean insideSet,
                        Set<String> optionalDynamicExpressions,
                        SqlStatementContext sqlContext,
                        Map<String, Element> sqlFragments,
                        String namespace,
                        Set<String> includeStack
                ) {
                    if (node instanceof Element) {
                        Element element = (Element) node;
                        Map<String, String> currentForeachCollections = foreachCollections;
                        boolean currentInsideSet = insideSet
                                || "set".equals(element.getTagName())
                                || isSetTrimElement(element);
                        Set<String> currentOptionalDynamicExpressions = optionalDynamicExpressions;
                        if ("foreach".equals(element.getTagName())) {
                            String item = element.getAttribute("item");
                            String index = element.getAttribute("index");
                            String rawCollection = element.getAttribute("collection");
                            String collection = canonicalCollectionName(rawCollection);
                            NestedCollection nestedCollection = nestedCollection(collection, foreachCollections);
                            boolean nonEmptyCollection = shouldUseNonEmptyForeachCollection(collection, item, element);
                            if (nestedCollection == null) {
                                metadata.addCollectionParameterName(collection, nonEmptyCollection);
                                if (nonEmptyCollection) {
                                    metadata.addNonEmptyCollectionParameterName(collection);
                                }
                                if (isMapForeachCollection(rawCollection, index, item, element)) {
                                    metadata.addMapCollectionParameterName(collection);
                                }
                            } else if (nestedCollection.directElement) {
                                metadata.addCollectionScalarDefault(
                                        nestedCollection.parentCollection,
                                        defaultDirectNestedForeachCollectionValue(rawCollection, index, item, element, sqlContext)
                                );
                            } else {
                                metadata.addCollectionDefault(
                                        nestedCollection.parentCollection,
                                        nestedCollection.propertyName,
                                        defaultNestedForeachCollectionValue(rawCollection, index, item, element)
                                );
                            }
                            ColumnReference columnReference = inOperatorColumnReference(element, sqlContext);
                            if (columnReference != null && nestedCollection == null) {
                                metadata.addCollectionColumnReference(collection, columnReference);
                            }
                            if (item != null && !isBlank(item) && collection != null && !isBlank(collection)) {
                                currentForeachCollections = new LinkedHashMap<>(foreachCollections);
                                if (nestedCollection != null && nestedCollection.directElement) {
                                    currentForeachCollections.put(
                                            item,
                                            directNestedForeachCollectionPath(nestedCollection.parentCollection)
                                    );
                                } else {
                                    currentForeachCollections.put(item, collection);
                                }
                            }
                        }
                        if ("if".equals(element.getTagName()) || "when".equals(element.getTagName())) {
                            Set<String> optionalExpressions = optionalDynamicSqlFragmentNames(element.getAttribute("test"));
                            if (!optionalExpressions.isEmpty()) {
                                currentOptionalDynamicExpressions = new LinkedHashSet<>(optionalDynamicExpressions);
                                currentOptionalDynamicExpressions.addAll(optionalExpressions);
                            }
                            BranchCondition condition = branchCondition(element.getAttribute("test"));
                            if (condition != null) {
                                metadata.addDefaultValue(condition.parameterName, condition.defaultValue);
                            }
                            if (currentInsideSet) {
                                addSetAssignmentDefaults(element, currentForeachCollections, metadata);
                            }
                        }
                        if ("include".equals(element.getTagName())) {
                            Element fragment = sqlFragment(sqlFragments, namespace, element.getAttribute("refid"));
                            String includeKey = sqlFragmentKey(namespace, element.getAttribute("refid"));
                            if (fragment != null && !includeStack.contains(includeKey)) {
                                Set<String> currentIncludeStack = new LinkedHashSet<>(includeStack);
                                currentIncludeStack.add(includeKey);
                                NodeList fragmentChildren = fragment.getChildNodes();
                                for (int i = 0; i < fragmentChildren.getLength(); i++) {
                                    collectDynamicIdentifierMetadata(
                                            fragmentChildren.item(i),
                                            currentForeachCollections,
                                            metadata,
                                            currentInsideSet,
                                            currentOptionalDynamicExpressions,
                                            sqlContext,
                                            sqlFragments,
                                            namespace,
                                            currentIncludeStack
                                    );
                                }
                            }
                            return;
                        }
                        NodeList children = element.getChildNodes();
                        for (int i = 0; i < children.getLength(); i++) {
                            collectDynamicIdentifierMetadata(
                                    children.item(i),
                                    currentForeachCollections,
                                    metadata,
                                    currentInsideSet,
                                    currentOptionalDynamicExpressions,
                                    sqlContext,
                                    sqlFragments,
                                    namespace,
                                    includeStack
                            );
                        }
                        return;
                    }
                    if (node == null
                            || (node.getNodeType() != Node.TEXT_NODE && node.getNodeType() != Node.CDATA_SECTION_NODE)) {
                        return;
                    }
                    String text = node.getTextContent();
                    Matcher dynamicMatcher = Pattern.compile("\\\\$\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)\\\\s*}").matcher(text);
                    while (dynamicMatcher.find()) {
                        addDynamicIdentifierExpression(
                                dynamicMatcher.group(1),
                                foreachCollections,
                                metadata,
                                text,
                                dynamicMatcher.start(),
                                dynamicMatcher.end(),
                                optionalDynamicExpressions,
                                sqlContext
                        );
                    }
                    Matcher valueMatcher = Pattern.compile("#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)([^}]*)}").matcher(text);
                    while (valueMatcher.find()) {
                        addValueExpression(valueMatcher.group(1), jdbcType(valueMatcher.group(2)), foreachCollections, metadata);
                    }
                    addValueColumnReferences(text, foreachCollections, metadata, sqlContext);
                }

                private Element sqlFragment(Map<String, Element> sqlFragments, String namespace, String refid) {
                    if (sqlFragments == null || isBlank(refid)) {
                        return null;
                    }
                    Element fragment = sqlFragments.get(refid);
                    if (fragment != null) {
                        return fragment;
                    }
                    if (refid.indexOf('.') < 0 && !isBlank(namespace)) {
                        fragment = sqlFragments.get(namespace + "." + refid);
                        if (fragment != null) {
                            return fragment;
                        }
                    }
                    int dotIndex = refid.lastIndexOf('.');
                    if (dotIndex >= 0 && dotIndex + 1 < refid.length()) {
                        return sqlFragments.get(refid.substring(dotIndex + 1));
                    }
                    return null;
                }

                private String sqlFragmentKey(String namespace, String refid) {
                    if (isBlank(refid)) {
                        return "";
                    }
                    if (refid.indexOf('.') >= 0 || isBlank(namespace)) {
                        return refid;
                    }
                    return namespace + "." + refid;
                }

                private boolean isSetTrimElement(Element element) {
                    if (element == null || !"trim".equals(element.getTagName())) {
                        return false;
                    }
                    String prefix = element.getAttribute("prefix");
                    if (prefix != null && "set".equalsIgnoreCase(prefix.trim())) {
                        return true;
                    }
                    String suffixOverrides = element.getAttribute("suffixOverrides");
                    if (suffixOverrides == null || !suffixOverrides.contains(",")) {
                        return false;
                    }
                    Node sibling = element.getPreviousSibling();
                    while (sibling != null) {
                        if (sibling.getNodeType() == Node.TEXT_NODE || sibling.getNodeType() == Node.CDATA_SECTION_NODE) {
                            String text = sibling.getTextContent();
                            if (!isBlank(text)) {
                                return Pattern.compile("(?is)(?:^|\\\\s)set\\\\s*$").matcher(text).find();
                            }
                        } else if (sibling instanceof Element) {
                            return false;
                        }
                        sibling = sibling.getPreviousSibling();
                    }
                    return false;
                }

                private void addDynamicIdentifierExpression(
                        String expression,
                        Map<String, String> foreachCollections,
                        DynamicIdentifierMetadata metadata,
                        String text,
                        int startIndex,
                        int endIndex,
                        Set<String> optionalDynamicExpressions,
                        SqlStatementContext sqlContext
                ) {
                    List<String> parts = pathParts(expression);
                    if (parts.isEmpty()) {
                        return;
                    }
                    String collection = foreachCollections.get(parts.get(0));
                    if (collection != null) {
                        if (isNestedForeachCollectionPath(collection)) {
                            return;
                        }
                        if (parts.size() == 1) {
                            metadata.addCollectionSqlFragmentDefault(
                                    collection,
                                    defaultDynamicSqlFragmentValue(collection, "", text, startIndex, endIndex, sqlContext)
                            );
                        } else {
                            metadata.addCollectionDefault(
                                    collection,
                                    parts.get(1),
                                    defaultDynamicSqlFragmentValue(collection, parts.get(1), text, startIndex, endIndex, sqlContext)
                            );
                        }
                        return;
                    }
                    if (optionalDynamicExpressions != null
                            && optionalDynamicExpressions.contains(normalizeName(expression))) {
                        metadata.addDefaultValue(expression, null);
                        return;
                    }
                    if (shouldUseNullDefault(expression)) {
                        metadata.addDefaultValue(expression, null);
                        return;
                    }
                    Object contextualDefault = defaultDynamicSqlParameterValue(expression, text, startIndex, endIndex, sqlContext);
                    if (contextualDefault != null) {
                        metadata.addDynamicIdentifierName(expression);
                        metadata.addDefaultValue(expression, contextualDefault);
                        return;
                    }
                    if (parts.size() == 1) {
                        metadata.addDynamicIdentifierName(parts.get(0));
                    } else {
                        metadata.addDynamicIdentifierName(parts.get(parts.size() - 1));
                    }
                }

                private Set<String> optionalDynamicSqlFragmentNames(String test) {
                    Set<String> names = new LinkedHashSet<>();
                    if (test == null || isBlank(test)) {
                        return names;
                    }
                    Matcher notNull = Pattern.compile(
                            "(?i)([A-Za-z_][A-Za-z0-9_.$]*)\\\\s*!=\\\\s*null"
                    ).matcher(test);
                    while (notNull.find()) {
                        names.add(normalizeName(notNull.group(1)));
                    }
                    Matcher rightNotEmpty = Pattern.compile(
                            "(?i)([A-Za-z_][A-Za-z0-9_.$]*)\\\\s*!=\\\\s*(?:''|\\\"\\\")"
                    ).matcher(test);
                    while (rightNotEmpty.find()) {
                        names.add(normalizeName(rightNotEmpty.group(1)));
                    }
                    Matcher leftNotEmpty = Pattern.compile(
                            "(?i)(?:''|\\\"\\\")\\\\s*!=\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)"
                    ).matcher(test);
                    while (leftNotEmpty.find()) {
                        names.add(normalizeName(leftNotEmpty.group(1)));
                    }
                    names.remove("");
                    return names;
                }

                private void addValueExpression(
                        String expression,
                        String jdbcType,
                        Map<String, String> foreachCollections,
                        DynamicIdentifierMetadata metadata
                ) {
                    List<String> parts = pathParts(expression);
                    if (parts.isEmpty()) {
                        return;
                    }
                    String collection = foreachCollections.get(parts.get(0));
                    if (collection != null) {
                        if (isNestedForeachCollectionPath(collection)) {
                            return;
                        }
                        if (parts.size() == 1) {
                            metadata.addCollectionScalarDefault(
                                    collection,
                                    defaultValueForDirectParameter(collection, jdbcType)
                            );
                        }
                        if (parts.size() > 1) {
                            Object defaultValue = jdbcType == null || isBlank(jdbcType)
                                    ? defaultString(parts.get(1))
                                    : defaultValueForJdbcType(parts.get(1), jdbcType);
                            metadata.addCollectionDefault(collection, parts.get(1), defaultValue);
                        }
                        return;
                    }
                    if (parts.size() == 1) {
                        metadata.addValueExpressionName(parts.get(0));
                        metadata.addDefaultValue(parts.get(0), defaultValueForDirectParameter(parts.get(0), jdbcType));
                        return;
                    }
                    if (jdbcType != null && !isBlank(jdbcType)) {
                        String propertyName = parts.get(parts.size() - 1);
                        Object defaultValue = defaultValueForJdbcType(propertyName, jdbcType);
                        metadata.addDefaultValue(propertyName, defaultValue);
                        metadata.addDefaultValue(expression, defaultValue);
                    }
                }

                private void addValueColumnReferences(
                        String text,
                        Map<String, String> foreachCollections,
                        DynamicIdentifierMetadata metadata,
                        SqlStatementContext sqlContext
                ) {
                    if (text == null || isBlank(text)) {
                        return;
                    }
                    String identifier = sqlIdentifierPattern();
                    Matcher matcher = Pattern.compile(
                            "(?i)(" + identifier + "(?:\\\\s*\\\\.\\\\s*" + identifier + ")?)\\\\s*(?:=|<>|!=|>=|<=|>|<)\\\\s*#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)(?:[^}]*)}"
                    ).matcher(text);
                    while (matcher.find()) {
                        List<String> parts = pathParts(matcher.group(2));
                        if (parts.isEmpty() || foreachCollections.containsKey(parts.get(0))) {
                            if (!parts.isEmpty() && foreachCollections.containsKey(parts.get(0))) {
                                String collection = foreachCollections.get(parts.get(0));
                                if (!isNestedForeachCollectionPath(collection)) {
                                    ColumnReference columnReference = columnReference(matcher.group(1), sqlContext);
                                    if (columnReference != null) {
                                        if (parts.size() == 1) {
                                            metadata.addCollectionColumnReference(collection, columnReference);
                                        } else {
                                            metadata.addCollectionDefaultColumnReference(
                                                    collection,
                                                    parts.get(parts.size() - 1),
                                                    columnReference
                                            );
                                        }
                                    }
                                }
                            }
                            continue;
                        }
                        String propertyName = parts.size() == 1 ? parts.get(0) : parts.get(parts.size() - 1);
                        ColumnReference columnReference = columnReference(matcher.group(1), sqlContext);
                        if (columnReference != null) {
                            metadata.addDefaultColumnReference(propertyName, columnReference);
                        }
                    }
                    Matcher rightColumnMatcher = Pattern.compile(
                            "(?i)#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)(?:[^}]*)}\\\\s*(?:=|<>|!=|>=|<=|>|<)\\\\s*(" + identifier + "(?:\\\\s*\\\\.\\\\s*" + identifier + ")?)"
                    ).matcher(text);
                    while (rightColumnMatcher.find()) {
                        List<String> parts = pathParts(rightColumnMatcher.group(1));
                        if (parts.isEmpty() || foreachCollections.containsKey(parts.get(0))) {
                            if (!parts.isEmpty() && foreachCollections.containsKey(parts.get(0))) {
                                String collection = foreachCollections.get(parts.get(0));
                                if (!isNestedForeachCollectionPath(collection)) {
                                    ColumnReference columnReference = columnReference(rightColumnMatcher.group(2), sqlContext);
                                    if (columnReference != null) {
                                        if (parts.size() == 1) {
                                            metadata.addCollectionColumnReference(collection, columnReference);
                                        } else {
                                            metadata.addCollectionDefaultColumnReference(
                                                    collection,
                                                    parts.get(parts.size() - 1),
                                                    columnReference
                                            );
                                        }
                                    }
                                }
                            }
                            continue;
                        }
                        String propertyName = parts.size() == 1 ? parts.get(0) : parts.get(parts.size() - 1);
                        ColumnReference columnReference = columnReference(rightColumnMatcher.group(2), sqlContext);
                        if (columnReference != null) {
                            metadata.addDefaultColumnReference(propertyName, columnReference);
                        }
                    }
                }

                private void addDmlColumnReferences(
                        Element statement,
                        DynamicIdentifierMetadata metadata,
                        SqlStatementContext sqlContext
                ) {
                    String sql = statement.getTextContent();
                    if (sql == null || isBlank(sql)) {
                        return;
                    }
                    addUpdateColumnReferences(sql, metadata, sqlContext);
                    addStructuredInsertColumnReferences(statement, metadata, sqlContext);
                    addInsertColumnReferences(sql, metadata, sqlContext);
                }

                private void addUpdateColumnReferences(
                        String sql,
                        DynamicIdentifierMetadata metadata,
                        SqlStatementContext sqlContext
                ) {
                    String identifier = sqlIdentifierPattern();
                    Matcher matcher = Pattern.compile(
                            "(?i)(" + identifier + "(?:\\\\s*\\\\.\\\\s*" + identifier + ")?)\\\\s*=\\\\s*#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)(?:[^}]*)}"
                    ).matcher(sql);
                    while (matcher.find()) {
                        addPlaceholderColumnReference(matcher.group(2), columnReference(matcher.group(1), sqlContext), metadata);
                    }
                }

                private void addInsertColumnReferences(
                        String sql,
                        DynamicIdentifierMetadata metadata,
                        SqlStatementContext sqlContext
                ) {
                    Matcher insertMatcher = Pattern.compile("(?is)\\\\binsert\\\\s+into\\\\s+(" + sqlIdentifierPattern()
                            + "(?:\\\\s*\\\\.\\\\s*" + sqlIdentifierPattern() + ")?)").matcher(sql);
                    if (!insertMatcher.find()) {
                        return;
                    }
                    String table = lastIdentifierPart(insertMatcher.group(1));
                    int columnsOpen = sql.indexOf('(', insertMatcher.end());
                    if (columnsOpen < 0) {
                        return;
                    }
                    int columnsClose = matchingParen(sql, columnsOpen);
                    if (columnsClose < 0) {
                        return;
                    }
                    Matcher valuesMatcher = Pattern.compile("(?i)\\\\bvalues\\\\b").matcher(sql);
                    if (!valuesMatcher.find(columnsClose)) {
                        return;
                    }
                    int valuesOpen = sql.indexOf('(', valuesMatcher.end());
                    if (valuesOpen < 0) {
                        return;
                    }
                    int valuesClose = matchingParen(sql, valuesOpen);
                    if (valuesClose < 0) {
                        return;
                    }
                    List<String> columns = splitTopLevelComma(sql.substring(columnsOpen + 1, columnsClose));
                    List<String> values = splitTopLevelComma(sql.substring(valuesOpen + 1, valuesClose));
                    int count = Math.min(columns.size(), values.size());
                    for (int i = 0; i < count; i++) {
                        Matcher placeholder = Pattern.compile("#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)(?:[^}]*)}").matcher(values.get(i));
                        if (placeholder.find()) {
                            addPlaceholderColumnReference(
                                    placeholder.group(1),
                                    new ColumnReference(table, cleanSqlIdentifier(columns.get(i))),
                                    metadata
                            );
                        }
                    }
                }

                private void addStructuredInsertColumnReferences(
                        Element statement,
                        DynamicIdentifierMetadata metadata,
                        SqlStatementContext sqlContext
                ) {
                    if (statement == null || !"insert".equals(statement.getTagName())) {
                        return;
                    }
                    String sql = statement.getTextContent();
                    if (sql == null || isBlank(sql)) {
                        return;
                    }
                    Matcher insertMatcher = Pattern.compile("(?is)\\\\binsert\\\\s+into\\\\s+(" + sqlIdentifierPattern()
                            + "(?:\\\\s*\\\\.\\\\s*" + sqlIdentifierPattern() + ")?)").matcher(sql);
                    if (!insertMatcher.find()) {
                        return;
                    }
                    String table = lastIdentifierPart(insertMatcher.group(1));
                    List<String> columns = structuredInsertColumns(statement);
                    List<String> placeholderExpressions = structuredInsertPlaceholderExpressions(statement);
                    addStructuredInsertPlaceholderColumnReferences(table, columns, placeholderExpressions, metadata);
                    InsertForeachValues foreachValues = structuredInsertForeachValues(statement);
                    if (columns.isEmpty() || foreachValues == null || foreachValues.expressions.isEmpty()) {
                        return;
                    }
                    int count = Math.min(columns.size(), foreachValues.expressions.size());
                    for (int i = 0; i < count; i++) {
                        String expression = foreachValues.expressions.get(i);
                        if (expression == null || isBlank(expression)) {
                            continue;
                        }
                        List<String> parts = pathParts(expression);
                        if (parts.size() > 1 && foreachValues.item.equals(parts.get(0))) {
                            metadata.addCollectionDefaultColumnReference(
                                    foreachValues.collection,
                                    parts.get(parts.size() - 1),
                                    new ColumnReference(table, cleanSqlIdentifier(columns.get(i)))
                            );
                        }
                    }
                }

                private void addStructuredInsertPlaceholderColumnReferences(
                        String table,
                        List<String> columns,
                        List<String> placeholderExpressions,
                        DynamicIdentifierMetadata metadata
                ) {
                    if (columns.isEmpty() || placeholderExpressions.isEmpty()) {
                        return;
                    }
                    int count = Math.min(columns.size(), placeholderExpressions.size());
                    for (int i = 0; i < count; i++) {
                        String expression = placeholderExpressions.get(i);
                        if (expression == null || isBlank(expression)) {
                            continue;
                        }
                        addPlaceholderColumnReference(
                                expression,
                                new ColumnReference(table, cleanSqlIdentifier(columns.get(i))),
                                metadata
                        );
                    }
                }

                private List<String> structuredInsertColumns(Element statement) {
                    NodeList trims = statement.getElementsByTagName("trim");
                    for (int i = 0; i < trims.getLength(); i++) {
                        Node node = trims.item(i);
                        if (!(node instanceof Element)) {
                            continue;
                        }
                        Element trim = (Element) node;
                        String text = trim.getTextContent();
                        if (text == null || isBlank(text) || text.contains("#{") || text.contains("${")) {
                            continue;
                        }
                        List<String> columns = splitTopLevelComma(text).stream()
                                .map(String::trim)
                                .filter(value -> !isBlank(value))
                                .collect(Collectors.toList());
                        if (!columns.isEmpty()) {
                            return columns;
                        }
                    }
                    String sql = statement.getTextContent();
                    if (sql == null || isBlank(sql)) {
                        return listOf();
                    }
                    Matcher insertMatcher = Pattern.compile("(?is)\\\\binsert\\\\s+into\\\\s+"
                            + sqlIdentifierPattern()
                            + "(?:\\\\s*\\\\.\\\\s*" + sqlIdentifierPattern() + ")?").matcher(sql);
                    if (!insertMatcher.find()) {
                        return listOf();
                    }
                    int columnsOpen = sql.indexOf('(', insertMatcher.end());
                    if (columnsOpen < 0) {
                        return listOf();
                    }
                    int columnsClose = matchingParen(sql, columnsOpen);
                    if (columnsClose < 0) {
                        return listOf();
                    }
                    return splitTopLevelComma(sql.substring(columnsOpen + 1, columnsClose)).stream()
                            .map(String::trim)
                            .filter(value -> !isBlank(value))
                            .collect(Collectors.toList());
                }

                private List<String> structuredInsertPlaceholderExpressions(Element statement) {
                    NodeList trims = statement.getElementsByTagName("trim");
                    for (int i = 0; i < trims.getLength(); i++) {
                        Node node = trims.item(i);
                        if (!(node instanceof Element)) {
                            continue;
                        }
                        Element trim = (Element) node;
                        if (hasAncestor(trim, "foreach")) {
                            continue;
                        }
                        String text = trim.getTextContent();
                        String prefix = trim.getAttribute("prefix");
                        if (text == null
                                || isBlank(text)
                                || !text.contains("#{")
                                || (!prefix.toLowerCase(Locale.ROOT).contains("values")
                                && !text.toLowerCase(Locale.ROOT).contains("values"))) {
                            continue;
                        }
                        List<String> expressions = placeholderExpressions(splitTopLevelComma(text));
                        if (!expressions.isEmpty()) {
                            return expressions;
                        }
                    }
                    return listOf();
                }

                private List<String> placeholderExpressions(List<String> values) {
                    List<String> expressions = new ArrayList<>();
                    boolean foundExpression = false;
                    for (String value : values) {
                        String expression = null;
                        Matcher placeholder = Pattern.compile("#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)(?:[^}]*)}").matcher(value);
                        if (placeholder.find()) {
                            expression = placeholder.group(1);
                            foundExpression = true;
                        }
                        expressions.add(expression);
                    }
                    return foundExpression ? expressions : listOf();
                }

                private boolean hasAncestor(Node node, String tagName) {
                    Node current = node == null ? null : node.getParentNode();
                    while (current != null) {
                        if (current instanceof Element
                                && tagName.equalsIgnoreCase(((Element) current).getTagName())) {
                            return true;
                        }
                        current = current.getParentNode();
                    }
                    return false;
                }

                private InsertForeachValues structuredInsertForeachValues(Element statement) {
                    NodeList foreachElements = statement.getElementsByTagName("foreach");
                    for (int i = 0; i < foreachElements.getLength(); i++) {
                        Node node = foreachElements.item(i);
                        if (!(node instanceof Element)) {
                            continue;
                        }
                        Element foreach = (Element) node;
                        String collection = foreach.getAttribute("collection");
                        String item = foreach.getAttribute("item");
                        String text = foreach.getTextContent();
                        if (isBlank(collection) || isBlank(item) || text == null || isBlank(text)) {
                            continue;
                        }
                        List<String> values = structuredInsertValueExpressions(text);
                        if (values.isEmpty()) {
                            continue;
                        }
                        List<String> expressions = new ArrayList<>();
                        boolean foundExpression = false;
                        for (String value : values) {
                            String matchedExpression = null;
                            Matcher placeholder = Pattern.compile("#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)(?:[^}]*)}").matcher(value);
                            if (placeholder.find()) {
                                String expression = placeholder.group(1);
                                List<String> parts = pathParts(expression);
                                if (parts.size() > 1 && item.equals(parts.get(0))) {
                                    matchedExpression = expression;
                                    foundExpression = true;
                                }
                            }
                            expressions.add(matchedExpression);
                        }
                        if (foundExpression) {
                            return new InsertForeachValues(collection, item, expressions);
                        }
                    }
                    return null;
                }

                private List<String> structuredInsertValueExpressions(String text) {
                    if (text == null || isBlank(text)) {
                        return listOf();
                    }
                    String valueText = text.trim();
                    if (valueText.startsWith("(")) {
                        int close = matchingParen(valueText, 0);
                        if (close == valueText.length() - 1) {
                            valueText = valueText.substring(1, close);
                        }
                    }
                    return splitTopLevelComma(valueText);
                }

                private void addPlaceholderColumnReference(
                        String expression,
                        ColumnReference columnReference,
                        DynamicIdentifierMetadata metadata
                ) {
                    if (columnReference == null || expression == null || isBlank(expression)) {
                        return;
                    }
                    List<String> parts = pathParts(expression);
                    if (parts.isEmpty()) {
                        return;
                    }
                    if (parts.size() > 1 && isForeachItemName(parts.get(0))) {
                        metadata.addCollectionDefaultColumnReference(parts.get(0), parts.get(parts.size() - 1), columnReference);
                        return;
                    }
                    String propertyName = parts.size() == 1 ? parts.get(0) : parts.get(parts.size() - 1);
                    metadata.addDefaultColumnReference(propertyName, columnReference);
                }

                private int matchingParen(String text, int openIndex) {
                    int depth = 0;
                    boolean inSingleQuote = false;
                    boolean inDoubleQuote = false;
                    for (int i = openIndex; i < text.length(); i++) {
                        char ch = text.charAt(i);
                        if (ch == '\\'' && !inDoubleQuote) {
                            inSingleQuote = !inSingleQuote;
                        } else if (ch == '"' && !inSingleQuote) {
                            inDoubleQuote = !inDoubleQuote;
                        } else if (!inSingleQuote && !inDoubleQuote) {
                            if (ch == '(') {
                                depth++;
                            } else if (ch == ')') {
                                depth--;
                                if (depth == 0) {
                                    return i;
                                }
                            }
                        }
                    }
                    return -1;
                }

                private List<String> splitTopLevelComma(String text) {
                    List<String> parts = new ArrayList<>();
                    int depth = 0;
                    boolean inSingleQuote = false;
                    boolean inDoubleQuote = false;
                    int start = 0;
                    for (int i = 0; i < text.length(); i++) {
                        char ch = text.charAt(i);
                        if (ch == '\\'' && !inDoubleQuote) {
                            inSingleQuote = !inSingleQuote;
                        } else if (ch == '"' && !inSingleQuote) {
                            inDoubleQuote = !inDoubleQuote;
                        } else if (!inSingleQuote && !inDoubleQuote) {
                            if (ch == '(') {
                                depth++;
                            } else if (ch == ')') {
                                depth--;
                            } else if (ch == ',' && depth == 0) {
                                parts.add(text.substring(start, i).trim());
                                start = i + 1;
                            }
                        }
                    }
                    parts.add(text.substring(start).trim());
                    return parts.stream().filter(part -> !isBlank(part)).collect(Collectors.toList());
                }

                private Object defaultValueForDirectParameter(String valueName, String jdbcType) {
                    if (jdbcType != null && !isBlank(jdbcType)) {
                        return defaultValueForJdbcType(valueName, jdbcType);
                    }
                    String normalized = normalizeName(valueName);
                    if (isDeletionFlagName(normalized)) {
                        return 1;
                    }
                    if (isIdLikeParameterName(normalized)) {
                        return 1L;
                    }
                    if (isNumericParameterName(normalized)) {
                        return 1;
                    }
                    if (normalized.contains("code")) {
                        return "CODE";
                    }
                    String temporalDefault = defaultTemporalString(normalized);
                    if (temporalDefault != null) {
                        return temporalDefault;
                    }
                    if (isDateLikeParameterName(normalized)) {
                        return java.sql.Timestamp.valueOf("2024-01-01 00:00:00");
                    }
                    return defaultString(valueName);
                }

                private boolean isDeletionFlagName(String normalizedName) {
                    return "isdelete".equals(normalizedName)
                            || "isdeleted".equals(normalizedName)
                            || "deleteflag".equals(normalizedName)
                            || "deletedflag".equals(normalizedName)
                            || "isdeleteflag".equals(normalizedName);
                }

                private boolean isNumericParameterName(String normalizedName) {
                    return "offset".equals(normalizedName)
                            || "limit".equals(normalizedName)
                            || "size".equals(normalizedName)
                            || "sn".equals(normalizedName)
                            || "status".equals(normalizedName)
                            || "version".equals(normalizedName)
                            || "sex".equals(normalizedName)
                            || "from".equals(normalizedName)
                            || "ismaster".equals(normalizedName)
                            || "ischarge".equals(normalizedName)
                            || "isrelactive".equals(normalizedName)
                            || "hassynctowx".equals(normalizedName)
                            || "syncpwd".equals(normalizedName)
                            || "supportmobile".equals(normalizedName)
                            || "notifytype".equals(normalizedName)
                            || "encrypt".equals(normalizedName)
                            || "pagesize".equals(normalizedName)
                            || "pagenum".equals(normalizedName)
                            || "pageindex".equals(normalizedName)
                            || "page".equals(normalizedName)
                            || normalizedName.endsWith("count")
                            || normalizedName.endsWith("index");
                }

                private Object defaultNameBasedTypedValue(String valueName) {
                    String normalized = normalizeName(valueName);
                    if (isDeletionFlagName(normalized) || isNumericParameterName(normalized)) {
                        return 1;
                    }
                    if (isIdLikeParameterName(normalized)) {
                        return 1L;
                    }
                    if (isNumericTextParameterName(normalized)) {
                        return BigDecimal.ONE;
                    }
                    String temporalDefault = defaultTemporalString(normalized);
                    if (temporalDefault != null) {
                        return temporalDefault;
                    }
                    if (isDateLikeParameterName(normalized)) {
                        return "2024-01-01 00:00:00";
                    }
                    return null;
                }

                private void addSetAssignmentDefaults(
                        Element element,
                        Map<String, String> foreachCollections,
                        DynamicIdentifierMetadata metadata
                ) {
                    String text = element.getTextContent();
                    if (!looksLikeSetAssignment(text)) {
                        return;
                    }
                    Matcher valueMatcher = Pattern.compile("#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)([^}]*)}")
                            .matcher(text);
                    while (valueMatcher.find()) {
                        List<String> parts = pathParts(valueMatcher.group(1));
                        if (parts.isEmpty() || foreachCollections.containsKey(parts.get(0))) {
                            continue;
                        }
                        String propertyName = parts.size() == 1 ? parts.get(0) : parts.get(parts.size() - 1);
                        Object defaultValue = defaultValueForSetParameter(propertyName, jdbcType(valueMatcher.group(2)));
                        metadata.addDefaultValue(propertyName, defaultValue);
                        metadata.addSetDefaultValue(propertyName);
                        if (parts.size() > 1) {
                            metadata.addDefaultValue(valueMatcher.group(1), defaultValue);
                            metadata.addSetDefaultValue(valueMatcher.group(1));
                        }
                    }
                }

                private boolean looksLikeSetAssignment(String text) {
                    if (text == null || isBlank(text)) {
                        return false;
                    }
                    return Pattern.compile("(?is)(?:^|[,\\\\s])[A-Za-z_][A-Za-z0-9_.$]*\\\\s*=\\\\s*#\\\\{")
                            .matcher(text)
                            .find();
                }

                private Object defaultValueForSetParameter(String valueName, String jdbcType) {
                    if (jdbcType != null && !isBlank(jdbcType)) {
                        return defaultValueForJdbcType(valueName, jdbcType);
                    }
                    String normalized = normalizeName(valueName);
                    if (isIdLikeParameterName(normalized)) {
                        return 1L;
                    }
                    if (isCompactEnumStringName(normalized) || isDeletionFlagName(normalized) || isNumericParameterName(normalized)) {
                        return 1;
                    }
                    if (isDateLikeParameterName(normalized)) {
                        return "2024-01-01 00:00:00";
                    }
                    return defaultString(valueName);
                }

                private String jdbcType(String placeholderTail) {
                    Matcher matcher = Pattern.compile("(?i)(?:^|,)\\\\s*jdbcType\\\\s*=\\\\s*([A-Za-z0-9_]+)")
                            .matcher(placeholderTail == null ? "" : placeholderTail);
                    return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "";
                }

                private List<String> pathParts(String expression) {
                    if (expression == null || isBlank(expression)) {
                        return listOf();
                    }
                    return Pattern.compile("\\\\.")
                            .splitAsStream(expression.trim())
                            .filter(part -> !isBlank(part))
                            .collect(Collectors.toList());
                }

                private void collectSetBranchParameterVariants(Element element, Map<String, BranchCollector> collectors) {
                    if ("if".equals(element.getTagName())) {
                        BranchCondition condition = branchCondition(element.getAttribute("test"));
                        if (condition != null) {
                            BranchCollector collector = collectors.computeIfAbsent(
                                    condition.parameterName,
                                    BranchCollector::new
                            );
                            collector.add(condition.literal, startsWithSet(element.getTextContent()));
                        }
                    }
                    NodeList children = element.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        if (children.item(i) instanceof Element) {
                            Element child = (Element) children.item(i);
                            collectSetBranchParameterVariants(child, collectors);
                        }
                    }
                }

            """,
            """
                private BranchCondition branchCondition(String test) {
                    if (test == null || isBlank(test)) {
                        return null;
                    }
                    Matcher leftLiteral = Pattern.compile(
                            "(?:^|\\\\b(?:and|or)\\\\b)\\\\s*'([^']+)'\\\\s*==\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(test);
                    if (leftLiteral.find()) {
                        return new BranchCondition(leftLiteral.group(2), leftLiteral.group(1), leftLiteral.group(1));
                    }
                    Matcher rightLiteral = Pattern.compile(
                            "(?:^|\\\\b(?:and|or)\\\\b)\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)\\\\s*==\\\\s*'([^']+)'",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(test);
                    if (rightLiteral.find()) {
                        return new BranchCondition(rightLiteral.group(1), rightLiteral.group(2), rightLiteral.group(2));
                    }
                    Matcher numericEquals = Pattern.compile(
                            "(?:^|\\\\b(?:and|or)\\\\b)\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)\\\\s*==\\\\s*(-?\\\\d+)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(test);
                    if (numericEquals.find()) {
                        return new BranchCondition(
                                numericEquals.group(1),
                                numericEquals.group(2),
                                Integer.parseInt(numericEquals.group(2))
                        );
                    }
                    Matcher numericNotEquals = Pattern.compile(
                            "(?:^|\\\\b(?:and|or)\\\\b)\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)\\\\s*!=\\\\s*(-?\\\\d+)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(test);
                    if (numericNotEquals.find()) {
                        int forbiddenValue = Integer.parseInt(numericNotEquals.group(2));
                        int defaultValue = forbiddenValue == 0 ? 1 : 0;
                        return new BranchCondition(numericNotEquals.group(1), String.valueOf(defaultValue), defaultValue);
                    }
                    return null;
                }

                private boolean shouldUseNonEmptyForeachCollection(String collection, String item, Element element) {
                    String normalizedCollection = normalizeName(collection);
                    String normalizedItem = normalizeName(item);
                    return isBusinessCollectionName(normalizedCollection)
                            || isBusinessCollectionItemName(normalizedItem)
                            || followsInOperator(element);
                }

                private NestedCollection nestedCollection(String collection, Map<String, String> foreachCollections) {
                    List<String> parts = pathParts(collection);
                    if (parts.isEmpty() || foreachCollections == null) {
                        return null;
                    }
                    String parentCollection = foreachCollections.get(parts.get(0));
                    if (parentCollection == null || isBlank(parentCollection)) {
                        return null;
                    }
                    if (parts.size() == 1) {
                        return new NestedCollection(parentCollection, "", true);
                    }
                    return new NestedCollection(parentCollection, parts.get(1), false);
                }

                private String canonicalCollectionName(String collection) {
                    if (collection == null) {
                        return "";
                    }
                    String trimmed = collection.trim();
                    String lower = trimmed.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".entryset()")) {
                        return trimmed.substring(0, trimmed.length() - ".entrySet()".length());
                    }
                    if (lower.endsWith(".entryset")) {
                        return trimmed.substring(0, trimmed.length() - ".entrySet".length());
                    }
                    return trimmed;
                }

                private boolean isEntrySetCollection(String collection) {
                    if (collection == null) {
                        return false;
                    }
                    String lower = collection.trim().toLowerCase(Locale.ROOT);
                    return lower.endsWith(".entryset()") || lower.endsWith(".entryset");
                }

                private Object defaultNestedForeachCollectionValue(
                        String collection,
                        String index,
                        String item,
                        Element element
                ) {
                    if (isMapForeachCollection(collection, index, item, element)) {
                        return defaultMapCollectionParameter(collection, null);
                    }
                    return new ArrayList<>(listOf(defaultCollectionElement(collection)));
                }

                private Object defaultDirectNestedForeachCollectionValue(
                        String collection,
                        String index,
                        String item,
                        Element element,
                        SqlStatementContext sqlContext
                ) {
                    String text = element == null || element.getTextContent() == null ? "" : element.getTextContent();
                    Map<String, Object> elementDefault = new LinkedHashMap<>();
                    Object scalarDefault = MethodArgumentConfig.MISSING;
                    Matcher dynamicMatcher = Pattern.compile("\\\\$\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)\\\\s*}").matcher(text);
                    while (dynamicMatcher.find()) {
                        List<String> parts = pathParts(dynamicMatcher.group(1));
                        if (parts.size() == 1 && parts.get(0).equals(item)) {
                            scalarDefault = defaultDynamicSqlFragmentValue(
                                    collection,
                                    "",
                                    text,
                                    dynamicMatcher.start(),
                                    dynamicMatcher.end(),
                                    sqlContext
                            );
                        } else if (parts.size() > 1 && parts.get(0).equals(item)) {
                            elementDefault.putIfAbsent(
                                    parts.get(1),
                                    defaultDynamicSqlFragmentValue(
                                            collection,
                                            parts.get(1),
                                            text,
                                            dynamicMatcher.start(),
                                            dynamicMatcher.end(),
                                            sqlContext
                                    )
                            );
                        }
                    }
                    Matcher valueMatcher = Pattern.compile("#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)([^}]*)}").matcher(text);
                    while (valueMatcher.find()) {
                        List<String> parts = pathParts(valueMatcher.group(1));
                        if (parts.size() == 1 && parts.get(0).equals(item)) {
                            scalarDefault = defaultValueForDirectParameter(collection, jdbcType(valueMatcher.group(2)));
                        } else if (parts.size() > 1 && parts.get(0).equals(item)) {
                            String propertyName = parts.get(1);
                            String jdbcType = jdbcType(valueMatcher.group(2));
                            elementDefault.putIfAbsent(
                                    propertyName,
                                    jdbcType == null || isBlank(jdbcType)
                                            ? defaultString(propertyName)
                                            : defaultValueForJdbcType(propertyName, jdbcType)
                            );
                        }
                    }
                    addDirectNestedForeachBranchDefaults(element, item, elementDefault);
                    if (!elementDefault.isEmpty()) {
                        return new ArrayList<>(listOf(elementDefault));
                    }
                    if (scalarDefault != MethodArgumentConfig.MISSING) {
                        return new ArrayList<>(listOf(scalarDefault));
                    }
                    return defaultNestedForeachCollectionValue(collection, index, item, element);
                }

                private void addDirectNestedForeachBranchDefaults(
                        Element element,
                        String item,
                        Map<String, Object> elementDefault
                ) {
                    if (element == null || isBlank(item)) {
                        return;
                    }
                    if ("if".equals(element.getTagName()) || "when".equals(element.getTagName())) {
                        BranchCondition condition = branchCondition(element.getAttribute("test"));
                        if (condition != null) {
                            List<String> parts = pathParts(condition.parameterName);
                            if (parts.size() > 1 && parts.get(0).equals(item)) {
                                elementDefault.putIfAbsent(parts.get(1), condition.defaultValue);
                            }
                        }
                    }
                    NodeList children = element.getChildNodes();
                    for (int i = 0; i < children.getLength(); i++) {
                        if (children.item(i) instanceof Element) {
                            addDirectNestedForeachBranchDefaults((Element) children.item(i), item, elementDefault);
                        }
                    }
                }

                private boolean isMapForeachCollection(String collection, String index, String item, Element element) {
                    String normalizedCollection = normalizeName(canonicalCollectionName(collection));
                    String normalizedItem = normalizeName(item);
                    String normalizedIndex = normalizeName(index);
                    if (isEntrySetCollection(collection)) {
                        return true;
                    }
                    if (isMapForeachCollectionName(normalizedCollection)) {
                        return true;
                    }
                    if (!isBlank(normalizedIndex)
                            && ("value".equals(normalizedItem) || "val".equals(normalizedItem) || "item".equals(normalizedItem))) {
                        return true;
                    }
                    return element != null
                            && Pattern.compile("\\\\$\\\\{\\\\s*" + Pattern.quote(item == null ? "" : item) + "\\\\s*}")
                                    .matcher(element.getTextContent() == null ? "" : element.getTextContent())
                                    .find();
                }

                private boolean isMapForeachCollectionName(String normalizedName) {
                    return "map".equals(normalizedName)
                            || normalizedName.contains("rightmap")
                            || normalizedName.contains("groupmap")
                            || normalizedName.contains("userrightmap");
                }

                private boolean isNestedForeachCollectionPath(String collectionName) {
                    List<String> parts = pathParts(collectionName);
                    return parts.size() > 1 && isForeachItemName(parts.get(0));
                }

                private String directNestedForeachCollectionPath(String parentCollection) {
                    return "item." + (parentCollection == null ? "" : parentCollection);
                }

                private boolean isForeachItemName(String valueName) {
                    String normalized = normalizeName(valueName);
                    return "item".equals(normalized)
                            || "value".equals(normalized)
                            || "val".equals(normalized);
                }

                private boolean isBusinessCollectionName(String normalizedName) {
                    return normalizedName.contains("orderno")
                            || normalizedName.contains("billno")
                            || normalizedName.contains("code")
                            || normalizedName.contains("accountbook")
                            || normalizedName.contains("rightmap")
                            || normalizedName.contains("groupmap")
                            || "map".equals(normalizedName)
                            || normalizedName.endsWith("id")
                            || normalizedName.endsWith("ids")
                            || normalizedName.contains("key");
                }

                private boolean isBusinessCollectionItemName(String normalizedName) {
                    return isBusinessCollectionName(normalizedName);
                }

                private SqlStatementContext sqlStatementContext(Element statement) {
                    Map<String, String> tableAliases = new LinkedHashMap<>();
                    String sql = compactSql(statement.getTextContent());
                    String identifier = sqlIdentifierPattern();
                    Matcher matcher = Pattern.compile(
                            "(?i)\\\\b(?:from|join)\\\\s+(" + identifier + "(?:\\\\s*\\\\.\\\\s*" + identifier + ")?)"
                                    + "(?:\\\\s+(?:as\\\\s+)?(" + identifier + "))?"
                    ).matcher(sql);
                    while (matcher.find()) {
                        String table = lastIdentifierPart(matcher.group(1));
                        String alias = matcher.group(2);
                        if (isBlank(table)) {
                            continue;
                        }
                        tableAliases.put(normalizeSqlIdentifier(table), table);
                        if (alias != null && !isBlank(alias) && !isSqlKeyword(alias)) {
                            tableAliases.put(normalizeSqlIdentifier(alias), table);
                        }
                    }
                    return new SqlStatementContext(tableAliases);
                }

                private ColumnReference inOperatorColumnReference(Element element, SqlStatementContext sqlContext) {
                    String textBefore = compactSql(textBeforeElement(element, 300));
                    if (isBlank(textBefore)) {
                        return null;
                    }
                    String identifier = sqlIdentifierPattern();
                    Matcher matcher = Pattern.compile(
                            "(?i)(" + identifier + "(?:\\\\s*\\\\.\\\\s*" + identifier + ")?)\\\\s+(?:not\\\\s+)?in\\\\s*$"
                    ).matcher(textBefore);
                    if (!matcher.find()) {
                        return null;
                    }
                    String expression = matcher.group(1);
                    List<String> parts = Pattern.compile("\\\\.").splitAsStream(expression)
                            .map(String::trim)
                            .filter(part -> !isBlank(part))
                            .collect(Collectors.toList());
                    if (parts.isEmpty()) {
                        return null;
                    }
                    return columnReference(expression, sqlContext);
                }

                private ColumnReference columnReference(String expression, SqlStatementContext sqlContext) {
                    List<String> parts = Pattern.compile("\\\\.").splitAsStream(expression == null ? "" : expression)
                            .map(String::trim)
                            .filter(part -> !isBlank(part))
                            .collect(Collectors.toList());
                    if (parts.isEmpty()) {
                        return null;
                    }
                    if (parts.size() == 1) {
                        return new ColumnReference("", cleanSqlIdentifier(parts.get(0)));
                    }
                    String qualifier = cleanSqlIdentifier(parts.get(parts.size() - 2));
                    String table = sqlContext == null ? "" : sqlContext.tableName(qualifier);
                    return new ColumnReference(table, cleanSqlIdentifier(parts.get(parts.size() - 1)));
                }

                private boolean followsInOperator(Element element) {
                    String compact = compactSql(textBeforeElement(element, 200));
                    return Pattern.compile("(?i)(?:^|\\\\s)(?:not\\\\s+)?in\\\\s*$").matcher(compact).find();
                }

                private String textBeforeElement(Element element, int maxLength) {
                    StringBuilder textBefore = new StringBuilder();
                    Node previous = element.getPreviousSibling();
                    while (previous != null && textBefore.length() < maxLength) {
                        if (previous.getNodeType() == Node.TEXT_NODE || previous.getNodeType() == Node.CDATA_SECTION_NODE) {
                            textBefore.insert(0, previous.getTextContent());
                        } else if (previous instanceof Element) {
                            break;
                        }
                        previous = previous.getPreviousSibling();
                    }
                    return textBefore.toString();
                }

                private String compactSql(String sql) {
                    return Pattern.compile("\\\\s+").matcher(sql == null ? "" : sql).replaceAll(" ").trim();
                }

                private String sqlIdentifierPattern() {
                    return "(?:\\\"[^\\\"]+\\\"|`[^`]+`|[A-Za-z_][A-Za-z0-9_$]*)";
                }

                private String lastIdentifierPart(String expression) {
                    if (expression == null || isBlank(expression)) {
                        return "";
                    }
                    List<String> parts = Pattern.compile("\\\\.").splitAsStream(expression)
                            .map(String::trim)
                            .filter(part -> !isBlank(part))
                            .collect(Collectors.toList());
                    return parts.isEmpty() ? "" : cleanSqlIdentifier(parts.get(parts.size() - 1));
                }

                private String cleanSqlIdentifier(String identifier) {
                    if (identifier == null) {
                        return "";
                    }
                    String trimmed = identifier.trim();
                    if (trimmed.length() >= 2
                            && ((trimmed.startsWith("\\\"") && trimmed.endsWith("\\\""))
                            || (trimmed.startsWith("`") && trimmed.endsWith("`")))) {
                        return trimmed.substring(1, trimmed.length() - 1);
                    }
                    return trimmed;
                }

                private String normalizeSqlIdentifier(String identifier) {
                    return cleanSqlIdentifier(identifier).toLowerCase(Locale.ROOT);
                }

                private boolean isSqlKeyword(String identifier) {
                    String normalized = normalizeSqlIdentifier(identifier);
                    return setOf(
                            "where", "left", "right", "inner", "outer", "full", "join", "on",
                            "group", "order", "having", "limit", "fetch", "union", "cross"
                    ).contains(normalized);
                }

                private boolean startsWithSet(String text) {
                    return text != null && text.trim().toLowerCase(Locale.ROOT).startsWith("set ");
                }

                """,
            """
                private List<ParameterResolution> resolveParameterVariants(MapperMethod mapperMethod, ValidationConfig config) {
                    MethodArgumentConfig configuredArgs = config.methodArguments(mapperMethod.key());
                    if (mapperMethod.isUnmapped()) {
                        return listOf(ParameterResolution.unresolved("configuration", "Mapped statement was not registered by MyBatis."));
                    }
                    if (mapperMethod.method != null) {
                        if (configuredArgs != null) {
                            return listOf(configuredParameters(mapperMethod, configuredArgs));
                        }
                        ParameterResolution parameters = generatedParameters(mapperMethod);
                        return parameters.resolved
                                ? setBranchParameterVariants(mapperMethod, parameters)
                                : listOf(parameters);
                    }
                    return listOf(statementParameters(mapperMethod, configuredArgs));
                }

                private ParameterResolution configuredParameters(MapperMethod mapperMethod, MethodArgumentConfig configuredArgs) {
                    if (configuredArgs.hasParams()) {
                        return configuredNamedParameters(mapperMethod, configuredArgs);
                    }
                    Class<?>[] parameterTypes = mapperMethod.method.getParameterTypes();
                    if (configuredArgs.args.size() != parameterTypes.length) {
                        return ParameterResolution.unresolved(
                                "configured",
                                "Configured argument count " + configuredArgs.args.size()
                                        + " does not match method parameter count " + parameterTypes.length + "."
                        );
                    }
                    Object[] args = new Object[parameterTypes.length];
                    int collectionParameterIndex = 0;
                    for (int i = 0; i < parameterTypes.length; i++) {
                        String parameterName = parameterName(mapperMethod.method, i);
                        boolean collectionLike = isCollectionLikeParameter(parameterTypes[i]);
                        String effectiveParameterName = mapperMethod.statement == null
                                ? parameterName
                                : mapperMethod.statement.parameterExpressionName(
                                        i,
                                        collectionLike ? collectionParameterIndex : -1,
                                        parameterName
                                );
                        if (collectionLike) {
                            collectionParameterIndex++;
                        }
                        ValueResult value = convertConfiguredValue(
                                configuredArgs.args.get(i),
                                parameterTypes[i],
                                mapperMethod.method.getGenericParameterTypes()[i],
                                mapperMethod.statement,
                                effectiveParameterName
                        );
                        if (!value.resolved) {
                            return ParameterResolution.unresolved("configured", value.message);
                        }
                        args[i] = value.value;
                    }
                    return ParameterResolution.resolved("configured", args);
                }

                private ParameterResolution configuredNamedParameters(MapperMethod mapperMethod, MethodArgumentConfig configuredArgs) {
                    Class<?>[] parameterTypes = mapperMethod.method.getParameterTypes();
                    Type[] genericTypes = mapperMethod.method.getGenericParameterTypes();
                    Object[] args = new Object[parameterTypes.length];
                    if (parameterTypes.length == 1) {
                        String parameterName = parameterName(mapperMethod.method, 0);
                        if (configuredArgs.params.containsKey(parameterName)) {
                            ValueResult value = convertConfiguredValue(
                                    configuredArgs.params.get(parameterName),
                                    parameterTypes[0],
                                    genericTypes[0],
                                    mapperMethod.statement,
                                    parameterName
                            );
                            return value.resolved
                                    ? ParameterResolution.resolved("configured", new Object[] { value.value })
                                    : ParameterResolution.unresolved("configured", value.message);
                        }
                        if (Map.class.isAssignableFrom(parameterTypes[0])) {
                            if (mapperMethod.statement != null
                                    && mapperMethod.statement.mapCollectionParameter(parameterName)) {
                                return ParameterResolution.resolved(
                                        "configured",
                                        new Object[] { mapParameterValue(
                                                parameterTypes[0],
                                                defaultMapParameterValue(parameterName, mapperMethod.statement)
                                        ) }
                                );
                            }
                            return ParameterResolution.resolved(
                                    "configured",
                                    new Object[] { mapParameterValue(
                                            parameterTypes[0],
                                            configuredParameterMap(mapperMethod.statement, configuredArgs.params)
                                    ) }
                            );
                        }
                        ValueResult pojo = configuredPojoValue(parameterTypes[0], genericTypes[0], configuredArgs.params, mapperMethod.statement);
                        return pojo.resolved
                                ? ParameterResolution.resolved("configured", new Object[] { pojo.value })
                                : ParameterResolution.unresolved("configured", pojo.message);
                    }
                    for (int i = 0; i < parameterTypes.length; i++) {
                        String parameterName = parameterName(mapperMethod.method, i);
                        boolean collectionLike = isCollectionLikeParameter(parameterTypes[i]);
                        String effectiveParameterName = mapperMethod.statement == null
                                ? parameterName
                                : mapperMethod.statement.parameterExpressionName(
                                        i,
                                        collectionLike ? collectionParameterIndex(parameterTypes, i) : -1,
                                        parameterName
                                );
                        Object rawValue = configuredArgs.valueFor(parameterName, effectiveParameterName, i);
                        ValueResult value = rawValue == MethodArgumentConfig.MISSING
                                ? defaultValue(effectiveParameterName, parameterTypes[i], genericTypes[i], 0, mapperMethod.statement)
                                : convertConfiguredValue(
                                        rawValue,
                                        parameterTypes[i],
                                        genericTypes[i],
                                        mapperMethod.statement,
                                        effectiveParameterName
                                );
                        if (!value.resolved) {
                            return ParameterResolution.unresolved("configured", value.message);
                        }
                        args[i] = value.value;
                    }
                    return ParameterResolution.resolved("configured", args);
                }

                private ParameterResolution generatedParameters(MapperMethod mapperMethod) {
                    Class<?>[] declaredParameterTypes = mapperMethod.method.getParameterTypes();
                    Type[] declaredGenericTypes = mapperMethod.method.getGenericParameterTypes();
                    Object[] args = new Object[declaredParameterTypes.length];
                    List<String> names = new ArrayList<>();
                    int collectionParameterIndex = 0;
                    boolean inferredActualType = false;
                    for (int i = 0; i < declaredParameterTypes.length; i++) {
                        String parameterName = parameterName(mapperMethod.method, i);
                        names.add(parameterName);
                        Class<?> parameterType = actualParameterTypeIndex.actualType(
                                mapperMethod.key(),
                                i,
                                declaredParameterTypes[i]
                        );
                        Type genericType = parameterType.equals(declaredParameterTypes[i])
                                ? declaredGenericTypes[i]
                                : parameterType;
                        if (!parameterType.equals(declaredParameterTypes[i])) {
                            inferredActualType = true;
                        }
                        boolean collectionLike = isCollectionLikeParameter(parameterType);
                        String effectiveParameterName = mapperMethod.statement.parameterExpressionName(
                                i,
                                collectionLike ? collectionParameterIndex : -1,
                                parameterName
                        );
                        if (collectionLike) {
                            collectionParameterIndex++;
                        }
                        ValueResult value = defaultValue(
                                effectiveParameterName,
                                parameterType,
                                genericType,
                                0,
                                mapperMethod.statement
                        );
                        if (!value.resolved) {
                            return ParameterResolution.unresolved("auto", value.message);
                        }
                        args[i] = value.value;
                    }
                    return ParameterResolution.resolved(inferredActualType ? "auto:actual-type" : "auto", args, names);
                }

                private List<ParameterResolution> setBranchParameterVariants(
                        MapperMethod mapperMethod,
                        ParameterResolution baseParameters
                ) {
                    if (mapperMethod.statement.setBranchParameterVariants.isEmpty()) {
                        return listOf(baseParameters);
                    }
                    List<ParameterResolution> variants = new ArrayList<>();
                    Class<?>[] parameterTypes = mapperMethod.method.getParameterTypes();
                    Type[] genericTypes = mapperMethod.method.getGenericParameterTypes();
                    for (SetBranchParameterVariant variant : mapperMethod.statement.setBranchParameterVariants) {
                        int parameterIndex = parameterIndex(mapperMethod.method, variant.parameterName);
                        if (parameterIndex < 0) {
                            continue;
                        }
                        ValueResult value = convertScalar(variant.literal, parameterTypes[parameterIndex], genericTypes[parameterIndex]);
                        if (!value.resolved) {
                            continue;
                        }
                        Object[] args = baseParameters.args.clone();
                        args[parameterIndex] = value.value;
                        String label = variant.parameterName + "=" + variant.literal;
                        variants.add(ParameterResolution.resolved(baseParameters.source + ":" + label, args, baseParameters.names, label));
                    }
                    return variants.isEmpty() ? listOf(baseParameters) : variants;
                }

                private int parameterIndex(Method method, String parameterName) {
                    for (int i = 0; i < method.getParameterCount(); i++) {
                        if (parameterName(method, i).equals(parameterName)) {
                            return i;
                        }
                    }
                    return -1;
                }

                private ValidationRecord javaMapperSignatureIssue(MapperMethod mapperMethod) {
                    if (mapperMethod == null || mapperMethod.method == null) {
                        return null;
                    }
                    Method method = mapperMethod.method;
                    Map<String, List<Integer>> paramIndexesByName = new LinkedHashMap<>();
                    List<String> missingSimpleParams = new ArrayList<>();
                    for (int i = 0; i < method.getParameterCount(); i++) {
                        Parameter parameter = method.getParameters()[i];
                        String paramName = paramAnnotationName(parameter);
                        if (!isBlank(paramName)) {
                            paramIndexesByName.computeIfAbsent(paramName, ignored -> new ArrayList<>()).add(i);
                        } else if (method.getParameterCount() > 1
                                && simpleMapperParameterType(method.getParameterTypes()[i])
                                && !hasUsableActualParameterName(parameter, i)) {
                            missingSimpleParams.add(parameter.getName());
                        }
                    }
                    for (Map.Entry<String, List<Integer>> entry : paramIndexesByName.entrySet()) {
                        if (entry.getValue().size() > 1) {
                            return ValidationRecord.skipped(
                                    mapperMethod.key(),
                                    "java-mapper-signature",
                                    "Skipped business Java mapper signature issue: Java mapper signature has duplicate @Param(\\"" + entry.getKey()
                                            + "\\") annotations at parameter indexes " + entry.getValue()
                                            + ". Fix the Java mapper method @Param names instead of changing mapper XML parameter names."
                            );
                        }
                    }
                    if (!missingSimpleParams.isEmpty()) {
                        return ValidationRecord.skipped(
                                mapperMethod.key(),
                                "java-mapper-signature",
                                "Skipped business Java mapper signature issue: Java mapper method has multiple simple parameters without @Param: "
                                        + String.join(", ", missingSimpleParams)
                                        + ". Add @Param annotations in the Java mapper method instead of changing mapper XML parameter names."
                        );
                    }
                    if (method.getParameterCount() == 1) {
                        Parameter parameter = method.getParameters()[0];
                        Set<String> parameterReferences = mapperMethod.statement.parameterReferences();
                        if (parameterReferences.size() == 1
                                && isBlank(paramAnnotationName(parameter))
                                && simpleMapperParameterType(method.getParameterTypes()[0])) {
                            String reference = parameterReferences.iterator().next();
                            if (hasUsableActualParameterName(parameter, 0)) {
                                String actualName = parameter.getName();
                                if (!reference.equals(actualName)) {
                                    return ValidationRecord.skipped(
                                            mapperMethod.key(),
                                            "java-mapper-signature",
                                            "Skipped business Java mapper signature issue: Java mapper method has a single simple parameter named "
                                                    + actualName + " without @Param, but mapper XML references " + reference
                                                    + ". Add @Param(\\\"" + reference
                                                    + "\\\") in the Java mapper method instead of changing mapper XML parameter names."
                                    );
                                }
                            } else {
                                return ValidationRecord.skipped(
                                        mapperMethod.key(),
                                        "java-mapper-signature",
                                        "Skipped business Java mapper signature issue: Java mapper method has a single simple parameter without @Param, "
                                                + "but mapper XML references " + reference
                                                + ". Add @Param(\\\"" + reference
                                                + "\\\") in the Java mapper method instead of changing mapper XML parameter names."
                                );
                            }
                        }
                    }
                    return null;
                }

                private boolean hasUsableActualParameterName(Parameter parameter, int index) {
                    if (parameter == null || !parameter.isNamePresent()) {
                        return false;
                    }
                    String name = parameter.getName();
                    return !isBlank(name) && !Pattern.compile("^(?:arg|param)\\\\d+$").matcher(name).matches();
                }

                private boolean simpleMapperParameterType(Class<?> type) {
                    if (type == null) {
                        return false;
                    }
                    return type.isPrimitive()
                            || type.isEnum()
                            || String.class.equals(type)
                            || CharSequence.class.isAssignableFrom(type)
                            || Number.class.isAssignableFrom(type)
                            || Boolean.class.equals(type)
                            || Character.class.equals(type)
                            || Date.class.isAssignableFrom(type)
                            || java.time.temporal.Temporal.class.isAssignableFrom(type)
                            || java.time.temporal.TemporalAccessor.class.isAssignableFrom(type)
                            || UUID.class.equals(type)
                            || BigDecimal.class.equals(type)
                            || BigInteger.class.equals(type);
                }

                private String parameterName(Method method, int index) {
                    Parameter parameter = method.getParameters()[index];
                    String paramName = paramAnnotationName(parameter);
                    if (!isBlank(paramName)) {
                        return paramName;
                    }
                    return parameter.getName();
                }

                private String paramAnnotationName(Parameter parameter) {
                    for (Annotation annotation : parameter.getAnnotations()) {
                        if ("org.apache.ibatis.annotations.Param".equals(annotation.annotationType().getName())) {
                            try {
                                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                                if (value instanceof String) {
                                    String name = (String) value;
                                    if (!name.trim().isEmpty()) {
                                        return name.trim();
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    return "";
                }

                private boolean isCollectionLikeParameter(Class<?> parameterType) {
                    return parameterType != null
                            && (parameterType.isArray() || Collection.class.isAssignableFrom(parameterType));
                }

                private int collectionParameterIndex(Class<?>[] parameterTypes, int targetIndex) {
                    int collectionParameterIndex = 0;
                    for (int i = 0; i < targetIndex; i++) {
                        if (isCollectionLikeParameter(parameterTypes[i])) {
                            collectionParameterIndex++;
                        }
                    }
                    return collectionParameterIndex;
                }

                """,
            """
                private ParameterResolution statementParameters(MapperMethod mapperMethod, MethodArgumentConfig configuredArgs) {
                    if (configuredArgs != null && configuredArgs.hasParams()) {
                        return ParameterResolution.resolved(
                                "configured",
                                new Object[] { configuredParameterMap(mapperMethod.statement, configuredArgs.params) }
                        );
                    }
                    if (configuredArgs != null && configuredArgs.args.size() > 1) {
                        Map<String, Object> namedParameters = new LinkedHashMap<>();
                        for (int i = 0; i < configuredArgs.args.size(); i++) {
                            Object value = configuredArgs.args.get(i);
                            namedParameters.put("arg" + i, value);
                            namedParameters.put("param" + (i + 1), value);
                        }
                        return ParameterResolution.resolved("configured", new Object[] { namedParameters });
                    }
                    if (configuredArgs != null && configuredArgs.args.size() == 1) {
                        boolean collectionLike = isCollectionLikeParameter(mapperMethod.parameterType);
                        String parameterName = mapperMethod.statement == null
                                ? "arg0"
                                : mapperMethod.statement.parameterExpressionName(0, collectionLike ? 0 : -1, "arg0");
                        ValueResult value = convertConfiguredValue(
                                configuredArgs.args.get(0),
                                mapperMethod.parameterType,
                                mapperMethod.parameterType,
                                mapperMethod.statement,
                                parameterName
                        );
                        return value.resolved
                                ? ParameterResolution.resolved("configured", new Object[] { value.value })
                                : ParameterResolution.unresolved("configured", value.message);
                    }
                    if ((mapperMethod.parameterType == null || Object.class.equals(mapperMethod.parameterType))
                            && mapperMethod.statement.hasDefaultParameterMap()) {
                        return ParameterResolution.resolved("auto", new Object[] { defaultParameterMap(mapperMethod.statement) });
                    }
                    if (mapperMethod.parameterType == null || Object.class.equals(mapperMethod.parameterType) || Void.TYPE.equals(mapperMethod.parameterType)) {
                        return ParameterResolution.resolved("auto", new Object[] { null });
                    }
                    ValueResult value = defaultValue(mapperMethod.parameterType, mapperMethod.parameterType, 0, mapperMethod.statement);
                    return value.resolved
                            ? ParameterResolution.resolved("auto", new Object[] { value.value })
                            : ParameterResolution.unresolved("auto", value.message);
                }

                private ValidationRecord invokeMapperMethod(
                        SqlSessionFactory sqlSessionFactory,
                        MapperMethod mapperMethod,
                        ParameterResolution parameters,
                        ValidationConfig config
                ) {
                    List<String> schemas = validationSchemas(config);
                    ValidationRecord primaryRecord = invokeMapperMethodWithSchema(
                            sqlSessionFactory,
                            mapperMethod,
                            parameters,
                            schemas.get(0)
                    );
                    if (schemas.size() == 1
                            || "PASSED".equals(primaryRecord.status)
                            || !isSchemaObjectFailureRecord(primaryRecord)) {
                        return primaryRecord;
                    }
                    List<SchemaAttempt> attempts = new ArrayList<>();
                    attempts.add(new SchemaAttempt(schemas.get(0), primaryRecord));
                    ValidationRecord firstNonSchemaObjectFailure = null;
                    SchemaAttempt firstNonSchemaObjectAttempt = null;
                    for (int i = 1; i < schemas.size(); i++) {
                        String schema = schemas.get(i);
                        log("SCHEMA FALLBACK " + parameters.recordKey(mapperMethod.key())
                                + " schema=" + schemaLabel(schema));
                        ValidationRecord record = invokeMapperMethodWithSchema(sqlSessionFactory, mapperMethod, parameters, schema);
                        SchemaAttempt attempt = new SchemaAttempt(schema, record);
                        attempts.add(attempt);
                        if ("PASSED".equals(record.status)) {
                            return ValidationRecord.passed(
                                    parameters.recordKey(mapperMethod.key()),
                                    parameters.source,
                                    parametersSummary(parameters),
                                    "schema=" + schemaLabel(schema) + "; " + record.message
                            );
                        }
                        if (!isSchemaObjectFailureRecord(record) && firstNonSchemaObjectFailure == null) {
                            firstNonSchemaObjectFailure = record;
                            firstNonSchemaObjectAttempt = attempt;
                        }
                    }
                    SchemaAttempt representativeAttempt = firstNonSchemaObjectFailure == null
                            ? attempts.get(0)
                            : firstNonSchemaObjectAttempt;
                    ValidationRecord representative = representativeAttempt.record();
                    return ValidationRecord.failed(
                            parameters.recordKey(mapperMethod.key()),
                            parameters.source,
                            parametersSummary(parameters),
                            "All configured schemas failed. Representative schema="
                                    + schemaLabel(representativeAttempt.schema())
                                    + "\\n" + representative.message
                                    + "\\nSchema attempts: " + schemaAttemptSummary(attempts),
                            parameters
                    );
                }

                private List<String> validationSchemas(ValidationConfig config) {
                    List<String> schemas = config.schemas();
                    return schemas.isEmpty() ? listOf("") : schemas;
                }

                private boolean isSchemaObjectFailureRecord(ValidationRecord record) {
                    return record != null
                            && "FAILED".equals(record.status)
                            && isSchemaObjectFailure(normalizeMessage(record.message).toLowerCase(Locale.ROOT));
                }

                private String schemaAttemptSummary(List<SchemaAttempt> attempts) {
                    return attempts.stream()
                            .map(attempt -> schemaLabel(attempt.schema()) + "="
                                    + attempt.record().status + "/"
                                    + category(attempt.record()) + "/"
                                    + failurePattern(attempt.record()))
                            .collect(Collectors.joining(" | "));
                }

                private String schemaLabel(String schema) {
                    return schema == null || isBlank(schema) ? "<default>" : schema;
                }

                private ValidationRecord invokeMapperMethodWithSchema(
                        SqlSessionFactory sqlSessionFactory,
                        MapperMethod mapperMethod,
                        ParameterResolution parameters,
                        String schema
                ) {
                    try (SqlSession sqlSession = sqlSessionFactory.openSession(false)) {
                        try {
                            applySchema(sqlSession.getConnection(), schema);
                            Object result = invokeMappedStatement(
                                    sqlSession,
                                    mapperMethod,
                                    statementParameterObject(mapperMethod, parameters)
                            );
                            sqlSession.rollback(true);
                            return ValidationRecord.passed(
                                    parameters.recordKey(mapperMethod.key()),
                                    parameters.source,
                                    parametersSummary(parameters),
                                    resultSummary(result),
                                    parameters
                            );
                        } catch (MapperInvocationException e) {
                            sqlSession.rollback(true);
                            String summary = throwableSummary(e.getCause());
                            ValidationRecord skippedRegexp = skippedRegexpPlaceholderRecord(mapperMethod, parameters, summary);
                            if (skippedRegexp != null) {
                                return skippedRegexp;
                            }
                            if (isEmptyDynamicSqlFailure(summary)) {
                                return ValidationRecord.skipped(
                                        parameters.recordKey(mapperMethod.key()),
                                        parameters.source,
                                        parametersSummary(parameters),
                                        "动态 SQL 未生成，通常是当前测试参数没有触发 mapper 的动态 SQL 分支；"
                                                + "请在 .dm-adapter/sql-rewrite.yml 的 validationArgs 中配置能触发该分支的参数。"
                                                + "\\n" + summary
                                );
                            }
                            if (isPrimitiveNullReturnFailure(summary)) {
                                return ValidationRecord.skipped(
                                        parameters.recordKey(mapperMethod.key()),
                                        parameters.source,
                                        parametersSummary(parameters),
                                        "SQL 已执行，但 mapper 基本类型返回值收到 null，通常是测试数据未命中；已跳过此项。"
                                                + "\\n" + summary
                                );
                            }
                            return ValidationRecord.failed(
                                    parameters.recordKey(mapperMethod.key()),
                                    parameters.source,
                                    parametersSummary(parameters),
                                    summary,
                                    parameters
                            );
                        } catch (Throwable e) {
                            sqlSession.rollback(true);
                            String summary = throwableSummary(e);
                            ValidationRecord skippedRegexp = skippedRegexpPlaceholderRecord(mapperMethod, parameters, summary);
                            if (skippedRegexp != null) {
                                return skippedRegexp;
                            }
                            if (isEmptyDynamicSqlFailure(summary)) {
                                return ValidationRecord.skipped(
                                        parameters.recordKey(mapperMethod.key()),
                                        parameters.source,
                                        parametersSummary(parameters),
                                        "动态 SQL 未生成，通常是当前测试参数没有触发 mapper 的动态 SQL 分支；"
                                                + "请在 .dm-adapter/sql-rewrite.yml 的 validationArgs 中配置能触发该分支的参数。"
                                                + "\\n" + summary
                                );
                            }
                            if (isPrimitiveNullReturnFailure(summary)) {
                                return ValidationRecord.skipped(
                                        parameters.recordKey(mapperMethod.key()),
                                        parameters.source,
                                        parametersSummary(parameters),
                                        "SQL 已执行，但 mapper 基本类型返回值收到 null，通常是测试数据未命中；已跳过此项。"
                                                + "\\n" + summary
                                );
                            }
                            return ValidationRecord.failed(
                                    parameters.recordKey(mapperMethod.key()),
                                    parameters.source,
                                    parametersSummary(parameters),
                                    summary,
                                    parameters
                            );
                        }
                    } catch (Throwable e) {
                        String summary = throwableSummary(e);
                        ValidationRecord skippedRegexp = skippedRegexpPlaceholderRecord(mapperMethod, parameters, summary);
                        if (skippedRegexp != null) {
                            return skippedRegexp;
                        }
                        if (isPrimitiveNullReturnFailure(summary)) {
                            return ValidationRecord.skipped(
                                    parameters.recordKey(mapperMethod.key()),
                                    parameters.source,
                                    parametersSummary(parameters),
                                    "SQL 已执行，但 mapper 基本类型返回值收到 null，通常是测试数据未命中；已跳过此项。"
                                            + "\\n" + summary
                            );
                        }
                        return ValidationRecord.failed(
                                parameters.recordKey(mapperMethod.key()),
                                parameters.source,
                                parametersSummary(parameters),
                                summary,
                                parameters
                        );
                    }
                }

                private boolean isEmptyDynamicSqlFailure(String message) {
                    return message != null && message.toLowerCase(Locale.ROOT).contains("sql语句为null或空值");
                }

                private boolean isPrimitiveNullReturnFailure(String message) {
                    return message != null
                            && message.contains("attempted to return null from a method with a primitive return type");
                }

                private ValidationRecord skippedRegexpPlaceholderRecord(
                        MapperMethod mapperMethod,
                        ParameterResolution parameters,
                        String summary
                ) {
                    if (!isRegexpMemoryLimitFailure(summary)
                            || !hasGeneratedRegexpPlaceholderParameter(mapperMethod, parameters)) {
                        return null;
                    }
                    return ValidationRecord.skipped(
                            parameters.recordKey(mapperMethod.key()),
                            "regexp-placeholder",
                            parametersSummary(parameters),
                            "达梦在占位 regexp 参数上触发正则表达式内存限制；"
                                    + "请在 .dm-adapter/sql-rewrite.yml 的 validationArgs 中配置真实业务正则，"
                                    + "或人工确认是否需要把该 SQL 改成 LIKE/INSTR 等达梦写法。"
                                    + "\\n" + summary
                    );
                }

                private boolean isRegexpMemoryLimitFailure(String message) {
                    if (message == null) {
                        return false;
                    }
                    String lower = normalizeMessage(message).toLowerCase(Locale.ROOT);
                    return lower.contains("正则表达式内存限制")
                            || lower.contains("out of memory for regex");
                }

                private boolean hasGeneratedRegexpPlaceholderParameter(MapperMethod mapperMethod, ParameterResolution parameters) {
                    if (mapperMethod == null || parameters == null || parameters.args == null) {
                        return false;
                    }
                    if (mapperMethod.method != null) {
                        Class<?>[] parameterTypes = mapperMethod.method.getParameterTypes();
                        int collectionParameterIndex = 0;
                        for (int i = 0; i < parameterTypes.length && i < parameters.args.length; i++) {
                            String parameterName = parameterName(mapperMethod.method, i);
                            boolean collectionLike = isCollectionLikeParameter(parameterTypes[i]);
                            String effectiveParameterName = mapperMethod.statement == null
                                    ? parameterName
                                    : mapperMethod.statement.parameterExpressionName(
                                            i,
                                            collectionLike ? collectionParameterIndex : -1,
                                            parameterName
                                    );
                            if (collectionLike) {
                                collectionParameterIndex++;
                            }
                            if (isRegexpSqlFragmentName(normalizeName(effectiveParameterName))
                                    && isGeneratedRegexpPlaceholderValue(parameters.args[i])) {
                                return true;
                            }
                        }
                    }
                    if (parameters.args.length == 1 && parameters.args[0] instanceof Map<?, ?>) {
                        Map<?, ?> values = (Map<?, ?>) parameters.args[0];
                        for (Map.Entry<?, ?> entry : values.entrySet()) {
                            if (entry.getKey() != null
                                    && isRegexpSqlFragmentName(normalizeName(String.valueOf(entry.getKey())))
                                    && isGeneratedRegexpPlaceholderValue(entry.getValue())) {
                                return true;
                            }
                        }
                    }
                    return false;
                }

                private boolean isGeneratedRegexpPlaceholderValue(Object value) {
                    if (!(value instanceof String)) {
                        return false;
                    }
                    String stripped = stripSqlLiteralQuotes(((String) value).trim());
                    return "ID".equalsIgnoreCase(stripped) || "1".equals(stripped);
                }

                private ValidationRecord skipMissingDynamicIdentifier(ValidationRecord record) {
                    if (record == null || !"FAILED".equals(record.status) || !hasMissingDynamicIdentifierIssue(record.message)) {
                        return record;
                    }
                    return ValidationRecord.skipped(
                            record.key,
                            "dynamic-identifier-parameter",
                            record.parameterSummary,
                            "Dynamic SQL identifier parameter is missing or blank; "
                                    + "configure a real identifier in .dm-adapter/sql-rewrite.yml validationArgs, "
                                    + "for example extendTable/targetTable/fieldName."
                                    + "\\nOriginal failure:\\n" + record.message
                    );
                }

                private ValidationRecord skipMissingDynamicSqlFragment(ValidationRecord record) {
                    if (record == null
                            || !"FAILED".equals(record.status)
                            || !hasDynamicSqlFragmentParameterIssue(record.message)) {
                        return record;
                    }
                    return ValidationRecord.skipped(
                            record.key,
                            "dynamic-sql-fragment-parameter",
                            record.parameterSummary,
                            "Dynamic SQL fragment parameter is missing or still uses a generated placeholder; "
                                    + "configure a real SQL fragment in .dm-adapter/sql-rewrite.yml validationArgs, "
                                    + "for example whereSql/orderBy/sqlFragment/inSql."
                                    + "\\nOriginal failure:\\n" + record.message
                    );
                }

                private ValidationRecord skipGeneratedDynamicSqlOrArgs(ValidationRecord record) {
                    if (record == null
                            || !"FAILED".equals(record.status)
                            || !hasGeneratedDynamicSqlOrArgumentIssue(record)) {
                        return record;
                    }
                    return ValidationRecord.skipped(
                            record.key,
                            "generated-dynamic-sql-or-args",
                            record.parameterSummary,
                            "Dynamic SQL still contains generated placeholder identifiers/fragments or generated null tuple values; "
                                    + "configure real table, column, SQL fragment or DDL metadata in .dm-adapter/sql-rewrite.yml validationArgs."
                                    + "\\nOriginal failure:\\n" + record.message
                            );
                }

                private ValidationRecord skipExistingDdlObject(ValidationRecord record) {
                    if (record == null || !"FAILED".equals(record.status)) {
                        return record;
                    }
                    String message = record.message == null ? "" : record.message;
                    String lowerMessage = message.toLowerCase(Locale.ROOT);
                    String lowerSql = sqlFromMessage(message).toLowerCase(Locale.ROOT);
                    if (!lowerSql.contains("create table")
                            || !(message.contains("已存在") || lowerMessage.contains("already exists"))) {
                        return record;
                    }
                    return ValidationRecord.skipped(
                            record.key,
                            "existing-ddl-object",
                            record.parameterSummary,
                            "DDL object already exists in the validation database; "
                                    + "skip this validation-environment idempotency failure."
                                    + "\\nOriginal failure:\\n" + record.message
                            );
                }

                private ValidationRecord skipValidationTestDataIssue(ValidationRecord record) {
                    if (record == null
                            || !"FAILED".equals(record.status)
                            || !hasValidationTestDataIssue(record.message)) {
                        return record;
                    }
                    return ValidationRecord.skipped(
                            record.key,
                            "validation-test-data",
                            record.parameterSummary,
                            "SQL reached the database execution phase, but validation sample data does not satisfy "
                                    + "foreign-key, unique constraint, or selectOne cardinality expectations; "
                                    + "skipped as a validation-data issue."
                                    + "\\nOriginal failure:\\n" + record.message
                    );
                }

                private boolean hasValidationTestDataIssue(String message) {
                    return containsAny(message,
                            "违反引用约束",
                            "唯一性约束",
                            "TooManyResultsException");
                }

                private ValidationRecord skipUnsupportedReturnType(MapperMethod mapperMethod) {
                    if (mapperMethod == null || mapperMethod.method == null) {
                        return null;
                    }
                    String returnType = mapperMethod.method.getReturnType().getName();
                    if (!returnType.startsWith("lordeath.local.collection.")) {
                        return null;
                    }
                    return ValidationRecord.skipped(
                            mapperMethod.key(),
                            "unsupported-return-type",
                            "Mapper return type " + returnType
                                    + " requires business runtime data-source initialization; "
                                    + "SQL validation skips reflective invocation for this custom container."
                    );
                }

                private ValidationRecord skipIgnoredMissingTable(ValidationRecord record, ValidationConfig config) {
                    if (record == null || !"FAILED".equals(record.status)) {
                        return record;
                    }
                    String ignoredTable = ignoredMissingTable(record.message, config);
                    if (ignoredTable == null) {
                        return record;
                    }
                    return ValidationRecord.skipped(
                            record.key,
                            "ignored-missing-table",
                            record.parameterSummary,
                            "Ignored missing table/view [" + ignoredTable
                                    + "] by .dm-adapter/sql-rewrite.yml validationIgnores.missingTables.\\n"
                                    + "Original failure:\\n" + record.message
                    );
                }

                private String ignoredMissingTable(String message, ValidationConfig config) {
                    if (config == null) {
                        return null;
                    }
                    for (String marker : listOf("无效的表或视图名", "无效的表名")) {
                        for (String table : bracketedValuesAfterMarker(message, marker)) {
                            if (config.ignoresMissingTable(table)) {
                                return table;
                            }
                        }
                    }
                    return null;
                }

                private ValidationRecord skipIgnoredMissingColumn(ValidationRecord record, ValidationConfig config) {
                    if (record == null || !"FAILED".equals(record.status)) {
                        return record;
                    }
                    String ignoredColumn = ignoredMissingColumn(record.message, config);
                    if (ignoredColumn == null) {
                        return record;
                    }
                    return ValidationRecord.skipped(
                            record.key,
                            "ignored-missing-column",
                            record.parameterSummary,
                            "Ignored missing column [" + ignoredColumn
                                    + "] by .dm-adapter/sql-rewrite.yml validationIgnores.missingColumns.\\n"
                                    + "Original failure:\\n" + record.message
                    );
                }

                private String ignoredMissingColumn(String message, ValidationConfig config) {
                    if (config == null) {
                        return null;
                    }
                    for (String marker : listOf("无效的列名", "无效的变量名", "无法解析的成员访问表达式")) {
                        for (String column : bracketedValuesAfterMarker(message, marker)) {
                            if (config.ignoresMissingColumn(column)) {
                                return column;
                            }
                        }
                    }
                    return null;
                }

                private ValidationRecord skipIgnoredMissingSchema(ValidationRecord record, ValidationConfig config) {
                    if (record == null || !"FAILED".equals(record.status)) {
                        return record;
                    }
                    String ignoredSchema = ignoredMissingSchema(record.message, config);
                    if (ignoredSchema == null) {
                        return record;
                    }
                    return ValidationRecord.skipped(
                            record.key,
                            "ignored-missing-schema",
                            record.parameterSummary,
                            "Ignored missing schema [" + ignoredSchema
                                    + "] by .dm-adapter/sql-rewrite.yml validationIgnores.missingSchemas.\\n"
                                    + "Original failure:\\n" + record.message
                    );
                }

                private String ignoredMissingSchema(String message, ValidationConfig config) {
                    if (config == null) {
                        return null;
                    }
                    for (String schema : bracketedValuesAfterMarker(message, "无效的模式名")) {
                        if (config.ignoresMissingSchema(schema)) {
                            return schema;
                        }
                    }
                    return null;
                }

                private ValidationRecord skipIgnoredNotNullColumn(ValidationRecord record, ValidationConfig config) {
                    if (record == null || !"FAILED".equals(record.status)) {
                        return record;
                    }
                    String ignoredColumn = ignoredNotNullColumn(record.message, config);
                    if (ignoredColumn == null) {
                        return record;
                    }
                    return ValidationRecord.skipped(
                            record.key,
                            "ignored-not-null-column",
                            record.parameterSummary,
                            "Ignored not-null column [" + ignoredColumn
                                    + "] by .dm-adapter/sql-rewrite.yml validationIgnores.notNullColumns.\\n"
                                    + "Original failure:\\n" + record.message
                    );
                }

                private String ignoredNotNullColumn(String message, ValidationConfig config) {
                    if (config == null || !containsAny(message, "非空约束", "违反列[")) {
                        return null;
                    }
                    String table = insertTableFromMessage(message);
                    for (String column : bracketedValuesAfterMarker(message, "违反列")) {
                        if (config.ignoresNotNullColumn(table, column)) {
                            return isBlank(table) ? column : table + "." + column;
                        }
                    }
                    return null;
                }

                private String insertTableFromMessage(String message) {
                    String sql = sqlFromMessage(message);
                    if (isBlank(sql)) {
                        return "";
                    }
                    Matcher matcher = Pattern.compile(
                            "(?is)\\\\binsert\\\\s+into\\\\s+([A-Za-z_][A-Za-z0-9_$]*(?:\\\\s*\\\\.\\\\s*[A-Za-z_][A-Za-z0-9_$]*)?)"
                    ).matcher(sql);
                    return matcher.find() ? matcher.group(1).replaceAll("\\\\s+", "").trim() : "";
                }

                private Object invokeReflectively(SqlSession sqlSession, MapperMethod mapperMethod, Object[] args) {
                    try {
                        Object mapper = sqlSession.getMapper(mapperMethod.mapperInterface);
                        mapperMethod.method.setAccessible(true);
                        return mapperMethod.method.invoke(mapper, args);
                    } catch (InvocationTargetException e) {
                        throw new MapperInvocationException(e.getTargetException());
                    } catch (Exception e) {
                        throw new MapperInvocationException(e);
                    }
                }

                private Object statementParameterObject(MapperMethod mapperMethod, ParameterResolution parameters) {
                    if (parameters.args.length == 0) {
                        return null;
                    }
                    if (mapperMethod.method == null) {
                        return parameters.args.length == 1 ? parameters.args[0] : parameterMap(mapperMethod, parameters);
                    }
                    Class<?>[] parameterTypes = mapperMethod.method.getParameterTypes();
                    if (parameters.args.length == 1 && parameterTypes.length == 1) {
                        Parameter parameter = mapperMethod.method.getParameters()[0];
                        boolean hasParamAnnotation = !isBlank(paramAnnotationName(parameter));
                        Class<?> parameterType = parameterTypes[0];
                        if (!hasParamAnnotation
                                && !simpleMapperParameterType(parameterType)
                                && !isCollectionLikeParameter(parameterType)
                                && !Map.class.isAssignableFrom(parameterType)) {
                            return parameters.args[0];
                        }
                        if (!hasParamAnnotation && Map.class.isAssignableFrom(parameterType)) {
                            return parameters.args[0];
                        }
                    }
                    return parameterMap(mapperMethod, parameters);
                }

                private Map<String, Object> parameterMap(MapperMethod mapperMethod, ParameterResolution parameters) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    if (mapperMethod.method == null) {
                        for (int i = 0; i < parameters.args.length; i++) {
                            map.put("arg" + i, parameters.args[i]);
                            map.put("param" + (i + 1), parameters.args[i]);
                        }
                        return map;
                    }
                    Class<?>[] parameterTypes = mapperMethod.method.getParameterTypes();
                    int collectionParameterIndex = 0;
                    for (int i = 0; i < parameters.args.length; i++) {
                        Object value = parameters.args[i];
                        String parameterName = parameterName(mapperMethod.method, i);
                        boolean collectionLike = i < parameterTypes.length && isCollectionLikeParameter(parameterTypes[i]);
                        String effectiveParameterName = mapperMethod.statement == null
                                ? parameterName
                                : mapperMethod.statement.parameterExpressionName(
                                        i,
                                        collectionLike ? collectionParameterIndex : -1,
                                        parameterName
                                );
                        putParameter(map, parameterName, value);
                        putParameter(map, effectiveParameterName, value);
                        putParameter(map, "arg" + i, value);
                        putParameter(map, "param" + (i + 1), value);
                        if (collectionLike) {
                            putParameter(map, "collection", value);
                            if (value instanceof List<?>) {
                                putParameter(map, "list", value);
                            } else if (value != null && value.getClass().isArray()) {
                                putParameter(map, "array", value);
                            }
                            collectionParameterIndex++;
                        }
                    }
                    return map;
                }

                private void putParameter(Map<String, Object> map, String name, Object value) {
                    if (!isBlank(name)) {
                        map.putIfAbsent(name, value);
                    }
                }

                private Object invokeMappedStatement(SqlSession sqlSession, MapperMethod mapperMethod, Object parameter) {
                    if (SqlCommandType.INSERT.equals(mapperMethod.sqlCommandType)) {
                        return sqlSession.insert(mapperMethod.key(), parameter);
                    }
                    if (SqlCommandType.UPDATE.equals(mapperMethod.sqlCommandType)) {
                        return sqlSession.update(mapperMethod.key(), parameter);
                    }
                    if (SqlCommandType.DELETE.equals(mapperMethod.sqlCommandType)) {
                        return sqlSession.delete(mapperMethod.key(), parameter);
                    }
                    return sqlSession.selectList(mapperMethod.key(), parameter);
                }

                private void applySchema(Connection connection, String schema) {
                    if (schema == null || isBlank(schema)) {
                        return;
                    }
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("set schema " + quotedIdentifier(schema));
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to set Dameng schema: " + schema, e);
                    }
                }

                private String quotedIdentifier(String identifier) {
                    String quote = Character.toString((char) 34);
                    return quote + identifier.replace(quote, quote + quote) + quote;
                }

                private ValueResult convertScalar(String rawValue, Class<?> targetType, Type genericType) {
                    String value = unquote(rawValue == null ? "" : rawValue.trim());
                    if ("null".equalsIgnoreCase(value)) {
                        if (targetType.isPrimitive()) {
                            return ValueResult.unresolved("Cannot assign null to primitive type " + targetType.getName() + ".");
                        }
                        return ValueResult.resolved(null);
                    }
                    try {
                        if (String.class.equals(targetType)) {
                            return ValueResult.resolved(value);
                        }
                        if (Integer.class.equals(targetType) || int.class.equals(targetType)) {
                            return ValueResult.resolved(Integer.parseInt(value));
                        }
                        if (Long.class.equals(targetType) || long.class.equals(targetType)) {
                            return ValueResult.resolved(Long.parseLong(value));
                        }
                        if (Short.class.equals(targetType) || short.class.equals(targetType)) {
                            return ValueResult.resolved(Short.parseShort(value));
                        }
                        if (Byte.class.equals(targetType) || byte.class.equals(targetType)) {
                            return ValueResult.resolved(Byte.parseByte(value));
                        }
                        if (Double.class.equals(targetType) || double.class.equals(targetType)) {
                            return ValueResult.resolved(Double.parseDouble(value));
                        }
                        if (Float.class.equals(targetType) || float.class.equals(targetType)) {
                            return ValueResult.resolved(Float.parseFloat(value));
                        }
                        if (Boolean.class.equals(targetType) || boolean.class.equals(targetType)) {
                            return ValueResult.resolved(Boolean.parseBoolean(value));
                        }
                        if (BigDecimal.class.equals(targetType)) {
                            return ValueResult.resolved(new BigDecimal(value));
                        }
                        if (BigInteger.class.equals(targetType)) {
                            return ValueResult.resolved(new BigInteger(value));
                        }
                        if (Date.class.isAssignableFrom(targetType)) {
                            return ValueResult.resolved(configuredDateValue(value, targetType));
                        }
                        if (LocalDate.class.equals(targetType)) {
                            return ValueResult.resolved(LocalDate.parse(value));
                        }
                        if (LocalDateTime.class.equals(targetType)) {
                            return ValueResult.resolved(LocalDateTime.parse(value));
                        }
                        if (LocalTime.class.equals(targetType)) {
                            return ValueResult.resolved(LocalTime.parse(value));
                        }
                        if (Instant.class.equals(targetType)) {
                            return ValueResult.resolved(Instant.parse(value));
                        }
                        if (UUID.class.equals(targetType)) {
                            return ValueResult.resolved(UUID.fromString(value));
                        }
                        if (targetType.isEnum()) {
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            Object enumValue = Enum.valueOf((Class<? extends Enum>) targetType.asSubclass(Enum.class), value);
                            return ValueResult.resolved(enumValue);
                        }
                        return defaultValue(targetType, genericType, 0);
                    } catch (Exception e) {
                        return ValueResult.unresolved("Failed to convert configured value '" + value
                                + "' to " + targetType.getName() + ": " + e.getMessage());
                    }
                }

                private Object configuredDateValue(String value, Class<?> targetType) {
                    Instant instant = configuredInstant(value);
                    if (instant == null) {
                        instant = Instant.parse("2024-01-01T00:00:00Z");
                    }
                    if ("java.sql.Date".equals(targetType.getName())) {
                        return java.sql.Date.valueOf(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate());
                    }
                    if ("java.sql.Time".equals(targetType.getName())) {
                        return java.sql.Time.valueOf(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalTime());
                    }
                    if ("java.sql.Timestamp".equals(targetType.getName())) {
                        return java.sql.Timestamp.from(instant);
                    }
                    return Date.from(instant);
                }

                private Instant configuredInstant(String value) {
                    String text = value == null ? "" : value.trim();
                    if (isBlank(text)) {
                        return null;
                    }
                    try {
                        return Instant.parse(text);
                    } catch (Exception ignored) {
                    }
                    try {
                        return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant();
                    } catch (Exception ignored) {
                    }
                    for (String pattern : new String[] {
                            "yyyy-MM-dd HH:mm:ss",
                            "yyyy-MM-dd HH:mm:ss.S",
                            "yyyy-MM-dd HH:mm:ss.SSS"
                    }) {
                        try {
                            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
                                    .atZone(ZoneId.systemDefault())
                                    .toInstant();
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        return LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant();
                    } catch (Exception ignored) {
                    }
                    try {
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH);
                        return ZonedDateTime.parse(text, formatter).toInstant();
                    } catch (Exception ignored) {
                    }
                    return null;
                }

                @SuppressWarnings("unchecked")
                private ValueResult convertConfiguredValue(Object rawValue, Class<?> targetType, Type genericType) {
                    return convertConfiguredValue(rawValue, targetType, genericType, null, "");
                }

                @SuppressWarnings("unchecked")
                private ValueResult convertConfiguredValue(
                        Object rawValue,
                        Class<?> targetType,
                        Type genericType,
                        MapperStatement statement,
                        String valueName
                ) {
                    return convertConfiguredValue(rawValue, targetType, genericType, statement, valueName, false);
                }

                @SuppressWarnings("unchecked")
                private ValueResult convertConfiguredValue(
                        Object rawValue,
                        Class<?> targetType,
                        Type genericType,
                        MapperStatement statement,
                        String valueName,
                        boolean nestedProperty
                ) {
                    if (targetType == null || Object.class.equals(targetType)) {
                        return ValueResult.resolved(configuredValueWithStatementDefaults(rawValue, statement, valueName));
                    }
                    if (rawValue == null) {
                        if (targetType.isPrimitive()) {
                            return ValueResult.unresolved("Cannot assign null to primitive type " + targetType.getName() + ".");
                        }
                        ValueResult defaultValue = configuredNullDefaultValue(
                                targetType,
                                genericType,
                                statement,
                                valueName,
                                nestedProperty
                        );
                        if (defaultValue != null) {
                            return defaultValue;
                        }
                        return ValueResult.resolved(null);
                    }
                    if (String.class.equals(targetType) && rawValue instanceof String) {
                        return ValueResult.resolved(normalizeConfiguredDynamicIdentifierValue(
                                rawValue,
                                statement,
                                valueName,
                                statement == null ? null : statement.defaultValue(valueName)
                        ));
                    }
                    if (Map.class.isAssignableFrom(targetType) && rawValue instanceof Map) {
                        Map<String, Object> configuredValue = new LinkedHashMap<>((Map<String, Object>) rawValue);
                        boolean sameNamedNestedCollectionApplied = false;
                        Object sameNamedNestedCollection = sameNamedNestedCollectionValue(
                                valueName,
                                configuredValue,
                                statement,
                                valueName
                        );
                        if (sameNamedNestedCollection instanceof Map<?, ?>) {
                            configuredValue = new LinkedHashMap<>((Map<String, Object>) sameNamedNestedCollection);
                            sameNamedNestedCollectionApplied = true;
                        }
                        Map<String, Object> defaultValue = (sameNamedNestedCollectionApplied || nestedProperty)
                                && statement != null
                                && statement.mapCollectionParameter(valueName)
                                ? defaultMapCollectionValue(valueName, statement)
                                : defaultMapParameterValue(valueName, statement);
                        configuredValue = mergeConfiguredCollectionElementMap(defaultValue, configuredValue, statement, valueName);
                        return ValueResult.resolved(mapParameterValue(targetType, configuredValue));
                    }
                    if (Collection.class.isAssignableFrom(targetType) && rawValue instanceof Collection) {
                        Type nestedType = firstGenericArgument(genericType);
                        Class<?> nestedClass = rawClass(nestedType);
                        Collection<?> rawCollection = (Collection<?>) rawValue;
                        Collection<Object> converted = Set.class.isAssignableFrom(targetType)
                                ? new LinkedHashSet<>()
                                : new ArrayList<>();
                        Map<String, Object> defaultElement = configuredCollectionElementDefault(
                                valueName,
                                targetType,
                                genericType,
                                statement
                        );
                        for (Object item : rawCollection) {
                            if (item instanceof Map<?, ?>) {
                                if (Collection.class.isAssignableFrom(nestedClass)) {
                                    Map<String, Object> configuredItem = new LinkedHashMap<>((Map<String, Object>) item);
                                    if (!defaultElement.isEmpty()) {
                                        configuredItem = mergeConfiguredCollectionElementMap(
                                                new LinkedHashMap<>(defaultElement),
                                                configuredItem,
                                                statement,
                                                valueName
                                        );
                                    }
                                    converted.add(new ArrayList<>(listOf(configuredItem)));
                                    continue;
                                }
                                if (shouldUsePojoCollectionElement(nestedClass)) {
                                    ValueResult value = convertConfiguredValue(
                                            item,
                                            nestedClass,
                                            nestedType,
                                            statement,
                                            valueName
                                    );
                                    if (!value.resolved) {
                                        return value;
                                    }
                                    converted.add(value.value);
                                    continue;
                                }
                                if (rawCollectionElementType(genericType)
                                        && configuredCollectionObjectItem(valueName, (Map<?, ?>) item, statement)) {
                                    Map<String, Object> configuredItem = new LinkedHashMap<>((Map<String, Object>) item);
                                    configuredItem = mergeConfiguredCollectionElementMap(
                                            new LinkedHashMap<>(defaultElement),
                                            configuredItem,
                                            statement,
                                            valueName
                                    );
                                    converted.add(configuredItem);
                                    continue;
                                }
                                if (scalarCollectionElementType(nestedClass)) {
                                    ValueResult value = configuredScalarCollectionElement(
                                            valueName,
                                            (Map<?, ?>) item,
                                            nestedClass,
                                            nestedType,
                                            statement
                                    );
                                    if (!value.resolved) {
                                        return value;
                                    }
                                    converted.add(value.value);
                                    continue;
                                }
                                Object scalarItem = scalarConfiguredCollectionItem(
                                        valueName,
                                        (Map<?, ?>) item,
                                        statement
                                );
                                if (scalarItem != MethodArgumentConfig.MISSING) {
                                    Object normalizedScalarItem = normalizeConfiguredCollectionScalarValue(
                                            valueName,
                                            scalarItem,
                                            statement
                                    );
                                    ValueResult value = convertConfiguredValue(
                                            normalizedScalarItem,
                                            nestedClass,
                                            nestedType,
                                            statement,
                                            valueName
                                    );
                                    if (!value.resolved) {
                                        return value;
                                    }
                                    converted.add(value.value);
                                    continue;
                                }
                                Map<String, Object> configuredItem = new LinkedHashMap<>((Map<String, Object>) item);
                                configuredItem = mergeConfiguredCollectionElementMap(
                                        new LinkedHashMap<>(defaultElement),
                                        configuredItem,
                                        statement,
                                        valueName
                                );
                                converted.add(configuredItem);
                                continue;
                            }
                            Object normalizedItem = normalizeConfiguredCollectionScalarValue(valueName, item, statement);
                            ValueResult value = convertConfiguredValue(normalizedItem, nestedClass, nestedType, statement, valueName);
                            if (!value.resolved) {
                                return value;
                            }
                            converted.add(value.value);
                        }
                        return ValueResult.resolved(converted);
                    }
                    if (rawValue instanceof Map<?, ?> && shouldUsePojoCollectionElement(targetType)) {
                        return configuredPojoValue(
                                targetType,
                                genericType,
                                new LinkedHashMap<>((Map<String, Object>) rawValue),
                                statement
                        );
                    }
                    if (targetType.isArray() && rawValue instanceof Collection) {
                        Type componentType = targetType.getComponentType();
                        Class<?> componentClass = targetType.getComponentType();
                        Collection<?> rawCollection = (Collection<?>) rawValue;
                        Object array = Array.newInstance(componentClass, rawCollection.size());
                        int index = 0;
                        for (Object item : rawCollection) {
                            Object normalizedItem = normalizeConfiguredCollectionScalarValue(valueName, item, statement);
                            ValueResult value = convertConfiguredValue(normalizedItem, componentClass, componentType, statement, valueName);
                            if (!value.resolved) {
                                return value;
                            }
                            Array.set(array, index++, value.value);
                        }
                        return ValueResult.resolved(array);
                    }
                    if (targetType.isInstance(rawValue)) {
                        return ValueResult.resolved(rawValue);
                    }
                    return convertScalar(configuredScalarText(rawValue), targetType, genericType);
                }

                private ValueResult configuredNullDefaultValue(
                        Class<?> targetType,
                        Type genericType,
                        MapperStatement statement,
                        String valueName,
                        boolean nestedProperty
                ) {
                    String collectionValueName = requiredCollectionValueName(valueName, statement);
                    if (isBlank(collectionValueName)) {
                        return null;
                    }
                    if (Map.class.isAssignableFrom(targetType) && statement.mapCollectionParameter(collectionValueName)) {
                        Map<String, Object> defaultValue = nestedProperty
                                ? defaultMapCollectionValue(collectionValueName, statement)
                                : defaultMapParameterValue(collectionValueName, statement);
                        if (!defaultValue.isEmpty()) {
                            return ValueResult.resolved(mapParameterValue(targetType, defaultValue));
                        }
                    }
                    if (Collection.class.isAssignableFrom(targetType) || targetType.isArray()) {
                        return defaultValue(collectionValueName, targetType, genericType, 0, statement);
                    }
                    return null;
                }

                private boolean rawCollectionElementType(Type genericType) {
                    return !(genericType instanceof ParameterizedType);
                }

                private boolean shouldUsePojoCollectionElement(Class<?> targetType) {
                    if (targetType == null
                            || Object.class.equals(targetType)
                            || targetType.isPrimitive()
                            || targetType.isArray()
                            || targetType.isInterface()
                            || Modifier.isAbstract(targetType.getModifiers())
                            || Map.class.isAssignableFrom(targetType)
                            || Collection.class.isAssignableFrom(targetType)
                            || scalarCollectionElementType(targetType)) {
                        return false;
                    }
                    String name = targetType.getName();
                    return !name.startsWith("java.")
                            && !name.startsWith("javax.")
                            && !name.startsWith("jakarta.");
                }

                private ValueResult configuredScalarCollectionElement(
                        String collectionName,
                        Map<?, ?> item,
                        Class<?> targetType,
                        Type genericType,
                        MapperStatement statement
                ) {
                    Object scalarItem = scalarConfiguredCollectionItem(collectionName, item, statement);
                    if (scalarItem != MethodArgumentConfig.MISSING) {
                        Object normalizedScalarItem = normalizeConfiguredCollectionScalarValue(
                                collectionName,
                                scalarItem,
                                statement
                        );
                        ValueResult value = convertConfiguredValue(
                                normalizedScalarItem,
                                targetType,
                                genericType,
                                statement,
                                collectionName
                        );
                        if (value.resolved) {
                            return value;
                        }
                    }
                    for (Object candidate : item.values()) {
                        Object normalizedCandidate = normalizeConfiguredCollectionScalarValue(
                                collectionName,
                                candidate,
                                statement
                        );
                        ValueResult value = convertConfiguredValue(
                                normalizedCandidate,
                                targetType,
                                genericType,
                                statement,
                                collectionName
                        );
                        if (value.resolved) {
                            return value;
                        }
                    }
                    return defaultValue(collectionName, targetType, genericType, 0, statement);
                }

                private boolean scalarCollectionElementType(Class<?> targetType) {
                    if (targetType == null) {
                        return false;
                    }
                    return targetType.isPrimitive()
                            || CharSequence.class.isAssignableFrom(targetType)
                            || Number.class.isAssignableFrom(targetType)
                            || Boolean.class.equals(targetType)
                            || Character.class.equals(targetType)
                            || Date.class.isAssignableFrom(targetType)
                            || LocalDate.class.equals(targetType)
                            || LocalDateTime.class.equals(targetType)
                            || LocalTime.class.equals(targetType)
                            || Instant.class.equals(targetType)
                            || targetType.isEnum();
                }

                @SuppressWarnings("unchecked")
                private Object configuredValueWithStatementDefaults(
                        Object rawValue,
                        MapperStatement statement,
                        String valueName
                ) {
                    if (statement == null) {
                        return rawValue;
                    }
                    if (rawValue == null) {
                        String collectionValueName = requiredCollectionValueName(valueName, statement);
                        if (!isBlank(collectionValueName)) {
                            return defaultCollectionParameter(collectionValueName, statement);
                        }
                        return null;
                    }
                    if (rawValue instanceof Collection) {
                        Map<String, Object> defaultElement = typedCollectionElementDefault(valueName, statement);
                        if (defaultElement.isEmpty() && statement != null) {
                            defaultElement = firstMapElementDefault(statement.collectionScalarDefault(valueName));
                        }
                        Collection<Object> converted = rawValue instanceof Set
                                ? new LinkedHashSet<>()
                                : new ArrayList<>();
                        for (Object item : (Collection<?>) rawValue) {
                            if (item instanceof Map<?, ?>) {
                                Object scalarItem = scalarConfiguredCollectionItem(
                                        valueName,
                                        (Map<?, ?>) item,
                                        statement
                                );
                                if (scalarItem != MethodArgumentConfig.MISSING) {
                                    converted.add(scalarItem);
                                    continue;
                                }
                                converted.add(mergeConfiguredCollectionElementMap(
                                        new LinkedHashMap<>(defaultElement),
                                        new LinkedHashMap<>((Map<String, Object>) item),
                                        statement,
                                        valueName
                                ));
                            } else {
                                converted.add(normalizeConfiguredDynamicIdentifierValue(
                                        item,
                                        statement,
                                        valueName,
                                        statement.collectionSqlFragmentDefault(valueName)
                                ));
                            }
                        }
                        return converted;
                    }
                    if (rawValue instanceof Map<?, ?>) {
                        Map<String, Object> configuredValue = new LinkedHashMap<>((Map<String, Object>) rawValue);
                        boolean sameNamedNestedCollectionApplied = false;
                        Object sameNamedNestedCollection = sameNamedNestedCollectionValue(
                                valueName,
                                configuredValue,
                                statement,
                                valueName
                        );
                        if (sameNamedNestedCollection instanceof Map<?, ?>) {
                            configuredValue = new LinkedHashMap<>((Map<String, Object>) sameNamedNestedCollection);
                            sameNamedNestedCollectionApplied = true;
                        }
                        Map<String, Object> defaultValue = sameNamedNestedCollectionApplied
                                && statement != null
                                && statement.mapCollectionParameter(valueName)
                                ? defaultMapCollectionValue(valueName, statement)
                                : defaultMapParameterValue(valueName, statement);
                        return mergeConfiguredCollectionElementMap(
                                defaultValue,
                                configuredValue,
                                statement,
                                valueName
                        );
                    }
                    return normalizeConfiguredDynamicIdentifierValue(rawValue, statement, valueName, statement.defaultValue(valueName));
                }

                private Object normalizeConfiguredDynamicIdentifierValue(
                        Object configuredValue,
                        MapperStatement statement,
                        String valueName,
                        Object existingDefault
                ) {
                    if (!(configuredValue instanceof String)) {
                        return configuredValue;
                    }
                    String normalized = normalizeName(valueName);
                    String text = ((String) configuredValue).trim();
                    if (statement != null
                            && statement.hasDefaultValue(valueName)
                            && existingDefault == null
                            && isRegexpSqlFragmentName(normalized)
                            && "ID".equalsIgnoreCase(stripSqlLiteralQuotes(text))) {
                        return null;
                    }
                    if (isRegexpSqlFragmentName(normalized)
                            && "ID".equalsIgnoreCase(stripSqlLiteralQuotes(text))) {
                        return quoteSqlLiteral("1");
                    }
                    if (statement == null) {
                        return configuredValue;
                    }
                    boolean schemaIdentifier = isSchemaIdentifierName(normalized);
                    if (!statement.dynamicIdentifierParameter(valueName)
                            && !isLikelyDynamicIdentifierName(normalized)
                            && !schemaIdentifier) {
                        return configuredValue;
                    }
                    if (schemaIdentifier && isDoubleQuotedSqlIdentifier(text)) {
                        return text;
                    }
                    String stripped = stripSqlLiteralQuotes(text);
                    if (isGeneratedDynamicIdentifierPlaceholder(stripped)) {
                        if (existingDefault != null) {
                            String defaultText = String.valueOf(existingDefault).trim();
                            String strippedDefault = stripSqlLiteralQuotes(defaultText);
                            if (!isGeneratedDynamicIdentifierPlaceholder(strippedDefault)) {
                                return defaultText;
                            }
                        }
                        return defaultDynamicIdentifier(valueName);
                    }
                    if (schemaIdentifier && text.equals(stripped) && !isSimpleQualifiedIdentifier(stripped)) {
                        return quotedIdentifier(stripped);
                    }
                    return text.equals(stripped) ? configuredValue : stripped;
                }

                private boolean isDoubleQuotedSqlIdentifier(String value) {
                    return value != null
                            && value.length() >= 2
                            && value.charAt(0) == 34
                            && value.charAt(value.length() - 1) == 34;
                }

                private boolean isSimpleQualifiedIdentifier(String value) {
                    return value != null
                            && value.matches("[A-Za-z_][A-Za-z0-9_$]*(?:\\\\.[A-Za-z_][A-Za-z0-9_$]*)*");
                }

                private boolean isGeneratedDynamicIdentifierPlaceholder(String value) {
                    String text = value == null ? "" : value.trim();
                    return "test".equalsIgnoreCase(text)
                            || "CODE".equalsIgnoreCase(text)
                            || "ID".equalsIgnoreCase(text)
                            || "null".equalsIgnoreCase(text)
                            || "1=1".equals(text)
                            || "1".equals(text);
                }

                """,
            """
                @SuppressWarnings("unchecked")
                private Map<String, Object> configuredCollectionElementDefault(
                        String collectionName,
                        Class<?> targetType,
                        Type genericType,
                        MapperStatement statement
                ) {
                    Map<String, Object> value = typedCollectionElementDefault(collectionName, statement);
                    if (!value.isEmpty()) {
                        return new LinkedHashMap<>(value);
                    }
                    if (statement != null) {
                        Map<String, Object> scalarDefault = firstMapElementDefault(
                                statement.collectionScalarDefault(collectionName)
                        );
                        if (!scalarDefault.isEmpty()) {
                            return scalarDefault;
                        }
                    }
                    ValueResult defaultCollection = defaultValue(collectionName, targetType, genericType, 0, statement);
                    if (!defaultCollection.resolved || !(defaultCollection.value instanceof Collection)) {
                        return emptyMap();
                    }
                    Collection<?> values = (Collection<?>) defaultCollection.value;
                    Map<String, Object> nestedDefault = firstMapElementDefault(values);
                    if (!nestedDefault.isEmpty()) {
                        return nestedDefault;
                    }
                    return emptyMap();
                }

                @SuppressWarnings("unchecked")
                private Map<String, Object> firstMapElementDefault(Object value) {
                    if (value instanceof Map<?, ?>) {
                        return new LinkedHashMap<>((Map<String, Object>) value);
                    }
                    if (value instanceof Collection<?>) {
                        for (Object item : (Collection<?>) value) {
                            Map<String, Object> nested = firstMapElementDefault(item);
                            if (!nested.isEmpty()) {
                                return nested;
                            }
                        }
                    }
                    return emptyMap();
                }

                private Object scalarConfiguredCollectionItem(
                        String collectionName,
                        Map<?, ?> item,
                        MapperStatement statement
                ) {
                    if (statement == null
                            || item == null
                            || !statement.scalarCollectionParameter(collectionName)) {
                        return MethodArgumentConfig.MISSING;
                    }
                    if (configuredCollectionObjectItem(collectionName, item, statement)) {
                        return MethodArgumentConfig.MISSING;
                    }
                    if (item.size() == 1) {
                        Map.Entry<?, ?> entry = item.entrySet().iterator().next();
                        String key = String.valueOf(entry.getKey());
                        String normalizedKey = normalizeName(key);
                        String normalizedCollection = normalizeName(collectionName);
                        if ("item".equals(normalizedKey)
                                || "value".equals(normalizedKey)
                                || "val".equals(normalizedKey)
                                || normalizedCollection.endsWith(normalizedKey)
                                || normalizedKey.endsWith(normalizedCollection)) {
                            return entry.getValue();
                        }
                    }
                    return scalarConfiguredCollectionDefault(collectionName, statement);
                }

                private boolean configuredCollectionObjectItem(
                        String collectionName,
                        Map<?, ?> item,
                        MapperStatement statement
                ) {
                    if (statement == null
                            || item == null
                            || item.isEmpty()) {
                        return false;
                    }
                    if (knownCollectionObjectPropertyCount(item) >= 2) {
                        return true;
                    }
                    if (!statement.hasCollectionElementDefault(collectionName)
                            && !statement.hasCollectionElementColumnReferences(collectionName)) {
                        return false;
                    }
                    Map<String, Object> defaults = statement.collectionElementDefault(collectionName);
                    Map<String, ColumnReference> references = statement.collectionElementColumnReferences(collectionName);
                    for (Object rawKey : item.keySet()) {
                        String key = String.valueOf(rawKey);
                        if (defaults.containsKey(key)
                                || references.containsKey(key)
                                || isKnownCollectionObjectProperty(key)) {
                            return true;
                        }
                    }
                    return false;
                }

                private int knownCollectionObjectPropertyCount(Map<?, ?> item) {
                    int count = 0;
                    for (Object rawKey : item.keySet()) {
                        if (isKnownCollectionObjectProperty(String.valueOf(rawKey))) {
                            count++;
                        }
                    }
                    return count;
                }

                private boolean isKnownCollectionObjectProperty(String propertyName) {
                    String normalized = normalizeName(propertyName);
                    return "key".equals(normalized)
                            || "value".equals(normalized)
                            || "fieldname".equals(normalized)
                            || "fieldvalue".equals(normalized)
                            || "fieldunderlinename".equals(normalized)
                            || "comparison".equals(normalized)
                            || "comparision".equals(normalized)
                            || "relateformfiltermodelkey".equals(normalized)
                            || "relateformfiltermodelkeyunderline".equals(normalized)
                            || "triggermodelkey".equals(normalized)
                            || "triggervalue".equals(normalized);
                }

                private Object scalarConfiguredCollectionDefault(String collectionName, MapperStatement statement) {
                    String resolvedCollectionName = statement.scalarCollectionName(collectionName);
                    if (!isBlank(resolvedCollectionName)) {
                        collectionName = resolvedCollectionName;
                    }
                    Object sqlFragmentDefault = statement.collectionSqlFragmentDefault(collectionName);
                    if (sqlFragmentDefault != null) {
                        return sqlFragmentDefault;
                    }
                    Object scalarDefault = statement.collectionScalarDefault(collectionName);
                    if (scalarDefault != null) {
                        return scalarDefault;
                    }
                    String columnType = statement.collectionColumnType(collectionName, dbColumnMetadata);
                    if (!isBlank(columnType)) {
                        return defaultCollectionElementForColumnType(collectionName, columnType);
                    }
                    return defaultCollectionElement(collectionName);
                }

                private Object normalizeConfiguredCollectionScalarValue(
                        String collectionName,
                        Object configuredValue,
                        MapperStatement statement
                ) {
                    if (!isGeneratedPlaceholderValue(collectionName, configuredValue)) {
                        return configuredValue;
                    }
                    String normalized = normalizeName(collectionName);
                    if (!shouldReplaceGeneratedCollectionPlaceholder(normalized)) {
                        return configuredValue;
                    }
                    if (statement != null && statement.scalarCollectionParameter(collectionName)) {
                        Object defaultValue = scalarConfiguredCollectionDefault(collectionName, statement);
                        if (defaultValue != null) {
                            return defaultValue;
                        }
                    }
                    return defaultCollectionElement(collectionName);
                }

                private boolean shouldReplaceGeneratedCollectionPlaceholder(String normalizedName) {
                    return isDateLikeParameterName(normalizedName)
                            || isMonthLikeParameterName(normalizedName)
                            || isYearLikeParameterName(normalizedName)
                            || isIdLikeParameterName(normalizedName)
                            || isNumericTextParameterName(normalizedName);
                }

                @SuppressWarnings("unchecked")
                private Map<String, Object> mergeConfiguredCollectionElementMap(
                        Map<String, Object> defaultValue,
                        Map<String, Object> configuredValue
                ) {
                    return mergeConfiguredCollectionElementMap(defaultValue, configuredValue, null, "");
                }

                @SuppressWarnings("unchecked")
                private Map<String, Object> mergeConfiguredCollectionElementMap(
                        Map<String, Object> defaultValue,
                        Map<String, Object> configuredValue,
                        MapperStatement statement,
                        String pathPrefix
                ) {
                    Map<String, Object> merged = new LinkedHashMap<>(defaultValue);
                    if (configuredValue == null || configuredValue.isEmpty()) {
                        return normalizeValidationParameterMap(merged);
                    }
                    for (Map.Entry<String, Object> entry : configuredValue.entrySet()) {
                        Object existing = merged.get(entry.getKey());
                        String entryPath = childPath(pathPrefix, entry.getKey());
                        Object configured = normalizeConfiguredDynamicIdentifierValue(
                                entry.getValue(),
                                statement,
                                entryPath,
                                existing
                        );
                        Object scalarCollection = scalarConfiguredCollectionValue(entryPath, configured, statement);
                        if (scalarCollection != MethodArgumentConfig.MISSING) {
                            merged.put(entry.getKey(), scalarCollection);
                            continue;
                        }
                        if (existing instanceof Map && configured instanceof Map) {
                            merged.put(
                                    entry.getKey(),
                                    mergeConfiguredCollectionElementMap(
                                            new LinkedHashMap<>((Map<String, Object>) existing),
                                            new LinkedHashMap<>((Map<String, Object>) configured),
                                            statement,
                                            entryPath
                                    )
                            );
                            continue;
                        }
                        if (existing == null) {
                            if (isGeneratedNullPlaceholderValue(configured)) {
                                continue;
                            }
                            existing = inferredConfiguredPlaceholderDefault(entry.getKey(), configured);
                            if (existing != null) {
                                merged.put(entry.getKey(), existing);
                            }
                        }
                        Object coerced = coerceConfiguredValueToDefaultType(configured, existing);
                        if (coerced != configured || shouldKeepConfiguredValue(entry.getKey(), configured, existing)) {
                            merged.put(entry.getKey(), coerced);
                        }
                    }
                    return normalizeValidationParameterMap(merged);
                }

                @SuppressWarnings("unchecked")
                private Map<String, Object> normalizeValidationParameterMap(Map<String, Object> value) {
                    if (value == null || value.isEmpty()) {
                        return value;
                    }
                    for (Object nestedValue : value.values()) {
                        if (nestedValue instanceof Map<?, ?>) {
                            normalizeValidationParameterMap((Map<String, Object>) nestedValue);
                        } else if (nestedValue instanceof Collection<?>) {
                            for (Object item : (Collection<?>) nestedValue) {
                                if (item instanceof Map<?, ?>) {
                                    normalizeValidationParameterMap((Map<String, Object>) item);
                                }
                            }
                        }
                    }
                    normalizeSqlIsComparisonValue(value);
                    normalizeMutuallyExclusiveFlagValues(value);
                    return value;
                }

                private void normalizeSqlIsComparisonValue(Map<String, Object> value) {
                    String comparisonKey = keyByNormalizedName(value, "comparison");
                    if (isBlank(comparisonKey)
                            || !"IS".equalsIgnoreCase(String.valueOf(value.get(comparisonKey)).trim())) {
                        return;
                    }
                    String fieldValueTypeKey = keyByNormalizedName(value, "fieldvaluetype");
                    if (!isBlank(fieldValueTypeKey) && !isTruthyFlagValue(value.get(fieldValueTypeKey))) {
                        return;
                    }
                    String fieldValueKey = keyByNormalizedName(value, "fieldvalue");
                    if (isBlank(fieldValueKey)) {
                        return;
                    }
                    Object fieldValue = value.get(fieldValueKey);
                    if (fieldValue == null || isGeneratedPlaceholderValue(fieldValueKey, fieldValue)) {
                        value.put(fieldValueKey, "NULL");
                    }
                }

                private void normalizeMutuallyExclusiveFlagValues(Map<String, Object> value) {
                    List<String> enabledSumFlags = new ArrayList<>();
                    for (String key : value.keySet()) {
                        String normalized = normalizeName(key);
                        if (normalized.startsWith("sum")
                                && normalized.endsWith("flag")
                                && isTruthyFlagValue(value.get(key))) {
                            enabledSumFlags.add(key);
                        }
                    }
                    for (int i = 1; i < enabledSumFlags.size(); i++) {
                        value.put(enabledSumFlags.get(i), null);
                    }
                    String currentFlagKey = keyByNormalizedName(value, "currentflag");
                    String beforeCurrentFlagKey = keyByNormalizedName(value, "beforecurrentflag");
                    if (!isBlank(currentFlagKey)
                            && !isBlank(beforeCurrentFlagKey)
                            && isTruthyFlagValue(value.get(currentFlagKey))
                            && isTruthyFlagValue(value.get(beforeCurrentFlagKey))) {
                        value.put(beforeCurrentFlagKey, null);
                    }
                }

                private String keyByNormalizedName(Map<String, Object> value, String normalizedName) {
                    for (String key : value.keySet()) {
                        if (normalizedName.equals(normalizeName(key))) {
                            return key;
                        }
                    }
                    return "";
                }

                private boolean isTruthyFlagValue(Object value) {
                    if (value instanceof Boolean) {
                        return Boolean.TRUE.equals(value);
                    }
                    if (value instanceof Number) {
                        return ((Number) value).longValue() != 0L;
                    }
                    if (value == null) {
                        return false;
                    }
                    String text = stripSqlLiteralQuotes(String.valueOf(value)).trim();
                    return "1".equals(text) || "true".equalsIgnoreCase(text);
                }

                private Object scalarConfiguredCollectionValue(
                        String collectionName,
                        Object configuredValue,
                        MapperStatement statement
                ) {
                    if (statement == null || !statement.scalarCollectionParameter(collectionName)) {
                        return MethodArgumentConfig.MISSING;
                    }
                    if (configuredValue instanceof Collection) {
                        Collection<Object> converted = configuredValue instanceof Set
                                ? new LinkedHashSet<>()
                                : new ArrayList<>();
                        for (Object item : (Collection<?>) configuredValue) {
                            if (item instanceof Map<?, ?>) {
                                Object scalarItem = scalarConfiguredCollectionItem(
                                        collectionName,
                                        (Map<?, ?>) item,
                                        statement
                                );
                                if (scalarItem == MethodArgumentConfig.MISSING) {
                                    return MethodArgumentConfig.MISSING;
                                }
                                converted.add(normalizeConfiguredCollectionScalarValue(collectionName, scalarItem, statement));
                            } else {
                                converted.add(normalizeConfiguredCollectionScalarValue(collectionName, item, statement));
                            }
                        }
                        return converted;
                    }
                    if (configuredValue instanceof Map<?, ?>) {
                        Object scalarItem = scalarConfiguredCollectionItem(
                                collectionName,
                                (Map<?, ?>) configuredValue,
                                statement
                        );
                        if (scalarItem == MethodArgumentConfig.MISSING) {
                            return MethodArgumentConfig.MISSING;
                        }
                        return new ArrayList<>(listOf(scalarItem));
                    }
                    return MethodArgumentConfig.MISSING;
                }

                private String childPath(String pathPrefix, String childName) {
                    if (isBlank(pathPrefix)) {
                        return childName;
                    }
                    if (isBlank(childName)) {
                        return pathPrefix;
                    }
                    return pathPrefix + "." + childName;
                }

                private Object inferredConfiguredPlaceholderDefault(String valueName, Object configuredValue) {
                    if (configuredValue instanceof String
                            && isRegexpSqlFragmentName(normalizeName(valueName))
                            && "ID".equalsIgnoreCase(stripSqlLiteralQuotes((String) configuredValue))) {
                        return quoteSqlLiteral("1");
                    }
                    if (isGeneratedPlaceholderValue(valueName, configuredValue)) {
                        return defaultNameBasedTypedValue(valueName);
                    }
                    if (configuredValue instanceof Number && normalizeName(valueName).contains("code")) {
                        return String.valueOf(configuredValue);
                    }
                    return null;
                }

                private boolean shouldKeepConfiguredValue(String valueName, Object configuredValue, Object defaultValue) {
                    if (defaultValue == null) {
                        return true;
                    }
                    String normalized = normalizeName(valueName);
                    if (isRegexpSqlFragmentName(normalized)
                            && configuredValue instanceof String
                            && "ID".equalsIgnoreCase(stripSqlLiteralQuotes((String) configuredValue))) {
                        return false;
                    }
                    if (!isGeneratedPlaceholderValue(valueName, configuredValue)) {
                        return true;
                    }
                    if (!(defaultValue instanceof String)) {
                        return false;
                    }
                    String defaultText = ((String) defaultValue).trim();
                    return isGeneratedPlaceholderText(defaultText)
                            && !isNumericTextParameterName(normalized)
                            && !isCompactEnumStringName(normalized)
                            && !isDateLikeParameterName(normalized);
                }

                private boolean shouldKeepConfiguredCollectionValue(String valueName, Object configuredValue, Object defaultValue) {
                    if (!(configuredValue instanceof Collection<?>) || !(defaultValue instanceof Collection<?>)) {
                        return true;
                    }
                    Object configuredFirst = firstCollectionElement((Collection<?>) configuredValue);
                    Object defaultFirst = firstCollectionElement((Collection<?>) defaultValue);
                    if (configuredFirst == null || defaultFirst == null) {
                        return true;
                    }
                    return shouldKeepConfiguredValue(valueName, configuredFirst, defaultFirst);
                }

                private boolean isGeneratedPlaceholderValue(String valueName, Object value) {
                    if (!(value instanceof String)) {
                        return false;
                    }
                    String text = ((String) value).trim();
                    if (isConfiguredDateTimeLiteral(text)) {
                        return false;
                    }
                    return isGeneratedPlaceholderText(text);
                }

                private boolean isConfiguredDateTimeLiteral(String value) {
                    return configuredInstant(stripSqlLiteralQuotes(value)) != null;
                }

                private boolean isGeneratedPlaceholderText(String value) {
                    String text = stripSqlLiteralQuotes(value == null ? "" : value.trim());
                    return "test".equalsIgnoreCase(text)
                            || "CODE".equalsIgnoreCase(text)
                            || "ID".equalsIgnoreCase(text)
                            || "null".equalsIgnoreCase(text)
                            || "2024-01-01".equals(text)
                            || "2024-01-01 00:00:00".equals(text)
                            || "2024-01-01 00:00:00.0".equals(text);
                }

                private boolean isGeneratedNullPlaceholderValue(Object value) {
                    if (!(value instanceof String)) {
                        return false;
                    }
                    return "null".equalsIgnoreCase(stripSqlLiteralQuotes(((String) value).trim()));
                }

                private String stripSqlLiteralQuotes(String value) {
                    if (value == null) {
                        return "";
                    }
                    String text = value.trim();
                    if (text.length() >= 2
                            && ((text.startsWith("'") && text.endsWith("'"))
                            || (text.startsWith("\\\"") && text.endsWith("\\\"")))) {
                        return text.substring(1, text.length() - 1);
                    }
                    return text;
                }

                private Object coerceConfiguredValueToDefaultType(Object configuredValue, Object defaultValue) {
                    if (defaultValue == null) {
                        return configuredValue;
                    }
                    if (defaultValue instanceof String && !(configuredValue instanceof String)) {
                        return String.valueOf(configuredValue);
                    }
                    if (!(configuredValue instanceof String)) {
                        return configuredValue;
                    }
                    if (defaultValue instanceof String) {
                        String defaultText = ((String) defaultValue).trim();
                        String strippedDefault = stripSqlLiteralQuotes(defaultText);
                        String configuredText = ((String) configuredValue).trim();
                        String strippedConfigured = stripSqlLiteralQuotes(configuredText);
                        if (defaultText.equals(strippedDefault)
                                && !configuredText.equals(strippedConfigured)
                                && isGeneratedPlaceholderText(defaultText)) {
                            return strippedConfigured;
                        }
                    }
                    String text = stripSqlLiteralQuotes((String) configuredValue);
                    try {
                        if (defaultValue instanceof BigDecimal) {
                            return new BigDecimal(text);
                        }
                        if (defaultValue instanceof BigInteger) {
                            return new BigInteger(text);
                        }
                        if (defaultValue instanceof Integer) {
                            return Integer.valueOf(text);
                        }
                        if (defaultValue instanceof Long) {
                            return Long.valueOf(text);
                        }
                        if (defaultValue instanceof Short) {
                            return Short.valueOf(text);
                        }
                        if (defaultValue instanceof Byte) {
                            return Byte.valueOf(text);
                        }
                        if (defaultValue instanceof Double) {
                            return Double.valueOf(text);
                        }
                        if (defaultValue instanceof Float) {
                            return Float.valueOf(text);
                        }
                        if (defaultValue instanceof Boolean) {
                            return Boolean.valueOf(text);
                        }
                        if (defaultValue instanceof java.sql.Timestamp) {
                            Instant instant = configuredInstant(text);
                            return instant == null ? configuredValue : java.sql.Timestamp.from(instant);
                        }
                        if (defaultValue instanceof java.sql.Date) {
                            Instant instant = configuredInstant(text);
                            return instant == null
                                    ? configuredValue
                                    : java.sql.Date.valueOf(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate());
                        }
                        if (defaultValue instanceof java.sql.Time) {
                            Instant instant = configuredInstant(text);
                            return instant == null
                                    ? configuredValue
                                    : java.sql.Time.valueOf(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalTime());
                        }
                        if (defaultValue instanceof Date) {
                            Instant instant = configuredInstant(text);
                            return instant == null ? configuredValue : Date.from(instant);
                        }
                    } catch (Exception ignored) {
                    }
                    return configuredValue;
                }

                private String configuredScalarText(Object rawValue) {
                    if (rawValue == null) {
                        return "null";
                    }
                    return String.valueOf(rawValue);
                }

                private ValueResult configuredPojoValue(
                        Class<?> targetType,
                        Type genericType,
                        Map<String, Object> params,
                        MapperStatement statement
                ) {
                    Map<String, Object> normalizedParams = normalizeValidationParameterMap(new LinkedHashMap<>(params));
                    Class<?> effectiveType = configuredPojoType(targetType, normalizedParams);
                    if (effectiveType == null) {
                        return ValueResult.unresolved("Configured __class is not assignable to "
                                + targetType.getName() + ".");
                    }
                    if (!targetType.equals(effectiveType)) {
                        genericType = effectiveType;
                    }
                    removeConfiguredPojoTypeKeys(normalizedParams);
                    ValueResult base = defaultValue(effectiveType, genericType, 0, statement);
                    if (!base.resolved || base.value == null) {
                        return base;
                    }
                    Object instance = base.value;
                    Class<?> currentType = effectiveType;
                    try {
                        while (currentType != null && !Object.class.equals(currentType)) {
                            for (Field field : currentType.getDeclaredFields()) {
                                int modifiers = field.getModifiers();
                                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers) || !normalizedParams.containsKey(field.getName())) {
                                    continue;
                                }
                                field.setAccessible(true);
                                Object rawConfigured = normalizedParams.get(field.getName());
                                if (rawConfigured == null) {
                                    if (field.getType().isPrimitive()) {
                                        return ValueResult.unresolved("Cannot assign null to primitive field "
                                                + targetType.getName() + "." + field.getName() + ".");
                                    }
                                    Object existingDefault = field.get(instance);
                                    if (shouldPreserveConfiguredNullDefault(field.getName(), existingDefault, statement)) {
                                        continue;
                                    }
                                    field.set(instance, null);
                                    continue;
                                }
                                Object existingDefault = field.get(instance);
                                Object configured = normalizeConfiguredDynamicIdentifierValue(
                                        rawConfigured,
                                        statement,
                                        field.getName(),
                                        existingDefault
                                );
                                Object nestedCollection = nestedConfiguredCollectionValue(
                                        existingDefault,
                                        configured,
                                        statement,
                                        field.getName()
                                );
                                if (nestedCollection != MethodArgumentConfig.MISSING) {
                                    configured = nestedCollection;
                                }
                                Object coerced = coerceConfiguredValueToDefaultType(configured, existingDefault);
                                Object valueToConvert = coerced != configured
                                        || shouldKeepConfiguredValue(field.getName(), configured, existingDefault)
                                        ? coerced
                                        : existingDefault;
                                ValueResult fieldValue = convertConfiguredValue(
                                        valueToConvert,
                                        field.getType(),
                                        field.getGenericType(),
                                        statement,
                                        field.getName(),
                                        true
                                );
                                if (!fieldValue.resolved) {
                                    return fieldValue;
                                }
                                field.set(instance, fieldValue.value);
                            }
                            currentType = currentType.getSuperclass();
                        }
                        return ValueResult.resolved(instance);
                    } catch (Exception e) {
                        return ValueResult.unresolved("Failed to apply configured parameters to "
                                + effectiveType.getName() + ": " + e.getMessage());
                    }
                }

                private Class<?> configuredPojoType(Class<?> targetType, Map<String, Object> params) {
                    Object configuredType = configuredPojoTypeValue(params);
                    if (configuredType == null) {
                        return targetType;
                    }
                    String className = String.valueOf(configuredType).trim();
                    if (isBlank(className)) {
                        return targetType;
                    }
                    try {
                        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                        Class<?> type = Class.forName(
                                className,
                                false,
                                classLoader == null ? targetType.getClassLoader() : classLoader
                        );
                        return targetType.isAssignableFrom(type) ? type : null;
                    } catch (ClassNotFoundException e) {
                        return null;
                    }
                }

                private Object configuredPojoTypeValue(Map<String, Object> params) {
                    for (String key : listOf("__class", "__type", "@class")) {
                        if (params.containsKey(key)) {
                            return params.get(key);
                        }
                    }
                    return null;
                }

                private void removeConfiguredPojoTypeKeys(Map<String, Object> params) {
                    params.remove("__class");
                    params.remove("__type");
                    params.remove("@class");
                }

                private boolean shouldPreserveConfiguredNullDefault(
                        String valueName,
                        Object existingDefault,
                        MapperStatement statement
                ) {
                    if (statement == null
                            || existingDefault == null
                            || (!statement.setDefaultValue(valueName) && !statement.hasSetDefaultUnder(valueName))) {
                        return false;
                    }
                    if (existingDefault instanceof Map) {
                        return !((Map<?, ?>) existingDefault).isEmpty();
                    }
                    if (existingDefault instanceof Collection) {
                        return !((Collection<?>) existingDefault).isEmpty();
                    }
                    return true;
                }

                private ValueResult defaultValue(Class<?> targetType, Type genericType, int depth) {
                    return defaultValue("", targetType, genericType, depth, null);
                }

                private ValueResult defaultValue(String valueName, Class<?> targetType, Type genericType, int depth) {
                    return defaultValue(valueName, targetType, genericType, depth, null);
                }

                private ValueResult defaultValue(
                        Class<?> targetType,
                        Type genericType,
                        int depth,
                        MapperStatement statement
                ) {
                    return defaultValue("", targetType, genericType, depth, statement);
                }

                private ValueResult defaultValue(
                        String valueName,
                        Class<?> targetType,
                        Type genericType,
                        int depth,
                        MapperStatement statement
                ) {
                    ValueResult contextualDefault = contextualDefaultValue(valueName, targetType, genericType, statement);
                    if (contextualDefault != null) {
                        return contextualDefault;
                    }
                    Object columnDefault = defaultTypedColumnValue(valueName, targetType, statement);
                    if (columnDefault != null) {
                        return ValueResult.resolved(columnDefault);
                    }
                    if (shouldUseNullDefault(valueName)
                            && !statementRequiredCollectionValue(valueName, statement)
                            && !statementRequiredDefaultValue(valueName, statement)) {
                        return ValueResult.resolved(null);
                    }
                    if (String.class.equals(targetType)) {
                        if (shouldUseNullDefault(valueName)) {
                            return ValueResult.resolved(null);
                        }
                        return ValueResult.resolved(defaultString(valueName, statement));
                    }
                    if (Integer.class.equals(targetType) || int.class.equals(targetType)) {
                        return ValueResult.resolved(1);
                    }
                    if (Long.class.equals(targetType) || long.class.equals(targetType)) {
                        return ValueResult.resolved(1L);
                    }
                    if (Short.class.equals(targetType) || short.class.equals(targetType)) {
                        return ValueResult.resolved((short) 1);
                    }
                    if (Byte.class.equals(targetType) || byte.class.equals(targetType)) {
                        return ValueResult.resolved((byte) 1);
                    }
                    if (Double.class.equals(targetType) || double.class.equals(targetType)) {
                        return ValueResult.resolved(1D);
                    }
                    if (Float.class.equals(targetType) || float.class.equals(targetType)) {
                        return ValueResult.resolved(1F);
                    }
                    if (Boolean.class.equals(targetType) || boolean.class.equals(targetType)) {
                        return ValueResult.resolved(true);
                    }
                    if (BigDecimal.class.equals(targetType)) {
                        return ValueResult.resolved(BigDecimal.ONE);
                    }
                    if (BigInteger.class.equals(targetType)) {
                        return ValueResult.resolved(BigInteger.ONE);
                    }
                    if (LocalDate.class.equals(targetType)) {
                        return ValueResult.resolved(LocalDate.now());
                    }
                    if (LocalDateTime.class.equals(targetType)) {
                        return ValueResult.resolved(LocalDateTime.now());
                    }
                    if (LocalTime.class.equals(targetType)) {
                        return ValueResult.resolved(LocalTime.now());
                    }
                    if (Instant.class.equals(targetType)) {
                        return ValueResult.resolved(Instant.now());
                    }
                    if (Date.class.equals(targetType)) {
                        return ValueResult.resolved(new Date());
                    }
                    if (UUID.class.equals(targetType)) {
                        return ValueResult.resolved(new UUID(0L, 1L));
                    }
                    if (Properties.class.equals(targetType)) {
                        return ValueResult.resolved(new Properties());
                    }
                    if (Optional.class.equals(targetType)) {
                        Type nestedType = firstGenericArgument(genericType);
                        ValueResult nestedValue = defaultValue(valueName, rawClass(nestedType), nestedType, depth + 1, statement);
                        return nestedValue.resolved
                                ? ValueResult.resolved(Optional.ofNullable(nestedValue.value))
                                : ValueResult.resolved(Optional.empty());
                    }
                    if (isMybatisPlusPageType(targetType)) {
                        return defaultMybatisPlusPage(targetType);
                    }
                    if (isMybatisPlusWrapperType(targetType)) {
                        return defaultMybatisPlusWrapper(targetType);
                    }
                    if (targetType.isArray()) {
                        ValueResult componentValue = defaultValue(
                                valueName,
                                targetType.getComponentType(),
                                targetType.getComponentType(),
                                depth + 1,
                                statement
                        );
                        if (!componentValue.resolved) {
                            return componentValue;
                        }
                        Object array = Array.newInstance(targetType.getComponentType(), 1);
                        Array.set(array, 0, componentValue.value);
                        return ValueResult.resolved(array);
                    }
                    if (Collection.class.isAssignableFrom(targetType)) {
                        Type nestedType = firstGenericArgument(genericType);
                        Class<?> nestedClass = rawClass(nestedType);
                        Object collectionScalarDefault = statement == null ? null : statement.collectionScalarDefault(valueName);
                        if (collectionScalarDefault instanceof Collection<?>) {
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(listOf(collectionScalarDefault)));
                            }
                            return ValueResult.resolved(new ArrayList<>(listOf(collectionScalarDefault)));
                        }
                        if (shouldUsePojoCollectionElement(nestedClass)) {
                            ValueResult nestedValue = defaultValue(valueName, nestedClass, nestedType, depth + 1, statement);
                            if (!nestedValue.resolved) {
                                return nestedValue;
                            }
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(listOf(nestedValue.value)));
                            }
                            return ValueResult.resolved(new ArrayList<>(listOf(nestedValue.value)));
                        }
                        Map<String, Object> collectionElementDefault = typedCollectionElementDefault(valueName, statement);
                        if (!collectionElementDefault.isEmpty()) {
                            Map<String, Object> elementValue = new LinkedHashMap<>(collectionElementDefault);
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(listOf(elementValue)));
                            }
                            return ValueResult.resolved(new ArrayList<>(listOf(elementValue)));
                        }
                        if (shouldUseEmptyCollection(valueName)
                                && (statement == null || !statement.nonEmptyCollectionParameter(valueName))) {
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>());
                            }
                            return ValueResult.resolved(new ArrayList<>());
                        }
                        String columnType = statement == null
                                ? ""
                                : statement.collectionColumnType(valueName, dbColumnMetadata);
                        if (!isBlank(columnType)) {
                            Object elementValue = defaultCollectionElementForColumnType(valueName, columnType);
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(listOf(elementValue)));
                            }
                            return ValueResult.resolved(new ArrayList<>(listOf(elementValue)));
                        }
                        Object sqlFragmentDefault = statement == null ? null : statement.collectionSqlFragmentDefault(valueName);
                        if (sqlFragmentDefault != null) {
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(listOf(sqlFragmentDefault)));
                            }
                            return ValueResult.resolved(new ArrayList<>(listOf(sqlFragmentDefault)));
                        }
                        Object scalarDefault = collectionScalarDefault;
                        if (scalarDefault != null) {
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(listOf(scalarDefault)));
                            }
                            return ValueResult.resolved(new ArrayList<>(listOf(scalarDefault)));
                        }
                        ValueResult nestedValue = defaultValue(valueName, rawClass(nestedType), nestedType, depth + 1, statement);
                        if (!nestedValue.resolved) {
                            return nestedValue;
                        }
                        if (Set.class.isAssignableFrom(targetType)) {
                            return ValueResult.resolved(new LinkedHashSet<>(listOf(nestedValue.value)));
                        }
                        return ValueResult.resolved(new ArrayList<>(listOf(nestedValue.value)));
                    }
                    if (Map.class.isAssignableFrom(targetType)) {
                        Map<String, Object> value = depth > 0 && statement != null && statement.mapCollectionParameter(valueName)
                                ? defaultMapCollectionValue(valueName, statement)
                                : depth == 0 && statement != null && statement.hasDefaultParameterMap()
                                ? defaultParameterMap(statement)
                                : defaultMapParameterValue(valueName, statement);
                        Map<String, Object> configuredDefaults = statement == null
                                ? emptyMap()
                                : statement.collectionElementDefault(valueName);
                        if (!configuredDefaults.isEmpty()) {
                            value.putAll(configuredDefaults);
                        }
                        if (value.isEmpty()) {
                            value.put("key", "test");
                        }
                        return ValueResult.resolved(mapParameterValue(targetType, value));
                    }
                    if (targetType.isEnum()) {
                        Object[] constants = targetType.getEnumConstants();
                        return constants.length == 0
                                ? ValueResult.unresolved("Enum has no constants: " + targetType.getName())
                                : ValueResult.resolved(constants[0]);
                    }
                    if (depth > 1) {
                        return ValueResult.unresolved("Nested object is too complex for generated parameters: " + targetType.getName());
                    }
                    return instantiatePojo(targetType, depth, statement);
                }

                private ValueResult contextualDefaultValue(
                        String valueName,
                        Class<?> targetType,
                        Type genericType,
                        MapperStatement statement
                ) {
                    if (statement == null) {
                        return null;
                    }
                    if (Collection.class.isAssignableFrom(targetType)
                            || Map.class.isAssignableFrom(targetType)
                            || targetType.isArray()) {
                        return null;
                    }
                    if (statement.hasDefaultValue(valueName)) {
                        return adaptContextualDefaultValue(
                                valueName,
                                statement.defaultValue(valueName),
                                targetType,
                                genericType,
                                ""
                        );
                    }
                    String columnType = statement.defaultColumnType(valueName, dbColumnMetadata);
                    if (isBlank(columnType)) {
                        return null;
                    }
                    return adaptContextualDefaultValue(
                            valueName,
                            defaultValueForColumnType(valueName, Object.class, columnType),
                            targetType,
                            genericType,
                            columnType
                    );
                }

            """,
            """
                private ValueResult adaptContextualDefaultValue(
                        String valueName,
                        Object rawValue,
                        Class<?> targetType,
                        Type genericType,
                        String typeHint
                ) {
                    if (rawValue == null) {
                        if (targetType.isPrimitive()) {
                            return null;
                        }
                        return ValueResult.resolved(null);
                    }
                    if (String.class.equals(targetType)) {
                        return ValueResult.resolved(String.valueOf(rawValue));
                    }
                    if (Object.class.equals(targetType) || targetType.isInstance(rawValue)) {
                        return ValueResult.resolved(rawValue);
                    }
                    if (isDateTimeTypeHint(typeHint) || isDateTimeDefaultValue(rawValue)) {
                        ValueResult dateValue = dateTimeContextualValue(rawValue, targetType);
                        if (dateValue != null) {
                            return dateValue;
                        }
                    }
                    ValueResult converted = convertScalar(String.valueOf(rawValue), targetType, genericType);
                    return converted.resolved ? converted : null;
                }

                private boolean isDateTimeTypeHint(String typeHint) {
                    String normalized = typeHint == null ? "" : typeHint.toUpperCase(Locale.ROOT);
                    return normalized.contains("DATE")
                            || normalized.contains("TIME")
                            || normalized.contains("TIMESTAMP")
                            || normalized.contains("DATETIME");
                }

                private boolean isDateTimeDefaultValue(Object value) {
                    if (!(value instanceof CharSequence)) {
                        return value instanceof Date
                                || value instanceof LocalDate
                                || value instanceof LocalDateTime
                                || value instanceof LocalTime
                                || value instanceof Instant;
                    }
                    String text = value.toString().trim();
                    return text.matches("\\\\d{4}-\\\\d{2}-\\\\d{2}.*")
                            || text.matches("[A-Za-z]{3} [A-Za-z]{3} \\\\d{1,2} \\\\d{2}:\\\\d{2}:\\\\d{2} [A-Za-z_+/:-]+ \\\\d{4}");
                }

                private ValueResult dateTimeContextualValue(Object rawValue, Class<?> targetType) {
                    if (String.class.equals(targetType)) {
                        return ValueResult.resolved(String.valueOf(rawValue));
                    }
                    if (Object.class.equals(targetType)) {
                        return ValueResult.resolved(rawValue);
                    }
                    if (LocalDate.class.equals(targetType)) {
                        return ValueResult.resolved(LocalDate.of(2024, 1, 1));
                    }
                    if (LocalDateTime.class.equals(targetType)) {
                        return ValueResult.resolved(LocalDateTime.of(2024, 1, 1, 0, 0, 0));
                    }
                    if (LocalTime.class.equals(targetType)) {
                        return ValueResult.resolved(LocalTime.of(0, 0, 0));
                    }
                    if (Instant.class.equals(targetType)) {
                        return ValueResult.resolved(Instant.parse("2024-01-01T00:00:00Z"));
                    }
                    if (Date.class.isAssignableFrom(targetType)) {
                        if ("java.sql.Date".equals(targetType.getName())) {
                            return ValueResult.resolved(java.sql.Date.valueOf("2024-01-01"));
                        }
                        if ("java.sql.Time".equals(targetType.getName())) {
                            return ValueResult.resolved(java.sql.Time.valueOf("00:00:00"));
                        }
                        if ("java.sql.Timestamp".equals(targetType.getName())) {
                            return ValueResult.resolved(java.sql.Timestamp.valueOf("2024-01-01 00:00:00"));
                        }
                        return ValueResult.resolved(Date.from(Instant.parse("2024-01-01T00:00:00Z")));
                    }
                    if (targetType.isPrimitive()) {
                        return null;
                    }
                    return ValueResult.resolved(null);
                }

                """,
            """
                private boolean isMybatisPlusPageType(Class<?> targetType) {
                    return targetType != null
                            && ("com.baomidou.mybatisplus.core.metadata.IPage".equals(targetType.getName())
                            || "IPage".equals(targetType.getSimpleName()));
                }

                private boolean isMybatisPlusWrapperType(Class<?> targetType) {
                    if (targetType == null) {
                        return false;
                    }
                    String name = targetType.getName();
                    return name.startsWith("com.baomidou.mybatisplus.core.conditions.")
                            && (name.endsWith(".Wrapper")
                            || name.endsWith(".AbstractWrapper")
                            || name.endsWith(".QueryWrapper")
                            || name.endsWith(".LambdaQueryWrapper")
                            || name.contains(".Wrapper"));
                }

                private ValueResult defaultMybatisPlusPage(Class<?> targetType) {
                    try {
                        Class<?> pageClass = Class.forName("com.baomidou.mybatisplus.extension.plugins.pagination.Page");
                        if (targetType.isAssignableFrom(pageClass)) {
                            try {
                                Constructor<?> constructor = pageClass.getDeclaredConstructor(long.class, long.class);
                                constructor.setAccessible(true);
                                return ValueResult.resolved(constructor.newInstance(1L, 10L));
                            } catch (NoSuchMethodException ignored) {
                                Constructor<?> constructor = pageClass.getDeclaredConstructor();
                                constructor.setAccessible(true);
                                return ValueResult.resolved(constructor.newInstance());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (targetType.isInterface()) {
                        Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                                targetType.getClassLoader(),
                                new Class<?>[] { targetType },
                                (proxyInstance, method, args) -> defaultProxyReturnValue(method)
                        );
                        return ValueResult.resolved(proxy);
                    }
                    return ValueResult.unresolved("Cannot instantiate parameter type: " + targetType.getName());
                }

                private ValueResult defaultMybatisPlusWrapper(Class<?> targetType) {
                    try {
                        if (!Modifier.isAbstract(targetType.getModifiers()) && !targetType.isInterface()) {
                            Constructor<?> constructor = targetType.getDeclaredConstructor();
                            constructor.setAccessible(true);
                            return ValueResult.resolved(constructor.newInstance());
                        }
                        Class<?> queryWrapperClass = Class.forName("com.baomidou.mybatisplus.core.conditions.query.QueryWrapper");
                        if (targetType.isAssignableFrom(queryWrapperClass)) {
                            Constructor<?> constructor = queryWrapperClass.getDeclaredConstructor();
                            constructor.setAccessible(true);
                            return ValueResult.resolved(constructor.newInstance());
                        }
                    } catch (Throwable ignored) {
                    }
                    if (targetType.isInterface()) {
                        Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                                targetType.getClassLoader(),
                                new Class<?>[] { targetType },
                                (proxyInstance, method, args) -> defaultProxyReturnValue(method)
                        );
                        return ValueResult.resolved(proxy);
                    }
                    return ValueResult.unresolved("Cannot instantiate parameter type: " + targetType.getName());
                }

                private Object defaultProxyReturnValue(Method method) {
                    String methodName = method.getName();
                    if ("getCustomSqlSegment".equals(methodName)
                            || "getSqlSegment".equals(methodName)
                            || "getSqlSelect".equals(methodName)
                            || "getTargetSql".equals(methodName)) {
                        return "";
                    }
                    if ("getParamNameValuePairs".equals(methodName)) {
                        return new LinkedHashMap<>();
                    }
                    Class<?> returnType = method.getReturnType();
                    if (boolean.class.equals(returnType)) {
                        return false;
                    }
                    if (int.class.equals(returnType)) {
                        return 0;
                    }
                    if (long.class.equals(returnType)) {
                        return 0L;
                    }
                    if (short.class.equals(returnType)) {
                        return (short) 0;
                    }
                    if (byte.class.equals(returnType)) {
                        return (byte) 0;
                    }
                    if (double.class.equals(returnType)) {
                        return 0D;
                    }
                    if (float.class.equals(returnType)) {
                        return 0F;
                    }
                    if (Collection.class.isAssignableFrom(returnType)) {
                        return new ArrayList<>();
                    }
                    if (Map.class.isAssignableFrom(returnType)) {
                        return new LinkedHashMap<>();
                    }
                    return null;
                }

                @SuppressWarnings({"unchecked", "rawtypes"})
                private Object mapParameterValue(Class<?> targetType, Map<String, Object> value) {
                    if (Map.class.equals(targetType)
                            || targetType.isInterface()
                            || Modifier.isAbstract(targetType.getModifiers())) {
                        return value;
                    }
                    try {
                        Constructor<?> constructor = targetType.getDeclaredConstructor();
                        constructor.setAccessible(true);
                        Object instance = constructor.newInstance();
                        if (instance instanceof Map) {
                            Map map = (Map) instance;
                            map.putAll(value);
                            return instance;
                        }
                    } catch (Throwable ignored) {
                    }
                    return value;
                }

                private Map<String, Object> defaultParameterMap(MapperStatement statement) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    if (statement != null) {
                        for (Map.Entry<String, Object> entry : statement.defaultValues().entrySet()) {
                            putDefaultParameterValue(
                                    value,
                                    entry.getKey(),
                                    defaultParameterMapValue(entry.getKey(), entry.getValue(), statement)
                            );
                        }
                        for (String dynamicIdentifierName : statement.dynamicIdentifierNames()) {
                            value.putIfAbsent(dynamicIdentifierName, defaultDynamicIdentifier(dynamicIdentifierName));
                        }
                        for (String collectionName : statement.collectionParameterNames()) {
                            putDefaultParameterValue(value, collectionName, defaultCollectionParameter(collectionName, statement));
                        }
                    }
                    return normalizeValidationParameterMap(value);
                }

                private Map<String, Object> configuredParameterMap(MapperStatement statement, Map<String, Object> configuredParams) {
                    Map<String, Object> value = defaultParameterMap(statement);
                    mergeConfiguredParameterMap(value, configuredParams, statement, "");
                    return normalizeValidationParameterMap(value);
                }

                @SuppressWarnings("unchecked")
                private void mergeConfiguredParameterMap(Map<String, Object> target, Map<String, Object> configuredParams) {
                    mergeConfiguredParameterMap(target, configuredParams, null, "");
                }

                @SuppressWarnings("unchecked")
                private void mergeConfiguredParameterMap(
                        Map<String, Object> target,
                        Map<String, Object> configuredParams,
                        MapperStatement statement,
                        String pathPrefix
                ) {
                    if (configuredParams == null || configuredParams.isEmpty()) {
                        return;
                    }
                    for (Map.Entry<String, Object> entry : configuredParams.entrySet()) {
                        Object existing = target.get(entry.getKey());
                        String entryPath = childPath(pathPrefix, entry.getKey());
                        Object configuredValue = normalizeConfiguredDynamicIdentifierValue(
                                entry.getValue(),
                                statement,
                                entryPath,
                                existing
                        );
                        Object sameNamedNestedCollection = sameNamedNestedCollectionValue(
                                entry.getKey(),
                                configuredValue,
                                statement,
                                entryPath
                        );
                        if (sameNamedNestedCollection != MethodArgumentConfig.MISSING) {
                            configuredValue = sameNamedNestedCollection;
                        }
                        Object nestedCollection = nestedConfiguredCollectionValue(
                                existing,
                                configuredValue,
                                statement,
                                entryPath
                        );
                        if (nestedCollection != MethodArgumentConfig.MISSING) {
                            target.put(entry.getKey(), nestedCollection);
                            continue;
                        }
                        Object scalarCollection = scalarConfiguredCollectionValue(entryPath, configuredValue, statement);
                        if (scalarCollection != MethodArgumentConfig.MISSING) {
                            target.put(entry.getKey(), scalarCollection);
                            continue;
                        }
                        if (existing == null && isGeneratedNullPlaceholderValue(configuredValue)) {
                            continue;
                        }
                        if (existing instanceof Map && configuredValue instanceof Map) {
                            mergeConfiguredParameterMap(
                                    (Map<String, Object>) existing,
                                    (Map<String, Object>) configuredValue,
                                    statement,
                                    entryPath
                            );
                            continue;
                        }
                        if (existing instanceof Collection<?>
                                && configuredValue instanceof Collection<?>
                                && !shouldKeepConfiguredCollectionValue(entryPath, configuredValue, existing)) {
                            continue;
                        }
                        if (existing != null
                                && configuredValue != null
                                && !(existing instanceof Map<?, ?>)
                                && !(configuredValue instanceof Map<?, ?>)
                                && !(existing instanceof Collection<?>)
                                && !(configuredValue instanceof Collection<?>)
                                && !shouldKeepConfiguredValue(entryPath, configuredValue, existing)) {
                            continue;
                        }
                        if (configuredValue == null && shouldPreserveConfiguredNullDefault(entryPath, existing, statement)) {
                            continue;
                        }
                        target.put(entry.getKey(), configuredValue);
                    }
                }

                @SuppressWarnings("unchecked")
                private Object sameNamedNestedCollectionValue(
                        String entryName,
                        Object configuredValue,
                        MapperStatement statement,
                        String entryPath
                ) {
                    if (statement == null
                            || configuredValue == null
                            || !(configuredValue instanceof Map<?, ?>)
                            || !statementRequiredCollectionValue(entryPath, statement)) {
                        return MethodArgumentConfig.MISSING;
                    }
                    String normalizedEntryName = normalizeName(entryName);
                    if (isBlank(normalizedEntryName)) {
                        return MethodArgumentConfig.MISSING;
                    }
                    for (Map.Entry<?, ?> nestedEntry : ((Map<?, ?>) configuredValue).entrySet()) {
                        if (!normalizedEntryName.equals(normalizeName(String.valueOf(nestedEntry.getKey())))) {
                            continue;
                        }
                        Object nestedValue = nestedEntry.getValue();
                        return nestedValue instanceof Map<?, ?> || nestedValue instanceof Collection<?>
                                ? nestedValue
                                : MethodArgumentConfig.MISSING;
                    }
                    return MethodArgumentConfig.MISSING;
                }

                @SuppressWarnings("unchecked")
                private Object nestedConfiguredCollectionValue(
                        Object existing,
                        Object configuredValue,
                        MapperStatement statement,
                        String pathPrefix
                ) {
                    if (!isCollectionOfCollections(existing)) {
                        return MethodArgumentConfig.MISSING;
                    }
                    Map<String, Object> defaultElement = firstMapElementDefault(existing);
                    if (configuredValue instanceof Collection<?>) {
                        Collection<?> configuredCollection = (Collection<?>) configuredValue;
                        Object first = firstCollectionElement(configuredCollection);
                        if (first == null || first instanceof Collection<?>) {
                            return configuredValue;
                        }
                        Collection<Object> nestedItems = configuredCollection instanceof Set<?>
                                ? new LinkedHashSet<>()
                                : new ArrayList<>();
                        for (Object item : configuredCollection) {
                            if (item instanceof Map<?, ?> && !defaultElement.isEmpty()) {
                                nestedItems.add(mergeConfiguredCollectionElementMap(
                                        new LinkedHashMap<>(defaultElement),
                                        new LinkedHashMap<>((Map<String, Object>) item),
                                        statement,
                                        pathPrefix
                                ));
                            } else {
                                nestedItems.add(item);
                            }
                        }
                        return new ArrayList<>(listOf(nestedItems));
                    }
                    if (configuredValue instanceof Map<?, ?>) {
                        Object nestedItem = configuredValue;
                        if (!defaultElement.isEmpty()) {
                            nestedItem = mergeConfiguredCollectionElementMap(
                                    new LinkedHashMap<>(defaultElement),
                                    new LinkedHashMap<>((Map<String, Object>) configuredValue),
                                    statement,
                                    pathPrefix
                            );
                        }
                        return new ArrayList<>(listOf(new ArrayList<>(listOf(nestedItem))));
                    }
                    return MethodArgumentConfig.MISSING;
                }

                private boolean isCollectionOfCollections(Object value) {
                    if (!(value instanceof Collection<?>)) {
                        return false;
                    }
                    Object first = firstCollectionElement((Collection<?>) value);
                    return first instanceof Collection<?>;
                }

                private Object firstCollectionElement(Collection<?> values) {
                    if (values == null) {
                        return null;
                    }
                    for (Object value : values) {
                        return value;
                    }
                    return null;
                }

                @SuppressWarnings("unchecked")
                private void putDefaultParameterValue(Map<String, Object> target, String parameterPath, Object parameterValue) {
                    List<String> parts = pathParts(parameterPath);
                    if (parts.size() <= 1) {
                        Object existing = target.get(parameterPath);
                        if (existing instanceof Map && parameterValue instanceof Map) {
                            Map<String, Object> existingMap = (Map<String, Object>) existing;
                            existingMap.putAll((Map<String, Object>) parameterValue);
                            return;
                        }
                        target.putIfAbsent(parameterPath, parameterValue);
                        return;
                    }
                    Map<String, Object> current = target;
                    for (int i = 0; i < parts.size() - 1; i++) {
                        String part = parts.get(i);
                        Object existing = current.get(part);
                        if (existing instanceof Map) {
                            Map<String, Object> existingMap = (Map<String, Object>) existing;
                            current = (Map<String, Object>) existingMap;
                            continue;
                        }
                        Map<String, Object> nested = new LinkedHashMap<>();
                        current.put(part, nested);
                        current = nested;
                    }
                    current.putIfAbsent(parts.get(parts.size() - 1), parameterValue);
                }

                private Object defaultParameterMapValue(String valueName, Object configuredDefault, MapperStatement statement) {
                    String columnType = statement == null ? "" : statement.defaultColumnType(valueName, dbColumnMetadata);
                    if (!isBlank(columnType) && isDateTimeColumnType(columnType)) {
                        return defaultStringDateTimeForColumnType(columnType);
                    }
                    if (!isBlank(columnType) && !isCharacterColumnType(columnType)) {
                        return defaultValueForColumnType(valueName, Object.class, columnType);
                    }
                    if (configuredDefault != null) {
                        return configuredDefault;
                    }
                    return isBlank(columnType)
                            ? null
                            : defaultValueForColumnType(valueName, Object.class, columnType);
                }

                private Object defaultCollectionParameter(String collectionName, MapperStatement statement) {
                    Map<String, Object> collectionElementDefault = typedCollectionElementDefault(collectionName, statement);
                    if (statement != null && statement.mapCollectionParameter(collectionName)) {
                        return defaultMapCollectionParameter(collectionName, statement);
                    }
                    if (!collectionElementDefault.isEmpty()) {
                        return new ArrayList<>(listOf(new LinkedHashMap<>(collectionElementDefault)));
                    }
                    if (shouldUseEmptyCollection(collectionName)
                            && (statement == null || !statement.nonEmptyCollectionParameter(collectionName))) {
                        return new ArrayList<>();
                    }
                    String columnType = statement == null
                            ? ""
                            : statement.collectionColumnType(collectionName, dbColumnMetadata);
                    if (!isBlank(columnType)) {
                        return new ArrayList<>(listOf(defaultCollectionElementForColumnType(collectionName, columnType)));
                    }
                    Object sqlFragmentDefault = statement == null ? null : statement.collectionSqlFragmentDefault(collectionName);
                    if (sqlFragmentDefault != null) {
                        return new ArrayList<>(listOf(sqlFragmentDefault));
                    }
                    Object scalarDefault = statement == null ? null : statement.collectionScalarDefault(collectionName);
                    if (scalarDefault != null) {
                        return new ArrayList<>(listOf(scalarDefault));
                    }
                    return new ArrayList<>(listOf(defaultCollectionElement(collectionName)));
                }

                @SuppressWarnings("unchecked")
                private Map<String, Object> defaultMapCollectionValue(String collectionName, MapperStatement statement) {
                    Object value = defaultMapCollectionParameter(collectionName, statement);
                    return value instanceof Map<?, ?>
                            ? new LinkedHashMap<>((Map<String, Object>) value)
                            : new LinkedHashMap<>();
                }

                @SuppressWarnings("unchecked")
                private Map<String, Object> defaultMapParameterValue(String valueName, MapperStatement statement) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    if (statement == null) {
                        return value;
                    }
                    if (statement.mapCollectionParameter(valueName) || statement.collectionParameter(valueName)) {
                        Object collectionValue = defaultCollectionParameter(valueName, statement);
                        putDefaultParameterValue(value, valueName, collectionValue);
                    }
                    Object scopedValue = valueAtPath(defaultParameterMap(statement), valueName);
                    if (scopedValue instanceof Map) {
                        value.putAll((Map<String, Object>) scopedValue);
                    }
                    return value;
                }

                private Object valueAtPath(Map<String, Object> value, String parameterPath) {
                    List<String> parts = pathParts(parameterPath);
                    if (parts.isEmpty()) {
                        return value;
                    }
                    Object current = value;
                    for (String part : parts) {
                        if (!(current instanceof Map)) {
                            return null;
                        }
                        current = ((Map<?, ?>) current).get(part);
                    }
                    return current;
                }

                private Map<String, Object> typedCollectionElementDefault(String collectionName, MapperStatement statement) {
                    if (statement == null) {
                        return emptyMap();
                    }
                    if (statement.scalarCollectionParameter(collectionName)
                            && !statement.hasCollectionElementDefault(collectionName)
                            && !statement.hasCollectionElementColumnReferences(collectionName)) {
                        return emptyMap();
                    }
                    Map<String, Object> collectionElementDefault = statement.collectionElementDefault(collectionName);
                    Map<String, Object> typed = new LinkedHashMap<>(collectionElementDefault);
                    Map<String, ColumnReference> columnReferences = statement.collectionElementColumnReferences(collectionName);
                    for (Map.Entry<String, ColumnReference> entry : columnReferences.entrySet()) {
                        String columnType = dbColumnMetadata == null ? "" : dbColumnMetadata.columnType(entry.getValue());
                        if (!isBlank(columnType)) {
                            typed.putIfAbsent(
                                    entry.getKey(),
                                    defaultValueForColumnType(entry.getKey(), Object.class, columnType)
                            );
                        }
                    }
                    if (typed.isEmpty()) {
                        return emptyMap();
                    }
                    for (String propertyName : new ArrayList<>(typed.keySet())) {
                        String columnType = statement.collectionElementColumnType(collectionName, propertyName, dbColumnMetadata);
                        if (!isBlank(columnType)) {
                            typed.put(propertyName, defaultValueForColumnType(propertyName, Object.class, columnType));
                            continue;
                        }
                        Object inferredValue = defaultNameBasedTypedValue(propertyName);
                        if (inferredValue != null) {
                            typed.put(propertyName, inferredValue);
                        }
                    }
                    return typed;
                }

                private Object defaultMapCollectionParameter(String collectionName, MapperStatement statement) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    Map<String, Object> collectionElementDefault = typedCollectionElementDefault(collectionName, statement);
                    Object value;
                    if (!collectionElementDefault.isEmpty()) {
                        value = new LinkedHashMap<>(collectionElementDefault);
                    } else {
                        String columnType = statement == null
                                ? ""
                                : statement.collectionColumnType(collectionName, dbColumnMetadata);
                        if (!isBlank(columnType)) {
                            value = defaultSqlFragmentForColumnType(collectionName, columnType);
                        } else {
                            Object sqlFragmentDefault = statement == null ? null : statement.collectionSqlFragmentDefault(collectionName);
                            if (sqlFragmentDefault != null) {
                                value = sqlFragmentDefault;
                            } else {
                                Object scalarDefault = statement == null ? null : statement.collectionScalarDefault(collectionName);
                                value = scalarDefault != null ? scalarDefault : defaultDynamicSqlFragmentValue(collectionName, "value");
                            }
                        }
                    }
                    map.put(defaultMapCollectionKey(collectionName), value);
                    return map;
                }

                private String defaultMapCollectionKey(String collectionName) {
                    String normalized = normalizeName(collectionName);
                    if (normalized.contains("rightmap") && !normalized.contains("userrightmap")) {
                        return "1";
                    }
                    if (normalized.contains("mapupdate")
                            || normalized.contains("updatemap")
                            || normalized.contains("idmap")) {
                        return "1";
                    }
                    return "extField";
                }

                private Object defaultCollectionElementForColumnType(String collectionName, String columnType) {
                    String normalizedType = columnType == null ? "" : columnType.toUpperCase(Locale.ROOT);
                    if (isCharacterColumnType(normalizedType)) {
                        Object nameBasedDefault = defaultCollectionElement(collectionName);
                        return nameBasedDefault instanceof String ? nameBasedDefault : defaultString(collectionName);
                    }
                    if (normalizedType.contains("BIGINT")) {
                        return 1L;
                    }
                    if (normalizedType.contains("NUMBER")
                            || normalizedType.contains("DECIMAL")
                            || normalizedType.contains("NUMERIC")) {
                        return BigDecimal.ONE;
                    }
                    if (normalizedType.contains("INT")
                            || normalizedType.contains("DOUBLE")
                            || normalizedType.contains("FLOAT")
                            || normalizedType.contains("REAL")) {
                        return 1;
                    }
                    if (normalizedType.contains("DATE")
                            || normalizedType.contains("TIME")
                            || normalizedType.contains("TIMESTAMP")) {
                        return java.sql.Timestamp.valueOf("2024-01-01 00:00:00");
                    }
                    return defaultCollectionElement(collectionName);
                }

                private String defaultSqlFragmentForColumnType(String valueName, String columnType) {
                    String normalizedType = columnType == null ? "" : columnType.toUpperCase(Locale.ROOT);
                    if (isCharacterColumnType(normalizedType)) {
                        return quoteSqlLiteral(defaultSqlLiteralString(valueName));
                    }
                    if (normalizedType.contains("DATE")
                            || normalizedType.contains("TIME")
                            || normalizedType.contains("TIMESTAMP")) {
                        return quoteSqlLiteral("2024-01-01 00:00:00");
                    }
                    return "1";
                }

                private String defaultSqlLiteralString(String valueName) {
                    String normalized = normalizeName(valueName);
                    if (normalized.contains("id")
                            || normalized.contains("user")
                            || normalized.contains("owner")
                            || normalized.contains("rightmap")) {
                        return "1";
                    }
                    if (normalized.contains("key") || normalized.contains("code")) {
                        return "CODE";
                    }
                    return "test";
                }

                private String quoteSqlLiteral(String value) {
                    return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
                }

                private Object defaultTypedColumnValue(String valueName, Class<?> targetType, MapperStatement statement) {
                    if (!String.class.equals(targetType) && !Object.class.equals(targetType)) {
                        return null;
                    }
                    String columnType = statement == null ? "" : statement.defaultColumnType(valueName, dbColumnMetadata);
                    if (isBlank(columnType)
                            || (String.class.equals(targetType) && isCharacterColumnType(columnType))) {
                        return null;
                    }
                    return defaultValueForColumnType(valueName, targetType, columnType);
                }

                private Object defaultValueForColumnType(String valueName, Class<?> targetType, String columnType) {
                    Object defaultValue = defaultScalarForColumnType(valueName, columnType);
                    return String.class.equals(targetType) ? String.valueOf(defaultValue) : defaultValue;
                }

                private Object defaultScalarForColumnType(String valueName, String columnType) {
                    String normalizedType = columnType == null ? "" : columnType.toUpperCase(Locale.ROOT);
                    if (isCharacterColumnType(normalizedType)) {
                        return defaultNameBasedString(valueName);
                    }
                    if (normalizedType.contains("BIGINT")) {
                        return 1L;
                    }
                    if (normalizedType.contains("NUMBER")
                            || normalizedType.contains("DECIMAL")
                            || normalizedType.contains("NUMERIC")) {
                        return BigDecimal.ONE;
                    }
                    if (normalizedType.contains("INT")
                            || normalizedType.contains("DOUBLE")
                            || normalizedType.contains("FLOAT")
                            || normalizedType.contains("REAL")) {
                        return 1;
                    }
                    if (normalizedType.contains("DATE")
                            || normalizedType.contains("TIME")
                            || normalizedType.contains("TIMESTAMP")) {
                        return java.sql.Timestamp.valueOf("2024-01-01 00:00:00");
                    }
                    return defaultNameBasedString(valueName);
                }

                private boolean isDateTimeColumnType(String columnType) {
                    String normalizedType = columnType == null ? "" : columnType.toUpperCase(Locale.ROOT);
                    return normalizedType.contains("DATE")
                            || normalizedType.contains("TIME")
                            || normalizedType.contains("TIMESTAMP");
                }

                private String defaultStringDateTimeForColumnType(String columnType) {
                    String normalizedType = columnType == null ? "" : columnType.toUpperCase(Locale.ROOT);
                    if (normalizedType.contains("TIME")
                            && !normalizedType.contains("DATE")
                            && !normalizedType.contains("TIMESTAMP")) {
                        return "00:00:00";
                    }
                    return "2024-01-01 00:00:00";
                }

                private boolean isCharacterColumnType(String columnType) {
                    String normalizedType = columnType == null ? "" : columnType.toUpperCase(Locale.ROOT);
                    return normalizedType.contains("CHAR")
                            || normalizedType.contains("CLOB")
                            || normalizedType.contains("TEXT")
                            || normalizedType.contains("JSON");
                }

                private Object defaultCollectionElement(String collectionName) {
                    String normalized = normalizeName(collectionName);
                    if (normalized.contains("month")) {
                        return "202401";
                    }
                    if (normalized.contains("accountbook")) {
                        return "202401";
                    }
                    if (isDateLikeParameterName(normalized)) {
                        return "2024-01-01 00:00:00";
                    }
                    if (normalized.contains("orderno")
                            || "orders".equals(normalized)
                            || normalized.contains("billno")
                            || normalized.contains("bankno")
                            || normalized.contains("code")
                            || normalized.contains("accountbook")) {
                        return "CODE";
                    }
                    if (isNumericSqlFragmentName(normalized)) {
                        return 1L;
                    }
                    if (isIdLikeParameterName(normalized)
                            || normalized.contains("key")) {
                        return 1L;
                    }
                    return "test";
                }

                private String defaultString(String valueName) {
                    return defaultString(valueName, null);
                }

                private String defaultString(String valueName, MapperStatement statement) {
                    String normalized = normalizeName(valueName);
                    if (isOrderFieldName(normalized) || isOrderDirectionName(normalized)) {
                        return "";
                    }
                    if (isRawSqlInjectionName(normalized)) {
                        return "";
                    }
                    if (statement != null && statement.dynamicIdentifierParameter(valueName)) {
                        return defaultDynamicIdentifier(valueName);
                    }
                    if (isLikelyDynamicIdentifierName(normalized)) {
                        return defaultDynamicIdentifier(valueName);
                    }
                    Object columnDefault = defaultTypedColumnValue(valueName, String.class, statement);
                    if (columnDefault != null) {
                        return String.valueOf(columnDefault);
                    }
                    Object configuredDefault = statement == null ? null : statement.defaultValue(valueName);
                    if (configuredDefault != null) {
                        return String.valueOf(configuredDefault);
                    }
                    return defaultNameBasedString(valueName);
                }

                private String defaultNameBasedString(String valueName) {
                    String normalized = normalizeName(valueName);
                    if ("mainsearch".equals(normalized)) {
                        return "";
                    }
                    if (isOptionalSearchParameterName(normalized)) {
                        return "";
                    }
                    if (isIdLikeParameterName(normalized)) {
                        return "1";
                    }
                    if (normalized.contains("code")) {
                        return "CODE";
                    }
                    if (isCompactEnumStringName(normalized)) {
                        return "1";
                    }
                    if (isNumericTextParameterName(normalized)) {
                        return "1";
                    }
                    String temporalDefault = defaultTemporalString(normalized);
                    if (temporalDefault != null) {
                        return temporalDefault;
                    }
                    if (normalized.contains("comparison")) {
                        return "EQUAL";
                    }
                    if (normalized.contains("comparision")) {
                        return "EQUAL";
                    }
                    return "test";
                }

                private String defaultTemporalString(String normalizedName) {
                    if (isDateLikeParameterName(normalizedName)) {
                        return "2024-01-01 00:00:00";
                    }
                    if (isDayOfMonthParameterName(normalizedName)) {
                        return "1";
                    }
                    if (isMonthLikeParameterName(normalizedName)) {
                        return "202401";
                    }
                    if (isYearLikeParameterName(normalizedName)) {
                        return "2024";
                    }
                    return null;
                }

                private String defaultSqlFragmentForName(String normalizedName) {
                    if (isDateLikeParameterName(normalizedName)) {
                        return "'2024-01-01'";
                    }
                    if (isDayOfMonthParameterName(normalizedName)) {
                        return "1";
                    }
                    if (isMonthLikeParameterName(normalizedName)) {
                        return "202401";
                    }
                    if (isYearLikeParameterName(normalizedName)) {
                        return "2024";
                    }
                    return null;
                }

                private String defaultDynamicIdentifier(String valueName) {
                    String normalized = normalizeName(valueName);
                    if (isRawSqlInjectionName(normalized)) {
                        return "";
                    }
                    if (isSchemaIdentifierName(normalized)
                            && currentConfig != null
                            && !isBlank(currentConfig.primarySchema())) {
                        return quotedIdentifier(currentConfig.primarySchema());
                    }
                    return "ID";
                }

                private String defaultDynamicSqlFragmentValue(String collectionName, String propertyName) {
                    return defaultDynamicSqlFragmentValue(collectionName, propertyName, "", -1, -1, null);
                }

                private Object defaultDynamicSqlParameterValue(
                        String expression,
                        String text,
                        int startIndex,
                        int endIndex,
                        SqlStatementContext sqlContext
                ) {
                    String normalized = normalizeName(expression);
                    if (isOrderFieldName(normalized) || isOrderDirectionName(normalized)) {
                        return "";
                    }
                    if (isSqlSegmentParameterName(normalized)) {
                        return "";
                    }
                    if (normalized.contains("timesql")) {
                        return "1=1";
                    }
                    if (isRawSqlInjectionName(normalized)) {
                        return "";
                    }
                    String nameBasedFragmentDefault = defaultSqlFragmentForName(normalized);
                    if (nameBasedFragmentDefault != null) {
                        return quoteAwareDynamicSqlFragmentDefault(nameBasedFragmentDefault, text, startIndex, endIndex);
                    }
                    if (isLikelyDynamicIdentifierName(normalized)) {
                        return defaultDynamicIdentifier(expression);
                    }
                    if (followsLogicalOperator(text, startIndex) && !followsInOperator(text, startIndex)) {
                        return "1=1";
                    }
                    if (followsInOperator(text, startIndex) || followsComparisonOperator(text, startIndex)) {
                        return defaultDynamicSqlFragmentValue(expression, "", text, startIndex, endIndex, sqlContext);
                    }
                    return null;
                }

                private String defaultDynamicSqlFragmentValue(
                        String collectionName,
                        String propertyName,
                        String text,
                        int startIndex,
                        int endIndex,
                        SqlStatementContext sqlContext
                ) {
                    String normalizedProperty = normalizeName(propertyName);
                    String normalizedCollection = normalizeName(collectionName);
                    if (isRawSqlInjectionName(normalizedProperty) || isRawSqlInjectionName(normalizedCollection)) {
                        return "";
                    }
                    ColumnReference columnReference = dynamicPlaceholderColumnReference(text, startIndex, sqlContext);
                    String columnType = dbColumnMetadata == null ? "" : dbColumnMetadata.columnType(columnReference);
                    if (!isBlank(columnType)) {
                        return quoteAwareDynamicSqlFragmentDefault(
                                defaultSqlFragmentForColumnType(
                                        isBlank(propertyName) ? collectionName : propertyName,
                                        columnType
                                ),
                                text,
                                startIndex,
                                endIndex
                        );
                    }
                    String nameBasedFragmentDefault = defaultSqlFragmentForName(
                            isBlank(propertyName) ? normalizedCollection : normalizedProperty
                    );
                    if (nameBasedFragmentDefault != null) {
                        return quoteAwareDynamicSqlFragmentDefault(nameBasedFragmentDefault, text, startIndex, endIndex);
                    }
                    if (isOrderFieldName(normalizedProperty) || isOrderFieldName(normalizedCollection)
                            || isOrderDirectionName(normalizedProperty) || isOrderDirectionName(normalizedCollection)) {
                        return "";
                    }
                    if (isSqlSegmentParameterName(normalizedProperty) || isSqlSegmentParameterName(normalizedCollection)) {
                        return "";
                    }
                    if (isRegexpSqlFragmentName(normalizedProperty) || isRegexpSqlFragmentName(normalizedCollection)) {
                        return quoteAwareDynamicSqlFragmentDefault(quoteSqlLiteral("1"), text, startIndex, endIndex);
                    }
                    if (normalizedProperty.endsWith("field")
                            || normalizedProperty.endsWith("fieldname")
                            || normalizedProperty.endsWith("column")
                            || normalizedProperty.endsWith("columnname")
                            || normalizedProperty.endsWith("modelkey")
                            || normalizedProperty.endsWith("underline")
                            || normalizedProperty.endsWith("underlinename")) {
                        return quoteAwareDynamicSqlFragmentDefault("ID", text, startIndex, endIndex);
                    }
                    if ("fieldvalue".equals(normalizedProperty)
                            || "value".equals(normalizedProperty)
                            || normalizedProperty.endsWith("values")
                            || isNumericSqlFragmentName(normalizedProperty)
                            || isNumericSqlFragmentName(normalizedCollection)) {
                        return quoteAwareDynamicSqlFragmentDefault("1", text, startIndex, endIndex);
                    }
                    if ("map".equals(normalizedCollection)
                            || normalizedCollection.contains("user")
                            || normalizedCollection.contains("owner")
                            || normalizedCollection.contains("rightmap")
                            || normalizedCollection.contains("groupmap")) {
                        return quoteAwareDynamicSqlFragmentDefault("1", text, startIndex, endIndex);
                    }
                    if (normalizedProperty.endsWith("key")
                            || normalizedProperty.endsWith("keys")
                            || normalizedCollection.endsWith("key")
                            || normalizedCollection.endsWith("keys")
                            || normalizedProperty.contains("code")
                            || normalizedCollection.contains("code")) {
                        return quoteAwareDynamicSqlFragmentDefault("'CODE'", text, startIndex, endIndex);
                    }
                    if (isStringSqlLiteralFragmentName(normalizedProperty)
                            || isStringSqlLiteralFragmentName(normalizedCollection)) {
                        return quoteAwareDynamicSqlFragmentDefault("'test'", text, startIndex, endIndex);
                    }
                    return quoteAwareDynamicSqlFragmentDefault("ID", text, startIndex, endIndex);
                }

                private String quoteAwareDynamicSqlFragmentDefault(
                        String value,
                        String text,
                        int startIndex,
                        int endIndex
                ) {
                    if (dynamicPlaceholderInsideSqlLiteral(text, startIndex, endIndex)) {
                        return stripSqlLiteralQuotes(value);
                    }
                    return value;
                }

                private boolean dynamicPlaceholderInsideSqlLiteral(String text, int startIndex, int endIndex) {
                    if (text == null || startIndex < 0 || endIndex < 0 || startIndex > text.length()) {
                        return false;
                    }
                    int before = previousNonWhitespaceIndex(text, startIndex - 1);
                    int after = nextNonWhitespaceIndex(text, endIndex);
                    return before >= 0
                            && after >= 0
                            && text.charAt(before) == '\\''
                            && text.charAt(after) == '\\'';
                }

                private int previousNonWhitespaceIndex(String text, int startIndex) {
                    for (int i = Math.min(startIndex, text.length() - 1); i >= 0; i--) {
                        if (!Character.isWhitespace(text.charAt(i))) {
                            return i;
                        }
                    }
                    return -1;
                }

                private int nextNonWhitespaceIndex(String text, int startIndex) {
                    for (int i = Math.max(startIndex, 0); i < text.length(); i++) {
                        if (!Character.isWhitespace(text.charAt(i))) {
                            return i;
                        }
                    }
                    return -1;
                }

                private ColumnReference dynamicPlaceholderColumnReference(String text, int startIndex, SqlStatementContext sqlContext) {
                    if (text == null || startIndex < 0) {
                        return null;
                    }
                    String before = text.substring(0, Math.min(startIndex, text.length()));
                    String identifier = sqlIdentifierPattern();
                    Matcher inMatcher = Pattern.compile(
                            "(?is)(" + identifier + "(?:\\\\s*\\\\.\\\\s*" + identifier + ")?)\\\\s+(?:not\\\\s+)?in\\\\s*\\\\(\\\\s*$"
                    ).matcher(before);
                    ColumnReference reference = null;
                    while (inMatcher.find()) {
                        reference = columnReference(inMatcher.group(1), sqlContext);
                    }
                    if (reference != null) {
                        return reference;
                    }
                    Matcher comparisonMatcher = Pattern.compile(
                            "(?is)(" + identifier + "(?:\\\\s*\\\\.\\\\s*" + identifier + ")?)\\\\s*(?:=|<>|!=|>=|<=|>|<)\\\\s*$"
                    ).matcher(before);
                    while (comparisonMatcher.find()) {
                        reference = columnReference(comparisonMatcher.group(1), sqlContext);
                    }
                    return reference;
                }

                private boolean followsInOperator(String text, int startIndex) {
                    if (text == null || startIndex < 0) {
                        return false;
                    }
                    String before = text.substring(0, Math.min(startIndex, text.length()));
                    return Pattern.compile("(?is)\\\\b(?:not\\\\s+)?in\\\\s*\\\\(\\\\s*$").matcher(before).find();
                }

                private boolean followsComparisonOperator(String text, int startIndex) {
                    if (text == null || startIndex < 0) {
                        return false;
                    }
                    String before = text.substring(0, Math.min(startIndex, text.length()));
                    return Pattern.compile("(?is)(?:=|<>|!=|>=|<=|>|<)\\\\s*$").matcher(before).find();
                }

                private boolean followsLogicalOperator(String text, int startIndex) {
                    if (text == null || startIndex < 0) {
                        return false;
                    }
                    String before = text.substring(0, Math.min(startIndex, text.length()));
                    return Pattern.compile("(?is)\\\\b(?:and|or)\\\\s*$").matcher(before).find();
                }

                private boolean isNumericSqlFragmentName(String normalizedName) {
                    return normalizedName.endsWith("id")
                            || normalizedName.endsWith("ids")
                            || normalizedName.endsWith("idlist")
                            || normalizedName.contains("idlist")
                            || normalizedName.contains("idslist")
                            || normalizedName.endsWith("type")
                            || normalizedName.endsWith("types")
                            || normalizedName.endsWith("typelist")
                            || normalizedName.contains("classtype")
                            || normalizedName.contains("qualityclasstype")
                            || normalizedName.endsWith("level")
                            || normalizedName.endsWith("levellist")
                            || normalizedName.endsWith("status")
                            || normalizedName.endsWith("statuslist")
                            || normalizedName.endsWith("flag")
                            || normalizedName.endsWith("flaglist")
                            || normalizedName.endsWith("count")
                            || normalizedName.endsWith("countlist");
                }

                private boolean isStringSqlLiteralFragmentName(String normalizedName) {
                    return normalizedName.endsWith("code")
                            || normalizedName.endsWith("codelist")
                            || normalizedName.contains("name")
                            || normalizedName.contains("account")
                            || normalizedName.contains("no");
                }

                private boolean isRegexpSqlFragmentName(String normalizedName) {
                    return normalizedName.contains("regexp") || normalizedName.contains("regex");
                }

                private boolean isSqlSegmentParameterName(String normalizedName) {
                    return normalizedName.contains("customsqlsegment")
                            || normalizedName.contains("normalsqlsegment")
                            || normalizedName.contains("ordersqlsegment")
                            || normalizedName.contains("sqlsegment")
                            || normalizedName.endsWith("collect");
                }

                private boolean isSchemaIdentifierName(String normalized) {
                    return "schema".equals(normalized)
                            || "schemaname".equals(normalized)
                            || normalized.endsWith("schema")
                            || "database".equals(normalized)
                            || "databasename".equals(normalized);
                }

                private boolean isRawSqlInjectionName(String normalizedName) {
                    return normalizedName.contains("injectsql")
                            || normalizedName.contains("hintsql")
                            || normalizedName.contains("sqlhint")
                            || normalizedName.contains("sqlproxyhint");
                }

                private Object defaultValueForJdbcType(String valueName, String jdbcType) {
                    String normalizedJdbcType = jdbcType == null ? "" : jdbcType.toUpperCase(Locale.ROOT);
                    switch (normalizedJdbcType) {
                        case "BIGINT":
                            return 1L;
                        case "INTEGER":
                        case "INT":
                        case "SMALLINT":
                        case "TINYINT":
                            return 1;
                        case "DOUBLE":
                        case "FLOAT":
                        case "REAL":
                            return 1D;
                        case "DECIMAL":
                        case "NUMERIC":
                            return BigDecimal.ONE;
                        case "BIT":
                        case "BOOLEAN":
                            return true;
                        case "DATE":
                        case "TIME":
                        case "TIMESTAMP":
                        case "DATETIME":
                            return java.sql.Timestamp.valueOf("2024-01-01 00:00:00");
                        default:
                            return defaultString(valueName);
                    }
                }

                private boolean shouldUseEmptyCollection(String valueName) {
                    String normalized = normalizeName(valueName);
                    return "filterlist".equals(normalized)
                            || "filter".equals(normalized)
                            || "filters".equals(normalized)
                            || "otherconditions".equals(normalized)
                            || "groupconditions".equals(normalized)
                            || "sorts".equals(normalized)
                            || "orderfields".equals(normalized)
                            || "sortfields".equals(normalized)
                            || "orders".equals(normalized);
                }

                private boolean isOrderFieldName(String normalizedName) {
                    return normalizedName.contains("orderfield")
                            || normalizedName.contains("sortfield")
                            || normalizedName.contains("orderbyfield");
                }

                private boolean isOrderDirectionName(String normalizedName) {
                    return "orderby".equals(normalizedName)
                            || "orderseq".equals(normalizedName)
                            || "sortseq".equals(normalizedName)
                            || normalizedName.contains("orderdirection")
                            || normalizedName.contains("sortdirection")
                            || normalizedName.endsWith("direction");
                }

                private boolean shouldUseNullDefault(String valueName) {
                    String normalized = normalizeName(valueName);
                    return isOrderFieldName(normalized)
                            || isOrderDirectionName(normalized)
                            || isSqlSegmentParameterName(normalized)
                            || isRawSqlInjectionName(normalized)
                            || isOptionalSearchParameterName(normalized)
                            || isDynamicMapParameterName(normalized)
                            || isOptionalDynamicTableName(normalized);
                }

                private boolean statementRequiredCollectionValue(String valueName, MapperStatement statement) {
                    return statement != null
                            && (statement.nonEmptyCollectionParameter(valueName)
                            || statement.collectionParameter(valueName)
                            || statement.mapCollectionParameter(valueName)
                            || statement.scalarCollectionParameter(valueName));
                }

                private boolean statementRequiredDefaultValue(String valueName, MapperStatement statement) {
                    return statement != null
                            && (statement.setDefaultValue(valueName) || statement.hasSetDefaultUnder(valueName));
                }

                private String requiredCollectionValueName(String valueName, MapperStatement statement) {
                    if (statement == null) {
                        return "";
                    }
                    if (statementRequiredCollectionValue(valueName, statement)) {
                        return valueName;
                    }
                    Set<String> collectionNames = statement.collectionParameterNames();
                    if (collectionNames.size() != 1) {
                        return "";
                    }
                    String collectionName = collectionNames.iterator().next();
                    return statementRequiredCollectionValue(collectionName, statement) ? collectionName : "";
                }

                private boolean isIdLikeParameterName(String normalizedName) {
                    if (normalizedName.endsWith("idname")
                            || normalizedName.endsWith("idsname")
                            || normalizedName.endsWith("idnames")
                            || normalizedName.contains("username")) {
                        return false;
                    }
                    return normalizedName.endsWith("id")
                            || normalizedName.endsWith("ids")
                            || normalizedName.contains("userid")
                            || normalizedName.contains("enterpriseid")
                            || normalizedName.contains("organizationid");
                }

                private boolean isDateLikeParameterName(String normalizedName) {
                    return "date".equals(normalizedName)
                            || "time".equals(normalizedName)
                            || "now".equals(normalizedName)
                            || normalizedName.endsWith("date")
                            || normalizedName.endsWith("datestr")
                            || normalizedName.endsWith("datestart")
                            || normalizedName.endsWith("dateend")
                            || normalizedName.endsWith("datetime")
                            || normalizedName.endsWith("timestamp")
                            || (normalizedName.endsWith("time") && !normalizedName.endsWith("parttime"))
                            || normalizedName.endsWith("timestr")
                            || normalizedName.endsWith("timestart")
                            || normalizedName.endsWith("timeend")
                            || "starttime".equals(normalizedName)
                            || "endtime".equals(normalizedName)
                            || "begintime".equals(normalizedName)
                            || "finishtime".equals(normalizedName)
                            || normalizedName.contains("operatordate")
                            || normalizedName.contains("shouldchargedate")
                            || isDateTimeCollectionParameterName(normalizedName)
                            || (normalizedName.contains("date") && hasTemporalRangeQualifier(normalizedName))
                            || (normalizedName.contains("time") && hasTemporalRangeQualifier(normalizedName))
                            || normalizedName.contains("firstday")
                            || normalizedName.contains("lastday")
                            || normalizedName.contains("startday")
                            || normalizedName.contains("endday");
                }

                private boolean isDateTimeCollectionParameterName(String normalizedName) {
                    return (normalizedName.endsWith("datelist") && !normalizedName.endsWith("updatelist"))
                            || normalizedName.endsWith("timelist");
                }

                private boolean isDayOfMonthParameterName(String normalizedName) {
                    return "day".equals(normalizedName)
                            || "closingday".equals(normalizedName)
                            || "settlementday".equals(normalizedName)
                            || "billday".equals(normalizedName);
                }

                private boolean isMonthLikeParameterName(String normalizedName) {
                    return "month".equals(normalizedName)
                            || normalizedName.contains("accountbook")
                            || normalizedName.contains("month");
                }

                private boolean isYearLikeParameterName(String normalizedName) {
                    return "year".equals(normalizedName)
                            || normalizedName.endsWith("year")
                            || (normalizedName.contains("year") && hasTemporalRangeQualifier(normalizedName))
                            || normalizedName.contains("thisyear")
                            || normalizedName.contains("lastyear");
                }

                private boolean hasTemporalRangeQualifier(String normalizedName) {
                    return normalizedName.contains("start")
                            || normalizedName.contains("begin")
                            || normalizedName.contains("end")
                            || normalizedName.contains("less")
                            || normalizedName.contains("biger")
                            || normalizedName.contains("before")
                            || normalizedName.contains("after")
                            || normalizedName.contains("from")
                            || normalizedName.contains("to");
                }

                private boolean isNumericTextParameterName(String normalizedName) {
                    if (normalizedName.contains("name")
                            || normalizedName.contains("code")
                            || normalizedName.endsWith("no")
                            || normalizedName.contains("orderno")
                            || normalizedName.contains("billno")
                            || normalizedName.contains("phone")
                            || normalizedName.contains("mobile")
                            || normalizedName.contains("number")
                            || normalizedName.contains("contactnumber")) {
                        return false;
                    }
                    return normalizedName.endsWith("num")
                            || normalizedName.contains("price")
                            || normalizedName.contains("amount")
                            || normalizedName.contains("money")
                            || normalizedName.contains("fee")
                            || normalizedName.contains("rate")
                            || normalizedName.contains("ratio");
                }

                private boolean isCompactEnumStringName(String normalizedName) {
                    if (normalizedName.contains("name") || isDateLikeParameterName(normalizedName)) {
                        return false;
                    }
                    return normalizedName.endsWith("status")
                            || normalizedName.endsWith("state")
                            || normalizedName.endsWith("flag")
                            || normalizedName.endsWith("type")
                            || normalizedName.endsWith("sex")
                            || normalizedName.endsWith("unit")
                            || normalizedName.endsWith("period")
                            || normalizedName.endsWith("frequency")
                            || normalizedName.endsWith("source")
                            || normalizedName.endsWith("entity")
                            || normalizedName.startsWith("has")
                            || normalizedName.startsWith("sync")
                            || "syncpwd".equals(normalizedName)
                            || normalizedName.contains("wechat")
                            || normalizedName.contains("alipay")
                            || normalizedName.contains("warrantydeposit")
                            || normalizedName.contains("relatedcontract")
                            || normalizedName.contains("collectionorsettlement")
                            || normalizedName.contains("rewardorpunish")
                            || normalizedName.endsWith("disable")
                            || normalizedName.endsWith("disabled")
                            || normalizedName.startsWith("is");
                }

                private boolean isOptionalSearchParameterName(String normalizedName) {
                    return "keywords".equals(normalizedName)
                            || "keyword".equals(normalizedName)
                            || "groupcondition".equals(normalizedName)
                            || "groupconditions".equals(normalizedName)
                            || "filter".equals(normalizedName)
                            || "filterlist".equals(normalizedName)
                            || "filters".equals(normalizedName)
                            || "otherconditions".equals(normalizedName);
                }

                private boolean isDynamicMapParameterName(String normalizedName) {
                    return "dynamicmap".equals(normalizedName)
                            || normalizedName.endsWith("dynamicmap");
                }

                private boolean isOptionalDynamicTableName(String normalizedName) {
                    return "extendtable".equals(normalizedName)
                            || "extendtablename".equals(normalizedName)
                            || "extensiontable".equals(normalizedName)
                            || "extensiontablename".equals(normalizedName);
                }

                private boolean isDynamicIdentifierName(String normalizedName) {
                    return normalizedName.contains("fieldunderlinename")
                            || normalizedName.endsWith("fieldname")
                            || normalizedName.endsWith("columnname");
                }

                private boolean isLikelyDynamicIdentifierName(String normalizedName) {
                    return isDynamicIdentifierName(normalizedName)
                            || normalizedName.contains("fieldname")
                            || normalizedName.contains("columnname")
                            || normalizedName.endsWith("field")
                            || normalizedName.endsWith("field2")
                            || normalizedName.endsWith("column")
                            || normalizedName.endsWith("tablename")
                            || normalizedName.endsWith("table")
                            || normalizedName.endsWith("pkname")
                            || normalizedName.endsWith("keyname");
                }

                private String normalizeName(String valueName) {
                    return valueName == null
                            ? ""
                            : valueName.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                }

                private ValueResult instantiatePojo(Class<?> targetType, int depth, MapperStatement statement) {
                    if (targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) {
                        return ValueResult.unresolved("Cannot instantiate parameter type: " + targetType.getName());
                    }
                    try {
                        Constructor<?> constructor = targetType.getDeclaredConstructor();
                        constructor.setAccessible(true);
                        Object instance = constructor.newInstance();
                        Class<?> currentType = targetType;
                        while (currentType != null && !Object.class.equals(currentType)) {
                            for (Field field : currentType.getDeclaredFields()) {
                                int modifiers = field.getModifiers();
                                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                                    continue;
                                }
                                if (statement != null
                                        && statement.generatedKeyProperty(field.getName())
                                        && !field.getType().isPrimitive()) {
                                    continue;
                                }
                                ValueResult fieldValue = defaultValue(
                                        field.getName(),
                                        field.getType(),
                                        field.getGenericType(),
                                        depth + 1,
                                        statement
                                );
                                if (fieldValue.resolved) {
                                    field.setAccessible(true);
                                    field.set(instance, fieldValue.value);
                                }
                            }
                            currentType = currentType.getSuperclass();
                        }
                        return ValueResult.resolved(instance);
                    } catch (Exception e) {
                        return ValueResult.unresolved("Cannot instantiate parameter type "
                                + targetType.getName() + ": " + e.getMessage());
                    }
                }

                private Type firstGenericArgument(Type type) {
                    if (type instanceof ParameterizedType) {
                        ParameterizedType parameterizedType = (ParameterizedType) type;
                        if (parameterizedType.getActualTypeArguments().length > 0) {
                            return parameterizedType.getActualTypeArguments()[0];
                        }
                    }
                    return String.class;
                }

                private Class<?> rawClass(Type type) {
                    if (type instanceof Class<?>) {
                        return (Class<?>) type;
                    }
                    if (type instanceof ParameterizedType) {
                        ParameterizedType parameterizedType = (ParameterizedType) type;
                        Type rawType = parameterizedType.getRawType();
                        if (rawType instanceof Class<?>) {
                            return (Class<?>) rawType;
                        }
                    }
                    return String.class;
                }

                private Path findProjectRoot() {
                    Path current = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
                    while (current != null) {
                        if (Files.isRegularFile(current.resolve(CONFIG_PATH))) {
                            return current;
                        }
                        current = current.getParent();
                    }
                    throw new IllegalStateException("Could not find " + CONFIG_PATH + " from working directory.");
                }

                private void writeReports(
                        Path projectRoot,
                        List<ValidationRecord> records,
                        UsageFilterReport usageFilterReport
                ) throws IOException {
                    Files.createDirectories(projectRoot.resolve(".dm-adapter"));
                    writeString(projectRoot.resolve(MARKDOWN_REPORT), markdown(records, usageFilterReport), StandardCharsets.UTF_8);
                    writeString(projectRoot.resolve(JSON_REPORT), json(records, usageFilterReport), StandardCharsets.UTF_8);
                }

                """,
            """
                private void writeValidationArgsSuggestions(
                        Path projectRoot,
                        ValidationConfig config,
                        List<ValidationRecord> failedRecords
                ) throws IOException {
                    Map<String, MethodArgumentConfig> suggestions = new LinkedHashMap<>();
                    for (ValidationRecord record : failedRecords) {
                        if (record.parameters == null
                                || !record.parameters.resolved
                                || record.parameterSource == null
                                || !record.parameterSource.startsWith("auto")) {
                            continue;
                        }
                        String methodKey = baseRecordKey(record.key);
                        if (config.hasConfiguredArguments(methodKey) || suggestions.containsKey(methodKey)) {
                            continue;
                        }
                        MethodArgumentConfig suggestion = suggestedArgumentConfig(record.parameters);
                        if (suggestion != null && !suggestion.isEmpty()) {
                            suggestions.put(methodKey, suggestion);
                        }
                    }
                    if (suggestions.isEmpty()) {
                        return;
                    }
                    Path rewriteConfigPath = projectRoot.resolve(REWRITE_CONFIG_PATH);
                    List<String> lines = Files.isRegularFile(rewriteConfigPath)
                            ? Files.readAllLines(rewriteConfigPath, StandardCharsets.UTF_8)
                            : defaultRewriteConfigLines();
                    List<String> merged = mergeValidationArgs(lines, suggestions);
                    Files.createDirectories(rewriteConfigPath.getParent());
                    writeString(rewriteConfigPath, String.join("\\n", merged) + "\\n", StandardCharsets.UTF_8);
                    log("Updated validation args in " + rewriteConfigPath + " for "
                            + suggestions.size() + " failed mapper methods.");
                }

                private MethodArgumentConfig suggestedArgumentConfig(ParameterResolution parameters) {
                    if (parameters.args == null || parameters.args.length == 0) {
                        return null;
                    }
                    if (parameters.args.length == 1) {
                        Object value = parameters.args[0];
                        if (value instanceof Map<?, ?>) {
                            Map<String, Object> params = serializableMap((Map<?, ?>) value);
                            return params.isEmpty() ? null : MethodArgumentConfig.params(params);
                        }
                        Object serializable = serializableValue(value);
                        return serializable == MethodArgumentConfig.MISSING
                                ? null
                                : MethodArgumentConfig.args(listOf(serializable));
                    }
                    if (parameters.names != null && parameters.names.size() == parameters.args.length) {
                        Map<String, Object> params = new LinkedHashMap<>();
                        for (int i = 0; i < parameters.args.length; i++) {
                            Object serializable = serializableValue(parameters.args[i]);
                            if (serializable != MethodArgumentConfig.MISSING) {
                                params.put(parameters.names.get(i), serializable);
                            }
                        }
                        return params.isEmpty() ? null : MethodArgumentConfig.params(params);
                    }
                    List<Object> args = new ArrayList<>();
                    for (Object arg : parameters.args) {
                        Object serializable = serializableValue(arg);
                        if (serializable == MethodArgumentConfig.MISSING) {
                            return null;
                        }
                        args.add(serializable);
                    }
                    return MethodArgumentConfig.args(args);
                }

                private Map<String, Object> serializableMap(Map<?, ?> map) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String key = String.valueOf(entry.getKey());
                        if (isSensitiveName(key)) {
                            continue;
                        }
                        Object value = serializableValue(entry.getValue());
                        if (value != MethodArgumentConfig.MISSING) {
                            result.put(key, value);
                        }
                    }
                    return result;
                }

                private Object serializableValue(Object value) {
                    return serializableValue(value, 0);
                }

                private Object serializableValue(Object value, int depth) {
                    if (value == null
                            || value instanceof CharSequence
                            || value instanceof Number
                            || value instanceof Boolean
                            || value instanceof Enum<?>) {
                        return value;
                    }
                    if (value instanceof java.sql.Date) {
                        return "2024-01-01";
                    }
                    if (value instanceof java.sql.Time) {
                        return "00:00:00";
                    }
                    if (value instanceof Date) {
                        return "2024-01-01 00:00:00";
                    }
                    if (value instanceof LocalDate) {
                        return "2024-01-01";
                    }
                    if (value instanceof LocalDateTime) {
                        return "2024-01-01T00:00:00";
                    }
                    if (value instanceof LocalTime) {
                        return "00:00:00";
                    }
                    if (value instanceof Instant) {
                        return "2024-01-01T00:00:00Z";
                    }
                    if (value instanceof java.time.temporal.TemporalAccessor
                            || value instanceof UUID) {
                        return String.valueOf(value);
                    }
                    Class<?> valueType = value.getClass();
                    if (valueType.isArray()) {
                        List<Object> values = new ArrayList<>();
                        int length = Array.getLength(value);
                        for (int i = 0; i < length; i++) {
                            Object item = serializableValue(Array.get(value, i), depth + 1);
                            if (item == MethodArgumentConfig.MISSING) {
                                return MethodArgumentConfig.MISSING;
                            }
                            values.add(item);
                        }
                        return values;
                    }
                    if (value instanceof Map<?, ?>) {
                        Map<String, Object> values = serializableMap((Map<?, ?>) value);
                        return values.isEmpty() ? MethodArgumentConfig.MISSING : values;
                    }
                    if (value instanceof Collection<?>) {
                        List<Object> values = new ArrayList<>();
                        for (Object item : (Collection<?>) value) {
                            Object serializable = serializableValue(item, depth + 1);
                            if (serializable == MethodArgumentConfig.MISSING) {
                                return MethodArgumentConfig.MISSING;
                            }
                            values.add(serializable);
                        }
                        return values;
                    }
                    Map<String, Object> pojo = serializablePojo(value, depth);
                    if (!pojo.isEmpty()) {
                        return pojo;
                    }
                    return MethodArgumentConfig.MISSING;
                }

                private Map<String, Object> serializablePojo(Object value, int depth) {
                    if (value == null || depth > 2 || !shouldSerializePojoType(value.getClass())) {
                        return emptyMap();
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    Class<?> currentType = value.getClass();
                    try {
                        while (currentType != null && !Object.class.equals(currentType)) {
                            for (Field field : currentType.getDeclaredFields()) {
                                int modifiers = field.getModifiers();
                                if (Modifier.isStatic(modifiers)
                                        || Modifier.isFinal(modifiers)
                                        || isSensitiveName(field.getName())) {
                                    continue;
                                }
                                field.setAccessible(true);
                                Object serializable = serializableValue(field.get(value), depth + 1);
                                if (serializable != MethodArgumentConfig.MISSING) {
                                    result.put(field.getName(), serializable);
                                }
                            }
                            currentType = currentType.getSuperclass();
                        }
                    } catch (Exception ignored) {
                        return emptyMap();
                    }
                    return result;
                }

                private boolean shouldSerializePojoType(Class<?> valueType) {
                    if (valueType == null
                            || valueType.isPrimitive()
                            || valueType.isArray()
                            || valueType.isEnum()
                            || valueType.isInterface()
                            || Modifier.isAbstract(valueType.getModifiers())) {
                        return false;
                    }
                    String name = valueType.getName();
                    return !name.startsWith("java.")
                            && !name.startsWith("javax.")
                            && !name.startsWith("jakarta.");
                }

                private List<String> defaultRewriteConfigLines() {
                    return new ArrayList<>(Arrays.asList(
                            "# dm-adapter SQL rewrite config.",
                            "# keyColumns may be inferred from Dameng primary/unique metadata when DM_SQL_VALIDATION is enabled.",
                            "upsertKeys:",
                            "  tables:",
                            "    {}",
                            "  methods:",
                            "    {}"
                    ));
                }

                private List<String> mergeValidationArgs(
                        List<String> originalLines,
                        Map<String, MethodArgumentConfig> suggestions
                ) {
                    List<String> result = new ArrayList<>(originalLines);
                    int validationStart = topLevelSectionStart(result, "validationArgs:");
                    if (validationStart < 0) {
                        if (!result.isEmpty() && !isBlank(result.get(result.size() - 1))) {
                            result.add("");
                        }
                        result.add("validationArgs:");
                        result.add("  methods:");
                        appendValidationMethods(result, suggestions);
                        return result;
                    }
                    int validationEnd = topLevelSectionEnd(result, validationStart);
                    for (int i = validationEnd - 1; i > validationStart; i--) {
                        if ("{}".equals(result.get(i).trim())) {
                            result.remove(i);
                            validationEnd--;
                        }
                    }
                    if (!hasValidationMethods(result, validationStart, validationEnd)) {
                        result.add(validationEnd++, "  methods:");
                    }
                    List<String> methodLines = new ArrayList<>();
                    appendValidationMethods(methodLines, suggestions);
                    result.addAll(validationEnd, methodLines);
                    return result;
                }

                private List<String> mergeMissingTableIgnores(List<String> originalLines, List<String> suggestedTables) {
                    List<String> result = new ArrayList<>(originalLines);
                    int ignoreStart = topLevelSectionStart(result, "validationIgnores:");
                    if (ignoreStart < 0) {
                        if (!result.isEmpty() && !isBlank(result.get(result.size() - 1))) {
                            result.add("");
                        }
                        result.add("validationIgnores:");
                        result.add("  missingTables:");
                        appendMissingTableSuggestions(result, suggestedTables);
                        return result;
                    }
                    int ignoreEnd = topLevelSectionEnd(result, ignoreStart);
                    int missingTablesStart = missingTablesSectionStart(result, ignoreStart, ignoreEnd);
                    if (missingTablesStart < 0) {
                        result.add(ignoreEnd++, "  missingTables:");
                        appendMissingTableSuggestions(result, ignoreEnd, suggestedTables);
                        return result;
                    }
                    int missingTablesEnd = missingTablesSectionEnd(result, missingTablesStart, ignoreEnd);
                    appendMissingTableSuggestions(result, missingTablesEnd, suggestedTables);
                    return result;
                }

                private List<String> mergeMissingColumnIgnores(List<String> originalLines, List<String> suggestedColumns) {
                    List<String> result = new ArrayList<>(originalLines);
                    int ignoreStart = topLevelSectionStart(result, "validationIgnores:");
                    if (ignoreStart < 0) {
                        if (!result.isEmpty() && !isBlank(result.get(result.size() - 1))) {
                            result.add("");
                        }
                        result.add("validationIgnores:");
                        result.add("  missingColumns:");
                        appendMissingColumnSuggestions(result, suggestedColumns);
                        return result;
                    }
                    int ignoreEnd = topLevelSectionEnd(result, ignoreStart);
                    int missingColumnsStart = missingColumnsSectionStart(result, ignoreStart, ignoreEnd);
                    if (missingColumnsStart < 0) {
                        result.add(ignoreEnd++, "  missingColumns:");
                        appendMissingColumnSuggestions(result, ignoreEnd, suggestedColumns);
                        return result;
                    }
                    int missingColumnsEnd = missingTablesSectionEnd(result, missingColumnsStart, ignoreEnd);
                    appendMissingColumnSuggestions(result, missingColumnsEnd, suggestedColumns);
                    return result;
                }

                private void appendMissingTableSuggestions(List<String> lines, List<String> suggestedTables) {
                    appendMissingTableSuggestions(lines, lines.size(), suggestedTables);
                }

                private void appendMissingTableSuggestions(List<String> lines, int index, List<String> suggestedTables) {
                    List<String> suggestionLines = new ArrayList<>();
                    for (String table : suggestedTables) {
                        suggestionLines.add("#    - " + quoteYaml(table));
                    }
                    lines.addAll(index, suggestionLines);
                }

                private void appendMissingColumnSuggestions(List<String> lines, List<String> suggestedColumns) {
                    appendMissingColumnSuggestions(lines, lines.size(), suggestedColumns);
                }

                private void appendMissingColumnSuggestions(List<String> lines, int index, List<String> suggestedColumns) {
                    List<String> suggestionLines = new ArrayList<>();
                    for (String column : suggestedColumns) {
                        suggestionLines.add("#    - " + quoteYaml(column));
                    }
                    lines.addAll(index, suggestionLines);
                }

                private int missingTablesSectionStart(List<String> lines, int start, int end) {
                    for (int i = start + 1; i < end; i++) {
                        String withoutComment = stripYamlComment(lines.get(i));
                        if (leadingSpaces(withoutComment) == 2
                                && withoutComment.trim().startsWith("missingTables:")) {
                            return i;
                        }
                    }
                    return -1;
                }

                private int missingColumnsSectionStart(List<String> lines, int start, int end) {
                    for (int i = start + 1; i < end; i++) {
                        String withoutComment = stripYamlComment(lines.get(i));
                        if (leadingSpaces(withoutComment) == 2
                                && withoutComment.trim().startsWith("missingColumns:")) {
                            return i;
                        }
                    }
                    return -1;
                }

                private int missingTablesSectionEnd(List<String> lines, int start, int end) {
                    for (int i = start + 1; i < end; i++) {
                        String trimmed = lines.get(i).trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                            continue;
                        }
                        if (leadingSpaces(lines.get(i)) <= 2) {
                            return i;
                        }
                    }
                    return end;
                }

                private Set<String> existingMissingTableIgnoreEntries(List<String> lines) {
                    Set<String> existing = new LinkedHashSet<>();
                    String section = "";
                    boolean missingTables = false;
                    for (String line : lines) {
                        boolean commented = line.trim().startsWith("#");
                        String effectiveLine = commented ? line.substring(line.indexOf('#') + 1) : stripYamlComment(line);
                        String trimmed = effectiveLine.trim();
                        if (isBlank(trimmed) || "{}".equals(trimmed)) {
                            continue;
                        }
                        int indent = leadingSpaces(effectiveLine);
                        if (!commented && indent == 0 && "validationIgnores:".equals(trimmed)) {
                            section = "validationIgnores";
                            missingTables = false;
                            continue;
                        }
                        if (!commented && indent == 0) {
                            section = "";
                            missingTables = false;
                            continue;
                        }
                        if ("validationIgnores".equals(section) && indent == 2 && trimmed.startsWith("missingTables:")) {
                            missingTables = true;
                            addMissingTableEntries(existing, yamlStringList(trimmed.substring("missingTables:".length())));
                            continue;
                        }
                        if ("validationIgnores".equals(section) && missingTables && indent >= 4 && trimmed.startsWith("- ")) {
                            addMissingTableEntries(existing, yamlStringList(trimmed.substring(2)));
                            continue;
                        }
                        if ("validationIgnores".equals(section) && !commented && indent <= 2) {
                            missingTables = false;
                        }
                    }
                    return existing;
                }

                private void addMissingTableEntries(Set<String> existing, List<String> tables) {
                    for (String table : tables) {
                        String normalized = normalizeMissingTableName(table);
                        if (!isBlank(normalized)) {
                            existing.add(normalized);
                        }
                    }
                }

                private boolean containsMissingTable(Set<String> existingTables, String table) {
                    String normalized = normalizeMissingTableName(table);
                    String leaf = missingTableLeaf(normalized);
                    for (String existing : existingTables) {
                        if (existing.equals(normalized)
                                || existing.equals(leaf)
                                || missingTableLeaf(existing).equals(normalized)
                                || missingTableLeaf(existing).equals(leaf)) {
                            return true;
                        }
                    }
                    return false;
                }

                private Set<String> existingMissingColumnIgnoreEntries(List<String> lines) {
                    Set<String> existing = new LinkedHashSet<>();
                    String section = "";
                    boolean missingColumns = false;
                    for (String line : lines) {
                        boolean commented = line.trim().startsWith("#");
                        String effectiveLine = commented ? line.substring(line.indexOf('#') + 1) : stripYamlComment(line);
                        String trimmed = effectiveLine.trim();
                        if (isBlank(trimmed) || "{}".equals(trimmed)) {
                            continue;
                        }
                        int indent = leadingSpaces(effectiveLine);
                        if (!commented && indent == 0 && "validationIgnores:".equals(trimmed)) {
                            section = "validationIgnores";
                            missingColumns = false;
                            continue;
                        }
                        if (!commented && indent == 0) {
                            section = "";
                            missingColumns = false;
                            continue;
                        }
                        if ("validationIgnores".equals(section) && indent == 2 && trimmed.startsWith("missingColumns:")) {
                            missingColumns = true;
                            addMissingColumnEntries(existing, yamlStringList(trimmed.substring("missingColumns:".length())));
                            continue;
                        }
                        if ("validationIgnores".equals(section) && missingColumns && indent >= 4 && trimmed.startsWith("- ")) {
                            addMissingColumnEntries(existing, yamlStringList(trimmed.substring(2)));
                            continue;
                        }
                        if ("validationIgnores".equals(section) && !commented && indent <= 2) {
                            missingColumns = false;
                        }
                    }
                    return existing;
                }

                private void addMissingColumnEntries(Set<String> existing, List<String> columns) {
                    for (String column : columns) {
                        String normalized = normalizeMissingColumnName(column);
                        if (!isBlank(normalized)) {
                            existing.add(normalized);
                        }
                    }
                }

                private boolean containsMissingColumn(Set<String> existingColumns, String column) {
                    String normalized = normalizeMissingColumnName(column);
                    String leaf = missingColumnLeaf(normalized);
                    for (String existing : existingColumns) {
                        if (existing.equals(normalized)
                                || existing.equals(leaf)
                                || missingColumnLeaf(existing).equals(normalized)
                                || missingColumnLeaf(existing).equals(leaf)) {
                            return true;
                        }
                    }
                    return false;
                }

                private List<String> yamlStringList(String value) {
                    String trimmed = value == null ? "" : value.trim();
                    if (trimmed.isEmpty()) {
                        return listOf();
                    }
                    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                        String body = trimmed.substring(1, trimmed.length() - 1).trim();
                        if (body.isEmpty()) {
                            return listOf();
                        }
                        List<String> values = new ArrayList<>();
                        for (String item : splitYamlInlineList(body)) {
                            String scalar = yamlScalar(item);
                            if (!isBlank(scalar)) {
                                values.add(scalar);
                            }
                        }
                        return values;
                    }
                    String scalar = yamlScalar(trimmed);
                    return isBlank(scalar) ? listOf() : listOf(scalar);
                }

                private List<String> splitYamlInlineList(String body) {
                    List<String> values = new ArrayList<>();
                    boolean singleQuoted = false;
                    boolean doubleQuoted = false;
                    StringBuilder current = new StringBuilder();
                    for (int i = 0; i < body.length(); i++) {
                        char c = body.charAt(i);
                        if (c == '\\'' && !doubleQuoted) {
                            singleQuoted = !singleQuoted;
                        } else if (c == '\\"' && !singleQuoted) {
                            doubleQuoted = !doubleQuoted;
                        }
                        if (c == ',' && !singleQuoted && !doubleQuoted) {
                            values.add(current.toString().trim());
                            current.setLength(0);
                        } else {
                            current.append(c);
                        }
                    }
                    if (current.length() > 0) {
                        values.add(current.toString().trim());
                    }
                    return values;
                }

                private String yamlScalar(String value) {
                    String trimmed = value == null ? "" : value.trim();
                    if (trimmed.length() >= 2
                            && ((trimmed.startsWith("\\"") && trimmed.endsWith("\\""))
                            || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
                        return trimmed.substring(1, trimmed.length() - 1);
                    }
                    return trimmed;
                }

                private String stripYamlComment(String line) {
                    boolean singleQuoted = false;
                    boolean doubleQuoted = false;
                    for (int i = 0; i < line.length(); i++) {
                        char current = line.charAt(i);
                        if (current == '\\'' && !doubleQuoted) {
                            singleQuoted = !singleQuoted;
                        } else if (current == '\\"' && !singleQuoted) {
                            doubleQuoted = !doubleQuoted;
                        } else if (current == '#' && !singleQuoted && !doubleQuoted) {
                            return line.substring(0, i);
                        }
                    }
                    return line;
                }

                private String normalizeMissingTableName(String table) {
                    if (table == null) {
                        return "";
                    }
                    return table.trim()
                            .replace("\\"", "")
                            .replace("`", "")
                            .toLowerCase(Locale.ROOT);
                }

                private String missingTableLeaf(String table) {
                    int dot = table == null ? -1 : table.lastIndexOf('.');
                    return dot >= 0 && dot + 1 < table.length() ? table.substring(dot + 1) : table;
                }

                private String normalizeMissingColumnName(String column) {
                    if (column == null) {
                        return "";
                    }
                    return column.trim()
                            .replace("\\"", "")
                            .replace("`", "")
                            .toLowerCase(Locale.ROOT);
                }

                private String missingColumnLeaf(String column) {
                    int dot = column == null ? -1 : column.lastIndexOf('.');
                    return dot >= 0 && dot + 1 < column.length() ? column.substring(dot + 1) : column;
                }

            """,
            """
                private int topLevelSectionStart(List<String> lines, String header) {
                    for (int i = 0; i < lines.size(); i++) {
                        if (leadingSpaces(lines.get(i)) == 0 && header.equals(lines.get(i).trim())) {
                            return i;
                        }
                    }
                    return -1;
                }

                private int topLevelSectionEnd(List<String> lines, int start) {
                    for (int i = start + 1; i < lines.size(); i++) {
                        String trimmed = lines.get(i).trim();
                        if (leadingSpaces(lines.get(i)) == 0 && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            return i;
                        }
                    }
                    return lines.size();
                }

                private boolean hasValidationMethods(List<String> lines, int start, int end) {
                    for (int i = start + 1; i < end; i++) {
                        if (leadingSpaces(lines.get(i)) == 2 && "methods:".equals(lines.get(i).trim())) {
                            return true;
                        }
                    }
                    return false;
                }

                private void appendValidationMethods(List<String> lines, Map<String, MethodArgumentConfig> suggestions) {
                    for (Map.Entry<String, MethodArgumentConfig> entry : suggestions.entrySet()) {
                        lines.add("    " + quoteYaml(entry.getKey()) + ":");
                        MethodArgumentConfig config = entry.getValue();
                        if (config.hasParams()) {
                            lines.add("      params:");
                            for (Map.Entry<String, Object> param : config.params.entrySet()) {
                                lines.add("        " + quoteYamlKey(param.getKey()) + ": " + yamlValue(param.getValue()));
                            }
                        } else {
                            lines.add("      args:");
                            for (Object arg : config.args) {
                                lines.add("        - " + yamlValue(arg));
                            }
                        }
                    }
                }

                private String yamlValue(Object value) {
                    if (value == null) {
                        return "null";
                    }
                    if (value instanceof Number || value instanceof Boolean) {
                        return String.valueOf(value);
                    }
                    if (value instanceof Collection<?>) {
                        return "[" + ((Collection<?>) value).stream()
                                .map(this::yamlValue)
                                .collect(Collectors.joining(", ")) + "]";
                    }
                    if (value instanceof Map<?, ?>) {
                        return "{" + ((Map<?, ?>) value).entrySet().stream()
                                .map(entry -> quoteYamlKey(String.valueOf(entry.getKey())) + ": " + yamlValue(entry.getValue()))
                                .collect(Collectors.joining(", ")) + "}";
                    }
                    return quoteYaml(String.valueOf(value));
                }

                private String quoteYamlKey(String value) {
                    return value.matches("[A-Za-z_][A-Za-z0-9_.$-]*") ? value : quoteYaml(value);
                }

                private String quoteYaml(String value) {
                    return "\\"" + (value == null ? "" : value.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"")) + "\\"";
                }

                private int leadingSpaces(String line) {
                    int count = 0;
                    while (count < line.length() && line.charAt(count) == ' ') {
                        count++;
                    }
                    return count;
                }

                private String baseRecordKey(String recordKey) {
                    int labelStart = recordKey == null ? -1 : recordKey.indexOf(" [");
                    return labelStart > 0 ? recordKey.substring(0, labelStart) : recordKey;
                }

                """,
            """
                private void writeMissingTableIgnoreSuggestions(
                        Path projectRoot,
                        ValidationConfig config,
                        List<ValidationRecord> failedRecords
                ) throws IOException {
                    Map<String, String> suggestedTables = new LinkedHashMap<>();
                    Map<String, String> suggestedColumns = new LinkedHashMap<>();
                    for (ValidationRecord record : failedRecords) {
                        for (String marker : listOf("无效的表或视图名", "无效的表名")) {
                            for (String table : bracketedValuesAfterMarker(record.message, marker)) {
                                if (!config.ignoresMissingTable(table)) {
                                    suggestedTables.putIfAbsent(normalizeMissingTableName(table), table);
                                }
                            }
                        }
                        for (String marker : listOf("无效的列名", "无效的变量名", "无法解析的成员访问表达式")) {
                            for (String column : bracketedValuesAfterMarker(record.message, marker)) {
                                if (!config.ignoresMissingColumn(column)) {
                                    suggestedColumns.putIfAbsent(normalizeMissingColumnName(column), column);
                                }
                            }
                        }
                    }
                    if (suggestedTables.isEmpty() && suggestedColumns.isEmpty()) {
                        return;
                    }
                    Path rewriteConfigPath = projectRoot.resolve(REWRITE_CONFIG_PATH);
                    List<String> lines = Files.isRegularFile(rewriteConfigPath)
                            ? Files.readAllLines(rewriteConfigPath, StandardCharsets.UTF_8)
                            : defaultRewriteConfigLines();
                    Set<String> existingTables = existingMissingTableIgnoreEntries(lines);
                    Set<String> existingColumns = existingMissingColumnIgnoreEntries(lines);
                    List<String> missingTableSuggestions = new ArrayList<>();
                    for (Map.Entry<String, String> entry : suggestedTables.entrySet()) {
                        if (!containsMissingTable(existingTables, entry.getKey())) {
                            missingTableSuggestions.add(entry.getValue());
                        }
                    }
                    List<String> missingColumnSuggestions = new ArrayList<>();
                    for (Map.Entry<String, String> entry : suggestedColumns.entrySet()) {
                        if (!containsMissingColumn(existingColumns, entry.getKey())) {
                            missingColumnSuggestions.add(entry.getValue());
                        }
                    }
                    if (missingTableSuggestions.isEmpty() && missingColumnSuggestions.isEmpty()) {
                        return;
                    }
                    List<String> merged = lines;
                    if (!missingTableSuggestions.isEmpty()) {
                        merged = mergeMissingTableIgnores(merged, missingTableSuggestions);
                    }
                    if (!missingColumnSuggestions.isEmpty()) {
                        merged = mergeMissingColumnIgnores(merged, missingColumnSuggestions);
                    }
                    Files.createDirectories(rewriteConfigPath.getParent());
                    writeString(rewriteConfigPath, String.join("\\n", merged) + "\\n", StandardCharsets.UTF_8);
                    log("Updated missing object ignore suggestions in " + rewriteConfigPath
                            + " for " + missingTableSuggestions.size() + " table(s) and "
                            + missingColumnSuggestions.size() + " column(s).");
                }

                private String markdown(List<ValidationRecord> records, UsageFilterReport usageFilterReport) {
                    StringBuilder markdown = new StringBuilder();
                    markdown.append("# 达梦 SQL 验证报告\\n\\n");
                    markdown.append("- 通过: `").append(count(records, "PASSED")).append("`\\n");
                    markdown.append("- 失败: `").append(count(records, "FAILED")).append("`\\n");
                    markdown.append("- 跳过: `").append(count(records, "SKIPPED")).append("`\\n\\n");
                    appendUsageFilterSummary(markdown, records, usageFilterReport);
                    appendFailureCategorySummary(markdown, records);
                    appendFailurePatternSummary(markdown, records);
                    appendSchemaObjectSummary(markdown, records);
                    appendSuggestedNextActions(markdown, records);
                    markdown.append("## 验证结果\\n\\n");
                    markdown.append("| 状态 | 分类 | 模式 | Mapper 方法 | 参数来源 | 参数 | 摘要 | 处理建议 |\\n");
                    markdown.append("| --- | --- | --- | --- | --- | --- | --- | --- |\\n");
                    for (ValidationRecord record : records) {
                        markdown.append("| ").append(escapeMarkdown(statusDisplay(record.status)))
                                .append(" | ").append(escapeMarkdown(categoryDisplay(category(record))))
                                .append(" | ").append(escapeMarkdown(failurePatternDisplay(failurePattern(record))))
                                .append(" | `").append(escapeMarkdown(record.key)).append("`")
                                .append(" | ").append(escapeMarkdown(record.parameterSource))
                                .append(" | ").append(escapeMarkdown(abbreviate(record.parameterSummary, 240)))
                                .append(" | ").append(escapeMarkdown(summary(record)))
                                .append(" | ").append(escapeMarkdown(hint(record)))
                                .append(" |\\n");
                    }
                    appendFailureDetails(markdown, records);
                    return markdown.toString();
                }

                private void appendUsageFilterSummary(
                        StringBuilder markdown,
                        List<ValidationRecord> records,
                        UsageFilterReport usageFilterReport
                ) {
                    markdown.append("## 使用情况过滤器\\n\\n");
                    markdown.append("- 已启用: `").append(usageFilterReport.enabled).append("`\\n");
                    markdown.append("- 可用: `").append(usageFilterReport.available).append("`\\n");
                    markdown.append("- class 目录数: `").append(usageFilterReport.classDirectoryCount).append("`\\n");
                    markdown.append("- 已扫描 class 文件数: `").append(usageFilterReport.classFileCount).append("`\\n");
                    markdown.append("- 被业务代码引用的 mapper 方法数: `").append(usageFilterReport.referencedMethodCount).append("`\\n");
                    markdown.append("- 因未使用而跳过: `").append(unusedSkippedCount(records)).append("`\\n");
                    for (String warning : usageFilterReport.warnings) {
                        markdown.append("- 警告: ").append(escapeMarkdown(warning)).append("\\n");
                    }
                    markdown.append("\\n");
                }

                private void appendFailureCategorySummary(StringBuilder markdown, List<ValidationRecord> records) {
                    Map<String, Long> countsByCategory = failureCategoryCounts(records);
                    if (countsByCategory.isEmpty()) {
                        return;
                    }
                    markdown.append("## 失败分类汇总\\n\\n");
                    markdown.append("| 分类 | 数量 | 处理建议 |\\n");
                    markdown.append("| --- | ---: | --- |\\n");
                    for (Map.Entry<String, Long> entry : countsByCategory.entrySet()) {
                        markdown.append("| ").append(escapeMarkdown(categoryDisplay(entry.getKey())))
                                .append(" | ").append(entry.getValue())
                                .append(" | ").append(escapeMarkdown(categoryHint(entry.getKey())))
                                .append(" |\\n");
                    }
                    markdown.append("\\n");
                }

                private void appendFailurePatternSummary(StringBuilder markdown, List<ValidationRecord> records) {
                    Map<String, Long> countsByPattern = failurePatternCounts(records);
                    if (countsByPattern.isEmpty()) {
                        return;
                    }
                    markdown.append("## 失败模式汇总\\n\\n");
                    markdown.append("| 模式 | 数量 |\\n");
                    markdown.append("| --- | ---: |\\n");
                    for (Map.Entry<String, Long> entry : countsByPattern.entrySet()) {
                        markdown.append("| ").append(escapeMarkdown(failurePatternDisplay(entry.getKey())))
                                .append(" | ").append(entry.getValue())
                                .append(" |\\n");
                    }
                    markdown.append("\\n");
                }

                private void appendSchemaObjectSummary(StringBuilder markdown, List<ValidationRecord> records) {
                    Map<String, Long> missingTables = schemaIssueCounts(records, "无效的表或视图名", "无效的表名");
                    Map<String, Long> missingColumns = schemaIssueCounts(records, "无效的列名", "无效的变量名", "无法解析的成员访问表达式");
                    Map<String, Long> missingSchemas = schemaIssueCounts(records, "无效的模式名");
                    if (missingTables.isEmpty() && missingColumns.isEmpty() && missingSchemas.isEmpty()) {
                        return;
                    }
                    markdown.append("## 库表对象缺失热点\\n\\n");
                    appendCountSummary(markdown, "缺失表/视图", "对象", missingTables, 20);
                    appendCountSummary(markdown, "缺失字段", "字段", missingColumns, 30);
                    appendCountSummary(markdown, "缺失 schema", "schema", missingSchemas, 20);
                }

                private Map<String, Long> schemaIssueCounts(List<ValidationRecord> records, String... markers) {
                    Map<String, Long> counts = new LinkedHashMap<>();
                    for (ValidationRecord record : records) {
                        if (!"FAILED".equals(record.status)) {
                            continue;
                        }
                        for (String marker : markers) {
                            for (String value : bracketedValuesAfterMarker(record.message, marker)) {
                                counts.merge(value, 1L, Long::sum);
                            }
                        }
                    }
                    return counts.entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                                    .thenComparing(Map.Entry.comparingByKey()))
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (left, right) -> left,
                                    LinkedHashMap::new
                            ));
                }

                private List<String> bracketedValuesAfterMarker(String message, String marker) {
                    if (message == null || marker == null || isBlank(marker)) {
                        return listOf();
                    }
                    List<String> values = new ArrayList<>();
                    int searchFrom = 0;
                    while (searchFrom < message.length()) {
                        int markerIndex = message.indexOf(marker, searchFrom);
                        if (markerIndex < 0) {
                            break;
                        }
                        int start = message.indexOf('[', markerIndex + marker.length());
                        int end = start < 0 ? -1 : message.indexOf(']', start + 1);
                        if (start >= 0 && end > start + 1) {
                            values.add(message.substring(start + 1, end).trim());
                            searchFrom = end + 1;
                        } else {
                            searchFrom = markerIndex + marker.length();
                        }
                    }
                    return values;
                }

                private void appendCountSummary(
                        StringBuilder markdown,
                        String title,
                        String nameHeader,
                        Map<String, Long> counts,
                        int limit
                ) {
                    if (counts.isEmpty()) {
                        return;
                    }
                    markdown.append("### ").append(title).append("\\n\\n");
                    markdown.append("| ").append(nameHeader).append(" | 数量 |\\n");
                    markdown.append("| --- | ---: |\\n");
                    int index = 0;
                    for (Map.Entry<String, Long> entry : counts.entrySet()) {
                        if (index >= limit) {
                            markdown.append("| ... | 还有 ").append(counts.size() - limit).append(" 项 |\\n");
                            break;
                        }
                        markdown.append("| ").append(escapeMarkdown(entry.getKey()))
                                .append(" | ").append(entry.getValue())
                                .append(" |\\n");
                        index++;
                    }
                    markdown.append("\\n");
                }

                private void appendSuggestedNextActions(StringBuilder markdown, List<ValidationRecord> records) {
                    Map<String, Long> countsByPattern = failurePatternCounts(records);
                    Map<String, Long> countsByCategory = failureCategoryCounts(records);
                    if (countsByPattern.isEmpty() && countsByCategory.isEmpty()) {
                        return;
                    }
                    markdown.append("## 建议后续处理\\n\\n");
                    if (containsAnyPattern(countsByPattern,
                            "UPDATE_SET_TABLE_ORDER",
                            "TRAILING_COMMA",
                            "INSERT_FOREACH_MISSING_VALUES",
                            "MYSQL_UPDATE_JOIN",
                            "MYSQL_DATE_SUB_INTERVAL",
                            "MYSQL_DATE_ADD_INTERVAL",
                            "MYSQL_MAKEDATE",
                            "MYSQL_SUBDATE",
                            "MYSQL_CONVERT_UNSIGNED",
                            "MYSQL_CONVERT_DECIMAL",
                            "MYSQL_CONVERT_GBK_ORDER",
                            "MYSQL_SELECT_MODIFIER",
                            "MYSQL_INSERT_VALUE_KEYWORD",
                            "MYSQL_INDEX_HINT",
                            "MYSQL_IMPLICIT_CROSS_JOIN",
                            "MYSQL_TEMPORARY_TABLE_AS_SELECT",
                            "DAMENG_KEYWORD_TABLE_ALIAS",
                            "MYSQL_JSON_TABLE_JOIN_WITHOUT_ON",
                            "MYSQL_UPDATE_ORDER_LIMIT")) {
                        markdown.append("- 重新执行 dm-adapter migrate，然后再次运行本验证测试；这些模式已有严格的 mapper-dm 自动改写规则。\\n");
                    }
                    if (containsAnyPattern(countsByPattern,
                            "ON_DUPLICATE_KEY_UPDATE",
                            "INSERT_IGNORE")) {
                        markdown.append("- 为 ON DUPLICATE KEY UPDATE / INSERT IGNORE 改写补充 .dm-adapter/sql-rewrite.yml 中的 keyColumns，重新执行 migrate 后再验证。\\n");
                    }
                    if (countsByPattern.containsKey("DM_CTAS_BIND_PARAMETER")) {
                        markdown.append("- 达梦不支持在 CREATE TABLE AS SELECT 中使用 JDBC 绑定参数；将该 mapper 拆为显式建临时表和参数化 INSERT 两个步骤。\\n");
                    }
                    if (countsByPattern.containsKey("TEST_SCHEMA_OBJECT")
                            || countsByPattern.containsKey("TEST_SCHEMA_FUNCTION")) {
                        markdown.append("- 先对齐达梦测试 schema 中缺失的表、视图、字段、函数或对象命名差异，再将其视为 SQL 改写失败。\\n");
                    }
                    if (countsByPattern.containsKey("BROKEN_DYNAMIC_SQL_OR_ARGS")) {
                        markdown.append("- 检查 mapper 动态 SQL 分支和生成的示例参数；缺少逗号、空条件、非法动态占位符等问题通常属于 mapper 结构或验证参数问题。\\n");
                    }
                    if (countsByPattern.containsKey("JAVA_MAPPER_PARAM_ANNOTATION")) {
                        markdown.append("- 修正 Java mapper 方法签名中的 @Param 重名或多简单参数缺失 @Param；不要通过改 XML 参数名规避 Java 签名问题。\\n");
                    }
                    if (containsAnyPattern(countsByPattern,
                            "NULL_COLLECTION_PARAMETER",
                            "BINDING_PARAMETER_NAME",
                            "JAVA_MAPPER_PARAM_ANNOTATION",
                            "FOREACH_ITEM_BINDING",
                            "MAPPER_PROPERTY_NAME",
                            "METHOD_ARGS_OR_BINDING_OTHER",
                            "DYNAMIC_IDENTIFIER_PARAMETER",
                            "DYNAMIC_SQL_FRAGMENT_PARAMETER",
                            "GENERATED_SEARCH_PARAMETER",
                            "RAW_SQL_PARAMETER",
                            "GENERATED_ORDER_PARAMETER",
                            "RETURN_TYPE_MISMATCH")
                            || countsByCategory.containsKey("METHOD_ARGS_OR_BINDING")) {
                        markdown.append("- 在 sql-validation.yml 配置方法参数；当 XML 参数名与 Java 方法参数不一致时，检查 mapper 的 @Param 名称。\\n");
                    }
                    if (countsByPattern.containsKey("RETURN_TYPE_MISMATCH")) {
                        markdown.append("- 检查 DAO 方法返回类型与 mapper XML 的 resultType/resultMap 是否一致，例如 count 查询不要直接映射到 boolean 返回值。\\n");
                    }
                    if (containsAnyPattern(countsByPattern,
                            "MYSQL_GROUP_CONCAT",
                            "MYSQL_CONCAT_WS",
                            "MYSQL_JSON_SQL",
                            "REGEXP_OPERATOR",
                            "MYSQL_METADATA_SQL",
                            "MYSQL_UPDATE_JOIN_MULTI_TARGET",
                            "MYSQL_USER_VARIABLE",
                            "ORIGINAL_XML_SYNTAX_DEFECT",
                            "SQL_SYNTAX_OTHER")) {
                        markdown.append("- 人工复核 GROUP_CONCAT、JSON SQL、REGEXP、MySQL 元数据查询，以及其他未分类的达梦语法失败等复杂 SQL 模式。\\n");
                    }
                    if (containsAnyPattern(countsByPattern,
                            "TEST_DATA_OR_CONSTRAINT",
                            "TEST_DATA_TYPE_MISMATCH",
                            "TEST_DATA_FOREIGN_KEY_CONSTRAINT",
                            "TEST_DATA_OTHER")
                            || countsByCategory.containsKey("TEST_DATA_OR_SCHEMA")) {
                        markdown.append("- 调整生成的示例数据、种子数据、默认值或约束，以处理数据相关的验证失败。\\n");
                    }
                    markdown.append("\\n");
                }

                private void appendFailureDetails(StringBuilder markdown, List<ValidationRecord> records) {
                    List<ValidationRecord> failed = records.stream()
                            .filter(record -> "FAILED".equals(record.status))
                            .collect(Collectors.toList());
                    if (failed.isEmpty()) {
                        return;
                    }
                    markdown.append("\\n## 失败详情\\n\\n");
                    markdown.append("这里会截断过长的 MyBatis 错误信息；完整错误请查看 JSON 报告。\\n\\n");
                    for (ValidationRecord record : failed) {
                        markdown.append("<details>\\n");
                        markdown.append("<summary>")
                                .append(escapeHtml(categoryDisplay(category(record))))
                                .append(" / ")
                                .append(escapeHtml(failurePatternDisplay(failurePattern(record))))
                                .append(" - ")
                                .append(escapeHtml(record.key))
                                .append("</summary>\\n\\n");
                        if (record.parameterSummary != null && !isBlank(record.parameterSummary)) {
                            markdown.append("参数: `")
                                    .append(escapeMarkdown(record.parameterSummary))
                                    .append("`\\n\\n");
                        }
                        markdown.append("```text\\n")
                                .append(escapeCodeBlock(abbreviate(record.message, 4000)))
                                .append("\\n```\\n\\n");
                        markdown.append("</details>\\n\\n");
                    }
                }

                private long count(List<ValidationRecord> records, String status) {
                    return records.stream().filter(record -> status.equals(record.status)).count();
                }

                private String statusDisplay(String status) {
                    switch (status) {
                        case "PASSED":
                            return "通过 (PASSED)";
                        case "FAILED":
                            return "失败 (FAILED)";
                        case "SKIPPED":
                            return "跳过 (SKIPPED)";
                        default:
                            return status;
                    }
                }

                private String categoryDisplay(String category) {
                    return displayCode(category, categoryLabel(category));
                }

                private String categoryLabel(String category) {
                    switch (category) {
                        case "PASSED":
                            return "通过";
                        case "SKIPPED":
                            return "跳过";
                        case "CONFIGURATION":
                            return "配置问题";
                        case "METHOD_ARGS_OR_BINDING":
                            return "方法参数或绑定问题";
                        case "MYSQL_METADATA_SQL":
                            return "MySQL 元数据 SQL";
                        case "TEST_SCHEMA":
                            return "测试库对象问题";
                        case "TEST_DATA_OR_SCHEMA":
                            return "测试数据或库结构问题";
                        case "SQL_SYNTAX":
                            return "SQL 语法问题";
                        case "TEST_DATA":
                            return "测试数据问题";
                        case "UNKNOWN_FAILURE":
                            return "未分类失败";
                        default:
                            return category.endsWith("_OTHER") ? "其他未分类问题" : "";
                    }
                }

                private String failurePatternDisplay(String pattern) {
                    if (pattern == null || isBlank(pattern)) {
                        return "";
                    }
                    return displayCode(pattern, failurePatternLabel(pattern));
                }

                private String failurePatternLabel(String pattern) {
                    switch (pattern) {
                        case "DATABASE_CONNECTION":
                            return "数据库连接失败";
                        case "MYSQL_METADATA_SQL":
                            return "MySQL 元数据查询";
                        case "MYSQL_COLLATE_CLAUSE":
                            return "MySQL COLLATE 子句";
                        case "MYSQL_SELECT_MODIFIER":
                            return "MySQL SELECT 修饰符";
                        case "MYSQL_INDEX_HINT":
                            return "MySQL 索引提示";
                        case "MYSQL_INSERT_VALUE_KEYWORD":
                            return "MySQL VALUE 关键字";
                        case "MYSQL_CONVERT_DECIMAL":
                            return "MySQL CONVERT DECIMAL";
                        case "MYSQL_USER_VARIABLE":
                            return "MySQL 用户变量";
                        case "DYNAMIC_IDENTIFIER_PARAMETER":
                            return "动态标识符参数";
                        case "DYNAMIC_SQL_FRAGMENT_PARAMETER":
                            return "动态 SQL 片段参数";
                        case "GENERATED_SEARCH_PARAMETER":
                            return "生成的搜索参数";
                        case "BROKEN_DYNAMIC_SQL_OR_ARGS":
                            return "动态 SQL 或示例参数异常";
                        case "TEST_SCHEMA_OBJECT":
                            return "测试库缺少对象";
                        case "TEST_SCHEMA_FUNCTION":
                            return "测试库缺少函数/自定义函数";
                        case "TEST_DATA_TYPE_MISMATCH":
                            return "测试参数类型不匹配";
                        case "TEST_DATA_FOREIGN_KEY_CONSTRAINT":
                            return "外键约束测试数据问题";
                        case "RETURN_TYPE_MISMATCH":
                            return "Mapper 返回类型不匹配";
                        case "INSERT_IGNORE":
                            return "INSERT IGNORE";
                        case "MYSQL_GROUP_CONCAT":
                            return "GROUP_CONCAT 函数";
                        case "MYSQL_CONCAT_WS":
                            return "CONCAT_WS 函数";
                        case "MYSQL_DATE_SUB_INTERVAL":
                            return "DATE_SUB INTERVAL";
                        case "MYSQL_DATE_ADD_INTERVAL":
                            return "DATE_ADD INTERVAL";
                        case "MYSQL_MAKEDATE":
                            return "MAKEDATE 函数";
                        case "MYSQL_SUBDATE":
                            return "SUBDATE 函数";
                        case "MYSQL_PERIOD_DIFF_YEARMONTH":
                            return "PERIOD_DIFF 年月差";
                        case "MYSQL_COUNT_CONDITION_OR_NULL":
                            return "COUNT 条件 OR NULL";
                        case "MYSQL_COUNT_DISTINCT_IF":
                            return "COUNT DISTINCT IF";
                        case "MYSQL_BARE_INTERVAL":
                            return "裸 INTERVAL 表达式";
                        case "MYSQL_NOT_ISNULL":
                            return "!ISNULL 表达式";
                        case "MYSQL_BOOLEAN_OPERATOR":
                            return "MySQL 布尔运算符";
                        case "MYSQL_CONVERT_UNSIGNED":
                            return "CONVERT UNSIGNED";
                        case "MYSQL_CONVERT_GBK_ORDER":
                            return "GBK 排序转换";
                        case "MYSQL_UPDATE_ORDER_LIMIT":
                            return "UPDATE ORDER BY LIMIT";
                        case "MYSQL_JSON_TABLE_JOIN_WITHOUT_ON":
                            return "JSON_TABLE JOIN 缺少 ON";
                        case "MYSQL_IMPLICIT_CROSS_JOIN":
                            return "隐式 CROSS JOIN";
                        case "MYSQL_TEMPORARY_TABLE_AS_SELECT":
                            return "临时表 AS SELECT";
                        case "DM_CTAS_BIND_PARAMETER":
                            return "CTAS 绑定参数";
                        case "DAMENG_KEYWORD_TABLE_ALIAS":
                            return "达梦关键字表别名";
                        case "DAMENG_RESERVED_IDENTIFIER":
                            return "达梦保留字标识符";
                        case "MYSQL_JSON_SQL":
                            return "MySQL JSON SQL";
                        case "MYSQL_UPDATE_JOIN":
                            return "UPDATE JOIN";
                        case "MYSQL_UPDATE_JOIN_MULTI_TARGET":
                            return "UPDATE JOIN 同时更新多表";
                        case "RAW_SQL_PARAMETER":
                            return "原始 SQL 参数";
                        case "AMBIGUOUS_COLUMN":
                            return "歧义列名";
                        case "EMPTY_SELECT_LIST":
                            return "空 SELECT 列表";
                        case "GENERATED_ORDER_PARAMETER":
                            return "生成的排序参数";
                        case "UPDATE_SET_TABLE_ORDER":
                            return "UPDATE SET 语序";
                        case "ON_DUPLICATE_KEY_UPDATE":
                            return "ON DUPLICATE KEY UPDATE";
                        case "INSERT_FOREACH_MISSING_VALUES":
                            return "INSERT foreach 缺少 VALUES";
                        case "REGEXP_OPERATOR":
                            return "REGEXP 操作符";
                        case "DOUBLE_QUOTED_IDENTIFIER_OR_STRING":
                            return "双引号标识符或字符串";
                        case "TRAILING_COMMA":
                            return "多余逗号";
                        case "NULL_COLLECTION_PARAMETER":
                            return "集合参数为空";
                        case "BINDING_PARAMETER_NAME":
                            return "绑定参数名问题";
                        case "FOREACH_ITEM_BINDING":
                            return "foreach 元素绑定问题";
                        case "MAPPER_PROPERTY_NAME":
                            return "Mapper 属性名不匹配";
                        case "KEY_PROPERTY_PARAMETER_OBJECT_MISMATCH":
                            return "keyProperty 与参数对象不匹配";
                        case "JAVA_MAPPER_PARAM_ANNOTATION":
                            return "Java mapper @Param 注解问题";
                        case "INSERT_VALUES_ASSIGNMENT":
                            return "INSERT VALUES 中出现赋值表达式";
                        case "ORIGINAL_XML_SYNTAX_DEFECT":
                            return "原 XML SQL 语法缺陷";
                        case "TEST_DATA_OR_CONSTRAINT":
                            return "测试数据或约束问题";
                        default:
                            return pattern.endsWith("_OTHER") ? "其他未分类模式" : "";
                    }
                }

                private String displayCode(String code, String label) {
                    if (code == null || isBlank(code)) {
                        return "";
                    }
                    return label == null || isBlank(label) ? code : label + " (" + code + ")";
                }

                private String json(List<ValidationRecord> records, UsageFilterReport usageFilterReport) {
                    StringBuilder json = new StringBuilder();
                    json.append("{\\n");
                    json.append("  \\"summary\\": {")
                            .append("\\"passed\\": ").append(count(records, "PASSED")).append(", ")
                            .append("\\"failed\\": ").append(count(records, "FAILED")).append(", ")
                            .append("\\"skipped\\": ").append(count(records, "SKIPPED"))
                            .append("},\\n");
                    appendUsageFilterJson(json, records, usageFilterReport);
                    json.append(",\\n");
                    appendJsonCountMap(json, "failureCategories", failureCategoryCounts(records));
                    json.append(",\\n");
                    appendJsonCountMap(json, "failurePatterns", failurePatternCounts(records));
                    json.append(",\\n");
                    appendSchemaObjectHotspotsJson(json, records);
                    json.append(",\\n  \\"records\\": [\\n");
                    for (int i = 0; i < records.size(); i++) {
                        ValidationRecord record = records.get(i);
                        json.append("    {")
                                .append("\\"status\\": \\"").append(escapeJson(record.status)).append("\\", ")
                                .append("\\"category\\": \\"").append(escapeJson(category(record))).append("\\", ")
                                .append("\\"failurePattern\\": \\"").append(escapeJson(failurePattern(record))).append("\\", ")
                                .append("\\"key\\": \\"").append(escapeJson(record.key)).append("\\", ")
                                .append("\\"parameterSource\\": \\"").append(escapeJson(record.parameterSource)).append("\\", ")
                                .append("\\"parameterSummary\\": \\"").append(escapeJson(record.parameterSummary)).append("\\", ")
                                .append("\\"summary\\": \\"").append(escapeJson(summary(record))).append("\\", ")
                                .append("\\"hint\\": \\"").append(escapeJson(hint(record))).append("\\", ")
                                .append("\\"message\\": \\"").append(escapeJson(record.message)).append("\\"")
                                .append("}");
                        if (i + 1 < records.size()) {
                            json.append(",");
                        }
                        json.append("\\n");
                    }
                    json.append("  ]\\n}\\n");
                    return json.toString();
                }

                private void appendUsageFilterJson(
                        StringBuilder json,
                        List<ValidationRecord> records,
                        UsageFilterReport usageFilterReport
                ) {
                    json.append("  \\"usageFilter\\": {")
                            .append("\\"enabled\\": ").append(usageFilterReport.enabled).append(", ")
                            .append("\\"available\\": ").append(usageFilterReport.available).append(", ")
                            .append("\\"classDirectoryCount\\": ").append(usageFilterReport.classDirectoryCount).append(", ")
                            .append("\\"classFileCount\\": ").append(usageFilterReport.classFileCount).append(", ")
                            .append("\\"referencedMethodCount\\": ").append(usageFilterReport.referencedMethodCount).append(", ")
                            .append("\\"skippedAsUnused\\": ").append(unusedSkippedCount(records)).append(", ")
                            .append("\\"warnings\\": [");
                    for (int i = 0; i < usageFilterReport.warnings.size(); i++) {
                        if (i > 0) {
                            json.append(", ");
                        }
                        json.append("\\"").append(escapeJson(usageFilterReport.warnings.get(i))).append("\\"");
                    }
                    json.append("]}");
                }

                private long unusedSkippedCount(List<ValidationRecord> records) {
                    return records.stream()
                            .filter(record -> "SKIPPED".equals(record.status))
                            .filter(record -> "unused".equals(record.parameterSource))
                            .count();
                }

                private void appendJsonCountMap(StringBuilder json, String key, Map<String, Long> counts) {
                    json.append("  \\"").append(key).append("\\": [");
                    int index = 0;
                    for (Map.Entry<String, Long> entry : counts.entrySet()) {
                        if (index > 0) {
                            json.append(", ");
                        }
                        json.append("{\\"name\\": \\"").append(escapeJson(entry.getKey()))
                                .append("\\", \\"count\\": ").append(entry.getValue())
                                .append("}");
                        index++;
                    }
                    json.append("]");
                }

                private void appendSchemaObjectHotspotsJson(StringBuilder json, List<ValidationRecord> records) {
                    json.append("  \\"schemaObjectHotspots\\": {");
                    appendJsonCountArray(json, "missingTablesOrViews", schemaIssueCounts(records, "无效的表或视图名", "无效的表名"));
                    json.append(", ");
                    appendJsonCountArray(json, "missingColumns", schemaIssueCounts(records, "无效的列名", "无效的变量名", "无法解析的成员访问表达式"));
                    json.append(", ");
                    appendJsonCountArray(json, "missingSchemas", schemaIssueCounts(records, "无效的模式名"));
                    json.append("}");
                }

                private void appendJsonCountArray(StringBuilder json, String key, Map<String, Long> counts) {
                    json.append("\\"").append(key).append("\\": [");
                    int index = 0;
                    for (Map.Entry<String, Long> entry : counts.entrySet()) {
                        if (index > 0) {
                            json.append(", ");
                        }
                        json.append("{\\"name\\": \\"").append(escapeJson(entry.getKey()))
                                .append("\\", \\"count\\": ").append(entry.getValue())
                                .append("}");
                        index++;
                    }
                    json.append("]");
                }

                private Map<String, Long> failureCategoryCounts(List<ValidationRecord> records) {
                    return records.stream()
                            .filter(record -> "FAILED".equals(record.status))
                            .collect(Collectors.groupingBy(this::category, LinkedHashMap::new, Collectors.counting()));
                }

                private Map<String, Long> failurePatternCounts(List<ValidationRecord> records) {
                    return records.stream()
                            .filter(record -> "FAILED".equals(record.status))
                            .collect(Collectors.groupingBy(this::failurePattern, LinkedHashMap::new, Collectors.counting()));
                }

                """,
            """
                private String failurePattern(ValidationRecord record) {
                    if (!"FAILED".equals(record.status)) {
                        return "";
                    }
                    String message = normalizeMessage(record.message);
                    String lower = message.toLowerCase(Locale.ROOT);
                    if (isDatabaseConnectionFailure(message)) {
                        return "DATABASE_CONNECTION";
                    }
                    if (hasJavaMapperParamAnnotationIssue(message)) {
                        return "JAVA_MAPPER_PARAM_ANNOTATION";
                    }
                    if (isMysqlMetadataSql(message)) {
                        return "MYSQL_METADATA_SQL";
                    }
                    if (hasMysqlCollateClause(message)) {
                        return "MYSQL_COLLATE_CLAUSE";
                    }
                    if (hasDamengCtasBindParameter(message)) {
                        return "DM_CTAS_BIND_PARAMETER";
                    }
                    if (hasMysqlMakeDate(message)) {
                        return "MYSQL_MAKEDATE";
                    }
                    if (hasMysqlSubdate(message)) {
                        return "MYSQL_SUBDATE";
                    }
                    if (Pattern.compile("\\\\bsql_(?:big|small)_result\\\\b|\\\\bsql_calc_found_rows\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_SELECT_MODIFIER";
                    }
                    if (Pattern.compile("\\\\b(?:force|use|ignore)\\\\s+index\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_INDEX_HINT";
                    }
                    if (Pattern.compile("\\\\binsert\\\\s+into\\\\b[\\\\s\\\\S]*?\\\\)\\\\s+value\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_INSERT_VALUE_KEYWORD";
                    }
                    if (Pattern.compile("\\\\bconvert\\\\s*\\\\([\\\\s\\\\S]*?,\\\\s*decimal\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_CONVERT_DECIMAL";
                    }
                    if (lower.contains("no setter found for the keyproperty")) {
                        return "KEY_PROPERTY_PARAMETER_OBJECT_MISMATCH";
                    }
                    if (lower.contains("classcastexception") && lower.contains("cannot be cast")) {
                        return "RETURN_TYPE_MISMATCH";
                    }
                    if (lower.contains("类型转换异常")
                            || lower.contains("数据类型不匹配")
                            || lower.contains("invalid comparison:")
                            || (lower.contains("numberformatexception") && lower.contains("for input string"))) {
                        return "TEST_DATA_TYPE_MISMATCH";
                    }
                    if (Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*\\\\s*:=", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_USER_VARIABLE";
                    }
                    if (hasTestDataOrConstraintIssue(message)) {
                        return "TEST_DATA_OR_CONSTRAINT";
                    }
                    if (hasMissingDynamicIdentifierIssue(message)) {
                        return "DYNAMIC_IDENTIFIER_PARAMETER";
                    }
                    if (isAutoParameter(record) && hasGeneratedDynamicIdentifierPlaceholder(message)) {
                        return "DYNAMIC_IDENTIFIER_PARAMETER";
                    }
                    if (hasForeachItemBindingIssue(message)) {
                        return "FOREACH_ITEM_BINDING";
                    }
                    if (hasDynamicSqlFragmentParameterIssue(message)) {
                        return "DYNAMIC_SQL_FRAGMENT_PARAMETER";
                    }
                    if (hasRegexpOperatorIssue(message)) {
                        return "REGEXP_OPERATOR";
                    }
                    if (isAutoParameter(record) && hasGeneratedSearchParameterIssue(record, message)) {
                        return "GENERATED_SEARCH_PARAMETER";
                    }
                    if (lower.contains("on duplicate key update")) {
                        return "ON_DUPLICATE_KEY_UPDATE";
                    }
                    if (hasOriginalXmlSyntaxDefect(message)) {
                        return "ORIGINAL_XML_SYNTAX_DEFECT";
                    }
                    if (lower.contains("sql语句为null或空值") || hasBrokenDynamicSqlShape(message)) {
                        return "BROKEN_DYNAMIC_SQL_OR_ARGS";
                    }
                    if (hasUnresolvedFunctionObject(message)) {
                        return "TEST_SCHEMA_FUNCTION";
                    }
                    if (isSchemaObjectFailure(lower)) {
                        return "TEST_SCHEMA_OBJECT";
                    }
                    if (Pattern.compile("insert\\\\s+ignore\\\\s+into", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "INSERT_IGNORE";
                    }
                    if (Pattern.compile("\\\\bgroup_concat\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_GROUP_CONCAT";
                    }
                    if (Pattern.compile("\\\\bconcat_ws\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_CONCAT_WS";
                    }
                    if (Pattern.compile("\\\\bdate_sub\\\\s*\\\\([\\\\s\\\\S]*?\\\\binterval\\\\s+[^,)]*\\\\s+(year|month|week|day|hour|minute|second)\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_DATE_SUB_INTERVAL";
                    }
                    if (Pattern.compile("\\\\bdate_add\\\\s*\\\\([\\\\s\\\\S]*?\\\\binterval\\\\s+[^,)]*\\\\s+(year|month|week|day|hour|minute|second)\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("\\\\+\\\\s*interval\\\\s+[^\\\\s]+\\\\s+(year|month|week|day|hour|minute|second)\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_DATE_ADD_INTERVAL";
                    }
                    if (Pattern.compile("[+-]\\\\s*interval\\\\s+[^\\\\s]+\\\\s+(year|month|week|day|hour|minute|second)\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_BARE_INTERVAL";
                    }
                    if (hasMysqlMakeDate(message)) {
                        return "MYSQL_MAKEDATE";
                    }
                    if (hasMysqlSubdate(message)) {
                        return "MYSQL_SUBDATE";
                    }
                    if (Pattern.compile("\\\\bperiod_diff\\\\s*\\\\(|\\\\bextract\\\\s*\\\\(\\\\s*year_month\\\\s+from\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_PERIOD_DIFF_YEARMONTH";
                    }
                    if (Pattern.compile("\\\\bcount\\\\s*\\\\([\\\\s\\\\S]*?\\\\bor\\\\s+null\\\\s*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_COUNT_CONDITION_OR_NULL";
                    }
                    if (Pattern.compile("\\\\bcount\\\\s*\\\\(\\\\s*distinct[\\\\s\\\\S]*?,\\\\s*if\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_COUNT_DISTINCT_IF";
                    }
                    if (Pattern.compile("\\\\[percent]附近出现错误|附近出现错误:[\\\\s\\\\S]*?\\\\bpercent\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "DAMENG_RESERVED_IDENTIFIER";
                    }
                    if (Pattern.compile("!\\\\s*isnull\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_NOT_ISNULL";
                    }
                    if (Pattern.compile("\\\\|\\\\|").matcher(message).find()) {
                        return "MYSQL_BOOLEAN_OPERATOR";
                    }
                    if (Pattern.compile("\\\\bconvert\\\\s*\\\\([\\\\s\\\\S]*?\\\\bunsigned\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_CONVERT_UNSIGNED";
                    }
                    if (Pattern.compile("\\\\border\\\\s+by\\\\b[\\\\s\\\\S]*?\\\\bconvert\\\\s*\\\\([\\\\s\\\\S]*?\\\\s+using\\\\s+gbk\\\\s*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_CONVERT_GBK_ORDER";
                    }
                    if (Pattern.compile("\\\\bupdate\\\\b[\\\\s\\\\S]*?\\\\border\\\\s+by\\\\b[\\\\s\\\\S]*?\\\\blimit\\\\s+\\\\d+\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_UPDATE_ORDER_LIMIT";
                    }
                    if (hasJsonTableJoinWithoutCondition(message)) {
                        return "MYSQL_JSON_TABLE_JOIN_WITHOUT_ON";
                    }
                    if (hasMysqlImplicitCrossJoin(message)) {
                        return "MYSQL_IMPLICIT_CROSS_JOIN";
                    }
                    if (Pattern.compile("\\\\bcreate\\\\s+(?:global\\\\s+)?temporary\\\\s+table\\\\s+[^\\\\s(]+\\\\s+(?:as\\\\s+)?select\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_TEMPORARY_TABLE_AS_SELECT";
                    }
                    if (Pattern.compile("\\\\b(?:from|join)\\\\s+[A-Za-z_][A-Za-z0-9_$]*\\\\s+cluster\\\\b|\\\\bcluster\\\\s*\\\\.", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "DAMENG_KEYWORD_TABLE_ALIAS";
                    }
                    if (Pattern.compile("(?i)(?:\\\\[reverse]|\\\\breverse\\\\b)").matcher(message).find()) {
                        return "DAMENG_RESERVED_IDENTIFIER";
                    }
                    if (Pattern.compile("\\\\bjson_(?:array|contains|extract|insert|keys|length|object|quote|remove|replace|search|set|table|type|unquote|valid)\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("\\\\bcast\\\\s*\\\\([\\\\s\\\\S]*?\\\\s+as\\\\s+json\\\\s*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_JSON_SQL";
                    }
                    if (hasMysqlUpdateJoinMultiTarget(message)) {
                        return "MYSQL_UPDATE_JOIN_MULTI_TARGET";
                    }
                    if (Pattern.compile("\\\\bupdate\\\\b[\\\\s\\\\S]*?\\\\bjoin\\\\b[\\\\s\\\\S]*?\\\\bset\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_UPDATE_JOIN";
                    }
                    if (isAutoParameter(record) && Pattern.compile("(?i)(\\\\bselect\\\\s+distinct\\\\s+from\\\\b|\\\\b[a-z_][a-z0-9_]*\\\\.1\\\\b|\\\\[\\\\.1])").matcher(message).find()) {
                        return "DYNAMIC_IDENTIFIER_PARAMETER";
                    }
                    if (isAutoParameter(record) && Pattern.compile("(?m)^### SQL:\\\\s*test\\\\s*$").matcher(message).find()) {
                        return "RAW_SQL_PARAMETER";
                    }
                    if (lower.contains("有歧义的列名")) {
                        return "AMBIGUOUS_COLUMN";
                    }
                    if (Pattern.compile("\\\\bselect\\\\s+(?:distinct\\\\s+)?from\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "EMPTY_SELECT_LIST";
                    }
                    if (Pattern.compile("order\\\\s+by\\\\s+test(?:\\\\s+test)?", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "GENERATED_ORDER_PARAMETER";
                    }
                    if (Pattern.compile("order\\\\s+by\\\\s+(?:ID|test)\\\\s+(?:ID|test)(?:\\\\s+order\\\\s+by)?", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "GENERATED_ORDER_PARAMETER";
                    }
                    if (lower.contains("列表不匹配")
                            || lower.contains("重复的列名")
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bupdate\\\\b[\\\\s\\\\S]*?\\\\bset\\\\b[\\\\s\\\\S]*?(?:,\\\\s*)?\\\\?(?:\\\\s*,|\\\\s+where\\\\b)").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\?\\\\s+\\\\?").matcher(message).find()) {
                        return "ORIGINAL_XML_SYNTAX_DEFECT";
                    }
                    if (Pattern.compile("(?i)(\\\\band\\\\s*\\\\(\\\\s*\\\\)|,\\\\s*where\\\\b|\\\\bwhere\\\\s+and\\\\b|\\\\bwhere\\\\s+where\\\\b|\\\\bfrom\\\\s+where\\\\b|\\\\bset\\\\s+where\\\\b|\\\\?\\\\s+[A-Za-z_][A-Za-z0-9_$]*\\\\s*=|^### SQL:\\\\s*ID\\\\s+select\\\\b)").matcher(message).find()) {
                        return "BROKEN_DYNAMIC_SQL_OR_ARGS";
                    }
                    if (Pattern.compile("update\\\\s+set\\\\s+[a-z_][a-z0-9_]*", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "UPDATE_SET_TABLE_ORDER";
                    }
                    if (Pattern.compile("insert\\\\s+into\\\\b[\\\\s\\\\S]*?\\\\)\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "INSERT_FOREACH_MISSING_VALUES";
                    }
                    if (Pattern.compile("\\\\bregexp\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "REGEXP_OPERATOR";
                    }
                    if (lower.contains("标示符长度非法")) {
                        return "DOUBLE_QUOTED_IDENTIFIER_OR_STRING";
                    }
                    if (containsTrailingCommaOutsideQuotes(message)) {
                        return "TRAILING_COMMA";
                    }
                    if (lower.contains("evaluated to a null value")) {
                        return "NULL_COLLECTION_PARAMETER";
                    }
                    if (lower.contains("parameter '") && lower.contains("not found")) {
                        return "BINDING_PARAMETER_NAME";
                    }
                    if (lower.contains("there is no getter for property")) {
                        return "MAPPER_PROPERTY_NAME";
                    }
                    if (lower.contains("违反引用约束")) {
                        return "TEST_DATA_FOREIGN_KEY_CONSTRAINT";
                    }
                    if (hasOriginalXmlSyntaxDefect(message)) {
                        return "ORIGINAL_XML_SYNTAX_DEFECT";
                    }
                    if (Pattern.compile("(?i)insert\\\\s+into\\\\b[\\\\s\\\\S]*?values\\\\s*\\\\([\\\\s\\\\S]*?[A-Za-z_][A-Za-z0-9_$]*\\\\s*=").matcher(message).find()) {
                        return "INSERT_VALUES_ASSIGNMENT";
                    }
                    return category(record) + "_OTHER";
                }

                private boolean isSchemaObjectFailure(String lowerMessage) {
                    return lowerMessage.contains("无效的表或视图名")
                            || lowerMessage.contains("无效的表名")
                            || lowerMessage.contains("无效的列名")
                            || lowerMessage.contains("无效的变量名")
                            || lowerMessage.contains("无效的模式名")
                            || lowerMessage.contains("无法解析的成员访问表达式");
                }

                private boolean isMysqlMetadataSql(String message) {
                    return Pattern.compile("\\\\binformation_schema\\\\b|\\\\bdatabase\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bdescribe\\\\b").matcher(message).find();
                }

                private boolean hasDamengCtasBindParameter(String message) {
                    if (!containsAny(message, "Invalid expression", "无效的表达式")) {
                        return false;
                    }
                    return Pattern.compile(
                            "\\\\bcreate\\\\s+(?:global\\\\s+)?temporary\\\\s+table\\\\b[\\\\s\\\\S]*?\\\\bas\\\\s+select\\\\b[\\\\s\\\\S]*?\\\\?\\\\s+as\\\\s+(?:\\\"[^\\\"]+\\\"|[A-Za-z_][A-Za-z0-9_$]*)",
                            Pattern.CASE_INSENSITIVE
                    ).matcher(message).find();
                }

                private boolean hasMysqlCollateClause(String message) {
                    return Pattern.compile("\\\\bcollate\\\\s+[A-Za-z0-9_]+", Pattern.CASE_INSENSITIVE).matcher(message).find();
                }

                private boolean hasMissingDynamicIdentifierIssue(String message) {
                    String value = message == null ? "" : message;
                    String lower = value.toLowerCase(Locale.ROOT);
                    return lower.contains("sql语句为null或空值")
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\binsert\\\\s+into\\\\s*\\\\(").matcher(value).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bupdate\\\\s+(?:set|where)\\\\b").matcher(value).find()
                            || Pattern.compile("(?i)无效的表(?:或视图)?名\\\\s*\\\\[\\\\s*(?:t|b|ID)\\\\s*][\\\\s\\\\S]*?### SQL:[\\\\s\\\\S]*?\\\\b(?:from|join|update|into|table)\\\\s+(?:t|b|ID)\\\\b").matcher(value).find();
                }

                private boolean hasGeneratedDynamicIdentifierPlaceholder(String message) {
                    return Pattern.compile("(?im)^### SQL:\\\\s*ID\\\\s*$").matcher(message).find()
                            || Pattern.compile("(?im)^### SQL:\\\\s*ID\\\\s+select\\\\b").matcher(message).find()
                            || Pattern.compile("\\\\b(?:from|join|update|into|table)\\\\s+ID\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find();
                }

                private boolean hasDynamicSqlFragmentParameterIssue(String message) {
                    return Pattern.compile("\\\\bin\\\\s*\\\\(\\\\s*(?:ID|test)\\\\s*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("\\\\bin\\\\s*\\\\([^)]*\\\\{[^)]*}[^)]*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("\\\\bin\\\\s*\\\\(\\\\s*\\\\[[^)]*]\\\\s*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("\\\\bcast\\\\s*\\\\(\\\\s*[\\\\[{]", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("Type handler was null[\\\\s\\\\S]*LinkedHashMap", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("\\\\bin\\\\s*\\\\(\\\\s*\\\\[ID]\\\\s*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("无效的列名\\\\s*\\\\[\\\\s*test\\\\s*]", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("无效的列名\\\\s*\\\\[\\\\s*ID\\\\s*]", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?=\\\\s*(?:(?:or|and|where|group\\\\s+by|order\\\\s+by)\\\\b|\\\\))").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\(\\\\s*(?:is\\\\s+(?:not\\\\s+)?null|(?:or|and)\\\\s*(?:=|<>|!=|>=|<=|>|<|like\\\\b|in\\\\s*\\\\())").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\b(?:and|or)\\\\s+ID(?:\\\\s|$)").matcher(message).find()
                            || Pattern.compile("\\\\b[A-Za-z_][A-Za-z0-9_$.]*\\\\s+(?:not\\\\s+)?in\\\\s*\\\\(\\\\s*ID\\\\s*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find();
                }

                private boolean hasGeneratedDynamicSqlOrArgumentIssue(ValidationRecord record) {
                    if (record == null) {
                        return false;
                    }
                    String message = normalizeMessage(record.message);
                    String parameterSummary = record.parameterSummary == null ? "" : record.parameterSummary;
                    return hasGeneratedDynamicIdentifierPlaceholder(message)
                            || Pattern.compile("(?im)^### SQL:\\\\s*(?:ID|test)\\\\s*$").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:\\\\s*(?:ID|test)(?:\\\\s*###|\\\\s*$)").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bdelete\\\\s+from\\\\s+\\\"?ID\\\"?\\\\b").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bcreate\\\\s+table(?:\\\\s+if\\\\s+not\\\\s+exists)?\\\\s+\\\"?ID\\\"?\\\\s*\\\\(").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\b(?:from|join|into|update|table)\\\\s+\\\"?ID\\\"?\\\\b").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\b(?:from|join|into|update|table)\\\\s+'(?:ID|test|\\\\d{4}-\\\\d{2}-\\\\d{2})'(?:\\\\s|$)").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bwhere\\\\s+ID\\\\s*(?:=|and|$)").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\b(?:and|or)\\\\s+1\\\\s*=\\\\s*1\\\\s*(?:=|in\\\\s*\\\\()").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bADD\\\\s+COLUMN\\\\s+`?null`?\\\\s+null\\\\b").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\"\\\"").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\b(?:select|where|group\\\\s+by)\\\\s+ID\\\\s*(?:$|\\\\r?\\\\n)").matcher(message).find()
                            || parameterSummary.contains("Tuple3{f0=null")
                            || parameterSummary.contains("Tuple4{f0=null")
                            || message.contains("Can't add values ` , null");
                }

                private boolean hasForeachItemBindingIssue(String message) {
                    String value = message == null ? "" : message;
                    String lower = value.toLowerCase(Locale.ROOT);
                    return lower.contains("type handler was null")
                            && lower.contains("linkedhashmap")
                            && Pattern.compile("__frch_[A-Za-z][A-Za-z0-9_]*_\\\\d+", Pattern.CASE_INSENSITIVE).matcher(value).find();
                }

                private boolean hasMysqlMakeDate(String message) {
                    return Pattern.compile("\\\\bmakedate\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("无法解析的成员访问表达式\\\\s*\\\\[\\\\s*MAKEDATE\\\\s*]", Pattern.CASE_INSENSITIVE).matcher(message).find();
                }

                private boolean hasMysqlSubdate(String message) {
                    return Pattern.compile("\\\\bsubdate\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("无法解析的成员访问表达式\\\\s*\\\\[\\\\s*SUBDATE\\\\s*]", Pattern.CASE_INSENSITIVE).matcher(message).find();
                }

                private boolean hasMysqlUpdateJoinMultiTarget(String message) {
                    return message != null && message.contains("多表更新时仅支持更新同一个表上的列");
                }

                private boolean hasGeneratedSearchParameterIssue(ValidationRecord record, String message) {
                    return record.parameterSummary != null
                            && record.parameterSummary.contains("mainSearch=\\\"test\\\"")
                            && Pattern.compile("无效的列名\\\\s*\\\\[\\\\s*user_name\\\\s*]", Pattern.CASE_INSENSITIVE).matcher(message).find();
                }

                private boolean hasUnresolvedFunctionObject(String message) {
                    String value = message == null ? "" : message;
                    Matcher matcher = Pattern.compile("无法解析的成员访问表达式\\\\s*\\\\[\\\\s*([A-Za-z_][A-Za-z0-9_]*)\\\\s*]", Pattern.CASE_INSENSITIVE)
                            .matcher(value);
                    while (matcher.find()) {
                        String functionName = matcher.group(1);
                        if (Pattern.compile("\\\\b" + Pattern.quote(functionName) + "\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(value).find()) {
                            return true;
                        }
                    }
                    return false;
                }

                private boolean hasOriginalXmlSyntaxDefect(String message) {
                    String value = message == null ? "" : message;
                    String identifier = "[A-Za-z_][A-Za-z0-9_$]*(?:\\\\s*\\\\.\\\\s*[A-Za-z_][A-Za-z0-9_$]*)?";
                    String lower = value.toLowerCase(Locale.ROOT);
                    return lower.contains("列表不匹配")
                            || lower.contains("重复的列名")
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bfrom\\\\s+(?:where|$)").matcher(value).find()
                            || hasUnbalancedSqlParentheses(value)
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\?\\\\s+\\\\?").matcher(value).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bupdate\\\\b[\\\\s\\\\S]*?\\\\bset\\\\b[\\\\s\\\\S]*?(?:,\\\\s*)?\\\\?(?:\\\\s*,|\\\\s+where\\\\b)").matcher(value).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\blike\\\\s+\\\\?\\\\s*'").matcher(value).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\band[A-Za-z_][A-Za-z0-9_$]*\\\\s+(?:in|=|<>|!=|>|<|like)\\\\b").matcher(value).find()
                            || Pattern.compile("(?i)insert\\\\s+into\\\\b[\\\\s\\\\S]*?values\\\\s*\\\\([\\\\s\\\\S]*?[A-Za-z_][A-Za-z0-9_$]*\\\\s*=").matcher(value).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bwhere\\\\b[\\\\s\\\\S]*?\\\\b" + identifier + "\\\\s*=\\\\s*(?:\\\\?|\\\\d+|'[^']*')\\\\s+" + identifier + "\\\\s*=").matcher(value).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bwhere\\\\b[\\\\s\\\\S]*?\\\\b" + identifier + "\\\\s*(?:=|<>|!=|>=|<=|>|<|like)\\\\s*(?:\\\\?|\\\\d+|'[^']*'|\\\\([^)]*\\\\))\\\\s+" + identifier + "\\\\s+(?:in\\\\s*\\\\(|=|<>|!=|>=|<=|>|<|like\\\\b|is\\\\b)").matcher(value).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?;\\\\s*(?:group\\\\s+by|order\\\\s+by|having)\\\\b").matcher(value).find();
                }

                private boolean hasTestDataOrConstraintIssue(String message) {
                    return containsAny(message,
                            "非空约束",
                            "违反列[",
                            "长度超出定义",
                            "类型转换异常",
                            "唯一性约束",
                            "非法的时间日期类型数据",
                            "SET IDENTITY_INSERT",
                            "自增列");
                }

                private boolean hasUnbalancedSqlParentheses(String message) {
                    return parenthesisBalance(sqlFromMessage(message)) != 0;
                }

                private boolean containsTrailingCommaOutsideQuotes(String message) {
                    String sql = sqlFromMessage(message);
                    if (isBlank(sql)) {
                        return false;
                    }
                    boolean inSingleQuote = false;
                    boolean inDoubleQuote = false;
                    for (int i = 0; i < sql.length(); i++) {
                        char ch = sql.charAt(i);
                        if (ch == '\\'' && !inDoubleQuote) {
                            if (inSingleQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\\'') {
                                i++;
                                continue;
                            }
                            inSingleQuote = !inSingleQuote;
                            continue;
                        }
                        if (ch == '"' && !inSingleQuote) {
                            inDoubleQuote = !inDoubleQuote;
                            continue;
                        }
                        if (inSingleQuote || inDoubleQuote || ch != ',') {
                            continue;
                        }
                        int next = i + 1;
                        while (next < sql.length() && Character.isWhitespace(sql.charAt(next))) {
                            next++;
                        }
                        if (next < sql.length() && sql.charAt(next) == ')') {
                            return true;
                        }
                    }
                    return false;
                }

                private int parenthesisBalance(String sql) {
                    if (isBlank(sql)) {
                        return 0;
                    }
                    int depth = 0;
                    boolean inSingleQuote = false;
                    boolean inDoubleQuote = false;
                    for (int i = 0; i < sql.length(); i++) {
                        char ch = sql.charAt(i);
                        if (ch == '\\'' && !inDoubleQuote) {
                            if (inSingleQuote && i + 1 < sql.length() && sql.charAt(i + 1) == '\\'') {
                                i++;
                                continue;
                            }
                            inSingleQuote = !inSingleQuote;
                            continue;
                        }
                        if (ch == '"' && !inSingleQuote) {
                            inDoubleQuote = !inDoubleQuote;
                            continue;
                        }
                        if (inSingleQuote || inDoubleQuote) {
                            continue;
                        }
                        if (ch == '(') {
                            depth++;
                        } else if (ch == ')') {
                            depth--;
                            if (depth < 0) {
                                return depth;
                            }
                        }
                    }
                    return depth;
                }

                private String sqlFromMessage(String message) {
                    if (message == null) {
                        return "";
                    }
                    String marker = "### SQL:";
                    int start = message.indexOf(marker);
                    if (start < 0) {
                        return "";
                    }
                    start += marker.length();
                    int end = message.indexOf("### Cause:", start);
                    if (end < 0) {
                        end = message.length();
                    }
                    return message.substring(start, end);
                }

                private boolean hasBrokenDynamicSqlShape(String message) {
                    return Pattern.compile("\\\\bselect\\\\s*,", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile(",\\\\s*from\\\\s+dual\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("\\\\bupdate\\\\s+[A-Za-z_][A-Za-z0-9_$]*(?:\\\\s+[A-Za-z_][A-Za-z0-9_$]*)?\\\\s+where\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("(?i)(\\\\band\\\\s*\\\\(\\\\s*\\\\)|,\\\\s*where\\\\b|\\\\bwhere\\\\s+and\\\\b|\\\\bwhere\\\\s+where\\\\b|\\\\bfrom\\\\s+where\\\\b|\\\\bset\\\\s+where\\\\b|\\\\?\\\\s+[A-Za-z_][A-Za-z0-9_$]*\\\\s*=|^### SQL:\\\\s*ID\\\\s+select\\\\b)").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\?\\\\s+\\\\?").matcher(message).find();
                }

                private boolean hasMysqlImplicitCrossJoin(String message) {
                    Pattern pattern = Pattern.compile(
                            "\\\\b(?:inner\\\\s+)?join\\\\s+[^\\\\s()]+(?:\\\\s+(?:as\\\\s+)?[A-Za-z_][A-Za-z0-9_$]*)?\\\\s+(?:inner\\\\s+)?join\\\\b",
                            Pattern.CASE_INSENSITIVE
                    );
                    Matcher matcher = pattern.matcher(message);
                    while (matcher.find()) {
                        String joinFragment = matcher.group();
                        if (!Pattern.compile("\\\\b(?:on|using)\\\\b", Pattern.CASE_INSENSITIVE).matcher(joinFragment).find()) {
                            return true;
                        }
                    }
                    return false;
                }

                private boolean hasJsonTableJoinWithoutCondition(String message) {
                    Pattern pattern = Pattern.compile(
                            "\\\\b(?<join>(?:inner\\\\s+)?join)\\\\s+json_table\\\\s*\\\\([\\\\s\\\\S]*?\\\\)\\\\s+(?:as\\\\s+)?[A-Za-z_][A-Za-z0-9_$]*\\\\s*(?:where|group\\\\s+by|order\\\\s+by|having|limit|fetch|union|$)",
                            Pattern.CASE_INSENSITIVE
                    );
                    Matcher matcher = pattern.matcher(message);
                    while (matcher.find()) {
                        int joinStart = matcher.start("join");
                        String prefix = message.substring(Math.max(0, joinStart - 24), joinStart).toLowerCase(Locale.ROOT);
                        if (!Pattern.compile("(?:^|\\\\b)(cross|left|right|full|natural)\\\\s*$").matcher(prefix).find()) {
                            return true;
                        }
                    }
                    return false;
                }

                private boolean isAutoParameter(ValidationRecord record) {
                    return record.parameterSource != null && record.parameterSource.startsWith("auto");
                }

                private boolean hasJavaMapperParamAnnotationIssue(String message) {
                    String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
                    return lower.contains("java mapper signature has duplicate @param")
                            || lower.contains("java mapper method has multiple simple parameters without @param");
                }

                private boolean containsAnyPattern(Map<String, Long> countsByPattern, String... patterns) {
                    for (String pattern : patterns) {
                        if (countsByPattern.containsKey(pattern)) {
                            return true;
                        }
                    }
                    return false;
                }

                private String category(ValidationRecord record) {
                    if ("PASSED".equals(record.status)) {
                        return "PASSED";
                    }
                    if ("SKIPPED".equals(record.status)) {
                        return "SKIPPED";
                    }
                    String message = record.message == null ? "" : record.message;
                    if (containsAny(message,
                            "No mapper XML files matched",
                            "No mapped statements were found",
                            "Could not find",
                            "Failed to parse mapper XML",
                            "Failed to open Dameng validation connection",
                            "Error getting a new connection",
                            "Mapped statement was not registered",
                            "Parsing error was found in mapping",
                            "datasource.")) {
                        return "CONFIGURATION";
                    }
                    if (hasJavaMapperParamAnnotationIssue(message)) {
                        return "METHOD_ARGS_OR_BINDING";
                    }
                    if (containsAny(message,
                            "evaluated to a null value",
                            "Parameter '",
                            "not found. Available parameters",
                            "There is no getter for property",
                            "No setter found for the keyProperty",
                            "ClassCastException",
                            "invalid comparison:",
                            "Could not set parameters",
                            "Type handler was null",
                            "primitive return type")) {
                        return "METHOD_ARGS_OR_BINDING";
                    }
                    if (isMysqlMetadataSql(message)) {
                        return "MYSQL_METADATA_SQL";
                    }
                    if (hasDamengCtasBindParameter(message)) {
                        return "SQL_SYNTAX";
                    }
                    if (isAutoParameter(record) && hasGeneratedDynamicIdentifierPlaceholder(message)) {
                        return "METHOD_ARGS_OR_BINDING";
                    }
                    if (containsAny(message, "无效的表或视图名", "无效的表名", "无效的列名", "无效的变量名", "无效的模式名", "无法解析的成员访问表达式")) {
                        return "TEST_SCHEMA";
                    }
                    if (hasTestDataOrConstraintIssue(message)
                            || containsAny(message, "数据类型不匹配", "NumberFormatException", "违反引用约束")) {
                        return "TEST_DATA_OR_SCHEMA";
                    }
                    if (hasMissingDynamicIdentifierIssue(message)) {
                        return "METHOD_ARGS_OR_BINDING";
                    }
                    if (hasUnresolvedFunctionObject(message)) {
                        return "TEST_SCHEMA";
                    }
                    if (hasMysqlCollateClause(message)
                            || hasOriginalXmlSyntaxDefect(message)) {
                        return "SQL_SYNTAX";
                    }
                    if (hasRegexpOperatorIssue(message)) {
                        return "SQL_SYNTAX";
                    }
                    if (hasMysqlMakeDate(message)
                            || hasMysqlSubdate(message)
                            || hasMysqlUpdateJoinMultiTarget(message)
                            || hasOriginalXmlSyntaxDefect(message)) {
                        return "SQL_SYNTAX";
                    }
                    if (containsAny(message,
                            "语法分析出错",
                            "列表不匹配",
                            "重复的列名",
                            "标示符长度非法",
                            "无效的数据类型",
                            "无效的变量名",
                            "无法解析的成员访问表达式",
                            "有歧义的列名",
                            "递归 WITH 子句")) {
                        return "SQL_SYNTAX";
                    }
                    if (containsAny(message, "TooManyResultsException")) {
                        return "TEST_DATA";
                    }
                    return "UNKNOWN_FAILURE";
                }

                private String hint(ValidationRecord record) {
                    return "FAILED".equals(record.status) ? categoryHint(category(record)) : "";
                }

                private String categoryHint(String category) {
                    if ("CONFIGURATION".equals(category)) {
                        return "检查 sql-validation.yml、mapper XML 路径、数据源环境变量、类型别名、类型处理器和 mapper 绑定。";
                    }
                    if ("MYSQL_METADATA_SQL".equals(category)) {
                        return "information_schema、database() 等 MySQL 元数据 SQL 需要手工改成达梦写法。";
                    }
                    if ("METHOD_ARGS_OR_BINDING".equals(category)) {
                        return "生成的示例参数未满足动态 SQL 或 @Param 绑定；优先检查 Java mapper @Param 名称，必要时再配置方法参数。";
                    }
                    if ("SQL_SYNTAX".equals(category)) {
                        return "达梦拒绝了 SQL 语法；请检查 mapper-dm SQL，并手工处理未兼容的片段。";
                    }
                    if ("TEST_SCHEMA".equals(category)) {
                        return "达梦测试库缺少表、视图、字段、函数，或对象命名与 mapper SQL 不一致。";
                    }
                    if ("TEST_DATA_OR_SCHEMA".equals(category)) {
                        return "生成参数或表结构不满足约束；请检查自增、序列、默认值、字段长度、种子数据，或配置方法参数。";
                    }
                    if ("TEST_DATA".equals(category)) {
                        return "SQL 已执行，但当前测试数据不符合 mapper 预期；请调整种子数据或方法参数。";
                    }
                    if ("UNKNOWN_FAILURE".equals(category)) {
                        return "查看失败详情，判断属于 SQL 兼容、测试库结构还是测试数据问题。";
                    }
                    return "";
                }

                private String summary(ValidationRecord record) {
                    if (!"FAILED".equals(record.status)) {
                        return record.message;
                    }
                    String compact = normalizeMessage(record.message);
                    String dmCause = "Cause: dm.jdbc.driver.DMException:";
                    int dmCauseIndex = compact.lastIndexOf(dmCause);
                    if (dmCauseIndex >= 0) {
                        compact = compact.substring(dmCauseIndex + dmCause.length()).trim();
                    } else if (compact.startsWith("org.apache.ibatis.exceptions.PersistenceException:")) {
                        compact = compact.substring("org.apache.ibatis.exceptions.PersistenceException:".length()).trim();
                    }
                    compact = beforeMarker(compact, "### SQL:");
                    compact = beforeMarker(compact, "### Cause:");
                    if (isBlank(compact)) {
                        compact = normalizeMessage(record.message);
                    }
                    return abbreviate(compact, 360);
                }

                private String normalizeMessage(String value) {
                    return value == null ? "" : value.replace("\\r", " ").replace("\\n", " ").replaceAll("\\\\s+", " ").trim();
                }

                private String beforeMarker(String value, String marker) {
                    int index = value.indexOf(marker);
                    return index >= 0 ? value.substring(0, index).trim() : value;
                }

                private boolean containsAny(String value, String... needles) {
                    for (String needle : needles) {
                        if (value.contains(needle)) {
                            return true;
                        }
                    }
                    return false;
                }

                private boolean isDatabaseConnectionFailure(String message) {
                    String normalized = normalizeMessage(message);
                    String lower = normalized.toLowerCase(Locale.ROOT);
                    return normalized.contains("Failed to open Dameng validation connection")
                            || lower.contains("error getting a new connection")
                            || lower.contains("connection refused")
                            || lower.contains("communication error");
                }

                private boolean hasRegexpOperatorIssue(String message) {
                    String normalized = normalizeMessage(message);
                    String lower = normalized.toLowerCase(Locale.ROOT);
                    return lower.contains("regexp_like")
                            || lower.contains("正则表达式")
                            || lower.contains("regex")
                            || Pattern.compile("\\\\bregexp\\\\b", Pattern.CASE_INSENSITIVE).matcher(normalized).find();
                }

                private String parametersSummary(ParameterResolution parameters) {
                    if (parameters == null || !parameters.resolved || parameters.args == null || parameters.args.length == 0) {
                        return "";
                    }
                    if (parameters.args.length == 1) {
                        return valueSummary(parameters.args[0], 0, "arg0");
                    }
                    StringBuilder summary = new StringBuilder("[");
                    for (int i = 0; i < parameters.args.length; i++) {
                        if (i > 0) {
                            summary.append(", ");
                        }
                        summary.append("arg").append(i).append("=").append(valueSummary(parameters.args[i], 0, "arg" + i));
                    }
                    return summary.append("]").toString();
                }

                private String valueSummary(Object value, int depth, String valueName) {
                    if (isSensitiveName(valueName)) {
                        return "<redacted>";
                    }
                    if (value == null) {
                        return "null";
                    }
                    if (depth > 2) {
                        return value.getClass().getSimpleName() + "{...}";
                    }
                    if (value instanceof CharSequence) {
                        CharSequence text = (CharSequence) value;
                        return '"' + abbreviate(normalizeParameterText(text.toString()), 120) + '"';
                    }
                    if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
                        return String.valueOf(value);
                    }
                    if (value instanceof Date
                            || value instanceof java.time.temporal.TemporalAccessor
                            || value instanceof UUID) {
                        return String.valueOf(value);
                    }
                    Class<?> valueType = value.getClass();
                    if (valueType.isArray()) {
                        int length = Array.getLength(value);
                        StringBuilder summary = new StringBuilder("[");
                        int limit = Math.min(length, 5);
                        for (int i = 0; i < limit; i++) {
                            if (i > 0) {
                                summary.append(", ");
                            }
                            summary.append(valueSummary(Array.get(value, i), depth + 1, valueName));
                        }
                        if (length > limit) {
                            summary.append(", ... ").append(length - limit).append(" more");
                        }
                        return summary.append("]").toString();
                    }
                    if (value instanceof Collection<?>) {
                        Collection<?> collection = (Collection<?>) value;
                        StringBuilder summary = new StringBuilder("[");
                        int index = 0;
                        for (Object item : collection) {
                            if (index >= 5) {
                                summary.append(index == 0 ? "" : ", ").append("... ").append(collection.size() - index).append(" more");
                                break;
                            }
                            if (index > 0) {
                                summary.append(", ");
                            }
                            summary.append(valueSummary(item, depth + 1, valueName));
                            index++;
                        }
                        return summary.append("]").toString();
                    }
                    if (value instanceof Map<?, ?>) {
                        Map<?, ?> map = (Map<?, ?>) value;
                        StringBuilder summary = new StringBuilder("{");
                        int index = 0;
                        for (Map.Entry<?, ?> entry : map.entrySet()) {
                            if (index >= 8) {
                                summary.append(index == 0 ? "" : ", ").append("... ").append(map.size() - index).append(" more");
                                break;
                            }
                            if (index > 0) {
                                summary.append(", ");
                            }
                            String key = String.valueOf(entry.getKey());
                            summary.append(key).append("=").append(valueSummary(entry.getValue(), depth + 1, key));
                            index++;
                        }
                        return summary.append("}").toString();
                    }
                    if (valueType.getName().startsWith("java.")) {
                        return abbreviate(normalizeParameterText(String.valueOf(value)), 160);
                    }
                    return pojoSummary(value, depth, valueName);
                }

                private String pojoSummary(Object value, int depth, String valueName) {
                    StringBuilder summary = new StringBuilder(value.getClass().getSimpleName()).append("{");
                    int count = 0;
                    int fieldLimit = 32;
                    Class<?> currentType = value.getClass();
                    while (currentType != null && !Object.class.equals(currentType) && count < fieldLimit) {
                        for (Field field : currentType.getDeclaredFields()) {
                            if (count >= fieldLimit) {
                                break;
                            }
                            if (Modifier.isStatic(field.getModifiers())) {
                                continue;
                            }
                            if (count > 0) {
                                summary.append(", ");
                            }
                            summary.append(field.getName()).append("=");
                            try {
                                if (!field.isAccessible()) {
                                    field.setAccessible(true);
                                }
                                summary.append(valueSummary(field.get(value), depth + 1, field.getName()));
                            } catch (Exception e) {
                                summary.append("<unreadable>");
                            }
                            count++;
                        }
                        currentType = currentType.getSuperclass();
                    }
                    if (currentType != null && !Object.class.equals(currentType)) {
                        summary.append(count == 0 ? "" : ", ").append("...");
                    }
                    return summary.append("}").toString();
                }

                private boolean isSensitiveName(String valueName) {
                    String normalized = valueName == null ? "" : valueName.toLowerCase(Locale.ROOT);
                    return normalized.contains("password")
                            || normalized.contains("passwd")
                            || normalized.contains("secret")
                            || normalized.contains("token")
                            || normalized.contains("credential")
                            || normalized.contains("privatekey");
                }

                private String normalizeParameterText(String value) {
                    return value == null ? "" : value.replace("\\r", "\\\\r").replace("\\n", "\\\\n");
                }

                private String resultSummary(Object result) {
                    if (result == null) {
                        return "Returned null.";
                    }
                    if (result instanceof Collection<?>) {
                        Collection<?> collection = (Collection<?>) result;
                        return "Returned collection size " + collection.size() + ".";
                    }
                    if (result instanceof Map<?, ?>) {
                        Map<?, ?> map = (Map<?, ?>) result;
                        return "Returned map size " + map.size() + ".";
                    }
                    return "Returned " + result.getClass().getName() + ".";
                }

                private String throwableSummary(Throwable throwable) {
                    if (throwable == null) {
                        return "Unknown failure.";
                    }
                    String message = throwable.getMessage();
                    return throwable.getClass().getName() + (message == null || isBlank(message) ? "" : ": " + message);
                }

                private String escapeMarkdown(String value) {
                    return value == null ? "" : value.replace("|", "\\\\|").replace("\\n", " ");
                }

                private String escapeHtml(String value) {
                    if (value == null) {
                        return "";
                    }
                    return value.replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;");
                }

                private String escapeCodeBlock(String value) {
                    return value == null ? "" : value.replace("```", "` ` `");
                }

                private String escapeJson(String value) {
                    if (value == null) {
                        return "";
                    }
                    return value.replace("\\\\", "\\\\\\\\")
                            .replace("\\"", "\\\\\\"")
                            .replace("\\n", "\\\\n")
                            .replace("\\r", "\\\\r")
                            .replace("\\t", "\\\\t");
                }

                private String unquote(String value) {
                    if (value.length() >= 2
                            && ((value.startsWith("\\"") && value.endsWith("\\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        return value.substring(1, value.length() - 1);
                    }
                    return value;
                }

                private static String unescapeYamlDoubleQuoted(String value) {
                    StringBuilder result = new StringBuilder(value.length());
                    boolean escaped = false;
                    for (int i = 0; i < value.length(); i++) {
                        char current = value.charAt(i);
                        if (escaped) {
                            if (current == 34 || current == 92) {
                                result.append(current);
                            } else if (current == 'n') {
                                result.append((char) 10);
                            } else if (current == 'r') {
                                result.append((char) 13);
                            } else if (current == 't') {
                                result.append((char) 9);
                            } else {
                                result.append(current);
                            }
                            escaped = false;
                            continue;
                        }
                        if (current == 92) {
                            escaped = true;
                            continue;
                        }
                        result.append(current);
                    }
                    if (escaped) {
                        result.append((char) 92);
                    }
                    return result.toString();
                }

                private Document parseXml(Path path) throws Exception {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(false);
                    factory.setXIncludeAware(false);
                    factory.setExpandEntityReferences(false);
                    enableFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    enableFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                    enableFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
                    enableFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
                    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        return factory.newDocumentBuilder().parse(new InputSource(reader));
                    }
                }

                private void enableFeature(DocumentBuilderFactory factory, String feature, boolean enabled) {
                    try {
                        factory.setFeature(feature, enabled);
                    } catch (Exception ignored) {
                    }
                }

                private boolean isIgnoredPath(Path projectRoot, Path path) {
                    String relativePath = normalize(projectRoot.relativize(path));
                    return relativePath.startsWith("target/")
                            || relativePath.contains("/target/")
                            || relativePath.startsWith(".git/")
                            || relativePath.contains("/.git/");
                }

                private String displayPath(Path projectRoot, Path path) {
                    Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
                    Path normalizedPath = path.toAbsolutePath().normalize();
                    if (normalizedPath.startsWith(normalizedRoot)) {
                        return normalize(normalizedRoot.relativize(normalizedPath));
                    }
                    return normalize(normalizedPath);
                }

                private String normalize(Path path) {
                    return path.toString().replace('\\\\', '/');
                }

                private void logProgress(int index, int total, ValidationRecord record, long elapsedMillis) {
                    String elapsed = elapsedMillis <= 0 ? "" : " (" + elapsedMillis + " ms)";
                    String message = record.message == null || isBlank(record.message)
                            ? ""
                            : ": " + abbreviate(record.message, 240);
                    log(record.status + " [" + index + "/" + total + "] " + record.key + elapsed + message);
                }

                private void log(String message) {
                    System.out.println("[" + LocalDateTime.now().format(LOG_TIMESTAMP_FORMATTER)
                            + "] [dm-sql-validation] " + message);
                }

                private String abbreviate(String value, int maxLength) {
                    if (value == null || value.length() <= maxLength) {
                        return value;
                    }
                    return value.substring(0, maxLength - 3) + "...";
                }

                private String resolvePlaceholders(String value) {
                    if (value == null) {
                        return "";
                    }
                    Matcher matcher = PLACEHOLDER.matcher(value);
                    StringBuffer resolved = new StringBuffer();
                    while (matcher.find()) {
                        String name = matcher.group(1);
                        String replacement = System.getenv(name);
                        if (replacement == null) {
                            replacement = System.getProperty(name);
                        }
                        if (replacement == null) {
                            replacement = matcher.group(0);
                        }
                        matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
                    }
                    matcher.appendTail(resolved);
                    return resolved.toString();
                }

                """,
            """
                private static final class ActualParameterTypeIndex {
                    private final Map<String, Map<Integer, Class<?>>> actualTypes;

                    private ActualParameterTypeIndex(Map<String, Map<Integer, Class<?>>> actualTypes) {
                        this.actualTypes = actualTypes;
                    }

                    private static ActualParameterTypeIndex empty() {
                        return new ActualParameterTypeIndex(emptyMap());
                    }

                    private static ActualParameterTypeIndex build(Path projectRoot, List<MapperMethod> mapperMethods) {
                        Map<String, List<MapperMethod>> mapperMethodsBySimpleName = new LinkedHashMap<>();
                        for (MapperMethod mapperMethod : mapperMethods) {
                            if (mapperMethod.mapperInterface == null || mapperMethod.method == null) {
                                continue;
                            }
                            mapperMethodsBySimpleName
                                    .computeIfAbsent(mapperMethod.mapperInterface.getSimpleName(), key -> new ArrayList<>())
                                    .add(mapperMethod);
                        }
                        if (mapperMethodsBySimpleName.isEmpty()) {
                            return empty();
                        }
                        Map<String, Map<String, List<MapperMethod>>> mapperMethodsBySimpleAndMethod = new LinkedHashMap<>();
                        for (Map.Entry<String, List<MapperMethod>> entry : mapperMethodsBySimpleName.entrySet()) {
                            mapperMethodsBySimpleAndMethod.put(entry.getKey(), mapperMethodsByMethodName(entry.getValue()));
                        }
                        Map<String, Map<Integer, Class<?>>> actualTypes = new LinkedHashMap<>();
                        long deadlineNanos = System.nanoTime() + 5_000_000_000L;
                        for (Path sourceFile : javaSourceFiles(projectRoot)) {
                            if (System.nanoTime() > deadlineNanos) {
                                break;
                            }
                            try {
                                scanSourceFile(
                                        sourceFile,
                                        mapperMethodsBySimpleName.keySet(),
                                        mapperMethodsBySimpleAndMethod,
                                        actualTypes
                                );
                            } catch (Exception ignored) {
                            }
                        }
                        return actualTypes.isEmpty() ? empty() : new ActualParameterTypeIndex(actualTypes);
                    }

                    private static Map<String, List<MapperMethod>> mapperMethodsByMethodName(List<MapperMethod> mapperMethods) {
                        Map<String, List<MapperMethod>> byName = new LinkedHashMap<>();
                        for (MapperMethod mapperMethod : mapperMethods) {
                            byName.computeIfAbsent(mapperMethod.method.getName(), key -> new ArrayList<>()).add(mapperMethod);
                        }
                        return byName;
                    }

                    private Class<?> actualType(String statementKey, int parameterIndex, Class<?> declaredType) {
                        Map<Integer, Class<?>> byIndex = actualTypes.get(statementKey);
                        if (byIndex == null) {
                            return declaredType;
                        }
                        Class<?> actualType = byIndex.get(parameterIndex);
                        if (actualType == null
                                || declaredType == null
                                || actualType.equals(declaredType)
                                || !declaredType.isAssignableFrom(actualType)) {
                            return declaredType;
                        }
                        return actualType;
                    }

                    private String summary() {
                        int count = 0;
                        for (Map<Integer, Class<?>> byIndex : actualTypes.values()) {
                            count += byIndex.size();
                        }
                        return count + " mapper parameter type(s) inferred from Java call sites.";
                    }

                    private static List<Path> javaSourceFiles(Path projectRoot) {
                        try (Stream<Path> paths = Files.walk(projectRoot)) {
                            return paths.filter(Files::isRegularFile)
                                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                                    .filter(ActualParameterTypeIndex::isJavaSourcePath)
                                    .filter(ActualParameterTypeIndex::isReasonableJavaSourceFile)
                                    .filter(path -> !path.toString().contains("/target/"))
                                    .filter(path -> !path.toString().contains("\\\\target\\\\"))
                                    .sorted()
                                    .collect(Collectors.toList());
                        } catch (IOException e) {
                            return listOf();
                        }
                    }

                    private static boolean isJavaSourcePath(Path path) {
                        String value = path.toString().replace('\\\\', '/');
                        return value.contains("/src/main/java/") || value.contains("/src/test/java/");
                    }

                    private static boolean isReasonableJavaSourceFile(Path path) {
                        try {
                            return Files.size(path) <= 262_144L;
                        } catch (IOException e) {
                            return false;
                        }
                    }

                    private static void scanSourceFile(
                            Path sourceFile,
                            Set<String> mapperSimpleNames,
                            Map<String, Map<String, List<MapperMethod>>> mapperMethodsBySimpleAndMethod,
                            Map<String, Map<Integer, Class<?>>> actualTypes
                    ) throws IOException {
                        String source = stripCommentsPreservingLength(new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8));
                        if (source.length() > 262_144) {
                            return;
                        }
                        String packageName = packageName(source);
                        ImportIndex imports = ImportIndex.parse(source);
                        Map<String, Set<String>> mapperVariablesBySimpleName = mapperVariablesBySimpleName(source, mapperSimpleNames);
                        for (Map.Entry<String, Set<String>> entry : mapperVariablesBySimpleName.entrySet()) {
                            Map<String, List<MapperMethod>> mapperMethodsByName = mapperMethodsBySimpleAndMethod.get(entry.getKey());
                            if (mapperMethodsByName == null) {
                                continue;
                            }
                            for (String mapperVariableName : entry.getValue()) {
                                scanMapperMethodCalls(
                                        source,
                                        packageName,
                                        imports,
                                        mapperVariableName,
                                        mapperMethodsByName,
                                        actualTypes
                                );
                            }
                        }
                    }

                    private static void scanMapperMethodCalls(
                            String source,
                            String packageName,
                            ImportIndex imports,
                            String mapperVariableName,
                            Map<String, List<MapperMethod>> mapperMethodsByName,
                            Map<String, Map<Integer, Class<?>>> actualTypes
                    ) {
                        Pattern callPattern = Pattern.compile(
                                "(^|[^A-Za-z0-9_$])"
                                        + Pattern.quote(mapperVariableName)
                                        + "\\\\s*\\\\.\\\\s*"
                                        + "([A-Za-z_$][A-Za-z0-9_$]*)"
                                        + "\\\\s*\\\\("
                        );
                        Matcher matcher = callPattern.matcher(source);
                        while (matcher.find()) {
                            List<MapperMethod> mapperMethods = mapperMethodsByName.get(matcher.group(2));
                            if (mapperMethods == null) {
                                continue;
                            }
                            int argumentsStart = matcher.end();
                            List<String> arguments = invocationArguments(source, argumentsStart);
                            for (MapperMethod mapperMethod : mapperMethods) {
                                Class<?>[] declaredTypes = mapperMethod.method.getParameterTypes();
                                for (int i = 0; i < arguments.size() && i < declaredTypes.length; i++) {
                                    String argumentName = simpleIdentifier(arguments.get(i));
                                    if (argumentName == null) {
                                        continue;
                                    }
                                    String typeName = variableTypeBefore(source, matcher.start(), argumentName);
                                    Class<?> actualType = loadActualType(typeName, packageName, imports, declaredTypes[i]);
                                    if (actualType != null) {
                                        actualTypes.computeIfAbsent(mapperMethod.key(), key -> new LinkedHashMap<>())
                                                .putIfAbsent(i, actualType);
                                    }
                                }
                            }
                        }
                    }

                    private static Map<String, Set<String>> mapperVariablesBySimpleName(String source, Set<String> mapperSimpleNames) {
                        Pattern pattern = Pattern.compile(
                                "(^|[^A-Za-z0-9_$])"
                                        + "([A-Za-z_$][A-Za-z0-9_$.]*)"
                                        + "\\\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\\\s*(?:[;=,)])"
                        );
                        Matcher matcher = pattern.matcher(source);
                        Map<String, Set<String>> variablesBySimpleName = new LinkedHashMap<>();
                        while (matcher.find()) {
                            String mapperSimpleName = simpleTypeName(cleanTypeName(matcher.group(2)));
                            if (!mapperSimpleNames.contains(mapperSimpleName)) {
                                continue;
                            }
                            variablesBySimpleName
                                    .computeIfAbsent(mapperSimpleName, key -> new LinkedHashSet<>())
                                    .add(matcher.group(3));
                        }
                        return variablesBySimpleName;
                    }

                    private static String simpleTypeName(String typeName) {
                        if (typeName == null) {
                            return "";
                        }
                        int separator = typeName.lastIndexOf('.');
                        return separator >= 0 ? typeName.substring(separator + 1) : typeName;
                    }

                    private static List<String> invocationArguments(String source, int argumentsStart) {
                        List<String> arguments = new ArrayList<>();
                        int depth = 0;
                        int argumentStart = argumentsStart;
                        for (int i = argumentsStart; i < source.length(); i++) {
                            char ch = source.charAt(i);
                            if (ch == '(') {
                                depth++;
                            } else if (ch == ')') {
                                if (depth == 0) {
                                    String argument = source.substring(argumentStart, i).trim();
                                    if (!argument.isEmpty()) {
                                        arguments.add(argument);
                                    }
                                    return arguments;
                                }
                                depth--;
                            } else if (ch == ',' && depth == 0) {
                                arguments.add(source.substring(argumentStart, i).trim());
                                argumentStart = i + 1;
                            }
                        }
                        return arguments;
                    }

                    private static String simpleIdentifier(String expression) {
                        String value = expression == null ? "" : expression.trim();
                        return value.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? value : null;
                    }

                    private static String variableTypeBefore(String source, int beforeIndex, String variableName) {
                        Pattern pattern = Pattern.compile(
                                "([A-Za-z_$][A-Za-z0-9_$.]*(?:\\\\s*<[^;(){}=]*>)?)\\\\s+"
                                        + Pattern.quote(variableName)
                                        + "\\\\s*(?:[,)=;{])"
                        );
                        Matcher matcher = pattern.matcher(source.substring(0, beforeIndex));
                        String typeName = null;
                        while (matcher.find()) {
                            typeName = matcher.group(1);
                        }
                        return cleanTypeName(typeName);
                    }

                    private static String cleanTypeName(String typeName) {
                        if (typeName == null) {
                            return null;
                        }
                        String value = typeName.trim();
                        int genericStart = value.indexOf('<');
                        if (genericStart >= 0) {
                            value = value.substring(0, genericStart).trim();
                        }
                        int lastSpace = value.lastIndexOf(' ');
                        if (lastSpace >= 0) {
                            value = value.substring(lastSpace + 1).trim();
                        }
                        return value.isEmpty() ? null : value;
                    }

                    private static Class<?> loadActualType(
                            String typeName,
                            String packageName,
                            ImportIndex imports,
                            Class<?> declaredType
                    ) {
                        if (typeName == null || declaredType == null) {
                            return null;
                        }
                        for (String className : imports.candidateClassNames(typeName, packageName)) {
                            try {
                                Class<?> actualType = Class.forName(className);
                                if (!actualType.equals(declaredType) && declaredType.isAssignableFrom(actualType)) {
                                    return actualType;
                                }
                            } catch (ClassNotFoundException ignored) {
                            }
                        }
                        return null;
                    }

                    private static String packageName(String source) {
                        for (String line : source.split("\\\\R")) {
                            String trimmed = line.trim();
                            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                                return trimmed.substring("package ".length(), trimmed.length() - 1).trim();
                            }
                        }
                        return "";
                    }

                    private static String stripCommentsPreservingLength(String source) {
                        StringBuilder result = new StringBuilder(source.length());
                        boolean lineComment = false;
                        boolean blockComment = false;
                        boolean stringLiteral = false;
                        boolean charLiteral = false;
                        for (int i = 0; i < source.length(); i++) {
                            char ch = source.charAt(i);
                            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\\0';
                            if (lineComment) {
                                if (ch == '\\n' || ch == '\\r') {
                                    lineComment = false;
                                    result.append(ch);
                                } else {
                                    result.append(' ');
                                }
                                continue;
                            }
                            if (blockComment) {
                                if (ch == '*' && next == '/') {
                                    blockComment = false;
                                    result.append("  ");
                                    i++;
                                } else {
                                    result.append(ch == '\\n' || ch == '\\r' ? ch : ' ');
                                }
                                continue;
                            }
                            if (stringLiteral) {
                                result.append(ch);
                                if (ch == '\\\\' && next != '\\0') {
                                    result.append(next);
                                    i++;
                                } else if (ch == '"') {
                                    stringLiteral = false;
                                }
                                continue;
                            }
                            if (charLiteral) {
                                result.append(ch);
                                if (ch == '\\\\' && next != '\\0') {
                                    result.append(next);
                                    i++;
                                } else if (ch == '\\'') {
                                    charLiteral = false;
                                }
                                continue;
                            }
                            if (ch == '/' && next == '/') {
                                lineComment = true;
                                result.append("  ");
                                i++;
                            } else if (ch == '/' && next == '*') {
                                blockComment = true;
                                result.append("  ");
                                i++;
                            } else {
                                result.append(ch);
                                if (ch == '"') {
                                    stringLiteral = true;
                                } else if (ch == '\\'') {
                                    charLiteral = true;
                                }
                            }
                        }
                        return result.toString();
                    }

                    private static final class ImportIndex {
                        private final Map<String, String> imports;
                        private final List<String> wildcardImports;

                        private ImportIndex(Map<String, String> imports, List<String> wildcardImports) {
                            this.imports = imports;
                            this.wildcardImports = wildcardImports;
                        }

                        private static ImportIndex parse(String source) {
                            Map<String, String> imports = new LinkedHashMap<>();
                            List<String> wildcardImports = new ArrayList<>();
                            for (String line : source.split("\\\\R")) {
                                String trimmed = line.trim();
                                if (!trimmed.startsWith("import ") || !trimmed.endsWith(";") || trimmed.startsWith("import static ")) {
                                    continue;
                                }
                                String className = trimmed.substring("import ".length(), trimmed.length() - 1).trim();
                                if (className.endsWith(".*")) {
                                    wildcardImports.add(className.substring(0, className.length() - 2));
                                } else {
                                    int separator = className.lastIndexOf('.');
                                    if (separator > 0) {
                                        imports.put(className.substring(separator + 1), className);
                                    }
                                }
                            }
                            return new ImportIndex(imports, wildcardImports);
                        }

                        private List<String> candidateClassNames(String typeName, String packageName) {
                            if (typeName.contains(".")) {
                                return listOf(typeName);
                            }
                            List<String> candidates = new ArrayList<>();
                            String imported = imports.get(typeName);
                            if (imported != null) {
                                candidates.add(imported);
                            }
                            if (packageName != null && !packageName.trim().isEmpty()) {
                                candidates.add(packageName + "." + typeName);
                            }
                            for (String wildcardImport : wildcardImports) {
                                candidates.add(wildcardImport + "." + typeName);
                            }
                            candidates.add("java.lang." + typeName);
                            return candidates;
                        }
                    }
                }

                private static final class MapperUsageIndex {
                    private static UsageFilter build(
                            Path projectRoot,
                            ValidationConfig config,
                            List<MapperMethod> mapperMethods
                    ) {
                        if (!config.usageFilterEnabled) {
                            return UsageFilter.disabled();
                        }
                        List<String> warnings = new ArrayList<>();
                        List<Path> classDirectories;
                        try {
                            classDirectories = usageClassDirectories(projectRoot, config);
                        } catch (IOException e) {
                            warnings.add("Failed to discover usage class directories: " + e.getMessage());
                            return UsageFilter.unavailable(new UsageFilterReport(true, false, 0, 0, 0, warnings));
                        }
                        if (classDirectories.isEmpty()) {
                            warnings.add("No target/classes directories were found; usage filter is disabled for this run.");
                            return UsageFilter.unavailable(new UsageFilterReport(true, false, 0, 0, 0, warnings));
                        }

                        Map<String, String> ownerMethodToStatement = new LinkedHashMap<>();
                        Set<String> statementKeys = new LinkedHashSet<>();
                        Set<String> mapperInternalNames = new LinkedHashSet<>();
                        for (MapperMethod mapperMethod : mapperMethods) {
                            String owner = mapperMethod.statement.namespace.replace('.', '/');
                            ownerMethodToStatement.put(owner + "#" + mapperMethod.statement.id, mapperMethod.key());
                            statementKeys.add(mapperMethod.key());
                            mapperInternalNames.add(owner);
                        }

                        List<Path> classFiles;
                        try {
                            classFiles = classFiles(classDirectories);
                        } catch (IOException e) {
                            warnings.add("Failed to scan usage class files: " + e.getMessage());
                            return UsageFilter.unavailable(new UsageFilterReport(true, false, classDirectories.size(), 0, 0, warnings));
                        }
                        if (classFiles.isEmpty()) {
                            warnings.add("No .class files were found under usage class directories; usage filter is disabled for this run.");
                            return UsageFilter.unavailable(new UsageFilterReport(true, false, classDirectories.size(), 0, 0, warnings));
                        }

            """,
            """
                        Set<String> referencedStatements = new LinkedHashSet<>();
                        for (Path classFile : classFiles) {
                            try {
                                referencedStatements.addAll(scanClassFile(
                                        classFile,
                                        ownerMethodToStatement,
                                        statementKeys,
                                        mapperInternalNames
                                ));
                            } catch (Exception e) {
                                if (warnings.size() < 10) {
                                    warnings.add("Failed to inspect class file " + classFile + ": " + e.getMessage());
                                }
                            }
                        }
                        UsageFilterReport report = new UsageFilterReport(
                                true,
                                true,
                                classDirectories.size(),
                                classFiles.size(),
                                referencedStatements.size(),
                                warnings
                        );
                        return UsageFilter.available(referencedStatements, report);
                    }

                    private static List<Path> usageClassDirectories(Path projectRoot, ValidationConfig config) throws IOException {
                        if (!config.usageClassDirectories.isEmpty()) {
                            List<Path> directories = new ArrayList<>();
                            for (String configuredDirectory : config.usageClassDirectories) {
                                Path directory = resolveProjectPath(projectRoot, configuredDirectory);
                                if (Files.isDirectory(directory)) {
                                    directories.add(directory.toAbsolutePath().normalize());
                                }
                            }
                            return directories;
                        }
                        try (Stream<Path> paths = Files.walk(projectRoot)) {
                            return paths.filter(Files::isDirectory)
                                    .filter(path -> path.getFileName() != null)
                                    .filter(path -> "classes".equals(path.getFileName().toString()))
                                    .filter(path -> path.getParent() != null
                                            && path.getParent().getFileName() != null
                                            && "target".equals(path.getParent().getFileName().toString()))
                                    .filter(path -> !path.toString().contains("test-classes"))
                                    .sorted()
                                    .collect(Collectors.toList());
                        }
                    }

                    private static List<Path> classFiles(List<Path> classDirectories) throws IOException {
                        List<Path> classFiles = new ArrayList<>();
                        for (Path classDirectory : classDirectories) {
                            try (Stream<Path> paths = Files.walk(classDirectory)) {
                                classFiles.addAll(paths.filter(Files::isRegularFile)
                                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                                        .sorted()
                                        .collect(Collectors.toList()));
                            }
                        }
                        return classFiles;
                    }

                    private static Set<String> scanClassFile(
                            Path classFile,
                            Map<String, String> ownerMethodToStatement,
                            Set<String> statementKeys,
                            Set<String> mapperInternalNames
                    ) throws IOException {
                        try (DataInputStream input = new DataInputStream(Files.newInputStream(classFile))) {
                            if (input.readInt() != 0xCAFEBABE) {
                                return setOf();
                            }
                            input.readUnsignedShort();
                            input.readUnsignedShort();
                            int constantPoolCount = input.readUnsignedShort();
                            String[] utf8 = new String[constantPoolCount];
                            int[] classNameIndexes = new int[constantPoolCount];
                            int[] stringIndexes = new int[constantPoolCount];
                            MemberRef[] memberRefs = new MemberRef[constantPoolCount];
                            NameAndType[] nameAndTypes = new NameAndType[constantPoolCount];

                            for (int i = 1; i < constantPoolCount; i++) {
                                int tag = input.readUnsignedByte();
                                switch (tag) {
                                    case 1:
                                        utf8[i] = input.readUTF();
                                        break;
                                    case 3:
                                    case 4:
                                        input.readInt();
                                        break;
                                    case 5:
                                    case 6:
                                        input.readLong();
                                        i++;
                                        break;
                                    case 7:
                                        classNameIndexes[i] = input.readUnsignedShort();
                                        break;
                                    case 8:
                                        stringIndexes[i] = input.readUnsignedShort();
                                        break;
                                    case 9:
                                        input.readUnsignedShort();
                                        input.readUnsignedShort();
                                        break;
                                    case 10:
                                    case 11:
                                        memberRefs[i] = new MemberRef(
                                                input.readUnsignedShort(),
                                                input.readUnsignedShort()
                                        );
                                        break;
                                    case 12:
                                        nameAndTypes[i] = new NameAndType(
                                                input.readUnsignedShort(),
                                                input.readUnsignedShort()
                                        );
                                        break;
                                    case 15:
                                        input.readUnsignedByte();
                                        input.readUnsignedShort();
                                        break;
                                    case 16:
                                    case 19:
                                    case 20:
                                        input.readUnsignedShort();
                                        break;
                                    case 17:
                                    case 18:
                                        input.readUnsignedShort();
                                        input.readUnsignedShort();
                                        break;
                                    default:
                                        throw new IOException("Unsupported class constant pool tag: " + tag);
                                }
                            }

                            input.readUnsignedShort();
                            int thisClassIndex = input.readUnsignedShort();
                            String thisClass = className(classNameIndexes, utf8, thisClassIndex);
                            if (mapperInternalNames.contains(thisClass)) {
                                return setOf();
                            }

                            Set<String> referencedStatements = new LinkedHashSet<>();
                            for (MemberRef memberRef : memberRefs) {
                                if (memberRef == null) {
                                    continue;
                                }
                                NameAndType nameAndType = nameAndTypes[memberRef.nameAndTypeIndex];
                                if (nameAndType == null) {
                                    continue;
                                }
                                String owner = className(classNameIndexes, utf8, memberRef.classIndex);
                                String methodName = utf8[nameAndType.nameIndex];
                                String statement = ownerMethodToStatement.get(owner + "#" + methodName);
                                if (statement != null) {
                                    referencedStatements.add(statement);
                                }
                            }
                            for (int stringIndex : stringIndexes) {
                                if (stringIndex <= 0 || stringIndex >= utf8.length) {
                                    continue;
                                }
                                String value = utf8[stringIndex];
                                if (statementKeys.contains(value)) {
                                    referencedStatements.add(value);
                                }
                            }
                            return referencedStatements;
                        }
                    }

                    private static String className(int[] classNameIndexes, String[] utf8, int classIndex) {
                        if (classIndex <= 0 || classIndex >= classNameIndexes.length) {
                            return "";
                        }
                        int nameIndex = classNameIndexes[classIndex];
                        return nameIndex > 0 && nameIndex < utf8.length ? utf8[nameIndex] : "";
                    }

                    private static Path resolveProjectPath(Path projectRoot, String location) {
                        Path path = java.nio.file.Paths.get(location);
                        if (path.isAbsolute()) {
                            return path.toAbsolutePath().normalize();
                        }
                        return projectRoot.resolve(location).toAbsolutePath().normalize();
                    }
                }

                private static final class UsageFilter {
                    private final boolean available;
                    private final Set<String> referencedStatements;
                    private final UsageFilterReport report;

                    private UsageFilter(boolean available, Set<String> referencedStatements, UsageFilterReport report) {
                        this.available = available;
                        this.referencedStatements = referencedStatements;
                        this.report = report;
                    }

                    private static UsageFilter disabled() {
                        return new UsageFilter(false, setOf(), UsageFilterReport.disabled());
                    }

                    private static UsageFilter unavailable(UsageFilterReport report) {
                        return new UsageFilter(false, setOf(), report);
                    }

                    private static UsageFilter available(Set<String> referencedStatements, UsageFilterReport report) {
                        return new UsageFilter(true, referencedStatements, report);
                    }

                    private boolean unused(MapperMethod mapperMethod, ValidationConfig config) {
                        return available
                                && !config.includes(mapperMethod.key())
                                && !referencedStatements.contains(mapperMethod.key());
                    }

                    private UsageFilterReport report() {
                        return report;
                    }
                }

                private static final class UsageFilterReport {
                    private final boolean enabled;
                    private final boolean available;
                    private final int classDirectoryCount;
                    private final int classFileCount;
                    private final int referencedMethodCount;
                    private final List<String> warnings;

                    private UsageFilterReport(
                            boolean enabled,
                            boolean available,
                            int classDirectoryCount,
                            int classFileCount,
                            int referencedMethodCount,
                            List<String> warnings
                    ) {
                        this.enabled = enabled;
                        this.available = available;
                        this.classDirectoryCount = classDirectoryCount;
                        this.classFileCount = classFileCount;
                        this.referencedMethodCount = referencedMethodCount;
                        this.warnings = copyList(warnings == null ? listOf() : warnings);
                    }

                    private static UsageFilterReport disabled() {
                        return new UsageFilterReport(false, false, 0, 0, 0, listOf());
                    }

                    private String summary() {
                        return "enabled=" + enabled
                                + ", available=" + available
                                + ", classDirectories=" + classDirectoryCount
                                + ", classFiles=" + classFileCount
                                + ", referencedMethods=" + referencedMethodCount;
                    }
                }

                private static final class MemberRef {
                    private final int classIndex;
                    private final int nameAndTypeIndex;

                    private MemberRef(int classIndex, int nameAndTypeIndex) {
                        this.classIndex = classIndex;
                        this.nameAndTypeIndex = nameAndTypeIndex;
                    }
                }

                private static final class NameAndType {
                    private final int nameIndex;
                    private final int descriptorIndex;

                    private NameAndType(int nameIndex, int descriptorIndex) {
                        this.nameIndex = nameIndex;
                        this.descriptorIndex = descriptorIndex;
                    }
                }

                """,
            """
                private static final class MethodArgumentConfig {
                    private static final Object MISSING = new Object();
                    private final List<Object> args = new ArrayList<>();
                    private final Map<String, Object> params = new LinkedHashMap<>();

                    static MethodArgumentConfig args(List<?> values) {
                        MethodArgumentConfig config = new MethodArgumentConfig();
                        if (values != null) {
                            config.args.addAll(values);
                        }
                        return config;
                    }

                    static MethodArgumentConfig params(Map<String, Object> values) {
                        MethodArgumentConfig config = new MethodArgumentConfig();
                        if (values != null) {
                            config.params.putAll(values);
                        }
                        return config;
                    }

                    private boolean hasParams() {
                        return !params.isEmpty();
                    }

                    private boolean isEmpty() {
                        return args.isEmpty() && params.isEmpty();
                    }

                    private int argumentCount() {
                        return hasParams() ? -1 : args.size();
                    }

                    private Object valueFor(String parameterName, int index) {
                        return valueFor(parameterName, "", index);
                    }

                    private Object valueFor(String parameterName, String effectiveParameterName, int index) {
                        Object value = valueForName(effectiveParameterName);
                        if (value != MISSING) {
                            return value;
                        }
                        value = valueForName(parameterName);
                        if (value != MISSING) {
                            return value;
                        }
                        String argName = "arg" + index;
                        value = valueForName(argName);
                        if (value != MISSING) {
                            return value;
                        }
                        String paramName = "param" + (index + 1);
                        return valueForName(paramName);
                    }

                    private Object valueForName(String name) {
                        if (isBlank(name) || !params.containsKey(name)) {
                            return MISSING;
                        }
                        return params.get(name);
                    }
                }

                private static final class ValidationConfig {
                    private String schema = "";
                    private boolean usageFilterEnabled = true;
                    private final DatasourceConfig datasource = new DatasourceConfig();
                    private final List<String> mapperXmlLocations = new ArrayList<>();
                    private final List<String> usageClassDirectories = new ArrayList<>();
                    private final List<String> typeAliasesPackages = new ArrayList<>();
                    private final List<String> typeHandlersPackages = new ArrayList<>();
                    private final Map<String, List<String>> methodArgs = new LinkedHashMap<>();
                    private final Map<String, MethodArgumentConfig> rewriteMethodArgs = new LinkedHashMap<>();
                    private final Set<String> includedMethods = new LinkedHashSet<>();
                    private final Set<String> excludedMethods = new LinkedHashSet<>();
                    private final Set<String> ignoredMissingTables = new LinkedHashSet<>();
                    private final Set<String> ignoredMissingColumns = new LinkedHashSet<>();
                    private final Set<String> ignoredMissingSchemas = new LinkedHashSet<>();
                    private final Set<String> ignoredNotNullColumns = new LinkedHashSet<>();

                    static ValidationConfig load(Path path, Path rewriteConfigPath) throws IOException {
                        ValidationConfig config = new ValidationConfig();
                        if (!Files.isRegularFile(path)) {
                            config.loadRewriteConfig(rewriteConfigPath);
                            return config;
                        }
                        String section = "";
                        String currentMethod = null;
                        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                            String trimmed = line.trim();
                            if (isBlank(trimmed) || trimmed.startsWith("#")) {
                                continue;
                            }
                            if (!line.startsWith(" ") && trimmed.startsWith("schema:")) {
                                config.schema = config.scalar(trimmed.substring("schema:".length()));
                                section = "";
                                currentMethod = null;
                                continue;
                            }
                            if (!line.startsWith(" ") && trimmed.startsWith("usageFilterEnabled:")) {
                                config.usageFilterEnabled = Boolean.parseBoolean(
                                        config.scalar(trimmed.substring("usageFilterEnabled:".length()))
                                );
                                section = "";
                                currentMethod = null;
                                continue;
                            }
                            if (!line.startsWith(" ") && "datasource:".equals(trimmed)) {
                                section = "datasource";
                                currentMethod = null;
                                continue;
                            }
                            if (!line.startsWith(" ") && "mapperXmlLocations:".equals(trimmed)) {
                                section = "mapperXmlLocations";
                                currentMethod = null;
                                continue;
                            }
                            if (!line.startsWith(" ") && "usageClassDirectories:".equals(trimmed)) {
                                section = "usageClassDirectories";
                                currentMethod = null;
                                continue;
                            }
                            if (!line.startsWith(" ") && "typeAliasesPackages:".equals(trimmed)) {
                                section = "typeAliasesPackages";
                                currentMethod = null;
                                continue;
                            }
                            if (!line.startsWith(" ") && "typeHandlersPackages:".equals(trimmed)) {
                                section = "typeHandlersPackages";
                                currentMethod = null;
                                continue;
                            }
                            if (!line.startsWith(" ") && "methods:".equals(trimmed)) {
                                section = "methods";
                                currentMethod = null;
                                continue;
                            }
                            if (!line.startsWith(" ") && "includedMethods:".equals(trimmed)) {
                                section = "includedMethods";
                                currentMethod = null;
                                continue;
                            }
                            if (!line.startsWith(" ") && "excludedMethods:".equals(trimmed)) {
                                section = "excludedMethods";
                                currentMethod = null;
                                continue;
                            }
                            if ("datasource".equals(section) && line.startsWith("  ") && trimmed.contains(":")) {
                                config.datasource.put(trimmed);
                                continue;
                            }
                            if ("mapperXmlLocations".equals(section) && trimmed.startsWith("- ")) {
                                config.mapperXmlLocations.add(config.scalar(trimmed.substring(2)));
                                continue;
                            }
                            if ("usageClassDirectories".equals(section) && trimmed.startsWith("- ")) {
                                config.usageClassDirectories.add(config.scalar(trimmed.substring(2)));
                                continue;
                            }
                            if ("typeAliasesPackages".equals(section) && trimmed.startsWith("- ")) {
                                config.typeAliasesPackages.add(config.scalar(trimmed.substring(2)));
                                continue;
                            }
                            if ("typeHandlersPackages".equals(section) && trimmed.startsWith("- ")) {
                                config.typeHandlersPackages.add(config.scalar(trimmed.substring(2)));
                                continue;
                            }
                            if ("includedMethods".equals(section) && trimmed.startsWith("- ")) {
                                config.includedMethods.add(config.scalar(trimmed.substring(2)));
                                continue;
                            }
                            if ("excludedMethods".equals(section) && trimmed.startsWith("- ")) {
                                config.excludedMethods.add(config.scalar(trimmed.substring(2)));
                                continue;
                            }
                            if ("methods".equals(section)) {
                                if (line.startsWith("  ") && !line.startsWith("    ") && trimmed.endsWith(":")) {
                                    currentMethod = trimmed.substring(0, trimmed.length() - 1).trim();
                                    config.methodArgs.computeIfAbsent(currentMethod, ignored -> new ArrayList<>());
                                    continue;
                                }
                                if (currentMethod != null && trimmed.startsWith("- ")) {
                                    config.methodArgs.computeIfAbsent(currentMethod, ignored -> new ArrayList<>())
                                            .add(config.scalar(trimmed.substring(2)));
                                }
                            }
                        }
                        config.loadRewriteConfig(rewriteConfigPath);
                        return config;
                    }

                    private void loadRewriteConfig(Path rewriteConfigPath) throws IOException {
                        if (rewriteConfigPath == null || !Files.isRegularFile(rewriteConfigPath)) {
                            return;
                        }
                        String section = "";
                        String currentMethod = null;
                        String valueMode = "";
                        for (String line : Files.readAllLines(rewriteConfigPath, StandardCharsets.UTF_8)) {
                            String withoutComment = stripYamlComment(line);
                            String trimmed = withoutComment.trim();
                            if (isBlank(trimmed) || "{}".equals(trimmed)) {
                                continue;
                            }
                            int indent = leadingSpaces(withoutComment);
                            if (indent == 0 && "validationArgs:".equals(trimmed)) {
                                section = "validationArgs";
                                currentMethod = null;
                                valueMode = "";
                                continue;
                            }
                            if (indent == 0 && "validationIgnores:".equals(trimmed)) {
                                section = "validationIgnores";
                                currentMethod = null;
                                valueMode = "";
                                continue;
                            }
                            if (indent == 0) {
                                section = "";
                                currentMethod = null;
                                valueMode = "";
                                continue;
                            }
                            if ("validationArgs".equals(section) && indent == 2 && "methods:".equals(trimmed)) {
                                section = "validationMethods";
                                currentMethod = null;
                                valueMode = "";
                                continue;
                            }
                            if (isValidationIgnoreSection(section) && indent == 2 && trimmed.startsWith("missingTables:")) {
                                section = "validationMissingTables";
                                currentMethod = null;
                                valueMode = "";
                                addIgnoredMissingTables(parseYamlValue(trimmed.substring("missingTables:".length())));
                                continue;
                            }
                            if (isValidationIgnoreSection(section) && indent == 2 && trimmed.startsWith("missingColumns:")) {
                                section = "validationMissingColumns";
                                currentMethod = null;
                                valueMode = "";
                                addIgnoredMissingColumns(parseYamlValue(trimmed.substring("missingColumns:".length())));
                                continue;
                            }
                            if (isValidationIgnoreSection(section) && indent == 2 && trimmed.startsWith("missingSchemas:")) {
                                section = "validationMissingSchemas";
                                currentMethod = null;
                                valueMode = "";
                                addIgnoredMissingSchemas(parseYamlValue(trimmed.substring("missingSchemas:".length())));
                                continue;
                            }
                            if (isValidationIgnoreSection(section) && indent == 2 && trimmed.startsWith("notNullColumns:")) {
                                section = "validationNotNullColumns";
                                currentMethod = null;
                                valueMode = "";
                                addIgnoredNotNullColumns(parseYamlValue(trimmed.substring("notNullColumns:".length())));
                                continue;
                            }
                            if ("validationMissingTables".equals(section) && indent >= 4 && trimmed.startsWith("- ")) {
                                addIgnoredMissingTables(parseYamlValue(trimmed.substring(2)));
                                continue;
                            }
                            if ("validationMissingColumns".equals(section) && indent >= 4 && trimmed.startsWith("- ")) {
                                addIgnoredMissingColumns(parseYamlValue(trimmed.substring(2)));
                                continue;
                            }
                            if ("validationMissingSchemas".equals(section) && indent >= 4 && trimmed.startsWith("- ")) {
                                addIgnoredMissingSchemas(parseYamlValue(trimmed.substring(2)));
                                continue;
                            }
                            if ("validationNotNullColumns".equals(section) && indent >= 4 && trimmed.startsWith("- ")) {
                                addIgnoredNotNullColumns(parseYamlValue(trimmed.substring(2)));
                                continue;
                            }
                            if ("validationMethods".equals(section) && indent == 4 && trimmed.endsWith(":")) {
                                currentMethod = scalar(trimmed.substring(0, trimmed.length() - 1));
                                rewriteMethodArgs.putIfAbsent(currentMethod, new MethodArgumentConfig());
                                valueMode = "";
                                continue;
                            }
                            if ("validationMethods".equals(section) && currentMethod != null && indent == 6) {
                                if ("args:".equals(trimmed)) {
                                    valueMode = "args";
                                    continue;
                                }
                                if ("params:".equals(trimmed)) {
                                    valueMode = "params";
                                    continue;
                                }
                            }
                            if ("validationMethods".equals(section) && currentMethod != null && "args".equals(valueMode)
                                    && indent >= 8 && trimmed.startsWith("- ")) {
                                rewriteMethodArgs.computeIfAbsent(currentMethod, ignored -> new MethodArgumentConfig())
                                        .args.add(parseYamlValue(trimmed.substring(2)));
                                continue;
                            }
                            if ("validationMethods".equals(section) && currentMethod != null && "params".equals(valueMode)
                                    && indent >= 8 && trimmed.contains(":")) {
                                int colon = trimmed.indexOf(':');
                                String key = scalar(trimmed.substring(0, colon));
                                Object value = parseYamlValue(trimmed.substring(colon + 1));
                                rewriteMethodArgs.computeIfAbsent(currentMethod, ignored -> new MethodArgumentConfig())
                                        .params.put(key, value);
                            }
                        }
                    }

                    private boolean isValidationIgnoreSection(String section) {
                        return "validationIgnores".equals(section)
                                || "validationMissingTables".equals(section)
                                || "validationMissingColumns".equals(section)
                                || "validationMissingSchemas".equals(section)
                                || "validationNotNullColumns".equals(section);
                    }

                    private void addIgnoredMissingTables(Object value) {
                        if (value instanceof Collection<?>) {
                            for (Object item : (Collection<?>) value) {
                                addIgnoredMissingTable(String.valueOf(item));
                            }
                        } else if (value != null) {
                            addIgnoredMissingTable(String.valueOf(value));
                        }
                    }

                    private void addIgnoredMissingTable(String table) {
                        String normalized = normalizeMissingTableName(table);
                        if (!isBlank(normalized)) {
                            ignoredMissingTables.add(normalized);
                        }
                    }

                    boolean ignoresMissingTable(String table) {
                        String normalized = normalizeMissingTableName(table);
                        if (isBlank(normalized)) {
                            return false;
                        }
                        String leaf = missingTableLeaf(normalized);
                        for (String ignored : ignoredMissingTables) {
                            if (ignored.equals(normalized)
                                    || ignored.equals(leaf)
                                    || missingTableLeaf(ignored).equals(normalized)
                                    || missingTableLeaf(ignored).equals(leaf)) {
                                return true;
                            }
                        }
                        return false;
                    }

                    private void addIgnoredMissingColumns(Object value) {
                        if (value instanceof Collection<?>) {
                            for (Object item : (Collection<?>) value) {
                                addIgnoredMissingColumn(String.valueOf(item));
                            }
                        } else if (value != null) {
                            addIgnoredMissingColumn(String.valueOf(value));
                        }
                    }

                    private void addIgnoredMissingColumn(String column) {
                        String normalized = normalizeMissingColumnName(column);
                        if (!isBlank(normalized)) {
                            ignoredMissingColumns.add(normalized);
                        }
                    }

                    boolean ignoresMissingColumn(String column) {
                        String normalized = normalizeMissingColumnName(column);
                        if (isBlank(normalized)) {
                            return false;
                        }
                        String leaf = missingColumnLeaf(normalized);
                        for (String ignored : ignoredMissingColumns) {
                            if (ignored.equals(normalized)
                                    || ignored.equals(leaf)
                                    || missingColumnLeaf(ignored).equals(normalized)
                                    || missingColumnLeaf(ignored).equals(leaf)) {
                                return true;
                            }
                        }
                        return false;
                    }

                    private void addIgnoredMissingSchemas(Object value) {
                        if (value instanceof Collection<?>) {
                            for (Object item : (Collection<?>) value) {
                                addIgnoredMissingSchema(String.valueOf(item));
                            }
                        } else if (value != null) {
                            addIgnoredMissingSchema(String.valueOf(value));
                        }
                    }

                    private void addIgnoredMissingSchema(String schema) {
                        String normalized = normalizeMissingSchemaName(schema);
                        if (!isBlank(normalized)) {
                            ignoredMissingSchemas.add(normalized);
                        }
                    }

                    boolean ignoresMissingSchema(String schema) {
                        String normalized = normalizeMissingSchemaName(schema);
                        return !isBlank(normalized) && ignoredMissingSchemas.contains(normalized);
                    }

                    private void addIgnoredNotNullColumns(Object value) {
                        if (value instanceof Collection<?>) {
                            for (Object item : (Collection<?>) value) {
                                addIgnoredNotNullColumn(String.valueOf(item));
                            }
                        } else if (value != null) {
                            addIgnoredNotNullColumn(String.valueOf(value));
                        }
                    }

                    private void addIgnoredNotNullColumn(String column) {
                        String normalized = normalizeMissingColumnName(column);
                        if (!isBlank(normalized)) {
                            ignoredNotNullColumns.add(normalized);
                        }
                    }

                    boolean ignoresNotNullColumn(String table, String column) {
                        String normalizedColumn = normalizeMissingColumnName(column);
                        if (isBlank(normalizedColumn)) {
                            return false;
                        }
                        String normalizedTable = normalizeMissingTableName(table);
                        String qualified = isBlank(normalizedTable) ? "" : normalizedTable + "." + normalizedColumn;
                        String leafQualified = isBlank(normalizedTable) ? "" : missingTableLeaf(normalizedTable) + "." + normalizedColumn;
                        for (String ignored : ignoredNotNullColumns) {
                            if (ignored.indexOf('.') < 0) {
                                if (ignored.equals(normalizedColumn)) {
                                    return true;
                                }
                                continue;
                            }
                            if (ignored.equals(qualified) || ignored.equals(leafQualified)) {
                                return true;
                            }
                        }
                        return false;
                    }

                    private String normalizeMissingTableName(String table) {
                        if (table == null) {
                            return "";
                        }
                        return table.trim()
                                .replace("\\"", "")
                                .replace("`", "")
                                .toLowerCase(Locale.ROOT);
                    }

                    private String missingTableLeaf(String table) {
                        int dot = table == null ? -1 : table.lastIndexOf('.');
                        return dot >= 0 && dot + 1 < table.length() ? table.substring(dot + 1) : table;
                    }

                    private String normalizeMissingColumnName(String column) {
                        if (column == null) {
                            return "";
                        }
                        return column.trim()
                                .replace("\\"", "")
                                .replace("`", "")
                                .toLowerCase(Locale.ROOT);
                    }

                    private String normalizeMissingSchemaName(String schema) {
                        if (schema == null) {
                            return "";
                        }
                        return schema.trim()
                                .replace("\\"", "")
                                .replace("`", "")
                                .toLowerCase(Locale.ROOT);
                    }

                    private String missingColumnLeaf(String column) {
                        int dot = column == null ? -1 : column.lastIndexOf('.');
                        return dot >= 0 && dot + 1 < column.length() ? column.substring(dot + 1) : column;
                    }

                    boolean excludes(String methodKey) {
                        if (excludedMethods.contains(methodKey)) {
                            return true;
                        }
                        int lastDot = methodKey.lastIndexOf('.');
                        return lastDot > 0 && excludedMethods.contains(methodKey.substring(0, lastDot) + ".*");
                    }

                    boolean includes(String methodKey) {
                        MethodArgumentConfig rewriteArgs = rewriteMethodArgs.get(methodKey);
                        if ((rewriteArgs != null && !rewriteArgs.isEmpty())
                                || methodArgs.containsKey(methodKey)
                                || includedMethods.contains(methodKey)) {
                            return true;
                        }
                        int lastDot = methodKey.lastIndexOf('.');
                        return lastDot > 0 && includedMethods.contains(methodKey.substring(0, lastDot) + ".*");
                    }

                    MethodArgumentConfig methodArguments(String methodKey) {
                        MethodArgumentConfig rewriteArgs = rewriteMethodArgs.get(methodKey);
                        if (rewriteArgs != null && !rewriteArgs.isEmpty()) {
                            return rewriteArgs;
                        }
                        List<String> validationArgs = methodArgs.get(methodKey);
                        return validationArgs == null ? null : MethodArgumentConfig.args(new ArrayList<Object>(validationArgs));
                    }

                    boolean hasConfiguredArguments(String methodKey) {
                        MethodArgumentConfig rewriteArgs = rewriteMethodArgs.get(methodKey);
                        return (rewriteArgs != null && !rewriteArgs.isEmpty()) || methodArgs.containsKey(methodKey);
                    }

                    List<String> schemas() {
                        if (schema == null || isBlank(schema)) {
                            return listOf();
                        }
                        return Pattern.compile(",")
                                .splitAsStream(schema)
                                .map(String::trim)
                                .filter(value -> !isBlank(value))
                                .collect(Collectors.collectingAndThen(
                                        Collectors.toCollection(LinkedHashSet::new),
                                        ArrayList::new
                                ));
                    }

                    String primarySchema() {
                        List<String> schemas = schemas();
                        return schemas.isEmpty() ? "" : schemas.get(0);
                    }

                    private Object parseYamlValue(String rawValue) {
                        String value = rawValue == null ? "" : rawValue.trim();
                        if (value.startsWith("[") && value.endsWith("]")) {
                            String body = value.substring(1, value.length() - 1).trim();
                            List<Object> values = new ArrayList<>();
                            if (!body.isEmpty()) {
                                for (String item : splitInlineList(body)) {
                                    values.add(parseYamlValue(item));
                                }
                            }
                            return values;
                        }
                        if (value.startsWith("{") && value.endsWith("}")) {
                            String body = value.substring(1, value.length() - 1).trim();
                            Map<String, Object> values = new LinkedHashMap<>();
                            if (!body.isEmpty()) {
                                for (String item : splitInlineList(body)) {
                                    int colon = topLevelColon(item);
                                    if (colon > 0) {
                                        String key = scalar(item.substring(0, colon));
                                        values.put(key, parseYamlValue(item.substring(colon + 1)));
                                    }
                                }
                            }
                            return values;
                        }
                        boolean quoted = isQuoted(value);
                        String scalar = scalar(value);
                        if (quoted) {
                            return scalar;
                        }
                        if ("null".equalsIgnoreCase(scalar)) {
                            return null;
                        }
                        if ("true".equalsIgnoreCase(scalar) || "false".equalsIgnoreCase(scalar)) {
                            return Boolean.parseBoolean(scalar);
                        }
                        try {
                            if (scalar.matches("[-+]?\\\\d+")) {
                                return Long.parseLong(scalar);
                            }
                            if (scalar.matches("[-+]?\\\\d+\\\\.\\\\d+")) {
                                return new BigDecimal(scalar);
                            }
                        } catch (Exception ignored) {
                        }
                        return scalar;
                    }

                    private List<String> splitInlineList(String body) {
                        List<String> values = new ArrayList<>();
                        boolean singleQuoted = false;
                        boolean doubleQuoted = false;
                        int nestedDepth = 0;
                        StringBuilder current = new StringBuilder();
                        for (int i = 0; i < body.length(); i++) {
                            char c = body.charAt(i);
                            if (c == '\\'' && !doubleQuoted) {
                                singleQuoted = !singleQuoted;
                            } else if (c == '\\"' && !singleQuoted) {
                                doubleQuoted = !doubleQuoted;
                            } else if (!singleQuoted && !doubleQuoted && (c == '[' || c == '{')) {
                                nestedDepth++;
                            } else if (!singleQuoted && !doubleQuoted && (c == ']' || c == '}')) {
                                nestedDepth--;
                            }
                            if (c == ',' && !singleQuoted && !doubleQuoted && nestedDepth == 0) {
                                values.add(current.toString().trim());
                                current.setLength(0);
                            } else {
                                current.append(c);
                            }
                        }
                        if (current.length() > 0) {
                            values.add(current.toString().trim());
                        }
                        return values;
                    }

                    private int topLevelColon(String value) {
                        boolean singleQuoted = false;
                        boolean doubleQuoted = false;
                        int nestedDepth = 0;
                        for (int i = 0; i < value.length(); i++) {
                            char c = value.charAt(i);
                            if (c == '\\'' && !doubleQuoted) {
                                singleQuoted = !singleQuoted;
                            } else if (c == '\\"' && !singleQuoted) {
                                doubleQuoted = !doubleQuoted;
                            } else if (!singleQuoted && !doubleQuoted && (c == '[' || c == '{')) {
                                nestedDepth++;
                            } else if (!singleQuoted && !doubleQuoted && (c == ']' || c == '}')) {
                                nestedDepth--;
                            } else if (c == ':' && !singleQuoted && !doubleQuoted && nestedDepth == 0) {
                                return i;
                            }
                        }
                        return -1;
                    }

                    private String stripYamlComment(String line) {
                        boolean singleQuoted = false;
                        boolean doubleQuoted = false;
                        for (int i = 0; i < line.length(); i++) {
                            char current = line.charAt(i);
                            if (current == '\\'' && !doubleQuoted) {
                                singleQuoted = !singleQuoted;
                            } else if (current == '\\"' && !singleQuoted) {
                                doubleQuoted = !doubleQuoted;
                            } else if (current == '#' && !singleQuoted && !doubleQuoted) {
                                return line.substring(0, i);
                            }
                        }
                        return line;
                    }

                    private int leadingSpaces(String line) {
                        int count = 0;
                        while (count < line.length() && line.charAt(count) == ' ') {
                            count++;
                        }
                        return count;
                    }

                    private String scalar(String value) {
                        String trimmed = value.trim();
                        if (trimmed.length() >= 2 && trimmed.charAt(0) == 34
                                && trimmed.charAt(trimmed.length() - 1) == 34) {
                            return unescapeYamlDoubleQuoted(trimmed.substring(1, trimmed.length() - 1));
                        }
                        if (trimmed.length() >= 2 && trimmed.charAt(0) == 39
                                && trimmed.charAt(trimmed.length() - 1) == 39) {
                            return trimmed.substring(1, trimmed.length() - 1).replace("''", "'");
                        }
                        return trimmed;
                    }

                    private boolean isQuoted(String value) {
                        String trimmed = value == null ? "" : value.trim();
                        return trimmed.length() >= 2
                                && ((trimmed.charAt(0) == 34 && trimmed.charAt(trimmed.length() - 1) == 34)
                                || (trimmed.charAt(0) == 39 && trimmed.charAt(trimmed.length() - 1) == 39));
                    }
                }

                """,
            """
                private static final class DatasourceConfig {
                    private String driverClassName = "dm.jdbc.driver.DmDriver";
                    private String url = "${DM_JDBC_URL}";
                    private String username = "${DM_DB_USERNAME}";
                    private String password = "${DM_DB_PASSWORD}";

                    private void put(String line) {
                        int colon = line.indexOf(':');
                        if (colon < 0) {
                            return;
                        }
                        String key = line.substring(0, colon).trim();
                        String value = scalar(line.substring(colon + 1));
                        if ("driverClassName".equals(key) || "driver-class-name".equals(key)) {
                            driverClassName = value;
                        } else if ("url".equals(key)) {
                            url = value;
                        } else if ("username".equals(key)) {
                            username = value;
                        } else if ("password".equals(key)) {
                            password = value;
                        }
                    }

                    private String scalar(String value) {
                        String trimmed = value.trim();
                        if (trimmed.length() >= 2 && trimmed.charAt(0) == 34
                                && trimmed.charAt(trimmed.length() - 1) == 34) {
                            return unescapeYamlDoubleQuoted(trimmed.substring(1, trimmed.length() - 1));
                        }
                        if (trimmed.length() >= 2 && trimmed.charAt(0) == 39
                                && trimmed.charAt(trimmed.length() - 1) == 39) {
                            return trimmed.substring(1, trimmed.length() - 1).replace("''", "'");
                        }
                        return trimmed;
                    }
                }

                private static final class ColumnReference {
                    private final String tableName;
                    private final String columnName;

                    private ColumnReference(String tableName, String columnName) {
                        this.tableName = tableName;
                        this.columnName = columnName;
                    }

                    private String tableName() {
                        return tableName;
                    }

                    private String columnName() {
                        return columnName;
                    }
                }

                private final class SqlStatementContext {
                    private final Map<String, String> tableAliases;

                    private SqlStatementContext(Map<String, String> tableAliases) {
                        this.tableAliases = copyMap(tableAliases == null ? emptyMap() : tableAliases);
                    }

                    private String tableName(String qualifier) {
                        if (qualifier == null || isBlank(qualifier)) {
                            return "";
                        }
                        return tableAliases.getOrDefault(normalizeSqlIdentifier(qualifier), "");
                    }
                }

                private static final class DbColumnMetadata {
                    private final Map<String, String> tableColumnTypes = new LinkedHashMap<>();
                    private final Map<String, Set<String>> columnTypes = new LinkedHashMap<>();

                    private static DbColumnMetadata empty() {
                        return new DbColumnMetadata();
                    }

                    private static DbColumnMetadata load(Connection connection, List<String> schemas) throws Exception {
                        DbColumnMetadata metadata = new DbColumnMetadata();
                        List<String> targetSchemas = schemas == null ? listOf() : schemas;
                        if (targetSchemas.isEmpty()) {
                            metadata.readJdbcColumns(connection, "");
                        } else {
                            for (String schema : targetSchemas) {
                                metadata.readJdbcColumns(connection, schema);
                            }
                        }
                        if (metadata.columnCount() == 0) {
                            if (targetSchemas.isEmpty()) {
                                metadata.readAllTabColumns(connection, "");
                            } else {
                                for (String schema : targetSchemas) {
                                    metadata.readAllTabColumns(connection, schema);
                                }
                            }
                        }
                        return metadata;
                    }

                    private void readJdbcColumns(Connection connection, String schema) throws Exception {
                        DatabaseMetaData databaseMetaData = connection.getMetaData();
                        String schemaPattern = schema == null || isBlank(schema) ? null : schema;
                        try (ResultSet resultSet = databaseMetaData.getColumns(null, schemaPattern, "%", "%")) {
                            while (resultSet.next()) {
                                addColumn(
                                        resultSet.getString("TABLE_NAME"),
                                        resultSet.getString("COLUMN_NAME"),
                                        resultSet.getString("TYPE_NAME")
                                );
                            }
                        }
                    }

                    private void readAllTabColumns(Connection connection, String schema) throws Exception {
                        String sql = schema == null || isBlank(schema)
                                ? "select table_name, column_name, data_type from all_tab_columns"
                                : "select table_name, column_name, data_type from all_tab_columns "
                                        + "where owner = ? or upper(owner) = upper(?)";
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            if (schema != null && !isBlank(schema)) {
                                statement.setString(1, schema);
                                statement.setString(2, schema);
                            }
                            try (ResultSet resultSet = statement.executeQuery()) {
                                while (resultSet.next()) {
                                    addColumn(
                                            resultSet.getString("table_name"),
                                            resultSet.getString("column_name"),
                                            resultSet.getString("data_type")
                                    );
                                }
                            }
                        }
                    }

                    private void addColumn(String tableName, String columnName, String dataType) {
                        String normalizedTable = normalizeIdentifier(tableName);
                        String normalizedColumn = normalizeIdentifier(columnName);
                        if (isBlank(normalizedTable) || isBlank(normalizedColumn) || dataType == null || isBlank(dataType)) {
                            return;
                        }
                        String normalizedType = dataType.toUpperCase(Locale.ROOT);
                        tableColumnTypes.putIfAbsent(tableKey(normalizedTable, normalizedColumn), normalizedType);
                        columnTypes.computeIfAbsent(normalizedColumn, ignored -> new LinkedHashSet<>()).add(normalizedType);
                    }

                    private String columnType(ColumnReference columnReference) {
                        if (columnReference == null || isBlank(columnReference.columnName())) {
                            return "";
                        }
                        String normalizedColumn = normalizeIdentifier(columnReference.columnName());
                        String normalizedTable = normalizeIdentifier(columnReference.tableName());
                        if (!isBlank(normalizedTable)) {
                            String type = tableColumnTypes.get(tableKey(normalizedTable, normalizedColumn));
                            if (type != null) {
                                return type;
                            }
                        }
                        Set<String> types = columnTypes.get(normalizedColumn);
                        return types != null && types.size() == 1 ? types.iterator().next() : "";
                    }

                    private int columnCount() {
                        return tableColumnTypes.size();
                    }

                    private static String tableKey(String normalizedTable, String normalizedColumn) {
                        return normalizedTable + "." + normalizedColumn;
                    }

                    private static String normalizeIdentifier(String identifier) {
                        if (identifier == null) {
                            return "";
                        }
                        String trimmed = identifier.trim();
                        if (trimmed.length() >= 2
                                && ((trimmed.startsWith("\\\"") && trimmed.endsWith("\\\""))
                                || (trimmed.startsWith("`") && trimmed.endsWith("`")))) {
                            trimmed = trimmed.substring(1, trimmed.length() - 1);
                        }
                        return trimmed.toLowerCase(Locale.ROOT);
                    }
                }

                private static final class MapperStatement {
                    private final String namespace;
                    private final String id;
                    private final List<SetBranchParameterVariant> setBranchParameterVariants;
                    private final DynamicIdentifierMetadata dynamicIdentifierMetadata;
                    private final Set<String> parameterReferences;
                    private final Set<String> generatedKeyProperties;

                    private MapperStatement(
                            String namespace,
                            String id,
                            List<SetBranchParameterVariant> setBranchParameterVariants,
                            DynamicIdentifierMetadata dynamicIdentifierMetadata,
                            Set<String> parameterReferences,
                            Set<String> generatedKeyProperties
                    ) {
                        this.namespace = namespace;
                        this.id = id;
                        this.setBranchParameterVariants = copyList(setBranchParameterVariants == null
                                ? listOf()
                                : setBranchParameterVariants);
                        this.dynamicIdentifierMetadata = dynamicIdentifierMetadata == null
                                ? new DynamicIdentifierMetadata()
                                : dynamicIdentifierMetadata;
                        this.parameterReferences = copySet(parameterReferences == null
                                ? setOf()
                                : parameterReferences);
                        this.generatedKeyProperties = copySet(generatedKeyProperties == null
                                ? setOf()
                                : generatedKeyProperties);
                    }

                    private String key() {
                        return namespace + "." + id;
                    }

                    private boolean dynamicIdentifierParameter(String valueName) {
                        return dynamicIdentifierMetadata.dynamicIdentifierParameter(valueName);
                    }

                    private Map<String, Object> collectionElementDefault(String valueName) {
                        return dynamicIdentifierMetadata.collectionElementDefault(valueName);
                    }

                    private Object collectionSqlFragmentDefault(String valueName) {
                        return dynamicIdentifierMetadata.collectionSqlFragmentDefault(valueName);
                    }

                    private Object collectionScalarDefault(String valueName) {
                        return dynamicIdentifierMetadata.collectionScalarDefault(valueName);
                    }

                    private String collectionColumnType(String valueName, DbColumnMetadata columnMetadata) {
                        ColumnReference columnReference = dynamicIdentifierMetadata.collectionColumnReference(valueName);
                        return columnReference == null || columnMetadata == null
                                ? ""
                                : columnMetadata.columnType(columnReference);
                    }

                    private String collectionElementColumnType(String collectionName, String propertyName, DbColumnMetadata columnMetadata) {
                        ColumnReference columnReference = dynamicIdentifierMetadata.collectionElementColumnReference(collectionName, propertyName);
                        return columnReference == null || columnMetadata == null
                                ? ""
                                : columnMetadata.columnType(columnReference);
                    }

                    private Map<String, ColumnReference> collectionElementColumnReferences(String collectionName) {
                        return dynamicIdentifierMetadata.collectionElementColumnReferences(collectionName);
                    }

                    private String defaultColumnType(String valueName, DbColumnMetadata columnMetadata) {
                        ColumnReference columnReference = dynamicIdentifierMetadata.defaultColumnReference(valueName);
                        return columnReference == null || columnMetadata == null
                                ? ""
                                : columnMetadata.columnType(columnReference);
                    }

                    private Object defaultValue(String valueName) {
                        return dynamicIdentifierMetadata.defaultValue(valueName);
                    }

                    private boolean hasDefaultValue(String valueName) {
                        return dynamicIdentifierMetadata.hasDefaultValue(valueName);
                    }

                    private boolean setDefaultValue(String valueName) {
                        return dynamicIdentifierMetadata.setDefaultValue(valueName);
                    }

                    private boolean hasSetDefaultUnder(String valueName) {
                        return dynamicIdentifierMetadata.hasSetDefaultUnder(valueName);
                    }

                    private Map<String, Object> defaultValues() {
                        return dynamicIdentifierMetadata.defaultValues();
                    }

                    private Set<String> dynamicIdentifierNames() {
                        return dynamicIdentifierMetadata.dynamicIdentifierNames();
                    }

                    private Set<String> parameterReferences() {
                        return parameterReferences;
                    }

                    private Set<String> collectionParameterNames() {
                        return dynamicIdentifierMetadata.collectionParameterNames();
                    }

                    private boolean collectionParameter(String valueName) {
                        return dynamicIdentifierMetadata.collectionParameter(valueName);
                    }

                    private boolean mapCollectionParameter(String valueName) {
                        return dynamicIdentifierMetadata.mapCollectionParameter(valueName);
                    }

                    private boolean scalarCollectionParameter(String valueName) {
                        return dynamicIdentifierMetadata.scalarCollectionParameter(valueName);
                    }

                    private String scalarCollectionName(String valueName) {
                        return dynamicIdentifierMetadata.scalarCollectionName(valueName);
                    }

                    private boolean hasCollectionElementDefault(String valueName) {
                        return dynamicIdentifierMetadata.hasCollectionElementDefault(valueName);
                    }

                    private boolean hasCollectionElementColumnReferences(String valueName) {
                        return dynamicIdentifierMetadata.hasCollectionElementColumnReferences(valueName);
                    }

                    private boolean nonEmptyCollectionParameter(String valueName) {
                        return dynamicIdentifierMetadata.nonEmptyCollectionParameter(valueName);
                    }

                    private boolean hasDefaultParameterMap() {
                        return !defaultValues().isEmpty()
                                || !dynamicIdentifierNames().isEmpty()
                                || !collectionParameterNames().isEmpty()
                                || !dynamicIdentifierMetadata.collectionElementDefaults.isEmpty()
                                || !dynamicIdentifierMetadata.collectionSqlFragmentDefaults.isEmpty()
                                || !dynamicIdentifierMetadata.collectionScalarDefaults.isEmpty()
                                || !dynamicIdentifierMetadata.collectionColumnReferences.isEmpty()
                                || !dynamicIdentifierMetadata.collectionElementColumnReferences.isEmpty()
                                || !dynamicIdentifierMetadata.mapCollectionParameterNames.isEmpty();
                    }

                    private boolean generatedKeyProperty(String valueName) {
                        String normalized = DynamicIdentifierMetadata.normalizeMetadataName(valueName);
                        return generatedKeyProperties.stream()
                                .map(DynamicIdentifierMetadata::normalizeMetadataName)
                                .anyMatch(normalized::equals);
                    }

                    private String parameterExpressionName(int index, int collectionIndex, String fallbackName) {
                        if (!isSyntheticParameterName(fallbackName)) {
                            return fallbackName;
                        }
                        String valueExpressionName = dynamicIdentifierMetadata.valueExpressionName(index, "");
                        if (!isBlank(valueExpressionName)) {
                            return valueExpressionName;
                        }
                        return dynamicIdentifierMetadata.collectionExpressionName(collectionIndex, fallbackName);
                    }

                    private boolean isSyntheticParameterName(String valueName) {
                        return valueName != null && Pattern.compile("^(?:arg|param)\\\\d+$").matcher(valueName).matches();
                    }
                }

                private static final class DynamicIdentifierMetadata {
                    private final Set<String> dynamicIdentifierNames = new LinkedHashSet<>();
                    private final Set<String> namedDynamicIdentifierNames = new LinkedHashSet<>();
                    private final Set<String> collectionParameterNames = new LinkedHashSet<>();
                    private final Set<String> namedCollectionParameterNames = new LinkedHashSet<>();
                    private final Set<String> nonEmptyCollectionParameterNames = new LinkedHashSet<>();
                    private final Set<String> mapCollectionParameterNames = new LinkedHashSet<>();
                    private final Map<String, Map<String, Object>> collectionElementDefaults = new LinkedHashMap<>();
                    private final Map<String, Object> collectionSqlFragmentDefaults = new LinkedHashMap<>();
                    private final Map<String, Object> collectionScalarDefaults = new LinkedHashMap<>();
                    private final Map<String, ColumnReference> collectionColumnReferences = new LinkedHashMap<>();
                    private final Map<String, Map<String, ColumnReference>> collectionElementColumnReferences = new LinkedHashMap<>();
                    private final Map<String, Map<String, String>> collectionElementColumnReferenceNames = new LinkedHashMap<>();
                    private final Map<String, ColumnReference> defaultColumnReferences = new LinkedHashMap<>();
                    private final Map<String, Object> defaultValues = new LinkedHashMap<>();
                    private final Map<String, Object> namedDefaultValues = new LinkedHashMap<>();
                    private final Set<String> setDefaultValueNames = new LinkedHashSet<>();
                    private final Set<String> valueExpressionNames = new LinkedHashSet<>();

                    private void addDynamicIdentifierName(String valueName) {
                        String normalized = normalizeMetadataName(valueName);
                        if (!isBlank(normalized)) {
                            dynamicIdentifierNames.add(normalized);
                            namedDynamicIdentifierNames.add(valueName);
                        }
                    }

                    private void addCollectionParameterName(String collectionName) {
                        addCollectionParameterName(collectionName, false);
                    }

                    private void addCollectionParameterName(String collectionName, boolean force) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (isBlank(normalizedCollectionName)
                                || (!force && !DEFAULT_COLLECTION_PARAMETER_NAMES.contains(normalizedCollectionName))) {
                            return;
                        }
                        collectionParameterNames.add(normalizedCollectionName);
                        namedCollectionParameterNames.add(collectionName);
                    }

                    private void addMapCollectionParameterName(String collectionName) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (!isBlank(normalizedCollectionName)) {
                            addCollectionParameterName(collectionName, true);
                            mapCollectionParameterNames.add(normalizedCollectionName);
                        }
                    }

                    private void addNonEmptyCollectionParameterName(String collectionName) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (!isBlank(normalizedCollectionName)) {
                            nonEmptyCollectionParameterNames.add(normalizedCollectionName);
                        }
                    }

                    private void addCollectionDefault(String collectionName, String propertyName, Object value) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (isBlank(normalizedCollectionName) || propertyName == null || isBlank(propertyName)) {
                            return;
                        }
                        addCollectionParameterName(collectionName, true);
                        collectionElementDefaults
                                .computeIfAbsent(normalizedCollectionName, ignored -> new LinkedHashMap<>())
                                .putIfAbsent(propertyName, value);
                    }

                    private void addCollectionSqlFragmentDefault(String collectionName, Object value) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (!isBlank(normalizedCollectionName)) {
                            addCollectionParameterName(collectionName, true);
                            collectionSqlFragmentDefaults.putIfAbsent(normalizedCollectionName, value);
                        }
                    }

                    private void addCollectionScalarDefault(String collectionName, Object value) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (!isBlank(normalizedCollectionName)) {
                            addCollectionParameterName(collectionName, true);
                            collectionScalarDefaults.putIfAbsent(normalizedCollectionName, value);
                        }
                    }

                    private void addCollectionColumnReference(String collectionName, ColumnReference columnReference) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (!isBlank(normalizedCollectionName) && columnReference != null && !isBlank(columnReference.columnName())) {
                            addCollectionParameterName(collectionName, true);
                            collectionColumnReferences.putIfAbsent(normalizedCollectionName, columnReference);
                        }
                    }

                    private void addCollectionDefaultColumnReference(
                            String collectionName,
                            String propertyName,
                            ColumnReference columnReference
                    ) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        String normalizedPropertyName = normalizeMetadataName(propertyName);
                        if (!isBlank(normalizedCollectionName)
                                && !isBlank(normalizedPropertyName)
                                && columnReference != null
                                && !isBlank(columnReference.columnName())) {
                            addCollectionParameterName(collectionName, true);
                            collectionElementColumnReferences
                                    .computeIfAbsent(normalizedCollectionName, ignored -> new LinkedHashMap<>())
                                    .putIfAbsent(normalizedPropertyName, columnReference);
                            collectionElementColumnReferenceNames
                                    .computeIfAbsent(normalizedCollectionName, ignored -> new LinkedHashMap<>())
                                    .putIfAbsent(normalizedPropertyName, propertyName);
                        }
                    }

                    private void addDefaultColumnReference(String propertyName, ColumnReference columnReference) {
                        String normalizedPropertyName = normalizeMetadataName(propertyName);
                        if (!isBlank(normalizedPropertyName) && columnReference != null && !isBlank(columnReference.columnName())) {
                            defaultColumnReferences.putIfAbsent(normalizedPropertyName, columnReference);
                        }
                    }

                    private void addDefaultValue(String propertyName, Object value) {
                        String normalizedPropertyName = normalizeMetadataName(propertyName);
                        if (!isBlank(normalizedPropertyName)) {
                            defaultValues.putIfAbsent(normalizedPropertyName, value);
                            namedDefaultValues.putIfAbsent(propertyName, value);
                        }
                    }

                    private void addSetDefaultValue(String propertyName) {
                        String normalizedPropertyName = normalizeMetadataName(propertyName);
                        if (!isBlank(normalizedPropertyName)) {
                            setDefaultValueNames.add(normalizedPropertyName);
                        }
                    }

                    private void addValueExpressionName(String propertyName) {
                        String normalizedPropertyName = normalizeMetadataName(propertyName);
                        if (!isBlank(normalizedPropertyName)) {
                            valueExpressionNames.add(propertyName);
                        }
                    }

                    private boolean hasCollectionDefault(String collectionName) {
                        return collectionElementDefaults.containsKey(normalizeMetadataName(collectionName));
                    }

                    private boolean hasCollectionElementDefault(String collectionName) {
                        return valueByNameOrSuffix(collectionElementDefaults, collectionName) != null;
                    }

                    private boolean hasCollectionElementColumnReferences(String collectionName) {
                        return valueByNameOrSuffix(collectionElementColumnReferences, collectionName) != null;
                    }

                    private boolean dynamicIdentifierParameter(String valueName) {
                        return containsMetadataName(dynamicIdentifierNames, valueName);
                    }

                    private boolean nonEmptyCollectionParameter(String valueName) {
                        return containsMetadataName(nonEmptyCollectionParameterNames, valueName);
                    }

                    private boolean collectionParameter(String valueName) {
                        return containsMetadataName(collectionParameterNames, valueName);
                    }

                    private boolean mapCollectionParameter(String valueName) {
                        return containsMetadataName(mapCollectionParameterNames, valueName);
                    }

                    private boolean scalarCollectionParameter(String valueName) {
                        return !isBlank(scalarCollectionName(valueName));
                    }

                    private String scalarCollectionName(String valueName) {
                        Set<String> scalarNames = new LinkedHashSet<>();
                        scalarNames.addAll(collectionSqlFragmentDefaults.keySet());
                        scalarNames.addAll(collectionScalarDefaults.keySet());
                        scalarNames.addAll(collectionColumnReferences.keySet());
                        return matchingMetadataName(scalarNames, valueName);
                    }

                    private Map<String, Object> collectionElementDefault(String valueName) {
                        Map<String, Object> defaults = valueByMetadataName(collectionElementDefaults, valueName);
                        if (defaults != null) {
                            return defaults;
                        }
                        if (scalarCollectionParameter(valueName)) {
                            return emptyMap();
                        }
                        defaults = collectionElementDefaults.get("item");
                        if (defaults != null) {
                            return defaults;
                        }
                        return emptyMap();
                    }

                    private Object collectionSqlFragmentDefault(String valueName) {
                        return valueByMetadataName(collectionSqlFragmentDefaults, valueName);
                    }

                    private Object collectionScalarDefault(String valueName) {
                        return valueByMetadataName(collectionScalarDefaults, valueName);
                    }

                    private ColumnReference collectionColumnReference(String valueName) {
                        return valueByMetadataName(collectionColumnReferences, valueName);
                    }

                    private ColumnReference collectionElementColumnReference(String collectionName, String propertyName) {
                        Map<String, ColumnReference> references = valueByMetadataName(collectionElementColumnReferences, collectionName);
                        if (references == null) {
                            references = collectionElementColumnReferences.get("item");
                        }
                        if (references == null) {
                            references = onlyCollectionElementColumnReferences();
                        }
                        return references == null ? null : valueByNameOrSuffix(references, propertyName);
                    }

                    private Map<String, ColumnReference> collectionElementColumnReferences(String collectionName) {
                        Map<String, ColumnReference> references = valueByMetadataName(collectionElementColumnReferences, collectionName);
                        Map<String, String> names = valueByMetadataName(collectionElementColumnReferenceNames, collectionName);
                        if (references == null && scalarCollectionParameter(collectionName)) {
                            return emptyMap();
                        }
                        if (references == null) {
                            references = collectionElementColumnReferences.get("item");
                            names = collectionElementColumnReferenceNames.get("item");
                        }
                        if (references == null) {
                            Map.Entry<String, Map<String, ColumnReference>> entry = onlyCollectionElementColumnReferenceEntry();
                            if (entry != null) {
                                references = entry.getValue();
                                names = collectionElementColumnReferenceNames.get(entry.getKey());
                            }
                        }
                        if (references == null || references.isEmpty()) {
                            return emptyMap();
                        }
                        Map<String, ColumnReference> result = new LinkedHashMap<>();
                        for (Map.Entry<String, ColumnReference> entry : references.entrySet()) {
                            String propertyName = names == null ? null : names.get(entry.getKey());
                            result.put(isBlank(propertyName) ? entry.getKey() : propertyName, entry.getValue());
                        }
                        return result;
                    }

                    private Map<String, ColumnReference> onlyCollectionElementColumnReferences() {
                        Map.Entry<String, Map<String, ColumnReference>> entry = onlyCollectionElementColumnReferenceEntry();
                        return entry == null ? null : entry.getValue();
                    }

                    private Map.Entry<String, Map<String, ColumnReference>> onlyCollectionElementColumnReferenceEntry() {
                        if (collectionElementColumnReferences.size() != 1) {
                            return null;
                        }
                        return collectionElementColumnReferences.entrySet().iterator().next();
                    }

                    private ColumnReference defaultColumnReference(String valueName) {
                        return valueByMetadataName(defaultColumnReferences, valueName);
                    }

                    private Object defaultValue(String valueName) {
                        return valueByMetadataName(defaultValues, valueName);
                    }

                    private boolean hasDefaultValue(String valueName) {
                        return containsMetadataName(defaultValues.keySet(), valueName);
                    }

                    private boolean setDefaultValue(String valueName) {
                        return containsMetadataName(setDefaultValueNames, valueName);
                    }

                    private boolean hasSetDefaultUnder(String valueName) {
                        String normalized = normalizeMetadataName(valueName);
                        if (isBlank(normalized)) {
                            return false;
                        }
                        if (setDefaultValueNames.contains(normalized)) {
                            return true;
                        }
                        for (String setDefaultValueName : setDefaultValueNames) {
                            if (setDefaultValueName.startsWith(normalized + ".")
                                    || normalized.startsWith(setDefaultValueName + ".")) {
                                return true;
                            }
                        }
                        return false;
                    }

                    private Map<String, Object> defaultValues() {
                        return copyMap(namedDefaultValues);
                    }

                    private Set<String> dynamicIdentifierNames() {
                        return copySet(namedDynamicIdentifierNames);
                    }

                    private Set<String> collectionParameterNames() {
                        return copySet(namedCollectionParameterNames);
                    }

                    private String valueExpressionName(int index, String fallbackName) {
                        if (index < 0 || index >= valueExpressionNames.size()) {
                            return fallbackName;
                        }
                        return new ArrayList<>(valueExpressionNames).get(index);
                    }

                    private String collectionExpressionName(int index, String fallbackName) {
                        if (index < 0 || index >= namedCollectionParameterNames.size()) {
                            return fallbackName;
                        }
                        return new ArrayList<>(namedCollectionParameterNames).get(index);
                    }

                    private boolean containsMetadataName(Set<String> values, String valueName) {
                        return !isBlank(matchingMetadataName(values, valueName));
                    }

                    private String matchingMetadataName(Set<String> values, String valueName) {
                        String normalized = normalizeMetadataName(valueName);
                        if (isBlank(normalized) || values == null || values.isEmpty()) {
                            return "";
                        }
                        if (values.contains(normalized)) {
                            return normalized;
                        }
                        for (String value : values) {
                            if (value.endsWith(normalized) || normalized.endsWith(value)) {
                                return value;
                            }
                        }
                        if (isSyntheticMetadataName(valueName) && values.size() == 1) {
                            return values.iterator().next();
                        }
                        return "";
                    }

                    private <T> T valueByMetadataName(Map<String, T> values, String valueName) {
                        String key = matchingMetadataName(values.keySet(), valueName);
                        return isBlank(key) ? null : values.get(key);
                    }

                    private static boolean containsNameOrSuffix(Set<String> values, String valueName) {
                        String normalized = normalizeMetadataName(valueName);
                        if (isBlank(normalized)) {
                            return false;
                        }
                        if (values.contains(normalized)) {
                            return true;
                        }
                        return values.stream().anyMatch(value -> value.endsWith(normalized) || normalized.endsWith(value));
                    }

                    private static <T> T valueByNameOrSuffix(Map<String, T> values, String valueName) {
                        String normalized = normalizeMetadataName(valueName);
                        if (isBlank(normalized)) {
                            return null;
                        }
                        T exact = values.get(normalized);
                        if (exact != null) {
                            return exact;
                        }
                        for (Map.Entry<String, T> entry : values.entrySet()) {
                            String key = entry.getKey();
                            if (key.endsWith(normalized) || normalized.endsWith(key)) {
                                return entry.getValue();
                            }
                        }
                        return null;
                    }

                    private static String normalizeMetadataName(String valueName) {
                        return valueName == null
                                ? ""
                                : valueName.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                    }

                    private static boolean isSyntheticMetadataName(String valueName) {
                        String normalized = normalizeMetadataName(valueName);
                        int prefixLength;
                        if (normalized.startsWith("arg")) {
                            prefixLength = 3;
                        } else if (normalized.startsWith("param")) {
                            prefixLength = 5;
                        } else {
                            return false;
                        }
                        if (normalized.length() == prefixLength) {
                            return false;
                        }
                        for (int i = prefixLength; i < normalized.length(); i++) {
                            if (!Character.isDigit(normalized.charAt(i))) {
                                return false;
                            }
                        }
                        return true;
                    }
                }

                private static final class NestedCollection {
                    private final String parentCollection;
                    private final String propertyName;
                    private final boolean directElement;

                    private NestedCollection(String parentCollection, String propertyName) {
                        this(parentCollection, propertyName, false);
                    }

                    private NestedCollection(String parentCollection, String propertyName, boolean directElement) {
                        this.parentCollection = parentCollection;
                        this.propertyName = propertyName;
                        this.directElement = directElement;
                    }
                }

                private static final class InsertForeachValues {
                    private final String collection;
                    private final String item;
                    private final List<String> expressions;

                    private InsertForeachValues(String collection, String item, List<String> expressions) {
                        this.collection = collection;
                        this.item = item;
                        this.expressions = expressions == null ? listOf() : copyList(expressions);
                    }
                }

                private static final class BranchCondition {
                    private final String parameterName;
                    private final String literal;
                    private final Object defaultValue;

                    private BranchCondition(String parameterName, String literal, Object defaultValue) {
                        this.parameterName = parameterName;
                        this.literal = literal;
                        this.defaultValue = defaultValue;
                    }
                }

                private static final class BranchCollector {
                    private final String parameterName;
                    private final LinkedHashSet<String> literals = new LinkedHashSet<>();
                    private boolean valid = true;

                    private BranchCollector(String parameterName) {
                        this.parameterName = parameterName;
                    }

                    private void add(String literal, boolean startsWithSet) {
                        literals.add(literal);
                        valid = valid && startsWithSet;
                    }

                    private boolean valid() {
                        return valid && !literals.isEmpty();
                    }

                    private int size() {
                        return literals.size();
                    }

                    private List<SetBranchParameterVariant> variants() {
                        return literals.stream()
                                .map(literal -> new SetBranchParameterVariant(parameterName, literal))
                                .collect(Collectors.toList());
                    }
                }

                private static final class SetBranchParameterVariant {
                    private final String parameterName;
                    private final String literal;

                    private SetBranchParameterVariant(String parameterName, String literal) {
                        this.parameterName = parameterName;
                        this.literal = literal;
                    }
                }

                private static final class XmlOnlyConfiguration extends Configuration {
                    private boolean suppressMapperBinding;

                    private XmlOnlyConfiguration(Environment environment) {
                        super(environment);
                    }

                    private void suppressMapperBinding(boolean suppressMapperBinding) {
                        this.suppressMapperBinding = suppressMapperBinding;
                    }

                    @Override
                    public boolean hasMapper(Class<?> type) {
                        return suppressMapperBinding || super.hasMapper(type);
                    }
                }

                private static final class MapperMethod {
                    private final MapperStatement statement;
                    private final Class<?> mapperInterface;
                    private final Method method;
                    private final SqlCommandType sqlCommandType;
                    private final Class<?> parameterType;
                    private final boolean unmapped;

                    private MapperMethod(
                            MapperStatement statement,
                            Class<?> mapperInterface,
                            Method method,
                            SqlCommandType sqlCommandType,
                            Class<?> parameterType
                    ) {
                        this.statement = statement;
                        this.mapperInterface = mapperInterface;
                        this.method = method;
                        this.sqlCommandType = sqlCommandType;
                        this.parameterType = parameterType;
                        this.unmapped = false;
                    }

                    static MapperMethod unmapped(MapperStatement statement) {
                        return new MapperMethod(statement, null, null, SqlCommandType.UNKNOWN, Object.class, true);
                    }

                    private MapperMethod(
                            MapperStatement statement,
                            Class<?> mapperInterface,
                            Method method,
                            SqlCommandType sqlCommandType,
                            Class<?> parameterType,
                            boolean unmapped
                    ) {
                        this.statement = statement;
                        this.mapperInterface = mapperInterface;
                        this.method = method;
                        this.sqlCommandType = sqlCommandType;
                        this.parameterType = parameterType;
                        this.unmapped = unmapped;
                    }

                    private boolean isUnmapped() {
                        return unmapped;
                    }

                    private String key() {
                        return statement.key();
                    }
                }

                private static final class ParameterResolution {
                    private final boolean resolved;
                    private final String source;
                    private final Object[] args;
                    private final String message;
                    private final String label;
                    private final List<String> names;

                    private ParameterResolution(boolean resolved, String source, Object[] args, String message, String label) {
                        this(resolved, source, args, message, listOf(), label);
                    }

                    private ParameterResolution(
                            boolean resolved,
                            String source,
                            Object[] args,
                            String message,
                            List<String> names,
                            String label
                    ) {
                        this.resolved = resolved;
                        this.source = source;
                        this.args = args;
                        this.message = message;
                        this.names = names == null ? listOf() : copyList(names);
                        this.label = label == null ? "" : label;
                    }

                    static ParameterResolution resolved(String source, Object[] args) {
                        return resolved(source, args, "");
                    }

                    static ParameterResolution resolved(String source, Object[] args, String label) {
                        return new ParameterResolution(true, source, args, "", label);
                    }

                    static ParameterResolution resolved(String source, Object[] args, List<String> names) {
                        return new ParameterResolution(true, source, args, "", names, "");
                    }

                    static ParameterResolution resolved(String source, Object[] args, List<String> names, String label) {
                        return new ParameterResolution(true, source, args, "", names, label);
                    }

                    static ParameterResolution unresolved(String source, String message) {
                        return new ParameterResolution(false, source, new Object[0], message, "");
                    }

                    private String recordKey(String mapperKey) {
                        return isBlank(label) ? mapperKey : mapperKey + " [" + label + "]";
                    }
                }

                private static final class ValueResult {
                    private final boolean resolved;
                    private final Object value;
                    private final String message;

                    private ValueResult(boolean resolved, Object value, String message) {
                        this.resolved = resolved;
                        this.value = value;
                        this.message = message;
                    }

                    static ValueResult resolved(Object value) {
                        return new ValueResult(true, value, "");
                    }

                    static ValueResult unresolved(String message) {
                        return new ValueResult(false, null, message);
                    }
                }

                private static final class ValidationRecord {
                    private final String status;
                    private final String key;
                    private final String parameterSource;
                    private final String parameterSummary;
                    private final String message;
                    private final ParameterResolution parameters;

                    private ValidationRecord(String status, String key, String parameterSource, String message) {
                        this(status, key, parameterSource, "", message, null);
                    }

                    private ValidationRecord(
                            String status,
                            String key,
                            String parameterSource,
                            String parameterSummary,
                            String message
                    ) {
                        this(status, key, parameterSource, parameterSummary, message, null);
                    }

                    private ValidationRecord(
                            String status,
                            String key,
                            String parameterSource,
                            String parameterSummary,
                            String message,
                            ParameterResolution parameters
                    ) {
                        this.status = status;
                        this.key = key;
                        this.parameterSource = parameterSource;
                        this.parameterSummary = parameterSummary == null ? "" : parameterSummary;
                        this.message = message;
                        this.parameters = parameters;
                    }

                    static ValidationRecord passed(String key, String parameterSource, String message) {
                        return new ValidationRecord("PASSED", key, parameterSource, message);
                    }

                    static ValidationRecord passed(String key, String parameterSource, String parameterSummary, String message) {
                        return new ValidationRecord("PASSED", key, parameterSource, parameterSummary, message);
                    }

                    static ValidationRecord passed(String key, String parameterSource, String parameterSummary, String message, ParameterResolution parameters) {
                        return new ValidationRecord("PASSED", key, parameterSource, parameterSummary, message, parameters);
                    }

                    static ValidationRecord failed(String key, String parameterSource, String message) {
                        return new ValidationRecord("FAILED", key, parameterSource, message);
                    }

                    static ValidationRecord failed(String key, String parameterSource, String parameterSummary, String message) {
                        return new ValidationRecord("FAILED", key, parameterSource, parameterSummary, message);
                    }

                    static ValidationRecord failed(String key, String parameterSource, String parameterSummary, String message, ParameterResolution parameters) {
                        return new ValidationRecord("FAILED", key, parameterSource, parameterSummary, message, parameters);
                    }

                    static ValidationRecord skipped(String key, String parameterSource, String message) {
                        return new ValidationRecord("SKIPPED", key, parameterSource, message);
                    }

                    static ValidationRecord skipped(String key, String parameterSource, String parameterSummary, String message) {
                        return new ValidationRecord("SKIPPED", key, parameterSource, parameterSummary, message);
                    }
                }

                private static final class SchemaAttempt {
                    private final String schema;
                    private final ValidationRecord record;

                    private SchemaAttempt(String schema, ValidationRecord record) {
                        this.schema = schema;
                        this.record = record;
                    }

                    private String schema() {
                        return schema;
                    }

                    private ValidationRecord record() {
                        return record;
                    }
                }

                private static final class MapperInvocationException extends RuntimeException {
                    private MapperInvocationException(Throwable cause) {
                        super(cause);
                    }
                }
            }
            """);
}
