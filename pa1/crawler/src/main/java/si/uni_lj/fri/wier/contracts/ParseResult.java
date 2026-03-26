package si.uni_lj.fri.wier.contracts;

import java.util.List;

public record ParseResult(List<DiscoveredUrl> discoveredUrls) {
    public ParseResult {
        // Immutable snapshot: parsing must not see later mutations of the caller's list.
        discoveredUrls = discoveredUrls == null ? List.of() : List.copyOf(discoveredUrls);
    }
}
