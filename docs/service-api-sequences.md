# Service API — semantics and request sequence

**Status: proposal, awaiting review.** The decisions from the product owner are folded in (see
§13); the OpenAPI spec (`openapi/testautomation-service.yaml`) is **not** changed yet. Once the open
points in §12 are settled, the spec is extended and this file becomes the description of the
implemented behaviour.

Companion documents: [`postgres-job-queue.md`](postgres-job-queue.md) (claim mechanics, job
lifecycle, lease/reaper, idle behaviour, broker comparison).

## 1. Actors and trust boundary

| Actor | Role | Database access |
|---|---|---|
| **Jira client** | Producer: registers job executions for a Jira issue. | none |
| **testautomation-desktop** | Producer *and* reader: may register executions, and reads job status/results. | none |
| **test agent** | Worker: claims an execution, heartbeats it, submits the result. | none |
| **testautomation-service** | Owns the queue and all state; the only component with a DB connection. | PostgreSQL |

Two consequences follow from "no database access for the clients":

1. **Every state transition is an HTTP call** the service validates — a client can never invent or
   repair state behind the service's back.
2. **The claim response must be self-contained**: everything needed to run the execution (parameters,
   tags, capability requirements, lease deadline, heartbeat interval) travels in the response. An
   agent that would have to look up something else is a broken contract.

## 2. Identity of a job execution

A job execution is identified on the wire by the pair

```
(issueKey, executionKey)
```

- `issueKey` — the Jira issue the execution belongs to (e.g. `TEST-4711`).
- `executionKey` — the execution identity within that issue (test case/scenario identifier; whether
  it identifies the test case itself or a single requested run is open, §12.1).

**The pair is the identity, not a service-generated UUID.** Result submission and status reads carry
the pair, so a client never needs to reconcile an internal id.

Re-registering the same pair is allowed and intended: it starts a fresh execution of that job and its
result **overwrites** the stored one. To keep that safe, the service keeps an internal
**generation** counter per pair:

- every registration increments the generation and puts the job back to `QUEUED`;
- the generation is handed to the agent with the claim and must be echoed on lease renewal and result
  submission;
- a result whose generation is not the current one is rejected with `409 STALE_EXECUTION` — it must
  never overwrite the newer execution's state (§7).

This is the one place where "overwrite is wanted" and "at-least-once delivery" collide, and the
generation is what reconciles them: *a new registration overwrites*, *an old in-flight execution
does not*.

## 3. Endpoints by caller

### 3.1 Producer / reader API (Jira client, testautomation-desktop)

| Method | Path | Semantics |
|---|---|---|
| `POST` | `/api/v1/jobs` | Register an execution for `(issueKey, executionKey)`. Body: `issueKey`, `executionKey`, `testCaseId`, `parameters{}`, `tags[]`, `requiresCapabilities[]`, `priority`, `maxAttempts`. `201` = new job, `200` = re-registration of an existing pair (generation bumped). |
| `GET` | `/api/v1/jobs/{issueKey}/{executionKey}` | Current state and, when finished, the result. Includes `DEAD` (§5) and the generation/attempt. |
| `GET` | `/api/v1/jobs` | List/filter (`issueKey`, `status`, `limit`) — needed by the desktop component for a queue view; proposal, §12.2. |
| `DELETE` | `/api/v1/jobs/{issueKey}/{executionKey}` | Cancel a queued or running execution — not requested yet, §12.5. |

### 3.2 Agent API (test agents)

| Method | Path | Semantics |
|---|---|---|
| `POST` | `/api/v1/agents` | Register/upsert this agent: `agentId`, `name`, `capabilities[]`, `labels{}`, `maxJobs`, `version`. Response carries `heartbeatIntervalSeconds` and the liveness window. Idempotent on `agentId`. |
| `POST` | `/api/v1/jobs/claim?wait=25` | Long poll. Body: `{agentId}` (capabilities come from the registration). `200` + job (with generation, lease expiry, heartbeat interval) or `204` when nothing was available within `wait`. |
| `PUT` | `/api/v1/jobs/{issueKey}/{executionKey}/lease` | Heartbeat while the test runs. Body: `generation`. `200` + new expiry, `409 STALE_EXECUTION`/`LEASE_LOST`. |
| `POST` | `/api/v1/jobs/{issueKey}/{executionKey}/result` | Terminal result. Body: `generation`, `status`, `startedAt`, `finishedAt`, `durationMs`, `message`, `stackTrace`, `artifacts[]` (in-band). `200` + ack, `409` = stale/lost. |
| `GET` | `/api/v1/agents` , `GET /api/v1/agents/{agentId}` | Agent inventory for ops/desktop: which agents exist, what they can do, when they were last seen. |
| `DELETE` | `/api/v1/agents/{agentId}` | Graceful shutdown: mark the agent offline instead of waiting for the liveness window. |

