# PostgreSQL as a job queue — mechanics, ports and connections

Design notes for `testautomation-service`, 2026-10-11.
Status: **investigation only** — no queue table, no schema and no REST layer exist yet.
Purpose: make the mechanics and the resource/port requirements explicit *before* we commit to a design.

---

## 1. The primitive: claim a job atomically with `SKIP LOCKED`

A table becomes a queue because PostgreSQL can hand out a row to exactly one worker, without
queueing the other workers behind a lock:

```sql
UPDATE jobs
   SET status = 'running',
       locked_by = :agent_id,
       locked_at = now(),
       attempts = attempts + 1
 WHERE id = (
       SELECT id
         FROM jobs
        WHERE status = 'queued'
          AND run_at <= now()
        ORDER BY priority DESC, run_at, id
        FOR UPDATE SKIP LOCKED
        LIMIT 1
 )
RETURNING id, payload;
```

Why the subquery round-trip matters:

| Approach | Failure mode |
|---|---|
| `SELECT` then `UPDATE` | two workers read the same row before either updates it → duplicate execution |
| `SELECT ... FOR UPDATE` | correct, but workers wait in line behind the locked row → queue is serial |
| `SELECT ... FOR UPDATE SKIP LOCKED` | locked rows are skipped "as if they did not exist" → every worker gets a *different* row, no waiting |

`SKIP LOCKED` exists since PostgreSQL 9.5 and is a modifier of the row-locking clause
(`FOR UPDATE` / `FOR NO KEY UPDATE` / `FOR SHARE` / `FOR KEY SHARE`).
The lock itself lives as long as the surrounding transaction, which is why the "claim" is a
single statement: lock, status change and payload return happen atomically.
The row lock is released at `COMMIT`, so a crashed worker cannot leave a permanently locked row.

Supporting structure (this is the part people skip and then wonder why the queue crawls):

```sql
-- partial index: keeps the scan small and shrinks as jobs complete
CREATE INDEX jobs_claim_idx ON jobs (priority DESC, run_at, id) WHERE status = 'queued';
```

Reference: PostgreSQL manual, *The Locking Clause* (`SKIP LOCKED`), and the pattern walk-throughs
in the references section.

## 2. Why a queue in the same database at all

Enqueueing happens in the same transaction as the business change, so the classic dual-write race
("job is picked up before the row that enqueued it is committed") cannot happen. That argument is
laid out in detail by Brandur Leach (*Postgres Job Queues & Failure By MVCC*).
Cost: the same table now serves request traffic and queue traffic (bloat, autovacuum, index churn).
A dedicated broker earns its keep only above roughly thousands of jobs per second, or when other
services must consume the same stream.

## 3. Job lifecycle — the states this service needs

```
queued ──claim──> running ──success──> succeeded
   ▲                 │
   │                 ├── failure, attempts < max_attempts ──> queued (run_at = now() + backoff)
   └──reaper─────────└── failure, attempts >= max_attempts ─> dead (needs operator review)
```

Two ways to model "running":

1. **Transaction-bound lease** — keep the transaction open while the job runs. Auto-cleanup on
   crash, one round trip, but a transaction that stays open for the duration of a test suite is
   bad for PostgreSQL (long transactions block vacuum).
2. **Status column + lease timeout + reaper** — the claim commits immediately; the row carries
   `locked_by`, `locked_at` and a heartbeat. A reaper requeues jobs whose lease expired:

   ```sql
   UPDATE jobs
      SET status = 'queued', locked_by = NULL, locked_at = NULL
    WHERE status = 'running'
      AND heartbeat_at < now() - interval '5 minutes';
   ```

For test *execution* (seconds to hours per job) option 2 is the right one — and it means a job
must never hold a database connection while it runs (§4).

Additional columns the domain will need: `attempts` / `max_attempts`, `run_at` (delayed and
backoff), `priority`, `idempotency_key` (safe re-delivery), `result`, `error`, timestamps, plus a
`dead` state for the DLQ. Retention: archive or delete succeeded jobs on a schedule, otherwise the
table and its indexes bloat; tune autovacuum for the jobs table specifically.

## 4. Wake-up latency: polling vs `LISTEN/NOTIFY`

