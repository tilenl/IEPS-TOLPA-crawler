package si.uni_lj.fri.wier.unit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.app.PreferentialCrawler;

class PreferentialCrawlerTest {

    @Test
    void safeJdbcUrlForLog_stripsUserPassword() {
        assertEquals(
                "jdbc:postgresql://db.example.com:5432/crawldb",
                PreferentialCrawler.safeJdbcUrlForLog(
                        "jdbc:postgresql://u:secret@db.example.com:5432/crawldb"));
    }

    @Test
    void safeJdbcUrlForLog_keepsHostWithoutCredentials() {
        assertEquals(
                "jdbc:postgresql://localhost:5432/crawldb",
                PreferentialCrawler.safeJdbcUrlForLog("jdbc:postgresql://localhost:5432/crawldb"));
    }
}
