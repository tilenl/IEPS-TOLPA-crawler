/*
 * TS-04 HTML extraction using Jsoup selectors and regex-only onclick URL parsing.
 *
 * Callers: {@link si.uni_lj.fri.wier.downloader.worker.WorkerLoop}. Emits {@link DiscoveredUrl} rows with
 * {@code fromPageId = 0}; the worker MUST rewrite {@code fromPageId} to the leased source page before persistence
 * (TS-02 Stage B).
 *
 * Created: 2026-03. Major revision: full TS-04 implementation (replaces empty stub).
 */

package si.uni_lj.fri.wier.downloader.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
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
 * Parses one HTML document into links, onclick targets, images, and light metadata.
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

    /** Bound surrounding text so scoring stays stable on huge DOMs. */
    private static final int MAX_SURROUNDING_CHARS = 400;

    /** Placeholder until the worker injects the real source page id (see class Javadoc). */
    private static final long PLACEHOLDER_FROM_PAGE_ID = 0L;

    private final Canonicalizer canonicalizer;
    private final RelevanceScorer relevanceScorer;
    private final Function<String, Long> siteIdForDomain;

    /**
     * @param canonicalizer TS-05 normalizer for every extracted URL string
     * @param relevanceScorer frontier priority input
     * @param siteIdForDomain maps registry host key (see {@link HostKeys#domainKey(String)}) to {@code site.id};
     *     typically wraps {@link si.uni_lj.fri.wier.contracts.Storage#ensureSite(String)}
     */
    public HtmlParser(
            Canonicalizer canonicalizer,
            RelevanceScorer relevanceScorer,
            Function<String, Long> siteIdForDomain) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
        this.relevanceScorer = Objects.requireNonNull(relevanceScorer, "relevanceScorer");
        this.siteIdForDomain = Objects.requireNonNull(siteIdForDomain, "siteIdForDomain");
    }

    @Override
    public ParseResult parse(String canonicalUrl, String html) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl");
        if (html == null || html.isBlank()) {
            return ParseResult.empty();
        }
        Document doc = Jsoup.parse(html, canonicalUrl);
        List<DiscoveredUrl> discovered = new ArrayList<>();
        discovered.addAll(extractHrefLinks(doc, canonicalUrl));
        discovered.addAll(extractOnclickLinks(doc, canonicalUrl));

        List<ExtractedImage> images = extractImages(doc, canonicalUrl);
        java.util.Optional<ExtractedPageMetadata> meta = extractMetadata(doc);

        return new ParseResult(discovered, images, meta);
    }

    private List<DiscoveredUrl> extractHrefLinks(Document doc, String baseCanonical) {
        List<DiscoveredUrl> out = new ArrayList<>();
        Elements links = doc.select("a[href]");
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
                long siteId = siteIdForDomain.apply(HostKeys.domainKey(canonical));
                String anchorText = link.text().trim();
                String context = surroundingText(link);
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

    private List<DiscoveredUrl> extractOnclickLinks(Document doc, String baseCanonical) {
        List<DiscoveredUrl> out = new ArrayList<>();
        Elements nodes = doc.select("[onclick]");
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
                long siteId = siteIdForDomain.apply(HostKeys.domainKey(canonical));
                String anchorText = el.text().trim();
                String context = surroundingText(el);
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
                String ct = inferImageContentType(fn);
                out.add(new ExtractedImage(canonical, fn, ct));
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("skip image: {}", e.toString());
                }
            }
        }
        return out;
    }

    private static java.util.Optional<ExtractedPageMetadata> extractMetadata(Document doc) {
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
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ExtractedPageMetadata(title, desc));
    }

    /**
     * Compact parent chain text so anchor keywords still influence scoring without dumping the whole document.
     */
    private static String surroundingText(Element el) {
        StringBuilder sb = new StringBuilder();
        Element p = el.parent();
        int hops = 0;
        while (p != null && hops < 3 && sb.length() < MAX_SURROUNDING_CHARS) {
            String t = p.ownText();
            if (t != null && !t.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t.trim());
            }
            p = p.parent();
            hops++;
        }
        if (sb.length() > MAX_SURROUNDING_CHARS) {
            return sb.substring(0, MAX_SURROUNDING_CHARS);
        }
        return sb.toString();
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

    private static String inferImageContentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".svg") || lower.endsWith(".svgz")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return null;
    }
}
