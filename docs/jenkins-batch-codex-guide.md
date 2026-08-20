# Jenkins 无人值守 Batch 模式部署说明

本文档用于把 `dm-adapter` 的多仓库日常转换任务交给 Jenkins 或负责配置 Jenkins 的 Codex。任务只需启动一次 CLI；拉取、转换、提交和推送均由 CLI 内部完成，不需要在外围脚本中循环仓库或调用 Git。

## 目标与边界

执行入口只有一个：

```text
java -jar dm-adapter-cli-0.1.0-SNAPSHOT.jar batch --config <batch.yml>
```

每次运行会按 YAML 中的顺序处理所有启用仓库：

1. 使用 JGit 克隆或更新持久化缓存，并强制回到配置分支的最新远端提交。
2. 执行 MySQL 到达梦的离线转换，原 mapper XML 不会被覆盖，默认输出仍是 `src/main/resources/mapper-dm`。
3. 不连接达梦数据库、不读取达梦元数据、不生成或运行数据库验证测试。
4. 没有人工确认项时，使用 YAML 指定的作者信息和提交信息创建提交，然后以非强推方式推送。
5. 一个仓库失败后继续处理后续仓库，最后用整体非零退出码让 Jenkins 标记失败。

仓库必须在 YAML 中逐个声明 URL 和 branch。第一版不会扫描某个本地根目录来猜测仓库，也不支持 SSH URL；支持 `http`、`https` 和 `file` URL。

## YAML 配置

以下内容可直接复制为 `batch.yml`，再替换路径、凭据和仓库清单。Windows 路径建议使用正斜杠，避免 YAML 反斜杠转义。

```yaml
schemaVersion: 1

# CLI 自己维护的 Git 缓存。不要指向已有人工工作区。
workspaceDir: G:/dm-adapter-batch/workspace

# 每次运行的报告根目录；不能与 workspaceDir 相同或互相嵌套。
reportDir: G:/dm-adapter-batch/reports

# 默认保留 30 天；设为 0 表示永久保留。
reportRetentionDays: 30

git:
  # HTTP(S) 仓库使用的全局凭据。当前版本按约定直接读取 YAML 明文值。
  username: "batch-user"
  password: "replace-with-access-token-or-password"
  authorName: "DM Adapter Bot"
  authorEmail: "dm-adapter@example.com"
  commitMessage: "自动转换新增 MySQL SQL 为达梦 SQL"

migrationDefaults:
  # 可选：groupId:artifactId:version。省略时沿用 migrate 的默认达梦驱动坐标。
  # dmDriver: "com.dameng:DmJdbcDriver18:8.1.3.140"

  # 以下相对路径都以仓库检出根目录为基准；一般无需设置 mapperDir。
  # mapperDir: "service/src/main/resources/mapper-dm"
  # rewriteConfig: ".dm-adapter/sql-rewrite.yml"
  sqlScriptsOnly: false

  # 可选：为无法从当前仓库 DDL 获取元数据的 INSERT IGNORE/ON DUPLICATE KEY 配置真实表主键或唯一键。
  # 表名需要与 Mapper SQL 中的写法一致；动态 schema 占位符按字面量配置。
  upsertKeys:
    tables:
      "${schemaName}.charge_customerchargedetail_ext":
        keyColumns:
          - chargeDetailId
    # 动态表名无法绑定到静态 DDL 时，可按 Mapper 完整方法名配置权威冲突键。
    methods:
      "com.example.canal.dao.CanalMapper.upsertCompressedSnapshot":
        keyColumns:
          - pk
      "com.example.canal.dao.CanalMapper.insertIfAbsent":
        conflictKeyGroups:
          - [pk]
          - [tenant_id, business_code]

  sql:
    # IF_PRESENT：目录存在就转换，不存在则跳过。
    # REQUIRED：目录不存在时当前仓库失败。
    # DISABLED：当前层级禁用 SQL 脚本转换。
    mode: IF_PRESENT
    sourceDir: "sql/v2"
    outputDir: "sql/v2-dm"
    # 路径相对 sourceDir；00000000.sql 无需配置也会默认保留。
    preserveSql:
      - "manual/keep.sql"

repositories:
  - name: service-a
    url: "https://git.example.com/platform/service-a.git"
    branch: main
    projectSubdir: "."

  - name: service-b
    url: "https://git.example.com/platform/service-b.git"
    branch: develop
    projectSubdir: "java-service"
    migration:
      sqlScriptsOnly: true
      sql:
        mode: REQUIRED
        sourceDir: "database/mysql"
        outputDir: "database/dameng"

  - name: temporarily-disabled
    url: "https://git.example.com/platform/temporarily-disabled.git"
    branch: main
    enabled: false
```

