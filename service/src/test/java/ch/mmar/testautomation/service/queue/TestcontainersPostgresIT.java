package ch.mmar.testautomation.service.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The explicit Testcontainers test: it starts its <em>own</em> PostgreSQL 16 and asserts the two
 * things that have to hold for every other database test to be worth anything.
 *
 * <ol>
 *   <li>the canonical DDL applies to a virgin container (tables and indexes appear), and
 *   <li>the claim works against that container end to end.
 * </ol>
 *
 * <p>{@code disabledWithoutDocker = true} means the class is skipped on a machine without a Docker
 * daemon (like the Hermes VM — docs/test-execution.md §4) rather than failing the build. The
 * database-backed assertions themselves live in {@link JobClaimConcurrencyIT}, which runs in both
 * environments.
 */
@Testcontainers(disabledWithoutDocker = true)
class TestcontainersPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(PostgresTestDatabase.POSTGRES_IMAGE);

    @Test
    @DisplayName("postgres:16 in a container: the DDL from the compose folder applies and the queue runs")
    void schemaAppliesAndTheQueueWorks() {
        DataSource dataSource =
                new SimpleDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PostgresTestDatabase.resetSchema(dataSource);

        assertThat(QueueFixtures.countIndexes(dataSource))
                .as("indexes of jobs/agents/jira_outbox: the partial claim index and friends")
                .isGreaterThanOrEqualTo(11);

        JdbcJobQueueRepository repository = new JdbcJobQueueRepository(dataSource);
        QueueFixtures.insertAgent(dataSource, "agent-container", 2, "linux", "chrome");
        UUID first = QueueFixtures.insertQueuedRun(dataSource, "in-container-1", 0, List.of("linux"),
                "{\"env\":\"staging\"}");
        UUID second = QueueFixtures.insertQueuedRun(dataSource, "in-container-2", 0, List.of(), "{}");

        JobRunClaim claimed = repository
                .claimNext("agent-container", List.of("linux", "chrome"), Duration.ofSeconds(60))
                .orElseThrow();

        assertThat(claimed.jobRunId()).isIn(first, second);
        assertThat(claimed.source()).isEqualTo(ch.mmar.testautomation.service.model.JobRunSource.AD_HOC);
        assertThat(QueueFixtures.fetchStatus(dataSource, claimed.jobRunId())).isEqualTo("RUNNING");

        JobRunClaim next = repository
                .claimNext("agent-container", List.of("linux", "chrome"), Duration.ofSeconds(60))
                .orElseThrow();
        assertThat(next.jobRunId()).isNotEqualTo(claimed.jobRunId());
        assertThat(QueueFixtures.countByStatus(dataSource, "RUNNING")).isEqualTo(2);
    }
}
