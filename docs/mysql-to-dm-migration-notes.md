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
- MySQL 允许的零日期、非法日期需要在迁移前清洗。达梦不接受 `0000-00-00`、`0000-00-00 00:00:00` 这类值。

## 类型和对象差异

- MySQL `VARCHAR(n)`/`CHAR(n)` 迁移到 `LENGTH_IN_CHAR=0` 的达梦实例时，应定义为 `VARCHAR(n CHAR)`/`CHAR(n CHAR)`，保持原字符数上限。不要按 `utf8` 3 倍或 `utf8mb4` 4 倍修改 `n`：倍数扩长只能增加字节容量，还会允许写入超过 MySQL 上限的较短字节字符。达梦 DTS 的 MySQL 类型映射同样使用 `VARCHAR(n char)`/`CHAR(n char)`。
- MySQL `TEXT`、`LONGTEXT`、`JSON` 等类型迁移到达梦时通常需要映射到大字段或字符串类型，并检查业务是否依赖 MySQL JSON 函数。
- 自增列迁移后要特别处理。MySQL `AUTO_INCREMENT` 可对应达梦 `IDENTITY(start, increment)` 或迁移工具提供的 `auto_increment` 兼容方案；`IDENTITY` 自增列类型只能使用 `INT` 或 `BIGINT`。脚本里无列清单的 `INSERT INTO t VALUES(NULL, ...)` / `VALUES(DEFAULT, ...)` 如果首列明确是自增列，可省略该列和值，让达梦继续自动生成主键；显式插入具体 id 且列清单可从同脚本表定义确定时，可补列清单并用 `SET IDENTITY_INSERT ... ON/OFF` 保留种子 id。验证中出现“仅当指定列列表，且 SET IDENTITY_INSERT 为 ON 时，才能对自增列赋值”时，通常不是 SQL 语法转换问题，而是测试数据、表结构或业务插入策略问题。
- 触发器、函数、存储过程、视图、事件、外键、索引等对象不能只靠 mapper SQL 验证判断完整性。缺对象导致的 `无效的表或视图名`、`无效的列名`、`无法解析成员访问表达式`，优先归类为测试库对象缺失或原始 SQL 引用错误。

## SQL 语法差异

