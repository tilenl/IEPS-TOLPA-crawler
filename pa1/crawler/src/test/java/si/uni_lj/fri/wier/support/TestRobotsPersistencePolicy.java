/*
 * Test-only extension of crawl-scope rules for robots {@code crawldb.site} persistence (loopback ITs).
 */

package si.uni_lj.fri.wier.support;

import java.util.Locale;
import java.util.function.Predicate;
import si.uni_lj.fri.wier.config.CrawlScope;
import si.uni_lj.fri.wier.config.CrawlScopes;

public final class TestRobotsPersistencePolicy {

    private TestRobotsPersistencePolicy() {}

    /**
     * Same as production {@link CrawlScopes#persistencePredicate(CrawlScope)} plus typical loopback registry hosts
     * used in integration tests ({@code localhost}, {@code 127.0.0.1}, {@code 127.*.*.*}).
     */
    public static Predicate<String> githubScopePlusLoopback(CrawlScope scope) {
        Predicate<String> base = CrawlScopes.persistencePredicate(scope);
        return d -> base.test(d) || isLoopbackRegistryHost(d);
    }

    private static boolean isLoopbackRegistryHost(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        String d = domain.toLowerCase(Locale.ROOT);
        if ("localhost".equals(d) || "127.0.0.1".equals(d)) {
            return true;
        }
        return d.startsWith("127.") && d.matches("^127(\\.\\d{1,3}){3}$");
    }
}
