/*
 * TS-13 runtime parameters: typed view of merged Properties (CLI > env > file > defaults).
 *
 * Callers: PreferentialCrawler, Main, RecoveryPolicy, ProcessingFailureHandler, tests.
 *
 * Created: 2026-03. Major revisions: TS-13 maxRedirects/heartbeat, keyword file validation, robots deny rule.
 */

package si.uni_lj.fri.wier.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * Runtime configuration (TS-13). Single-domain crawl: no per-domain page cap — use {@code
 * budgetMaxTotalPages} only.
 *
 * <p>{@link #validate()} must run before database access that depends on bounded frontier/recovery settings;
 * it also validates {@code crawler.scoring.keywordConfig} JSON on the filesystem (relative paths use the JVM
 * working directory).
 */
public record RuntimeConfig(
        int nCrawlers,
        int frontierPollMs,
        int frontierLeaseSeconds,
        int frontierLeaseRecoveryBatchSize,
        int frontierStartupLeaseRecoveryBatchSize,
        int frontierTerminationGraceMs,
        int fetchConnectTimeoutMs,
        int fetchReadTimeoutMs,
        int fetchMaxHeadlessSessions,
        int fetchHeadlessAcquireTimeoutMs,
        int fetchHeadlessCircuitOpenThreshold,
        int fetchMaxRedirects,
        int healthHeartbeatIntervalMs,
        int rateLimitMinDelayMs,
        int rateLimitMaxBackoffMs,
        int robotsCacheTtlHours,
        int robotsCacheMaxEntries,
        int robotsTemporaryDenyMaxMinutes,
        int robotsTemporaryDenyRetryMinutes,
        int bucketsCacheTtlHours,
        int bucketsCacheMaxEntries,
        int retryJitterMs,
        int recoveryPathMaxAttempts,
        int recoveryPathBaseBackoffMs,
        int retryMaxAttemptsFetchTimeout,
        int retryMaxAttemptsFetchOverload,
        int retryMaxAttemptsFetchCapacity,
        int retryMaxAttemptsDbTransient,
        int budgetMaxTotalPages,
        int budgetMaxFrontierRows,
        Path scoringKeywordConfig,
        String dbUrl,
        String dbUser,
        String dbPassword,
        int dbPoolSize,
        String dbExpectedSchemaVersion) {

    public static RuntimeConfig fromProperties(Properties p, int availableCpuCores) {
        int n =
                parseInt(
                        p,
                        "crawler.nCrawlers",
                        Math.min(Math.max(availableCpuCores, 1) * 2, 8));
        return new RuntimeConfig(
                n,
                parseInt(p, "crawler.frontier.pollMs", 500),
                parseInt(p, "crawler.frontier.leaseSeconds", 60),
                parseInt(p, "crawler.frontier.leaseRecoveryBatchSize", 10),
                parseInt(p, "crawler.frontier.startupLeaseRecoveryBatchSize", 100),
                parseInt(p, "crawler.frontier.terminationGraceMs", 2000),
                parseInt(p, "crawler.fetch.connectTimeoutMs", 5000),
                parseInt(p, "crawler.fetch.readTimeoutMs", 10000),
                parseInt(p, "crawler.fetch.maxHeadlessSessions", 2),
                parseInt(p, "crawler.fetch.headlessAcquireTimeoutMs", 2000),
                parseInt(p, "crawler.fetch.headlessCircuitOpenThreshold", 20),
                parseInt(p, "crawler.fetch.maxRedirects", 10),
                parseInt(p, "crawler.health.heartbeatIntervalMs", 45_000),
                parseInt(p, "crawler.rateLimit.minDelayMs", 5000),
                parseInt(p, "crawler.rateLimit.maxBackoffMs", 300_000),
                parseInt(p, "crawler.robots.cacheTtlHours", 24),
                parseInt(p, "crawler.robots.cacheMaxEntries", 10_000),
                parseInt(p, "crawler.robots.temporaryDenyMaxMinutes", 10),
                parseInt(p, "crawler.robots.temporaryDenyRetryMinutes", 2),
                parseInt(p, "crawler.buckets.cacheTtlHours", 8760),
                parseInt(p, "crawler.buckets.cacheMaxEntries", 100_000),
                parseInt(p, "crawler.retry.jitterMs", 250),
                parseInt(p, "crawler.recoveryPath.maxAttempts", 3),
                parseInt(p, "crawler.recoveryPath.baseBackoffMs", 100),
                parseInt(p, "crawler.retry.maxAttempts.fetchTimeout", 3),
                parseInt(p, "crawler.retry.maxAttempts.fetchOverload", 5),
                parseInt(p, "crawler.retry.maxAttempts.fetchCapacity", 3),
                parseInt(p, "crawler.retry.maxAttempts.dbTransient", 5),
                parseInt(p, "crawler.budget.maxTotalPages", 5000),
                parseInt(p, "crawler.budget.maxFrontierRows", 20_000),
                Path.of(Objects.requireNonNull(p.getProperty("crawler.scoring.keywordConfig"), "crawler.scoring.keywordConfig")),
                Objects.requireNonNull(p.getProperty("crawler.db.url"), "crawler.db.url"),
                Objects.requireNonNull(p.getProperty("crawler.db.user"), "crawler.db.user"),
                Objects.requireNonNull(p.getProperty("crawler.db.password"), "crawler.db.password"),
                parseInt(p, "crawler.db.poolSize", Math.min(n + 2, 20)),
                Objects.requireNonNull(
                        p.getProperty("crawler.db.expectedSchemaVersion"),
                        "crawler.db.expectedSchemaVersion"));
    }

    private static int parseInt(Properties p, String key, int defaultValue) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(v.trim());
    }

    /**
     * Validates numeric ranges, relational constraints, and keyword JSON file shape (TS-13).
     *
     * @throws IllegalArgumentException if any check fails
     */
    public void validate() {
        require(nCrawlers >= 1, "crawler.nCrawlers", ">= 1");
        require(frontierPollMs >= 50 && frontierPollMs <= 5000, "crawler.frontier.pollMs", "50..5000");
        require(frontierLeaseSeconds >= 10 && frontierLeaseSeconds <= 900, "crawler.frontier.leaseSeconds", "10..900");
        require(
                frontierLeaseRecoveryBatchSize >= 1 && frontierLeaseRecoveryBatchSize <= 1000,
                "crawler.frontier.leaseRecoveryBatchSize",
                "1..1000");
        require(
                frontierStartupLeaseRecoveryBatchSize >= 1 && frontierStartupLeaseRecoveryBatchSize <= 5000,
                "crawler.frontier.startupLeaseRecoveryBatchSize",
                "1..5000");
        require(
                frontierTerminationGraceMs >= 0 && frontierTerminationGraceMs <= 60_000,
                "crawler.frontier.terminationGraceMs",
                "0..60000");
        require(fetchConnectTimeoutMs >= 100, "crawler.fetch.connectTimeoutMs", ">= 100");
        require(fetchReadTimeoutMs >= 1000, "crawler.fetch.readTimeoutMs", ">= 1000");
        require(fetchMaxHeadlessSessions >= 1, "crawler.fetch.maxHeadlessSessions", ">= 1");
        require(
                fetchHeadlessAcquireTimeoutMs >= 100 && fetchHeadlessAcquireTimeoutMs <= 30_000,
                "crawler.fetch.headlessAcquireTimeoutMs",
                "100..30000");
        require(fetchHeadlessCircuitOpenThreshold >= 1, "crawler.fetch.headlessCircuitOpenThreshold", ">= 1");
        require(fetchMaxRedirects >= 0 && fetchMaxRedirects <= 20, "crawler.fetch.maxRedirects", "0..20");
        require(
                healthHeartbeatIntervalMs >= 5000 && healthHeartbeatIntervalMs <= 300_000,
                "crawler.health.heartbeatIntervalMs",
                "5000..300000");
        require(rateLimitMinDelayMs >= 5000, "crawler.rateLimit.minDelayMs", ">= 5000");
        require(rateLimitMaxBackoffMs >= 5000, "crawler.rateLimit.maxBackoffMs", ">= 5000");
        require(robotsCacheTtlHours >= 1, "crawler.robots.cacheTtlHours", ">= 1");
        require(robotsCacheMaxEntries >= 100, "crawler.robots.cacheMaxEntries", ">= 100");
        require(
                robotsTemporaryDenyMaxMinutes >= 1 && robotsTemporaryDenyMaxMinutes <= 120,
                "crawler.robots.temporaryDenyMaxMinutes",
                "1..120");
        require(
                robotsTemporaryDenyRetryMinutes >= 1 && robotsTemporaryDenyRetryMinutes <= 60,
                "crawler.robots.temporaryDenyRetryMinutes",
                "1..60");
        // Retry cadence must fit within the max deny window (TS-13 startup consistency).
        require(
                robotsTemporaryDenyRetryMinutes <= robotsTemporaryDenyMaxMinutes,
                "crawler.robots.temporaryDenyRetryMinutes",
                "must be <= crawler.robots.temporaryDenyMaxMinutes");
        require(bucketsCacheTtlHours >= 1, "crawler.buckets.cacheTtlHours", ">= 1");
        require(bucketsCacheMaxEntries >= 100, "crawler.buckets.cacheMaxEntries", ">= 100");
        require(retryJitterMs >= 0 && retryJitterMs <= 10_000, "crawler.retry.jitterMs", "0..10000");
        require(recoveryPathMaxAttempts >= 1 && recoveryPathMaxAttempts <= 10, "crawler.recoveryPath.maxAttempts", "1..10");
        require(
                recoveryPathBaseBackoffMs >= 10 && recoveryPathBaseBackoffMs <= 5000,
                "crawler.recoveryPath.baseBackoffMs",
                "10..5000");
        require(
                retryMaxAttemptsFetchTimeout >= 0 && retryMaxAttemptsFetchTimeout <= 20,
                "crawler.retry.maxAttempts.fetchTimeout",
                "0..20");
        require(
                retryMaxAttemptsFetchOverload >= 0 && retryMaxAttemptsFetchOverload <= 20,
                "crawler.retry.maxAttempts.fetchOverload",
                "0..20");
        require(
                retryMaxAttemptsFetchCapacity >= 0 && retryMaxAttemptsFetchCapacity <= 20,
                "crawler.retry.maxAttempts.fetchCapacity",
                "0..20");
        require(
                retryMaxAttemptsDbTransient >= 0 && retryMaxAttemptsDbTransient <= 20,
                "crawler.retry.maxAttempts.dbTransient",
                "0..20");
        require(budgetMaxTotalPages >= 1, "crawler.budget.maxTotalPages", ">= 1");
        require(budgetMaxFrontierRows >= 100, "crawler.budget.maxFrontierRows", ">= 100");
        require(dbPoolSize >= 2, "crawler.db.poolSize", ">= 2");
        require(fetchMaxHeadlessSessions <= nCrawlers, "crawler.fetch.maxHeadlessSessions", "must be <= crawler.nCrawlers");
        require(dbPoolSize >= nCrawlers + 1, "crawler.db.poolSize", "must be >= crawler.nCrawlers + 1");

        try {
            ScoringKeywordConfigValidator.validate(scoringKeywordConfig);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "crawler.scoring.keywordConfig: cannot read " + scoringKeywordConfig.toAbsolutePath(), e);
        }
    }

    private static void require(boolean ok, String key, String range) {
        if (!ok) {
            ConfigRemediation.Remediation r = ConfigRemediation.invalidConfigValue(key, range);
            throw new IllegalArgumentException(
                    "Invalid configuration: " + key + " (" + range + "). remediationHint=" + r.remediationHint());
        }
    }
}
