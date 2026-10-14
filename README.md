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

Skeleton plus a **generated REST layer** — ten endpoints exist and are routed (answering the
generated delegates' default `501 Not Implemented`), but nothing is implemented behind them: no
queue, no persistence, no Jira push, no agent client loop.
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
compile source root by the plugin) — two tags, so two controllers and two delegate interfaces:

```
api/JobsApi.java + JobsApiController.java + JobsApiDelegate.java      tag Jobs
api/AgentsApi.java + AgentsApiController.java + AgentsApiDelegate.java tag Agents
api/ApiUtil.java
model/…                                                               JobRun, ClaimedJobRun, Agent, …
```

| Endpoint | Caller | Returns |
|---|---|---|
| `POST /api/v1/jobs` | producer (Jira client, desktop, curl) | `200` `JobRun` (`source: JIRA` with an `issueKey` → `status: PENDING` until the scheduled Jira collection fills in the details; `AD_HOC` with a `testName` alone → `QUEUED` at once); optional `Idempotency-Key` header. No Jira call on this path |
| `GET /api/v1/jobs` | producer / ops | `200` `JobRunPage` (`issueKey`, `status`, `limit`, `offset`) |
| `GET /api/v1/jobs/{jobRunId}` | producer / ops | `200` `JobRun` (incl. result), `404` |
| `POST /api/v1/jobs/claim?wait=` | agent | `200` `ClaimedJobRun`, `204` when nothing is claimable |
| `PUT /api/v1/jobs/{jobRunId}/lease` | agent | `200` `JobRunLease`, `409` lease lost |
| `POST /api/v1/jobs/{jobRunId}/result` | agent | `200` `JobRunResultAck`, `409` lease lost / already terminal |
| `POST /api/v1/agents` | agent | `200` `Agent` (upsert on `agentId`) |
| `GET /api/v1/agents` | ops / desktop | `200` `Array<Agent>` (`status` filter) |
| `GET /api/v1/agents/{agentId}` | ops / desktop | `200` `Agent`, `404` |
| `DELETE /api/v1/agents/{agentId}` | agent | `204` (marks it `OFFLINE`) |

### What you write

One `@Service` class per delegate interface — the generated controllers wire them up automatically:

```java
package ch.mmar.testautomation.service.impl;   // any package below ch.mmar.testautomation.service

@Service
public class JobsApiDelegateImpl implements JobsApiDelegate {

    @Override
    public ResponseEntity<JobRun> registerJobRun(JobRunRegistration registration, String idempotencyKey) { … }

    @Override
    public ResponseEntity<JobRunPage> listJobRuns(String issueKey, JobRunStatus status, Integer limit, Integer offset) { … }

    @Override
    public ResponseEntity<JobRun> getJobRun(UUID jobRunId) { … }

    @Override
    public ResponseEntity<ClaimedJobRun> claimJobRun(JobClaimRequest request, Integer wait) { … }

    @Override
    public ResponseEntity<JobRunLease> renewJobRunLease(UUID jobRunId) { … }

    @Override
    public ResponseEntity<JobRunResultAck> submitJobRunResult(UUID jobRunId, JobRunResult result) { … }
}

@Service
public class AgentsApiDelegateImpl implements AgentsApiDelegate {

    @Override
    public ResponseEntity<Agent> registerAgent(AgentRegistration registration) { … }

    @Override
    public ResponseEntity<List<Agent>> listAgents(AgentStatus status) { … }

    @Override
    public ResponseEntity<Agent> getAgent(String agentId) { … }

    @Override
    public ResponseEntity<Void> deregisterAgent(String agentId) { … }
}
```

Rules that matter:

- **One class per delegate interface, one bean per class.** `JobsApiDelegate` and `AgentsApiDelegate`
  each declare their own `default getRequest()`, so a single class implementing both does **not**
  compile ("inherits unrelated defaults"). And each generated controller uses
  `@Autowired(required = false)` *without* a `@Qualifier`: a second bean of the same interface breaks
  the context, no bean at all is legal and yields `501 Not Implemented` everywhere.
- Add endpoints in the YAML, not in the generated files — regenerating overwrites them (they carry a
  `@Generated` marker and the header `Do not edit the class manually`).
- Paths must already include `/api/v1`; the generated `@RequestMapping` on the interface reads the
  property `openapi.testautomationService.base-path` (default empty). Leave it empty or the prefix
  is applied twice.
- The generated controllers are `@Controller`; the interface methods carry the mapping and body
  binding, bean validation is on (`@Valid` request bodies → `400` on violation, verified).
