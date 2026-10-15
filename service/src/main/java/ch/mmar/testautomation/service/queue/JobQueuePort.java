package ch.mmar.testautomation.service.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The port the queue is behind: everything that needs the database.
 *
 * <p>Two implementations exist on purpose:
 *
 * <ul>
 *   <li>{@link JdbcJobQueueRepository} — PostgreSQL, the real thing. Its claim method is the one
 *       statement that carries the engine-specific behaviour ({@code FOR UPDATE SKIP LOCKED}), which
 *       is why the concurrency tests run against real PostgreSQL and never against H2
 *       (docs/test-strategy.md).
 *   <li>{@code ch.mmar.testautomation.service.queue.InMemoryJobQueuePort} (test scope) — a fake for
 *       the service-layer decisions (unknown agent, {@code maxJobs} reached, mapping onto the wire
 *       model). It deliberately does <em>not</em> pretend to be concurrency-safe; that is the
 *       integration tests' job.
 * </ul>
 */
public interface JobQueuePort {

    /** @return the agent, or empty when {@code agentId} was never registered. */
    Optional<AgentRecord> findAgent(String agentId);

    /** How many runs this agent currently holds (status {@code RUNNING}), for {@code maxJobs}. */
    int countActiveRuns(String agentId);

    /** Records that the agent just called us; this is what keeps it {@code ACTIVE}. */
    void touchAgent(String agentId, Instant seenAt);

    /**
     * Atomically claims the next run this agent is allowed to execute.
     *
     * <p>The claim is one statement: it selects the highest-priority {@code QUEUED} row whose
     * {@code requiresCapabilities} are a subset of the agent's own, locks it with
     * {@code FOR UPDATE SKIP LOCKED} (so concurrent claimers never block and never get the same row)
     * and updates it to {@code RUNNING} with this agent, {@code attempt + 1} and a fresh lease.
     *
     * @return the claimed run, or empty when nothing is claimable for this agent right now
     */
    Optional<JobRunClaim> claimNext(String agentId, List<String> capabilities, Duration leaseDuration);
}
