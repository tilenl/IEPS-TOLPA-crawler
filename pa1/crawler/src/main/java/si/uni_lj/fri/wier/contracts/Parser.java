package si.uni_lj.fri.wier.contracts;

/** Extracts assignment artifacts from one fetched HTML page. */
public interface Parser {
    ParseResult parse(String canonicalUrl, String html);
}
