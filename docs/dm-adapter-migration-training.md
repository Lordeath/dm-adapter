# MySQL 到达梦迁移培训：dm-adapter 工具使用与功能说明

本文是 `dm-adapter-migration-training.pptx` 的 Markdown 版 PPT 文案，面向项目开发组培训和后续维护使用。

---

## 1. 标题页

# MySQL 到达梦迁移培训

## dm-adapter 工具使用与功能说明

面向 Spring Boot + MyBatis + Maven 项目的低侵入达梦适配辅助工具。

关键词：

- 扫描
- 迁移
- 报告
- 验证
- 反复修正

---

## 2. 适用范围与边界

## 适用项目

- Maven 项目。
- Spring Boot + MyBatis XML mapper。
- MySQL 迁移到达梦 8。
- 希望保留原 MySQL mapper，同时生成达梦适配路径。

## 当前边界

- 暂不覆盖 Gradle、JPA、复杂多数据源。
- DDL、存储过程、触发器、函数对象仍需要结合业务人工迁移。
- 复杂动态 SQL 不盲猜；工具会保留原内容并进入报告或验证失败分类。
- 达梦实例参数、schema 策略、测试库对象完整性仍需要人工确认。

核心原则：

> 能安全转换才自动转换；不能确认就保留、报告、验证，再由业务侧修正。

---

## 3. 推荐使用流程

## 推荐：直接执行 `migrate`

开发组实际迁移时，推荐直接用 `migrate` 生成达梦适配路径、报告和验证配置，然后根据失败项迭代处理。

流程：

1. 构建 CLI。
2. 执行 `migrate` 进行迁移。
3. 查看迁移报告和验证报告中的失败项。
4. 按失败类型处理：
   - 原始业务 SQL 有问题：修改原 mapper XML 或 Java 注解 SQL。
   - 达梦测试库缺表、缺视图、缺函数、缺字段：优先补齐测试库对象。
   - 确认只是测试环境缺对象且本轮无需验证：在 `<应用工作目录>/sql-rewrite.yml` 的 `validationIgnores` 中解注或加入需要跳过的表、字段、schema。
   - 动态表名、动态字段、排序片段、where 片段缺少真实入参：在 `<应用工作目录>/sql-rewrite.yml` 中补充验证参数或安全枚举。
5. 重新执行 `migrate`。
6. 重复查看报告、修正、重跑，直到剩余项都能明确归类。

推荐命令：

```bash
mvn -pl dm-adapter-cli -am package

java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar \
  migrate \
  --project ./demo \
  --app-module demo-rest \
  --schema sample-system
```

本文中的 `<应用工作目录>` 默认指启动 CLI 的当前目录下 `.dm-adapter/<应用 artifactId>/`。例如从 dm-adapter 工具目录执行并传入 `--app-module demo-rest` 时，默认目录是 `dm-adapter/.dm-adapter/demo-rest/`；也可用可选的 `--report-dir` 指定完整最终目录。

说明：

- `migrate` 会生成 `<应用工作目录>/dm-adapter-report.md` 和 JSON 报告。
- 传入 `--app-module`、`--schema` 或 `--config` 时，会触发达梦 SQL 验证测试生成。
- 配置达梦连接环境变量后，工具会尝试执行生成的验证测试。
- `--dry-run` 可作为预览手段，但不作为本培训推荐主流程。

---

## 4. 运行后会改什么

## POM

- 检查目标模块 `pom.xml`。
- 补充达梦 JDBC 驱动依赖。
- 已有依赖时不会重复添加。

## Mapper

- 复制 mapper XML 到默认达梦目录：`src/main/resources/mapper-dm`。
- 在达梦副本中接入 SQL 自动改写结果。
- 不覆盖原始 mapper XML。

## 报告

- `<应用工作目录>/dm-adapter-report.md`
- `<应用工作目录>/dm-adapter-report.json`
- 记录文件变化、自动转换 SQL、人工确认项和风险提示。

