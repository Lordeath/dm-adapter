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
    void marksDateFormatForManualReview() {
        SqlConversionResult result = converter.convert("select DATE_FORMAT(created_at, '%Y-%m-%d') from user");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.convertedSql()).isEqualTo(result.originalSql());
        assertThat(result.reason()).contains("DATE_FORMAT");
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
    void marksMySqlSpecificFunctionsForManualReview() {
        List<String> functionNames = List.of(
                "DATE_ADD",
                "DATE_SUB",
                "STR_TO_DATE",
                "UNIX_TIMESTAMP",
                "FROM_UNIXTIME",
                "TIMESTAMPDIFF",
                "CONCAT_WS",
                "JSON_EXTRACT",
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
    void marksOnDuplicateKeyUpdateForManualReview() {
        SqlConversionResult result = converter.convert("""
                insert into user(id, name) values(1, 'a')
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
    void marksRegexpForManualReview() {
        SqlConversionResult result = converter.convert("select * from user where code REGEXP '^[0-9]+$'");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("REGEXP");
    }

    @Test
    void marksAmbiguousLimitForManualReview() {
        SqlConversionResult result = converter.convert("select * from user limit ?, ?");

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(result.reason()).contains("LIMIT");
    }
}
