/*
 * TS-02 bounded retries for durable queue transitions after processing failures (reschedule / terminal error).
 *
 * Callers: {@link si.uni_lj.fri.wier.downloader.worker.WorkerLoop} wraps {@link ProcessingFailureHandler} and
 * direct {@link si.uni_lj.fri.wier.contracts.Storage#markPageAsError} / {@link si.uni_lj.fri.wier.contracts.Frontier#reschedule}.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.error;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import si.uni_lj.fri.wier.config.RuntimeConfig;

/**
 * Retries transient {@code RuntimeException} outcomes from recovery-path JDBC calls with exponential backoff
 * and jitter; logs lease context on exhaustion (TS-07 stale-lease fallback remains authoritative).
 */
public final class RecoveryPathExecutor {

    private final RuntimeConfig config;
    private final Logger log;

    public RecoveryPathExecutor(RuntimeConfig config, Logger log) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * Runs {@code action} once or retries on failure up to {@link RuntimeConfig#recoveryPathMaxAttempts()}.
     *
     * @param workerId lease owner for logs
     * @param pageId claimed page id
     * @param claimExpiresAt lease upper bound for logs (may be null)
     * @param category error bucket for logs
     * @param action runnable that performs reschedule or markPageAsError
     */
    public void runWithRetries(
            String workerId,
            long pageId,
            java.time.Instant claimExpiresAt,
            String category,
            Runnable action) {
        int max = config.recoveryPathMaxAttempts();
        long baseMs = config.recoveryPathBaseBackoffMs();
        for (int attempt = 0; attempt < max; attempt++) {
            try {
                action.run();
                return;
            } catch (RuntimeException e) {
                if (attempt == max - 1) {
                    log.error(
                            "recoveryPathExhausted workerId={} pageId={} category={} claimExpiresAt={} attempts={} msg={}",
                            workerId,
                            pageId,
                            category,
                            claimExpiresAt,
                            max,
                            e.getMessage(),
                            e);
                    return;
                }
                sleepBackoff(attempt, baseMs);
            }
        }
    }

    private void sleepBackoff(int attemptIndex, long baseMs) {
        long exp = baseMs * (1L << attemptIndex);
        long cap = Math.min(exp, 30_000L);
        int jitter = config.retryJitterMs() <= 0 ? 0 : ThreadLocalRandom.current().nextInt(config.retryJitterMs() + 1);
        long sleepMs = Math.min(cap + jitter, 60_000L);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("recovery path sleep interrupted", ie);
        }
    }
}
