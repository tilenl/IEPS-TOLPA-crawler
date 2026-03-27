package si.uni_lj.fri.wier.integration.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import si.uni_lj.fri.wier.contracts.CanonicalizationResult;
import si.uni_lj.fri.wier.contracts.DiscoveredUrl;
import si.uni_lj.fri.wier.contracts.IngestResult;
import si.uni_lj.fri.wier.downloader.normalize.UrlCanonicalizer;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Thin Stage-A integration: TS-05 {@link UrlCanonicalizer} output is fed into {@link PageRepository#ingestDiscoveredUrls}
 * as {@link DiscoveredUrl} canonical strings — without waiting on TS-04 HTML extraction.
 */
@Testcontainers(disabledWithoutDocker = true)
class UrlCanonicalizationPipelineIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("crawldb")
                    .withUsername("test")
                    .withPassword("test");

    private static DataSource dataSource;
    private PageRepository repository;
    private final UrlCanonicalizer canonicalizer = new UrlCanonicalizer();

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
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "TRUNCATE TABLE crawldb.link, crawldb.image, crawldb.page_data, "
                                        + "crawldb.content_owner, crawldb.page, crawldb.site RESTART IDENTITY CASCADE")) {
            ps.executeUpdate();
        }
    }

    @Test
    void canonicalizedRelativeHref_ingestedIntoFrontierWithExpectedUrl() throws Exception {
        String base = "https://github.com/topics/image-segmentation";
        long siteId = repository.ensureSite("github.com").orElseThrow();
        long fromPage = repository.insertFrontierIfAbsent(base, siteId, 0.5).pageId();

        CanonicalizationResult r = canonicalizer.canonicalize("../torvalds/linux", base);
        assertTrue(r.accepted(), "expected accepted, got " + r.reasonCode());
        String expected = "https://github.com/torvalds/linux";
        assertEquals(expected, r.canonicalUrl());

        IngestResult ingest =
                repository.ingestDiscoveredUrls(
                        List.of(new DiscoveredUrl(r.canonicalUrl(), siteId, fromPage, "link", "ctx", 0.3)));
        assertEquals(1, ingest.acceptedPageIds().size());

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT url FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, ingest.acceptedPageIds().get(0));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getString(1));
            }
        }
    }

    @Test
    void canonicalizedUrlWithFragmentAndNoiseQuery_storedAsAllowlistedCanonical() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long fromPage = repository.insertFrontierIfAbsent("https://example.com/parent", siteId, 0.5).pageId();

        CanonicalizationResult r =
                canonicalizer.canonicalize(
                        "https://example.com/topics?page=2&utm_source=x#section", null);
        assertTrue(r.accepted());
        String expected = "https://example.com/topics?page=2";
        assertEquals(expected, r.canonicalUrl());

        IngestResult ingest =
                repository.ingestDiscoveredUrls(
                        List.of(new DiscoveredUrl(r.canonicalUrl(), siteId, fromPage, "", "", 0.1)));
        assertEquals(1, ingest.acceptedPageIds().size());

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT url FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, ingest.acceptedPageIds().get(0));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getString(1));
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
