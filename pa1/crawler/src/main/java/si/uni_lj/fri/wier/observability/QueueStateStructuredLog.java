/*
 * TS-15 structured queue-state logs: claim, lease recovery, with fromState/toState and scheduling fields.
 *
 * Callers: {@link si.uni_lj.fri.wier.storage.frontier.FrontierStore},
 * {@link si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository}.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.observability;

import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import si.uni_lj.fri.wier.contracts.FrontierRow;

/**
 * Emits {@link StructuredCrawlerLog#EVENT_QUEUE_STATE_TRANSITION} lines with TS-15 queue-state fields.
 */
public final class QueueStateStructuredLog {

    public static final String STATE_FRONTIER = "FRONTIER";
    public static final String STATE_PROCESSING = "PROCESSING";

    private QueueStateStructuredLog() {}

    /**
     * Best-effort host for {@code domain=} when only a URL string is available (invalid URLs log empty domain).
     */
    public static String safeDomainForLog(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI u = URI.create(url.trim());
            String host = u.getHost();
            return host != null ? host.toLowerCase(Locale.ROOT) : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Logs {@code FRONTIER → PROCESSING} claim with row snapshot fields (attemptCount, nextAttemptAt) after the
     * atomic claim returns.
     */
    public static void logFrontierClaim(Logger log, String workerId, FrontierRow row) {
        if (!log.isInfoEnabled()) {
            return;
        }
        String domain = safeDomainForLog(row.url());
        log.info(
                "event={} transition=CLAIM result=OK fromState={} toState={} workerId={} pageId={} url={} domain={}"
                        + " attemptCount={} parserRetryCount={} nextAttemptAt={}",
                StructuredCrawlerLog.EVENT_QUEUE_STATE_TRANSITION,
                STATE_FRONTIER,
                STATE_PROCESSING,
                workerId,
                row.pageId(),
                row.url(),
                domain,
                row.attemptCount(),
                row.parserRetryCount(),
                row.nextAttemptAt());
    }

    /**
     * Logs a stale-lease recovery batch ({@code PROCESSING → FRONTIER}). Per-row {@code attemptCount} /
     * {@code nextAttemptAt} vary; SQL sets {@code next_attempt_at = now()} for recovered rows.
     */
    public static void logLeaseRecoveryBatch(
            Logger log, int rowsRecovered, String reason, String recovererIdentity) {
        if (!log.isInfoEnabled() || rowsRecovered <= 0) {
            return;
        }
        log.info(
                "event={} transition=LEASE_RECOVERY result=OK fromState={} toState={} rowsRecovered={}"
                        + " nextAttemptAt=now attemptCount=n/a workerId={} reason={}",
                StructuredCrawlerLog.EVENT_QUEUE_STATE_TRANSITION,
                STATE_PROCESSING,
                STATE_FRONTIER,
                rowsRecovered,
                recovererIdentity,
                reason);
    }
}
