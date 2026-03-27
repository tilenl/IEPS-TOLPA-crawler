/*
 * TS-05 URL canonicalization for frontier enqueue and deduplication keys.
 *
 * <p>Called from link extraction and storage boundaries (see {@link si.uni_lj.fri.wier.contracts.Canonicalizer}).
 * Uses {@code org.netpreserve:urlcanon} WHATWG rules plus project-specific fragment stripping and query filtering.
 *
 * <p><b>Resolution step:</b> Relative and scheme-relative URLs are resolved with {@link java.net.URI#resolve}
 * against the page base URL. We use the JDK resolver here because {@link org.netpreserve.urlcanon.ParsedUrl#resolve}
 * mishandles scheme-relative references ({@code //host/path}) by omitting the colon after the scheme; the JDK
 * matches browser resolution for those inputs. WHATWG normalization still runs on the resolved string via urlcanon.
 *
 * <p><b>Invariants:</b> Rejections never throw to callers; the worker batch continues. Length and scheme checks apply
 * after normalization and query filtering.
 *
 * <p>Created for TS-05; major change: replaced URI-only stub with urlcanon WHATWG + query allowlist (2025-03).
 */
package si.uni_lj.fri.wier.downloader.normalize;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.netpreserve.urlcanon.ParsedUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.contracts.CanonicalizationResult;
import si.uni_lj.fri.wier.contracts.Canonicalizer;

/**
 * Canonicalizes raw discovered URLs to deterministic {@code http(s)} strings per TS-05.
 *
 * <p>Pipeline: resolve to absolute URI string → {@link ParsedUrl} + WHATWG → strip fragment → keep only {@code page}
 * query parameter (sorted) → enforce scheme allowlist and max length.
 */
public final class UrlCanonicalizer implements Canonicalizer {

    private static final Logger log = LoggerFactory.getLogger(UrlCanonicalizer.class);

    /** Assignment scope: reject longer strings before any Stage-A DB use (TS-05). */
    public static final int MAX_CANONICAL_URL_LENGTH = 3000;

    /** Query parameter names retained after WHATWG (pagination); all others dropped (TS-05). */
    private static final String ALLOWED_QUERY_PARAM = "page";

