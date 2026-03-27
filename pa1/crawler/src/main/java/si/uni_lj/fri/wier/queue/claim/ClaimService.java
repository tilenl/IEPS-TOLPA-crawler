package si.uni_lj.fri.wier.queue.claim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.storage.frontier.FrontierStore;

/**
 * TS-07 frontier lifecycle helpers: startup reclaim runs before workers may claim work, draining stale
 * {@code PROCESSING} leases in bounded batches until none remain.
 *
 * <p>Claim orchestration for steady-state workers lives on {@link FrontierStore} (pre-claim recovery +
 * atomic claim). {@link si.uni_lj.fri.wier.cli.Main} invokes {@link #runStartupLeaseRecovery} once after
 * schema validation.
 *
 * <p>Created for IEPS crawler; change log: initial TS-07 startup recovery hook.
 */
public final class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    private ClaimService() {}

    /**
     * Reclaims expired {@code PROCESSING} leases using {@link FrontierStore#recoverExpiredLeases} in
     * batches of {@code batchSize} until a batch returns zero rows.
     *
     * @param store frontier store backed by the application {@link javax.sql.DataSource}
     * @param batchSize upper bound per round-trip (maps to {@code crawler.frontier.startupLeaseRecoveryBatchSize})
     * @param reason persisted on recovered rows for diagnostics (SQL {@code last_error_message})
     * @param recovererIdentity stable identity for application logs (TS-07 worker identity; e.g. {@code "startup"})
     */
    public static void runStartupLeaseRecovery(
            FrontierStore store, int batchSize, String reason, String recovererIdentity) {
        int cap = Math.max(1, batchSize);
        // Each iteration touches at most cap rows; loop until the backlog of stale leases is empty.
        while (true) {
            int recovered = store.recoverExpiredLeases(cap, reason, recovererIdentity);
            if (recovered == 0) {
                log.info("startup lease recovery finished recoverer={}", recovererIdentity);
                return;
            }
            // Per-batch details are logged in PageRepository.recoverExpiredLeases.
        }
    }
}
