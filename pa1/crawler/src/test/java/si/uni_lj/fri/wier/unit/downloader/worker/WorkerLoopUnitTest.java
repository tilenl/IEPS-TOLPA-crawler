package si.uni_lj.fri.wier.unit.downloader.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.FetchContext;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.Fetcher;
import si.uni_lj.fri.wier.contracts.Frontier;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.contracts.InsertFrontierResult;
import si.uni_lj.fri.wier.contracts.IngestResult;
import si.uni_lj.fri.wier.contracts.LinkInsertResult;
import si.uni_lj.fri.wier.contracts.PageOutcomeType;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.contracts.PersistOutcome;
import si.uni_lj.fri.wier.contracts.Storage;
import si.uni_lj.fri.wier.downloader.extract.HtmlParser;
import si.uni_lj.fri.wier.downloader.extract.KeywordRelevanceScorer;
import si.uni_lj.fri.wier.downloader.normalize.UrlCanonicalizer;
import si.uni_lj.fri.wier.downloader.worker.WorkerLoop;
import si.uni_lj.fri.wier.error.ProcessingFailureHandler;
import si.uni_lj.fri.wier.error.RecoveryPathExecutor;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;
import si.uni_lj.fri.wier.unit.downloader.fetch.AllowAllPolitenessStub;

/**
 * Lightweight orchestration check: one claimed HTML page triggers exactly one {@link Storage#persistFetchOutcomeWithLinks}.
 */
class WorkerLoopUnitTest {

    @Test
    void oneHtmlClaim_persistCalledOnce() throws Exception {
        Instant t0 = Instant.parse("2026-03-01T12:00:00Z");
        FrontierRow row =
                new FrontierRow(
                        7L,
                        "https://example.com/page",
                        3L,
                        0.5,
                        0,
                        0,
                        t0,
                        t0,
                        t0.plusSeconds(120));

        AtomicInteger claims = new AtomicInteger();
        Frontier frontier =
                new Frontier() {
                    @Override
                    public Optional<FrontierRow> claimNextFrontier() {
                        return claims.getAndIncrement() == 0 ? Optional.of(row) : Optional.empty();
                    }

                    @Override
                    public void reschedule(
                            long pageId,
                            Instant nextAttemptAt,
                            String errorCategory,
                            String diagnosticMessage) {
                        throw new AssertionError("unexpected reschedule");
                    }
                };

        AtomicInteger persistCount = new AtomicInteger();
        Storage storage =
                new Storage() {
                    @Override
                    public Optional<Long> ensureSite(String domain) {
                        return Optional.of(3L);
                    }

                    @Override
                    public PersistOutcome persistFetchOutcomeWithLinks(
                            FetchContext context,
                            FetchResult result,
                            ParseResult parsed,
                            Collection<si.uni_lj.fri.wier.contracts.DiscoveredUrl> discovered) {
                        persistCount.incrementAndGet();
                        return new PersistOutcome(
                                context.pageId(),
                                PageOutcomeType.HTML,
                                context.pageId(),
                                new IngestResult(java.util.List.of(), java.util.List.of()));
                    }

                    @Override
                    public LinkInsertResult insertLink(long fromPageId, long toPageId) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public IngestResult ingestDiscoveredUrls(
                            Collection<si.uni_lj.fri.wier.contracts.DiscoveredUrl> discoveredUrls) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public InsertFrontierResult insertFrontierIfAbsent(
                            String canonicalUrl, long siteId, double relevanceScore) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public InsertFrontierResult insertFrontierIfAbsent(
                            String canonicalUrl, long siteId, double relevanceScore, Instant nextAttemptAt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void markPageAsError(long pageId, String category, String message) {
                        throw new AssertionError("unexpected terminal");
                    }
                };

        Fetcher fetcher =
                request ->
                        new FetchResult(
                                200,
                                "text/html; charset=utf-8",
                                "<html><body><a href=\"https://other.example/x\">x</a></body></html>",
                                t0);

        Path kw = Paths.get(WorkerLoopUnitTest.class.getResource("/keywords-valid.json").toURI());
        HtmlParser parser =
                new HtmlParser(
                        new UrlCanonicalizer(),
                        new KeywordRelevanceScorer(kw),
                        domain -> 5L);

        RuntimeConfig cfg = testConfig();
        Clock clock = Clock.fixed(t0, ZoneOffset.UTC);
        ProcessingFailureHandler handler =
                new ProcessingFailureHandler(
                        frontier, storage, cfg, clock, org.slf4j.helpers.NOPLogger.NOP_LOGGER, new CrawlerMetrics());
        RecoveryPathExecutor recovery = new RecoveryPathExecutor(cfg, org.slf4j.helpers.NOPLogger.NOP_LOGGER);
        AtomicBoolean shutdown = new AtomicBoolean(false);

        WorkerLoop loop =
                new WorkerLoop(
                        "test-worker",
                        frontier,
                        new AllowAllPolitenessStub(),
                        fetcher,
                        parser,
                        storage,
                        handler,
                        recovery,
                        cfg,
                        clock,
                        shutdown);

        Thread vt = Thread.ofVirtual().start(loop::runLoop);
        Thread.sleep(300);
        shutdown.set(true);
        vt.join(10_000);

        assertEquals(1, persistCount.get());
    }

    private static RuntimeConfig testConfig() throws Exception {
        Properties p = new Properties();
        Path kw = Paths.get(WorkerLoopUnitTest.class.getResource("/keywords-valid.json").toURI());
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        p.setProperty("crawler.frontier.pollMs", "50");
        p.setProperty("crawler.retry.jitterMs", "0");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        return cfg;
    }
}