## 配置

- `<应用工作目录>/sql-rewrite.yml`
- 用于配置 upsert 的 `keyColumns`。
- 用于配置验证入参、动态 SQL 回放、`validationIgnores` 等迁移验证辅助信息。

## 验证测试

- 生成 JUnit/MyBatis/JDBC 验证测试。
- 生成或维护 `<应用工作目录>/sql-validation.yml`。
- 普通 `mvn test` 默认不连接达梦库。

---

## 5. Mapper 迁移策略

## 定位 mapper

- 优先读取 `application*.properties`、`application*.yml`、`application*.yaml` 中的 mapper 配置。
- 支持 `mybatis.mapperLocations` / `mybatis.mapper-locations`。
- 支持 `classpath*:/mapper/*.xml` 这类跨模块 classpath 配置。
- 未配置时回退扫描资源目录。

## 低侵入输出

- 原始 mapper XML 保持不覆盖。
- 默认输出目录保持为 `src/main/resources/mapper-dm`。
- 不确定的 SQL 保留原内容，写入人工确认报告或验证报告。

## 修正策略

- 如果原始 SQL 本身错误，修原 mapper XML 或 Java 注解 SQL。
- 不建议直接手改 `mapper-dm` 当作长期修复来源。
- 修完原始 SQL 后重新跑 `migrate`，让达梦副本重新生成。

---

## 6. SQL 主要规则：自动转换

以下规则以“达梦环境验证失败、且工具能安全等价改写”为前提。

## 字符串与别名

- MySQL 双引号字符串改为单引号字符串。
- 单引号别名改为达梦可识别的标识符别名。

示例：

```sql
status = "ACTIVE"
-- 转为
status = 'ACTIVE'
```

## 函数与表达式

- `REGEXP` / `NOT REGEXP` 改为 `REGEXP_LIKE`。
- `DATE_ADD` / `DATE_SUB` / `INTERVAL` 改为 `DATEADD`。
- `CONVERT(expr, DECIMAL(...))` 改为 `CAST(expr AS DECIMAL(...))`。
- `CAST(... AS UNSIGNED)` 改为明确数值类型。
- 部分 `GROUP_CONCAT` 改为 `LISTAGG ... WITHIN GROUP`。

## 特殊列名与关键字

- 达梦特殊列名如 `ROWID`、`ROWNUM`、`TRXID` 等追加下划线。
- 部分命中达梦关键字的业务标识符会加双引号。
- 字符串、注释、MyBatis 参数占位符不做误改。

---

## 7. SQL 主要规则：保留与人工确认

## 已验证可执行时保留原样

以下 MySQL 写法在当前验证口径下可在达梦兼容模式执行，默认不再为了迁移而改写：

- `IFNULL`
- `NOW()`
- `CONCAT`
- `CONCAT_WS`
- `SELECT ... LIMIT`
- 反引号标识符
- `FIND_IN_SET`
- `DATE_FORMAT`
- `STR_TO_DATE`
- 常见 JSON 函数
- `UPDATE JOIN` / `DELETE JOIN`

## 人工确认或配置后处理

以下场景不能盲目转换：

- `ON DUPLICATE KEY UPDATE` 缺少可确认唯一键。
- `INSERT IGNORE`、`REPLACE INTO` 语义依赖业务。
- `${}` 动态表名、动态字段名、排序片段、where 片段。
- 用户变量，如 `@rownum := @rownum + 1`。
- 复杂时间函数、未覆盖的 `PERIOD_DIFF` / `YEARWEEK`。
- 未支持的 AES/加密函数形态。

处理方式：

- 能确认唯一键时，在 `<应用工作目录>/sql-rewrite.yml` 中补 `keyColumns`。
- 能确认动态入参时，在 `<应用工作目录>/sql-rewrite.yml` 中补验证参数或安全枚举。
- 不能确认时保留人工确认项，不强行转换。

---

