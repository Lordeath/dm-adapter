package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectDdlKeyMetadataReaderTest {
    @TempDir
    Path tempDir;

    private final ProjectDdlKeyMetadataReader reader = new ProjectDdlKeyMetadataReader();

    @Test
    void readsPrimaryAndUniqueKeysFromCreateTableDdl() throws Exception {
        Path ddl = tempDir.resolve("sql/01_Update.sql");
        Files.createDirectories(ddl.getParent());
        Files.writeString(ddl, """
                CREATE TABLE IF NOT EXISTS `ns_contract_schedule` (
                  `id` bigint NOT NULL AUTO_INCREMENT,
                  `contractId` bigint DEFAULT NULL COMMENT '合同ID',
                  `paymentRecordId` varchar(255) DEFAULT NULL COMMENT '明细ID',
                  PRIMARY KEY (`id`),
                  UNIQUE KEY `paymentRecord_contract_id` (`contractId`,`paymentRecordId`)
                ) ENGINE=InnoDB;

                CREATE TABLE IF NOT EXISTS `ns_recently_used` (
                    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
                    `agentType` varchar(50) NOT NULL COMMENT '智能体类型',
                    `agentId` bigint NOT NULL COMMENT '智能体id',
                    `userId` bigint NOT NULL COMMENT '使用人',
                    PRIMARY KEY (`id`) USING BTREE,
                    UNIQUE KEY uk_user_agent_type (userId, agentType, agentId) COMMENT '同一用户唯一'
                ) ENGINE=InnoDB;
                """);

        Map<String, TableKeyMetadata> metadata = reader.readTableKeys(
                tempDir,
                List.of("ns_contract_schedule", "ns_recently_used")
        );

        assertThat(metadata).containsKeys("ns_contract_schedule", "ns_recently_used");
        assertThat(metadata.get("ns_contract_schedule").primaryKeys())
                .extracting(TableConstraint::columns)
                .containsExactly(List.of("id"));
        assertThat(metadata.get("ns_contract_schedule").uniqueKeys())
                .extracting(TableConstraint::columns)
                .containsExactly(List.of("contractId", "paymentRecordId"));
        assertThat(metadata.get("ns_recently_used").uniqueKeys())
                .extracting(TableConstraint::columns)
                .containsExactly(List.of("userId", "agentType", "agentId"));
    }

    @Test
    void readsSchemaQualifiedTableAndCaseSensitiveColumnSpelling() throws Exception {
        Path ddl = tempDir.resolve("sql/schema.sql");
        Files.createDirectories(ddl.getParent());
        Files.writeString(ddl, """
                CREATE TABLE IF NOT EXISTS `newsee-soss`.`ns_project_management_extend` (
                  `extendId` bigint NOT NULL AUTO_INCREMENT,
                  `foreignkeyId` bigint NOT NULL,
                  PRIMARY KEY (`extendId`) USING BTREE,
                  UNIQUE KEY `ns_project_management_extend_foreignerKeyId_Index` (`foreignkeyId`)
                ) ENGINE=InnoDB;
                """);

        Map<String, TableKeyMetadata> metadata = reader.readTableKeys(
                tempDir,
                List.of("ns_project_management_extend")
        );

        assertThat(metadata.get("ns_project_management_extend").uniqueKeys())
                .singleElement()
                .satisfies(uniqueKey -> assertThat(uniqueKey.columns()).containsExactly("foreignkeyId"));
    }
}
