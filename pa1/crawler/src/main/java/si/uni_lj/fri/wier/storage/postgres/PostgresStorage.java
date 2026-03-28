package si.uni_lj.fri.wier.storage.postgres;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import si.uni_lj.fri.wier.contracts.DiscoveredUrl;
import si.uni_lj.fri.wier.contracts.FetchContext;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.IngestResult;
import si.uni_lj.fri.wier.contracts.InsertFrontierResult;
import si.uni_lj.fri.wier.contracts.LinkInsertResult;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.contracts.PersistOutcome;
import si.uni_lj.fri.wier.contracts.Storage;
import si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository;

/** PostgreSQL implementation of the Stage B {@link Storage} contract. */
public final class PostgresStorage implements Storage {
    private final PageRepository pageRepository;

    public PostgresStorage(PageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    @Override
    public Optional<Long> ensureSite(String domain) {
        return pageRepository.ensureSite(domain);
    }

    @Override
    public PersistOutcome persistFetchOutcomeWithLinks(
            FetchContext context,
            FetchResult result,
            ParseResult parsed,
            Collection<DiscoveredUrl> discovered) {
        return pageRepository.persistFetchOutcomeWithLinks(context, result, parsed, discovered);
    }

    @Override
    public LinkInsertResult insertLink(long fromPageId, long toPageId) {
        return pageRepository.insertLink(fromPageId, toPageId);
    }

    @Override
    public IngestResult ingestDiscoveredUrls(Collection<DiscoveredUrl> discoveredUrls) {
        return pageRepository.ingestDiscoveredUrls(discoveredUrls);
    }

    @Override
    public InsertFrontierResult insertFrontierIfAbsent(
            String canonicalUrl, long siteId, double relevanceScore) {
        return pageRepository.insertFrontierIfAbsent(canonicalUrl, siteId, relevanceScore);
    }

    @Override
    public InsertFrontierResult insertFrontierIfAbsent(
            String canonicalUrl, long siteId, double relevanceScore, Instant nextAttemptAt) {
        return pageRepository.insertFrontierIfAbsent(canonicalUrl, siteId, relevanceScore, nextAttemptAt);
    }

    @Override
    public void markPageAsError(long pageId, String category, String message) {
        // Contract name is generic; repository uses the DB term for the terminal ERROR row state.
        pageRepository.markPageTerminalError(pageId, category, message);
    }
}
