# Repository Guidelines

## 项目结构与模块划分

本仓库是 Java 17 + Maven 多模块 CLI 项目，用于辅助 Spring Boot + MyBatis + Maven 项目适配达梦数据库。

- `dm-adapter-cli`：Picocli 命令入口，编排 `scan`、`migrate`、`report`。
- `dm-adapter-core`：共享上下文、异常、扫描结果、迁移报告等核心模型。
- `dm-adapter-maven`：解析 `pom.xml`，识别并补充达梦 JDBC 依赖。
- `dm-adapter-mybatis`：扫描 mapper XML、复制到 `mapper-dm`、接入 SQL 重写。
- `dm-adapter-sql`：保守的 MySQL 到达梦 SQL 转换规则。
- `dm-adapter-report`：生成和读取 Markdown/JSON 报告。
- `dm-adapter-gui`：Swing 桌面界面，通过独立 JVM 子进程调用 CLI，并支持 Windows EXE 打包。
- `dm-adapter-test-fixtures`：测试用示例项目和资源。

生产代码放在各模块 `src/main/java`，测试放在 `src/test/java`，fixture 资源放在 `dm-adapter-test-fixtures/src/main/resources`。

## 构建、测试与本地运行

- `mvn test`：运行全部模块测试。
- `mvn -q -DskipTests compile`：快速编译所有模块，不运行测试。
- `mvn -q -DskipTests package`：构建 CLI shaded jar。
- `java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar --help`：验证 CLI 入口。
- `java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --dry-run`：对目标项目执行 dry-run 迁移报告。
- `java -jar dm-adapter-gui/target/dm-adapter-gui-0.1.0-SNAPSHOT.jar`：本地启动 GUI。
- `powershell -File scripts/package-windows.ps1`：在 Windows 生成自带运行时的便携 EXE 应用目录。

## 编码风格与命名规范

使用 Java 17。不可变数据模型优先使用 `record`。统一 4 空格缩进，包名保持在 `com.github.dmadapter` 下。类名按职责命名，例如 `PomAnalyzer`、`MapperMigrator`、`ReportWriter`。CLI 层只做参数解析和流程编排，业务逻辑放到对应模块服务中。

当前未配置格式化插件。修改代码时保持现有风格，不做无关格式化。

## 测试要求

测试框架为 JUnit 5 + AssertJ。测试类命名为 `*Test`，测试方法使用行为描述式命名，例如 `dryRunDoesNotModifyPom`。涉及 SQL 规则、POM 修改、mapper XML 迁移、CLI 命令流程的改动必须补测试。文件系统相关测试使用 `@TempDir`。

提交前必须运行 `mvn test`，且所有测试通过。

## 信息脱敏要求

- 代码、测试样本、SQL、配置示例、文件名、注释、文档和提交信息，以及上传到 GitHub 的日志、报告和 CI 产物，均不得包含可识别真实客户或内部项目的信息。检查范围包括客户名称及简称、客户编号、联系人及联系方式、工单或需求编号、项目路径、仓库地址、包名、命名空间、数据库标识、服务器地址、业务数据和凭据。
- 从真实报错、脚本或日志中提取回归样本时，先提取复现问题所需的最小结构，再替换为虚构名称和数据，例如 `com.example`、`sample_table`、`sample_id`。必须同步替换测试输入、断言、配置、文件名及注释中的关联标识，不能只删除客户名称而保留可追溯的项目或业务信息。
- 脱敏必须保留触发问题所需的 SQL 结构、数据类型、约束、控制流程、参数绑定顺序，以及有意义的长度、编码和转义特征。不得通过删除用例、跳过测试或弱化断言掩盖行为变化；修改测试样本后应重跑相关测试，既有红灯样本还应确认脱敏后仍能复现原问题。
- 原始客户报告、业务脚本副本、数据库导出、调试日志、截图及真实值与虚构值的对照表，仅用于本地排查，不得直接提交或上传为 CI 产物。测试凭据必须明确为虚构值，实际连接信息通过本地配置或环境变量提供，不得写入提交内容或日志。
- 提交前检查 `git status --short`、工作区差异和 `git diff --cached`，逐项核对新增文件、样本、注释及提交说明。推送前还必须检查本次待推送的每个提交，不能只确认最终文件快照已脱敏。用于排查的真实标识不得反过来写入仓库内的脱敏规则、黑名单或检查脚本。
- 若敏感信息已经进入提交，必须检查受影响的历史版本；新增删除提交或加入 `.gitignore` 不等于清除了历史。已经推送时，还需核查远端分支、标签、PR、CI 日志和产物中的残留信息。
- 历史改写、远端更新和相关清理操作遵循用户已有授权及提交、推送规则，不因本节要求自动获得推送或删除远端数据的权限。经授权更新已改写的远端历史时，使用明确预期旧 SHA 的 `--force-with-lease`，避免覆盖他人新增提交。
- 清理后核查相关文件、提交说明和可达历史，并如实说明已处理范围、测试结果及剩余事项。GitHub 旧提交仍可能通过 SHA 或缓存访问，必要时需由 GitHub Support 清除；未经核实，不得声称敏感信息已彻底删除。

## Git 操作要求

AI 完成任何变更后都必须执行 Git 流程，包括代码、测试、配置、README、AGENTS.md 等纯文档修改。不要因为“只是文档改动”而跳过测试、提交或推送。

- 使用 `git status --short` 确认变更范围。
- 运行 `mvn test`，确认单元测试全部通过。
- 测试通过后，直接在主干分支完成提交和推送，不需要创建 PR。
- Git 提交信息优先使用中文，除非项目已有明确英文约定或用户特别指定英文。

推荐流程：

```bash
git status --short
mvn test
git add .
git commit -m "补充 Git 提交信息约束"
git push origin main
```

如果当前不在 `main` 分支，应先切回或合并到 `main`，再提交和推送。不要提交 `target/`、`.dm-adapter/`、IDE 配置等构建或本地环境产物。

## AI 专项约束

处理 MySQL 到达梦迁移、SQL 重写或验证失败分类前，必须先阅读并遵守 `docs/mysql-to-dm-migration-notes.md`，区分工具可自动转换、业务 SQL 需修正、测试库缺对象、必须人工配置入参这几类问题。

不得覆盖原始 mapper XML。默认迁移输出目录必须保持为 `src/main/resources/mapper-dm`。不确定的 SQL 必须保留原内容并写入人工确认报告，不能强行转换。
