package si.uni_lj.fri.wier.contracts;

import java.util.Collection;
import java.util.Optional;

/**
 * Authoritative persistence contract for Stage B writes.
 *
 * <p>All methods expect canonical URLs and must preserve SQL-level idempotence guarantees.
 */
public interface Storage {
    Optional<Long> ensureSite(String domain);

    PersistOutcome persistFetchOutcomeWithLinks(
            FetchContext context,
            FetchResult result,
            ParseResult parsed,
            Collection<DiscoveredUrl> discovered);

    LinkInsertResult insertLink(long fromPageId, long toPageId);

    IngestResult ingestDiscoveredUrls(Collection<DiscoveredUrl> discoveredUrls);

    InsertFrontierResult insertFrontierIfAbsent(String canonicalUrl, long siteId, double relevanceScore);

    /**
     * Terminal {@code PROCESSING → ERROR} with diagnostics (TS-10 / TS-12).
     *
     * @throws IllegalStateException when no row was in {@code PROCESSING} (strict single transition)
     */
    void markPageAsError(long pageId, String category, String message);
}
