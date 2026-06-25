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
                # DM_SQL_VALIDATION=true DM_JDBC_URL=jdbc:dm://host:5236 DM_DB_USERNAME=user DM_DB_PASSWORD=password mvn -Dtest=DmSqlValidationTest test
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
            import java.time.format.DateTimeFormatter;
            import java.util.ArrayList;
            import java.util.Collection;
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

            import static org.junit.jupiter.api.Assertions.fail;

            @Tag("dm-sql-validation")
            @EnabledIfEnvironmentVariable(named = "DM_SQL_VALIDATION", matches = "true")
            class DmSqlValidationTest {
                private static final String CONFIG_PATH = ".dm-adapter/sql-validation.yml";
                private static final String MARKDOWN_REPORT = ".dm-adapter/sql-validation-report.md";
                private static final String JSON_REPORT = ".dm-adapter/sql-validation-report.json";
                private static final Pattern PLACEHOLDER = Pattern.compile("\\\\$\\\\{([^}]+)}");
                private static final Set<String> DEFAULT_COLLECTION_PARAMETER_NAMES = Set.of(
                        "primarykeylist",
                        "removeitemids",
                        "ids",
                        "chargeitemids",
                        "ordernos",
                        "ordernolist",
                        "orders",
                        "accountbooklist",
                        "allitem",
                        "chargedetailids",
                        "owneridlist"
                );
                private static final DateTimeFormatter LOG_TIMESTAMP_FORMATTER =
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                private ValidationConfig currentConfig = new ValidationConfig();
                private DbColumnMetadata dbColumnMetadata = DbColumnMetadata.empty();

                @Test
                void validateMappedDaoSql() throws Exception {
                    Path projectRoot = findProjectRoot();
                    Path configPath = projectRoot.resolve(CONFIG_PATH);
                    log("Started. Project root: " + projectRoot);
                    log("Loading config: " + configPath);
                    ValidationConfig config = ValidationConfig.load(configPath);
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
                                    records.add(record);
                                    logProgress(index, total, record, System.currentTimeMillis() - startedAt);
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
                        fail("Dameng SQL validation failed for " + failed.size()
                                + " mapper methods. See " + projectRoot.resolve(MARKDOWN_REPORT));
                    }
                }

                private SqlSessionFactory buildSqlSessionFactory(
                        ValidationConfig config,
                        List<Path> mapperXmlFiles,
                        Path projectRoot
                ) {
                    log("Building MyBatis SqlSessionFactory...");
                    Configuration configuration = new Configuration(new Environment(
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
                            xmlMapperBuilder.parse();
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to parse mapper XML: " + mapperXmlFile, e);
                        }
                    }
                    log("MyBatis SqlSessionFactory ready.");
                    return new SqlSessionFactoryBuilder().build(configuration);
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
                    if (value == null || value.isBlank() || value.contains("${")) {
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
                    try (var paths = Files.walk(projectRoot)) {
                        return paths.filter(Files::isDirectory)
                                .filter(path -> path.getFileName().toString().equals(directoryName))
                                .filter(path -> normalize(projectRoot.relativize(path)).contains("src/main/resources/"))
                                .filter(path -> !isIgnoredPath(projectRoot, path))
                                .sorted()
                                .collect(Collectors.toList());
                    }
                }

                private List<Path> resolveMapperXmlLocation(Path projectRoot, String location) throws IOException {
                    if (location == null || location.isBlank() || location.startsWith("#")) {
                        return List.of();
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
                        return List.of(path.toAbsolutePath().normalize());
                    }
                    return List.of();
                }

                private List<Path> xmlFiles(Path directory, boolean recursive) throws IOException {
                    if (!Files.isDirectory(directory)) {
                        return List.of();
                    }
                    int maxDepth = recursive ? Integer.MAX_VALUE : 1;
                    try (var paths = Files.walk(directory, maxDepth)) {
                        return paths.filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".xml"))
                                .sorted()
                                .collect(Collectors.toList());
                    }
                }

                private Path resolveProjectPath(Path projectRoot, String location) {
                    Path path = Path.of(location);
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
                        if (mapperInterface != null && !configuration.hasMapper(mapperInterface)) {
                            try {
                                configuration.addMapper(mapperInterface);
                            } catch (Exception ignored) {
                                method = null;
                            }
                        }
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
                    List<Method> methods = List.of(mapperInterface.getMethods()).stream()
                            .filter(method -> isInvocableMapperMethod(method) && method.getName().equals(methodName))
                            .collect(Collectors.toList());
                    if (methods.size() == 1) {
                        return methods.get(0);
                    }
                    List<String> configuredArgs = config.methodArgs.get(mapperInterface.getName() + "." + methodName);
                    if (configuredArgs != null) {
                        List<Method> matchingByCount = methods.stream()
                                .filter(method -> method.getParameterCount() == configuredArgs.size())
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
                            return List.of();
                        }
                        String namespace = root.getAttribute("namespace");
                        if (namespace == null || namespace.isBlank()) {
                            return List.of();
                        }
                        List<MapperStatement> statements = new ArrayList<>();
                        NodeList children = root.getChildNodes();
                        for (int i = 0; i < children.getLength(); i++) {
                            Node node = children.item(i);
                            if (node instanceof Element element && isStatementElement(element)) {
                                String id = element.getAttribute("id");
                                if (id != null && !id.isBlank()) {
                                    statements.add(new MapperStatement(
                                            namespace,
                                            id,
                                            setBranchParameterVariants(element),
                                            dynamicIdentifierMetadata(element),
                                            generatedKeyProperties(element)
                                    ));
                                }
                            }
                        }
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

                private Set<String> generatedKeyProperties(Element statement) {
                    if (!"insert".equals(statement.getTagName())
                            || !"true".equalsIgnoreCase(statement.getAttribute("useGeneratedKeys"))) {
                        return Set.of();
                    }
                    String keyProperty = statement.getAttribute("keyProperty");
                    if (keyProperty == null || keyProperty.isBlank()) {
                        return Set.of();
                    }
                    return Pattern.compile("[,\\\\s]+")
                            .splitAsStream(keyProperty.trim())
                            .map(String::trim)
                            .filter(value -> !value.isBlank())
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                }

                private List<SetBranchParameterVariant> setBranchParameterVariants(Element statement) {
                    if (!"update".equals(statement.getTagName())) {
                        return List.of();
                    }
                    Map<String, BranchCollector> collectors = new LinkedHashMap<>();
                    collectSetBranchParameterVariants(statement, collectors);
                    return collectors.values().stream()
                            .filter(BranchCollector::valid)
                            .max(Comparator.comparingInt(BranchCollector::size))
                            .map(BranchCollector::variants)
                            .orElse(List.of());
                }

                private DynamicIdentifierMetadata dynamicIdentifierMetadata(Element statement) {
                    DynamicIdentifierMetadata metadata = new DynamicIdentifierMetadata();
                    SqlStatementContext sqlContext = sqlStatementContext(statement);
                    collectDynamicIdentifierMetadata(statement, new LinkedHashMap<>(), metadata, false, sqlContext);
                    return metadata;
                }

                private void collectDynamicIdentifierMetadata(
                        Node node,
                        Map<String, String> foreachCollections,
                        DynamicIdentifierMetadata metadata,
                        boolean insideSet,
                        SqlStatementContext sqlContext
                ) {
                    if (node instanceof Element element) {
                        Map<String, String> currentForeachCollections = foreachCollections;
                        boolean currentInsideSet = insideSet || "set".equals(element.getTagName());
                        if ("foreach".equals(element.getTagName())) {
                            String item = element.getAttribute("item");
                            String collection = element.getAttribute("collection");
                            boolean nonEmptyCollection = shouldUseNonEmptyForeachCollection(collection, item, element);
                            metadata.addCollectionParameterName(collection, nonEmptyCollection);
                            if (nonEmptyCollection) {
                                metadata.addNonEmptyCollectionParameterName(collection);
                            }
                            ColumnReference columnReference = inOperatorColumnReference(element, sqlContext);
                            if (columnReference != null) {
                                metadata.addCollectionColumnReference(collection, columnReference);
                            }
                            if (item != null && !item.isBlank() && collection != null && !collection.isBlank()) {
                                currentForeachCollections = new LinkedHashMap<>(foreachCollections);
                                currentForeachCollections.put(item, collection);
                            }
                        }
                        if ("if".equals(element.getTagName()) || "when".equals(element.getTagName())) {
                            BranchCondition condition = branchCondition(element.getAttribute("test"));
                            if (condition != null) {
                                metadata.addDefaultValue(condition.parameterName, condition.defaultValue);
                            }
                            if (currentInsideSet) {
                                addSetAssignmentDefaults(element, currentForeachCollections, metadata);
                            }
                        }
                        NodeList children = element.getChildNodes();
                        for (int i = 0; i < children.getLength(); i++) {
                            collectDynamicIdentifierMetadata(
                                    children.item(i),
                                    currentForeachCollections,
                                    metadata,
                                    currentInsideSet,
                                    sqlContext
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
                        addDynamicIdentifierExpression(dynamicMatcher.group(1), foreachCollections, metadata);
                    }
                    Matcher valueMatcher = Pattern.compile("#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)([^}]*)}").matcher(text);
                    while (valueMatcher.find()) {
                        addValueExpression(valueMatcher.group(1), jdbcType(valueMatcher.group(2)), foreachCollections, metadata);
                    }
                    addValueColumnReferences(text, foreachCollections, metadata, sqlContext);
                }

                private void addDynamicIdentifierExpression(
                        String expression,
                        Map<String, String> foreachCollections,
                        DynamicIdentifierMetadata metadata
                ) {
                    List<String> parts = pathParts(expression);
                    if (parts.isEmpty()) {
                        return;
                    }
                    String collection = foreachCollections.get(parts.get(0));
                    if (collection != null) {
                        if (parts.size() == 1) {
                            metadata.addCollectionSqlFragmentDefault(collection, defaultDynamicSqlFragmentValue(collection, ""));
                        } else {
                            metadata.addCollectionDefault(collection, parts.get(1), defaultDynamicSqlFragmentValue(collection, parts.get(1)));
                        }
                        return;
                    }
                    if (parts.size() == 1) {
                        metadata.addDynamicIdentifierName(parts.get(0));
                    } else {
                        metadata.addDynamicIdentifierName(parts.get(parts.size() - 1));
                    }
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
                        if (parts.size() == 1
                                && jdbcType != null
                                && !jdbcType.isBlank()) {
                            metadata.addCollectionScalarDefault(collection, defaultValueForJdbcType(collection, jdbcType));
                        }
                        if (parts.size() > 1) {
                            Object defaultValue = jdbcType == null || jdbcType.isBlank()
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
                    if (jdbcType != null && !jdbcType.isBlank()) {
                        metadata.addDefaultValue(parts.get(parts.size() - 1), defaultValueForJdbcType(parts.get(parts.size() - 1), jdbcType));
                    }
                }

                private void addValueColumnReferences(
                        String text,
                        Map<String, String> foreachCollections,
                        DynamicIdentifierMetadata metadata,
                        SqlStatementContext sqlContext
                ) {
                    if (text == null || text.isBlank()) {
                        return;
                    }
                    String identifier = sqlIdentifierPattern();
                    Matcher matcher = Pattern.compile(
                            "(?i)(" + identifier + "(?:\\\\s*\\\\.\\\\s*" + identifier + ")?)\\\\s*(?:=|<>|!=|>=|<=|>|<)\\\\s*#\\\\{\\\\s*([A-Za-z_][A-Za-z0-9_.$]*)(?:[^}]*)}"
                    ).matcher(text);
                    while (matcher.find()) {
                        List<String> parts = pathParts(matcher.group(2));
                        if (parts.isEmpty() || foreachCollections.containsKey(parts.get(0))) {
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
                            continue;
                        }
                        String propertyName = parts.size() == 1 ? parts.get(0) : parts.get(parts.size() - 1);
                        ColumnReference columnReference = columnReference(rightColumnMatcher.group(2), sqlContext);
                        if (columnReference != null) {
                            metadata.addDefaultColumnReference(propertyName, columnReference);
                        }
                    }
                }

                private Object defaultValueForDirectParameter(String valueName, String jdbcType) {
                    if (jdbcType != null && !jdbcType.isBlank()) {
                        return defaultValueForJdbcType(valueName, jdbcType);
                    }
                    String normalized = normalizeName(valueName);
                    if (isDeletionFlagName(normalized)) {
                        return 1;
                    }
                    if (normalized.endsWith("id")
                            || normalized.endsWith("ids")
                            || normalized.contains("userid")
                            || normalized.contains("enterpriseid")
                            || normalized.contains("organizationid")) {
                        return 1L;
                    }
                    if (isNumericParameterName(normalized)) {
                        return 1;
                    }
                    if (normalized.contains("code")) {
                        return "CODE";
                    }
                    if (normalized.contains("date")
                            || normalized.contains("time")
                            || normalized.endsWith("day")) {
                        return "2024-01-01 00:00:00";
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
                            || "pagesize".equals(normalizedName)
                            || "pagenum".equals(normalizedName)
                            || "pageindex".equals(normalizedName)
                            || "page".equals(normalizedName)
                            || normalizedName.endsWith("count")
                            || normalizedName.endsWith("index");
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
                        metadata.addDefaultValue(
                                propertyName,
                                defaultValueForSetParameter(propertyName, jdbcType(valueMatcher.group(2)))
                        );
                    }
                }

                private boolean looksLikeSetAssignment(String text) {
                    if (text == null || text.isBlank()) {
                        return false;
                    }
                    return Pattern.compile("(?is)(?:^|[,\\\\s])[A-Za-z_][A-Za-z0-9_.$]*\\\\s*=\\\\s*#\\\\{")
                            .matcher(text)
                            .find();
                }

                private Object defaultValueForSetParameter(String valueName, String jdbcType) {
                    if (jdbcType != null && !jdbcType.isBlank()) {
                        return defaultValueForJdbcType(valueName, jdbcType);
                    }
                    String normalized = normalizeName(valueName);
                    if (normalized.endsWith("day") || normalized.contains("date") || normalized.contains("time")) {
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
                    if (expression == null || expression.isBlank()) {
                        return List.of();
                    }
                    return Pattern.compile("\\\\.")
                            .splitAsStream(expression.trim())
                            .filter(part -> !part.isBlank())
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
                        if (children.item(i) instanceof Element child) {
                            collectSetBranchParameterVariants(child, collectors);
                        }
                    }
                }

                private BranchCondition branchCondition(String test) {
                    if (test == null || test.isBlank()) {
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

                private boolean isBusinessCollectionName(String normalizedName) {
                    return normalizedName.contains("orderno")
                            || normalizedName.contains("billno")
                            || normalizedName.contains("code")
                            || normalizedName.contains("accountbook")
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
                        if (table.isBlank()) {
                            continue;
                        }
                        tableAliases.put(normalizeSqlIdentifier(table), table);
                        if (alias != null && !alias.isBlank() && !isSqlKeyword(alias)) {
                            tableAliases.put(normalizeSqlIdentifier(alias), table);
                        }
                    }
                    return new SqlStatementContext(tableAliases);
                }

                private ColumnReference inOperatorColumnReference(Element element, SqlStatementContext sqlContext) {
                    String textBefore = compactSql(textBeforeElement(element, 300));
                    if (textBefore.isBlank()) {
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
                            .filter(part -> !part.isBlank())
                            .collect(Collectors.toList());
                    if (parts.isEmpty()) {
                        return null;
                    }
                    return columnReference(expression, sqlContext);
                }

                private ColumnReference columnReference(String expression, SqlStatementContext sqlContext) {
                    List<String> parts = Pattern.compile("\\\\.").splitAsStream(expression == null ? "" : expression)
                            .map(String::trim)
                            .filter(part -> !part.isBlank())
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
                    if (expression == null || expression.isBlank()) {
                        return "";
                    }
                    List<String> parts = Pattern.compile("\\\\.").splitAsStream(expression)
                            .map(String::trim)
                            .filter(part -> !part.isBlank())
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
                    return Set.of(
                            "where", "left", "right", "inner", "outer", "full", "join", "on",
                            "group", "order", "having", "limit", "fetch", "union", "cross"
                    ).contains(normalized);
                }

                private boolean startsWithSet(String text) {
                    return text != null && text.stripLeading().toLowerCase(Locale.ROOT).startsWith("set ");
                }

                private List<ParameterResolution> resolveParameterVariants(MapperMethod mapperMethod, ValidationConfig config) {
                    List<String> configuredArgs = config.methodArgs.get(mapperMethod.key());
                    if (mapperMethod.isUnmapped()) {
                        return List.of(ParameterResolution.unresolved("configuration", "Mapped statement was not registered by MyBatis."));
                    }
                    if (mapperMethod.method != null) {
                        if (configuredArgs != null) {
                            return List.of(configuredParameters(mapperMethod, configuredArgs));
                        }
                        ParameterResolution parameters = generatedParameters(mapperMethod);
                        return parameters.resolved
                                ? setBranchParameterVariants(mapperMethod, parameters)
                                : List.of(parameters);
                    }
                    return List.of(statementParameters(mapperMethod, configuredArgs));
                }

                private ParameterResolution configuredParameters(MapperMethod mapperMethod, List<String> configuredArgs) {
                    Class<?>[] parameterTypes = mapperMethod.method.getParameterTypes();
                    if (configuredArgs.size() != parameterTypes.length) {
                        return ParameterResolution.unresolved(
                                "configured",
                                "Configured argument count " + configuredArgs.size()
                                        + " does not match method parameter count " + parameterTypes.length + "."
                        );
                    }
                    Object[] args = new Object[parameterTypes.length];
                    for (int i = 0; i < parameterTypes.length; i++) {
                        ValueResult value = convertScalar(configuredArgs.get(i), parameterTypes[i], mapperMethod.method.getGenericParameterTypes()[i]);
                        if (!value.resolved) {
                            return ParameterResolution.unresolved("configured", value.message);
                        }
                        args[i] = value.value;
                    }
                    return ParameterResolution.resolved("configured", args);
                }

                private ParameterResolution generatedParameters(MapperMethod mapperMethod) {
                    Class<?>[] parameterTypes = mapperMethod.method.getParameterTypes();
                    Type[] genericTypes = mapperMethod.method.getGenericParameterTypes();
                    Object[] args = new Object[parameterTypes.length];
                    for (int i = 0; i < parameterTypes.length; i++) {
                        String parameterName = parameterName(mapperMethod.method, i);
                        String effectiveParameterName = mapperMethod.statement.parameterExpressionName(i, parameterName);
                        ValueResult value = defaultValue(
                                effectiveParameterName,
                                parameterTypes[i],
                                genericTypes[i],
                                0,
                                mapperMethod.statement
                        );
                        if (!value.resolved) {
                            return ParameterResolution.unresolved("auto", value.message);
                        }
                        args[i] = value.value;
                    }
                    return ParameterResolution.resolved("auto", args);
                }

                private List<ParameterResolution> setBranchParameterVariants(
                        MapperMethod mapperMethod,
                        ParameterResolution baseParameters
                ) {
                    if (mapperMethod.statement.setBranchParameterVariants.isEmpty()) {
                        return List.of(baseParameters);
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
                        variants.add(ParameterResolution.resolved(baseParameters.source + ":" + label, args, label));
                    }
                    return variants.isEmpty() ? List.of(baseParameters) : variants;
                }

                private int parameterIndex(Method method, String parameterName) {
                    for (int i = 0; i < method.getParameterCount(); i++) {
                        if (parameterName(method, i).equals(parameterName)) {
                            return i;
                        }
                    }
                    return -1;
                }

                private String parameterName(Method method, int index) {
                    Parameter parameter = method.getParameters()[index];
                    for (Annotation annotation : parameter.getAnnotations()) {
                        if ("org.apache.ibatis.annotations.Param".equals(annotation.annotationType().getName())) {
                            try {
                                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                                if (value instanceof String name && !name.isBlank()) {
                                    return name;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    return parameter.getName();
                }

                """,
            """
                private ParameterResolution statementParameters(MapperMethod mapperMethod, List<String> configuredArgs) {
                    if (configuredArgs != null && configuredArgs.size() > 1) {
                        Map<String, Object> namedParameters = new LinkedHashMap<>();
                        for (int i = 0; i < configuredArgs.size(); i++) {
                            String value = unquote(configuredArgs.get(i));
                            namedParameters.put("arg" + i, value);
                            namedParameters.put("param" + (i + 1), value);
                        }
                        return ParameterResolution.resolved("configured", new Object[] { namedParameters });
                    }
                    if (configuredArgs != null && configuredArgs.size() == 1) {
                        ValueResult value = convertScalar(configuredArgs.get(0), mapperMethod.parameterType, mapperMethod.parameterType);
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
                                    + "\\nSchema attempts: " + schemaAttemptSummary(attempts)
                    );
                }

                private List<String> validationSchemas(ValidationConfig config) {
                    List<String> schemas = config.schemas();
                    return schemas.isEmpty() ? List.of("") : schemas;
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
                    return schema == null || schema.isBlank() ? "<default>" : schema;
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
                            Object result = mapperMethod.method == null
                                    ? invokeMappedStatement(sqlSession, mapperMethod, parameters.args.length == 0 ? null : parameters.args[0])
                                    : invokeReflectively(sqlSession, mapperMethod, parameters.args);
                            sqlSession.rollback(true);
                            return ValidationRecord.passed(
                                    parameters.recordKey(mapperMethod.key()),
                                    parameters.source,
                                    parametersSummary(parameters),
                                    resultSummary(result)
                            );
                        } catch (MapperInvocationException e) {
                            sqlSession.rollback(true);
                            return ValidationRecord.failed(
                                    parameters.recordKey(mapperMethod.key()),
                                    parameters.source,
                                    parametersSummary(parameters),
                                    throwableSummary(e.getCause())
                            );
                        } catch (Throwable e) {
                            sqlSession.rollback(true);
                            return ValidationRecord.failed(
                                    parameters.recordKey(mapperMethod.key()),
                                    parameters.source,
                                    parametersSummary(parameters),
                                    throwableSummary(e)
                            );
                        }
                    } catch (Throwable e) {
                        return ValidationRecord.failed(
                                parameters.recordKey(mapperMethod.key()),
                                parameters.source,
                                parametersSummary(parameters),
                                throwableSummary(e)
                        );
                    }
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
                    if (schema == null || schema.isBlank()) {
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
                    Object columnDefault = defaultTypedColumnValue(valueName, targetType, statement);
                    if (columnDefault != null) {
                        return ValueResult.resolved(columnDefault);
                    }
                    if (String.class.equals(targetType)) {
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
                        Map<String, Object> collectionElementDefault = statement == null
                                ? Map.of()
                                : statement.collectionElementDefault(valueName);
                        if (!collectionElementDefault.isEmpty()) {
                            Map<String, Object> elementValue = new LinkedHashMap<>(collectionElementDefault);
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(List.of(elementValue)));
                            }
                            return ValueResult.resolved(new ArrayList<>(List.of(elementValue)));
                        }
                        Object sqlFragmentDefault = statement == null ? null : statement.collectionSqlFragmentDefault(valueName);
                        if (sqlFragmentDefault != null) {
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(List.of(sqlFragmentDefault)));
                            }
                            return ValueResult.resolved(new ArrayList<>(List.of(sqlFragmentDefault)));
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
                        if (!columnType.isBlank()) {
                            Object elementValue = defaultCollectionElementForColumnType(valueName, columnType);
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(List.of(elementValue)));
                            }
                            return ValueResult.resolved(new ArrayList<>(List.of(elementValue)));
                        }
                        Object scalarDefault = statement == null ? null : statement.collectionScalarDefault(valueName);
                        if (scalarDefault != null) {
                            if (Set.class.isAssignableFrom(targetType)) {
                                return ValueResult.resolved(new LinkedHashSet<>(List.of(scalarDefault)));
                            }
                            return ValueResult.resolved(new ArrayList<>(List.of(scalarDefault)));
                        }
                        Type nestedType = firstGenericArgument(genericType);
                        ValueResult nestedValue = defaultValue(valueName, rawClass(nestedType), nestedType, depth + 1, statement);
                        if (!nestedValue.resolved) {
                            return nestedValue;
                        }
                        if (Set.class.isAssignableFrom(targetType)) {
                            return ValueResult.resolved(new LinkedHashSet<>(List.of(nestedValue.value)));
                        }
                        return ValueResult.resolved(new ArrayList<>(List.of(nestedValue.value)));
                    }
                    if (Map.class.isAssignableFrom(targetType)) {
                        Map<String, Object> value = defaultParameterMap(statement);
                        Map<String, Object> configuredDefaults = statement == null
                                ? Map.of()
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
                        if (instance instanceof Map map) {
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
                            value.put(entry.getKey(), defaultParameterMapValue(entry.getKey(), entry.getValue(), statement));
                        }
                        for (String dynamicIdentifierName : statement.dynamicIdentifierNames()) {
                            value.putIfAbsent(dynamicIdentifierName, defaultDynamicIdentifier(dynamicIdentifierName));
                        }
                        for (String collectionName : statement.collectionParameterNames()) {
                            putDefaultParameterValue(value, collectionName, defaultCollectionParameter(collectionName, statement));
                        }
                    }
                    return value;
                }

                @SuppressWarnings("unchecked")
                private void putDefaultParameterValue(Map<String, Object> target, String parameterPath, Object parameterValue) {
                    List<String> parts = pathParts(parameterPath);
                    if (parts.size() <= 1) {
                        target.putIfAbsent(parameterPath, parameterValue);
                        return;
                    }
                    Map<String, Object> current = target;
                    for (int i = 0; i < parts.size() - 1; i++) {
                        String part = parts.get(i);
                        Object existing = current.get(part);
                        if (existing instanceof Map<?, ?> existingMap) {
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
                    if (!columnType.isBlank() && !isCharacterColumnType(columnType)) {
                        return defaultValueForColumnType(valueName, Object.class, columnType);
                    }
                    if (configuredDefault != null) {
                        return configuredDefault;
                    }
                    return columnType.isBlank()
                            ? null
                            : defaultValueForColumnType(valueName, Object.class, columnType);
                }

                private Object defaultCollectionParameter(String collectionName, MapperStatement statement) {
                    Map<String, Object> collectionElementDefault = statement == null
                            ? Map.of()
                            : statement.collectionElementDefault(collectionName);
                    if (!collectionElementDefault.isEmpty()) {
                        return new ArrayList<>(List.of(new LinkedHashMap<>(collectionElementDefault)));
                    }
                    Object sqlFragmentDefault = statement == null ? null : statement.collectionSqlFragmentDefault(collectionName);
                    if (sqlFragmentDefault != null) {
                        return new ArrayList<>(List.of(sqlFragmentDefault));
                    }
                    if (shouldUseEmptyCollection(collectionName)
                            && (statement == null || !statement.nonEmptyCollectionParameter(collectionName))) {
                        return new ArrayList<>();
                    }
                    String columnType = statement == null
                            ? ""
                            : statement.collectionColumnType(collectionName, dbColumnMetadata);
                    if (!columnType.isBlank()) {
                        return new ArrayList<>(List.of(defaultCollectionElementForColumnType(collectionName, columnType)));
                    }
                    Object scalarDefault = statement == null ? null : statement.collectionScalarDefault(collectionName);
                    if (scalarDefault != null) {
                        return new ArrayList<>(List.of(scalarDefault));
                    }
                    return new ArrayList<>(List.of(defaultCollectionElement(collectionName)));
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
                        return "2024-01-01 00:00:00";
                    }
                    return defaultCollectionElement(collectionName);
                }

                private Object defaultTypedColumnValue(String valueName, Class<?> targetType, MapperStatement statement) {
                    if (!String.class.equals(targetType) && !Object.class.equals(targetType)) {
                        return null;
                    }
                    String columnType = statement == null ? "" : statement.defaultColumnType(valueName, dbColumnMetadata);
                    if (columnType.isBlank()
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
                        return "2024-01-01 00:00:00";
                    }
                    return defaultNameBasedString(valueName);
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
                    if (normalized.endsWith("id")
                            || normalized.endsWith("ids")
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
                    if (isDynamicIdentifierName(normalized)) {
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
                    if (normalized.endsWith("id")
                            || normalized.endsWith("ids")
                            || normalized.contains("userid")
                            || normalized.contains("enterpriseid")
                            || normalized.contains("organizationid")) {
                        return "1";
                    }
                    if (normalized.contains("code")) {
                        return "CODE";
                    }
                    if (normalized.contains("date") || normalized.contains("time")) {
                        return "2024-01-01 00:00:00";
                    }
                    if (normalized.contains("comparison")) {
                        return "EQUAL";
                    }
                    return "test";
                }

                private String defaultDynamicIdentifier(String valueName) {
                    String normalized = normalizeName(valueName);
                    if (isRawSqlInjectionName(normalized)) {
                        return "";
                    }
                    if (isSchemaIdentifierName(normalized)
                            && currentConfig != null
                            && !currentConfig.primarySchema().isBlank()) {
                        return quotedIdentifier(currentConfig.primarySchema());
                    }
                    if (isDynamicIdentifierName(normalized)) {
                        return "ID";
                    }
                    if (isNumericSqlFragmentName(normalized)) {
                        return "1";
                    }
                    if (isStringSqlLiteralFragmentName(normalized)) {
                        return "'test'";
                    }
                    return "ID";
                }

                private String defaultDynamicSqlFragmentValue(String collectionName, String propertyName) {
                    String normalizedProperty = normalizeName(propertyName);
                    String normalizedCollection = normalizeName(collectionName);
                    if (isRawSqlInjectionName(normalizedProperty) || isRawSqlInjectionName(normalizedCollection)) {
                        return "";
                    }
                    if (normalizedProperty.endsWith("field")
                            || normalizedProperty.endsWith("fieldname")
                            || normalizedProperty.endsWith("column")
                            || normalizedProperty.endsWith("columnname")
                            || normalizedProperty.endsWith("modelkey")
                            || normalizedProperty.endsWith("underline")
                            || normalizedProperty.endsWith("underlinename")) {
                        return "ID";
                    }
                    if ("fieldvalue".equals(normalizedProperty)
                            || "value".equals(normalizedProperty)
                            || normalizedProperty.endsWith("values")
                            || isNumericSqlFragmentName(normalizedProperty)
                            || isNumericSqlFragmentName(normalizedCollection)) {
                        return "1";
                    }
                    if (normalizedProperty.contains("code") || normalizedCollection.contains("code")) {
                        return "'CODE'";
                    }
                    if (isStringSqlLiteralFragmentName(normalizedProperty)
                            || isStringSqlLiteralFragmentName(normalizedCollection)) {
                        return "'test'";
                    }
                    return "ID";
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

                private boolean isSchemaIdentifierName(String normalized) {
                    return "schema".equals(normalized)
                            || "schemaname".equals(normalized)
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
                    return switch (normalizedJdbcType) {
                        case "BIGINT" -> 1L;
                        case "INTEGER", "INT", "SMALLINT", "TINYINT" -> 1;
                        case "DOUBLE", "FLOAT", "REAL" -> 1D;
                        case "DECIMAL", "NUMERIC" -> BigDecimal.ONE;
                        case "BIT", "BOOLEAN" -> true;
                        case "DATE", "TIME", "TIMESTAMP", "DATETIME" -> "2024-01-01 00:00:00";
                        default -> defaultString(valueName);
                    };
                }

                private boolean shouldUseEmptyCollection(String valueName) {
                    String normalized = normalizeName(valueName);
                    return "filterlist".equals(normalized)
                            || "filters".equals(normalized)
                            || "sorts".equals(normalized)
                            || "orders".equals(normalized);
                }

                private boolean isOrderFieldName(String normalizedName) {
                    return normalizedName.contains("orderfield")
                            || normalizedName.contains("sortfield")
                            || normalizedName.contains("orderbyfield");
                }

                private boolean isOrderDirectionName(String normalizedName) {
                    return "orderby".equals(normalizedName)
                            || normalizedName.contains("orderdirection")
                            || normalizedName.contains("sortdirection")
                            || normalizedName.endsWith("direction");
                }

                private boolean isDynamicIdentifierName(String normalizedName) {
                    return normalizedName.contains("fieldunderlinename")
                            || normalizedName.endsWith("fieldname")
                            || normalizedName.endsWith("columnname");
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
                    if (type instanceof ParameterizedType parameterizedType && parameterizedType.getActualTypeArguments().length > 0) {
                        return parameterizedType.getActualTypeArguments()[0];
                    }
                    return String.class;
                }

                private Class<?> rawClass(Type type) {
                    if (type instanceof Class<?> clazz) {
                        return clazz;
                    }
                    if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> clazz) {
                        return clazz;
                    }
                    return String.class;
                }

                private Path findProjectRoot() {
                    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
                    Files.writeString(projectRoot.resolve(MARKDOWN_REPORT), markdown(records, usageFilterReport), StandardCharsets.UTF_8);
                    Files.writeString(projectRoot.resolve(JSON_REPORT), json(records, usageFilterReport), StandardCharsets.UTF_8);
                }

                """,
            """
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
                    Map<String, Long> missingTables = schemaIssueCounts(records, "无效的表或视图名");
                    Map<String, Long> missingColumns = schemaIssueCounts(records, "无效的列名");
                    if (missingTables.isEmpty() && missingColumns.isEmpty()) {
                        return;
                    }
                    markdown.append("## 库表对象缺失热点\\n\\n");
                    appendCountSummary(markdown, "缺失表/视图", "对象", missingTables, 20);
                    appendCountSummary(markdown, "缺失字段", "字段", missingColumns, 30);
                }

                private Map<String, Long> schemaIssueCounts(List<ValidationRecord> records, String marker) {
                    Map<String, Long> counts = new LinkedHashMap<>();
                    for (ValidationRecord record : records) {
                        if (!"FAILED".equals(record.status)) {
                            continue;
                        }
                        for (String value : bracketedValuesAfterMarker(record.message, marker)) {
                            counts.merge(value, 1L, Long::sum);
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
                    if (message == null || marker == null || marker.isBlank()) {
                        return List.of();
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
                    if (countsByPattern.containsKey("TEST_SCHEMA_OBJECT")) {
                        markdown.append("- 先对齐达梦测试 schema 中缺失的表、视图、字段或对象命名差异，再将其视为 SQL 改写失败。\\n");
                    }
                    if (countsByPattern.containsKey("BROKEN_DYNAMIC_SQL_OR_ARGS")) {
                        markdown.append("- 检查 mapper 动态 SQL 分支和生成的示例参数；缺少逗号、空条件、非法动态占位符等问题通常属于 mapper 结构或验证参数问题。\\n");
                    }
                    if (containsAnyPattern(countsByPattern,
                            "NULL_COLLECTION_PARAMETER",
                            "BINDING_PARAMETER_NAME",
                            "MAPPER_PROPERTY_NAME",
                            "METHOD_ARGS_OR_BINDING_OTHER",
                            "DYNAMIC_IDENTIFIER_PARAMETER",
                            "DYNAMIC_SQL_FRAGMENT_PARAMETER",
                            "GENERATED_SEARCH_PARAMETER",
                            "RAW_SQL_PARAMETER",
                            "GENERATED_ORDER_PARAMETER")
                            || countsByCategory.containsKey("METHOD_ARGS_OR_BINDING")) {
                        markdown.append("- 在 sql-validation.yml 配置方法参数；当 XML 参数名与 Java 方法参数不一致时，检查 mapper 的 @Param 名称。\\n");
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
                        if (record.parameterSummary != null && !record.parameterSummary.isBlank()) {
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
                    return switch (status) {
                        case "PASSED" -> "通过 (PASSED)";
                        case "FAILED" -> "失败 (FAILED)";
                        case "SKIPPED" -> "跳过 (SKIPPED)";
                        default -> status;
                    };
                }

                private String categoryDisplay(String category) {
                    return displayCode(category, categoryLabel(category));
                }

                private String categoryLabel(String category) {
                    return switch (category) {
                        case "PASSED" -> "通过";
                        case "SKIPPED" -> "跳过";
                        case "CONFIGURATION" -> "配置问题";
                        case "METHOD_ARGS_OR_BINDING" -> "方法参数或绑定问题";
                        case "MYSQL_METADATA_SQL" -> "MySQL 元数据 SQL";
                        case "TEST_SCHEMA" -> "测试库对象问题";
                        case "TEST_DATA_OR_SCHEMA" -> "测试数据或库结构问题";
                        case "SQL_SYNTAX" -> "SQL 语法问题";
                        case "TEST_DATA" -> "测试数据问题";
                        case "UNKNOWN_FAILURE" -> "未分类失败";
                        default -> category.endsWith("_OTHER") ? "其他未分类问题" : "";
                    };
                }

                private String failurePatternDisplay(String pattern) {
                    if (pattern == null || pattern.isBlank()) {
                        return "";
                    }
                    return displayCode(pattern, failurePatternLabel(pattern));
                }

                private String failurePatternLabel(String pattern) {
                    return switch (pattern) {
                        case "MYSQL_METADATA_SQL" -> "MySQL 元数据查询";
                        case "MYSQL_SELECT_MODIFIER" -> "MySQL SELECT 修饰符";
                        case "MYSQL_INDEX_HINT" -> "MySQL 索引提示";
                        case "MYSQL_INSERT_VALUE_KEYWORD" -> "MySQL VALUE 关键字";
                        case "MYSQL_CONVERT_DECIMAL" -> "MySQL CONVERT DECIMAL";
                        case "MYSQL_USER_VARIABLE" -> "MySQL 用户变量";
                        case "DYNAMIC_IDENTIFIER_PARAMETER" -> "动态标识符参数";
                        case "DYNAMIC_SQL_FRAGMENT_PARAMETER" -> "动态 SQL 片段参数";
                        case "GENERATED_SEARCH_PARAMETER" -> "生成的搜索参数";
                        case "BROKEN_DYNAMIC_SQL_OR_ARGS" -> "动态 SQL 或示例参数异常";
                        case "TEST_SCHEMA_OBJECT" -> "测试库缺少对象";
                        case "TEST_DATA_TYPE_MISMATCH" -> "测试参数类型不匹配";
                        case "TEST_DATA_FOREIGN_KEY_CONSTRAINT" -> "外键约束测试数据问题";
                        case "INSERT_IGNORE" -> "INSERT IGNORE";
                        case "MYSQL_GROUP_CONCAT" -> "GROUP_CONCAT 函数";
                        case "MYSQL_CONCAT_WS" -> "CONCAT_WS 函数";
                        case "MYSQL_DATE_SUB_INTERVAL" -> "DATE_SUB INTERVAL";
                        case "MYSQL_DATE_ADD_INTERVAL" -> "DATE_ADD INTERVAL";
                        case "MYSQL_MAKEDATE" -> "MAKEDATE 函数";
                        case "MYSQL_SUBDATE" -> "SUBDATE 函数";
                        case "MYSQL_PERIOD_DIFF_YEARMONTH" -> "PERIOD_DIFF 年月差";
                        case "MYSQL_COUNT_CONDITION_OR_NULL" -> "COUNT 条件 OR NULL";
                        case "MYSQL_NOT_ISNULL" -> "!ISNULL 表达式";
                        case "MYSQL_BOOLEAN_OPERATOR" -> "MySQL 布尔运算符";
                        case "MYSQL_CONVERT_UNSIGNED" -> "CONVERT UNSIGNED";
                        case "MYSQL_CONVERT_GBK_ORDER" -> "GBK 排序转换";
                        case "MYSQL_UPDATE_ORDER_LIMIT" -> "UPDATE ORDER BY LIMIT";
                        case "MYSQL_JSON_TABLE_JOIN_WITHOUT_ON" -> "JSON_TABLE JOIN 缺少 ON";
                        case "MYSQL_IMPLICIT_CROSS_JOIN" -> "隐式 CROSS JOIN";
                        case "MYSQL_TEMPORARY_TABLE_AS_SELECT" -> "临时表 AS SELECT";
                        case "DAMENG_KEYWORD_TABLE_ALIAS" -> "达梦关键字表别名";
                        case "DAMENG_RESERVED_IDENTIFIER" -> "达梦保留字标识符";
                        case "MYSQL_JSON_SQL" -> "MySQL JSON SQL";
                        case "MYSQL_UPDATE_JOIN" -> "UPDATE JOIN";
                        case "MYSQL_UPDATE_JOIN_MULTI_TARGET" -> "UPDATE JOIN 同时更新多表";
                        case "RAW_SQL_PARAMETER" -> "原始 SQL 参数";
                        case "AMBIGUOUS_COLUMN" -> "歧义列名";
                        case "EMPTY_SELECT_LIST" -> "空 SELECT 列表";
                        case "GENERATED_ORDER_PARAMETER" -> "生成的排序参数";
                        case "UPDATE_SET_TABLE_ORDER" -> "UPDATE SET 语序";
                        case "ON_DUPLICATE_KEY_UPDATE" -> "ON DUPLICATE KEY UPDATE";
                        case "INSERT_FOREACH_MISSING_VALUES" -> "INSERT foreach 缺少 VALUES";
                        case "REGEXP_OPERATOR" -> "REGEXP 操作符";
                        case "DOUBLE_QUOTED_IDENTIFIER_OR_STRING" -> "双引号标识符或字符串";
                        case "TRAILING_COMMA" -> "多余逗号";
                        case "NULL_COLLECTION_PARAMETER" -> "集合参数为空";
                        case "BINDING_PARAMETER_NAME" -> "绑定参数名问题";
                        case "MAPPER_PROPERTY_NAME" -> "Mapper 属性名不匹配";
                        case "INSERT_VALUES_ASSIGNMENT" -> "INSERT VALUES 中出现赋值表达式";
                        case "ORIGINAL_XML_SYNTAX_DEFECT" -> "原 XML SQL 语法缺陷";
                        case "TEST_DATA_OR_CONSTRAINT" -> "测试数据或约束问题";
                        default -> pattern.endsWith("_OTHER") ? "其他未分类模式" : "";
                    };
                }

                private String displayCode(String code, String label) {
                    if (code == null || code.isBlank()) {
                        return "";
                    }
                    return label == null || label.isBlank() ? code : label + " (" + code + ")";
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
                    appendJsonCountArray(json, "missingTablesOrViews", schemaIssueCounts(records, "无效的表或视图名"));
                    json.append(", ");
                    appendJsonCountArray(json, "missingColumns", schemaIssueCounts(records, "无效的列名"));
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

                private String failurePattern(ValidationRecord record) {
                    if (!"FAILED".equals(record.status)) {
                        return "";
                    }
                    String message = normalizeMessage(record.message);
                    String lower = message.toLowerCase(Locale.ROOT);
                    if (isMysqlMetadataSql(message)) {
                        return "MYSQL_METADATA_SQL";
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
                    if (Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*\\\\s*:=", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "MYSQL_USER_VARIABLE";
                    }
                    if (isAutoParameter(record) && hasGeneratedDynamicIdentifierPlaceholder(message)) {
                        return "DYNAMIC_IDENTIFIER_PARAMETER";
                    }
                    if (isAutoParameter(record) && hasDynamicSqlFragmentParameterIssue(message)) {
                        return "DYNAMIC_SQL_FRAGMENT_PARAMETER";
                    }
                    if (isAutoParameter(record) && hasGeneratedSearchParameterIssue(record, message)) {
                        return "GENERATED_SEARCH_PARAMETER";
                    }
                    if (lower.contains("sql语句为null或空值") || hasBrokenDynamicSqlShape(message)) {
                        return "BROKEN_DYNAMIC_SQL_OR_ARGS";
                    }
                    if (hasOriginalXmlSyntaxDefect(message)) {
                        return "ORIGINAL_XML_SYNTAX_DEFECT";
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
                    if (lower.contains("列表不匹配")
                            || lower.contains("重复的列名")
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\?\\\\s+\\\\?").matcher(message).find()) {
                        return "BROKEN_DYNAMIC_SQL_OR_ARGS";
                    }
                    if (Pattern.compile("(?i)(\\\\band\\\\s*\\\\(\\\\s*\\\\)|,\\\\s*where\\\\b|\\\\bwhere\\\\s+and\\\\b|\\\\bwhere\\\\s+where\\\\b|\\\\bfrom\\\\s+where\\\\b|\\\\bset\\\\s+where\\\\b|\\\\?\\\\s+[A-Za-z_][A-Za-z0-9_$]*\\\\s*=|^### SQL:\\\\s*ID\\\\s+select\\\\b)").matcher(message).find()) {
                        return "BROKEN_DYNAMIC_SQL_OR_ARGS";
                    }
                    if (Pattern.compile("update\\\\s+set\\\\s+[a-z_][a-z0-9_]*", Pattern.CASE_INSENSITIVE).matcher(message).find()) {
                        return "UPDATE_SET_TABLE_ORDER";
                    }
                    if (lower.contains("on duplicate key update")) {
                        return "ON_DUPLICATE_KEY_UPDATE";
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
                    if (Pattern.compile(",\\\\s*\\\\)").matcher(message).find()) {
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
                    if (lower.contains("数据类型不匹配")) {
                        return "TEST_DATA_TYPE_MISMATCH";
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
                    if (containsAny(message,
                            "非空约束",
                            "违反列[",
                            "长度超出定义",
                            "类型转换异常",
                            "唯一性约束",
                            "非法的时间日期类型数据",
                            "SET IDENTITY_INSERT",
                            "自增列")) {
                        return "TEST_DATA_OR_CONSTRAINT";
                    }
                    return category(record) + "_OTHER";
                }

                private boolean isSchemaObjectFailure(String lowerMessage) {
                    return lowerMessage.contains("无效的表或视图名")
                            || lowerMessage.contains("无效的列名")
                            || lowerMessage.contains("无效的模式名")
                            || lowerMessage.contains("无法解析的成员访问表达式");
                }

                private boolean isMysqlMetadataSql(String message) {
                    return Pattern.compile("\\\\binformation_schema\\\\b|\\\\bdatabase\\\\s*\\\\(", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("(?im)^### SQL:\\\\s*describe\\\\b").matcher(message).find();
                }

                private boolean hasGeneratedDynamicIdentifierPlaceholder(String message) {
                    return Pattern.compile("(?im)^### SQL:\\\\s*ID\\\\s*$").matcher(message).find()
                            || Pattern.compile("(?im)^### SQL:\\\\s*ID\\\\s+select\\\\b").matcher(message).find()
                            || Pattern.compile("\\\\b(?:from|join|update|into|table)\\\\s+ID\\\\b", Pattern.CASE_INSENSITIVE).matcher(message).find();
                }

                private boolean hasDynamicSqlFragmentParameterIssue(String message) {
                    return Pattern.compile("\\\\bin\\\\s*\\\\(\\\\s*(?:ID|test)\\\\s*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("无效的列名\\\\s*\\\\[\\\\s*test\\\\s*]", Pattern.CASE_INSENSITIVE).matcher(message).find()
                            || Pattern.compile("\\\\b[A-Za-z_][A-Za-z0-9_$.]*\\\\s+(?:not\\\\s+)?in\\\\s*\\\\(\\\\s*ID\\\\s*\\\\)", Pattern.CASE_INSENSITIVE).matcher(message).find();
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

                private boolean hasOriginalXmlSyntaxDefect(String message) {
                    return Pattern.compile("(?i)insert\\\\s+into\\\\b[\\\\s\\\\S]*?values\\\\s*\\\\([\\\\s\\\\S]*?[A-Za-z_][A-Za-z0-9_$]*\\\\s*=").matcher(message).find()
                            || Pattern.compile("(?i)### SQL:[\\\\s\\\\S]*?\\\\bwhere\\\\b[\\\\s\\\\S]*?\\\\b[A-Za-z_][A-Za-z0-9_$]*\\\\s*=\\\\s*(?:\\\\?|\\\\d+|'[^']*')\\\\s+[A-Za-z_][A-Za-z0-9_$]*\\\\s*=").matcher(message).find();
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
                            "Mapped statement was not registered",
                            "Parsing error was found in mapping",
                            "datasource.")) {
                        return "CONFIGURATION";
                    }
                    if (containsAny(message,
                            "evaluated to a null value",
                            "Parameter '",
                            "not found. Available parameters",
                            "There is no getter for property",
                            "invalid comparison:",
                            "Could not set parameters",
                            "primitive return type")) {
                        return "METHOD_ARGS_OR_BINDING";
                    }
                    if (isMysqlMetadataSql(message)) {
                        return "MYSQL_METADATA_SQL";
                    }
                    if (hasMysqlMakeDate(message)
                            || hasMysqlSubdate(message)
                            || hasMysqlUpdateJoinMultiTarget(message)
                            || hasOriginalXmlSyntaxDefect(message)) {
                        return "SQL_SYNTAX";
                    }
                    if (containsAny(message, "无效的表或视图名", "无效的列名", "无效的模式名", "无法解析的成员访问表达式")) {
                        return "TEST_SCHEMA";
                    }
                    if (containsAny(message,
                            "非空约束",
                            "违反列[",
                            "长度超出定义",
                            "类型转换异常",
                            "数据类型不匹配",
                            "唯一性约束",
                            "违反引用约束",
                            "非法的时间日期类型数据",
                            "SET IDENTITY_INSERT",
                            "自增列")) {
                        return "TEST_DATA_OR_SCHEMA";
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
                        return "生成的示例参数未满足动态 SQL 或 @Param 绑定；请配置方法参数或检查 mapper 参数名。";
                    }
                    if ("SQL_SYNTAX".equals(category)) {
                        return "达梦拒绝了 SQL 语法；请检查 mapper-dm SQL，并手工处理未兼容的片段。";
                    }
                    if ("TEST_SCHEMA".equals(category)) {
                        return "达梦测试库缺少表、视图或字段，或对象命名与 mapper SQL 不一致。";
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
                    if (compact.isBlank()) {
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
                    if (value instanceof CharSequence text) {
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
                    if (value instanceof Collection<?> collection) {
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
                    if (value instanceof Map<?, ?> map) {
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
                    Class<?> currentType = value.getClass();
                    while (currentType != null && !Object.class.equals(currentType) && count < 8) {
                        for (Field field : currentType.getDeclaredFields()) {
                            if (count >= 8) {
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
                                if (!field.canAccess(value)) {
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
                    if (result instanceof Collection<?> collection) {
                        return "Returned collection size " + collection.size() + ".";
                    }
                    if (result instanceof Map<?, ?> map) {
                        return "Returned map size " + map.size() + ".";
                    }
                    return "Returned " + result.getClass().getName() + ".";
                }

                private String throwableSummary(Throwable throwable) {
                    if (throwable == null) {
                        return "Unknown failure.";
                    }
                    String message = throwable.getMessage();
                    return throwable.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
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
                    String message = record.message == null || record.message.isBlank()
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
                        try (var paths = Files.walk(projectRoot)) {
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
                            try (var paths = Files.walk(classDirectory)) {
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
                                return Set.of();
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
                                    case 1 -> utf8[i] = input.readUTF();
                                    case 3, 4 -> input.readInt();
                                    case 5, 6 -> {
                                        input.readLong();
                                        i++;
                                    }
                                    case 7 -> classNameIndexes[i] = input.readUnsignedShort();
                                    case 8 -> stringIndexes[i] = input.readUnsignedShort();
                                    case 9 -> {
                                        input.readUnsignedShort();
                                        input.readUnsignedShort();
                                    }
                                    case 10, 11 -> memberRefs[i] = new MemberRef(
                                            input.readUnsignedShort(),
                                            input.readUnsignedShort()
                                    );
                                    case 12 -> nameAndTypes[i] = new NameAndType(
                                            input.readUnsignedShort(),
                                            input.readUnsignedShort()
                                    );
                                    case 15 -> {
                                        input.readUnsignedByte();
                                        input.readUnsignedShort();
                                    }
                                    case 16, 19, 20 -> input.readUnsignedShort();
                                    case 17, 18 -> {
                                        input.readUnsignedShort();
                                        input.readUnsignedShort();
                                    }
                                    default -> throw new IOException("Unsupported class constant pool tag: " + tag);
                                }
                            }

                            input.readUnsignedShort();
                            int thisClassIndex = input.readUnsignedShort();
                            String thisClass = className(classNameIndexes, utf8, thisClassIndex);
                            if (mapperInternalNames.contains(thisClass)) {
                                return Set.of();
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
                        Path path = Path.of(location);
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
                        return new UsageFilter(false, Set.of(), UsageFilterReport.disabled());
                    }

                    private static UsageFilter unavailable(UsageFilterReport report) {
                        return new UsageFilter(false, Set.of(), report);
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
                        this.warnings = List.copyOf(warnings == null ? List.of() : warnings);
                    }

                    private static UsageFilterReport disabled() {
                        return new UsageFilterReport(false, false, 0, 0, 0, List.of());
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
                private static final class ValidationConfig {
                    private String schema = "";
                    private boolean usageFilterEnabled = true;
                    private final DatasourceConfig datasource = new DatasourceConfig();
                    private final List<String> mapperXmlLocations = new ArrayList<>();
                    private final List<String> usageClassDirectories = new ArrayList<>();
                    private final List<String> typeAliasesPackages = new ArrayList<>();
                    private final List<String> typeHandlersPackages = new ArrayList<>();
                    private final Map<String, List<String>> methodArgs = new LinkedHashMap<>();
                    private final Set<String> includedMethods = new LinkedHashSet<>();
                    private final Set<String> excludedMethods = new LinkedHashSet<>();

                    static ValidationConfig load(Path path) throws IOException {
                        ValidationConfig config = new ValidationConfig();
                        if (!Files.isRegularFile(path)) {
                            return config;
                        }
                        String section = "";
                        String currentMethod = null;
                        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                            String trimmed = line.trim();
                            if (trimmed.isBlank() || trimmed.startsWith("#")) {
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
                        return config;
                    }

                    boolean excludes(String methodKey) {
                        if (excludedMethods.contains(methodKey)) {
                            return true;
                        }
                        int lastDot = methodKey.lastIndexOf('.');
                        return lastDot > 0 && excludedMethods.contains(methodKey.substring(0, lastDot) + ".*");
                    }

                    boolean includes(String methodKey) {
                        if (methodArgs.containsKey(methodKey) || includedMethods.contains(methodKey)) {
                            return true;
                        }
                        int lastDot = methodKey.lastIndexOf('.');
                        return lastDot > 0 && includedMethods.contains(methodKey.substring(0, lastDot) + ".*");
                    }

                    List<String> schemas() {
                        if (schema == null || schema.isBlank()) {
                            return List.of();
                        }
                        return Pattern.compile(",")
                                .splitAsStream(schema)
                                .map(String::trim)
                                .filter(value -> !value.isBlank())
                                .collect(Collectors.collectingAndThen(
                                        Collectors.toCollection(LinkedHashSet::new),
                                        ArrayList::new
                                ));
                    }

                    String primarySchema() {
                        List<String> schemas = schemas();
                        return schemas.isEmpty() ? "" : schemas.get(0);
                    }

                    private String scalar(String value) {
                        String trimmed = value.trim();
                        if (trimmed.length() >= 2
                                && ((trimmed.startsWith("\\"") && trimmed.endsWith("\\""))
                                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
                            return trimmed.substring(1, trimmed.length() - 1);
                        }
                        return trimmed;
                    }
                }

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
                        if (trimmed.length() >= 2
                                && ((trimmed.startsWith("\\"") && trimmed.endsWith("\\""))
                                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
                            return trimmed.substring(1, trimmed.length() - 1);
                        }
                        return trimmed;
                    }
                }

                private record ColumnReference(String tableName, String columnName) {
                }

                private final class SqlStatementContext {
                    private final Map<String, String> tableAliases;

                    private SqlStatementContext(Map<String, String> tableAliases) {
                        this.tableAliases = Map.copyOf(tableAliases == null ? Map.of() : tableAliases);
                    }

                    private String tableName(String qualifier) {
                        if (qualifier == null || qualifier.isBlank()) {
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
                        List<String> targetSchemas = schemas == null ? List.of() : schemas;
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
                        String schemaPattern = schema == null || schema.isBlank() ? null : schema;
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
                        String sql = schema == null || schema.isBlank()
                                ? "select table_name, column_name, data_type from all_tab_columns"
                                : "select table_name, column_name, data_type from all_tab_columns "
                                        + "where owner = ? or upper(owner) = upper(?)";
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            if (schema != null && !schema.isBlank()) {
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
                        if (normalizedTable.isBlank() || normalizedColumn.isBlank() || dataType == null || dataType.isBlank()) {
                            return;
                        }
                        String normalizedType = dataType.toUpperCase(Locale.ROOT);
                        tableColumnTypes.putIfAbsent(tableKey(normalizedTable, normalizedColumn), normalizedType);
                        columnTypes.computeIfAbsent(normalizedColumn, ignored -> new LinkedHashSet<>()).add(normalizedType);
                    }

                    private String columnType(ColumnReference columnReference) {
                        if (columnReference == null || columnReference.columnName().isBlank()) {
                            return "";
                        }
                        String normalizedColumn = normalizeIdentifier(columnReference.columnName());
                        String normalizedTable = normalizeIdentifier(columnReference.tableName());
                        if (!normalizedTable.isBlank()) {
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
                    private final Set<String> generatedKeyProperties;

                    private MapperStatement(
                            String namespace,
                            String id,
                            List<SetBranchParameterVariant> setBranchParameterVariants,
                            DynamicIdentifierMetadata dynamicIdentifierMetadata,
                            Set<String> generatedKeyProperties
                    ) {
                        this.namespace = namespace;
                        this.id = id;
                        this.setBranchParameterVariants = List.copyOf(setBranchParameterVariants == null
                                ? List.of()
                                : setBranchParameterVariants);
                        this.dynamicIdentifierMetadata = dynamicIdentifierMetadata == null
                                ? new DynamicIdentifierMetadata()
                                : dynamicIdentifierMetadata;
                        this.generatedKeyProperties = Set.copyOf(generatedKeyProperties == null
                                ? Set.of()
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

                    private String defaultColumnType(String valueName, DbColumnMetadata columnMetadata) {
                        ColumnReference columnReference = dynamicIdentifierMetadata.defaultColumnReference(valueName);
                        return columnReference == null || columnMetadata == null
                                ? ""
                                : columnMetadata.columnType(columnReference);
                    }

                    private Object defaultValue(String valueName) {
                        return dynamicIdentifierMetadata.defaultValue(valueName);
                    }

                    private Map<String, Object> defaultValues() {
                        return dynamicIdentifierMetadata.defaultValues();
                    }

                    private Set<String> dynamicIdentifierNames() {
                        return dynamicIdentifierMetadata.dynamicIdentifierNames();
                    }

                    private Set<String> collectionParameterNames() {
                        return dynamicIdentifierMetadata.collectionParameterNames();
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
                                || !dynamicIdentifierMetadata.collectionColumnReferences.isEmpty();
                    }

                    private boolean generatedKeyProperty(String valueName) {
                        String normalized = DynamicIdentifierMetadata.normalizeMetadataName(valueName);
                        return generatedKeyProperties.stream()
                                .map(DynamicIdentifierMetadata::normalizeMetadataName)
                                .anyMatch(normalized::equals);
                    }

                    private String parameterExpressionName(int index, String fallbackName) {
                        if (!isSyntheticParameterName(fallbackName)) {
                            return fallbackName;
                        }
                        return dynamicIdentifierMetadata.valueExpressionName(index, fallbackName);
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
                    private final Map<String, Map<String, Object>> collectionElementDefaults = new LinkedHashMap<>();
                    private final Map<String, Object> collectionSqlFragmentDefaults = new LinkedHashMap<>();
                    private final Map<String, Object> collectionScalarDefaults = new LinkedHashMap<>();
                    private final Map<String, ColumnReference> collectionColumnReferences = new LinkedHashMap<>();
                    private final Map<String, ColumnReference> defaultColumnReferences = new LinkedHashMap<>();
                    private final Map<String, Object> defaultValues = new LinkedHashMap<>();
                    private final Map<String, Object> namedDefaultValues = new LinkedHashMap<>();
                    private final Set<String> valueExpressionNames = new LinkedHashSet<>();

                    private void addDynamicIdentifierName(String valueName) {
                        String normalized = normalizeMetadataName(valueName);
                        if (!normalized.isBlank()) {
                            dynamicIdentifierNames.add(normalized);
                            namedDynamicIdentifierNames.add(valueName);
                        }
                    }

                    private void addCollectionParameterName(String collectionName) {
                        addCollectionParameterName(collectionName, false);
                    }

                    private void addCollectionParameterName(String collectionName, boolean force) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (normalizedCollectionName.isBlank()
                                || (!force && !DEFAULT_COLLECTION_PARAMETER_NAMES.contains(normalizedCollectionName))) {
                            return;
                        }
                        collectionParameterNames.add(normalizedCollectionName);
                        namedCollectionParameterNames.add(collectionName);
                    }

                    private void addNonEmptyCollectionParameterName(String collectionName) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (!normalizedCollectionName.isBlank()) {
                            nonEmptyCollectionParameterNames.add(normalizedCollectionName);
                        }
                    }

                    private void addCollectionDefault(String collectionName, String propertyName, Object value) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (normalizedCollectionName.isBlank() || propertyName == null || propertyName.isBlank()) {
                            return;
                        }
                        addCollectionParameterName(collectionName, true);
                        collectionElementDefaults
                                .computeIfAbsent(normalizedCollectionName, ignored -> new LinkedHashMap<>())
                                .putIfAbsent(propertyName, value);
                    }

                    private void addCollectionSqlFragmentDefault(String collectionName, Object value) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (!normalizedCollectionName.isBlank()) {
                            addCollectionParameterName(collectionName, true);
                            collectionSqlFragmentDefaults.putIfAbsent(normalizedCollectionName, value);
                        }
                    }

                    private void addCollectionScalarDefault(String collectionName, Object value) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (!normalizedCollectionName.isBlank()) {
                            addCollectionParameterName(collectionName, true);
                            collectionScalarDefaults.putIfAbsent(normalizedCollectionName, value);
                        }
                    }

                    private void addCollectionColumnReference(String collectionName, ColumnReference columnReference) {
                        String normalizedCollectionName = normalizeMetadataName(collectionName);
                        if (!normalizedCollectionName.isBlank() && columnReference != null && !columnReference.columnName().isBlank()) {
                            addCollectionParameterName(collectionName, true);
                            collectionColumnReferences.putIfAbsent(normalizedCollectionName, columnReference);
                        }
                    }

                    private void addDefaultColumnReference(String propertyName, ColumnReference columnReference) {
                        String normalizedPropertyName = normalizeMetadataName(propertyName);
                        if (!normalizedPropertyName.isBlank() && columnReference != null && !columnReference.columnName().isBlank()) {
                            defaultColumnReferences.putIfAbsent(normalizedPropertyName, columnReference);
                        }
                    }

                    private void addDefaultValue(String propertyName, Object value) {
                        String normalizedPropertyName = normalizeMetadataName(propertyName);
                        if (!normalizedPropertyName.isBlank()) {
                            defaultValues.putIfAbsent(normalizedPropertyName, value);
                            namedDefaultValues.putIfAbsent(propertyName, value);
                        }
                    }

                    private void addValueExpressionName(String propertyName) {
                        String normalizedPropertyName = normalizeMetadataName(propertyName);
                        if (!normalizedPropertyName.isBlank()) {
                            valueExpressionNames.add(propertyName);
                        }
                    }

                    private boolean hasCollectionDefault(String collectionName) {
                        return collectionElementDefaults.containsKey(normalizeMetadataName(collectionName));
                    }

                    private boolean dynamicIdentifierParameter(String valueName) {
                        return dynamicIdentifierNames.contains(normalizeMetadataName(valueName));
                    }

                    private boolean nonEmptyCollectionParameter(String valueName) {
                        return containsNameOrSuffix(nonEmptyCollectionParameterNames, valueName);
                    }

                    private Map<String, Object> collectionElementDefault(String valueName) {
                        Map<String, Object> defaults = valueByNameOrSuffix(collectionElementDefaults, valueName);
                        return defaults == null ? Map.of() : defaults;
                    }

                    private Object collectionSqlFragmentDefault(String valueName) {
                        return valueByNameOrSuffix(collectionSqlFragmentDefaults, valueName);
                    }

                    private Object collectionScalarDefault(String valueName) {
                        return valueByNameOrSuffix(collectionScalarDefaults, valueName);
                    }

                    private ColumnReference collectionColumnReference(String valueName) {
                        return valueByNameOrSuffix(collectionColumnReferences, valueName);
                    }

                    private ColumnReference defaultColumnReference(String valueName) {
                        return valueByNameOrSuffix(defaultColumnReferences, valueName);
                    }

                    private Object defaultValue(String valueName) {
                        return valueByNameOrSuffix(defaultValues, valueName);
                    }

                    private Map<String, Object> defaultValues() {
                        return Map.copyOf(namedDefaultValues);
                    }

                    private Set<String> dynamicIdentifierNames() {
                        return Set.copyOf(namedDynamicIdentifierNames);
                    }

                    private Set<String> collectionParameterNames() {
                        return Set.copyOf(namedCollectionParameterNames);
                    }

                    private String valueExpressionName(int index, String fallbackName) {
                        if (index < 0 || index >= valueExpressionNames.size()) {
                            return fallbackName;
                        }
                        return new ArrayList<>(valueExpressionNames).get(index);
                    }

                    private static boolean containsNameOrSuffix(Set<String> values, String valueName) {
                        String normalized = normalizeMetadataName(valueName);
                        if (normalized.isBlank()) {
                            return false;
                        }
                        if (values.contains(normalized)) {
                            return true;
                        }
                        return values.stream().anyMatch(value -> value.endsWith(normalized) || normalized.endsWith(value));
                    }

                    private static <T> T valueByNameOrSuffix(Map<String, T> values, String valueName) {
                        String normalized = normalizeMetadataName(valueName);
                        if (normalized.isBlank()) {
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

                    private ParameterResolution(boolean resolved, String source, Object[] args, String message, String label) {
                        this.resolved = resolved;
                        this.source = source;
                        this.args = args;
                        this.message = message;
                        this.label = label == null ? "" : label;
                    }

                    static ParameterResolution resolved(String source, Object[] args) {
                        return resolved(source, args, "");
                    }

                    static ParameterResolution resolved(String source, Object[] args, String label) {
                        return new ParameterResolution(true, source, args, "", label);
                    }

                    static ParameterResolution unresolved(String source, String message) {
                        return new ParameterResolution(false, source, new Object[0], message, "");
                    }

                    private String recordKey(String mapperKey) {
                        return label.isBlank() ? mapperKey : mapperKey + " [" + label + "]";
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

                    private ValidationRecord(String status, String key, String parameterSource, String message) {
                        this(status, key, parameterSource, "", message);
                    }

                    private ValidationRecord(
                            String status,
                            String key,
                            String parameterSource,
                            String parameterSummary,
                            String message
                    ) {
                        this.status = status;
                        this.key = key;
                        this.parameterSource = parameterSource;
                        this.parameterSummary = parameterSummary == null ? "" : parameterSummary;
                        this.message = message;
                    }

                    static ValidationRecord passed(String key, String parameterSource, String message) {
                        return new ValidationRecord("PASSED", key, parameterSource, message);
                    }

                    static ValidationRecord passed(String key, String parameterSource, String parameterSummary, String message) {
                        return new ValidationRecord("PASSED", key, parameterSource, parameterSummary, message);
                    }

                    static ValidationRecord failed(String key, String parameterSource, String message) {
                        return new ValidationRecord("FAILED", key, parameterSource, message);
                    }

                    static ValidationRecord failed(String key, String parameterSource, String parameterSummary, String message) {
                        return new ValidationRecord("FAILED", key, parameterSource, parameterSummary, message);
                    }

                    static ValidationRecord skipped(String key, String parameterSource, String message) {
                        return new ValidationRecord("SKIPPED", key, parameterSource, message);
                    }

                    static ValidationRecord skipped(String key, String parameterSource, String parameterSummary, String message) {
                        return new ValidationRecord("SKIPPED", key, parameterSource, parameterSummary, message);
                    }
                }

                private record SchemaAttempt(String schema, ValidationRecord record) {
                }

                private static final class MapperInvocationException extends RuntimeException {
                    private MapperInvocationException(Throwable cause) {
                        super(cause);
                    }
                }
            }
            """);
}
