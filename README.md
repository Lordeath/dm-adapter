# dm-adapter

中文 | [English](README.en.md)

`dm-adapter` 是一个基于 Java 17 的命令行工具，用于辅助 Spring Boot + MyBatis + Maven 项目新增达梦数据库适配路径。

## 当前能力

- 扫描 Maven、Spring Boot、MyBatis XML mapper 项目；Spring Boot/MyBatis 识别会结合 `pom.xml` 直接声明和 `mvn dependency:tree` fallback。
- mapper XML 优先根据项目内 `application*.properties`、`application*.yml`、`application*.yaml` 中的 `mybatis.mapperLocations` / `mybatis.mapper-locations` 等配置项定位；支持 `classpath*:/mapper/*.xml` 这类跨模块 classpath 配置；未配置时回退到资源目录扫描。
- 检查 `pom.xml` 是否已有达梦 JDBC 驱动依赖。
- `migrate` 默认复制 mapper XML 到 mapper 所在模块的 `src/main/resources/mapper-dm`，不覆盖原文件。
- 自动转换保守 SQL 规则：`IFNULL` -> `NVL`、`NOW()` -> `SYSDATE`、双引号字符串常量 -> 单引号字符串常量、简单 `LIMIT` 分页、`DATE_ADD(..., INTERVAL n UNIT)` -> `DATEADD(UNIT, n, ...)`、`CONVERT(..., UNSIGNED)` -> `CAST(... AS BIGINT)`、`FROM/JOIN ... AS 别名` -> `FROM/JOIN ... 别名`，并将 `ROWID`、`ROWNUM`、`TRXID`、`PHYROWID`、`VERSIONS_*` 等达梦特殊业务列名重命名为前缀下划线形式。
- 支持通过应用工作目录中的 `sql-rewrite.yml` 配置 `keyColumns`，将可确认唯一键的 `ON DUPLICATE KEY UPDATE` / `INSERT IGNORE` 改写为达梦 `MERGE`；配置达梦验证环境变量后，`migrate` 会优先从测试库主键、唯一键和自增列元数据自动推断并维护该配置。若元数据证明 `INSERT IGNORE` 不可能发生重复键冲突，则自动转为普通 `INSERT`；其他无法确认的情况保留原 SQL 并写入报告。
- 将 `GROUP_CONCAT`、JSON 函数、复杂时间计算/转换函数、`REPLACE INTO`、无法安全确认唯一键的 upsert/ignore 等标记为人工确认；达梦 MySQL 兼容模式可执行的反引号标识符默认保留。
- 生成达梦测试环境 SQL 集成验证测试：在目标项目生成 JUnit/MyBatis/JDBC 测试类，在工具侧应用工作目录生成 `sql-validation.yml` 参数模板，不启动 Spring Boot、ShardingSphere、MQ 或 Web 相关 Bean；若 `DM_SQL_VALIDATION=true` 且连接环境变量齐全，生成后会自动执行一次验证测试并输出报告路径。
- 默认将配置、Markdown/JSON 报告和验证临时文件输出到 `<当前命令目录>/.dm-adapter/<应用 artifactId>/`，不在业务项目中创建 `.dm-adapter`。
- 提供 `batch --config <yaml>` 无人值守模式：CLI 使用 JGit 按配置逐个拉取仓库和指定分支，离线转换后自动提交、推送并生成汇总报告；整个流程不连接达梦数据库，也不需要外围 Git 脚本。

## 达梦特殊列名重写注意事项

达梦中的 `ROWID`、`ROWNUM`、`TRXID`、`PHYROWID`、`VERSIONS_STARTTIME`、`VERSIONS_ENDTIME`、`VERSIONS_STARTTRXID`、`VERSIONS_ENDTRXID`、`VERSIONS_OPERATION` 属于伪列或特殊列名，业务表字段迁移到达梦时应改名。`migrate` 会为这些物理列名增加前缀下划线，例如 `rowid` -> `_rowid`、`trxid` -> `_trxid`。

Mapper 最外层显式查询投影会同时保留原结果标签，例如 `SELECT trxid ... WHERE trxid = #{trxid}` 转为 `SELECT _trxid AS "trxid" ... WHERE _trxid = #{trxid}`。因此已有的 `resultMap column="trxid"`、Java 属性名和 MyBatis 参数名不需要修改。只用于 SELECT 投影的本地 `<sql>` 列清单使用相同规则；用途混合或无法确认的片段保留原样并进入人工确认。`SELECT *` / `t.*` 不展开、不增加专项提示。

该规则大小写不敏感，并保留原标识符的大小写；字符串常量、SQL 注释、`#{rowid}` / `${trxid}` 这类 MyBatis 参数占位符会保持原样，避免误改 Java 参数名和文本内容。数据库侧的 `keyColumn` 会同步使用前缀物理列名，`keyProperty` 保持不变。

