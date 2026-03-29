/*
 * Declares which host suffix sets qualify for crawl frontier expansion and robots DB persistence (TS-13 extension).
 *
 * Parsed from {@code crawler.crawlScope}; see {@link CrawlScopes}.
 */

package si.uni_lj.fri.wier.config;

/** Crawl host set for frontier and {@code crawldb.site} robots persistence. */
public enum CrawlScope {
    /** {@code github.com} and {@code *.github.com} only. */
    GITHUB
}
