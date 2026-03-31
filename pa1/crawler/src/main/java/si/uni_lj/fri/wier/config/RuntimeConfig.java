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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import si.uni_lj.fri.wier.downloader.extract.KeywordRelevanceScorer;
import si.uni_lj.fri.wier.downloader.fetch.UrlPathSuffixHtmlPolicy;

/**
 * Runtime configuration (TS-13). Single-domain crawl: no per-domain page cap — use {@code
 * budgetMaxTotalPages} only.
 *
 * <p>{@link #validate()} must run before database access that depends on bounded frontier/recovery settings;
 * it also validates {@code crawler.scoring.keywordConfig} JSON on the filesystem (relative paths use the JVM
 * working directory).
 *
 * <p>TS-02: {@code crawler.seedUrls} is a comma-separated list of HTTP(S) bootstrap URLs (no CLI override;
 * use env {@code CRAWLER_SEEDURLS} via overlay). At least one URL is required after trim.
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
        boolean discoveryBlockGithubTopicsPaths,
        CrawlScope crawlScope,
        List<String> seedUrls,
        Path scoringKeywordConfig,
        double scoringPrimaryWeight,
        double scoringSecondaryWeight,
        int scoringMaxOccurrencesPerKeyword,
        double scoringSeedRelevanceScore,
        String dbUrl,
        String dbUser,
        String dbPassword,
        int dbPoolSize,
        String dbExpectedSchemaVersion,
        List<String> fetchDenyPathPostfixes) {

    public RuntimeConfig {
        seedUrls = seedUrls == null ? List.of() : List.copyOf(seedUrls);
        fetchDenyPathPostfixes =
                fetchDenyPathPostfixes == null ? List.of() : List.copyOf(fetchDenyPathPostfixes);
    }

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
                parseBoolean(p, "crawler.discovery.blockGithubTopicsPaths", false),
                CrawlScopes.parseCrawlScope(p.getProperty("crawler.crawlScope")),
                parseSeedUrls(Objects.requireNonNull(p.getProperty("crawler.seedUrls"), "crawler.seedUrls")),
                Path.of(Objects.requireNonNull(p.getProperty("crawler.scoring.keywordConfig"), "crawler.scoring.keywordConfig")),
                parseDouble(p, "crawler.scoring.primaryWeight", KeywordRelevanceScorer.DEFAULT_PRIMARY_WEIGHT),
                parseDouble(p, "crawler.scoring.secondaryWeight", KeywordRelevanceScorer.DEFAULT_SECONDARY_WEIGHT),
                parseInt(
                        p,
                        "crawler.scoring.maxOccurrencesPerKeyword",
                        KeywordRelevanceScorer.DEFAULT_MAX_OCCURRENCES_PER_KEYWORD),
                parseDouble(p, "crawler.scoring.seedRelevanceScore", 1000.0),
                Objects.requireNonNull(p.getProperty("crawler.db.url"), "crawler.db.url"),
                Objects.requireNonNull(p.getProperty("crawler.db.user"), "crawler.db.user"),
                Objects.requireNonNull(p.getProperty("crawler.db.password"), "crawler.db.password"),
                parseInt(p, "crawler.db.poolSize", Math.min(n + 2, 20)),
                Objects.requireNonNull(
                        p.getProperty("crawler.db.expectedSchemaVersion"),
                        "crawler.db.expectedSchemaVersion"),
                parseFetchDenyPathPostfixes(p));
    }

    private static int parseInt(Properties p, String key, int defaultValue) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(v.trim());
    }

    private static double parseDouble(Properties p, String key, double defaultValue) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(v.trim());
    }

    private static boolean parseBoolean(Properties p, String key, boolean defaultValue) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        String t = v.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default ->
                    throw new IllegalArgumentException(
                            "Invalid boolean for " + key + ": " + v + " (use true/false, yes/no, or 1/0)");
        };
    }

    /**
     * Splits comma-separated seed URLs; trims each segment; drops empties. Property value must be non-null
     * (caller uses {@code requireNonNull} before invoke).
     */
    private static List<String> parseSeedUrls(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Comma-separated last-segment suffixes (with or without leading dot) that force BINARY when Content-Type is
     * {@code text/html}. Null/blank property defaults to {@link UrlPathSuffixHtmlPolicy#DEFAULT_DENY_PATH_POSTFIXES}.
     * A non-blank value that parses to no tokens (e.g. only commas) yields an empty list and disables path-based
     * forcing.
     */
    private static List<String> parseFetchDenyPathPostfixes(Properties p) {
        String raw = p.getProperty("crawler.fetch.denyPathPostfixes");
        if (raw == null || raw.isBlank()) {
            return List.copyOf(UrlPathSuffixHtmlPolicy.DEFAULT_DENY_PATH_POSTFIXES);
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (t.startsWith(".")) {
                t = t.substring(1);
            }
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        if (out.isEmpty()) {
            return List.of();
        }
        return List.copyOf(out);
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
        for (String sfx : fetchDenyPathPostfixes) {
            require(
                    sfx.length() >= 1 && sfx.length() <= 32,
                    "crawler.fetch.denyPathPostfixes",
                    "each suffix length 1..32");
            boolean alphanumeric =
                    sfx.chars()
                            .allMatch(
                                    c ->
                                            (c >= 'a' && c <= 'z')
                                                    || (c >= '0' && c <= '9'));
            require(
                    alphanumeric,
                    "crawler.fetch.denyPathPostfixes",
                    "each suffix must be [a-z0-9]+ only");
        }
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
        Objects.requireNonNull(crawlScope, "crawlScope");
        require(!seedUrls.isEmpty(), "crawler.seedUrls", "at least one non-blank URL after comma-split");
        for (String u : seedUrls) {
            String lower = u.toLowerCase(Locale.ROOT);
            require(
                    lower.startsWith("http://") || lower.startsWith("https://"),
                    "crawler.seedUrls",
                    "each URL must use http:// or https://");
        }
        require(dbPoolSize >= 2, "crawler.db.poolSize", ">= 2");
        require(fetchMaxHeadlessSessions <= nCrawlers, "crawler.fetch.maxHeadlessSessions", "must be <= crawler.nCrawlers");
        require(dbPoolSize >= nCrawlers + 1, "crawler.db.poolSize", "must be >= crawler.nCrawlers + 1");

        try {
            ScoringKeywordConfigValidator.validate(scoringKeywordConfig);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "crawler.scoring.keywordConfig: cannot read " + scoringKeywordConfig.toAbsolutePath(), e);
        }
        require(scoringPrimaryWeight > 0.0 && scoringPrimaryWeight <= 10.0, "crawler.scoring.primaryWeight", "(0, 10]");
        require(
                scoringSecondaryWeight > 0.0 && scoringSecondaryWeight <= 10.0,
                "crawler.scoring.secondaryWeight",
                "(0, 10]");
        require(
                scoringMaxOccurrencesPerKeyword >= 1 && scoringMaxOccurrencesPerKeyword <= 4096,
                "crawler.scoring.maxOccurrencesPerKeyword",
                "1..4096");
        require(scoringSeedRelevanceScore > 0.0, "crawler.scoring.seedRelevanceScore", "> 0");
        try {
            KeywordRelevanceScorer.KeywordTierCounts tiers = KeywordRelevanceScorer.readTierCounts(scoringKeywordConfig);
            double maxKeyword =
                    tiers.maxKeywordScore(
                            scoringPrimaryWeight,
                            scoringSecondaryWeight,
                            scoringMaxOccurrencesPerKeyword);
            // Strictly greater so no discovered URL ties or beats seeds under capped occurrence scoring.
            if (!(scoringSeedRelevanceScore > maxKeyword)) {
                throw new IllegalArgumentException(
                        "Invalid configuration: crawler.scoring.seedRelevanceScore must be strictly greater than "
                                + "the maximum possible keyword score ("
                                + maxKeyword
                                + " = ("
                                + tiers.primaryCount()
                                + " primary * "
                                + scoringPrimaryWeight
                                + " + "
                                + tiers.secondaryCount()
                                + " secondary * "
                                + scoringSecondaryWeight
                                + ") * crawler.scoring.maxOccurrencesPerKeyword="
                                + scoringMaxOccurrencesPerKeyword
                                + "). remediationHint=Raise crawler.scoring.seedRelevanceScore, or reduce weights / "
                                + "keyword list size / crawler.scoring.maxOccurrencesPerKeyword per "
                                + "crawler.scoring.keywordConfig.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "crawler.scoring.keywordConfig: cannot read for tier counts "
                            + scoringKeywordConfig.toAbsolutePath(),
                    e);
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
