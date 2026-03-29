package si.uni_lj.fri.wier.unit.downloader.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import si.uni_lj.fri.wier.contracts.CanonicalizationResult;
import si.uni_lj.fri.wier.contracts.Canonicalizer;
import si.uni_lj.fri.wier.contracts.ExtractedImage;
import si.uni_lj.fri.wier.contracts.ExtractedPageMetadata;
import si.uni_lj.fri.wier.config.CrawlScope;
import si.uni_lj.fri.wier.config.CrawlScopes;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.downloader.extract.HtmlParser;
import si.uni_lj.fri.wier.downloader.extract.KeywordRelevanceScorer;
import si.uni_lj.fri.wier.downloader.normalize.UrlCanonicalizer;

class HtmlParserUnitTest {

    private HtmlParser parser;

    @BeforeEach
    void setUp() throws Exception {
        Path kw = Paths.get(HtmlParserUnitTest.class.getResource("/keywords-valid.json").toURI());
        KeywordRelevanceScorer scorer = new KeywordRelevanceScorer(kw);
        parser =
                new HtmlParser(
                        new UrlCanonicalizer(),
                        scorer,
                        domain -> 99L,
                        CrawlScopes.persistencePredicate(CrawlScope.GITHUB));
    }

    @Test
    void href_relative_resolvesAgainstBase() {
        String html =
                "<html><body><a href=\"/topics/foo\">Go</a></body></html>";
        ParseResult r = parser.parse("https://github.com/org/repo", html);
        assertEquals(1, r.discoveredUrls().size());
        assertTrue(r.discoveredUrls().get(0).canonicalUrl().startsWith("https://github.com/topics/foo"));
        assertEquals(99L, r.discoveredUrls().get(0).siteId());
    }

    @Test
    void href_absoluteWwwGithub_normalizedToApexByTs05() {
        String html =
                "<html><body><a href=\"https://www.github.com/org/www-normalized\">x</a></body></html>";
        ParseResult r = parser.parse("https://github.com/topics/seed", html);
        assertEquals(1, r.discoveredUrls().size());
        assertEquals("https://github.com/org/www-normalized", r.discoveredUrls().get(0).canonicalUrl());
    }

    @Test
    void href_whenCanonicalPreservesWwwHost_githubScopeStillAdmits() throws Exception {
        Canonicalizer fixedWww =
                (raw, base) -> CanonicalizationResult.accepted("https://www.github.com/org/preserved");
        Path kw = Paths.get(HtmlParserUnitTest.class.getResource("/keywords-valid.json").toURI());
        HtmlParser p =
                new HtmlParser(
                        fixedWww,
                        new KeywordRelevanceScorer(kw),
                        domain -> 99L,
                        CrawlScopes.persistencePredicate(CrawlScope.GITHUB));
        ParseResult r =
                p.parse(
                        "https://github.com/topics/seed",
                        "<html><body><a href=\"https://example.com/ignored\">x</a></body></html>");
        assertEquals(1, r.discoveredUrls().size());
        assertEquals("https://www.github.com/org/preserved", r.discoveredUrls().get(0).canonicalUrl());
    }

    @Test
    void onclick_locationHref_and_documentLocation_extracted() {
        String html =
                """
                <html><body>
                <button onclick="location.href='/a'">A</button>
                <span onclick='document.location="https://github.com/other/b"'>B</span>
                </body></html>
                """;
        ParseResult r = parser.parse("https://github.com/org/repo", html);
        assertEquals(2, r.discoveredUrls().size());
    }

    @Test
    void malformedHtml_stillExtractsWhatJsoupCan() {
        String html = "<html><a href=\"https://github.com/x\">ok</a><unclosed>";
        ParseResult r = parser.parse("https://github.com/base/repo", html);
        assertEquals(1, r.discoveredUrls().size());
    }

