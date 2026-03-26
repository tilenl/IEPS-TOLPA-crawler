package si.uni_lj.fri.wier.contracts;

import java.util.List;

public record ParseResult(List<DiscoveredUrl> discoveredUrls) {
    public ParseResult {
        discoveredUrls = discoveredUrls == null ? List.of() : List.copyOf(discoveredUrls);
    }
}
