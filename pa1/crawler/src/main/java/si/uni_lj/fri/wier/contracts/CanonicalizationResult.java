package si.uni_lj.fri.wier.contracts;

/**
 * Outcome of URL canonicalization for one extracted candidate URL.
 *
 * <p>The canonicalizer keeps ingestion deterministic by returning a value object for both accepted
 * and rejected inputs. Rejections are explicit and non-exceptional so batch extraction can continue.
 *
 * @param accepted whether the URL passed canonicalization and scheme/length policy checks.
 * @param canonicalUrl canonical URL when accepted; null when rejected.
 * @param reasonCode machine-readable rejection reason (for example, INVALID_URL or URL_TOO_LONG);
 *     null when accepted.
 */
public record CanonicalizationResult(boolean accepted, String canonicalUrl, String reasonCode) {
    public static CanonicalizationResult accepted(String canonicalUrl) {
        return new CanonicalizationResult(true, canonicalUrl, null);
    }

    public static CanonicalizationResult rejected(String reasonCode) {
        return new CanonicalizationResult(false, null, reasonCode);
    }
}
