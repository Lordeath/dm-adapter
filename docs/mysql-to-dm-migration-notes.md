# MySQL 到达梦迁移注意点

资料来源：

- 达梦官方文档：MySQL 到 DM，https://eco.dameng.com/document/dm/zh-cn/start/mysql_dm
- 达梦官方 FAQ：MySQL 迁移 DM8，https://eco.dameng.com/document/dm/zh-cn/faq/faq-mysql-dm8-migrate.html
- 达梦官方参数说明：https://eco.dameng.com/document/dm/zh-cn/pm/physical-storage
- 达梦官方初始化参数 FAQ：https://eco.dameng.com/document/dm/zh-cn/faq/faq-dm-install
- 最后核对日期：2026-08-19

本文用于指导 dm-adapter 后续处理 Spring Boot + MyBatis 项目从 MySQL 迁移到达梦 8。修改代码前先按本文区分：应由 dm-adapter 自动转换的问题、业务 SQL 本身需要修正的问题、测试库缺对象的问题、必须人工提供参数或 `sql-rewrite.yml` 配置的问题。

## 实例和模式参数

达梦的 MySQL 兼容性不是只由 `COMPATIBLE_MODE` 决定。迁移前必须同时核对不可变的建库属性、通用兼容参数和业务功能参数；不能看到 `COMPATIBLE_MODE=4` 就认定实例已经与 MySQL 等价。

### 参数核对方法

先查询源 MySQL 的真实设置。大小写、严格模式、周编号、自增和加密算法都不应按“常见默认值”猜测：

```sql
SELECT @@character_set_server,
       @@collation_server,
       @@lower_case_table_names,
       @@sql_mode,
       @@default_week_format,
       @@auto_increment_increment,
       @@auto_increment_offset,
       @@block_encryption_mode;
```

再查询目标达梦的建库属性和兼容参数：

```sql
SELECT SF_GET_CASE_SENSITIVE_FLAG() AS case_sensitive,
       SF_GET_PAGE_SIZE() / 1024 AS page_size_kb,
       SF_GET_EXTENT_SIZE() AS extent_size,
       SF_GET_UNICODE_FLAG() AS charset,
       SF_GET_LENGTH_IN_CHAR() AS length_in_char;

SELECT PARA_NAME, PARA_VALUE, DEFAULT_VALUE,
       SESS_VALUE, FILE_VALUE, PARA_TYPE
FROM V$DM_INI
WHERE PARA_NAME IN (
    'BLANK_PAD_MODE', 'COMPATIBLE_MODE', 'ORDER_BY_NULLS_FLAG',
    'MY_STRICT_TABLES', 'ERROR_COMPATIBLE_FLAG', 'CALC_AS_DECIMAL',
    'COUNT_64BIT', 'BACKSLASH_ESCAPE', 'JSON_MODE',
    'USE_JSON_DATATYPE', 'ENABLE_BLOB_CMP_FLAG', 'MD5_TYPE',
    'NVARCHAR_LENGTH_IN_CHAR', 'SPACE_COMPARE_MODE',
    'STR_LIKE_IGNORE_MATCH_END_SPACE', 'AUTO_INCREMENT_INCREMENT',
    'AUTO_INCREMENT_OFFSET', 'NO_AUTO_VALUE_ON_ZERO', 'TIME_MODE',
    'DEFAULT_WEEK_FORMAT', 'BLOCK_ENCRYPTION_MODE',
    'NLS_TIMESTAMP_FORMAT', 'DATETIME_STRICT_FLAG'
)
ORDER BY PARA_NAME;
```

参数在当前版本不存在时，`V$DM_INI` 不会返回对应行，不能把“没有返回”当成默认值。升级达梦后应重新执行整组核对。

### 不可变的建库属性

| 参数 | MySQL 迁移重点 |
| --- | --- |
| `PAGE_SIZE` | 官方通用迁移示例建议 `32KB`。它不是 SQL 语法开关，但会限制普通列行长度；8KB 实例更容易在迁移宽表、长 `VARCHAR` 时出现“记录超长”。建库后不能修改。 |
| `CHARSET`/`UNICODE_FLAG` | 通常使用 `1`（UTF-8），但仍要与源库实际字符集和数据核对。建库后不能修改。 |
| `CASE_SENSITIVE` | 通常的 `_ci` MySQL 排序规则可考虑 `0`，但必须同时检查 `lower_case_table_names` 和字段级 collation。MySQL 能分别控制对象名和字段值，达梦在实例级统一控制，无法用一个值完整复刻混合规则。建库后不能修改。 |
| `LENGTH_IN_CHAR` | MySQL 的 `VARCHAR(n)`/`CHAR(n)` 中 `n` 是字符数。dm-adapter 生成表 DDL 时始终显式使用 `VARCHAR(n CHAR)`/`CHAR(n CHAR)`，因此输出不依赖该参数；已有表和其他工具生成的裸类型仍需核对。建库后不能修改。 |
| `BLANK_PAD_MODE` | 影响尾部空格比较和唯一约束。官方 MySQL 迁移示例使用 `0`，但 MySQL 8 的 `PAD SPACE`/`NO PAD` 行为还取决于字段 collation，必须按真实字段和索引验证。建库后不能修改。 |

### 通用 MySQL 兼容参数

| 参数 | 建议和风险 |
| --- | --- |
| `COMPATIBLE_MODE=4` | MySQL 兼容的总开关，静态参数，修改后重启生效。它只提供部分兼容，不会自动修正所有函数、隐式转换、对象 DDL 和存储过程。 |
| `ORDER_BY_NULLS_FLAG=2` | 使升序时 NULL 在前、降序时 NULL 在后，与 MySQL 排序方向一致。 |
| `MY_STRICT_TABLES` | 仅在模式 4 生效的位掩码：`1` 数据超长报错、`2` 字符转数值失败报错、`4` 除 0 报错。官方通用建议为 `1`；若源端启用了 `STRICT_TRANS_TABLES`、`STRICT_ALL_TABLES`、`ERROR_FOR_DIVISION_BY_ZERO`，应按真实 DML 决定是否组合为 `3`、`5` 或 `7`，不能为了“像 MySQL”固定写死。 |
| `CALC_AS_DECIMAL=1` | 使整数 `/` 按 DECIMAL 计算，更接近 MySQL 的小数除法。当前本地达梦在值为 `0` 时，`1/2` 实测返回 `0`，而 MySQL 8 返回 `0.5000`。MySQL 的 `DIV` 操作符仍可能需要 SQL 改写，不能只靠此参数。 |
| `COUNT_64BIT=1` | 让 `COUNT`/`SUM` 使用 BIGINT，符合 MySQL `COUNT()` 返回 64 位整数的常见预期。 |
| `ERROR_COMPATIBLE_FLAG=1` | 关闭派生表和 CTE 的同名列报错，可兼容分页插件等对 `SELECT a.*, b.*` 的外层包装。顶层查询本来就可返回同名列；视图仍不能定义重复列，MyBatis 按列名映射也仍有歧义。该开关应在确认调用方可按序号或唯一别名取值后启用。 |
| `BACKSLASH_ESCAPE` | 源 MySQL 未启用 `NO_BACKSLASH_ESCAPES` 时，字符串字面量默认识别反斜杠转义，达梦通常应评估设为 `1`；源端启用了该 SQL mode 时应保持 `0`。只影响 SQL 字面量，不影响 JDBC 绑定值。 |

### 按业务功能核对的参数

- 使用 MySQL JSON 类型或 `JSON_*` 函数时，核对 `JSON_MODE=2` 和 `USE_JSON_DATATYPE=1`；即使参数正确，JSON 路径、返回类型和索引仍需数据库实测。
- MySQL `MD5()` 返回十六进制字符串；达梦 `MD5_TYPE=0` 返回 `VARBINARY`，业务把 MD5 当字符串保存、比较或序列化时应评估 `MD5_TYPE=1`。这是静态参数，修改后重启生效。
- `ENABLE_BLOB_CMP_FLAG` 控制 TEXT/BLOB 的比较、排序和聚合。`1` 会把大字段转为字符串，`2` 在 MySQL 模式下会把字符串转为大字段；两者在超长内容、索引和性能上的结果不同，不能为了消除报错盲目全局切换。
- 使用 `AUTO_INCREMENT` 时，对照源端的 `auto_increment_increment`、`auto_increment_offset` 和 `NO_AUTO_VALUE_ON_ZERO` SQL mode，核对达梦的 `AUTO_INCREMENT_INCREMENT`、`AUTO_INCREMENT_OFFSET`、`NO_AUTO_VALUE_ON_ZERO`。达梦后一个参数为 `1` 表示插入 0 时生成下一个自增值，对应未开启 MySQL `NO_AUTO_VALUE_ON_ZERO` 的常见行为。
- 使用 `WEEK()`/`YEARWEEK()` 时，对照源端 `default_week_format` 核对达梦 `DEFAULT_WEEK_FORMAT`。显式传入 mode 的 SQL 仍以 SQL 参数为准。
- 使用 `AES_ENCRYPT`/`AES_DECRYPT` 时，对照源端 `block_encryption_mode` 核对达梦 `BLOCK_ENCRYPTION_MODE`；算法相同也不代表密钥派生、IV 和返回类型完全相同，必须用固定明文、密钥和密文做双端回归。
- `SPACE_COMPARE_MODE`、`STR_LIKE_IGNORE_MATCH_END_SPACE` 会进一步影响尾部空格的比较和 LIKE 行为，应与 `BLANK_PAD_MODE`、字段类型及源 collation 一起验证，不能孤立调整。
- 日期默认格式 `NLS_DATE_FORMAT`/`NLS_TIMESTAMP_FORMAT` 一次只能描述一种输入格式。它可以解决某一类隐式字符串转日期，但可能同时破坏另一类日期字面量；不能把它当成 MySQL 多格式日期容错总开关。