## 4. Sequence — registration, claim, execution, result

```
Jira client / desktop        Service (only DB owner)                 Test agent
      |                             |                                    |
      |-- 1 POST /jobs ------------>| upsert (issueKey,executionKey):    |
      |                             |   generation++, status=QUEUED,     |
      |                             |   previous RUNNING execution →     |
      |                             |   CANCELLED (stale)                |
      |<-- 201/200 {status, gen} ---|                                    |
      |                             |                                    |
      |                             |<-- 0 POST /agents (once) ----------| register capabilities
      |                             |--- 200 {heartbeatIntervalSeconds}->|
      |                             |<-- 2 POST /jobs/claim?wait=25 -----| agentId
      |                             | UPDATE ... WHERE status='QUEUED'   |
      |                             |   AND requires ⊆ agent.capabilities|
      |                             |   FOR UPDATE SKIP LOCKED           |
      |                             |   status=RUNNING, attempt++,       |
      |                             |   generation echoed, lease set     |
      |                             |--- 200 {job, leaseExpiresAt} ----->|
      |                             |                                    | runs the test
      |                             |<-- 3 PUT .../lease ----------------| every heartbeatInterval
      |                             |--- 200 {leaseExpiresAt} ---------->|
      |                             |<-- 4 POST .../result --------------| generation + status
      |                             | generation matches → SUCCEEDED/…   |
      |                             |   result stored, lease released,   |
      |                             |   status change + outbox row in    |
      |                             |   ONE commit                       |
      |                             |--- 200 {ack} --------------------->|
      |                             |                                    |
      |                             | 5 outbox worker → Jira REST API    |
      |<== status pushed to Jira ===|   (at-least-once, idempotent)      |
      |-- 6 GET /jobs/{issue}/{exec}|                                    |
      |<-- 200 {status, result} ----|                                    |
```

The empty-queue case is identical, except that step 2 holds the request for up to `wait` seconds and
answers `204`; the agent immediately calls again (see `postgres-job-queue.md` §8).

## 5. State machine

```
                    register (generation++)
                              │
                              ▼
        ┌────────────────▶ QUEUED ────claim────▶ RUNNING ────result────▶ SUCCEEDED
        │                    ▲                     │              (or FAILED / ERROR / SKIPPED)
        │                    │                     │
        │              lease expired          new registration
        │              (attempt++)           for the same pair
        │                    │                     │
        │                    │                     ▼
        │                    │                CANCELLED (stale generation)
        │                    │                     │
        └────────────────────┴─────────────────────┘  → fresh QUEUED run
                             │
                    attempt > maxAttempts
                             ▼
                           DEAD  ──▶ producer is notified (§9)
```

`DEAD` is a first-class status: it is returned by `GET /api/v1/jobs/{issueKey}/{executionKey}` and
included in the list endpoint, so the desktop component and the Jira client can both see it. It is
terminal — a new execution requires a new registration (which bumps the generation).

## 6. Capability matching

- The job carries `requiresCapabilities[]` (from the producer, or derived from the test case
  definition once that exists).
- The agent registers `capabilities[]` once.
- Claim matches `job.requiresCapabilities ⊆ agent.capabilities` in the same `WHERE` clause as the
  queue selection — it costs nothing extra and keeps routing in SQL (see `postgres-job-queue.md` §9).
- An empty `requiresCapabilities` means "any registered agent".

## 7. Failure semantics

| Situation | Behaviour |
|---|---|
| Claim response lost in transit | The execution is already leased; the agent never saw it. After lease expiry the reaper requeues it with `attempt++`. Unavoidable — **every execution must be safe to repeat**. |
| Agent crashes mid-test | Same path: lease expires, `attempt++`, another agent runs it. |
| Agent stalls but is alive | Heartbeat keeps the lease; no heartbeat → reclaimed. |
| Late result of an **old generation** | `409 STALE_EXECUTION`; the agent discards it. It never overwrites the newer execution. |
| Re-registration while an execution is RUNNING | The running execution is marked `CANCELLED` (stale); the agent learns on its next heartbeat (`409`) and aborts. A fresh `QUEUED` run exists for the new generation. |
| Result POST retried after a network error | Idempotent on `(issueKey, executionKey, generation)`: the second call returns the same ack. |
| Job exhausts `maxAttempts` | `DEAD`, terminal, producer notified (§9). |
| Service restart | Nothing to recover — all state is in PostgreSQL. |
| Agent re-registers (`POST /agents` twice) | Upsert on `agentId`; capabilities and `maxJobs` are refreshed. |

