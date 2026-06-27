package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

class MapperJdbcTypeAlignerTest {
    @TempDir
    Path tempDir;

    private final MapperJdbcTypeAligner aligner = new MapperJdbcTypeAligner();

    @Test
    void alignsUpdateForeachJdbcTypeFromDamengColumnMetadata() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/NsCoreDictionaryitemMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.NsCoreDictionaryitemMapper">
                    <update id="updateBatchByDictionaryIdAndCode" parameterType="list">
                        <foreach collection="list" separator=";" item="item">
                            update ns_core_dictionaryitem set
                            DICTIONARYITEM_ITEMNAME = #{item.dictionaryitemItemname}
                            where DICTIONARYITEM_ITEMCODE = #{item.dictionaryitemItemcode, jdbcType=BIGINT }
                            and DICTIONARYITEM_DICTIONARY_ID = #{item.dictionaryitemDictionaryId}
                        </foreach>
                    </update>
                </mapper>
                """);

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of("ns_core_dictionaryitem", Map.of("dictionaryitem_itemcode", "CLOB"))
        );

        String rewritten = Files.readString(tempDir.resolve("module/src/main/resources/mapper-dm/NsCoreDictionaryitemMapper.xml"));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(rewritten)
                .contains("#{item.dictionaryitemItemcode, jdbcType=VARCHAR }")
                .doesNotContain("#{item.dictionaryitemItemcode, jdbcType=BIGINT }");
    }

    @Test
    void alignsDynamicInsertTrimJdbcTypeFromDamengColumnMetadata() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/SystemAreaMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.SystemAreaMapper">
                    <insert id="insert" parameterType="com.example.SystemArea">
                        insert into ns_system_area
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="areaName != null">
                                area_name,
                            </if>
                            <if test="orderNo != null">
                                order_no,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="areaName != null">
                                #{areaName, jdbcType=VARCHAR} ,
                            </if>
                            <if test="orderNo != null">
                                #{orderNo, jdbcType=VARCHAR} ,
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """);

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of("ns_system_area", Map.of("order_no", "INT", "area_name", "VARCHAR"))
        );

        String rewritten = Files.readString(tempDir.resolve("module/src/main/resources/mapper-dm/SystemAreaMapper.xml"));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(rewritten)
                .contains("#{orderNo, jdbcType=INTEGER} ,")
                .contains("#{areaName, jdbcType=VARCHAR} ,");
    }

    @Test
    void addsMissingJdbcTypeForBatchInsertFromDamengColumnMetadata() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/NsContractRoomMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.NsContractRoomMapper">
                    <insert id="insertRooms" parameterType="java.util.List">
                        insert into ns_contract_room (ContractID, houseId, standardId, temporaryBillingArea)
                        values
                        <foreach collection="list" item="item" separator=",">
                            (#{item.contractId}, #{item.houseId}, #{item.standardId}, #{item.temporaryBillingArea})
                        </foreach>
                    </insert>
                </mapper>
                """);

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of("ns_contract_room", Map.of(
                        "contractid", "BIGINT",
                        "houseid", "BIGINT",
                        "standardid", "BIGINT",
                        "temporarybillingarea", "DECIMAL"
                ))
        );

        String rewritten = Files.readString(tempDir.resolve("module/src/main/resources/mapper-dm/NsContractRoomMapper.xml"));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(rewritten)
                .contains("#{item.contractId,jdbcType=BIGINT}")
                .contains("#{item.temporaryBillingArea,jdbcType=DECIMAL}");
    }

    @Test
    void replacesDanglingCommaWhenAddingMissingJdbcType() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/NsSystemUserMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.NsSystemUserMapper">
                    <insert id="insertBatch" parameterType="java.util.List">
                        insert into ns_system_user (create_user_name)
                        values
                        <foreach collection="list" item="item" separator=",">
                            (#{item.createUserName,})
                        </foreach>
                    </insert>
                </mapper>
                """);

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of("ns_system_user", Map.of("create_user_name", "VARCHAR"))
        );

        String rewritten = Files.readString(tempDir.resolve("module/src/main/resources/mapper-dm/NsSystemUserMapper.xml"));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(rewritten)
                .contains("#{item.createUserName,jdbcType=VARCHAR}")
                .doesNotContain(",,jdbcType");
    }

    @Test
    void castsStringPojoNumericPlaceholderFromDamengColumnMetadata() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/OwnerHouseResultMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.OwnerHouseResultMapper">
                    <insert id="insertSelective" parameterType="com.example.HouseListEntity">
                        insert into owner_house_result
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="hasRelevance != null">
                                has_relevance,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="hasRelevance != null">
                                #{hasRelevance,jdbcType=INTEGER},
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """);
        writeJava("src/main/java/com/example/HouseListEntity.java", """
                package com.example;

                public class HouseListEntity {
                    private String hasRelevance;
                }
                """);

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of("owner_house_result", Map.of("has_relevance", "INTEGER"))
        );

        String rewritten = Files.readString(tempDir.resolve("module/src/main/resources/mapper-dm/OwnerHouseResultMapper.xml"));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(rewritten)
                .contains("CAST(#{hasRelevance,jdbcType=VARCHAR} AS INTEGER)")
                .doesNotContain("#{hasRelevance,jdbcType=INTEGER}");
    }

    @Test
    void castsStringPojoNumericPlaceholderFromCompiledClassMetadata() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/OwnerHouseResultMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.OwnerHouseResultMapper">
                    <insert id="insertSelective" parameterType="com.example.HouseListEntity">
                        insert into owner_house_result
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="hasRelevance != null">
                                has_relevance,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="hasRelevance != null">
                                #{hasRelevance,jdbcType=INTEGER},
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """);
        compileJavaClass("com/example/HouseListEntity.java", """
                package com.example;

                public class HouseListEntity {
                    private String hasRelevance;
                }
                """);

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of("owner_house_result", Map.of("has_relevance", "INTEGER"))
        );

        String rewritten = Files.readString(tempDir.resolve("module/src/main/resources/mapper-dm/OwnerHouseResultMapper.xml"));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(result.warnings()).isEmpty();
        assertThat(rewritten)
                .contains("CAST(#{hasRelevance,jdbcType=VARCHAR} AS INTEGER)")
                .doesNotContain("#{hasRelevance,jdbcType=INTEGER}");
    }

    @Test
    void castsStringForeachItemNumericPlaceholderFromCompiledMapperMethodSignature() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/NSMeiDiEBSMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.NSMeiDiEBSMapper">
                    <insert id="batchInsertX_OUT_ACC_DATA_ITEM" parameterType="java.util.List">
                        insert into x_out_acc_data_item (ACC_SEGMENT_CODE, R_NO)
                        values
                        <foreach collection="list" item="item" separator=",">
                            (#{item.ACC_SEGMENT_CODE,jdbcType=VARCHAR}, #{item.R_NO,jdbcType=BIGINT})
                        </foreach>
                    </insert>
                </mapper>
                """);
        compileJavaClass("com/example/X_OUT_ACC_DATA_ITEM.java", """
                package com.example;

                public class X_OUT_ACC_DATA_ITEM {
                    private String ACC_SEGMENT_CODE;
                    private String R_NO;
                }
                """);
        compileJavaClass("com/example/NSMeiDiEBSMapper.java", """
                package com.example;

                import java.util.List;

                public interface NSMeiDiEBSMapper {
                    int batchInsertX_OUT_ACC_DATA_ITEM(List<X_OUT_ACC_DATA_ITEM> items);
                }
                """);

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of("x_out_acc_data_item", Map.of("acc_segment_code", "VARCHAR", "r_no", "BIGINT"))
        );

        String rewritten = Files.readString(tempDir.resolve("module/src/main/resources/mapper-dm/NSMeiDiEBSMapper.xml"));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(result.warnings()).isEmpty();
        assertThat(rewritten)
                .contains("#{item.ACC_SEGMENT_CODE,jdbcType=VARCHAR}")
                .contains("CAST(#{item.R_NO,jdbcType=VARCHAR} AS BIGINT)")
                .doesNotContain("#{item.R_NO,jdbcType=BIGINT}");
    }

    @Test
    void castsStringForeachItemWhenInsertColumnUsesCamelCaseName() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/BrandResourceLibraryMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.BrandResourceLibraryMapper">
                    <insert id="insertBatch" parameterType="java.util.List">
                        insert into ns_brand_resource_library (enterpriseId, precinctIdModified)
                        values
                        <foreach collection="list" item="item" separator=",">
                            (#{item.enterpriseId,jdbcType=BIGINT}, #{item.precinctIdModified})
                        </foreach>
                    </insert>
                </mapper>
                """);
        compileJavaClass("com/example/BrandResourceLibrary.java", """
                package com.example;

                public class BrandResourceLibrary {
                    private Long enterpriseId;
                    private String precinctIdModified;
                }
                """);
        compileJavaClass("com/example/BrandResourceLibraryMapper.java", """
                package com.example;

                import java.util.List;

                public interface BrandResourceLibraryMapper {
                    int insertBatch(List<BrandResourceLibrary> brandResourceLibraryList);
                }
                """);

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of(
                        "ns_brand_resource_library",
                        Map.of("enterprise_id", "BIGINT", "precinct_id_modified", "TINYINT")
                )
        );

        String rewritten = Files.readString(tempDir.resolve(
                "module/src/main/resources/mapper-dm/BrandResourceLibraryMapper.xml"
        ));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(result.warnings()).isEmpty();
        assertThat(rewritten)
                .contains("#{item.enterpriseId,jdbcType=BIGINT}")
                .contains("CAST(#{item.precinctIdModified,jdbcType=VARCHAR} AS TINYINT)")
                .doesNotContain("#{item.precinctIdModified}");
    }

    @Test
    void castsStringForeachItemForTrimColumnsAndForeachValues() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/OwnerHouseBaseInfoMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.OwnerHouseBaseInfoMapper">
                    <insert id="insertOwnerHouseBaseInfos" parameterType="java.util.List">
                        insert into owner_house_base_info
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            enterprise_id,
                            sys_time,
                            vacant_stage,
                            uuid
                        </trim>
                        values
                        <foreach collection="list" separator="," index="index" item="item">
                            (
                            #{item.enterpriseId,jdbcType=BIGINT},
                            SYSDATE,
                            #{item.vacantStage},
                            REPLACE(UUID(),'-','')
                            )
                        </foreach>
                    </insert>
                </mapper>
                """);
        compileJavaClass("com/example/OwnerHouseBaseInfo.java", """
                package com.example;

                public class OwnerHouseBaseInfo {
                    private Long enterpriseId;
                    private String vacantStage;
                }
                """);
        compileJavaClass("com/example/OwnerHouseBaseInfoMapper.java", """
                package com.example;

                import java.util.List;

                public interface OwnerHouseBaseInfoMapper {
                    int insertOwnerHouseBaseInfos(List<OwnerHouseBaseInfo> ownerHouseBaseInfos);
                }
                """);

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of("owner_house_base_info", Map.of(
                        "enterprise_id", "BIGINT",
                        "sys_time", "TIMESTAMP",
                        "vacant_stage", "TINYINT",
                        "uuid", "VARCHAR"
                ))
        );

        String rewritten = Files.readString(tempDir.resolve(
                "module/src/main/resources/mapper-dm/OwnerHouseBaseInfoMapper.xml"
        ));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(result.warnings()).isEmpty();
        assertThat(rewritten)
                .contains("#{item.enterpriseId,jdbcType=BIGINT}")
                .contains("CAST(#{item.vacantStage,jdbcType=VARCHAR} AS TINYINT)")
                .contains("SYSDATE")
                .contains("REPLACE(UUID(),'-','')")
                .doesNotContain("#{item.vacantStage},");
    }

    @Test
    void keepsReadableJavaMetadataWhenAnotherSourceFileIsMalformed() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/OwnerHouseResultMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.OwnerHouseResultMapper">
                    <insert id="insertSelective" parameterType="com.example.HouseListEntity">
                        insert into owner_house_result
                        <trim prefix="(" suffix=")" suffixOverrides=",">
                            <if test="hasRelevance != null">
                                has_relevance,
                            </if>
                        </trim>
                        <trim prefix="values (" suffix=")" suffixOverrides=",">
                            <if test="hasRelevance != null">
                                #{hasRelevance,jdbcType=INTEGER},
                            </if>
                        </trim>
                    </insert>
                </mapper>
                """);
        writeJava("src/main/java/com/example/HouseListEntity.java", """
                package com.example;

                public class HouseListEntity {
                    private String hasRelevance;
                }
                """);
        writeBytes("src/main/java/com/example/MalformedSource.java", new byte[]{
                (byte) 0xF0, 0x28, (byte) 0x8C, (byte) 0xBC
        });

        MapperJdbcTypeAlignmentResult result = aligner.align(
                scanResult,
                AdapterContext.builder(tempDir).build(),
                Map.of("owner_house_result", Map.of("has_relevance", "INTEGER"))
        );

        String rewritten = Files.readString(tempDir.resolve("module/src/main/resources/mapper-dm/OwnerHouseResultMapper.xml"));
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(result.warnings()).isEmpty();
        assertThat(rewritten)
                .contains("CAST(#{hasRelevance,jdbcType=VARCHAR} AS INTEGER)")
                .doesNotContain("#{hasRelevance,jdbcType=INTEGER}");
    }

    @Test
    void collectsReferencedMapperDmTables() throws Exception {
        ProjectScanResult scanResult = writeMapperDm("mapper/SystemAreaMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.SystemAreaMapper">
                    <insert id="insert">
                        insert into ns_system_area (order_no) values (#{orderNo, jdbcType=VARCHAR})
                    </insert>
                    <update id="update">
                        update ns_core_dictionaryitem set name = #{name} where id = #{id}
                    </update>
                </mapper>
                """);

        assertThat(aligner.referencedTables(scanResult, AdapterContext.builder(tempDir).build()))
                .containsExactlyInAnyOrder("ns_system_area", "ns_core_dictionaryitem");
    }

    private ProjectScanResult writeMapperDm(String resourcesRelativePath, String content) throws Exception {
        Path resourcesRoot = tempDir.resolve("module/src/main/resources");
        Path mapperDmPath = resourcesRoot.resolve("mapper-dm")
                .resolve(resourcesRelativePath.substring("mapper/".length()));
        Files.createDirectories(mapperDmPath.getParent());
        Files.writeString(mapperDmPath, content);
        return new ProjectScanResult(
                true,
                true,
                true,
                false,
                tempDir.resolve("pom.xml").toString(),
                List.of(new MapperXmlFile(
                        tempDir.resolve("module/src/main/resources/" + resourcesRelativePath).toString(),
                        resourcesRoot.toString(),
                        resourcesRelativePath
                )),
                List.of()
        );
    }

    private void writeJava(String relativePath, String content) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private void writeBytes(String relativePath, byte[] content) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, content);
    }

    private void compileJavaClass(String sourceRelativePath, String content) throws Exception {
        Path source = tempDir.resolve("compile-src").resolve(sourceRelativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        Path output = tempDir.resolve("module/target/classes");
        Files.createDirectories(output);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(
                null,
                null,
                null,
                "-classpath",
                output.toString(),
                "-d",
                output.toString(),
                source.toString()
        )).isZero();
    }
}
