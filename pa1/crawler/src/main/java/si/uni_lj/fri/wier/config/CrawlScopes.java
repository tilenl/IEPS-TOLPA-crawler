/*
 * Host matching for {@link CrawlScope} (frontier discoveries, robots metadata persistence, enqueue guard).
 *
 * Callers: HtmlParser, WorkerLoop, PolitenessGate, EnqueueCoordinator, PreferentialCrawler, tests.
 */

package si.uni_lj.fri.wier.config;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import si.uni_lj.fri.wier.downloader.fetch.HostKeys;

public final class CrawlScopes {

    private CrawlScopes() {}

    /**
     * @param raw property value; blank defaults to {@link CrawlScope#GITHUB}
     */
    public static CrawlScope parseCrawlScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return CrawlScope.GITHUB;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if ("GITHUB".equals(t)) {
            return CrawlScope.GITHUB;
        }
        throw new IllegalArgumentException("Unknown crawler.crawlScope: " + raw.trim());
    }

    /**
     * @param domainKey lower-case registry host from {@link HostKeys#domainKey(String)}
     */
    public static boolean hostMatchesCrawlScope(CrawlScope scope, String domainKey) {
        Objects.requireNonNull(scope, "scope");
        if (domainKey == null || domainKey.isBlank()) {
            return false;
        }
        return switch (scope) {
            case GITHUB -> HostKeys.isGitHubHost(domainKey);
        };
    }

    /** Predicate for persisting robots/sitemap text to {@code crawldb.site} for a domain key. */
    public static Predicate<String> persistencePredicate(CrawlScope scope) {
        Objects.requireNonNull(scope, "scope");
        return d -> hostMatchesCrawlScope(scope, d);
    }
}
