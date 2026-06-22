# dm-adapter

[中文](README.md) | English

`dm-adapter` is a Java 17 CLI tool that helps Spring Boot + MyBatis + Maven projects add a Dameng database adaptation path with low intrusion.

## Current Capabilities

- Scan Maven, Spring Boot, and MyBatis XML mapper projects; Spring Boot/MyBatis detection combines direct `pom.xml` declarations with a `mvn dependency:tree` fallback.
- Resolve mapper XML files from `mybatis.mapperLocations` / `mybatis.mapper-locations` and related keys in project `application*.properties`, `application*.yml`, and `application*.yaml` files first; support cross-module classpath patterns such as `classpath*:/mapper/*.xml`; fall back to resource directory scanning when not configured.
- Check whether `pom.xml` already contains a Dameng JDBC driver dependency.
- Copy mapper XML files to the source module's `src/main/resources/mapper-dm` during `migrate` without overwriting originals.
- Apply conservative SQL rewrites: `IFNULL` -> `NVL`, `NOW()` -> `SYSDATE`, double-quoted string literals -> single-quoted string literals, simple `LIMIT` pagination, and suffix Dameng special column names such as `ROWID`, `ROWNUM`, `TRXID`, `PHYROWID`, and `VERSIONS_*` with an underscore.
- Mark `DATE_FORMAT`, `GROUP_CONCAT`, `FIND_IN_SET`, JSON functions, time calculation/conversion functions, `ON DUPLICATE KEY UPDATE`, `REPLACE INTO`, and backtick-quoted identifiers for manual review.
- Generate a Dameng test-environment SQL integration test: a JUnit/MyBatis/JDBC test class plus a `.dm-adapter/sql-validation.yml` parameter template in the target project, without starting Spring Boot, ShardingSphere, MQ, or web beans.
- Write Markdown and JSON reports under `.dm-adapter/`.

## Dameng Special Column Rewrite Notes

Dameng treats `ROWID`, `ROWNUM`, `TRXID`, `PHYROWID`, `VERSIONS_STARTTIME`, `VERSIONS_ENDTIME`, `VERSIONS_STARTTRXID`, `VERSIONS_ENDTRXID`, and `VERSIONS_OPERATION` as pseudo columns or special column names. Business table columns with these names should be renamed during migration. When `migrate` rewrites mapper XML, it suffixes matching bare SQL identifiers with an underscore, for example `rowid` -> `rowid_` and `trxid` -> `trxid_`.

The rule is case-insensitive, but it only handles bare SQL identifiers. String literals, SQL comments, and MyBatis parameter placeholders such as `#{rowid}` / `${trxid}` are preserved to avoid changing Java parameter names or text values.

Other ordinary Dameng keywords or reserved words are not renamed globally. Terms such as `SELECT`, `FROM`, `WHERE`, `ORDER`, and `LIMIT` are also SQL syntax, so blind replacement would break valid statements. If a business column conflicts with an ordinary reserved word, handle it according to the target schema by renaming the column, using quoted identifiers, configuring connection/client `KEYWORDS`, or reviewing the generated manual-confirmation report.

## Quick Start

```bash
mvn test
mvn -pl dm-adapter-cli -am package

java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar scan --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --dry-run
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar report --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar generate-validation-test --project ./demo --schema sample-system

# You can also generate the SQL validation test after migrate; --app-module, --schema, or --config implies generation.
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --app-module demo-rest --schema sample-system
```

The generated SQL validation test does not connect to the database during ordinary `mvn test` runs. Run it explicitly in the Dameng test environment, for example:

```bash
DM_SQL_VALIDATION=true \
DM_JDBC_URL=jdbc:dm://host:5236 \
DM_DB_USERNAME=user \
DM_DB_PASSWORD=password \
mvn -Dtest=DmSqlValidationTest test
```

The test creates a MyBatis `SqlSessionFactory` directly from the `datasource` environment-variable placeholders in `.dm-adapter/sql-validation.yml`; it does not load the target project's Spring Boot configuration. When `--schema` is configured, the test executes `set schema "<schema>"` before each DAO invocation, which supports quoted schema names such as `sample-system`. Runtime progress is printed with the `[dm-sql-validation]` prefix, including mapper XML loading, the current mapper method, and passed/failed/skipped results. Results are written to `.dm-adapter/sql-validation-report.md` plus `.dm-adapter/sql-validation-report.json`; the report groups failures by SQL compatibility, test schema, test data/constraints, and keeps long errors in a details section.

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
- v0.3: Improve standalone SQL validation, mapper-location suggestions, and configurable output paths.
- v0.4: Add stronger migration auditing, including before/after diffs, risk levels, machine-readable report schema, and CI examples.
- Later: Evaluate Gradle, multi-datasource projects, MyBatis annotation SQL, DDL analysis, and pluggable rule extensions.

## Development

```bash
mvn test
mvn -q -DskipTests compile
mvn -q -DskipTests package
```

See [AGENTS.md](AGENTS.md) for contribution and Git workflow rules.
