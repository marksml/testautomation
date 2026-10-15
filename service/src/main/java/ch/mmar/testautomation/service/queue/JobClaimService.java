package ch.mmar.testautomation.service.queue;

import ch.mmar.testautomation.service.model.ClaimedJobRun;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The decisions that sit above the database and below the HTTP layer: who may claim, whether they
 * may claim right now, and how a claimed row is handed to the agent.
 *
 * <p>Deliberately free of JDBC and of Spring, so it can be unit-tested against the in-memory port;
 * the atomic part it delegates to {@link JobQueuePort#claimNext} is tested against real PostgreSQL
 * (docs/test-execution.md).
 *
 * <p>Not wired into the REST delegate yet: {@code JobsApiDelegateImpl.claimJobRun} still answers
 * {@code 501}, because wiring it needs a configured {@code DataSource} in the running application
 * (docs/test-execution.md §5).
 */
public class JobClaimService {

    /** How long a claim stays valid before the reaper may take the run back. */
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(60);

    /** How often a holder is expected to renew inside that lease. */
    public static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 20;

    private final JobQueuePort queue;
    private final Duration leaseDuration;
    private final int heartbeatIntervalSeconds;
    private final Clock clock;

    public JobClaimService(JobQueuePort queue, Duration leaseDuration, int heartbeatIntervalSeconds) {
        this(queue, leaseDuration, heartbeatIntervalSeconds, Clock.systemUTC());
    }

    JobClaimService(JobQueuePort queue, Duration leaseDuration, int heartbeatIntervalSeconds, Clock clock) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        if (heartbeatIntervalSeconds <= 0 || heartbeatIntervalSeconds >= leaseDuration.toSeconds()) {
            throw new IllegalArgumentException(
                    "heartbeat interval must be positive and shorter than the lease ("
                            + heartbeatIntervalSeconds + "s vs " + leaseDuration.toSeconds() + "s)");
        }
        this.queue = queue;
        this.leaseDuration = leaseDuration;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.clock = clock;
    }

    /**
     * One claim attempt. The long-polling loop ({@code claim?wait=}) belongs to the HTTP layer: it
     * repeats this call and answers {@code 204} when it stays empty.
     *
     * @throws AgentUnknownException when the agent is not registered — {@code 409 AGENT_UNKNOWN}
     * @return the claimed run, or empty when this agent is at its {@code maxJobs} limit or nothing
     *     claimable matches its capabilities
     */
    public Optional<ClaimedJobRun> claim(String agentId) {
        AgentRecord agent = queue.findAgent(agentId)
                .orElseThrow(() -> new AgentUnknownException(agentId));

        // Any call counts as a sign of life, including one that ends up claiming nothing.
        queue.touchAgent(agentId, clock.instant());

        if (queue.countActiveRuns(agentId) >= agent.maxJobs()) {
            return Optional.empty();
        }
        return queue.claimNext(agentId, agent.capabilities(), leaseDuration).map(this::toWire);
    }

    private ClaimedJobRun toWire(JobRunClaim claim) {
        return new ClaimedJobRun()
                .jobRunId(claim.jobRunId())
                .source(claim.source())
                .issueKey(claim.issueKey())
                .testName(claim.testName())
                .parameters(claim.parameters())
                .requiresCapabilities(claim.requiresCapabilities())
                .attempt(claim.attempt())
                .createdAt(claim.createdAt() == null ? null : OffsetDateTime.ofInstant(claim.createdAt(), ZoneOffset.UTC))
                .leaseExpiresAt(OffsetDateTime.ofInstant(claim.leaseExpiresAt(), ZoneOffset.UTC))
                .heartbeatIntervalSeconds(heartbeatIntervalSeconds);
    }
}
