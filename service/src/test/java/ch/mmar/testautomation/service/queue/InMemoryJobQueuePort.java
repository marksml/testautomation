package ch.mmar.testautomation.service.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link JobQueuePort} for the service-layer unit tests.
 *
 * <p>It exists to test <em>decisions</em> (unknown agent, {@code maxJobs} reached, capability filter,
 * mapping onto the wire model) without a database. It is deliberately single-threaded and proves
 * nothing about concurrency: the claim's atomicity is PostgreSQL's job and is tested against real
 * PostgreSQL in {@code JobClaimConcurrencyIT} (docs/test-execution.md).
 */
public class InMemoryJobQueuePort implements JobQueuePort {

    private record Row(
            UUID id,
            String issueKey,
            String testName,
            List<String> requiresCapabilities,
            Map<String, String> parameters,
            int priority,
            int attempt,
            Instant createdAt,
            String status,
            String agentId,
            Instant leaseExpiresAt) {
    }

    private final Map<String, AgentRecord> agents = new LinkedHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private final Set<Instant> touches = new LinkedHashSet<>();
    private String lastTouchedAgentId;

    public InMemoryJobQueuePort withAgent(String agentId, int maxJobs, String... capabilities) {
        agents.put(agentId, new AgentRecord(
                agentId,
                agentId + "-name",
                List.of(capabilities),
                Map.of("env", "test"),
                maxJobs,
                "test",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null));
        return this;
    }

    public InMemoryJobQueuePort withDeregisteredAgent(String agentId, int maxJobs, String... capabilities) {
        withAgent(agentId, maxJobs, capabilities);
        AgentRecord existing = agents.get(agentId);
        agents.put(agentId, new AgentRecord(existing.agentId(), existing.name(), existing.capabilities(),
                existing.labels(), existing.maxJobs(), existing.version(), existing.registeredAt(),
                existing.lastSeenAt(), Instant.parse("2026-01-02T00:00:00Z")));
        return this;
    }

    /** A run the agents may pick up. */
    public UUID withQueuedRun(String testName, int priority, List<String> requiresCapabilities, Map<String, String> parameters) {
        return add(new Row(UUID.randomUUID(), null, testName, requiresCapabilities, parameters, priority, 0,
                Instant.parse("2026-01-01T00:00:00Z"), "QUEUED", null, null));
    }

    /** A Jira-backed run whose details have already arrived — the collection job set it to QUEUED. */
    public UUID withQueuedJiraRun(String issueKey, String testName) {
        return add(new Row(UUID.randomUUID(), issueKey, testName, List.of(), Map.of(), 0, 0,
                Instant.parse("2026-01-01T00:00:00Z"), "QUEUED", null, null));
    }

    /** A Jira-backed run that waits for its details — must never be claimed. */
    public UUID withPendingRun(String issueKey) {
        return add(new Row(UUID.randomUUID(), issueKey, null, List.of(), Map.of(), 0, 0,
                Instant.parse("2026-01-01T00:00:00Z"), "PENDING", null, null));
    }

    /** A run already held by an agent, for the {@code maxJobs} limit. */
    public UUID withRunningRun(String agentId, String testName) {
        return add(new Row(UUID.randomUUID(), null, testName, List.of(), Map.of(), 0, 1,
                Instant.parse("2026-01-01T00:00:00Z"), "RUNNING", agentId, Instant.parse("2026-01-01T00:01:00Z")));
    }

    private UUID add(Row row) {
        rows.add(row);
        return row.id();
    }

    @Override
    public Optional<AgentRecord> findAgent(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    @Override
    public int countActiveRuns(String agentId) {
        return (int) rows.stream().filter(row -> "RUNNING".equals(row.status()) && agentId.equals(row.agentId())).count();
    }

    @Override
    public void touchAgent(String agentId, Instant seenAt) {
        touches.add(seenAt);
        lastTouchedAgentId = agentId;
    }

    @Override
    public Optional<JobRunClaim> claimNext(String agentId, List<String> capabilities, Duration leaseDuration) {
        AgentRecord agent = agents.get(agentId);
        if (agent == null) {
            return Optional.empty();
        }
        Optional<Row> candidate = rows.stream()
                .filter(row -> "QUEUED".equals(row.status()))
                .filter(row -> capabilities.containsAll(row.requiresCapabilities()))
                .max((left, right) -> Integer.compare(left.priority(), right.priority()));

        return candidate.map(row -> {
            Row claimed = new Row(row.id(), row.issueKey(), row.testName(), row.requiresCapabilities(),
                    row.parameters(), row.priority(), row.attempt() + 1, row.createdAt(), "RUNNING", agentId,
                    Instant.now().plus(leaseDuration));
            rows.set(rows.indexOf(row), claimed);
            return new JobRunClaim(claimed.id(), claimed.issueKey(), claimed.testName(),
                    claimed.issueKey() == null
                            ? ch.mmar.testautomation.service.model.JobRunSource.AD_HOC
                            : ch.mmar.testautomation.service.model.JobRunSource.JIRA,
                    claimed.parameters(), claimed.requiresCapabilities(), claimed.attempt(), claimed.createdAt(),
                    claimed.leaseExpiresAt());
        });
    }

    // --- assertions helpers -------------------------------------------------

    public Set<Instant> touches() {
        return touches;
    }

    public String lastTouchedAgentId() {
        return lastTouchedAgentId;
    }

    public long queuedCount() {
        return rows.stream().filter(row -> "QUEUED".equals(row.status())).count();
    }

    public String statusOf(UUID jobRunId) {
        return rows.stream().filter(row -> row.id().equals(jobRunId)).findFirst().orElseThrow().status();
    }

    /** A convenience factory for tests that need a mutable parameter map. */
    public static Map<String, String> parameters(String... keyValuePairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }
}
