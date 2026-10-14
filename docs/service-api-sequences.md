# Service API — semantics and request sequence

**Status: the API surface below is implemented in `openapi/testautomation-service.yaml`** and
generated from there (the endpoints answer `501` until a delegate implementation exists). The
*semantics* still marked open in §12 are proposals — they describe behaviour the service is meant to
have, not behaviour that exists. If this file and the spec disagree, the spec wins for the wire
format and this file for the intent; a disagreement is a bug in one of them.

**Rev 3** — validated against the required API surface and extended in two places: the result
acceptance rule is now exact (lease holder + terminal-state handling, §5/§7) and the spec-impact list
in §11 turned into the contract that is **now implemented** in `openapi/testautomation-service.yaml`
(the semantics still marked open in §12 remain proposals).

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
POST /api/v1/jobs   { issueKey: "TEST-4711", parameters: { … } }        → Jira-backed run
POST /api/v1/jobs   { testName: "login-smoke", parameters: { … } }      → ad-hoc run
   → 201 { jobRunId: "9f1c…", source: "JIRA" | "AD_HOC", status: "QUEUED", createdAt: … }
```

- The client sends the **issue key or the test name** (plus parameters). It does not choose an
  execution id.
- The service generates a **`jobRunId`** (UUID) per registration. That id is the wire identity of the
  run: the agent receives it at claim time and uses it for lease renewal and result submission.
- **Which kind of run is created is decided implicitly by the payload** — no client-type field, no
  separate endpoint (§2.1).
- **Registration is deliberately not idempotent.** Two calls with the same payload produce two
  distinct runs, and both are executed. No dedupe, no overwrite, no generation counter.
- Runs are **fully independent**: separate queue rows, leases, attempts and results. They execute in
  any order and finish in any order.
- For a Jira-backed run, the service pushes the result to Jira **as soon as that run is finished** —
  it does not wait for sibling runs of the same issue and does not care which one finishes last.
  Ad-hoc runs never reach Jira at all.

### 2.1 Two sources, distinguished implicitly

| Registration payload | Source | Test definition | Result |
|---|---|---|---|
| `issueKey` set (with or without `testName`) | `JIRA` | the service fetches it from Jira via REST | pushed to Jira when the run finishes |
| `issueKey` empty, `testName` set | `AD_HOC` | taken from `testName` | **nowhere** — only readable through `GET /api/v1/jobs` |
| neither | — | — | `400` |

Notes:

- The caller's identity (Jira client, desktop, a `curl` invocation by hand) is irrelevant: what
  decides is whether the details live in Jira. A curl call with an `issueKey` is a Jira-backed run.
- `testName` may also be sent *together with* an `issueKey`; it then acts as a refinement/record of
  which test within the issue is meant, while the service still resolves the details from Jira.
- On the way **out** the distinction is explicit: every run, claim response and list entry carries a
  derived `source` field, so an agent and the desktop can always tell where a run came from.
- An agent never talks to Jira (it has no credentials and no network path for that); Jira-derived
  details are materialised into the run by the service and travel in the claim response.
- The `neither set → 400` rule is enforced by the **service**, not by the generated layer: OpenAPI
  cannot express "exactly one of these two fields". The generated models accept an empty
  registration; the delegate has to reject it.
- What exactly the service fetches from Jira, and how those fields map to the run's parameters and
  test definition, is still open (§12.1 / §12.9).

What this costs, stated plainly: a producer that retries a registration whose response it never saw
creates a second run. For Jira webhooks (at-least-once on their side) that is a real hazard.
Mitigation is opt-in, not default: an optional `Idempotency-Key` request header stored per producer,
so a caller that wants retry safety can have it without changing the semantics for everyone else
(§12.2).

## 3. Endpoints by caller

### 3.1 Producer / reader API (Jira client, testautomation-desktop)

| Method | Path | Semantics |
|---|---|---|
| `POST` | `/api/v1/jobs` | Register a run. Body: `issueKey` (Jira-backed) **or** `testName` (ad-hoc), plus `parameters{}`, `requiresCapabilities[]`, `priority`, `maxAttempts`. Always creates a run → `201` + `{jobRunId, source, status, createdAt}`; `503` if a Jira-backed run cannot be resolved from Jira. |
| `GET` | `/api/v1/jobs/{jobRunId}` | State and, when finished, the result of one run. |
| `GET` | `/api/v1/jobs` | List runs, filter by `issueKey` and `status`, paged with `limit`/`offset`. An issue can have several runs, so this is what answers "what happened with TEST-4711?". |
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
- **A result is accepted only from the agent currently holding the lease of that run**, and only while
  the run is not yet terminal (`SUCCEEDED`, `FAILED`, `ERROR`, `SKIPPED`, `CANCELLED`, `DEAD` are all
  terminal):
  - lease holder + run not terminal → accepted; the run becomes terminal and the outbox row is written
    in the same commit;
  - lease holder + run already terminal with the **same** status → `200` and the same ack (a network
    retry of an identical submission must not fail);
  - lease holder + run already terminal with a **different** status → `409 ALREADY_TERMINAL`;
  - **not** the lease holder (lease expired and was reclaimed, i.e. a superseded attempt) → `409
    LEASE_LOST`.

  The distinction matters: a late result of a superseded attempt must never update Jira a second
  time, while a simple retry of the same result must be harmless.
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
| Result of a superseded attempt arrives late | `409 LEASE_LOST`; the run's Jira update is not touched again. |
| Result POST retried after a network error | Same lease holder, same status, run already terminal → `200` with the same ack. `409 ALREADY_TERMINAL` only when the status differs. |
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
- **Deregistration does not cancel running runs.** An agent that shuts down while holding runs leaves
  their leases to expire and the reaper re-queues them (`attempt++`). A graceful agent should finish
  or abandon its runs first; an explicit "give this run back" call does not exist yet (§12.7).
- **Concurrency** (`maxJobs`) is enforced server-side at claim time by counting the agent's active
  leases — the agent cannot see the queue, so the service is the only place that can be sure.
- Optional: the desktop component reads `GET /api/v1/agents` for an ops view. Whether a dedicated
  heartbeat endpoint is needed for agents that are neither claiming nor running is open (§12.4).

## 9. Jira status push

The service pushes results to Jira's REST API; clients never do this themselves, and the Jira client
never has to ask.

- **Only for Jira-backed runs** (`source: JIRA`). Ad-hoc runs registered by the desktop component or
  a curl call are never pushed anywhere — their result exists only in the service's database and is
  read back through `GET /api/v1/jobs` (§3.1). Nobody is notified actively.
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

## 11. The OpenAPI spec (now implemented)

`openapi/testautomation-service.yaml` contains this API surface. It generates two delegate
interfaces, one per tag, each satisfied by exactly one bean: `JobsApiDelegate` (tag `Jobs`) and
`AgentsApiDelegate` (tag `Agents`).

**Producer / reader** — tag `Jobs`:

| Endpoint | Request | Response |
|---|---|---|
| `POST /api/v1/jobs` | `JobRunRegistration` (`issueKey` **or** `testName`; `parameters`, `requiresCapabilities`, `priority`, `maxAttempts` optional) + optional `Idempotency-Key` header | `201` `JobRun`, `503` if Jira cannot resolve a Jira-backed run |
| `GET /api/v1/jobs` | `issueKey`, `status`, `limit`, `offset` | `200` `JobRunPage` |
| `GET /api/v1/jobs/{jobRunId}` | — | `200` `JobRun` (incl. `result` when terminal), `404` |

**Agent** — tags `Jobs` / `Agents`:

| Endpoint | Request | Response |
|---|---|---|
| `POST /api/v1/agents` | `AgentRegistration` | `200` `Agent` |
| `GET /api/v1/agents` | `status` filter | `200` `Array<Agent>` |
| `GET /api/v1/agents/{agentId}` | — | `200` `Agent`, `404` |
| `DELETE /api/v1/agents/{agentId}` | — | `204` |
| `POST /api/v1/jobs/claim` | `wait` (query), `JobClaimRequest` | `200` `ClaimedJobRun`, `204` (nothing available) |
| `PUT /api/v1/jobs/{jobRunId}/lease` | — | `200` `JobRunLease`, `409` `LEASE_LOST` |
| `POST /api/v1/jobs/{jobRunId}/result` | `JobRunResult` | `200` `JobRunResultAck`, `409` `LEASE_LOST` / `ALREADY_TERMINAL` |

Models: `JobRunRegistration`, `JobRun`, `JobRunSummary`, `JobRunPage`, `JobClaimRequest`,
`ClaimedJobRun`, `JobRunLease`, `JobRunResult`, `JobRunResultAck`, `TestArtifact`,
`AgentRegistration`, `Agent`, `ApiError`; enums `JobRunSource` (`JIRA | AD_HOC`), `JobRunStatus`
(`QUEUED | RUNNING | SUCCEEDED | FAILED | ERROR | SKIPPED | CANCELLED | DEAD`), `TestResultStatus`
(`PASSED | FAILED | ERROR | SKIPPED | CANCELLED`), `AgentStatus` (`ACTIVE | STALE | OFFLINE`).
`JobRun`, `JobRunSummary` and `ClaimedJobRun` all carry the derived `source`; `issueKey` is optional
in all three because ad-hoc runs have none.

Deliberately **not** in the spec, with the reason:

- `bearerAuth` is declared as the security scheme but nothing enforces it — the role model of §10 is
  not implemented yet, and only the `401` responses are contractual today.
- `testCaseRef` is optional in the registration because §12.1 is open.
- `DELETE /api/v1/jobs/{jobRunId}` (cancel) — not requested, §12.5.
- release/abandon a claimed run — §12.7.
- per-run maximum runtime — §12.8.
- issue-key validation against Jira — §12.9.

## 12. Open points (need a decision)

1. **What the service fetches from Jira, and how it maps to the run.** The decision that an
   `issueKey` means "resolve the details from Jira" is taken; which Jira fields are read, and how
   they become the run's parameters and test definition (so that the claim response is complete for a
   DB-less agent), is still open.
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
7. **Releasing a claimed run** — an agent that cannot execute a claimed run today has only two
   options: let the lease expire (wasting the lease window) or submit a failure that counts as an
   attempt. Should there be an explicit "give this run back" call that re-queues it without counting
   an attempt (e.g. a missing dependency on that agent)?
8. **Per-run maximum runtime** — nothing stops a hung agent that keeps heartbeating from holding a
   run indefinitely. Is a maximum runtime (or a runtime hint at claim time) needed?
9. **Jira enrichment on registration** — when is it fetched (at registration, i.e. fail fast, or
   lazily at claim time?), and what happens when Jira rejects the key or is unreachable? Current
   proposal in the spec: fetch at registration, answer `503 JIRA_UNAVAILABLE`, create no run, let the
   caller retry.
10. **Error response shape** — the spec declares `ApiError` for `400`/`401`/`404`/`409`, but Spring
    Boot's default error JSON is what clients actually receive today. A `@RestControllerAdvice`
    mapping exceptions to `ApiError` has to be part of the delegate implementation, or the spec
    should stop promising that shape. Note that the delegate **cannot** produce the body itself: its
    return type is `ResponseEntity<JobRun>`, so the payload has to come from a thrown exception.
11. **Generated delegates cannot be merged** — `JobsApiDelegate` and `AgentsApiDelegate` each declare
    `default getRequest()`, so one class cannot implement both (compile error). Accept two small
    delegate classes, or re-tag the API so a single `Api` interface carries everything and one class
    implements it.
12. **Incremental reads for the desktop** — the desktop polls instead of being notified, so it needs
    "everything that changed since X". Should `GET /api/v1/jobs` get an `updatedSince` filter (needs
    an `updated_at` column and index) rather than re-reading the whole table?
13. **"Jira details, but no push"** — the implicit discrimination cannot express it. If the desktop
    ever starts a Jira-backed run that must *not* comment in Jira, we need an explicit override flag;
    until then, ad-hoc is the only way to stay silent.
