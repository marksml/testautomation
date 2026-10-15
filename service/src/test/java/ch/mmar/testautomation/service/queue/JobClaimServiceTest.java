package ch.mmar.testautomation.service.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.mmar.testautomation.service.model.ClaimedJobRun;
import ch.mmar.testautomation.service.model.JobRunSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the decisions above the database, against the in-memory port. No Docker, no
 * PostgreSQL, no Spring context — and, importantly, no claims about concurrency: the atomic part is
 * covered by {@code JobClaimConcurrencyIT} against real PostgreSQL (docs/test-execution.md).
 */
class JobClaimServiceTest {

    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final int HEARTBEAT_SECONDS = 20;

    private final InMemoryJobQueuePort queue = new InMemoryJobQueuePort();
    private final JobClaimService service = new JobClaimService(queue, LEASE, HEARTBEAT_SECONDS);

    @Test
    @DisplayName("an agent that never registered cannot claim (409 AGENT_UNKNOWN)")
    void unknownAgentIsRejected() {
        queue.withQueuedRun("login-smoke", 0, List.of(), InMemoryJobQueuePort.parameters());

        assertThatThrownBy(() -> service.claim("ghost"))
                .isInstanceOf(AgentUnknownException.class)
                .hasMessageContaining("ghost")
                .satisfies(e -> assertThat(((AgentUnknownException) e).agentId()).isEqualTo("ghost"));
    }

    @Test
    @DisplayName("an agent at its maxJobs limit gets nothing while its run is still running")
    void agentAtMaxJobsGetsNothing() {
        UUID running = queue.withRunningRun("agent-01", "long-running");
        queue.withQueuedRun("next-one", 0, List.of(), InMemoryJobQueuePort.parameters());
        queue.withAgent("agent-01", 1, "linux");

        assertThat(service.claim("agent-01")).isEmpty();
        assertThat(queue.statusOf(running)).isEqualTo("RUNNING");
        assertThat(queue.queuedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a claimed run is mapped onto the wire model, heartbeat interval included")
    void claimedRunIsMappedOntoTheWireModel() {
        UUID id = queue.withQueuedRun("login-smoke", 0, List.of("linux"),
                InMemoryJobQueuePort.parameters("browser", "chrome", "env", "staging"));
        queue.withAgent("agent-01", 3, "linux", "chrome");

        Instant before = Instant.now();
        ClaimedJobRun claimed = service.claim("agent-01").orElseThrow();

        assertThat(claimed.getJobRunId()).isEqualTo(id);
        assertThat(claimed.getSource()).isEqualTo(JobRunSource.AD_HOC);
        assertThat(claimed.getIssueKey()).isNull();
        assertThat(claimed.getTestName()).isEqualTo("login-smoke");
        assertThat(claimed.getParameters())
                .containsEntry("browser", "chrome")
                .containsEntry("env", "staging");
        assertThat(claimed.getRequiresCapabilities()).containsExactly("linux");
        assertThat(claimed.getAttempt()).isEqualTo(1);
        assertThat(claimed.getCreatedAt()).isNotNull();
        assertThat(claimed.getHeartbeatIntervalSeconds()).isEqualTo(HEARTBEAT_SECONDS);
        // the lease is what the agent has to renew inside
        assertThat(claimed.getLeaseExpiresAt().toInstant())
                .isAfterOrEqualTo(before.plus(LEASE).minusSeconds(1));
    }

    @Test
    @DisplayName("a Jira-backed run carries its issue key and source JIRA")
    void jiraBackedRunIsMapped() {
        queue.withQueuedJiraRun("TEST-4711", "login-smoke");
        queue.withAgent("agent-01", 1, "linux");

        ClaimedJobRun claimed = service.claim("agent-01").orElseThrow();

        assertThat(claimed.getSource()).isEqualTo(JobRunSource.JIRA);
        assertThat(claimed.getIssueKey()).isEqualTo("TEST-4711");
    }

    @Test
    @DisplayName("a PENDING run is invisible to the claim — no agent runs it half-informed")
    void pendingRunsAreNeverClaimed() {
        queue.withPendingRun("TEST-4711");
        queue.withAgent("agent-01", 3, "linux");

        assertThat(service.claim("agent-01")).isEmpty();
    }

    @Test
    @DisplayName("a run whose requirements the agent does not meet is not handed out")
    void capabilityFilterIsRespected() {
        queue.withQueuedRun("windows-only", 0, List.of("windows"), InMemoryJobQueuePort.parameters());
        queue.withAgent("agent-01", 3, "linux");

        assertThat(service.claim("agent-01")).isEmpty();
        assertThat(queue.queuedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the highest priority run is handed out first")
    void highestPriorityWins() {
        queue.withQueuedRun("low", 1, List.of(), InMemoryJobQueuePort.parameters());
        UUID high = queue.withQueuedRun("high", 9, List.of(), InMemoryJobQueuePort.parameters());
        queue.withAgent("agent-01", 3, "linux");

        assertThat(service.claim("agent-01").orElseThrow().getJobRunId()).isEqualTo(high);
    }

    @Test
    @DisplayName("two slots, two runs, then nothing: maxJobs is per agent")
    void maxJobsAllowsSeveralRuns() {
        UUID first = queue.withQueuedRun("a", 0, List.of(), InMemoryJobQueuePort.parameters());
        UUID second = queue.withQueuedRun("b", 0, List.of(), InMemoryJobQueuePort.parameters());
        queue.withAgent("agent-01", 2, "linux");

        UUID claimedFirst = service.claim("agent-01").orElseThrow().getJobRunId();
        UUID claimedSecond = service.claim("agent-01").orElseThrow().getJobRunId();

        assertThat(List.of(claimedFirst, claimedSecond)).containsExactlyInAnyOrder(first, second);
        assertThat(service.claim("agent-01")).isEmpty();
    }

    @Test
    @DisplayName("a claimable run goes to exactly one caller")
    void aRunIsHandedOutOnce() {
        queue.withQueuedRun("only-one", 0, List.of(), InMemoryJobQueuePort.parameters());
        queue.withAgent("agent-01", 3, "linux");
        queue.withAgent("agent-02", 3, "linux");

        assertThat(service.claim("agent-01")).isPresent();
        assertThat(service.claim("agent-02")).isEmpty();
    }

    @Test
    @DisplayName("every claim attempt counts as a sign of life")
    void claimTouchesTheAgent() {
        queue.withAgent("agent-01", 1, "linux");

        assertThat(service.claim("agent-01")).isEmpty();

        assertThat(queue.lastTouchedAgentId()).isEqualTo("agent-01");
        assertThat(queue.touches()).isNotEmpty();
    }

    @Test
    @DisplayName("a heartbeat that would expire the lease first is a configuration error")
    void heartbeatMustBeShorterThanTheLease() {
        assertThatThrownBy(() -> new JobClaimService(queue, Duration.ofSeconds(10), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat");
        assertThatThrownBy(() -> new JobClaimService(queue, Duration.ofSeconds(10), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobClaimService(queue, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
    }

    @Test
    @DisplayName("a deregistered agent can still claim until it is removed from the inventory (open point)")
    void deregisteredAgentBehaviourIsExplicit() {
        queue.withDeregisteredAgent("agent-old", 1, "linux");
        queue.withQueuedRun("a", 0, List.of(), InMemoryJobQueuePort.parameters());

        // documented as-is: the service does not check deregisteredAt yet (docs §12.4); the test
        // pins the current behaviour so a change is deliberate
        Optional<ClaimedJobRun> claimed = service.claim("agent-old");
        assertThat(claimed).isPresent();
    }
}
