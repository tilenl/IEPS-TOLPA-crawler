package si.uni_lj.fri.wier.contracts;

import java.time.Instant;

/**
 * Normalized fetch payload for Stage B persistence (TS-01 / TS-03).
 *
 * <p>Storage ({@link Storage#persistFetchOutcomeWithLinks}) uses {@code statusCode}, {@code contentType},
 * {@code body}, and {@code fetchedAt}. {@link #fetchMode()} and {@link #finalUrlAfterRedirects()} are
 * diagnostics and TS-03 traceability; they do not change dedup keys (claimed URL remains authoritative).
 */
public record FetchResult(
        int statusCode,
        String contentType,
        String body,
        Instant fetchedAt,
        FetchMode fetchMode,
        String finalUrlAfterRedirects) {

    /** TS-01 legacy constructor: plain HTTP, no redirect diagnostic. */
    public FetchResult(int statusCode, String contentType, String body, Instant fetchedAt) {
        this(statusCode, contentType, body, fetchedAt, FetchMode.PLAIN_HTTP, null);
    }

    public FetchResult {
        if (fetchedAt == null) {
            throw new IllegalArgumentException("fetchedAt must be non-null");
        }
    }
}
