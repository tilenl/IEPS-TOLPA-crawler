package si.uni_lj.fri.wier.queue.enqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.ConfigRemediation;
import si.uni_lj.fri.wier.observability.CrawlerMetrics;

/**
 * Stage A ingestion logging. When budget or frontier limits apply, logs MUST include {@code configKey}
 * and {@code remediationHint} (TS-15).
 *
 * <p>Robots-aware frontier insertion is implemented by {@link EnqueueCoordinator} (TS-06). TS-02 should call it
 * (or equivalent) when wiring discovery into the DB; {@link si.uni_lj.fri.wier.cli.Main} retains a shared
 * {@link si.uni_lj.fri.wier.downloader.politeness.PolitenessGate} for that integration.
 */
public final class EnqueueService {

    private static final Logger log = LoggerFactory.getLogger(EnqueueService.class);

    private final CrawlerMetrics metrics;

    public EnqueueService() {
        this(null);
    }

    public EnqueueService(CrawlerMetrics metrics) {
        this.metrics = metrics;
    }

    public void logBudgetDropped(String url, String domain) {
        if (metrics != null) {
            metrics.recordBudgetDropped();
        }
        ConfigRemediation.Remediation r = ConfigRemediation.budgetTotalPagesDropped();
        log.warn(
                "event=BUDGET_DROPPED result=REJECTED workerId=ingestion pageId=0 url={} domain={} configKey={}"
                        + " remediationHint={}",
                url,
                domain,
                r.configKey(),
                r.remediationHint());
    }

    public void logFrontierDeferred(String url, String domain) {
        if (metrics != null) {
            metrics.recordFrontierDeferred();
        }
        ConfigRemediation.Remediation r = ConfigRemediation.frontierHighWatermarkDeferred();
        log.warn(
                "event=FRONTIER_DEFERRED result=DEFERRED workerId=ingestion pageId=0 url={} domain={} configKey={}"
                        + " remediationHint={}",
                url,
                domain,
                r.configKey(),
                r.remediationHint());
    }

    /**
     * TS-15: successful score-based FRONTIER replacement (TS-02 / TS-13). {@code triggerConfigKeys} names which cap(s)
     * required admission via eviction (for example {@code crawler.budget.maxTotalPages}).
     */
    public void logFrontierEvictedForScore(
            String victimUrl,
            double victimScore,
            String newUrl,
            double newScore,
            String triggerConfigKeys,
            String domain) {
        if (metrics != null) {
            metrics.recordFrontierEvictedForScore();
        }
        ConfigRemediation.Remediation r = ConfigRemediation.frontierEvictedForScore();
        log.info(
                "event=FRONTIER_EVICTED_FOR_SCORE result=SWAPPED workerId=ingestion pageId=0 victimUrl={}"
                        + " victimScore={} newUrl={} newScore={} triggerConfigKeys={} domain={} configKey={}"
                        + " remediationHint={}",
                victimUrl,
                victimScore,
                newUrl,
                newScore,
                triggerConfigKeys,
                domain,
                r.configKey(),
                r.remediationHint());
    }

    /** TS-02 / TS-13: at {@code maxFrontierRows} and discovery does not beat the worst FRONTIER score. */
    public void logFrontierFullLowScore(String url, String domain) {
        if (metrics != null) {
            metrics.recordFrontierFullLowScore();
        }
        ConfigRemediation.Remediation r = ConfigRemediation.frontierFullLowScore();
        log.warn(
                "event=FRONTIER_FULL_LOW_SCORE result=REJECTED workerId=ingestion pageId=0 url={} domain={}"
                        + " configKey={} remediationHint={}",
                url,
                domain,
                r.configKey(),
                r.remediationHint());
    }
}
