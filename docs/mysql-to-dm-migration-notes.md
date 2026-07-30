# MySQL 到达梦迁移注意点

资料来源：

- 达梦官方文档：MySQL 到 DM，https://eco.dameng.com/document/dm/zh-cn/start/mysql_dm
- 达梦官方 FAQ：MySQL 迁移 DM8，https://eco.dameng.com/document/dm/zh-cn/faq/faq-mysql-dm8-migrate.html
- 最后核对日期：2026-07-28

本文用于指导 dm-adapter 后续处理 Spring Boot + MyBatis 项目从 MySQL 迁移到达梦 8。修改代码前先按本文区分：应由 dm-adapter 自动转换的问题、业务 SQL 本身需要修正的问题、测试库缺对象的问题、必须人工提供参数或 `sql-rewrite.yml` 配置的问题。

## 实例和模式参数

- 新建达梦实例时应按 MySQL 兼容场景确认实例参数，尤其是 `COMPATIBLE_MODE=4`、`CASE_SENSITIVE`、`LENGTH_IN_CHAR`、`BLANK_PAD_MODE`。实例参数不匹配时，SQL 转换正确也可能因为大小写、字符长度、空格比较等行为差异失败。
- `COMPATIBLE_MODE=4` 只是 MySQL 兼容模式，不代表所有 MySQL 语法都能直接执行。官方 FAQ 明确要求迁移前修改兼容参数并重启数据库服务使其生效；兼容模式下仍可能需要手工重写表、视图、游标、系统包、函数、存储过程和非法数据。
- `CASE_SENSITIVE` 是实例级参数，确定后不可随意修改。MySQL 可细到字段级大小写规则，达梦实例级大小写会同时影响对象名和数据比较；如果迁移时保留小写对象名，MyBatis SQL 往往需要双引号，若取消 DTS 的“保持对象名大小写”则对象名会转大写。
- `LENGTH_IN_CHAR` 和 DTS 类型映射会影响 `VARCHAR`/`CHAR` 长度语义。MySQL 的 `VARCHAR(n)`/`CHAR(n)` 中 `n` 是字符数；达梦 BYTE 实例若直接使用 `VARCHAR(n)`/`CHAR(n)`，中文或 emoji 可能在未达到 `n` 个字符时就超长。
- `BLANK_PAD_MODE` 会影响 `CHAR` 尾部空格补齐和比较行为。遇到字符列比较、唯一约束、迁移后数据尾部空格异常时，先确认实例参数和字段类型。
- MySQL 兼容容错参数会影响数据超长、字符串转数值、除 0 等行为。验证失败如果是“字符串转数值失败”“除数为 0”“超出列长度”，不能只从 mapper SQL 判断，需要同时看实例容错策略和测试数据。
- MySQL 的 `database` 通常迁移为达梦的 `schema`。验证 SQL 时不能默认所有对象都在当前 schema，跨库 SQL 要么映射到多个 schema，要么明确跳过缺失库表。
- SQL 脚本中的 `USE database` 只在它与对应的 `--schema`/`--system-schema` 一致时自动替换为不含库名的说明注释，实际目标 schema 由运行参数选择；不一致或未配置时保留原语句并要求显式 database-to-schema 映射，不能把来源库名或当前验证 schema 固化为 `SET SCHEMA` 输出。
- SQL 对象名中显式书写的来源库限定符，仅在它与当前脚本对应的 `--schema`/`--system-schema` 明确相同时去除，由外部执行上下文选择目标 schema；规则和输出都不能固化该配置值。未匹配的限定符可能是跨库依赖，必须保留并通过显式 database-to-schema 映射确认，不能猜测为当前 schema。
- system 脚本文件名既包括 `20260205_system.sql`，也包括历史仓库中的 `01_Update_System_Enterprise.sql`、`2024.system.sql` 等形式。只要 `system` 是由点、下划线或连字符分隔的独立文件名段，就应使用 `--system-schema`；`ecosystem.sql`、`systematic.sql` 等普通单词不能误判。
- MySQL 允许的零日期、非法日期需要在迁移前清洗。达梦不接受 `0000-00-00`、`0000-00-00 00:00:00` 这类值。

