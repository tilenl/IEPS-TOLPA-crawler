package si.uni_lj.fri.wier.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.FetchException;
import si.uni_lj.fri.wier.contracts.FetchRequest;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;
import si.uni_lj.fri.wier.unit.downloader.fetch.AllowAllPolitenessStub;

/**
 * Redirect chain against loopback {@link HttpServer} (no external network). Uses {@link AllowAllPolitenessStub}
 * so robots are not fetched.
 */
class HttpFetcherRedirectLoopbackTest {

    @Test
    void fetch_followsRedirect_returnsFinalBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String host = "127.0.0.1";
        server.createContext(
                "/start",
                ex -> {
                    ex.getResponseHeaders().add("Location", "http://" + host + ":" + port + "/final");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                });
        server.createContext(
                "/final",
                ex -> {
                    String ua = ex.getRequestHeaders().getFirst("User-agent");
                    assertTrue(
                            ua != null && ua.contains(CrawlerUserAgents.FETCHER),
                            "TS-03 normative User-Agent on content GET");
                    byte[] b = "<html><body><a href=\"/x\">link</a></body></html>".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                });
        server.start();
        try {
            RuntimeConfig cfg = testConfig("10");
            AllowAllPolitenessStub stub = new AllowAllPolitenessStub();
            HttpFetcher fetcher = new HttpFetcher(cfg, stub, stub);
            String url = "http://" + host + ":" + port + "/start";
            FetchResult r =
                    fetcher.fetch(
                            new FetchRequest(
                                    url, "w1", Instant.now().plusSeconds(300), false));
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("<a href"));
            assertEquals("http://" + host + ":" + port + "/final", r.finalUrlAfterRedirects());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetch_redirectLoop_throwsFetchTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String host = "127.0.0.1";
        server.createContext(
                "/loop",
                ex -> {
                    ex.getResponseHeaders().add("Location", "http://" + host + ":" + port + "/loop");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                });
        server.start();
        try {
            RuntimeConfig cfg = testConfig("10");
            AllowAllPolitenessStub stub = new AllowAllPolitenessStub();
            HttpFetcher fetcher = new HttpFetcher(cfg, stub, stub);
            FetchException ex =
                    assertThrows(
                            FetchException.class,
                            () ->
                                    fetcher.fetch(
                                            new FetchRequest(
                                                    "http://" + host + ":" + port + "/loop",
                                                    "w1",
                                                    Instant.now().plusSeconds(300),
                                                    false)));
            assertEquals(CrawlerErrorCategory.FETCH_TIMEOUT.name(), ex.category());
            assertTrue(ex.getMessage().contains("redirect loop"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fetch_exceedsMaxRedirects_throwsFetchTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String host = "127.0.0.1";
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            server.createContext(
                    "/s" + i,
                    ex -> {
                        String next = idx < 4 ? "/s" + (idx + 1) : "/end";
                        ex.getResponseHeaders().add("Location", "http://" + host + ":" + port + next);
                        ex.sendResponseHeaders(302, -1);
                        ex.close();
                    });
        }
        server.createContext(
                "/end",
                ex -> {
                    byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                });
        server.start();
        try {
            RuntimeConfig cfg = testConfig("2");
            AllowAllPolitenessStub stub = new AllowAllPolitenessStub();
            HttpFetcher fetcher = new HttpFetcher(cfg, stub, stub);
            FetchException ex =
                    assertThrows(
                            FetchException.class,
                            () ->
                                    fetcher.fetch(
                                            new FetchRequest(
                                                    "http://" + host + ":" + port + "/s0",
                                                    "w1",
                                                    Instant.now().plusSeconds(300),
                                                    false)));
            assertEquals(CrawlerErrorCategory.FETCH_TIMEOUT.name(), ex.category());
        } finally {
            server.stop(0);
        }
    }

    private static RuntimeConfig testConfig(String maxRedirects) throws Exception {
        Properties p = new Properties();
        Path kw = Paths.get(HttpFetcherRedirectLoopbackTest.class.getResource("/keywords-valid.json").toURI());
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        p.setProperty("crawler.fetch.maxRedirects", maxRedirects);
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        return cfg;
    }
}
