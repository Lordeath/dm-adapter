# dm-adapter

[中文](README.md) | English

`dm-adapter` is a Java 17 CLI tool that helps Spring Boot + MyBatis + Maven projects add a Dameng database adaptation path with low intrusion.

## Current Capabilities

- Scan Maven, Spring Boot, and MyBatis XML mapper projects; Spring Boot/MyBatis detection combines direct `pom.xml` declarations with a `mvn dependency:tree` fallback.
- Resolve mapper XML files from `mybatis.mapperLocations` / `mybatis.mapper-locations` and related keys in project `application*.properties`, `application*.yml`, and `application*.yaml` files first; support cross-module classpath patterns such as `classpath*:/mapper/*.xml`; fall back to resource directory scanning when not configured.
- Check whether `pom.xml` already contains a Dameng JDBC driver dependency.
- Copy mapper XML files to the source module's `src/main/resources/mapper-dm` during `migrate` without overwriting originals.
- Apply conservative SQL rewrites: `IFNULL` -> `NVL`, `NOW()` -> `SYSDATE`, double-quoted string literals -> single-quoted string literals, simple `LIMIT` pagination, `DATE_ADD(..., INTERVAL n UNIT)` -> `DATEADD(UNIT, n, ...)`, `CONVERT(..., UNSIGNED)` -> `CAST(... AS BIGINT)`, `FROM/JOIN ... AS alias` -> `FROM/JOIN ... alias`, and suffix Dameng special column names such as `ROWID`, `ROWNUM`, `TRXID`, `PHYROWID`, and `VERSIONS_*` with an underscore.
- Rewrite configurable `ON DUPLICATE KEY UPDATE` / `INSERT IGNORE` statements to Dameng `MERGE` when the application workspace's `sql-rewrite.yml` provides trusted `keyColumns`; when Dameng validation environment variables are configured, `migrate` can infer and maintain that config from test-database primary/unique metadata, leaving unresolved SQL unchanged and reported.
- Mark `GROUP_CONCAT`, JSON functions, complex time calculation/conversion functions, `REPLACE INTO`, upsert/ignore SQL without safe key-column configuration, and backtick-quoted identifiers for manual review.
- Generate a Dameng test-environment SQL integration test: a JUnit/MyBatis/JDBC test class in the target project plus an external `sql-validation.yml` template, without starting Spring Boot, ShardingSphere, MQ, or web beans; when `DM_SQL_VALIDATION=true` and connection variables are complete, generation also runs the validation test once and prints the report path.
- Write configs, Markdown/JSON reports, and validation temporary files under `<current-directory>/.dm-adapter/<application-artifactId>/` by default instead of creating `.dm-adapter` in the target project.

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

# Declare the target length semantics when SQL scripts are converted offline.
# BYTE targets use explicit VARCHAR(n CHAR)/CHAR(n CHAR) definitions.
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate \
  --project ./demo --sql-scripts-only \
  --sql-root ./sql/v2 --sql-root-out ./sql/v2-dm \
  --schema sample-system --target-length-semantics BYTE

# --report-dir overrides the complete application workspace and does not append the artifactId.
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --app-module demo-rest --report-dir /data/dm-work/demo-rest

# You can also generate the SQL validation test after migrate; --app-module accepts a module path or Maven artifactId, and --app-module, --schema, or --config implies generation.
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --app-module demo-rest --schema sample-system
```

When these commands are started from the `dm-adapter` directory, the default application workspace is `dm-adapter/.dm-adapter/demo-rest/`. The directory name uses the Maven `artifactId` resolved from `--app-module`; without it, the CLI discovers a unique Spring Boot application module and falls back to the root POM artifactId or project directory name. The subcommands share this rule. Existing target-project `sql-rewrite.yml` and `sql-validation.yml` files are copied once when their new destinations are absent; old files are not deleted and existing destination files are never overwritten.

The generated SQL validation test does not connect to the database during ordinary `mvn test` runs. With the following environment variables, `generate-validation-test` runs it once after generation; you can also run it manually in the Dameng test environment:

```bash
DM_SQL_VALIDATION=true \
DM_JDBC_URL=jdbc:dm://host:5236 \
DM_DB_USERNAME=user \
DM_DB_PASSWORD=password \
DM_ADAPTER_DIR=/path/to/dm-adapter/.dm-adapter/demo-rest \
mvn -Ddm.adapter.projectRoot=/path/to/demo -Dtest=DmSqlValidationTest test
```

Non-dry-run SQL-script migration writes a strict
`sql-script-validation-plan.json` to the application workspace. It records the
target schema and capabilities plus SHA-256 hashes for every output file and
statement. Run it later with:

```bash
DM_SQL_VALIDATION=true \
DM_JDBC_URL=jdbc:dm://host:5236 \
DM_DB_USERNAME=user \
DM_DB_PASSWORD=password \
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar validate-sql \
  --project /path/to/demo
```

`validate-sql` has no arbitrary-directory execution mode. It rejects changed
files, project/schema mismatches, and incompatible `LENGTH_IN_CHAR` or
`COMPATIBLE_MODE` values before executing SQL. Manual-review statements are
skipped while unrelated statements continue, and results are written to
`sql-script-validation-report.md/json`.

The test creates a MyBatis `SqlSessionFactory` from the application workspace's `sql-validation.yml` and does not load the target project's Spring Boot configuration. The CLI passes the workspace automatically; manual Maven runs must provide `-Ddm.adapter.dir=...` or `DM_ADAPTER_DIR`, and may provide `dm.adapter.projectRoot`. Validation reports are written back to the same application workspace.

Generated procedure scripts must be run in DBeaver with **Execute SQL Script**
(`Alt+X` by default), not by sending a multi-statement selection with
`Ctrl+Enter`. Keep `;` as the statement delimiter and configure `/` as the
procedure-block/script delimiter. Temporary procedures resolve
`SF_GET_SCHEMA_NAME_BY_ID(CURRENT_SCHID)` once into a local variable at runtime
and are called without an extra schema argument. The generated script does not
embed the CLI `--schema` value. `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')` is not
used inside these procedures because it may resolve to the procedure owner's
default schema. Scripts containing backtick identifiers require Dameng
`COMPATIBLE_MODE=4`.

The same environment variables are also used by `migrate` for read-only Dameng metadata inference. Schema resolution prefers CLI `--schema` / the workspace `sql-validation.yml`, then the JDBC URL `schema` parameter, then the connection default schema or username. Inference only writes table names, method names, and `keyColumns`; it never stores JDBC URLs, usernames, or passwords in repository files.

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