配置规则：

- `schemaVersion` 当前只能为 `1`。未知字段、重复 YAML 键、重复仓库名、非法分支和越界相对路径都会在操作 Git 前失败。
- `workspaceDir`、`reportDir` 可使用绝对路径；相对路径以 YAML 文件所在目录为基准。
- `name` 只允许字母、数字、点、下划线和连字符，且忽略大小写后不能重复。
- `projectSubdir` 指向仓库内 Maven 项目根目录，必须存在 `pom.xml`。
- `mapperDir`、`rewriteConfig`、SQL 输入输出目录均相对仓库检出根目录，而不是相对 `projectSubdir`。
- `upsertKeys` 支持 `tables` 和 `methods`。表配置用于静态表名；方法配置键必须是 `namespace.statementId`，用于 `${tableName}` 等无法静态绑定 DDL 的动态写入。两者都只能填写已由业务 DDL 或业务约束确认的真实键，不能根据 `id`、`pk` 等字段名猜测。
- `tables` 中每个表必须配置非空 `keyColumns`。`methods` 中的 `keyColumns` 表示普通 upsert 的唯一匹配键，`conflictKeyGroups` 表示 `INSERT IGNORE` 需要覆盖的全部可达主键/唯一键，组内为 AND、组间为 OR；每个方法必须至少配置其中一项。列名和键组不得为空或重复。
- `migrationDefaults.upsertKeys.tables/methods` 对所有仓库生效；仓库自己的 `migration.upsertKeys.tables/methods` 分别按表或完整方法名覆盖全局配置。
- batch 内联的表/方法键优先于 `rewriteConfig` 中的同名配置；值不一致时使用内联配置，并在当前仓库迁移报告中输出告警。运行时会把最终配置写入当前报告目录的 `sql-rewrite.yml`，便于审计和复现。
- 表名比较忽略大小写、反引号和双引号，但不会移除 schema。`${schemaName}.table_name` 与 `table_name` 是不同配置键。
- SQL 表字段统一生成显式 `VARCHAR(n CHAR)` / `CHAR(n CHAR)`，不访问数据库，也不依赖目标库的 `LENGTH_IN_CHAR`。
- 旧配置中的 `targetLengthSemantics` 会继续被接受，但其值不再生效；每次加载配置最多输出一条弃用告警，建议后续清理该字段。
- 仓库 URL 不允许内嵌凭据。HTTP(S) 仓库必须同时配置全局 `username` 和 `password`；`file` URL 可不配凭据。
- 当前 batch 模式故意不提供 schema 参数。它不会访问数据库，也不会生成 SQL 验证清单、调用验证器或输出 system-schema、外部存储过程未验证等数据库验证告警。
- Batch 仍会阻止包含真实转换人工确认项的仓库提交和推送；数据库验证被静默不代表自动放行不确定 SQL。

## Git 与失败处理

CLI 的缓存位于 `<workspaceDir>/repositories/<name>`，所有删除或重建动作都要求对应的 CLI 所有权标记：

- 首次运行克隆配置分支；后续运行 fetch 后 hard reset/clean 到最新远端提交，缓存中的人工改动会被丢弃。
- URL 改变或已标记缓存损坏时，CLI 会删除该缓存并重新克隆。
- 目录存在但没有 CLI 所有权标记时，CLI 拒绝修改或删除它，当前仓库按 Git 错误失败。
- 转换后、提交前会再次 fetch。远端分支在转换期间变化时，CLI 从新提交重新转换，最多尝试两次。
- push 使用普通非强推。push 结果不确定时会查询远端分支确认；发生并发更新时按上述策略重试。
- 同一个 `workspaceDir` 同时只允许一个 batch 实例。第二个实例获取不到锁时跳过并返回 `0`。

如果源 SQL 脚本包含 `USE <database>`，当前仓库会进入人工确认，不创建提交也不推送；必须由开发人员从 MySQL 源脚本中删除该语句。其他仓库仍继续处理。

## 报告与退出码

每次运行在 `<reportDir>/<runId>` 下生成：