达梦普通关键字/保留字不会做全量自动重命名。原因是 `SELECT`、`FROM`、`WHERE`、`ORDER`、`LIMIT` 等词同时也是 SQL 语法的一部分，盲目替换会破坏语句。若业务表字段命中普通保留字，应结合实际表结构采用改字段名、双引号标识符、连接串/客户端 `KEYWORDS` 配置或人工确认报告处理。

## 快速开始

```bash
mvn test
mvn -pl dm-adapter-cli -am package

java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar scan --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --dry-run
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar report --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar generate-validation-test --project ./demo --schema sample-system

# 多仓库无人值守转换；仓库、分支、Git 身份和迁移参数均从 YAML 读取。
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar batch --config /data/dm-batch/batch.yml

# 离线转换 SQL 脚本时显式声明目标库字符长度语义；BYTE 会生成 VARCHAR(n CHAR)/CHAR(n CHAR)。
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate \
  --project ./demo --sql-scripts-only \
  --sql-root ./sql/v2 --sql-root-out ./sql/v2-dm \
  --schema sample-system --target-length-semantics BYTE

# 需要自定义完整应用工作目录时，--report-dir 的值就是最终目录，不会再追加 artifactId。
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --app-module demo-rest --report-dir /data/dm-work/demo-rest

# 也可以在 migrate 后自动生成 SQL 验证测试；--app-module 可传模块路径或 Maven artifactId，传入 --app-module、--schema 或 --config 会自动触发生成。
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --app-module demo-rest --schema sample-system
```

`batch` 的完整 YAML 示例、Jenkins 配置、IntelliJ IDEA 调试参数、退出码和缓存恢复规则见
[Jenkins 无人值守 Batch 模式部署说明](docs/jenkins-batch-codex-guide.md)。

## GUI 与 Windows EXE

`dm-adapter-gui` 提供 Java 17 Swing 桌面界面。GUI 不复制迁移逻辑，而是在独立 JVM
子进程中调用同一套 Picocli 命令，因此 CLI 和 GUI 的迁移规则、报告及退出码保持一致。界面支持项目扫描、
dry-run、正式迁移、SQL 脚本目录、达梦验证环境、实时日志、取消任务和摘要报告展示。

GUI 表单标签会直接显示对应的 CLI 参数或环境变量，主要映射如下：

| GUI 输入项 | CLI 参数或环境变量 |
| --- | --- |
| 项目根目录 / 应用模块 | `--project` / `--app-module` |
| Mapper 输出目录 | `--mapper-dir` |
| MySQL SQL 源目录 / 达梦 SQL 输出目录 | `--sql-root` / `--sql-root-out` |
| 业务 schema / system schema | `--schema` / `--system-schema` |
| 仅迁移 SQL 脚本 | `--sql-scripts-only` |
| SQL 重写配置 / 验证配置 | `--rewrite-config` / `--config` |
| 高级页工作目录覆盖 | `--report-dir`（可选；留空使用 `<启动目录>/.dm-adapter/<应用 artifactId>`） |
| 达梦驱动坐标 / 字符长度语义 | `--dm-driver` / `--target-length-semantics` |
| 生成 Mapper 验证测试 | `--generate-validation-test` |
| 数据库验证、JDBC URL、用户名、密码 | `DM_SQL_VALIDATION`、`DM_JDBC_URL`、`DM_DB_USERNAME`、`DM_DB_PASSWORD` |

底部三个操作按钮分别对应 `scan`、`migrate --dry-run` 和 `migrate`。

开发环境可直接运行 shaded jar：

```bash
mvn -q -DskipTests package
java -jar dm-adapter-gui/target/dm-adapter-gui-0.1.0-SNAPSHOT.jar
```

在 Windows PowerShell 中生成可双击运行、无需预装 Java 的便携应用：

```powershell
.\scripts\package-windows.ps1
```

产物入口为
`dm-adapter-gui\target\package\dm-adapter-gui\dm-adapter-gui.exe`。整个
`dm-adapter-gui` 目录需要一起分发，因为其中包含应用 jar 和 Java 运行时。

如需 Windows 安装器 EXE，可在已安装兼容 WiX Toolset 的 Windows 构建机上运行：

```powershell
.\scripts\package-windows.ps1 -Type exe
```

若 shaded jar 已由前一步或 CI 构建，可追加 `-SkipBuild`，只执行 Windows 打包。

数据库连接信息只注入 GUI 启动的 CLI 子进程环境，不进入命令参数、项目配置或报告。启用数据库验证前请注意：
SQL 脚本验证会真实修改测试库，按清单执行且不自动回滚。

