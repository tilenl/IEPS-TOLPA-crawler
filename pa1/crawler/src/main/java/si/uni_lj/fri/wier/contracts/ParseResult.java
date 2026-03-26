package si.uni_lj.fri.wier.contracts;

import java.util.List;
import java.util.Optional;

/**
 * Aggregated parser output for one fetched HTML page (TS-04).
 *
 * <p>Holds discovered outlinks (canonical {@link DiscoveredUrl} rows), image references, and optional
 * page-level metadata. The parser MUST NOT call {@link Storage}; the worker passes this object into
 * Stage B persistence.
 */
public record ParseResult(
        List<DiscoveredUrl> discoveredUrls,
        List<ExtractedImage> extractedImages,
        Optional<ExtractedPageMetadata> pageMetadata) {

    /**
     * Normalizes lists and optional metadata: null collections become empty lists; null optional
     * becomes empty.
     */
    public ParseResult {
        // Immutable snapshot: parsing must not see later mutations of the caller's lists.
        discoveredUrls = discoveredUrls == null ? List.of() : List.copyOf(discoveredUrls);
        extractedImages = extractedImages == null ? List.of() : List.copyOf(extractedImages);
        pageMetadata = pageMetadata == null ? Optional.empty() : pageMetadata;
    }

    /**
     * Empty parse result (valid for non-HTML bodies or failed extraction).
     *
     * @return result with no links, images, or metadata.
     */
    public static ParseResult empty() {
        return new ParseResult(List.of(), List.of(), Optional.empty());
    }

    /**
     * Result containing only discovered links (no images or page metadata).
     *
     * @param discoveredUrls canonical outlinks; null treated as empty.
     * @return parse result with only the link list populated.
     */
    public static ParseResult linksOnly(List<DiscoveredUrl> discoveredUrls) {
        return new ParseResult(discoveredUrls, List.of(), Optional.empty());
    }
}
