package si.uni_lj.fri.wier.unit.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.Frontier;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.contracts.Storage;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;
import si.uni_lj.fri.wier.error.FailureContext;
import si.uni_lj.fri.wier.error.ProcessingFailureHandler;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;

class ProcessingFailureHandlerUnitTest {

    @Test
    void handleProcessingFailure_reschedule_invokesFrontier() {
        AtomicReference<String> categorySeen = new AtomicReference<>();
        Frontier frontier =
                new Frontier() {
                    @Override
                    public Optional<FrontierRow> claimNextFrontier() {
                        return Optional.empty();
                    }

                    @Override
                    public void reschedule(
                            long pageId,
                            Instant nextAttemptAt,
                            String errorCategory,
                            String diagnosticMessage) {
                        categorySeen.set(errorCategory);
                    }
                };
        Storage storage =
                new Storage() {
                    @Override
                    public java.util.Optional<Long> ensureSite(String domain) {
                        return Optional.empty();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.PersistOutcome persistFetchOutcomeWithLinks(
                            si.uni_lj.fri.wier.contracts.FetchContext context,
                            si.uni_lj.fri.wier.contracts.FetchResult result,
                            si.uni_lj.fri.wier.contracts.ParseResult parsed,
                            java.util.Collection<si.uni_lj.fri.wier.contracts.DiscoveredUrl> discovered) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.LinkInsertResult insertLink(
                            long fromPageId, long toPageId) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.IngestResult ingestDiscoveredUrls(
                            java.util.Collection<si.uni_lj.fri.wier.contracts.DiscoveredUrl> discoveredUrls) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.InsertFrontierResult insertFrontierIfAbsent(
                            String canonicalUrl, long siteId, double relevanceScore) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.InsertFrontierResult insertFrontierIfAbsent(
                            String canonicalUrl, long siteId, double relevanceScore, Instant nextAttemptAt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void markPageAsError(long pageId, String category, String message) {
                        throw new AssertionError("should not terminal");
                    }
                };
        CrawlerMetrics metrics = new CrawlerMetrics();
        ProcessingFailureHandler handler =
                new ProcessingFailureHandler(
                        frontier,
                        storage,
                        config(),
                        Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC),
                        NOPLogger.NOP_LOGGER,
                        metrics);
        handler.handleProcessingFailure(
                new FailureContext(
                        42L,
                        "https://ex.test/p",
                        "ex.test",
                        0,
                        0,
                        CrawlerErrorCategory.FETCH_TIMEOUT,
                        "timeout",
                        null));
        assertEquals(CrawlerErrorCategory.FETCH_TIMEOUT.name(), categorySeen.get());
        assertTrue(metrics.retryScheduledCount() >= 1);
    }

    @Test
    void handleProcessingFailure_terminal_invokesStorage() {
        AtomicReference<String> cat = new AtomicReference<>();
        Frontier frontier =
                new Frontier() {
                    @Override
                    public Optional<FrontierRow> claimNextFrontier() {
                        return Optional.empty();
                    }

                    @Override
                    public void reschedule(
                            long pageId,
                            Instant nextAttemptAt,
                            String errorCategory,
                            String diagnosticMessage) {
                        throw new AssertionError("should not reschedule");
                    }
                };
        Storage storage =
                new Storage() {
                    @Override
                    public java.util.Optional<Long> ensureSite(String domain) {
                        return Optional.empty();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.PersistOutcome persistFetchOutcomeWithLinks(
                            si.uni_lj.fri.wier.contracts.FetchContext context,
                            si.uni_lj.fri.wier.contracts.FetchResult result,
                            si.uni_lj.fri.wier.contracts.ParseResult parsed,
                            java.util.Collection<si.uni_lj.fri.wier.contracts.DiscoveredUrl> discovered) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.LinkInsertResult insertLink(
                            long fromPageId, long toPageId) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.IngestResult ingestDiscoveredUrls(
                            java.util.Collection<si.uni_lj.fri.wier.contracts.DiscoveredUrl> discoveredUrls) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.InsertFrontierResult insertFrontierIfAbsent(
                            String canonicalUrl, long siteId, double relevanceScore) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public si.uni_lj.fri.wier.contracts.InsertFrontierResult insertFrontierIfAbsent(
                            String canonicalUrl, long siteId, double relevanceScore, Instant nextAttemptAt) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void markPageAsError(long pageId, String category, String message) {
                        cat.set(category);
                    }
                };
        CrawlerMetrics metrics = new CrawlerMetrics();
        ProcessingFailureHandler handler =
                new ProcessingFailureHandler(
                        frontier,
                        storage,
                        config(),
                        Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC),
                        NOPLogger.NOP_LOGGER,
                        metrics);
        handler.handleProcessingFailure(
                new FailureContext(
                        7L,
                        "https://ex.test/x",
                        "ex.test",
                        0,
                        0,
                        CrawlerErrorCategory.INVALID_URL,
                        "bad",
                        null));
        assertEquals(CrawlerErrorCategory.INVALID_URL.name(), cat.get());
        assertTrue(metrics.terminalFailureCount() >= 1);
    }

    private static RuntimeConfig config() {
        Properties p = new Properties();
        try {
            p.setProperty(
                    "crawler.scoring.keywordConfig",
                    Paths.get(ProcessingFailureHandlerUnitTest.class.getResource("/keywords-valid.json").toURI())
                            .toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        p.setProperty("crawler.retry.jitterMs", "0");
        return RuntimeConfig.fromProperties(p, 4);
    }
}