前面的 CLI 命令从 `dm-adapter` 目录执行时，默认应用工作目录为 `dm-adapter/.dm-adapter/demo-rest/`。目录名优先取 `--app-module` 定位到的 Maven `artifactId`；未传时自动发现唯一 Spring Boot 应用模块，无法唯一发现时回退到根 POM `artifactId` 和 `--project` 目录名。`scan`、`migrate`、`validate-sql`、`report`、`generate-validation-test` 使用同一规则。显式传入 `--report-dir` 时，其值就是最终目录。升级前业务项目中已有的 `sql-rewrite.yml`、`sql-validation.yml` 会在新目录缺文件时首次复制，旧文件不会删除或覆盖新文件。

生成的 SQL 验证测试默认不会在普通 `mvn test` 中连接数据库。配置以下环境变量后，`generate-validation-test` 会在生成后自动运行一次；也可以在达梦测试环境中手动运行：

```bash
DM_SQL_VALIDATION=true \
DM_JDBC_URL=jdbc:dm://host:5236 \
DM_DB_USERNAME=user \
DM_DB_PASSWORD=password \
DM_SQL_VALIDATION_TOTAL_TIMEOUT_SECONDS=7200 \
DM_ADAPTER_DIR=/path/to/dm-adapter/.dm-adapter/demo-rest \
mvn -Ddm.adapter.projectRoot=/path/to/demo -Dtest=DmSqlValidationTest test
```

SQL 脚本迁移的非 dry-run 会在应用工作目录生成
`sql-script-validation-plan.json`。清单固化输出文件、目标 schema、数据库能力快照、人工确认项以及文件/语句 SHA-256。需要稍后单独执行时，使用：

```bash
DM_SQL_VALIDATION=true \
DM_JDBC_URL=jdbc:dm://host:5236 \
DM_DB_USERNAME=user \
DM_DB_PASSWORD=password \
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar validate-sql \
  --project /path/to/demo
```

`validate-sql` 只接受该迁移清单，不提供执行任意 SQL 目录的旁路。文件内容、语句切分、项目目录、schema、`LENGTH_IN_CHAR` 或 `COMPATIBLE_MODE` 与清单不一致时，会在执行任何 SQL 前失败。清单中的人工确认项只跳过对应语句；其他互不依赖语句继续验证。结果写入 `sql-script-validation-report.md/json`。

测试直接用应用工作目录中 `sql-validation.yml` 的 `datasource` 环境变量占位创建 MyBatis `SqlSessionFactory`，不会加载目标项目的 Spring Boot 配置。CLI 自动传递工作目录；手工 Maven 运行时需通过 `-Ddm.adapter.dir=...` 或 `DM_ADAPTER_DIR` 指定，并可通过 `dm.adapter.projectRoot` 指定业务项目根目录。配置 `--schema` 后，测试会先对全部 schema 做项目级前置检查；任一 schema 无效时只报告一次根因，不执行 Mapper SQL。前置检查通过后，仍会在每次 DAO 调用前执行 `set schema "<schema>"`。执行结果写入同一工作目录的 `sql-validation-report.md` 和 `sql-validation-report.json`。

> **数据库写入警告：** `DM_SQL_VALIDATION=true` 是数据库验证的唯一开关。使用 `migrate --sql-root ... --sql-root-out ...` 或 `validate-sql` 时，工具会按清单顺序执行未标记人工确认的 SQL，连接保持自动提交且不自动回滚。这会真实修改共享测试库；业务脚本必须自行保证幂等，并且只能连接可接受变更的测试环境。连接信息只能通过当前会话环境变量提供，不会写入仓库、清单或报告。

### DBeaver 执行生成脚本

生成脚本保留 `DROP PROCEDURE IF EXISTS`、`CREATE OR REPLACE PROCEDURE`、`CALL`、再次 `DROP` 的生命周期，并使用单独一行 `/` 结束过程块。在 DBeaver 中应使用“执行 SQL 脚本”（Windows/Linux 默认 `Alt+X`），不要选中整段后使用“执行 SQL 语句”（默认 `Ctrl+Enter`），否则多个语句可能被一次发送并在第二个 `PROCEDURE` 附近报语法错误。连接的 SQL Processing 设置中需保留 `;` 语句分隔符，并将 `/` 配置为脚本/过程块分隔符。

旧版 DIsql 即使使用 `START`/`@文件`，也可能对包含超长字符串的单条 DML 报 `DISQL-10033: 输入过长`。迁移器会把 `UPDATE SET`、`INSERT VALUES` 和 `MERGE` 中超过 3000 UTF-8 字节的安全直接字符串值转换为匿名块，以最多 900 字节的 `TO_CLOB` 片段拼接后执行；生成块使用单独一行 `/` 结束。位于 `WHERE`、函数、`RETURNING`、DDL 或已有过程块中的超长字符串不会强制改写，而会保留原 SQL 并进入人工确认。单个字符串的自动处理上限为 20MB。

