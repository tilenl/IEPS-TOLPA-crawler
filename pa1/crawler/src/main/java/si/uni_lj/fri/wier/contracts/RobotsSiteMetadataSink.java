package si.uni_lj.fri.wier.contracts;

/**
 * Persists raw robots and sitemap directive text for a crawl domain (TS-06).
 *
 * <p>Implementations typically update {@code crawldb.site}. The gate invokes this after a successful
 * robots.txt response (HTTP 2xx body or 4xx body treated as allow-all). Transient failures do not call
 * this sink.
 *
 * @param domain registry host key (lower-case), aligned with {@code crawldb.site.domain}
 * @param robotsContent raw robots.txt bytes interpreted as UTF-8, or empty when unavailable
 * @param sitemapContent newline-separated sitemap URLs from parsed rules, or empty
 */
@FunctionalInterface
public interface RobotsSiteMetadataSink {

    void save(String domain, String robotsContent, String sitemapContent);
}
