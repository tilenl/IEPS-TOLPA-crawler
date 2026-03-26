package si.uni_lj.fri.wier.contracts;

/** Computes deterministic relevance score bounded to the [0.0, 1.0] range. */
public interface RelevanceScorer {
    double compute(String canonicalUrl, String anchorText, String contextText);
}
