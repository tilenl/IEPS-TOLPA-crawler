/*
 * TS-16 / TS-17 item 13: PostgreSQL integration gate for concurrency and restart-sensitive queue semantics.
 *
 * <p>Validates single-row claim atomicity under thread contention, repeated parallel content-dedup stability
 * ({@link Ts09DedupAssertions}), concurrent stale-lease recovery idempotency, and terminal ERROR after retry
 * budget exhaustion using the real {@link si.uni_lj.fri.wier.error.ProcessingFailureHandler} stack.
 *
 * <p>Invoked by {@code ./gradlew test} from {@code pa1/}; requires Docker (Testcontainers). JDK 21 per project
 * toolchain. Created: 2026-03. Major revisions: initial TS-16 release gate suite; repeated dedup test uses
 * per-iteration HTML so content_hash batches do not collide within one {@code @BeforeEach} scope.
 */
package si.uni_lj.fri.wier.integration.storage.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.FetchContext;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.downloader.dedup.ContentHasherImpl;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;
import si.uni_lj.fri.wier.error.FailureContext;
import si.uni_lj.fri.wier.error.ProcessingFailureHandler;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.storage.frontier.ContractFrontier;
import si.uni_lj.fri.wier.storage.frontier.FrontierStore;
import si.uni_lj.fri.wier.storage.postgres.PostgresStorage;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

@Testcontainers(disabledWithoutDocker = true)
class Ts16ConcurrencyRestartGateIT {

    /** Threads racing for a single FRONTIER row (plan: CI-friendly default). */
    private static final int CLAIM_HAMMER_THREADS = 8;

    /** Repetitions of the parallel same-hash persist scenario (plan: CI-friendly default). */
    private static final int DEDUP_REPEAT_ITERATIONS = 5;

