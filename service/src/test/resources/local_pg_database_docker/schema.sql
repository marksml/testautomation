-- testautomation — job queue schema (PostgreSQL 16)
-- ============================================================================
-- Canonical DDL, the single description of the queue. It is applied by
--   * the local development database : service/src/test/resources/local_pg_database_docker/docker-compose.yml
--     (mounted into /docker-entrypoint-initdb.d, so it runs on first start of an empty data volume)
--   * the Testcontainers integration tests : withInitScript("local_pg_database_docker/schema.sql")
--   * the fallback local cluster : psql -f schema.sql
-- Design decisions and the reasoning behind each constraint: docs/postgres-job-queue.md §10.
-- How the tests use it: docs/test-execution.md.
--
-- Deliberately no PL/pgSQL (no triggers, no functions, no extensions):
--   * `updated_at` is maintained by the application (the queue writes are single statements that
--     already set it), so no trigger function is needed and the script stays a plain DDL;
--   * `gen_random_uuid()` is built in since PostgreSQL 13 (no pgcrypto needed);
--   * the script must be splittable by the JDBC clients that apply it (Testcontainers, psql),
--     and a dollar-quoted function body is the classic thing that breaks naive splitting.
--
-- Engine-specific by nature: `TEXT[]`, `JSONB`, partial indexes and `SKIP LOCKED` in the claim query
-- are PostgreSQL-only. Do not run this against H2 — see docs/test-strategy.md.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- agents — the inventory behind POST/GET/DELETE /api/v1/agents
-- ---------------------------------------------------------------------------
-- One row per registered agent. Liveness is *derived*, never stored: an agent is ACTIVE while
-- last_seen_at is recent, STALE beyond the stale window, OFFLINE beyond the offline window or once
-- deregistered_at is set (DELETE /agents/{agentId} sets that timestamp; the row stays, because runs
-- reference it). Every claim/lease/result call refreshes last_seen_at.
CREATE TABLE agents (
    agent_id        TEXT        PRIMARY KEY,
    name            TEXT,
    capabilities    TEXT[]      NOT NULL DEFAULT '{}',
    labels          JSONB       NOT NULL DEFAULT '{}',
    max_jobs        INT         NOT NULL DEFAULT 1 CHECK (max_jobs >= 1),
    version         TEXT,
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deregistered_at TIMESTAMPTZ
);

COMMENT ON TABLE  agents IS 'Agent inventory. Status ACTIVE/STALE/OFFLINE is computed from last_seen_at + deregistered_at, not stored.';
COMMENT ON COLUMN agents.capabilities IS 'Matched against jobs.requires_capabilities with <@ (requires must be a subset of these).';
COMMENT ON COLUMN agents.max_jobs IS 'How many runs this agent may hold at once; enforced by the service before claiming.';


-- ---------------------------------------------------------------------------
-- jobs — one row per registered test run (the queue itself)
-- ---------------------------------------------------------------------------
-- A Jira-backed run (issue_key set) is created PENDING and only becomes QUEUED once the scheduled
-- Jira collection has filled in the details; an ad-hoc run (test_name only) starts QUEUED. The claim
-- query looks at status = 'QUEUED' only, so a PENDING run is structurally unclaimable — an agent can
-- never pick up a run whose details have not arrived.
CREATE TABLE jobs (
    id                    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_key             TEXT,
    test_name             TEXT,
    source                TEXT        NOT NULL,
    status                TEXT        NOT NULL,
    priority              INT         NOT NULL DEFAULT 0,
    attempt               INT         NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts          INT         NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
    requires_capabilities TEXT[]      NOT NULL DEFAULT '{}',
    parameters            JSONB       NOT NULL DEFAULT '{}',
    idempotency_key       TEXT,
    agent_id              TEXT        REFERENCES agents (agent_id) ON DELETE RESTRICT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    lease_expires_at      TIMESTAMPTZ,
    result                JSONB,

    -- the status enum of the API (JobRunStatus)
    CONSTRAINT jobs_status_known CHECK (status IN (
        'PENDING', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'ERROR', 'SKIPPED', 'CANCELLED', 'DEAD')),

    -- source is derived from the payload: an issue key means the details live in Jira (docs §2.1)
    CONSTRAINT jobs_source_derived CHECK (source = CASE WHEN issue_key IS NULL THEN 'AD_HOC' ELSE 'JIRA' END),

    -- a registration without issue key and without test name is rejected by the API (400);
    -- the database refuses it as well rather than accepting a run that names nothing
    CONSTRAINT jobs_identity_present CHECK (issue_key IS NOT NULL OR test_name IS NOT NULL),

    -- only Jira-backed runs wait for their details
    CONSTRAINT jobs_pending_only_for_jira CHECK (status <> 'PENDING' OR source = 'JIRA'),

    -- RUNNING always means "somebody holds a lease on it"
    CONSTRAINT jobs_running_has_lease CHECK (
        status <> 'RUNNING' OR (agent_id IS NOT NULL AND lease_expires_at IS NOT NULL)),

    -- agent_id is only ever the current lease holder
    CONSTRAINT jobs_agent_only_when_running CHECK (agent_id IS NULL OR status = 'RUNNING')
);

