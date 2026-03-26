package si.uni_lj.fri.wier.contracts;

/** Canonicalizes extracted URLs according to TS-05 normalization rules. */
public interface Canonicalizer {
    CanonicalizationResult canonicalize(String rawUrl, String baseUrl);
}
