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
                .contains("AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR } \t,'XXXXXXXX')")
                .doesNotContain(",\"XXXXXXXX\"");
        assertThat(result.automaticConversions()).hasSize(1);
        assertThat(result.automaticConversions().get(0).appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
        assertThat(result.manualReviewItems()).hasSize(1);
        assertThat(result.manualReviewItems().get(0).reason()).contains("dynamic XML");
    }

    @Test
    void mysqlSpecificFunctionIsMarkedForManualReview() throws Exception {
        Path mapper = writeMapper(
                "src/main/resources/mapper/UserMapper.xml",
                "select JSON_EXTRACT(profile, '$.name') from user"
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
        assertThat(result.manualReviewItems().get(0).reason()).contains("JSON_EXTRACT");
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