| Mechanism | Latency | Notes |
|---|---|---|
| Poll loop (`WHERE status='queued' LIMIT n` every N seconds) | up to N seconds | trivial, robust, wastes one query per interval when idle. JobRunr defaults to `poll-interval-in-seconds=15`; PGMQ offers `read_with_poll` |
| `LISTEN` / `NOTIFY` wake-up | milliseconds | notification is delivered **only on COMMIT** of the notifying transaction, deliveries happen between transactions, payload < 8000 bytes by default. Session-bound — see the PgBouncer table in §5 |
| Both (NOTIFY as wake-up, slow poll as safety net) | ms typical | the standard production answer; a missed notification costs at most one poll interval |

`NOTIFY` details that matter in practice: duplicate payloads of the same channel inside one
transaction are folded into a single delivery, notifications are ordered per transaction and by
commit order across transactions, and the notification queue is ~8 GB per installation (a
`LISTEN` session that sits in a transaction for a long time prevents cleanup and eventually
makes `NOTIFY` fail at commit).

## 5. Ports, connections and the budget behind them

### Default ports

| Component | Default port | Direction | Comment |
|---|---|---|---|
| PostgreSQL | **5432** | service → DB | one TCP connection per pooled/session connection |
| PgBouncer (optional) | **6432** | service → PgBouncer → PG | only when the number of client connections must be decoupled from the server |
| Service HTTP (later) | 8080 | agents/CI → service | behind a TLS reverse proxy on 443 |
| Agent → service | 443/HTTPS | inbound to service | agents should *not* talk to PostgreSQL directly |

### Server-side limits

