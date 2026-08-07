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
        assertThat(Files.readString(tempDir.resolve("src/main/java/com/example/VoucherTaskMapper.java")))
                .doesNotContain("@Select")
                .doesNotContain("@Update")
                .contains("String selectNow();")
                .contains("int update(VoucherTask voucherTask);");
        assertThat(xml)
                .contains("<mapper namespace=\"com.example.VoucherTaskMapper\">")
                .contains("<select id=\"selectNow\" resultType=\"java.lang.String\">")
                .contains("<select id=\"listIds\" resultType=\"java.lang.Long\">")
                .contains("<update id=\"update\">")
                .contains("NOW()")
                .contains("limit 1");
        assertThat(result.fileChanges()).hasSize(3);
        assertThat(result.automaticConversions()).isEmpty();
    }

    @Test
    void annotationSelectUsesPhysicalColumnAndReportsResultTypeAutoMapping() throws Exception {
        writeFile("src/main/java/com/example/PaymentOrderMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Select;

                public interface PaymentOrderMapper {
                    @Select("select trxid from NS_PAYMENT_ORDER where trxid = #{trxid}")
                    String findTrxid(String trxid);
                }
                """);

        MapperMigrationResult dryRun = new MapperAnnotationMigrator().migrate(
                scanResult(List.of()),
                AdapterContext.builder(tempDir).dryRun(true).build(),
                new MySqlToDmSqlConverter(),
                SqlRewriteConfig.empty()
        );

        assertThat(dryRun.automaticConversions())
                .singleElement()
                .satisfies(change -> assertThat(change.convertedSql())
                        .isEqualTo("select _trxid from NS_PAYMENT_ORDER where _trxid = #{trxid}"));
        assertThat(dryRun.manualReviewItems())
                .singleElement()
                .satisfies(change -> assertThat(change.reason()).contains("resultType/automatic mapping"));

        MapperMigrationResult applied = new MapperAnnotationMigrator().migrate(
                scanResult(List.of()),
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                SqlRewriteConfig.empty()
        );

        assertThat(Files.readString(tempDir.resolve("src/main/resources/mapper-dm/PaymentOrderMapper.xml")))
                .contains("select _trxid from NS_PAYMENT_ORDER where _trxid = #{trxid}");
        assertThat(applied.manualReviewItems())
                .singleElement()
                .satisfies(change -> assertThat(change.reason()).contains("resultType/automatic mapping"));
    }

    @Test
    void extractsAnnotationSqlWithoutRewritingExistingMapperDmStatements() throws Exception {
        writeFile("src/main/resources/mapper-dm/VoucherTaskMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.VoucherTaskMapper">
                    <insert id="insertSegment">
                        INSERT INTO "sample-system".ns_system_organization (organization_id)
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
                .contains("INSERT INTO \"sample-system\".ns_system_organization")
                .doesNotContain("INSERT INTO 'sample-system'.ns_system_organization")
                .contains("select NOW() from dual");
        assertThat(result.automaticConversions()).isEmpty();
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

        String javaSource = Files.readString(tempDir.resolve("src/main/java/com/example/VoucherTaskMapper.java"));
        assertThat(javaSource)
                .doesNotContain("@Select")
                .contains("String selectNow();");
        assertThat(result.fileChanges())
                .extracting(change -> change.description())
                .containsExactly("Removed extracted MyBatis annotation SQL from Java mapper");
        assertThat(Files.exists(tempDir.resolve("src/main/resources/mapper-dm/VoucherTaskMapper.xml"))).isFalse();
    }

    @Test
    void cleansVoucherTaskAnnotationSqlWhenXmlStatementsAlreadyExistAndRepairsMissingSetCommas() throws Exception {
        Path mapper = writeFile("src/main/resources/mapper/VoucherTaskMapper.xml", voucherTaskExistingXml());
        writeFile("src/main/java/com/sample/bill/dao/VoucherTaskMapper.java", voucherTaskMapperSource());
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

        String xml = Files.readString(mapper);
        assertThat(xml)
                .containsOnlyOnce("id=\"insert\"")
                .containsOnlyOnce("id=\"insertWithUpdateInfo\"")
                .containsOnlyOnce("id=\"selectById\"")
                .containsOnlyOnce("id=\"deleteItemRelByTaskId\"")
                .containsOnlyOnce("id=\"deletePrecinctRelByTaskId\"")
                .containsOnlyOnce("id=\"changeEnableFlag\"")
                .containsOnlyOnce("id=\"deleteById\"")
                .containsOnlyOnce("id=\"selectIncludeItemIdByTaskId\"")
                .containsOnlyOnce("id=\"selectRelPrecinctIdByTaskId\"")
                .containsOnlyOnce("id=\"selectByTaskName\"")
                .containsOnlyOnce("id=\"selectRelOrganizationIdByTaskId\"")
                .containsOnlyOnce("id=\"selectReceiptTask\"")
                .containsOnlyOnce("id=\"sleep\"")
                .containsOnlyOnce("id=\"realDeleteByTaskId\"")
                .containsOnlyOnce("id=\"update\"")
                .contains("voucherTemplateId    = #{voucherTemplateId}, accountBooK")
                .contains("usedDateEnd    = #{usedDateEnd}, mergeFlag")
                .contains("limit 1")
                .contains("select SLEEP(${elapseSecond})")
                .contains(" value (#{id}");

        String javaSource = Files.readString(tempDir.resolve("src/main/java/com/sample/bill/dao/VoucherTaskMapper.java"));
        assertThat(javaSource)
                .doesNotContain("@Insert")
                .doesNotContain("@Select")
                .doesNotContain("@Update")
                .doesNotContain("@Delete")
                .contains("int update(VoucherTask voucherTask);")
                .contains("Long sleep(@Param(\"elapseSecond\") Long elapseSecond);");
        assertThat(result.fileChanges())
                .extracting(change -> change.description())
                .contains(
                        "Fixed extracted MyBatis annotation SQL in mapper XML",
                        "Removed extracted MyBatis annotation SQL from Java mapper"
                );
    }

    @Test
    void javaParserExtractsArrayAndTextBlockSqlAndPreservesNonSqlAnnotations() throws Exception {
        writeFile("src/main/java/com/example/ParserMapper.java", """
                package com.example;

                import org.apache.ibatis.annotations.Select;
                import org.apache.ibatis.annotations.Update;

                public interface ParserMapper {
                    @Deprecated
                    @Select({"select ", "id from task"})
                    Long selectId();

                    @Update(value = \"""
                            update task
                            set name = #{name} owner = #{owner}
                            where id = #{id}
                            \""")
                    int updateTask(Task task);
                }
                """);

        MapperMigrationResult result = new MapperAnnotationMigrator().migrate(
                scanResult(List.of()),
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                SqlRewriteConfig.empty()
        );

        String sourceXml = Files.readString(tempDir.resolve("src/main/resources/mapper/ParserMapper.xml"));
        assertThat(sourceXml)
                .contains("<select id=\"selectId\" resultType=\"java.lang.Long\">")
                .containsPattern("select\\s+id from task")
                .contains("<update id=\"updateTask\">")
                .contains("name = #{name}, owner = #{owner}");
        String javaSource = Files.readString(tempDir.resolve("src/main/java/com/example/ParserMapper.java"));
        assertThat(javaSource)
                .contains("@Deprecated")
                .doesNotContain("@Select")
                .doesNotContain("@Update")
                .contains("Long selectId();")
                .contains("int updateTask(Task task);");
        assertThat(result.fileChanges())
                .extracting(change -> change.description())
                .contains("Removed extracted MyBatis annotation SQL from Java mapper");
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
        assertThat(result.fileChanges()).hasSize(3);
        assertThat(xml)
                .containsOnlyOnce("<select id=\"listIds\"")
                .contains("<select id=\"listIds\" resultType=\"java.lang.Long\">");
    }

    @Test
    void preservesExistingMapperLineSeparatorsWhenExtractingAnnotationSql() throws Exception {
        Path mapper = writeFile("src/main/resources/mapper/VoucherTaskMapper.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<mapper namespace=\"com.example.VoucherTaskMapper\">\n"
                        + "    <select id=\"existing\">\n"
                        + "        select 1\n"
                        + "    </select>\n"
                        + "</mapper>\n");
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

        new MapperAnnotationMigrator().migrate(
                scanResult,
                AdapterContext.builder(tempDir).dryRun(false).build(),
                new MySqlToDmSqlConverter(),
                SqlRewriteConfig.empty()
        );

        String xml = Files.readString(mapper);
        assertThat(xml)
                .doesNotContain("\r\n")
                .contains("\n    <select id=\"selectNow\" resultType=\"java.lang.String\">\n");
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
                .contains("NOW()")
                .contains("limit 1");
        assertThat(result.automaticConversions()).isEmpty();
        assertThat(result.fileChanges())
                .noneSatisfy(change -> assertThat(change.path()).endsWith(".java"));
    }

    private String voucherTaskMapperSource() {
        return """
                package com.sample.bill.dao;

                import com.alibaba.fastjson.JSONObject;
                import com.sample.bill.dto.VoucherTaskRelDTO;
                import com.sample.bill.entity.VoucherTask;
                import com.sample.bill.entity.VoucherTaskItemRel;
                import com.sample.bill.entity.VoucherTaskPrecinctRel;
                import org.apache.ibatis.annotations.*;
                import org.springframework.stereotype.Repository;

                import java.util.List;

                @Repository
                public interface VoucherTaskMapper {

                    @Insert("insert into ns_bill_voucher_task (id, enterpriseId, organizationId, taskName, voucherType, voucherTypeDes, taskType, cron, cronDes, pushType, workTimeBegin, workTimeEnd, createUserId, createUserName, enableFlag, voucherTemplateId, accountBooK, documentTemplateId, operatorStart, operatorEnd, contrastAccountBook, houseIdJson, businessDate, voucherTypeCode, squareTypeIdJson, usedDateStart, usedDateEnd, mergeFlag) " +
                            "value (#{id},#{enterpriseId},#{organizationId},#{taskName},#{voucherType},#{voucherTypeDes},#{taskType},#{cron},#{cronDes},#{pushType},#{workTimeBegin},#{workTimeEnd},#{createUserId},#{createUserName},#{enableFlag},#{voucherTemplateId},#{accountBooK},#{documentTemplateId},#{operatorStart},#{operatorEnd},#{contrastAccountBook},#{houseIdJson}, #{businessDate}, #{voucherTypeCode}, #{squareTypeIdJson}, #{usedDateStart}, #{usedDateEnd}, #{mergeFlag})")
                    int insert(VoucherTask voucherTask);

                    @Insert("insert into ns_bill_voucher_task (id, enterpriseId, organizationId, taskName, voucherType, voucherTypeDes, taskType, cron, cronDes, pushType, workTimeBegin, workTimeEnd, createUserId, createUserName, enableFlag, updateUserId, updateUserName, voucherTemplateId, accountBooK, documentTemplateId, operatorStart, operatorEnd, contrastAccountBook, houseIdJson, businessDate, voucherTypeCode, squareTypeIdJson, usedDateStart, usedDateEnd, mergeFlag) " +
                            "value (#{id},#{enterpriseId},#{organizationId},#{taskName},#{voucherType},#{voucherTypeDes},#{taskType},#{cron},#{cronDes},#{pushType},#{workTimeBegin},#{workTimeEnd},#{createUserId},#{createUserName},#{enableFlag},#{updateUserId},#{updateUserName},#{voucherTemplateId},#{accountBooK},#{documentTemplateId},#{operatorStart},#{operatorEnd},#{contrastAccountBook},#{houseIdJson},#{businessDate}, #{voucherTypeCode}, #{squareTypeIdJson}, #{usedDateStart}, #{usedDateEnd}, #{mergeFlag})")
                    int insertWithUpdateInfo(VoucherTask voucherTask);

                    int insertPrecinctRel(List<VoucherTaskPrecinctRel> precinctRelList);

                    int insertItemRel(List<VoucherTaskItemRel> itemRelList);

                    @Select("select * from ns_bill_voucher_task where id = #{id} and deleteFlag = 0")
                    VoucherTask selectById(@Param("id") Long id);

                    @Update("update ns_bill_voucher_task " +
                            "set taskName      = #{taskName}, " +
                            "    voucherType   = #{voucherType}, " +
                            "    voucherTypeDes= #{voucherTypeDes}, " +
                            "    taskType      = #{taskType}, " +
                            "    cron          = #{cron}, " +
                            "    cronDes       = #{cronDes}, " +
                            "    pushType      = #{pushType}, " +
                            "    workTimeBegin = #{workTimeBegin}, " +
                            "    workTimeEnd   = #{workTimeEnd}, " +
                            "    updateUserId  = #{updateUserId}, " +
                            "    updateUserName= #{updateUserName}, " +
                            "    enableFlag    = #{enableFlag}, " +
                            "    voucherTemplateId    = #{voucherTemplateId} " +
                            "    accountBooK    = #{accountBooK} " +
                            "    documentTemplateId    = #{documentTemplateId} " +
                            "    operatorStart    = #{operatorStart} " +
                            "    operatorEnd    = #{operatorEnd} " +
                            "    contrastAccountBook    = #{contrastAccountBook} " +
                            "    houseIdJson    = #{houseIdJson} " +
                            "    businessDate    = #{businessDate} " +
                            "    squareTypeIdJson    = #{squareTypeIdJson} " +
                            "    usedDateStart    = #{usedDateStart} " +
                            "    usedDateEnd    = #{usedDateEnd} " +
                            "    mergeFlag    = #{mergeFlag} " +
                            "where id = #{id}")
                    int update(VoucherTask voucherTask);

                    @Delete("delete from ns_bill_voucher_task_item_rel where taskId = #{taskId}")
                    int deleteItemRelByTaskId(@Param("taskId") Long taskId);

                    @Delete("delete from ns_bill_voucher_task_precinct_rel where taskId = #{taskId}")
                    int deletePrecinctRelByTaskId(@Param("taskId") Long taskId);

                    @Update("update ns_bill_voucher_task set enableFlag = #{enableFlag} where id = #{id}")
                    int changeEnableFlag(VoucherTask voucherTask);

                    @Update("update ns_bill_voucher_task set deleteFlag = 1,taskName = #{taskName},updateUserId = #{updateUserId},updateUserName = #{updateUserName} where id = #{id}")
                    int deleteById(@Param("id") Long id, @Param("taskName") String taskName, @Param("updateUserId") Long updateUserId, @Param("updateUserName") String updateUserName);

                    Integer countPage(JSONObject searchVo);
                    List<VoucherTask> listPage(JSONObject searchVo);

                    @Select("select itemId from ns_bill_voucher_task_item_rel where rel = 1 and deleteFlag = 0 and taskId = #{taskId}")
                    List<Long> selectIncludeItemIdByTaskId(@Param("taskId") Long taskId);

                    @Select("select precinctId from ns_bill_voucher_task_precinct_rel where deleteFlag = 0 and taskId = #{taskId}")
                    List<Long> selectRelPrecinctIdByTaskId(@Param("taskId") Long taskId);

                    @Select("select * from ns_bill_voucher_task where taskName = #{taskName} and deleteFlag = 0")
                    VoucherTask selectByTaskName(@Param("taskName")String taskName);

                    List<VoucherTaskRelDTO> selectRelByVoucherType(@Param("voucherType") Integer voucherType);

                    @Select("select organizationId from ns_bill_voucher_task_precinct_rel where deleteFlag = 0 and taskId = #{taskId}")
                    List<Long> selectRelOrganizationIdByTaskId(@Param("taskId") Long taskId);

                    List<VoucherTaskRelDTO> selectImmediateByPrecinctAndVoucherType(@Param("precinctId") Long precinctId, @Param("voucherType") Integer voucherType);

                    @Select("select * from ns_bill_voucher_task where enableFlag = 1 and deleteFlag = 0 and voucherType = #{voucherType} order by createDateTime desc limit 1")
                    VoucherTask selectReceiptTask(@Param("voucherType") Integer voucherType);

                    VoucherTask selectReceiptTaskByPrecinctId(@Param("precinctId") Long precinctId);

                    @Select("select SLEEP(${elapseSecond})")
                    Long sleep(@Param("elapseSecond") Long elapseSecond);

                    @Delete("delete from ns_bill_voucher_task where id = #{taskId}")
                    int realDeleteByTaskId(@Param("taskId") Long taskId);

                    void updateBillVoucherBatch(@Param("precinctIds") List<Long> precinctIds,@Param("voucherTypeDes") String voucherTypeDes);

                    List<VoucherTask> listTaskByPrecinctId(@Param("precinctId") Long precinctId, @Param("voucherType") Integer voucherType);
                }
                """;
    }

    private String voucherTaskExistingXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.sample.bill.dao.VoucherTaskMapper">
                    <insert id="insert">
                        <![CDATA[
                            insert into ns_bill_voucher_task (id) value (#{id})
                        ]]>
                    </insert>
                    <insert id="insertWithUpdateInfo">
                        <![CDATA[
                            insert into ns_bill_voucher_task (id) value (#{id})
                        ]]>
                    </insert>
                    <select id="selectById">
                        <![CDATA[
                            select * from ns_bill_voucher_task where id = #{id}
                        ]]>
                    </select>
                    <update id="update">
                        <![CDATA[
                            update ns_bill_voucher_task set taskName = #{taskName}, voucherTemplateId    = #{voucherTemplateId}     accountBooK    = #{accountBooK}     usedDateEnd    = #{usedDateEnd}     mergeFlag    = #{mergeFlag} where id = #{id}
                        ]]>
                    </update>
                    <delete id="deleteItemRelByTaskId"><![CDATA[delete from ns_bill_voucher_task_item_rel where taskId = #{taskId}]]></delete>
                    <delete id="deletePrecinctRelByTaskId"><![CDATA[delete from ns_bill_voucher_task_precinct_rel where taskId = #{taskId}]]></delete>
                    <update id="changeEnableFlag"><![CDATA[update ns_bill_voucher_task set enableFlag = #{enableFlag} where id = #{id}]]></update>
                    <update id="deleteById"><![CDATA[update ns_bill_voucher_task set deleteFlag = 1 where id = #{id}]]></update>
                    <select id="selectIncludeItemIdByTaskId"><![CDATA[select itemId from ns_bill_voucher_task_item_rel where taskId = #{taskId}]]></select>
                    <select id="selectRelPrecinctIdByTaskId"><![CDATA[select precinctId from ns_bill_voucher_task_precinct_rel where taskId = #{taskId}]]></select>
                    <select id="selectByTaskName"><![CDATA[select * from ns_bill_voucher_task where taskName = #{taskName}]]></select>
                    <select id="selectRelOrganizationIdByTaskId"><![CDATA[select organizationId from ns_bill_voucher_task_precinct_rel where taskId = #{taskId}]]></select>
                    <select id="selectReceiptTask"><![CDATA[select * from ns_bill_voucher_task where enableFlag = 1 and deleteFlag = 0 and voucherType = #{voucherType} order by createDateTime desc limit 1]]></select>
                    <select id="sleep"><![CDATA[select SLEEP(${elapseSecond})]]></select>
                    <delete id="realDeleteByTaskId"><![CDATA[delete from ns_bill_voucher_task where id = #{taskId}]]></delete>
                </mapper>
                """;
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
