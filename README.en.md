# dm-adapter

[中文](README.md) | English

`dm-adapter` is a Java 17 CLI tool that helps Spring Boot + MyBatis + Maven projects add a Dameng database adaptation path with low intrusion.

## Current Capabilities

- Scan Maven, Spring Boot, and MyBatis XML mapper projects; Spring Boot/MyBatis detection combines direct `pom.xml` declarations with a `mvn dependency:tree` fallback.
- Resolve mapper XML files from `mybatis.mapperLocations` / `mybatis.mapper-locations` and related keys in project `application*.properties`, `application*.yml`, and `application*.yaml` files first; support cross-module classpath patterns such as `classpath*:/mapper/*.xml`; fall back to resource directory scanning when not configured.
- Check whether `pom.xml` already contains a Dameng JDBC driver dependency.
- Copy mapper XML files to the source module's `src/main/resources/mapper-dm` during `migrate` without overwriting originals.
- Generate `application-dm.yml` and point MyBatis mapper locations to `classpath*:mapper-dm/**/*.xml`.
- Apply conservative SQL rewrites: `IFNULL` -> `NVL`, `NOW()` -> `SYSDATE`, double-quoted string literals -> single-quoted string literals, and simple `LIMIT` pagination.
- Mark `DATE_FORMAT`, `GROUP_CONCAT`, `FIND_IN_SET`, JSON functions, time calculation/conversion functions, `ON DUPLICATE KEY UPDATE`, `REPLACE INTO`, and backtick-quoted identifiers for manual review.
- Generate a Dameng test-environment SQL integration test: a JUnit/Spring Boot test class plus a `.dm-adapter/sql-validation.yml` parameter template in the target project.
- Write Markdown and JSON reports under `.dm-adapter/`.

## Quick Start

```bash
mvn test
mvn -pl dm-adapter-cli -am package

java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar scan --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --dry-run
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar report --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar generate-validation-test --project ./demo --schema sample-system
```

The generated SQL validation test does not connect to the database during ordinary `mvn test` runs. Run it explicitly in the Dameng test environment, for example:

```bash
DM_SQL_VALIDATION=true mvn -Dtest=DmSqlValidationTest test
```

The test uses the target project's `dm` Spring profile datasource configuration. When `--schema` is configured, the test executes `set schema "<schema>"` before each DAO invocation, which supports quoted schema names such as `sample-system`. Results are written to `.dm-adapter/sql-validation-report.md` plus `.dm-adapter/sql-validation-report.json`.

## Module Layout

- `dm-adapter-cli`: Picocli commands and workflow orchestration.
- `dm-adapter-core`: shared context, dependency coordinates, scan results, and migration report models.
- `dm-adapter-maven`: POM parsing and Dameng JDBC dependency updates.
- `dm-adapter-mybatis`: mapper XML scanning, copying, and SQL rewrite integration.
- `dm-adapter-sql`: MySQL-to-Dameng SQL conversion rules.
- `dm-adapter-report`: Markdown/JSON report writing and reading.
- `dm-adapter-test-fixtures`: sample projects for tests.

## Current Scope

The first version supports Maven + Spring Boot + MyBatis XML mapper projects only, mainly for MySQL -> Dameng migration. Gradle, JPA, MyBatis annotation SQL, complex multi-datasource projects, and automatic DDL migration are out of scope. Complex SQL is preserved and reported for manual review.

## Roadmap

- v0.1: Harden the MVP with better dry-run reports, mapper path detection, POM formatting preservation, and CLI error messages.
- v0.2: Expand SQL conversion coverage with more MySQL functions, pagination variants, dynamic SQL risk categories, and rule-level fixtures.
- v0.3: Improve Spring Boot configuration generation with flexible profiles, mapper-location merge suggestions, and configurable output paths.
- v0.4: Add stronger migration auditing, including before/after diffs, risk levels, machine-readable report schema, and CI examples.
- Later: Evaluate Gradle, multi-datasource projects, MyBatis annotation SQL, DDL analysis, and pluggable rule extensions.

## Development

```bash
mvn test
mvn -q -DskipTests compile
mvn -q -DskipTests package
```

See [AGENTS.md](AGENTS.md) for contribution and Git workflow rules.
