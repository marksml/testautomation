package ch.mmar.testautomation.service.queue;

import java.time.Instant;

/**
 * One registered agent as the queue sees it.
 *
 * <p>Field set matches the {@code agents} table in
 * {@code service/src/test/resources/local_pg_database_docker/schema.sql}. Liveness is not a field: it
 * is derived from {@code lastSeenAt} and {@code deregisteredAt} (docs/postgres-job-queue.md §10).
 */
public record AgentRecord(
        String agentId,
        String name,
        java.util.List<String> capabilities,
        java.util.Map<String, String> labels,
        int maxJobs,
        String version,
        Instant registeredAt,
        Instant lastSeenAt,
        Instant deregisteredAt) {

    /** An agent that was shut down deliberately ({@code DELETE /api/v1/agents/{agentId}}). */
    public boolean deregistered() {
        return deregisteredAt != null;
    }
}
