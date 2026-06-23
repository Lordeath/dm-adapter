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
                .isEqualTo("select DATEADD(MINUTE, 120, CONCAT(DATE(checkDate), ' ', onOffTime)) from record");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE);
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
    void doesNotConvertGroupConcatWithMultipleTopLevelExpressions() {
        SqlConversionResult result = converter.convert("select GROUP_CONCAT(first_name, last_name) from user");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.reason()).contains("GROUP_CONCAT");
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
                .isEqualTo("select u.id, u.user_name, ${item.fieldName} from sys_user u where u.enabled = 'Y'");
        assertThat(result.appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_BACKTICK_IDENTIFIER_RULE);
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
                where create_time < (SYSDATE - #{expireDays, jdbcType=INTEGER})
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_SUB_NOW_DAY_RULE);
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
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_SUB_NOW_DAY_RULE);
    }

    @Test
    void convertsDateSubCurdateIntervalDayWithMyBatisAmountToDateadd() {
        SqlConversionResult result = converter.convert(
                "select DATE_SUB(CURDATE(), INTERVAL #{days, jdbcType=INTEGER} DAY)"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select DATEADD(DAY, -#{days, jdbcType=INTEGER}, CURDATE())");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_DATE_SUB_NOW_DAY_RULE);
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
    void marksAmbiguousLimitForManualReview() {
        SqlConversionResult result = converter.convert("select * from user limit ?, ?");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("LIMIT");
    }
}
