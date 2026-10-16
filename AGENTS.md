# testautomation — Agent Guide

## Rule zero: pull before you touch anything

`git pull` **before doing anything else in this repository**, every session, without being asked.
Changes may have been pushed from another machine or session in between (review commits, IDE edits,
manual fixes). Sequence:

```bash
cd /opt/data/projects/testautomation     # clone path on the Hermes VM
git pull --ff-only                       # stop and report if this is not a fast-forward
git status -sb                           # working tree must be clean before starting work
git log --oneline -5                     # see what arrived
```

If the pull is not a fast-forward, or the working tree has uncommitted changes that are not yours:
**stop and ask** — do not rebase, stash or force anything on your own.

## Directory Layout

| Path | Language | Purpose |
|---|---|---|
| `pom.xml` | Maven | Parent + aggregator (`testautomation-parent`, `packaging=pom`, parent `spring-boot-starter-parent` 4.1.1) |
| `openapi/` | YAML | The HTTP contract (`testautomation-service.yaml`) — source of truth for the generated REST layer |
| `service/` | Java 21 | `testautomation-service` — owns the job queue and the REST API for distributing test cases |
| `agent/` | Java 21 | `testautomation-agent` — claims test cases and executes them |
| `docs/` | Markdown | Design documentation (`postgres-job-queue.md` = queue mechanics, `service-api-sequences.md` = endpoint semantics and call sequence) |
| `mvnw`, `.mvn/wrapper/` | shell | Maven wrapper (script-only, Maven 3.9.9) |

Package roots: `ch.mmar.testautomation.service` and `ch.mmar.testautomation.agent`.
Both modules are Spring Boot applications; they share no code yet (a `shared/` module is the
intended home for the wire DTOs once the claim protocol is fixed).

## Commands

```bash
./mvnw verify                        # both modules: compile, tests, executable boot jars
./mvnw -pl service verify            # single module
./mvnw -pl agent -am verify          # module plus its parent chain
./mvnw -pl service spring-boot:run   # run from source
java -jar service/target/testautomation-service-0.1.0-SNAPSHOT.jar
java -jar agent/target/testautomation-agent-0.1.0-SNAPSHOT.jar
```

Toolchain on the Hermes VM: JDK 21 at `/opt/data/tools/jdk-21`, Maven at
`/opt/data/tools/maven-3.9.9` (both already on `PATH` for interactive shells). `./mvnw` also works
with no local Maven. In a **non-interactive/background** shell neither `PATH` nor `JAVA_HOME` is set:
`java` is not found, and `./mvnw` aborts with "The JAVA_HOME environment variable is not defined
correctly" before Maven even starts — export `JAVA_HOME=/opt/data/tools/jdk-21` and, when calling the
JDK directly, use `/opt/data/tools/jdk-21/bin/java`.

## Critical Gotchas

- **Branch `main` only.** No PR workflow, no CI, no formatter or lint config in this repo yet.
  Push directly to `main` after `./mvnw verify` passes.
- **Do not pin dependency versions in `service/pom.xml` / `agent/pom.xml`** — versions come from
  the parent chain (Spring Boot BOM). Add a dependency without `<version>` and let the BOM decide.
  Two documented exceptions exist because the BOM does not manage them: `swagger-annotations-jakarta`
  and `springdoc-openapi-starter-webmvc-ui` (each has the reason as a comment in the `<properties>`
  block of `service/pom.xml`). Testcontainers is *not* an exception: `spring-boot-dependencies` 4.1.1
  imports `testcontainers-bom` 2.0.5.
- **Testcontainers is the 2.x line** — artifacts `org.testcontainers:testcontainers-postgresql` and
  `:testcontainers-junit-jupiter` (2.x renamed every module artifact with a `testcontainers-` prefix),
  classes in per-module packages (`org.testcontainers.postgresql.PostgreSQLContainer`, now a raw type
  without the self-referencing generic); `org.testcontainers.DockerClientFactory` and
  `org.testcontainers.junit.jupiter.{Testcontainers,Container}` kept their packages. **Never reintroduce
  a 1.x artifact or the 1.x import** — besides the artifact renames, 1.x bundles docker-java 3.4.2,
  which sends Docker API 1.32, and every daemon from Docker 29 on rejects that with
  `400 client version 1.32 is too old. Minimum supported API version is 1.40`. 2.0.5 bundles
  docker-java 3.7.1, which negotiates the version with the daemon.
