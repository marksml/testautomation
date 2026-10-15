package ch.mmar.testautomation.service.queue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The real PostgreSQL an integration test runs against, resolved in this order:
 *
 * <ol>
 *   <li>{@code -DTA_IT_JDBC_URL=…} (or the environment variable of the same name) — use that
 *       database. This is how the tests run on a machine without a Docker daemon, against the
 *       cluster from {@code local_pg_database_docker/docker-compose.yml} or a local install.
 *   <li>a Testcontainers {@code postgres:16} — started automatically when a Docker daemon is there.
 *   <li>neither → the test is <strong>skipped</strong> with a message saying what to do, so
 *       {@code ./mvnw verify} never fails for want of Docker.
 * </ol>
 *
 * The schema always comes from {@code local_pg_database_docker/schema.sql} — the same file the
 * compose service mounts and the production migration will be made of. Nothing here keeps a second
 * copy of the DDL.
 */
public final class PostgresTestDatabase implements AutoCloseable {

    public static final String URL_PROPERTY = "TA_IT_JDBC_URL";
    public static final String USER_PROPERTY = "TA_IT_JDBC_USER";
    public static final String PASSWORD_PROPERTY = "TA_IT_JDBC_PASSWORD";

    /** Classpath location of the canonical DDL, relative to {@code src/test/resources}. */
    public static final String SCHEMA_RESOURCE = "local_pg_database_docker/schema.sql";

    /** Image used for the container path; the compose file uses the same tag. */
    public static final String POSTGRES_IMAGE = "postgres:16";

    private final PostgreSQLContainer<?> container;
    private final DataSource dataSource;
    private final String description;

    private PostgresTestDatabase(PostgreSQLContainer<?> container, DataSource dataSource, String description) {
        this.container = container;
        this.dataSource = dataSource;
        this.description = description;
    }

    public static PostgresTestDatabase start() {
        String url = configured(URL_PROPERTY);
        if (url != null && !url.isBlank()) {
            DataSource dataSource = new SimpleDataSource(url,
                    orDefault(configured(USER_PROPERTY), "postgres"),
                    orDefault(configured(PASSWORD_PROPERTY), "postgres"));
            PostgresTestDatabase database = new PostgresTestDatabase(null, dataSource, "external database " + url);
            database.resetSchema();
            return database;
        }

        if (DockerClientFactory.instance().isDockerAvailable()) {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>(POSTGRES_IMAGE);
            container.start();
            PostgresTestDatabase database = new PostgresTestDatabase(container,
                    new SimpleDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword()),
                    "Testcontainers " + container.getDockerImageName());
            database.resetSchema();
            return database;
        }

        Assumptions.assumeTrue(false, """
                no PostgreSQL available for this integration test:
                  * with Docker  : nothing to do, Testcontainers starts postgres:16 itself
                  * without it   : start service/src/test/resources/local_pg_database_docker/docker-compose.yml \
                (or any PostgreSQL 16 with schema.sql applied) and pass \
                -DTA_IT_JDBC_URL=jdbc:postgresql://localhost:5433/testautomation \
                -DTA_IT_JDBC_USER=testautomation -DTA_IT_JDBC_PASSWORD=testautomation""");
        throw new IllegalStateException("unreachable: the assumption above always aborts");
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Human-readable origin, printed by the tests and quoted in docs/test-execution.md. */
    public String description() {
        return description;
    }

    public void resetSchema() {
        resetSchema(dataSource);
    }

    /**
     * Drops whatever is in the {@code public} schema and applies the canonical DDL again, so each
     * test starts from a known empty database. The DDL itself contains no {@code DROP}: it is
     * production DDL and must never delete data by being applied.
     */
    public static void resetSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
            for (String sql : splitStatements(readSchemaScript())) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not reset the database schema", e);
        }
    }

    @Override
    public void close() {
        if (container != null) {
            container.stop();
        }
    }

    private static String readSchemaScript() {
        try (InputStream in = PostgresTestDatabase.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("schema not on the test classpath: " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + SCHEMA_RESOURCE, e);
        }
    }

    /**
     * Splits a script into statements, ignoring {@code ;} inside string literals and stripping
     * {@code --} comments.
     *
     * <p>The canonical DDL is written so this works: no dollar-quoted function bodies, no {@code ;}
     * inside a literal. That is a deliberate property of the file — a naive splitter is what
     * Testcontainers and psql do too.
     */
    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLiteral = false;
        boolean inLineComment = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (!inLiteral && c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                inLineComment = true;
                i++;
                continue;
            }
            if (c == '\'') {
                inLiteral = !inLiteral;
            }
            if (c == ';' && !inLiteral) {
                addIfNotBlank(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder candidate) {
        String sql = candidate.toString().strip();
        if (!sql.isEmpty()) {
            statements.add(sql);
        }
    }

    private static String configured(String key) {
        String value = System.getProperty(key);
        return value != null ? value : System.getenv(key);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