- 以 2026-06-30 对 `192.168.1.53:5236` 的验证结果为基准，dm-adapter 默认不再改写达梦 53 兼容模式已可执行的 MySQL 语法。只有验证失败、语义明显不同、或无法安全推断的 SQL 才进入自动改写或人工确认。
- MySQL 反引号标识符在达梦 53 兼容模式下可执行，默认保留。只有目标库实例参数、大小写策略或旧版本达梦验证失败时，才考虑改为达梦双引号或统一对象名大小写。动态 `${column}`、`${table}` 仍必须依赖白名单参数或 `sql-rewrite.yml`。
- MySQL 用双引号表示字符串的写法应改为单引号；达梦双引号表示标识符。转换时必须区分字符串常量、对象名、动态 `${}` 片段，不能把未知业务字符串转成对象名。
- MySQL 查询分页 `LIMIT offset,size`、`LIMIT size` 在达梦 53 兼容模式下可执行，默认保留。`UPDATE`/`DELETE` 等非查询 DML 上的 `LIMIT` 仍要人工确认；当前仅对已识别的 `UPDATE ... ORDER BY ... LIMIT 1` 做等价改写。
- MySQL `LIKE #{name} '%'`、`LIKE '%' #{name}` 这类参数与字符串字面量相邻拼接在达梦 53 仍会失败，应改为 `#{name} || '%'` 形式。`#{}` 参数可以安全拼接，`${}` 动态片段必须保守处理。
- `CONCAT`、`CONCAT_WS`、`IFNULL`、`IF`、`ISNULL`、`FIND_IN_SET`、`DATE_FORMAT`、`STR_TO_DATE`、`SUBSTRING_INDEX`、两参数 `DATEDIFF`、`UNIX_TIMESTAMP`、`FROM_UNIXTIME`、`TIMESTAMPDIFF`、常见 `JSON_*` 函数在达梦 53 验证可执行，默认保留 MySQL 函数形态。
- `NOW()` 与达梦 `SYSDATE` 在 53 环境中存在时区/时间来源差异，不能再把 `NOW()` 盲目替换为 `SYSDATE`。原始 mapper 中也应保留 MySQL 函数形态，不能把达梦函数反写到原始 MySQL XML。
- MySQL `GROUP_CONCAT(DISTINCT a, ',', b)` 这类聚合不能保留 MySQL 形态，要先把参数拼接为一个表达式，再转为达梦 `LISTAGG(DISTINCT ..., ',') WITHIN GROUP (...)`。
- MySQL `REGEXP`/`NOT REGEXP` 操作符应改写为达梦 `REGEXP_LIKE`。右侧表达式如果已经是达梦可执行的 `CONCAT(...)`，不需要额外转成 `||`。
- MySQL `DATE_ADD`/`DATE_SUB`/`INTERVAL` 形式应改写为 `DATEADD`。`YEARWEEK`、无法识别的 `DATE_ADD`/`DATE_SUB` 形态、以及未被规则覆盖的 `PERIOD_DIFF` 需要人工确认；已识别的 `PERIOD_DIFF(DATE_FORMAT(...,'%Y%m'), ...)` 可转为月份差。
- MySQL `CONVERT(expr, DECIMAL(n))`、`CONVERT(expr, DECIMAL(n,m))` 应转为 `CAST(expr AS DECIMAL(...))`，不能按达梦 `CONVERT` 函数原样保留。
- MySQL `ON DUPLICATE KEY UPDATE` 不能直接在达梦执行，通常要改为 `MERGE INTO` 或业务侧先查后写。dm-adapter 不应在无法确认唯一键和更新列语义时强行转换。项目 DDL 已明确主键/唯一键、但 INSERT 列不包含任何一个完整冲突键时，原写法本身无法按预期触发冲突更新，应归为原始 SQL/键元数据冲突；普通非唯一索引不能冒充冲突键，也不能据此猜测 `keyColumns`。`column = column` 这类自赋值仅用于表达“冲突时不更新”，转换后的 `MERGE` 应省略 `WHEN MATCHED`，不能生成歧义的自赋值表达式。
- MySQL `INSERT IGNORE`、`REPLACE INTO` 需要确认唯一键、忽略冲突和替换删除语义。达梦元数据存在多个可用唯一键、元数据不可用或工具无法解析 INSERT 列时，必须人工配置真实 `keyColumns`；表不存在可用主键/唯一键，或 INSERT 未包含任何完整冲突键时，原写法本身无法表达预期的冲突忽略语义，应归类为原始 SQL/键约束冲突，不能猜测 `keyColumns`。
- MySQL `UPDATE ... JOIN ... SET ...` 在达梦 53 兼容模式下可执行，默认保留，不再自动改写为 `UPDATE FROM`。如果目标环境验证失败，或存在多目标更新、触发器副作用、行数语义差异，再按业务 SQL 人工处理。
- MySQL 用户变量和累加写法如 `@rownum := @rownum + 1` 不能直接迁移，通常改为达梦窗口函数 `ROW_NUMBER() OVER (...)`，或在存储过程/业务代码中显式声明变量。
- MySQL `CREATE TABLE ... COMMENT '...'`、列级 `COMMENT '...'`、`ENGINE`、`USING BTREE` 等 DDL 选项要从迁移 SQL 中移除或改写。表/列注释如需保留，应后续生成达梦 `COMMENT ON` 语句，不应留在建表语句内。
- 同一列定义出现两个或更多 `DEFAULT` 子句时，原始 MySQL DDL 本身存在互相冲突的默认值，工具不能替业务选择保留哪一个。数据库验证应将其归为 `ORIGINAL_SQL`，保留转换结果并要求修正源脚本，不能误报为待补充的达梦转换规则。
- MySQL `AUTO_INCREMENT` 和 `ALTER TABLE t AUTO_INCREMENT = n` 默认不再为了验证而改写。目标环境如果不支持或业务依赖重置序列语义，应按达梦身份列/序列方案人工确认，不能把 `AUTO_INCREMENT = n` 删除后留下半截 `ALTER TABLE`。
- MySQL `ON UPDATE CURRENT_TIMESTAMP` 在达梦 53 环境验证失败，但达梦 53 支持 `ON UPDATE NOW()` 列属性，且无需触发器即可在更新普通列时自动刷新时间列。默认应把 `ON UPDATE CURRENT_TIMESTAMP` 改为 `ON UPDATE NOW()`；只有目标达梦版本不支持 `ON UPDATE` 时，才退回触发器或应用 SQL 维护更新时间。
- MySQL `information_schema.TABLES/COLUMNS` 不应原样迁移。表存在性检查可映射到 `ALL_TABLES`，列清单可映射到 `ALL_TAB_COLUMNS`，需要创建时间或 schema 名的表详情可映射到 `ALL_OBJECTS`，并按当前 schema 过滤。
- `AES_ENCRYPT`、`AES_DECRYPT`、`MD5`、`TO_BASE64` 等加密/编码函数要逐项确认。当前不再把 Base64 包裹 AES 密码场景改写为达梦 `SF_*` 函数，优先通过系统库兼容函数保持 MySQL 调用形态，避免修改业务 SQL。
- MySQL 除法在分母为 0 时的容错和达梦参数相关。业务 SQL 如直接 `a / b`，应优先改为 `a / NULLIF(b, 0)` 或 `CASE WHEN b = 0 THEN ...`，不要依赖实例容错。
- MySQL 中常见 `SUM(varchar_col)` 依赖隐式转换，达梦可能报类型转换失败。应优先修业务 SQL，显式 `CAST` 且清洗非数字数据。