### 当前本地容器基线

2026-08-18 对本地 MySQL 8.0.34 和主达梦容器的只读核对结果如下：

- 源 MySQL 为 `utf8mb4_0900_ai_ci`、`lower_case_table_names=0`，SQL mode 包含 `STRICT_TRANS_TABLES` 和 `ERROR_FOR_DIVISION_BY_ZERO`，`default_week_format=0`，自增步长和偏移均为 `1`，块加密算法为 `aes-128-ecb`。
- 达梦建库属性为 `PAGE_SIZE=8KB`、`EXTENT_SIZE=16`、`CHARSET=1`、`CASE_SENSITIVE=0`、`LENGTH_IN_CHAR=0`、`BLANK_PAD_MODE=0`。其中字符集、大小写和尾空格已按常见 MySQL 场景调整；8KB 页与官方迁移示例的 32KB 不同，宽表要重点验证；dm-adapter 表 DDL 固定生成 `VARCHAR(n CHAR)`/`CHAR(n CHAR)`，不依赖该值。
- 已匹配的主要参数包括 `COMPATIBLE_MODE=4`、`ORDER_BY_NULLS_FLAG=2`、`JSON_MODE=2`、`USE_JSON_DATATYPE=1`、`COUNT_64BIT=1`、自增步长/偏移 `1/1`、`DEFAULT_WEEK_FORMAT=0` 和 `BLOCK_ENCRYPTION_MODE=AES-128-ECB`。
- 仍需决策或存在可见语义差异的参数包括 `ERROR_COMPATIBLE_FLAG=0`、`CALC_AS_DECIMAL=0`、`MD5_TYPE=0`、`BACKSLASH_ESCAPE=0`、`MY_STRICT_TABLES=1`。它们分别对应重复结果列、整数除法、MD5 返回类型、反斜杠字面量和严格模式，不能仅因其他参数已经调整就忽略。

动态会话级参数修改后要重建应用连接并重新验证 PreparedStatement；涉及执行计划的参数还要注意旧计划缓存。静态或只读建库参数必须按 `PARA_TYPE` 判断是重启可生效，还是只能重新初始化实例。任何参数调整都应先在测试实例用代表性 SQL 和边界数据回归，不得直接照表修改生产库。

- MySQL 的 `database` 通常迁移为达梦的 `schema`。验证 SQL 时不能默认所有对象都在当前 schema，跨库 SQL 要么映射到多个 schema，要么明确跳过缺失库表。
- SQL 脚本中的 `USE database` 只在它与对应的 `--schema`/`--system-schema` 一致时自动替换为不含库名的说明注释，实际目标 schema 由运行参数选择；不一致或未配置时保留原语句并要求显式 database-to-schema 映射，不能把来源库名或当前验证 schema 固化为 `SET SCHEMA` 输出。
- SQL 对象名中显式书写的来源库限定符，仅在它与当前脚本对应的 `--schema`/`--system-schema` 明确相同时去除，由外部执行上下文选择目标 schema；规则和输出都不能固化该配置值。未匹配的限定符可能是跨库依赖，必须保留并通过显式 database-to-schema 映射确认，不能猜测为当前 schema。
- system 脚本文件名既包括 `20260205_system.sql`，也包括历史仓库中的 `01_Update_System_Enterprise.sql`、`2024.system.sql` 等形式。只要 `system` 是由点、下划线或连字符分隔的独立文件名段，就应使用 `--system-schema`；`ecosystem.sql`、`systematic.sql` 等普通单词不能误判。
- MySQL 允许的零日期、非法日期需要在迁移前清洗。达梦不接受 `0000-00-00`、`0000-00-00 00:00:00` 这类值。

## 类型和对象差异

- MySQL `VARCHAR(n)`/`CHAR(n)` 迁移到达梦时统一定义为 `VARCHAR(n CHAR)`/`CHAR(n CHAR)`，无论目标库 `LENGTH_IN_CHAR` 为何都保持原字符数上限。不要按 `utf8` 3 倍或 `utf8mb4` 4 倍修改 `n`：倍数扩长只能增加字节容量，还会允许写入超过 MySQL 上限的较短字节字符。达梦 DTS 的 MySQL 类型映射同样使用 `VARCHAR(n char)`/`CHAR(n char)`。
- MySQL `TEXT`、`LONGTEXT`、`JSON` 等类型迁移到达梦时通常需要映射到大字段或字符串类型，并检查业务是否依赖 MySQL JSON 函数。
- 自增列迁移后要特别处理。MySQL `AUTO_INCREMENT` 可对应达梦原生 `IDENTITY(start, increment)`，也可在 MySQL 兼容模式下保留为达梦 `AUTO_INCREMENT`；两者都是自增机制，但不能混用控制语法。只有目标表实际为原生 `IDENTITY` 时，显式插入 id 才能使用 `SET IDENTITY_INSERT ... ON/OFF`；达梦 `AUTO_INCREMENT` 表不需要也不接受该开关，省略 id 时直接由数据库生成，显式 id 则按兼容自增语义插入。脚本里无列清单的 `INSERT INTO t VALUES(NULL, ...)` / `VALUES(DEFAULT, ...)` 如果首列明确是自增列，可省略该列和值。原生 `IDENTITY` 的 MyBatis 批量插入可能同时包含空 id 和显式 id，不能按首个元素决定整批是否保留 id；达梦 8 已验证 `SET IDENTITY_INSERT ... ON WITH REPLACE NULL` 能让空值自动生成、同时保留显式值。dm-adapter 的 `identityInsertTables` 只能记录经目标达梦元数据确认的原生 `IDENTITY` 表；目标为 `AUTO_INCREMENT`、普通列，或验证明确报告“不存在 IDENTITY 列”时必须撤销错误记录，不能把具体表名写死在通用规则里。
- 触发器、函数、存储过程、视图、事件、外键、索引等对象不能只靠 mapper SQL 验证判断完整性。缺对象导致的 `无效的表或视图名`、`无效的列名`、`无法解析成员访问表达式`，优先归类为测试库对象缺失或原始 SQL 引用错误。

## SQL 语法差异

