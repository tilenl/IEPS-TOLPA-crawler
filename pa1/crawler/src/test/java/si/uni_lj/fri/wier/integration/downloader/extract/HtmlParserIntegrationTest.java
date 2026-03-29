package si.uni_lj.fri.wier.integration.downloader.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.contracts.ExtractedImage;
import si.uni_lj.fri.wier.contracts.ExtractedPageMetadata;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.downloader.extract.HtmlParser;
import si.uni_lj.fri.wier.downloader.extract.KeywordRelevanceScorer;
import si.uni_lj.fri.wier.downloader.normalize.UrlCanonicalizer;

/**
 * TS-04 integration: {@link HtmlParser} with real {@link UrlCanonicalizer}, {@link KeywordRelevanceScorer} from
 * classpath keywords JSON, and deterministic {@code siteId} mapping — no crawler worker or DB.
 */
class HtmlParserIntegrationTest {

    private HtmlParser parser;

    @BeforeEach
    void setUp() throws Exception {
        Path kw = Paths.get(HtmlParserIntegrationTest.class.getResource("/keywords-valid.json").toURI());
        KeywordRelevanceScorer scorer = new KeywordRelevanceScorer(kw);
        parser =
                new HtmlParser(
                        new UrlCanonicalizer(),
                        scorer,
                        domain -> 42L);
    }

    @Test
    void href_relative_resolvesAgainstBase() {
        String html = "<html><body><a href=\"/topics/foo\">Go</a></body></html>";
        ParseResult r = parser.parse("https://github.com/org/repo", html);
        assertEquals(1, r.discoveredUrls().size());
        assertTrue(r.discoveredUrls().get(0).canonicalUrl().startsWith("https://github.com/topics/foo"));
    }

    @Test
    void onclick_locationHref_and_documentLocation_extracted() {
        String html =
                """
                <html><body>
                <button onclick="location.href='/a'">A</button>
                <span onclick='document.location="https://example.com/b"'>B</span>
                </body></html>
                """;
        ParseResult r = parser.parse("https://github.com/org/repo", html);
        assertEquals(2, r.discoveredUrls().size());
    }

    @Test
    void malformedHtml_stillExtractsWhatJsoupCan() {
        String html = "<html><a href=\"https://example.com/x\">ok</a><unclosed>";
        ParseResult r = parser.parse("https://base.example/", html);
        assertEquals(1, r.discoveredUrls().size());
    }

    @Test
    void images_extractedWithBinaryContentType() {
        String html =
                "<html><body><img src=\"https://cdn.example.com/a/b/logo.png\" alt=\"x\"></body></html>";
        ParseResult r = parser.parse("https://example.com/", html);
        assertEquals(1, r.extractedImages().size());
        ExtractedImage img = r.extractedImages().get(0);
        assertTrue(img.canonicalUrl().contains("logo.png"));
        assertEquals("BINARY", img.contentType());
    }

    @Test
    void title_and_metaDescription_inParseResult() {
        String html =
                """
                <html><head>
                <title>  Hello Title  </title>
                <meta name="description" content="  Desc here  ">
                </head><body></body></html>
                """;
        ParseResult r = parser.parse("https://example.com/", html);
        Optional<ExtractedPageMetadata> meta = r.pageMetadata();
        assertTrue(meta.isPresent());
        assertEquals("Hello Title", meta.get().title());
        assertEquals("Desc here", meta.get().metaDescription());
    }
}
