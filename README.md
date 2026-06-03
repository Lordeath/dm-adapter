# dm-adapter

中文 | [English](README.en.md)

`dm-adapter` 是一个基于 Java 17 的命令行工具，用于辅助 Spring Boot + MyBatis + Maven 项目新增达梦数据库适配路径。

## 当前能力

- 扫描 Maven、Spring Boot、MyBatis XML mapper 项目；Spring Boot/MyBatis 识别会结合 `pom.xml` 直接声明和 `mvn dependency:tree` fallback。
- mapper XML 优先根据项目内 `application*.properties`、`application*.yml`、`application*.yaml` 中的 `mybatis.mapperLocations` / `mybatis.mapper-locations` 等配置项定位；支持 `classpath*:/mapper/*.xml` 这类跨模块 classpath 配置；未配置时回退到资源目录扫描。
- 检查 `pom.xml` 是否已有达梦 JDBC 驱动依赖。
- `migrate` 默认复制 mapper XML 到 mapper 所在模块的 `src/main/resources/mapper-dm`，不覆盖原文件。
- 生成 `application-dm.yml`，将 MyBatis mapper 指向 `classpath*:mapper-dm/**/*.xml`。
- 自动转换保守 SQL 规则：`IFNULL` -> `NVL`、`NOW()` -> `SYSDATE`、双引号字符串常量 -> 单引号字符串常量、简单 `LIMIT` 分页。
- 将 `DATE_FORMAT`、`GROUP_CONCAT`、`FIND_IN_SET`、JSON 函数、时间计算/转换函数、`ON DUPLICATE KEY UPDATE`、`REPLACE INTO`、反引号标识符等标记为人工确认。
- 生成达梦测试环境 SQL 集成验证测试：在目标项目生成 JUnit/Spring Boot 测试类和 `.dm-adapter/sql-validation.yml` 参数模板。
- 输出 Markdown 和 JSON 报告到 `.dm-adapter/`。

## 快速开始

```bash
mvn test
mvn -pl dm-adapter-cli -am package

java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar scan --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --dry-run
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar report --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar generate-validation-test --project ./demo --schema sample-system
```

生成的 SQL 验证测试默认不会在普通 `mvn test` 中连接数据库。需要在达梦测试环境显式运行，例如：

```bash
DM_SQL_VALIDATION=true mvn -Dtest=DmSqlValidationTest test
```

测试使用目标项目的 `dm` Spring profile 数据源配置。配置 `--schema` 后，测试会在每次 DAO 调用前执行 `set schema "<schema>"`，可支持 `sample-system` 这类需要双引号的 schema 名。执行结果写入 `.dm-adapter/sql-validation-report.md` 和 `.dm-adapter/sql-validation-report.json`。

## 模块结构

- `dm-adapter-cli`：Picocli 命令入口与流程编排。
- `dm-adapter-core`：上下文、依赖坐标、扫描结果、迁移报告等核心模型。
- `dm-adapter-maven`：POM 解析与达梦 JDBC 依赖修改。
- `dm-adapter-mybatis`：mapper XML 扫描、复制和 SQL 重写接入。
- `dm-adapter-sql`：MySQL 到达梦 SQL 转换规则。
- `dm-adapter-report`：Markdown/JSON 报告生成与读取。
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
