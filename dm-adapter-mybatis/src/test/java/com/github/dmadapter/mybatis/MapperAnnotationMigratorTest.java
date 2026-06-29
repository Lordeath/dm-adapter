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
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

class MapperAnnotationMigratorTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsAnnotationSqlToMapperDmXmlAndRewritesIt() throws Exception {
        writeFile("src/main/java/com/example/VoucherTaskMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.Update;
                import java.util.List;

                public interface VoucherTaskMapper {
                    @Select("select NOW() from dual limit 1")
                    String selectNow();

                    @Select("select id from ns_bill_voucher_task")
                    List<Long> listIds();

                    @Update("update ns_bill_voucher_task " +
                            "set taskName = #{taskName}, " +
                            "    updateTime = NOW() " +
                            "where id = #{id}")
                    int update(VoucherTask voucherTask);
                }
                """);

        MapperMigrationResult result = new MapperAnnotationMigrator().migrate(
                scanResult(List.of()),
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                SqlRewriteConfig.empty()
        );

        String sourceXml = Files.readString(tempDir.resolve("src/main/resources/mapper/VoucherTaskMapper.xml"));
        String xml = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/VoucherTaskMapper.xml"));
        assertThat(sourceXml)
                .contains("<mapper namespace=\"com.example.VoucherTaskMapper\">")
                .contains("<select id=\"selectNow\" resultType=\"java.lang.String\">")
                .contains("NOW()")
                .contains("limit 1");
        assertThat(xml)
                .contains("<mapper namespace=\"com.example.VoucherTaskMapper\">")
                .contains("<select id=\"selectNow\" resultType=\"java.lang.String\">")
                .contains("<select id=\"listIds\" resultType=\"java.lang.Long\">")
                .contains("<update id=\"update\">")
                .contains("SYSDATE")
                .contains("FETCH FIRST 1 ROWS ONLY")
                .doesNotContain("NOW()");
        assertThat(result.fileChanges()).hasSize(2);
        assertThat(result.automaticConversions())
                .extracting(change -> change.statementId())
                .contains(
                        "com.example.VoucherTaskMapper.selectNow",
                        "com.example.VoucherTaskMapper.update"
                );
        assertThat(result.automaticConversions())
                .allSatisfy(change -> assertThat(change.appliedRules())
                        .contains(MapperAnnotationMigrator.MYBATIS_ANNOTATION_SQL_TO_MAPPER_DM_XML_RULE));
    }

    @Test
    void extractsAnnotationSqlWithoutRewritingExistingMapperDmStatements() throws Exception {
        writeFile("src/main/resources/mapper-dm/VoucherTaskMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.VoucherTaskMapper">
                    <insert id="insertSegment">
                        INSERT INTO "newsee-system".ns_system_organization (organization_id)
                        SELECT organization_id FROM ys_organization
                    </insert>
                </mapper>
                """);
        writeFile("src/main/java/com/example/VoucherTaskMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Select;

                public interface VoucherTaskMapper {
                    @Select("select NOW() from dual")
                    String selectNow();
                }
                """);

        MapperMigrationResult result = new MapperAnnotationMigrator().migrate(
                scanResult(List.of()),
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                SqlRewriteConfig.empty()
        );

        assertThat(Files.readString(tempDir.resolve("src/main/resources/mapper/VoucherTaskMapper.xml")))
                .contains("select NOW() from dual");
        String xml = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/VoucherTaskMapper.xml"));
        assertThat(xml)
                .contains("INSERT INTO \"newsee-system\".ns_system_organization")
                .doesNotContain("INSERT INTO 'newsee-system'.ns_system_organization")
                .contains("SYSDATE");
        assertThat(result.automaticConversions())
                .extracting(change -> change.statementId())
                .containsExactly("com.example.VoucherTaskMapper.selectNow");
    }

    @Test
    void doesNotDuplicateAnnotationMethodWhenXmlStatementAlreadyExists() throws Exception {
        Path mapper = writeFile("src/main/resources/mapper/VoucherTaskMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.VoucherTaskMapper">
                    <select id="selectNow">
                        select SYSDATE from dual
                    </select>
                </mapper>
                """);
        writeFile("src/main/java/com/example/VoucherTaskMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Select;

                public interface VoucherTaskMapper {
                    @Select("select NOW() from dual")
                    String selectNow();
                }
                """);
        ProjectScanResult scanResult = scanResult(List.of(new MapperXmlFile(
                mapper.toString(),
                tempDir.resolve("src/main/resources").toString(),
                "mapper/VoucherTaskMapper.xml"
        )));

        MapperMigrationResult result = new MapperAnnotationMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                SqlRewriteConfig.empty()
        );

        assertThat(result.fileChanges()).isEmpty();
        assertThat(Files.exists(tempDir.resolve("src/main/resources/mapper-dm/VoucherTaskMapper.xml"))).isFalse();
    }

    @Test
    void updatesExistingExtractedAnnotationSelectWithMissingResultType() throws Exception {
        writeFile("src/main/resources/mapper-dm/VoucherTaskMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.VoucherTaskMapper">
                    <select id="listIds">
                        <![CDATA[select id from ns_bill_voucher_task]]>
                    </select>
                </mapper>
                """);
        writeFile("src/main/java/com/example/VoucherTaskMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Select;
                import java.util.List;

                public interface VoucherTaskMapper {
                    @Select("select id from ns_bill_voucher_task")
                    List<Long> listIds();
                }
                """);

        MapperMigrationResult result = new MapperAnnotationMigrator().migrate(
                scanResult(List.of()),
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                SqlRewriteConfig.empty()
        );

        String xml = Files.readString(tempDir.resolve("src/main/resources/mapper-dm/VoucherTaskMapper.xml"));
        assertThat(result.fileChanges()).hasSize(2);
        assertThat(xml)
                .containsOnlyOnce("<select id=\"listIds\"")
                .contains("<select id=\"listIds\" resultType=\"java.lang.Long\">");
    }

    @Test
    void extractsAnnotationSqlFromCompiledMapperClasses() throws Exception {
        compileClassAnnotationFixture();

        MapperMigrationResult result = new MapperAnnotationMigrator().migrate(
                scanResult(List.of()),
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                SqlRewriteConfig.empty()
        );

        String sourceXml = Files.readString(tempDir.resolve("module/src/main/resources/mapper/CompiledMapper.xml"));
        assertThat(sourceXml)
                .contains("<mapper namespace=\"com.example.CompiledMapper\">")
                .contains("<select id=\"selectNow\" resultType=\"java.lang.String\">")
                .contains("NOW()")
                .contains("limit 1");
        String xml = Files.readString(tempDir.resolve("module/src/main/resources/mapper-dm/CompiledMapper.xml"));
        assertThat(xml)
                .contains("<mapper namespace=\"com.example.CompiledMapper\">")
                .contains("<select id=\"selectNow\" resultType=\"java.lang.String\">")
                .contains("<select id=\"listIds\" resultType=\"java.lang.Long\">")
                .contains("<update id=\"touch\">")
                .contains("SYSDATE")
                .contains("FETCH FIRST 1 ROWS ONLY")
                .doesNotContain("NOW()");
        assertThat(result.automaticConversions())
                .extracting(change -> change.statementId())
                .contains(
                        "com.example.CompiledMapper.selectNow",
                        "com.example.CompiledMapper.touch"
                );
    }

    private ProjectScanResult scanResult(List<MapperXmlFile> mapperXmlFiles) {
        return new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                mapperXmlFiles,
                List.of()
        );
    }

    private Path writeFile(String relativePath, String content) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private void compileClassAnnotationFixture() throws Exception {
        Path sourceRoot = tempDir.resolve("compiler-src");
        Path output = tempDir.resolve("module/target/classes");
        Path select = writeCompilerSource(sourceRoot, "org/apache/ibatis/annotations/Select.java", """
                package org.apache.ibatis.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface Select {
                    String[] value();
                }
                """);
        Path update = writeCompilerSource(sourceRoot, "org/apache/ibatis/annotations/Update.java", """
                package org.apache.ibatis.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface Update {
                    String[] value();
                }
                """);
        Path mapper = writeCompilerSource(sourceRoot, "com/example/CompiledMapper.java", """
                package com.example;

                import java.util.List;
                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.Update;

                public interface CompiledMapper {
                    @Select("select NOW() from dual limit 1")
                    String selectNow();

                    @Select("select id from task")
                    List<Long> listIds();

                    @Update("update task set updated_at = NOW() where id = #{id}")
                    int touch(Long id);
                }
                """);

        Files.createDirectories(output);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(output.toString());
        args.add("-source");
        args.add("17");
        args.add("-target");
        args.add("17");
        args.add(select.toString());
        args.add(update.toString());
        args.add(mapper.toString());
        assertThat(compiler.run(null, null, null, args.toArray(String[]::new))).isZero();
    }

    private Path writeCompilerSource(Path sourceRoot, String relativePath, String content) throws Exception {
        Path path = sourceRoot.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