## 类型和对象差异

- MySQL `VARCHAR(n)`/`CHAR(n)` 迁移到 `LENGTH_IN_CHAR=0` 的达梦实例时，应定义为 `VARCHAR(n CHAR)`/`CHAR(n CHAR)`，保持原字符数上限。不要按 `utf8` 3 倍或 `utf8mb4` 4 倍修改 `n`：倍数扩长只能增加字节容量，还会允许写入超过 MySQL 上限的较短字节字符。达梦 DTS 的 MySQL 类型映射同样使用 `VARCHAR(n char)`/`CHAR(n char)`。
- MySQL `TEXT`、`LONGTEXT`、`JSON` 等类型迁移到达梦时通常需要映射到大字段或字符串类型，并检查业务是否依赖 MySQL JSON 函数。
- 自增列迁移后要特别处理。MySQL `AUTO_INCREMENT` 可对应达梦 `IDENTITY(start, increment)` 或迁移工具提供的 `auto_increment` 兼容方案；`IDENTITY` 自增列类型只能使用 `INT` 或 `BIGINT`。脚本里无列清单的 `INSERT INTO t VALUES(NULL, ...)` / `VALUES(DEFAULT, ...)` 如果首列明确是自增列，可省略该列和值，让达梦继续自动生成主键；显式插入具体 id 且列清单可从同脚本表定义确定时，可补列清单并用 `SET IDENTITY_INSERT ... ON/OFF` 保留种子 id。MyBatis 批量插入可能同时包含空 id 和显式 id，不能按首个元素决定整批是否保留 id；达梦 8 已验证 `SET IDENTITY_INSERT ... ON WITH REPLACE NULL` 能让空值自动生成、同时保留显式值。dm-adapter 应从语句表名和验证报告学习项目级 `identityInsertTables`，不能把具体表名写死在通用规则里。
- 触发器、函数、存储过程、视图、事件、外键、索引等对象不能只靠 mapper SQL 验证判断完整性。缺对象导致的 `无效的表或视图名`、`无效的列名`、`无法解析成员访问表达式`，优先归类为测试库对象缺失或原始 SQL 引用错误。

## SQL 语法差异