## 8. Agent inventory

Requirement: know which agents exist, what they can do and whether they are alive — without giving
agents database access.

- **Registration** (`POST /api/v1/agents`) is the inventory's source of truth: `agentId` (stable, from
  the agent's config), `name`, `capabilities[]`, `labels{}`, `maxJobs`, `version`, `registeredAt`.
- **Liveness** is derived, not polled separately: every authenticated agent call (claim, lease,
  result) updates `lastSeenAt`. An idle agent is still "alive" as long as its long-poll cycle keeps
  running (≤ `wait` seconds between calls).
  - `ACTIVE` — seen within the liveness window (`2 × wait` + margin, e.g. 90 s).
  - `STALE` — not seen within that window; still claimable in theory, but visibly unhealthy.
  - `OFFLINE` — explicit `DELETE /api/v1/agents/{agentId}` on graceful shutdown.
- **Concurrency** (`maxJobs`) is enforced server-side at claim time by counting the agent's active
  leases — the agent cannot see the queue, so the service is the only place that can be sure.
- Optional: the desktop component reads `GET /api/v1/agents` for an ops view. Whether a dedicated
  heartbeat endpoint is needed for agents that are neither claiming nor running is open (§12.4).

## 9. Jira status push

The service pushes status updates to Jira's REST API; clients never do this themselves.

- **Which transitions**: at least terminal states (`SUCCEEDED`, `FAILED`, `ERROR`, `SKIPPED`),
  `CANCELLED` and `DEAD`; whether `QUEUED`/`RUNNING` are also pushed (noisy in Jira) is open (§12.3).
- **How**: a transactional outbox. The status change and an outbox row are written in the same
  commit; a service-side worker drains the outbox against the Jira REST API with retries. This keeps
  "job state changed" and "Jira knows about it" from drifting apart, and it reuses the queue
  machinery from `postgres-job-queue.md` (§1–§3) with Jira as the consumer.
- **At-least-once**: Jira calls are retried; the update must therefore be idempotent on the Jira side
  (comment marker per `(issueKey, executionKey, generation)` or a field write that is naturally
  idempotent).
- **Failure is not job failure**: if Jira is unreachable, the job result stays valid, the outbox entry
  stays queued and is retried. Jira being down must never lose a result.

## 10. Authentication and roles

Three roles with distinct credentials, so an agent cannot register jobs and a producer cannot claim
them:

| Role | Can |
|---|---|
| `producer` (Jira client, desktop) | register, read status, list |
| `agent` | register itself, claim, lease, result |
| `ops` (desktop, admin) | read inventory and queue views |

The mechanism (static bearer tokens per role, mTLS, or something else) is not decided; the spec's
`401` responses are already declared but nothing enforces them.

## 11. Impact on the current OpenAPI spec

Once the open points are settled, the spec changes to:

- `POST /api/v1/jobs` — registration (idempotent pair + generation).
- `GET /api/v1/jobs/{issueKey}/{executionKey}` — status/result incl. `DEAD`.
- `GET /api/v1/jobs` — list/filter (desktop).
- `POST /api/v1/agents`, `GET /api/v1/agents`, `GET /api/v1/agents/{agentId}`,
  `DELETE /api/v1/agents/{agentId}` — inventory.
- Path parameters switch from the service UUID to `(issueKey, executionKey)` for lease and result.
- `TestJob` gains `generation` and `heartbeatIntervalSeconds` (so the agent knows the cadence right
  after claiming, not only after the first renewal).
- `TestJob` gains `requiresCapabilities[]`; `JobClaimRequest` shrinks to `{agentId}`
  (capabilities move to the agent registration).
- `TestResultStatus` gains `CANCELLED`→ already present; add `DEAD` and a stale-execution error code
  (`STALE_EXECUTION`) to `ApiError`.

## 12. Open points (need a decision)

1. **`executionKey` semantics** — does it identify the test case, or one requested run of it? If a
   test case can be requested twice in parallel per issue, the identity needs a third element.
2. **List endpoint** — does testautomation-desktop need `GET /api/v1/jobs` (queue view over all
   issues), or is per-job `GET` enough?
3. **Jira push scope** — terminal + `DEAD` only, or also `QUEUED`/`RUNNING`? Into the issue as a
   comment, a custom field, or a workflow transition?
4. **Agent liveness** — derive from claim/lease/result calls (recommended), or add an explicit
   heartbeat endpoint for idle agents?
5. **Cancellation** — `DELETE /api/v1/jobs/...` now, or later? Same question for "cancel all
   executions of an issue".
6. **Where testautomation-desktop lives** — third root folder in this repository (`desktop/`,
   language/stack?), or a separate repository? It defines whether we need a generated client module
   here.