COMMENT ON TABLE  jobs IS 'One row per registered run. jobRunId (API) = id. Every registration is an independent row: no dedupe, no overwrite.';
COMMENT ON COLUMN jobs.source IS 'Derived from issue_key (JIRA vs AD_HOC); Jira-derived details are materialised here by the scheduled collection so a DB-less agent gets everything in the claim response.';
COMMENT ON COLUMN jobs.attempt IS 'Incremented on every claim. attempt > max_attempts is what the reaper turns into DEAD.';
COMMENT ON COLUMN jobs.parameters IS 'Key/value inputs for the execution, as sent by the producer (Jira-derived values are merged in by the collection job).';
COMMENT ON COLUMN jobs.result IS 'The JobRunResult payload once the run is terminal (status, message, duration, artifacts in-band).';
COMMENT ON COLUMN jobs.idempotency_key IS 'Optional Idempotency-Key of the registration, for producers that retry (docs §12.2).';
COMMENT ON COLUMN jobs.updated_at IS 'Maintained by the application on every state change; the desktop polls incrementally on it (docs §12.12).';

-- The claim query (docs/postgres-job-queue.md §1) scans exactly these rows, in this order:
--   ... WHERE status = 'queued' AND requires_capabilities <@ ? ORDER BY priority DESC, created_at ASC
--   ... FOR UPDATE SKIP LOCKED LIMIT 1
CREATE INDEX idx_jobs_claimable ON jobs (priority DESC, created_at ASC) WHERE status = 'QUEUED';

-- The reaper looks for running runs whose lease has run out.
CREATE INDEX idx_jobs_lease_expiry ON jobs (lease_expires_at) WHERE status = 'RUNNING';

-- "What happened with TEST-4711?" (GET /api/v1/jobs?issueKey=…) and the per-agent run count
-- for max_jobs enforcement.
CREATE INDEX idx_jobs_issue_key ON jobs (issue_key, created_at DESC) WHERE issue_key IS NOT NULL;
CREATE INDEX idx_jobs_agent_running ON jobs (agent_id) WHERE status = 'RUNNING';

-- List filters and the incremental read path.
CREATE INDEX idx_jobs_status_created ON jobs (status, created_at DESC);
CREATE INDEX idx_jobs_updated_at ON jobs (updated_at DESC);

-- A retrying producer must not create a second run when it repeats the same Idempotency-Key.
CREATE UNIQUE INDEX uq_jobs_idempotency_key ON jobs (idempotency_key) WHERE idempotency_key IS NOT NULL;


-- ---------------------------------------------------------------------------
-- jira_outbox — the transactional outbox for the Jira status push (docs §9)
-- ---------------------------------------------------------------------------
-- The status change and the outbox row are written in ONE transaction, so a result can never be
-- stored without its Jira notification being scheduled (and Jira being down can never lose a result).
-- The worker sends at-least-once and is idempotent towards Jira.
CREATE TABLE jira_outbox (
    id              BIGSERIAL   PRIMARY KEY,
    job_run_id      UUID        NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    event_type      TEXT        NOT NULL DEFAULT 'RESULT',
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    last_error      TEXT
);

COMMENT ON TABLE jira_outbox IS 'Outbound Jira updates, written in the same commit as the state change. Ad-hoc runs never get a row here.';

-- The worker's only query: unsent rows that are due.
CREATE INDEX idx_jira_outbox_due ON jira_outbox (next_attempt_at) WHERE sent_at IS NULL;
