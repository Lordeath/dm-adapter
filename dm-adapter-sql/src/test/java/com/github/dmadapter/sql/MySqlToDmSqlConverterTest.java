package com.github.dmadapter.sql;

import com.github.dmadapter.core.SqlConversionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlToDmSqlConverterTest {
    private final MySqlToDmSqlConverter converter = new MySqlToDmSqlConverter();

    @Test
    void leavesIfnullAndNowNative() {
        SqlConversionResult result = converter.convert("select IFNULL(name, 'n/a'), NOW() from user");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void leavesMysqlSysdateFunctionNative() {
        SqlConversionResult result = converter.convert("insert into audit_log(create_time) values (SYSDATE())");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void convertsMysqlCurrentSchemaFunctions() {
        SqlConversionResult result = converter.convert("select schema(), database(), 'schema()' as raw");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo(
                "select SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), "
                        + "SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), 'schema()' as raw"
        );
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_CURRENT_SCHEMA_FUNCTION_RULE);
    }

    @Test
    void convertsMysqlNullSafeEqualityWithoutChangingNotSemantics() {
        SqlConversionResult result = converter.convert("""
                SELECT *
                FROM ns_core_module target
                WHERE NOT (
                    target.module_group <=> NULLIF(TRIM(#{moduleGroup}), '')
                    AND target.order_id <=> CASE
                        WHEN #{mode} = 'BEFORE' THEN ref.order_id - 1
                        ELSE IFNULL(ref.order_id, 0) + 1
                    END
                )
                """);

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("(CASE WHEN (target.module_group) = (NULLIF(TRIM(#{moduleGroup}), ''))"
                        + " OR ((target.module_group) IS NULL"
                        + " AND (NULLIF(TRIM(#{moduleGroup}), '')) IS NULL)"
                        + " THEN 1 ELSE 0 END = 1)")
                .contains("(CASE WHEN (target.order_id) = (CASE")
                .contains("ELSE IFNULL(ref.order_id, 0) + 1")
                .contains("END) IS NULL) THEN 1 ELSE 0 END = 1)")
                .doesNotContain("<=>");
        assertThat(result.appliedRules()).contains(MySqlToDmSqlConverter.MYSQL_NULL_SAFE_EQUAL_RULE);
    }

    @Test
    void convertsMysqlNullSafeEqualityWithArithmeticRightOperand() {
        SqlConversionResult result = converter.convert(
                "SELECT 1 FROM dual WHERE mm.menu_order_index <=> IFNULL(target_last.menu_order_index, 0) + 1"
        );

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("(mm.menu_order_index) = (IFNULL(target_last.menu_order_index, 0) + 1)")
                .doesNotContain("<=>");
    }

    @Test
    void ignoresMysqlNullSafeEqualityInsideStringsAndComments() {
        SqlConversionResult result = converter.convert("""
                SELECT '<=>' AS operator_text
                FROM dual
                -- legacy predicate: a <=> b
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
    }

    @Test
    void convertsStrToDateHourSecondIntervalToDateaddSecond() {
        SqlConversionResult result = converter.convert("""
                select str_to_date(( date_format( a.`workDate`, '%Y-%m-%d' ) + INTERVAL '23:59:59' HOUR_SECOND ), '%Y-%m-%d %H:%i:%s' )
                from ns_user_workcheck a
                """);

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("DATEADD(SECOND, 86399, CAST(date_format( a.`workDate`, '%Y-%m-%d' ) AS DATETIME))")
                .doesNotContain("HOUR_SECOND")
                .doesNotContain("str_to_date");
        assertThat(result.appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_HOUR_SECOND_INTERVAL_RULE);
    }

    @Test
    void ignoresUnsupportedKeywordsInsideCommentsForManualReview() {
        SqlConversionResult result = converter.convert("""
                select 1
                -- ON DUPLICATE KEY UPDATE is mentioned in a migration note.
                from dual
                """);

        assertThat(result.manualReviewRequired()).isFalse();
    }

    @Test
    void convertsMysqlHashLineCommentsWithoutChangingMybatisPlaceholdersOrLiterals() {
        SqlConversionResult result = converter.convert("""
                SELECT id
                FROM ns_message_warehouse
                WHERE enterpriseId = #{enterpriseId}
                # 防止 OOM
                AND tag = '# keep literal'
                """);

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("WHERE enterpriseId = #{enterpriseId}")
                .contains("-- 防止 OOM")
                .contains("AND tag = '# keep literal'")
                .doesNotContain("\n# 防止 OOM");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_HASH_LINE_COMMENT_RULE);
    }

    @Test
    void ignoresMysqlMetadataReferencesInsideCommentsAndStrings() {
        SqlConversionResult result = converter.convert("""
                select 1 as ok
                /* old check: information_schema.columns where table_schema = database() */
                where note = 'information_schema.tables database()'
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
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
    void convertsDoubleQuotedStringLiteralsBeforeNativeFunctions() {
        SqlConversionResult result = converter.convert("select IFNULL(status, \"UNKNOWN\") from user");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("select IFNULL(status, 'UNKNOWN') from user");
        assertThat(result.appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
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
    void keepsAesEncryptExpressionAndConvertsDoubleQuotedStringLiteral() {
        SqlConversionResult result = converter.convert(
                "user_password = to_base64(AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR } \t,\"XXXXXXXX\")) ,"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("user_password = to_base64(AES_ENCRYPT(#{userPassword, jdbcType=VARCHAR } \t,'XXXXXXXX')) ,");
        assertThat(result.appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void safeDynamicTextSegmentRulesDoNotRewriteUpdateJoinPrefix() {
        SqlConversionResult result = converter.convertDynamicTextSegmentSafeRules(
                "UPDATE ns_system_user nu INNER JOIN ys_user c ON nu.ys_user_id = c.sso_user_id "
                        + "SET nu.sentry_id = case c.`sentry_id` when \"0\" then nu.sentry_id else c.`sentry_id` end, "
                        + "nu.user_password = to_base64(AES_ENCRYPT(c.`password`, \"sample-key\"))"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("UPDATE ns_system_user nu INNER JOIN ys_user c ON nu.ys_user_id = c.sso_user_id SET")
                .contains("when '0' then")
                .contains("to_base64(AES_ENCRYPT(c.`password`, 'sample-key'))")
                .doesNotContain(" from ");
        assertThat(result.appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void keepsBase64WrappedAesDecryptForCompatibilityFunction() {
        SqlConversionResult result = converter.convert(
                "select AES_DECRYPT(FROM_BASE64(user_password), 'XXXXXXXX') from user"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select AES_DECRYPT(FROM_BASE64(user_password), 'XXXXXXXX') from user");
        assertThat(result.appliedRules())
                .isEmpty();
    }

    @Test
    void convertsSimpleIntegerDivisionToDecimalArithmetic() {
        SqlConversionResult result = converter.convert("select 1/2*100 as pct, 100*(1/3) as pct2 from dual");

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo(
                "select CAST(1 AS DECIMAL(38,10)) / NULLIF(CAST(2 AS DECIMAL(38,10)), 0)*100 as pct, "
                        + "100*(CAST(1 AS DECIMAL(38,10)) / NULLIF(CAST(3 AS DECIMAL(38,10)), 0)) as pct2 from dual"
        );
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE);
    }

    @Test
    void convertsAggregateAndIdentifierDivisionToDecimalArithmetic() {
        SqlConversionResult result = converter.convert(
                "select SUM(receivable)/SUM(total), amount / count from bill_detail"
        );

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(
                "select CAST(SUM(receivable) AS DECIMAL(38,10)) / "
                        + "NULLIF(CAST(SUM(total) AS DECIMAL(38,10)), 0), "
                        + "CAST(amount AS DECIMAL(38,10)) / NULLIF(CAST(count AS DECIMAL(38,10)), 0) "
                        + "from bill_detail"
        );
    }

    @Test
    void convertsTimeToSecTimeDiffAndChainedIntegerDivision() {
        SqlConversionResult result = converter.convert(
                "select TIME_TO_SEC(TIMEDIFF(NOW(), task.created_at))/60/task.complete_limit "
                        + "from tenant_task task"
        );

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(
                "select CAST(DATEDIFF(SECOND, task.created_at, SYSDATE) AS DECIMAL(38,10))"
                        + " / NULLIF(CAST(60 AS DECIMAL(38,10)), 0)"
                        + " / NULLIF(CAST(task.complete_limit AS DECIMAL(38,10)), 0) "
                        + "from tenant_task task"
        );
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_TIME_TO_SEC_TIMEDIFF_RULE,
                MySqlToDmSqlConverter.MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE
        );
    }

    @Test
    void leavesUnsupportedTimeToSecShapeForManualReview() {
        SqlConversionResult result = converter.convert(
                "select TIME_TO_SEC(duration_value)/60 from tenant_task"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isTrue();
    }

    @Test
    void convertsMysqlDivOperatorToTruncDecimalArithmetic() {
        SqlConversionResult result = converter.convert("select 5 DIV 2, -5 DIV #{count} from dual");

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(
                "select TRUNC(CAST(5 AS DECIMAL(38,10)) / NULLIF(CAST(2 AS DECIMAL(38,10)), 0), 0), "
                        + "TRUNC(CAST(-5 AS DECIMAL(38,10)) / NULLIF(CAST(#{count} AS DECIMAL(38,10)), 0), 0) "
                        + "from dual"
        );
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_DIV_OPERATOR_TO_TRUNC_DECIMAL_RULE);
    }

    @Test
    void keepsDecimalDivisionAndAddsDecimalCastBeforeExistingNullifDenominator() {
        SqlConversionResult result = converter.convert(
                "select 1.0/2, CAST(a AS DECIMAL(18,6))/b, a/NULLIF(b,0) from dual"
        );

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(
                "select 1.0/2, CAST(a AS DECIMAL(18,6))/b, "
                        + "CAST(a AS DECIMAL(38,10)) / NULLIF(b,0) from dual"
        );
    }

    @Test
    void keepsDecimalDivisionWithScalarSubqueryDenominatorWithoutManualReview() {
        SqlConversionResult result = converter.convert("""
                SELECT ROUND(COUNT(DISTINCT companyId) * 100.0 /
                    (SELECT COUNT(DISTINCT companyId) FROM member_charge), 2) AS ratio
                FROM member_charge
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
    }

    @Test
    void marksIncompleteDecimalDivisionForManualReview() {
        SqlConversionResult result = converter.convert("SELECT 1.0 /");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.reason()).contains("整数算术");
    }

    @Test
    void marksUnsafeArithmeticDivisionForManualReview() {
        List<String> sqlItems = List.of(
                "select '10'/4 from dual",
                "select ${expr}/4 from dual",
                "select 1e0/2 from dual",
                "select TIMESTAMPDIFF(SECOND, start_time, end_time)/60 from task"
        );

        for (String sql : sqlItems) {
            SqlConversionResult result = converter.convert(sql);

            assertThat(result.changed()).isFalse();
            assertThat(result.manualReviewRequired()).isTrue();
            assertThat(result.reason()).contains("整数算术表达式风险");
        }
    }

    @Test
    void ignoresArithmeticOperatorsInsideStringsAndComments() {
        SqlConversionResult result = converter.convert("""
                select '1/2' as raw, note from demo
                -- 1/2
                where id = 1
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
    }

    @Test
    void keepsAesFunctionsForCompatibilityFunctions() {
        List<String> sqlItems = List.of(
                "select AES_ENCRYPT(name, 'XXXXXXXX') from user",
                "select TO_BASE64(AES_ENCRYPT(name, #{aesKey})) from user",
                "select AES_DECRYPT(password, 'XXXXXXXX') from user"
        );

        for (String sql : sqlItems) {
            SqlConversionResult result = converter.convert(sql);

            assertThat(result.manualReviewRequired()).isFalse();
            assertThat(result.changed()).isFalse();
            assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
    void preservesRownumPseudoColumnLimitPredicate() {
        SqlConversionResult result = converter.convert("""
                select * from bpm_check_opinion
                where proc_inst_id_ = #{procId} and ROWNUM = 1
                order by complete_time_ desc
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
    void leavesSimpleSelectLimitWithOffsetNative() {
        SqlConversionResult result = converter.convert("select * from user order by id limit 10, 20");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void leavesSelectLimitWithMyBatisPlaceholdersNative() {
        SqlConversionResult result = converter.convert("select * from user limit #{offset}, #{size}");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
                order by id desc limit 1
                """);
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_LIKE_PLACEHOLDER_LITERAL_TO_DM_CONCAT_RULE);
    }

    @Test
    void doesNotConvertWhereOnlyLimitFragment() {
        SqlConversionResult result = converter.convert(
                "where customerId = #{customerId} order by createTime desc limit 1"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void acceptsSelectLimitInsideUpdateScalarSubquery() {
        SqlConversionResult result = converter.convert("""
                UPDATE sample_event
                SET handled_at = #{handledAt}
                WHERE event_id = (
                    SELECT event_id FROM (
                        SELECT event_id
                        FROM sample_event
                        WHERE account_id = #{accountId}
                        ORDER BY updated_at DESC
                        LIMIT 1
                    ) latest_event
                )
                """);

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
    void keepsSingleQuotedProcedureArgumentsAfterInlineComments() {
        SqlConversionResult result = converter.convert("""
                call addOrUpdate_button('buttonId', -- button id
                '打印二维码', -- button name
                'actionPrintQrCodeBtn'
                )
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).contains("'打印二维码'").doesNotContain("AS \"打印二维码\"");
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
    void removesQuotedMysqlCollateClauses() {
        SqlConversionResult result = converter.convert("""
                create table if not exists demo_table (
                  id bigint,
                  name varchar(100) COLLATE 'utf8_general_ci'
                ) COLLATE "utf8mb4_unicode_ci"
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .doesNotContainIgnoringCase("COLLATE")
                .contains("name varchar(100)");
        assertThat(result.appliedRules()).contains(MySqlToDmSqlConverter.MYSQL_COLLATE_CLAUSE_REMOVAL_RULE);
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
                  `id` bigint NOT NULL IDENTITY(1,1),
                  `chargeItem` varchar(200) DEFAULT NULL,
                  PRIMARY KEY (`id`)
                )
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_COLLATE_CLAUSE_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_CHARACTER_SET_CLAUSE_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE,
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVAL_RULE
        );
    }

    @Test
    void preservesMysqlCharacterColumnLengthsWhenRemovingCharacterSets() {
        SqlConversionResult result = converter.convert("""
                create table tmp_charge_precinct (
                  `IsCheck` varchar(10) CHARACTER SET utf8 DEFAULT '审核通过',
                  `EmojiName` varchar(10) CHARSET=utf8mb4 DEFAULT NULL,
                  `Flag` char(2) CHARACTER SET utf8mb4 DEFAULT NULL
                )
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                create table tmp_charge_precinct (
                  `IsCheck` varchar(10) DEFAULT '审核通过',
                  `EmojiName` varchar(10) DEFAULT NULL,
                  `Flag` char(2) DEFAULT NULL
                )
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_CHARACTER_SET_CLAUSE_REMOVAL_RULE
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
                .contains("`id` bigint NOT NULL IDENTITY(1,1)")
                .contains("`type` int DEFAULT NULL")
                .contains("`doneFlag` tinyint DEFAULT NULL")
                .contains("PRIMARY KEY (`id`)")
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
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_OPTION_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_NUMERIC_TYPE_ATTRIBUTE_RULE,
                MySqlToDmSqlConverter.MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE,
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_COLUMN_COMMENT_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_USING_BTREE_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_KEY_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_ON_UPDATE_TIMESTAMP_REMOVAL_RULE
        );
    }

    @Test
    void removesMysqlZerofillNumericAttributeFromCreateAndAlterTable() {
        SqlConversionResult createResult = converter.convert("""
                CREATE TABLE ns_ipaas_interface_type (
                  direction tinyint(1) unsigned zerofill DEFAULT '0'
                )
                """);
        SqlConversionResult alterResult = converter.convert(
                "ALTER TABLE ns_ipaas_interface ADD COLUMN direction tinyint(1) zerofill unsigned DEFAULT '0'"
        );

        assertThat(createResult.convertedSql())
                .contains("direction tinyint DEFAULT '0'")
                .doesNotContainIgnoringCase("unsigned")
                .doesNotContainIgnoringCase("zerofill");
        assertThat(alterResult.convertedSql())
                .contains("direction tinyint DEFAULT '0'")
                .doesNotContainIgnoringCase("unsigned")
                .doesNotContainIgnoringCase("zerofill");
        assertThat(createResult.appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_NUMERIC_TYPE_ATTRIBUTE_RULE);
        assertThat(alterResult.appliedRules())
                .contains(MySqlToDmSqlConverter.MYSQL_NUMERIC_TYPE_ATTRIBUTE_RULE);
    }

    @Test
    void movesInlinePrimaryKeyFromIdentityColumnToTableConstraint() {
        SqlConversionResult result = converter.convert("""
                CREATE TABLE IF NOT EXISTS ns_core_role_del (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    enterpriseId BIGINT
                )
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                CREATE TABLE IF NOT EXISTS ns_core_role_del (
                    id BIGINT NOT NULL IDENTITY(1,1),
                    enterpriseId BIGINT
                , PRIMARY KEY (id))
                """);
        assertThat(result.appliedRules()).contains(
                MySqlToDmSqlConverter.MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE,
                MySqlToDmSqlConverter.MYSQL_IDENTITY_INLINE_PRIMARY_KEY_RULE
        );
    }

    @Test
    void removesCreateTableIndexesAfterLeadingCommentsAndGeneratedColumnStoredKeyword() {
        SqlConversionResult result = converter.convert("""
                create table if not exists ns_gate_operation_log (
                  id bigint NOT NULL AUTO_INCREMENT,
                  path varchar(100),
                  houseId bigint,
                  fullPath varchar(200) GENERATED ALWAYS AS (concat(path, houseId, _utf8mb3'/')) STORED,
                  PRIMARY KEY (id),
                  -- supports request lookup
                  INDEX idx_request_id (request_id),
                  /* supports interface lookup */
                  KEY idx_interface_time (interface_name, log_time)
                )
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("id bigint NOT NULL IDENTITY(1,1)")
                .contains("GENERATED ALWAYS AS (concat(path, houseId, _utf8mb3'/'))")
                .doesNotContain("AUTO_INCREMENT")
                .doesNotContain(" STORED")
                .doesNotContain("INDEX idx_request_id")
                .doesNotContain("KEY idx_interface_time");
        assertThat(result.appliedRules()).contains(
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_KEY_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE,
                MySqlToDmSqlConverter.MYSQL_GENERATED_COLUMN_STORED_REMOVAL_RULE
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
                  `chargeSum` DECIMAL(38,2) DEFAULT '0.00',
                  `ratio` numeric(38,38) DEFAULT NULL
                )
                """);
        assertThat(result.appliedRules()).containsExactly(
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
                    `dailyProperty` varchar(64) DEFAULT NULL,
                    PRIMARY KEY (id)
                ) ;
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_CREATE_TABLE_OPTION_REMOVAL_RULE,
                MySqlToDmSqlConverter.MYSQL_AUTO_INCREMENT_TO_DM_IDENTITY_RULE
        );
        assertThat(result.convertedSql()).doesNotContainIgnoringCase("COMMENT");
    }

    @Test
    void removesMysqlCreateTableTrailingCharacterSetForDameng() {
        SqlConversionResult result = converter.convert("""
                CREATE TABLE if not exists third_party_order_log (
                    id bigint NOT NULL AUTO_INCREMENT,
                    name varchar(64)
                ) CHARACTER SET = utf8mb4;
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .doesNotContainIgnoringCase("CHARACTER SET")
                .contains(") ;");
        assertThat(result.appliedRules()).contains(MySqlToDmSqlConverter.MYSQL_CHARACTER_SET_CLAUSE_REMOVAL_RULE);
    }

    @Test
    void removesQuotedMysqlCharacterSetClausesForDameng() {
        SqlConversionResult result = converter.convert("""
                CREATE TABLE IF NOT EXISTS ns_quality_demo (
                  id bigint NOT NULL AUTO_INCREMENT,
                  name varchar(20) CHARACTER SET 'utf8mb4' DEFAULT NULL,
                  PRIMARY KEY (id)
                ) CHARACTER SET 'utf8mb4';
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("name varchar(20) DEFAULT NULL")
                .doesNotContainIgnoringCase("CHARACTER SET");
        assertThat(result.appliedRules()).contains(MySqlToDmSqlConverter.MYSQL_CHARACTER_SET_CLAUSE_REMOVAL_RULE);
    }

    @Test
    void convertsMysqlTruncateWithoutTableKeywordForDameng() {
        SqlConversionResult result = converter.convert("TRUNCATE tmp_static_report_precinct_steward_report;");

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("TRUNCATE TABLE tmp_static_report_precinct_steward_report;");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_TRUNCATE_TABLE_RULE);
    }

    @Test
    void leavesMysqlAlterTableAutoIncrementResetNative() {
        SqlConversionResult result = converter.convert("ALTER TABLE ns_contract_info AUTO_INCREMENT = 1");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
    void quotesDamengRefTableAlias() {
        SqlConversionResult result = converter.convert("""
                UPDATE ns_core_module target
                JOIN ns_core_module ref ON ref.enterprise_id = target.enterprise_id
                SET target.module_group = ref.module_group
                WHERE target.module_id = #{moduleId}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                update ns_core_module target set module_group = "ref".module_group from ns_core_module "ref" where "ref".enterprise_id = target.enterprise_id and target.module_id = #{moduleId}
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.DAMENG_KEYWORD_TABLE_ALIAS_RULE,
                MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE
        );
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
    void removesEarlierDuplicateUpdateSetLiteralAssignments() {
        SqlConversionResult result = converter.convert("""
                UPDATE ns_core_resourcecolumn
                SET RESOURCECOLUMN_FILTERXTYPE = "select",
                    RESOURCECOLUMN_ISMULTIPLE = 2,
                    RESOURCECOLUMN_SOURCE = 0,
                    RESOURCECOLUMN_ISMULTIPLE = 99,
                    SY_ORDERINDEX = 500
                WHERE JE_CORE_RESOURCECOLUMN_ID = 'accountFromType'
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                UPDATE ns_core_resourcecolumn
                SET RESOURCECOLUMN_FILTERXTYPE = 'select', RESOURCECOLUMN_SOURCE = 0, RESOURCECOLUMN_ISMULTIPLE = 99, SY_ORDERINDEX = 500 WHERE JE_CORE_RESOURCECOLUMN_ID = 'accountFromType'
                """);
        assertThat(result.appliedRules())
                .containsExactly(
                        "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_DUPLICATE_UPDATE_SET_LITERAL_RULE
                );
    }

    @Test
    void keepsDuplicateUpdateSetAssignmentsWhenValueIsNotSimpleLiteral() {
        SqlConversionResult result = converter.convert("UPDATE demo SET amount = amount + 1, amount = amount + 1 WHERE id = 1");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
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
    void convertsNestedMysqlConvertDecimalToCast() {
        SqlConversionResult result = converter.convert(
                "SELECT CONVERT(CONVERT(price * area, DECIMAL(12, 2)) * ratio, DECIMAL(12, 2)) AS chargeSum"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT CAST(CAST(price * area AS DECIMAL(12, 2)) * ratio AS DECIMAL(12, 2)) AS chargeSum");
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
    void removesSqlServerNoLockTableHintsWithoutChangingCtesOrIgnoredText() {
        SqlConversionResult result = converter.convert("""
                WITH active_users AS (
                    SELECT id FROM users WHERE state = 1
                )
                SELECT a.id, b.name
                FROM CE_Standard_Scores a WITH(NOLOCK)
                LEFT JOIN CE_ExpertsGroup_Project b with ( NOLOCK ) ON a.ProjectID = b.ProjectID
                LEFT JOIN System_Area with(nolock) ON System_Area.id = a.area_id
                WHERE a.note = 'WITH(NOLOCK)'
                -- WITH(NOLOCK)
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("WITH active_users AS (")
                .contains("FROM CE_Standard_Scores a LEFT JOIN CE_ExpertsGroup_Project b ON")
                .contains("LEFT JOIN System_Area ON")
                .contains("a.note = 'WITH(NOLOCK)'")
                .contains("-- WITH(NOLOCK)")
                .doesNotContain("a WITH(NOLOCK)")
                .doesNotContain("b with ( NOLOCK )")
                .doesNotContain("\"with\"(nolock)");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.SQLSERVER_NOLOCK_HINT_REMOVAL_RULE);
    }

    @Test
    void convertsSqlServerTopInOuterAndNestedSelectScopes() {
        SqlConversionResult result = converter.convert("""
                SELECT TOP 1 id,
                       ABS(DATEDIFF(DAY, planned_date, (
                           SELECT TOP (1) paid_date
                           FROM payments
                           ORDER BY paid_date DESC
                       ))) AS days
                FROM contracts
                ORDER BY create_date DESC
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT id,
                       ABS(DATEDIFF(DAY, planned_date, (
                           SELECT paid_date
                           FROM payments
                           ORDER BY paid_date DESC FETCH FIRST 1 ROWS ONLY
                       ))) AS days
                FROM contracts
                ORDER BY create_date DESC FETCH FIRST 1 ROWS ONLY
                """);
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.SQLSERVER_TOP_TO_DM_FETCH_FIRST_RULE);
    }

    @Test
    void convertsSqlServerDefaultSchemaStringConcatenationAndCharIndex() {
        SqlConversionResult result = converter.convert("""
                SELECT c.MemberID
                FROM dbo.Register_CompanyMember c WITH(NOLOCK)
                WHERE ',' + CONVERT(VARCHAR(100), c.MemberType) + ',' LIKE '%,1,%'
                  AND CHARINDEX(#{keywords}, c.CompanyName) > 0
                  AND CHARINDEX('x', c.CompanyName, 2) > 0
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT c.MemberID
                FROM Register_CompanyMember c WHERE ',' || CONVERT(VARCHAR(100), c.MemberType) || ',' LIKE '%,1,%'
                  AND INSTR(c.CompanyName, #{keywords}) > 0
                  AND INSTR(c.CompanyName, 'x', 2) > 0
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.SQLSERVER_NOLOCK_HINT_REMOVAL_RULE,
                MySqlToDmSqlConverter.SQLSERVER_DBO_SCHEMA_REMOVAL_RULE,
                MySqlToDmSqlConverter.SQLSERVER_STRING_PLUS_TO_DM_CONCAT_RULE,
                MySqlToDmSqlConverter.SQLSERVER_CHARINDEX_TO_DM_INSTR_RULE
        );
    }

    @Test
    void leavesDboAndSqlServerOperatorsInsideIgnoredText() {
        SqlConversionResult result = converter.convert("""
                SELECT 'dbo.users ''a'' + CONVERT(VARCHAR(10), id) CHARINDEX(x, y)' AS sample
                FROM users
                -- dbo.audit ',' + name CHARINDEX(x, y)
                """);

        assertThat(result.changed()).isFalse();
    }

    @Test
    void doesNotConvertSqlServerTopInsideIgnoredTextOrUnsupportedPercentClause() {
        SqlConversionResult result = converter.convert("""
                SELECT 'SELECT TOP 1 id' AS sample
                FROM users
                -- SELECT TOP 1 id FROM users
                WHERE note = #{note}
                """);
        SqlConversionResult percent = converter.convert("SELECT TOP 10 PERCENT id FROM users");

        assertThat(result.changed()).isFalse();
        assertThat(percent.convertedSql()).contains("TOP 10");
        assertThat(percent.appliedRules())
                .doesNotContain(MySqlToDmSqlConverter.SQLSERVER_TOP_TO_DM_FETCH_FIRST_RULE);
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
                                       and family.owner_id = customer.owner_id
                limit #{offset},#{rows}
                """);
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_IMPLICIT_CROSS_JOIN_RULE);
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
    void convertsMysqlTemporaryTableAsSelectForDameng() {
        SqlConversionResult result = converter.convert("""
                drop table if exists tmp_relationship_owner_20200204;
                create TEMPORARY table tmp_relationship_owner_20200204
                SELECT rs.owner_id, rs.house_id
                FROM owner_house_relationship rs
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                drop table if exists tmp_relationship_owner_20200204;
                CREATE GLOBAL TEMPORARY TABLE tmp_relationship_owner_20200204 ON COMMIT PRESERVE ROWS AS SELECT rs.owner_id, rs.house_id
                FROM owner_house_relationship rs
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE);
    }

    @Test
    void convertsMysqlTemporaryTableAsSelectBeforeMyBatisForeach() {
        SqlConversionResult result = converter.convert("""
                create temporary table t_${tmpTableName}
                <foreach collection="list" item="item" separator=" union all ">
                  select #{item.id} AS id from dual
                </foreach>
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                CREATE GLOBAL TEMPORARY TABLE t_${tmpTableName} ON COMMIT PRESERVE ROWS AS <foreach collection='list' item='item' separator=' union all '>
                  select #{item.id} AS id from dual
                </foreach>
                """);
        assertThat(result.appliedRules()).containsExactly(
                "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                MySqlToDmSqlConverter.MYSQL_TEMPORARY_TABLE_AS_SELECT_RULE
        );
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
    void convertsMysqlDeleteAliasStarAfterRemovingIndexHint() {
        SqlConversionResult result = converter.convert("""
                delete x.* from ns_core_role_perm x USE index(ns_core_role_perm_idx)
                where x.perid = #{perid}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                delete from ns_core_role_perm x where x.perid = #{perid}
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_DELETE_ALIAS_STAR_RULE,
                MySqlToDmSqlConverter.MYSQL_INDEX_HINT_REMOVAL_RULE
        );
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
                .isEqualTo("select DATEADD(DAY, 1 - 1, TO_DATE(CONCAT(EXTRACT(YEAR FROM #{day}), '-01-01'), 'YYYY-MM-DD')) from dual");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_MAKEDATE_RULE);
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
                .isEqualTo("select DATE_FORMAT(LAST_DAY(DATEADD(MONTH, QUARTER (#{day}) * 3-1, DATEADD(DAY, 1 - 1, TO_DATE(CONCAT(EXTRACT(YEAR FROM #{day}), '-01-01'), 'YYYY-MM-DD')))),'%Y-%m-%d 23:59:59')");
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_DATE_ADD_INTERVAL_RULE,
                        MySqlToDmSqlConverter.MYSQL_MAKEDATE_RULE
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
    void convertsGroupConcatWithImplicitAliasAndTrimSeparator() {
        SqlConversionResult result = converter.convert(
                "SELECT GROUP_CONCAT(TRIM(TRAILING ','FROM inspectType))inspectType FROM ns_equip_inspect_template_item"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT LISTAGG(TRIM(TRAILING ','FROM inspectType), ',') WITHIN GROUP (ORDER BY TRIM(TRAILING ','FROM inspectType)) inspectType FROM ns_equip_inspect_template_item");
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
    void leavesSubstringIndexNativeAfterGroupConcatRewrite() {
        SqlConversionResult result = converter.convert(
                "select SUBSTRING_INDEX(GROUP_CONCAT(DISTINCT rs.owner_id order by rs.house_owner_relationship_id desc , ','),',',1) from owner_house_relationship rs"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select SUBSTRING_INDEX(LISTAGG(DISTINCT rs.owner_id, ',') WITHIN GROUP (ORDER BY rs.house_owner_relationship_id desc),',',1) from owner_house_relationship rs");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
    }

    @Test
    void leavesSubstringIndexNegativeOneNative() {
        SqlConversionResult result = converter.convert(
                "select SUBSTRING_INDEX(ys_ets_code,'-',-1) as ysEtsCode from owner_house_base_info"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void convertsMysqlHelpTopicStringSplitToCorrelatedCrossApply() {
        SqlConversionResult result = converter.convert("""
                select distinct ss.companyId
                from (
                    select s.companyId,
                           substring_index(
                               substring_index(s.serviceCategoryIds, ',', b.help_topic_id + 1),
                               ',',
                               -1
                           ) as serviceCategoryId
                    from ns_city_store s
                    join mysql.help_topic b
                      on b.help_topic_id < (
                          length(s.serviceCategoryIds)
                          - length(replace(s.serviceCategoryIds, ',', ''))
                          + 1
                      )
                ) ss
                where ss.serviceCategoryId = '1'
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("REGEXP_SUBSTR(s.serviceCategoryIds, '[^,]+', 1, b.help_topic_id + 1)")
                .contains("CROSS APPLY (SELECT LEVEL - 1 AS help_topic_id FROM dual CONNECT BY LEVEL <= "
                        + "LENGTH(s.serviceCategoryIds) - LENGTH(REPLACE(s.serviceCategoryIds, ',', '')) + 1) b")
                .doesNotContainIgnoringCase("mysql.help_topic")
                .doesNotContainIgnoringCase("substring_index");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_HELP_TOPIC_SPLIT_TO_CROSS_APPLY_RULE);
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
    void leavesMysqlConcatNative() {
        SqlConversionResult result = converter.convert(
                "select CONCAT(cd.PreinctName, '-', cd.HouseName) as userAddress from Charge_CustomerChargeDetail cd"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void convertsSingleArgumentMysqlConcatToInnerExpression() {
        SqlConversionResult result = converter.convert(
                "select CONCAT(DATE_FORMAT(CalcStartDate,'%Y-%m-%d')) as CalcStartDate from detail"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select DATE_FORMAT(CalcStartDate,'%Y-%m-%d') as CalcStartDate from detail");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SINGLE_ARGUMENT_CONCAT_RULE);
    }

    @Test
    void convertsNestedSingleArgumentMysqlConcatWithMyBatisPlaceholder() {
        SqlConversionResult result = converter.convert(
                "and cd.OwnerName like concat(concat(#{customerName}),'%')"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("and cd.OwnerName like concat(#{customerName},'%')");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SINGLE_ARGUMENT_CONCAT_RULE);
    }

    @Test
    void convertsOwnerLikeNestedSingleArgumentMysqlConcat() {
        SqlConversionResult result = converter.convert(
                "and (i.house_full_name like concat('%', concat(#{ownerName}), '%') "
                        + "or c.owner_name like concat(concat(#{ownerName}), '%'))"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("and (i.house_full_name like concat('%', #{ownerName}, '%') "
                        + "or c.owner_name like concat(#{ownerName}, '%'))");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_SINGLE_ARGUMENT_CONCAT_RULE);
    }

    @Test
    void convertsLikePlaceholderAdjacentStringLiteralToDamengConcat() {
        SqlConversionResult result = converter.convert(
                "select * from ns_wms_material where `materialCode` LIKE #{materialClassCode}\"%\""
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select * from ns_wms_material where `materialCode` LIKE (#{materialClassCode}) || ('%')");
        assertThat(result.appliedRules())
                .containsExactly(
                        "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
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
    void leavesNotFindInSetNativeAfterGroupConcatRewrite() {
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
                having !find_in_set('1', (LISTAGG(callStatus, ',') WITHIN GROUP (ORDER BY callStatus)))
                """);
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE,
                        MySqlToDmSqlConverter.MYSQL_HAVING_AGGREGATE_ALIAS_RULE
                );
    }

    @Test
    void leavesNotFindInSetOutsideHavingNative() {
        String sql = """
                select * from bill
                where ! FIND_IN_SET(#{status}, status_list)
                  and note = '!find_in_set(1, x)'
                  -- !find_in_set(1, x)
                """;

        SqlConversionResult result = converter.convert(sql);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(sql);
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
    void leavesBacktickQuotedHavingAggregateAliasNative() {
        SqlConversionResult result = converter.convert("""
                select SUM(amount) AS `totalAmount`, item_id
                from bill
                group by item_id
                having `totalAmount` > 0 and remark <> 'totalAmount'
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
    void convertsDefaultYearWeekWhileKeepingOtherSafeConversions() {
        SqlConversionResult result = converter.convert(
                "select \"ACTIVE\" as status, YEARWEEK(created_at) from audit_log"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("select 'ACTIVE' as status, "
                        + "(YEAR(DATEADD(DAY, -WEEKDAY(created_at), created_at)) * 100 "
                        + "+ WEEK(created_at, 2)) from audit_log");
        assertThat(result.appliedRules())
                .containsExactly(
                        "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_YEARWEEK_RULE
                );
    }

    @Test
    void convertsYearWeekDateFormatEqualityUsingOriginalDateValues() {
        SqlConversionResult result = converter.convert(
                "select * from payment where "
                        + "YEARWEEK(date_format(OperatorDate,'%Y-%m-%d')) = YEARWEEK(now())"
        );

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("(YEAR(DATEADD(DAY, -WEEKDAY(OperatorDate), OperatorDate)) * 100 "
                        + "+ WEEK(OperatorDate, 2))")
                .contains("(YEAR(DATEADD(DAY, -WEEKDAY(now()), now())) * 100 + WEEK(now(), 2))")
                .doesNotContain("YEARWEEK")
                .doesNotContain("DATE_FORMAT");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_YEARWEEK_RULE);
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
    void leavesBacktickIdentifiersNativeWhileStillConvertingStrings() {
        SqlConversionResult result = converter.convert(
                "select u.`id`, u.`user_name`, `${item.fieldName}` from `sys_user` u where u.`enabled` = \"Y\""
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select u.`id`, u.`user_name`, `${item.fieldName}` from `sys_user` u where u.`enabled` = 'Y'");
        assertThat(result.appliedRules())
                .containsExactly("DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING");
    }

    @Test
    void leavesBacktickIdentifiersThatNeedCasePreservationNative() {
        SqlConversionResult result = converter.convert(
                "select `foreignerKeyId`, t.`extField`, `${key}` from `ns_other_information_extend` t"
        );

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void leavesReservedOrSpecialBacktickIdentifiersNative() {
        SqlConversionResult result = converter.convert("select `order`, `sample-system`.`user-table` from `user`");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void leavesBacktickKeywordIdentifiersInsideGroupConcatNative() {
        SqlConversionResult result = converter.convert(
                "select GROUP_CONCAT(IF(`percent` IS NULL, '', `percent`) ORDER BY id SEPARATOR ';') from ns_equip_maintain_task_support"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .isEqualTo("select LISTAGG(IF(`percent` IS NULL, '', `percent`), ';') WITHIN GROUP (ORDER BY id) from ns_equip_maintain_task_support");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE);
    }

    @Test
    void leavesBackticksInsideStringsCommentsAndIdentifiersNative() {
        SqlConversionResult result = converter.convert("""
                select '`order`' as raw, `status` -- `comment`
                from user /* `block` */
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
    void convertsNestedDateSubIntervalsFromInsideOut() {
        SqlConversionResult result = converter.convert("""
                select DATE_FORMAT(
                    DATE_SUB(
                        DATE_SUB(#{rangeStart}, INTERVAL WEEKDAY(#{rangeStart}) DAY),
                        INTERVAL 1 WEEK
                    ),
                    '%Y-%m-%d 00:00:00'
                )
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("DATEADD(WEEK, -1,")
                .contains("DATEADD(DAY, (0 - WEEKDAY(#{rangeStart})), #{rangeStart})")
                .doesNotContainIgnoringCase("DATE_SUB");
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
    void convertsMysqlRegexpOperatorWithConcatRightOperand() {
        SqlConversionResult result = converter.convert("""
                select id
                from ns_contract_template
                where departmentIds REGEXP CONCAT( '(^|,)(',#{seeOrganizationIds,jdbcType=VARCHAR}, ')(,|$)')
                order by id desc
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select id
                from ns_contract_template
                where REGEXP_LIKE(departmentIds, CONCAT( '(^|,)(',#{seeOrganizationIds,jdbcType=VARCHAR}, ')(,|$)'))
                order by id desc
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
    void convertsDerivedTableMysqlUpdateJoinToDamengUpdateFrom() {
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
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).contains("""
                update ys_organization y set is_deleted =1 from (
                    select a.organization_id
                    from ys_organization a
                    left join ys_organization b on a.sync_organization_parent_id=b.sync_organization_id
                    where b.is_deleted=1 and a.is_deleted=0
                ) c where y.organization_id =c.organization_id and y.sync_organization_parent_id is not null
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void convertsDerivedTableMysqlUpdateJoinBeforeQuotingKeywordColumns() {
        SqlConversionResult result = converter.convert("""
                update ns_system_pre_organization y inner join (
                    select a.organization_id from ns_system_pre_organization a
                ) c on c.organization_id = y.organization_id
                set y.sync_flag=2,y.desc = 'NsOrgParentNotExist'
                where y.organization_id =c.organization_id
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                update ns_system_pre_organization y set sync_flag=2,"DESC" = 'NsOrgParentNotExist' from (
                    select a.organization_id from ns_system_pre_organization a
                ) c where c.organization_id = y.organization_id and y.organization_id =c.organization_id
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void convertsOrdinaryMysqlUpdateJoinToDamengUpdateFrom() {
        SqlConversionResult result = converter.convert("""
                update ns_system_user nu
                inner join ys_user c on nu.ys_user_id = c.sso_user_id
                set nu.user_name = c.user_name,
                    nu.update_time = now()
                where nu.enterprise_id = #{enterpriseId}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                update ns_system_user nu set user_name = c.user_name,
                    update_time = now() from ys_user c where nu.ys_user_id = c.sso_user_id and nu.enterprise_id = #{enterpriseId}
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void convertsMultipleMysqlUpdateJoinStatements() {
        SqlConversionResult result = converter.convert("""
                update ns_system_organization_detail od inner join ns_system_organization o on od.organization_id =o.organization_id
                    set od.organization_type = o.organization_type,od.organization_nature = o.organization_nature,od.organization_path = o.organization_path;

                update ns_system_organization_detail od inner join ns_system_organization o on od.affiliated_organization_id =o.organization_id
                    set od.affiliated_organization_type = o.organization_type,od.affiliated_organization_nature = o.organization_nature,od.affiliated_organization_path = o.organization_path;
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("update ns_system_organization_detail od set organization_type = o.organization_type")
                .contains("from ns_system_organization o where od.organization_id =o.organization_id")
                .contains("update ns_system_organization_detail od set affiliated_organization_type = o.organization_type")
                .contains("from ns_system_organization o where od.affiliated_organization_id =o.organization_id");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void convertsSingleTargetMysqlUpdateWithMultipleInnerJoins() {
        SqlConversionResult result = converter.convert("""
                UPDATE sample_target extend
                INNER JOIN sample_info info ON extend.id = info.id
                INNER JOIN sample_base base ON info.base_id = base.id
                INNER JOIN sample_scope scope ON base.scope_id = scope.id
                SET extend.kind = scope.kind
                WHERE base.is_deleted = 0 AND scope.kind IS NOT NULL
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                update sample_target extend set kind = scope.kind from sample_info info, sample_base base, sample_scope scope where extend.id = info.id and info.base_id = base.id and base.scope_id = scope.id and base.is_deleted = 0 AND scope.kind IS NOT NULL
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void leavesSingleTargetMysqlUpdateWithOuterJoinForManualHandling() {
        SqlConversionResult result = converter.convert("""
                UPDATE sample_target target
                LEFT JOIN sample_source source ON target.source_id = source.id
                SET target.label = source.label
                WHERE target.is_deleted = 0
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.reason()).contains("UPDATE JOIN");
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void leavesSingleTargetMysqlUpdateWithExplicitOuterJoinForManualHandling() {
        SqlConversionResult result = converter.convert("""
                UPDATE sample_target target
                LEFT OUTER JOIN sample_source source ON target.source_id = source.id
                SET target.label = source.label
                WHERE target.is_deleted = 0
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.reason()).contains("UPDATE JOIN");
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }

    @Test
    void convertsDerivedTableFollowedByAnotherMysqlUpdateJoin() {
        SqlConversionResult result = converter.convert("""
                UPDATE sample_task target
                JOIN (
                    SELECT candidate.id
                    FROM sample_task candidate
                    JOIN sample_transfer transfer ON transfer.owner_id = candidate.owner_id
                    WHERE transfer.id = #{transferId}
                    LIMIT #{rowCount}
                ) selected ON target.id = selected.id
                JOIN sample_transfer transfer ON transfer.owner_id = target.owner_id
                SET target.owner_id = transfer.new_owner_id
                WHERE transfer.id = #{transferId}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                update sample_task target set owner_id = transfer.new_owner_id from (
                    SELECT candidate.id
                    FROM sample_task candidate
                    JOIN sample_transfer transfer ON transfer.owner_id = candidate.owner_id
                    WHERE transfer.id = #{transferId}
                    LIMIT #{rowCount}
                ) selected, sample_transfer transfer where target.id = selected.id and transfer.owner_id = target.owner_id and transfer.id = #{transferId}
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void convertsUpdateOfSingleJoinedAliasAfterLimitedDerivedSelection() {
        SqlConversionResult result = converter.convert("""
                UPDATE sample_task target
                JOIN (
                    SELECT candidate.id
                    FROM sample_task candidate
                    JOIN sample_transfer filter_transfer ON filter_transfer.owner_id = candidate.owner_id
                    WHERE filter_transfer.id = #{transferId}
                    LIMIT #{rowCount}
                ) selected ON target.id = selected.id
                JOIN sample_transfer transfer ON transfer.scope_id = target.scope_id
                JOIN sample_task_assignee assignee ON assignee.task_id = target.id
                SET assignee.owner_id = transfer.new_owner_id,
                    assignee.updated_by = #{operatorId}
                WHERE transfer.id = #{transferId}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                update sample_task_assignee assignee set owner_id = transfer.new_owner_id,
                    updated_by = #{operatorId} from sample_task target, (
                    SELECT candidate.id
                    FROM sample_task candidate
                    JOIN sample_transfer filter_transfer ON filter_transfer.owner_id = candidate.owner_id
                    WHERE filter_transfer.id = #{transferId}
                    LIMIT #{rowCount}
                ) selected, sample_transfer transfer where target.id = selected.id and transfer.scope_id = target.scope_id and assignee.task_id = target.id and transfer.id = #{transferId}
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void convertsMysqlUpdateJoinThatSetsMultipleAliasesWhenPredicatesStayStable() {
        SqlConversionResult result = converter.convert("""
                update ns_quality_check_schedule_task a
                join ns_quality_check_schedule_task_user u on a.ID = u.checkScheduleTaskID
                set u.checkUserID = #{userId}, a.transferType = 1
                where a.ID = #{id}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("BEGIN")
                .contains("update ns_quality_check_schedule_task_user u set u.checkUserID = #{userId}")
                .contains("update ns_quality_check_schedule_task a set a.transferType = 1")
                .endsWith("END;");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void convertsSelfJoinMultiTargetUpdateWithRowCountGuardWhenBothPredicatesChange() {
        SqlConversionResult result = converter.convert("""
                UPDATE ns_payment_chargepayment a
                INNER JOIN ns_payment_chargepayment b
                    ON a.id = b.RefPaymentID AND b.IsDelete = 0
                SET a.canRefundPaid = a.canRefundPaid + abs(b.ChargePaid),
                    a.IsCanceled = 0,
                    b.IsDelete = 1
                WHERE a.id = #{id} AND a.IsCanceled = 1;
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("BEGIN")
                .contains("update ns_payment_chargepayment a set a.canRefundPaid")
                .contains("IF SQL%ROWCOUNT > 0 THEN")
                .contains("update ns_payment_chargepayment b set b.IsDelete = 1")
                .contains("where b.RefPaymentID = #{id} and b.IsDelete = 0")
                .contains("END;")
                .doesNotContain("END;;");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
    }

    @Test
    void leavesMultiTargetUpdateNativeWhenOneAssignmentReadsAColumnUpdatedOnAnotherTarget() {
        String sql = """
                update account a
                join account_detail d on a.ID = d.accountID
                set d.status = 1,
                    a.previousStatus = d.status
                where a.ID = #{id}
                """;

        SqlConversionResult result = converter.convert(sql);

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(sql);
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
    void convertsSimpleMysqlDeleteOrderLimitOneToDamengRowidSubquery() {
        SqlConversionResult result = converter.convert("""
                DELETE FROM tenant_event_log
                WHERE tenant_id = #{tenantId} ORDER BY event_id DESC LIMIT 1
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                delete from tenant_event_log where ROWID in (select rid from (select ROWID rid from tenant_event_log where tenant_id = #{tenantId} order by event_id DESC) where ROWNUM <= 1)
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_DELETE_ORDER_LIMIT_ONE_RULE
        );
    }

    @Test
    void doesNotConvertUnsafeMysqlDeleteOrderLimitShapes() {
        SqlConversionResult aliasResult = converter.convert(
                "delete from tenant_event_log e where e.tenant_id = #{tenantId} order by e.event_id desc limit 1"
        );
        SqlConversionResult multiLimitResult = converter.convert(
                "delete from tenant_event_log where tenant_id = #{tenantId} order by event_id desc limit 2"
        );

        assertThat(aliasResult.manualReviewRequired()).isTrue();
        assertThat(multiLimitResult.manualReviewRequired()).isTrue();
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
    void convertsComplexMultiJoinUpdateToDamengUpdateFrom() {
        SqlConversionResult result = converter.convert("""
                update ys_role_permission_exp yrpe
                inner join ns_core_resourcebutton ncrb on yrpe.button_id = ncrb.id
                inner join ns_core_funcinfo f on f.id = ncrb.funcinfo_id
                set yrpe.func_name = f.funcinfo_funcname
                where yrpe.enterprise_id = #{enterpriseId}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                update ys_role_permission_exp yrpe set func_name = f.funcinfo_funcname from ns_core_resourcebutton ncrb, ns_core_funcinfo f where yrpe.button_id = ncrb.id and f.id = ncrb.funcinfo_id and yrpe.enterprise_id = #{enterpriseId}
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_UPDATE_JOIN_RULE);
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
                "DATE_ADD",
                "DATE_SUB",
                "MAKEDATE",
                "PERIOD_DIFF"
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
    void keepsExplicitYearWeekModeForManualReview() {
        SqlConversionResult result = converter.convert("select YEARWEEK(created_at, 3) from user");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.reason()).contains("YEARWEEK");
    }

    @Test
    void leavesDameng53CompatibleMysqlFunctionsNative() {
        List<String> sqlItems = List.of(
                "select STR_TO_DATE('202401', '%Y%m') from dual",
                "select UNIX_TIMESTAMP(created_at), FROM_UNIXTIME(0) from user",
                "select TIMESTAMPDIFF(DAY, start_at, end_at) from user",
                "select CONCAT_WS('-', province, city) from user",
                "select JSON_SET(payload, '$.name', 'x'), JSON_UNQUOTE(JSON_QUOTE('x')) from audit_log"
        );

        for (String sql : sqlItems) {
            SqlConversionResult result = converter.convert(sql);

            assertThat(result.changed()).isFalse();
            assertThat(result.manualReviewRequired()).isFalse();
            assertThat(result.convertedSql()).isEqualTo(sql);
        }
    }

    @Test
    void marksMysqlUserVariablesForManualReview() {
        SqlConversionResult result = converter.convert("""
                select @rn := @rn + 1 row_num, t.*
                from bill t, (select @rn := 0) vars
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("@var");
    }

    @Test
    void removesUnusedMysqlUserVariableInitializerFromDerivedSelect() {
        SqlConversionResult result = converter.convert("""
                select wrapped.id
                from (
                    select @unused := 0, user.id
                    from user
                ) wrapped
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                select wrapped.id
                from (
                    select user.id
                    from user
                ) wrapped
                """);
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_UNUSED_USER_VARIABLE_SELECT_ITEM_RULE);
    }

    @Test
    void keepsMysqlUserVariableInitializerWhenVariableIsReferencedElsewhere() {
        SqlConversionResult result = converter.convert("""
                select @rn := @rn + 1 row_num, t.*
                from bill t, (select @rn := 0, 1 as seed) vars
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("@var");
    }

    @Test
    void removesTrailingStatementSemicolonsFromMapperSql() {
        SqlConversionResult result = converter.convert("""
                update ys_organization
                set update_time = sys_time
                where update_time != sys_time;
                ;
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                update ys_organization
                set update_time = sys_time
                where update_time != sys_time
                """);
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_TRAILING_SEMICOLON_REMOVAL_RULE);
    }

    @Test
    void ignoresMysqlUserVariableMarkersInsideSafeText() {
        SqlConversionResult result = converter.convert("""
                select '@rn := 1' as sample, #{userName}
                -- @rn := 1
                from user
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
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
    void convertsDescribeTableToDamengUserTabColumns() {
        SqlConversionResult result = converter.convert("describe owner_customer_result");

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT COLUMN_NAME AS \"Field\", DATA_TYPE AS \"Type\", NULLABLE AS \"Null\", "
                        + "NULL AS \"Key\", DATA_DEFAULT AS \"Default\", NULL AS \"Extra\" "
                        + "FROM USER_TAB_COLUMNS WHERE TABLE_NAME = UPPER('owner_customer_result') ORDER BY COLUMN_ID");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_DESCRIBE_TABLE_RULE);
    }

    @Test
    void convertsDescribeDynamicTableToDamengUserTabColumns() {
        SqlConversionResult result = converter.convert("describe ${targetTable}");

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .contains("WHERE TABLE_NAME = UPPER('${targetTable}')");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_DESCRIBE_TABLE_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaColumnsQueryToAllTabColumns() {
        SqlConversionResult result = converter.convert("""
                SELECT column_name FROM information_schema.COLUMNS
                WHERE TABLE_NAME =? and TABLE_SCHEMA='sample-quality'
                ORDER BY ORDINAL_POSITION asc
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE TABLE_NAME = UPPER(?) AND OWNER = UPPER('sample-quality') ORDER BY COLUMN_ID ASC");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaColumnsDynamicTableWithoutSchemaToAllTabColumns() {
        SqlConversionResult result = converter.convert(
                "SELECT column_name FROM information_schema.columns WHERE table_name = '${taskTableName}';"
        );

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE TABLE_NAME = UPPER('${taskTableName}') ORDER BY COLUMN_ID");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaColumnsDatabaseSchemaToCurrentSchema() {
        SqlConversionResult result = converter.convert("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = '${taskTableName}' and table_schema = DATABASE()
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE TABLE_NAME = UPPER('${taskTableName}') AND OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA') ORDER BY COLUMN_ID");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaColumnCountForDifferentRuntimeSchemas() {
        for (String schema : List.of("tenant_alpha", "tenant_beta")) {
            SqlConversionResult result = converter.convert("""
                    SELECT COUNT(1)
                    FROM information_schema.COLUMNS
                    WHERE table_schema = '%s'
                      AND table_name = #{tableName}
                      AND column_name = #{columnName}
                    """.formatted(schema));

            assertThat(result.changed()).isTrue();
            assertThat(result.manualReviewRequired()).isFalse();
            assertThat(result.convertedSql())
                    .isEqualTo("SELECT COUNT(*) FROM ALL_TAB_COLUMNS"
                            + " WHERE TABLE_NAME = UPPER(#{tableName})"
                            + " AND OWNER = UPPER('" + schema + "')"
                            + " AND COLUMN_NAME = UPPER(#{columnName})");
            assertThat(result.appliedRules())
                    .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
        }
    }

    @Test
    void convertsMysqlInformationSchemaColumnNameAggregation() {
        SqlConversionResult result = converter.convert("""
                SELECT GROUP_CONCAT(COLUMN_NAME) AS result
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = (select database())
                  AND TABLE_NAME = #{tableName}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT LISTAGG(COLUMN_NAME, ',') WITHIN GROUP (ORDER BY COLUMN_ID) AS result"
                        + " FROM ALL_TAB_COLUMNS"
                        + " WHERE TABLE_NAME = UPPER(#{tableName})"
                        + " AND OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')");
        assertThat(result.appliedRules())
                .containsExactly(
                        MySqlToDmSqlConverter.MYSQL_GROUP_CONCAT_TO_DM_LISTAGG_RULE,
                        MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE
                );
    }

    @Test
    void convertsMysqlInformationSchemaSimpleColumnDetails() {
        SqlConversionResult result = converter.convert("""
                SELECT COLUMN_NAME, COLUMN_COMMENT, DATA_TYPE, IS_NULLABLE,
                       COLUMN_DEFAULT, CHARACTER_MAXIMUM_LENGTH
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = (select database())
                  AND TABLE_NAME = #{tableName}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT
                    c.COLUMN_NAME AS COLUMN_NAME,
                    cc.COMMENTS AS COLUMN_COMMENT,
                    c.DATA_TYPE AS DATA_TYPE,
                    CASE c.NULLABLE WHEN 'Y' THEN 'YES' ELSE 'NO' END AS IS_NULLABLE,
                    c.DATA_DEFAULT AS COLUMN_DEFAULT,
                    c.CHAR_LENGTH AS CHARACTER_MAXIMUM_LENGTH
                FROM ALL_TAB_COLUMNS c
                LEFT JOIN ALL_COL_COMMENTS cc
                    ON cc.OWNER = c.OWNER
                    AND cc.TABLE_NAME = c.TABLE_NAME
                    AND cc.COLUMN_NAME = c.COLUMN_NAME
                 WHERE c.TABLE_NAME = UPPER(#{tableName}) AND c.OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
                ORDER BY c.COLUMN_ID
                """.strip());
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaColumnDetailsToDamengMetadataViews() {
        SqlConversionResult result = converter.convert("""
                select
                    TABLE_SCHEMA as tableSchema,
                    TABLE_NAME as tableName,
                    COLUMN_NAME as columnName,
                    COLUMN_TYPE as columnType,
                    COLUMN_COMMENT as columnComment,
                    IS_NULLABLE as isNullAble
                from information_schema.COLUMNS
                where TABLE_SCHEMA = (select database())
                  and TABLE_NAME = #{tableName}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT
                    c.OWNER AS "tableSchema",
                    c.TABLE_NAME AS "tableName",
                    c.COLUMN_NAME AS "columnName",
                    c.DATA_TYPE AS "columnType",
                    cc.COMMENTS AS "columnComment",
                    CASE c.NULLABLE WHEN 'Y' THEN 'YES' ELSE 'NO' END AS "isNullAble"
                FROM ALL_TAB_COLUMNS c
                LEFT JOIN ALL_COL_COMMENTS cc
                    ON cc.OWNER = c.OWNER
                    AND cc.TABLE_NAME = c.TABLE_NAME
                    AND cc.COLUMN_NAME = c.COLUMN_NAME
                WHERE c.TABLE_NAME = UPPER(#{tableName}) AND c.OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
                ORDER BY c.COLUMN_ID
                """.strip());
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
    }

    @Test
    void convertsMysqlColumnDescriptorMetadataForDifferentRuntimeSchemas() {
        for (String schema : List.of("tenant_alpha", "tenant_beta")) {
            SqlConversionResult result = converter.convert("""
                    select c.COLUMN_NAME as columnName
                    , c.COLUMN_TYPE as columnType
                    , c.COLUMN_COMMENT as columnComment
                    , c.COLUMN_DEFAULT as columnDefault
                    , c.COLUMN_KEY as columnKey
                    , c.EXTRA as extra
                    from information_schema.`COLUMNS` c
                    where TABLE_SCHEMA = '%s'
                    and TABLE_NAME = '${tableName}'
                    order by ORDINAL_POSITION
                    """.formatted(schema));

            assertThat(result.changed()).isTrue();
            assertThat(result.manualReviewRequired()).isFalse();
            assertThat(result.convertedSql())
                    .contains("sc.NAME AS \"columnName\"")
                    .contains("END AS \"columnType\"")
                    .contains("scc.COMMENT$ AS \"columnComment\"")
                    .contains("sc.DEFVAL AS \"columnDefault\"")
                    .contains("THEN 'PRI'")
                    .contains("THEN 'UNI'")
                    .contains("THEN 'MUL'")
                    .contains("MOD(sc.INFO2, 2) = 1")
                    .contains("WHERE sch.NAME = UPPER('" + schema + "')")
                    .contains("AND obj.NAME = UPPER('${tableName}')")
                    .doesNotContain("information_schema");
            assertThat(result.appliedRules())
                    .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
        }
    }

    @Test
    void convertsMysqlIndexMetadataForCurrentRuntimeSchema() {
        SqlConversionResult result = converter.convert("""
                select distinct s.INDEX_NAME
                from information_schema.`STATISTICS` s
                where TABLE_SCHEMA = (select DATABASE())
                and TABLE_NAME = '${tableName}'
                and INDEX_NAME != 'PRIMARY'
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("FROM ALL_INDEXES i")
                .contains("WHERE i.OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')")
                .contains("AND i.TABLE_NAME = UPPER('${tableName}')")
                .contains("ac.CONSTRAINT_TYPE = 'P'")
                .doesNotContain("information_schema")
                .doesNotContain("DATABASE()");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_STATISTICS_RULE);
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
                AND TABLE_NAME LIKE UPPER(concat(#{tablePrefix},'%'))
                AND COLUMN_NAME NOT IN
                <foreach collection='columnNameList' item='item' open='(' close=')' separator=','>
                    #{item}
                </foreach>
                GROUP BY TABLE_NAME""");
        assertThat(result.appliedRules())
                .containsExactly(
                        "DOUBLE_QUOTED_STRING_TO_SINGLE_QUOTED_STRING",
                        MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE
                );
    }

    @Test
    void convertsMysqlInformationSchemaColumnsTableListPrefixSplitByDynamicForeach() {
        SqlConversionResult result = converter.convert("""
                SELECT table_name
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE table_name LIKE concat(#{tablePrefix},'%')
                AND table_schema = (select DATABASE())
                AND column_name not in
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                SELECT TABLE_NAME
                FROM ALL_TAB_COLUMNS
                WHERE OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')
                AND TABLE_NAME LIKE UPPER(concat(#{tablePrefix},'%'))
                AND COLUMN_NAME NOT IN""");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_COLUMNS_RULE);
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
    void convertsMysqlInformationSchemaTableExistsForDifferentRuntimeSchemas() {
        for (String schema : List.of("tenant_alpha", "tenant_beta")) {
            SqlConversionResult result = converter.convert("""
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = '%s' AND table_name = #{tableName}
                    """.formatted(schema));

            assertThat(result.changed()).isTrue();
            assertThat(result.manualReviewRequired()).isFalse();
            assertThat(result.convertedSql())
                    .isEqualTo("SELECT 1 FROM ALL_TABLES WHERE TABLE_NAME = UPPER(#{tableName})"
                            + " AND OWNER = UPPER('" + schema + "')");
            assertThat(result.appliedRules())
                    .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_TABLES_RULE);
        }
    }

    @Test
    void convertsMysqlInformationSchemaTableExistsForCurrentRuntimeSchema() {
        SqlConversionResult result = converter.convert("""
                select 1 from information_schema.tables
                where table_schema=(select database()) and table_name = #{tableName}
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT 1 FROM ALL_TABLES WHERE TABLE_NAME = UPPER(#{tableName})"
                        + " AND OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_TABLES_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaTablesListQueryToAllTables() {
        SqlConversionResult result = converter.convert("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name LIKE '${tablePrefix}%'
                group by table_name limit 500
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT TABLE_NAME FROM ALL_TABLES WHERE TABLE_NAME LIKE UPPER('${tablePrefix}%') AND OWNER = SYS_CONTEXT('USERENV','CURRENT_SCHEMA') GROUP BY TABLE_NAME FETCH FIRST 500 ROWS ONLY");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_TABLES_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaBaseTableAndViewLists() {
        SqlConversionResult baseTableResult = converter.convert("""
                SELECT TABLE_NAME
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = (select database())
                  AND TABLE_TYPE = 'BASE TABLE'
                """);
        SqlConversionResult viewResult = converter.convert("""
                SELECT TABLE_NAME
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = (select database())
                  AND TABLE_TYPE = 'VIEW'
                  AND TABLE_NAME LIKE 'vw_datacenter_%'
                """);

        assertThat(baseTableResult.changed()).isTrue();
        assertThat(baseTableResult.manualReviewRequired()).isFalse();
        assertThat(baseTableResult.convertedSql())
                .isEqualTo("SELECT TABLE_NAME FROM ALL_TABLES"
                        + " WHERE OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')");
        assertThat(viewResult.changed()).isTrue();
        assertThat(viewResult.manualReviewRequired()).isFalse();
        assertThat(viewResult.convertedSql())
                .isEqualTo("SELECT VIEW_NAME AS TABLE_NAME FROM ALL_VIEWS"
                        + " WHERE OWNER = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')"
                        + " AND VIEW_NAME LIKE UPPER('vw_datacenter_%')");
        assertThat(baseTableResult.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_TABLES_RULE);
        assertThat(viewResult.appliedRules())
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
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INFORMATION_SCHEMA_TABLES_RULE);
    }

    @Test
    void convertsMysqlInformationSchemaTablesQueryWithSchemaToAllTables() {
        SqlConversionResult result = converter.convert("""
                SELECT COUNT(1) table_count FROM information_schema.tables
                WHERE TABLE_SCHEMA = 'sample-contract' AND TABLE_NAME = ?
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .isEqualTo("SELECT COUNT(*) AS table_count FROM ALL_TABLES WHERE TABLE_NAME = UPPER(?) AND OWNER = UPPER('sample-contract')");
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
                .isEqualTo("select * from schedule where LOCATE(CAST(#{precinctId} AS VARCHAR(64)), concat(',', s3.precinctID, ',')) > 0");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_LOCATE_NUMERIC_NEEDLE_RULE);
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
    void removesUnusedUserVariableInitializerFromDerivedSelect() {
        SqlConversionResult result = converter.convert("""
                select *
                from (
                    select
                        @g := 1
                        ,row_number() over(order by id) n
                        ,id
                    from user
                ) t
                """);

        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql())
                .doesNotContain("@g")
                .contains("select\n        row_number() over(order by id) n")
                .contains("        ,id\n    from user");
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_UNUSED_USER_VARIABLE_SELECT_ITEM_RULE);
    }

    @Test
    void keepsReferencedUserVariableInitializerForManualReview() {
        SqlConversionResult result = converter.convert("""
                select *
                from (
                    select @g := 1, @g := @g + 1 as n, id
                    from user
                ) t
                """);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("MySQL user variables");
        assertThat(result.convertedSql()).contains("@g := 1");
    }

    @Test
    void keepsTopLevelUserVariableInitializerForManualReview() {
        SqlConversionResult result = converter.convert("select @g := 1, id from user");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("MySQL user variables");
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
                select DATEDIFF(MONTH, min(str_to_date(AccountBook, '%Y%m')), SYSDATE)
                from Charge_CustomerChargeDetail
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_PERIOD_DIFF_YEARMONTH_RULE);
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
    void convertsPeriodDiffBetweenDateAndMyBatisYearMonthParameter() {
        SqlConversionResult result = converter.convert("""
                select *
                from sample_task t
                where PERIOD_DIFF(DATE_FORMAT(t.finished_at, '%Y%m'), #{criteria.yearMonth}) = 0
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("(YEAR(t.finished_at) * 12 + MONTH(t.finished_at))")
                .contains("CAST(#{criteria.yearMonth} AS DECIMAL(38, 0))")
                .contains("THEN 2000")
                .contains("THEN 1900")
                .doesNotContainIgnoringCase("PERIOD_DIFF")
                .doesNotContainIgnoringCase("DATE_FORMAT");
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_PERIOD_DIFF_YEARMONTH_RULE);
    }

    @Test
    void convertsPeriodDiffWithFourDigitYearMonthLiteral() {
        SqlConversionResult result = converter.convert("select PERIOD_DIFF(9912, '0001') from dual");

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql())
                .contains("CAST(9912 AS DECIMAL(38, 0))")
                .contains("CAST('0001' AS DECIMAL(38, 0))")
                .contains("THEN 2000")
                .contains("THEN 1900")
                .doesNotContainIgnoringCase("PERIOD_DIFF");
    }

    @Test
    void keepsComplexPeriodDiffValueExpressionForManualReview() {
        String sql = "select PERIOD_DIFF(DATE_FORMAT(CURDATE(), '%Y%m'), CONCAT(year_code, month_code)) from dual";

        SqlConversionResult result = converter.convert(sql);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.convertedSql()).isEqualTo(sql);
        assertThat(result.reason()).contains("PERIOD_DIFF");
    }

    @Test
    void leavesMysqlDateDiffTwoArgumentFormNative() {
        SqlConversionResult result = converter.convert("""
                select DATEDIFF(DATEADD(MONTH, 1, startDate), CURDATE())
                from ns_contract_info
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
                select DATEDIFF(DATEADD(MONTH,
                    DATEDIFF(MONTH, startDate, CURDATE()),
                    startDate), CURDATE())
                from ns_contract_info
                """);
        assertThat(result.appliedRules()).containsExactly(MySqlToDmSqlConverter.MYSQL_PERIOD_DIFF_YEARMONTH_RULE);
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
    void convertsNotIsNullWhileConvertingAggregations() {
        SqlConversionResult result = converter.convert("""
                select count(sendState = 'SEND_SUCCESS' or null) smsSuccessCount,
                       !ISNULL(c.refMeterReadId) charged,
                       IF((isnull(xm.billType) = 1) || (length(xm.billType) = 0), jt.billType, xm.billType) billType
                from ns_sms_details
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select COUNT(CASE WHEN sendState = 'SEND_SUCCESS' THEN 1 END) smsSuccessCount,
                       CASE WHEN c.refMeterReadId IS NOT NULL THEN 1 ELSE 0 END charged,
                       IF((isnull(xm.billType) = 1) OR (length(xm.billType) = 0), jt.billType, xm.billType) billType
                from ns_sms_details
                """);
        assertThat(result.appliedRules()).containsExactly(
                MySqlToDmSqlConverter.MYSQL_COUNT_CONDITION_OR_NULL_RULE,
                MySqlToDmSqlConverter.MYSQL_NOT_ISNULL_RULE,
                MySqlToDmSqlConverter.MYSQL_BOOLEAN_OPERATOR_RULE
        );
    }

    @Test
    void convertsBooleanNullPredicatesProjectedAsFlags() {
        SqlConversionResult result = converter.convert("""
                select re.*,
                       (coll.id is not null) as isCollection,
                       (re.deleted_at IS NULL) AS active
                from ns_finance_receivables re
                left join ns_finance_collection coll on coll.documentNumber = re.documentNumber
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.convertedSql()).isEqualTo("""
                select re.*,
                       CASE WHEN coll.id IS NOT NULL THEN 1 ELSE 0 END as isCollection,
                       CASE WHEN re.deleted_at IS NULL THEN 1 ELSE 0 END AS active
                from ns_finance_receivables re
                left join ns_finance_collection coll on coll.documentNumber = re.documentNumber
                """);
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_BOOLEAN_NULL_PROJECTION_RULE);
    }

    @Test
    void leavesBooleanNullPredicatesOutsideSelectProjectionUnchanged() {
        String sql = """
                select id
                from ns_finance_receivables
                where (deleted_at is null)
                  and note = '(coll.id is not null) as isCollection'
                """;

        SqlConversionResult result = converter.convert(sql);

        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(sql);
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
    void leavesBooleanLiteralComparisonsNative() {
        SqlConversionResult result = converter.convert("""
                delete from owner_house_result
                where house_id = #{houseId} and is_current_record = true
                  and deleteFlag != false
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
    void convertsMysqlIfFunctionDivisionToDecimalArithmetic() {
        SqlConversionResult result = converter.convert("""
                select sum(amount) / if(count(DISTINCT log.id) = 0, 1, count(DISTINCT log.id)) as avgAmount
                from payment_log log
                """);

        assertThat(result.changed()).isTrue();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo("""
                select CAST(sum(amount) AS DECIMAL(38,10)) / NULLIF(CAST(if(count(DISTINCT log.id) = 0, 1, count(DISTINCT log.id)) AS DECIMAL(38,10)), 0) as avgAmount
                from payment_log log
                """);
        assertThat(result.appliedRules())
                .containsExactly(MySqlToDmSqlConverter.MYSQL_INTEGER_DIVISION_TO_DECIMAL_RULE);
    }

    @Test
    void leavesMysqlIfFunctionWithImplicitAliasNative() {
        SqlConversionResult result = converter.convert("""
                select if(e.isMustCheck = 1,'是','否')isMustCheckValue,d.equipName
                from ns_equip_equip e
                """);

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
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
    void leavesSelectLimitWithPlaceholdersNative() {
        SqlConversionResult result = converter.convert("select * from user limit ?, ?");

        assertThat(result.changed()).isFalse();
        assertThat(result.manualReviewRequired()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
    }
}
