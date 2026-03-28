/*
 * Shared manual redirect detection and Location resolution (TS-03, TS-06).
 *
 * HttpClient automatic redirect following is disabled; callers implement hop-by-hop policy.
 */

package si.uni_lj.fri.wier.downloader.fetch;

import java.net.URI;

/** Utilities for manual HTTP redirect chains. */
public final class ManualHttpRedirects {

    private ManualHttpRedirects() {}

    public static boolean isRedirect(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    /**
     * Resolves a redirect {@code Location} against the current request URL.
     *
     * @throws IllegalArgumentException if URLs are not valid
     */
    public static String resolveLocation(String currentAbsolute, String locationHeader) {
        URI base = URI.create(currentAbsolute);
        URI resolved = base.resolve(locationHeader.trim());
        return resolved.toString();
    }
}
