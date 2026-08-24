-- NLC-2843【AIpaas】菜单管理增加“菜单入口”字段
DROP PROCEDURE IF EXISTS add_col_ns_core_menu_menu_entrance;
DELIMITER $$
CREATE PROCEDURE add_col_ns_core_menu_menu_entrance()
BEGIN
IF NOT EXISTS (
SELECT COLUMN_NAME FROM information_schema. COLUMNS
WHERE table_schema = (select database()) AND table_name = 'ns_core_menu' AND column_name = 'menu_entrance'
) THEN
ALTER TABLE ns_core_menu add COLUMN  `menu_entrance` varchar(10) DEFAULT '0' COMMENT '菜单入口';
END IF;
END$$
DELIMITER ;
CALL add_col_ns_core_menu_menu_entrance();
DROP PROCEDURE IF EXISTS add_col_ns_core_menu_menu_entrance;


-- 修复 addOrUpdate_button 的按钮判重、更新条件与 uk_enterprise_resbtn 不一致的问题。
-- uk_enterprise_resbtn 的业务键为：
-- ENTERPRISE_ID + JE_CORE_RESOURCEBUTTON_ID + RESOURCEBUTTON_FUNCINFO_ID。
-- 1. 判重不再包含 ORGANIZATION_ID，避免相同业务键位于非最小组织时误插入并触发 1062。
-- 2. UPDATE 补齐 RESOURCEBUTTON_FUNCINFO_ID 条件，避免同按钮挂在多个功能点时被批量改成同一功能点。
-- 3. 删除无用临时表和会话级变量，保留老库无唯一键时的串行幂等能力。
DROP PROCEDURE IF EXISTS `addOrUpdate_button`;
DELIMITER $$
CREATE PROCEDURE `addOrUpdate_button`(
    IN PPPP_JE_CORE_RESOURCEBUTTON_ID VARCHAR(9999),
    IN PPPP_RESOURCEBUTTON_NAME VARCHAR(9999),
    IN PPPP_RESOURCEBUTTON_FUNCINFO_ID VARCHAR(9999),
    IN PPPP_RESOURCEBUTTON_ICONCLS VARCHAR(9999),
    IN PPPP_RESOURCEBUTTON_CODE VARCHAR(9999),
    IN PPPP_RESOURCEBUTTON_TYPE VARCHAR(9999),
    IN PPPP_RESOURCEBUTTON_BIGICONCLS VARCHAR(9999),
    IN PPPP_RESOURCEBUTTON_DISABLED VARCHAR(9999),
    IN PPPP_SY_ORDERINDEX VARCHAR(9999)
)
label_exit: BEGIN
    DECLARE v_button_id VARCHAR(128) CHARACTER SET utf8 COLLATE utf8_general_ci;
    DECLARE v_button_name VARCHAR(255) CHARACTER SET utf8 COLLATE utf8_general_ci;
    DECLARE v_func_id VARCHAR(150) CHARACTER SET utf8 COLLATE utf8_general_ci;
    DECLARE v_iconcls VARCHAR(255) CHARACTER SET utf8 COLLATE utf8_general_ci;
    DECLARE v_button_code VARCHAR(255) CHARACTER SET utf8 COLLATE utf8_general_ci;
    DECLARE v_button_type VARCHAR(255) CHARACTER SET utf8 COLLATE utf8_general_ci;
    DECLARE v_bigiconcls VARCHAR(255) CHARACTER SET utf8 COLLATE utf8_general_ci;
    DECLARE v_disabled VARCHAR(4) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT '0';
    DECLARE v_orderindex BIGINT DEFAULT NULL;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        DROP TEMPORARY TABLE IF EXISTS tmp_aoub_enterprise;
        RESIGNAL;
    END;

    IF PPPP_JE_CORE_RESOURCEBUTTON_ID IS NULL
       OR TRIM(PPPP_JE_CORE_RESOURCEBUTTON_ID) = '' THEN
        LEAVE label_exit;
    END IF;
    IF PPPP_RESOURCEBUTTON_NAME IS NULL
       OR TRIM(PPPP_RESOURCEBUTTON_NAME) = '' THEN
        LEAVE label_exit;
    END IF;
    IF PPPP_RESOURCEBUTTON_FUNCINFO_ID IS NULL
       OR TRIM(PPPP_RESOURCEBUTTON_FUNCINFO_ID) = '' THEN
        LEAVE label_exit;
    END IF;
    IF PPPP_RESOURCEBUTTON_CODE IS NULL
       OR TRIM(PPPP_RESOURCEBUTTON_CODE) = '' THEN
        LEAVE label_exit;
    END IF;
    IF PPPP_RESOURCEBUTTON_TYPE IS NULL
       OR TRIM(PPPP_RESOURCEBUTTON_TYPE) = '' THEN
        LEAVE label_exit;
    END IF;

    SET v_button_id = CAST(PPPP_JE_CORE_RESOURCEBUTTON_ID AS CHAR CHARACTER SET utf8) COLLATE utf8_general_ci;
    SET v_button_name = CAST(PPPP_RESOURCEBUTTON_NAME AS CHAR CHARACTER SET utf8) COLLATE utf8_general_ci;
    SET v_func_id = CAST(PPPP_RESOURCEBUTTON_FUNCINFO_ID AS CHAR CHARACTER SET utf8) COLLATE utf8_general_ci;
    SET v_iconcls = CAST(PPPP_RESOURCEBUTTON_ICONCLS AS CHAR CHARACTER SET utf8) COLLATE utf8_general_ci;
    SET v_button_code = CAST(PPPP_RESOURCEBUTTON_CODE AS CHAR CHARACTER SET utf8) COLLATE utf8_general_ci;
    SET v_button_type = CAST(PPPP_RESOURCEBUTTON_TYPE AS CHAR CHARACTER SET utf8) COLLATE utf8_general_ci;
    SET v_bigiconcls = CAST(PPPP_RESOURCEBUTTON_BIGICONCLS AS CHAR CHARACTER SET utf8) COLLATE utf8_general_ci;
    SET v_disabled = CAST(
        COALESCE(NULLIF(PPPP_RESOURCEBUTTON_DISABLED, ''), '0')
        AS CHAR CHARACTER SET utf8
    ) COLLATE utf8_general_ci;

    IF PPPP_SY_ORDERINDEX IS NOT NULL
       AND TRIM(PPPP_SY_ORDERINDEX) <> '' THEN
        SET v_orderindex = CAST(PPPP_SY_ORDERINDEX AS SIGNED);
    END IF;

    DROP TEMPORARY TABLE IF EXISTS tmp_aoub_enterprise;
    CREATE TEMPORARY TABLE tmp_aoub_enterprise (
        enterprise_id BIGINT NOT NULL,
        organization_id BIGINT NOT NULL,
        roleid VARCHAR(50) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL,
        orderindex BIGINT NOT NULL,
        PRIMARY KEY (enterprise_id)
    ) ENGINE = InnoDB;

    INSERT INTO tmp_aoub_enterprise (
        enterprise_id,
        organization_id,
        roleid,
        orderindex
    )
    SELECT eo.enterprise_id,
           eo.organization_id,
           first_role.roleid,
           CASE
               WHEN v_orderindex IS NOT NULL THEN v_orderindex
               ELSE COALESCE((
                   SELECT MAX(rb.SY_ORDERINDEX) + 1
                   FROM ns_core_resourcebutton rb
                   WHERE rb.ENTERPRISE_ID = eo.enterprise_id
                     AND rb.ORGANIZATION_ID = eo.organization_id
                     AND rb.RESOURCEBUTTON_FUNCINFO_ID = v_func_id
                     AND rb.JE_CORE_RESOURCEBUTTON_ID <> v_button_id
               ), 1)
           END AS orderindex
    FROM (
        SELECT o.ENTERPRISE_ID AS enterprise_id,
               MIN(o.ORGANIZATION_ID) AS organization_id
        FROM ns_system_organization o
        GROUP BY o.ENTERPRISE_ID
    ) eo
    LEFT JOIN (
        SELECT r.ENTERPRISE_ID AS enterprise_id,
               r.ROLEID AS roleid
        FROM ns_core_role r
        INNER JOIN (
            SELECT ENTERPRISE_ID,
                   MIN(ID) AS id
            FROM ns_core_role
            GROUP BY ENTERPRISE_ID
        ) first_role_id
          ON first_role_id.ENTERPRISE_ID = r.ENTERPRISE_ID
         AND first_role_id.id = r.ID
    ) first_role
      ON first_role.enterprise_id = eo.enterprise_id;

    -- 只按 uk_enterprise_resbtn 的完整三列业务键判断是否缺失；
    -- 新按钮仍写入企业最小组织，已有按钮保留原 ORGANIZATION_ID。
    INSERT INTO ns_core_resourcebutton (
        ENTERPRISE_ID,
        ORGANIZATION_ID,
        JE_CORE_RESOURCEBUTTON_ID,
        RESOURCEBUTTON_FUNCINFO_ID,
        RESOURCEBUTTON_BIGICONCLS,
        RESOURCEBUTTON_CODE,
        RESOURCEBUTTON_CONFIGINFO,
        RESOURCEBUTTON_DISABLED,
        RESOURCEBUTTON_FIREEVENT,
        RESOURCEBUTTON_FORMBIND,
        RESOURCEBUTTON_HIDDEN,
        RESOURCEBUTTON_ICONCLS,
        RESOURCEBUTTON_INTERPRETER,
        RESOURCEBUTTON_JSLISTENER,
        RESOURCEBUTTON_MSG,
        RESOURCEBUTTON_NAME,
        RESOURCEBUTTON_NAME_EN,
        RESOURCEBUTTON_NEWFUNCID,
        RESOURCEBUTTON_NOREADONLY,
        RESOURCEBUTTON_SYSMODE,
        RESOURCEBUTTON_TYPE,
        RESOURCEBUTTON_WFENDEDENABLE,
        RESOURCEBUTTON_XTYPE,
        SY_AUDFLAG,
        SY_CREATEORG,
        SY_CREATEORGNAME,
        SY_CREATETIME,
        SY_CREATEUSER,
        SY_CREATEUSERNAME,
        SY_FLAG,
        SY_FORMUPLOADFILES,
        SY_MODIFYORG,
        SY_MODIFYORGNAME,
        SY_MODIFYTIME,
        SY_MODIFYUSER,
        SY_MODIFYUSERNAME,
        SY_ORDERINDEX,
        SY_PDID,
        SY_PIID,
        SY_PYJZ,
        SY_PYQC,
        SY_STATUS,
        sys_time
    )
    SELECT eo.enterprise_id,
           eo.organization_id,
           v_button_id,
           v_func_id,
           v_bigiconcls,
           v_button_code,
           '',
           v_disabled,
           '',
           '',
           '',
           v_iconcls,
           '',
           '',
           '',
           v_button_name,
           '',
           '',
           '',
           '',
           v_button_type,
           '',
           '',
           '',
           '',
           '',
           '',
           '',
           '',
           '',
           '',
           '脚本',
           '脚本',
           NOW(),
           '脚本',
           '脚本',
           eo.orderindex,
           '',
           '',
           '',
           '',
           '1',
           NOW()
    FROM tmp_aoub_enterprise eo
    WHERE NOT EXISTS (
        SELECT 1
        FROM ns_core_resourcebutton rb
        WHERE rb.ENTERPRISE_ID = eo.enterprise_id
          AND rb.JE_CORE_RESOURCEBUTTON_ID = v_button_id
          AND rb.RESOURCEBUTTON_FUNCINFO_ID = v_func_id
    )
    ON DUPLICATE KEY UPDATE
        ID = ns_core_resourcebutton.ID;

    -- INSERT 后再做精确 UPDATE；并发会话先插入时也能更新到目标行。
    -- 不能只按按钮 ID 更新，否则同一按钮挂在多个功能点时会触发 1062。
    UPDATE ns_core_resourcebutton rb
    INNER JOIN tmp_aoub_enterprise eo
      ON eo.enterprise_id = rb.ENTERPRISE_ID
    SET rb.RESOURCEBUTTON_BIGICONCLS = v_bigiconcls,
        rb.RESOURCEBUTTON_CODE = v_button_code,
        rb.RESOURCEBUTTON_DISABLED = v_disabled,
        rb.RESOURCEBUTTON_ICONCLS = v_iconcls,
        rb.RESOURCEBUTTON_NAME = v_button_name,
        rb.RESOURCEBUTTON_TYPE = v_button_type,
        rb.SY_ORDERINDEX = eo.orderindex,
        rb.sys_time = CURRENT_TIMESTAMP
    WHERE rb.JE_CORE_RESOURCEBUTTON_ID = v_button_id
      AND rb.RESOURCEBUTTON_FUNCINFO_ID = v_func_id;

    INSERT INTO ns_core_permission (
        ENTERPRISE_ID,
        ORGANIZATION_ID,
        PERID,
        FUNCID,
        PERMCODE,
        PERMPATH,
        PERMTYPE
    )
    SELECT eo.enterprise_id,
           eo.organization_id,
           CONCAT(v_button_id, '-perm'),
           v_func_id,
           v_button_id,
           '',
           'button'
    FROM tmp_aoub_enterprise eo
    WHERE NOT EXISTS (
        SELECT 1
        FROM ns_core_permission p
        WHERE p.ENTERPRISE_ID = eo.enterprise_id
          AND p.ORGANIZATION_ID = eo.organization_id
          AND p.PERID = CONCAT(v_button_id, '-perm')
    );

    INSERT INTO ns_core_role_perm (
        ENTERPRISE_ID,
        ORGANIZATION_ID,
        PERID,
        ROLEID
    )
    SELECT eo.enterprise_id,
           eo.organization_id,
           CONCAT(v_button_id, '-perm'),
           eo.roleid
    FROM tmp_aoub_enterprise eo
    WHERE eo.roleid IS NOT NULL
      AND NOT EXISTS (
          SELECT 1
          FROM ns_core_role_perm rp
          WHERE rp.ENTERPRISE_ID = eo.enterprise_id
            AND rp.ORGANIZATION_ID = eo.organization_id
            AND rp.PERID = CONCAT(v_button_id, '-perm')
            AND rp.ROLEID = eo.roleid
      );

    DROP TEMPORARY TABLE IF EXISTS tmp_aoub_enterprise;
END $$
DELIMITER ;
