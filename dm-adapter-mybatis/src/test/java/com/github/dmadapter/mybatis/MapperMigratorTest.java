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
        assertThat(Files.readString(copied)).contains("SYSDATE");
        assertThat(Files.exists(tempDir.resolve("src/main/resources/mapper-dm/UserMapper.xml"))).isFalse();
        assertThat(result.automaticConversions()).hasSize(1);
    }

    @Test
    void dryRunReportsCopyAndSqlChangesWithoutWritingTarget() throws Exception {
        Path mapper = writeMapper("src/main/resources/mapper/UserMapper.xml", """
                select IFNULL(name, 'n/a') from user limit #{offset}, #{size}
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
        assertThat(result.automaticConversions().get(0).convertedSql()).contains("NVL(");
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
                .contains("SYSDATE")
                .contains("FETCH FIRST 5 ROWS ONLY")
                .doesNotContain("standalone=\"no\"");
        assertThat(result.automaticConversions()).hasSize(1);
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
                .contains("SELECT #{record.id} AS id, #{record.state} AS \"state\", SYSDATE AS createTime FROM dual")
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
                                select SYSDATE from dual FETCH FIRST 5 ROWS ONLY
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
    void dynamicXmlKeepsSafeTextConversionsWhenRemainingSqlNeedsManualReview() throws Exception {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.AuditMapper">
                    <select id="selectAudit" resultType="map">
                        <if test="enabled != null">
                            select `user`, JSON_SET(payload, '$.name', 'x') from audit_log limit 1
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
                .contains("select \"user\", JSON_SET(payload, '$.name', 'x') from audit_log FETCH FIRST 1 ROWS ONLY");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                        "LIMIT_TO_DM_FETCH"
                );
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason())
                .contains("dynamic XML", "JSON_SET");
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
                .contains("update ns_system_entry_org eo")
                .contains("<if test=\"'secondaryDepartment' ==  entryOrgLevel\">")
                .contains("set eo.secondaryDepartmentId = o.organization_id")
                .contains("from ns_system_organization o")
                .contains("where")
                .contains("eo.${entryOrgParentId} = o.organization_parent_id")
                .contains("and eo.deleteFlag = 0")
                .doesNotContain("inner JOIN ns_system_organization");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly(MapperXmlRewriter.MYBATIS_DYNAMIC_UPDATE_JOIN_TO_DM_UPDATE_FROM_RULE);
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
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
                .contains("u.id, u.user_name, u.\"order\"")
                .contains("from sys_user u")
                .contains("u.enabled = 'Y'")
                .contains("and ${fieldName} = #{fieldValue}")
                .doesNotContain("`");
        assertThat(result.automaticConversions()).hasSize(2);
        assertThat(result.automaticConversions())
                .allSatisfy(sqlChange -> assertThat(sqlChange.appliedRules())
                        .contains(MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE));
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
                "select JSON_SET(profile, '$.name', #{name}) from user"
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
        assertThat(result.manualReviewItems().get(0).reason()).contains("JSON_SET");
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
