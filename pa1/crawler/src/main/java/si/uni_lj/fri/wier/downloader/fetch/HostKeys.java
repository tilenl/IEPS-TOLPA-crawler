/*
 * Host/domain keys for politeness, robots, and rate buckets (TS-03 / TS-08).
 *
 * Callers: HttpFetcher, PolitenessGate, tests.
 *
 * Created: 2026-03 for TS-03.
 */

package si.uni_lj.fri.wier.downloader.fetch;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Derives normalized registry host keys from HTTP(S) URLs for per-domain policy.
 */
public final class HostKeys {

    private HostKeys() {}

    /**
     * Returns lower-case host from an absolute http(s) URL string.
     *
     * @throws IllegalArgumentException if the URL has no host
     */
    public static String domainKey(String httpUrl) {
        Objects.requireNonNull(httpUrl, "httpUrl");
        URI uri = URI.create(httpUrl.trim());
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host: " + httpUrl);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * True for the public GitHub web hosts used in crawl scope: {@code github.com} and {@code www.github.com} (apex
     * alias). Other subdomains such as {@code api.github.com} are out of scope for frontier expansion.
     */
    public static boolean isGitHubHost(String domainKey) {
        if (domainKey == null || domainKey.isBlank()) {
            return false;
        }
        return "github.com".equals(domainKey) || "www.github.com".equals(domainKey);
    }

    /**
     * Maps {@code www.github.com} to {@code github.com} for {@code crawldb.site.domain} and {@code ensureSite} keys;
     * returns {@code domainKey} unchanged for all other values (including {@code github.com} and non-GitHub hosts).
     */
    public static String githubSiteRegistryKey(String domainKey) {
        if (domainKey == null || domainKey.isBlank()) {
            return domainKey;
        }
        return "www.github.com".equals(domainKey) ? "github.com" : domainKey;
    }
}
