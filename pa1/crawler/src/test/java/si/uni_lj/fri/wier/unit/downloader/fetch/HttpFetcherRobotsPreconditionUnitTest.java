/*
 * TS-06 fetch path: HttpFetcher invokes ensureLoaded before evaluate on each hop.
 */

package si.uni_lj.fri.wier.unit.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.FetchRequest;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;
import si.uni_lj.fri.wier.contracts.RobotDecision;
import si.uni_lj.fri.wier.contracts.RobotsTxtCache;
import si.uni_lj.fri.wier.downloader.fetch.HttpFetcher;

class HttpFetcherRobotsPreconditionUnitTest {

    @Test
    void fetch_invokesEnsureLoadedBeforeEvaluate_eachHop() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        byte[] body = "<html><body>ok</body></html>".getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/p",
                ex -> {
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                });
        server.start();
        try {
            Properties p = baseProps();
            RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
            cfg.validate();
            CountingRobotsCache robots = new CountingRobotsCache();
            HttpFetcher fetcher = new HttpFetcher(cfg, robots, robots);
            fetcher.fetch(
                    new FetchRequest(
                            "http://127.0.0.1:" + port + "/p",
                            "w1",
                            Instant.now().plusSeconds(60),
                            true));
            assertTrue(robots.ensureLoadedCount >= 1);
            assertTrue(robots.evaluateCount >= 1);
            assertTrue(robots.ensureLoadedCount <= robots.evaluateCount + 1);
        } finally {
            server.stop(0);
        }
    }

    private static Properties baseProps() throws Exception {
        Properties p = new Properties();
        Path kw =
                Paths.get(HttpFetcherRobotsPreconditionUnitTest.class.getResource("/keywords-valid.json").toURI());
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "5");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        return p;
    }

    private static final class CountingRobotsCache implements RobotsTxtCache, si.uni_lj.fri.wier.contracts.RateLimiterRegistry {
        private static final BaseRobotRules ALLOW =
                new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL);

        private final AtomicInteger ensureCounter = new AtomicInteger();
        private final AtomicInteger evalCounter = new AtomicInteger();
        int ensureLoadedCount;
        int evaluateCount;

        @Override
        public void ensureLoaded(String domain) {
            ensureLoadedCount = ensureCounter.incrementAndGet();
        }

        @Override
        public RobotDecision evaluate(String canonicalUrl) {
            evaluateCount = evalCounter.incrementAndGet();
            // ensureLoaded must have been called at least once before first evaluate in production order.
            assertTrue(
                    ensureLoadedCount >= 1,
                    "HttpFetcher must call ensureLoaded before evaluate (TS-06 precondition)");
            return RobotDecision.allowed();
        }

        @Override
        public BaseRobotRules getRulesForDomain(String domain) {
            return ALLOW;
        }

        @Override
        public RateLimitDecision tryAcquire(String domain) {
            return RateLimitDecision.allowed();
        }
    }
}
