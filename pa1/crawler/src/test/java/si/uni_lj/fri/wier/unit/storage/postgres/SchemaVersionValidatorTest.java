package si.uni_lj.fri.wier.unit.storage.postgres;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.storage.postgres.SchemaVersionValidator;

class SchemaVersionValidatorTest {

    @Test
    void validateExpectedVersion_rejectsNonNumericExpectedVersion() {
        SchemaVersionValidator validator = new SchemaVersionValidator(throwingDataSource("unused"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> validator.validateExpectedVersion("abc"));
        assertTrue(ex.getMessage().contains("crawler.db.expectedSchemaVersion"));
        assertTrue(ex.getMessage().contains("integer >= 1"));
    }

    @Test
    void validateExpectedVersion_wrapsConnectivityFailureWithDiagnostics() {
        SchemaVersionValidator validator =
                new SchemaVersionValidator(throwingDataSource("Synthetic DB down for startup preflight"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.validateExpectedVersion("2"));
        assertTrue(ex.getMessage().contains("configKey=crawler.db.expectedSchemaVersion"));
        assertTrue(ex.getMessage().contains("expectedVersion=2"));
        assertTrue(ex.getMessage().contains("dbVersion=<unknown>"));
        assertTrue(ex.getMessage().contains("remediationHint="));
    }

    private static DataSource throwingDataSource(String message) {
        return new DataSource() {
            @Override
            public java.sql.Connection getConnection() throws SQLException {
                throw new SQLException(message);
            }

            @Override
            public java.sql.Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("unwrap is not supported");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {}

            @Override
            public void setLoginTimeout(int seconds) {}

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
        };
    }
}
