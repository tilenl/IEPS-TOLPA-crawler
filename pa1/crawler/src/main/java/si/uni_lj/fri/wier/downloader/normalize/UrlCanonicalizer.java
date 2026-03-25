package si.uni_lj.fri.wier.downloader.normalize;

import si.uni_lj.fri.wier.contracts.Contracts;
import si.uni_lj.fri.wier.contracts.Canonicalizer;

public final class UrlCanonicalizer implements Canonicalizer {
    @Override
    public Contracts.CanonicalizationResult canonicalize(String rawUrl, String baseUrl) {
        return Contracts.CanonicalizationResult.failure("UrlCanonicalizer not implemented yet");
    }
}
