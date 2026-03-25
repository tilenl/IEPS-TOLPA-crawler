package si.uni_lj.fri.wier.contracts;

public interface Parser {
    Contracts.ParseResult parse(String canonicalUrl, String html);
}
