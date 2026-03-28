package si.uni_lj.fri.wier.downloader.politeness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;

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
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        return p;
    }
}
