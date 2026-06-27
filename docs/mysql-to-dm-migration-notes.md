# MySQL 到达梦迁移注意点

资料来源：

- 达梦官方文档：MySQL 到 DM，https://eco.dameng.com/document/dm/zh-cn/start/mysql_dm
- 达梦官方 FAQ：MySQL 迁移 DM8，https://eco.dameng.com/document/dm/zh-cn/faq/faq-mysql-dm8-migrate.html
- 最后核对日期：2026-06-27

本文用于指导 dm-adapter 后续处理 Spring Boot + MyBatis 项目从 MySQL 迁移到达梦 8。修改代码前先按本文区分：应由 dm-adapter 自动转换的问题、业务 SQL 本身需要修正的问题、测试库缺对象的问题、必须人工提供参数或 `sql-rewrite.yml` 配置的问题。

## 实例和模式参数

- 新建达梦实例时应按 MySQL 兼容场景确认实例参数，尤其是 `COMPATIBLE_MODE=4`、`CASE_SENSITIVE`、`LENGTH_IN_CHAR`、`BLANK_PAD_MODE`。实例参数不匹配时，SQL 转换正确也可能因为大小写、字符长度、空格比较等行为差异失败。
- `COMPATIBLE_MODE=4` 只是 MySQL 兼容模式，不代表所有 MySQL 语法都能直接执行。官方 FAQ 明确要求迁移前修改兼容参数并重启数据库服务使其生效；兼容模式下仍可能需要手工重写表、视图、游标、系统包、函数、存储过程和非法数据。
- `CASE_SENSITIVE` 是实例级参数，确定后不可随意修改。MySQL 可细到字段级大小写规则，达梦实例级大小写会同时影响对象名和数据比较；如果迁移时保留小写对象名，MyBatis SQL 往往需要双引号，若取消 DTS 的“保持对象名大小写”则对象名会转大写。
- `LENGTH_IN_CHAR` 和 DTS 类型映射会影响 `VARCHAR`/`CHAR` 长度语义。MySQL 常按字符理解长度，达梦若按字节或自动放大长度，可能出现字段内容截断、乱码或长度变为原来的 3 倍。
- `BLANK_PAD_MODE` 会影响 `CHAR` 尾部空格补齐和比较行为。遇到字符列比较、唯一约束、迁移后数据尾部空格异常时，先确认实例参数和字段类型。
- MySQL 兼容容错参数会影响数据超长、字符串转数值、除 0 等行为。验证失败如果是“字符串转数值失败”“除数为 0”“超出列长度”，不能只从 mapper SQL 判断，需要同时看实例容错策略和测试数据。
- MySQL 的 `database` 通常迁移为达梦的 `schema`。验证 SQL 时不能默认所有对象都在当前 schema，跨库 SQL 要么映射到多个 schema，要么明确跳过缺失库表。
- MySQL 允许的零日期、非法日期需要在迁移前清洗。达梦不接受 `0000-00-00`、`0000-00-00 00:00:00` 这类值。

## 类型和对象差异

- MySQL `VARCHAR(n)`/`CHAR(n)` 按字符语义使用时，应确保达梦侧采用字符长度语义，或在 DDL 转换时按 `utf8` 3 字节、`utf8mb4` 4 字节放大长度；否则 `'审核通过'` 等中文默认值和查询写入临时表时可能报列长度超出定义。
- MySQL `TEXT`、`LONGTEXT`、`JSON` 等类型迁移到达梦时通常需要映射到大字段或字符串类型，并检查业务是否依赖 MySQL JSON 函数。
- 自增列迁移后要特别处理。MySQL `AUTO_INCREMENT` 可对应达梦 `IDENTITY(start, increment)` 或迁移工具提供的 `auto_increment` 兼容方案；`IDENTITY` 自增列类型只能使用 `INT` 或 `BIGINT`。验证中出现“仅当指定列列表，且 SET IDENTITY_INSERT 为 ON 时，才能对自增列赋值”时，通常不是 SQL 语法转换问题，而是测试数据、表结构或业务插入策略问题。
- 触发器、函数、存储过程、视图、事件、外键、索引等对象不能只靠 mapper SQL 验证判断完整性。缺对象导致的 `无效的表或视图名`、`无效的列名`、`无法解析成员访问表达式`，优先归类为测试库对象缺失或原始 SQL 引用错误。

## SQL 语法差异

