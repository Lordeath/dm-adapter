# Repository Guidelines

## 项目结构与模块划分

本仓库是 Java 17 + Maven 多模块 CLI 项目，用于辅助 Spring Boot + MyBatis + Maven 项目适配达梦数据库。

- `dm-adapter-cli`：Picocli 命令入口，编排 `scan`、`migrate`、`report`。
- `dm-adapter-core`：共享上下文、异常、扫描结果、迁移报告等核心模型。
- `dm-adapter-maven`：解析 `pom.xml`，识别并补充达梦 JDBC 依赖。
- `dm-adapter-mybatis`：扫描 mapper XML、复制到 `mapper-dm`、接入 SQL 重写。
- `dm-adapter-sql`：保守的 MySQL 到达梦 SQL 转换规则。
- `dm-adapter-report`：生成和读取 Markdown/JSON 报告。
- `dm-adapter-test-fixtures`：测试用示例项目和资源。

生产代码放在各模块 `src/main/java`，测试放在 `src/test/java`，fixture 资源放在 `dm-adapter-test-fixtures/src/main/resources`。

## 构建、测试与本地运行

- `mvn test`：运行全部模块测试。
- `mvn -q -DskipTests compile`：快速编译所有模块，不运行测试。
- `mvn -q -DskipTests package`：构建 CLI shaded jar。
- `java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar --help`：验证 CLI 入口。
- `java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --dry-run`：对目标项目执行 dry-run 迁移报告。

## 编码风格与命名规范

使用 Java 17。不可变数据模型优先使用 `record`。统一 4 空格缩进，包名保持在 `com.github.dmadapter` 下。类名按职责命名，例如 `PomAnalyzer`、`MapperMigrator`、`ReportWriter`。CLI 层只做参数解析和流程编排，业务逻辑放到对应模块服务中。

当前未配置格式化插件。修改代码时保持现有风格，不做无关格式化。

## 测试要求

测试框架为 JUnit 5 + AssertJ。测试类命名为 `*Test`，测试方法使用行为描述式命名，例如 `dryRunDoesNotModifyPom`。涉及 SQL 规则、POM 修改、mapper XML 迁移、CLI 命令流程的改动必须补测试。文件系统相关测试使用 `@TempDir`。

提交前必须运行 `mvn test`，且所有测试通过。

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

不得覆盖原始 mapper XML。默认迁移输出目录必须保持为 `src/main/resources/mapper-dm`。不确定的 SQL 必须保留原内容并写入人工确认报告，不能强行转换。
