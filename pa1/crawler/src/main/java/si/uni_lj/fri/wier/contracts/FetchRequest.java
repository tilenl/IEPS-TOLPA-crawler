package si.uni_lj.fri.wier.contracts;

import java.time.Instant;
import java.util.Objects;

/**
 * Execution context for one leased fetch (TS-03 / TS-08).
 *
 * <p>{@link #canonicalUrl()} is the frontier claimed URL; redirect hops use the same politeness gates per hop
 * but stored page rows remain keyed by this URL. {@link #firstHopRateLimitSatisfied()} avoids double-consuming
 * the per-domain token when the TS-02 worker has already passed the outer {@code tryAcquire} for this claim.
 *
 * @param canonicalUrl claimed canonical URL (non-blank)
 * @param workerId stable worker id for logs (TS-14)
 * @param claimExpiresAt lease upper bound; used with {@link si.uni_lj.fri.wier.config.RuntimeConfig#frontierLeaseSeconds()}
 *     to reserve the last 25% of the lease for persist when deciding whether mid-chain politeness may block
 * @param firstHopRateLimitSatisfied when true, {@link si.uni_lj.fri.wier.downloader.fetch.HttpFetcher} skips
 *     {@code tryAcquire} on the first hop only (outer gate already consumed the token)
 */
public record FetchRequest(
        String canonicalUrl,
        String workerId,
        Instant claimExpiresAt,
        boolean firstHopRateLimitSatisfied) {

    public FetchRequest {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt");
        if (canonicalUrl.isBlank()) {
            throw new IllegalArgumentException("canonicalUrl must be non-blank");
        }
    }
}
