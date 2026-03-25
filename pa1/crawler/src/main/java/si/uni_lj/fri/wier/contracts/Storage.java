package si.uni_lj.fri.wier.contracts;

import java.util.Collection;
import java.util.Optional;

public interface Storage {
    Optional<Long> ensureSite(String domain);

    Contracts.PersistOutcome persistFetchOutcomeWithLinks(
            Contracts.FetchContext context,
            Contracts.FetchResult result,
            Contracts.ParseResult parsed,
            Collection<Contracts.DiscoveredUrl> discovered
    );

    Contracts.LinkInsertResult insertLink(long fromPageId, long toPageId);

    Contracts.IngestResult ingestDiscoveredUrls(Collection<Contracts.DiscoveredUrl> discoveredUrls);

    Contracts.InsertFrontierResult insertFrontierIfAbsent(String canonicalUrl, long siteId, double relevanceScore);

    void markPageAsError(long pageId, String category, String message);
}
