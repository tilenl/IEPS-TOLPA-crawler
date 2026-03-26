package si.uni_lj.fri.wier.downloader.normalize;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import si.uni_lj.fri.wier.contracts.CanonicalizationResult;
import si.uni_lj.fri.wier.contracts.Canonicalizer;

/** Minimal TS-05 canonicalizer implementation used by the TS-01 contract wiring. */
public final class UrlCanonicalizer implements Canonicalizer {
    @Override
    public CanonicalizationResult canonicalize(String rawUrl, String baseUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return CanonicalizationResult.rejected("INVALID_URL");
        }
        try {
            URI base = baseUrl == null || baseUrl.isBlank() ? null : new URI(baseUrl);
            URI resolved = (base == null ? new URI(rawUrl) : base.resolve(rawUrl)).normalize();
            String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return CanonicalizationResult.rejected("UNSUPPORTED_SCHEME");
            }
            URI loweredHost =
                    new URI(
                            scheme,
                            resolved.getUserInfo(),
                            resolved.getHost() == null ? null : resolved.getHost().toLowerCase(Locale.ROOT),
                            resolved.getPort(),
                            resolved.getPath(),
                            resolved.getQuery(),
                            null);
            String canonical = Objects.toString(loweredHost.toASCIIString(), "");
            if (canonical.isBlank()) {
                return CanonicalizationResult.rejected("INVALID_URL");
            }
            if (canonical.length() > 3000) {
                return CanonicalizationResult.rejected("URL_TOO_LONG");
            }
            return CanonicalizationResult.accepted(canonical);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return CanonicalizationResult.rejected("INVALID_URL");
        }
    }
}
