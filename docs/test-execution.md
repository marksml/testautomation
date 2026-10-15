# Test execution — what runs, against what

Everything in one place: which command runs which tests, how a database is provided, and what each
test class is allowed to prove. The *why* behind the database rules (why never H2) is in
[`test-strategy.md`](test-strategy.md); the queue design is in
[`postgres-job-queue.md`](postgres-job-queue.md).

## 1. One command runs everything

```bash
./mvnw verify                 # all modules: unit tests + integration tests + jar packaging
./mvnw -pl service verify     # just the service
```

| Phase | Plugin | Picks up | Needs |
|---|---|---|---|
| `test` | surefire | `**/*Test.java` — unit tests | nothing (no database, no Docker) |
| `integration-test` + `verify` | failsafe | `**/*IT.java` — integration tests | a real PostgreSQL (see §2), otherwise the test **skips** |

The split is by name, not by annotation: a unit test that needs no database is `*Test`, a test that
needs PostgreSQL is `*IT`. Surefire is configured to exclude `**/*IT.java` (its default pattern
`**/Test*.java` would otherwise pull in `TestcontainersPostgresIT`), and failsafe is configured to
include only `**/*IT.java`. Consequence: `mvn test` never needs a database, and `mvn verify` is the
only command a database requirement can come from.

## 2. Which database the integration tests use

Resolution order (first match wins), in `PostgresTestDatabase`:

1. **An existing database**, given by `-DTA_IT_JDBC_URL=…` (or the same name as an environment
   variable). Credentials via `TA_IT_JDBC_USER` / `TA_IT_JDBC_PASSWORD` (default `postgres`/`postgres`).
   Used on machines without Docker, against the compose service below or any PostgreSQL 16.
2. **Testcontainers**, when a Docker daemon is reachable: starts its own `postgres:16` and drops it
   afterwards. Nothing to configure.
3. **Neither** → the integration tests *skip* with a message saying exactly what to start, so the
   build never fails for want of Docker.

| Environment | What happens |
|---|---|
| Dev machine with Docker | `./mvnw verify` — Testcontainers starts `postgres:16`, integration tests run |
| Dev machine, database from the compose file | `./mvnw verify -DTA_IT_JDBC_URL=jdbc:postgresql://localhost:5433/testautomation -DTA_IT_JDBC_USER=testautomation -DTA_IT_JDBC_PASSWORD=testautomation` |
| Hermes VM (no Docker daemon) | same as above, against a locally started cluster — §4 |
| Nothing available | integration tests skip; unit tests still run |

The database is reset before each test: `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` and then
the canonical DDL is applied again. The DDL itself contains no `DROP` statement — it is production
DDL and must never delete anything by being applied.

## 3. What each test class covers

| Class | Kind | Database | What it proves |
|---|---|---|---|
| `JobClaimServiceTest` | unit (surefire) | none — in-memory port | the decisions above the database: an unregistered agent is rejected (`409 AGENT_UNKNOWN`), an agent at its `maxJobs` limit gets nothing, a `PENDING` run is invisible, the capability filter holds, the highest priority wins, and a claimed row is mapped onto the wire model (`source`, `testName`, `parameters`, `attempt`, lease, heartbeat interval) |
| `TestautomationServiceApplicationTests`, `TestautomationAgentApplicationTests` | context load (surefire) | none | both Spring contexts still start |
| `JobClaimConcurrencyIT` | integration (failsafe) | **real PostgreSQL** | the atomic part: 8 agents claiming 8 queued runs concurrently get 8 distinct runs and no run is claimed twice; two overlapping uncommitted claims take different rows (`FOR UPDATE SKIP LOCKED`, no blocking); a claim sets `RUNNING`, `agent_id`, `attempt + 1`, `started_at` and a future lease; priority order; the capability filter as it appears in the claim query; a `PENDING` run stays unclaimable |
| `TestcontainersPostgresIT` | integration (failsafe, `disabledWithoutDocker`) | Testcontainers `postgres:16` | the DDL in `local_pg_database_docker/` applies to a virgin container (tables + all indexes appear) and the queue works against that container end to end |

The in-memory port used by the unit tests is `InMemoryJobQueuePort` (test scope). It exists for the
*decisions*, and it is single-threaded by design: it makes no claim about concurrency, because that is
what PostgreSQL's row locks decide — and that is why `JobClaimConcurrencyIT` exists
([`test-strategy.md`](test-strategy.md) §1).