- MySQL 反引号标识符需要改为达梦双引号，或者去掉引用并统一大小写。动态 `${column}`、`${table}` 不能盲目转换，必须依赖白名单参数或 `sql-rewrite.yml`。
- MySQL 用双引号表示字符串的写法应改为单引号；达梦双引号表示标识符。转换时必须区分字符串常量、对象名、动态 `${}` 片段，不能把未知业务字符串转成对象名。
- MySQL 分页 `LIMIT offset,size`、`LIMIT size` 应转换为达梦可执行写法，例如 `OFFSET ... ROWS FETCH NEXT ... ROWS ONLY` 或 `FETCH FIRST ... ROWS ONLY`。当 `LIMIT` 被 MyBatis `<include>` 拆成独立文本片段时，也必须识别并转换。
- MySQL `ON DUPLICATE KEY UPDATE` 不能直接在达梦执行，通常要改为 `MERGE INTO` 或业务侧先查后写。dm-adapter 不应在无法确认唯一键和更新列语义时强行转换。
- MySQL `UPDATE ... JOIN ... SET ...`、多表 `DELETE`、`REPLACE INTO` 需要改写为达梦支持的 `MERGE`、相关子查询、`EXISTS` 或分步 SQL。
- MySQL 用户变量和累加写法如 `@rownum := @rownum + 1` 不能直接迁移，通常改为达梦窗口函数 `ROW_NUMBER() OVER (...)`，或在存储过程/业务代码中显式声明变量。
- MySQL 字符串拼接常见写法如 `LIKE #{name} '%'`、`CONCAT(a,b)`、`GROUP_CONCAT` 需要转换为达梦等价表达式。`#{}` 参数可以安全拼接，`${}` 动态片段必须保守处理。
- MySQL `GROUP_CONCAT(DISTINCT a, ',', b)` 这类多参数聚合要先把参数拼接为一个表达式，再转为达梦 `LISTAGG(DISTINCT ..., ',') WITHIN GROUP (...)`，不能保留 MySQL 的多参数函数形态。
- MySQL `CONVERT(expr, DECIMAL(n))`、`CONVERT(expr, DECIMAL(n,m))` 应转为 `CAST(expr AS DECIMAL(...))`，不能按达梦 `CONVERT` 函数原样保留。
- MySQL `CREATE TABLE ... COMMENT '...'`、列级 `COMMENT '...'`、`ENGINE`、`USING BTREE`、`ON UPDATE CURRENT_TIMESTAMP` 等 DDL 选项要从迁移 SQL 中移除或改写。表/列注释如需保留，应后续生成达梦 `COMMENT ON` 语句，不应留在建表语句内。
- MySQL `ALTER TABLE t AUTO_INCREMENT = n` 是重置自增起点的写法，不能通过删除 `AUTO_INCREMENT = n` 保留为半截 `ALTER TABLE`。dm-adapter 会转成达梦可执行的占位语句避免验证失败；如业务确实依赖重置序列语义，应按达梦身份列/序列方案人工确认。
- MySQL `ON UPDATE CURRENT_TIMESTAMP` 不是达梦列属性，通常改成 `BEFORE UPDATE` 触发器给时间列赋 `SYSDATE`，或由业务代码显式维护更新时间。
- MySQL `information_schema.TABLES/COLUMNS` 不应原样迁移。表存在性检查可映射到 `ALL_TABLES`，列清单可映射到 `ALL_TAB_COLUMNS`，需要创建时间或 schema 名的表详情可映射到 `ALL_OBJECTS`，并按当前 schema 过滤。
- MySQL 正则、日期、加密、编码、空值处理函数与达梦函数不完全一致，遇到 `REGEXP`、`DATE_FORMAT`、`STR_TO_DATE`、`IFNULL`、`IF`、`FIND_IN_SET`、`AES_ENCRYPT`、`AES_DECRYPT`、`MD5`、`TO_BASE64`、`YEARWEEK`、`PERIOD_DIFF` 等函数时必须逐项确认达梦等价写法。
- MySQL 原始 mapper 中应保留 MySQL 函数形态，例如 `SYSDATE()`；达梦侧可在生成的 `mapper-dm` 中转换为 `SYSDATE`。验证清零时不能把达梦函数反写到原始 MySQL XML，否则原项目在 MySQL 上会直接失效。
- `FIND_IN_SET` 不要简单替换为 `LIKE '%x%'`。需要确认分隔符、空值语义、返回位置还是布尔过滤；无法确定时标记人工确认或改写为规范的关联表/拆分函数。
- MySQL `IF(expr,a,b)` 可以在布尔表达式清晰时改为 `CASE WHEN expr THEN a ELSE b END`，但 `IF(count(...),...)`、`COUNT(DISTINCT IF(...))` 这类聚合内条件需要先确认 NULL 过滤语义。
- MySQL 除法在分母为 0 时的容错和达梦参数相关。业务 SQL 如直接 `a / b`，应优先改为 `a / NULLIF(b, 0)` 或 `CASE WHEN b = 0 THEN ...`，不要依赖实例容错。
- MySQL 中常见 `SUM(varchar_col)` 依赖隐式转换，达梦可能报类型转换失败。应优先修业务 SQL，显式 `CAST` 且清洗非数字数据。

