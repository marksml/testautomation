# testautomation-service

a spring-boot application offering a REST API for distributing test case execution to test agents.

## Status

Skeleton only — **no REST endpoints and no persistence exist yet**.
Java 21, Maven, Spring Boot 4.1.1 (parent-managed dependency versions).

## Requirements

- JDK 21 (build verified with Temurin 21.0.12)
- Maven 3.9+, or the bundled wrapper (`./mvnw`) — no local Maven installation required

## Build & run

```bash
./mvnw verify                      # compile + tests + executable jar
./mvnw spring-boot:run             # run from source
java -jar target/testautomation-service-0.1.0-SNAPSHOT.jar
```

The build produces a repackaged, self-contained Spring Boot jar
(`spring-boot-maven-plugin:repackage`). The current application context starts in ~1.5 s and
exits immediately because nothing non-daemon (web server, scheduler, worker) is registered yet.

## Layout

```
pom.xml                                   Maven build (spring-boot-starter-parent 4.1.1)
mvnw, mvnw.cmd, .mvn/wrapper/             Maven wrapper (script-only, Maven 3.9.9)
src/main/java/ch/mmar/testautomation/     application code
src/main/resources/application.yml        configuration
src/test/java/...                         tests (context-load smoke test)
docs/                                     design documentation
```

Group id `ch.mmar.testautomation`, base package `ch.mmar.testautomation`, single module.
Dependencies are intentionally limited to `spring-boot-starter` plus `spring-boot-starter-test`;
web, JDBC and queue dependencies are added once the design below is settled.

## Documentation

- [`docs/postgres-job-queue.md`](docs/postgres-job-queue.md) — how a PostgreSQL job queue works
  (`SELECT ... FOR UPDATE SKIP LOCKED`, job states, lease/reaper, `LISTEN/NOTIFY`), which ports and
  connections it needs (PostgreSQL 5432, PgBouncer 6432, pool sizing vs `max_connections`), Java
  library options and the decisions still open.

## Next steps

1. Decide the agent claim protocol (REST long-poll vs short-poll) and the job lifecycle details.
2. Add `spring-boot-starter-jdbc` + PostgreSQL driver and the `jobs` table (Flyway or plain SQL).
3. Add the REST layer for agent registration, claim, heartbeat and result submission.