    @Test
    void offScope_httpHttpsTarget_omittedFromDiscovered() {
        String html =
                "<html><body>"
                        + "<a href=\"https://example.com/off\">e</a>"
                        + "<a href=\"https://github.com/on\">g</a>"
                        + "</body></html>";
        ParseResult r = parser.parse("https://github.com/org/repo", html);
        assertEquals(1, r.discoveredUrls().size());
        assertTrue(r.discoveredUrls().get(0).canonicalUrl().contains("github.com/on"));
    }

    @Test
    void onclick_in_head_notExtracted_whenElementStaysInHead() {
        // HTML5 often reparents invalid <a> from <head> into <body>; use <script> in head so the node stays there.
        String html =
                """
                <html><head>
                <script onclick="location.href='https://example.com/head-onclick'"></script>
                </head><body>
                <a href="https://github.com/body-link">b</a>
                </body></html>
                """;
        ParseResult r = parser.parse("https://github.com/p/repo", html);
        assertEquals(1, r.discoveredUrls().size());
        assertTrue(r.discoveredUrls().get(0).canonicalUrl().contains("github.com/body-link"));
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

    @Test
    void emptyHtml_returnsEmptyParseResult() {
        ParseResult r = parser.parse("https://example.com/", "");
        assertTrue(r.discoveredUrls().isEmpty());
    }

    @Test
    void topicCard_includesSiblingTopicTagsInContext() {
        String html =
                """
                <html><head><title>Topic page</title></head><body>
                <article class="card">
                <a href="https://github.com/org/repo">repo</a>
                <a href="/topics/keyword-one" class="topic-tag topic-tag-link">keyword one</a>
                <a href="/topics/foo" class="topic-tag topic-tag-link">foo</a>
                <p>Short description for the repository card.</p>
                </article>
                </body></html>
                """;
        ParseResult r = parser.parse("https://github.com/topics/image-segmentation", html);
        assertEquals(3, r.discoveredUrls().size());
        var repoLink =
                r.discoveredUrls().stream()
                        .filter(u -> u.canonicalUrl().contains("/org/repo"))
                        .findFirst()
                        .orElseThrow();
        String ctx = repoLink.contextText().toLowerCase();
        assertTrue(ctx.contains("keyword one"), "A2 topic tag text: " + ctx);
        assertTrue(ctx.contains("topic page"), "B title appended: " + ctx);
    }

    @Test
    void pageTitle_andMeta_appendedToLinkContext() {
        String html =
                """
                <html><head>
                <title>Seg topic</title>
                <meta name="description" content="About segmentation">
                </head><body>
                <a href="https://github.com/topics/seg">go</a>
                </body></html>
                """;
        ParseResult r = parser.parse("https://github.com/org/r", html);
        assertEquals(1, r.discoveredUrls().size());
        String ctx = r.discoveredUrls().get(0).contextText().toLowerCase();
        assertTrue(ctx.contains("seg topic"));
        assertTrue(ctx.contains("about segmentation"));
    }

    @Test
    void anchorCenteredWindow_excludesDistantKeywordInSameArticle() {
        String pad = "padding ".repeat(200);
        String html =
                """
                <html><body>
                <article>
                <a href="/topics/keyword-one" class="topic-tag topic-tag-link">keyword one</a>
                <p>card summary text</p>
                <a href="https://github.com/org/hub">hub link</a>
                <div>%s</div>
                <p>keyword two is mentioned only far from the hub link anchor in this card body text.</p>
                </article>
                </body></html>
                """
                        .formatted(pad);
        ParseResult r = parser.parse("https://github.com/topics/t", html);
        var hub =
                r.discoveredUrls().stream()
                        .filter(u -> u.canonicalUrl().contains("/org/hub"))
                        .findFirst()
                        .orElseThrow();
        String ctx = hub.contextText().toLowerCase();
        assertTrue(ctx.contains("keyword one"));
        assertFalse(ctx.contains("keyword two"), "distant keyword should not fall inside anchor window: " + ctx);
    }
}
