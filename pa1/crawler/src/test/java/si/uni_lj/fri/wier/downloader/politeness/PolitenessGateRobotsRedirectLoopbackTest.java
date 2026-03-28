package si.uni_lj.fri.wier.downloader.politeness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.RobotDecisionType;

/**
 * TS-06: robots.txt hop-by-hop redirects with per-hop politeness keys (different hosts on loopback).
 */
class PolitenessGateRobotsRedirectLoopbackTest {

    @Test
    void ensureLoaded_followsRedirect_parsesFinalRobots() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        byte[] body =
                ("User-agent: fri-wier-IEPS-TOLPA\n" + "Crawl-delay: 77\n")
                        .getBytes(StandardCharsets.UTF_8);

        server.createContext(
                "/robots.txt",
                ex -> {
                    ex.getResponseHeaders()
                            .add("Location", "http://127.0.0.1:" + port + "/r2.txt");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                });
        server.createContext(
                "/r2.txt",
                ex -> {
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
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
            String origin = "http://127.0.0.1:" + port;
            PolitenessGate gate = new PolitenessGate(cfg, client, origin);
            gate.ensureLoaded("127.0.0.1");
            assertTrue(
                    gate.spacingMillisForDomain("127.0.0.1") >= 77_000L,
                    "rules after redirect should expose Crawl-delay 77s for our User-agent");
            assertEquals(RobotDecisionType.ALLOWED, gate.evaluate("http://127.0.0.1/public/page").type());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ensureLoaded_twoHops_respectsMaxRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext(
                "/robots.txt",
                ex -> {
                    ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/r1");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                });
        server.createContext(
                "/r1",
                ex -> {
                    ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/r2");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                });
        server.createContext(
                "/r2",
                ex -> {
                    byte[] b = "User-agent: *\nDisallow:\n".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                });
        server.start();
        try {
            Properties p = baseProps();
            p.setProperty("crawler.fetch.maxRedirects", "1");
            RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
            cfg.validate();
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofMillis(cfg.fetchConnectTimeoutMs()))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            PolitenessGate gate =
                    new PolitenessGate(cfg, client, "http://127.0.0.1:" + port);
            gate.ensureLoaded("127.0.0.1");
            assertTrue(
                    gate.getRulesForDomain("127.0.0.1").isAllowNone(),
                    "hop limit exceeded should install deny-all fallback rules");
        } finally {
            server.stop(0);
        }
    }

    private static RuntimeConfig baseConfig() throws Exception {
        Properties p = baseProps();
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        return cfg;
    }

    private static Properties baseProps() throws Exception {
        Properties p = new Properties();
        Path kw =
                Paths.get(
                                PolitenessGateRobotsRedirectLoopbackTest.class
                                        .getResource("/keywords-valid.json")
                                        .toURI())
                        .toAbsolutePath();
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        return p;
    }
}
