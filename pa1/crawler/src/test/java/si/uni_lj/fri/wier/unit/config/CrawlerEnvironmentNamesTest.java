package si.uni_lj.fri.wier.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.CrawlerEnvironmentNames;

class CrawlerEnvironmentNamesTest {

    @Test
    void propertyKeyToEnvName_mapsDbUrl() {
        assertEquals("CRAWLER_DB_URL", CrawlerEnvironmentNames.propertyKeyToEnvName("crawler.db.url"));
    }

    @Test
    void propertyKeyToEnvName_mapsNestedRetryKey() {
        assertEquals(
                "CRAWLER_RETRY_MAXATTEMPTS_FETCHCAPACITY",
                CrawlerEnvironmentNames.propertyKeyToEnvName("crawler.retry.maxAttempts.fetchCapacity"));
    }

    @Test
    void propertyKeyToEnvName_mapsNCrawlers_singleSegmentCamelCase() {
        assertEquals("CRAWLER_NCRAWLERS", CrawlerEnvironmentNames.propertyKeyToEnvName("crawler.nCrawlers"));
    }

    @Test
    void propertyKeyToEnvName_rejectsNonCrawlerPrefix() {
        assertThrows(IllegalArgumentException.class, () -> CrawlerEnvironmentNames.propertyKeyToEnvName("foo.bar"));
    }

    @Test
    void propertyKeyToEnvName_mapsSeedUrls() {
        assertEquals("CRAWLER_SEEDURLS", CrawlerEnvironmentNames.propertyKeyToEnvName("crawler.seedUrls"));
        assertEquals("CRAWLER_CRAWLSCOPE", CrawlerEnvironmentNames.propertyKeyToEnvName("crawler.crawlScope"));
    }
}