    @Override
    public CanonicalizationResult canonicalize(String rawUrl, String baseUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return reject("INVALID_URL", rawUrl, baseUrl);
        }
        final String trimmedRaw = rawUrl.trim();
        try {
            String resolvedAbsolute = resolveAbsoluteUriString(trimmedRaw, baseUrl);
            ParsedUrl parsed = ParsedUrl.parseUrl(resolvedAbsolute);
            org.netpreserve.urlcanon.Canonicalizer.WHATWG.canonicalize(parsed);
            stripFragment(parsed);
            applyGitHubStyleQueryAllowlist(parsed);
            String canonical = parsed.toString();
            if (canonical.isBlank()) {
                return reject("INVALID_URL", trimmedRaw, baseUrl);
            }
            String scheme = parsed.getScheme();
            if (scheme == null || scheme.isBlank()) {
                return reject("INVALID_URL", trimmedRaw, baseUrl);
            }
            String schemeLower = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(schemeLower) && !"https".equals(schemeLower)) {
                return reject("UNSUPPORTED_SCHEME", trimmedRaw, baseUrl);
            }
            if (canonical.length() > MAX_CANONICAL_URL_LENGTH) {
                return reject("URL_TOO_LONG", trimmedRaw, baseUrl);
            }
            return CanonicalizationResult.accepted(canonical);
        } catch (URISyntaxException e) {
            return reject("INVALID_URL", trimmedRaw, baseUrl, e);
        } catch (RuntimeException e) {
            // WHATWG or string handling must not break the extraction batch (TS-05).
            return reject("INVALID_URL", trimmedRaw, baseUrl, e);
        }
    }

    /**
     * Resolves a reference against an optional base URL and returns a serial form suitable for {@link
     * ParsedUrl#parseUrl}.
     *
     * @param rawUrl non-blank trimmed href or absolute URL
     * @param baseUrl optional page URL; if blank, {@code rawUrl} must be an absolute URI
     * @return absolute URI string
     * @throws URISyntaxException if the JDK cannot parse or resolve the inputs
     */
    private static String resolveAbsoluteUriString(String rawUrl, String baseUrl) throws URISyntaxException {
        if (baseUrl == null || baseUrl.isBlank()) {
            URI ref = new URI(rawUrl);
            if (!ref.isAbsolute()) {
                throw new URISyntaxException(rawUrl, "relative URL requires a base URL");
            }
            return ref.toString();
        }
        URI base = new URI(baseUrl.trim());
        return base.resolve(rawUrl).toString();
    }

    /**
     * Removes the fragment for crawl/dedup purposes; fragments are not sent on the wire (TS-05).
     *
     * <p>{@link ParsedUrl#setFragment} does not accept {@code null}; empty strings clear the serialized fragment.
     */
    private static void stripFragment(ParsedUrl parsed) {
        parsed.setFragment("");
        parsed.setHashSign("");
    }

    /**
     * Keeps only allowlisted query parameters ({@value #ALLOWED_QUERY_PARAM}), drops tracking noise ({@code utm_*},
     * etc.), and sorts surviving pairs by name then value for deterministic output (TS-05).
     *
     * <p>Runs after WHATWG; urlcanon does not strip query params by itself.
     */
    private static void applyGitHubStyleQueryAllowlist(ParsedUrl parsed) {
        String query = parsed.getQuery();
        if (query == null || query.isEmpty()) {
            return;
        }
        List<QueryPair> kept = new ArrayList<>();
        for (String segment : query.split("&")) {
            if (segment.isEmpty()) {
                continue;
            }
            int eq = segment.indexOf('=');
            String encName = eq >= 0 ? segment.substring(0, eq) : segment;
            String encValue = eq >= 0 ? segment.substring(eq + 1) : "";
            String decodedName = percentDecodeOrRaw(encName);
            if (!ALLOWED_QUERY_PARAM.equals(decodedName)) {
                continue;
            }
            kept.add(new QueryPair(encValue));
        }
        kept.sort(
                Comparator.comparing(QueryPair::decodedValue, Comparator.nullsFirst(Comparator.naturalOrder())));
        if (kept.isEmpty()) {
            parsed.setQuery("");
            parsed.setQuestionMark("");
            return;
        }
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) {
                rebuilt.append('&');
            }
            rebuilt.append(ALLOWED_QUERY_PARAM)
                    .append('=')
                    .append(kept.get(i).encodedValueForOutput());
        }
        parsed.setQuery(rebuilt.toString());
        parsed.setQuestionMark("?");
    }

    private static String percentDecodeOrRaw(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed percent-sequences: treat name as literal so we do not accidentally keep a wrong param.
            return s;
        }
    }

    /** One surviving {@code page} parameter after filtering. */
    private static final class QueryPair {
        private final String rawValue;

        QueryPair(String rawValue) {
            this.rawValue = Objects.requireNonNull(rawValue);
        }

        String decodedValue() {
            try {
                return URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return rawValue;
            }
        }

        /** Rebuild with standard UTF-8 form encoding for deterministic serialization. */
        String encodedValueForOutput() {
            String decoded = decodedValue();
            return URLEncoder.encode(decoded, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }

    private CanonicalizationResult reject(String reasonCode, String rawUrl, String baseUrl) {
        logRejection(reasonCode, rawUrl, baseUrl, null);
        return CanonicalizationResult.rejected(reasonCode);
    }

    private CanonicalizationResult reject(String reasonCode, String rawUrl, String baseUrl, Throwable cause) {
        logRejection(reasonCode, rawUrl, baseUrl, cause);
        return CanonicalizationResult.rejected(reasonCode);
    }

    private static void logRejection(String reasonCode, String rawUrl, String baseUrl, Throwable cause) {
        if (cause == null) {
            log.warn(
                    "canonicalize rejected reason={} rawUrl={} baseUrl={}",
                    reasonCode,
                    rawUrl == null ? "" : rawUrl,
                    baseUrl == null || baseUrl.isBlank() ? "" : baseUrl);
        } else {
            log.warn(
                    "canonicalize rejected reason={} rawUrl={} baseUrl={}",
                    reasonCode,
                    rawUrl == null ? "" : rawUrl,
                    baseUrl == null || baseUrl.isBlank() ? "" : baseUrl,
                    cause);
        }
    }
}
