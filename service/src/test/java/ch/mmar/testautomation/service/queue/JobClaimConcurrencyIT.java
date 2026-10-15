package ch.mmar.testautomation.service.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tests that make the claim mechanism credible: they run against a <strong>real PostgreSQL</strong>
 * and they exercise concurrency. Everything here fails or proves nothing on H2
 * (docs/test-strategy.md) — that is the whole point of the class.
 *
 * <p>Database: Testcontainers {@code postgres:16} when a Docker daemon is available, otherwise the
 * database given by {@code -DTA_IT_JDBC_URL}. Without either, the tests skip with an explanation
 * instead of failing (docs/test-execution.md).
 */
class JobClaimConcurrencyIT {

    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final List<String> AGENT_CAPABILITIES = List.of("linux", "chrome");

    private static PostgresTestDatabase database;

    private DataSource dataSource;
    private JdbcJobQueueRepository repository;

    @BeforeAll
    static void startDatabase() {
        database = PostgresTestDatabase.start();
        System.out.println("[IT] PostgreSQL under test: " + database.description());
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void freshSchema() {
        database.resetSchema();
        dataSource = database.dataSource();
        repository = new JdbcJobQueueRepository(dataSource);
    }

    @Test
    @DisplayName("8 agents claiming 8 queued runs concurrently get 8 distinct runs, none claimed twice")
    void concurrentClaimsAreDistinct() throws Exception {
        int agents = 8;
        List<UUID> queued = new ArrayList<>();
        for (int i = 1; i <= agents; i++) {
            QueueFixtures.insertAgent(dataSource, "agent-" + i, 10, AGENT_CAPABILITIES.toArray(String[]::new));
            queued.add(QueueFixtures.insertQueuedRun(dataSource, "run-" + i, 0, List.of("linux", "chrome"), "{}"));
        }
        assertThat(QueueFixtures.countByStatus(dataSource, "QUEUED")).isEqualTo(agents);

        ExecutorService pool = Executors.newFixedThreadPool(agents);
        try {
            List<Callable<UUID>> claims = new ArrayList<>();
            for (int i = 1; i <= agents; i++) {
                String agentId = "agent-" + i;
                claims.add(() -> claimInOwnTransaction(agentId));
            }
            List<Future<UUID>> futures = pool.invokeAll(claims);
            List<UUID> claimed = new ArrayList<>();
            for (Future<UUID> future : futures) {
                claimed.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(claimed).doesNotContainNull().hasSize(agents);
            assertThat(claimed).doesNotHaveDuplicates();
            assertThat(claimed).containsExactlyInAnyOrderElementsOf(queued);
            assertThat(QueueFixtures.countByStatus(dataSource, "RUNNING")).isEqualTo(agents);
            assertThat(QueueFixtures.countByStatus(dataSource, "QUEUED")).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("two overlapping claims see different rows instead of blocking (SKIP LOCKED)")
    void overlappingClaimsTakeDifferentRows() throws Exception {
        QueueFixtures.insertAgent(dataSource, "agent-A", 10, AGENT_CAPABILITIES.toArray(String[]::new));
        QueueFixtures.insertAgent(dataSource, "agent-B", 10, AGENT_CAPABILITIES.toArray(String[]::new));
        UUID first = QueueFixtures.insertQueuedRun(dataSource, "first", 0, List.of(), "{}");
        UUID second = QueueFixtures.insertQueuedRun(dataSource, "second", 0, List.of(), "{}");

        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);

            // A claims and holds the lock — it has not committed yet
            UUID claimedByA = repository.claimNext(a, "agent-A", AGENT_CAPABILITIES, LEASE).orElseThrow().jobRunId();

            // B must step over A's row instead of waiting for A (this is what H2 cannot do)
            UUID claimedByB = repository.claimNext(b, "agent-B", AGENT_CAPABILITIES, LEASE).orElseThrow().jobRunId();

            assertThat(List.of(claimedByA, claimedByB)).containsExactlyInAnyOrder(first, second);

            a.rollback();
            b.rollback();
        }

        // nothing was committed, so both runs are back where they were
        assertThat(QueueFixtures.countByStatus(dataSource, "QUEUED")).isEqualTo(2);
        assertThat(QueueFixtures.countByStatus(dataSource, "RUNNING")).isZero();
    }

    @Test
    @DisplayName("a claim marks the run RUNNING for that agent, increments attempt and sets a lease")
    void claimUpdatesTheRow() {
        QueueFixtures.insertAgent(dataSource, "agent-01", 5, AGENT_CAPABILITIES.toArray(String[]::new));
        UUID run = QueueFixtures.insertQueuedRun(dataSource, "login-smoke", 0, List.of("linux"),
                "{\"browser\":\"chrome\"}");

        JobRunClaim claim = repository.claimNext("agent-01", AGENT_CAPABILITIES, LEASE).orElseThrow();

        assertThat(claim.jobRunId()).isEqualTo(run);
        assertThat(claim.testName()).isEqualTo("login-smoke");
        assertThat(claim.attempt()).isEqualTo(1);
        assertThat(claim.parameters()).containsEntry("browser", "chrome");
        assertThat(claim.leaseExpiresAt()).isAfter(java.time.Instant.now());

        assertThat(QueueFixtures.fetchStatus(dataSource, run)).isEqualTo("RUNNING");
        assertThat(QueueFixtures.fetchAgentId(dataSource, run)).isEqualTo("agent-01");
        assertThat(QueueFixtures.fetchAttempt(dataSource, run)).isEqualTo(1);
        assertThat(QueueFixtures.leaseIsInTheFuture(dataSource, run)).isTrue();
        assertThat(QueueFixtures.startedAtIsSet(dataSource, run)).isTrue();
    }

    @Test
    @DisplayName("the highest priority run is handed out first, oldest first among equals")
    void highestPriorityFirst() {
        QueueFixtures.insertAgent(dataSource, "agent-01", 5, AGENT_CAPABILITIES.toArray(String[]::new));
        QueueFixtures.insertQueuedRun(dataSource, "later", 1, List.of(), "{}");
        UUID urgent = QueueFixtures.insertQueuedRun(dataSource, "urgent", 9, List.of(), "{}");

        assertThat(repository.claimNext("agent-01", AGENT_CAPABILITIES, LEASE).orElseThrow().jobRunId())
                .isEqualTo(urgent);
    }

    @Test
    @DisplayName("a run requiring capabilities the agent lacks stays queued")
    void capabilityFilterHoldsInTheClaimQuery() {
        QueueFixtures.insertAgent(dataSource, "agent-linux", 5, "linux");
        QueueFixtures.insertQueuedRun(dataSource, "windows-only", 0, List.of("windows"), "{}");

        assertThat(repository.claimNext("agent-linux", List.of("linux"), LEASE)).isEmpty();
        assertThat(QueueFixtures.countByStatus(dataSource, "QUEUED")).isEqualTo(1);
    }

    @Test
    @DisplayName("a PENDING run (details not yet collected from Jira) is never claimed")
    void pendingRunsAreNotClaimable() {
        QueueFixtures.insertAgent(dataSource, "agent-01", 5, AGENT_CAPABILITIES.toArray(String[]::new));
        UUID pending = QueueFixtures.insertPendingJiraRun(dataSource, "TEST-4711");

        assertThat(repository.claimNext("agent-01", AGENT_CAPABILITIES, LEASE)).isEmpty();
        assertThat(QueueFixtures.fetchStatus(dataSource, pending)).isEqualTo("PENDING");
    }

    /** One claim in its own transaction, the way the service calls it in production. */
    private UUID claimInOwnTransaction(String agentId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID claimed = repository
                        .claimNext(connection, agentId, AGENT_CAPABILITIES, LEASE)
                        .orElseThrow()
                        .jobRunId();
                connection.commit();
                return claimed;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }
}
