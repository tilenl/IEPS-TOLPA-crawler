/*
 * TS-06 path allow/disallow decisions against a fixed robots.txt (crawler-commons semantics).
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.unit.downloader.politeness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import crawlercommons.robots.SimpleRobotRulesParser;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.RobotDecisionType;
import si.uni_lj.fri.wier.downloader.fetch.CrawlerUserAgents;
import si.uni_lj.fri.wier.downloader.politeness.PolitenessGate;

class PolitenessGateEvaluatePathsUnitTest {

    @Test
    void evaluate_siteWideDisallow_blocksPaths() throws Exception {
        String robots = "User-agent: " + CrawlerUserAgents.FETCHER + "\nDisallow: /\n";
        var parser = new SimpleRobotRulesParser();
        var parsed =
                parser.parseContent(
                        "http://127.0.0.1/robots.txt",
                        robots.getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8.name(),
                        CrawlerUserAgents.FETCHER);
        assertFalse(
                parsed.isAllowed("http://127.0.0.1/topics/page"),
                "sanity: site-wide Disallow:/ blocks full-URL paths (crawler-commons URL parsing)");

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        byte[] body = robots.getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/robots.txt",
                ex -> {
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
            PolitenessGate gate = new PolitenessGate(cfg, client, "http://127.0.0.1:" + port);
            gate.ensureLoaded("127.0.0.1");
            assertEquals(
                    RobotDecisionType.DISALLOWED,
                    gate.evaluate("http://127.0.0.1/topics/page").type());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void evaluate_githubStyleWildcardDisallow_blocksTreeAndPulsePaths() throws Exception {
        String robots =
                "User-agent: "
                        + CrawlerUserAgents.FETCHER
                        + "\nDisallow: /*/tree/\nDisallow: /*/*/pulse\n";
        var parser = new SimpleRobotRulesParser();
        var parsed =
                parser.parseContent(
                        "https://github.com/robots.txt",
                        robots.getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8.name(),
                        CrawlerUserAgents.FETCHER);
        String httpsBase = "https://github.com";
        assertFalse(
                parsed.isAllowed(httpsBase + "/onnx/models/tree/main/Generative_AI"),
                "GitHub-style /*/tree/ should block tree URLs when isAllowed gets a full https URL");
        assertFalse(
                parsed.isAllowed(httpsBase + "/onnx/models/pulse"),
                "GitHub-style /*/*/pulse should block pulse when isAllowed gets a full https URL");
        assertTrue(parsed.isAllowed(httpsBase + "/onnx/models"), "repo root stays allowed");
        assertTrue(
                parsed.isAllowed(httpsBase + "/onnx/models/issues"),
                "issues list is not covered by tree/pulse disallows");

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        byte[] body = robots.getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/robots.txt",
                ex -> {
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
            assertEquals(
                    RobotDecisionType.DISALLOWED,
                    gate.evaluate(origin + "/onnx/models/tree/main/Generative_AI").type());
            assertEquals(RobotDecisionType.DISALLOWED, gate.evaluate(origin + "/onnx/models/pulse").type());
            assertEquals(RobotDecisionType.ALLOWED, gate.evaluate(origin + "/onnx/models").type());
            assertEquals(RobotDecisionType.ALLOWED, gate.evaluate(origin + "/onnx/models/issues").type());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void evaluate_emptyDisallow_allowsPaths() throws Exception {
        String robots = "User-agent: " + CrawlerUserAgents.FETCHER + "\nDisallow:\n";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        byte[] body = robots.getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/robots.txt",
                ex -> {
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
            PolitenessGate gate = new PolitenessGate(cfg, client, "http://127.0.0.1:" + port);
            gate.ensureLoaded("127.0.0.1");
            assertEquals(
                    RobotDecisionType.ALLOWED,
                    gate.evaluate("http://127.0.0.1/any/deep/path").type());
        } finally {
            server.stop(0);
        }
    }

    private static RuntimeConfig baseConfig() throws Exception {
        Properties p = new Properties();
        Path kw =
                Paths.get(PolitenessGateEvaluatePathsUnitTest.class.getResource("/keywords-valid.json").toURI())
                        .toAbsolutePath();
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "6");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        return cfg;
    }
}
