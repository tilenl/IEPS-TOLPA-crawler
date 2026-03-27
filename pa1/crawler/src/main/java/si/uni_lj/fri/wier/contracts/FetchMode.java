package si.uni_lj.fri.wier.contracts;

/**
 * Transport used to obtain the fetched body (TS-03).
 *
 * <p>Callers: {@link FetchResult}; set by {@link si.uni_lj.fri.wier.downloader.fetch.HttpFetcher}.
 */
public enum FetchMode {
    /** {@link java.net.http.HttpClient} without browser automation. */
    PLAIN_HTTP,
    /** Selenium headless Chrome (TS-03). */
    HEADLESS
}