- 以 2026-06-30 对 `192.168.1.53:5236` 的验证结果为基准，dm-adapter 默认不再改写达梦 53 兼容模式已可执行的 MySQL 语法。只有验证失败、语义明显不同、或无法安全推断的 SQL 才进入自动改写或人工确认。
- MySQL 反引号标识符在达梦 53 兼容模式下可执行，默认保留。这个原则同样适用于带表别名的列（例如 ``a.`deleteFlag```）和使用达梦关键字的已引用别名（例如 `` `cluster` ``）；如果工具需要补齐该别名的裸引用，应沿用声明处原有的反引号，不得擅自改为双引号。只有目标库实例参数、大小写策略或旧版本达梦验证失败时，才考虑改为达梦双引号或统一对象名大小写。动态 `${column}`、`${table}` 仍必须依赖白名单参数或 `sql-rewrite.yml`。
- `ROWID`、`ROWNUM`、`TRXID`、`PHYROWID`、`VERSIONS_*` 等达梦伪列或特殊名称如果原本是业务表物理列，迁移命名统一增加前缀下划线，例如 `trxid` -> `_trxid`。本地 DM 8.1.5.8 已验证 `_trxid AS "trxid"` 与 `_trxid AS trxid` 都会报 `-2113 Invalid alias`，因此 SELECT、WHERE、JOIN、DML、DDL、子查询和 CTE 均直接使用前缀物理名，并把 `mapper-dm` 中对应的 `resultMap column="trxid"` 同步改为 `column="_trxid"`。Java 属性、参数和 `keyProperty` 保持原名；没有显式 resultMap 的自动映射查询进入人工确认。`SELECT *` / `t.*` 不自动展开。
- 顶层 `SELECT a.*, b.* FROM a JOIN b ...` 在本地 DM8 即使两表都有 `ID`、`NAME` 也能返回结果；真正触发 `-2112 Ambiguous column name` 的常见形态是分页、计数或框架生成的外层派生表和 CTE，例如 `SELECT * FROM (SELECT a.*, b.* ...) x`。`ERROR_COMPATIBLE_FLAG=1` 可关闭派生表/CTE 同名列检查，系统级持久修改可使用 `CALL SP_SET_PARA_VALUE(1, 'ERROR_COMPATIBLE_FLAG', 1)`；只放开单条语句时可在最外层 SELECT 使用 `/*+ ERROR_COMPATIBLE_FLAG(1) */`。创建视图仍会报 `-2114 Repetitive column name`，JDBC/MyBatis 按列名读取也仍有歧义，因此业务可控时优先显式列清单和唯一别名。MySQL 8 顶层同名列可执行，但外层派生表自身也会报 `Duplicate column name`，所以该参数是更宽松的达梦兼容策略，不是对 MySQL 行为的完全复制。
- MySQL 用双引号表示字符串的写法应改为单引号；达梦双引号表示标识符。转换时必须区分字符串常量、对象名、动态 `${}` 片段，不能把未知业务字符串转成对象名。
- 日期列与紧凑字符串的隐式比较不能只依赖 `COMPATIBLE_MODE=4`。本地 MySQL 8 可把 `DATETIME > '20260816235123'` 解析为 `2026-08-16 23:51:23`，同版本本地达梦会报 `-6118 Invalid datetime value`。`ALTER SESSION SET NLS_TIMESTAMP_FORMAT='YYYYMMDDHH24MISS'` 能让这类 SQL 执行，但随后普通的 `'2026-08-16 23:51:23'` 又会解析失败，因此仅适合某个连接的全部时间串都严格统一为 14 位紧凑格式的场景，不得作为混合格式应用的全局兼容开关。优先顺序是：Java/MyBatis 绑定 `LocalDateTime`/`Timestamp`；把常量写成两库都接受的标准格式；需要显式解析且原始 SQL 要同时兼容两库时使用 `STR_TO_DATE('20260816235123', '%Y%m%d%H%i%s')`；只修改 `mapper-dm` 时可使用 `TO_TIMESTAMP('20260816235123', 'YYYYMMDDHH24MISS')`。如果目标列本身是固定 14 位 `CHAR`/`VARCHAR`，比较属于字符串字典序而非日期转换，要另外校验长度和非法日期，不能套用日期列结论。`DATETIME_STRICT_FLAG` 在不同 DM8 构建中可能不存在，且官方说明它是总体时间容错策略，不应为一条紧凑日期 SQL 盲目切换。
- MySQL 查询分页 `LIMIT offset,size`、`LIMIT size` 在达梦 53 兼容模式下可执行，默认保留。简单单表 `UPDATE`/`DELETE` 的无排序 `LIMIT row_count` 可把候选行限制改写为 `ROWID` 子查询和 `ROWNUM <= row_count`，保留 MySQL 未指定顺序时“任取不超过 N 行”的语义；带 `ORDER BY ... LIMIT 1` 的形态必须把排序保留在内层候选查询。含表别名、多表目标、offset 或无法解析计数的 DML 仍要人工确认。
- MySQL `LIKE #{name} '%'`、`LIKE '%' #{name}` 这类参数与字符串字面量相邻拼接在达梦 53 仍会失败，应改为 `#{name} || '%'` 形式。`#{}` 参数可以安全拼接，`${}` 动态片段必须保守处理。
- MySQL 表级索引提示 `USE INDEX(...)`、`FORCE INDEX(...)`、`IGNORE INDEX(...)` 不能原样交给达梦执行，工具应删除这些优化器提示；`PRIMARY` 也是索引提示参数，例如 `FORCE INDEX(PRIMARY)` 必须整体删除。`KEY` 同义写法以及可选的 `FOR JOIN`、`FOR ORDER BY`、`FOR GROUP BY` 作用域使用相同规则。
- `CONCAT`、`CONCAT_WS`、`IFNULL`、`IF`、`ISNULL`、`FIND_IN_SET`、`DATE_FORMAT`、`STR_TO_DATE`、`SUBSTRING_INDEX`、两参数 `DATEDIFF`、`UNIX_TIMESTAMP`、`FROM_UNIXTIME`、`TIMESTAMPDIFF`、常见 `JSON_*` 函数在达梦 53 验证可执行，默认保留 MySQL 函数形态。
- MySQL 可在查询列中用 `(列 IS [NOT] NULL) AS 标志` 直接返回 0/1，达梦不接受这种布尔投影；工具应转换为 `CASE WHEN 列 IS [NOT] NULL THEN 1 ELSE 0 END AS 标志`，且不能改写 `WHERE` 中本来合法的空值判断。
- `NOW()` 与达梦 `SYSDATE` 在 53 环境中存在时区/时间来源差异，不能再把 `NOW()` 盲目替换为 `SYSDATE`。原始 mapper 中也应保留 MySQL 函数形态，不能把达梦函数反写到原始 MySQL XML。
- MySQL `GROUP_CONCAT(DISTINCT a, ',', b)` 这类聚合不能保留 MySQL 形态，要先把参数拼接为一个表达式，再转为达梦 `LISTAGG(DISTINCT ..., ',') WITHIN GROUP (...)`。
- MySQL 允许在 `HAVING` 中写未聚合、未列入 `GROUP BY` 的普通列条件，达梦会报“无效的 HAVING 项”。仅由 `AND` 连接、没有聚合函数、子查询、`OR` 或动态标识符的普通比较条件，应前移到同一查询作用域的 `WHERE`，聚合条件继续保留在 `HAVING`；嵌套子查询必须在各自作用域内改写。达梦允许 `HAVING` 直接过滤实际分组列，因此条件位于 MyBatis `<if>` 内且不能安全前移时，只要静态 `GROUP BY` 或完整 `<choose>` 的每个 `<when>`/`<otherwise>` 分支都明确包含条件引用的普通列，就应保留原 `HAVING` 且不报告人工确认；缺少兜底分支、使用动态分组表达式或任一分支无法证明时仍须人工确认。
- MySQL `REGEXP`/`NOT REGEXP` 操作符应改写为达梦 `REGEXP_LIKE`。右侧表达式如果已经是达梦可执行的 `CONCAT(...)`，不需要额外转成 `||`。
- MySQL `DATE_ADD`/`DATE_SUB`/`INTERVAL` 形式应改写为 `DATEADD`。`YEARWEEK`、无法识别的 `DATE_ADD`/`DATE_SUB` 形态、以及未被规则覆盖的 `PERIOD_DIFF` 需要人工确认；已识别的 `PERIOD_DIFF(DATE_FORMAT(...,'%Y%m'), ...)` 可转为月份差。通用函数风险扫描必须跳过普通字符串字面量，避免把配置文案或待存储的 SQL 文本误报成当前语句的函数风险；但顶层 DML 或普通存储过程内 `INSERT`、`UPDATE`、`MERGE` 直接写入字段、且内容以 `SELECT`、`WITH`、`INSERT`、`UPDATE`、`DELETE`、`MERGE` 开头的完整 SQL 字符串，应作为内嵌 SQL 单独执行同一套安全转换。内嵌 SQL 无法完整转换时仍须保留原值并进入人工确认，不能只消除告警。
- MySQL `CONVERT(expr, DECIMAL(n))`、`CONVERT(expr, DECIMAL(n,m))` 应转为 `CAST(expr AS DECIMAL(...))`，不能按达梦 `CONVERT` 函数原样保留。
- MySQL `ON DUPLICATE KEY UPDATE` 不能直接在达梦执行，通常要改为 `MERGE INTO` 或业务侧先查后写。dm-adapter 不应在无法确认唯一键和更新列语义时强行转换。项目 DDL 已明确主键/唯一键、但 INSERT 列不包含任何一个完整冲突键时，原写法本身无法按预期触发冲突更新，应归为原始 SQL/键元数据冲突；普通非唯一索引不能冒充冲突键，也不能据此猜测 `keyColumns`。`column = column` 这类自赋值仅用于表达“冲突时不更新”，转换后的 `MERGE` 应省略 `WHEN MATCHED`，不能生成歧义的自赋值表达式。
- MySQL `INSERT IGNORE`、`REPLACE INTO` 需要确认唯一键、忽略冲突和替换删除语义。元数据不可用或工具无法解析 INSERT 列时，必须人工配置真实键；普通 upsert 存在多个可用唯一键时也不能猜测更新目标。如果 `INSERT IGNORE` 的所有主键/唯一键冲突都不可达（表没有主键/唯一键，或每个未显式插入的冲突键都依赖由数据库生成的自增列），`IGNORE` 对重复键语义没有作用，可安全转为普通 `INSERT`；其他 INSERT 未包含完整冲突键的情况仍归为原始 SQL/键约束冲突，不能猜测 `keyColumns`。`ON DUPLICATE KEY UPDATE` 的更新分支如果只是 `VALUES(同列)`、常量/当前时间，或目标列自身加减数值常量，可分别映射为 MERGE 的 `s.列`、原常量或 `t.列 +/- 常量`；包含其他列、子查询或混合新旧值的表达式仍须保守处理。
- `INSERT IGNORE` 的 INSERT 列同时覆盖多个主键/唯一键时，不能任取一个 `keyColumns`，必须把每个约束保存为独立 `conflictKeyGroups`，并在达梦 `MERGE ON` 中以“组内 AND、组间 OR”覆盖所有可达冲突。没有 `FROM` 的单行 `INSERT IGNORE ... SELECT 参数列表` 可按单行源转换；普通 `ON DUPLICATE KEY UPDATE` 仍不得在多个冲突键之间猜测更新目标。MyBatis 批量 `VALUES <foreach>` 可逐行生成达梦匿名块，并只捕获 `DUP_VAL_ON_INDEX` 后继续下一行；这种转换不需要猜测某一个冲突键，也不能使用 `WHEN OTHERS` 吞掉非重复键错误。动态字段列表必须按完整 XML 节点配对，结构不完整时仍保留原文并报告。
- MyBatis 动态表名、动态字段的批量 `ON DUPLICATE KEY UPDATE` 只有在 `sql-rewrite.yml` 或 batch YAML 以完整 `namespace.statementId` 提供权威 `keyColumns` 时，才可逐行转换为 `MERGE`；更新列使用 `VALUES(同列)` 可引用来源行，目标旧值加减来源值可映射为 `t.列 +/- s.列`。键缺失、动态列和值结构不一致、更新表达式跨列或含未知 SQL 片段时必须拒绝猜测。
- 动态 `INSERT [IGNORE] ... SELECT` 在目标列和 SELECT 投影可逐项配对且已配置方法键时，可使用有序游标逐行执行 `MERGE`，从而保留同一来源中重复键的处理顺序。原查询尾部带 `FOR UPDATE` 时，达梦不允许聚合游标继续保留该子句；工具仅在来源是可识别的单表时改为匿名块内 `LOCK TABLE 来源表 IN SHARE MODE` 后移除 `FOR UPDATE`，以保护读取期间的来源数据。来源为派生表、复杂表达式或无法提取锁表对象时保留原 SQL 并报告，不能静默去锁。
- 推断冲突键时必须按项目 DDL 的执行顺序计算最终约束状态，不能只收集 `CREATE TABLE` 中曾经出现过的键。后续 `ALTER TABLE ... DROP INDEX/KEY/CONSTRAINT`（包括被调用的迁移过程内语句）必须移除旧唯一键，后续新增的主键/唯一键和独立 `CREATE UNIQUE INDEX` 必须纳入；普通 `ADD INDEX` 仍不属于冲突键。项目中存在完整建表历史时，其最终状态优先于可能未同步的达梦测试库键元数据。已持久化的 `conflictKeyGroups` 若与当前 DDL 中所有可达冲突键不再一致，必须丢弃并重新推断，避免历史键删除后仍生成错误 `MERGE`。
- MySQL `UPDATE ... JOIN ... SET ...` 只更新一个表别名且目标达梦可执行时可以保留；本地 DM8 已分别在 `COMPATIBLE_MODE=0` 和 `COMPATIBLE_MODE=4` 验证“主表 `INNER JOIN` 后再 `LEFT JOIN` 普通表/派生表”、更新主表别名和更新 JOIN 右表别名均可执行，即使连接源键重复也不会因基数报错。因此只要每个 `SET` 左值都带可识别限定别名且最终只归属于同一个目标表，就不应仅因缺少来源唯一键而报告人工确认；右表目标不能被误判成多目标。任一左值未限定、引用未知别名或同时更新多个别名时仍需保守处理。达梦对真正的多目标更新会报“多表更新时仅支持更新同一个表上的列”。拆分语句必须保持原 JOIN 匹配快照，不能让第一条 UPDATE 改掉后续语句仍依赖的谓词。若只有一个目标会修改匹配谓词且各目标右值不依赖其他目标被修改的列，可按“未改谓词的目标在前、改谓词的目标最后”生成达梦匿名块；若两个目标都会修改谓词，但能证明主表 `ID` 由方法参数唯一绑定、JOIN 将该 ID 映射到从表外键，则先更新主表，并用 `IF SQL%ROWCOUNT > 0 THEN` 和推导出的从表外键条件更新从表。表名、别名、列名和参数都必须从原 SQL 提取，不能写死项目值；无法证明等价时保留原 SQL 并报告，不能盲目顺序拆分。
- `UPDATE ... JOIN` 后接 MyBatis `<where>` 时，应把 JOIN 谓词放进同一个 `<where>`，再保留原有静态或纯动态 `<if>/<foreach>` 条件，不能先生成普通 `WHERE` 再留下第二个动态 `WHERE`。即使所有原条件都位于动态标签内，JOIN 谓词也必须作为无条件首项保留，保证动态条件为空时仍不会扩大更新范围。
- MySQL 用户变量和累加写法如 `@rownum := @rownum + 1` 不能直接迁移，通常改为达梦窗口函数 `ROW_NUMBER() OVER (...)`，或在存储过程/业务代码中显式声明变量。
- 同一查询中多个用户变量赋值彼此引用时，原 SQL 依赖 MySQL 不稳定的表达式求值顺序；不能把这种状态机直接猜成达梦 SQL。报告必须指出涉及的变量和原始求值顺序风险，并要求用明确排序的窗口函数、gaps-and-islands 查询重写，或由业务方提供预期分组语义。
- 对 `GROUP_CONCAT`/`FIND_IN_SET` 配合用户变量累积父级或子级 ID 的 MyBatis 层级遍历，只有在游标变量、起点参数、ID/父 ID 列、三处来源表和输出过滤关系全部一致时，才可改写为达梦 `START WITH ... CONNECT BY NOCYCLE`。表名、列名、别名和参数必须从原 SQL 提取，不能写死项目库名或模式名；父级遍历须保留起点，子级遍历须按原语义决定是否包含起点，额外租户过滤和排序也必须保留。
- MySQL `CREATE TABLE` 尾部的表级 `COMMENT '...'`、`ENGINE`、`DEFAULT CHARSET`、`COLLATE`、`ROW_FORMAT`、表级 `AUTO_INCREMENT` 等选项要从迁移 SQL 中移除，且删除 `DEFAULT CHARSET` 时必须整体删除，不能遗留孤立的 `DEFAULT`。本地 DM8 已验证建表列定义内的 `COMMENT '列备注'` 可以执行，应予保留；`COMMENT='列备注'` 要规范为无等号的单引号形式，MyBatis 动态拼接的双引号备注要改成最终生成单引号并转义备注值中的单引号。表级备注按当前迁移策略丢弃；尾部明确为 `COMMENT '${comment}'` 等动态表备注时也应整体删除，不需要解析占位符或报告人工确认，只有删除已支持选项后仍残留动态尾部结构才进入人工处理。`ALTER TABLE ... ADD COLUMN ... COMMENT` 在本地 DM8 不可执行，本规则暂不自动拆成 `COMMENT ON COLUMN`，无法确认时继续进入人工处理。
- `CREATE TABLE` 中普通/唯一的 MySQL 内联 `KEY` / `INDEX` 必须提取为带存在性保护的达梦 `CREATE [UNIQUE] INDEX`，并按表名生成 schema 范围唯一的索引名；前缀索引转函数索引。达梦会拒绝在同一表上创建异名但列定义相同的索引，因此保护条件不能只查目标索引名，必须通过 `ALL_INDEXES`、`ALL_IND_COLUMNS` 和 `ALL_IND_EXPRESSIONS` 比较目标 schema、表、唯一性、列数、列/表达式顺序及升降序。异名等价索引应视为已满足；同名但定义不同不能静默跳过，应让部署准确暴露名称冲突。过程内 `ALTER TABLE ... DROP INDEX` 不能原样放入动态 SQL：达梦索引名是 schema 级对象，且 MySQL 唯一索引可能对应达梦唯一约束，必须同时兼容原始名与表作用域名，先尝试 `DROP CONSTRAINT`，再按存在性执行 `DROP INDEX`。`FULLTEXT`、`SPATIAL` 或无法确认等价性的表达式索引不得静默删除，必须保留原 SQL 并报告人工设计索引语义。
- 同一列定义出现两个或更多 `DEFAULT` 子句时，原始 MySQL DDL 本身存在互相冲突的默认值，工具不能替业务选择保留哪一个。数据库验证应将其归为 `ORIGINAL_SQL`，保留转换结果并要求修正源脚本，不能误报为待补充的达梦转换规则。
- 存储过程中出现 `SELECT ... END INTO ...` 且对应表达式中没有 `CASE` 时，多出的 `END` 是原始 SQL 语法缺陷。验证应归为 `ORIGINAL_SQL`，不能由转换器猜测删除；合法的 `SELECT CASE ... END INTO ...` 不属于此类。
- MySQL 为删除自增主键列而连续执行“删除主键并补普通索引 → 去掉自增属性 → 删除该列”时，达梦会在第一个中间态拒绝没有唯一约束的自增列。若三个 DDL 紧邻、表名和列名一致且新增索引只包含该列，可直接收敛为 `DROP COLUMN`，最终结构等价且避免无效中间态。
- 存储过程中的 `CREATE TEMPORARY TABLE name AS (SELECT ...)` 需要去掉包裹整个查询的单层括号，再按会话级全局临时表加 `DELETE + INSERT ... SELECT` 转换；否则过程编译时会把临时表误当成运行期普通表，形成“无效的表”假失败。
- 存储过程中的显式 `CREATE TEMPORARY TABLE name (...)` 必须把原字段类型带到达梦全局临时表编译占位对象，不能根据列名后缀猜测类型。尤其是以 `ID` 结尾的字母数字业务键不能推断成 `BIGINT`；没有原始定义时，除已明确的数值租户字段和裸 `id` 外，未知 ID 应保守使用字符串类型。`CREATE ... IF NOT EXISTS` 不会修正历史错误对象，因此占位表已存在时还必须按当前 schema 校正字符串 ID 的类型和长度，再编译过程。
- 存储过程体中的 `UPDATE ... JOIN` 必须与顶层 SQL 使用同一转换规则；语句前的行注释或块注释不能让 `UPDATE` 被当成普通标识符，也不能导致原始 JOIN 更新绕过转换。生成的 `UPDATE ... FROM`/`MERGE` 必须保留全部 JOIN 和 WHERE 谓词，并以包含无关目标行的回归用例证明不会扩大更新范围。
- MySQL `AUTO_INCREMENT` 和 `ALTER TABLE t AUTO_INCREMENT = n` 默认不再为了验证而改写。目标环境如果不支持或业务依赖重置序列语义，应按达梦身份列/序列方案人工确认，不能把 `AUTO_INCREMENT = n` 删除后留下半截 `ALTER TABLE`。
- MySQL `ON UPDATE CURRENT_TIMESTAMP` 在达梦 53 环境验证失败，但达梦 53 支持 `ON UPDATE NOW()` 列属性，且无需触发器即可在更新普通列时自动刷新时间列。默认应把 `ON UPDATE CURRENT_TIMESTAMP` 改为 `ON UPDATE NOW()`；带精度的 `CURRENT_TIMESTAMP(n)` 必须对应为 `NOW(n)`，否则 `TIMESTAMP(n)` 会因表达式精度不匹配报“ON UPDATE 表达式错误”。只有目标达梦版本不支持 `ON UPDATE` 时，才退回触发器或应用 SQL 维护更新时间。
- MySQL 允许在一条 `ALTER TABLE` 中连续写多个 `MODIFY COLUMN`，达梦需要把它们拆成按原顺序执行的独立 `ALTER TABLE ... MODIFY ...`。脚本转换必须保留第一条语句前的注释，并把每个字段修改作为独立验证单元。
- `UPDATE ... SET a = value AND b = value` 在 MySQL 中会把 `AND` 解释为赋给 `a` 的布尔表达式，而不是同时更新 `a`、`b`；达梦会拒绝这种写法。工具无法判断业务究竟想用逗号更新两列，还是想计算单列布尔值，因此应保留原 SQL 并精确报告原始语义歧义。`SET a = CASE WHEN 条件1 AND 条件2 THEN ... END` 中的 `AND` 属于合法的 CASE 分支条件；本地 DM8 已验证普通 UPDATE、存储过程静态 UPDATE 和 `EXECUTE IMMEDIATE` 动态 UPDATE 均可执行，歧义检测不得把 CASE 内部的条件误认成第二个列赋值。
- MySQL `information_schema.TABLES/COLUMNS` 不应原样迁移。表存在性检查可映射到 `ALL_TABLES`，列清单可映射到 `ALL_TAB_COLUMNS`，需要创建时间或 schema 名的表详情可映射到 `ALL_OBJECTS`，并按当前 schema 过滤。业务代码同时读取 `COLUMN_TYPE`、注释和默认值时，可从 `SYS.SYSCOLUMNS`、`SYS.SYSOBJECTS`、`SYS.SYSCOLUMNCOMMENTS` 按运行时 schema 和表名重建相同投影；不得把某个项目的 schema 固化到转换规则。
- `AES_ENCRYPT`、`AES_DECRYPT`、`MD5`、`TO_BASE64` 等加密/编码函数要逐项确认。当前不再把 Base64 包裹 AES 密码场景改写为达梦 `SF_*` 函数，优先通过系统库兼容函数保持 MySQL 调用形态，避免修改业务 SQL。
- MySQL `/` 具有小数除法语义，达梦的整数/整数可能先截断；对可完整识别的数值、列、日期数值函数、聚合和无子查询括号表达式，应把分子转为 `DECIMAL(38,10)`，并把分母转为 `NULLIF(CAST(... AS DECIMAL(38,10)), 0)`。分母为 0 的容错和达梦参数相关，不能依赖实例容错；无法完整识别表达式边界时仍须人工确认。
- SQL Server 风格 `+` 只能在一侧是字符串字面量、`CONCAT`，或 `CAST/CONVERT` 明确声明字符返回类型时改为达梦 `||`。`CAST(... AS DECIMAL)`、`CONVERT(..., DECIMAL)` 等数值结果之间的 `+` 必须保留算术加法，不能仅凭函数名推断为字符串拼接。
- MySQL 中常见 `SUM(varchar_col)` 依赖隐式转换，达梦可能报类型转换失败。应优先修业务 SQL，显式 `CAST` 且清洗非数字数据。

