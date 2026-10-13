# Service API — semantics and request sequence

**Status: proposal, awaiting review.** Product-owner decisions are folded in; the OpenAPI spec
(`openapi/testautomation-service.yaml`) is **not** changed yet. Once the open points in §12 are
settled, the spec is extended and this file becomes the description of the implemented behaviour.

**Rev 2** — registration identity corrected: a registration carries the **issue key only**, the
service creates a **`jobRunId`**, and every registration is an **independent run**. The
generation/overwrite mechanism of Rev 1 is gone (§2).

Companion document: [`postgres-job-queue.md`](postgres-job-queue.md) (claim mechanics, job
lifecycle, lease/reaper, idle behaviour, broker comparison).

## 1. Actors and trust boundary

| Actor | Role | Database access |
|---|---|---|
| **Jira client** | Producer: registers a run for a Jira issue. | none |
| **testautomation-desktop** | Producer *and* reader: may register runs, and reads job status/results. | none |
| **test agent** | Worker: claims a run, heartbeats it, submits the result. | none |
| **testautomation-service** | Owns the queue and all state; the only component with a DB connection. Also pushes results to Jira. | PostgreSQL |

Two consequences follow from "no database access for the clients":

1. **Every state transition is an HTTP call** the service validates — a client can never invent or
   repair state behind the service's back.
2. **The claim response must be self-contained**: everything needed to run the execution (parameters,
   capability requirements, lease deadline, heartbeat interval) travels in the response. An agent
   that would have to look something up elsewhere is a broken contract.

## 2. Identity: one registration = one independent run

```
POST /api/v1/jobs   { issueKey: "TEST-4711", parameters: { … } }
   → 201 { jobRunId: "9f1c…", issueKey: "TEST-4711", status: "QUEUED", createdAt: … }
```

- The client sends the **issue key only** (plus parameters). It does not choose an execution id.
- The service generates a **`jobRunId`** (UUID) per registration. That id is the wire identity of the
  run: the agent receives it at claim time and uses it for lease renewal and result submission.
- **Registration is deliberately not idempotent.** Two calls with the same `issueKey` produce two
  distinct runs, and both are executed. No dedupe, no overwrite, no generation counter.
- Runs of the same issue are **fully independent**: separate queue rows, leases, attempts and results.
  They execute in any order and finish in any order.
- The service pushes each run's result to Jira **as soon as that run is finished** — it does not wait
  for sibling runs of the same issue and does not care which one finishes last.

What this costs, stated plainly: a producer that retries a registration whose response it never saw
creates a second run. For Jira webhooks (at-least-once on their side) that is a real hazard.
Mitigation is opt-in, not default: an optional `Idempotency-Key` request header stored per producer,
so a caller that wants retry safety can have it without changing the semantics for everyone else
(§12.2).

## 3. Endpoints by caller

### 3.1 Producer / reader API (Jira client, testautomation-desktop)

| Method | Path | Semantics |
|---|---|---|
| `POST` | `/api/v1/jobs` | Register a run. Body: `issueKey`, `parameters{}`, `requiresCapabilities[]`, `priority`, `maxAttempts`. Always creates a run → `201` + `{jobRunId, issueKey, status, createdAt}`. |
| `GET` | `/api/v1/jobs/{jobRunId}` | State and, when finished, the result of one run. |
| `GET` | `/api/v1/jobs` | List runs, filter by `issueKey`, `status`, `limit`. An issue can have several runs, so this is what answers "what happened with TEST-4711?". |
| `DELETE` | `/api/v1/jobs/{jobRunId}` | Cancel a queued or running run — not requested yet, §12.5. |

### 3.2 Agent API (test agents)

| Method | Path | Semantics |
|---|---|---|
| `POST` | `/api/v1/agents` | Register/upsert this agent: `agentId`, `name`, `capabilities[]`, `labels{}`, `maxJobs`, `version`. Idempotent on `agentId`. Response carries `heartbeatIntervalSeconds` and the liveness window. |
| `POST` | `/api/v1/jobs/claim?wait=25` | Long poll. Body: `{agentId}` (capabilities come from the registration). `200` + run (with `jobRunId`, `issueKey`, `parameters`, lease expiry, heartbeat interval) or `204` when nothing was available within `wait`. |
| `PUT` | `/api/v1/jobs/{jobRunId}/lease` | Heartbeat while the test runs. `200` + new expiry; `409` when the run is no longer this agent's. |
| `POST` | `/api/v1/jobs/{jobRunId}/result` | Terminal result. Body: `status`, `startedAt`, `finishedAt`, `durationMs`, `message`, `stackTrace`, `artifacts[]` (in-band). `200` + ack; `409` when the run is already terminal. |
| `GET` | `/api/v1/agents` , `GET /api/v1/agents/{agentId}` | Agent inventory for ops/desktop: which agents exist, what they can do, when they were last seen. |
| `DELETE` | `/api/v1/agents/{agentId}` | Graceful shutdown: mark the agent offline instead of waiting for the liveness window. |