- **Error bodies are not `ApiError` yet — and the delegate cannot make them.** Validation and
  conversion failures are answered by Spring Boot's default error JSON
  (`{"timestamp":…,"error":"Bad Request",…}`), not by the shape the spec declares. Every delegate
  method returns a success model (`ResponseEntity<JobRun>`), so an `ApiError` body has to come from a
  thrown exception handled by an `@RestControllerAdvice` — which is still missing.

### Verified behaviour (2026-10-14, temporary probes against the service jar on port 8080)

| Request | Result |
|---|---|
| `POST /api/v1/jobs` with `{"issueKey":"TEST-4711",…}` | `200`, `source: JIRA`, `status: PENDING`, **no** `Location` header |
| `POST /api/v1/jobs` with `{"testName":"login-smoke"}` | `200`, `source: AD_HOC`, `status: QUEUED`, no `issueKey` in the response |
| `POST /api/v1/jobs` with `issueKey` **and** `testName` | `200`, `source: JIRA`, `testName` recorded on the run |
| `POST /api/v1/jobs` with neither field | `400` — enforced by the delegate; the generated models do not reject it |
| `GET /api/v1/jobs?issueKey=TEST-4711&status=PENDING&limit=5&offset=0` | `200`, the new enum value binds, all four parameters bound |
| `GET /api/v1/jobs?issueKey=TEST-4711&status=RUNNING&limit=5&offset=10` | `200`, all four parameters bound (enum included) |
| `GET /api/v1/jobs?status=BOGUS` | `400` (enum conversion rejected) |
| `POST /api/v1/jobs/claim?wait=7` + `{"agentId":"agent-01"}` | `200` `ClaimedJobRun` incl. `heartbeatIntervalSeconds` |
| `PUT …/lease`, `POST …/result` | `200` `JobRunLease`, `200` `JobRunResultAck` with the reported status |
| `POST /api/v1/agents`, `GET /api/v1/agents?status=STALE`, `GET /api/v1/agents/{id}`, `DELETE …` | `200`, `200`, `200`, `204` |
| any endpoint of a delegate left unimplemented | `501` |

The `400` path answered with an **empty body**: the delegate cannot render `ApiError` (above). The
probes were temporary (not committed).

## Layout

```
pom.xml                                       parent/aggregator (spring-boot-starter-parent 4.1.1)
mvnw, mvnw.cmd, .mvn/wrapper/                 Maven wrapper (script-only, Maven 3.9.9)
openapi/testautomation-service.yaml           HTTP contract, source of truth for generated code
openapi/.openapi-generator-ignore             keeps the generator's sample app out of the output
service/pom.xml                               testautomation-service module (web + OpenAPI generation)
service/src/main/java/ch/mmar/testautomation/service/  hand-written code, incl. the delegate impls
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
  which order (Jira client / desktop / curl registering runs, agents claiming and reporting), run
  identity (either an issue key or a test name in, service-generated `jobRunId` out; every
  registration is an independent run), the two sources (§2.1: `JIRA` = details fetched from Jira and
  result pushed back, `AD_HOC` = no Jira contact at all), the state machine incl. `DEAD`, failure
  semantics, agent inventory, capability matching and the Jira status push. §11 mirrors the spec; §12
  lists the semantics still open.

## Next steps

1. Implement both delegates in `service/src/main/java/…` (hand-written, survives regeneration) —
   start with `registerJobRun` + `claimJobRun`, and add the `@RestControllerAdvice` that renders
   `ApiError`. `registerJobRun` persists only (no Jira call, `200`, `PENDING`/`QUEUED`); the Jira
   collection is a scheduled job that moves `PENDING` → `QUEUED`.
2. Add `spring-boot-starter-jdbc` + PostgreSQL driver to `service`, the `jobs` table (Flyway or plain
   SQL), the claim query (**excluding `PENDING` runs**), lease and reaper
   (`docs/postgres-job-queue.md` §1–§3, §7).
3. Generate the client for `agent` from the same YAML (openapi-generator client generator) and add
   the long-poll loop (`docs/postgres-job-queue.md` §8).
4. Jira push via transactional outbox, and enforce the role model (`docs/service-api-sequences.md`
   §9, §10).
5. Settle the open semantics in `docs/service-api-sequences.md` §12 (what determines the test to run,
   registration retry safety, Jira representation of several runs, liveness, cancellation, releasing
   a claimed run, per-run runtime limit, issue-key validation, error shape).