## 存储过程、函数和触发器

- 达梦官方迁移流程强调：DTS 可以迁移部分对象，但 MySQL 与 DM 在存储过程、函数、触发器语法上差异明显，迁移失败或自动转换后仍不兼容时要按 DM 语法人工重建。
- MySQL 存储过程中的局部变量、游标、异常处理、临时表、动态 SQL、函数调用不能直接照搬。dm-adapter 当前主要处理 MyBatis mapper SQL，遇到存储过程/函数/触发器调用失败，应先判断目标库是否已创建等价对象。
- MySQL 过程先写 `DROP TABLE IF EXISTS t`、再写 `CREATE TEMPORARY TABLE t` 时，前一条 `DROP` 仍属于该过程局部临时表的初始化语义，即使没有显式 `TEMPORARY` 关键字。转换为达梦 `#t` 局部临时表后应把这类 `DROP` 消解为过程空操作；不能生成位于 `CREATE TABLE #t` 之前的 `DELETE FROM #t`，否则达梦会在编译过程时因临时表尚不存在而把过程标记为无效。
- 达梦过程解析器在较长过程体中不能稳定接受紧贴在 `CREATE TABLE #局部临时表` 之前的注释，错误位置会误报到首个列类型。转换时应把连续相邻注释原样移到该局部临时表 DDL 之后；不能删除注释内容，也不能把这一类解析器缺陷误判成列类型不支持。
- MySQL `CONCAT` 支持多个参数，而目标达梦过程表达式只接受二元 `CONCAT`。过程中的多参数动态 SQL 拼接不能原样保留，也不能直接换成 NULL 语义不同的 `||` 后就声称等价。对“局部变量赋值，且参数仅由字符串、数字、局部变量组成”的安全动态 SQL，按原参数顺序拆成多条浅层二元 `CONCAT`；任一参数为 NULL 后目标变量及后续拼接仍为 NULL，与 MySQL 的传播语义一致，也能避开达梦过程解析器对大型嵌套表达式的限制。如果赋值目标在参数中恰好出现一次，例如 `v := CONCAT('prefix', v, 'suffix')`，该 `v` 必须读取赋值前的旧值：先从旧值向右追加后缀，再按逆序把前缀拼到左侧，不能先用首段覆盖，也不能退化成达梦仍无法稳定编译的长平衡嵌套。目标出现多次或含复杂表达式时保守使用平衡二元嵌套并继续真实编译验证。达梦过程解析器不能稳定接受嵌套 `CONCAT` 参数中的多行字符串字面量，因此字面量里的 CR/LF 还要等价拆成 `CHR(13)`/`CHR(10)`，不能简单删除换行并改变字符串内容。
- MySQL 过程中的 `IF [NOT] EXISTS (SELECT 1 FROM ... WHERE ...)` 可改写为局部变量直接 `SELECT COUNT(*) ... FROM ... WHERE ...` 后判断。目标达梦在较长、同时包含 `LISTAGG` 与动态 SQL 的过程里，可能无法编译 `SELECT COUNT(*) FROM (SELECT 1 ...)` 派生表写法，即使该片段单独编译有效；直接计数时整条 `SELECT COUNT(*) INTO 局部变量 FROM ... WHERE ...` 必须保持在同一逻辑行，不能在 `INTO`、`FROM` 或 `WHERE` 前换行。只有投影严格为常量 `1`，且查询顶层不含分组、聚合过滤、集合运算、排序、分页、锁或窗口子句时，才能去掉派生表；`ALL_TAB_COLUMNS` 等还需后续重建的元数据查询也必须保留原形态，复杂 `EXISTS` 同样继续保留派生查询，以免跳过专用转换或改变存在性语义。
- MySQL `CONTINUE HANDLER FOR SQLSTATE '02000'`/`NOT FOUND` 仅作为游标结束标志，且能由完整的 `OPEN`、`FETCH`、循环退出和 `CLOSE` 结构证明语义等价时，可自动改写为达梦游标循环与 `cursor%NOTFOUND`；结构关键字之间的空白和 SQL 注释不应阻止识别。以游标目标为 NULL 哨兵的写法，只有在循环内每个可能无结果的 `SELECT ... INTO` 都先把同一目标置为 NULL、并可等价改成保留 NULL 语义的标量子查询时才能自动转换。处理标志还有业务用途，或同一处理器还可能捕获循环体内其他可能无结果的 `SELECT ... INTO` 时，原 SQL 会提前结束循环并漏处理后续游标数据，应归为原始 SQL 逻辑缺陷，不能只删除处理器。
- 脚本反复 `DROP`、重建并调用同名存储过程时，人工确认状态必须按执行顺序跟随当前过程版本；后续成功转换的 `CREATE PROCEDURE` 会覆盖前一人工版本，不能仅按过程名把后续 `CALL` 永久连带标记。
- 仅用于一次性迁移、满足“`DROP PROCEDURE IF EXISTS` → 无参 `CREATE PROCEDURE` → 无参 `CALL` → 同名 `DROP PROCEDURE IF EXISTS`”连续完整闭环的临时过程，可等价折叠为达梦匿名块。这样既不留下过程对象，也避免大量过程 DDL 带来的验证和部署耗时；同名过程后续出现新的独立完整闭环时，可按生命周期分别折叠。单个生命周期内被多次调用、带参数、缺少完整闭环、包含人工确认语句，或闭环中间还有其他对象生命周期时不得折叠。
- Java 或 mapper 中调用业务自定义函数时，如果达梦测试库缺函数，应归类为测试库缺对象；如果函数存在但签名或返回类型不同，应归类为业务对象迁移问题，不能通过 SQL 字符串替换掩盖。
- SQL 脚本中的存储过程依赖必须按目标 schema 判断。当前迁移队列内尚未创建就被引用属于脚本顺序问题；当前队列未定义的过程可能是系统库预置的共享依赖，不能仅凭当前项目缺少其 `CREATE PROCEDURE` 就判定对象缺失。
- 多个业务仓库共同调用、但不属于各仓库升级脚本的系统共享过程，应先在系统库的 output-only 基础脚本（例如 `sql-root-out/00000000.sql`）中人工维护等价的达梦实现，再验证依赖仓库。迁移器必须保留这类只存在于输出目录的脚本，不能在重新生成日期脚本时覆盖；过程实现属于项目初始化资产，不能把过程名、schema 或业务表写进通用转换规则。
- 共享过程不能以“文件里已有 `CREATE PROCEDURE`”作为完成依据。应先单独创建或编译目标过程，确认实际目标 schema、`ALL_OBJECTS.STATUS` 和编译错误，再用回滚事务或幂等样例验证必填入参、插入、更新和重复调用语义。数据库在 JDBC 握手阶段超时时，应归为环境连接故障；只有成功建立会话并进入对应 DDL 后的错误，才能归因到过程 SQL。
- 真实全量脚本耗时较长时，不应在每轮工具修改后立即重跑全部仓库。先选择系统库、共享过程依赖覆盖最全的仓库、最近暴露规则缺陷的仓库和至少一个正常基线仓库做代表性真实回归；这些项目的脚本与 MyBatis 均通过且已检查语义后，再基于最终候选版本运行一次全量。
- 升级脚本可能由 Windows 工具保存为带 BOM 的 UTF-16LE/UTF-16BE，而不只是 UTF-8。读取时应按 BOM 解码，迁移输出统一写为 UTF-8；不能因固定按 UTF-8 读取而在全量执行中途抛出 `MalformedInputException`。
- 达梦 JDBC 能执行超长字符串 SQL，不代表旧版 DIsql 通过 `START`/`@文件` 执行时也能接收同一条超长输入；单行 `UPDATE ... SET column = '<超长内容>'` 仍可能报 `DISQL-10033: 输入过长`。对最终达梦脚本中 UTF-8 超过 3000 字节、且能明确证明位于顶层 `UPDATE SET`、`INSERT VALUES` 或 `MERGE` 直接赋值位置的字符串，dm-adapter 使用规则 `DM_DISQL_LONG_DML_LITERAL_TO_CLOB_BLOCK` 生成匿名块：每段最多 900 UTF-8 字节，通过 `TO_CLOB` 逐段拼接后再执行原 DML。存储过程内已声明的 CLOB 直接赋值、已知 JSON/CLOB 过程参数，以及 `UPDATE SET`、`INSERT VALUES`、`INSERT ... SELECT` 直接投影和 `MERGE` 直接赋值，可使用规则 `DM_PROCEDURE_LONG_LITERAL_TO_CLOB_VARIABLE` 在原控制流位置生成局部 CLOB 变量并按相同规则分段拼接；未知过程参数不得猜测为大字段。存储过程内 `EXECUTE IMMEDIATE '<超长 SQL>'` 的直接字符串参数若 UTF-8 不超过 32767 字节，可使用规则 `DM_PROCEDURE_LONG_DYNAMIC_SQL_TO_VARCHAR_VARIABLE` 声明 `VARCHAR(32767)` 局部变量，并在原控制流分支内按最多 900 UTF-8 字节逐段赋值后执行，避免把不同分支的动态 SQL 提前初始化。超过 32767 字节、由表达式动态拼接或无法确认 `INTO`/`USING` 边界的动态 SQL 仍须人工处理。所有转换只折叠 SQL 的成对单引号，必须逐字符保留中文、emoji、单引号和反斜杠；原脚本不覆盖，只修改输出目录。其他位于 `WHERE`、函数表达式、`RETURNING` 等不安全位置的长字符串，以及单个超过 20MB 的字符串必须保留原 SQL 并进入人工确认，不能为了绕过客户端长度限制强行改写语义。
- 所有生成的达梦字符串表达式都不得让单引号字面量跨物理行。长 JSON、HTML、动态 DDL 和过程变量中的 CRLF、LF、CR 必须分别用 `CHR(13) || CHR(10)`、`CHR(10)`、`CHR(13)` 重建；CRLF 按一个逻辑边界参与 900 字节分段，但原始两个字符必须完整保留。输出语句还要经过线性静态门禁：字符串、注释、括号和过程末尾必须闭合，且不得残留 `DELIMITER`、`$$`、脚本级 `@变量`、`ENGINE=`、列级 `COMMENT`、`AFTER` 或 `USING BTREE`。门禁失败时保留原 SQL 并生成人工确认项，批处理不得提交或推送。
- `information_schema.statistics.SUB_PART` 当前只自动兼容空值判断：普通完整列索引投影为 `NULL`，在相同 owner、表、索引和列位置上存在 `ALL_IND_EXPRESSIONS` 记录时投影为非空标记。因此 `SUB_PART IS NULL`/`IS NOT NULL` 可自动转换；读取实际长度、数值比较、排序或分组必须人工确认，不能把非空标记冒充真实 MySQL 前缀长度。
- MySQL 多字符 `TRIM([BOTH|LEADING|TRAILING] remstr FROM value)` 只有在过程内直接赋给已声明文本局部变量、`remstr` 是非空常量、来源是可完整解析且不含子查询的标量表达式时，才使用规则 `MYSQL_PROCEDURE_MULTI_CHARACTER_TRIM_TO_DM` 改成 `WHILE` 循环，重复移除完整 remstr。不得把 remstr 当成字符集合。动态或空 remstr、嵌套查询、非文本目标及其他上下文保留原 SQL 并阻断发布。
- 原始 CREATE/ALTER 列定义中的重复 `DEFAULT`、同时出现 `NULL` 与 `NOT NULL`，以及可证明的 INSERT 列值数量不一致，分别以 `ORIGINAL_SQL_DUPLICATE_DEFAULT`、`ORIGINAL_SQL_CONTRADICTORY_NULLABILITY`、`ORIGINAL_SQL_INSERT_VALUE_COUNT` 报告。显式 INSERT 列清单和同一脚本已知表结构可离线检查；联网验证时，无列清单 INSERT 还要对照当前达梦表的可见列数。工具只分类并保留原文，不能擅自删除约束或值来猜业务意图。
- 脚本级 `SET @变量`、`FOREIGN_KEY_CHECKS`、`PREPARE` 等 MySQL 控制语句被安全消解为审计注释时，写盘 SQL 必须同时生成可执行的达梦匿名 `NULL` 空操作块，使迁移结果、输出脚本和严格验证计划仍保持逐条对应。不能因解析器忽略纯注释而产生语句计数漂移，也不能因此跳过数据库验证。
- 脚本级 `SELECT ... INTO @变量`、多列 `SELECT ... INTO @变量1,@变量2` 和 `SET @变量=(SELECT ...)` 若能证明查询来源表在最后一次引用前未被修改，可把查询转为达梦标量子查询并内联到后续普通 SQL 或临时过程中；未被使用的赋值可安全消解。来源表在引用区间内发生修改、查询仍依赖未解析用户变量或无法证明标量语义时必须保留人工确认，不能把“每次重新查询”冒充 MySQL 的赋值快照。
- MyBatis 语句包含 `<if>`、`<foreach>`、`<include>`、`<where>` 等 XML 节点本身不是人工确认理由。只有转换后仍检测到明确的未解决兼容风险（例如未改写的 `ON DUPLICATE KEY UPDATE`、无法安全拼接的动态 `UPDATE JOIN` 或未支持的函数）才进入人工确认；成功改写后还必须移除由原始片段产生、但在最终 SQL 中已不存在的过期风险项。不能只按 `<insert>`、`<update>`、`<delete>` 标签决定转换类型，历史 mapper 可能在 `<delete>` 中实际书写 `UPDATE`，必须以 SQL 首关键字为准。
- 临时迁移过程中的元数据检查不能把 CLI `--schema` 固化到输出脚本，也不能在过程内部用 `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')` 推断。真实达梦探针已验证：普通会话中它能随 `SET SCHEMA` 变化，但存储过程内可能返回过程定义者相关的 `SYSDBA`；同一过程中 `SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)` 则会随调用会话分别返回实际目标 schema。因此应以 `CURRENT_SCHID` 作为当前模式的运行时依据。仅含当前模式、表名和列名的列存在性检查，可改写为 `SYS.SYSOBJECTS` 与 `SYS.SYSCOLUMNS` 的窄查询，并用原始名称与其大写形式的等值候选匹配，避免在字典列上调用 `UPPER()` 导致全量扫描；含类型、长度、可空性等附加条件的复杂检查仍使用 `SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)` 配合 `ALL_TAB_COLUMNS`。两种输出都不得固化 CLI schema。
- 存储过程通过动态 DDL 修改对象后，后续静态 SQL 继续访问同一对象可能因编译期对象不存在、列定义变化或执行计划版本失效而报对象无效或 `-7184`。应按执行顺序判断：静态访问全部发生在最终 DDL 之前时无需改写；DDL 后的 `UPDATE`、`INSERT`、`DELETE`、`MERGE` 和 `SELECT ... INTO` 可整体改为 `EXECUTE IMMEDIATE`。其中可明确识别的过程参数和局部变量输入应改为 `?` 并按出现顺序生成 `USING` 绑定，重复引用同一变量时也要重复传入；DML 目标列和 `SELECT ... INTO` 输出变量不能误当输入绑定。动态对象名、循环或无法可靠解析的控制流仍保留原 SQL 并进入人工确认，不能只改写其中一部分。
- 扫描 `EXECUTE IMMEDIATE` 内的长 SQL 字面量时不能使用逐字符递归回溯的正则表达式；真实升级脚本可能在较小 JVM 线程栈上触发 `StackOverflowError`。应按引号和注释边界线性扫描，再对提取出的 DDL 做精确匹配。
- 启用数据库验证时，外部共享过程依赖交给达梦实际编译和创建后对象状态检查确认。dry-run、关闭验证或验证基础设施不可用时，只在报告中按 schema 汇总“尚未验证”的外部过程依赖，不应将其伪装成人工确认 SQL。

