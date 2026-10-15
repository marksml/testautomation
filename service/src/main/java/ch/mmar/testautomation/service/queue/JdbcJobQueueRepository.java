package ch.mmar.testautomation.service.queue;

import ch.mmar.testautomation.service.model.JobRunSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;

/**
 * PostgreSQL implementation of {@link JobQueuePort} — plain JDBC, no ORM, no Spring JDBC.
 *
 * <p>The reason is {@link #CLAIM_SQL}: the claim is one statement whose semantics are
 * engine-specific. Keeping it as hand-written JDBC means it is reviewable as the SQL that was
 * measured against PostgreSQL in docs/test-strategy.md, and it keeps the dialect in exactly one
 * class instead of leaking into a mapping layer.
 *
 * <p>No {@code spring-boot-starter-jdbc} on purpose: adding spring-jdbc would switch on Boot's
 * {@code DataSourceAutoConfiguration}, and the service would then refuse to start without a
 * database — which it must not, because persistence is not wired yet (docs/test-execution.md §5).
 */
public class JdbcJobQueueRepository implements JobQueuePort {

    private static final JsonParser JSON = JsonParserFactory.getJsonParser();

    /**
     * The claim. One statement, so no explicit transaction is needed: PostgreSQL takes the row lock
     * and applies the update atomically.
     *
     * <ul>
     *   <li>{@code requires_capabilities <@ ?} — the run's requirements must be a subset of the
     *       agent's capabilities (an empty requirement set fits every agent).
     *   <li>{@code ORDER BY priority DESC, created_at ASC} — matches {@code idx_jobs_claimable}.
     *   <li>{@code FOR UPDATE SKIP LOCKED} — concurrent claimers step over each other's rows instead
     *       of blocking; this is the part H2 cannot emulate (docs/test-strategy.md).
     *   <li>{@code status = 'QUEUED'} — {@code PENDING} runs are structurally unclaimable, so an
     *       agent can never pick up a Jira-backed run whose details have not arrived.
     * </ul>
     */
    static final String CLAIM_SQL = """
            WITH claimed AS (
                SELECT id
                  FROM jobs
                 WHERE status = 'QUEUED'
                   AND requires_capabilities <@ ?
                 ORDER BY priority DESC, created_at ASC
                   FOR UPDATE SKIP LOCKED
                 LIMIT 1
            )
            UPDATE jobs
               SET status           = 'RUNNING',
                   agent_id         = ?,
                   attempt          = attempt + 1,
                   started_at       = COALESCE(jobs.started_at, now()),
                   lease_expires_at = now() + make_interval(secs => ?),
                   updated_at       = now()
              FROM claimed
             WHERE jobs.id = claimed.id
            RETURNING jobs.id, jobs.issue_key, jobs.test_name, jobs.source, jobs.parameters,
                      jobs.requires_capabilities, jobs.attempt, jobs.created_at, jobs.lease_expires_at
            """;

    private static final String FIND_AGENT_SQL = """
            SELECT agent_id, name, capabilities, labels, max_jobs, version,
                   registered_at, last_seen_at, deregistered_at
              FROM agents
             WHERE agent_id = ?
            """;

    private static final String COUNT_ACTIVE_RUNS_SQL =
            "SELECT count(*) FROM jobs WHERE agent_id = ? AND status = 'RUNNING'";

    private static final String TOUCH_AGENT_SQL = "UPDATE agents SET last_seen_at = ? WHERE agent_id = ?";

    private final DataSource dataSource;

    public JdbcJobQueueRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<AgentRecord> findAgent(String agentId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(FIND_AGENT_SQL)) {
            statement.setString(1, agentId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readAgent(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new QueueAccessException("could not read agent " + agentId, e);
        }
    }

    @Override
    public int countActiveRuns(String agentId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(COUNT_ACTIVE_RUNS_SQL)) {
            statement.setString(1, agentId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new QueueAccessException("could not count active runs of " + agentId, e);
        }
    }

    @Override
    public void touchAgent(String agentId, Instant seenAt) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(TOUCH_AGENT_SQL)) {
            statement.setObject(1, OffsetDateTime.ofInstant(seenAt, ZoneOffset.UTC));
            statement.setString(2, agentId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new QueueAccessException("could not touch agent " + agentId, e);
        }
    }

    @Override
    public Optional<JobRunClaim> claimNext(String agentId, List<String> capabilities, Duration leaseDuration) {
        try (Connection connection = dataSource.getConnection()) {
            return claimNext(connection, agentId, capabilities, leaseDuration);
        } catch (SQLException e) {
            throw new QueueAccessException("claim failed for agent " + agentId, e);
        }
    }

    /**
     * The same claim on a caller-supplied connection, for callers that need it inside a wider
     * transaction. Autocommit stays the caller's decision; the row lock is held until commit, which
     * is exactly what makes two overlapping claimers take different rows.
     */
    public Optional<JobRunClaim> claimNext(
            Connection connection, String agentId, List<String> capabilities, Duration leaseDuration)
            throws SQLException {
        Array capabilityArray = connection.createArrayOf("text", capabilities.toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement(CLAIM_SQL)) {
            statement.setArray(1, capabilityArray);
            statement.setString(2, agentId);
            statement.setInt(3, (int) Math.max(1, leaseDuration.toSeconds()));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(readClaim(rs)) : Optional.empty();
            }
        } finally {
            capabilityArray.free();
        }
    }

    private static AgentRecord readAgent(ResultSet rs) throws SQLException {
        return new AgentRecord(
                rs.getString("agent_id"),
                rs.getString("name"),
                readTextArray(rs, "capabilities"),
                readParameters(rs.getString("labels")),
                rs.getInt("max_jobs"),
                rs.getString("version"),
                readInstant(rs, "registered_at"),
                readInstant(rs, "last_seen_at"),
                readInstant(rs, "deregistered_at"));
    }

    private static JobRunClaim readClaim(ResultSet rs) throws SQLException {
        return new JobRunClaim(
                rs.getObject("id", UUID.class),
                rs.getString("issue_key"),
                rs.getString("test_name"),
                JobRunSource.fromValue(rs.getString("source")),
                readParameters(rs.getString("parameters")),
                readTextArray(rs, "requires_capabilities"),
                rs.getInt("attempt"),
                readInstant(rs, "created_at"),
                readInstant(rs, "lease_expires_at"));
    }

    private static List<String> readTextArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * JSONB → flat key/value map. {@code parameters} is documented as key/value strings, so Boot's
     * own parser is enough and no JSON library has to be wired in.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> readParameters(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = JSON.parseMap(json);
        Map<String, String> result = new LinkedHashMap<>();
        parsed.forEach((key, value) -> result.put(key, value == null ? null : String.valueOf(value)));
        return result;
    }

    /** Wraps SQL failures so the service layer never has to know about JDBC. */
    public static class QueueAccessException extends RuntimeException {
        public QueueAccessException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
