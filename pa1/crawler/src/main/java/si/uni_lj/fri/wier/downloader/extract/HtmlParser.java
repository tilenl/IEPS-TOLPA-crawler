package si.uni_lj.fri.wier.downloader.extract;

import java.util.List;
import si.uni_lj.fri.wier.contracts.Contracts;
import si.uni_lj.fri.wier.contracts.Parser;

public final class HtmlParser implements Parser {
    @Override
    public Contracts.ParseResult parse(String canonicalUrl, String html) {
        return new Contracts.ParseResult(List.of(), List.of());
    }
}
