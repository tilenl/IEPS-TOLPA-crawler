/*
 * TS-04 HTML extraction using Jsoup selectors and regex-only onclick URL parsing.
 *
 * Callers: {@link si.uni_lj.fri.wier.downloader.worker.WorkerLoop}. Emits {@link DiscoveredUrl} rows with
 * {@code fromPageId = 0}; the worker MUST rewrite {@code fromPageId} to the leased source page before persistence
 * (TS-02 Stage B).
 *
 * Created: 2026-03. Major revision: full TS-04 implementation (replaces empty stub). Outlinks use {@code body}
 * descendants only ({@code body a[href]}, {@code body [onclick]}) per TS-04.
 *
 * Link relevance context merges: optional GitHub topic-card tags (A2), anchor-centered neighborhood (A), page title
 * and meta description last (B), then one global cap — see preferential-crawling plan.
 */

package si.uni_lj.fri.wier.downloader.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.uni_lj.fri.wier.contracts.CanonicalizationResult;
import si.uni_lj.fri.wier.contracts.Canonicalizer;
import si.uni_lj.fri.wier.contracts.DiscoveredUrl;
import si.uni_lj.fri.wier.contracts.ExtractedImage;
import si.uni_lj.fri.wier.contracts.ExtractedPageMetadata;
import si.uni_lj.fri.wier.contracts.ParseResult;
import si.uni_lj.fri.wier.contracts.Parser;
import si.uni_lj.fri.wier.contracts.RelevanceScorer;
import si.uni_lj.fri.wier.downloader.fetch.HostKeys;

/**
 * Parses one HTML document into links, onclick targets, images, and light metadata. Crawl outlinks are taken only
 * from {@code <body>} (not {@code <head>}); see TS-04.
 */
public final class HtmlParser implements Parser {

    private static final Logger log = LoggerFactory.getLogger(HtmlParser.class);

    /**
     * Assignment TS-04 pattern: single- or double-quoted URL after {@code location.href} or {@code document.location}.
     */
    private static final Pattern ONCLICK_REDIRECT =
            Pattern.compile(
                    "(?:location\\.href|document\\.location)\\s*=\\s*['\"]([^'\"]+)['\"]",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * After joining A2 + A + B, cap stored context for stable scoring and DB size (preferential plan: single global
     * cap).
     */
    private static final int MAX_LINK_CONTEXT_CHARS = 800;

    /** Half-width of anchor-centered window on the neighborhood source string (notebook cell 9 style). */
    private static final int ANCHOR_CONTEXT_RADIUS = 120;

    /** Max characters read from the neighborhood scope element before slicing around the anchor. */
    private static final int MAX_NEIGHBORHOOD_WIDE_CHARS = 3000;

    /** Max characters for the topic-card tag + description fragment (A2). */
    private static final int MAX_TOPIC_CARD_FRAGMENT_CHARS = 400;

    /** Max description snippet appended from a topic card. */
    private static final int MAX_TOPIC_CARD_DESC_CHARS = 200;

    /** Walk this many parents when not inside a GitHub-style topic {@code article} card. */
    private static final int DEFAULT_NEIGHBORHOOD_ANCESTOR_HOPS = 4;

    /** Placeholder until the worker injects the real source page id (see class Javadoc). */
    private static final long PLACEHOLDER_FROM_PAGE_ID = 0L;

    /** Assignment: persist {@code crawldb.image.content_type} as {@code BINARY} for all {@code img[src]} refs. */
    private static final String IMAGE_CONTENT_TYPE = "BINARY";

    private final Canonicalizer canonicalizer;
    private final RelevanceScorer relevanceScorer;
    private final Function<String, Long> siteIdForDomain;
    /** Registry host keys allowed as crawl frontier targets ({@link HostKeys#domainKey(String)}). */
    private final Predicate<String> hostAllowedForCrawlOutlinks;

    /**
     * @param canonicalizer TS-05 normalizer for every extracted URL string
     * @param relevanceScorer frontier priority input
     * @param siteIdForDomain maps registry host key (see {@link HostKeys#domainKey(String)}) to {@code site.id};
     *     typically wraps {@link si.uni_lj.fri.wier.contracts.Storage#ensureSite(String)}
     * @param hostAllowedForCrawlOutlinks when false for a target host, the URL is not added to discovered outlinks
     *     (images are unaffected)
     */
    public HtmlParser(
            Canonicalizer canonicalizer,
            RelevanceScorer relevanceScorer,
            Function<String, Long> siteIdForDomain,
            Predicate<String> hostAllowedForCrawlOutlinks) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.relevanceScorer = Objects.requireNonNull(relevanceScorer, "relevanceScorer");
        this.siteIdForDomain = Objects.requireNonNull(siteIdForDomain, "siteIdForDomain");
        this.hostAllowedForCrawlOutlinks =
                Objects.requireNonNull(hostAllowedForCrawlOutlinks, "hostAllowedForCrawlOutlinks");
    }

