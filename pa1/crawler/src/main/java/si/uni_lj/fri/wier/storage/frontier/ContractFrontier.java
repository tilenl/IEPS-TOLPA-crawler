package si.uni_lj.fri.wier.storage.frontier;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import si.uni_lj.fri.wier.contracts.Frontier;
import si.uni_lj.fri.wier.contracts.FrontierRow;

/**
 * TS-01 frontier contract facade backed by the TS-07 frontier storage services.
 *
 * <p>This adapter keeps worker orchestration dependent on the {@link Frontier} contract while
 * delegating claim and reschedule SQL behavior to {@link FrontierStore}.
 */
public final class ContractFrontier implements Frontier {
    public interface Delegate {
        Optional<FrontierRow> claim(String workerId, Duration leaseDuration);

        boolean reschedule(long pageId, Instant nextAttemptAt, String reason);
    }

    private final Delegate delegate;
    private final String workerId;
    private final Duration leaseDuration;

    public ContractFrontier(FrontierStore frontierStore, String workerId, Duration leaseDuration) {
        this(
                new Delegate() {
                    @Override
                    public Optional<FrontierRow> claim(String workerId, Duration leaseDuration) {
                        return frontierStore.claimNextEligibleFrontier(workerId, leaseDuration);
                    }

                    @Override
                    public boolean reschedule(long pageId, Instant nextAttemptAt, String reason) {
                        return frontierStore.reschedulePage(pageId, nextAttemptAt, reason);
                    }
                },
                workerId,
                leaseDuration);
    }

    public ContractFrontier(Delegate delegate, String workerId, Duration leaseDuration) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
    }

    @Override
    public Optional<FrontierRow> claimNextFrontier() {
        return delegate.claim(workerId, leaseDuration);
    }

    @Override
    public void reschedule(long pageId, Instant nextAttemptAt, String reason) {
        boolean updated = delegate.reschedule(pageId, nextAttemptAt, reason);
        if (!updated) {
            throw new IllegalStateException("Failed to reschedule missing pageId=" + pageId);
        }
    }
}
