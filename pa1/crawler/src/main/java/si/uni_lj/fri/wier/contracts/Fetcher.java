package si.uni_lj.fri.wier.contracts;

/**
 * Fetches one canonical URL and returns normalized fetch metadata and payload (TS-01 / TS-03).
 *
 * <p>Implementations perform hop-by-hop redirects with per-hop robots and rate limiting (TS-03). Callers must
 * supply lease metadata in {@link FetchRequest} so mid-chain politeness can block only within safe lease margin
 * (TS-08 redirect exception).
 *
 * @throws FetchException recoverable or classified failures; {@link FetchException#category()} uses
 *     {@link si.uni_lj.fri.wier.error.CrawlerErrorCategory} names (e.g. {@code FETCH_TIMEOUT} includes redirect
 *     chain exhaustion per project convention, as well as true timeouts)
 */
public interface Fetcher {
    FetchResult fetch(FetchRequest request) throws FetchException;
}
