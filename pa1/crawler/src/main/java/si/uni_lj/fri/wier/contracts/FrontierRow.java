package si.uni_lj.fri.wier.contracts;

import java.time.Instant;

/**
 * Row returned from an atomic frontier claim ({@code UPDATE ... RETURNING}), including scheduling fields
 * needed to build {@link FetchContext#attempt} and TS-12 recovery decisions (TS-07 / TS-01).
 *
 * <p>{@code parserRetryCount} counts completed parser-stage reschedules persisted on the page row; it is
 * independent of {@code attemptCount} so fetch-stage retries do not consume the parser retry budget.
 *
 * <p>{@code claimedAt} and {@code claimExpiresAt} are set by the atomic claim (TS-07) and feed
 * {@link si.uni_lj.fri.wier.contracts.FetchRequest#claimExpiresAt()} for lease-margin fetches (TS-03).
 */
public record FrontierRow(
        long pageId,
        String url,
        long siteId,
        double relevanceScore,
        int attemptCount,
        int parserRetryCount,
        Instant nextAttemptAt,
        Instant claimedAt,
        Instant claimExpiresAt) {}
