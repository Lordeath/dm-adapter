package com.github.dmadapter.mybatis;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.MapperMigrationResult;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import com.github.dmadapter.sql.MySqlToDmSqlConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapperMigratorTest {
    @TempDir
    Path tempDir;

    @Test
    void scansMapperXmlAndSkipsMapperDmDirectory() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/UserMapper.xml", "select * from user");
        writeMapper("src/main/resources/mapper-dm/UserMapper.xml", "select * from copied");

        List<MapperXmlFile> files = new MapperXmlScanner().scan(tempDir);

        assertThat(files)
                .extracting(MapperXmlFile::path)
                .containsExactly(mapper.toAbsolutePath().normalize().toString());
    }

    @Test
    void malformedRewriteIsRejectedBeforeExistingMapperDmIsOverwritten() throws Exception {
        Path mapperDm = writeFile("src/main/resources/mapper-dm/UserMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUser">select 1</select>
                </mapper>
                """);
        String original = Files.readString(mapperDm);
        String malformed = original.replace(
                "select 1",
                "<when test=\"enabled == true\">select 1"
        );

        assertThatThrownBy(() -> new MapperXmlRewriter().writeWellFormedXml(mapperDm, malformed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to write malformed mapper XML after rewrite")
                .hasMessageContaining(mapperDm.toString());
        assertThat(Files.readString(mapperDm)).isEqualTo(original);
    }

    @Test
    void scansMapperXmlFromApplicationPropertiesMapperLocations() throws Exception {
        Path selectedMapper = writeMapper("src/main/resources/mapper/selected/UserMapper.xml", "select * from user");
        writeMapper("src/main/resources/mapper/other/OtherMapper.xml", "select * from other");
        writeFile("config/application.properties", """
                mybatis.mapperLocations=classpath*:mapper/selected/**/*.xml
                """);

        List<MapperXmlFile> files = new MapperXmlScanner().scan(tempDir);

        assertThat(files)
                .extracting(MapperXmlFile::path)
                .containsExactly(selectedMapper.toAbsolutePath().normalize().toString());
    }

    @Test
    void fallsBackToActualMapperXmlWhenConfiguredLocationsMatchNothing() throws Exception {
        Path mysqlMapper = writeMapper(
                "src/main/resources/mapper/mysql/EquipAddressMapper.xml",
                "select * from equip_address"
        );
        Path sqlServerMapper = writeMapper(
                "src/main/resources/mapper/sqlserver/ActivityMapper.xml",
                "select top 1 * from Register_Member with(nolock) order by createDate desc"
        );
        writeFile("src/main/resources/application.properties", """
                mybatis.mapperLocations=classpath:/mapper/*.xml
                """);

        List<MapperXmlFile> files = new MapperXmlScanner().scan(tempDir);

        assertThat(files)
                .extracting(MapperXmlFile::path)
                .containsExactly(
                        mysqlMapper.toAbsolutePath().normalize().toString(),
                        sqlServerMapper.toAbsolutePath().normalize().toString()
                );

        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                files,
                List.of()
        );
        new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path convertedSqlServerMapper =
                tempDir.resolve("src/main/resources/mapper-dm/sqlserver/ActivityMapper.xml");
        assertThat(Files.readString(convertedSqlServerMapper))
                .contains("select * from Register_Member")
                .contains("order by createDate desc FETCH FIRST 1 ROWS ONLY")
                .doesNotContainIgnoringCase("with(nolock)");
        assertThat(Files.exists(
                tempDir.resolve("src/main/resources/mapper-dm/mysql/EquipAddressMapper.xml")
        )).isTrue();
    }

    @Test
    void scansMapperXmlFromYamlMapperLocations() throws Exception {
        Path mapper = writeMapper("src/main/resources/sqlmap/UserMapper.xml", "select * from user");
        writeMapper("src/main/resources/mapper/OtherMapper.xml", "select * from other");
        writeFile("src/main/resources/application.yml", """
                mybatis:
                  mapper-locations:
                    - classpath*:sqlmap/*.xml
                """);

        List<MapperXmlFile> files = new MapperXmlScanner().scan(tempDir);

        assertThat(files)
                .extracting(MapperXmlFile::path)
                .containsExactly(mapper.toAbsolutePath().normalize().toString());
    }

    @Test
    void scansClasspathMapperLocationsAcrossMavenModulesAndMigratesNextToSourceModule() throws Exception {
        writeFile("sample-system-rest/src/main/resources/application.properties", """
                mybatis.mapperLocations=classpath*:/mapper/*.xml
                """);
        Path mapper = writeMapper("sample-system-base/src/main/resources/mapper/UserMapper.xml", "select NOW() from dual");
        writeMapper("sample-system-base/src/main/resources/sqlmap/OtherMapper.xml", "select * from other");

        List<MapperXmlFile> files = new MapperXmlScanner().scan(tempDir);

        assertThat(files)
                .extracting(MapperXmlFile::path)
                .containsExactly(mapper.toAbsolutePath().normalize().toString());
        assertThat(files.get(0).resourcesRoot())
                .isEqualTo(tempDir.resolve("sample-system-base/src/main/resources").toAbsolutePath().normalize().toString());

        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                files,
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path copied = tempDir.resolve("sample-system-base/src/main/resources/mapper-dm/UserMapper.xml");
        assertThat(Files.exists(copied)).isTrue();
        assertThat(Files.readString(copied)).contains("NOW()");
        assertThat(Files.exists(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"))).isFalse();
        assertThat(result.automaticConversions()).isEmpty();
    }

    @Test
    void dryRunReportsCopyAndSqlChangesWithoutWritingTarget() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/UserMapper.xml", """
                select "ACTIVE" as status from user
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );
        AdapterContext context = AdapterContext.builder(tempDir).dryRun(true).build();

        MapperMigrationResult result = new MapperMigrator().migrate(scanResult, context, new MySqlToDmSqlConverter());

        assertThat(result.fileChanges()).hasSize(1);
        assertThat(result.fileChanges().get(0).applied()).isFalse();
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).convertedSql()).contains("'ACTIVE'");
        assertThat(Files.exists(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"))).isFalse();
    }

    @Test
    void reportsProgressDuringMapperMigration() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/UserMapper.xml", "select NOW() from dual");
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );
        List<String> progressMessages = new ArrayList<>();

        new MapperMigrator(progressMessages::add).migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(true).build(),
                new MySqlToDmSqlConverter()
        );

        assertThat(progressMessages)
                .anySatisfy(message -> assertThat(message)
                        .contains("Mapper XML migration [1/1]")
                        .contains("mapper/UserMapper.xml"))
                .anySatisfy(message -> assertThat(message)
                        .contains("Mapper XML migration finished")
                        .contains("Files: 1"));
    }

    @Test
    void migrationCopiesMapperAndRewritesSafeSql() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/UserMapper.xml", "select NOW() from dual limit 5");
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );
        AdapterContext context = AdapterContext.builder(tempDir).dryRun(false).build();

        MapperMigrationResult result = new MapperMigrator().migrate(scanResult, context, new MySqlToDmSqlConverter());

        Path copied = tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml");
        assertThat(Files.exists(copied)).isTrue();
        assertThat(Files.readString(copied))
                .contains("<!DOCTYPE mapper")
                .contains("NOW()")
                .contains("limit 5")
                .doesNotContain("standalone=\"no\"");
        assertThat(result.automaticConversions()).isEmpty();
    }

    @Test
    void migrationReportsUnsafeIntegerArithmeticForManualReview() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/UserMapper.xml", "select '10'/4 from dual");
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.statementId()).isEqualTo("com.example.UserMapper.selectUsers");
                    assertThat(item.reason()).contains("整数算术表达式风险");
                    assertThat(item.originalSql()).contains("'10'/4");
                });
    }

    @Test
    void migrationRewritesStaticOnDuplicateKeyUpdateToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="updateExtend">
                        INSERT INTO ns_organization_and_employees_extend (foreignerKeyId, key)
                        VALUES (#{foreignerKeyId}, #{key})
                        ON DUPLICATE KEY UPDATE key = VALUES(key)
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of("com.example.UserMapper.updateExtend", List.of("foreignerKeyId"))
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO ns_organization_and_employees_extend t")
                .contains("ON (t.foreignerKeyId = s.foreignerKeyId)")
                .contains("WHEN MATCHED THEN UPDATE SET t.\"key\" = s.\"key\"")
                .doesNotContain("ON DUPLICATE KEY UPDATE");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void migrationRewritesSingleAndBatchUpsertSelfIncrementToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ReminderMapper">
                    <insert id="insertOrIncrement">
                        INSERT INTO sample_reminder (
                            house_id, customer_id, remind_month, remind_count, update_time
                        )
                        VALUES (
                            #{houseId}, #{customerId}, #{remindMonth}, 1, NOW()
                        )
                        ON DUPLICATE KEY UPDATE
                            remind_count = remind_count + 1,
                            update_time = NOW()
                    </insert>
                    <insert id="batchInsertOrIncrement">
                        INSERT INTO sample_reminder (
                            house_id, customer_id, remind_month, remind_count, update_time
                        )
                        VALUES
                        <foreach collection="list" item="item" separator=",">
                            (
                                #{item.houseId}, #{item.customerId}, #{item.remindMonth}, 1, NOW()
                            )
                        </foreach>
                        ON DUPLICATE KEY UPDATE
                            remind_count = remind_count + 1,
                            update_time = NOW()
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ReminderMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ReminderMapper.xml")),
                List.of()
        );
        List<String> keys = List.of("house_id", "customer_id", "remind_month");

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of("sample_reminder", keys),
                        Map.of(
                                "com.example.ReminderMapper.insertOrIncrement", keys,
                                "com.example.ReminderMapper.batchInsertOrIncrement", keys
                        )
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ReminderMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO sample_reminder t")
                .contains("t.remind_count = t.remind_count + 1")
                .contains("t.update_time = NOW()")
                .contains("<foreach collection=\"list\" item=\"item\" separator=\";\">")
                .doesNotContainIgnoringCase("ON DUPLICATE KEY UPDATE");
        assertThat(result.automaticConversions()).hasSize(2);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void migrationRewritesForeachOnDuplicateKeyUpdateWithBacktickKeywordColumnsToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.AttendanceRecordMapper">
                    <insert id="batchInsertOrUpdateAttendanceRecord" parameterType="java.util.List">
                        <foreach collection="list" item="record" separator=";">
                            INSERT INTO ns_attendance_record (id, `state`, createTime)
                            VALUES (#{record.id}, #{record.state}, now())
                            ON DUPLICATE KEY UPDATE
                            id = VALUES(id)
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/AttendanceRecordMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/AttendanceRecordMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of("com.example.AttendanceRecordMapper.batchInsertOrUpdateAttendanceRecord", List.of("id"))
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/AttendanceRecordMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO ns_attendance_record t")
                .contains("SELECT #{record.id} AS id, #{record.state} AS \"state\", now() AS createTime FROM dual")
                .contains("WHEN NOT MATCHED THEN INSERT (id, \"state\", createTime) VALUES (s.id, s.\"state\", s.createTime)")
                .doesNotContain("ON DUPLICATE KEY UPDATE")
                .doesNotContain("AS 'state'")
                .doesNotContain("s.'state'");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void migrationReportsOriginalUpsertWithoutReachableConflictKey() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ScoreRuleMapper">
                    <insert id="insertOrUpdateBatch">
                        INSERT INTO sample_score_rule (rule_id, score)
                        VALUES
                        <foreach collection="entities" item="entity" separator=",">
                            (#{entity.ruleId}, #{entity.score})
                        </foreach>
                        ON DUPLICATE KEY UPDATE score = VALUES(score)
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ScoreRuleMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ScoreRuleMapper.xml")),
                List.of()
        );
        String statementKey = "com.example.ScoreRuleMapper.insertOrUpdateBatch";
        SqlRewriteConfig rewriteConfig = new SqlRewriteConfig(
                Map.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(statementKey, SqlRewriteConfig.ORIGINAL_SQL_NO_USABLE_CONFLICT_KEY)
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                rewriteConfig
        );

        assertThat(result.manualReviewItems())
                .singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("Original ON DUPLICATE KEY UPDATE")
                        .contains("UPDATE branch cannot be reached")
                        .contains("do not guess keyColumns")
                        .doesNotContain("requires configured keyColumns"));
    }

    @Test
    void migrationWrapsConfiguredIdentityInsertTableWithReplaceNull() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.AreaClassMapper">
                    <insert id="insertIdBatch" parameterType="java.util.List">
                        insert into ns_equip_area_class
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            ID,
                            areaClassName
                        </trim>
                        values
                        <foreach collection="list" item="item" separator=",">
                            (#{item.id}, #{item.areaClassName})
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/AreaClassMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/AreaClassMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of("ns_equip_area_class")
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/AreaClassMapper.xml"));
        assertThat(rewritten)
                .contains("insert into ns_equip_area_class")
                .contains("<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">")
                .contains("<foreach collection=\"list\" item=\"item\" separator=\",\">")
                .contains("SET IDENTITY_INSERT ns_equip_area_class ON WITH REPLACE NULL;")
                .contains("SET IDENTITY_INSERT ns_equip_area_class OFF")
                .doesNotContain("BEGIN");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_IDENTITY_INSERT_REPLACE_NULL_RULE);
    }

    @Test
    void migrationMakesGeneratedBatchKeyConditionalForDamengIdentity() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.SiKuBankFlowMapper">
                    <insert id="insertBatch" parameterType="java.util.List"
                            useGeneratedKeys="true" keyProperty="id">
                        insert into ns_si_ku_bank_flow
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            `id`,
                            `enterpriseId`,
                            `organizationId`
                        </trim>
                        values
                        <foreach collection="list" item="item" index="index" separator=",">
                            (
                            #{item.id, jdbcType=BIGINT},
                            #{item.enterpriseId, jdbcType=BIGINT},
                            #{item.organizationId, jdbcType=BIGINT}
                            )
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/SiKuBankFlowMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/SiKuBankFlowMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/SiKuBankFlowMapper.xml"));
        String generatedKeyFlag = "_dmAdapterHasExplicitId";
        assertThat(rewritten)
                .contains("<bind name=\"" + generatedKeyFlag + "\"")
                .contains("list.{? #this.id != null}.size() > 0")
                .contains("<if test=\"" + generatedKeyFlag + "\">")
                .contains("SET IDENTITY_INSERT ns_si_ku_bank_flow ON WITH REPLACE NULL;")
                .contains("SET IDENTITY_INSERT ns_si_ku_bank_flow OFF")
                .contains("`id`,")
                .contains("#{item.id, jdbcType=BIGINT},")
                .contains("useGeneratedKeys=\"true\" keyProperty=\"id\"")
                .contains("`enterpriseId`,")
                .contains("#{item.enterpriseId, jdbcType=BIGINT}")
                .contains("<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(
                        MapperXmlRewriter.MYBATIS_BATCH_GENERATED_KEY_CONDITIONAL_RULE,
                        MapperXmlRewriter.MYBATIS_IDENTITY_INSERT_REPLACE_NULL_RULE
                );
    }

    @Test
    void generatedBatchIdentityRewriteDoesNotDuplicateLastTupleValue() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ProcessDefMapper">
                    <insert id="insertBatch" parameterType="java.util.List"
                            useGeneratedKeys="true" keyProperty="id">
                        insert into ns_process_def
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            `id`,
                            `enterprise_id`,
                            `organization_id`,
                            `precinct_id`,
                            `name`,
                            `business_type`,
                            `batch_number_prefix`,
                            `process_channel`,
                            `request_structure`,
                            `process_def_status`,
                            `description`,
                            `delete_flag`,
                            `create_user_id`,
                            `create_user_name`,
                            `create_date_time`,
                            `update_user_id`,
                            `update_user_name`,
                            `update_date_time`
                        </trim>
                        values
                        <foreach collection="list" item="item" index="index" separator=",">
                            (
                            #{item.id, jdbcType=BIGINT} ,
                            #{item.enterpriseId, jdbcType=BIGINT} ,
                            #{item.organizationId, jdbcType=BIGINT} ,
                            #{item.precinctId, jdbcType=BIGINT} ,
                            #{item.name, jdbcType=VARCHAR} ,
                            #{item.businessType, jdbcType=INTEGER} ,
                            #{item.batchNumberPrefix, jdbcType=VARCHAR} ,
                            #{item.processChannel, jdbcType=INTEGER} ,
                            #{item.requestStructure, jdbcType=LONGVARCHAR} ,
                            #{item.processDefStatus, jdbcType=INTEGER} ,
                            #{item.description, jdbcType=VARCHAR} ,
                            #{item.deleteFlag, jdbcType=INTEGER} ,
                            #{item.createUserId, jdbcType=BIGINT} ,
                            #{item.createUserName, jdbcType=VARCHAR} ,
                            #{item.createDateTime, jdbcType=TIMESTAMP} ,
                            #{item.updateUserId, jdbcType=BIGINT} ,
                            #{item.updateUserName, jdbcType=VARCHAR} ,
                            #{item.updateDateTime, jdbcType=TIMESTAMP}
                            )
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ProcessDefMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ProcessDefMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ProcessDefMapper.xml"));
        assertThat(rewritten)
                .contains("SET IDENTITY_INSERT ns_process_def ON WITH REPLACE NULL;")
                .containsOnlyOnce("#{item.updateDateTime, jdbcType=TIMESTAMP}")
                .doesNotContain("""
                        #{item.updateDateTime, jdbcType=TIMESTAMP},
                        #{item.updateDateTime, jdbcType=TIMESTAMP}
                        """);
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(
                        MapperXmlRewriter.MYBATIS_BATCH_GENERATED_KEY_CONDITIONAL_RULE,
                        MapperXmlRewriter.MYBATIS_IDENTITY_INSERT_REPLACE_NULL_RULE
                );
    }

    @Test
    void migrationDoesNotWrapGeneratedKeyInsertForConfiguredIdentityInsertTable() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.BankAccountMapper">
                    <insert id="insertSelective" parameterType="com.example.BankAccount"
                            useGeneratedKeys="true" keyProperty="ownerBankAccountId">
                        insert into owner_customer_bank_account
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="ownerId != null">
                                owner_id,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="ownerId != null">
                                #{ownerId,jdbcType=BIGINT},
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/BankAccountMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/BankAccountMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of("owner_customer_bank_account")
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/BankAccountMapper.xml"));
        assertThat(rewritten)
                .contains("<trim prefix=\"values (\" suffix=\")\" suffixOverrides=\",\">")
                .contains("useGeneratedKeys=\"true\" keyProperty=\"ownerBankAccountId\"")
                .doesNotContain("owner_bank_account_id")
                .doesNotContain("SET IDENTITY_INSERT")
                .doesNotContain("BEGIN");
        assertThat(result.automaticConversions()).isEmpty();
    }

    @Test
    void migrationWrapsGeneratedKeyInsertWhenIdentityColumnIsExplicit() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.IdentityItemMapper">
                    <insert id="insert" parameterType="com.example.IdentityItem"
                            useGeneratedKeys="true" keyProperty="id">
                        insert into identity_item (id, item_name)
                        values (#{id}, #{itemName})
                    </insert>
                    <insert id="insertSelective" parameterType="com.example.IdentityItem"
                            useGeneratedKeys="true" keyProperty="id" keyColumn="id">
                        insert into identity_item
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="id != null">
                                id,
                            </if>
                            <if test="itemName != null">
                                item_name,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="id != null">
                                #{id},
                            </if>
                            <if test="itemName != null">
                                #{itemName},
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/IdentityItemMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/IdentityItemMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of("identity_item")
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/IdentityItemMapper.xml"));
        assertThat(rewritten)
                .contains("useGeneratedKeys=\"true\" keyProperty=\"id\"")
                .contains("SET IDENTITY_INSERT identity_item ON WITH REPLACE NULL;")
                .contains("SET IDENTITY_INSERT identity_item OFF");
        assertThat(result.automaticConversions()).hasSize(2);
        assertThat(result.automaticConversions())
                .allSatisfy(change -> assertThat(change.appliedRules())
                        .contains(MapperXmlRewriter.MYBATIS_IDENTITY_INSERT_REPLACE_NULL_RULE));
    }

    @Test
    void migrationWrapsStaticInsertForConfiguredIdentityInsertTable() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ThirdAddHouseInfoMapper">
                    <insert id="insertThirdHouseEntity" parameterType="com.example.ThirdHouseInfo">
                        insert zj_add_house_info (id, enterprise_id, roomName)
                        values (#{id}, #{enterpriseId}, #{roomName})
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ThirdAddHouseInfoMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ThirdAddHouseInfoMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of("zj_add_house_info")
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ThirdAddHouseInfoMapper.xml"));
        assertThat(rewritten)
                .contains("insert zj_add_house_info (id, enterprise_id, roomName)")
                .contains("SET IDENTITY_INSERT zj_add_house_info ON WITH REPLACE NULL;")
                .contains("SET IDENTITY_INSERT zj_add_house_info OFF")
                .doesNotContain("BEGIN");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_IDENTITY_INSERT_REPLACE_NULL_RULE);
    }

    @Test
    void migrationRewritesBatchInsertIgnoreWithTrimColumnListToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.RolePermMapper">
                    <insert id="insertBatch" parameterType="java.util.List">
                        insert ignore into ns_core_role_perm
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            ENTERPRISE_ID,
                            ORGANIZATION_ID,
                            ENABLED,
                            PERID,
                            ROLEID,
                            TYPE,
                        </trim>
                        values
                        <foreach collection="list" item="item" index="index" separator=",">
                        (
                            #{item.enterpriseId, jdbcType=BIGINT},
                            #{item.organizationId, jdbcType=BIGINT},
                            #{item.enabled, jdbcType=VARCHAR},
                            #{item.perid, jdbcType=VARCHAR},
                            #{item.roleid, jdbcType=VARCHAR},
                            #{item.type, jdbcType=VARCHAR}
                        )
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/RolePermMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/RolePermMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(
                                "com.example.RolePermMapper.insertBatch",
                                List.of("ENTERPRISE_ID", "ORGANIZATION_ID", "PERID", "ROLEID", "TYPE")
                        )
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/RolePermMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO ns_core_role_perm t")
                .contains("ON (t.ENTERPRISE_ID = s.ENTERPRISE_ID AND t.ORGANIZATION_ID = s.ORGANIZATION_ID")
                .contains("t.\"TYPE\" = s.\"TYPE\"")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .doesNotContain("insert ignore");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_INSERT_IGNORE_TO_DM_MERGE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void migrationDropsInsertIgnoreWhenOnlyConflictKeyIsGenerated() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.BankFileMapper">
                    <insert id="insert" useGeneratedKeys="true" keyProperty="id">
                        insert ignore into ns_bank_file
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="fileId != null">fileId,</if>
                            <if test="fileName != null">fileName,</if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="fileId != null">#{fileId},</if>
                            <if test="fileName != null">#{fileName},</if>
                        </trim>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/BankFileMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/BankFileMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(
                                "com.example.BankFileMapper.insert",
                                "INSERT_IGNORE_AS_PLAIN_INSERT"
                        )
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/BankFileMapper.xml"));
        assertThat(rewritten)
                .contains("insert into ns_bank_file")
                .doesNotContain("insert ignore")
                .doesNotContain("MERGE INTO");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_INSERT_IGNORE_AS_PLAIN_INSERT_RULE);
    }

    @Test
    void migrationRewritesInsertIgnoreSelectWithMultipleConflictKeyGroups() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.JobMapper">
                    <insert id="insert">
                        insert ignore into sample_job_config(
                            logical_name, version_no, job_name, main_class
                        )
                        select #{logicalName}, #{version}, #{jobName}, #{mainClass};
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/JobMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/JobMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Map.of(
                                "com.example.JobMapper.insert",
                                List.of(
                                        List.of("logical_name", "version_no"),
                                        List.of("job_name")
                                )
                        )
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/JobMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO sample_job_config t")
                .contains("(t.logical_name = s.logical_name"
                        + " AND t.version_no = s.version_no)"
                        + " OR (t.job_name = s.job_name)")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .doesNotContainIgnoringCase("insert ignore")
                .doesNotContain("WHEN MATCHED");
        assertThat(result.manualReviewItems()).isEmpty();
        assertThat(result.automaticConversions()).singleElement().satisfies(change ->
                assertThat(change.appliedRules())
                        .contains(MySqlToDmSqlConverter.MYSQL_INSERT_IGNORE_TO_DM_MERGE_RULE));
    }

    @Test
    void migrationRewritesBatchOnDuplicateKeySelfAssignmentToInsertOnlyMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.BankFlowMapper">
                    <insert id="insertBatch">
                        insert into ns_bill_bank_flow
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            enterprise_id,
                            transaction_serial_number,
                            create_user_name
                        </trim>
                        values
                        <foreach collection="list" item="item" separator=",">
                            (
                            #{item.enterpriseId},
                            #{item.transactionSerialNumber},
                            #{item.createUserName}
                            )
                        </foreach>
                        on duplicate key update create_user_name = create_user_name
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/BankFlowMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/BankFlowMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(
                                "com.example.BankFlowMapper.insertBatch",
                                List.of("transaction_serial_number")
                        )
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/BankFlowMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO ns_bill_bank_flow t")
                .contains("ON (t.transaction_serial_number = s.transaction_serial_number)")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .doesNotContain("WHEN MATCHED")
                .doesNotContain("on duplicate key update");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
    }

    @Test
    void migrationRewritesBacktickBatchInsertIgnoreToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.PaymentExtMapper">
                    <insert id="insertSplitBatch">
                        INSERT ignore INTO `ns_payment_chargepayment_ext` (
                            `payment_id`,
                            `payBillCompany`,
                            `carry_voucher_status`
                        ) VALUES
                        <foreach collection="list" item="item" separator=",">
                        (
                            #{item.id,jdbcType=BIGINT},
                            #{item.payBillCompany,jdbcType=VARCHAR},
                            CAST(#{item.carryVoucherStatus,jdbcType=VARCHAR} AS INTEGER)
                        )
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/PaymentExtMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/PaymentExtMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of("com.example.PaymentExtMapper.insertSplitBatch", List.of("payment_id"))
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/PaymentExtMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO `ns_payment_chargepayment_ext` t")
                .contains("#{item.id,jdbcType=BIGINT} AS payment_id")
                .contains("CAST(#{item.carryVoucherStatus,jdbcType=VARCHAR} AS INTEGER) AS carry_voucher_status")
                .contains("ON (t.payment_id = s.payment_id)")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .doesNotContain("INSERT ignore");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_INSERT_IGNORE_TO_DM_MERGE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void migrationRewritesConditionalTrimInsertIgnoreToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserExtMapper">
                    <insert id="insertExt">
                        insert ignore into ${schemaName}.user_ext
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="id != null">
                                user_id,
                            </if>
                            <if test="state != null">
                                state,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="id != null">
                                #{id},
                            </if>
                            <if test="state != null">
                                #{state},
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserExtMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserExtMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of("com.example.UserExtMapper.insertExt", List.of("user_id"))
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserExtMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO ${schemaName}.user_ext t")
                .contains("#{id} AS user_id")
                .contains("#{state} AS \"state\"")
                .contains("ON (t.user_id = s.user_id)")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .contains("s.user_id")
                .doesNotContain("insert ignore");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_INSERT_IGNORE_TO_DM_MERGE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void migrationRewritesConditionalTrimOnDuplicateKeyUpdateToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.RecentlyUsedMapper">
                    <insert id="insertOrUpdate">
                        insert into ns_recently_used
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="enterpriseId != null">
                                `enterpriseId`,
                            </if>
                            <if test="organizationId != null">
                                `organizationId`,
                            </if>
                            <if test="agentType != null">
                                `agentType`,
                            </if>
                            <if test="useTime != null">
                                `useTime`,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="enterpriseId != null">
                                #{enterpriseId},
                            </if>
                            <if test="organizationId != null">
                                #{organizationId},
                            </if>
                            <if test="agentType != null">
                                #{agentType},
                            </if>
                            <if test="useTime != null">
                                #{useTime},
                            </if>
                        </trim>
                        ON DUPLICATE KEY UPDATE
                        <trim suffixOverrides=",">
                            <if test="useTime != null">
                                `useTime` = VALUES(`useTime`),
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/RecentlyUsedMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/RecentlyUsedMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(
                                "com.example.RecentlyUsedMapper.insertOrUpdate",
                                List.of("enterpriseId", "organizationId", "agentType")
                        )
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/RecentlyUsedMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO ns_recently_used t")
                .contains("ON (t.enterpriseId = s.enterpriseId AND t.organizationId = s.organizationId AND t.agentType = s.agentType)")
                .contains("WHEN MATCHED THEN UPDATE SET")
                .contains("t.useTime = s.useTime")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .doesNotContain("ON DUPLICATE KEY UPDATE");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MapperXmlRewriter.MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
    }

    @Test
    void migrationRewritesConditionalTrimOnDuplicateKeySelfAssignmentToInsertOnlyMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.PaidInAuditMapper">
                    <insert id="insert">
                        insert into ns_paid_in_audit
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="enterpriseId != null">
                                `enterpriseId`,
                            </if>
                            <if test="orderNo != null">
                                `orderNo`,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="enterpriseId != null">
                                #{enterpriseId},
                            </if>
                            <if test="orderNo != null">
                                #{orderNo},
                            </if>
                        </trim>
                        on duplicate key update enterpriseId = enterpriseId
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/PaidInAuditMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/PaidInAuditMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of("com.example.PaidInAuditMapper.insert", List.of("orderNo"))
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/PaidInAuditMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO ns_paid_in_audit t")
                .contains("ON (t.orderNo = s.orderNo)")
                .contains("WHEN NOT MATCHED THEN INSERT")
                .doesNotContain("WHEN MATCHED")
                .doesNotContain("on duplicate key update");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
    }

    @Test
    void migrationRenamesDamengReservedColumnNamesInMapperSql() throws Exception {
        Path mapper = writeMapper(
                "src/main/resources/mapper/UserMapper.xml",
                "select rowid, trxid, rownum from user where rowid = #{rowid} and trxid = #{trxid}"
        );
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path copied = tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml");
        assertThat(Files.readString(copied))
                .contains("select rowid_, trxid_, rownum_ from user where rowid_ = #{rowid} and trxid_ = #{trxid}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly("DAMENG_RESERVED_COLUMN_RENAME");
    }

    @Test
    void migrationRewritesDuplicateStatementIdsByOccurrenceAndPreservesRownumPseudoColumn() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.BpmCheckOpinionMapper">
                    <select id="getLastOpinionByProcId" resultType="map">
                        select * from bpm_check_opinion
                        where PROC_INST_ID_ = #{procId} and FORM_DATA_ is not null
                        order by complete_time_ desc limit 1 offset 0
                    </select>

                    <select id="getLastOpinionByProcId" databaseId="oracle" resultType="map">
                        select "OK" as marker from bpm_check_opinion
                        where PROC_INST_ID_ = #{procId} and FORM_DATA_ is not null
                        and ROWNUM = 1
                        order by complete_time_ desc
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/BpmCheckOpinionMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/BpmCheckOpinionMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/BpmCheckOpinionMapper.xml"));
        assertThat(rewritten)
                .contains("order by complete_time_ desc limit 1 offset 0")
                .contains("select 'OK' as marker from bpm_check_opinion")
                .contains("and ROWNUM = 1")
                .doesNotContain("ROWNUM_ = 1");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void rewritingPreservesMapperDoctypeAndUnchangedFormatting() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <resultMap id="UserResultMap" type="com.example.User">
                        <id column="id" property="id"/>
                    </resultMap>

                    <select id="selectUsers">
                        select NOW() from dual limit 5
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        assertThat(Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml")))
                .isEqualTo("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                        <mapper namespace="com.example.UserMapper">
                            <resultMap id="UserResultMap" type="com.example.User">
                                <id column="id" property="id"/>
                            </resultMap>

                            <select id="selectUsers">
                                select NOW() from dual limit 5
                            </select>
                        </mapper>
                        """);
    }

    @Test
    void dynamicSqlWithoutCompatibilityRiskDoesNotRequireManualReview() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/UserMapper.xml", """
                select * from user
                <where>
                  <if test="name != null">name = #{name}</if>
                </where>
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(true).build(),
                new MySqlToDmSqlConverter()
        );

        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void decimalRatioWithScalarSubqueryDoesNotRequireManualReview() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/RatioMapper.xml", """
                SELECT ROUND(COUNT(DISTINCT companyId) * 100.0 /
                    (SELECT COUNT(DISTINCT companyId)
                     FROM member_charge
                     WHERE feeYear = #{feeYear}), 2) AS ratio
                FROM member_charge
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/RatioMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(true).build(),
                new MySqlToDmSqlConverter()
        );

        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicOnDuplicateKeyUpdateIsNotRewrittenToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="insertBatch">
                        insert into sample_user(id, name)
                        <foreach collection="list" item="item" separator=",">
                            (#{item.id}, #{item.name})
                        </foreach>
                        on duplicate key update name = values(name)
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("on duplicate key update name = values(name)")
                .doesNotContain("MERGE INTO");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason())
                .contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void batchOnDuplicateKeyUpdateUsesConstantAssignmentForMissingKeyColumn() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.NsMeterUseDayMapper">
                    <insert id="upsertBatch">
                        insert into ns_meter_use_day
                        (bm, `dataTime`, pointId, `usage`)
                        values
                        <foreach collection="list" item="item" separator=",">
                            (#{item.bm}, #{item.dataTime}, #{item.pointId}, #{item.usage})
                        </foreach>
                        on duplicate key update
                            bm = values(bm),
                            `usage` = values(`usage`),
                            isDelete = 0
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/NsMeterUseDayMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/NsMeterUseDayMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of(
                                "com.example.NsMeterUseDayMapper.upsertBatch",
                                List.of("pointId", "dataTime", "isDelete")
                        )
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/NsMeterUseDayMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO ns_meter_use_day t")
                .contains("#{item.bm} AS bm")
                .contains("#{item.dataTime} AS dataTime")
                .contains("0 AS isDelete")
                .contains("ON (t.pointId = s.pointId AND t.dataTime = s.dataTime AND t.isDelete = s.isDelete)")
                .contains("WHEN MATCHED THEN UPDATE SET t.bm = s.bm, t.usage = s.usage")
                .contains("WHEN NOT MATCHED THEN INSERT (bm, dataTime, pointId, usage) VALUES (s.bm, s.dataTime, s.pointId, s.usage)")
                .doesNotContain("ON DUPLICATE KEY UPDATE")
                .doesNotContain("INSERT (bm, dataTime, pointId, usage, isDelete)");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MapperXmlRewriter.MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE
                );
    }

    @Test
    void configuredBatchUpsertWithCurrentTimestampIsRewrittenToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.CustomerMapper">
                    <insert id="batchUpsert">
                        INSERT INTO dw_sample_customer (
                            customer_id, name, raw_json, created_at, updated_at
                        ) VALUES
                        <foreach collection="list" item="item" separator=",">
                            (
                                #{item.customerId,jdbcType=VARCHAR},
                                #{item.name,jdbcType=VARCHAR},
                                #{item.rawJson,jdbcType=LONGVARCHAR},
                                NOW(),
                                NOW()
                            )
                        </foreach>
                        ON DUPLICATE KEY UPDATE
                            name = VALUES(name),
                            raw_json = VALUES(raw_json),
                            updated_at = NOW()
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/CustomerMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/CustomerMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of("com.example.CustomerMapper.batchUpsert", List.of("customer_id"))
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/CustomerMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO dw_sample_customer t")
                .contains("ON (t.customer_id = s.customer_id)")
                .contains("t.name = s.name")
                .contains("t.raw_json = s.raw_json")
                .contains("t.updated_at = NOW()")
                .doesNotContain("ON DUPLICATE KEY UPDATE");
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicXmlConvertsDefaultYearWeekWithoutManualReview() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.AuditMapper">
                    <select id="selectAudit" resultType="map">
                        <if test="enabled != null">
                            select "ACTIVE" as status, YEARWEEK(created_at) from audit_log
                        </if>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/AuditMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/AuditMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/AuditMapper.xml"));
        assertThat(rewritten)
                .contains("select 'ACTIVE' as status, "
                        + "(YEAR(DATEADD(DAY, -WEEKDAY(created_at), created_at)) * 100 "
                        + "+ WEEK(created_at, 2)) from audit_log");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_YEARWEEK_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicSelectWithIncludeRewritesTrailingLimitFragment() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.MaterialMapper">
                    <sql id="Material_Column_List">
                        `id`,`materialCode`
                    </sql>
                    <select id="selectMax" resultType="map">
                        select
                        <include refid="Material_Column_List" />
                        from
                        ns_wms_material
                        where `enterpriseId` = #{enterpriseId,jdbcType=BIGINT}
                        and `materialCode` LIKE #{materialClassCode}"%"
                        order by
                        `id`
                        desc limit 1
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/MaterialMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/MaterialMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/MaterialMapper.xml"));
        assertThat(rewritten)
                .contains("`enterpriseId` = #{enterpriseId,jdbcType=BIGINT}")
                .contains("and `materialCode` LIKE (#{materialClassCode}) || ('%')")
                .contains("desc limit 1");
        assertThat(result.automaticConversions())
                .anySatisfy(conversion -> assertThat(conversion.appliedRules())
                        .contains(MySqlToDmSqlConverter.MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT_RULE));
    }

    @Test
    void staticInformationSchemaColumnDetailsAreRewrittenWithoutManualReview() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/TableFieldMapper.xml", """
                select
                    TABLE_SCHEMA as tableSchema,
                    TABLE_NAME as tableName,
                    COLUMN_NAME as columnName,
                    COLUMN_TYPE as columnType,
                    COLUMN_COMMENT as columnComment,
                    IS_NULLABLE as isNullAble
                from information_schema.COLUMNS
                where TABLE_SCHEMA = (select database())
                  and TABLE_NAME = #{tableName}
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/TableFieldMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/TableFieldMapper.xml")
        );
        assertThat(rewritten)
                .contains("c.OWNER AS \"tableSchema\"")
                .contains("c.DATA_TYPE AS \"columnType\"")
                .contains("cc.COMMENTS AS \"columnComment\"")
                .contains("CASE c.NULLABLE WHEN 'Y' THEN 'YES' ELSE 'NO' END AS \"isNullAble\"")
                .contains("c.OWNER = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)")
                .contains("c.TABLE_NAME = UPPER(#{tableName})")
                .doesNotContain("information_schema")
                .doesNotContain("database()");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void columnAndIndexDescriptorMetadataAreRewrittenWithoutSchemaHardcoding() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.InformationSchemaMapper">
                    <select id="selectColumnNames" resultType="map">
                        select c.COLUMN_NAME as columnName
                        , c.COLUMN_TYPE as columnType
                        , c.COLUMN_COMMENT as columnComment
                        , c.COLUMN_DEFAULT as columnDefault
                        , c.COLUMN_KEY as columnKey
                        , c.EXTRA as extra
                        from information_schema.`COLUMNS` c
                        where TABLE_SCHEMA = '${schema}'
                        and TABLE_NAME = '${tableName}'
                        order by ORDINAL_POSITION
                    </select>
                    <select id="selectColumnNamesNowDatabase" resultType="map">
                        select c.COLUMN_NAME as columnName
                        , c.COLUMN_TYPE as columnType
                        , c.COLUMN_COMMENT as columnComment
                        , c.COLUMN_DEFAULT as columnDefault
                        , c.COLUMN_KEY as columnKey
                        , c.EXTRA as extra
                        from information_schema.`COLUMNS` c
                        where TABLE_SCHEMA = (select DATABASE())
                        and TABLE_NAME = '${tableName}'
                        order by ORDINAL_POSITION
                    </select>
                    <select id="selectIndexNamesNowDatabase" resultType="java.lang.String">
                        select distinct s.INDEX_NAME
                        from information_schema.`STATISTICS` s
                        where TABLE_SCHEMA = (select DATABASE())
                        and TABLE_NAME = '${tableName}'
                        and INDEX_NAME != 'PRIMARY'
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/InformationSchemaMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/InformationSchemaMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/InformationSchemaMapper.xml")
        );
        assertThat(rewritten)
                .contains("FROM SYS.SYSCOLUMNS sc")
                .contains("WHERE sch.NAME = UPPER('${schema}')")
                .contains("WHERE sch.NAME = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)")
                .contains("AND obj.NAME = UPPER('${tableName}')")
                .contains("FROM ALL_INDEXES i")
                .contains("ac.CONSTRAINT_TYPE = 'P'")
                .doesNotContain("information_schema")
                .doesNotContain("DATABASE()");
        assertThat(result.automaticConversions()).hasSize(3);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void commonTableAndColumnMetadataQueriesAreRewrittenWithoutManualReview() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.MetadataMapper">
                    <select id="countColumn" resultType="int">
                        SELECT COUNT(1)
                        FROM information_schema.COLUMNS
                        WHERE table_schema = (select database())
                          AND table_name = #{tableName}
                          AND column_name = #{columnName}
                    </select>
                    <select id="listViews" resultType="string">
                        SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                        WHERE TABLE_SCHEMA = (select database())
                          AND TABLE_TYPE = 'VIEW'
                          AND TABLE_NAME LIKE 'vw_sample_%'
                    </select>
                    <select id="listTables" resultType="string">
                        SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES
                        WHERE TABLE_SCHEMA = (select database())
                          AND TABLE_TYPE = 'BASE TABLE'
                    </select>
                    <select id="listColumnNames" resultType="string">
                        SELECT GROUP_CONCAT(COLUMN_NAME) AS result
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_SCHEMA = (select database())
                          AND TABLE_NAME = #{tableName}
                    </select>
                    <select id="listColumnDetails" resultType="map">
                        SELECT COLUMN_NAME, COLUMN_COMMENT, DATA_TYPE, IS_NULLABLE,
                               COLUMN_DEFAULT, CHARACTER_MAXIMUM_LENGTH
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_SCHEMA = (select database())
                          AND TABLE_NAME = #{tableName}
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/MetadataMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/MetadataMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/MetadataMapper.xml")
        );
        assertThat(rewritten)
                .contains("SELECT COUNT(*) FROM ALL_TAB_COLUMNS")
                .contains("SELECT VIEW_NAME AS TABLE_NAME FROM ALL_VIEWS")
                .contains("SELECT TABLE_NAME FROM ALL_TABLES")
                .contains("LISTAGG(COLUMN_NAME, ',') WITHIN GROUP (ORDER BY COLUMN_ID) AS result")
                .contains("cc.COMMENTS AS COLUMN_COMMENT")
                .contains("c.CHAR_LENGTH AS CHARACTER_MAXIMUM_LENGTH")
                .contains("SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)")
                .doesNotContain("information_schema")
                .doesNotContain("database()");
        assertThat(result.automaticConversions()).hasSize(5);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void tableExistenceMetadataQueryIsRewrittenWithoutManualReview() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/NoticeMapper.xml", """
                select 1 from information_schema.tables
                where table_schema=(select database()) and table_name = #{tableName}
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/NoticeMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/NoticeMapper.xml"));
        assertThat(rewritten)
                .contains("SELECT 1 FROM ALL_TABLES")
                .contains("TABLE_NAME = UPPER(#{tableName})")
                .contains("OWNER = SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)")
                .doesNotContain("information_schema")
                .doesNotContain("database()");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicInformationSchemaColumnsWithIncludeIsRewrittenToDamengMetadataViews() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.CommonMapper">
                    <sql id="Column_List">
                        TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLUMN_COMMENT, COLUMN_DEFAULT,
                        CHARACTER_SET_NAME, IS_NULLABLE
                    </sql>
                    <select id="selectTableInfo" parameterType="java.util.Map" resultType="java.util.HashMap">
                        SELECT
                            <include refid="Column_List" />
                        FROM information_schema.columns
                        WHERE TABLE_SCHEMA = #{tableSchema}
                        <!-- table filter -->
                        AND TABLE_NAME = #{tableName}
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/CommonMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/CommonMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/CommonMapper.xml"));
        assertThat(rewritten)
                .contains("c.OWNER AS TABLE_SCHEMA")
                .contains("FROM ALL_TAB_COLUMNS c")
                .contains("LEFT JOIN ALL_COL_COMMENTS cc")
                .contains("WHERE c.OWNER = UPPER(REPLACE(#{tableSchema}, '\"', ''))")
                .contains("AND c.TABLE_NAME = UPPER(REPLACE(#{tableName}, '\"', ''))")
                .contains("ORDER BY c.COLUMN_ID")
                .doesNotContain("information_schema.columns")
                .doesNotContain("<include refid=\"Column_List\"");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicMapOnDuplicateKeyUpdateIsRewrittenToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateExtend">
                        <if test="dynamicMap != null and !dynamicMap.isEmpty()">
                            INSERT INTO ns_organization_and_employees_extend (foreignerKeyId
                            <foreach collection="dynamicMap.keys" item="key" open="," separator=",">
                                ${key}
                            </foreach>
                            )
                            VALUES (#{userId, jdbcType=BIGINT}
                            <foreach collection="dynamicMap.values" item="value" open="," separator=",">
                                #{value}
                            </foreach>
                            )
                            ON DUPLICATE KEY UPDATE
                            <foreach collection="dynamicMap" index="key" item="value" separator=",">
                                ${key} = VALUES(${key})
                            </foreach>
                        </if>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of("com.example.UserMapper.updateExtend", List.of("foreignerKeyId"))
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("<if test=\"dynamicMap != null and !dynamicMap.isEmpty()\">")
                .contains("MERGE INTO ns_organization_and_employees_extend t")
                .contains("SELECT #{userId, jdbcType=BIGINT} AS foreignerKeyId")
                .contains("<foreach collection=\"dynamicMap\" index=\"key\" item=\"value\">")
                .contains(", #{value} AS ${key}")
                .contains("ON (t.foreignerKeyId = s.foreignerKeyId)")
                .contains("t.${key} = s.${key}")
                .contains("s.${key}")
                .doesNotContain("ON DUPLICATE KEY UPDATE")
                .doesNotContain("VALUES(${key})");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicMapOnDuplicateKeyUpdateWithBacktickDynamicColumnsIsRewrittenToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateExtend">
                        <if test="dynamicMap != null and !dynamicMap.isEmpty()">
                            INSERT INTO ns_organization_and_employees_extend (foreignerKeyId
                            <foreach collection="dynamicMap.keys" item="key" open="," separator=",">
                                `${key}`
                            </foreach>
                            )
                            VALUES (#{userId, jdbcType=BIGINT}
                            <foreach collection="dynamicMap.values" item="value" open="," separator=",">
                                #{value}
                            </foreach>
                            )
                            ON DUPLICATE KEY UPDATE
                            <foreach collection="dynamicMap" index="key" item="value" separator=",">
                                `${key}` = VALUES(`${key}`)
                            </foreach>
                        </if>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(
                        Map.of(),
                        Map.of("com.example.UserMapper.updateExtend", List.of("foreignerKeyId"))
                )
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO ns_organization_and_employees_extend t")
                .contains("SELECT #{userId, jdbcType=BIGINT} AS foreignerKeyId")
                .contains(", #{value} AS ${key}")
                .contains("ON (t.foreignerKeyId = s.foreignerKeyId)")
                .contains("t.${key} = s.${key}")
                .doesNotContain("ON DUPLICATE KEY UPDATE")
                .doesNotContain("VALUES(`${key}`)")
                .doesNotContain("\"${key}\" = VALUES(\"${key}\")");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MapperXmlRewriter.MYBATIS_DYNAMIC_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
    }

    @Test
    void dynamicMapOnDuplicateKeyUpdateWithDifferentMapNamesIsNotRewrittenToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateExtend">
                        INSERT INTO ns_organization_and_employees_extend (foreignerKeyId
                        <foreach collection="dynamicMap.keys" item="key" open="," separator=",">
                            ${key}
                        </foreach>
                        )
                        VALUES (#{userId, jdbcType=BIGINT}
                        <foreach collection="otherMap.values" item="value" open="," separator=",">
                            #{value}
                        </foreach>
                        )
                        ON DUPLICATE KEY UPDATE
                        <foreach collection="dynamicMap" index="key" item="value" separator=",">
                            ${key} = VALUES(${key})
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("MERGE INTO");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason())
                .contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void dynamicMapOnDuplicateKeyUpdateWithNonValuesAssignmentIsNotRewrittenToMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateExtend">
                        INSERT INTO ns_organization_and_employees_extend (foreignerKeyId
                        <foreach collection="dynamicMap.keys" item="key" open="," separator=",">
                            ${key}
                        </foreach>
                        )
                        VALUES (#{userId, jdbcType=BIGINT}
                        <foreach collection="dynamicMap.values" item="value" open="," separator=",">
                            #{value}
                        </foreach>
                        )
                        ON DUPLICATE KEY UPDATE
                        <foreach collection="dynamicMap" index="key" item="value" separator=",">
                            ${key} = #{value}
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("ON DUPLICATE KEY UPDATE")
                .doesNotContain("MERGE INTO");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason())
                .contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void dynamicUpdateJoinWithSetBranchesIsRewrittenToUpdateFrom() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateByNsOrgByLevel">
                        update
                            ns_system_entry_org eo
                            inner JOIN ns_system_organization o ON eo.${entryOrgParentId} = o.organization_parent_id
                            AND eo.${entryOrgName} = o.ORGANIZATION_NAME
                            AND o.is_deleted = 0
                        <if test="'secondaryDepartment' ==  entryOrgLevel">
                            set eo.secondaryDepartmentId = o.organization_id
                        </if>
                        <if test="'tertiaryDepartment' ==  entryOrgLevel">
                            set eo.tertiaryDepartmentId = o.organization_id
                        </if>
                        WHERE
                            eo.deleteFlag = 0
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("update\n            ns_system_entry_org eo")
                .contains("<if test=\"'secondaryDepartment' ==  entryOrgLevel\">")
                .contains("set eo.secondaryDepartmentId = o.organization_id")
                .contains("inner JOIN ns_system_organization o")
                .contains("AND eo.${entryOrgName} = o.ORGANIZATION_NAME")
                .contains("eo.deleteFlag = 0");
    }

    @Test
    void dynamicUpdateJoinWithConditionalSetItemsIsRewrittenAsWholeStatement() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="syncOrgUpdateByTime">
                        UPDATE ns_system_organization yy
                        INNER JOIN (
                            SELECT y.organization_id, y.organization_code, y.organization_type, y.organization_name, y.sync_organization_id
                            FROM ys_organization y
                            WHERE y.enterprise_id = #{enterpriseId}
                        ) c ON yy.sync_organization_id = c.sync_organization_id
                        SET yy.organization_id = c.organization_id,
                            yy.organization_code = c.organization_code,
                        <if test="syncOrgTypeFromYs != 0">
                            yy.organization_type = c.organization_type,
                        </if>
                            yy.organization_name = c.organization_name
                        WHERE yy.enterprise_id = #{enterpriseId}
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("UPDATE ns_system_organization yy")
                .contains("INNER JOIN (")
                .contains("SET yy.organization_id = c.organization_id")
                .contains("<if test=\"syncOrgTypeFromYs != 0\">")
                .contains("yy.organization_type = c.organization_type")
                .contains("WHERE yy.enterprise_id = #{enterpriseId}");
        assertThat(result.automaticConversions()).isEmpty();
    }

    @Test
    void dynamicMultiTargetUpdateJoinRemovesDuplicateBlockSemicolon() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="syncCustomer">
                        update owner_customer_result r
                        inner join tmp_owner_precinct_result_20200322 t on t.owner_id = r.owner_id
                        inner join owner_customer_base_info b on b.owner_id = t.owner_id
                        set
                        r.precinct_id = t.precinct_id,
                        b.precinct_id = t.precinct_id
                        ;
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("BEGIN")
                .contains("from tmp_owner_precinct_result_20200322 t, owner_customer_base_info b")
                .contains("update owner_customer_base_info b")
                .contains("b.precinct_id = t.precinct_id")
                .doesNotContain("END;;");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void selfJoinMultiTargetUpdateUsesRowCountGuardedDamengBlock() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.PaymentMapper">
                    <update id="updateByRefPaymentID">
                        UPDATE ns_payment_chargepayment a
                        INNER JOIN ns_payment_chargepayment b
                            ON a.id = b.RefPaymentID AND b.IsDelete = 0
                        SET a.canRefundPaid = a.canRefundPaid + abs(b.ChargePaid),
                            a.IsCanceled = 0,
                            b.IsDelete = 1
                        WHERE a.id = #{id} AND a.IsCanceled = 1
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/PaymentMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/PaymentMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/PaymentMapper.xml"));
        assertThat(rewritten)
                .contains("BEGIN")
                .contains("IF SQL%ROWCOUNT &gt; 0 THEN")
                .contains("where b.RefPaymentID = #{id} and b.IsDelete = 0")
                .contains("END;")
                .doesNotContain("INNER JOIN");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void dynamicMultiTargetUpdateJoinKeepsTrailingPredicatesInsideDamengBlock() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateShareCheckTaskById">
                        update ns_quality_check_schedule_task a
                        join ns_quality_check_schedule_task_user u on a.ID=u.checkScheduleTaskID
                        join ns_quality_day_task_transfer b on b.reportUserID=u.checkUserID
                        set u.checkUserID=b.transferUserID,
                            u.checkUserName=b.transferUserName,
                            a.transferType=1,
                            a.transferFromUserID=b.reportUserID,
                            a.transferFromUserName=b.reportUserName
                        where b.ID = #{id} and a.checkStatus=1
                        <if test="importantFlag != null">
                            and a.importantFlag = #{importantFlag}
                        </if>
                        and a.searchStartDate between b.startDate and b.endDate
                        and a.searchEndDate between b.startDate and b.endDate
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("BEGIN")
                .contains("from ns_quality_check_schedule_task_user u, ns_quality_day_task_transfer b")
                .contains("update ns_quality_check_schedule_task_user u")
                .contains("<if test=\"importantFlag != null\">")
                .contains("and a.searchStartDate between b.startDate and b.endDate")
                .contains("END;");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void dynamicUpdateJoinWithConditionalTrailingAssignmentKeepsAssignmentBeforeFrom() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateNsUser">
                        UPDATE ns_system_user nu
                        INNER JOIN ys_user c ON nu.ys_user_id = c.sso_user_id
                        SET nu.AD_account = c.`AD_account`,
                            nu.sentry_id = case c.`sentry_id` when "0" then nu.sentry_id else c.`sentry_id` end,
                            nu.user_password = to_base64(AES_ENCRYPT(c.`password`, "sample-key")),
                            nu.update_time = now()
                        <if test="isFromV8 != null and (isFromV8 == '1' or isFromV8 == 1)">
                            ,nu.v8_user_id = c.sso_user_id
                        </if>
                        WHERE
                            nu.enterprise_id = #{enterpriseId}
                            AND c.update_time &gt; #{lastFinishTime}
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("UPDATE ns_system_user nu")
                .contains("INNER JOIN ys_user c ON nu.ys_user_id = c.sso_user_id")
                .contains("SET nu.AD_account = c.`AD_account`")
                .contains("nu.sentry_id = case c.`sentry_id` when '0' then nu.sentry_id else c.`sentry_id` end")
                .contains("nu.user_password = to_base64(AES_ENCRYPT(c.`password`, 'sample-key'))")
                .contains("nu.update_time = now()")
                .contains(",nu.v8_user_id = c.sso_user_id")
                .contains("WHERE\n            nu.enterprise_id = #{enterpriseId}");
        assertThat(result.automaticConversions())
                .singleElement()
                .satisfies(change -> assertThat(change.appliedRules())
                        .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING"));
    }

    @Test
    void dynamicUpdateJoinWithMultipleJoinsIsNotRewritten() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateComplex">
                        update ys_organization a
                        inner join ys_organization b on a.parent_id = b.id
                        inner join ys_organization c on a.scope_id = c.id
                        <if test="'x' == level">
                            set a.parent_id = c.id
                        </if>
                        where a.is_deleted = 0
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("inner join ys_organization b")
                .contains("inner join ys_organization c")
                .doesNotContain("from ys_organization b");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicUpdateWithMultipleInnerJoinsAndWhereTagIsRewrittenToUpdateFrom() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.SampleMapper">
                    <update id="updateKinds">
                        UPDATE sample_target extend
                        INNER JOIN sample_info info ON extend.id = info.id
                        INNER JOIN sample_base base ON info.base_id = base.id
                        INNER JOIN sample_scope scope ON base.scope_id = scope.id
                        SET extend.kind = scope.kind
                        <where>
                            base.is_deleted = 0 AND scope.kind IS NOT NULL
                            <if test="scopeIds != null and scopeIds.size > 0">
                                AND base.scope_id IN
                                <foreach collection="scopeIds" open="(" separator="," item="scopeId" close=")">
                                    #{scopeId}
                                </foreach>
                            </if>
                        </where>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/SampleMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/SampleMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/SampleMapper.xml"));
        assertThat(rewritten)
                .contains("update sample_target extend set kind = scope.kind from sample_info info, sample_base base, sample_scope scope")
                .contains("extend.id = info.id and info.base_id = base.id and base.scope_id = scope.id")
                .contains("and base.is_deleted = 0 AND scope.kind IS NOT NULL")
                .contains("<if test=\"scopeIds != null and scopeIds.size > 0\">")
                .doesNotContain("INNER JOIN")
                .doesNotContain("DM_ADAPTER_DYNAMIC_WHERE_SENTINEL");
        assertThat(result.manualReviewItems()).isEmpty();
        assertThat(result.automaticConversions())
                .singleElement()
                .satisfies(change -> assertThat(change.appliedRules())
                        .contains(
                                MapperXmlRewriter.MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE,
                                MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE
                        ));
    }

    @Test
    void dynamicLeftJoinUpdateUsesConfiguredSourceKeyAcrossForeach() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.OrganizationMapper">
                    <update id="updatePathNames">
                        update sample_organization child
                        left join sample_organization parent
                            on child.parent_id = parent.id
                        set child.path_name = concat(parent.path_name, "-", child.name)
                        where child.id in
                        <foreach collection="ids" item="id" open="(" separator="," close=")">
                            #{id}
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/OrganizationMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/OrganizationMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(Map.of("sample_organization", List.of("id")), Map.of())
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/OrganizationMapper.xml")
        );
        assertThat(rewritten)
                .contains("update sample_organization child set path_name = (SELECT concat(parent.path_name, '-', child.name)")
                .contains("FROM sample_organization parent WHERE child.parent_id = parent.id)")
                .contains("<foreach collection='ids'")
                .doesNotContainIgnoringCase("left join");
        assertThat(result.automaticConversions()).singleElement()
                .satisfies(change -> assertThat(change.appliedRules())
                        .contains(
                                MapperXmlRewriter.MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE,
                                MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE
                        ));
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void staticLeftJoinUpdateUsesConfiguredSourceKey() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/CategoryMapper.xml", """
                update sample_category child
                left join sample_category parent on child.parent_id = parent.id
                set child.path_name = concat(parent.path_name, child.name),
                    child.category_level = ifnull(parent.category_level, 1) + 1
                where parent.id is not null and child.category_level = #{level}
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/CategoryMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(Map.of("sample_category", List.of("id")), Map.of())
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/CategoryMapper.xml"));
        assertThat(rewritten)
                .contains("path_name = (SELECT concat(parent.path_name, child.name)")
                .contains("category_level = (SELECT ifnull(parent.category_level, 1) + 1")
                .contains("EXISTS (SELECT 1 FROM sample_category parent")
                .doesNotContainIgnoringCase("left join");
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicLeftJoinSourceFilterBecomesScalarAndExistsSubqueries() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateOrganizationNames">
                        update sample_user user_row
                        left join sample_organization organization
                            on user_row.organization_id = organization.id
                        set user_row.organization_name = organization.name,
                            user_row.update_time = now()
                        where organization.id in
                        <foreach collection="ids" item="id" open="(" separator="," close=")">
                            #{id}
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                new SqlRewriteConfig(Map.of("sample_organization", List.of("id")), Map.of())
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("organization_name = (SELECT organization.name FROM sample_organization organization")
                .contains("where EXISTS (SELECT 1 FROM sample_organization organization")
                .contains("<foreach collection='ids'")
                .doesNotContain("where organization.id in")
                .doesNotContainIgnoringCase("left join");
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicLeftJoinGroupedSourceIsConvertedWithoutConfiguredKey() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.BillMapper">
                    <update id="updateTotals">
                        update sample_header header
                        left join (
                            select detail.header_id, sum(detail.amount) as total_amount
                            from sample_detail detail
                            group by detail.header_id
                        ) totals on header.id = totals.header_id
                        set header.total_amount = ifnull(totals.total_amount, 0)
                        where header.id in
                        <foreach collection="ids" item="id" open="(" separator="," close=")">
                            #{id}
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/BillMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/BillMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/BillMapper.xml"));
        assertThat(rewritten)
                .contains("total_amount = (SELECT ifnull(totals.total_amount, 0) FROM (")
                .contains("group by detail.header_id")
                .contains(") totals WHERE header.id = totals.header_id)")
                .contains("<foreach collection='ids'")
                .doesNotContainIgnoringCase("left join");
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicLeftJoinConstantTargetAssignmentUsesExistsWithoutSourceKey() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ContractRoomMapper">
                    <update id="deletePendingRooms">
                        update sample_contract_room room
                        left join sample_contract contract on room.contract_id = contract.id
                        set room.is_deleted = 1
                        where contract.status in ('PENDING', 'REVIEW')
                        and room.contract_id in
                        <foreach collection="ids" item="id" open="(" separator="," close=")">
                            #{id}
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ContractRoomMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ContractRoomMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/ContractRoomMapper.xml")
        );
        assertThat(rewritten)
                .contains("update sample_contract_room room set is_deleted = 1")
                .contains("room.contract_id in")
                .contains("EXISTS (SELECT 1 FROM sample_contract contract")
                .contains("contract.status in ('PENDING', 'REVIEW')")
                .doesNotContainIgnoringCase("left join");
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicOuterJoinUpdatingJoinedTargetUsesRowIdMergeAcrossForeach() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.DetailMapper">
                    <delete id="deleteSettlementDetails">
                        update sample_extension extension
                        left join sample_detail detail on detail.id = extension.detail_id
                        set detail.is_deleted = "1"
                        where detail.scope_id = #{scopeId}
                        and extension.settlement_id in
                        <foreach collection="ids" item="id" open="(" separator="," close=")">
                            #{id}
                        </foreach>
                    </delete>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/DetailMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/DetailMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/DetailMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO sample_detail detail")
                .contains("SELECT DISTINCT detail.ROWID AS dm_target_rowid")
                .contains("<foreach collection=\"ids\"")
                .contains("WHEN MATCHED THEN UPDATE SET detail.is_deleted = '1'")
                .doesNotContainIgnoringCase("update sample_extension extension");
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicSqlTextNodesRewriteDoubleQuotedStringLiterals() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updatePassword">
                        update sys_user
                        <set>
                            <if test="userPassword != null">
                                user_password = to_base64(AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR } \t,"XXXXXXXX")) ,
                            </if>
                        </set>
                        where user_id = #{userId}
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("<if test=\"userPassword != null\">")
                .contains("to_base64(AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR } \t,'XXXXXXXX'))")
                .doesNotContain(",\"XXXXXXXX\"");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicUpdateSetRewritesOrderByLimitOneToRowidSubquery() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.BillsharingMapper">
                    <update id="updateLatestBillsharingByCustomerId">
                        UPDATE ns_bill_billsharing
                        <set>
                            <if test="customerId != null">
                                customerId = #{customerId,jdbcType=INTEGER},
                            </if>
                            <if test="payTime != null">
                                payTime = #{payTime,jdbcType=TIMESTAMP},
                            </if>
                        </set>
                        where customerId = #{customerId,jdbcType=INTEGER} order by createTime desc limit 1
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/BillsharingMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/BillsharingMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/BillsharingMapper.xml"));
        assertThat(rewritten)
                .contains("""
                        where ROWID in (select rid from (select ROWID rid from ns_bill_billsharing where customerId = #{customerId,jdbcType=INTEGER} order by createTime desc) where ROWNUM &lt;= 1)
                        """.strip())
                .doesNotContain("order by createTime desc limit 1");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_ORDER_LIMIT_ONE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void rewritesDeleteOrderByLimitOneToRowidSubquery() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.TenantEventLogMapper">
                    <delete id="deleteNewestByTenantId">
                        delete from tenant_event_log
                        where tenant_id = #{tenantId} order by event_id desc limit 1
                    </delete>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/TenantEventLogMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/TenantEventLogMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/TenantEventLogMapper.xml")
        );
        assertThat(rewritten)
                .contains("""
                        delete from tenant_event_log where ROWID in (select rid from (select ROWID rid from tenant_event_log where tenant_id = #{tenantId} order by event_id desc) where ROWNUM &lt;= 1)
                        """.strip())
                .doesNotContain("order by event_id desc limit 1");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_DELETE_ORDER_LIMIT_ONE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void rewritesTimeToSecTimeDiffChainedDivisionInMapperSelect() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.TenantTaskMapper">
                    <select id="findUrgentTasks">
                        select task.id
                        from tenant_task task
                        where TIME_TO_SEC(TIMEDIFF(NOW(), task.created_at))/60/task.complete_limit &gt; 0.8
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/TenantTaskMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/TenantTaskMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/TenantTaskMapper.xml"));
        assertThat(rewritten)
                .contains("CAST(DATEDIFF(SECOND, task.created_at, SYSDATE) AS DECIMAL(38,10))")
                .contains("/ NULLIF(CAST(60 AS DECIMAL(38,10)), 0)")
                .contains("/ NULLIF(CAST(task.complete_limit AS DECIMAL(38,10)), 0)")
                .doesNotContain("TIME_TO_SEC")
                .doesNotContain("TIMEDIFF");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_TIME_TO_SEC_TIMEDIFF_RULE,
                MySqlToDmSqlConverter.MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE
        );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void rewritesTimePartsExtractedFromTimeDiffInMapperSelect() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ServiceMapper">
                    <select id="censusCollection">
                        select sum(minute(timediff(a.accomplish_date,a.reception_date))) / 60 workHours
                        from ns_sr_services a
                        where hour(timediff(a.accomplish_date,a.accept_date)) &lt; 24
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ServiceMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ServiceMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ServiceMapper.xml"));
        assertThat(rewritten)
                .contains("MOD(TRUNC(ABS(DATEDIFF(SECOND, a.reception_date, a.accomplish_date)) / 60), 60)")
                .contains("TRUNC(CAST(ABS(DATEDIFF(SECOND, a.accept_date, a.accomplish_date))")
                .doesNotContainIgnoringCase("TIMEDIFF");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules()).contains(
                MySqlToDmSqlConverter.MYSQL_TIME_PART_TIMEDIFF_RULE,
                MySqlToDmSqlConverter.MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE
        );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicDeleteUpdateAddsMissingAndBetweenStaticWherePredicates() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ProcessDefPrecinctRelationMapper">
                    <delete id="deleteBatchByProcessDefId" parameterType="java.util.List">
                        update ns_process_def
                        set delete_flag = 1
                        where
                        is_deleted = 0
                        process_def_id in
                        <foreach collection="processDefIds" item="item" open="(" close=")" separator=",">
                            ${item}
                        </foreach>
                    </delete>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ProcessDefPrecinctRelationMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ProcessDefPrecinctRelationMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve(
                "src/main/resources/mapper-dm/ProcessDefPrecinctRelationMapper.xml"
        ));
        assertThat(rewritten)
                .containsPattern("(?s)is_deleted = 0\\s+and process_def_id in")
                .doesNotContain("is_deleted = 0\n        process_def_id in");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_STATIC_WHERE_MISSING_AND_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicTwoTargetJoinedDeleteDuplicatesForeachInSafeChildFirstBlock() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.FlowActionMapper">
                    <update id="deleteByStepIds" parameterType="java.util.List">
                        delete a,c from ns_sr_flow_action a
                        left join ns_sr_flow_action_condition c on a.id = c.action_id
                        where a.step_id in
                        <foreach collection="list" item="item" open="(" close=")" separator=",">
                            #{item}
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/FlowActionMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/FlowActionMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve(
                "src/main/resources/mapper-dm/FlowActionMapper.xml"
        ));
        assertThat(rewritten)
                .contains("BEGIN")
                .contains("DELETE FROM ns_sr_flow_action_condition WHERE ROWID IN")
                .contains("DELETE FROM ns_sr_flow_action WHERE ROWID IN")
                .doesNotContain("delete a,c from");
        assertThat(rewritten.split("<foreach\\b", -1).length - 1).isEqualTo(2);
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_DELETE_JOIN_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicWhereKeepsForeachPredicateAfterOpenConnectorGroup() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ChargeDetailMapper">
                    <select id="getDetailForCarryOver" resultType="map">
                        select *
                        from Charge_CustomerChargeDetail
                        where
                        IsDelete = 0 and (
                        Id in
                        <foreach collection="ids" item="item" separator="," close=")" open="(">
                            #{item}
                        </foreach>
                        OR (
                        RefChargeDetailId IN
                        <foreach collection="ids" item="item" separator="," close=")" open="(">
                            #{item}
                        </foreach>
                        AND Arrears &lt; 0
                        )
                        )
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ChargeDetailMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ChargeDetailMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ChargeDetailMapper.xml"));
        assertThat(rewritten)
                .containsPattern("(?s)IsDelete = 0 and \\(\\s+Id in")
                .doesNotContain("and Id in");
        assertThat(result.automaticConversions()).isEmpty();
    }

    @Test
    void dynamicWhereAddsMissingAndBeforeConditionalPredicates() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.CarryOverTaskMapper">
                    <select id="selectByCondition" parameterType="com.example.CarryOverTask" resultType="map">
                        select *
                        from charge_auto_carry_over_task
                        <where>
                            <if test="enterpriseId != null">
                                enterprise_id = #{enterpriseId,jdbcType=BIGINT}
                            </if>
                            <if test="organizationId != null">
                                organization_id = #{organizationId,jdbcType=BIGINT}
                            </if>
                            <if test="taskName != null">
                                task_name = #{taskName,jdbcType=VARCHAR}
                            </if>
                        </where>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/CarryOverTaskMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/CarryOverTaskMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/CarryOverTaskMapper.xml"));
        assertThat(rewritten)
                .contains("and enterprise_id = #{enterpriseId")
                .contains("and organization_id = #{organizationId")
                .contains("and task_name = #{taskName");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_WHERE_MISSING_AND_RULE);
    }

    @Test
    void dynamicWhereAddsMissingAndBeforeConditionalFunctionPredicate() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ChargeDetailMapper">
                    <select id="selectShouldPaymentKFS" parameterType="map" resultType="map">
                        select *
                        from Charge_CustomerChargeDetail c
                        <where>
                            and c.OwnerId = #{ownerId}
                            <if test="isAccount != null">
                                ifnull(uncancelAccountAmount, 0) != 0
                            </if>
                            <if test="isKongzhi != null">
                                and ifnull(c.isKongzhi, 0) = #{isKongzhi}
                            </if>
                        </where>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ChargeDetailMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ChargeDetailMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ChargeDetailMapper.xml"));
        assertThat(rewritten)
                .contains("and ifnull(uncancelAccountAmount, 0) != 0")
                .contains("and ifnull(c.isKongzhi, 0) = #{isKongzhi}")
                .doesNotContain("and and");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_WHERE_MISSING_AND_RULE);
    }

    @Test
    void dynamicUpdateSetAddsMissingCommasBetweenConditionalAssignments() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.HouseMapper">
                    <update id="batchUpdateExpirationOrEffDate" parameterType="java.util.List">
                        update owner_house_house_extend_info
                        <set>
                            <foreach collection="list" item="record" separator=",">
                                <if test="record.effectiveDate != null">
                                    effective_date = #{record.effectiveDate, jdbcType=DATE}
                                </if>
                                <if test="record.expirationDate != null">
                                    expiration_date = #{record.expirationDate, jdbcType=DATE}
                                </if>
                            </foreach>
                        </set>
                        where house_id in
                        <foreach collection="list" item="record" open="(" close=")" separator=",">
                            #{record.houseId}
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/HouseMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/HouseMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/HouseMapper.xml"));
        assertThat(rewritten)
                .contains("effective_date = #{record.effectiveDate, jdbcType=DATE},")
                .contains("expiration_date = #{record.expirationDate, jdbcType=DATE}")
                .doesNotContain("expiration_date = #{record.expirationDate, jdbcType=DATE},");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_SET_MISSING_COMMA_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicUpdateMergesAdjacentSetTrimBlocks() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateBatchPostManager">
                        update ns_system_user a
                        left join ys_user b on a.ys_user_id = b.sso_user_id
                        <trim prefix="set" suffixOverrides=",">
                            <trim prefix="user_key_post_manager = case" suffix="end,">
                                <foreach collection="list" item="item">
                                    when b.manager_sso_user_id = #{item.employNo} then #{item.userId}
                                </foreach>
                            </trim>
                        </trim>
                        <trim prefix="set" suffixOverrides=",">
                            <trim prefix="user_key_post_manager_name = case" suffix="end,">
                                <foreach collection="list" item="item">
                                    when b.manager_sso_user_id = #{item.employNo} then #{item.userName}
                                </foreach>
                            </trim>
                        </trim>
                        where b.enterprise_id = #{enterpriseId}
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(countMatches(rewritten, "prefix=\"set\"")).isEqualTo(1);
        assertThat(rewritten)
                .contains("a.user_key_post_manager = case")
                .contains("a.user_key_post_manager_name = case");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MapperXmlRewriter.MYBATIS_DYNAMIC_SET_TRIM_BLOCKS_MERGED_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_UPDATE_JOIN_SET_TARGET_QUALIFIED_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicUpdateJoinWithTrimSetQualifiesBareTargetColumns() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateBatchPostManager">
                        UPDATE ns_system_user a
                        left JOIN ys_user b on a.ys_user_id = b.sso_user_id and a.enterprise_id = b.enterprise_id
                        <trim prefix="set" suffixOverrides=",">
                            <trim prefix="user_key_post_manager =case" suffix="end,">
                                <foreach collection="list" item="i" index="index">
                                    when  b.manager_sso_user_id = #{i.employNo,jdbcType=VARCHAR} then #{i.userId}
                                </foreach>
                            </trim>
                            <trim prefix="user_key_post_manager_name =case" suffix="end,">
                                <foreach collection="list" item="i" index="index">
                                    when  b.manager_sso_user_id = #{i.employNo,jdbcType=VARCHAR} then #{i.userName}
                                </foreach>
                            </trim>
                        </trim>
                        where
                        b.enterprise_id = #{enterpriseId,jdbcType=BIGINT} and
                        <foreach collection="list" separator="or" item="i" index="index" >
                            b.manager_sso_user_id = #{i.employNo, jdbcType=VARCHAR}
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("prefix=\"a.user_key_post_manager =case\"")
                .contains("prefix=\"a.user_key_post_manager_name =case\"")
                .contains("left JOIN ys_user b");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_UPDATE_JOIN_SET_TARGET_QUALIFIED_RULE);
    }

    @Test
    void dynamicUpdateSetNormalizesPropertyTargetsFromResultMap() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.FileMapper">
                    <resultMap id="BaseResultMap" type="com.example.File">
                        <id column="file_id" property="fileId"/>
                        <result column="path" property="path"/>
                        <result column="decrypted_after_date" property="decryptedAfterDate"/>
                    </resultMap>
                    <update id="updateByPrimaryKeySelective">
                        update ns_system_file
                        <set>
                            <if test="path != null">
                                path = #{path},
                            </if>
                            <if test="decryptedAfterDate != null">
                                decryptedAfterDate = #{decryptedAfterDate},
                            </if>
                        </set>
                        where file_id = #{fileId}
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/FileMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/FileMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/FileMapper.xml"));
        assertThat(rewritten)
                .contains("path = #{path}")
                .contains("decrypted_after_date = #{decryptedAfterDate}")
                .doesNotContain("decryptedAfterDate = #{decryptedAfterDate}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_SET_PROPERTY_COLUMN_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicUpdateSetDoesNotRewriteExplicitColumnsFromResultMap() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ReminderMapper">
                    <resultMap id="BaseResultMap" type="com.example.Reminder">
                        <id column="id" property="id"/>
                        <result column="treeCode" property="classification"/>
                        <result column="classification" property="classificationName"/>
                        <result column="decrypted_after_date" property="decryptedAfterDate"/>
                    </resultMap>
                    <update id="updateById">
                        update ns_system_reminder_configuration
                        <set>
                            <if test="classification != null">
                                `classification` = #{classification},
                            </if>
                            <if test="treeCode != null">
                                `treeCode` = #{treeCode},
                            </if>
                            <if test="decryptedAfterDate != null">
                                decryptedAfterDate = #{decryptedAfterDate},
                            </if>
                        </set>
                        where id = #{id}
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ReminderMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ReminderMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ReminderMapper.xml"));
        assertThat(rewritten)
                .contains("`classification` = #{classification}")
                .contains("`treeCode` = #{treeCode}")
                .contains("decrypted_after_date = #{decryptedAfterDate}")
                .doesNotContain("treeCode = #{classification}")
                .doesNotContain("decryptedAfterDate = #{decryptedAfterDate}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_SET_PROPERTY_COLUMN_RULE);
    }

    @Test
    void dynamicUpdateSetRemovesDuplicateAssignments() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ChargeItemMapper">
                    <update id="updateById">
                        update Charge_ChargeItem
                        <set>
                            <if test="powerType != null">
                                powerType = #{powerType,jdbcType=INTEGER},
                            </if>
                            <if test="powerType != null">
                                powerType = #{powerType, jdbcType=INTEGER },
                            </if>
                            <if test="billName != null">
                                billName = #{billName,jdbcType=VARCHAR},
                            </if>
                            <if test="billName == null">
                                billName = null,
                            </if>
                            <if test="chargeItemClass!=''">
                                ChargeItemClass = #{chargeItemClass, jdbcType=TINYINT },
                            </if>
                            ChargeItemClass = #{chargeItemClass},
                        </set>
                        where id = #{id}
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ChargeItemMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ChargeItemMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ChargeItemMapper.xml"));
        assertThat(countMatches(rewritten, "powerType =")).isEqualTo(1);
        assertThat(countMatches(rewritten, "billName = #{billName")).isEqualTo(1);
        assertThat(countMatches(rewritten, "billName = null")).isEqualTo(1);
        assertThat(countMatches(rewritten, "ChargeItemClass =")).isEqualTo(1);
        assertThat(rewritten)
                .doesNotContain("<if test=\"chargeItemClass!=''\">")
                .contains("ChargeItemClass = #{chargeItemClass}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_SET_DUPLICATE_ASSIGNMENT_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicUpdateSetGuardsEarlierAliasAssignmentsForSameColumn() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.SystemAreaMapper">
                    <update id="updateById">
                        update ns_system_area
                        <set>
                            <if test="parentCode != null">
                                `parent_area_code` = #{parentCode, jdbcType=VARCHAR },
                            </if>
                            <if test="level != null">
                                `area_level` = #{level, jdbcType=VARCHAR },
                            </if>
                            <if test="areaName != null">
                                `area_name` = #{areaName, jdbcType=VARCHAR },
                            </if>
                            <if test="parentAreaCode != null">
                                `parent_area_code` = #{parentAreaCode, jdbcType=VARCHAR },
                            </if>
                            <if test="areaLevel != null">
                                `area_level` = #{areaLevel, jdbcType=VARCHAR },
                            </if>
                        </set>
                        where id = #{id}
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/SystemAreaMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/SystemAreaMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/SystemAreaMapper.xml"));
        assertThat(rewritten)
                .contains("<if test=\"(parentCode != null) and parentAreaCode == null\">")
                .contains("<if test=\"(level != null) and areaLevel == null\">")
                .contains("<if test=\"parentAreaCode != null\">")
                .contains("<if test=\"areaLevel != null\">");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_SET_DUPLICATE_ASSIGNMENT_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicInsertTrimAddsMissingCommasBetweenConditionalValues() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.CashCountDetailMapper">
                    <insert id="insert">
                        insert into ns_payment_cash_count_detail
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="updateDateTime != null">
                                updateDateTime,
                            </if>
                            <if test="currentCashReceipt != null">
                                currentCashReceipt,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="updateDateTime != null">
                                #{updateDateTime,jdbcType=TIMESTAMP}
                            </if>
                            <if test="currentCashReceipt != null">
                                #{currentCashReceipt,jdbcType=DECIMAL},
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/CashCountDetailMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/CashCountDetailMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/CashCountDetailMapper.xml"));
        assertThat(rewritten)
                .contains("""
                                    #{updateDateTime,jdbcType=TIMESTAMP},
                            """.stripTrailing())
                .contains("#{currentCashReceipt,jdbcType=DECIMAL},");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_INSERT_TRIM_MISSING_COMMA_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicInsertTrimDoesNotAddCommaBeforeFollowingLeadingComma() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.InterfaceCallDetailsMapper">
                    <insert id="insert">
                        insert into ns_interface_call_details
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="treeCode != null">treeCode,</if>
                            <if test="path != null">path,</if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="treeCode != null">
                                #{treeCode}
                            </if>
                            <if test="path != null">
                                ,#{path}
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/InterfaceCallDetailsMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/InterfaceCallDetailsMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/InterfaceCallDetailsMapper.xml")
        );
        assertThat(rewritten)
                .contains("#{treeCode}\n")
                .contains(",#{path}")
                .doesNotContain("#{treeCode},\n")
                .doesNotContain("#{treeCode},\r\n");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicBatchInsertAddsMissingForeachTupleCommas() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="insertBatch" parameterType="java.util.List">
                        insert into sample_user (id, name)
                        values
                        <foreach collection="list" item="item" separator=",">
                            (
                            #{item.id}
                            #{item.name}
                            )
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("(\n            #{item.id},\n            #{item.name}\n            )");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_FOREACH_TUPLE_MISSING_COMMA_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicBatchInsertAddsValuesAndRemovesForeachTrailingComma() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="insertBatch" parameterType="java.util.List">
                        insert into sample_user
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            id,
                            name,
                        </trim>
                        <foreach collection="list" item="item" separator=",">
                            (
                            #{item.id},
                            #{item.name},
                            )
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("""
                                </trim>
                                values
                                <foreach collection="list" item="item" separator=",">
                        """)
                .contains("#{item.id},\n            #{item.name}\n            )")
                .doesNotContain("#{item.name},\n            )");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MapperXmlRewriter.MYBATIS_BATCH_INSERT_ADD_VALUES_RULE,
                        MapperXmlRewriter.MYBATIS_FOREACH_TRAILING_COMMA_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicBatchInsertAddsMissingCommaBetweenForeachTuplePlaceholders() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.WorkbenchAppMapper">
                    <insert id="insertBatch" parameterType="java.util.List">
                        insert into ns_soss_workbench_app
                        (
                            `enterpriseId`,
                            `organizationId`,
                            `orderIndex`,
                            `moduleIcon`
                        )
                        values
                        <foreach collection="list" item="item" separator=",">
                            (
                            #{item.enterpriseId,jdbcType=BIGINT},
                            #{item.organizationId,jdbcType=BIGINT},
                            #{item.orderIndex,jdbcType=INTEGER}
                            #{item.moduleIcon,jdbcType=VARCHAR}
                            )
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/WorkbenchAppMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/WorkbenchAppMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/WorkbenchAppMapper.xml"));
        assertThat(rewritten)
                .contains("(\n            #{item.enterpriseId,jdbcType=BIGINT},")
                .contains("#{item.orderIndex,jdbcType=INTEGER},\n            #{item.moduleIcon,jdbcType=VARCHAR}")
                .contains("#{item.moduleIcon,jdbcType=VARCHAR}\n            )")
                .doesNotContain("#{item.orderIndex,jdbcType=INTEGER}\n            #{item.moduleIcon,jdbcType=VARCHAR}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_FOREACH_TUPLE_MISSING_COMMA_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicBatchInsertQualifiesBareListItemReferences() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ContractRoomMapper">
                    <insert id="insertBatch" parameterType="java.util.List">
                        insert into ns_contract_room
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="contractId != null">
                                ContractID,
                            </if>
                            <if test="houseId != null">
                                houseId
                            </if>
                        </trim>
                        values
                        <foreach collection="list" item="item" index="index" separator=",">
                            (
                            <if test="contractId != null">
                                #{contractId, jdbcType=VARCHAR},
                            </if>
                            <if test="houseId != null">
                                #{houseId, jdbcType=BIGINT},
                            </if>
                            )
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ContractRoomMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ContractRoomMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ContractRoomMapper.xml"));
        assertThat(rewritten)
                .contains("<if test=\"list != null and list.size() &gt; 0 and list[0].contractId != null\">")
                .contains("<if test=\"list != null and list.size() &gt; 0 and list[0].houseId != null\">")
                .contains("<foreach collection=\"list\" item=\"item\" index=\"index\" separator=\",\">\n"
                        + "            <trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">")
                .contains("<if test=\"item.contractId != null\">")
                .contains("#{item.contractId, jdbcType=VARCHAR}")
                .contains("<if test=\"item.houseId != null\">")
                .contains("#{item.houseId, jdbcType=BIGINT}")
                .doesNotContain("<if test=\"contractId != null\">")
                .doesNotContain("#{contractId, jdbcType=VARCHAR}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MapperXmlRewriter.MYBATIS_BATCH_INSERT_LIST_ITEM_REFERENCE_RULE,
                        MapperXmlRewriter.MYBATIS_FOREACH_TRAILING_COMMA_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicTemporaryTableAsSelectSplitsScalarForeachBindingsIntoParameterizedInsert() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="createTmp">
                        create TEMPORARY table tmp_relationship_owner_20200204
                        SELECT rs.owner_id, rs.house_id
                        FROM owner_house_relationship rs
                        where rs.house_id in
                        <foreach collection="list" item="houseId" open="(" separator="," close=")">
                            #{houseId,jdbcType=BIGINT}
                        </foreach>
                        GROUP BY rs.house_id
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("BEGIN")
                .contains("CREATE GLOBAL TEMPORARY TABLE tmp_relationship_owner_20200204 ON COMMIT PRESERVE ROWS AS SELECT")
                .contains("SELECT * FROM (")
                .contains("NULL")
                .contains(") dm_adapter_ctas_source WHERE 1 = 0'")
                .contains("EXECUTE IMMEDIATE 'INSERT INTO tmp_relationship_owner_20200204")
                .contains("?\n")
                .contains("' USING")
                .contains("<foreach collection=\"list\" item=\"houseId\" separator=\",\">")
                .contains("#{houseId,jdbcType=BIGINT}")
                .doesNotContain("${houseId}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_TEMPORARY_TABLE_BIND_SELECT_TO_INSERT_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicTemporaryTableAsSelectSplitsObjectForeachBindingsIntoParameterizedInsert() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="createTmp">
                        create TEMPORARY table tmp_owner
                        SELECT rs.owner_id, rs.house_id
                        FROM owner_house_relationship rs
                        where rs.house_id in
                        <foreach collection="records" item="item" open="(" separator="," close=")">
                            #{item.houseId}
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("BEGIN")
                .contains("CREATE GLOBAL TEMPORARY TABLE tmp_owner ON COMMIT PRESERVE ROWS AS SELECT")
                .contains("SELECT * FROM (")
                .contains("NULL")
                .contains(") dm_adapter_ctas_source WHERE 1 = 0'")
                .contains("EXECUTE IMMEDIATE 'INSERT INTO tmp_owner")
                .contains("?\n")
                .contains("<foreach collection=\"records\" item=\"item\" separator=\",\">")
                .contains("#{item.houseId}")
                .doesNotContain("${item}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_TEMPORARY_TABLE_BIND_SELECT_TO_INSERT_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicTemporaryTableAsSelectRetainsUnsupportedBindingsForManualReview() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <insert id="createTmp">
                        create TEMPORARY table tmp_owner
                        SELECT rs.owner_id, rs.house_id
                        FROM owner_house_relationship rs
                        where rs.owner_type = #{ownerType}
                        and rs.house_id in
                        <foreach collection="records" item="item" open="(" separator="," close=")">
                            #{item.houseId}
                        </foreach>
                    </insert>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("CREATE GLOBAL TEMPORARY TABLE tmp_owner ON COMMIT PRESERVE ROWS AS SELECT")
                .contains("#{ownerType}")
                .contains("#{item.houseId}")
                .doesNotContain("${ownerType}")
                .doesNotContain("${item.houseId}");
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason())
                .contains("CREATE TABLE AS SELECT does not support JDBC bind parameters")
                .contains("injection-unsafe")
                .contains("parameterized INSERT ... SELECT");
    }

    @Test
    void dynamicTemporaryTableAsSelectConvertsPrefixBeforeForeachSelect() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="createTmp">
                        create temporary table t_${tmpTableName}
                        <foreach collection="list" item="item" separator=" union all ">
                            select
                            <foreach collection="item" item="field" separator=",">
                                #{field.fieldValue} AS ${field.fieldName}
                            </foreach>
                            from dual
                        </foreach>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("BEGIN")
                .contains("EXECUTE IMMEDIATE 'CREATE GLOBAL TEMPORARY TABLE t_${tmpTableName}")
                .contains(") ON COMMIT PRESERVE ROWS'")
                .contains("<foreach collection=\"list[0]\" item=\"field\" separator=\",\">")
                .contains("${field.fieldName} VARCHAR(4000)")
                .contains("<foreach collection=\"list\" item=\"item\" separator=\";\">")
                .contains("EXECUTE IMMEDIATE 'insert into t_${tmpTableName}")
                .contains("<foreach collection=\"item\" item=\"field\" separator=\",\">")
                .contains("#{field.fieldValue}")
                .contains("USING")
                .doesNotContain("#{field.fieldValue} AS ${field.fieldName}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_TEMPORARY_TABLE_BIND_SELECT_TO_INSERT_RULE
                );
    }

    @Test
    void rewritesBacktickIdentifiersInSqlFragmentsAndDynamicSqlText() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <sql id="UserColumns">
                        u.`id`, u.`user_name`, u.`order`
                    </sql>
                    <select id="selectUsers">
                        select <include refid="UserColumns"/>
                        from `sys_user` u
                        <where>
                            u.`enabled` = "Y"
                            <if test="fieldName != null">
                                and `${fieldName}` = #{fieldValue}
                            </if>
                        </where>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("u.`id`, u.`user_name`, u.`order`")
                .contains("from `sys_user` u")
                .contains("u.`enabled` = 'Y'")
                .contains("and `${fieldName}` = #{fieldValue}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicSelectRewritesHavingAggregateAliasAcrossWhereTag() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ReportMapper">
                    <select id="collectByType">
                        SELECT
                        ChargeItem as typeName,
                        SUM(chargePaid) as chargePaid
                        from NS_Payment_ChargePayment
                        <where>
                            IsDelete = 0
                            <if test="enterpriseId != null">
                                and EnterpriseId = #{enterpriseId}
                            </if>
                        </where>
                        GROUP BY ChargeItemID
                        HAVING chargePaid != 0
                        ORDER BY ChargeItemID ASC
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ReportMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ReportMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ReportMapper.xml"));
        assertThat(rewritten)
                .contains("HAVING (SUM(chargePaid)) != 0")
                .doesNotContain("SUM((SUM(chargePaid)))")
                .doesNotContain("HAVING chargePaid != 0");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicSelectRewritesChooseAggregateAliasInHavingWithoutMovingToWhere() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ChargeMapper">
                    <sql id="lateFee_value_logic">cd.late_fee</sql>
                    <select id="getMonthPaymentDetail">
                        SELECT
                        cd.HouseId,
                        cd.OwnerId,
                        <choose>
                            <when test="payStatus == '待支付'">
                                sum(cd.Arrears -<include refid="lateFee_value_logic"/>) chargePaid
                            </when>
                            <when test="payStatus == '已支付'">
                                sum(cd.PaidChargeSum) chargePaid
                            </when>
                            <otherwise>
                                sum(cd.ChargeSum) chargePaid
                            </otherwise>
                        </choose>
                        FROM charge_customerchargedetail cd
                        <where>
                            cd.IsDelete = 0
                        </where>
                        GROUP BY cd.HouseId, cd.OwnerId
                        HAVING chargePaid > 0
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ChargeMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ChargeMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ChargeMapper.xml"));
        assertThat(rewritten)
                .contains("GROUP BY cd.HouseId, cd.OwnerId\n        <choose>")
                .contains("HAVING (sum(cd.Arrears -<include refid=\"lateFee_value_logic\"/>)) > 0")
                .contains("HAVING (sum(cd.PaidChargeSum)) > 0")
                .contains("HAVING (sum(cd.ChargeSum)) > 0")
                .doesNotContain("and chargePaid > 0")
                .doesNotContain("HAVING chargePaid > 0");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_DYNAMIC_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicSelectRewritesChooseAggregateAliasWithBranchCommasInHaving() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ChargeMapper">
                    <select id="getReminderPayment">
                        select
                        <choose>
                            <when test="filterLateFee == '1'">
                                sum(greatest(Arrears - ifnull(late_fee, 0), 0)) arrearsSum,
                            </when>
                            <otherwise>
                                sum(Arrears) as arrearsSum,
                            </otherwise>
                        </choose>
                        count(1) as standrdId,
                        HouseId,
                        OwnerId
                        from Charge_CustomerChargeDetail
                        where IsDelete = 0
                        <if test='groupType==null or groupType=="0"'>
                            GROUP BY HouseId, OwnerId
                        </if>
                        <if test='groupType=="1"'>
                            GROUP BY OwnerId
                        </if>
                        having arrearsSum > 0
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ChargeMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ChargeMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ChargeMapper.xml"));
        assertThat(rewritten)
                .contains("HAVING (sum(greatest(Arrears - ifnull(late_fee, 0), 0))) > 0")
                .contains("HAVING (sum(Arrears)) > 0")
                .contains("count(1) as standrdId")
                .doesNotContain("having arrearsSum > 0");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_DYNAMIC_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicSelectRemovesUnusedUserVariableInitializerAcrossXmlTags() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ReportMapper">
                    <select id="arrears">
                        SELECT *
                        FROM (
                            SELECT
                                @g := 1
                                ,ROW_NUMBER() OVER(PARTITION BY house_id ORDER BY account_book) n
                                ,house_id
                                <choose>
                                    <when test="includeMoney">
                                        ,sum(arrears) arrears
                                    </when>
                                    <otherwise>
                                        ,0 arrears
                                    </otherwise>
                                </choose>
                                ,account_book
                            FROM charge_detail
                            <where>
                                is_delete = 0
                            </where>
                        ) t
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ReportMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ReportMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ReportMapper.xml"));
        assertThat(rewritten)
                .doesNotContain("@g := 1")
                .contains("ROW_NUMBER() OVER(PARTITION BY house_id ORDER BY account_book) n")
                .contains(",house_id")
                .contains("<choose>");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_UNUSED_USER_VARIABLE_SELECT_ITEM_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicHavingRewritesComputedSelectAliasWithoutMovingItToWhere() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ReportMapper">
                    <select id="collectAccountChanges">
                        SELECT
                        concat(account_book, '/', charge_item) AS dataGroup,
                        concat(account_book, '/', history_charge_item) AS historyDataGroup,
                        sum(charge_sum) totalAmount
                        from charge_account
                        where tenant_id = #{tenantId}
                        <if test="enabled != null">
                        </if>
                        GROUP BY dataGroup, historyDataGroup
                        HAVING dataGroup is not null
                        and ifnull(dataGroup,'') != ifnull(historyDataGroup,'')
                        and totalAmount != 0
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ReportMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ReportMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ReportMapper.xml"));
        assertThat(rewritten)
                .contains("GROUP BY (concat(account_book, '/', charge_item)), "
                        + "(concat(account_book, '/', history_charge_item))")
                .contains("HAVING (concat(account_book, '/', charge_item)) is not null")
                .contains("ifnull((concat(account_book, '/', charge_item)),'') != "
                        + "ifnull((concat(account_book, '/', history_charge_item)),'')")
                .contains("and (sum(charge_sum)) != 0")
                .doesNotContain("\n        and (concat(account_book, '/', charge_item)) is not null")
                .doesNotContain("ifnull(dataGroup")
                .doesNotContain("ifnull(historyDataGroup")
                .doesNotContain("and dataGroup is not null")
                .doesNotContain("HAVING dataGroup is not null");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SELECT_ALIAS_TO_EXPRESSION_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_GROUP_BY_SELECT_ALIAS_TO_EXPRESSION_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicTrimHavingRewritesAggregateAlias() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.PaymentMapper">
                    <select id="selectAccountOrder">
                        select
                        o.OrderNo,
                        sum(p.AccountTotal - p.CancelAccountTotal) orderAmount
                        from NS_Payment_Order o
                        left join ns_payment_chargepayment p on o.OrderNo = p.OrderNo
                        <where>
                            o.OrderStatus = '已支付'
                        </where>
                        group by p.OrderNo
                        <trim prefix="HAVING" prefixOverrides="and">
                            <if test="transactionAmountList != null and transactionAmountList.size() > 0">
                                and orderAmount in
                                <foreach collection="transactionAmountList" item="item" open="(" separator="," close=")">
                                    #{item}
                                </foreach>
                            </if>
                        </trim>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/PaymentMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/PaymentMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/PaymentMapper.xml"));
        assertThat(rewritten)
                .contains("and (sum(p.AccountTotal - p.CancelAccountTotal)) in")
                .doesNotContain("and orderAmount in");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicNestedHavingMovesNonAggregateConditionsBeforeGroupBy() throws Exception {
        String originalXml = "\uFEFF" + """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.BillUsedMapper">
                    <select id="listPage2">
                        select a.Id
                        from NS_Bill_BillUsed a
                        <where>
                            a.IsDelete = 0
                            <if test="startTime != null and endTime != null">
                                and a.id in (select id from (SELECT
                                a.Id,b.IsDelete,a.PrecinctId
                                FROM
                                NS_Bill_BillUsed a
                                LEFT JOIN ns_bill_billuseddetail b ON a.id = b.BillUsedId
                                GROUP BY
                                a.id
                                HAVING
                                b.IsDelete = 0
                                <if test="precinctId != null">
                                    and a.PrecinctId = #{precinctId}
                                </if>
                                AND to_days(
                                Min( b.BeginDate )) &gt;= to_days(DATE_FORMAT(#{startTime},'%Y-%m-%d'))
                                AND to_days(
                                Max( b.EndDate )) &lt;= to_days(DATE_FORMAT(#{endTime},'%Y-%m-%d'))) a)
                            </if>
                        </where>
                        group by a.id
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/BillUsedMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/BillUsedMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/BillUsedMapper.xml"));
        assertThat(rewritten)
                .contains("WHERE\n                    b.IsDelete = 0")
                .contains("and a.PrecinctId = #{precinctId}")
                .contains("GROUP BY\n                a.id")
                .contains("HAVING\n                to_days(")
                .contains("AND to_days(")
                .doesNotContain("HAVING\n                b.IsDelete = 0");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicHavingInsideIfMovesWholeBlockBeforeAllGroupByBranches() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ChargeDetailMapper">
                    <select id="getArrearsReport">
                        select d.precinct_id, d.house_id, sum(d.charge_sum) charge_sum
                        from charge_detail d
                        where d.is_delete = 0
                        <choose>
                            <when test="type != null and type == 2">
                                group by d.precinct_id, d.house_id
                            </when>
                            <when test="type != null and type == 3">
                                group by d.precinct_id, d.house_id, year(d.calc_start_date)
                            </when>
                        </choose>
                        <if test="groupByReason == true">
                            ,d.arrearage_reason
                        </if>
                        <if test="houseIdList != null and houseIdList.size() > 0">
                            having d.house_id in
                            <foreach collection="houseIdList" item="item" open="(" separator="," close=")">
                                #{item}
                            </foreach>
                        </if>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ChargeDetailMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ChargeDetailMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path output = tempDir.resolve("src/main/resources/mapper-dm/ChargeDetailMapper.xml");
        String rewritten = Files.readString(output);
        String houseFilter = "<if test=\"houseIdList != null and houseIdList.size() > 0\">";
        assertThat(rewritten.indexOf(houseFilter)).isLessThan(rewritten.indexOf("<choose>"));
        assertThat(rewritten)
                .contains("and d.house_id in")
                .contains("group by d.precinct_id, d.house_id")
                .contains("group by d.precinct_id, d.house_id, year(d.calc_start_date)")
                .contains("<if test=\"groupByReason == true\">")
                .doesNotContainIgnoringCase("having d.house_id")
                .doesNotContain("</if>\n                                group by");
        assertThat(XmlSupport.parse(output).getDocumentElement().getTagName()).isEqualTo("mapper");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicHavingInsideIfIsRetainedForReviewWhenAggregateAndPlainConditionsAreMixed() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ChargeDetailMapper">
                    <select id="getArrearsReport">
                        select d.house_id, sum(d.charge_sum) charge_sum
                        from charge_detail d
                        group by d.house_id
                        <if test="minimum != null">
                            having d.house_id = #{houseId}
                            and sum(d.charge_sum) &gt;= #{minimum}
                        </if>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ChargeDetailMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ChargeDetailMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path output = tempDir.resolve("src/main/resources/mapper-dm/ChargeDetailMapper.xml");
        assertThat(Files.readString(output)).isEqualTo(originalXml);
        assertThat(XmlSupport.parse(output).getDocumentElement().getTagName()).isEqualTo("mapper");
        assertThat(result.manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("complete XML-tag and query-branch equivalence"));
    }

    @Test
    void dynamicHavingInsideIfExpandsAggregateAliasWithoutMovingTheXmlBlock() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.QualityMapper">
                    <select id="listSecurityInspectionPlan">
                        select a.id,
                               case
                                   when sum(case when p.status = 1 then 1 else 0 end) &gt; 0 then 2
                                   else 1
                               end taskStatus
                        from inspection_schedule a
                        left join inspection_project p on p.schedule_id = a.id
                        <where>
                            a.is_delete = 0
                        </where>
                        group by a.id
                        <if test="source != null">
                            having taskStatus = #{source}
                        </if>
                        order by a.id
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/QualityMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/QualityMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path output = tempDir.resolve("src/main/resources/mapper-dm/QualityMapper.xml");
        String rewritten = Files.readString(output);
        assertThat(rewritten)
                .contains("having (case")
                .contains("when sum(case when p.status = 1 then 1 else 0 end) &gt; 0 then 2")
                .contains("end) = #{source}")
                .contains("<if test=\"source != null\">")
                .doesNotContain("having taskStatus = #{source}");
        assertThat(XmlSupport.parse(output).getDocumentElement().getTagName()).isEqualTo("mapper");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicHavingInsideIfRetainsAggregateAliasThatAlsoReferencesUngroupedColumns() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.BillMapper">
                    <select id="getConfirmAccountList">
                        select p.id,
                               case
                                   when p.account_total - ifnull(sum(c.paid_amount), 0) = 0 then 2
                                   when p.account_total &gt; ifnull(sum(c.paid_amount), 0) then 3
                                   else 0
                               end accountStatus
                        from payment p
                        left join cancellation c on c.payment_id = p.id
                        group by p.id
                        <if test="accountStatusList != null and accountStatusList.size() > 0">
                            having accountStatus in
                            <foreach collection="accountStatusList" item="item" open="(" close=")" separator=",">
                                #{item}
                            </foreach>
                        </if>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/BillMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/BillMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path output = tempDir.resolve("src/main/resources/mapper-dm/BillMapper.xml");
        assertThat(Files.readString(output))
                .contains("having accountStatus in")
                .doesNotContain("having (case");
        assertThat(XmlSupport.parse(output).getDocumentElement().getTagName()).isEqualTo("mapper");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).singleElement()
                .satisfies(item -> assertThat(item.reason())
                        .contains("aggregate alias(es) [accountStatus]")
                        .contains("mixes aggregate output with non-aggregate column references"));
    }

    @Test
    void dynamicHavingInsideNestedForeachKeepsAlreadyValidAggregateExpression() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.QualityMapper">
                    <select id="scoreListPage">
                        select t.id
                        from assessment_task t
                        left join assessment_audit a on a.task_id = t.id
                        group by t.id
                        <if test="statusIds != null and statusIds.size() > 0">
                            <foreach collection="statusIds" item="item">
                                <if test="statusIds.size() == 1 and item == 3">
                                    having count(a.id) &gt; 0
                                </if>
                            </foreach>
                        </if>
                        order by t.id
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/QualityMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/QualityMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path output = tempDir.resolve("src/main/resources/mapper-dm/QualityMapper.xml");
        assertThat(Files.readString(output)).contains("having count(a.id) &gt; 0");
        assertThat(XmlSupport.parse(output).getDocumentElement().getTagName()).isEqualTo("mapper");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicChooseExpandsAggregateAliasInEveryHavingBranch() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.PaymentMapper">
                    <select id="listAllocationData">
                        select p.house_id, p.charge_item, sum(p.amount) totalAmount
                        from payment_detail p
                        where p.is_delete = 0
                        <choose>
                            <when test="summaryType == 1">
                                group by p.house_id
                                having totalAmount &lt;&gt; 0
                                order by p.house_id
                            </when>
                            <otherwise>
                                group by p.charge_item
                                having totalAmount &lt;&gt; 0
                                order by p.charge_item
                            </otherwise>
                        </choose>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/PaymentMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/PaymentMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path output = tempDir.resolve("src/main/resources/mapper-dm/PaymentMapper.xml");
        String rewritten = Files.readString(output);
        String expandedHaving = "having (sum(p.amount)) &lt;&gt; 0";
        assertThat(rewritten)
                .contains(expandedHaving)
                .doesNotContain("having totalAmount &lt;&gt; 0");
        assertThat(rewritten.indexOf(expandedHaving)).isNotEqualTo(rewritten.lastIndexOf(expandedHaving));
        assertThat(XmlSupport.parse(output).getDocumentElement().getTagName()).isEqualTo("mapper");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicHavingInsideIfCreatesMyBatisWhereWhenNoWhereExists() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ChargeDetailMapper">
                    <select id="listCharges">
                        select d.house_id, sum(d.charge_sum)
                        from charge_detail d
                        group by d.house_id
                        <if test="houseId != null">
                            having d.house_id = #{houseId}
                        </if>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ChargeDetailMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ChargeDetailMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path output = tempDir.resolve("src/main/resources/mapper-dm/ChargeDetailMapper.xml");
        String rewritten = Files.readString(output);
        assertThat(rewritten)
                .contains("<where>")
                .contains("and d.house_id = #{houseId}")
                .contains("</where>")
                .contains("group by d.house_id")
                .doesNotContainIgnoringCase("having d.house_id");
        assertThat(rewritten.indexOf("<where>")).isLessThan(rewritten.indexOf("group by d.house_id"));
        assertThat(XmlSupport.parse(output).getDocumentElement().getTagName()).isEqualTo("mapper");
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicMysqlHelpTopicStringSplitBecomesDamengCrossApply() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.CityCompanyMapper">
                    <select id="getCompanyIdsByServiceCategoryIds">
                        select distinct ss.companyId
                        from (
                            select s.companyId,
                                   substring_index(
                                       substring_index(s.serviceCategoryIds, ',', b.help_topic_id + 1),
                                       ',',
                                       -1
                                   ) as serviceCategoryId
                            from ns_city_store s
                            join mysql.help_topic b
                              on b.help_topic_id &lt; (
                                  length(s.serviceCategoryIds)
                                  - length(replace(s.serviceCategoryIds, ',', ''))
                                  + 1
                              )
                        ) ss
                        where ss.serviceCategoryId in
                        <foreach collection="categoryIdList" item="item" separator="," open="(" close=")">
                            #{item}
                        </foreach>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/CityCompanyMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/CityCompanyMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve(
                "src/main/resources/mapper-dm/CityCompanyMapper.xml"
        ));
        assertThat(rewritten)
                .contains("REGEXP_SUBSTR(s.serviceCategoryIds, '[^,]+', 1, b.help_topic_id + 1)")
                .contains("CROSS APPLY (SELECT LEVEL - 1 AS help_topic_id FROM dual CONNECT BY LEVEL &lt;= "
                        + "LENGTH(s.serviceCategoryIds) - LENGTH(REPLACE(s.serviceCategoryIds, ',', '')) + 1) b")
                .doesNotContainIgnoringCase("mysql.help_topic")
                .doesNotContainIgnoringCase("substring_index");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_HELP_TOPIC_SPLIT_TO_CROSS_APPLY_RULE);
    }

    @Test
    void dynamicUngroupedHavingAliasBecomesWhereExpression() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ArrearsHouseMapper">
                    <select id="listArrearsHouse">
                        select t1.*,
                               IF(t2.house_id is not null and t1.detail_time &lt;= t2.record_time, 1, 0)
                                   as isFollowUp
                        from arrears_house t1
                        left join follow_record t2 on t1.house_id = t2.house_id
                        having 1 = 1
                        <if test="isFollowUp != null">
                            and isFollowUp = #{isFollowUp}
                        </if>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ArrearsHouseMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ArrearsHouseMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve(
                "src/main/resources/mapper-dm/ArrearsHouseMapper.xml"
        ));
        assertThat(rewritten)
                .contains("WHERE 1 = 1")
                .contains("and (IF(t2.house_id is not null"
                        + " and t1.detail_time &lt;= t2.record_time, 1, 0)) = #{isFollowUp}")
                .doesNotContain("having 1 = 1")
                .doesNotContain("and isFollowUp = #{isFollowUp}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SELECT_ALIAS_TO_EXPRESSION_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicUngroupedHavingAliasMovesIntoMyBatisWhere() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ArrearsHouseMapper">
                    <select id="listArrearsHouse">
                        select IF(f.house_id is not null, 1, 0) as isFollowUp
                        from arrears_house h
                        left join follow_record f on h.house_id = f.house_id
                        <where>
                            <if test="enabled != null">
                                h.enabled = #{enabled}
                            </if>
                        </where>
                        having 1 = 1
                        <if test="isFollowUp != null">
                            and isFollowUp = #{isFollowUp}
                        </if>
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ArrearsHouseMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ArrearsHouseMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve(
                "src/main/resources/mapper-dm/ArrearsHouseMapper.xml"
        ));
        assertThat(rewritten)
                .contains("<where>")
                .contains("and 1 = 1")
                .contains("and (IF(f.house_id is not null, 1, 0)) = #{isFollowUp}")
                .doesNotContain("having 1 = 1")
                .doesNotContain("and isFollowUp = #{isFollowUp}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(
                        MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SELECT_ALIAS_TO_EXPRESSION_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE
                );
    }

    @Test
    void dynamicUngroupedAggregateHavingKeepsHavingAndExpandsAlias() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ArrearsHouseMapper">
                    <select id="countArrearsHouse">
                        select count(*) as totalCount
                        from arrears_house
                        having totalCount &gt; 0
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ArrearsHouseMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ArrearsHouseMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve(
                "src/main/resources/mapper-dm/ArrearsHouseMapper.xml"
        ));
        assertThat(rewritten)
                .contains("having (count(*)) &gt; 0")
                .doesNotContain("WHERE")
                .doesNotContain("having totalCount");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_HAVING_AGGREGATE_ALIAS_RULE);
    }

    @Test
    void dynamicHavingMovesSingleSimpleConditionAndAddsWhere() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUsers">
                        select status, count(*) countValue
                        from sys_user
                        <if test="enabled != null">
                        </if>
                        group by status
                        having status = 'A'
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("WHERE")
                .contains("status = 'A'")
                .doesNotContain("having status = 'A'");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicHavingMovesSimpleConditionsIntoExistingWhereWithoutJoiningGroupBy() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUsers">
                        select status, count(*) countValue
                        from sys_user
                        where tenant_id = #{tenantId}
                        <if test="enabled != null">
                        </if>
                        group by status
                        having status is not null and tenant_id is not null
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("and status is not null")
                .contains("and tenant_id is not null")
                .contains("group by status")
                .doesNotContain("nullgroup")
                .doesNotContain("nullGROUP")
                .doesNotContain("having tenant_id is not null");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicHavingMovesSimpleConditionWithoutMovingTrailingSemicolon() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ProcessMapper">
                    <select id="getDoneInstCount">
                        SELECT count(DISTINCT id_) AS "count", type_id_ AS "typeId"
                        FROM (
                            SELECT wfInst.id_, wfInst.type_id_
                            FROM bpm_check_opinion bco
                            INNER JOIN bpm_pro_inst wfInst ON bco.PROC_INST_ID_ = wfInst.ID_
                            WHERE auditor_ = #{ew.paramNameValuePairs.userId}
                            <if test="ew.paramNameValuePairs.leaders != null">
                            </if>
                        ) AS combined
                        GROUP BY combined.type_id_
                        HAVING combined.type_id_ IS NOT NULL;
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/ProcessMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ProcessMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ProcessMapper.xml"));
        assertThat(rewritten)
                .contains("WHERE\n")
                .contains("combined.type_id_ IS NOT NULL")
                .contains("GROUP BY combined.type_id_")
                .doesNotContain("combined.type_id_ IS NOT NULL;\n")
                .doesNotContain("HAVING combined.type_id_ IS NOT NULL");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void dynamicHavingKeepsComplexOrConditionsForManualReview() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUsers">
                        select status, count(*) countValue
                        from sys_user
                        <where>
                            enabled = 1
                        </where>
                        group by status
                        having status = 'A' or status = 'B'
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("having status = 'A' or status = 'B'")
                .doesNotContain("and status = 'A'");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void missingMapperStatementIdIsReportedForManualReview() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select resultType="string">
                        select NOW() from dual
                    </select>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        assertThat(Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml")))
                .contains("select NOW() from dual")
                .doesNotContain("SYSDATE");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).statementId()).isEqualTo("(missing id: <select>)");
        assertThat(result.manualReviewItems().get(0).reason())
                .contains("missing required id attribute")
                .contains("text-preserving rewrite");
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("missing required id attribute");
    }

    @Test
    void defaultYearWeekIsAutomaticallyConverted() throws Exception {
        Path mapper = writeMapper(
                "src/main/resources/mapper/UserMapper.xml",
                "select YEARWEEK(created_at) from user"
        );
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(true).build(),
                new MySqlToDmSqlConverter()
        );

        assertThat(result.automaticConversions()).singleElement()
                .satisfies(change -> assertThat(change.appliedRules())
                        .containsExactly(MySqlToDmSqlConverter.MYSQL_YEARWEEK_RULE));
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void migrationRewritesJsonTableInnerJoinToCrossJoin() throws Exception {
        Path mapper = writeMapper(
                "src/main/resources/mapper/SalaryManagementMapper.xml",
                """
                        select w.id, jt.salaryId, jt.money
                        from ns_user_salary_temp_wide w
                        inner join JSON_TABLE(
                            case when JSON_VALID(w.salaryDetailJson) then cast(w.salaryDetailJson as json) else JSON_ARRAY() end,
                            '$[*]' columns (
                                salaryId bigint path '$.salaryId',
                                money decimal(18,2) path '$.money'
                            )
                        ) jt
                        where w.createUserId = #{createUserId}
                        """
        );
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/SalaryManagementMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/SalaryManagementMapper.xml"));
        assertThat(rewritten)
                .contains("CROSS JOIN JSON_TABLE")
                .doesNotContain("inner join JSON_TABLE");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_JSON_TABLE_JOIN_TO_DM_CROSS_JOIN_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void rewritesUpdateJoinFollowedByDynamicWhere() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateBalance">
                        update ns_payment_prepayment npp
                        INNER JOIN ns_payment_prepaymentdetail nppd
                            on npp.Id = nppd.RefPrePaymentID AND nppd.IsDelete = 0
                        set npp.Balance = npp.Balance + nppd.OccurBalance * -1,
                            npp.AddSum = npp.AddSum - nppd.OccurBalance
                        <where>
                            nppd.ChargePaymentID = #{id}
                            AND npp.Balance + nppd.OccurBalance * -1 >= 0
                        </where>
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/UserMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("update ns_payment_prepayment npp set Balance = npp.Balance + nppd.OccurBalance * -1")
                .contains("AddSum = npp.AddSum - nppd.OccurBalance")
                .contains("from ns_payment_prepaymentdetail nppd")
                .contains("<where>")
                .contains("npp.Id = nppd.RefPrePaymentID AND nppd.IsDelete = 0")
                .contains("and nppd.ChargePaymentID = #{id}")
                .doesNotContain("INNER JOIN");
        assertThat(result.automaticConversions()).singleElement()
                .satisfies(change -> assertThat(change.appliedRules())
                        .contains(MapperXmlRewriter.MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE));
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void rewritesUpdateJoinFollowedByOnlyDynamicWhereConditions() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/HouseMapper.xml", """
                update sample_owner_house owner_row
                inner join sample_house house_row
                    on owner_row.house_id = house_row.house_id
                   and owner_row.precinct_id = house_row.precinct_id
                   and owner_row.house_name != house_row.house_name
                   and owner_row.precinct_id = #{precinctId}
                set owner_row.house_name = house_row.house_name
                <where>
                    <if test="houseIds != null and houseIds.size() > 0">
                        AND owner_row.house_id in
                        <foreach collection="houseIds" item="houseId" open="(" close=")" separator=",">
                            #{houseId}
                        </foreach>
                    </if>
                </where>
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/HouseMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/HouseMapper.xml"));
        assertThat(rewritten)
                .contains("update sample_owner_house owner_row set house_name = house_row.house_name")
                .contains("from sample_house house_row")
                .contains("<where>")
                .contains("owner_row.house_id = house_row.house_id")
                .contains("and owner_row.precinct_id = house_row.precinct_id")
                .contains("<if test=\"houseIds != null and houseIds.size() > 0\">")
                .contains("AND owner_row.house_id in")
                .doesNotContainIgnoringCase("inner join");
        assertThat(result.automaticConversions()).singleElement()
                .satisfies(change -> assertThat(change.appliedRules())
                        .contains(MapperXmlRewriter.MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE));
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void neutralizesMybatisPlaceholdersInsideSqlLineComments() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/UserMapper.xml", """
                select count(*)
                from ns_system_organization
                where organization_code = #{organizationCode}
                -- LOCATE(#{organizationCode}, organization_code) > 0 and ${rawCondition}
                and organization_id != #{organizationId}
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("organization_code = #{organizationCode}")
                .contains("and organization_id != #{organizationId}")
                .contains("-- LOCATE(# {organizationCode}, organization_code) &gt; 0 and $ {rawCondition}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_SQL_LINE_COMMENT_PLACEHOLDER_NEUTRALIZED_RULE);
    }

    @Test
    void convertsMysqlHashLineCommentInDynamicMapperSql() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/MessageWarehouseMapper.xml", """
                SELECT id, enterpriseId
                FROM ns_message_warehouse
                WHERE 1 = 1
                <if test="enterpriseId != null">
                    AND enterpriseId = #{enterpriseId}
                </if>
                # 防止ooM
                limit 2000
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/MessageWarehouseMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/MessageWarehouseMapper.xml")
        );
        assertThat(rewritten)
                .contains("-- 防止ooM")
                .doesNotContain("# 防止ooM");
        assertThat(result.automaticConversions())
                .anySatisfy(change -> assertThat(change.appliedRules())
                        .contains(MySqlToDmSqlConverter.MYSQL_HASH_LINE_COMMENT_RULE));
    }

    @Test
    void quotesDynamicKeywordAliasReferencesSplitAcrossXmlNodes() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/OwnerHouseResultMapper.xml", """
                select base.house_id,
                       cluster.house_id as clusterId,
                       cluster.cluster_no as clusterNo
                <choose>
                    <when test="includeBuilder != null">
                       ,cluster.builder
                    </when>
                </choose>
                from owner_house_base_info base
                left join owner_house_cluster_info cluster on base.cluster_id = cluster.house_id
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/OwnerHouseResultMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/OwnerHouseResultMapper.xml"));
        assertThat(rewritten)
                .contains("\"cluster\".house_id as clusterId")
                .contains("\"cluster\".cluster_no as clusterNo")
                .contains(",\"cluster\".builder")
                .contains("left join owner_house_cluster_info \"cluster\" on base.cluster_id = \"cluster\".house_id")
                .doesNotContain(" cluster.house_id as clusterId")
                .doesNotContain(" cluster.cluster_no as clusterNo");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(
                        MySqlToDmSqlConverter.DAMENG_KEYWORD_TABLE_ALIAS_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_DAMENG_KEYWORD_ALIAS_REFERENCE_RULE
                );
    }

    @Test
    void preservesBacktickKeywordAliasAcrossDynamicXmlNodes() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/OwnerHouseResultMapper.xml", """
                select cluster.house_id as clusterId
                <if test="includeBuilder != null">
                   ,cluster.builder
                </if>
                from owner_house_cluster_info `cluster`
                where cluster.house_id = #{houseId}
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/OwnerHouseResultMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/OwnerHouseResultMapper.xml"));
        assertThat(rewritten)
                .contains("select `cluster`.house_id as clusterId")
                .contains(",`cluster`.builder")
                .contains("from owner_house_cluster_info `cluster`")
                .contains("where `cluster`.house_id = #{houseId}")
                .doesNotContain("\"cluster\"");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(
                        MySqlToDmSqlConverter.DAMENG_KEYWORD_TABLE_ALIAS_RULE,
                        MapperXmlRewriter.MYBATIS_DYNAMIC_DAMENG_KEYWORD_ALIAS_REFERENCE_RULE
                );
    }

    @Test
    void removesUnusedMysqlUserVariableInitializerSplitAcrossDynamicXmlNodes() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/UserMapper.xml", """
                select wrapped.id
                from (
                    select @unused := 0, u.id
                    <if test="includeName">
                        , u.name
                    </if>
                    from sys_user u
                ) wrapped
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/UserMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"));
        assertThat(rewritten)
                .contains("select u.id")
                .contains(", u.name")
                .doesNotContain("@unused");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_UNUSED_USER_VARIABLE_SELECT_ITEM_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsDescendantUserVariableTraversalAroundSqlIncludeToDamengHierarchyQuery() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/CategoryMapper.xml", """
                select
                <include refid="Category_Column_List" />
                from category_tree
                where deleted = 0 and node_id in (select node_id from (
                    select ordered.node_id,
                    if(find_in_set(parent_node_id, @descendants) > 0,
                       @descendants := concat(@descendants, ',', node_id), 0) as is_child
                    from (
                        select node_id, parent_node_id
                        from category_tree source_tree
                        order by parent_node_id, node_id
                    ) ordered,
                    (select @descendants := #{parentNodeId}) seed
                ) discovered where is_child != 0)
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/CategoryMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/CategoryMapper.xml")
        );
        assertThat(rewritten)
                .contains("<include refid=\"Category_Column_List\" />")
                .contains("WHERE deleted = 0 AND node_id IN (")
                .contains("SELECT node_id")
                .contains("FROM category_tree")
                .contains("START WITH parent_node_id = #{parentNodeId}")
                .contains("CONNECT BY NOCYCLE PRIOR node_id = parent_node_id")
                .doesNotContain("@descendants")
                .doesNotContainIgnoringCase("find_in_set");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_HIERARCHY_USER_VARIABLE_TO_DM_CONNECT_BY_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsAccumulatedHouseAncestorTraversalToDamengHierarchyQuery() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/HouseMapper.xml", """
                SELECT
                <include refid="House_Column_List"/>
                FROM (
                    SELECT @node_ids idlist,
                           (SELECT @node_ids := GROUP_CONCAT(parent_id SEPARATOR ',')
                            FROM sample_house
                            WHERE FIND_IN_SET(node_id, @node_ids)) sub
                    FROM sample_house,
                         (SELECT @node_ids := #{nodeId}) vars
                    WHERE @node_ids IS NOT NULL
                ) accumulated,
                sample_house house_row
                WHERE FIND_IN_SET(house_row.node_id, accumulated.idlist)
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/HouseMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/HouseMapper.xml"));
        assertThat(rewritten)
                .contains("<include refid=\"House_Column_List\"/>")
                .contains("FROM (")
                .contains("SELECT house_row.*")
                .contains("FROM sample_house house_row")
                .contains("START WITH house_row.node_id = #{nodeId}")
                .contains("CONNECT BY NOCYCLE PRIOR house_row.parent_id = house_row.node_id")
                .doesNotContain("@node_ids")
                .doesNotContainIgnoringCase("find_in_set");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_HIERARCHY_USER_VARIABLE_TO_DM_CONNECT_BY_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsAccumulatedHouseDescendantTraversalToDamengHierarchyQuery() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/HouseMapper.xml", """
                SELECT node_id, parent_id, node_level
                FROM (
                    SELECT @node_ids idlist,
                           (SELECT @node_ids := GROUP_CONCAT(node_id SEPARATOR ',')
                            FROM sample_house
                            WHERE FIND_IN_SET(parent_id, @node_ids)) sub
                    FROM sample_house,
                         (SELECT @node_ids := #{nodeId}) vars
                    WHERE @node_ids IS NOT NULL
                ) accumulated,
                sample_house house_row
                WHERE FIND_IN_SET(house_row.parent_id, accumulated.idlist)
                  AND tenant_id = #{tenantId}
                ORDER BY node_id
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/HouseMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/HouseMapper.xml"));
        assertThat(rewritten)
                .contains("SELECT house_row.*")
                .contains("FROM sample_house house_row")
                .contains("START WITH house_row.parent_id = #{nodeId}")
                .contains("CONNECT BY NOCYCLE PRIOR house_row.node_id = house_row.parent_id")
                .contains("WHERE tenant_id = #{tenantId}")
                .contains("ORDER BY node_id")
                .doesNotContain("@node_ids")
                .doesNotContainIgnoringCase("find_in_set");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsAccumulatedOrganizationDescendantTraversalInsideDynamicWhere() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/PortalMapper.xml", """
                select portal.id
                from sample_portal portal
                <where>
                    portal.deleted = 0
                    <if test="organizationId != null">
                        and portal.organization_id in (
                            select organization_id from (
                                select ordered.organization_id,
                                       if(find_in_set(parent_id, @organization_ids) > 0,
                                          @organization_ids := concat(@organization_ids, ',', organization_id), 0) as is_child
                                from (
                                    select organization_id, parent_id
                                    from sample_organization source_row
                                    order by parent_id, organization_id
                                ) ordered,
                                (select @organization_ids := #{organizationId}) seed
                            ) discovered
                            where is_child != 0 or organization_id = #{organizationId}
                        )
                    </if>
                </where>
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/PortalMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/PortalMapper.xml"));
        assertThat(rewritten)
                .contains("SELECT organization_id FROM sample_organization")
                .contains("START WITH organization_id = #{organizationId}")
                .contains("CONNECT BY NOCYCLE PRIOR organization_id = parent_id")
                .doesNotContain("@organization_ids")
                .doesNotContainIgnoringCase("find_in_set");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsCursorOrganizationAncestorTraversalInsideDynamicStatement() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/PortalMapper.xml", """
                select portal.id
                from sample_portal portal
                where portal.organization_id in (
                    select current_id from (
                        SELECT @cursor AS current_id,
                               (SELECT @cursor := parent_id
                                FROM sample_organization
                                WHERE organization_id = current_id) AS scratch,
                               @depth := @depth + 1 AS hierarchy_level
                        FROM (SELECT @cursor := #{organizationId}) vars,
                             sample_organization organization_row
                        WHERE @cursor != 0
                    ) ancestors
                )
                <if test="active != null">
                    and portal.active = #{active}
                </if>
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/PortalMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/PortalMapper.xml"));
        assertThat(rewritten)
                .contains("SELECT organization_id FROM sample_organization")
                .contains("START WITH organization_id = #{organizationId}")
                .contains("CONNECT BY NOCYCLE PRIOR parent_id = organization_id")
                .contains("and portal.active = #{active}")
                .doesNotContain("@cursor")
                .doesNotContain("@depth");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsPeriodDiffWithYearMonthParameterInsideDynamicXml() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/TaskMapper.xml", """
                select t.id
                from sample_task t
                <where>
                    t.deleted = 0
                    <if test="criteria.statisticsType != null and criteria.statisticsType == 2">
                        and PERIOD_DIFF(DATE_FORMAT(t.finished_at, '%Y%m'), #{criteria.yearMonth}) = 0
                    </if>
                </where>
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/TaskMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        Path rewrittenMapper = tempDir.resolve("src/main/resources/mapper-dm/TaskMapper.xml");
        String rewritten = Files.readString(rewrittenMapper);
        assertThat(rewritten)
                .contains("(YEAR(t.finished_at) * 12 + MONTH(t.finished_at))")
                .contains("CAST(#{criteria.yearMonth} AS DECIMAL(38, 0))")
                .contains("&lt; 70")
                .contains("&lt; 100")
                .doesNotContain(" < 70")
                .doesNotContain(" < 100")
                .doesNotContainIgnoringCase("PERIOD_DIFF")
                .doesNotContainIgnoringCase("DATE_FORMAT");
        assertThat(XmlSupport.parse(rewrittenMapper).getDocumentElement().getTagName()).isEqualTo("mapper");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_PERIOD_DIFF_YEARMONTH_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsDecimalDivisionInsideDynamicReportSql() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/PaymentReportMapper.xml", """
                SELECT
                <if test="includeMonthRatio">
                    DAY(end_time) / DAY(LAST_DAY(end_time)) AS month_ratio,
                </if>
                (paid_amount - IFNULL(delay_amount, 0)) / (1 + IFNULL(tax_rate, 0)) AS net_amount,
                SUM(CASE WHEN active = 1 THEN paid_amount ELSE 0 END) / 10000 AS ten_thousands
                FROM sample_payment
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/PaymentReportMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/PaymentReportMapper.xml")
        );
        assertThat(rewritten)
                .contains("CAST(DAY(end_time) AS DECIMAL(38,10))")
                .contains("NULLIF(CAST(DAY(LAST_DAY(end_time)) AS DECIMAL(38,10)), 0)")
                .contains("CAST((paid_amount - IFNULL(delay_amount, 0)) AS DECIMAL(38,10))")
                .contains("NULLIF(CAST((1 + IFNULL(tax_rate, 0)) AS DECIMAL(38,10)), 0)")
                .contains("CAST(SUM(CASE WHEN active = 1 THEN paid_amount ELSE 0 END) AS DECIMAL(38,10))")
                .doesNotContain("||");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsNestedDateSubInsideDynamicXml() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/ReportMapper.xml", """
                select
                <if test="rangeType == 1">
                    DATE_FORMAT(
                        DATE_SUB(
                            DATE_SUB(#{rangeStart}, INTERVAL WEEKDAY(#{rangeStart}) DAY),
                            INTERVAL 1 WEEK
                        ),
                        '%Y-%m-%d 00:00:00'
                    )
                </if>
                rangeStart
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/ReportMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/ReportMapper.xml"));
        assertThat(rewritten)
                .contains("DATEADD(WEEK, -1,")
                .contains("DATEADD(DAY, (0 - WEEKDAY(#{rangeStart})), #{rangeStart})")
                .doesNotContainIgnoringCase("DATE_SUB");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_DATE_SUB_INTERVAL_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void castsMysqlImplicitNumericIntervalValuesInsideMapperXml() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/TaskMapper.xml", """
                select SUBDATE(f.begin_time, interval - #{customerEvalHour} hour)
                from sample_flow f
                where TIMESTAMPDIFF(MINUTE, f.begin_time, now())
                    >= IFNULL(f.timeout_value,#{timeoutMinute,jdbcType=INTEGER})
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/TaskMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/TaskMapper.xml"));
        assertThat(rewritten)
                .contains("DATEADD(HOUR, CAST(#{customerEvalHour} AS BIGINT), f.begin_time)")
                .contains("TO_NUMBER(IFNULL(f.timeout_value,#{timeoutMinute,jdbcType=INTEGER}))");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(
                        MySqlToDmSqlConverter.MYSQL_SUBDATE_RULE,
                        MySqlToDmSqlConverter.MYSQL_NUMERIC_IFNULL_COMPARISON_TO_NUMBER_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsUpdateJoinWithLimitedDerivedSelectionAndAdditionalSource() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/TaskMapper.xml", """
                update sample_task target
                join (
                    select candidate.id
                    from sample_task candidate
                    join sample_transfer filter_transfer
                      on filter_transfer.owner_id = candidate.owner_id
                    where filter_transfer.id = #{criteria.transferId}
                    limit #{criteria.rowCount}
                ) selected on target.id = selected.id
                join sample_transfer transfer on transfer.owner_id = target.owner_id
                set target.owner_id = transfer.new_owner_id,
                    target.updated_by = #{operatorId}
                where transfer.id = #{criteria.transferId}
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/TaskMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/TaskMapper.xml"));
        assertThat(rewritten)
                .contains("update sample_task target set owner_id = transfer.new_owner_id")
                .contains("from (")
                .contains("select candidate.id")
                .contains(") selected, sample_transfer transfer where target.id = selected.id")
                .contains("transfer.owner_id = target.owner_id")
                .doesNotContainIgnoringCase("update sample_task target\n            join");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsUpdateOfJoinedTableAfterLimitedDerivedSelection() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/AssigneeMapper.xml", """
                update sample_task target
                join (
                    select candidate.id
                    from sample_task candidate
                    join sample_transfer filter_transfer
                      on filter_transfer.owner_id = candidate.owner_id
                    where filter_transfer.id = #{criteria.transferId}
                    limit #{criteria.rowCount}
                ) selected on target.id = selected.id
                join sample_transfer transfer on transfer.scope_id = target.scope_id
                join sample_task_assignee assignee on assignee.task_id = target.id
                set assignee.owner_id = transfer.new_owner_id,
                    assignee.updated_by = #{operatorId}
                where transfer.id = #{criteria.transferId}
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/AssigneeMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/AssigneeMapper.xml"));
        assertThat(rewritten)
                .contains("update sample_task_assignee assignee set owner_id = transfer.new_owner_id")
                .contains("from sample_task target, (")
                .contains(") selected, sample_transfer transfer where target.id = selected.id")
                .contains("assignee.task_id = target.id")
                .doesNotContainIgnoringCase("set assignee.owner_id");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsDynamicOuterJoinUpdateOfJoinedTargetToRowIdMerge() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.FlowMapper">
                    <update id="transferCurrentUser">
                        update sample_ticket ticket
                        left join sample_flow flow on ticket.flow_id = flow.id
                        set flow.current_user_id =
                            case when flow.current_user_id = 0 then 0 else #{newUserId} end,
                            flow.current_user_name =
                            replace(flow.current_user_name, #{oldUserName}, #{newUserName})
                        where 1 = 1
                        <if test="statuses != null and statuses.size() > 0">
                            and ticket.status in
                            <foreach collection="statuses" item="status" open="(" close=")" separator=",">
                                #{status}
                            </foreach>
                        </if>
                        and flow.current_user_id = #{oldUserId}
                    </update>
                </mapper>
                """;
        Path mapper = writeFile("src/main/resources/mapper/FlowMapper.xml", originalXml);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/FlowMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/FlowMapper.xml"));
        assertThat(rewritten)
                .contains("MERGE INTO sample_flow flow")
                .contains("SELECT DISTINCT flow.ROWID AS dm_target_rowid")
                .contains("FROM sample_ticket ticket")
                .contains("left join sample_flow flow on ticket.flow_id = flow.id")
                .contains("<if test=\"statuses != null and statuses.size() > 0\">")
                .contains("and flow.current_user_id = #{oldUserId}")
                .contains("ON (flow.ROWID = dm_update_source.dm_target_rowid)")
                .contains("WHEN MATCHED THEN UPDATE SET flow.current_user_id")
                .doesNotContainIgnoringCase("update sample_ticket ticket");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .contains(
                        MapperXmlRewriter.MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE,
                        MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE
                );
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void usesMatchingResultTypeColumnsForRecursiveStarCte() throws Exception {
        Path mapper = writeFile("src/main/resources/mapper/RegionsMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.RegionsMapper">
                    <resultMap id="BaseResultMap" type="com.example.Regions">
                        <id column="id" property="id"/>
                        <result column="parentId" property="parentId"/>
                        <result column="regionPath" property="regionPath"/>
                        <result column="level" property="level"/>
                        <result column="localName" property="localName"/>
                    </resultMap>
                    <select id="getAllByFirstLocalName" resultType="com.example.Regions">
                        WITH RECURSIVE SubAddresses AS (
                            SELECT * FROM ns_regions WHERE localName = #{localName}
                            UNION ALL
                            SELECT child.* FROM ns_regions child
                            JOIN SubAddresses parent ON child.parentId = parent.id
                        )
                        SELECT * FROM SubAddresses
                    </select>
                </mapper>
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/RegionsMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/RegionsMapper.xml")
        );
        assertThat(rewritten).contains(
                "WITH RECURSIVE SubAddresses(id, parentId, regionPath, level, localName) AS ("
        );
        assertThat(result.automaticConversions()).singleElement().satisfies(change ->
                assertThat(change.appliedRules())
                        .contains(MySqlToDmSqlConverter.MYSQL_WITH_RECURSIVE_ALIAS_RULE));
        assertThat(result.manualReviewItems()).isEmpty();
    }

    @Test
    void convertsGbkSortExpressionInsideDynamicOrderByChoose() throws Exception {
        Path mapper = writeFile("src/main/resources/mapper/TalentMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.TalentMapper">
                    <select id="listPage" resultType="map">
                        select id, name from talent
                        order by
                        <choose>
                            <when test="sortField == 'name'">
                                CONVERT(`name` USING gbk) COLLATE gbk_chinese_ci
                                <if test="sortOrder == 'asc'">asc</if>
                                <if test="sortOrder != 'asc'">desc</if>
                            </when>
                            <otherwise>id desc</otherwise>
                        </choose>
                    </select>
                </mapper>
                """);
        ProjectScanResult scanResult = new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(mapper.toString(), "mapper/TalentMapper.xml")),
                List.of()
        );

        MapperMigrationResult result = new MapperMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter()
        );

        String rewritten = Files.readString(
                tempDir.resolve("src/main/resources/mapper-dm/TalentMapper.xml")
        );
        assertThat(rewritten)
                .contains("NLSSORT(`name`, 'NLS_SORT=SCHINESE_PINYIN_M')")
                .doesNotContainIgnoringCase("USING gbk")
                .doesNotContainIgnoringCase("gbk_chinese_ci");
        assertThat(result.automaticConversions()).singleElement().satisfies(change ->
                assertThat(change.appliedRules())
                        .contains(MySqlToDmSqlConverter.MYSQL_CONVERT_GBK_ORDER_RULE));
        assertThat(result.manualReviewItems()).isEmpty();
    }

    private int countMatches(String value, String needle) {
        int count = 0;
        int index = 0;
        while (index < value.length()) {
            int match = value.indexOf(needle, index);
            if (match < 0) {
                return count;
            }
            count++;
            index = match + needle.length();
        }
        return count;
    }

    private Path writeMapper(String relativePath, String sql) throws Exception {
        Path mapper = tempDir.resolve(relativePath);
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <select id="selectUsers">
                        %s
                    </select>
                </mapper>
                """.formatted(sql));
        return mapper;
    }

    private Path writeFile(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
