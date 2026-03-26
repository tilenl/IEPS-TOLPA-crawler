package si.uni_lj.fri.wier.downloader.extract;

import java.util.Objects;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.contracts.Parser;

/** Minimal parser placeholder that returns empty extraction until TS-04 parser work lands. */
public final class HtmlParser implements Parser {
    @Override
    public ParseResult parse(String canonicalUrl, String html) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        return ParseResult.empty();
    }
}
