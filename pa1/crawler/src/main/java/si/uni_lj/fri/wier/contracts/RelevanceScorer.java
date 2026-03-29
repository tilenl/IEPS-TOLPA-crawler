package si.uni_lj.fri.wier.contracts;

/**
 * Computes a deterministic, non-negative relevance score for frontier prioritization. Implementations are not required
 * to cap the upper bound (keyword overlap may exceed 1.0 when many terms match).
 */
public interface RelevanceScorer {
    double compute(String canonicalUrl, String anchorText, String contextText);
}
