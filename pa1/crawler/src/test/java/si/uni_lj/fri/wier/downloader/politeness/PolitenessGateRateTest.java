package si.uni_lj.fri.wier.downloader.politeness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.RateLimitDecision;

/** Same package as {@link PolitenessGate} for package-private spacing checks if needed. */
class PolitenessGateRateTest {

    @Test
    void tryAcquire_secondImmediateCallIsDelayed_whenUsingFloorSpacing() throws Exception {
        Properties p = baseProps();
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        PolitenessGate g = new PolitenessGate(cfg);
        assertFalse(g.tryAcquire("example.com").isDelayed());
        assertTrue(g.tryAcquire("example.com").isDelayed());
    }

    @Test
    void recordHttpResponse_overloadDelaysTryAcquire() throws Exception {
        Properties p = baseProps();
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        PolitenessGate g = new PolitenessGate(cfg);
        g.recordHttpResponse("overload.example", 503);
        assertTrue(g.tryAcquire("overload.example").isDelayed());
    }

    @Test
    void recordHttpResponse_successClearsOverloadBackoff() throws Exception {
        Properties p = baseProps();
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        PolitenessGate g = new PolitenessGate(cfg);
        g.recordHttpResponse("recover.example", 503);
        assertTrue(g.tryAcquire("recover.example").isDelayed());
        g.recordHttpResponse("recover.example", 200);
        assertFalse(
                g.tryAcquire("recover.example").isDelayed(),
                "overload state cleared; first token after 200 should not be overload-delayed");
    }

    /**
     * TS-06: robots.txt fetch must consume one token from the same per-host bucket as content GETs. After a
     * successful load the gate invalidates the bucket to apply crawl-delay, so a naive post-hoc tryAcquire can
     * appear immediate; instead we hold the HTTP response open so the in-flight robots GET keeps the bucket
     * depleted and prove a concurrent tryAcquire for the same host is delayed.
     */
    @Test
    void ensureLoaded_robotsFetchUsesSameHostBucketAsContent_whileResponseInFlightTryAcquireDelayed()
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        byte[] body = "User-agent: *\nDisallow:\n".getBytes(StandardCharsets.UTF_8);
        java.util.concurrent.CountDownLatch handlerEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseResponse = new java.util.concurrent.CountDownLatch(1);
        server.createContext(
                "/robots.txt",
                ex -> {
                    handlerEntered.countDown();
                    try {
                        assertTrue(releaseResponse.await(30, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        ex.sendResponseHeaders(200, body.length);
                        ex.getResponseBody().write(body);
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    } finally {
                        ex.close();
                    }
                });
        server.start();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Properties p = baseProps();
            RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
            cfg.validate();
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofMillis(cfg.fetchConnectTimeoutMs()))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            PolitenessGate g = new PolitenessGate(cfg, client, "http://127.0.0.1:" + port);
            Future<?> load = pool.submit(() -> g.ensureLoaded("127.0.0.1"));
            assertTrue(handlerEntered.await(10, TimeUnit.SECONDS));
            RateLimitDecision second = g.tryAcquire("127.0.0.1");
            assertTrue(
                    second.isDelayed(),
                    "robots GET already consumed the per-host token; same bucket as content must delay next acquire");
            releaseResponse.countDown();
            load.get(30, TimeUnit.SECONDS);
        } finally {
            releaseResponse.countDown();
            pool.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void spacingMillis_respectsRobotsCrawlDelayAboveFloor() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        byte[] body =
                ("User-agent: fri-wier-IEPS-TOLPA\nCrawl-delay: 120\nDisallow:\n\n"
                                + "User-agent: *\nDisallow:\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        server.createContext(
                "/robots.txt",
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
            java.net.http.HttpClient client =
                    java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofMillis(cfg.fetchConnectTimeoutMs()))
                            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                            .build();
            PolitenessGate g = new PolitenessGate(cfg, client, "http://127.0.0.1:" + port);
            g.ensureLoaded("127.0.0.1");
            assertTrue(
                    g.spacingMillisForDomain("127.0.0.1") >= 120_000L,
                    "crawl-delay 120s should raise spacing above default 5s floor");
        } finally {
            server.stop(0);
        }
    }

    private static Properties baseProps() throws Exception {
        Properties p = new Properties();
        Path kw = Paths.get(PolitenessGateRateTest.class.getResource("/keywords-valid.json").toURI());
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "8");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        return p;
    }
}
