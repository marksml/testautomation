# Test strategy — and why H2 cannot test the queue

The job distribution rests on PostgreSQL-specific behaviour
(`docs/postgres-job-queue.md` §1: `FOR UPDATE SKIP LOCKED`, `UPDATE ... RETURNING`, a partial index).
This document records what was **measured**, so nobody has to rediscover it: an in-memory H2 test can
pass while proving nothing, which is the worst possible outcome for a claim mechanism.

## 1. Measured: H2 2.3.232 versus PostgreSQL 16.4

Both engines received the same statements. H2 was run in default mode **and** `MODE=PostgreSQL`.

| Statement / behaviour | H2 2.3.232 | PostgreSQL 16.4 |
|---|---|---|
| `SELECT ... FOR UPDATE` (single transaction) | ✅ works | ✅ works |
| `FOR UPDATE SKIP LOCKED` **concurrent claim** | ⚠️ **parses**, but the second transaction gets **no rows at all** — H2 locks at table level, so "skip locked" skips the whole table instead of the locked row | ✅ second claimer gets the next row (`TEST-8` / `TEST-7`) |
| plain `FOR UPDATE`, two transactions | ❌ `[50200] Timeout trying to lock table "JOBS"` after the lock timeout — agents serialise, then fail | ✅ no blocking |
| `UPDATE ... RETURNING` / `INSERT ... RETURNING` | ❌ syntax error `[42000]` | ✅ |
| partial index (`CREATE INDEX ... WHERE status='queued'`) | ❌ syntax error `[42000]` | ✅ |
| `INSERT ... ON CONFLICT ... DO UPDATE` | ❌ syntax error `[42000]` | ✅ |
| `LISTEN` / `NOTIFY` | ❌ syntax error `[42000]` | ✅ |
| `pg_advisory_lock` | ❌ `[90022] function not found` | ✅ |
| `jsonb` column type | ❌ `[50004] unknown data type` (accepted in `MODE=PostgreSQL`) | ✅ |
| `gen_random_uuid()` | ❌ (H2: `RANDOM_UUID()`) | ✅ |
| lock modes 0–3 | change nothing; mode 4 does not exist in 2.x | — |

The headline is the **first two rows**: H2 accepts `SKIP LOCKED` syntax, so the query compiles and a
single-threaded test passes. Under concurrency the same query either returns nothing (agents starve,
each call looks like "empty queue") or, without `SKIP LOCKED`, throws lock timeouts. A green test on
H2 therefore tells you nothing about whether the distribution works.

Contrast, same machine, real PostgreSQL:

```
8 agents claiming 8 queued runs concurrently
  results : [TEST-1 … TEST-8]
  claimed 8 runs, 8 distinct, every agent got work, no run claimed twice
```

## 2. Consequences for the test layout

1. **No H2 anywhere near the queue.** Not for the repository layer, not for the claim query, not for
   migrations. The DDL is PostgreSQL DDL (partial index, `jsonb`, `text[]`, `gen_random_uuid()`), so
   an H2 schema would be a *second* schema that drifts from the real one.
2. **Test the service logic against a port, not against a DB.** The PostgreSQL dialect sits in one
   narrow place — `JobQueuePort` with `JdbcJobQueueRepository` behind it, whose claim method is the
   single statement from `docs/postgres-job-queue.md` §1 — and the layers above it are unit-tested
   against a hand-written fake (`InMemoryJobQueuePort`). What remains to be proven for real is one
   JDBC method per queue operation.
3. **Integration-test that adapter against real PostgreSQL**, including the concurrency case. This is
   not a load test (out of scope for now); it is a correctness test with 2–8 claimers. Implemented as
   `JobClaimConcurrencyIT`; `TestcontainersPostgresIT` additionally starts its own `postgres:16` where
   Docker exists. How each of these is executed, and against which database: `test-execution.md`.
4. Migrations (Flyway/plain SQL) must run against the same real PostgreSQL — they are the schema.

## 3. How to run against a real PostgreSQL

**On a machine with Docker (expected dev setup):** Testcontainers —
`org.springframework.boot:spring-boot-testcontainers` + `org.testcontainers:postgresql`, one
container per test JVM, auto-wired with `@ServiceConnection`:

```java
@SpringBootTest
@Testcontainers
class JobClaimIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
    …
}
```

**On the Hermes VM there is no Docker daemon** (no `/var/run/docker.sock`, no podman), so
Testcontainers cannot run here. A local PostgreSQL 16 exists instead:

```bash
export PGBIN=/opt/data/tools/postgresql-16/bin
D=/opt/data/cache/scratch/pgtest                       # ephemeral, anything under /opt/data
$PGBIN/initdb -D $D/data -U hermes --auth=trust -E UTF8
$PGBIN/pg_ctl -D $D/data -o "-p 55432 -k $D -c listen_addresses=127.0.0.1" -l $D/pg.log start
$PGBIN/psql -h $D -p 55432 -U hermes -d postgres -c "create database ta"
# stop again: $PGBIN/pg_ctl -D $D/data stop
```

So the integration tests must not hard-require a container: keep the JDBC URL overridable
(`spring.datasource.url` via a test profile or `-Dspring.datasource.url=…`) and let the local PG fill
in where Docker is missing. The probes that produced §1 are in
`/opt/data/cache/scratch/{h2probe,pgtest}/` (throwaway, not committed).

## 4. The tests that must exist before the queue is called done

| # | Test | Proven by |
|---|---|---|
| 1 | N agents / N queued runs → N distinct runs, none claimed twice | 8 threads, real PG (`SELECT`-side verified above) |
| 2 | capability filter respected: a run requiring `chrome` is not handed to a linux-only agent | claim query, real PG |
| 3 | second claimer does not block and does not starve | two overlapping transactions |
| 4 | lease expiry → run back to `QUEUED`, `attempt++` | reaper, fake clock |
| 5 | `attempt > maxAttempts` → `DEAD`, producer notified | reaper |
| 6 | result from a superseded attempt → `409 LEASE_LOST`; identical retry → `200` same ack | service layer + PG |
| 7 | long-poll `claim?wait=` returns as soon as a run is enqueued, `204` on timeout | two threads, PG `LISTEN/NOTIFY` or in-process wake-up |
| 8 | migrations apply to an empty database and are re-runnable | Flyway against real PG |
| 9 | `PENDING` runs are invisible to the claim query until the Jira collection has filled them in | claim query, real PG |

Test 1 is the one that would have silently passed on H2.

**Status (2026-10-15):** tests 1, 2 and 3 exist and run against real PostgreSQL
(`JobClaimConcurrencyIT`); test 9 is asserted there too (a `PENDING` run stays unclaimable).
Tests 4–8 need code that does not exist yet — lease expiry, the reaper, `DEAD`, the result path
(`LEASE_LOST` / `ALREADY_TERMINAL`), the long-poll wake-up and the outbox worker. The in-memory port
covers the decisions above the database (`JobClaimServiceTest`), never the atomic part.
