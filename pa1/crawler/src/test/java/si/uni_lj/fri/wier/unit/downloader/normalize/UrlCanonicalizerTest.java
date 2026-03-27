package si.uni_lj.fri.wier.unit.downloader.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.contracts.CanonicalizationResult;
import si.uni_lj.fri.wier.downloader.normalize.UrlCanonicalizer;

/**
 * Unit tests for TS-05 URL canonicalization: WHATWG normalization, fragment stripping, {@code page}-only query
 * policy, scheme/length guards, and safe rejection without exceptions.
 */
class UrlCanonicalizerTest {

    private final UrlCanonicalizer canonicalizer = new UrlCanonicalizer();

    @Test
    void relativePath_resolvedAgainstGitHubBase() {
        String base = "https://github.com/topics/image-segmentation";
        CanonicalizationResult r =
                canonicalizer.canonicalize("../torvalds/linux", base);
        assertAccepted(r, "https://github.com/torvalds/linux");
    }

    @Test
    void schemeRelative_resolvedAgainstHttpsBase() {
        String base = "https://github.com/topics/image-segmentation";
        CanonicalizationResult r = canonicalizer.canonicalize("//github.com/explore", base);
        assertAccepted(r, "https://github.com/explore");
    }

    @Test
    void whatwg_normalizesSchemeHostPortAndPath() {
        CanonicalizationResult r =
                canonicalizer.canonicalize("HTTP://EXAMPLE.com:80/foo/../bar", null);
        assertAccepted(r, "http://example.com/bar");
    }

    @Test
    void fragment_stripped_queryPreservedWhenAllowlisted() {
        // Only `page` survives the query allowlist (TS-05); fragment is still removed.
        CanonicalizationResult r =
                canonicalizer.canonicalize("https://example.com/page?page=2&x=1#intro", null);
        assertAccepted(r, "https://example.com/page?page=2");
    }

    @Test
    void githubFragment_strippedFromReadmeUrl() {
        CanonicalizationResult r =
                canonicalizer.canonicalize("https://github.com/org/repo#readme", null);
        assertAccepted(r, "https://github.com/org/repo");
    }

    @Test
    void query_keepPage_dropUtm() {
        CanonicalizationResult r =
                canonicalizer.canonicalize(
                        "https://github.com/topics/image-segmentation?page=2&utm_source=homepage", null);
        assertAccepted(r, "https://github.com/topics/image-segmentation?page=2");
    }

    @Test
    void query_dropTabAndRefCta() {
        CanonicalizationResult r =
                canonicalizer.canonicalize("https://github.com/a/b?tab=readme&ref_cta=signup", null);
        assertAccepted(r, "https://github.com/a/b");
    }

    @Test
    void query_deterministicOrdering_multiplePageParamsSortedByValue() {
        CanonicalizationResult r =
                canonicalizer.canonicalize("https://ex.com/a?page=1&page=10&page=2", null);
        assertAccepted(r, "https://ex.com/a?page=1&page=10&page=2");
    }

    @Test
    void relativeWithoutBase_rejected() {
        CanonicalizationResult r = canonicalizer.canonicalize("../foo", null);
        assertFalse(r.accepted());
        assertEquals("INVALID_URL", r.reasonCode());
    }

    @Test
    void unsupportedScheme_rejected() {
        CanonicalizationResult r = canonicalizer.canonicalize("javascript:alert(1)", null);
        assertFalse(r.accepted());
        assertEquals("UNSUPPORTED_SCHEME", r.reasonCode());
    }

    @Test
    void blankRaw_rejected() {
        CanonicalizationResult r = canonicalizer.canonicalize("   ", null);
        assertFalse(r.accepted());
        assertEquals("INVALID_URL", r.reasonCode());
    }

    @Test
    void malformedUri_rejectedWithoutThrowing() {
        CanonicalizationResult r = canonicalizer.canonicalize("http://a b", null);
        assertFalse(r.accepted());
        assertEquals("INVALID_URL", r.reasonCode());
    }

    @Test
    void tooLongAfterCanonicalization_rejected() {
        String longPath = "a".repeat(UrlCanonicalizer.MAX_CANONICAL_URL_LENGTH);
        CanonicalizationResult r = canonicalizer.canonicalize("https://example.com/" + longPath, null);
        assertFalse(r.accepted());
        assertEquals("URL_TOO_LONG", r.reasonCode());
    }

    @Test
    void idempotent_canonicalizingTwiceYieldsSameString() {
        String base = "https://github.com/topics/image-segmentation";
        CanonicalizationResult first = canonicalizer.canonicalize("../torvalds/linux", base);
        assertTrue(first.accepted());
        String canonical = first.canonicalUrl();
        CanonicalizationResult second = canonicalizer.canonicalize(canonical, base);
        assertAccepted(second, canonical);
    }

    @Test
    void percentEncoding_normalizedByWhatwg() {
        CanonicalizationResult r =
                canonicalizer.canonicalize("https://example.com/%7Euser/", null);
        assertTrue(r.accepted());
        // WHATWG output is deterministic for a given input; urlcanon may keep %7E for this path.
        assertEquals("https://example.com/%7Euser/", r.canonicalUrl());
    }

    private static void assertAccepted(CanonicalizationResult r, String expectedUrl) {
        assertTrue(r.accepted(), "expected accepted, got reason=" + r.reasonCode());
        assertEquals(expectedUrl, r.canonicalUrl());
    }
}
