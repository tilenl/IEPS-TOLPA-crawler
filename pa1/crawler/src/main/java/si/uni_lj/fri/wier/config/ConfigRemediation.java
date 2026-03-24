package si.uni_lj.fri.wier.config;

/**
 * Normative {@code configKey} / {@code remediationHint} pairs aligned with TS-13 Parameter-linked
 * diagnostics and TS-15 logging contract. Call sites should log these verbatim (or close paraphrase)
 * on limit-driven events.
 */
public final class ConfigRemediation {

    public record Remediation(String configKey, String remediationHint) {}

    private ConfigRemediation() {}

    public static Remediation budgetTotalPagesDropped() {
        return new Remediation(
                "crawler.budget.maxTotalPages",
                "To crawl more distinct pages in a run, increase crawler.budget.maxTotalPages and ensure"
                        + " database and disk capacity are sufficient for the larger crawl.");
    }

    public static Remediation frontierHighWatermarkDeferred() {
        return new Remediation(
                "crawler.budget.maxFrontierRows",
                "To admit more URLs into the frontier sooner, increase crawler.budget.maxFrontierRows,"
                        + " or reduce discovery breadth via scoring seeds and link policy; very large"
                        + " queues increase memory and DB load.");
    }

    public static Remediation fetchTimeoutExhausted() {
        return new Remediation(
                "crawler.retry.maxAttempts.fetchTimeout",
                "To allow more attempts before giving up, increase crawler.retry.maxAttempts.fetchTimeout;"
                        + " to wait longer per attempt, increase crawler.fetch.connectTimeoutMs or"
                        + " crawler.fetch.readTimeoutMs if appropriate for slow origins.");
    }

    public static Remediation fetchOverloadExhausted() {
        return new Remediation(
                "crawler.retry.maxAttempts.fetchOverload",
                "To tolerate longer overload episodes, increase crawler.retry.maxAttempts.fetchOverload"
                        + " or adjust crawler.rateLimit.maxBackoffMs within policy limits.");
    }

    public static Remediation fetchCapacityExhausted() {
        return new Remediation(
                "crawler.retry.maxAttempts.fetchCapacity",
                "To allow more headless capacity exhaustion retries before terminal handling, increase"
                        + " crawler.retry.maxAttempts.fetchCapacity; to reduce saturation, increase"
                        + " crawler.fetch.maxHeadlessSessions (must remain <= crawler.nCrawlers) or"
                        + " crawler.fetch.headlessAcquireTimeoutMs per TS-13.");
    }

    public static Remediation dbTransientExhausted() {
        return new Remediation(
                "crawler.retry.maxAttempts.dbTransient",
                "To tolerate longer DB outages per row, increase crawler.retry.maxAttempts.dbTransient;"
                        + " persistent failures require infrastructure or schema fixes, not only config.");
    }

    public static Remediation recoveryPathExhausted() {
        return new Remediation(
                "crawler.recoveryPath.maxAttempts",
                "To allow more transient DB retries on state transitions, increase"
                        + " crawler.recoveryPath.maxAttempts or tune crawler.recoveryPath.baseBackoffMs"
                        + " / crawler.retry.jitterMs.");
    }

    public static Remediation rateLimitReschedule() {
        return new Remediation(
                "crawler.rateLimit.minDelayMs",
                "The assignment enforces a minimum delay per domain; lowering below 5000 ms is not valid."
                        + " To change politeness, adjust crawler.rateLimit.minDelayMs (within >=5000) or"
                        + " overload cap crawler.rateLimit.maxBackoffMs.");
    }

    public static Remediation headlessSlotTimeout() {
        return new Remediation(
                "crawler.fetch.maxHeadlessSessions",
                "To reduce timeouts, increase crawler.fetch.maxHeadlessSessions (must remain <="
                        + " crawler.nCrawlers) or increase crawler.fetch.headlessAcquireTimeoutMs;"
                        + " alternatively reduce crawler.nCrawlers to match headless capacity.");
    }

    public static Remediation headlessCircuitOpen() {
        return new Remediation(
                "crawler.fetch.headlessCircuitOpenThreshold",
                "To make the circuit less sensitive, increase crawler.fetch.headlessCircuitOpenThreshold;"
                        + " to reduce saturation events, add headless slots or workers per headless guidance"
                        + " in TS-13.");
    }

    public static Remediation robotsCachePressure() {
        return new Remediation(
                "crawler.robots.cacheTtlHours",
                "To reduce robots refetch churn, increase crawler.robots.cacheTtlHours or"
                        + " crawler.robots.cacheMaxEntries within operational memory limits.");
    }

    public static Remediation robotsTemporaryDeny() {
        return new Remediation(
                "crawler.robots.temporaryDenyMaxMinutes",
                "To shorten deny windows or change retry cadence, adjust"
                        + " crawler.robots.temporaryDenyMaxMinutes and"
                        + " crawler.robots.temporaryDenyRetryMinutes within validated bounds.");
    }

    public static Remediation bucketCacheEviction() {
        return new Remediation(
                "crawler.buckets.cacheMaxEntries",
                "Defaults are assignment-safe per TS-08. If you tightened TTL or max entries and see"
                        + " burst traffic to origins, increase crawler.buckets.cacheMaxEntries or"
                        + " crawler.buckets.cacheTtlHours.");
    }

    public static Remediation schemaVersionMismatch() {
        return new Remediation(
                "crawler.db.expectedSchemaVersion",
                "Align crawler.db.expectedSchemaVersion with the migrated database version, or run"
                        + " migrations to match the configured expectation.");
    }

    public static Remediation jdbcPoolExhaustion() {
        return new Remediation(
                "crawler.db.poolSize",
                "Increase crawler.db.poolSize to at least crawler.nCrawlers + 1, or reduce"
                        + " crawler.nCrawlers to match pool capacity.");
    }

    public static Remediation leaseRecoveryPressure() {
        return new Remediation(
                "crawler.frontier.leaseRecoveryBatchSize",
                "Increase crawler.frontier.leaseRecoveryBatchSize to reclaim more stale leases per"
                        + " cycle, or shorten crawler.frontier.leaseSeconds so stuck leases expire sooner"
                        + " (trade-off with crash recovery latency).");
    }

    public static Remediation terminationGrace() {
        return new Remediation(
                "crawler.frontier.terminationGraceMs",
                "Increase crawler.frontier.terminationGraceMs if the scheduler stops while work still"
                        + " appears; decrease only if intentional faster exit is desired and flapping is"
                        + " acceptable.");
    }

    /** Startup validation: include {@code failedKey} and {@code message} in the log separately. */
    public static Remediation invalidConfigValue(String failedKey, String validRangeDescription) {
        return new Remediation(
                failedKey,
                "Fix the config file, environment variable, or CLI flag for this property; allowed:"
                        + " " + validRangeDescription);
    }
}
