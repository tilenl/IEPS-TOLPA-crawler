package si.uni_lj.fri.wier.downloader.fetch;

import si.uni_lj.fri.wier.config.RuntimeConfig;
import si.uni_lj.fri.wier.contracts.FetchException;
import si.uni_lj.fri.wier.contracts.FetchResult;
import si.uni_lj.fri.wier.contracts.Fetcher;

/**
 * Minimal fetcher placeholder wired to the TS-01 {@link Fetcher} signature.
 *
 * <p>TS-13 {@code crawler.fetch.maxRedirects} is retained for the future TS-03 {@code HttpClient} build
 * (redirect hop limit per request).
 */
public final class HttpFetcher implements Fetcher {

    private final int maxRedirects;

    public HttpFetcher() {
        this(10);
    }

    public HttpFetcher(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    /** TS-03 wiring: redirect hop limit from validated runtime config. */
    public static HttpFetcher from(RuntimeConfig config) {
        return new HttpFetcher(config.fetchMaxRedirects());
    }

    /** Max HTTP redirect hops (TS-13); use when wiring {@link java.net.http.HttpClient}. */
    public int maxRedirects() {
        return maxRedirects;
    }

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