## 存储过程、函数和触发器

- 达梦官方迁移流程强调：DTS 可以迁移部分对象，但 MySQL 与 DM 在存储过程、函数、触发器语法上差异明显，迁移失败或自动转换后仍不兼容时要按 DM 语法人工重建。
- MySQL 存储过程中的局部变量、游标、异常处理、临时表、动态 SQL、函数调用不能直接照搬。dm-adapter 当前主要处理 MyBatis mapper SQL，遇到存储过程/函数/触发器调用失败，应先判断目标库是否已创建等价对象。
- MySQL `CONTINUE HANDLER FOR SQLSTATE '02000'`/`NOT FOUND` 仅作为游标结束标志，且能由完整的 `OPEN`、`FETCH`、循环退出和 `CLOSE` 结构证明语义等价时，可自动改写为达梦游标循环与 `cursor%NOTFOUND`；结构关键字之间的空白和 SQL 注释不应阻止识别。以游标目标为 NULL 哨兵的写法，只有在循环内每个可能无结果的 `SELECT ... INTO` 都先把同一目标置为 NULL、并可等价改成保留 NULL 语义的标量子查询时才能自动转换。处理标志还有业务用途，或同一处理器还可能捕获循环体内其他可能无结果的 `SELECT ... INTO` 时，原 SQL 会提前结束循环并漏处理后续游标数据，应归为原始 SQL 逻辑缺陷，不能只删除处理器。
- 脚本反复 `DROP`、重建并调用同名存储过程时，人工确认状态必须按执行顺序跟随当前过程版本；后续成功转换的 `CREATE PROCEDURE` 会覆盖前一人工版本，不能仅按过程名把后续 `CALL` 永久连带标记。
- Java 或 mapper 中调用业务自定义函数时，如果达梦测试库缺函数，应归类为测试库缺对象；如果函数存在但签名或返回类型不同，应归类为业务对象迁移问题，不能通过 SQL 字符串替换掩盖。
- SQL 脚本中的存储过程依赖必须按目标 schema 判断。当前迁移队列内尚未创建就被引用属于脚本顺序问题；当前队列未定义的过程可能是系统库预置的共享依赖，不能仅凭当前项目缺少其 `CREATE PROCEDURE` 就判定对象缺失。
- 脚本级 `SET @变量`、`FOREIGN_KEY_CHECKS`、`PREPARE` 等 MySQL 控制语句被安全消解为审计注释时，写盘 SQL 必须同时生成可执行的达梦匿名 `NULL` 空操作块，使迁移结果、输出脚本和严格验证计划仍保持逐条对应。不能因解析器忽略纯注释而产生语句计数漂移，也不能因此跳过数据库验证。
- 临时迁移过程中的元数据检查不能把 CLI `--schema` 固化到输出脚本，也不能在过程内部用 `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')` 推断。达梦执行存储过程时该上下文可能变为过程定义者的默认 schema；应使用 `SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)` 在运行时解析当前 schema，再用于 `ALL_TAB_COLUMNS`、`ALL_IND_COLUMNS` 等存在性检查。该行为已用不同登录用户、当前 schema 和过程所有者的 DM8 容器场景验证。
- 存储过程通过动态 DDL 修改对象后，后续静态 SQL 继续访问同一对象可能因编译期对象不存在、列定义变化或执行计划版本失效而报对象无效或 `-7184`。应按执行顺序判断：静态访问全部发生在最终 DDL 之前时无需改写；DDL 后无输入变量的 `UPDATE`、`INSERT`、`DELETE`、`MERGE` 和 `SELECT ... INTO` 可整体改为 `EXECUTE IMMEDIATE`。涉及过程参数、局部变量输入、动态对象名、循环或无法可靠解析的控制流时保留原 SQL 并进入人工确认，不能只改写其中一部分。
- 启用数据库验证时，外部共享过程依赖交给达梦实际编译和创建后对象状态检查确认。dry-run、关闭验证或验证基础设施不可用时，只在报告中按 schema 汇总“尚未验证”的外部过程依赖，不应将其伪装成人工确认 SQL。

