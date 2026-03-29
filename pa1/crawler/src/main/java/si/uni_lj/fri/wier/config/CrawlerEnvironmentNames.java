/*
 * Maps TS-13 property keys to environment variable names (CRAWLER_* + dotted segments as underscores).
 * Used by ConfigurationBootstrap for env overlay; tests assert round-trips for operator documentation.
 *
 * Callers: ConfigurationBootstrap. Owned by TS-13.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.config;

import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic {@code crawler.*} key → {@code CRAWLER_*} env name mapping (TS-13).
 *
 * <p>Example: {@code crawler.db.url} → {@code CRAWLER_DB_URL}; {@code
 * crawler.retry.maxAttempts.fetchCapacity} → {@code CRAWLER_RETRY_MAXATTEMPTS_FETCHCAPACITY}. Keys with a
 * single segment after {@code crawler.} (e.g. {@code crawler.nCrawlers}) become {@code CRAWLER_NCRAWLERS},
 * not {@code CRAWLER_N_CRAWLERS}.
 */
public final class CrawlerEnvironmentNames {

    /**
     * Every property key that {@link RuntimeConfig#fromProperties} reads, plus {@code crawler.profile} for
     * bootstrap. Env overlay must consider each so operators can override any setting without a file change.
     */
    public static final String[] OVERLAY_PROPERTY_KEYS = {
        "crawler.profile",
        "crawler.nCrawlers",
        "crawler.frontier.pollMs",
        "crawler.frontier.leaseSeconds",
        "crawler.frontier.leaseRecoveryBatchSize",
        "crawler.frontier.startupLeaseRecoveryBatchSize",
        "crawler.frontier.terminationGraceMs",
        "crawler.fetch.connectTimeoutMs",
        "crawler.fetch.readTimeoutMs",
        "crawler.fetch.maxHeadlessSessions",
        "crawler.fetch.headlessAcquireTimeoutMs",
        "crawler.fetch.headlessCircuitOpenThreshold",
        "crawler.fetch.maxRedirects",
        "crawler.health.heartbeatIntervalMs",
        "crawler.rateLimit.minDelayMs",
        "crawler.rateLimit.maxBackoffMs",
        "crawler.robots.cacheTtlHours",
        "crawler.robots.cacheMaxEntries",
        "crawler.robots.temporaryDenyMaxMinutes",
        "crawler.robots.temporaryDenyRetryMinutes",
        "crawler.buckets.cacheTtlHours",
        "crawler.buckets.cacheMaxEntries",
        "crawler.retry.jitterMs",
        "crawler.recoveryPath.maxAttempts",
        "crawler.recoveryPath.baseBackoffMs",
        "crawler.retry.maxAttempts.fetchTimeout",
        "crawler.retry.maxAttempts.fetchOverload",
        "crawler.retry.maxAttempts.fetchCapacity",
        "crawler.retry.maxAttempts.dbTransient",
        "crawler.budget.maxTotalPages",
        "crawler.budget.maxFrontierRows",
        "crawler.crawlScope",
        "crawler.seedUrls",
        "crawler.scoring.keywordConfig",
        "crawler.db.url",
        "crawler.db.user",
        "crawler.db.password",
        "crawler.db.poolSize",
        "crawler.db.expectedSchemaVersion",
    };

    private CrawlerEnvironmentNames() {}

    /**
     * @param propertyKey a key starting with {@code crawler.}
     * @return the env var name (e.g. {@code CRAWLER_DB_URL})
     */
    public static String propertyKeyToEnvName(String propertyKey) {
        Objects.requireNonNull(propertyKey, "propertyKey");
        if (!propertyKey.startsWith("crawler.")) {
            throw new IllegalArgumentException("Expected key starting with crawler.: " + propertyKey);
        }
        String tail = propertyKey.substring("crawler.".length());
        return "CRAWLER_" + tail.replace('.', '_').toUpperCase(Locale.ROOT);
    }
}
