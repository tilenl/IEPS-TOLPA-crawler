package si.uni_lj.fri.wier.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import si.uni_lj.fri.wier.config.CrawlScope;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.downloader.fetch.GithubRepoSubpathDiscoveryBlock;

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
    void fromProperties_fetchDenyPathPostfixes_defaultsToPyTxtYamlIpynb() throws Exception {
        Properties p = baseProps();
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertIterableEquals(List.of("py", "txt", "yaml", "ipynb"), cfg.fetchDenyPathPostfixes());
    }

    @Test
    void fromProperties_fetchDenyPathPostfixes_parsesCommaSeparated() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.fetch.denyPathPostfixes", " .py , MD ");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertIterableEquals(List.of("py", "md"), cfg.fetchDenyPathPostfixes());
    }

    @Test
    void fromProperties_fetchDenyPathPostfixes_onlyCommas_yieldsEmptyList() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.fetch.denyPathPostfixes", " , , ");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertTrue(cfg.fetchDenyPathPostfixes().isEmpty());
    }

    @Test
    void validate_rejectsFetchDenyPathPostfixesWithInvalidCharacters() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.fetch.denyPathPostfixes", "py,readme.py");
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
        assertEquals(CrawlScope.GITHUB, cfg.crawlScope());
        assertFalse(cfg.discoveryBlockGithubTopicsPaths());
        assertEquals(
                Set.copyOf(GithubRepoSubpathDiscoveryBlock.DEFAULT_DENY_REPO_SUBPATHS),
                cfg.discoveryDenyGithubRepoSubpaths());
    }

    @Test
    void fromProperties_discoveryBlockGithubTopicsPaths_true() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.discovery.blockGithubTopicsPaths", "true");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertTrue(cfg.discoveryBlockGithubTopicsPaths());
    }

    @Test
    void fromProperties_discoveryBlockGithubTopicsPaths_invalid_throws() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.discovery.blockGithubTopicsPaths", "maybe");
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromProperties(p, 4));
    }

    @Test
    void fromProperties_discoveryDenyGithubRepoSubpaths_parsesCommaSeparated() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.discovery.denyGithubRepoSubpaths", " wiki , ACTIONS ");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertEquals(Set.of("wiki", "actions"), cfg.discoveryDenyGithubRepoSubpaths());
    }

    @Test
    void fromProperties_discoveryDenyGithubRepoSubpaths_onlyCommas_yieldsEmptySet() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.discovery.denyGithubRepoSubpaths", " , , ");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        cfg.validate();
        assertTrue(cfg.discoveryDenyGithubRepoSubpaths().isEmpty());
    }

    @Test
    void validate_rejectsDiscoveryDenyGithubRepoSubpaths_invalidCharacters() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.discovery.denyGithubRepoSubpaths", "issues,bad.seg");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void fromProperties_invalidCrawlScope_throws() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.crawlScope", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> RuntimeConfig.fromProperties(p, 4));
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
    void validate_rejectsSeedRelevanceNotStrictlyAboveMaxKeywordScore(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("good.json");
        Files.writeString(
                good,
                """
                {"primary":["x"],"secondary":["y"]}
                """);
        Properties p = baseProps();
        p.setProperty("crawler.scoring.keywordConfig", good.toString());
        p.setProperty("crawler.scoring.primaryWeight", "0.18");
        p.setProperty("crawler.scoring.secondaryWeight", "0.09");
        p.setProperty("crawler.scoring.maxOccurrencesPerKeyword", "128");
        // max = (0.18 + 0.09) * 128 = 34.56 — seed below that is invalid
        p.setProperty("crawler.scoring.seedRelevanceScore", "0.27");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_rejectsMaxOccurrencesPerKeywordOutOfRange(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("good.json");
        Files.writeString(
                good,
                """
                {"primary":["x"],"secondary":["y"]}
                """);
        Properties p = baseProps();
        p.setProperty("crawler.scoring.keywordConfig", good.toString());
        p.setProperty("crawler.scoring.maxOccurrencesPerKeyword", "0");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
        p.setProperty("crawler.scoring.maxOccurrencesPerKeyword", "4097");
        RuntimeConfig cfg2 = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg2::validate);
    }

    @Test
    void validate_rejectsScoringWeightAboveTen(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("good.json");
        Files.writeString(
                good,
                """
                {"primary":["x"],"secondary":["y"]}
                """);
        Properties p = baseProps();
        p.setProperty("crawler.scoring.keywordConfig", good.toString());
        p.setProperty("crawler.scoring.primaryWeight", "10.01");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
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

    @Test
    void validate_rejectsEmptySeedUrls() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.seedUrls", " , , ");
        RuntimeConfig cfg = RuntimeConfig.fromProperties(p, 4);
        assertThrows(IllegalArgumentException.class, cfg::validate);
    }

    @Test
    void validate_rejectsNonHttpSeedUrl() throws Exception {
        Properties p = baseProps();
        p.setProperty("crawler.seedUrls", "ftp://example.com/");
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
        p.setProperty("crawler.db.expectedSchemaVersion", "8");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        return p;
    }
}
