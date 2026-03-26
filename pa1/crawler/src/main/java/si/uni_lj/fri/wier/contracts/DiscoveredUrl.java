package si.uni_lj.fri.wier.contracts;

public record DiscoveredUrl(
        String canonicalUrl,
        long siteId,
        long fromPageId,
        String anchorText,
        String contextText,
        double relevanceScore) {}
