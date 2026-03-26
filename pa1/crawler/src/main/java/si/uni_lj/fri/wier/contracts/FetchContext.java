package si.uni_lj.fri.wier.contracts;

import java.time.Instant;

public record FetchContext(
        long pageId, String canonicalUrl, long siteId, int attempt, Instant claimedAt) {}
