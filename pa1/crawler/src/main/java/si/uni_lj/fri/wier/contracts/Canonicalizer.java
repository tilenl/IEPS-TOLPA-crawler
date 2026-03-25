package si.uni_lj.fri.wier.contracts;

public interface Canonicalizer {
    Contracts.CanonicalizationResult canonicalize(String rawUrl, String baseUrl);
}
