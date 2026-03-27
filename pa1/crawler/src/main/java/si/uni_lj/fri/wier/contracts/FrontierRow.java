package si.uni_lj.fri.wier.contracts;

import java.time.Instant;

/**
 * Row returned from an atomic frontier claim ({@code UPDATE ... RETURNING}), including scheduling fields
 * needed to build {@link FetchContext#attempt} and retry policy decisions (TS-07 / TS-01).
 */
public record FrontierRow(
        long pageId, String url, long siteId, double relevanceScore, int attemptCount, Instant nextAttemptAt) {}
