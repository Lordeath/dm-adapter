# MySQL 与达梦语法差异验证总结

验证环境：

- JDBC：`jdbc:dm://192.168.1.53:5236`
- 用户：`SYSDBA`
- 日期：`2026-06-30`
- 原则：MySQL 写法必须在该达梦环境实际执行失败，才列为需要改写的差异。

## 输出文件

- `G:\project\tmp\mysql.sql`：MySQL 写法示例，编号 `D01` 到 `D22`。
- `G:\project\tmp\dm.sql`：达梦等价写法示例，编号与 `mysql.sql` 一一对应。

## 已验证但不需要改写的写法

以下 MySQL 写法在 53 达梦环境可以直接执行，本次不列入差异 SQL：

| 写法 | 结论 |
| --- | --- |
| `CONCAT(name, '-', status)` | 可执行 |
| `CONCAT_WS('-', name, status)` | 可执行 |
| `SELECT ... LIMIT 1` | 可执行 |
| `SELECT ... LIMIT 1, 1` | 可执行 |
| 反引号标识符，例如 `` `id` `` | 可执行 |
| `IFNULL(expr, fallback)` | 可执行 |
| `IF(condition, a, b)` | 可执行 |
| `ISNULL(expr)` | 可执行 |
| `!(condition)` | 可执行 |
| `flag = TRUE` | 可执行 |
| `FIND_IN_SET('2', csv)` | 可执行 |
| `DATE_FORMAT(date_col, '%Y-%m-%d')` | 可执行 |
| `STR_TO_DATE('2026-06-30', '%Y-%m-%d')` | 可执行 |
| `CURDATE()` | 可执行 |
| `TIMESTAMPDIFF(...)` | 可执行 |
| `SUBSTRING_INDEX('a,b,c', ',', 2)` | 可执行 |
| `JSON_EXTRACT(...)` / `JSON_OBJECT(...)` | 可执行 |
| `UPDATE t JOIN s ON ... SET ...` | 可执行 |
| `DELETE t FROM t JOIN s ON ...` | 可执行 |
| `TRUNCATE TABLE t` | 可执行 |
| `CREATE TEMPORARY TABLE ... AS SELECT ...` | 可执行 |
| `AUTO_INCREMENT` 建表列属性 | 可执行 |
| `ALTER TABLE t AUTO_INCREMENT = n` | 可执行 |

## 需要改写的差异

| 编号 | MySQL 写法 | 达梦改写方向 | 达梦失败原因摘要 |
| --- | --- | --- | --- |
| D01 | 双引号字符串：`status = "ACTIVE"` | 单引号字符串：`status = 'ACTIVE'` | 达梦将双引号解析为标识符，报无效列名 |
| D02 | 单引号别名：`COUNT(*) 'totalCount'` | `AS "totalCount"` 或普通别名 | 单引号别名语法错误 |
| D03 | `GROUP_CONCAT(... ORDER BY ... SEPARATOR ',')` | `LISTAGG(..., ',') WITHIN GROUP (...)` | `GROUP_CONCAT` 聚合不可解析或语法不兼容 |
| D04 | `name REGEXP '^a'` | `REGEXP_LIKE(name, '^a')` | `REGEXP` 操作符语法错误 |
| D05 | `DATE_ADD(col, INTERVAL 1 DAY)` | `DATEADD(DAY, 1, col)` | `INTERVAL` 参数语法错误 |
| D06 | `DATE_SUB(col, INTERVAL 1 DAY)` | `DATEADD(DAY, -1, col)` | `INTERVAL` 参数语法错误 |
| D07 | `CONVERT(expr, DECIMAL(10,2))` | `CAST(expr AS DECIMAL(10,2))` | `CONVERT` 目标类型语法错误 |
| D08 | `CAST(expr AS UNSIGNED)` | `CAST(expr AS BIGINT)` 或明确精度数值类型 | `UNSIGNED` 不是达梦有效数据类型 |
| D09 | `@rn := @rn + 1` 用户变量 | `ROW_NUMBER() OVER (...)` | `@` 用户变量语法错误 |
| D10 | `ON DUPLICATE KEY UPDATE` | `MERGE INTO ... WHEN MATCHED ...` | `ON DUPLICATE KEY` 语法错误 |
| D11 | `REPLACE INTO` | `MERGE INTO` 或先删后插 | `REPLACE INTO` 语法错误 |
| D12 | `INSERT IGNORE` | `INSERT ... SELECT ... WHERE NOT EXISTS` 或只插入 `MERGE` | `INSERT IGNORE` 语法错误 |
| D13 | `UPDATE ... ORDER BY ... LIMIT` | 用子查询先选目标行再 `UPDATE` | `UPDATE` 后的 `ORDER BY LIMIT` 语法错误 |
| D14 | `DESCRIBE table` | 查询 `USER_TAB_COLUMNS` | `DESCRIBE` 语法错误 |
| D15 | `SHOW TABLES` | 查询 `USER_TABLES` | `SHOW TABLES` 语法错误 |
| D16 | `information_schema.tables` + `DATABASE()` | `ALL_TABLES` + `SYS_CONTEXT` | 无 `information_schema` schema，`DATABASE()` 不可解析 |
| D17 | `DATABASE()` / `SCHEMA()` | `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')` | 函数不可解析或关键字语法错误 |
| D18 | `PERIOD_DIFF(YYYYMM, YYYYMM)` | 年月拆分后做月份差 | 函数不可解析 |
| D19 | `MAKEDATE(year, day)` | `DATEADD(DAY, day - 1, TO_DATE(...))` | 函数不可解析 |
| D20 | `INSERT ... VALUE (...)` | `INSERT ... VALUES (...)` | 单数 `VALUE` 语法错误 |
| D21 | 建表内 `COMMENT`、`KEY ... USING BTREE`、`ENGINE`、`CHARSET`、表注释 | 建表、`COMMENT ON`、`CREATE INDEX` 分开写 | 建表选项语法错误 |
| D22 | `ON UPDATE CURRENT_TIMESTAMP` | 触发器或应用 SQL 维护更新时间 | `ON UPDATE` 列属性无效 |

## 分类建议

- 工具可自动转换：D01-D08、D13-D20、D21 中的注释/索引/表选项拆分。
- 需要结合唯一键或业务语义确认：D10、D11、D12。可用 `MERGE`，但更新列、缺省列和触发器行为要按业务确认。
- 建议人工确认：D22。触发器等价于自动更新时间，但如果原业务显式传更新时间，应避免重复覆盖。
- 不应再默认转换：`CONCAT`、`LIMIT`、反引号、`FIND_IN_SET`、`DATE_FORMAT`、`STR_TO_DATE`、`UPDATE JOIN`、`DELETE JOIN`，因为本次环境验证均可执行。
