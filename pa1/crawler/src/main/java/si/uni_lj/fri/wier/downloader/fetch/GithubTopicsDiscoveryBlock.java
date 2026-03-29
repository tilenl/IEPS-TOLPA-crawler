/*
 * Discovery-time filter: GitHub topic hub URLs on github.com (TS-13 crawler.discovery.blockGithubTopicsPaths).
 *
 * Callers: {@link si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository#ingestDiscoveredUrls}.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.downloader.fetch;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Detects canonical {@code https://github.com/topics} listing URLs so discovery ingestion can skip frontier enqueue.
 * Seed bootstrap is unaffected (different code path).
 */
public final class GithubTopicsDiscoveryBlock {

    private GithubTopicsDiscoveryBlock() {}

    /**
     * @param canonicalUrl absolute URL after TS-05 canonicalization
     * @return true when host is {@code github.com} or {@code www.github.com} and path is {@code /topics} or starts with
     *     {@code /topics/}
     */
    public static boolean isBlockedGithubTopicsDiscoveryUrl(String canonicalUrl) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        String trimmed = canonicalUrl.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            String host = HostKeys.domainKey(trimmed);
            if (!"github.com".equals(host) && !"www.github.com".equals(host)) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        URI uri = URI.create(trimmed);
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return false;
        }
        String p = path.toLowerCase(Locale.ROOT);
        return "/topics".equals(p) || p.startsWith("/topics/");
    }
}
