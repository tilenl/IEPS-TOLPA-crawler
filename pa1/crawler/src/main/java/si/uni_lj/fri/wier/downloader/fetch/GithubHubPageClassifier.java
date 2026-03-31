/*
 * Classifies GitHub web URLs that are discovery hubs (topic listings, user/org profiles), not repository roots.
 *
 * Callers: PageRepository frontier insert, fetch persist, discovery budget.
 *
 * Created: 2026-03.
 */

package si.uni_lj.fri.wier.downloader.fetch;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * GitHub hub pages: {@code /topics} listings and exactly-one-path-segment profiles (e.g. {@code /imgly}),
 * excluding reserved global first segments. Repository roots {@code /org/repo} are not hubs.
 */
public final class GithubHubPageClassifier {

    /**
     * Lowercase first path segments that are GitHub global pages, not user/org profiles (single-segment case).
     */
    public static final Set<String> RESERVED_SINGLE_SEGMENT =
            Set.of(
                    "explore",
                    "login",
                    "logout",
                    "signup",
                    "pricing",
                    "features",
                    "security",
                    "enterprise",
                    "marketplace",
                    "settings",
                    "sponsors",
                    "sponsor",
                    "collections",
                    "trending",
                    "readme",
                    "mobile",
                    "customer-stories",
                    "team",
                    "about",
                    "git",
                    "pulls",
                    "notifications",
                    "new",
                    "organizations",
                    "account");

    private GithubHubPageClassifier() {}

    /**
     * @param canonicalUrl absolute URL after TS-05 canonicalization
     * @return true for {@code github.com} / {@code www.github.com} hub URLs (topics paths or single-segment profile)
     */
    public static boolean matches(String canonicalUrl) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        String trimmed = canonicalUrl.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            String host = HostKeys.domainKey(trimmed);
            if (!HostKeys.isGitHubHost(host)) {
                return false;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (GithubTopicsDiscoveryBlock.isBlockedGithubTopicsDiscoveryUrl(trimmed)) {
            return true;
        }
        return isSingleSegmentProfileHub(trimmed);
    }

    private static boolean isSingleSegmentProfileHub(String canonicalUrl) {
        URI uri = URI.create(canonicalUrl);
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return false;
        }
        String normalized = path;
        if (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.indexOf('/') >= 0) {
            return false;
        }
        String segment = normalized.toLowerCase(Locale.ROOT);
        if (RESERVED_SINGLE_SEGMENT.contains(segment)) {
            return false;
        }
        return segment.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-');
    }
}