- 以 2026-06-30 对 `192.168.1.53:5236` 的验证结果为基准，dm-adapter 默认不再改写达梦 53 兼容模式已可执行的 MySQL 语法。只有验证失败、语义明显不同、或无法安全推断的 SQL 才进入自动改写或人工确认。
- MySQL 反引号标识符在达梦 53 兼容模式下可执行，默认保留。只有目标库实例参数、大小写策略或旧版本达梦验证失败时，才考虑改为达梦双引号或统一对象名大小写。动态 `${column}`、`${table}` 仍必须依赖白名单参数或 `sql-rewrite.yml`。
- MySQL 用双引号表示字符串的写法应改为单引号；达梦双引号表示标识符。转换时必须区分字符串常量、对象名、动态 `${}` 片段，不能把未知业务字符串转成对象名。
- MySQL 查询分页 `LIMIT offset,size`、`LIMIT size` 在达梦 53 兼容模式下可执行，默认保留。简单单表 `UPDATE`/`DELETE` 的无排序 `LIMIT row_count` 可把候选行限制改写为 `ROWID` 子查询和 `ROWNUM <= row_count`，保留 MySQL 未指定顺序时“任取不超过 N 行”的语义；带 `ORDER BY ... LIMIT 1` 的形态必须把排序保留在内层候选查询。含表别名、多表目标、offset 或无法解析计数的 DML 仍要人工确认。
- MySQL `LIKE #{name} '%'`、`LIKE '%' #{name}` 这类参数与字符串字面量相邻拼接在达梦 53 仍会失败，应改为 `#{name} || '%'` 形式。`#{}` 参数可以安全拼接，`${}` 动态片段必须保守处理。
- `CONCAT`、`CONCAT_WS`、`IFNULL`、`IF`、`ISNULL`、`FIND_IN_SET`、`DATE_FORMAT`、`STR_TO_DATE`、`SUBSTRING_INDEX`、两参数 `DATEDIFF`、`UNIX_TIMESTAMP`、`FROM_UNIXTIME`、`TIMESTAMPDIFF`、常见 `JSON_*` 函数在达梦 53 验证可执行，默认保留 MySQL 函数形态。
- MySQL 可在查询列中用 `(列 IS [NOT] NULL) AS 标志` 直接返回 0/1，达梦不接受这种布尔投影；工具应转换为 `CASE WHEN 列 IS [NOT] NULL THEN 1 ELSE 0 END AS 标志`，且不能改写 `WHERE` 中本来合法的空值判断。
- `NOW()` 与达梦 `SYSDATE` 在 53 环境中存在时区/时间来源差异，不能再把 `NOW()` 盲目替换为 `SYSDATE`。原始 mapper 中也应保留 MySQL 函数形态，不能把达梦函数反写到原始 MySQL XML。
- MySQL `GROUP_CONCAT(DISTINCT a, ',', b)` 这类聚合不能保留 MySQL 形态，要先把参数拼接为一个表达式，再转为达梦 `LISTAGG(DISTINCT ..., ',') WITHIN GROUP (...)`。
- MySQL 允许在 `HAVING` 中写未聚合、未列入 `GROUP BY` 的普通列条件，达梦会报“无效的 HAVING 项”。仅由 `AND` 连接、没有聚合函数、子查询、`OR` 或动态标识符的普通比较条件，应前移到同一查询作用域的 `WHERE`，聚合条件继续保留在 `HAVING`；嵌套子查询必须在各自作用域内改写。
- MySQL `REGEXP`/`NOT REGEXP` 操作符应改写为达梦 `REGEXP_LIKE`。右侧表达式如果已经是达梦可执行的 `CONCAT(...)`，不需要额外转成 `||`。
- MySQL `DATE_ADD`/`DATE_SUB`/`INTERVAL` 形式应改写为 `DATEADD`。`YEARWEEK`、无法识别的 `DATE_ADD`/`DATE_SUB` 形态、以及未被规则覆盖的 `PERIOD_DIFF` 需要人工确认；已识别的 `PERIOD_DIFF(DATE_FORMAT(...,'%Y%m'), ...)` 可转为月份差。
- MySQL `CONVERT(expr, DECIMAL(n))`、`CONVERT(expr, DECIMAL(n,m))` 应转为 `CAST(expr AS DECIMAL(...))`，不能按达梦 `CONVERT` 函数原样保留。
- MySQL `ON DUPLICATE KEY UPDATE` 不能直接在达梦执行，通常要改为 `MERGE INTO` 或业务侧先查后写。dm-adapter 不应在无法确认唯一键和更新列语义时强行转换。项目 DDL 已明确主键/唯一键、但 INSERT 列不包含任何一个完整冲突键时，原写法本身无法按预期触发冲突更新，应归为原始 SQL/键元数据冲突；普通非唯一索引不能冒充冲突键，也不能据此猜测 `keyColumns`。`column = column` 这类自赋值仅用于表达“冲突时不更新”，转换后的 `MERGE` 应省略 `WHEN MATCHED`，不能生成歧义的自赋值表达式。
- MySQL `INSERT IGNORE`、`REPLACE INTO` 需要确认唯一键、忽略冲突和替换删除语义。元数据不可用或工具无法解析 INSERT 列时，必须人工配置真实键；普通 upsert 存在多个可用唯一键时也不能猜测更新目标。如果 `INSERT IGNORE` 的所有主键/唯一键冲突都不可达（表没有主键/唯一键，或每个未显式插入的冲突键都依赖由数据库生成的自增列），`IGNORE` 对重复键语义没有作用，可安全转为普通 `INSERT`；其他 INSERT 未包含完整冲突键的情况仍归为原始 SQL/键约束冲突，不能猜测 `keyColumns`。`ON DUPLICATE KEY UPDATE` 的更新分支如果只是 `VALUES(同列)`、常量/当前时间，或目标列自身加减数值常量，可分别映射为 MERGE 的 `s.列`、原常量或 `t.列 +/- 常量`；包含其他列、子查询或混合新旧值的表达式仍须保守处理。
- `INSERT IGNORE` 的 INSERT 列同时覆盖多个主键/唯一键时，不能任取一个 `keyColumns`，必须把每个约束保存为独立 `conflictKeyGroups`，并在达梦 `MERGE ON` 中以“组内 AND、组间 OR”覆盖所有可达冲突。没有 `FROM` 的单行 `INSERT IGNORE ... SELECT 参数列表` 可按单行源转换；普通 `ON DUPLICATE KEY UPDATE` 仍不得在多个冲突键之间猜测更新目标。
- 推断冲突键时必须按项目 DDL 的执行顺序计算最终约束状态，不能只收集 `CREATE TABLE` 中曾经出现过的键。后续 `ALTER TABLE ... DROP INDEX/KEY/CONSTRAINT`（包括被调用的迁移过程内语句）必须移除旧唯一键，后续新增的主键/唯一键和独立 `CREATE UNIQUE INDEX` 必须纳入；普通 `ADD INDEX` 仍不属于冲突键。项目中存在完整建表历史时，其最终状态优先于可能未同步的达梦测试库键元数据。已持久化的 `conflictKeyGroups` 若与当前 DDL 中所有可达冲突键不再一致，必须丢弃并重新推断，避免历史键删除后仍生成错误 `MERGE`。
- MySQL `UPDATE ... JOIN ... SET ...` 只更新一个表别名且目标达梦可执行时可以保留；同时更新多个别名时，达梦会报“多表更新时仅支持更新同一个表上的列”。拆分语句必须保持原 JOIN 匹配快照，不能让第一条 UPDATE 改掉后续语句仍依赖的谓词。若只有一个目标会修改匹配谓词且各目标右值不依赖其他目标被修改的列，可按“未改谓词的目标在前、改谓词的目标最后”生成达梦匿名块；若两个目标都会修改谓词，但能证明主表 `ID` 由方法参数唯一绑定、JOIN 将该 ID 映射到从表外键，则先更新主表，并用 `IF SQL%ROWCOUNT > 0 THEN` 和推导出的从表外键条件更新从表。表名、别名、列名和参数都必须从原 SQL 提取，不能写死项目值；无法证明等价时保留原 SQL 并报告，不能盲目顺序拆分。
- `UPDATE ... JOIN` 后接 MyBatis `<where>` 时，应把 JOIN 谓词放进同一个 `<where>`，再保留原有静态或纯动态 `<if>/<foreach>` 条件，不能先生成普通 `WHERE` 再留下第二个动态 `WHERE`。即使所有原条件都位于动态标签内，JOIN 谓词也必须作为无条件首项保留，保证动态条件为空时仍不会扩大更新范围。
- MySQL 用户变量和累加写法如 `@rownum := @rownum + 1` 不能直接迁移，通常改为达梦窗口函数 `ROW_NUMBER() OVER (...)`，或在存储过程/业务代码中显式声明变量。
- 同一查询中多个用户变量赋值彼此引用时，原 SQL 依赖 MySQL 不稳定的表达式求值顺序；不能把这种状态机直接猜成达梦 SQL。报告必须指出涉及的变量和原始求值顺序风险，并要求用明确排序的窗口函数、gaps-and-islands 查询重写，或由业务方提供预期分组语义。
- 对 `GROUP_CONCAT`/`FIND_IN_SET` 配合用户变量累积父级或子级 ID 的 MyBatis 层级遍历，只有在游标变量、起点参数、ID/父 ID 列、三处来源表和输出过滤关系全部一致时，才可改写为达梦 `START WITH ... CONNECT BY NOCYCLE`。表名、列名、别名和参数必须从原 SQL 提取，不能写死项目库名或模式名；父级遍历须保留起点，子级遍历须按原语义决定是否包含起点，额外租户过滤和排序也必须保留。
- MySQL `CREATE TABLE ... COMMENT '...'`、列级 `COMMENT '...'`、`ENGINE`、`USING BTREE` 等 DDL 选项要从迁移 SQL 中移除或改写。表/列注释如需保留，应后续生成达梦 `COMMENT ON` 语句，不应留在建表语句内。
- `CREATE TABLE` 中普通/唯一的 MySQL 内联 `KEY` / `INDEX` 必须提取为带 `ALL_INDEXES` 存在性保护的达梦 `CREATE [UNIQUE] INDEX`，并按表名生成 schema 范围唯一的索引名；前缀索引转函数索引。`FULLTEXT`、`SPATIAL` 或无法确认等价性的表达式索引不得静默删除，必须保留原 SQL 并报告人工设计索引语义。
- 同一列定义出现两个或更多 `DEFAULT` 子句时，原始 MySQL DDL 本身存在互相冲突的默认值，工具不能替业务选择保留哪一个。数据库验证应将其归为 `ORIGINAL_SQL`，保留转换结果并要求修正源脚本，不能误报为待补充的达梦转换规则。
- MySQL `AUTO_INCREMENT` 和 `ALTER TABLE t AUTO_INCREMENT = n` 默认不再为了验证而改写。目标环境如果不支持或业务依赖重置序列语义，应按达梦身份列/序列方案人工确认，不能把 `AUTO_INCREMENT = n` 删除后留下半截 `ALTER TABLE`。
- MySQL `ON UPDATE CURRENT_TIMESTAMP` 在达梦 53 环境验证失败，但达梦 53 支持 `ON UPDATE NOW()` 列属性，且无需触发器即可在更新普通列时自动刷新时间列。默认应把 `ON UPDATE CURRENT_TIMESTAMP` 改为 `ON UPDATE NOW()`；只有目标达梦版本不支持 `ON UPDATE` 时，才退回触发器或应用 SQL 维护更新时间。
- MySQL `information_schema.TABLES/COLUMNS` 不应原样迁移。表存在性检查可映射到 `ALL_TABLES`，列清单可映射到 `ALL_TAB_COLUMNS`，需要创建时间或 schema 名的表详情可映射到 `ALL_OBJECTS`，并按当前 schema 过滤。业务代码同时读取 `COLUMN_TYPE`、注释和默认值时，可从 `SYS.SYSCOLUMNS`、`SYS.SYSOBJECTS`、`SYS.SYSCOLUMNCOMMENTS` 按运行时 schema 和表名重建相同投影；不得把某个项目的 schema 固化到转换规则。
- `AES_ENCRYPT`、`AES_DECRYPT`、`MD5`、`TO_BASE64` 等加密/编码函数要逐项确认。当前不再把 Base64 包裹 AES 密码场景改写为达梦 `SF_*` 函数，优先通过系统库兼容函数保持 MySQL 调用形态，避免修改业务 SQL。
- MySQL `/` 具有小数除法语义，达梦的整数/整数可能先截断；对可完整识别的数值、列、日期数值函数、聚合和无子查询括号表达式，应把分子转为 `DECIMAL(38,10)`，并把分母转为 `NULLIF(CAST(... AS DECIMAL(38,10)), 0)`。分母为 0 的容错和达梦参数相关，不能依赖实例容错；无法完整识别表达式边界时仍须人工确认。
- SQL Server 风格 `+` 只能在一侧是字符串字面量、`CONCAT`，或 `CAST/CONVERT` 明确声明字符返回类型时改为达梦 `||`。`CAST(... AS DECIMAL)`、`CONVERT(..., DECIMAL)` 等数值结果之间的 `+` 必须保留算术加法，不能仅凭函数名推断为字符串拼接。
- MySQL 中常见 `SUM(varchar_col)` 依赖隐式转换，达梦可能报类型转换失败。应优先修业务 SQL，显式 `CAST` 且清洗非数字数据。