- **`spring-boot-maven-plugin` belongs in each module, not in the root POM** — declaring it in the
  aggregator makes the `repackage` goal run for the `pom`-packaging root and fail.
- **Generated REST layer — never edit generated files.** `service/target/generated-sources/openapi`
  is produced by openapi-generator from `openapi/testautomation-service.yaml` on every build. Change
  the YAML, regenerate (`./mvnw -pl service generate-sources`), and check the result compiles.
  Hand-written code goes in `service/src/main/java/…` only; nothing is generated there.
- **One class per delegate interface, one bean per class.** There are two delegates now
  (`JobsApiDelegate` for tag `Jobs`, `AgentsApiDelegate` for tag `Agents`). Both declare their own
  `default getRequest()`, so a class implementing both does not compile ("inherits unrelated
  defaults for getRequest()"). Each generated controller injects its delegate with
  `@Autowired(required = false)` and no `@Qualifier`: a second bean of the same interface breaks the
  context, no bean is legal and answers `501` on every endpoint.
- **Error responses are still Spring Boot's default shape**, not the `ApiError` the spec declares.
  Worse, the delegate *cannot* produce that body itself: every method returns
  `ResponseEntity<JobRun>` (or another success model), so an error payload has to come from a thrown
  exception handled by a `@RestControllerAdvice`. Verified: the delegated `400` path answered with an
  empty body.
- **Do not set `openapi.testautomationService.base-path`** — the spec paths already start with
  `/api/v1`, the property only adds a prefix.
- **`openapi/.openapi-generator-ignore` is load-bearing.** It keeps the generator's sample app, its
  `application.properties` and two classes in the foreign `org.openapitools.configuration` package
  out of the output. Removing rules can break the build (the sample's `RFC3339DateFormat` does not
  compile against Spring Boot 4 / Jackson 3) or silently add a second Spring Boot main class.
- **Never test the queue against H2.** H2 2.3.232 *accepts* `FOR UPDATE SKIP LOCKED` but locks at table
  level: a concurrent claimer gets no rows at all (or a lock timeout without `SKIP LOCKED`), and
  `UPDATE ... RETURNING`, partial indexes, `ON CONFLICT`, `LISTEN/NOTIFY`, `jsonb` and
  `pg_advisory_lock` are all rejected. A green H2 test proves nothing about the distribution — read
  `docs/test-strategy.md` (measured side by side against PostgreSQL 16.4) before adding DB tests.
- **The queue schema is `service/src/test/resources/local_pg_database_docker/schema.sql`** — canonical
  DDL, applied by the compose file next to it, by the integration tests (after a schema reset) and one
  day by the production migration. It contains no `DROP` and no PL/pgSQL on purpose: it must apply
  identically through the compose entrypoint, Testcontainers and `psql`. Reasoning:
  `docs/postgres-job-queue.md` §10.
- **Unit tests are `*Test`, integration tests are `*IT`.** Surefire excludes `**/*IT.java`; failsafe
  runs it in the `verify` phase. So `mvn test` never needs a database, `./mvnw verify` runs everything,
  and the integration tests skip with an explanation when neither Docker nor `-DTA_IT_JDBC_URL=…` is
  available (`docs/test-execution.md`). Never add a database-dependent `*Test`.
- **Docker is not available on the Hermes VM** (no `/var/run/docker.sock`, no podman), so
  Testcontainers cannot run here — but a local PostgreSQL 16 sits at
  `/opt/data/tools/postgresql-16/bin` and can be started on a scratch data directory
  (`docs/test-strategy.md` §3 has the commands). Keep the JDBC URL overridable so the same
  integration tests run on a Docker dev machine and here.
- The repository was renamed from `testautomation-service` to `testautomation` on GitHub. The old
  URL redirects, but always use the current one in remotes and docs. Local clone path:
  `/opt/data/projects/testautomation`.
