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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
    }

    @Test
    void migrationWrapsConfiguredIdentityInsertTableWithDynamicColumnList() throws Exception {
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
                .contains("BEGIN")
                .contains("SET IDENTITY_INSERT ns_equip_area_class ON;")
                .contains("insert into ns_equip_area_class")
                .contains("<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">")
                .contains("<foreach collection=\"list\" item=\"item\" separator=\",\">")
                .contains("SET IDENTITY_INSERT ns_equip_area_class OFF;")
                .contains("END;");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.DAMENG_IDENTITY_INSERT_RULE);
    }

    @Test
    void migrationWrapsConfiguredIdentityInsertTableWithTrimValuesPrefix() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.BankAccountMapper">
                    <insert id="insertSelective" parameterType="com.example.BankAccount">
                        insert into owner_customer_bank_account
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="ownerBankAccountId != null">
                                owner_bank_account_id,
                            </if>
                            <if test="ownerId != null">
                                owner_id,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="ownerBankAccountId != null">
                                #{ownerBankAccountId,jdbcType=BIGINT},
                            </if>
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
                .contains("SET IDENTITY_INSERT owner_customer_bank_account ON;")
                .contains("<trim prefix=\"values (\" suffix=\")\" suffixOverrides=\",\">")
                .contains("SET IDENTITY_INSERT owner_customer_bank_account OFF;");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.DAMENG_IDENTITY_INSERT_RULE);
    }

    @Test
    void migrationWrapsConfiguredIdentityInsertTableWhenMysqlOmitsIntoKeyword() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.ThirdAddHouseInfoMapper">
                    <insert id="insertThirdHouseEntity" parameterType="list">
                        insert zj_add_house_info
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            id,
                            enterprise_id,
                            roomName
                        </trim>
                        values
                        <foreach collection="list" item="item" separator=",">
                            (#{item.id}, #{item.enterpriseId}, #{item.roomName})
                        </foreach>
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
                .contains("SET IDENTITY_INSERT zj_add_house_info ON;")
                .contains("insert zj_add_house_info")
                .contains("SET IDENTITY_INSERT zj_add_house_info OFF;");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.DAMENG_IDENTITY_INSERT_RULE);
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
    void dynamicSqlIsMarkedForManualReview() throws Exception {
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

        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
    void dynamicXmlKeepsSafeTextConversionsWhenRemainingSqlNeedsManualReview() throws Exception {
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
                .contains("select 'ACTIVE' as status, YEARWEEK(created_at) from audit_log");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason())
                .contains("dynamic XML", "YEARWEEK");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
                .contains("inner join tmp_owner_precinct_result_20200322")
                .contains("b.precinct_id = t.precinct_id")
                .doesNotContain("BEGIN");
        assertThat(result.automaticConversions()).isEmpty();
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
                .contains("join ns_quality_check_schedule_task_user u")
                .contains("join ns_quality_day_task_transfer b")
                .contains("<if test=\"importantFlag != null\">")
                .contains("and a.searchStartDate between b.startDate and b.endDate")
                .doesNotContain("BEGIN");
        assertThat(result.automaticConversions()).isEmpty();
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
                .contains("nu.sentry_id = case c.`sentry_id` when \"0\" then nu.sentry_id else c.`sentry_id` end")
                .contains("nu.update_time = now()")
                .contains(",nu.v8_user_id = c.sso_user_id")
                .contains("WHERE\n            nu.enterprise_id = #{enterpriseId}");
        assertThat(result.automaticConversions()).isEmpty();
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
                .contains("TO_BASE64(SF_ENCRYPT_CHAR(#{userPassword, jdbcType=VARCHAR }, 513, 'XXXXXXXX', NULL))")
                .doesNotContain(",\"XXXXXXXX\"");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_AES_BASE64_TO_DM_AES128_ECB_RULE
                );
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
    }

    @Test
    void dynamicTemporaryTableAsSelectKeepsScalarForeachBindings() throws Exception {
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
                .contains("CREATE GLOBAL TEMPORARY TABLE tmp_relationship_owner_20200204 ON COMMIT PRESERVE ROWS AS SELECT")
                .contains("#{houseId,jdbcType=BIGINT}")
                .doesNotContain("${houseId}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE);
    }

    @Test
    void dynamicTemporaryTableAsSelectKeepsObjectForeachBindings() throws Exception {
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
                .contains("CREATE GLOBAL TEMPORARY TABLE tmp_owner ON COMMIT PRESERVE ROWS AS SELECT")
                .contains("#{item.houseId}")
                .doesNotContain("${item}");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE);
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
                        SUM(ChargePaid) as chargePaid
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
                .contains("HAVING (SUM(ChargePaid)) != 0")
                .doesNotContain("HAVING chargePaid != 0");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_AGGREGATE_ALIAS_TO_EXPRESSION_RULE);
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
                        GROUP BY account_book, charge_item
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
                        MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SELECT_ALIAS_TO_EXPRESSION_RULE
                );
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
    }

    @Test
    void dynamicNestedHavingMovesSimpleConditionsToWhere() throws Exception {
        String originalXml = """
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
                .contains("WHERE")
                .contains("b.IsDelete = 0")
                .contains("and a.PrecinctId = #{precinctId}")
                .contains("HAVING\n                to_days(")
                .doesNotContain("HAVING\n                b.IsDelete = 0");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
                .doesNotContain("having status is not null");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_HAVING_SIMPLE_CONDITION_TO_WHERE_RULE);
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
    void mysqlSpecificFunctionIsMarkedForManualReview() throws Exception {
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

        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("YEARWEEK");
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
    void skipsTextSegmentUpdateJoinWhenFollowedByDynamicWhere() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                    <update id="updateBalance">
                        update ns_payment_prepayment npp
                        INNER JOIN ns_payment_prepaymentdetail nppd on npp.Id = nppd.RefPrePaymentID
                        set npp.Balance = npp.Balance + nppd.OccurBalance
                        <where>
                            nppd.ChargePaymentID = #{id}
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
                .contains("INNER JOIN ns_payment_prepaymentdetail")
                .contains("<where>")
                .doesNotContain(" from ns_payment_prepaymentdetail nppd where npp.Id = nppd.RefPrePaymentID");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason())
                .contains("MyBatis <where>")
                .contains("duplicate WHERE");
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
