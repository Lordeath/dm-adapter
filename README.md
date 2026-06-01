# dm-adapter

## 中文

`dm-adapter` 是一个基于 Java 17 的命令行工具，用于辅助 Spring Boot + MyBatis + Maven 项目新增达梦数据库适配路径。

第一版 MVP 聚焦低侵入迁移：

- 扫描 Maven、Spring Boot、MyBatis XML mapper 项目。
- 检查项目是否已有达梦 JDBC 驱动依赖。
- 默认复制 mapper XML 到 `src/main/resources/mapper-dm`，不覆盖原文件。
- 对常见 MySQL SQL 写法执行保守转换。
- 将自动转换项和需要人工确认的 SQL 写入报告。

当前支持的命令：

```bash
mvn test
mvn -pl dm-adapter-cli -am package
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar scan --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --dry-run
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar report --project ./demo
```

项目采用 Maven 多模块结构：

- `dm-adapter-cli`：CLI 命令入口。
- `dm-adapter-core`：核心模型与上下文。
- `dm-adapter-maven`：POM 解析与依赖修改。
- `dm-adapter-mybatis`：mapper XML 扫描、复制和重写。
- `dm-adapter-sql`：MySQL 到达梦 SQL 转换规则。
- `dm-adapter-report`：Markdown/JSON 报告。
- `dm-adapter-test-fixtures`：测试 fixture。

第一版不支持 Gradle、JPA、MyBatis 注解 SQL、多数据源复杂场景和 DDL 自动迁移。

## English

`dm-adapter` is a Java 17 CLI tool that helps Spring Boot + MyBatis + Maven projects add a low-intrusion Dameng database adaptation path.

The MVP focuses on conservative migration support:

- Scan Maven, Spring Boot, and MyBatis XML mapper projects.
- Check whether a Dameng JDBC dependency already exists.
- Copy mapper XML files to `src/main/resources/mapper-dm` by default without overwriting originals.
- Apply conservative MySQL-to-Dameng SQL rewrites.
- Report automatic conversions and SQL items that need manual review.

Common commands:

```bash
mvn test
mvn -pl dm-adapter-cli -am package
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar scan --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --dry-run
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar report --project ./demo
```

Module layout:

- `dm-adapter-cli`: CLI command entry point.
- `dm-adapter-core`: shared models and context.
- `dm-adapter-maven`: POM parsing and dependency updates.
- `dm-adapter-mybatis`: mapper XML scanning, copying, and rewriting.
- `dm-adapter-sql`: MySQL-to-Dameng SQL conversion rules.
- `dm-adapter-report`: Markdown/JSON reports.
- `dm-adapter-test-fixtures`: test fixtures.

The first version does not support Gradle, JPA, MyBatis annotation SQL, complex multi-datasource projects, or automatic DDL migration.
