package si.uni_lj.fri.wier.queue.enqueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.config.ConfigRemediation;

/**
 * Stage A ingestion logging (stub). When budget or frontier limits apply, logs MUST include {@code configKey}
 * and {@code remediationHint} (TS-15).
 *
 * <p>Robots-aware frontier insertion is implemented by {@link EnqueueCoordinator} (TS-06). TS-02 should call it
 * (or equivalent) when wiring discovery into the DB; {@link si.uni_lj.fri.wier.cli.Main} retains a shared
 * {@link si.uni_lj.fri.wier.downloader.politeness.PolitenessGate} for that integration.
 */
public final class EnqueueService {

    private static final Logger log = LoggerFactory.getLogger(EnqueueService.class);

    public void logBudgetDropped(String url, String domain) {
        ConfigRemediation.Remediation r = ConfigRemediation.budgetTotalPagesDropped();
        log.warn(
                "event=BUDGET_DROPPED result=REJECTED url={} domain={} configKey={} remediationHint={}",
                url,
                domain,
                r.configKey(),
                r.remediationHint());
    }

    public void logFrontierDeferred(String url, String domain) {
        ConfigRemediation.Remediation r = ConfigRemediation.frontierHighWatermarkDeferred();
        log.warn(
                "event=FRONTIER_DEFERRED result=DEFERRED url={} domain={} configKey={} remediationHint={}",
                url,
                domain,
                r.configKey(),
                r.remediationHint());
    }
}
