package si.uni_lj.fri.wier.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.FetchException;
import si.uni_lj.fri.wier.contracts.FetchRequest;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;
import si.uni_lj.fri.wier.unit.downloader.fetch.AllowAllPolitenessStub;

/** TS-03: structured log when plain HTTP looks like a shell and headless escalation is attempted. */
class HttpFetcherIncompleteShellLogTest {

    @Test
    void incompleteShell_logsFetchIncompleteShell_beforeHeadlessCapacityFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext(
                "/shell",
                ex -> {
                    byte[] b = "<html><body>hi</body></html>".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "text/html");
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                });
        server.start();
        Logger log = (Logger) LoggerFactory.getLogger(HttpFetcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            RuntimeConfig cfg = baseConfig();
            Clock clock = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);
            HttpClient client =
                    HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofMillis(cfg.fetchConnectTimeoutMs()))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
            AllowAllPolitenessStub stub = new AllowAllPolitenessStub();
            HeadlessSessionPool pool = new NeverAcquireHeadlessPool(cfg, clock);
            HttpFetcher fetcher = new HttpFetcher(cfg, stub, stub, clock, client, pool);
            String url = "http://127.0.0.1:" + port + "/shell";
            FetchException ex =
                    assertThrows(
                            FetchException.class,
                            () ->
                                    fetcher.fetch(
                                            new FetchRequest(
                                                    url,
                                                    "w1",
                                                    Instant.parse("2026-03-01T12:30:00Z"),
                                                    false)));
            assertEquals(CrawlerErrorCategory.FETCH_CAPACITY_EXHAUSTED.name(), ex.category());
            boolean logged =
                    appender.list.stream()
                            .anyMatch(
                                    e ->
                                            e.getFormattedMessage() != null
                                                    && e.getFormattedMessage()
                                                            .contains("FETCH_INCOMPLETE_SHELL"));
            assertTrue(logged, "expected FETCH_INCOMPLETE_SHELL log before headless failure");
        } finally {
            log.detachAppender(appender);
            appender.stop();
            server.stop(0);
        }
    }

    private static final class NeverAcquireHeadlessPool extends HeadlessSessionPool {
        NeverAcquireHeadlessPool(RuntimeConfig config, Clock clock) {
            super(config, clock);
        }

        @Override
        boolean tryAcquireSlot() {
            return false;
        }
    }

    private static RuntimeConfig baseConfig() throws Exception {
        Properties p = new Properties();
        Path kw =
                Paths.get(HttpFetcherIncompleteShellLogTest.class.getResource("/keywords-valid.json").toURI())
                        .toAbsolutePath();
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "5");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        return cfg;
    }
}