- `max_connections` default is **typically 100** ("might be less if your kernel settings will not
  support it"); it can only be set at server start and sizes shared memory.
- `superuser_reserved_connections` (default 3) and `reserved_connections` keep a few slots for
  admin/monitoring sessions — the queue must not eat all 100.

### Client-side pool (Spring Boot default: HikariCP)

| Property | Default |
|---|---|
| `spring.datasource.hikari.maximum-pool-size` | 10 |
| `spring.datasource.hikari.minimum-idle` | same as maximum pool size |
| `spring.datasource.hikari.connection-timeout` | 30000 ms |

Budget arithmetic: `service instances × pool size + admin/monitoring + other clients ≤ max_connections`.
On the 2 vCPU / 4 GB target VPS that suggests **pool 5–10 for the service** and keeping 20+ slots
free. A HikariCP pool should behave as a *fixed-size* pool (that is why `minimum-idle` defaults to
`maximum-pool-size`); raising pool size does not raise throughput once the database is saturated.

### Two rules that follow from the port/connection picture

1. **A running job must not hold a connection.** The claim transaction is short (milliseconds);
   the test execution then runs outside the database. Otherwise pool size would cap the number of
   concurrently executing tests, and long transactions would stall vacuum.
2. **Agents should not connect to PostgreSQL directly.** They authenticate against the service's
   REST API, and the service owns the queue. Then the connection math stays `instances × pool` for
   one component, DB credentials never leave the server, and `SKIP LOCKED` contention stays inside
   one process family. Direct agent access would make the pool budget grow with the agent fleet.

### PgBouncer, if it is introduced later

`pool_mode` is `session` by default; `transaction` releases the server connection when the
transaction ends. From the official feature map (transaction pooling column):

| Feature | Session pooling | Transaction pooling |
|---|---|---|
| `LISTEN` | Yes | **Never** |
| `NOTIFY` | Yes | Yes |
| Session-level advisory locks | Yes | **Never** |
| `PREPARE` / `DEALLOCATE` | Yes | Never |
| Protocol-level prepared plans | Yes | Yes (needs `max_prepared_statements > 0`) |

Consequence: **transaction pooling is incompatible with a `LISTEN`-based worker wake-up** (and
with session-level advisory locks). Either keep workers on a direct connection / session pooling,
or accept pure polling. `max_client_conn` defaults to 100, `default_pool_size` to 20.

## 6. Java options

| Option | What it gives you | Fit for this service |
|---|---|---|
| Roll your own (`JdbcClient`/`JdbcTemplate` + the claim query) | ~4 queries, full control over states, priority and lease semantics; you also own backoff, reaper and monitoring | best starting point — the queue semantics *are* the product here |
| [db-scheduler](https://github.com/kagkarlsson/db-scheduler) | persistent, cluster-friendly **scheduler** for Java (JDBC), recurring/timed tasks | good if we later need timed/recurring test runs, not needed for the pull queue |
| [JobRunr](https://www.jobrunr.io/en/documentation/configuration/spring/) | background job server + dashboard + retries/backoff on top of an SQL datasource; `poll-interval-in-seconds` default 15; Postgres event transport (Pro) for ms pickup | option if the operational surface (dashboard, retries) is worth a dependency |
| [PGMQ](https://pgmq.github.io/pgmq/) / [pg-boss](https://github.com/timgit/pg-boss) | a packaged version of exactly the SKIP LOCKED pattern (visibility timeout, archives) as extension/library | read them as the reference blueprint; PGMQ needs an extension installed in the database |

## 7. Decisions still open

1. Claim protocol for agents: REST long-poll vs short-poll vs (rejected) direct DB access.
2. One `jobs` table for all test runs, or one table/queue per agent pool (`SKIP LOCKED` per table
   reduces contention but multiplies indexes).
3. Lease length + heartbeat interval for test executions (needs a measured p95 runtime first).
4. Retry policy defaults (attempts, backoff curve, which failures are permanent).
5. Observability: queue depth, age of oldest queued job, attempts histogram, reaper actions.
6. Whether `LISTEN/NOTIFY` is worth it at all at this scale — with one service instance and a
   2–5 s poll, polling alone may be entirely sufficient.

## 8. Idle periods: how agents learn about new work quickly

Two separate problems, easy to conflate:

1. **Transport** — how an idle agent waits for work.
2. **Database cost while idle** — what the service does with its connections when nothing arrives
   for hours.

### 8.1 Transport options

| Transport | Latency | Cost / caveats |
|---|---|---|
| Short poll (`POST /jobs/claim`, interval 5–30 s) | up to one interval | simplest; cost scales with `agents × frequency` (20 agents / 5 s = 4 req/s, fine; 500 agents without backoff = 100 req/s, not fine) |
| **HTTP long poll** (`POST /jobs/claim?wait=25s`) | milliseconds | request stays open until a job is available (200 + job) or the wait expires (204); agent re-issues immediately. `DeferredResult` + virtual threads (Java 21) remove the thread-per-waiter problem: with `spring.threads.virtual.enabled=true` each parked request costs a virtual thread and `server.tomcat.threads.max` (default 200) no longer caps the fleet |
| SSE (`text/event-stream`) | milliseconds | server→agent stream, avoids request overhead, but the actual job still needs a claim call → two mechanisms racing. Only worth it if you also want live log/status streaming |
| WebSocket / gRPC streaming | milliseconds | the right answer when the channel must be bidirectional anyway (streaming test output); overkill for pure work distribution |

Rules that keep long polling healthy:

- Wait time **< every idle timeout on the path** (reverse proxy, load balancer, NAT): 25–30 s is the
  usual sweet spot below the ~60 s defaults.
- Set the timeout explicitly — Spring Boot's `spring.mvc.async.request-timeout` has no default;
  "the default timeout value for async requests depends on the underlying Servlet container".
- On `204`, the agent re-issues immediately (optionally with 0–500 ms jitter). On disconnect,
  reconnect immediately — a dropped long poll is not an error.
- Return hints in the 204 (e.g. `nextPollAfterMs`) so the server can slow the fleet down
  (thundering herd after a large batch) without client-side config changes.

### 8.2 What the service does with the database while idle

- **Never wait inside a database connection or transaction.** The pool is ~10 connections and the
  agent fleet may be 50: a worker that holds a transaction while waiting burns the pool and blocks
  vacuum. Waiting happens in application memory.
- **One queue poller per service instance, not per agent.** Park the waiting `DeferredResult`s in an
  in-memory structure, have a single poller (≈1 query/second while waiters exist) claim jobs and
  hand them to waiters FIFO. With zero waiting agents the poller can stop entirely, so *idle time
  costs no queries at all* — the cost scales with waiting agents, not with how long the queue was
  empty.
- **`LISTEN/NOTIFY` as an optional latency upgrade.** One dedicated connection per service instance
  (`NOTIFY jobs_ready` from the enqueue transaction, delivered on commit) wakes the poller instead
  of it polling. Caveats already covered in §4 and §5: session-bound, not persistent (notifications
  can be missed on reconnect), and broken by PgBouncer transaction pooling — so keep polling as the
  fallback.
- **The reaper keeps running regardless of traffic** (lease expiry, agent registration TTL,
  housekeeping — every 30–60 s). That is the only guaranteed idle-time query, and it is cheap.
- A claim query against an empty queue with the partial index from §1 is an index scan returning
  zero rows — negligible.

### 8.3 Idle-specific failure modes

- **Connections die while idle**: NAT/firewall/load balancers drop idle TCP sessions, so the first
  job after hours hits a stale connection. HikariCP already pings idle connections
  (`keepaliveTime` default 2 min) and recycles them (`maxLifetime` default 30 min) — keep
  `maxLifetime` shorter than any infrastructure-imposed limit.
- **Idle sessions holding locks**: `idle_in_transaction_session_timeout` is disabled by default (0)
  in PostgreSQL. Set it (e.g. 60 s) so a forgotten transaction cannot hold locks and stall vacuum.
- **Idle-time liveness**: heartbeat/health endpoints must keep answering while the queue is empty,
  otherwise the instance is removed from the load balancer exactly when work arrives.

## 9. References

**PostgreSQL (official)**
- `SELECT` — locking clause / `SKIP LOCKED`: https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE
- `NOTIFY`: https://www.postgresql.org/docs/current/sql-notify.html
- `LISTEN`: https://www.postgresql.org/docs/current/sql-listen.html
- Connections and authentication (`max_connections`, default 100): https://www.postgresql.org/docs/current/runtime-config-connection.html

**PgBouncer (official)**
- SQL feature map per pooling mode (`LISTEN` / advisory locks): https://www.pgbouncer.org/features.html
- Configuration reference (`listen_port` default 6432, `pool_mode`, `max_client_conn`, `default_pool_size`): https://www.pgbouncer.org/config.html

**Pattern write-ups**
- Brandur Leach, *Postgres Job Queues & Failure By MVCC*: https://brandur.org/postgres-queues
- Brandur Leach, *Transactionally Staged Job Drains in Postgres*: https://brandur.org/job-drain
- Prisma, *Build a Postgres Job Queue with SKIP LOCKED*: https://www.prisma.io/blog/you-dont-need-a-job-queue-postgres-already-has-skip-locked
- Matthews Wong, *A Reliable Job Queue in PostgreSQL with SKIP LOCKED*: https://www.matthewswong.com/en/blog/postgres-skip-locked-job-queue/
- Netdata Academy, *Using FOR UPDATE SKIP LOCKED For Queue Workflows*: https://www.netdata.cloud/academy/update-skip-locked/
- Terri S. Linenbach, *Why FOR UPDATE SKIP LOCKED Isn't Enough: pg_advisory_xact_lock* (global concurrency limits, duplicate-active-job prevention): https://terrislinenbach.medium.com/why-for-update-skip-locked-isnt-enough-using-pg-advisory-xact-lock-to-build-a-correct-postgresql-d3eb9db46473

**Spring (official)**
- Spring Framework — *Asynchronous Requests* (`DeferredResult`, async timeouts, SSE): https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html
- Spring Boot — *Common Application Properties* (`spring.mvc.async.request-timeout`, `spring.threads.virtual.enabled`, `server.tomcat.threads.max`, `server.tomcat.max-connections`): https://docs.spring.io/spring-boot/appendix/application-properties/index.html
- Baeldung — *Long Polling in Spring MVC*: https://www.baeldung.com/spring-mvc-long-polling

**Libraries / implementations**
- PGMQ (Postgres Message Queue, Tembo): https://pgmq.github.io/pgmq/ — repository: https://github.com/pgmq/pgmq
- pg-boss (Node, same pattern): https://github.com/timgit/pg-boss
- db-scheduler (Java): https://github.com/kagkarlsson/db-scheduler
- JobRunr (Java, Spring Boot starter + storage): https://www.jobrunr.io/en/documentation/configuration/spring/ , https://www.jobrunr.io/en/documentation/storage/
- HikariCP (pool defaults: `maximumPoolSize` 10, `connectionTimeout` 30000 ms): https://github.com/brettwooldridge/HikariCP#essentials
- HikariCP wiki, *About Pool Sizing*: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