## 存储过程、函数和触发器

- 达梦官方迁移流程强调：DTS 可以迁移部分对象，但 MySQL 与 DM 在存储过程、函数、触发器语法上差异明显，迁移失败或自动转换后仍不兼容时要按 DM 语法人工重建。
- MySQL 存储过程中的局部变量、游标、异常处理、临时表、动态 SQL、函数调用不能直接照搬。dm-adapter 当前主要处理 MyBatis mapper SQL，遇到存储过程/函数/触发器调用失败，应先判断目标库是否已创建等价对象。
- Java 或 mapper 中调用业务自定义函数时，如果达梦测试库缺函数，应归类为测试库缺对象；如果函数存在但签名或返回类型不同，应归类为业务对象迁移问题，不能通过 SQL 字符串替换掩盖。

## MyBatis 迁移判断准则

- 原始 mapper XML 不由 dm-adapter 迁移流程覆盖；自动迁移输出保持在 `src/main/resources/mapper-dm`。
- 原始 SQL 明显错误时，可以修原始业务 SQL。例如列名写错、insert 列和值数量不一致、`set` 末尾多逗号、Java 注解 SQL 难以转换时应拆回 mapper XML 或改成可迁移 SQL。
- Java 注解里的 SQL 如果包含复杂动态 SQL、MySQL 专有语法或需要达梦改写，应优先迁移到 mapper XML，再由 dm-adapter 生成 `mapper-dm`。
- 参数推测失败时，先增强 dm-adapter 的参数推测或 `sql-rewrite.yml` 回放能力；如果参数本身是业务枚举、动态表名、动态列名或 SQL 片段，必须写入配置或标记为人工确认。
- `${}` 动态 SQL 需要区分三类值：
  - 动态标识符，如表名、列名、schema，需要白名单配置，验证参数不能来自任意字符串。
  - SQL 片段，如排序、条件、函数表达式，只能通过 `sql-rewrite.yml` 或安全枚举回放，不能自动拼接用户输入。
  - 已被 SQL 引号包住的字符串值，如 `'${item.code}'`，配置值应是不带外层 SQL 引号的普通字符串；如果 `${item.code}` 本身承担 SQL 片段，配置值才可能需要带引号。
- 动态表名或动态列名缺少业务入参时，验证生成的 `ID`、`test`、空字符串等占位值只用于暴露问题，不能当作真实 SQL 兼容失败。能从 mapper 上下文推断的增强工具，不能推断的写配置或跳过。
- 缺表、缺视图、缺函数、缺列等测试环境问题可以加入 validation ignore，但必须能从错误信息确认是测试库对象缺失，不应掩盖 mapper 语法错误。

## 验证和分类流程

1. 运行 Windows 环境 CLI `migrate`，读取目标项目 `.dm-adapter/sql-validation-report.md` 和 `.json`。
2. 先排除数据库连接失败；连接失败不能作为 SQL 迁移结果。
3. 对失败项按以下顺序处理：
   - dm-adapter 可通用转换：补转换规则和单元测试。
   - 原始业务 SQL 错误：修原始 mapper XML 或 Java 注解 SQL。
   - 测试库缺对象：加入 `validationIgnores.missingTables`、`missingViews`、`missingFunctions` 等配置，或要求补库对象。
   - 参数无法推断：补 `sql-rewrite.yml` 入参回放；无法自动化时标记人工配置。
4. 每次 dm-adapter 代码变更后执行 `mvn test`，通过后提交并推送 `main`。
5. 同步 Windows 镜像，执行 Windows Maven 构建，再用 Windows CLI 对业务项目回归验证。

## 当前项目优先关注模式

- `ORIGINAL_XML_SYNTAX_DEFECT`：优先判断是否原始 SQL 也有问题。常见包括 insert 列值数量不一致、动态 `<set>` 末尾逗号、非法 XML 转义。
- `TEST_SCHEMA_OBJECT`：缺表、缺视图、缺列、缺函数。确认缺失对象后可跳过；如果是 SQL 引用错列，修业务 SQL。
- `DYNAMIC_IDENTIFIER_PARAMETER` / `DYNAMIC_SQL_FRAGMENT_PARAMETER`：动态表名、列名、排序、where 片段。不能盲猜，优先白名单配置或跳过。
- `ON_DUPLICATE_KEY_UPDATE`、`MYSQL_USER_VARIABLE`、`MYSQL_UPDATE_JOIN_MULTI_TARGET`：MySQL 专有写法，除非能完整识别唯一键和语义，否则不要自动强转。
- `BINDING_PARAMETER_NAME` / `MAPPER_PROPERTY_NAME`：通常是测试参数推测或业务对象属性名问题。能从 mapper 方法签名推断的增强工具，不能推断的写配置。
