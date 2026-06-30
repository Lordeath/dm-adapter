package com.github.dmadapter.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InsertColumnExtractorTest {
    @Test
    void extractsColumnsFromDynamicTrimInsertIgnore() {
        String sql = """
                insert ignore into charge_customerchargedetail_ext
                <trim prefix="(" suffix=")" suffixOverrides=",">
                    <if test="id != null">
                        chargeDetailId,
                    </if>
                    <if test="receivingBusiness != null">
                        receivingBusiness,
                    </if>
                    <if test="sourceId != null">
                        sourceId,
                    </if>
                </trim>
                <trim prefix="values (" suffix=")" suffixOverrides=",">
                    <if test="id != null">
                        #{id},
                    </if>
                    <if test="receivingBusiness != null">
                        #{receivingBusiness},
                    </if>
                    <if test="sourceId != null">
                        #{sourceId},
                    </if>
                </trim>
                """;

        String tableName = InsertColumnExtractor.tableName(sql);

        assertThat(tableName).isEqualTo("charge_customerchargedetail_ext");
        assertThat(InsertColumnExtractor.columns(sql, tableName))
                .containsExactly("chargeDetailId", "receivingBusiness", "sourceId");
    }

    @Test
    void keepsExistingParenthesizedColumnExtraction() {
        String sql = """
                INSERT INTO user_extend (user_id, `key_name`)
                VALUES (#{userId}, #{keyName})
                ON DUPLICATE KEY UPDATE key_name = VALUES(key_name)
                """;

        assertThat(InsertColumnExtractor.columns(sql, "user_extend"))
                .containsExactly("user_id", "key_name");
    }

    @Test
    void extractsFixedColumnFromDynamicMapColumnList() {
        String sql = """
                INSERT INTO ns_project_management_extend (foreignKeyId
                    <foreach collection="dynamicMap.keys" item="key" open="," separator=",">
                        `${key}`
                    </foreach>
                )
                VALUES (#{id, jdbcType=BIGINT}
                    <foreach collection="dynamicMap.values" item="value" open="," separator=",">
                        #{value}
                    </foreach>
                )
                ON DUPLICATE KEY UPDATE
                    <foreach collection="dynamicMap" index="key" item="value" separator=",">
                        `${key}` = VALUES(`${key}`)
                    </foreach>
                """;

        assertThat(InsertColumnExtractor.columns(sql, "ns_project_management_extend"))
                .containsExactly("foreignKeyId");
    }
}
