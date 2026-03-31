/*
 * Decides whether a fetched URL path suffix forces BINARY persistence even when Content-Type is text/html
 * (e.g. GitHub blob pages for source files). Uses a denylist so repo names like segmentation_models.pytorch
 * are not mistaken for .py files.
 */

package si.uni_lj.fri.wier.downloader.fetch;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class UrlPathSuffixHtmlPolicy {

    /**
     * When {@code crawler.fetch.denyPathPostfixes} is null/blank, these last-segment suffixes force BINARY despite
     * {@code text/html}.
     */
    public static final List<String> DEFAULT_DENY_PATH_POSTFIXES = List.of("py", "txt", "yaml", "ipynb");

    private UrlPathSuffixHtmlPolicy() {}

    /**
     * @param normalizedDenyList suffixes without leading dot, lowercased (as from {@link RuntimeConfig}); if empty,
     *     path-based forcing is disabled
     */
    public static boolean shouldForceBinary(String url, List<String> normalizedDenyList) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String suffix = pathSuffixAfterLastDot(url);
        if (suffix == null) {
            return false;
        }
        if (normalizedDenyList == null || normalizedDenyList.isEmpty()) {
            return false;
        }
        return Set.copyOf(normalizedDenyList).contains(suffix);
    }

    /**
     * Last path segment: if it has a {@code .} after the first character, the substring after the last {@code .}
     * is a candidate extension when it is at least two alphanumeric characters (avoids {@code /tags/v1.0}).
     */
    public static String pathSuffixAfterLastDot(String url) {
        String path;
        try {
            path = URI.create(url.trim()).getPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (path == null || path.isEmpty()) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String segment = slash < 0 ? path : path.substring(slash + 1);
        if (segment.isEmpty()) {
            return null;
        }
        int lastDot = segment.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String suffix = segment.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        if (suffix.length() < 2 || suffix.length() > 32) {
            return null;
        }
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')) {
                return null;
            }
        }
        return suffix;
    }
}
