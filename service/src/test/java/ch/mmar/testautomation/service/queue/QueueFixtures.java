package ch.mmar.testautomation.service.queue;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Fixtures for the queue integration tests: plain SQL, because the registration endpoint does not
 * exist yet. Once it does, these helpers can be replaced by real {@code POST /api/v1/jobs} calls —
 * until then, inserting rows directly is the honest way to set up a queue state.
 */
public final class QueueFixtures {

    private QueueFixtures() {
    }

    public static void insertAgent(DataSource dataSource, String agentId, int maxJobs, String... capabilities) {
        execute(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO agents (agent_id, name, capabilities, max_jobs) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, agentId);
                statement.setString(2, agentId + " (test)");
                statement.setArray(3, textArray(connection, capabilities));
                statement.setInt(4, maxJobs);
                statement.executeUpdate();
            }
        });
    }

    /** @return the {@code jobRunId} of the queued run. */
    public static UUID insertQueuedRun(
            DataSource dataSource, String testName, int priority, List<String> requiresCapabilities, String parametersJson) {
        UUID id = UUID.randomUUID();
        execute(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO jobs (id, test_name, source, status, priority, requires_capabilities, parameters)
                    VALUES (?, ?, 'AD_HOC', 'QUEUED', ?, ?, ?::jsonb)
                    """)) {
                statement.setObject(1, id);
                statement.setString(2, testName);
                statement.setInt(3, priority);
                statement.setArray(4, textArray(connection, requiresCapabilities.toArray(String[]::new)));
                statement.setString(5, parametersJson);
                statement.executeUpdate();
            }
        });
        return id;
    }

    /** A Jira-backed run that waits for the scheduled collection: must never be claimed. */
    public static UUID insertPendingJiraRun(DataSource dataSource, String issueKey) {
        UUID id = UUID.randomUUID();
        execute(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO jobs (id, issue_key, source, status) VALUES (?, ?, 'JIRA', 'PENDING')")) {
                statement.setObject(1, id);
                statement.setString(2, issueKey);
                statement.executeUpdate();
            }
        });
        return id;
    }

    /** A run already held by an agent (for {@code maxJobs}), with a lease that is still valid. */
    public static UUID insertRunningRun(DataSource dataSource, String agentId, String testName) {
        UUID id = UUID.randomUUID();
        execute(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO jobs (id, test_name, source, status, agent_id, attempt, started_at, lease_expires_at)
                    VALUES (?, ?, 'AD_HOC', 'RUNNING', ?, 1, now(), now() + interval '60 seconds')
                    """)) {
                statement.setObject(1, id);
                statement.setString(2, testName);
                statement.setString(3, agentId);
                statement.executeUpdate();
            }
        });
        return id;
    }

    public static String fetchStatus(DataSource dataSource, UUID jobRunId) {
        return queryString(dataSource, "SELECT status FROM jobs WHERE id = ?", jobRunId);
    }

    public static String fetchAgentId(DataSource dataSource, UUID jobRunId) {
        return queryString(dataSource, "SELECT agent_id FROM jobs WHERE id = ?", jobRunId);
    }

    public static int fetchAttempt(DataSource dataSource, UUID jobRunId) {
        return queryInt(dataSource, "SELECT attempt FROM jobs WHERE id = ?", jobRunId);
    }

    public static boolean leaseIsInTheFuture(DataSource dataSource, UUID jobRunId) {
        return queryBoolean(dataSource, "SELECT lease_expires_at > now() FROM jobs WHERE id = ?", jobRunId);
    }

    public static boolean startedAtIsSet(DataSource dataSource, UUID jobRunId) {
        return queryBoolean(dataSource, "SELECT started_at IS NOT NULL FROM jobs WHERE id = ?", jobRunId);
    }

    public static long countByStatus(DataSource dataSource, String status) {
        return queryLong(dataSource, "SELECT count(*) FROM jobs WHERE status = ?", status);
    }

    public static long countIndexes(DataSource dataSource) {
        return queryLong(dataSource, "SELECT count(*) FROM pg_indexes WHERE tablename IN ('jobs','agents','jira_outbox')");
    }

    // --- plumbing ------------------------------------------------------------

    private static Array textArray(Connection connection, String... values) throws SQLException {
        return connection.createArrayOf("text", values);
    }

    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    private static void execute(DataSource dataSource, SqlWork work) {
        try (Connection connection = dataSource.getConnection()) {
            work.run(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("fixture failed", e);
        }
    }

    private static String queryString(DataSource dataSource, String sql, UUID id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no row for " + id);
                }
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    private static int queryInt(DataSource dataSource, String sql, UUID id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    private static boolean queryBoolean(DataSource dataSource, String sql, UUID id) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    private static long queryLong(DataSource dataSource, String sql, Object... parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    /** Unused-import guard for the timestamp helpers above; keeps the file self-documenting. */
    static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
