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

Skeleton plus a **generated REST layer** — the endpoints exist and answer (with the generated
delegate's default `501 Not Implemented`) but are **not implemented**: no queue, no persistence,
no agent client loop yet.
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
(`spring-boot-maven-plugin:repackage`). The agent still exits immediately (nothing non-daemon
registered); the service starts Tomcat on port 8080.

## REST layer: OpenAPI + delegate pattern

`openapi/testautomation-service.yaml` is the single source of truth for the HTTP contract. The
build generates the REST layer from it — **nothing is generated into `src/main/java`**, so
hand-written code can never be overwritten by a build step.

```bash
./mvnw -pl service generate-sources   # just regenerate (add -am if the parent changed too)
./mvnw -pl service verify             # generate + compile + test
```

Generated into `service/target/generated-sources/openapi/src/main/java/` (git-ignored, added to the
compile source root by the plugin):

```
ch/mmar/testautomation/service/api/JobsApi.java           @RequestMapping/@Validated interface
ch/mmar/testautomation/service/api/JobsApiController.java @Controller, injects the delegate
ch/mmar/testautomation/service/api/JobsApiDelegate.java   ← the interface you implement
ch/mmar/testautomation/service/model/…                    TestJob, TestJobResult, ApiError, …
```

### What you write

Implement the delegate interface and annotate the class as a Spring bean — the generated controller
wires it up automatically:

```java
package ch.mmar.testautomation.service.impl;          // any package below ch.mmar.testautomation.service

@Service
public class JobsApiDelegateImpl implements JobsApiDelegate {

    @Override
    public ResponseEntity<TestJob> claimTestJob(JobClaimRequest request, Integer wait) {
        return ResponseEntity.noContent().build();     // 204 = nothing to do, agent polls again
    }

    @Override
    public ResponseEntity<TestJobLease> renewTestJobLease(UUID jobId) { … }

    @Override
    public ResponseEntity<TestJobResultAck> submitTestJobResult(UUID jobId, TestJobResult result) { … }
}
```

Rules that matter:

- **One bean per delegate interface.** The generated controller uses
  `@Autowired(required = false)` *without* a `@Qualifier`, so a second `JobsApiDelegate` bean breaks
  the context; no bean at all is legal and simply yields `501 Not Implemented` on every endpoint.
- Add endpoints in the YAML, not in the generated files — regenerating overwrites them (they carry a
  `@Generated` marker and the header `Do not edit the class manually`).
- Paths must already include `/api/v1`; the generated `@RequestMapping` on the interface reads the
  property `openapi.testautomationService.base-path` (default empty). Leave it empty or the prefix
  is applied twice.
- The generated controller is a `@Controller`; the interface methods carry the mapping and body
  binding, bean validation is on (`@Valid` request bodies → `400` on violation, verified).

### Verified behaviour (2026-10-12, service jar on port 8080)

| Request | Without a delegate bean | With a hand-written delegate |
|---|---|---|
| `POST /api/v1/jobs/claim?wait=1` | `501` | `204` (no job) |
| `PUT /api/v1/jobs/{id}/lease` | `501` | `200` + `{"jobId":…,"leaseExpiresAt":…,"heartbeatIntervalSeconds":60}` |
| `POST /api/v1/jobs/{id}/result` | `501` | `200` + `{"jobId":…,"status":"PASSED","acceptedAt":…}` |
| `POST /api/v1/jobs/claim` with `{}` (missing required `agentId`) | `400` | `400` |

## Layout

```
pom.xml                                       parent/aggregator (spring-boot-starter-parent 4.1.1)
mvnw, mvnw.cmd, .mvn/wrapper/                 Maven wrapper (script-only, Maven 3.9.9)
openapi/testautomation-service.yaml           HTTP contract, source of truth for generated code
openapi/.openapi-generator-ignore             keeps the generator's sample app out of the output
service/pom.xml                               testautomation-service module (web + OpenAPI generation)
service/src/main/java/ch/mmar/testautomation/service/  hand-written code, incl. the delegate impl
service/src/main/resources/application.yml
service/src/test/java/...                     context-load smoke test
agent/pom.xml                                 testautomation-agent module
agent/src/main/java/ch/mmar/testautomation/agent/
agent/src/main/resources/application.yml
agent/src/test/java/...                       context-load smoke test
docs/                                         design documentation
```

Group id `ch.mmar.testautomation`; packages `ch.mmar.testautomation.service` and
`ch.mmar.testautomation.agent`. Dependencies stay minimal: `service` has the web starter,
validation and the Swagger annotations the generated code needs; `agent` is still just
`spring-boot-starter` (no HTTP client yet). Persistence and queue dependencies are added once the
design below is settled.

## Documentation

- [`docs/postgres-job-queue.md`](docs/postgres-job-queue.md) — how a PostgreSQL job queue works
  (`SELECT ... FOR UPDATE SKIP LOCKED`, job states, lease/reaper, `LISTEN/NOTIFY`), which ports and
  connections it needs (PostgreSQL 5432, PgBouncer 6432, pool sizing vs `max_connections`), how
  agents idle and learn about new work (§8: long polling, in-process wake-up), how it compares to a
  dedicated message broker (§9: RabbitMQ/Kafka/SQS, dimension by dimension) and the decisions still
  open (§7).
- [`docs/service-api-sequences.md`](docs/service-api-sequences.md) — who calls which endpoint and in
  which order (Jira client / desktop registering runs, agents claiming and reporting), job identity
  (issue key in, service-generated `jobRunId`; every registration is an independent run), the state
  machine incl. `DEAD`, failure semantics, agent inventory, capability matching and the Jira status
  push. **Proposal, awaiting review** — the spec has not been changed to match it yet.

## Next steps

1. Implement `JobsApiDelegate` in `service/src/main/java/…` (hand-written, survives regeneration) —
   start with `claimTestJob` returning `204`.
2. Decide the remaining job-lifecycle details and add `spring-boot-starter-jdbc` + PostgreSQL driver
   to `service` plus the `jobs` table (Flyway or plain SQL), the claim query, lease and reaper
   (`docs/postgres-job-queue.md` §7).
3. Generate the client for `agent` from the same YAML (openapi-generator `java`/`spring` client
   generator) and add the long-poll loop (`docs/postgres-job-queue.md` §8).
4. Decide authentication for agents and add the security scheme to the spec — the `401` responses
   are already declared, nothing enforces them yet.
