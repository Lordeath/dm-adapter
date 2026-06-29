package com.github.dmadapter.cli;

import com.github.dmadapter.core.AdapterContext;
import com.github.dmadapter.core.MapperXmlFile;
import com.github.dmadapter.core.ProjectScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MapperJavaParamFixerTest {
    @TempDir
    Path tempDir;

    private final MapperJavaParamFixer fixer = new MapperJavaParamFixer();

    @Test
    void fixesDuplicateParamAnnotationUsingMapperXmlParameterName() throws Exception {
        writeMapper("NsRefundOrderDetailMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.NsRefundOrderDetailDao">
                    <select id="queryByOrderNoPrecinctId" resultMap="BaseResultMap">
                        select * from ns_refund_order_detail
                        where orderNo = #{orderNo} and precinctId = #{precinctId}
                    </select>
                </mapper>
                """);
        Path javaFile = writeJava("com/example/NsRefundOrderDetailDao.java", """
                package com.example;

                import org.apache.ibatis.annotations.Param;

                public interface NsRefundOrderDetailDao {
                    List<NsRefundOrderDetail> queryByOrderNoPrecinctId(
                            @Param("precinctId") String orderNo,
                            @Param("precinctId") Long precinctId);
                }
                """);

        MapperJavaParamFixResult result = fixer.fix(scanResult(), AdapterContext.builder(tempDir).build());

        assertThat(result.warnings()).isEmpty();
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(Files.readString(javaFile))
                .contains("@Param(\"orderNo\") String orderNo")
                .contains("@Param(\"precinctId\") Long precinctId");
    }

    @Test
    void addsMissingParamAnnotationsForMultipleSimpleParameters() throws Exception {
        writeMapper("NsProcessTaskDetailBillingInfoMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.NsProcessTaskDetailBillingInfoMapper">
                    <select id="pageList" resultType="map">
                        select * from ns_process_task_detail limit #{offset}, #{pagesize}
                    </select>
                </mapper>
                """);
        Path javaFile = writeJava("com/example/NsProcessTaskDetailBillingInfoMapper.java", """
                package com.example;

                public interface NsProcessTaskDetailBillingInfoMapper {
                    List<Object> pageList(int offset, int pagesize);
                }
                """);

        MapperJavaParamFixResult result = fixer.fix(scanResult(), AdapterContext.builder(tempDir).build());

        assertThat(result.warnings()).isEmpty();
        assertThat(result.fileChanges()).hasSize(1);
        assertThat(Files.readString(javaFile))
                .contains("import org.apache.ibatis.annotations.Param;")
                .contains("pageList(@Param(\"offset\") int offset, @Param(\"pagesize\") int pagesize)");
    }

    @Test
    void warnsAndSkipsUnreadableJavaSource() throws Exception {
        writeMapper("BadMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.BadMapper">
                    <select id="select" resultType="map">
                        select * from t where id = #{id}
                    </select>
                </mapper>
                """);
        Path javaFile = tempDir.resolve("module/src/main/java/com/example/BadMapper.java");
        Files.createDirectories(javaFile.getParent());
        Files.write(javaFile, new byte[]{(byte) 0xc3, 0x28});

        MapperJavaParamFixResult result = fixer.fix(scanResult(), AdapterContext.builder(tempDir).build());

        assertThat(result.fileChanges()).isEmpty();
        assertThat(result.warnings()).anySatisfy(warning ->
                assertThat(warning).contains("Skipped Java mapper @Param fix"));
    }

    private Path writeMapper(String name, String content) throws Exception {
        Path path = tempDir.resolve("module/src/main/resources/mapper/" + name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private Path writeJava(String relativePath, String content) throws Exception {
        Path path = tempDir.resolve("module/src/main/java/" + relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private ProjectScanResult scanResult() throws Exception {
        Path resourcesRoot = tempDir.resolve("module/src/main/resources");
        List<MapperXmlFile> mapperXmlFiles;
        try (var paths = Files.walk(resourcesRoot.resolve("mapper"))) {
            mapperXmlFiles = paths
                    .filter(Files::isRegularFile)
                    .map(path -> new MapperXmlFile(
                            path.toString(),
                            resourcesRoot.toString(),
                            resourcesRoot.relativize(path).toString()
                    ))
                    .toList();
        }
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
}
