package si.uni_lj.fri.wier.contracts;

/** Fetches one canonical URL and returns normalized fetch metadata and payload. */
public interface Fetcher {
    FetchResult fetch(String canonicalUrl) throws FetchException;
}