## MyBatis 迁移判断准则

- 原始 mapper XML 不由 dm-adapter 迁移流程覆盖；自动迁移输出保持在 `src/main/resources/mapper-dm`。
- MySQL `LEFT JOIN` 更新若右表列参与赋值，不能直接降级成达梦 `UPDATE ... FROM`，因为无匹配行和重复来源行的语义会变化。只有项目 DDL 的真实主键或唯一键被 `ON` 条件完整绑定，或右侧派生表按连接列 `GROUP BY` 已直接证明每个键最多一行，且赋值形态可等价表示时，才可把右表表达式改为相关标量子查询；`WHERE` 中引用右表的 `AND` 条件必须同时下推到标量子查询并在外层补等价 `EXISTS`，不能留下失效的右表别名。动态 `<foreach>` 只是外层过滤条件时应原样保留，不应阻止上述转换。赋值完全不依赖右表时，可用 `EXISTS` 保留匹配过滤语义，不需要唯一性证明。`IF(IFNULL(右表非空主键, 哨兵)=哨兵, 未匹配值, 匹配值)` 只表达右表是否存在时，即使连接列不唯一，也可按真实非空主键改为 `CASE WHEN EXISTS`，因为重复来源不会改变结果；不得把普通可空列误作存在性证明。缺少必要的唯一性证明时应准确报告约束风险，不能任选一条来源记录。
- 原始 SQL 明显错误时，可以修原始业务 SQL。例如列名写错、insert 列和值数量不一致、`set` 末尾多逗号、把 `UPDATE table` 写成 `UPDATE FROM table`、在 `#{...}` 前误粘普通字母而生成 `s?`，或把 MyBatis `jdbcType` 写成不存在的枚举（如 `TIMESTAMPT`）。这类错误即使后续通用规则引用了达梦关键字，也仍应按原始 XML 语法缺陷分类。若问题来自 Java mapper 方法签名，如多个参数复用同一个 `@Param` 名称，或多个简单参数缺少必要 `@Param`，应修 Java mapper 方法签名，不应为了绕过绑定错误去改 XML 参数名。
- Java 注解里的 SQL 如果包含复杂动态 SQL、MySQL 专有语法或需要达梦改写，应优先迁移到 mapper XML，再由 dm-adapter 生成 `mapper-dm`；自动迁移也应把可识别的 `@Select`、`@Insert`、`@Update`、`@Delete` SQL 提取到 `mapper-dm` XML 后再执行达梦改写和验证。
- mapper XML 可能带 UTF-8 BOM。解析器必须按字节流交给 XML 解析器识别 BOM；不能把 BOM 作为正文字符传入 `Reader`，否则整份文件会退化为“无法安全解析”，后续动态 SQL 结构转换将被跳过。
- 改写动态 SQL 时必须把 MyBatis XML 元素视为完整节点。普通 `HAVING` 条件位于 `<if>...</if>` 内时，不能只移动 `HAVING` 后的文本和结束标签；SELECT 聚合别名本身位于顶层 `<if>` 时也要按完整节点识别，只有同一动态条件覆盖投影和 HAVING 引用时才可安全替换。若 `GROUP BY` 位于 `<choose>/<when>` 分支中，前移条件必须放在覆盖所有相关分支的公共位置。每条自动改写都必须先替换到原 mapper 并进行安全 XML 解析；某一语句生成 XML 失败时只回退该语句、保留其他已验证改写并输出精确人工项，不能让整份 mapper 退化或写入损坏 XML。无法证明标签边界、查询作用域和分支语义等价时保留原文并报告。
- 每个 `mapper-dm` 最终内容必须在写盘前用禁用外部 DTD/实体的安全解析器重新验证 XML 完整性。验证失败必须保留写盘前的合法文件并使命令失败；生成达梦验证测试时也必须传播损坏 mapper 的文件名和解析错误，不能返回空方法列表后继续。
- 参数推测失败时，先增强 dm-adapter 的参数推测或 `sql-rewrite.yml` 回放能力；如果参数本身是业务枚举、动态表名、动态列名或 SQL 片段，必须写入配置或标记为人工确认。
- 参数类型不匹配不能按 mapper 方法加入忽略名单。应利用表字段类型和长度元数据修正自动测试参数，日期时间列不能沿用普通字符串，单字符状态列不能沿用月份等超长占位值；`validationArgs` 中与实际列类型或长度明显不兼容的旧生成值也应在运行时纠正。真正的业务枚举或无法推断的入参仍需提供正确示例，不能靠 `typeMismatchMethods` 制造全绿。
- 达梦 `*_TAB_COLUMNS.DATA_LENGTH` 是字节长度，字符列校验必须优先使用 `CHAR_LENGTH`；在 UTF-8 等多字节字符集下把 `DATA_LENGTH` 当字符数，会放过实际超长的验证参数。已取得列元数据时，列类型和字符长度必须优先于按参数名生成的日期、月份等占位值。
- 动态 INSERT 的列和值按逗号配对时，`#{property, jdbcType=...}`、`${...}` 占位符内部的逗号不属于 SQL 列表分隔符；若不跳过，会让后半段字段与参数错位，并生成错误的类型和长度元数据。
- 动态 INSERT 可能用下一个 `<if>` 内容开头的逗号连接前一个可选值。此时不能再给前一个 `<if>` 补尾逗号，否则两个条件同时成立会生成 `?, ,?`；保留原前导逗号形态并继续标记动态 SQL 人工确认。`FROM table AND predicate` 这类缺少 `WHERE` 的原 XML 应归为 `ORIGINAL_XML_SYNTAX_DEFECT`，不能把 `AND` 引成别名后误报为普通达梦语法问题。
- `${}` 动态 SQL 需要区分三类值：
  - 动态标识符，如表名、列名、schema，需要白名单配置，验证参数不能来自任意字符串。
  - SQL 片段，如排序、条件、函数表达式，只能通过 `sql-rewrite.yml` 或安全枚举回放，不能自动拼接用户输入。
  - 已被 SQL 引号包住的字符串值，如 `'${item.code}'`，配置值应是不带外层 SQL 引号的普通字符串；如果 `${item.code}` 本身承担 SQL 片段，配置值才可能需要带引号。
