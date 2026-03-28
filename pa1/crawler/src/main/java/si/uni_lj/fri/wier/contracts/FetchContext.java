package si.uni_lj.fri.wier.contracts;

import java.time.Instant;

/**
 * Stage B persistence hand-off for one claimed page (TS-02 / TS-10).
 *
 * <p>{@link #attempt} mirrors {@link FrontierRow#attemptCount()} at claim time for observability; retry
 * counters are advanced on reschedule SQL, not here.
 */
public record FetchContext(
        long pageId, String canonicalUrl, long siteId, int attempt, Instant claimedAt) {

    /**
     * Builds context from a row returned by an atomic frontier claim (TS-07).
     *
     * @param row non-null claimed frontier row with lease timestamps
     */
    public static FetchContext fromClaimedRow(FrontierRow row) {
        return new FetchContext(
                row.pageId(), row.url(), row.siteId(), row.attemptCount(), row.claimedAt());
    }
}
