package si.uni_lj.fri.wier.unit.error;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;
import si.uni_lj.fri.wier.error.RecoveryDecision;
import si.uni_lj.fri.wier.error.RecoveryPolicy;

class RecoveryPolicyUnitTest {

    @Test
    void isRetryable_matchesTs12Matrix() {
        assertFalse(RecoveryPolicy.isRetryable(CrawlerErrorCategory.INVALID_URL));
        assertFalse(RecoveryPolicy.isRetryable(CrawlerErrorCategory.ROBOTS_DISALLOWED));
        assertTrue(RecoveryPolicy.isRetryable(CrawlerErrorCategory.ROBOTS_TRANSIENT));
        assertTrue(RecoveryPolicy.isRetryable(CrawlerErrorCategory.FETCH_TIMEOUT));
        assertTrue(RecoveryPolicy.isRetryable(CrawlerErrorCategory.FETCH_HTTP_OVERLOAD));
        assertTrue(RecoveryPolicy.isRetryable(CrawlerErrorCategory.FETCH_CAPACITY_EXHAUSTED));
        assertFalse(RecoveryPolicy.isRetryable(CrawlerErrorCategory.FETCH_HTTP_CLIENT));
        assertTrue(RecoveryPolicy.isRetryable(CrawlerErrorCategory.PARSER_FAILURE));
        assertTrue(RecoveryPolicy.isRetryable(CrawlerErrorCategory.DB_TRANSIENT));
        assertFalse(RecoveryPolicy.isRetryable(CrawlerErrorCategory.DB_CONSTRAINT));
    }

    @Test
    void decide_nonRetryable_isTerminal() {
        RuntimeConfig cfg = config();
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        RecoveryDecision d =
                RecoveryPolicy.decide(CrawlerErrorCategory.INVALID_URL, 0, 0, cfg, clock);
        assertInstanceOf(RecoveryDecision.Terminal.class, d);
    }

    @Test
    void decide_fetchTimeout_reschedulesUntilAttemptThreshold() {
        RuntimeConfig cfg = config();
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        assertInstanceOf(
                RecoveryDecision.Reschedule.class,
                RecoveryPolicy.decide(CrawlerErrorCategory.FETCH_TIMEOUT, 2, 0, cfg, clock));
        assertInstanceOf(
                RecoveryDecision.Terminal.class,
                RecoveryPolicy.decide(CrawlerErrorCategory.FETCH_TIMEOUT, 3, 0, cfg, clock));
    }

    @Test
    void decide_parserFailure_ignoresHighAttemptCount() {
        RuntimeConfig cfg = config();
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        assertInstanceOf(
                RecoveryDecision.Reschedule.class,
                RecoveryPolicy.decide(CrawlerErrorCategory.PARSER_FAILURE, 99, 0, cfg, clock));
        assertInstanceOf(
                RecoveryDecision.Terminal.class,
                RecoveryPolicy.decide(CrawlerErrorCategory.PARSER_FAILURE, 99, 1, cfg, clock));
    }

    @Test
    void decide_robotsTransient_usesFixedThreshold() {
        RuntimeConfig cfg = config();
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        assertInstanceOf(
                RecoveryDecision.Reschedule.class,
                RecoveryPolicy.decide(CrawlerErrorCategory.ROBOTS_TRANSIENT, 2, 0, cfg, clock));
        assertInstanceOf(
                RecoveryDecision.Terminal.class,
                RecoveryPolicy.decide(CrawlerErrorCategory.ROBOTS_TRANSIENT, 3, 0, cfg, clock));
    }

    @Test
    void decide_fetchCapacityExhausted_reschedulesUntilAttemptThreshold() {
        RuntimeConfig cfg = configWithFetchCapacityMax(3);
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        assertInstanceOf(
                RecoveryDecision.Reschedule.class,
                RecoveryPolicy.decide(CrawlerErrorCategory.FETCH_CAPACITY_EXHAUSTED, 2, 0, cfg, clock));
        assertInstanceOf(
                RecoveryDecision.Terminal.class,
                RecoveryPolicy.decide(CrawlerErrorCategory.FETCH_CAPACITY_EXHAUSTED, 3, 0, cfg, clock));
    }

    @Test
    void computeNextAttemptAt_withZeroJitter_isDeterministic() {
        RuntimeConfig cfg = config();
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
        Instant t =
                RecoveryPolicy.computeNextAttemptAt(
                        CrawlerErrorCategory.FETCH_TIMEOUT, 1, cfg, clock);
        assertTrue(t.isAfter(Instant.parse("2026-03-01T10:00:00Z")));
    }

    private static RuntimeConfig config() {
        Properties p = new Properties();
        try {
            p.setProperty(
                    "crawler.scoring.keywordConfig",
                    Paths.get(RecoveryPolicyUnitTest.class.getResource("/keywords-valid.json").toURI())
                            .toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "7");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        p.setProperty("crawler.retry.jitterMs", "0");
        p.setProperty("crawler.retry.maxAttempts.fetchTimeout", "3");
        return RuntimeConfig.fromProperties(p, 4);
    }

    private static RuntimeConfig configWithFetchCapacityMax(int max) {
        Properties p = new Properties();
        try {
            p.setProperty(
                    "crawler.scoring.keywordConfig",
                    Paths.get(RecoveryPolicyUnitTest.class.getResource("/keywords-valid.json").toURI())
                            .toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        p.setProperty("crawler.db.url", "jdbc:postgresql://localhost:5432/crawldb");
        p.setProperty("crawler.db.user", "u");
        p.setProperty("crawler.db.password", "p");
        p.setProperty("crawler.db.expectedSchemaVersion", "7");
        p.setProperty("crawler.seedUrls", "https://example.com/");
        p.setProperty("crawler.retry.jitterMs", "0");
        p.setProperty("crawler.retry.maxAttempts.fetchTimeout", "3");
        p.setProperty("crawler.retry.maxAttempts.fetchCapacity", String.valueOf(max));
        return RuntimeConfig.fromProperties(p, 4);
    }
}