- 同一个 `${...}` 日期或账期参数同时出现在 SQL 字符串字面量内外时，不能把第一次出现位置生成的带引号默认值直接复用到所有位置，否则会产生 `''2024-01-01''` 这类仅由验证器制造的语法错误。对于明确的年度首末账期参数，可在这种混合上下文中生成无引号的 `YYYYMM` 片段；其他无法确定语义的混合上下文仍应保守处理。
- 原始 SQL 中的 MySQL 用户变量赋值（例如 `@rownum := @rownum + 1`）依赖 MySQL 特有语法及求值顺序。即使失败发生在自动参数解析之后，也应归为原始业务 SQL 问题并保留人工改写提示，不能误报为方法参数或绑定问题。
- 动态表名或动态列名缺少业务入参时，验证生成的 `ID`、`test`、空字符串等占位值只用于暴露问题，不能当作真实 SQL 兼容失败。能从 mapper 上下文推断的增强工具，不能推断的写配置或跳过。
- 缺表、缺视图、缺函数、缺列等测试环境问题可以加入 validation ignore，但必须能从错误信息确认是测试库对象缺失，不应掩盖 mapper 语法错误。
- 测试库表存在但 mapper 引用的列不存在，且该列名与表中唯一一个现有列仅相差一个字符时，应报告为 `ORIGINAL_SQL_COLUMN_NAME_MISMATCH` / `ORIGINAL_SQL`，提示对照源 DDL 修正原始 mapper。没有这种强相似证据的缺列仍归为 `TEST_SCHEMA_OBJECT`，避免把尚未部署到测试库的新字段误判为原始 SQL。

