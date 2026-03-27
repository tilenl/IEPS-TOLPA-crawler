package si.uni_lj.fri.wier.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import si.uni_lj.fri.wier.config.RuntimeConfig;

class RuntimeConfigTest {

    @Test
    void validate_rejectsFetchCapacityMaxAttemptsOutOfRange() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.retry.maxAttempts.fetchCapacity", "21");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_rejectsHeadlessSessionsGreaterThanWorkers() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.nCrawlers", "2");
        p.setProperty("crawler.fetch.maxHeadlessSessions", "4");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_acceptsDefaultsFromProperties() throws Exception {
        Properties p = baseProps();
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertEquals(8760, cfg.bucketsCacheTtlHours());
        assertEquals(100_000, cfg.bucketsCacheMaxEntries());
        assertEquals(3, cfg.retryMaxAttemptsFetchCapacity());
        assertEquals(10, cfg.fetchMaxRedirects());
        assertEquals(45_000, cfg.healthHeartbeatIntervalMs());
    }

    @Test
    void validate_rejectsMaxRedirectsOutOfRange() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.fetch.maxRedirects", "21");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_rejectsHeartbeatOutOfRange() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.health.heartbeatIntervalMs", "4000");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_rejectsRobotsDenyInconsistency() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.robots.temporaryDenyMaxMinutes", "5");
        p.setProperty("crawler.robots.temporaryDenyRetryMinutes", "10");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_rejectsInvalidKeywordFile(@TempDir Path dir) throws Exception {
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"primary\":[]}");
        Properties p = baseProps();
        p.setProperty("crawler.scoring.keywordConfig", bad.toString());
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_acceptsValidKeywordFile(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("good.json");
        Files.writeString(
                good,
                """
                {"primary":["x"],"secondary":["y"]}
                """);
        Properties p = baseProps();
        p.setProperty("crawler.scoring.keywordConfig", good.toString());
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
    }

    @Test
    void pageRepositoryRecoveryParams_matchRuntimeConfig() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.recoveryPath.maxAttempts", "7");
        p.setProperty("crawler.recoveryPath.baseBackoffMs", "200");
        p.setProperty("crawler.retry.jitterMs", "99");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertEquals(7, cfg.recoveryPathMaxAttempts());
        assertEquals(200, cfg.recoveryPathBaseBackoffMs());
        assertEquals(99, cfg.retryJitterMs());
    }

    @Test
    void validate_rejectsDbPoolSmallerThanNCrawlersPlusOne() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.nCrawlers", "4");
        p.setProperty("crawler.db.poolSize", "4");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 8);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_rejectsRobotsCacheMaxEntriesBelowMinimum() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.robots.cacheMaxEntries", "50");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_rejectsBucketsCacheMaxEntriesBelowMinimum() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.buckets.cacheMaxEntries", "50");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    private static Properties baseProps() throws Exception {
        Properties p = new Properties();
        Path kw = Paths.get(RuntimeConfigTest.class.getResource("/keywords-valid.json").toURI());
        p.setProperty("crawler.scoring.keywordConfig", kw.toString());
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "4");
        return p;
    }
}
