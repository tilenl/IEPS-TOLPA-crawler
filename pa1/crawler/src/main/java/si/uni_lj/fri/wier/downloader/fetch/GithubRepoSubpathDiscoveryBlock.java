/*
 * Discovery-time filter: GitHub repo subpaths (first segment after /owner/repo/) on github.com (TS-13
 * crawler.discovery.denyGithubRepoSubpaths).
 *
 * Callers: {@link si.uni_lj.fri.wier.storage.postgres.repositories.PageRepository#ingestDiscoveredUrls}.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.downloader.fetch;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Skips frontier enqueue for URLs like {@code /org/repo/blob/...}, {@code /org/repo/issues}, {@code /org/repo/pull/1}
 * when the first path segment after owner/repo is in the configured deny set.
 */
public final class GithubRepoSubpathDiscoveryBlock {

    /**
     * When {@code crawler.discovery.denyGithubRepoSubpaths} is null/blank, {@code RuntimeConfig} uses this list
     * (lowercase segments, no slashes).
     */
    public static final List<String> DEFAULT_DENY_REPO_SUBPATHS =
            List.of("blob", "issues", "labels", "discussions", "pulls", "pull");

    private GithubRepoSubpathDiscoveryBlock() {}

    /**
     * @param canonicalUrl absolute URL after TS-05 canonicalization
     * @param deniedFirstSegmentsLowercase non-null; if empty, always returns false
     * @return true when host is GitHub web and path has {@code /owner/repo/&lt;segment&gt;/...} with {@code segment} in
     *     the set
     */
    public static boolean isBlockedGithubRepoSubpathDiscoveryUrl(
            String canonicalUrl, Set<String> deniedFirstSegmentsLowercase) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        Objects.requireNonNull(deniedFirstSegmentsLowercase, "deniedFirstSegmentsLowercase");
        if (deniedFirstSegmentsLowercase.isEmpty()) {
            return false;
        }
        String trimmed = canonicalUrl.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String domainKey;
        try {
            domainKey = HostKeys.domainKey(trimmed);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!HostKeys.isGitHubHost(domainKey)) {
            return false;
        }
        URI uri = URI.create(trimmed);
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return false;
        }
        List<String> segments = pathSegments(path);
        if (segments.size() < 3) {
            return false;
        }
        String firstAfterRepo = segments.get(2).toLowerCase(Locale.ROOT);
        return deniedFirstSegmentsLowercase.contains(firstAfterRepo);
    }

    static List<String> pathSegments(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }
}
