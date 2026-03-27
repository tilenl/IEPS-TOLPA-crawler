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

    /** True when the host is {@code github.com} or a subdomain (TS-03 plain-HTTP preference). */
    public static boolean isGitHubHost(String domainKey) {
        if (domainKey == null || domainKey.isBlank()) {
            return false;
        }
        return "github.com".equals(domainKey) || domainKey.endsWith(".github.com");
    }
}
