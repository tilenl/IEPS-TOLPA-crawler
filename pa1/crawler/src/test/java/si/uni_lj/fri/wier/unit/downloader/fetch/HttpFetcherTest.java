package si.uni_lj.fri.wier.unit.downloader.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.downloader.fetch.HttpFetcher;
import si.uni_lj.fri.wier.downloader.politeness.PolitenessGate;

class HttpFetcherTest {

    @Test
    void defaultMaxRedirects_matchesTs13Default() throws Exception {
        RuntimeConfig cfg = baseConfig();
        HttpFetcher f = new HttpFetcher(cfg, new AllowAllPolitenessStub(), new AllowAllPolitenessStub());
        assertEquals(10, f.maxRedirects());
    }

    @Test
    void maxRedirects_fromProperties() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.fetch.maxRedirects", "7");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        HttpFetcher f = new HttpFetcher(cfg, new AllowAllPolitenessStub(), new AllowAllPolitenessStub());
        assertEquals(7, f.maxRedirects());
    }

    @Test
    void isIncompleteHtmlShell_trueForShortHtml_githubSameAsOtherHosts() {
        assertTrue(
                HttpFetcher.isIncompleteHtmlShell(
                        "text/html", "<html><body>tiny</body></html>"));
    }

    @Test
    void isIncompleteHtmlShell_trueWhenNoAnchorTag() {
        assertTrue(
                HttpFetcher.isIncompleteHtmlShell(
                        "text/html",
                        "<html><body><div>no links here</div></body></html>".repeat(30)));
    }

    @Test
    void isIncompleteHtmlShell_falseForSubstantialHtmlWithLinks() {
        assertFalse(
                HttpFetcher.isIncompleteHtmlShell(
                        "text/html",
                        "<html><body><a href=\"/x\">x</a>"
                                + "y".repeat(500)
                                + "</body></html>"));
    }

    @Test
    void isIncompleteHtmlShell_falseForNonHtml() {
        assertFalse(HttpFetcher.isIncompleteHtmlShell("application/json", "{}"));
    }

    @Test
    void from_usesRuntimeConfigFetchMaxRedirects() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.fetch.maxRedirects", "3");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        PolitenessGate gate = new PolitenessGate(cfg);
        assertEquals(3, HttpFetcher.from(cfg, gate).maxRedirects());
    }

    private static RuntimeConfig baseConfig() throws Exception {
        Properties p = baseProps();
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        return cfg;
    }

    private static Properties baseProps() throws Exception {
        Properties p = new Properties();
        Path kw = Paths.get(HttpFetcherTest.class.getResource("/keywords-valid.json").toURI());
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "5");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        return p;
    }
}
