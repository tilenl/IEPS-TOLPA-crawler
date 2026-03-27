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