## 8. 达梦 SQL 验证测试

## 运行方式

```bash
DM_SQL_VALIDATION=true \
DM_JDBC_URL=jdbc:dm://host:5236 \
DM_DB_USERNAME=user \
DM_DB_PASSWORD=password \
DM_SQL_VALIDATION_TOTAL_TIMEOUT_SECONDS=7200 \
DM_ADAPTER_DIR=/path/to/dm-adapter/.dm-adapter/demo-rest \
mvn -Ddm.adapter.projectRoot=/path/to/demo -Dtest=DmSqlValidationTest -DskipTests=false -Dmaven.test.skip=false test
```

## 验证特点

- 直接创建 MyBatis `SqlSessionFactory`。
- 不启动 Spring Boot、Web、MQ、ShardingSphere 等业务 Bean。
- 设置 `--schema` 后，先统一检查全部 schema；任一无效时只报告一次根因且不执行 Mapper SQL，检查通过后再在 DAO 调用前切换 schema。
- 控制台输出 `[dm-sql-validation]` 进度日志。
- SQL 脚本验证与 Mapper 验证共享总时限，默认 2 小时。

> SQL 脚本数据库验证会按顺序完整执行转换后的文件，保持自动提交、不自动回滚、不缓存、不跳过。这是真实写入共享测试库的模式，只能连接允许变更的测试环境，脚本必须自行保证幂等。

## 报告输出

- `<应用工作目录>/sql-validation-report.md`
- `<应用工作目录>/sql-validation-report.json`
- `<应用工作目录>/dm-adapter-summary.md`
- `<应用工作目录>/dm-adapter-summary.json`

Mapper 验证每 50 条记录原子更新一次详细报告。新一轮执行会将旧详细报告轮换为 `sql-validation-report.previous.md/json`，因此超时后仍可查看本轮已完成部分和上一轮结果。

报告会按以下维度帮助归类：

- SQL 兼容问题。
- 测试库缺表、缺视图、缺函数、缺字段。
- 参数无法推断。
- 测试数据或约束问题。
- 已跳过项。

---

## 9. 失败项处理与重跑闭环

## 先看失败类型

处理验证失败时，先排除数据库连接失败。连接失败不能作为 SQL 迁移结果。

## 原始 SQL 问题

常见表现：

- 原始 mapper XML SQL 本身语法错误。
- insert 列和值数量不一致。
- 动态 `<set>` 末尾多逗号。
- Java mapper 方法参数与 XML 参数名不一致。

处理方式：

- 修改原始 mapper XML 或 Java 注解 SQL。
- 必要时修 Java mapper 方法签名和 `@Param`。
- 重新执行 `migrate`。

## 测试库缺对象

常见表现：

- 缺表、缺视图。
- 缺函数、缺存储过程。
- 缺字段。
- schema 没切到目标业务 schema。

处理方式：

- 优先补齐达梦测试库对象。
- 如果确认该对象不属于本轮验证范围，可在 `<应用工作目录>/sql-rewrite.yml` 中解注或加入跳过配置：

```yaml
validationIgnores:
  missingTables:
    - demo_missing_table
  missingColumns:
    - demo_missing_column
  missingSchemas:
    - demo_missing_schema
```

注意：

- 只跳过能确认是测试环境缺对象的问题。
- 不要用跳过配置掩盖 mapper SQL 语法错误。

## 动态 SQL 或参数问题

处理方式：

- 动态表名、字段名、SQL 片段必须给真实业务值或白名单枚举。
- 在 `<应用工作目录>/sql-rewrite.yml` 中补验证参数。
- 无法自动化确认时保留人工确认。

## 重跑工具

每轮处理后重新执行：

```bash
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar \
  migrate \
  --project ./demo \
  --app-module demo-rest \
  --schema sample-system
```

目标：

> 让每个失败项都能明确归类：工具规则待增强、原 SQL 待修、测试库待补、配置待补、或确认跳过。