- `dm-adapter-batch-summary.md/json`：整次运行汇总。
- `<repository>/dm-adapter-batch-report.md/json`：单仓库状态、基线提交、推送提交、尝试次数和变更文件。
- `<repository>/dm-adapter-report.md/json`：mapper/POM 等迁移详情。
- `<repository>/dm-adapter-sql-script-report.md/json`：配置了 SQL 目录时的脚本转换详情。

报告清理由 CLI 自己执行。它只删除带有 batch 报告标记且超过 `reportRetentionDays` 的直属运行目录；不会删除未标记目录或符号链接。

退出码：

| 退出码 | 含义 |
| --- | --- |
| `0` | 全部成功、无需变更，或同一 workspace 已有任务运行而本次被跳过 |
| `1` | 离线迁移或项目结构错误 |
| `2` | YAML 配置错误 |
| `3` | 存在人工确认项，包括源 SQL 中的 `USE <database>` |
| `5` | clone、fetch、缓存安全、并发分支或 push 等 Git 错误 |

整体退出码的优先级为 Git 错误 `5`、人工确认 `3`、其他错误 `1`。详细根因以汇总 JSON 和各仓库报告为准。

## Jenkins 配置

建议使用一个固定节点和一个普通 Pipeline/Freestyle 定时任务：

1. 在受限目录中放置 batch YAML，只授予 Jenkins 服务账号和管理员读取权限。YAML 含明文凭据，不得提交到代码仓库、归档为构建产物或打印到控制台。
2. 构建或下载 CLI shaded jar。若 Jenkins 同时构建本仓库，可先执行 `mvn -q -DskipTests package`。
3. 构建步骤只调用一次 CLI，不要再写仓库循环、pull、commit 或 push 脚本。
4. 将 `<reportDir>/<runId>` 或至少汇总报告归档。不要归档 YAML 和 `workspaceDir`。
5. 使用 Jenkins 自身的定时触发器安排周期，例如每小时或每天运行。

Windows 构建步骤示例：

```bat
java -jar G:\tools\dm-adapter\dm-adapter-cli-0.1.0-SNAPSHOT.jar batch --config G:\secure\dm-adapter\batch.yml
```

Linux 构建步骤示例：

```bash
java -jar /opt/tools/dm-adapter/dm-adapter-cli-0.1.0-SNAPSHOT.jar batch --config /etc/dm-adapter/batch.yml
```

这两行只是启动 CLI；Git 编排仍全部在 Java/JGit 内部。

## IntelliJ IDEA 调试

新建 **Application** Run/Debug Configuration：

- Main class：`com.github.dmadapter.cli.DmAdapterCli`
- Use classpath of module：`dm-adapter-cli`
- Program arguments：

```text
batch --config G:\secure\dm-adapter\batch.yml
```

- Working directory：dm-adapter 工具仓库根目录，例如 `G:\tools\dm-adapter`
- JRE：Java 17

工具仓库和其他代码仓库位于同一个父目录没有问题。batch 不使用这些现有本地工作副本，而是根据 YAML URL 将仓库放入 `workspaceDir` 的受管缓存。建议让 `workspaceDir` 和 `reportDir` 位于工具仓库之外，避免 IDE、Git 状态或清理任务互相影响。

调试真实 HTTP 仓库前，可先把 YAML URL 换成 `file:///G:/path/to/test-repository.git` 验证转换流程；`file` URL 不要求用户名和密码。

## 交给 Jenkins Codex 的验收清单

Codex 配置任务时应逐项确认：

- Jenkins 节点使用 Java 17，shaded jar 可执行，且配置文件路径存在。
- YAML 中的每个仓库都有明确 URL、branch 和 Maven `projectSubdir`，没有依赖本地目录扫描。
- Jenkins 服务账号对 Git 远端具备读取和目标分支普通 push 权限。
- `workspaceDir` 是 batch 专用目录，`reportDir` 与其分离，两个目录都不在业务仓库中。
- 作业只运行一次 `batch --config`，没有外部多仓库 Git 循环。
- 首次试运行检查汇总 JSON、远端提交作者/信息、mapper-dm 与 SQL 输出目录。
- 重复运行在没有新源代码时返回 `0` 且仓库状态为 `NO_CHANGES`。
- 人为加入一个测试用 `USE sample_database;` 后，该仓库返回人工确认、没有 push，后续测试仓库仍会继续。
- Jenkins 不回显、不归档 YAML 中的用户名和密码。
