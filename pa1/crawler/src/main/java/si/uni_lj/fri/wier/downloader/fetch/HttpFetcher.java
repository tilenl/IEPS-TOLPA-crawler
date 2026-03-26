package si.uni_lj.fri.wier.downloader.fetch;

import si.uni_lj.fri.wier.contracts.FetchException;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.Fetcher;

/** Minimal fetcher placeholder wired to the TS-01 {@link Fetcher} signature. */
public final class HttpFetcher implements Fetcher {
    @Override
    public FetchResult fetch(String canonicalUrl) throws FetchException {
        if (canonicalUrl == null || canonicalUrl.isBlank()) {
            throw new FetchException("INVALID_URL", "canonicalUrl must be non-empty");
        }
        throw new FetchException(
                "NOT_IMPLEMENTED",
                "HttpFetcher fetch pipeline is pending TS-03 implementation for url=" + canonicalUrl,
                null);
    }
}
