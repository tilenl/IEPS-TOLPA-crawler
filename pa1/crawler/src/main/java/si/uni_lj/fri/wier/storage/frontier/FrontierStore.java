package si.uni_lj.fri.wier.storage.frontier;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.error.CrawlerErrorCategory;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Frontier-specific storage facade.
 *
 * <p>This class keeps claim/recover/reschedule operations scoped to frontier ownership while
 * delegating SQL execution to {@link PageRepository}. Per TS-07, each claim path runs a bounded
 * stale-lease recovery batch before selecting the next candidate.
 */
public final class FrontierStore {
    private static final Logger log = LoggerFactory.getLogger(FrontierStore.class);

    /** Log/diagnostic text for rows reclaimed immediately before a claim attempt. */
    private static final String PRE_CLAIM_RECOVERY_REASON = "stale lease recovery (pre-claim)";

    private final PageRepository pageRepository;

    public FrontierStore(PageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    /**
     * Recovers up to {@code leaseRecoveryBatchSize} stale {@code PROCESSING} rows, then claims the next
     * eligible {@code FRONTIER} row.
     *
     * @param leaseRecoveryBatchSize cap per TS-07 ({@code crawler.frontier.leaseRecoveryBatchSize}); coerced to at least 1
     */
    public Optional<FrontierRow> claimNextEligibleFrontier(
            String workerId, Duration leaseDuration, int leaseRecoveryBatchSize) {
        // Recover before candidate selection so expired leases cannot starve the frontier queue.
        log.debug("pre-claim stale lease recovery batch workerId={}", workerId);
        pageRepository.recoverExpiredLeases(
                Math.max(1, leaseRecoveryBatchSize), PRE_CLAIM_RECOVERY_REASON, workerId);
        return pageRepository.claimNextEligibleFrontier(workerId, leaseDuration);
    }

    public int recoverExpiredLeases(int batchSize, String reason, String recovererIdentity) {
        return pageRepository.recoverExpiredLeases(batchSize, reason, recovererIdentity);
    }

    /**
     * TS-12 reschedule: increments {@code attempt_count} for fetch-stage failures, or {@code
     * parser_retry_count} only for {@link CrawlerErrorCategory#PARSER_FAILURE}.
     */
    public boolean reschedulePage(
            long pageId, Instant nextAttemptAt, String errorCategory, String diagnosticMessage) {
        boolean parserStage =
                CrawlerErrorCategory.PARSER_FAILURE.name().equals(errorCategory);
        return pageRepository.reschedulePage(
                pageId, nextAttemptAt, errorCategory, diagnosticMessage, parserStage);
    }
}