## 4. Running on the Hermes VM (no Docker daemon)

The VM has no `/var/run/docker.sock` and no podman, so path 2 of §2 is unavailable — but PostgreSQL 16
is installed at `/opt/data/tools/postgresql-16/bin`:

```bash
export PGBIN=/opt/data/tools/postgresql-16/bin
D=/opt/data/cache/scratch/pgtest                     # scratch, anything under /opt/data

$PGBIN/initdb -D $D/data -U hermes --auth=trust -E UTF8
$PGBIN/pg_ctl -D $D/data -o "-p 5433 -k $D -c listen_addresses=127.0.0.1" -l $D/pg.log start
$PGBIN/psql -h 127.0.0.1 -p 5433 -U hermes -d postgres -c "create database testautomation"
# schema.sql is applied by the test itself (it resets the schema first), so no manual step

cd /opt/data/projects/testautomation
export JAVA_HOME=/opt/data/tools/jdk-21            # unset in non-interactive shells
./mvnw -pl service verify \
    -DTA_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:5433/testautomation \
    -DTA_IT_JDBC_USER=hermes

# when done
$PGBIN/pg_ctl -D $D/data stop -m fast
```

Result of exactly that run (2026-10-15):

```
[INFO] Tests run: 12, ... JobClaimServiceTest        Failures: 0  Errors: 0  Skipped: 0
[INFO] Tests run:  1, ... TestautomationServiceApplicationTests
[IT]   PostgreSQL under test: external database jdbc:postgresql://127.0.0.1:5433/testautomation
[INFO] Tests run:  6, ... JobClaimConcurrencyIT      Failures: 0  Errors: 0  Skipped: 0
[WARNING] Tests run: 1, ... TestcontainersPostgresIT Failures: 0  Errors: 0  Skipped: 1   (no Docker daemon)
[INFO] BUILD SUCCESS
```

## 5. Why the service still starts without a database

`service/pom.xml` has the PostgreSQL driver at **runtime** scope and deliberately **not**
`spring-boot-starter-jdbc`. Adding spring-jdbc would activate Boot's `DataSourceAutoConfiguration`, and
the application (and its context-load test) would then refuse to start without a database — while the
persistence layer is not wired into the REST delegates yet.

Consequently:

- the queue code takes a `javax.sql.DataSource` and uses plain JDBC (`JdbcJobQueueRepository`), so
  there is no auto-configuration to satisfy;
- the integration tests build their own DataSource (`SimpleDataSource`), so they do not need a Spring
  context either;
- `JobsApiDelegateImpl.claimJobRun` still answers `501`: wiring it means giving the application a
  DataSource configuration **and** an error mapping for `AGENT_UNKNOWN`/`LEASE_LOST` — that is the
  next step, not something to slip in behind a green build.

## 6. The schema has exactly one source

| Consumer | How it gets the schema |
|---|---|
| Local development database | `docker-compose.yml` mounts it into `/docker-entrypoint-initdb.d` (first start of an empty volume) |
| Integration tests (Testcontainers and external alike) | the same file, read from the test classpath and applied after a schema reset |
| The future production migration | will be made of this file (Flyway or plain SQL) — not yet wired |

File: `service/src/test/resources/local_pg_database_docker/schema.sql` — it lives next to the compose
file that consumes it, as requested, and each table carries its reasoning as SQL comments
([`postgres-job-queue.md`](postgres-job-queue.md) §10 has the prose version). When persistence goes to
production, the file moves to the migration location and the tests follow it; the DDL content stays the
single definition either way.

## 7. Conventions for the next test

- Needs no database → `*Test`, runs under surefire, uses `InMemoryJobQueuePort` for queue ports.
- Touches SQL semantics (locks, indexes, constraints, concurrency) → `*IT`, runs against real
  PostgreSQL, and asserts something H2 could not tell us ([`test-strategy.md`](test-strategy.md) §1).
- New fixtures: `QueueFixtures` (plain SQL, because the registration endpoint does not exist yet — the
  moment `POST /api/v1/jobs` works, the fixtures become real API calls).
- **Still not covered** (they need code that does not exist yet): lease expiry → re-queue,
  `attempt > maxAttempts` → `DEAD`, the reaper, the result path (`LEASE_LOST` / `ALREADY_TERMINAL` /
  repeated identical result), the Jira outbox worker, the long-poll `claim?wait=` behaviour.
  [`test-strategy.md`](test-strategy.md) §4 lists them with the mechanism each one needs.