## MyBatis 迁移判断准则

- 原始 mapper XML 不由 dm-adapter 迁移流程覆盖；自动迁移输出保持在 `src/main/resources/mapper-dm`。
- 原始 SQL 明显错误时，可以修原始业务 SQL。例如列名写错、insert 列和值数量不一致、`set` 末尾多逗号。若问题来自 Java mapper 方法签名，如多个参数复用同一个 `@Param` 名称，或多个简单参数缺少必要 `@Param`，应修 Java mapper 方法签名，不应为了绕过绑定错误去改 XML 参数名。
- Java 注解里的 SQL 如果包含复杂动态 SQL、MySQL 专有语法或需要达梦改写，应优先迁移到 mapper XML，再由 dm-adapter 生成 `mapper-dm`；自动迁移也应把可识别的 `@Select`、`@Insert`、`@Update`、`@Delete` SQL 提取到 `mapper-dm` XML 后再执行达梦改写和验证。
- 参数推测失败时，先增强 dm-adapter 的参数推测或 `sql-rewrite.yml` 回放能力；如果参数本身是业务枚举、动态表名、动态列名或 SQL 片段，必须写入配置或标记为人工确认。
- 参数类型不匹配不能按 mapper 方法加入忽略名单。应利用表字段元数据修正自动测试参数，或在 `validationArgs` 中提供类型正确的业务示例；否则必须保留失败，不能靠 `typeMismatchMethods` 制造全绿。
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
   - SQL 脚本单条语句默认硬超时为 300 秒。该值需要覆盖已观测到约 187 秒的合法数据初始化过程；项目确有更长过程时应显式配置 `DM_SQL_SCRIPT_VALIDATION_TIMEOUT_SECONDS`，不能把合法慢过程误分为 SQL 兼容失败。
4. 每次 dm-adapter 代码变更后执行 `mvn test`，通过后提交并推送 `main`。
5. 同步 Windows 镜像，执行 Windows Maven 构建，再用 Windows CLI 对业务项目回归验证。

## 当前项目优先关注模式

- `ORIGINAL_XML_SYNTAX_DEFECT`：优先判断是否原始 SQL 也有问题。常见包括 insert 列值数量不一致、动态 `<set>` 末尾逗号、非法 XML 转义。
- `ORIGINAL_XML_REQUIRED_COLUMN_OMISSION`：达梦明确报告某列违反非空约束，且原始 INSERT 的显式列清单确实漏写该列；这是原始业务 SQL 问题，不能归为测试数据或通过 ignore 掩盖。
- `TEST_SCHEMA_OBJECT`：缺表、缺视图、缺列、缺函数。确认缺失对象后可跳过；如果是 SQL 引用错列，修业务 SQL。
- `DYNAMIC_IDENTIFIER_PARAMETER` / `DYNAMIC_SQL_FRAGMENT_PARAMETER`：动态表名、列名、排序、where 片段。不能盲猜，优先白名单配置或跳过。
- `ON_DUPLICATE_KEY_UPDATE`、`INSERT_IGNORE`、`REPLACE_INTO`、`MYSQL_USER_VARIABLE`、`YEARWEEK`、未覆盖的 `PERIOD_DIFF`：MySQL 专有或语义不确定写法，除非能完整识别业务语义，否则不要自动强转。
- `BINDING_PARAMETER_NAME` / `MAPPER_PROPERTY_NAME`：通常是测试参数推测或业务对象属性名问题。能从 mapper 方法签名推断的增强工具，不能推断的写配置。