## 存储过程、函数和触发器

- 达梦官方迁移流程强调：DTS 可以迁移部分对象，但 MySQL 与 DM 在存储过程、函数、触发器语法上差异明显，迁移失败或自动转换后仍不兼容时要按 DM 语法人工重建。
- MySQL 存储过程中的局部变量、游标、异常处理、临时表、动态 SQL、函数调用不能直接照搬。dm-adapter 当前主要处理 MyBatis mapper SQL，遇到存储过程/函数/触发器调用失败，应先判断目标库是否已创建等价对象。
- MySQL `CONTINUE HANDLER FOR SQLSTATE '02000'`/`NOT FOUND` 仅作为游标结束标志，且能由完整的 `OPEN`、`FETCH`、循环退出和 `CLOSE` 结构证明语义等价时，可自动改写为达梦游标循环与 `cursor%NOTFOUND`；结构关键字之间的空白和 SQL 注释不应阻止识别。以游标目标为 NULL 哨兵的写法，只有在循环内每个可能无结果的 `SELECT ... INTO` 都先把同一目标置为 NULL、并可等价改成保留 NULL 语义的标量子查询时才能自动转换。处理标志还有业务用途，或同一处理器还可能捕获循环体内其他可能无结果的 `SELECT ... INTO` 时，原 SQL 会提前结束循环并漏处理后续游标数据，应归为原始 SQL 逻辑缺陷，不能只删除处理器。
- 脚本反复 `DROP`、重建并调用同名存储过程时，人工确认状态必须按执行顺序跟随当前过程版本；后续成功转换的 `CREATE PROCEDURE` 会覆盖前一人工版本，不能仅按过程名把后续 `CALL` 永久连带标记。
- 仅用于一次性迁移、满足“`DROP PROCEDURE IF EXISTS` → 无参 `CREATE PROCEDURE` → 无参 `CALL` → 同名 `DROP PROCEDURE IF EXISTS`”连续完整闭环的临时过程，可等价折叠为达梦匿名块。这样既不留下过程对象，也避免大量过程 DDL 带来的验证和部署耗时；同名过程后续出现新的独立完整闭环时，可按生命周期分别折叠。单个生命周期内被多次调用、带参数、缺少完整闭环、包含人工确认语句，或闭环中间还有其他对象生命周期时不得折叠。
- Java 或 mapper 中调用业务自定义函数时，如果达梦测试库缺函数，应归类为测试库缺对象；如果函数存在但签名或返回类型不同，应归类为业务对象迁移问题，不能通过 SQL 字符串替换掩盖。
- SQL 脚本中的存储过程依赖必须按目标 schema 判断。当前迁移队列内尚未创建就被引用属于脚本顺序问题；当前队列未定义的过程可能是系统库预置的共享依赖，不能仅凭当前项目缺少其 `CREATE PROCEDURE` 就判定对象缺失。
- 多个业务仓库共同调用、但不属于各仓库升级脚本的系统共享过程，应先在系统库的 output-only 基础脚本（例如 `sql-root-out/00000000.sql`）中人工维护等价的达梦实现，再验证依赖仓库。迁移器必须保留这类只存在于输出目录的脚本，不能在重新生成日期脚本时覆盖；过程实现属于项目初始化资产，不能把过程名、schema 或业务表写进通用转换规则。
- 共享过程不能以“文件里已有 `CREATE PROCEDURE`”作为完成依据。应先单独创建或编译目标过程，确认实际目标 schema、`ALL_OBJECTS.STATUS` 和编译错误，再用回滚事务或幂等样例验证必填入参、插入、更新和重复调用语义。数据库在 JDBC 握手阶段超时时，应归为环境连接故障；只有成功建立会话并进入对应 DDL 后的错误，才能归因到过程 SQL。
- 真实全量脚本耗时较长时，不应在每轮工具修改后立即重跑全部仓库。先选择系统库、共享过程依赖覆盖最全的仓库、最近暴露规则缺陷的仓库和至少一个正常基线仓库做代表性真实回归；这些项目的脚本与 MyBatis 均通过且已检查语义后，再基于最终候选版本运行一次全量。
- 升级脚本可能由 Windows 工具保存为带 BOM 的 UTF-16LE/UTF-16BE，而不只是 UTF-8。读取时应按 BOM 解码，迁移输出统一写为 UTF-8；不能因固定按 UTF-8 读取而在全量执行中途抛出 `MalformedInputException`。
- 脚本级 `SET @变量`、`FOREIGN_KEY_CHECKS`、`PREPARE` 等 MySQL 控制语句被安全消解为审计注释时，写盘 SQL 必须同时生成可执行的达梦匿名 `NULL` 空操作块，使迁移结果、输出脚本和严格验证计划仍保持逐条对应。不能因解析器忽略纯注释而产生语句计数漂移，也不能因此跳过数据库验证。
- 脚本级 `SELECT ... INTO @变量`、多列 `SELECT ... INTO @变量1,@变量2` 和 `SET @变量=(SELECT ...)` 若能证明查询来源表在最后一次引用前未被修改，可把查询转为达梦标量子查询并内联到后续普通 SQL 或临时过程中；未被使用的赋值可安全消解。来源表在引用区间内发生修改、查询仍依赖未解析用户变量或无法证明标量语义时必须保留人工确认，不能把“每次重新查询”冒充 MySQL 的赋值快照。
- MyBatis 语句包含 `<if>`、`<foreach>`、`<include>`、`<where>` 等 XML 节点本身不是人工确认理由。只有转换后仍检测到明确的未解决兼容风险（例如未改写的 `ON DUPLICATE KEY UPDATE`、无法安全拼接的动态 `UPDATE JOIN` 或未支持的函数）才进入人工确认；成功改写后还必须移除由原始片段产生、但在最终 SQL 中已不存在的过期风险项。不能只按 `<insert>`、`<update>`、`<delete>` 标签决定转换类型，历史 mapper 可能在 `<delete>` 中实际书写 `UPDATE`，必须以 SQL 首关键字为准。
- 临时迁移过程中的元数据检查不能把 CLI `--schema` 固化到输出脚本，也不能在过程内部用 `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')` 推断。达梦执行存储过程时该上下文可能变为过程定义者的默认 schema；应以 `CURRENT_SCHID` 作为当前模式的运行时依据。仅含当前模式、表名和列名的列存在性检查，可改写为 `SYS.SYSOBJECTS` 与 `SYS.SYSCOLUMNS` 的窄查询，并用原始名称与其大写形式的等值候选匹配，避免在字典列上调用 `UPPER()` 导致全量扫描；含类型、长度、可空性等附加条件的复杂检查仍使用 `SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)` 配合 `ALL_TAB_COLUMNS`。两种输出都不得固化 CLI schema。
- 存储过程通过动态 DDL 修改对象后，后续静态 SQL 继续访问同一对象可能因编译期对象不存在、列定义变化或执行计划版本失效而报对象无效或 `-7184`。应按执行顺序判断：静态访问全部发生在最终 DDL 之前时无需改写；DDL 后的 `UPDATE`、`INSERT`、`DELETE`、`MERGE` 和 `SELECT ... INTO` 可整体改为 `EXECUTE IMMEDIATE`。其中可明确识别的过程参数和局部变量输入应改为 `?` 并按出现顺序生成 `USING` 绑定，重复引用同一变量时也要重复传入；DML 目标列和 `SELECT ... INTO` 输出变量不能误当输入绑定。动态对象名、循环或无法可靠解析的控制流仍保留原 SQL 并进入人工确认，不能只改写其中一部分。
- 扫描 `EXECUTE IMMEDIATE` 内的长 SQL 字面量时不能使用逐字符递归回溯的正则表达式；真实升级脚本可能在较小 JVM 线程栈上触发 `StackOverflowError`。应按引号和注释边界线性扫描，再对提取出的 DDL 做精确匹配。
- 启用数据库验证时，外部共享过程依赖交给达梦实际编译和创建后对象状态检查确认。dry-run、关闭验证或验证基础设施不可用时，只在报告中按 schema 汇总“尚未验证”的外部过程依赖，不应将其伪装成人工确认 SQL。