## 4. Sequence — two runs of the same issue

```
Jira client / desktop        Service (only DB owner)                 Test agent A / B
      |                             |                                     |
      |-- 1 POST /jobs TEST-4711 -->| run 9f1c… : QUEUED                  |
      |<-- 201 {jobRunId} ----------|                                     |
      |-- 2 POST /jobs TEST-4711 -->| run 4ab2… : QUEUED  (independent!)  |
      |<-- 201 {jobRunId} ----------|                                     |
      |                             |<-- 3 POST /agents (once per agent)--|  register capabilities
      |                             |--- 200 {heartbeatIntervalSeconds}-->|
      |                             |<-- 4 POST /jobs/claim?wait=25 ------|  agent A
      |                             | 9f1c… → RUNNING, lease, attempt=1   |
      |                             |--- 200 {jobRunId 9f1c…} ----------->|
      |                             |<-- 4 POST /jobs/claim?wait=25 ------|  agent B
      |                             | 4ab2… → RUNNING (parallel to A)     |
      |                             |--- 200 {jobRunId 4ab2…} ----------->|
      |                             |<-- 5 PUT 4ab2…/lease ---------------|  B heartbeats
      |                             |<-- 6 POST 4ab2…/result -------------|  B finishes first
      |                             | 4ab2… = SUCCEEDED, result stored,    |
      |                             | status change + outbox row, 1 commit |
      |                             |--- 200 {ack} ---------------------->|
      |                             | 7 outbox worker → Jira REST API      |
      |<== result for 4ab2… ========|   (at-least-once, idempotent)       |
      |                             |<-- 8 POST 9f1c…/result -------------|  A finishes later
      |                             | 9f1c… = FAILED, its own Jira push    |
      |<== result for 9f1c… ========|                                     |
```

Out-of-order completion is normal here, not an edge case: each run's update is pushed on its own,
carries its own `jobRunId`, and never touches another run's representation (§9).

The empty-queue case is identical, except that step 4 holds the request for up to `wait` seconds and
answers `204`; the agent immediately calls again (see `postgres-job-queue.md` §8).

## 5. State machine

```
   register → QUEUED ──claim──▶ RUNNING ──first result──▶ SUCCEEDED | FAILED | ERROR | SKIPPED
                ▲                 │
                │                 ├── lease expired → QUEUED again, attempt++
                │                 │        └── attempt > maxAttempts → DEAD
                │                 │
                │                 └── late result of a superseded attempt → 409, discarded
                │                     (the run is already terminal: first result wins)
                └──
```

- **Per run**, not per issue: two runs of `TEST-4711` have completely separate states.
- **First terminal result wins.** After a lease expiry the run is re-queued as a new attempt; if the
  old agent reports late, that result is rejected (`409`) — the run may already be terminal, and a
  second result would corrupt its Jira update.
- `DEAD` is a first-class status: returned by `GET /api/v1/jobs/{jobRunId}`, included in list results,
  and pushed to Jira. Terminal — another execution means another registration.

## 6. Capability matching

- The run carries `requiresCapabilities[]` (from the producer, or derived from the test case
  definition once that exists).
- The agent registers `capabilities[]` once.
- Claim matches `run.requiresCapabilities ⊆ agent.capabilities` in the same `WHERE` clause as the
  queue selection — it costs nothing extra and keeps routing in SQL (`postgres-job-queue.md` §9).
- An empty `requiresCapabilities` means "any registered agent".

## 7. Failure semantics

| Situation | Behaviour |
|---|---|
| Claim response lost in transit | The run is already leased; the agent never saw it. After lease expiry the reaper re-queues it with `attempt++`. Unavoidable — **every execution must be safe to repeat**. |
| Agent crashes mid-test | Same: lease expires, `attempt++`, another agent runs that run. |
| Agent stalls but is alive | Heartbeat keeps the lease; no heartbeat → reclaimed. |
| Result of a superseded attempt arrives late | `409`; the first terminal result wins, the Jira update is not touched again. |
| Result POST retried after a network error | Idempotent per `jobRunId`: the second call returns the same ack for the same status. |
| Producer retries a registration (e.g. Jira webhook redelivery) | **Creates a second run** — by design. Opt in to `Idempotency-Key` if a producer needs retry safety (§12.2). |
| Run exhausts `maxAttempts` | `DEAD`, terminal, pushed to Jira. |
| Service restart | Nothing to recover — all state is in PostgreSQL. |
| Agent re-registers (`POST /agents` twice) | Upsert on `agentId`; capabilities and `maxJobs` refreshed. |

## 8. Agent inventory

Requirement: know which agents exist, what they can do and whether they are alive — without giving
agents database access.

- **Registration** (`POST /api/v1/agents`) is the source of truth: `agentId` (stable, from the agent's
  config), `name`, `capabilities[]`, `labels{}`, `maxJobs`, `version`, `registeredAt`.
