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
}
