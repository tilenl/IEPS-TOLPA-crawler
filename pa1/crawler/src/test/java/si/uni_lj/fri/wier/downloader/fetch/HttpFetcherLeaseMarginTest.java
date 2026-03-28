package si.uni_lj.fri.wier.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.FetchException;
import si.uni_lj.fri.wier.contracts.FetchRequest;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RateLimiterRegistry;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;

/**
 * TS-08: mid-chain politeness wait that cannot fit in remaining lease uses {@link
 * CrawlerErrorCategory#FETCH_TIMEOUT} so TS-12 reschedules with fetch-timeout policy.
 */
class HttpFetcherLeaseMarginTest {

    @Test
    void politenessWaitExceedingLeaseMargin_throwsFetchTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext(
                "/a",
                ex -> {
                    ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/b");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                });
        server.createContext(
                "/b",
                ex -> {
                    byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                });
        server.start();
        try {
            RuntimeConfig cfg = baseConfig();
            Clock clock = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofMillis(cfg.fetchConnectTimeoutMs()))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            HeadlessSessionPool pool = new HeadlessSessionPool(cfg, clock);
            DelaySecondHopLimiter politeness = new DelaySecondHopLimiter();
            HttpFetcher fetcher = new HttpFetcher(cfg, politeness, politeness, clock, client, pool);
            Instant claimEnd = Instant.parse("2026-03-01T12:00:05Z");
            FetchException ex =
                    assertThrows(
                            FetchException.class,
                            () ->
                                    fetcher.fetch(
                                            new FetchRequest(
                                                    "http://127.0.0.1:" + port + "/a",
                                                    "w1",
                                                    claimEnd,
                                                    false)));
            assertEquals(CrawlerErrorCategory.FETCH_TIMEOUT.name(), ex.category());
            assertTrue(ex.getMessage().contains("lease margin"));
        } finally {
            server.stop(0);
        }
    }

    /** First hop allowed; second hop always delayed ~60s so lease margin check fails. */
    private static final class DelaySecondHopLimiter implements RateLimiterRegistry, RobotsTxtCache {
        private static final BaseRobotRules ALLOW =
                new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL);

        private int acquireCalls;

        @Override
        public RateLimitDecision tryAcquire(String domain) {
            acquireCalls++;
            if (acquireCalls >= 2) {
                return RateLimitDecision.delayed(TimeUnit.MINUTES.toNanos(1));
            }
            return RateLimitDecision.allowed();
        }

        @Override
        public void ensureLoaded(String domain) {}

        @Override
        public RobotDecision evaluate(String canonicalUrl) {
            return RobotDecision.allowed();
        }

        @Override
        public BaseRobotRules getRulesForDomain(String domain) {
            return ALLOW;
        }
    }

    private static RuntimeConfig baseConfig() throws Exception {
        Properties p = new Properties();
        Path kw =
                Paths.get(HttpFetcherLeaseMarginTest.class.getResource("/keywords-valid.json").toURI())
                        .toAbsolutePath();
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        p.setProperty("crawler.frontier.leaseSeconds", "60");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        return cfg;
    }
}