    @Override
    public ParseResult parse(String canonicalUrl, String html) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        if (html == null || html.isBlank()) {
            return ParseResult.empty();
        }
        Document doc = Jsoup.parse(html, canonicalUrl);
        Optional<ExtractedPageMetadata> pageMeta = extractMetadata(doc);
        List<DiscoveredUrl> discovered = new ArrayList<>();
        discovered.addAll(extractHrefLinks(doc, canonicalUrl, pageMeta));
        discovered.addAll(extractOnclickLinks(doc, canonicalUrl, pageMeta));

        List<ExtractedImage> images = extractImages(doc, canonicalUrl);

        return new ParseResult(discovered, images, pageMeta);
    }

    private List<DiscoveredUrl> extractHrefLinks(
            Document doc, String baseCanonical, Optional<ExtractedPageMetadata> pageMeta) {
        List<DiscoveredUrl> out = new ArrayList<>();
        Elements links = doc.select("body a[href]");
        for (Element link : links) {
            try {
                String absolute = link.attr("abs:href");
                if (absolute == null || absolute.isBlank()) {
                    continue;
                }
                CanonicalizationResult cr = canonicalizer.canonicalize(absolute, baseCanonical);
                if (!cr.accepted()) {
                    if (log.isDebugEnabled()) {
                        log.debug("skip href reason={} raw={}", cr.reasonCode(), absolute);
                    }
                    continue;
                }
                String canonical = cr.canonicalUrl();
                String targetHost = HostKeys.domainKey(canonical);
                if (!hostAllowedForCrawlOutlinks.test(targetHost)) {
                    continue;
                }
                long siteId = siteIdForDomain.apply(targetHost);
                String anchorText = link.text().trim();
                String context = linkScoringContext(link, anchorText, pageMeta);
                double score = relevanceScorer.compute(canonical, anchorText, context);
                out.add(
                        new DiscoveredUrl(
                                canonical, siteId, PLACEHOLDER_FROM_PAGE_ID, anchorText, context, score));
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("skip malformed href element: {}", e.toString());
                }
            }
        }
        return out;
    }

    private List<DiscoveredUrl> extractOnclickLinks(
            Document doc, String baseCanonical, Optional<ExtractedPageMetadata> pageMeta) {
        List<DiscoveredUrl> out = new ArrayList<>();
        Elements nodes = doc.select("body [onclick]");
        for (Element el : nodes) {
            String onclick = el.attr("onclick");
            if (onclick == null || onclick.isBlank()) {
                continue;
            }
            Matcher m = ONCLICK_REDIRECT.matcher(onclick);
            if (!m.find()) {
                continue;
            }
            String extracted = m.group(1).trim();
            if (extracted.isEmpty()) {
                continue;
            }
            try {
                CanonicalizationResult cr = canonicalizer.canonicalize(extracted, baseCanonical);
                if (!cr.accepted()) {
                    if (log.isDebugEnabled()) {
                        log.debug("skip onclick reason={} raw={}", cr.reasonCode(), extracted);
                    }
                    continue;
                }
                String canonical = cr.canonicalUrl();
                String targetHost = HostKeys.domainKey(canonical);
                if (!hostAllowedForCrawlOutlinks.test(targetHost)) {
                    continue;
                }
                long siteId = siteIdForDomain.apply(targetHost);
                String anchorText = el.text().trim();
                String context = linkScoringContext(el, anchorText, pageMeta);
                double score = relevanceScorer.compute(canonical, anchorText, context);
                out.add(
                        new DiscoveredUrl(
                                canonical, siteId, PLACEHOLDER_FROM_PAGE_ID, anchorText, context, score));
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("skip malformed onclick: {}", e.toString());
                }
            }
        }
        return out;
    }

    private List<ExtractedImage> extractImages(Document doc, String baseCanonical) {
        List<ExtractedImage> out = new ArrayList<>();
        for (Element img : doc.select("img[src]")) {
            try {
                String absolute = img.attr("abs:src");
                if (absolute == null || absolute.isBlank()) {
                    continue;
                }
                CanonicalizationResult cr = canonicalizer.canonicalize(absolute, baseCanonical);
                if (!cr.accepted()) {
                    continue;
                }
                String canonical = cr.canonicalUrl();
                String fn = filenameFromUrl(canonical);
                out.add(new ExtractedImage(canonical, fn, IMAGE_CONTENT_TYPE));
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("skip image: {}", e.toString());
                }
            }
        }
        return out;
    }

    private static Optional<ExtractedPageMetadata> extractMetadata(Document doc) {
        String title = doc.title();
        if (title != null) {
            title = title.trim();
            if (title.isEmpty()) {
                title = null;
            }
        }
        String desc = null;
        Element metaDesc = doc.selectFirst("meta[name=description]");
        if (metaDesc != null) {
            String c = metaDesc.attr("content");
            if (c != null) {
                c = c.trim();
                if (!c.isEmpty()) {
                    desc = c;
                }
            }
        }
        if (title == null && desc == null) {
            return Optional.empty();
        }
        return Optional.of(new ExtractedPageMetadata(title, desc));
    }

    /**
     * Context for {@link RelevanceScorer}: A2 (topic card tags if DOM matches) + A (anchor-centered) + B (title, meta),
     * single cap.
     */
    private static String linkScoringContext(
            Element el, String anchorText, Optional<ExtractedPageMetadata> pageMeta) {
        String a2 = topicCardTagFragment(el);
        String a = anchorCenteredNeighborhood(el, anchorText);
        String b = pageMetadataContext(pageMeta);
        return mergeScoringContext(a2, a, b);
    }

    /**
     * GitHub topic-repo cards: {@code article} with {@code a.topic-tag.topic-tag-link} siblings; no URL-path gate.
     */
    private static String topicCardTagFragment(Element el) {
        Element card = el.closest("article");
        if (card == null) {
            return "";
        }
        Elements tags = card.select("a.topic-tag.topic-tag-link");
        if (tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Element t : tags) {
            String txt = t.text().trim();
            if (!txt.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(txt);
            }
        }
        Element desc = card.selectFirst("p");
        if (desc != null) {
            String d = desc.text().trim().replaceAll("\\s+", " ");
            if (!d.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                if (d.length() > MAX_TOPIC_CARD_DESC_CHARS) {
                    d = d.substring(0, MAX_TOPIC_CARD_DESC_CHARS);
                }
                sb.append(d);
            }
        }
        String out = sb.toString().trim();
        if (out.length() > MAX_TOPIC_CARD_FRAGMENT_CHARS) {
            return out.substring(0, MAX_TOPIC_CARD_FRAGMENT_CHARS);
        }
        return out;
    }

    private static String anchorCenteredNeighborhood(Element el, String anchorText) {
        Element article = el.closest("article");
        Element scope;
        if (article != null && article.selectFirst("a.topic-tag.topic-tag-link") != null) {
            scope = article;
        } else {
            scope = ancestorAtDepth(el, DEFAULT_NEIGHBORHOOD_ANCESTOR_HOPS);
            if (scope == null) {
                Element p = el.parent();
                scope = p != null ? p : el;
            }
        }
        String wide = scope.text().replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (wide.length() > MAX_NEIGHBORHOOD_WIDE_CHARS) {
            wide = wide.substring(0, MAX_NEIGHBORHOOD_WIDE_CHARS);
        }
        String needle = anchorText == null ? "" : anchorText.trim();
        int idx = -1;
        if (!needle.isEmpty()) {
            idx = wide.indexOf(needle);
            if (idx < 0) {
                int sp = needle.indexOf(' ');
                if (sp > 1) {
                    idx = wide.indexOf(needle.substring(0, sp));
                }
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        int start = Math.max(0, idx - ANCHOR_CONTEXT_RADIUS);
        int end =
                Math.min(
                        wide.length(),
                        idx + Math.max(needle.length(), 1) + ANCHOR_CONTEXT_RADIUS);
        return wide.substring(start, end).trim();
    }

    private static Element ancestorAtDepth(Element el, int depth) {
        Element p = el;
        for (int i = 0; i < depth && p != null; i++) {
            p = p.parent();
        }
        return p;
    }

    private static String pageMetadataContext(Optional<ExtractedPageMetadata> metaOpt) {
        if (metaOpt.isEmpty()) {
            return "";
        }
        ExtractedPageMetadata m = metaOpt.get();
        String t = m.title() != null ? m.title().trim() : "";
        String d = m.metaDescription() != null ? m.metaDescription().trim() : "";
        if (t.isEmpty()) {
            return d;
        }
        if (d.isEmpty()) {
            return t;
        }
        return t + " " + d;
    }

    private static String mergeScoringContext(String a2, String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (a2 != null && !a2.isBlank()) {
            sb.append(a2.trim());
            sb.append(' ');
        }
        sb.append(a != null ? a.trim() : "");
        sb.append(' ');
        sb.append(b != null ? b.trim() : "");
        String collapsed = sb.toString().trim().replaceAll("\\s+", " ");
        if (collapsed.length() > MAX_LINK_CONTEXT_CHARS) {
            return collapsed.substring(0, MAX_LINK_CONTEXT_CHARS);
        }
        return collapsed;
    }

    private static String filenameFromUrl(String canonicalUrl) {
        int slash = canonicalUrl.lastIndexOf('/');
        String segment =
                slash >= 0 && slash < canonicalUrl.length() - 1
                        ? canonicalUrl.substring(slash + 1)
                        : "image";
        int q = segment.indexOf('?');
        if (q >= 0) {
            segment = segment.substring(0, q);
        }
        int h = segment.indexOf('#');
        if (h >= 0) {
            segment = segment.substring(0, h);
        }
        return segment.isBlank() ? "image" : segment;
    }
}
