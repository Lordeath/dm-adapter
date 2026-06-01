# dm-adapter

`dm-adapter` is a Java 17 CLI that helps Spring Boot + MyBatis + Maven projects add a low-intrusion Dameng database adaptation path.

Current MVP scope:

- scan Maven, Spring Boot, MyBatis XML mapper projects
- check whether a Dameng JDBC dependency exists
- copy mapper XML files to `src/main/resources/mapper-dm`
- apply conservative MySQL-to-Dameng SQL rewrites
- report automatic rewrites and manual review items

Example:

```bash
mvn test
mvn -pl dm-adapter-cli -am package
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar scan --project ./demo
java -jar dm-adapter-cli/target/dm-adapter-cli-0.1.0-SNAPSHOT.jar migrate --project ./demo --dry-run
```