## MyBatis 迁移判断准则

- 原始 mapper XML 不由 dm-adapter 迁移流程覆盖；自动迁移输出保持在 `src/main/resources/mapper-dm`。
- MySQL `LEFT JOIN` 更新若右表列参与赋值，不能直接降级成达梦 `UPDATE ... FROM`，因为无匹配行和重复来源行的语义会变化。只有项目 DDL 的真实主键或唯一键被 `ON` 条件完整绑定，或右侧派生表按连接列 `GROUP BY` 已直接证明每个键最多一行，且赋值形态可等价表示时，才可把右表表达式改为相关标量子查询；`WHERE` 中引用右表的 `AND` 条件必须同时下推到标量子查询并在外层补等价 `EXISTS`，不能留下失效的右表别名。动态 `<foreach>` 只是外层过滤条件时应原样保留，不应阻止上述转换。赋值完全不依赖右表时，可用 `EXISTS` 保留匹配过滤语义，不需要唯一性证明。`IF(IFNULL(右表非空主键, 哨兵)=哨兵, 未匹配值, 匹配值)` 只表达右表是否存在时，即使连接列不唯一，也可按真实非空主键改为 `CASE WHEN EXISTS`，因为重复来源不会改变结果；不得把普通可空列误作存在性证明。缺少必要的唯一性证明时应准确报告约束风险，不能任选一条来源记录。
- 原始 SQL 明显错误时，可以修原始业务 SQL。例如列名写错、insert 列和值数量不一致、`set` 末尾多逗号、把 `UPDATE table` 写成 `UPDATE FROM table`。这类错误即使后续通用规则引用了达梦关键字，也仍应按原始 XML 语法缺陷分类。若问题来自 Java mapper 方法签名，如多个参数复用同一个 `@Param` 名称，或多个简单参数缺少必要 `@Param`，应修 Java mapper 方法签名，不应为了绕过绑定错误去改 XML 参数名。
- Java 注解里的 SQL 如果包含复杂动态 SQL、MySQL 专有语法或需要达梦改写，应优先迁移到 mapper XML，再由 dm-adapter 生成 `mapper-dm`；自动迁移也应把可识别的 `@Select`、`@Insert`、`@Update`、`@Delete` SQL 提取到 `mapper-dm` XML 后再执行达梦改写和验证。
- mapper XML 可能带 UTF-8 BOM。解析器必须按字节流交给 XML 解析器识别 BOM；不能把 BOM 作为正文字符传入 `Reader`，否则整份文件会退化为“无法安全解析”，后续动态 SQL 结构转换将被跳过。
- 参数推测失败时，先增强 dm-adapter 的参数推测或 `sql-rewrite.yml` 回放能力；如果参数本身是业务枚举、动态表名、动态列名或 SQL 片段，必须写入配置或标记为人工确认。
- 参数类型不匹配不能按 mapper 方法加入忽略名单。应利用表字段类型和长度元数据修正自动测试参数，日期时间列不能沿用普通字符串，单字符状态列不能沿用月份等超长占位值；`validationArgs` 中与实际列类型或长度明显不兼容的旧生成值也应在运行时纠正。真正的业务枚举或无法推断的入参仍需提供正确示例，不能靠 `typeMismatchMethods` 制造全绿。
- 达梦 `*_TAB_COLUMNS.DATA_LENGTH` 是字节长度，字符列校验必须优先使用 `CHAR_LENGTH`；在 UTF-8 等多字节字符集下把 `DATA_LENGTH` 当字符数，会放过实际超长的验证参数。已取得列元数据时，列类型和字符长度必须优先于按参数名生成的日期、月份等占位值。
- 动态 INSERT 的列和值按逗号配对时，`#{property, jdbcType=...}`、`${...}` 占位符内部的逗号不属于 SQL 列表分隔符；若不跳过，会让后半段字段与参数错位，并生成错误的类型和长度元数据。
- 动态 INSERT 可能用下一个 `<if>` 内容开头的逗号连接前一个可选值。此时不能再给前一个 `<if>` 补尾逗号，否则两个条件同时成立会生成 `?, ,?`；保留原前导逗号形态并继续标记动态 SQL 人工确认。`FROM table AND predicate` 这类缺少 `WHERE` 的原 XML 应归为 `ORIGINAL_XML_SYNTAX_DEFECT`，不能把 `AND` 引成别名后误报为普通达梦语法问题。
- `${}` 动态 SQL 需要区分三类值：
  - 动态标识符，如表名、列名、schema，需要白名单配置，验证参数不能来自任意字符串。
  - SQL 片段，如排序、条件、函数表达式，只能通过 `sql-rewrite.yml` 或安全枚举回放，不能自动拼接用户输入。
  - 已被 SQL 引号包住的字符串值，如 `'${item.code}'`，配置值应是不带外层 SQL 引号的普通字符串；如果 `${item.code}` 本身承担 SQL 片段，配置值才可能需要带引号。
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
