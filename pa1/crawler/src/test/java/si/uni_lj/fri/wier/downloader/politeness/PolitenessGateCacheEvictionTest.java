/*
 * TS-06: simulated Caffeine eviction forces a fresh robots fetch (stakeholder: counters reset on eviction).
 */

package si.uni_lj.fri.wier.downloader.politeness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.RobotDecisionType;

class PolitenessGateCacheEvictionTest {

    @Test
    void invalidateCacheEntry_refetchesRobots() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger hits = new AtomicInteger();
        byte[] ok = "User-agent: *\nDisallow:\n".getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/robots.txt",
                ex -> {
                    hits.incrementAndGet();
                    ex.sendResponseHeaders(200, ok.length);
                    ex.getResponseBody().write(ok);
                    ex.close();
                });
        server.start();
        try {
            Properties p = baseProps();
            RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
            cfg.validate();
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofMillis(cfg.fetchConnectTimeoutMs()))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            PolitenessGate gate = new PolitenessGate(cfg, client, "http://127.0.0.1:" + port);
            gate.ensureLoaded("127.0.0.1");
            assertEquals(1, hits.get());
            gate.invalidateRobotsPolicyCacheEntry("127.0.0.1");
            gate.ensureLoaded("127.0.0.1");
            assertEquals(2, hits.get());
            assertEquals(RobotDecisionType.ALLOWED, gate.evaluate("http://127.0.0.1/a").type());
        } finally {
            server.stop(0);
        }
    }

    private static Properties baseProps() throws Exception {
        Properties p = new Properties();
        Path kw =
                Paths.get(PolitenessGateCacheEvictionTest.class.getResource("/keywords-valid.json").toURI())
                        .toAbsolutePath();
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "5");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        return p;
    }
}