    private static final Duration LEASE = Duration.ofSeconds(60);

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
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "TRUNCATE TABLE crawldb.link, crawldb.image, crawldb.page_data, "
                                        + "crawldb.content_owner, crawldb.page, crawldb.site RESTART IDENTITY CASCADE")) {
            ps.executeUpdate();
        }
    }

    @Test
    void claimNextEligibleFrontier_singleEligibleRow_concurrentHammer_excludesDoubleLease() throws Exception {
        long siteId = repository.ensureSite("hammer.example.com").orElseThrow();
        String url = "https://hammer.example.com/only";
        repository.insertFrontierIfAbsent(url, siteId, 0.9);

        // All workers must hit claim in the same window so only one UPDATE ... RETURNING wins.
        CyclicBarrier barrier = new CyclicBarrier(CLAIM_HAMMER_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(CLAIM_HAMMER_THREADS);
        List<Optional<FrontierRow>> results = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < CLAIM_HAMMER_THREADS; i++) {
                final int workerIdx = i;
                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        barrier.await(60, TimeUnit.SECONDS);
                                    } catch (TimeoutException e) {
                                        throw new IllegalStateException("barrier wait timed out", e);
                                    } catch (BrokenBarrierException e) {
                                        throw new IllegalStateException("barrier broken", e);
                                    }
                                    results.add(
                                            repository.claimNextEligibleFrontier(
                                                    "hammer-w-" + workerIdx, LEASE));
                                    return null;
                                }));
            }
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }

        long winners = results.stream().filter(Optional::isPresent).count();
        assertEquals(1, winners, "exactly one worker may claim the sole FRONTIER row");
        FrontierRow won = results.stream().filter(Optional::isPresent).findFirst().orElseThrow().orElseThrow();
        assertEquals(url, won.url());

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT COUNT(*) FROM crawldb.page WHERE page_type_code = 'PROCESSING' AND url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT claimed_by FROM crawldb.page WHERE url = ? AND page_type_code = 'PROCESSING'")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String claimedBy = rs.getString(1);
                boolean matchesHammerWorker = false;
                for (int i = 0; i < CLAIM_HAMMER_THREADS; i++) {
                    if (("hammer-w-" + i).equals(claimedBy)) {
                        matchesHammerWorker = true;
                        break;
                    }
                }
                assertTrue(matchesHammerWorker, "claimed_by must be one of the hammer worker ids: " + claimedBy);
            }
        }
    }

    @Test
    void persistFetchOutcomeWithLinks_parallelSameHash_repeatedRuns_stableMinPageIdOwner() throws Exception {
        final int n = 4;
        ContentHasherImpl hasher = new ContentHasherImpl();
        PageRepository parallelRepo = new PageRepository(dataSource, 16, Duration.ofMillis(10), 40);
        long siteId = parallelRepo.ensureSite("example.com").orElseThrow();

        for (int rep = 0; rep < DEDUP_REPEAT_ITERATIONS; rep++) {
            final int repFinal = rep;
            // NOTE: @BeforeEach truncates once per test, not once per loop iteration. content_owner and page rows
            // are keyed globally by content_hash, so reusing identical HTML across reps would merge batches and
            // break TS-09 LEAST assertions. A rep-specific HTML comment keeps each iteration an isolated same-hash race.
            String htmlBody = "<html>ts16-repeat-stable<!--rep:" + repFinal + "--></html>";
            String expectedHash = hasher.sha256(htmlBody);
            String urlPrefix = "https://example.com/ts16rep/i" + repFinal + "/u";

            List<Long> insertedIds = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                insertedIds.add(parallelRepo.insertFrontierIfAbsent(urlPrefix + i, siteId, 0.5).pageId());
            }
            long expectedOwner = insertedIds.stream().mapToLong(Long::longValue).min().orElseThrow();

            List<Long> claimedIds = new ArrayList<>();
            List<String> claimedUrls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                FrontierRow row =
                        parallelRepo.claimNextEligibleFrontier("w-ts16-" + repFinal + "-" + i, Duration.ofSeconds(120))
                                .orElseThrow();
                claimedIds.add(row.pageId());
                claimedUrls.add(row.url());
            }
            assertEquals(
                    insertedIds.stream().sorted().toList(),
                    claimedIds.stream().sorted().toList(),
                    "rep=" + repFinal + " claimed set must match inserted ids");

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
                                            throw new IllegalStateException("barrier timed out rep=" + repFinal, e);
                                        } catch (BrokenBarrierException e) {
                                            throw new IllegalStateException("barrier broken rep=" + repFinal, e);
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
                    dataSource, claimedIds, expectedOwner, expectedHash, n);
        }
    }

    @Test
    void recoverExpiredLeases_concurrentBatches_onSingleStaleRow_updatesAtMostOnce() throws Exception {
        long siteId = repository.ensureSite("recover.example.com").orElseThrow();
        long pageId =
                repository.insertFrontierIfAbsent("https://recover.example.com/stale-one", siteId, 0.7).pageId();
        repository.claimNextEligibleFrontier("w-stale", Duration.ofSeconds(1));
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "UPDATE crawldb.page SET claim_expires_at = now() - interval '5 minutes' WHERE id = ?")) {
            ps.setLong(1, pageId);
            ps.executeUpdate();
        }

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> f1 =
                    executor.submit(
                            () -> {
                                try {
                                    barrier.await(60, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("interrupted at barrier", e);
                                } catch (TimeoutException e) {
                                    throw new IllegalStateException("barrier wait timed out", e);
                                } catch (BrokenBarrierException e) {
                                    throw new IllegalStateException("barrier broken", e);
                                }
                                return repository.recoverExpiredLeases(50, "concurrent-a", "thread-a");
                            });
            Future<Integer> f2 =
                    executor.submit(
                            () -> {
                                try {
                                    barrier.await(60, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("interrupted at barrier", e);
                                } catch (TimeoutException e) {
                                    throw new IllegalStateException("barrier wait timed out", e);
                                } catch (BrokenBarrierException e) {
                                    throw new IllegalStateException("barrier broken", e);
                                }
                                return repository.recoverExpiredLeases(50, "concurrent-b", "thread-b");
                            });
            int sumRecovered = f1.get(120, TimeUnit.SECONDS) + f2.get(120, TimeUnit.SECONDS);
            assertEquals(
                    1,
                    sumRecovered,
                    "FOR UPDATE SKIP LOCKED: exactly one recoverer transitions the single stale row");
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement(
                                "SELECT page_type_code, claimed_by, claim_expires_at FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("FRONTIER", rs.getString(1));
                assertTrue(rs.getString(2) == null && rs.wasNull());
                assertTrue(rs.getTimestamp(3) == null && rs.wasNull());
            }
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void processingFailureHandler_afterSimulatedRestart_terminalFetchTimeoutAtMaxAttempts_persistsError()
            throws Exception {
        long siteId = repository.ensureSite("ts16term.example.com").orElseThrow();
        String url = "https://ts16term.example.com/page";
        long pageId = repository.insertFrontierIfAbsent(url, siteId, 0.95).pageId();

        // Three fetch-stage reschedules take attempt_count to 3 while returning the row to FRONTIER each time.
        for (int cycle = 0; cycle < 3; cycle++) {
            repository.claimNextEligibleFrontier("w-cycle-" + cycle, LEASE);
            assertTrue(
                    repository.reschedulePage(
                            pageId,
                            Instant.now().plusSeconds(120),
                            "FETCH_TIMEOUT",
                            "ts16 cycle " + cycle,
                            false));
            try (Connection c = dataSource.getConnection();
                    PreparedStatement ps =
                            c.prepareStatement("UPDATE crawldb.page SET next_attempt_at = now() WHERE id = ?")) {
                ps.setLong(1, pageId);
                ps.executeUpdate();
            }
        }

        PageRepository repoAfterRestart = new PageRepository(dataSource, 3, Duration.ofMillis(10), 5);
        FrontierRow leased =
                repoAfterRestart.claimNextEligibleFrontier("w-after-restart", LEASE).orElseThrow();
        assertEquals(pageId, leased.pageId());
        assertEquals(3, leased.attemptCount(), "attempt_count must survive new repository / simulated restart");

        RuntimeConfig cfg = runtimeConfigForTs16();
        FrontierStore store = new FrontierStore(repoAfterRestart);
        ContractFrontier frontier = new ContractFrontier(store, "w-handler", LEASE, 10);
        PostgresStorage storage = new PostgresStorage(repoAfterRestart);
        ProcessingFailureHandler handler =
                new ProcessingFailureHandler(
                        frontier,
                        storage,
                        cfg,
                        Clock.systemUTC(),
                        NOPLogger.NOP_LOGGER,
                        new CrawlerMetrics());

        handler.handleProcessingFailure(
                new FailureContext(
                        pageId,
                        "w-handler",
                        url,
                        "ts16term.example.com",
                        3,
                        0,
                        CrawlerErrorCategory.FETCH_TIMEOUT,
                        "budget exhausted",
                        null));

        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT page_type_code FROM crawldb.page WHERE id = ?")) {
            ps.setLong(1, pageId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("ERROR", rs.getString(1));
            }
        }
    }

    private static RuntimeConfig runtimeConfigForTs16() {
        Properties p = new Properties();
        try {
            p.setProperty(
                    "crawler.scoring.keywordConfig",
                    Paths.get(Ts16ConcurrencyRestartGateIT.class.getResource("/keywords-valid.json").toURI())
                            .toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "5");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        p.setProperty("crawler.retry.jitterMs", "0");
        p.setProperty("crawler.retry.maxAttempts.fetchTimeout", "3");
        return RuntimeConfig.fromProperties(p, 4);
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
