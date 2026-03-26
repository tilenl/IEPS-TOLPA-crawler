package si.uni_lj.fri.wier.storage.frontier;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import si.uni_lj.fri.wier.contracts.FrontierRow;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/**
 * Frontier-specific storage facade.
 *
 * <p>This class keeps claim/recover/reschedule operations scoped to frontier ownership while
 * delegating SQL execution to {@link PageRepository}.
 */
public final class FrontierStore {
    private final PageRepository pageRepository;

    public FrontierStore(PageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    public Optional<FrontierRow> claimNextEligibleFrontier(String workerId, Duration leaseDuration) {
        return pageRepository.claimNextEligibleFrontier(workerId, leaseDuration);
    }

    public int recoverExpiredLeases(int batchSize, String reason) {
        return pageRepository.recoverExpiredLeases(batchSize, reason);
    }

    public boolean reschedulePage(long pageId, Instant nextAttemptAt, String reason) {
        return pageRepository.reschedulePage(pageId, nextAttemptAt, reason);
    }
}
