package si.uni_lj.fri.wier.unit.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.downloader.fetch.HttpFetcher;

class HttpFetcherTest {

    @Test
    void defaultMaxRedirects_matchesTs13Default() {
        assertEquals(10, new HttpFetcher().maxRedirects());
    }

    @Test
    void maxRedirects_storesFromConstructor() {
        assertEquals(7, new HttpFetcher(7).maxRedirects());
    }

    @Test
    void from_usesRuntimeConfigFetchMaxRedirects() throws Exception {
        Properties p = new Properties();
        Path kw = Paths.get(HttpFetcherTest.class.getResource("/keywords-valid.json").toURI());
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        p.setProperty("crawler.fetch.maxRedirects", "3");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertEquals(3, HttpFetcher.from(cfg).maxRedirects());
    }
}
