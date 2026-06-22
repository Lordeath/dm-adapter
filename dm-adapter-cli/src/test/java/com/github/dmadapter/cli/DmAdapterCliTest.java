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
                .contains("com.example.UserMapper.updateByLevel");
        assertThat(Files.readString(test))
                .contains("package com.example;")
                .contains("@Tag(\"dm-sql-validation\")")
                .contains("@EnabledIfEnvironmentVariable")
                .contains("SqlSessionFactory")
                .contains("UnpooledDataSource")
                .contains("[dm-sql-validation]")
                .contains("LOG_TIMESTAMP_FORMATTER")
                .contains("logProgress(index, total, record")
                .contains("Failure Categories")
                .contains("Failure Patterns")
                .contains("Schema Object Hotspots")
                .contains("Missing Tables/Views")
                .contains("Missing Columns")
                .contains("schemaIssueCounts")
                .contains("schemaObjectHotspots")
                .contains("missingTablesOrViews")
                .contains("Suggested Next Actions")
                .contains("Usage Filter")
                .contains("usageFilter")
                .contains("MapperUsageIndex")
                .contains("scanClassFile")
                .contains("MemberRef")
                .contains("No project class references mapper method")
                .contains("parameterSource")
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
                .contains("DynamicIdentifierMetadata")
                .contains("collectionElementDefault")
                .contains("dynamicIdentifierParameter")
                .contains("recordKey(mapperMethod.key())")
                .contains("parameterName")
                .contains("defaultString")
                .contains("DYNAMIC_IDENTIFIER_PARAMETER")
                .contains("RAW_SQL_PARAMETER")
                .contains("MYSQL_UPDATE_JOIN")
                .contains("INSERT_IGNORE")
                .contains("MYSQL_GROUP_CONCAT")
                .contains("MYSQL_DATE_ADD_INTERVAL")
                .contains("MYSQL_CONVERT_UNSIGNED")
                .contains("MYSQL_JSON_TABLE_JOIN_WITHOUT_ON")
                .contains("hasJsonTableJoinWithoutCondition")
                .contains("isSchemaObjectFailure")
                .contains("matcher.start(\"join\")")
                .contains("MYSQL_JSON_SQL")
                .contains("BROKEN_DYNAMIC_SQL_OR_ARGS")
                .contains("optionalSecret(resolvePlaceholders(config.datasource.password), \"datasource.password\")")
                .contains("references an unresolved placeholder")
                .contains("set schema")
                .contains("quotedIdentifier(config.schema)")
                .doesNotContain("@SpringBootTest")
                .doesNotContain("@ActiveProfiles")
                .doesNotContain("PlatformTransactionManager")
                .doesNotContain("RabbitTemplate");
    }

    @Test
    void generateValidationTestUpdatesExistingGeneratedTestButKeepsExistingConfig() throws Exception {
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
        assertThat(Files.readString(config)).isEqualTo("schema: \"custom\"\n");
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
