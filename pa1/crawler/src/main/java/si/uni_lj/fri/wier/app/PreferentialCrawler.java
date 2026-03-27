/*
 * Application shell: effective TS-13 config snapshot after validation (no secrets).
 *
 * Callers: Main. Owned by TS-13 / TS-02.
 *
 * Created: 2026-03. Major revisions: four-line effective snapshot per TS-13.
 */

package si.uni_lj.fri.wier.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.RuntimeConfig;

/**
 * Preflight logging for merged runtime configuration (TS-13). Passwords and DB user are never logged; JDBC
 * URL is reduced to host/port/database style when possible.
 */
public final class PreferentialCrawler {

    private static final Logger log = LoggerFactory.getLogger(PreferentialCrawler.class);

    private final RuntimeConfig config;

    public PreferentialCrawler(RuntimeConfig config) {
        this.config = config;
    }

    /**
     * Logs effective config without secrets (TS-13). Callers must invoke {@link RuntimeConfig#validate()}
     * before any database access that depends on validated bounds; {@link si.uni_lj.fri.wier.cli.Main} does so
     * before constructing the datasource and startup lease recovery.
     */
    public void preflightAndLogEffectiveConfig() {
        log.info(
                "effectiveConfig workersFrontierDb nCrawlers={} frontierPollMs={} frontierLeaseSeconds={}"
                        + " frontierLeaseRecoveryBatchSize={} frontierStartupLeaseRecoveryBatchSize={}"
                        + " frontierTerminationGraceMs={} dbPoolSize={} expectedSchemaVersion={}",
                config.nCrawlers(),
                config.frontierPollMs(),
                config.frontierLeaseSeconds(),
                config.frontierLeaseRecoveryBatchSize(),
                config.frontierStartupLeaseRecoveryBatchSize(),
                config.frontierTerminationGraceMs(),
                config.dbPoolSize(),
                config.dbExpectedSchemaVersion());
        log.info(
                "effectiveConfig fetchRateLimit connectTimeoutMs={} readTimeoutMs={} maxHeadlessSessions={}"
                        + " headlessAcquireTimeoutMs={} headlessCircuitOpenThreshold={} maxRedirects={}"
                        + " rateLimitMinDelayMs={} rateLimitMaxBackoffMs={} jdbcUrl={}",
                config.fetchConnectTimeoutMs(),
                config.fetchReadTimeoutMs(),
                config.fetchMaxHeadlessSessions(),
                config.fetchHeadlessAcquireTimeoutMs(),
                config.fetchHeadlessCircuitOpenThreshold(),
                config.fetchMaxRedirects(),
                config.rateLimitMinDelayMs(),
                config.rateLimitMaxBackoffMs(),
                safeJdbcUrlForLog(config.dbUrl()));
        log.info(
                "effectiveConfig retryBudget jitterMs={} recoveryPathMaxAttempts={} recoveryPathBaseBackoffMs={}"
                        + " retryFetchTimeout={} retryFetchOverload={} retryFetchCapacity={} retryDbTransient={}"
                        + " budgetMaxTotalPages={} budgetMaxFrontierRows={}",
                config.retryJitterMs(),
                config.recoveryPathMaxAttempts(),
                config.recoveryPathBaseBackoffMs(),
                config.retryMaxAttemptsFetchTimeout(),
                config.retryMaxAttemptsFetchOverload(),
                config.retryMaxAttemptsFetchCapacity(),
                config.retryMaxAttemptsDbTransient(),
                config.budgetMaxTotalPages(),
                config.budgetMaxFrontierRows());
        log.info(
                "effectiveConfig robotsBucketsHealthScoring robotsCacheTtlHours={} robotsCacheMaxEntries={}"
                        + " temporaryDenyMaxMinutes={} temporaryDenyRetryMinutes={} bucketsCacheTtlHours={}"
                        + " bucketsCacheMaxEntries={} healthHeartbeatIntervalMs={} scoringKeywordConfigPath={}",
                config.robotsCacheTtlHours(),
                config.robotsCacheMaxEntries(),
                config.robotsTemporaryDenyMaxMinutes(),
                config.robotsTemporaryDenyRetryMinutes(),
                config.bucketsCacheTtlHours(),
                config.bucketsCacheMaxEntries(),
                config.healthHeartbeatIntervalMs(),
                config.scoringKeywordConfig());
    }

    /**
     * Strips user/password from JDBC URL for logs (TS-13); falls back to {@code [redacted]} if parsing fails.
     */
    public static String safeJdbcUrlForLog(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "";
        }
        // postgresql://user:pass@host:port/db — hide credentials
        int idx = jdbcUrl.indexOf("://");
        if (idx < 0) {
            return "[redacted]";
        }
        String rest = jdbcUrl.substring(idx + 3);
        int at = rest.lastIndexOf('@');
        if (at > 0) {
            rest = rest.substring(at + 1);
        }
        return "jdbc:postgresql://" + rest;
    }

    public RuntimeConfig config() {
        return config;
    }
}
