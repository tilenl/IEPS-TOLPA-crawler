package si.uni_lj.fri.wier.integration.storage.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import si.uni_lj.fri.wier.contracts.DiscoveredUrl;
import si.uni_lj.fri.wier.contracts.FetchContext;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.InsertFrontierResult;
import si.uni_lj.fri.wier.contracts.PageOutcomeType;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.contracts.PersistOutcome;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

@Testcontainers(disabledWithoutDocker = true)
class PageRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("crawldb")
                    .withUsername("test")
                    .withPassword("test");

    private static DataSource dataSource;
    private PageRepository repository;

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
    void resetTables() throws Exception {
        repository = new PageRepository(dataSource, 3, Duration.ofMillis(10), 5);
        // CASCADE clears FK-linked tables; RESTART IDENTITY keeps tests independent of prior serial values.
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "TRUNCATE TABLE crawldb.link, crawldb.image, crawldb.page_data, "
                                        + "crawldb.content_owner, crawldb.page, crawldb.site RESTART IDENTITY CASCADE")) {
            ps.executeUpdate();
        }
    }

    @Test
    void claimNextEligibleFrontier_claimsOnlyOneRow() {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        repository.insertFrontierIfAbsent("https://example.com/a", siteId, 0.7);

        Optional<?> first = repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        Optional<?> second = repository.claimNextEligibleFrontier("w2", Duration.ofSeconds(60));

        assertTrue(first.isPresent());
        assertTrue(second.isEmpty());
    }

    @Test
    void insertFrontierIfAbsent_onConflictReturnsSameIdAndSingleRow() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        InsertFrontierResult first = repository.insertFrontierIfAbsent("https://example.com/same", siteId, 0.5);
        InsertFrontierResult second = repository.insertFrontierIfAbsent("https://example.com/same", siteId, 0.6);

        assertEquals(first.pageId(), second.pageId());
        assertTrue(first.inserted());
        assertFalse(second.inserted());

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT COUNT(*) FROM crawldb.page WHERE url = ?")) {
            ps.setString(1, "https://example.com/same");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void recoverExpiredLeases_movesRowsBackToFrontier() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/stale", siteId, 0.9).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(1));

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET claim_expires_at = now() - interval '5 minutes' WHERE id = ?")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
        }

        int recovered = repository.recoverExpiredLeases(10, "lease expired");
        assertEquals(1, recovered);

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT page_type_code, claimed_by, claim_expires_at FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("FRONTIER", rs.getString(1));
                assertEquals(null, rs.getString(2));
                assertEquals(null, rs.getTimestamp(3));
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_retriesOnSqlState40001() {
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicBoolean failArmed = new AtomicBoolean(false);
        DataSource failOnceDataSource =
                new FailOnceDataSource(dataSource, failed, failArmed, "40001");
        PageRepository retryingRepo = new PageRepository(failOnceDataSource, 2, Duration.ofMillis(1), 0);

        long siteId = retryingRepo.ensureSite("example.com").orElseThrow();
        long pageId = retryingRepo.insertFrontierIfAbsent("https://example.com/retry", siteId, 0.8).pageId();
        // Arm after setup so only the SERIALIZABLE work path sees the synthetic serialization failure once.
        failArmed.set(true);
        PersistOutcome outcome =
                retryingRepo.persistFetchOutcomeWithLinks(
                        new FetchContext(pageId, "https://example.com/retry", siteId, 0, Instant.now()),
                        new FetchResult(200, "text/html", "<html>ok</html>", Instant.now()),
                        new ParseResult(List.of()),
                        List.of());

        assertTrue(failed.get());
        assertEquals(PageOutcomeType.HTML, outcome.outcomeType());
    }

    @Test
    void persistFetchOutcomeWithLinks_enforcesContentOwnerLeastRule() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long firstPage = repository.insertFrontierIfAbsent("https://example.com/one", siteId, 1.0).pageId();
        long secondPage = repository.insertFrontierIfAbsent("https://example.com/two", siteId, 0.9).pageId();

        PersistOutcome ownerOutcome =
                repository.persistFetchOutcomeWithLinks(
                        new FetchContext(firstPage, "https://example.com/one", siteId, 0, Instant.now()),
                        new FetchResult(200, "text/html", "<html>same</html>", Instant.now()),
                        new ParseResult(List.of()),
                        List.of(
                                new DiscoveredUrl(
                                        "https://example.com/discovered",
                                        siteId,
                                        firstPage,
                                        "a",
                                        "ctx",
                                        0.2)));
        PersistOutcome duplicateOutcome =
                repository.persistFetchOutcomeWithLinks(
                        new FetchContext(secondPage, "https://example.com/two", siteId, 0, Instant.now()),
                        new FetchResult(200, "text/html", "<html>same</html>", Instant.now()),
                        new ParseResult(List.of()),
                        List.of());

        assertEquals(PageOutcomeType.HTML, ownerOutcome.outcomeType());
        assertEquals(PageOutcomeType.DUPLICATE, duplicateOutcome.outcomeType());
        assertEquals(firstPage, duplicateOutcome.canonicalOwnerPageId());
        assertNotNull(ownerOutcome.ingestResult());
        assertEquals(1, ownerOutcome.ingestResult().acceptedPageIds().size());

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT owner_page_id FROM crawldb.content_owner LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(firstPage, rs.getLong(1));
            }
        }
    }

    private static void applySqlScript(DataSource ds, Path scriptPath) throws IOException, SQLException {
        String sql = Files.readString(scriptPath);
        StringBuilder statement = new StringBuilder();
        try (Connection c = ds.getConnection();
                PreparedStatement dropSchema = c.prepareStatement("DROP SCHEMA IF EXISTS crawldb CASCADE")) {
            dropSchema.executeUpdate();
        }
        try (Connection c = ds.getConnection()) {
            // Split on semicolons line-by-line so multi-statement crawldb.sql runs without a full SQL parser.
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

    private static final class FailOnceDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicBoolean flag;
        private final AtomicBoolean armed;
        private final String sqlState;

        private FailOnceDataSource(
                DataSource delegate, AtomicBoolean flag, AtomicBoolean armed, String sqlState) {
            this.delegate = delegate;
            this.flag = flag;
            this.armed = armed;
            this.sqlState = sqlState;
        }

        @Override
        public Connection getConnection() throws SQLException {
            // First getConnection after armed simulates Postgres rejecting a SERIALIZABLE transaction (SQLSTATE 40001).
            if (armed.get() && flag.compareAndSet(false, true)) {
                throw new SQLException("Synthetic serialization failure", sqlState);
            }
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }
    }
}
