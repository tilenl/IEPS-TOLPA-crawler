/*
 * TS-06 HTTP outcome mapping: 4xx allow-all, 5xx temporary deny, recovery after successful refresh.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.unit.downloader.politeness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.RobotDecisionType;
import si.uni_lj.fri.wier.downloader.politeness.PolitenessGate;

class PolitenessGateHttpOutcomesUnitTest {

    @Test
    void robots404_treatedAsAllowAll() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext(
                "/robots.txt",
                ex -> {
                    ex.sendResponseHeaders(404, 0);
                    ex.close();
                });
        server.start();
        try {
            RuntimeConfig cfg = baseConfig();
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofMillis(cfg.fetchConnectTimeoutMs()))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            PolitenessGate gate = new PolitenessGate(cfg, client, "http://127.0.0.1:" + port);
            gate.ensureLoaded("127.0.0.1");
            assertEquals(RobotDecisionType.ALLOWED, gate.evaluate("http://127.0.0.1/any/path").type());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void robots503_thenRecovery_returnsAllowed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger hits = new AtomicInteger();
        byte[] okBody = "User-agent: *\nDisallow:\n".getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/robots.txt",
                ex -> {
                    int n = hits.incrementAndGet();
                    if (n == 1) {
                        ex.sendResponseHeaders(503, 0);
                    } else {
                        ex.sendResponseHeaders(200, okBody.length);
                        ex.getResponseBody().write(okBody);
                    }
                    ex.close();
                });
        server.start();
        try {
            Properties p = baseProps();
            p.setProperty("crawler.robots.temporaryDenyRetryMinutes", "1");
            p.setProperty("crawler.robots.temporaryDenyMaxMinutes", "10");
            p.setProperty("crawler.retry.jitterMs", "0");
            RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
            cfg.validate();
            MutableClock mutableClock = new MutableClock(Instant.parse("2026-06-01T12:00:00Z"));
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofMillis(cfg.fetchConnectTimeoutMs()))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            PolitenessGate gate = new PolitenessGate(cfg, client, "http://127.0.0.1:" + port, null, mutableClock);
            gate.ensureLoaded("127.0.0.1");
            assertEquals(RobotDecisionType.TEMPORARY_DENY, gate.evaluate("http://127.0.0.1/p").type());
            // Past deny window so ensureLoaded will hit the network again (same-thread ordering as HttpFetcher).
            mutableClock.advance(Duration.ofMinutes(5));
            gate.ensureLoaded("127.0.0.1");
            assertEquals(RobotDecisionType.ALLOWED, gate.evaluate("http://127.0.0.1/p").type());
            assertEquals(2, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void denyUntil_neverExceedsMaxMinutesFromNow() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext(
                "/robots.txt",
                ex -> {
                    ex.sendResponseHeaders(500, 0);
                    ex.close();
                });
        server.start();
        try {
            Properties p = baseProps();
            p.setProperty("crawler.robots.temporaryDenyRetryMinutes", "5");
            p.setProperty("crawler.robots.temporaryDenyMaxMinutes", "8");
            p.setProperty("crawler.retry.jitterMs", "0");
            RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
            cfg.validate();
            Instant start = Instant.parse("2026-06-01T12:00:00Z");
            MutableClock mutableClock = new MutableClock(start);
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofMillis(cfg.fetchConnectTimeoutMs()))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            PolitenessGate gate = new PolitenessGate(cfg, client, "http://127.0.0.1:" + port, null, mutableClock);
            gate.ensureLoaded("127.0.0.1");
            var decision = gate.evaluate("http://127.0.0.1/z");
            assertEquals(RobotDecisionType.TEMPORARY_DENY, decision.type());
            Instant cap = start.plus(Duration.ofMinutes(8));
            assertTrue(
                    !decision.denyUntil().isAfter(cap),
                    "denyUntil must be <= now + temporaryDenyMaxMinutes (TS-06)");
        } finally {
            server.stop(0);
        }
    }

    private static Properties baseProps() throws Exception {
        Properties p = new Properties();
        Path kw =
                Paths.get(PolitenessGateHttpOutcomesUnitTest.class.getResource("/keywords-valid.json").toURI())
                        .toAbsolutePath();
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "6");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        return p;
    }

    private static RuntimeConfig baseConfig() throws Exception {
        RuntimeConfig cfg = RuntimeConfig.fromProperties(baseProps(), 4);
        cfg.validate();
        return cfg;
    }

    /** Minimal mutable {@link Clock} for deterministic deny-window tests. */
    private static final class MutableClock extends Clock {
        private Instant current;
        private final ZoneOffset zone = ZoneOffset.UTC;

        private MutableClock(Instant start) {
            this.current = start;
        }

        void advance(Duration d) {
            current = current.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
