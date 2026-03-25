package si.uni_lj.fri.wier.contracts;

public interface RelevanceScorer {
    double compute(String canonicalUrl, String anchorText, String contextText);
}
