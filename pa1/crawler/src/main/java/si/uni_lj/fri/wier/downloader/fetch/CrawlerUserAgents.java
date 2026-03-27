/*
 * Shared User-Agent string for HTTP and headless fetch (TS-03).
 */

package si.uni_lj.fri.wier.downloader.fetch;

/** Normative crawler identity for robots and content fetches. */
public final class CrawlerUserAgents {

    /** Value mandated by TS-03 for HttpClient and Selenium. */
    public static final String FETCHER = "fri-wier-IEPS-TOLPA";

    private CrawlerUserAgents() {}
}
