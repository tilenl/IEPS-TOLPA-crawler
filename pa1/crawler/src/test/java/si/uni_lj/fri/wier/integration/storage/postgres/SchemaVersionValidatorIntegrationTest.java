package si.uni_lj.fri.wier.integration.storage.postgres;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import si.uni_lj.fri.wier.storage.postgres.SchemaVersionValidator;

@Testcontainers(disabledWithoutDocker = true)
class SchemaVersionValidatorIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("crawldb")
                    .withUsername("test")
                    .withPassword("test");

    private static DataSource dataSource;
    private SchemaVersionValidator validator;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dataSource =
                new DataSource() {
                    @Override
                    public Connection getConnection() throws SQLException {
                        return POSTGRES.createConnection("");
                    }

                    @Override
                    public Connection getConnection(String username, String password) throws SQLException {
                        return POSTGRES.createConnection("");
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
        applySqlScript(dataSource, Path.of("db", "crawldb.sql"));
    }

    @BeforeEach
    void resetSchemaVersionRow() throws SQLException {
        validator = new SchemaVersionValidator(dataSource);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                """
                                INSERT INTO crawldb.schema_version (id, version)
                                VALUES (1, 3)
                                ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version
                                """)) {
            ps.executeUpdate();
        }
    }

    @Test
    void validateExpectedVersion_acceptsMatchingVersion() {
        assertDoesNotThrow(() -> validator.validateExpectedVersion("3"));
    }

    @Test
    void validateExpectedVersion_rejectsMismatchedVersion() throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("UPDATE crawldb.schema_version SET version = 7 WHERE id = 1")) {
            ps.executeUpdate();
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.validateExpectedVersion("3"));
        assertTrue(ex.getMessage().contains("expectedVersion=3"));
        assertTrue(ex.getMessage().contains("dbVersion=7"));
        assertTrue(ex.getMessage().contains("remediationHint="));
    }

    @Test
    void validateExpectedVersion_rejectsMissingSingletonRow() throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("DELETE FROM crawldb.schema_version WHERE id = 1")) {
            ps.executeUpdate();
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator.validateExpectedVersion("3"));
        assertTrue(ex.getMessage().contains("dbVersion=<missing>"));
        assertTrue(ex.getMessage().contains("expectedVersion=3"));
    }

    private static void applySqlScript(DataSource ds, Path scriptPath) throws IOException, SQLException {
        String sql = Files.readString(scriptPath);
        StringBuilder statement = new StringBuilder();
        try (Connection c = ds.getConnection();
                PreparedStatement dropSchema = c.prepareStatement("DROP SCHEMA IF EXISTS crawldb CASCADE")) {
            dropSchema.executeUpdate();
        }
        try (Connection c = ds.getConnection()) {
            for (String line : sql.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--")) {
                    continue;
                }
                statement.append(line).append('\n');
                if (trimmed.endsWith(";")) {
                    String sqlStatement = statement.toString().trim();
                    statement.setLength(0);
                    if (sqlStatement.endsWith(";")) {
                        sqlStatement = sqlStatement.substring(0, sqlStatement.length() - 1);
                    }
                    if (!sqlStatement.isBlank()) {
                        try (PreparedStatement ps = c.prepareStatement(sqlStatement)) {
                            ps.execute();
                        }
                    }
                }
            }
        }
    }
}
