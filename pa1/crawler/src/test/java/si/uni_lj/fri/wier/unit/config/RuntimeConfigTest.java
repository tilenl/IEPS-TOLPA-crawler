package si.uni_lj.fri.wier.unit.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;

class RuntimeConfigTest {

    @Test
    void validate_rejectsHeadlessSessionsGreaterThanWorkers() {
        Properties p = baseProps();
        p.setProperty("crawler.nCrawlers", "2");
        p.setProperty("crawler.fetch.maxHeadlessSessions", "4");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_acceptsDefaultsFromProperties() {
        Properties p = baseProps();
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
    }

    private static Properties baseProps() {
        Properties p = new Properties();
        p.setProperty("crawler.scoring.keywordConfig", "keywords.json");
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "2");
        return p;
    }
}
