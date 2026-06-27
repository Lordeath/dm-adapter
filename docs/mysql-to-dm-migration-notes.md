# MySQL 到达梦迁移注意点

资料来源：

- 达梦官方文档：MySQL 到 DM，https://eco.dameng.com/document/dm/zh-cn/start/mysql_dm
- 达梦官方 FAQ：MySQL 迁移 DM8，https://eco.dameng.com/document/dm/zh-cn/faq/faq-mysql-dm8-migrate.html

本文用于指导 dm-adapter 后续处理 Spring Boot + MyBatis 项目从 MySQL 迁移到达梦 8。修改代码前先按本文区分：应由 dm-adapter 自动转换的问题、业务 SQL 本身需要修正的问题、测试库缺对象的问题、必须人工提供参数或 `sql-rewrite.yml` 配置的问题。

## 实例和模式参数

- 新建达梦实例时应按 MySQL 兼容场景确认实例参数，尤其是 `COMPATIBLE_MODE=4`、`CASE_SENSITIVE=1`、`LENGTH_IN_CHAR=1`、`BLANK_PAD_MODE=1`。实例参数不匹配时，SQL 转换正确也可能因为大小写、字符长度、空格比较等行为差异失败。
- MySQL 的 `database` 通常迁移为达梦的 `schema`。验证 SQL 时不能默认所有对象都在当前 schema，跨库 SQL 要么映射到多个 schema，要么明确跳过缺失库表。
- MySQL 允许的零日期、非法日期需要在迁移前清洗。达梦不接受 `0000-00-00`、`0000-00-00 00:00:00` 这类值。

## 类型和对象差异

- MySQL `VARCHAR(n)` 按字符语义使用时，应确保达梦侧采用字符长度语义，否则中文等多字节字符可能超长。
- MySQL `TEXT`、`LONGTEXT`、`JSON` 等类型迁移到达梦时通常需要映射到大字段或字符串类型，并检查业务是否依赖 MySQL JSON 函数。
- 自增列迁移后要特别处理。达梦对 identity 列直接插入显式值有限制，验证中出现“仅当指定列列表，且 SET IDENTITY_INSERT 为 ON 时，才能对自增列赋值”时，通常不是 SQL 语法转换问题，而是测试数据、表结构或业务插入策略问题。
- 触发器、函数、存储过程、视图、事件、外键、索引等对象不能只靠 mapper SQL 验证判断完整性。缺对象导致的 `无效的表或视图名`、`无效的列名`、`无法解析成员访问表达式`，优先归类为测试库对象缺失或原始 SQL 引用错误。

## SQL 语法差异

- MySQL 反引号标识符需要改为达梦双引号，或者去掉引用并统一大小写。动态 `${column}`、`${table}` 不能盲目转换，必须依赖白名单参数或 `sql-rewrite.yml`。
- MySQL 分页 `LIMIT offset,size`、`LIMIT size` 应转换为达梦可执行写法，例如 `OFFSET ... ROWS FETCH NEXT ... ROWS ONLY` 或 `FETCH FIRST ... ROWS ONLY`。当 `LIMIT` 被 MyBatis `<include>` 拆成独立文本片段时，也必须识别并转换。
- MySQL `ON DUPLICATE KEY UPDATE` 不能直接在达梦执行，通常要改为 `MERGE INTO` 或业务侧先查后写。dm-adapter 不应在无法确认唯一键和更新列语义时强行转换。
- MySQL `UPDATE ... JOIN ... SET ...`、多表 `DELETE`、`REPLACE INTO` 需要改写为达梦支持的 `MERGE`、相关子查询、`EXISTS` 或分步 SQL。
- MySQL 用户变量如 `@rownum := @rownum + 1` 不能直接迁移，通常改为达梦窗口函数 `ROW_NUMBER() OVER (...)`。
- MySQL 字符串拼接常见写法如 `LIKE #{name} '%'`、`CONCAT(a,b)`、`GROUP_CONCAT` 需要转换为达梦等价表达式。`#{}` 参数可以安全拼接，`${}` 动态片段必须保守处理。
- MySQL 正则、日期、加密、编码、空值处理函数与达梦函数不完全一致，遇到 `REGEXP`、`DATE_FORMAT`、`STR_TO_DATE`、`IFNULL`、`IF`、`FIND_IN_SET`、`AES_ENCRYPT`、`TO_BASE64` 等函数时必须逐项确认达梦等价写法。

## MyBatis 迁移判断准则

- 原始 mapper XML 不由 dm-adapter 迁移流程覆盖；自动迁移输出保持在 `src/main/resources/mapper-dm`。
- 原始 SQL 明显错误时，可以修原始业务 SQL。例如列名写错、insert 列和值数量不一致、`set` 末尾多逗号、Java 注解 SQL 难以转换时应拆回 mapper XML 或改成可迁移 SQL。
- Java 注解里的 SQL 如果包含复杂动态 SQL、MySQL 专有语法或需要达梦改写，应优先迁移到 mapper XML，再由 dm-adapter 生成 `mapper-dm`。
- 参数推测失败时，先增强 dm-adapter 的参数推测或 `sql-rewrite.yml` 回放能力；如果参数本身是业务枚举、动态表名、动态列名或 SQL 片段，必须写入配置或标记为人工确认。
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
