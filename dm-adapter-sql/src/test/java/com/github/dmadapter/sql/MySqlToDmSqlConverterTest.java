package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlToDmSqlConverterTest {
    private final MySqlToDmSqlConverter converter = new MySqlToDmSqlConverter();

    @Test
    void convertsIfnullAndNow() {
        SqlConversionResult result = converter.convert("select IFNULL(name, 'n/a'), NOW() from user");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select NVL(name, 'n/a'), SYSDATE from user");
        assertThat(result.appliedRules()).containsExactly("IFNULL_TO_NVL", "NOW_TO_SYSDATE");
    }

    @Test
    void convertsDoubleQuotedStringLiterals() {
        SqlConversionResult result = converter.convert(
                "select * from user where status = \"ACTIVE\" and remark = \"Bob's \\\"note\\\"\""
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select * from user where status = 'ACTIVE' and remark = 'Bob''s \"note\"'");
        assertThat(result.appliedRules()).containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void convertsDoubleQuotedStringLiteralsBeforeOtherSafeRules() {
        SqlConversionResult result = converter.convert("select IFNULL(status, \"UNKNOWN\") from user");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select NVL(status, 'UNKNOWN') from user");
        assertThat(result.appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING", "IFNULL_TO_NVL");
    }

    @Test
    void doesNotConvertDoubleQuotesInsideSingleQuotedStringsOrComments() {
        SqlConversionResult result = converter.convert("""
                select '"ACTIVE"' as raw, "ACTIVE" as status -- "comment"
                from user /* "block" */
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select '"ACTIVE"' as raw, 'ACTIVE' as status -- "comment"
                from user /* "block" */
                """);
        assertThat(result.appliedRules()).containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void convertsDoubleQuotedStringLiteralsAfterMyBatisPlaceholders() {
        SqlConversionResult result = converter.convert("select * from user where id = #{id} and status = \"ACTIVE\"");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select * from user where id = #{id} and status = 'ACTIVE'");
        assertThat(result.appliedRules()).containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void convertsDoubleQuotedStringLiteralInAesEncryptExpression() {
        SqlConversionResult result = converter.convert(
                "user_password = to_base64(AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR } \t,\"XXXXXXXX\")) ,"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("user_password = TO_BASE64(SF_ENCRYPT_CHAR(#{userPassword, jdbcType=VARCHAR }, 513, 'XXXXXXXX', NULL)) ,");
        assertThat(result.appliedRules())
                .containsExactly(
                        "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_AES_BASE64_TO_DM_AES128_ECB_RULE
                );
    }

    @Test
    void convertsBase64WrappedAesDecryptToDamengAes128Ecb() {
        SqlConversionResult result = converter.convert(
                "select AES_DECRYPT(FROM_BASE64(user_password), 'XXXXXXXX') from user"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select SF_DECRYPT_TO_CHAR(FROM_BASE64(user_password), 513, 'XXXXXXXX', NULL) from user");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_AES_BASE64_TO_DM_AES128_ECB_RULE);
    }

    @Test
    void marksUnsupportedAesFormsForManualReview() {
        List<String> sqlItems = List.of(
                "select AES_ENCRYPT(name, 'XXXXXXXX') from user",
                "select TO_BASE64(AES_ENCRYPT(name, #{aesKey})) from user",
                "select AES_DECRYPT(password, 'XXXXXXXX') from user"
        );

        for (String sql : sqlItems) {
            SqlConversionResult result = converter.convert(sql);

            assertThat(result.manualReviewRequired()).isTrue();
            assertThat(result.changed()).isFalse();
            assertThat(result.convertedSql()).isEqualTo(result.originalSql());
            assertThat(result.reason()).contains("Base64-wrapped AES password SQL");
        }
    }

    @Test
    void doesNotTreatAesTextInsideStringsOrCommentsAsFunctionCalls() {
        SqlConversionResult result = converter.convert("""
                select 'AES_DECRYPT(FROM_BASE64(user_password), ''XXXXXXXX'')' as sample
                -- AES_ENCRYPT(name, 'XXXXXXXX')
                from user
                """);

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.changed()).isFalse();
    }

    @Test
    void renamesDamengReservedColumnNames() {
        SqlConversionResult result = converter.convert("""
                select rowid, ROWNUM, TRXID, phyrowid, versions_starttime, versions_endtime,
                       versions_starttrxid, versions_endtrxid, versions_operation
                from user
                where u.rowid = #{rowid} and rownum = #{rownum} and trxid = ${trxid}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select rowid_, ROWNUM_, TRXID_, phyrowid_, versions_starttime_, versions_endtime_,
                       versions_starttrxid_, versions_endtrxid_, versions_operation_
                from user
                where u.rowid_ = #{rowid} and rownum_ = #{rownum} and trxid_ = ${trxid}
                """);
        assertThat(result.appliedRules()).containsExactly("DAMENG_RESERVED_COLUMN_RENAME");
    }

    @Test
    void doesNotRenameReservedColumnNamesInsideStringsCommentsOrPlaceholders() {
        SqlConversionResult result = converter.convert("""
                select rowid from user
                where note = 'rowid trxid'
                  and id = #{rowid}
                  and name = ${trxid}
                -- rowid comment
                /* trxid comment */
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select rowid_ from user
                where note = 'rowid trxid'
                  and id = #{rowid}
                  and name = ${trxid}
                -- rowid comment
                /* trxid comment */
                """);
    }

    @Test
    void convertsSimpleLimitWithOffset() {
        SqlConversionResult result = converter.convert("select * from user order by id limit 10, 20");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select * from user order by id OFFSET 10 ROWS FETCH NEXT 20 ROWS ONLY");
        assertThat(result.appliedRules()).containsExactly("LIMIT_OFFSET_TO_DM_FETCH");
    }

    @Test
    void convertsLimitWithMyBatisPlaceholders() {
        SqlConversionResult result = converter.convert("select * from user limit #{offset}, #{size}");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select * from user OFFSET #{offset} ROWS FETCH NEXT #{size} ROWS ONLY");
    }

    @Test
    void convertsSelectContinuationFragmentLimitAfterMyBatisInclude() {
        SqlConversionResult result = converter.convert("""
                from ns_wms_material
                where materialCode LIKE #{materialClassCode}'%'
                order by id desc limit 1
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                from ns_wms_material
                where materialCode LIKE (#{materialClassCode}) || ('%')
                order by id desc FETCH FIRST 1 ROWS ONLY
                """.stripTrailing());
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT_RULE,
                        "LIMIT_TO_DM_FETCH"
                );
    }

    @Test
    void doesNotConvertWhereOnlyLimitFragment() {
        SqlConversionResult result = converter.convert(
                "where customerId = #{customerId} order by createTime desc limit 1"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.reason()).contains("LIMIT");
    }

    @Test
    void leavesDateFormatNativeAndStillAppliesOtherSafeRules() {
        SqlConversionResult result = converter.convert("select DATE_FORMAT(created_at, '%Y-%m-%d') from user");

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void convertsMysqlSingleQuotedAliasesToDamengIdentifiers() {
        SqlConversionResult result = converter.convert(
                "SELECT t.precinct_code AS 'precinctThirdId', COUNT(*) as 'houseCount' FROM biz_house_info t"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT t.precinct_code AS \"precinctThirdId\", COUNT(*) as \"houseCount\" FROM biz_house_info t");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SINGLE_QUOTED_ALIAS_RULE);
    }

    @Test
    void convertsDynamicSingleQuotedAliasesWithoutEscapingOgnlStringLiterals() {
        SqlConversionResult result = converter.convert("""
                select sum(chargeSum) as '${"saturatedChargeSum" + item}'
                from charge_customerchargedetail
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select sum(chargeSum) as "${"saturatedChargeSum" + item}"
                from charge_customerchargedetail
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SINGLE_QUOTED_ALIAS_RULE);
    }

    @Test
    void convertsMysqlImplicitSingleQuotedAliasesToDamengIdentifiers() {
        SqlConversionResult result = converter.convert(
                "select count(id)'totalCount', NVL(sum(normalCount),0)'normalCount' from equip"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select count(id) AS \"totalCount\", NVL(sum(normalCount),0) AS \"normalCount\" from equip");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SINGLE_QUOTED_ALIAS_RULE);
    }

    @Test
    void convertsMysqlImplicitSingleQuotedColumnAliasesInSelectFragments() {
        SqlConversionResult result = converter.convert(
                "wfInst.id_ 'id',wfInst.subject_ 'subject', due.date_type_ 'dueDateType'"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("wfInst.id_ AS \"id\",wfInst.subject_ AS \"subject\", due.date_type_ AS \"dueDateType\"");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SINGLE_QUOTED_ALIAS_RULE);
    }

    @Test
    void convertsMysqlImplicitSingleQuotedAliasAfterSubqueryExpression() {
        SqlConversionResult result = converter.convert(
                "select (select count(tmp.id_) from role_auth tmp where tmp.menu_alias_ = menu.alias_) 'checked' from menu"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select (select count(tmp.id_) from role_auth tmp where tmp.menu_alias_ = menu.alias_) AS \"checked\" from menu");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SINGLE_QUOTED_ALIAS_RULE);
    }

    @Test
    void convertsMysqlImplicitSingleQuotedAliasAfterSubqueryFragment() {
        SqlConversionResult result = converter.convert(
                "(SELECT count(tmpB.id_) FROM portal_sys_role_auth tmpB WHERE tmpB.menu_alias_ = sysMenu.ALIAS_) 'checked'"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("(SELECT count(tmpB.id_) FROM portal_sys_role_auth tmpB WHERE tmpB.menu_alias_ = sysMenu.ALIAS_) AS \"checked\"");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SINGLE_QUOTED_ALIAS_RULE);
    }

    @Test
    void keepsSingleQuotedStringLiteralsInConditionFragments() {
        assertThat(converter.convert("AND opinion.status_ != 'signLineRetracted'").convertedSql())
                .isEqualTo("AND opinion.status_ != 'signLineRetracted'");
        assertThat(converter.convert("WHERE status_ 'ACTIVE'").convertedSql())
                .isEqualTo("WHERE status_ 'ACTIVE'");
    }

    @Test
    void removesMysqlSelectModifiers() {
        SqlConversionResult result = converter.convert(
                "select SQL_BIG_RESULT precinct_id, SUM(charging_area) from owner_house_result group by precinct_id"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select precinct_id, SUM(charging_area) from owner_house_result group by precinct_id");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SELECT_MODIFIER_REMOVAL_RULE);
    }

    @Test
    void removesMysqlCollateClausesWithoutChangingStringsOrComments() {
        SqlConversionResult result = converter.convert("""
                select * from user
                where name COLLATE utf8mb4_unicode_ci = #{name}
                  and title COLLATE utf8mb4_bin like '%test%'
                  and remark = 'COLLATE utf8mb4_unicode_ci'
                  -- note COLLATE utf8mb4_unicode_ci
                  /* block COLLATE utf8mb4_unicode_ci */
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select * from user
                where name = #{name}
                  and title like '%test%'
                  and remark = 'COLLATE utf8mb4_unicode_ci'
                  -- note COLLATE utf8mb4_unicode_ci
                  /* block COLLATE utf8mb4_unicode_ci */
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_COLLATE_CLAUSE_REMOVAL_RULE);
    }

    @Test
    void convertsMysqlCreateTableColumnOptionsForDameng() {
        SqlConversionResult result = converter.convert("""
                create table if not exists tmp_budget_payment_receipt (
                  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增id',
                  `chargeItem` varchar(200) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT '收费科目',
                  PRIMARY KEY (`id`)
                )
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                create table if not exists tmp_budget_payment_receipt (
                  id bigint NOT NULL IDENTITY(1,1),
                  "chargeItem" varchar(200) DEFAULT NULL,
                  PRIMARY KEY (id)
                )
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                MySqlToDmSqlConverter.MYSQL_COLLATE_CLAUSE_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_CHARACTER_SET_CLAUSE_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE,
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVAL_RULE
        );
    }

    @Test
    void removesMysqlCreateTableIndexesAndTableOptionsForDameng() {
        SqlConversionResult result = converter.convert("""
                create table if not exists tmp_report_sync_init(
                  `id` bigint(20) NOT NULL AUTO_INCREMENT,
                  `type` int(2) unsigned DEFAULT NULL COMMENT '类型',
                  `doneFlag` tinyint(1) DEFAULT NULL COMMENT '是否完成',
                  tmp_sys_time timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                  PRIMARY KEY (`id`) USING BTREE,
                  KEY idx_search (`type`,`doneFlag`) USING BTREE
                ) ENGINE=InnoDB DEFAULT COLLATE=utf8mb4_0900_ai_ci;
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("id bigint NOT NULL IDENTITY(1,1)")
                .contains("\"type\" int DEFAULT NULL")
                .contains("\"doneFlag\" tinyint DEFAULT NULL")
                .contains("PRIMARY KEY (id)")
                .doesNotContain("bigint(20)")
                .doesNotContain("int(2)")
                .doesNotContain("tinyint(1)")
                .doesNotContainIgnoringCase("unsigned")
                .doesNotContainIgnoringCase("USING BTREE")
                .doesNotContain("KEY idx_search")
                .doesNotContainIgnoringCase("ENGINE")
                .doesNotContainIgnoringCase("COLLATE")
                .doesNotContainIgnoringCase("ON UPDATE CURRENT_TIMESTAMP")
                .doesNotContainIgnoringCase("COMMENT");
        assertThat(result.appliedRules()).contains(
                MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_OPTION_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE,
                MySqlToDmSqlConverter.MYSQL_NUMERIC_TYPE_ATTRIBUTE_RULE,
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_USING_BTREE_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_KEY_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_ON_UPDATE_TIMESTAMP_REMOVAL_RULE
        );
    }

    @Test
    void capsMysqlDecimalPrecisionAndRemovesCommentsInCreateTable() {
        SqlConversionResult result = converter.convert("""
                create table tmp_should_amortize_detail (
                  `chargeSum` DECIMAL(40,2) DEFAULT '0.00' COMMENT '合同金额',
                  `ratio` numeric(50,40) DEFAULT NULL COMMENT '比例'
                )
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                create table tmp_should_amortize_detail (
                  "chargeSum" DECIMAL(38,2) DEFAULT '0.00',
                  ratio numeric(38,38) DEFAULT NULL
                )
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                MySqlToDmSqlConverter.MYSQL_DECIMAL_PRECISION_CAP_RULE,
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVAL_RULE
        );
    }

    @Test
    void removesMysqlCreateTableTrailingCommentForDameng() {
        SqlConversionResult result = converter.convert("""
                CREATE TABLE if not exists tmp_daily_property_rule (
                    id bigint NOT NULL AUTO_INCREMENT,
                    `dailyProperty` varchar(64) DEFAULT NULL,
                    PRIMARY KEY (id)
                ) COMMENT '物业日报表科目配置表';
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                CREATE TABLE if not exists tmp_daily_property_rule (
                    id bigint NOT NULL IDENTITY(1,1),
                    "dailyProperty" varchar(64) DEFAULT NULL,
                    PRIMARY KEY (id)
                ) ;
                """);
        assertThat(result.appliedRules()).contains(
                MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                MySqlToDmSqlConverter.MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE
        );
        assertThat(result.convertedSql()).doesNotContainIgnoringCase("COMMENT");
    }

    @Test
    void convertsMysqlTruncateToDamengTruncateTable() {
        SqlConversionResult result = converter.convert("TRUNCATE tmp_static_report_precinct_steward_report;");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("TRUNCATE TABLE tmp_static_report_precinct_steward_report;");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_TRUNCATE_TABLE_RULE);
    }

    @Test
    void convertsMysqlSignedCastAndCharConvertForDameng() {
        SqlConversionResult result = converter.convert("""
                select cast(REGEXP_SUBSTR(ids, '[^,]+', 1, 1) as SIGNED) as id,
                       CONVERT(payment.id, char) as paymentId
                from ns_payment_chargepayment payment
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select CAST(REGEXP_SUBSTR(ids, '[^,]+', 1, 1) AS BIGINT) as id,
                       CAST(payment.id AS VARCHAR(4000)) as paymentId
                from ns_payment_chargepayment payment
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_CAST_SIGNED_RULE,
                MySqlToDmSqlConverter.MYSQL_CONVERT_CHAR_RULE
        );
    }

    @Test
    void convertsMysqlDecimalConvertWithSinglePrecisionForDameng() {
        SqlConversionResult result = converter.convert(
                "SELECT CONVERT(detail.taxRate*100, DECIMAL(12)) AS taxRate FROM ns_bill_billuseddetail detail"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT CAST(detail.taxRate*100 AS DECIMAL(12)) AS taxRate FROM ns_bill_billuseddetail detail");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_CONVERT_DECIMAL_RULE);
    }

    @Test
    void quotesKeywordAliasesConvertedFromBackticksAndBareDistinctColumns() {
        SqlConversionResult result = converter.convert("""
                select cluster.house_id as clusterId,
                       distinct
                from owner_house_cluster_info `cluster`
                join owner_house_base_info stat on stat.house_id = cluster.house_id
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select "cluster".house_id as clusterId,
                       "distinct"
                from owner_house_cluster_info "cluster"
                join owner_house_base_info stat on stat.house_id = "cluster".house_id
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                MySqlToDmSqlConverter.DAMENG_KEYWORD_TABLE_ALIAS_RULE,
                MySqlToDmSqlConverter.DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE
        );
    }

    @Test
    void quotesDamengKeywordListTableAlias() {
        SqlConversionResult result = converter.convert("""
                SELECT max(list.id) as remindId
                FROM charge_reminder_list list
                left join charge_reminder_list_charge_relationship ship on ship.remind_list_id = list.id
                WHERE list.house_id in (1)
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT max("list".id) as remindId
                FROM charge_reminder_list "list"
                left join charge_reminder_list_charge_relationship ship on ship.remind_list_id = "list".id
                WHERE "list".house_id in (1)
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.DAMENG_KEYWORD_TABLE_ALIAS_RULE);
    }

    @Test
    void removesDuplicateWhereKeywordOutsideIgnoredText() {
        SqlConversionResult result = converter.convert("""
                update charge_allowance_detail
                set isdelete = 1
                where where id = #{id} and note = 'where where'
                -- where where should stay in comments
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                update charge_allowance_detail
                set isdelete = 1
                where id = #{id} and note = 'where where'
                -- where where should stay in comments
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.DUPLICATE_WHERE_KEYWORD_RULE);
    }

    @Test
    void convertsMysqlConvertDecimalToCast() {
        SqlConversionResult result = converter.convert(
                "SELECT CONVERT(NVL(SUM(charging_area),0), DECIMAL(16,6)) as chargingArea from owner_house_result"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT CAST(NVL(SUM(charging_area),0) AS DECIMAL(16,6)) as chargingArea from owner_house_result");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_CONVERT_DECIMAL_RULE);
    }

    @Test
    void convertsMysqlGbkOrderConvertToDamengNlssort() {
        SqlConversionResult result = converter.convert(
                "select id from NS_Payment_ChargePayment ORDER BY CreateTime DESC,CONVERT(ChargeItem USING GBK) asc"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select id from NS_Payment_ChargePayment ORDER BY CreateTime DESC,NLSSORT(ChargeItem, 'NLS_SORT=SCHINESE_PINYIN_M') asc");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_CONVERT_GBK_ORDER_RULE);
    }

    @Test
    void doesNotConvertMysqlGbkConvertOutsideOrderByOrInsideIgnoredText() {
        SqlConversionResult result = converter.convert("""
                select CONVERT(name USING GBK) as raw, 'ORDER BY CONVERT(name USING GBK)' as text
                from users
                -- order by CONVERT(name USING GBK)
                order by name
                """);

        assertThat(result.changed()).isFalse();
    }

    @Test
    void removesMysqlForceIndexHints() {
        SqlConversionResult result = converter.convert(
                "SELECT * FROM owner_house_relationship force index(idx_houseId) WHERE house_id = #{houseId}"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT * FROM owner_house_relationship WHERE house_id = #{houseId}");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_INDEX_HINT_REMOVAL_RULE);
    }

    @Test
    void convertsMysqlSingularInsertValueKeywordToValues() {
        SqlConversionResult result = converter.convert(
                "insert into owner_customer_bank_account (owner_id, account_name) value (#{ownerId}, #{accountName})"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("insert into owner_customer_bank_account (owner_id, account_name) VALUES (#{ownerId}, #{accountName})");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_INSERT_VALUE_TO_VALUES_RULE);
    }

    @Test
    void convertsMysqlImplicitInnerJoinToCrossJoin() {
        SqlConversionResult result = converter.convert("""
                select base.house_id
                from owner_house_base_info base
                         inner join owner_customer_family_info family
                         inner join owner_customer_result customer
                                    on base.house_id = family.house_id
                                       and family.owner_id = customer.owner_id
                limit #{offset},#{rows}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select base.house_id
                from owner_house_base_info base
                         CROSS JOIN owner_customer_family_info family
                         inner join owner_customer_result customer
                                    on base.house_id = family.house_id
                                       and family.owner_id = customer.owner_id OFFSET #{offset} ROWS FETCH NEXT #{rows} ROWS ONLY""");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_IMPLICIT_CROSS_JOIN_RULE, "LIMIT_OFFSET_TO_DM_FETCH");
    }

    @Test
    void keepsJoinWithConditionUnchanged() {
        SqlConversionResult result = converter.convert("""
                select *
                from owner_house_base_info base
                inner join owner_house_result result on base.house_id = result.house_id
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void convertsMysqlTemporaryTableAsSelectToDamengGlobalTemporaryTable() {
        SqlConversionResult result = converter.convert("""
                drop table if exists tmp_relationship_owner_20200204;
                create TEMPORARY table tmp_relationship_owner_20200204
                SELECT rs.owner_id, rs.house_id
                FROM owner_house_relationship rs
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                drop table if exists tmp_relationship_owner_20200204;
                CREATE GLOBAL TEMPORARY TABLE tmp_relationship_owner_20200204 ON COMMIT PRESERVE ROWS AS SELECT rs.owner_id, rs.house_id
                FROM owner_house_relationship rs
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE);
    }

    @Test
    void keepsTemporaryTableWithExplicitColumnsUnchanged() {
        SqlConversionResult result = converter.convert("""
                create temporary table tmp_owner (
                    id bigint,
                    owner_name varchar(100)
                )
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void convertsMysqlDeleteAliasStarToDamengDeleteAlias() {
        SqlConversionResult result = converter.convert("""
                delete t.* from ${targetTableName} t
                where t.id = #{id}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                delete from ${targetTableName} t
                where t.id = #{id}
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DELETE_ALIAS_STAR_RULE);
    }

    @Test
    void keepsDeleteAliasStarWhenAliasDoesNotMatchTargetAlias() {
        SqlConversionResult result = converter.convert("delete t.* from sample_user u where u.id = #{id}");

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void quotesDamengKeywordTableAliasAndReferences() {
        SqlConversionResult result = converter.convert("""
                SELECT base.precinct_id, SUM(cluster.manage_area) AS inpipeArea
                FROM owner_house_base_info base
                LEFT JOIN owner_house_cluster_info cluster ON base.house_id = cluster.house_id
                WHERE base.cluster_id IS NOT NULL
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT base.precinct_id, SUM("cluster".manage_area) AS inpipeArea
                FROM owner_house_base_info base
                LEFT JOIN owner_house_cluster_info "cluster" ON base.house_id = "cluster".house_id
                WHERE base.cluster_id IS NOT NULL
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.DAMENG_KEYWORD_TABLE_ALIAS_RULE);
    }

    @Test
    void doesNotApplyMysqlSyntaxCleanupInsideStringsOrComments() {
        String sql = """
                select 'SQL_BIG_RESULT force index(idx) insert into t (a) value (1)' as sample
                from audit_log
                where note = 'AS ''ownerId'''
                -- force index(idx)
                """;

        SqlConversionResult result = converter.convert(sql);

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(sql);
    }

    @Test
    void convertsMysqlDateAddIntervalToDateadd() {
        SqlConversionResult result = converter.convert(
                "select DATE_ADD(CONCAT(DATE(checkDate), ' ', onOffTime), INTERVAL 120 MINUTE) from record"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select DATEADD(MINUTE, 120, (DATE(checkDate)) || (' ') || (onOffTime)) from record");
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_CONCAT_TO_DM_OPERATOR_RULE
        );
    }

    @Test
    void convertsMysqlIntervalAdditionToDateadd() {
        SqlConversionResult result = converter.convert(
                "select count(distinct date(create_time)) monthLoginDayCount "
                        + "from app_login_log "
                        + "where create_time >= DATE_FORMAT(CURDATE(), '%Y-%m-01') "
                        + "and DATE_FORMAT(CURDATE(), '%Y-%m-01') + INTERVAL 1 MONTH > create_time"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select count(distinct date(create_time)) monthLoginDayCount "
                        + "from app_login_log "
                        + "where create_time >= DATE_FORMAT(CURDATE(), '%Y-%m-01') "
                        + "and DATEADD(MONTH, 1, DATE_FORMAT(CURDATE(), '%Y-%m-01')) > create_time");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE);
    }

    @Test
    void convertsMysqlIntervalAdditionWithMyBatisAmountToDateadd() {
        SqlConversionResult result = converter.convert("select created_at + INTERVAL #{days} DAY from login_log");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select DATEADD(DAY, #{days}, created_at) from login_log");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE);
    }

    @Test
    void convertsMysqlIntervalAdditionAfterSingleQuotedMyBatisValueToDateadd() {
        SqlConversionResult result = converter.convert(
                "select * from payment_order where pay_time &lt; '${lastDayOfMonth}' + interval 1 day"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select * from payment_order where pay_time &lt; DATEADD(DAY, 1, '${lastDayOfMonth}')");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE);
    }

    @Test
    void convertsMysqlIntervalSubtractionToDateadd() {
        SqlConversionResult result = converter.convert(
                "select * from task where taskStartTime BETWEEN (SYSDATE - INTERVAL ${day} DAY) and SYSDATE"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select * from task where taskStartTime BETWEEN (DATEADD(DAY, -${day}, SYSDATE)) and SYSDATE");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE);
    }

    @Test
    void convertsMysqlMakeDateToDateadd() {
        SqlConversionResult result = converter.convert(
                "select MAKEDATE(EXTRACT(YEAR FROM #{day}), 1) from dual"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select DATEADD(DAY, 1 - 1, TO_DATE((EXTRACT(YEAR FROM #{day})) || ('-01-01'), 'YYYY-MM-DD')) from dual");
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_MAKEDATE_RULE,
                MySqlToDmSqlConverter.MYSQL_CONCAT_TO_DM_OPERATOR_RULE
        );
    }

    @Test
    void convertsMysqlSubdateToDateadd() {
        SqlConversionResult result = converter.convert(
                "select SUBDATE(#{day}, WEEKDAY(#{day})) from dual"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select DATEADD(DAY, (0 - WEEKDAY(#{day})), #{day}) from dual");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SUBDATE_RULE);
    }

    @Test
    void convertsMysqlDateAddSubdateCombinationToDateadd() {
        SqlConversionResult result = converter.convert(
                "select DATE_ADD(SUBDATE(#{day},WEEKDAY(#{day})),INTERVAL 6 DAY) from dual"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select DATEADD(DAY, 6, DATEADD(DAY, (0 - WEEKDAY(#{day})), #{day})) from dual");
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_SUBDATE_RULE,
                        MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE
                );
    }

    @Test
    void convertsMysqlQuarterMakeDateExpressionAfterIntervalRewrite() {
        SqlConversionResult result = converter.convert(
                "select DATE_FORMAT(LAST_DAY(MAKEDATE(EXTRACT(YEAR FROM #{day}), 1) + INTERVAL QUARTER (#{day}) * 3-1 MONTH),'%Y-%m-%d 23:59:59')"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select DATE_FORMAT(LAST_DAY(DATEADD(MONTH, QUARTER (#{day}) * 3-1, DATEADD(DAY, 1 - 1, TO_DATE((EXTRACT(YEAR FROM #{day})) || ('-01-01'), 'YYYY-MM-DD')))),'%Y-%m-%d 23:59:59')");
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE,
                        MySqlToDmSqlConverter.MYSQL_MAKEDATE_RULE,
                        MySqlToDmSqlConverter.MYSQL_CONCAT_TO_DM_OPERATOR_RULE
                );
    }

    @Test
    void doesNotConvertMysqlIntervalAdditionInsideStringsOrComments() {
        String sql = """
                select 'created_at + INTERVAL 1 DAY' as sample
                -- created_at + INTERVAL 1 DAY
                /* created_at + INTERVAL 1 DAY */
                from login_log
                """;

        SqlConversionResult result = converter.convert(sql);

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(sql);
    }

    @Test
    void convertsGroupConcatSeparatorToListaggOrderedByExpression() {
        SqlConversionResult result = converter.convert(
                "select GROUP_CONCAT(message SEPARATOR ' 、 ') as message, userId from log group by userId"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select LISTAGG(message, ' 、 ') WITHIN GROUP (ORDER BY message) as message, userId from log group by userId");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
    }

    @Test
    void convertsDistinctGroupConcatCaseExpressionToListaggDistinct() {
        SqlConversionResult result = converter.convert("""
                select GROUP_CONCAT(DISTINCT case when o.isValid = 1 then p.productName else NULL end) as productList
                from ns_soss_enterprise e
                group by e.enterpriseID
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                select LISTAGG(DISTINCT case when o.isValid = 1 then p.productName else NULL end, ',') WITHIN GROUP (ORDER BY case when o.isValid = 1 then p.productName else NULL end) as productList
                from ns_soss_enterprise e
                group by e.enterpriseID
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
    }

    @Test
    void convertsGroupConcatOrderByTrailingSeparatorLiteralToListaggSeparator() {
        SqlConversionResult result = converter.convert(
                "select GROUP_CONCAT(DISTINCT rs.owner_id order by rs.house_owner_relationship_id desc , ',') from owner_house_relationship rs"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select LISTAGG(DISTINCT rs.owner_id, ',') WITHIN GROUP (ORDER BY rs.house_owner_relationship_id desc) from owner_house_relationship rs");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
    }

    @Test
    void convertsGroupConcatMultipleExpressionsToListaggConcatenation() {
        SqlConversionResult result = converter.convert(
                "select GROUP_CONCAT(chargeItemName,'/',NVL(chargeStandardName,'')) from ns_meter_standard"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select LISTAGG((chargeItemName) || ('/') || (NVL(chargeStandardName,'')), ',') WITHIN GROUP (ORDER BY (chargeItemName) || ('/') || (NVL(chargeStandardName,''))) from ns_meter_standard");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
    }

    @Test
    void convertsDistinctGroupConcatMultipleExpressionsToListaggConcatenation() {
        SqlConversionResult result = converter.convert(
                "select group_concat(DISTINCT precinctId ,',',chargeItemId ) as dataGroup from charge group by precinctId, chargeItemId"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select LISTAGG(DISTINCT (precinctId) || (',') || (chargeItemId), ',') WITHIN GROUP (ORDER BY (precinctId) || (',') || (chargeItemId)) as dataGroup from charge group by precinctId, chargeItemId");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
    }

    @Test
    void convertsSubstringIndexGroupConcatFirstItemToRegexpSubstrListagg() {
        SqlConversionResult result = converter.convert(
                "select SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT rs.owner_id order by rs.house_owner_relationship_id desc , ','),',',1) from owner_house_relationship rs"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select REGEXP_SUBSTR(LISTAGG(DISTINCT rs.owner_id, ',') WITHIN GROUP (ORDER BY rs.house_owner_relationship_id desc), '[^,]+', 1, 1) from owner_house_relationship rs");
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE,
                MySqlToDmSqlConverter.MYSQL_SUBSTRING_INDEX_TO_REGEXP_SUBSTR_RULE
        );
    }

    @Test
    void convertsSubstringIndexNegativeOneToLastToken() {
        SqlConversionResult result = converter.convert(
                "select SUBSTRING_INDEX(ys_ets_code,'-',-1) as ysEtsCode from owner_house_base_info"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select REGEXP_SUBSTR(ys_ets_code, '[^\\-]+$', 1, 1) as ysEtsCode from owner_house_base_info");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_SUBSTRING_INDEX_TO_REGEXP_SUBSTR_RULE);
    }

    @Test
    void convertsDistinctGroupConcatWithMultipleTopLevelExpressions() {
        SqlConversionResult result = converter.convert("select GROUP_CONCAT(DISTINCT first_name, last_name) from user");

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select LISTAGG(DISTINCT (first_name) || (last_name), ',') WITHIN GROUP (ORDER BY (first_name) || (last_name)) from user");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
    }

    @Test
    void convertsMysqlConcatToDamengConcatenationOperator() {
        SqlConversionResult result = converter.convert(
                "select CONCAT(cd.PreinctName, '-', cd.HouseName) as userAddress from Charge_CustomerChargeDetail cd"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select (cd.PreinctName) || ('-') || (cd.HouseName) as userAddress from Charge_CustomerChargeDetail cd");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_CONCAT_TO_DM_OPERATOR_RULE);
    }

    @Test
    void convertsSingleArgumentMysqlConcatToWrappedExpression() {
        SqlConversionResult result = converter.convert(
                "select CONCAT(DATE_FORMAT(CalcStartDate,'%Y-%m-%d')) as CalcStartDate from detail"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select (DATE_FORMAT(CalcStartDate,'%Y-%m-%d')) as CalcStartDate from detail");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_CONCAT_TO_DM_OPERATOR_RULE);
    }

    @Test
    void convertsNestedMysqlConcatWithMyBatisPlaceholder() {
        SqlConversionResult result = converter.convert(
                "and cd.OwnerName like concat(concat(#{customerName}),'%')"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("and cd.OwnerName like ((#{customerName})) || ('%')");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_CONCAT_TO_DM_OPERATOR_RULE);
    }

    @Test
    void convertsLikePlaceholderAdjacentStringLiteralToDamengConcat() {
        SqlConversionResult result = converter.convert(
                "select * from ns_wms_material where `materialCode` LIKE #{materialClassCode}\"%\""
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select * from ns_wms_material where \"materialCode\" LIKE (#{materialClassCode}) || ('%')");
        assertThat(result.appliedRules())
                .containsExactly(
                        "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                        MySqlToDmSqlConverter.MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT_RULE
                );
    }

    @Test
    void convertsLikeStringLiteralAdjacentPlaceholderToDamengConcat() {
        SqlConversionResult result = converter.convert(
                "select * from customer where name LIKE '%' #{customerName}"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select * from customer where name LIKE ('%') || (#{customerName})");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT_RULE);
    }

    @Test
    void doesNotConvertLikeDynamicPlaceholderAdjacentLiteral() {
        SqlConversionResult result = converter.convert(
                "select * from customer where name LIKE ${customerName}'%'"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
    }

    @Test
    void doesNotConvertMysqlConcatWithDynamicIdentifier() {
        SqlConversionResult result = converter.convert(
                "select concat(${remarkField}, '\\n') from customer"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
    }

    @Test
    void convertsHavingAggregateAliasToAggregateExpression() {
        SqlConversionResult result = converter.convert("""
                select sum(invoicedFee-checkFee) offAmount,ChargeItemName,ChargeItemID,taxRate
                from ns_develop_vacancy_detail
                where deleteFlag = 0
                GROUP BY ChargeItemID,taxRate
                Having offAmount > 0
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                select sum(invoicedFee-checkFee) offAmount,ChargeItemName,ChargeItemID,taxRate
                from ns_develop_vacancy_detail
                where deleteFlag = 0
                GROUP BY ChargeItemID,taxRate
                Having (sum(invoicedFee-checkFee)) > 0
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_HAVING_AGGREGATE_ALIAS_RULE);
    }

    @Test
    void convertsNotFindInSetToEqualsZero() {
        SqlConversionResult result = converter.convert("""
                select orderNo, group_concat(callStatus) allCallStatus
                from ns_bill_order_pay_info
                group by orderNo
                having !find_in_set('1', allCallStatus)
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                select orderNo, LISTAGG(callStatus, ',') WITHIN GROUP (ORDER BY callStatus) allCallStatus
                from ns_bill_order_pay_info
                group by orderNo
                having find_in_set('1', (LISTAGG(callStatus, ',') WITHIN GROUP (ORDER BY callStatus))) = 0
                """);
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE,
                        MySqlToDmSqlConverter.MYSQL_HAVING_AGGREGATE_ALIAS_RULE,
                        MySqlToDmSqlConverter.MYSQL_NOT_FIND_IN_SET_RULE
                );
    }

    @Test
    void convertsNotFindInSetOutsideHavingWithoutChangingCommentsOrStrings() {
        String sql = """
                select * from bill
                where ! FIND_IN_SET(#{status}, status_list)
                  and note = '!find_in_set(1, x)'
                  -- !find_in_set(1, x)
                """;

        SqlConversionResult result = converter.convert(sql);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select * from bill
                where FIND_IN_SET(#{status}, status_list) = 0
                  and note = '!find_in_set(1, x)'
                  -- !find_in_set(1, x)
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_NOT_FIND_IN_SET_RULE);
    }

    @Test
    void leavesPositiveFindInSetNativeBecauseDamengSupportsIt() {
        String sql = "select * from bill where FIND_IN_SET(#{status}, status_list)";

        SqlConversionResult result = converter.convert(sql);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(sql);
    }

    @Test
    void convertsBacktickQuotedHavingAggregateAliasWithoutChangingStringLiterals() {
        SqlConversionResult result = converter.convert("""
                select SUM(amount) AS `totalAmount`, item_id
                from bill
                group by item_id
                having `totalAmount` > 0 and remark <> 'totalAmount'
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select SUM(amount) AS "totalAmount", item_id
                from bill
                group by item_id
                having (SUM(amount)) > 0 and remark <> 'totalAmount'
                """);
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                        MySqlToDmSqlConverter.MYSQL_HAVING_AGGREGATE_ALIAS_RULE
                );
    }

    @Test
    void doesNotConvertNonAggregateHavingAliasOrNestedSelectAlias() {
        String nonAggregateAlias = """
                select amount offAmount, item_id
                from bill
                group by amount, item_id
                having offAmount > 0
                """;
        String nestedHavingAlias = """
                select *
                from (
                    select sum(amount) offAmount, item_id
                    from bill
                    group by item_id
                    having offAmount > 0
                ) t
                """;

        assertThat(converter.convert(nonAggregateAlias).changed()).isFalse();
        assertThat(converter.convert(nestedHavingAlias).changed()).isFalse();
    }

    @Test
    void keepsSafeConversionsWhenRemainingSqlNeedsManualReview() {
        SqlConversionResult result = converter.convert(
                "select `user`, JSON_SET(payload, '$.name', 'x') from audit_log limit 1"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select \"user\", JSON_SET(payload, '$.name', 'x') from audit_log FETCH FIRST 1 ROWS ONLY");
        assertThat(result.reason()).contains("JSON_SET");
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                        "LIMIT_TO_DM_FETCH"
                );
    }

    @Test
    void convertsMysqlConvertUnsignedToBigintCast() {
        SqlConversionResult result = converter.convert(
                "select max(CONVERT(REPLACE(serialNumber, #{prefix}, ''), UNSIGNED)) from ns_assessment_plan"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select max(CAST(REPLACE(serialNumber, #{prefix}, '') AS BIGINT)) from ns_assessment_plan");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_CONVERT_UNSIGNED_RULE);
    }

    @Test
    void convertsBacktickIdentifiers() {
        SqlConversionResult result = converter.convert(
                "select u.`id`, u.`user_name`, `${item.fieldName}` from `sys_user` u where u.`enabled` = \"Y\""
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select u.id, u.user_name, \"${item.fieldName}\" from sys_user u where u.enabled = 'Y'");
        assertThat(result.appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE);
    }

    @Test
    void quotesBacktickIdentifiersThatNeedCasePreservation() {
        SqlConversionResult result = converter.convert(
                "select `foreignerKeyId`, t.`extField`, `${key}` from `ns_other_information_extend` t"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select \"foreignerKeyId\", t.\"extField\", \"${key}\" from ns_other_information_extend t");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE);
    }

    @Test
    void quotesBacktickIdentifiersThatAreReservedOrContainSpecialCharacters() {
        SqlConversionResult result = converter.convert("select `order`, `newsee-system`.`user-table` from `user`");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select \"order\", \"newsee-system\".\"user-table\" from \"user\"");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE);
    }

    @Test
    void doesNotConvertBackticksInsideStringsOrComments() {
        SqlConversionResult result = converter.convert("""
                select '`order`' as raw, `status` -- `comment`
                from user /* `block` */
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select '`order`' as raw, status -- `comment`
                from user /* `block` */
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE);
    }

    @Test
    void convertsUpdateSetTableOrderToStandardUpdate() {
        SqlConversionResult result = converter.convert("""
                update set ns_core_dictionaryitem
                del_status = 1,
                update_time = #{updateTime}
                where ID = #{id}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                update ns_core_dictionaryitem set
                del_status = 1,
                update_time = #{updateTime}
                where ID = #{id}
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.UPDATE_SET_TABLE_ORDER_RULE);
    }

    @Test
    void convertsDateSubNowIntervalDayToDamengDateSubtraction() {
        SqlConversionResult result = converter.convert("""
                delete from ns_system_log_detail
                where create_time < date_sub(now(), interval #{expireDays, jdbcType=INTEGER} day)
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                delete from ns_system_log_detail
                where create_time < DATEADD(DAY, -#{expireDays, jdbcType=INTEGER}, SYSDATE)
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_SUB_INTERVAL_RULE);
    }

    @Test
    void convertsDateSubCurdateIntervalDayToDateadd() {
        SqlConversionResult result = converter.convert("""
                update ns_soss_saas_order
                set serviceEndDate = DATE_SUB(CURDATE(), INTERVAL 1 DAY)
                where id = #{id}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                update ns_soss_saas_order
                set serviceEndDate = DATEADD(DAY, -1, CURDATE())
                where id = #{id}
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_SUB_INTERVAL_RULE);
    }

    @Test
    void convertsDateSubCurdateIntervalDayWithMyBatisAmountToDateadd() {
        SqlConversionResult result = converter.convert(
                "select DATE_SUB(CURDATE(), INTERVAL #{days, jdbcType=INTEGER} DAY)"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select DATEADD(DAY, -#{days, jdbcType=INTEGER}, CURDATE())");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_SUB_INTERVAL_RULE);
    }

    @Test
    void convertsDateSubWeekAndMonthIntervalsWithSignedAmountsToDateadd() {
        SqlConversionResult result = converter.convert("""
                select date_sub(CURDATE(), interval -1 week) as weekEnd,
                       date_sub(CURDATE(), interval -1 month) as monthEnd,
                       date_sub(ohhi.end_time, interval +1 month) as unavailableStart
                from owner_house_result ohhi
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select DATEADD(WEEK, 1, CURDATE()) as weekEnd,
                       DATEADD(MONTH, 1, CURDATE()) as monthEnd,
                       DATEADD(MONTH, -1, ohhi.end_time) as unavailableStart
                from owner_house_result ohhi
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_SUB_INTERVAL_RULE);
    }

    @Test
    void convertsMysqlRegexpOperatorsToRegexpLike() {
        SqlConversionResult result = converter.convert("""
                update ys_organization y
                set is_deleted = 1
                where organization_path NOT REGEXP #{REGEXP}
                  and code REGEXP '^[0-9]+$'
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                update ys_organization y
                set is_deleted = 1
                where NOT REGEXP_LIKE(organization_path, #{REGEXP})
                  and REGEXP_LIKE(code, '^[0-9]+$')
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_REGEXP_OPERATOR_RULE);
    }

    @Test
    void convertsUnsignedCastAndAddsRecursiveCteColumnAliases() {
        SqlConversionResult result = converter.convert("""
                WITH RECURSIVE OrganizationHierarchy AS (
                    SELECT
                        organization_id,
                        organization_parent_id,
                        0 AS organization_level,
                        organization_name
                    FROM ns_system_organization
                    UNION ALL
                    SELECT
                        o.organization_id,
                        o.organization_parent_id,
                        oh.organization_level + 1 AS organization_level,
                        o.organization_name
                    FROM ns_system_organization o
                    INNER JOIN OrganizationHierarchy oh ON o.organization_id = oh.organization_parent_id
                )
                SELECT CAST(organization_id AS UNSIGNED) AS organization_id
                FROM OrganizationHierarchy
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).contains(
                "WITH RECURSIVE OrganizationHierarchy(organization_id, organization_parent_id, organization_level, organization_name) AS ("
        );
        assertThat(result.convertedSql()).contains("CAST(organization_id AS BIGINT) AS organization_id");
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_CAST_UNSIGNED_RULE,
                        MySqlToDmSqlConverter.MYSQL_WITH_RECURSIVE_ALIAS_RULE
                );
    }

    @Test
    void quotesDamengKeywordColumnIdentifiers() {
        SqlConversionResult result = converter.convert("""
                select id, message, dimension, create_time
                from receive_org
                where y.desc = 'x' and #{item.dimension} is not null
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select id, message, "DIMENSION", create_time
                from receive_org
                where y."DESC" = 'x' and #{item.dimension} is not null
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE);
    }

    @Test
    void quotesDamengKeywordQualifiedColumnIdentifiers() {
        SqlConversionResult result = converter.convert("""
                select id
                from ns_workcheck_log
                where ub.systemUserId = ns_workcheck_log.user
                  and record.state = #{state}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select id
                from ns_workcheck_log
                where ub.systemUserId = ns_workcheck_log."user"
                  and record."state" = #{state}
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE);
    }

    @Test
    void removesAsFromTableAliasesOnly() {
        SqlConversionResult result = converter.convert("""
                SELECT
                    t1.user_id AS userId,
                    t1.user_order AS userOrder
                FROM ns_system_user_dd AS t1
                LEFT JOIN ns_system_organization AS t2 ON t1.organization_id = t2.organization_id
                WHERE t1.is_change = 0
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT
                    t1.user_id AS userId,
                    t1.user_order AS userOrder
                FROM ns_system_user_dd t1
                LEFT JOIN ns_system_organization t2 ON t1.organization_id = t2.organization_id
                WHERE t1.is_change = 0
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_TABLE_ALIAS_AS_RULE);
    }

    @Test
    void convertsMysqlUpdateJoinToDamengUpdateFrom() {
        SqlConversionResult result = converter.convert("""
                update ys_organization y inner join (
                    select a.organization_id
                    from ys_organization a
                    left join ys_organization b on a.sync_organization_parent_id=b.sync_organization_id
                    where b.is_deleted=1 and a.is_deleted=0
                ) c
                set y.is_deleted =1
                where y.organization_id =c.organization_id and y.sync_organization_parent_id is not null
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                update ys_organization y set y.is_deleted =1 from (
                    select a.organization_id
                    from ys_organization a
                    left join ys_organization b on a.sync_organization_parent_id=b.sync_organization_id
                    where b.is_deleted=1 and a.is_deleted=0
                ) c where y.organization_id =c.organization_id and y.sync_organization_parent_id is not null
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void convertsMysqlUpdateJoinOnConditionToDamengUpdateFromWhereCondition() {
        SqlConversionResult result = converter.convert("""
                update ns_system_pre_organization y inner join (
                    select a.organization_id from ns_system_pre_organization a
                ) c on c.organization_id = y.organization_id
                set y.sync_flag=2,y.desc = 'NsOrgParentNotExist'
                where y.organization_id =c.organization_id
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                update ns_system_pre_organization y set y.sync_flag=2,y."DESC" = 'NsOrgParentNotExist' from (
                    select a.organization_id from ns_system_pre_organization a
                ) c where c.organization_id = y.organization_id and y.organization_id =c.organization_id
                """);
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE,
                        MySqlToDmSqlConverter.DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE
                );
    }

    @Test
    void convertsMultipleMysqlUpdateJoinStatementsInOneMapperStatement() {
        SqlConversionResult result = converter.convert("""
                update ns_system_organization_detail od inner join ns_system_organization o on od.organization_id =o.organization_id
                    set od.organization_type = o.organization_type,od.organization_nature = o.organization_nature,od.organization_path = o.organization_path;

                update ns_system_organization_detail od inner join ns_system_organization o on od.affiliated_organization_id =o.organization_id
                    set od.affiliated_organization_type = o.organization_type,od.affiliated_organization_nature = o.organization_nature,od.affiliated_organization_path = o.organization_path;
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("update ns_system_organization_detail od set od.organization_type = o.organization_type,od.organization_nature = o.organization_nature,od.organization_path = o.organization_path from ns_system_organization o where od.organization_id =o.organization_id;")
                .contains("update ns_system_organization_detail od set od.affiliated_organization_type = o.organization_type,od.affiliated_organization_nature = o.organization_nature,od.affiliated_organization_path = o.organization_path from ns_system_organization o where od.affiliated_organization_id =o.organization_id;")
                .doesNotContain("inner join");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void leavesMysqlUpdateJoinThatSetsJoinedTableForManualReview() {
        SqlConversionResult result = converter.convert("""
                update ns_quality_check_schedule_task a
                join ns_quality_check_schedule_task_user u on a.ID = u.checkScheduleTaskID
                set u.checkUserID = #{userId}, a.transferType = 1
                where a.ID = #{id}
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("UPDATE JOIN");
    }

    @Test
    void convertsSimpleMysqlUpdateOrderLimitOneToDamengRowidSubquery() {
        SqlConversionResult result = converter.convert("""
                UPDATE ns_bill_billsharing
                SET customerId = #{customerId},
                    payTime = #{payTime}
                where customerId = #{oldCustomerId} order by createTime desc limit 1
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                update ns_bill_billsharing set customerId = #{customerId},
                    payTime = #{payTime} where ROWID in (select rid from (select ROWID rid from ns_bill_billsharing where customerId = #{oldCustomerId} order by createTime desc) where ROWNUM <= 1)
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_ORDER_LIMIT_ONE_RULE);
    }

    @Test
    void doesNotConvertUnsafeMysqlUpdateOrderLimitShapes() {
        SqlConversionResult joinResult = converter.convert(
                "update user u join dept d on u.dept_id = d.id set u.name = #{name} where d.id = #{id} order by u.id limit 1"
        );
        SqlConversionResult aliasResult = converter.convert(
                "update user u set name = #{name} where id = #{id} order by id limit 1"
        );
        SqlConversionResult multiLimitResult = converter.convert(
                "update user set name = #{name} where id > #{id} order by id limit 2"
        );

        assertThat(joinResult.manualReviewRequired()).isTrue();
        assertThat(aliasResult.manualReviewRequired()).isTrue();
        assertThat(multiLimitResult.manualReviewRequired()).isTrue();
    }

    @Test
    void marksComplexMultiJoinUpdateForManualReview() {
        SqlConversionResult result = converter.convert("""
                update ys_role_permission_exp yrpe
                inner join ns_core_resourcebutton ncrb on yrpe.button_id = ncrb.id
                inner join ns_core_funcinfo f on f.id = ncrb.funcinfo_id
                set yrpe.func_name = f.funcinfo_funcname
                where yrpe.enterprise_id = #{enterpriseId}
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.reason()).contains("UPDATE JOIN");
    }

    @Test
    void convertsJsonTableInnerJoinWithoutConditionToCrossJoin() {
        SqlConversionResult result = converter.convert("""
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
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                select w.id, jt.salaryId, jt.money
                from ns_user_salary_temp_wide w
                CROSS JOIN JSON_TABLE(
                    case when JSON_VALID(w.salaryDetailJson) then cast(w.salaryDetailJson as json) else JSON_ARRAY() end,
                    '$[*]' columns (
                        salaryId bigint path '$.salaryId',
                        money decimal(18,2) path '$.money'
                    )
                ) jt
                where w.createUserId = #{createUserId}
                """);
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_JSON_TABLE_JOIN_TO_DM_CROSS_JOIN_RULE);
    }

    @Test
    void convertsBareJsonTableJoinWithoutConditionToCrossJoin() {
        SqlConversionResult result = converter.convert("""
                select w.id, jt.salaryId
                from ns_user_salary_temp_wide w
                join JSON_TABLE(w.salaryDetailJson, '$[*]' columns (salaryId bigint path '$.salaryId')) jt
                order by w.id
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).contains(
                "CROSS JOIN JSON_TABLE(w.salaryDetailJson, '$[*]' columns (salaryId bigint path '$.salaryId')) jt"
        );
    }

    @Test
    void keepsJsonTableJoinThatAlreadyHasCondition() {
        String sql = """
                select w.id, jt.salaryId
                from ns_user_salary_temp_wide w
                inner join JSON_TABLE(w.salaryDetailJson, '$[*]' columns (salaryId bigint path '$.salaryId')) jt on 1 = 1
                """;

        SqlConversionResult result = converter.convert(sql);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(sql);
    }

    @Test
    void keepsOuterAndCrossJsonTableJoinsUnchanged() {
        List<String> sqlItems = List.of(
                """
                        select w.id, jt.salaryId
                        from ns_user_salary_temp_wide w
                        left join JSON_TABLE(w.salaryDetailJson, '$[*]' columns (salaryId bigint path '$.salaryId')) jt on 1 = 1
                        """,
                """
                        select w.id, jt.salaryId
                        from ns_user_salary_temp_wide w
                        cross join JSON_TABLE(w.salaryDetailJson, '$[*]' columns (salaryId bigint path '$.salaryId')) jt
                        """
        );

        for (String sql : sqlItems) {
            SqlConversionResult result = converter.convert(sql);

            assertThat(result.changed()).isFalse();
            assertThat(result.manualReviewRequired()).isFalse();
            assertThat(result.convertedSql()).isEqualTo(sql);
        }
    }

    @Test
    void doesNotConvertJsonTableJoinInsideStringsOrComments() {
        SqlConversionResult result = converter.convert("""
                select 'inner join JSON_TABLE(profile, ''$[*]'' columns (id int path ''$.id'')) jt' as sample
                from ns_user_salary_temp_wide w
                -- inner join JSON_TABLE(profile, '$[*]' columns (id int path '$.id')) jt
                inner join JSON_TABLE(w.salaryDetailJson, '$[*]' columns (salaryId bigint path '$.salaryId')) jt
                where w.id = #{id}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("'inner join JSON_TABLE(profile, ''$[*]'' columns (id int path ''$.id'')) jt' as sample")
                .contains("-- inner join JSON_TABLE(profile, '$[*]' columns (id int path '$.id')) jt")
                .contains("CROSS JOIN JSON_TABLE(w.salaryDetailJson, '$[*]' columns (salaryId bigint path '$.salaryId')) jt");
    }

    @Test
    void verifiedDamengJsonFunctionsDoNotRequireManualReview() {
        List<String> sqlItems = List.of(
                "select JSON_VALID(profile) from user_profile",
                "select JSON_ARRAY(1, 'x') from dual",
                "select JSON_EXTRACT(profile, '$.name') from user_profile",
                "select * from JSON_TABLE('[{\"id\":1}]', '$[*]' columns (id int path '$.id')) jt"
        );

        for (String sql : sqlItems) {
            SqlConversionResult result = converter.convert(sql);

            assertThat(result.changed()).isFalse();
            assertThat(result.manualReviewRequired()).isFalse();
            assertThat(result.convertedSql()).isEqualTo(sql);
        }
    }

    @Test
    void marksMySqlSpecificFunctionsForManualReview() {
        List<String> functionNames = List.of(
                "DATE_SUB",
                "STR_TO_DATE",
                "UNIX_TIMESTAMP",
                "FROM_UNIXTIME",
                "TIMESTAMPDIFF",
                "CONCAT_WS",
                "JSON_UNQUOTE",
                "JSON_SET"
        );

        for (String functionName : functionNames) {
            SqlConversionResult result = converter.convert("select " + functionName + "(created_at) from user");

            assertThat(result.manualReviewRequired())
                    .as(functionName + " should require manual review")
                    .isTrue();
            assertThat(result.changed()).isFalse();
            assertThat(result.convertedSql()).isEqualTo(result.originalSql());
            assertThat(result.reason()).contains(functionName);
        }
    }

    @Test
    void convertsOnDuplicateKeyUpdateToDamengMerge() {
        SqlConversionResult result = converter.convert("""
                insert into ns_organization_and_employees_extend(foreignerKeyId, key)
                values(#{foreignerKeyId}, #{key})
                on duplicate key update key = values(key)
                """, List.of("foreignerKeyId"));

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                MERGE INTO ns_organization_and_employees_extend t
                USING (
                    SELECT #{foreignerKeyId} AS foreignerKeyId, #{key} AS "key" FROM dual
                ) s
                ON (t.foreignerKeyId = s.foreignerKeyId)
                WHEN MATCHED THEN UPDATE SET t."key" = s."key"
                WHEN NOT MATCHED THEN INSERT (foreignerKeyId, "key") VALUES (s.foreignerKeyId, s."key")
                """);
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_ON_DUPLICATE_KEY_UPDATE_TO_DM_MERGE_RULE);
    }

    @Test
    void convertsOnDuplicateKeyUpdateWithJdbcPlaceholdersToDamengMerge() {
        SqlConversionResult result = converter.convert("""
                INSERT INTO ns_organization_and_employees_extend (
                    foreignerKeyId,
                    key
                )
                VALUES (
                    #{foreignerKeyId, jdbcType=VARCHAR},
                    #{key, jdbcType=VARCHAR}
                )
                ON DUPLICATE KEY UPDATE
                    key = VALUES(key)
                """, List.of("foreignerKeyId"));

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).contains(
                "SELECT #{foreignerKeyId, jdbcType=VARCHAR} AS foreignerKeyId, #{key, jdbcType=VARCHAR} AS \"key\" FROM dual",
                "ON (t.foreignerKeyId = s.foreignerKeyId)",
                "WHEN MATCHED THEN UPDATE SET t.\"key\" = s.\"key\""
        );
    }

    @Test
    void convertsOnDuplicateKeyUpdateWithDamengKeywordColumnsToDoubleQuotedMergeIdentifiers() {
        SqlConversionResult result = converter.convert("""
                insert into ns_attendance_record(id, state, verify)
                values(#{id}, #{state}, #{verify})
                on duplicate key update state = values(state), verify = values(verify)
                """, List.of("id"));

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains(
                        "SELECT #{id} AS id, #{state} AS \"state\", #{verify} AS \"verify\" FROM dual",
                        "WHEN MATCHED THEN UPDATE SET t.\"state\" = s.\"state\", t.\"verify\" = s.\"verify\"",
                        "WHEN NOT MATCHED THEN INSERT (id, \"state\", \"verify\") VALUES (s.id, s.\"state\", s.\"verify\")"
                )
                .doesNotContain("AS 'state'")
                .doesNotContain("s.'state'");
    }

    @Test
    void marksOnDuplicateKeyUpdateWithMultipleCandidateKeysForManualReview() {
        SqlConversionResult result = converter.convert("""
                insert into user(id, tenant_id, name) values(1, 2, 'a')
                on duplicate key update name = values(name)
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void marksOnDuplicateKeyUpdateComplexAssignmentsForManualReview() {
        SqlConversionResult result = converter.convert("""
                insert into user(id, count) values(1, 2)
                on duplicate key update count = count + values(count)
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void marksOnDuplicateKeyUpdateAssignmentsOutsideInsertColumnsForManualReview() {
        SqlConversionResult result = converter.convert("""
                insert into user(id, name) values(1, 'a')
                on duplicate key update name = values(name), updated_at = values(updated_at)
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void marksOnDuplicateKeyUpdateWithDynamicTableForManualReview() {
        SqlConversionResult result = converter.convert("""
                insert into ${tableName}(id, name) values(1, 'a')
                on duplicate key update name = values(name)
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void marksMultiRowOnDuplicateKeyUpdateForManualReview() {
        SqlConversionResult result = converter.convert("""
                insert into user(id, name) values(1, 'a'), (2, 'b')
                on duplicate key update name = values(name)
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void marksMysqlMetadataSqlForManualReview() {
        SqlConversionResult result = converter.convert("""
                select column_name
                from information_schema.columns
                where table_schema = database()
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("information_schema", "database()");
    }

    @Test
    void convertsMysqlInformationSchemaColumnsQueryToAllTabColumns() {
        SqlConversionResult result = converter.convert("""
                SELECT column_name FROM information_schema.COLUMNS
                WHERE TABLE_NAME =? and TABLE_SCHEMA='newsee-quality'
                ORDER BY ORDINAL_POSITION asc
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE TABLE_NAME = UPPER(?) AND OWNER = UPPER('newsee-quality') ORDER BY COLUMN_ID ASC");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaColumnsTableListQueryToAllTabColumns() {
        SqlConversionResult result = converter.convert("""
                SELECT table_name
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE table_name LIKE concat(#{tablePrefix},'%')
                AND table_schema = (select DATABASE())
                AND column_name not in
                <foreach collection="columnNameList" item="item" open="(" close=")" separator=",">
                    #{item}
                </foreach>
                group by table_name
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT TABLE_NAME
                FROM ALL_TAB_COLUMNS
                WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
                AND TABLE_NAME LIKE UPPER((#{tablePrefix}) || ('%'))
                AND COLUMN_NAME NOT IN
                <foreach collection='columnNameList' item='item' open='(' close=')' separator=','>
                    #{item}
                </foreach>
                GROUP BY TABLE_NAME""");
        assertThat(result.appliedRules())
                .containsExactly(
                        "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_CONCAT_TO_DM_OPERATOR_RULE,
                        MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE
                );
    }

    @Test
    void convertsMysqlInformationSchemaTablesQueryToAllTables() {
        SqlConversionResult result = converter.convert("""
                SELECT COUNT(*) AS table_exists FROM information_schema.TABLES
                WHERE TABLE_NAME = #{tableName}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT COUNT(*) AS table_exists FROM ALL_TABLES WHERE TABLE_NAME = UPPER(#{tableName})");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_TABLES_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaTablesDetailQueryToAllObjects() {
        SqlConversionResult result = converter.convert("""
                select TABLE_NAME as tableName
                , CREATE_TIME as createTime
                , TABLE_SCHEMA as tableSchema
                from information_schema.`TABLES`
                where TABLE_SCHEMA = (SELECT DATABASE())
                and TABLE_NAME like '${tablePrefix}%';
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT OBJECT_NAME AS tableName
                , CREATED AS createTime
                , OWNER AS tableSchema
                FROM ALL_OBJECTS
                WHERE OBJECT_TYPE = 'TABLE'
                AND OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
                AND OBJECT_NAME LIKE UPPER('${tablePrefix}%')""");
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE,
                        MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_TABLES_RULE
                );
    }

    @Test
    void convertsMysqlInformationSchemaTablesQueryWithSchemaToAllTables() {
        SqlConversionResult result = converter.convert("""
                SELECT COUNT(1) table_count FROM information_schema.tables
                WHERE TABLE_SCHEMA = 'newsee-contract' AND TABLE_NAME = ?
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT COUNT(*) AS table_count FROM ALL_TABLES WHERE TABLE_NAME = UPPER(?) AND OWNER = UPPER('newsee-contract')");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_TABLES_RULE);
    }

    @Test
    void castsNumericLocateNeedleWhenSearchingConcatExpression() {
        SqlConversionResult result = converter.convert(
                "select * from schedule where LOCATE(#{precinctId}, concat(',', s3.precinctID, ',')) > 0"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select * from schedule where LOCATE(CAST(#{precinctId} AS VARCHAR(64)), (',') || (s3.precinctID) || (',')) > 0");
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_LOCATE_NUMERIC_NEEDLE_RULE,
                MySqlToDmSqlConverter.MYSQL_CONCAT_TO_DM_OPERATOR_RULE
        );
    }

    @Test
    void leavesStringLocateNeedleUnchanged() {
        SqlConversionResult result = converter.convert(
                "select * from user where LOCATE(#{mainSearch}, userName) > 0"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("select * from user where LOCATE(#{mainSearch}, userName) > 0");
    }

    @Test
    void marksIncompleteRegexpForManualReviewWhenItCannotBeConvertedSafely() {
        SqlConversionResult result = converter.convert("select * from user where code REGEXP");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("REGEXP");
    }

    @Test
    void marksInsertIgnoreForManualReview() {
        SqlConversionResult result = converter.convert("insert ignore into user(id, name) values(1, 'a')");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("INSERT IGNORE");
    }

    @Test
    void quotesReverseKeywordColumnIdentifiers() {
        SqlConversionResult result = converter.convert("""
                select reverse, m.reverse, reverse() as reversed
                from ns_meter_manage m
                where reverse = #{reverse}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select "reverse", m."reverse", reverse() as reversed
                from ns_meter_manage m
                where "reverse" = #{reverse}
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE);
    }

    @Test
    void convertsNestedMysqlDateAddIntervals() {
        SqlConversionResult result = converter.convert("""
                select DATE_ADD(DATE_ADD(CancelDate, INTERVAL 1 DAY), INTERVAL 1 YEAR)
                from Charge_ChargeStandard
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select DATEADD(YEAR, 1, DATEADD(DAY, 1, CancelDate))
                from Charge_ChargeStandard
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE);
    }

    @Test
    void convertsPeriodDiffYearMonthExpressions() {
        SqlConversionResult result = converter.convert("""
                select period_diff(extract(YEAR_MONTH from SYSDATE), extract(YEAR_MONTH from min(str_to_date(AccountBook, '%Y%m'))))
                from Charge_CustomerChargeDetail
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select DATEDIFF(MONTH, min(TO_DATE(AccountBook, 'YYYYMM')), SYSDATE)
                from Charge_CustomerChargeDetail
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_STR_TO_DATE_YEARMONTH_RULE,
                MySqlToDmSqlConverter.MYSQL_PERIOD_DIFF_YEARMONTH_RULE
        );
    }

    @Test
    void convertsPeriodDiffWithDateFormatYearMonthExpressions() {
        SqlConversionResult result = converter.convert("""
                select PERIOD_DIFF(DATE_FORMAT(CURDATE(), '%Y%m'), DATE_FORMAT(startDate, '%Y%m'))
                from ns_contract_info
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select DATEDIFF(MONTH, startDate, CURDATE())
                from ns_contract_info
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_PERIOD_DIFF_YEARMONTH_RULE);
    }

    @Test
    void convertsMysqlDateDiffTwoArgumentForm() {
        SqlConversionResult result = converter.convert("""
                select DATEDIFF(DATEADD(MONTH, 1, startDate), CURDATE())
                from ns_contract_info
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select DATEDIFF(DAY, CURDATE(), DATEADD(MONTH, 1, startDate))
                from ns_contract_info
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATEDIFF_2ARG_RULE);
    }

    @Test
    void convertsNestedPeriodDiffInsideMysqlDateDiff() {
        SqlConversionResult result = converter.convert("""
                select DATEDIFF(DATEADD(MONTH,
                    PERIOD_DIFF(DATE_FORMAT(CURDATE(), '%Y%m'), DATE_FORMAT(startDate, '%Y%m')),
                    startDate), CURDATE())
                from ns_contract_info
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select DATEDIFF(DAY, CURDATE(), DATEADD(MONTH,
                    DATEDIFF(MONTH, startDate, CURDATE()),
                    startDate))
                from ns_contract_info
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_PERIOD_DIFF_YEARMONTH_RULE,
                MySqlToDmSqlConverter.MYSQL_DATEDIFF_2ARG_RULE
        );
    }

    @Test
    void removesMysqlUseForceAndIgnoreIndexHints() {
        SqlConversionResult result = converter.convert("""
                select *
                from charge_customerchargedetail cd use index (idx_a)
                join other_table ot force index(idx_b) on cd.id = ot.id
                join third_table tt ignore index (idx_c) on tt.id = cd.id
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select *
                from charge_customerchargedetail cd join other_table ot on cd.id = ot.id
                join third_table tt on tt.id = cd.id
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_INDEX_HINT_REMOVAL_RULE);
    }

    @Test
    void convertsMysqlBooleanAggregationAndNotIsNullExpressions() {
        SqlConversionResult result = converter.convert("""
                select count(sendState = 'SEND_SUCCESS' or null) smsSuccessCount,
                       !ISNULL(c.refMeterReadId) charged,
                       IF((isnull(xm.billType) = 1) || (length(xm.billType) = 0), jt.billType, xm.billType) billType
                from ns_sms_details
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select COUNT(CASE WHEN sendState = 'SEND_SUCCESS' THEN 1 END) smsSuccessCount,
                       CASE WHEN ISNULL(c.refMeterReadId) THEN 0 ELSE 1 END charged,
                       CASE WHEN (isnull(xm.billType) = 1) OR (length(xm.billType) = 0) THEN jt.billType ELSE xm.billType END billType
                from ns_sms_details
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_NOT_ISNULL_RULE,
                MySqlToDmSqlConverter.MYSQL_COUNT_CONDITION_OR_NULL_RULE,
                MySqlToDmSqlConverter.MYSQL_BOOLEAN_OPERATOR_RULE,
                MySqlToDmSqlConverter.MYSQL_IF_TO_CASE_RULE
        );
    }

    @Test
    void convertsBareBooleanPredicateColumnsToEqualsOne() {
        SqlConversionResult result = converter.convert("""
                select count(*)
                from owner_house_result io
                where io.rent_status = '已租' and io.is_current_record
                  and io.deleteFlag
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                select count(*)
                from owner_house_result io
                where io.rent_status = '已租' and io.is_current_record = 1
                  and io.deleteFlag = 1
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_BARE_BOOLEAN_PREDICATE_RULE);
    }

    @Test
    void convertsBooleanLiteralComparisonsOnLikelyBooleanColumns() {
        SqlConversionResult result = converter.convert("""
                delete from owner_house_result
                where house_id = #{houseId} and is_current_record = true
                  and deleteFlag != false
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                delete from owner_house_result
                where house_id = #{houseId} and is_current_record = 1
                  and deleteFlag != 0
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_BOOLEAN_LITERAL_COMPARISON_RULE
        );
    }

    @Test
    void doesNotConvertBooleanLiteralComparisonsForNonBooleanColumnNames() {
        SqlConversionResult result = converter.convert("select * from t where status = true and note = 'true'");

        assertThat(result.changed()).isFalse();
    }

    @Test
    void doesNotConvertBooleanLikeColumnsThatAlreadyHaveOperators() {
        SqlConversionResult result = converter.convert("""
                select *
                from owner_house_result io
                where io.is_current_record = 1
                  and io.deleteFlag is null
                  or exists (select 1 from t where t.id = io.id)
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                select *
                from owner_house_result io
                where io.is_current_record = 1
                  and io.deleteFlag is null
                  or exists (select 1 from t where t.id = io.id)
                """);
    }

    @Test
    void convertsMysqlCountDistinctIfToCaseExpression() {
        SqlConversionResult result = converter.convert("""
                select count(DISTINCT completeUserId, if(completeUserId > 0, true, null)) userIdCount
                from ns_equip_inspect_task
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select COUNT(DISTINCT CASE WHEN completeUserId > 0 THEN completeUserId ELSE NULL END) userIdCount
                from ns_equip_inspect_task
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_COUNT_DISTINCT_IF_TO_CASE_RULE);
    }

    @Test
    void convertsMysqlIfFunctionToCaseExpression() {
        SqlConversionResult result = converter.convert("""
                select sum(amount) / if(count(DISTINCT log.id) = 0, 1, count(DISTINCT log.id)) as avgAmount
                from payment_log log
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select sum(amount) / CASE WHEN count(DISTINCT log.id) = 0 THEN 1 ELSE count(DISTINCT log.id) END as avgAmount
                from payment_log log
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_IF_TO_CASE_RULE);
    }

    @Test
    void quotesDamengPercentIdentifier() {
        SqlConversionResult result = converter.convert(
                "select id, percent, t.percent from ns_equip_maintain_task_support t where percent is not null"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select id, \"percent\", t.\"percent\" from ns_equip_maintain_task_support t where \"percent\" is not null");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.DAMENG_KEYWORD_IDENTIFIER_QUOTE_RULE);
    }

    @Test
    void marksAmbiguousLimitForManualReview() {
        SqlConversionResult result = converter.convert("select * from user limit ?, ?");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("LIMIT");
    }
}
