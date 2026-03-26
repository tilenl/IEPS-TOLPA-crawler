package si.uni_lj.fri.wier.unit.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.contracts.DiscoveredUrl;
import si.uni_lj.fri.wier.contracts.ExtractedImage;
import si.uni_lj.fri.wier.contracts.ParseResult;

class ParseResultContractTest {

    @Test
    void empty_factory_returns_no_links_images_or_metadata() {
        ParseResult result = ParseResult.empty();
        assertTrue(result.discoveredUrls().isEmpty());
        assertTrue(result.extractedImages().isEmpty());
        assertTrue(result.pageMetadata().isEmpty());
    }

    @Test
    void constructor_makes_defensive_copies_for_collections() {
        List<DiscoveredUrl> links = new ArrayList<>();
        links.add(new DiscoveredUrl("https://example.com/a", 2L, 10L, "a", "ctx", 0.3));
        List<ExtractedImage> images = new ArrayList<>();
        images.add(new ExtractedImage("https://example.com/i.png", "i.png", "image/png"));

        ParseResult result = new ParseResult(links, images, null);
        links.clear();
        images.clear();

        assertEquals(1, result.discoveredUrls().size());
        assertEquals(1, result.extractedImages().size());
        assertTrue(result.pageMetadata().isEmpty());
    }

    @Test
    void linksOnly_factory_populates_only_discovered_urls() {
        ParseResult result =
                ParseResult.linksOnly(
                        List.of(new DiscoveredUrl("https://example.com/b", 2L, 11L, "b", "ctx", 0.4)));

        assertEquals(1, result.discoveredUrls().size());
        assertTrue(result.extractedImages().isEmpty());
        assertTrue(result.pageMetadata().isEmpty());
    }
}