临时过程不再接收 schema 参数，也不会把 `--schema` 的值写入脚本；过程内部使用
`SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)` 在运行时解析当前 schema，并将结果保存到局部变量，调用统一为
`CALL procedure_name()`。不能在过程内部使用 `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')` 推断目标 schema：
过程执行期间该值可能切换为过程定义者的默认 schema，导致存在性检查查错对象。反引号字段名用于保留源字段大小写，因此包含反引号的脚本要求目标达梦实例 `COMPATIBLE_MODE=4`。

数据库验证的总时限由 `DM_SQL_VALIDATION_TOTAL_TIMEOUT_SECONDS` 控制，默认 `7200` 秒（2 小时），SQL 脚本验证和 Mapper 验证共享同一时限。SQL 脚本单条语句时限由 `DM_SQL_SCRIPT_VALIDATION_TIMEOUT_SECONDS` 控制，默认 `600` 秒；工具除设置 JDBC 查询超时外还会执行自身的硬超时，主动取消语句并停止当前验证，避免驱动忽略 `setQueryTimeout` 后无限挂起。默认值覆盖了真实全量脚本中随测试库数据量增长而超过 300 秒的数据同步过程；若项目存在更长的合法过程，可用环境变量按项目覆盖。Mapper 单条语句的 JDBC 超时默认 `120` 秒，可用 `dm.sql.validation.statementTimeoutSeconds` 覆盖，避免合法慢查询被旧的 30 秒默认值误判。Mapper 验证每累计 50 条记录会原子更新报告；超时或进程中断后可读取已完成部分。新一轮运行开始前，上一轮报告会保留为 `sql-validation-report.previous.md/json`。

每次 `migrate` 还会在应用工作目录生成 `dm-adapter-summary.md` 和 `dm-adapter-summary.json`，汇总迁移、SQL 脚本验证、Mapper 验证三阶段状态、根因/级联阻塞数、人工确认降噪统计和详细报告链接。`report` 命令优先读取该摘要，旧工作目录则回退到原迁移报告。

CLI 退出码约定：`0` 表示请求的迁移/验证成功或未请求数据库验证，`1` 表示工具内部错误，`2` 表示项目路径无效或不是 Maven 项目，`3` 表示数据库验证存在 SQL 根因失败或仍有人工确认项，`4` 表示清单、连接、schema、数据库能力前置检查、验证环境或总时限问题。

同一组环境变量也会被 `migrate` 用于只读读取达梦元数据：优先使用 CLI `--schema` / 应用工作目录 `sql-validation.yml` 中的 `schema`，其次使用 JDBC URL 的 `schema` 参数、连接默认 schema 或用户名。自动推断只写入表名、方法名和 `keyColumns`，不会把连接串、用户名或密码写入仓库文件。

## 模块结构

- `dm-adapter-cli`：Picocli 命令入口与流程编排。
- `dm-adapter-core`：上下文、依赖坐标、扫描结果、迁移报告等核心模型。
- `dm-adapter-maven`：POM 解析与达梦 JDBC 依赖修改。
- `dm-adapter-mybatis`：mapper XML 扫描、复制和 SQL 重写接入。
- `dm-adapter-sql`：MySQL 到达梦 SQL 转换规则。
- `dm-adapter-report`：Markdown/JSON 报告生成与读取。
- `dm-adapter-gui`：Swing 桌面界面、CLI 子进程桥接和 Windows EXE 打包。
- `dm-adapter-test-fixtures`：测试示例项目。

## 当前边界

第一版只支持 Maven + Spring Boot + MyBatis XML mapper 项目，主要面向 MySQL -> 达梦。暂不支持 Gradle、JPA、MyBatis 注解 SQL、多数据源复杂场景和 DDL 自动迁移。复杂 SQL 默认保留原内容并写入人工确认报告。

## Roadmap

- v0.1：完善当前 MVP，增强 dry-run 报告、mapper 路径识别、POM 保格式写入和 CLI 错误提示。
- v0.2：扩展 SQL 转换规则，增加更多 MySQL 函数、分页变体、动态 SQL 风险分类和规则级测试 fixture。
- v0.3：增强 Spring Boot 配置生成，支持更灵活的 profile、mapper-location 合并建议和可配置输出目录。
- v0.4：引入更完整的迁移审计能力，包括转换前后 diff、风险等级、可机读报告 schema 和 CI 集成示例。
- 后续：评估 Gradle、多数据源、MyBatis 注解 SQL、DDL 辅助分析和插件化规则扩展。

## 开发

```bash
mvn test
mvn -q -DskipTests compile
mvn -q -DskipTests package
```

贡献和 Git 操作要求见 [AGENTS.md](AGENTS.md)。