- **Liveness** is derived, not polled separately: every authenticated agent call (claim, lease,
  result) updates `lastSeenAt`. An idle agent is alive as long as its long-poll cycle keeps running
  (≤ `wait` seconds between calls).
  - `ACTIVE` — seen within the liveness window (`2 × wait` + margin, e.g. 90 s).
  - `STALE` — not seen within that window; visibly unhealthy, still claimable in theory.
  - `OFFLINE` — explicit `DELETE /api/v1/agents/{agentId}` on graceful shutdown.
- **Concurrency** (`maxJobs`) is enforced server-side at claim time by counting the agent's active
  leases — the agent cannot see the queue, so the service is the only place that can be sure.
- Optional: the desktop component reads `GET /api/v1/agents` for an ops view. Whether a dedicated
  heartbeat endpoint is needed for agents that are neither claiming nor running is open (§12.4).

## 9. Jira status push

The service pushes results to Jira's REST API; clients never do this themselves, and the Jira client
never has to ask.

- **Per run, as soon as it is available.** No aggregation per issue, no waiting for sibling runs, no
  assumption about order: the run that finishes first is pushed first.
- **The update must identify the run**: `jobRunId`, `issueKey`, `attempt`, `startedAt`/`finishedAt`,
  status and message/artifact summary. One issue can accumulate several runs and an older run may
  finish after a newer one, so a push saying only "TEST-4711: FAILED" would be meaningless.
- **How**: a transactional outbox. Status change and outbox row are written in the same commit; a
  service-side worker drains the outbox against the Jira REST API with retries. This keeps "run state
  changed" and "Jira knows about it" from drifting apart, and it reuses the queue machinery from
  `postgres-job-queue.md` (§1–§3) with Jira as the consumer.
- **At-least-once** → the Jira call must be idempotent per `jobRunId` (e.g. one comment per run, with
  the run id as the marker).
- **Failure is not run failure**: if Jira is unreachable, the result stays valid, the outbox entry
  stays queued and is retried. Jira being down must never lose a result.
- Open: whether `QUEUED`/`RUNNING` are pushed as well, and how Jira represents several runs of the
  same issue (§12.3).

## 10. Authentication and roles

Three roles with distinct credentials, so an agent cannot register runs and a producer cannot claim
them:

| Role | Can |
|---|---|
| `producer` (Jira client, desktop) | register, read status, list |
| `agent` | register itself, claim, lease, result |
| `ops` (desktop, admin) | read inventory and queue views |

The mechanism (static bearer tokens per role, mTLS, something else) is not decided; the spec's `401`
responses are already declared but nothing enforces them.

## 11. Impact on the current OpenAPI spec

Once the open points are settled, the spec changes to:

- `POST /api/v1/jobs` — registration by `issueKey` only; always creates a run and returns `jobRunId`.
- `GET /api/v1/jobs/{jobRunId}` — status/result incl. `DEAD`.
- `GET /api/v1/jobs` — list/filter by `issueKey` and `status` (an issue can have several runs).
- `POST /api/v1/agents`, `GET /api/v1/agents`, `GET /api/v1/agents/{agentId}`,
  `DELETE /api/v1/agents/{agentId}` — inventory.
- `TestJob` gains `jobRunId` (path identity), `heartbeatIntervalSeconds` (so the agent knows the
  cadence right after claiming, not only after the first renewal) and `requiresCapabilities[]`;
  `testCaseRef` replaces the old `testCaseId`/`testCaseName`/`tags` mix (see §12.1).
- `JobClaimRequest` shrinks to `{agentId}`; capabilities move to the agent registration.
- Run status enum: `QUEUED | RUNNING | SUCCEEDED | FAILED | ERROR | SKIPPED | CANCELLED | DEAD`;
  result status stays `PASSED | FAILED | ERROR | SKIPPED | CANCELLED`. New `ApiError` codes:
  `ALREADY_TERMINAL`, `LEASE_LOST`, `AGENT_UNKNOWN`.

## 12. Open points (need a decision)

1. **What defines "which test to run"?** Registration carries only the issue key and parameters. If a
   Jira issue *is* the test case (or maps 1:1 through a convention), the service resolves the test
   from the issue key and no test reference is needed. If the desktop component should also start
   *arbitrary* tests, it needs a test reference (e.g. `testCaseRef`) in the registration. Which is it?
2. **Registration retry safety** — accept the duplicate-run risk for the Jira webhook, or support the
   optional `Idempotency-Key` header for producers that want it?
3. **Jira push scope and representation** — terminal states + `DEAD` only, or also `QUEUED`/`RUNNING`?
   And with several runs per issue: one comment per run (run id as marker), a custom field, or a
   sub-task per run?
4. **Agent liveness** — derive from claim/lease/result calls (recommended), or add an explicit
   heartbeat endpoint for idle agents?
5. **Cancellation** — `DELETE /api/v1/jobs/{jobRunId}` now or later? Same question for "cancel all
   runs of an issue".
6. **Where testautomation-desktop lives** — third root folder in this repository (`desktop/`, which
   stack?), or a separate repository? That decides whether we generate a client module here.
