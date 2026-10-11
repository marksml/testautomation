# testautomation

Monorepo for distributing test case execution to test agents.

- **`service/`** — `testautomation-service`: the Spring Boot service that owns the job queue and
  offers a REST API for distributing test case execution to test agents.
- **`agent/`** — `testautomation-agent`: the Spring Boot agent that claims test cases from the
  service and executes them.

Both are independent Spring Boot applications in separate root folders, built by one Maven
reactor. They share no code yet; when the wire contract (claim/result DTOs) is fixed, a third
module (e.g. `shared/`) is the natural place for it.

## Status

Skeletons only — **no REST endpoints, no client code and no persistence exist yet**.
Java 21, Maven, Spring Boot 4.1.1 (parent-managed dependency versions, no version numbers in the
module POMs).

## Requirements

- JDK 21 (build verified with Temurin 21.0.12)
- Maven 3.9+, or the bundled wrapper (`./mvnw`) — no local Maven installation required

## Build & run

Run everything from the repository root:

```bash
./mvnw verify                                   # both modules: compile + tests + boot jars
./mvnw -pl service verify                       # one module only
./mvnw -pl agent -am verify                     # agent plus its (parent) dependencies

./mvnw -pl service spring-boot:run              # run the service from source
./mvnw -pl agent   spring-boot:run              # run the agent from source

java -jar service/target/testautomation-service-0.1.0-SNAPSHOT.jar
java -jar agent/target/testautomation-agent-0.1.0-SNAPSHOT.jar
```

Each module builds a repackaged, self-contained Spring Boot jar
(`spring-boot-maven-plugin:repackage`). Both contexts start in ~1.5 s and exit immediately because
nothing non-daemon (web server, scheduler, worker loop) is registered yet.

## Layout

```
pom.xml                                       parent/aggregator (spring-boot-starter-parent 4.1.1)
mvnw, mvnw.cmd, .mvn/wrapper/                 Maven wrapper (script-only, Maven 3.9.9)
service/pom.xml                               testautomation-service module
service/src/main/java/ch/mmar/testautomation/service/
service/src/main/resources/application.yml
service/src/test/java/...                     context-load smoke test
agent/pom.xml                                 testautomation-agent module
agent/src/main/java/ch/mmar/testautomation/agent/
agent/src/main/resources/application.yml
agent/src/test/java/...                       context-load smoke test
docs/                                         design documentation
```

Group id `ch.mmar.testautomation`; packages `ch.mmar.testautomation.service` and
`ch.mmar.testautomation.agent`. Module dependencies are intentionally limited to
`spring-boot-starter` plus `spring-boot-starter-test`; web, JDBC and queue dependencies are added
once the design below is settled.

## Documentation

- [`docs/postgres-job-queue.md`](docs/postgres-job-queue.md) — how a PostgreSQL job queue works
  (`SELECT ... FOR UPDATE SKIP LOCKED`, job states, lease/reaper, `LISTEN/NOTIFY`), which ports and
  connections it needs (PostgreSQL 5432, PgBouncer 6432, pool sizing vs `max_connections`), how
  agents idle and learn about new work (§8: long polling, in-process wake-up), Java library options
  and the decisions still open.

## Next steps

1. Decide the agent claim protocol (REST long-poll with `wait`, confirmed or not) and the job
   lifecycle details; then add the wire DTOs (candidate for a `shared/` module).
2. Add `spring-boot-starter-jdbc` + PostgreSQL driver to `service` and the `jobs` table
   (Flyway or plain SQL), plus the claim query, lease and reaper.
3. Add the REST layer (`spring-boot-starter-web`) to `service`: agent registration, `claim`, heartbeat,
   result submission — and the matching long-poll client loop in `agent`.
