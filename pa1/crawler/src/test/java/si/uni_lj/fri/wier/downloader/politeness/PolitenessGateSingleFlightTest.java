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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;

/** TS-06 / TS-14: one in-flight robots fetch per domain under concurrent ensureLoaded. */
class PolitenessGateSingleFlightTest {

    @Test
    void ensureLoaded_manyThreads_singleHttpRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger hits = new AtomicInteger();
        byte[] body = "User-agent: *\nDisallow:\n".getBytes(StandardCharsets.UTF_8);
        server.createContext(
                "/robots.txt",
                ex -> {
                    hits.incrementAndGet();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
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
            int threads = 12;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(
                        () -> {
                            try {
                                start.await();
                                gate.ensureLoaded("127.0.0.1");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                done.countDown();
                            }
                        });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdownNow();
            assertEquals(1, hits.get());
        } finally {
            server.stop(0);
        }
    }

    private static RuntimeConfig baseConfig() throws Exception {
        Properties p = new Properties();
        Path kw =
                Paths.get(
                                PolitenessGateSingleFlightTest.class.getResource("/keywords-valid.json").toURI())
                        .toAbsolutePath();
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        return cfg;
    }
}