## 验证和分类流程

1. 运行 Windows 环境 CLI `migrate`，读取工具侧应用工作目录（默认 `<启动目录>/.dm-adapter/<应用 artifactId>/`）中的 `sql-validation-report.md` 和 `.json`。
2. 先排除数据库连接失败；连接失败不能作为 SQL 迁移结果。
3. 对失败项按以下顺序处理：
   - dm-adapter 可通用转换：补转换规则和单元测试。
   - 原始业务 SQL 错误：修原始 mapper XML 或 Java 注解 SQL。
   - 测试库缺对象：加入 `validationIgnores.missingTables`、`missingViews`、`missingFunctions` 等配置，或要求补库对象。
   - 参数无法推断：补 `sql-rewrite.yml` 入参回放；无法自动化时标记人工配置。
   - 外部存储过程依赖：联网验证以过程编译结果和 `ALL_OBJECTS.STATUS` 为准；离线报告中的汇总警告不等同于失败。
   - `VALIDATION_TIMEOUT`：属于验证运行环境/执行时限问题，不是 SQL 兼容失败。SQL 脚本单条语句达到 `DM_SQL_SCRIPT_VALIDATION_TIMEOUT_SECONDS` 后，工具必须主动取消并停止本轮脚本验证；不能只依赖可能被驱动忽略的 JDBC `setQueryTimeout`，也不能继续复用仍有语句运行的连接。
   - SQL 脚本单条语句默认硬超时为 600 秒。真实全量脚本中同一合法数据同步过程的耗时会随测试库数据量从约 187 秒增长到超过 300 秒；项目确有更长过程时应显式配置 `DM_SQL_SCRIPT_VALIDATION_TIMEOUT_SECONDS`，不能把合法慢过程误分为 SQL 兼容失败。
   - SQL 脚本验证建立达梦连接时默认最多尝试 3 次，间隔 2 秒，并始终受本轮验证总时限约束；可用 `dm.adapter.sqlScriptConnectionAttempts` 和 `dm.adapter.sqlScriptConnectionRetryDelayMillis` 调整。短暂网络抖动不能让脚本阶段直接失败、而稍后的 Mapper 阶段又成功。脚本阶段重试耗尽、确认连接未建立时，本轮不再编译并启动 Mapper 数据库验证；项目仍以连接故障退出，数据库恢复后再重跑，避免对同一不可用连接重复等待。
   - 没有可执行语句的空脚本仍应写入逐文件验证结果，但不能为每个空文件重复切换 schema；所有 schema 已在预检阶段统一校验，空文件应直接记录为 `0/0`，避免大量占位脚本消耗总时限。
   - MyBatis mapper 验证同样不能只依赖驱动实现 `setQueryTimeout`。单条 mapper 达到 `dm.sql.validation.statementTimeoutSeconds`（默认 120 秒）后，生成的验证程序必须记录数据库语句超时、主动中止当前连接并结束本轮 mapper 验证；不得继续复用可能仍在执行 SQL 的连接。
   - mapper 参数推断所需的达梦列元数据应按 schema 批量读取 `SYS.SYSOBJECTS`/`SYS.SYSCOLUMNS`，通过 `CURRENT_SCHID()` 解析当前 schema；不要逐表扫描 `ALL_TAB_COLUMNS`，否则目录视图超时会让每轮验证固定等待并丢弃整批元数据。
   - Mapper 单条语句默认 JDBC 超时为 120 秒，可用 `dm.sql.validation.statementTimeoutSeconds` 覆盖。真实查询在 30 秒默认值下可能被误判为运行时超时；放宽后仍超时的项再按锁等待、数据库负载或执行计划问题分类。
