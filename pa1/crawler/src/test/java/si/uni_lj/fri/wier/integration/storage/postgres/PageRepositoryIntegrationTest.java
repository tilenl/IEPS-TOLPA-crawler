package si.uni_lj.fri.wier.integration.storage.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import si.uni_lj.fri.wier.contracts.DiscoveredUrl;
import si.uni_lj.fri.wier.contracts.ExtractedPageMetadata;
import si.uni_lj.fri.wier.contracts.FetchContext;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.contracts.InsertFrontierResult;
import si.uni_lj.fri.wier.contracts.IngestResult;
import si.uni_lj.fri.wier.contracts.PageOutcomeType;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.contracts.PersistOutcome;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.observability.RunSummaryReporter;
import si.uni_lj.fri.wier.observability.SeedBootstrapStats;
import si.uni_lj.fri.wier.queue.claim.ClaimService;
import si.uni_lj.fri.wier.storage.frontier.FrontierStore;
import si.uni_lj.fri.wier.downloader.dedup.ContentHasherImpl;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.queue.enqueue.EnqueueService;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;
import si.uni_lj.fri.wier.unit.downloader.fetch.AllowAllPolitenessStub;

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

        int recovered = repository.recoverExpiredLeases(10, "lease expired", "integration-test");
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
                        ParseResult.empty(),
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
                        ParseResult.empty(),
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
                        ParseResult.empty(),
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

        long duplicateFrom = secondPage;
        long ownerTo = firstPage;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT 1 FROM crawldb.link WHERE from_page = ? AND to_page = ? LIMIT 1")) {
            ps.setLong(1, duplicateFrom);
            ps.setLong(2, ownerTo);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "TS-09 duplicate page must link to content owner");
            }
        }
    }

    @Test
    void ingestDiscoveredUrls_viaPersist_strictUrlDedup_twoLinksToSharedTarget() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long parentA = repository.insertFrontierIfAbsent("https://example.com/parent-a", siteId, 0.6).pageId();
        long parentB = repository.insertFrontierIfAbsent("https://example.com/parent-b", siteId, 0.5).pageId();
        String sharedChild = "https://example.com/shared-child";

        repository.persistFetchOutcomeWithLinks(
                new FetchContext(parentA, "https://example.com/parent-a", siteId, 0, Instant.now()),
                new FetchResult(200, "text/html", "<html>a</html>", Instant.now()),
                ParseResult.empty(),
                List.of(new DiscoveredUrl(sharedChild, siteId, parentA, "x", "c", 0.3)));
        repository.persistFetchOutcomeWithLinks(
                new FetchContext(parentB, "https://example.com/parent-b", siteId, 0, Instant.now()),
                new FetchResult(200, "text/html", "<html>b</html>", Instant.now()),
                ParseResult.empty(),
                List.of(new DiscoveredUrl(sharedChild, siteId, parentB, "y", "c", 0.4)));

        long targetId;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT id FROM crawldb.page WHERE url = ?")) {
            ps.setString(1, sharedChild);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                targetId = rs.getLong(1);
                assertFalse(rs.next(), "exactly one page row for canonical URL");
            }
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT COUNT(*) FROM crawldb.link WHERE to_page = ? AND from_page IN (?, ?)")) {
            ps.setLong(1, targetId);
            ps.setLong(2, parentA);
            ps.setLong(3, parentB);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_discoveredUrlSameAsSourcePage_doesNotInsertSelfLink() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        String pageUrl = "https://example.com/home";
        long pageId = repository.insertFrontierIfAbsent(pageUrl, siteId, 0.7).pageId();

        repository.persistFetchOutcomeWithLinks(
                new FetchContext(pageId, pageUrl, siteId, 0, Instant.now()),
                new FetchResult(200, "text/html", "<html>home</html>", Instant.now()),
                ParseResult.empty(),
                List.of(new DiscoveredUrl(pageUrl, siteId, pageId, "Home", "nav", 0.5)));

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT COUNT(*) FROM crawldb.link WHERE from_page = ? AND to_page = ?")) {
            ps.setLong(1, pageId);
            ps.setLong(2, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1), "self-loop link must not be stored");
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_serialSameHash_twoDisjointBatches() throws Exception {
        int n = 4;
        long siteId = repository.ensureSite("example.com").orElseThrow();
        ContentHasherImpl hasher = new ContentHasherImpl();
        runSerialSameHashBatch(
                dataSource, repository, siteId, n, "<html>ts09-serial-a</html>", 0, hasher);
        runSerialSameHashBatch(
                dataSource, repository, siteId, n, "<html>ts09-serial-b</html>", 1, hasher);
    }

    /**
     * TS-09: overlapping {@code SERIALIZABLE} persists for the same HTML body must still yield one {@code HTML} row,
     * {@code n-1} {@code DUPLICATE} rows, {@code content_owner} keyed by LEAST({@code page_id}), and duplicate→owner
     * {@code link} edges. Postconditions: {@link Ts09DedupAssertions#assertSameHashBatchPostconditions}.
     */
    @Test
    void persistFetchOutcomeWithLinks_parallelSameHash_overlappingSerializable() throws Exception {
        int n = 4;
        long siteId = repository.ensureSite("example.com").orElseThrow();
        ContentHasherImpl hasher = new ContentHasherImpl();
        String htmlBody = "<html>ts09-parallel-overlap</html>";
        // Hot content_hash can still trigger 40001 under SERIALIZABLE; extra retries for overlapping persists.
        PageRepository parallelRepo = new PageRepository(dataSource, 16, Duration.ofMillis(10), 40);

        List<Long> insertedIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String url = "https://example.com/ts09par/u" + i;
            insertedIds.add(parallelRepo.insertFrontierIfAbsent(url, siteId, 0.5).pageId());
        }
        long expectedOwner = insertedIds.stream().mapToLong(Long::longValue).min().orElseThrow();

        List<Long> claimedIds = new ArrayList<>();
        List<String> claimedUrls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            FrontierRow row =
                    parallelRepo.claimNextEligibleFrontier("w-par-" + i, Duration.ofSeconds(120)).orElseThrow();
            claimedIds.add(row.pageId());
            claimedUrls.add(row.url());
        }
        assertEquals(
                insertedIds.stream().sorted().toList(),
                claimedIds.stream().sorted().toList(),
                "claimed set should match inserted frontier ids");

        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService executor = Executors.newFixedThreadPool(n);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        barrier.await(60, TimeUnit.SECONDS);
                                    } catch (TimeoutException e) {
                                        throw new IllegalStateException(
                                                "CyclicBarrier timed out waiting for peers", e);
                                    } catch (BrokenBarrierException e) {
                                        throw new IllegalStateException("CyclicBarrier broken", e);
                                    }
                                    parallelRepo.persistFetchOutcomeWithLinks(
                                            new FetchContext(
                                                    claimedIds.get(idx),
                                                    claimedUrls.get(idx),
                                                    siteId,
                                                    0,
                                                    Instant.now()),
                                            new FetchResult(200, "text/html", htmlBody, Instant.now()),
                                            ParseResult.empty(),
                                            List.of());
                                    return null;
                                }));
            }
            for (Future<?> f : futures) {
                f.get(180, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }

        Ts09DedupAssertions.assertSameHashBatchPostconditions(
                dataSource, claimedIds, expectedOwner, hasher.sha256(htmlBody), n);
    }

    /**
     * Inserts {@code n} frontier rows, claims each, then persists identical HTML for every claimed page <strong>in
     * sequence</strong> on the test thread. Asserts TS-09 LEAST ownership, duplicate→owner links, and stable
     * {@code content_hash}. Parallel overlap is covered by {@link
     * #persistFetchOutcomeWithLinks_parallelSameHash_overlappingSerializable}.
     */
    private static void runSerialSameHashBatch(
            DataSource dataSource,
            PageRepository repository,
            long siteId,
            int n,
            String htmlBody,
            int batchIndex,
            ContentHasherImpl hasher)
            throws Exception {
        List<Long> insertedIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String url = "https://example.com/ts09cc/b" + batchIndex + "-u" + i;
            insertedIds.add(repository.insertFrontierIfAbsent(url, siteId, 0.5).pageId());
        }
        long expectedOwner = insertedIds.stream().mapToLong(Long::longValue).min().orElseThrow();

        List<Long> claimedIds = new ArrayList<>();
        List<String> claimedUrls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            FrontierRow row =
                    repository.claimNextEligibleFrontier("w-b" + batchIndex + "-" + i, Duration.ofSeconds(120))
                            .orElseThrow();
            claimedIds.add(row.pageId());
            claimedUrls.add(row.url());
        }
        assertEquals(
                insertedIds.stream().sorted().toList(),
                claimedIds.stream().sorted().toList(),
                "claimed set should match inserted frontier ids");

        for (int i = 0; i < n; i++) {
            long pid = claimedIds.get(i);
            String url = claimedUrls.get(i);
            repository.persistFetchOutcomeWithLinks(
                    new FetchContext(pid, url, siteId, 0, Instant.now()),
                    new FetchResult(200, "text/html", htmlBody, Instant.now()),
                    ParseResult.empty(),
                    List.of());
        }

        Ts09DedupAssertions.assertSameHashBatchPostconditions(
                dataSource, claimedIds, expectedOwner, hasher.sha256(htmlBody), n);
    }

    @Test
    void persistFetchOutcomeWithLinks_doesNotWritePageDataForHtml() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/meta", siteId, 0.9).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        ParseResult parsed =
                new ParseResult(
                        List.of(),
                        List.of(),
                        Optional.of(new ExtractedPageMetadata("Doc title", "Meta desc here")));
        repository.persistFetchOutcomeWithLinks(
                new FetchContext(pageId, "https://example.com/meta", siteId, 0, Instant.now()),
                new FetchResult(200, "text/html", "<html><title>x</title></html>", Instant.now()),
                parsed,
                List.of());

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT COUNT(*) FROM crawldb.page_data WHERE page_id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_binaryUpsertsPageDataWithNullData_forPdfMime() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/doc.bin", siteId, 0.5).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        repository.persistFetchOutcomeWithLinks(
                new FetchContext(pageId, "https://example.com/doc.bin", siteId, 0, Instant.now()),
                new FetchResult(200, "application/pdf", "%PDF-1.4 minimal", Instant.now()),
                ParseResult.empty(),
                List.of());

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT data_type_code, data FROM crawldb.page_data WHERE page_id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("PDF", rs.getString(1));
                rs.getBytes(2);
                assertTrue(rs.wasNull());
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_textHtmlOnDenyPathSuffix_py_isBinary() throws Exception {
        long siteId = repository.ensureSite("github.com").orElseThrow();
        String url =
                "https://github.com/pytorch/fairseq/blob/master/fairseq/optim/adafactor.py";
        long pageId = repository.insertFrontierIfAbsent(url, siteId, 0.5).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        PersistOutcome outcome =
                repository.persistFetchOutcomeWithLinks(
                        new FetchContext(pageId, url, siteId, 0, Instant.now()),
                        new FetchResult(200, "text/html", "<html>wrapper</html>", Instant.now()),
                        ParseResult.empty(),
                        List.of());

        assertEquals(PageOutcomeType.BINARY, outcome.outcomeType());
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT page_type_code, html_content FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("BINARY", rs.getString(1));
                rs.getString(2);
                assertTrue(rs.wasNull());
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_textHtmlOnMdSuffix_notOnDenylist_isHtml() throws Exception {
        long siteId = repository.ensureSite("github.com").orElseThrow();
        String url = "https://github.com/org/repo/blob/main/README.md";
        long pageId = repository.insertFrontierIfAbsent(url, siteId, 0.5).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        PersistOutcome outcome =
                repository.persistFetchOutcomeWithLinks(
                        new FetchContext(pageId, url, siteId, 0, Instant.now()),
                        new FetchResult(200, "text/html", "<html><title>x</title></html>", Instant.now()),
                        ParseResult.empty(),
                        List.of());

        assertEquals(PageOutcomeType.HTML, outcome.outcomeType());
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT page_type_code FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("HTML", rs.getString(1));
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_textHtmlOnRepoNameEndingInPytorch_isHtml() throws Exception {
        long siteId = repository.ensureSite("github.com").orElseThrow();
        String url = "https://github.com/qubvel-org/segmentation_models.pytorch";
        long pageId = repository.insertFrontierIfAbsent(url, siteId, 0.5).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        PersistOutcome outcome =
                repository.persistFetchOutcomeWithLinks(
                        new FetchContext(pageId, url, siteId, 0, Instant.now()),
                        new FetchResult(200, "text/html", "<html><title>repo</title></html>", Instant.now()),
                        ParseResult.empty(),
                        List.of());

        assertEquals(PageOutcomeType.HTML, outcome.outcomeType());
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT page_type_code FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("HTML", rs.getString(1));
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_binarySkipsPageDataWhenTypeUnmapped() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/pic.png", siteId, 0.5).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        repository.persistFetchOutcomeWithLinks(
                new FetchContext(pageId, "https://example.com/pic.png", siteId, 0, Instant.now()),
                new FetchResult(200, "image/png", "fake", Instant.now()),
                ParseResult.empty(),
                List.of());

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT COUNT(*) FROM crawldb.page_data WHERE page_id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void reschedulePage_updatesOnlyProcessingRows() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/resched", siteId, 0.8).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        Instant next = Instant.now().plusSeconds(120);
        assertTrue(
                repository.reschedulePage(
                        pageId, next, "FETCH_TIMEOUT", "retry policy", false));
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT page_type_code, attempt_count, parser_retry_count, last_error_category, last_error_message FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("FRONTIER", rs.getString(1));
                assertEquals(1, rs.getInt(2));
                assertEquals(0, rs.getInt(3));
                assertEquals("FETCH_TIMEOUT", rs.getString(4));
                assertEquals("retry policy", rs.getString(5));
            }
        }
    }

    @Test
    void reschedulePage_returnsFalseWhenNotProcessing() {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/notclaimed", siteId, 0.7).pageId();
        assertFalse(
                repository.reschedulePage(
                        pageId,
                        Instant.now().plusSeconds(10),
                        "FETCH_TIMEOUT",
                        "should not apply",
                        false));
    }

    @Test
    void markPageTerminalError_updatesOnlyProcessingRows() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long claimed =
                repository.insertFrontierIfAbsent("https://example.com/errproc", siteId, 0.6).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        repository.markPageTerminalError(claimed, "FETCH", "failed");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT page_type_code FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, claimed);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("ERROR", rs.getString(1));
            }
        }

        long frontierOnly =
                repository.insertFrontierIfAbsent("https://example.com/errskip", siteId, 0.5).pageId();
        assertThrows(
                IllegalStateException.class,
                () -> repository.markPageTerminalError(frontierOnly, "FETCH", "should not apply"));
    }

    @Test
    void markPageTerminalError_throwsWhenRowAlreadyTerminal() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/errtwice", siteId, 0.6).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        repository.markPageTerminalError(pageId, "FETCH_TIMEOUT", "once");
        assertThrows(
                IllegalStateException.class, () -> repository.markPageTerminalError(pageId, "FETCH_TIMEOUT", "twice"));
    }

    @Test
    void reschedulePage_afterFetchRetries_parserRescheduleLeavesAttemptCount() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/mixed-retry", siteId, 0.95).pageId();
        for (int i = 0; i < 2; i++) {
            repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
            assertTrue(
                    repository.reschedulePage(
                            pageId, Instant.now().plusSeconds(10), "FETCH_TIMEOUT", "fetch fail", false));
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE crawldb.page SET next_attempt_at = now() WHERE id = ?")) {
                ps.setLong(1, pageId);
                ps.executeUpdate();
            }
        }
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        assertTrue(
                repository.reschedulePage(
                        pageId, Instant.now().plusSeconds(10), "PARSER_FAILURE", "parse fail", true));
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT attempt_count, parser_retry_count FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(2, rs.getInt(1));
                assertEquals(1, rs.getInt(2));
            }
        }
    }

    @Test
    void reschedulePage_parserStageIncrementsParserRetryOnly() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/parse-retry", siteId, 0.9).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        Instant next = Instant.now().plusSeconds(60);
        assertTrue(repository.reschedulePage(pageId, next, "PARSER_FAILURE", "bad html", true));
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT attempt_count, parser_retry_count, last_error_category FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
                assertEquals(1, rs.getInt(2));
                assertEquals("PARSER_FAILURE", rs.getString(3));
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_resetsParserRetryCountOnHtml() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/parse-ok", siteId, 0.9).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        assertTrue(
                repository.reschedulePage(
                        pageId, Instant.now().plusSeconds(5), "PARSER_FAILURE", "first fail", true));
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET page_type_code = 'FRONTIER', next_attempt_at = now(), claimed_by = NULL, claimed_at = NULL, claim_expires_at = NULL WHERE id = ?")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
        }
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        repository.persistFetchOutcomeWithLinks(
                new FetchContext(pageId, "https://example.com/parse-ok", siteId, 0, Instant.now()),
                new FetchResult(200, "text/html", "<html>x</html>", Instant.now()),
                ParseResult.empty(),
                List.of());
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT page_type_code, parser_retry_count FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("HTML", rs.getString(1));
                assertEquals(0, rs.getInt(2));
            }
        }
    }

    @Test
    void sampleFrontierOverdueHealth_emptyQueue_returnsZeros() {
        PageRepository.FrontierOverdueHealth h = repository.sampleFrontierOverdueHealth();
        assertEquals(0L, h.overdueFrontierCount());
        assertEquals(0L, h.avgOverdueMillis());
        assertEquals(0L, h.oldestOverdueMillis());
    }

    @Test
    void sampleFrontierOverdueHealth_reportsAvgAndMaxForOverdueFrontierRows() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long a = repository.insertFrontierIfAbsent("https://example.com/qa", siteId, 0.5).pageId();
        long b = repository.insertFrontierIfAbsent("https://example.com/qb", siteId, 0.4).pageId();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "UPDATE crawldb.page SET next_attempt_at = now() - interval '1 minute' WHERE id = ?")) {
                ps.setLong(1, a);
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "UPDATE crawldb.page SET next_attempt_at = now() - interval '5 minutes' WHERE id = ?")) {
                ps.setLong(1, b);
                ps.executeUpdate();
            }
        }
        PageRepository.FrontierOverdueHealth h = repository.sampleFrontierOverdueHealth();
        assertEquals(2L, h.overdueFrontierCount());
        assertTrue(h.oldestOverdueMillis() >= h.avgOverdueMillis());
        assertTrue(h.oldestOverdueMillis() > 0L);
        assertTrue(h.avgOverdueMillis() > 0L);
    }

    @Test
    void attemptBudget_survivesNewPageRepositoryInstance_sameDataSource() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/restart-budget", siteId, 0.95).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        assertTrue(
                repository.reschedulePage(
                        pageId, Instant.now().plusSeconds(30), "FETCH_TIMEOUT", "transient", false));
        PageRepository fresh = new PageRepository(dataSource, 3, Duration.ofMillis(10), 5);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = now() WHERE id = ?")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
        }
        Optional<FrontierRow> claimed =
                fresh.claimNextEligibleFrontier("w2", Duration.ofSeconds(60));
        assertTrue(claimed.isPresent());
        assertEquals(1, claimed.get().attemptCount());
        assertEquals(pageId, claimed.get().pageId());
    }

    @Test
    void reschedulePage_whileNotProcessing_returnsFalse() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/double-resched", siteId, 0.8).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        assertTrue(
                repository.reschedulePage(
                        pageId, Instant.now().plusSeconds(10), "FETCH_TIMEOUT", "first", false));
        assertFalse(
                repository.reschedulePage(
                        pageId, Instant.now().plusSeconds(20), "FETCH_TIMEOUT", "second", false));
    }

    @Test
    void claimNextEligibleFrontier_refreshesCrawlerMetricsQueueHealth() throws Exception {
        CrawlerMetrics metrics = new CrawlerMetrics();
        FrontierStore store = new FrontierStore(repository, metrics);
        long siteId = repository.ensureSite("example.com").orElseThrow();
        repository.insertFrontierIfAbsent("https://example.com/metrics-high", siteId, 0.99);
        long lowId = repository.insertFrontierIfAbsent("https://example.com/metrics-low", siteId, 0.1).pageId();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = now() - interval '30 seconds' WHERE id = ?")) {
            ps.setLong(1, lowId);
            ps.executeUpdate();
        }
        assertTrue(store.claimNextEligibleFrontier("w1", Duration.ofSeconds(60), 1).isPresent());
        assertEquals(1L, metrics.overdueFrontierRowCount());
        assertTrue(metrics.delayedQueueAgeMillis() > 0L);
        assertTrue(metrics.oldestOverdueRetryMillis() > 0L);
    }

    @Test
    void ingestDiscoveredUrls_recordsRejectionsAndAcceptsValid() {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long fromPage = repository.insertFrontierIfAbsent("https://example.com/from", siteId, 0.5).pageId();
        String longUrl = "https://example.com/" + "x".repeat(3000);
        IngestResult r =
                repository.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl("https://example.com/good", siteId, fromPage, "", "", 0.1),
                                new DiscoveredUrl(null, siteId, fromPage, "", "", 0.1),
                                new DiscoveredUrl(longUrl, siteId, fromPage, "", "", 0.1)));
        assertEquals(1, r.acceptedPageIds().size());
        assertEquals(2, r.rejections().size());
        assertEquals("INVALID_URL", r.rejections().get(0).reasonCode());
        assertEquals("URL_TOO_LONG", r.rejections().get(1).reasonCode());
    }

    @Test
    void persistFetchOutcomeWithLinks_mixedIngestAcceptsAndRejects() {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/mix", siteId, 0.9).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        String longUrl = "https://example.com/" + "y".repeat(3000);
        ParseResult parsed =
                ParseResult.linksOnly(
                        List.of(
                                new DiscoveredUrl("https://example.com/accepted", siteId, pageId, "", "", 0.2),
                                new DiscoveredUrl(null, siteId, pageId, "", "", 0.0),
                                new DiscoveredUrl(longUrl, siteId, pageId, "", "", 0.0)));
        PersistOutcome outcome =
                repository.persistFetchOutcomeWithLinks(
                        new FetchContext(pageId, "https://example.com/mix", siteId, 0, Instant.now()),
                        new FetchResult(200, "text/html", "<html>x</html>", Instant.now()),
                        parsed,
                        List.of());
        assertEquals(PageOutcomeType.HTML, outcome.outcomeType());
        assertEquals(1, outcome.ingestResult().acceptedPageIds().size());
        assertEquals(2, outcome.ingestResult().rejections().size());
    }

    @Test
    void claimNextEligibleFrontier_concurrentWorkersClaimDifferentRows() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        repository.insertFrontierIfAbsent("https://example.com/ca", siteId, 0.7);
        repository.insertFrontierIfAbsent("https://example.com/cb", siteId, 0.6);
        repository.insertFrontierIfAbsent("https://example.com/cc", siteId, 0.5);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Optional<FrontierRow>> r1 = new AtomicReference<>(Optional.empty());
        AtomicReference<Optional<FrontierRow>> r2 = new AtomicReference<>(Optional.empty());
        Thread t1 =
                new Thread(
                        () -> {
                            try {
                                start.await();
                                r1.set(repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60)));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                        });
        Thread t2 =
                new Thread(
                        () -> {
                            try {
                                start.await();
                                r2.set(repository.claimNextEligibleFrontier("w2", Duration.ofSeconds(60)));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                        });
        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();
        assertTrue(r1.get().isPresent());
        assertTrue(r2.get().isPresent());
        assertNotEquals(r1.get().orElseThrow().pageId(), r2.get().orElseThrow().pageId());
    }

    @Test
    void claimNextEligibleFrontier_prefersHigherRelevanceScore() {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        repository.insertFrontierIfAbsent("https://example.com/low", siteId, 0.3);
        repository.insertFrontierIfAbsent("https://example.com/high", siteId, 0.9);
        repository.insertFrontierIfAbsent("https://example.com/mid", siteId, 0.5);

        Optional<FrontierRow> claimed = repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));

        assertTrue(claimed.isPresent());
        assertEquals("https://example.com/high", claimed.get().url());
    }

    @Test
    void claimNextEligibleFrontierForDomain_ignoresOtherDomains() {
        long ex = repository.ensureSite("example.com").orElseThrow();
        long other = repository.ensureSite("other.org").orElseThrow();
        repository.insertFrontierIfAbsent("https://example.com/a", ex, 0.3);
        repository.insertFrontierIfAbsent("https://other.org/b", other, 0.99);
        Optional<FrontierRow> claimed =
                repository.claimNextEligibleFrontierForDomain("w1", Duration.ofSeconds(60), "example.com");
        assertTrue(claimed.isPresent());
        assertEquals("https://example.com/a", claimed.get().url());
    }

    @Test
    void claimNextEligibleFrontierForDomain_prefersHigherScoreWithinDomain() {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        repository.insertFrontierIfAbsent("https://example.com/low", siteId, 0.2);
        repository.insertFrontierIfAbsent("https://example.com/high", siteId, 0.9);
        Optional<FrontierRow> claimed =
                repository.claimNextEligibleFrontierForDomain("w1", Duration.ofSeconds(60), "example.com");
        assertTrue(claimed.isPresent());
        assertEquals("https://example.com/high", claimed.get().url());
    }

    @Test
    void listDistinctFrontierCrawlDomains_returnsAllSitesWithFrontier() {
        long ex = repository.ensureSite("example.com").orElseThrow();
        long other = repository.ensureSite("other.org").orElseThrow();
        repository.insertFrontierIfAbsent("https://example.com/z", ex, 0.5);
        repository.insertFrontierIfAbsent("https://other.org/z", other, 0.5);
        List<String> domains = repository.listDistinctFrontierCrawlDomains();
        assertTrue(domains.contains("example.com"));
        assertTrue(domains.contains("other.org"));
    }

    @Test
    void minNextAttemptAtForFrontierDomain_emptyWhenNoMatchingFrontier() {
        repository.ensureSite("ghost.example").orElseThrow();
        assertTrue(repository.minNextAttemptAtForFrontierDomain("ghost.example").isEmpty());
    }

    @Test
    void minNextAttemptAtForFrontierDomain_returnsEarliestAmongFrontierRows() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long laterId = repository.insertFrontierIfAbsent("https://example.com/later-min", siteId, 0.5).pageId();
        long soonerId = repository.insertFrontierIfAbsent("https://example.com/sooner-min", siteId, 0.5).pageId();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = now() + interval '2 hours' WHERE id = ?")) {
            ps.setLong(1, laterId);
            ps.executeUpdate();
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = now() + interval '30 seconds' WHERE id = ?")) {
            ps.setLong(1, soonerId);
            ps.executeUpdate();
        }
        Optional<Instant> min = repository.minNextAttemptAtForFrontierDomain("example.com");
        assertTrue(min.isPresent());
        assertTrue(min.get().isAfter(Instant.now().minusSeconds(5)));
        assertTrue(min.get().isBefore(Instant.now().plus(Duration.ofMinutes(10))));
    }

    @Test
    void insertFrontierIfAbsent_invokesFrontierWakeNotifier() {
        List<String> wakes = new ArrayList<>();
        PageRepository repo =
                new PageRepository(
                        dataSource, 3, Duration.ofMillis(10), 5, null, null, null, null, wakes::add);
        long siteId = repo.ensureSite("example.com").orElseThrow();
        repo.insertFrontierIfAbsent("https://example.com/wake", siteId, 0.5);
        assertEquals(1, wakes.size());
        assertEquals("example.com", wakes.get(0));
    }

    @Test
    void claimNextEligibleFrontier_skipsNotYetDueRow() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long delayedId = repository.insertFrontierIfAbsent("https://example.com/delayed", siteId, 0.99).pageId();
        long readyId = repository.insertFrontierIfAbsent("https://example.com/ready", siteId, 0.1).pageId();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = now() + interval '2 hours' WHERE id = ?")) {
            ps.setLong(1, delayedId);
            ps.executeUpdate();
        }

        Optional<FrontierRow> claimed = repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));

        assertTrue(claimed.isPresent());
        assertEquals(readyId, claimed.get().pageId());
    }

    @Test
    void claimNextEligibleFrontier_tieBreaksOnNextAttemptAtWhenScoresEqual() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long earlierDueId =
                repository.insertFrontierIfAbsent("https://example.com/due-soon", siteId, 0.5).pageId();
        long laterDueId =
                repository.insertFrontierIfAbsent("https://example.com/due-later", siteId, 0.5).pageId();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = now() + interval '3 hours' WHERE id = ?")) {
            ps.setLong(1, laterDueId);
            ps.executeUpdate();
        }

        Optional<FrontierRow> claimed = repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));

        assertTrue(claimed.isPresent());
        assertEquals(earlierDueId, claimed.get().pageId());
    }

    @Test
    void claimNextEligibleFrontier_tieBreaksOnAccessedTimeWhenScoreAndDueEqual() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long olderAccessId =
                repository.insertFrontierIfAbsent("https://example.com/older-access", siteId, 0.5).pageId();
        long newerAccessId =
                repository.insertFrontierIfAbsent("https://example.com/newer-access", siteId, 0.5).pageId();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = now(), accessed_time = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
            ps.setLong(2, olderAccessId);
            ps.executeUpdate();
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = now(), accessed_time = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.parse("2024-01-01T00:00:00Z")));
            ps.setLong(2, newerAccessId);
            ps.executeUpdate();
        }

        Optional<FrontierRow> claimed = repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));

        assertTrue(claimed.isPresent());
        assertEquals(olderAccessId, claimed.get().pageId());
    }

    @Test
    void claimNextEligibleFrontier_tieBreaksOnIdWhenScoreDueAndAccessedEqual() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long firstId = repository.insertFrontierIfAbsent("https://example.com/tie-a", siteId, 0.5).pageId();
        long secondId = repository.insertFrontierIfAbsent("https://example.com/tie-b", siteId, 0.5).pageId();
        Instant sameDue = Instant.parse("2020-06-01T12:00:00Z");
        Instant sameAccess = Instant.parse("2019-01-01T00:00:00Z");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = ?, accessed_time = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(sameDue));
            ps.setTimestamp(2, Timestamp.from(sameAccess));
            ps.setLong(3, firstId);
            ps.executeUpdate();
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET next_attempt_at = ?, accessed_time = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(sameDue));
            ps.setTimestamp(2, Timestamp.from(sameAccess));
            ps.setLong(3, secondId);
            ps.executeUpdate();
        }

        assertTrue(firstId < secondId);
        Optional<FrontierRow> claimed = repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(60));
        assertTrue(claimed.isPresent());
        assertEquals(firstId, claimed.get().pageId());
    }

    @Test
    void frontierStore_preClaimRecovery_reclaimsStaleLeaseBeforeClaim() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long staleId = repository.insertFrontierIfAbsent("https://example.com/stalepc", siteId, 0.8).pageId();
        repository.claimNextEligibleFrontier("w1", Duration.ofSeconds(1));
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET claim_expires_at = now() - interval '1 minute' WHERE id = ?")) {
            ps.setLong(1, staleId);
            ps.executeUpdate();
        }
        repository.insertFrontierIfAbsent("https://example.com/freshpc", siteId, 0.1);

        FrontierStore store = new FrontierStore(repository);
        Optional<FrontierRow> claimed = store.claimNextEligibleFrontier("w2", Duration.ofSeconds(60), 10);

        assertTrue(claimed.isPresent());
        // After recovery the former stale row returns as FRONTIER with higher score than "freshpc".
        assertEquals(staleId, claimed.get().pageId());
    }

    @Test
    void frontierStore_preClaimRecovery_respectsBatchCap() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        for (int i = 0; i < 3; i++) {
            long pid = repository.insertFrontierIfAbsent("https://example.com/cap" + i, siteId, 0.5).pageId();
            repository.claimNextEligibleFrontier("w", Duration.ofSeconds(1));
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE crawldb.page SET claim_expires_at = now() - interval '1 minute' WHERE id = ?")) {
                ps.setLong(1, pid);
                ps.executeUpdate();
            }
        }

        FrontierStore store = new FrontierStore(repository);
        store.claimNextEligibleFrontier("w2", Duration.ofSeconds(60), 1);

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT COUNT(*) FROM crawldb.page WHERE page_type_code = 'PROCESSING' AND"
                                        + " claim_expires_at < now()")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void ck_page_processing_lease_rejectsIncompleteLease() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/ck", siteId, 0.5).pageId();
        assertProcessingLeaseCheckRejected(
                pageId,
                "UPDATE crawldb.page SET page_type_code = 'PROCESSING', claimed_by = 'x',"
                        + " claimed_at = now(), claim_expires_at = NULL WHERE id = ?");
    }

    @Test
    void ck_page_processing_lease_rejectsNullClaimedBy() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/ck-nb", siteId, 0.5).pageId();
        // TS-16 / TS-11: PROCESSING requires a full lease triple; NULL claimed_by must violate ck_page_processing_lease.
        assertProcessingLeaseCheckRejected(
                pageId,
                "UPDATE crawldb.page SET page_type_code = 'PROCESSING', claimed_by = NULL,"
                        + " claimed_at = now(), claim_expires_at = now() + interval '1 hour' WHERE id = ?");
    }

    @Test
    void ck_page_processing_lease_rejectsNullClaimedAt() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        long pageId = repository.insertFrontierIfAbsent("https://example.com/ck-na", siteId, 0.5).pageId();
        assertProcessingLeaseCheckRejected(
                pageId,
                "UPDATE crawldb.page SET page_type_code = 'PROCESSING', claimed_by = 'worker',"
                        + " claimed_at = NULL, claim_expires_at = now() + interval '1 hour' WHERE id = ?");
    }

    private void assertProcessingLeaseCheckRejected(long pageId, String updateSql) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(updateSql)) {
            ps.setLong(1, pageId);
            SQLException ex = assertThrows(SQLException.class, ps::executeUpdate);
            assertTrue(
                    ex.getMessage().contains("ck_page_processing_lease")
                            || ex.getMessage().toLowerCase(Locale.ROOT).contains("check constraint"),
                    ex.getMessage());
        }
    }

    @Test
    void claimService_runStartupLeaseRecovery_drainsStaleLeases() throws Exception {
        long siteId = repository.ensureSite("example.com").orElseThrow();
        for (int i = 0; i < 5; i++) {
            long pid = repository.insertFrontierIfAbsent("https://example.com/su" + i, siteId, 0.5).pageId();
            repository.claimNextEligibleFrontier("w", Duration.ofSeconds(1));
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE crawldb.page SET claim_expires_at = now() - interval '1 minute' WHERE id = ?")) {
                ps.setLong(1, pid);
                ps.executeUpdate();
            }
        }

        FrontierStore store = new FrontierStore(repository);
        ClaimService.runStartupLeaseRecovery(store, 2, "test startup recovery", "integration-test");

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT COUNT(*) FROM crawldb.page WHERE page_type_code = 'PROCESSING' AND"
                                        + " claim_expires_at < now()")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void queryHeartbeatQueueSnapshot_countsFrontierProcessingAndTerminal() throws Exception {
        long siteId = repository.ensureSite("heartbeat.example.com").orElseThrow();
        repository.insertFrontierIfAbsent("https://heartbeat.example.com/a", siteId, 0.5);
        repository.insertFrontierIfAbsent("https://heartbeat.example.com/b", siteId, 0.5);

        PageRepository.HeartbeatQueueSnapshot s0 = repository.queryHeartbeatQueueSnapshot();
        assertEquals(2L, s0.frontierDepth());
        assertEquals(0L, s0.processingCount());
        assertEquals(0L, s0.pagesTerminalTotal());

        repository.claimNextEligibleFrontier("w-hb", Duration.ofSeconds(60));
        PageRepository.HeartbeatQueueSnapshot s1 = repository.queryHeartbeatQueueSnapshot();
        assertEquals(1L, s1.frontierDepth());
        assertEquals(1L, s1.processingCount());
        assertTrue(s1.oldestLeaseAgeMs() >= 0L);

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET page_type_code = 'HTML' WHERE url = ?")) {
            ps.setString(1, "https://heartbeat.example.com/b");
            ps.executeUpdate();
        }
        PageRepository.HeartbeatQueueSnapshot s2 = repository.queryHeartbeatQueueSnapshot();
        assertEquals(0L, s2.frontierDepth());
        assertEquals(1L, s2.processingCount());
        assertEquals(1L, s2.pagesTerminalTotal());
    }

    @Test
    void queryRunSummaryPageTypeSnapshot_reflectsFrontierRows() throws Exception {
        long siteId = repository.ensureSite("runsummary.example.com").orElseThrow();
        repository.insertFrontierIfAbsent("https://runsummary.example.com/a", siteId, 0.5);
        PageRepository.RunSummaryPageTypeSnapshot snap = repository.queryRunSummaryPageTypeSnapshot();
        assertTrue(snap.totalUrls() >= 1L);
        assertTrue(snap.frontierCount() >= 1L);
        assertNotNull(repository.queryErrorCountsByCategory());
        assertNotNull(repository.queryTopDomainsByTerminalPageCount(3));
    }

    @Test
    void runSummaryReporter_logsCrawlerRunSummaryEvent() throws Exception {
        long siteId = repository.ensureSite("rsevent.example.com").orElseThrow();
        repository.insertFrontierIfAbsent("https://rsevent.example.com/p", siteId, 0.5);
        Logger lg = (Logger) LoggerFactory.getLogger(RunSummaryReporter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        lg.addAppender(appender);
        try {
            CrawlerMetrics metrics = new CrawlerMetrics();
            metrics.recordBudgetDropped();
            SeedBootstrapStats seeds = new SeedBootstrapStats(2, 1, 0, false);
            RunSummaryReporter.emitRunSummary(repository, metrics, seeds);
            assertTrue(
                    appender.list.stream()
                            .anyMatch(
                                    e ->
                                            e.getFormattedMessage() != null
                                                    && e.getFormattedMessage().contains("CRAWLER_RUN_SUMMARY")));
        } finally {
            lg.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void ingestDiscoveredUrls_scoreEviction_atMaxFrontierRows_replacesLowestScoringFrontier() throws Exception {
        // TS-13: crawler.budget.maxFrontierRows must be >= 100 at validate() time.
        final int frontierCap = 100;
        RuntimeConfig cfg = budgetRuntimeConfig(5000, frontierCap);
        PageRepository repo = policyRepository(cfg);
        long siteId = repo.ensureSite("example.com").orElseThrow();
        long fromPage = repo.insertFrontierIfAbsent("https://example.com/parent-fe", siteId, 0.5).pageId();
        repo.insertFrontierIfAbsent("https://example.com/fe-low", siteId, 0.1);
        repo.insertFrontierIfAbsent("https://example.com/fe-mid", siteId, 0.2);
        for (int i = 0; i < frontierCap - 3; i++) {
            repo.insertFrontierIfAbsent("https://example.com/fe-fill-" + i, siteId, 0.4);
        }
        assertEquals((long) frontierCap, countFrontierRows());

        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl(
                                        "https://example.com/fe-better", siteId, fromPage, "", "", 0.99)));

        assertEquals(1, r.acceptedPageIds().size());
        assertTrue(r.rejections().isEmpty());
        assertEquals((long) frontierCap, countFrontierRows());
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT COUNT(*) FROM crawldb.page WHERE url = ?")) {
            ps.setString(1, "https://example.com/fe-low");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
            ps.setString(1, "https://example.com/fe-better");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void ingestDiscoveredUrls_scoreEviction_atMaxTotalPages_replacesWorstFrontier() throws Exception {
        RuntimeConfig cfg = budgetRuntimeConfig(3, 20_000);
        PageRepository repo = policyRepository(cfg);
        long siteId = repo.ensureSite("example.com").orElseThrow();
        long h1 = repo.insertFrontierIfAbsent("https://example.com/tp-html-1", siteId, 0.5).pageId();
        long h2 = repo.insertFrontierIfAbsent("https://example.com/tp-html-2", siteId, 0.5).pageId();
        long h3 = repo.insertFrontierIfAbsent("https://example.com/tp-html-3", siteId, 0.5).pageId();
        promoteToHtmlTerminal(h1);
        promoteToHtmlTerminal(h2);
        promoteToHtmlTerminal(h3);
        long fromPage = repo.insertFrontierIfAbsent("https://example.com/tp-parent", siteId, 0.5).pageId();
        repo.insertFrontierIfAbsent("https://example.com/tp-a", siteId, 0.1);
        repo.insertFrontierIfAbsent("https://example.com/tp-b", siteId, 0.2);
        assertEquals(3L, countHtmlPages());
        assertEquals(6L, countAllPages());
        assertEquals(3L, countFrontierRows());

        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl("https://example.com/tp-new", siteId, fromPage, "", "", 0.95)));

        assertEquals(1, r.acceptedPageIds().size());
        assertTrue(r.rejections().isEmpty());
        assertEquals(3L, countHtmlPages());
        assertEquals(6L, countAllPages());
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT COUNT(*) FROM crawldb.page WHERE url = ?")) {
            ps.setString(1, "https://example.com/tp-a");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void ingestDiscoveredUrls_nonHtmlTerminals_doNotCountTowardMaxTotalPages() throws Exception {
        RuntimeConfig cfg = budgetRuntimeConfig(3, 20_000);
        PageRepository repo = policyRepository(cfg);
        long siteId = repo.ensureSite("example.com").orElseThrow();
        for (int i = 0; i < 5; i++) {
            long id =
                    repo.insertFrontierIfAbsent("https://example.com/err-only-" + i, siteId, 0.5)
                            .pageId();
            promoteToErrorTerminal(id);
        }
        long fromPage = repo.insertFrontierIfAbsent("https://example.com/err-parent", siteId, 0.3).pageId();
        assertEquals(0L, countHtmlPages());
        assertEquals(6L, countAllPages());

        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl(
                                        "https://example.com/err-new-high", siteId, fromPage, "", "", 0.99)));

        assertEquals(1, r.acceptedPageIds().size());
        assertTrue(r.rejections().isEmpty());
        assertEquals(7L, countAllPages());
        assertEquals(2L, countFrontierRows());
    }

    @Test
    void ingestDiscoveredUrls_atHtmlCap_nonHtmlClutter_stillEvictsFrontierForBetterDiscovery() throws Exception {
        RuntimeConfig cfg = budgetRuntimeConfig(1, 20_000);
        PageRepository repo = policyRepository(cfg);
        long siteId = repo.ensureSite("example.com").orElseThrow();
        long htmlId = repo.insertFrontierIfAbsent("https://example.com/mix-html-1", siteId, 0.5).pageId();
        promoteToHtmlTerminal(htmlId);
        for (int i = 0; i < 3; i++) {
            long id =
                    repo.insertFrontierIfAbsent("https://example.com/mix-err-" + i, siteId, 0.5).pageId();
            promoteToErrorTerminal(id);
        }
        long fromPage = repo.insertFrontierIfAbsent("https://example.com/mix-parent", siteId, 0.5).pageId();
        repo.insertFrontierIfAbsent("https://example.com/mix-fe-low", siteId, 0.05);
        assertEquals(1L, countHtmlPages());
        assertEquals(6L, countAllPages());
        assertEquals(2L, countFrontierRows());

        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl(
                                        "https://example.com/mix-new-high", siteId, fromPage, "", "", 0.95)));

        assertEquals(1, r.acceptedPageIds().size());
        assertTrue(r.rejections().isEmpty());
        assertEquals(1L, countHtmlPages());
        assertEquals(6L, countAllPages());
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT COUNT(*) FROM crawldb.page WHERE url = ?")) {
            ps.setString(1, "https://example.com/mix-fe-low");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void ingestDiscoveredUrls_budgetDropped_whenNoFrontierVictimAtTotalCap() throws Exception {
        RuntimeConfig cfg = budgetRuntimeConfig(2, 20_000);
        PageRepository repo = policyRepository(cfg);
        long siteId = repo.ensureSite("example.com").orElseThrow();
        long id1 = repo.insertFrontierIfAbsent("https://example.com/cap-html-1", siteId, 0.5).pageId();
        long id2 = repo.insertFrontierIfAbsent("https://example.com/cap-html-2", siteId, 0.5).pageId();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET page_type_code = 'HTML', html_content = '',"
                                        + " http_status_code = 200, accessed_time = now() WHERE id = ?")) {
            ps.setLong(1, id1);
            ps.executeUpdate();
            ps.setLong(1, id2);
            ps.executeUpdate();
        }
        assertEquals(0L, countFrontierRows());
        assertEquals(2L, countAllPages());

        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl(
                                        "https://example.com/cap-new", siteId, id1, "", "", 0.99)));

        assertTrue(r.acceptedPageIds().isEmpty());
        assertEquals(1, r.rejections().size());
        assertEquals("BUDGET_DROPPED", r.rejections().getFirst().reasonCode());
    }

    @Test
    void ingestDiscoveredUrls_githubTopicsBlocked_whenDiscoveryBlockEnabled() throws Exception {
        RuntimeConfig cfg = budgetRuntimeConfig(5000, 20_000, true);
        PageRepository repo = policyRepository(cfg);
        long gh = repo.ensureSite("github.com").orElseThrow();
        long fromPage = repo.insertFrontierIfAbsent("https://github.com/org/seed", gh, 0.5).pageId();
        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl("https://github.com/topics/foo", gh, fromPage, "t", "c", 0.9),
                                new DiscoveredUrl("https://github.com/org/repo", gh, fromPage, "r", "c", 0.8)));
        assertEquals(1, r.acceptedPageIds().size());
        assertEquals(1, r.rejections().size());
        assertEquals("GITHUB_TOPICS_PATH_BLOCKED", r.rejections().getFirst().reasonCode());
    }

    @Test
    void ingestDiscoveredUrls_wwwGithubTopicsBlocked_nonTopicsAccepted_whenDiscoveryBlockEnabled() throws Exception {
        RuntimeConfig cfg = budgetRuntimeConfig(5000, 20_000, true);
        PageRepository repo = policyRepository(cfg);
        long gh = repo.ensureSite("github.com").orElseThrow();
        long fromPage = repo.insertFrontierIfAbsent("https://github.com/org/seed-www-ingest", gh, 0.5).pageId();
        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl("https://www.github.com/topics/from-www", gh, fromPage, "t", "c", 0.9),
                                new DiscoveredUrl("https://www.github.com/org/from-www", gh, fromPage, "r", "c", 0.8)));
        assertEquals(1, r.acceptedPageIds().size());
        assertEquals(1, r.rejections().size());
        assertEquals("GITHUB_TOPICS_PATH_BLOCKED", r.rejections().getFirst().reasonCode());
    }

    @Test
    void ingestDiscoveredUrls_githubTopicsAllowed_whenDiscoveryBlockDisabled() throws Exception {
        RuntimeConfig cfg = budgetRuntimeConfig(5000, 20_000, false);
        PageRepository repo = policyRepository(cfg);
        long gh = repo.ensureSite("github.com").orElseThrow();
        long fromPage = repo.insertFrontierIfAbsent("https://github.com/org/seed2", gh, 0.5).pageId();
        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl("https://github.com/topics/bar", gh, fromPage, "t", "c", 0.9),
                                new DiscoveredUrl("https://github.com/org/other", gh, fromPage, "r", "c", 0.8)));
        assertEquals(2, r.acceptedPageIds().size());
        assertTrue(r.rejections().isEmpty());
    }

    @Test
    void ingestDiscoveredUrls_githubRepoSubpathsBlocked_blobAndIssues_treeAndRootAccepted() throws Exception {
        RuntimeConfig cfg = budgetRuntimeConfig(5000, 20_000, false);
        PageRepository repo = policyRepository(cfg);
        long gh = repo.ensureSite("github.com").orElseThrow();
        long fromPage = repo.insertFrontierIfAbsent("https://github.com/org/seed-subpath", gh, 0.5).pageId();
        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl(
                                        "https://github.com/org/repo/blob/main/README.md", gh, fromPage, "b", "c", 0.9),
                                new DiscoveredUrl("https://github.com/org/repo/issues", gh, fromPage, "i", "c", 0.85),
                                new DiscoveredUrl("https://github.com/org/repo/tree/main", gh, fromPage, "t", "c", 0.8),
                                new DiscoveredUrl("https://github.com/org/repo", gh, fromPage, "r", "c", 0.75)));
        assertEquals(2, r.acceptedPageIds().size());
        assertEquals(2, r.rejections().size());
        assertEquals(
                "GITHUB_REPO_SUBPATH_BLOCKED", r.rejections().get(0).reasonCode());
        assertEquals(
                "GITHUB_REPO_SUBPATH_BLOCKED", r.rejections().get(1).reasonCode());
    }

    @Test
    void ingestDiscoveredUrls_frontierFullLowScore_whenAtFrontierCapAndNotBetter() throws Exception {
        final int frontierCap = 100;
        RuntimeConfig cfg = budgetRuntimeConfig(5000, frontierCap);
        PageRepository repo = policyRepository(cfg);
        long siteId = repo.ensureSite("example.com").orElseThrow();
        long fromPage = repo.insertFrontierIfAbsent("https://example.com/ff-parent", siteId, 0.9).pageId();
        repo.insertFrontierIfAbsent("https://example.com/ff-strong", siteId, 0.85);
        for (int i = 0; i < frontierCap - 2; i++) {
            repo.insertFrontierIfAbsent("https://example.com/ff-fill-" + i, siteId, 0.91);
        }
        assertEquals((long) frontierCap, countFrontierRows());

        IngestResult r =
                repo.ingestDiscoveredUrls(
                        List.of(
                                new DiscoveredUrl(
                                        "https://example.com/ff-weak", siteId, fromPage, "", "", 0.05)));

        assertTrue(r.acceptedPageIds().isEmpty());
        assertEquals(1, r.rejections().size());
        assertEquals("FRONTIER_FULL_LOW_SCORE", r.rejections().getFirst().reasonCode());
    }

    private PageRepository policyRepository(RuntimeConfig cfg) {
        return new PageRepository(
                dataSource,
                3,
                Duration.ofMillis(10),
                5,
                new AllowAllPolitenessStub(),
                cfg,
                new EnqueueService(),
                null,
                null);
    }

    private static RuntimeConfig budgetRuntimeConfig(int maxTotalPages, int maxFrontierRows) throws Exception {
        return budgetRuntimeConfig(maxTotalPages, maxFrontierRows, false);
    }

    private static RuntimeConfig budgetRuntimeConfig(int maxTotalPages, int maxFrontierRows, boolean blockGithubTopics)
            throws Exception {
        Properties p = new Properties();
        Path kw = Paths.get(PageRepositoryIntegrationTest.class.getResource("/keywords-valid.json").toURI());
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "7");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        p.setProperty("crawler.budget.maxTotalPages", Integer.toString(maxTotalPages));
        p.setProperty("crawler.budget.maxFrontierRows", Integer.toString(maxFrontierRows));
        p.setProperty("crawler.discovery.blockGithubTopicsPaths", Boolean.toString(blockGithubTopics));
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        return cfg;
    }

    private long countFrontierRows() throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT COUNT(*) FROM crawldb.page WHERE page_type_code = 'FRONTIER'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countAllPages() throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM crawldb.page")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countHtmlPages() throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT COUNT(*) FROM crawldb.page WHERE page_type_code = 'HTML'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void promoteToHtmlTerminal(long pageId) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET page_type_code = 'HTML', html_content = '',"
                                        + " http_status_code = 200, accessed_time = now() WHERE id = ?")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
        }
    }

    private void promoteToErrorTerminal(long pageId) throws SQLException {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET page_type_code = 'ERROR', html_content = NULL,"
                                        + " http_status_code = 500, accessed_time = now() WHERE id = ?")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
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
