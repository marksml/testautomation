package ch.mmar.testautomation.service.queue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Minimal {@link DataSource} over {@link DriverManager}.
 *
 * <p>Keeps the integration tests free of Spring JDBC: pulling in {@code spring-jdbc} would switch on
 * Boot's {@code DataSourceAutoConfiguration} and make the application itself require a database
 * (docs/test-execution.md §5). Ten lines beat that trade.
 */
public class SimpleDataSource implements DataSource {

    private final String url;
    private final String user;
    private final String password;

    public SimpleDataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pwd) throws SQLException {
        return DriverManager.getConnection(url, username, pwd);
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // not used in tests
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // not used in tests
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("global");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("SimpleDataSource is not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
