# MySQL 与达梦语法差异验证总结

## 需要改写的差异

| 编号  | MySQL 写法                                                   | MySQL编写规范       | 达梦改写方向                                                        | 达梦失败原因摘要                                        |
| --- | ---------------------------------------------------------- |:--------------- | ------------------------------------------------------------- | ----------------------------------------------- |
| D01 | 双引号字符串：`status = "ACTIVE"`                                 | 不准用双引号""，必须用单引号 | 单引号字符串：`status = 'ACTIVE'`                                    | 达梦将双引号解析为标识符，报无效列名                              |
| D02 | 单引号别名：`COUNT(*) 'totalCount'`                              | 不准用单引号，必须用双引号   | `AS "totalCount"` 或 as totalcount | 单引号别名语法错误                                       |
| D03 | `GROUP_CONCAT(... ORDER BY ... SEPARATOR ',')`             | 可以用             | `LISTAGG(..., ',') WITHIN GROUP (...)`                        | `GROUP_CONCAT` 聚合不可解析或语法不兼容                     |
| D04 | `name REGEXP '^a'`                                         | 不准用             | `REGEXP_LIKE(name, '^a')`                                     | `REGEXP` 操作符语法错误                                |
| D05 | `DATE_ADD(col, INTERVAL 1 DAY)`                            | <br />          | `DATEADD(DAY, 1, col)`                                        | `INTERVAL` 参数语法错误                               |
| D06 | `DATE_SUB(col, INTERVAL 1 DAY)`                            | 不准用             | `DATEADD(DAY, -1, col)`                                       | `INTERVAL` 参数语法错误                               |
| D07 | `CONVERT(expr, DECIMAL(10,2))`                             | <br />          | `CAST(expr AS DECIMAL(10,2))`                                 | `CONVERT` 目标类型语法错误                              |
| D08 | `CAST(expr AS UNSIGNED)`                                   | <br />          | `CAST(expr AS BIGINT)` 或明确精度数值类型                              | `UNSIGNED` 不是达梦有效数据类型                           |
| D09 | `@rn := @rn + 1` 用户变量                                      | 不准用             | `ROW_NUMBER() OVER (...)`                                     | `@` 用户变量语法错误                                    |
| D10 | `ON DUPLICATE KEY UPDATE`                                  | 不准用             | `MERGE INTO ... WHEN MATCHED ...`                             | `ON DUPLICATE KEY` 语法错误                         |
| D11 | `REPLACE INTO`                                             | 不准用             | `MERGE INTO` 或先删后插                                            | `REPLACE INTO` 语法错误                             |
| D12 | `INSERT IGNORE`                                            | 不准用             | `INSERT ... SELECT ... WHERE NOT EXISTS` 或只插入 `MERGE`         | `INSERT IGNORE` 语法错误                            |
| D13 | `UPDATE ... ORDER BY ... LIMIT`                            | 不准用             | 用子查询先选目标行再 `UPDATE`                                           | `UPDATE` 后的 `ORDER BY LIMIT` 语法错误               |
| D14 | `DESCRIBE table`                                           | 不准用             | 查询 `USER_TAB_COLUMNS`                                         | `DESCRIBE` 语法错误                                 |
| D15 | `SHOW TABLES`                                              | 不准用             | 查询 `USER_TABLES`                                              | `SHOW TABLES` 语法错误                              |
| D16 | `information_schema.tables/columns` + `DATABASE()`         | <br />          | 普通 SQL 使用 `ALL_TABLES/ALL_TAB_COLUMNS` + `SYS_CONTEXT`；存储过程以 `CURRENT_SCHID` 定位当前模式，简单列存在性检查使用 `SYS.SYSOBJECTS` + `SYS.SYSCOLUMNS` 窄查询，复杂元数据检查使用 `ALL_*` + `SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)` | 无 `information_schema` schema，`DATABASE()` 不可解析；简单存在性检查应避免对字典列使用 `UPPER()` 全量扫描 |
| D17 | `DATABASE()` / `SCHEMA()`                                  | <br />          | 普通 SQL 使用 `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')`；存储过程使用 `SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)` | 函数不可解析；存储过程内 `SYS_CONTEXT` 可能返回过程所有者 schema |
| D18 | `PERIOD_DIFF(YYYYMM, YYYYMM)`                              | 不准用             | 年月拆分后做月份差                                                     | 函数不可解析                                          |
| D19 | `MAKEDATE(year, day)`                                      | 不准用             | `DATEADD(DAY, day - 1, TO_DATE(...))`                         | 函数不可解析                                          |
| D20 | `INSERT ... VALUE (...)`                                   | 不准用             | `INSERT ... VALUES (...)`                                     | 单数 `VALUE` 语法错误                                 |
| D21 | 建表内 `COMMENT`、`KEY ... USING BTREE`、`ENGINE`、`CHARSET`、表注释 | 不准用             | 建表、`COMMENT ON`、`CREATE INDEX` 分开写                            | 建表选项语法错误                                        |
| D22 | `ON UPDATE CURRENT_TIMESTAMP`                              | <br />          | 触发器或应用 SQL 维护更新时间                                             | `ON UPDATE` 列属性无效                               |
| D23 | AES\_ENCRYPT                                               | 可以用             | 写了对应的函数，可以用                                                   | 不支持                                             |
| D24 | AES\_DECRYPT                                               | 可以用             | <br />                                                        | 不支持                                             |
| d25 | force index                                                |                 | SELECT /\*+ USE\_INDEX(c idx\_actualAccountBook) \*/          | 需要修改                                            |
| d26 | DATE_FORMAT('20260704','%Y%m%d') + interval 1 day          | 不准用             | <br />                                                        | <br />                                          |
| D27 | 多表更新：`UPDATE a JOIN b ... SET a.xxx = ..., b.xxx = ...` | 不准用             | 拆成按同一关联条件分别更新 `a`、`b` 的多条 `UPDATE`，或改业务代码按事务分步更新            | 达梦不支持一次 `UPDATE JOIN` 同时更新多张表字段                       |
| D28 | 整数算术表达式：`SELECT 1 / 2 * 100`                         | 不准依赖隐式整数/小数转换   | 比例、百分比计算显式转小数，如 `CAST(1 AS DECIMAL(18,4)) / 2 * 100`，并按业务确认是否需要百分比放大 | 现场观察达梦结果为 `0`，MySQL 结果为 `0.5000`，可能因整数除法截断或精度提升规则不同导致特殊场景结果偏差 |

## 建议

- 不应再默认转换：`CONCAT`、`LIMIT`、反引号、`FIND_IN_SET`、`DATE_FORMAT`、`STR_TO_DATE`、单目标 `UPDATE JOIN`、`DELETE JOIN`，now(),IFNULL,if因为本次环境验证均可执行。
- `UPDATE JOIN` 可保留仅限更新单张目标表的场景；如果 `SET` 中同时更新 `a.xxx`、`b.xxx` 等多张表字段，需要拆成多条更新语句，并由业务代码保证事务和行数语义一致。
- 比例、百分比、金额费率等计算不要写成纯整数字面量或整数字段直接相除，至少将一个操作数显式 `CAST` 为 `DECIMAL`，避免达梦与 MySQL 在整数除法、精度提升上的差异影响结果。