4. 每次 dm-adapter 代码变更后执行 `mvn test`，通过后提交并推送 `main`。
5. 同步 Windows 镜像后先执行 Windows Maven `clean install`，再用 Windows CLI 对业务项目回归验证。同步保留源文件时间戳且目标目录残留旧 `target/` 时，单独执行 `install` 可能把旧 class 误判为最新，产生“部分模块用了新代码、部分模块仍是旧代码”的假回归结果。

## 当前项目优先关注模式

- `ORIGINAL_XML_SYNTAX_DEFECT`：优先判断是否原始 SQL 也有问题。常见包括 insert 列值数量不一致、动态 `<set>` 末尾逗号、非法 XML 转义。
- `ORIGINAL_XML_REQUIRED_COLUMN_OMISSION`：达梦明确报告某列违反非空约束，且原始 INSERT 的显式列清单确实漏写该列；这是原始业务 SQL 问题，不能归为测试数据或通过 ignore 掩盖。
- `TEST_SCHEMA_OBJECT`：缺表、缺视图、缺列、缺函数。确认缺失对象后可跳过；如果是 SQL 引用错列，修业务 SQL。
- `DYNAMIC_IDENTIFIER_PARAMETER` / `DYNAMIC_SQL_FRAGMENT_PARAMETER`：动态表名、列名、排序、where 片段。不能盲猜，优先白名单配置或跳过。
- `ON_DUPLICATE_KEY_UPDATE`、`INSERT_IGNORE`、`REPLACE_INTO`、`MYSQL_USER_VARIABLE`、`YEARWEEK`、未覆盖的 `PERIOD_DIFF`：MySQL 专有或语义不确定写法，除非能完整识别业务语义，否则不要自动强转。
- `BINDING_PARAMETER_NAME` / `MAPPER_PROPERTY_NAME`：通常是测试参数推测或业务对象属性名问题。能从 mapper 方法签名推断的增强工具，不能推断的写配置。
