# TS-04 Parser And Extraction Specification

## Responsibility

Extract assignment-required artifacts from HTML document.

Parser boundary is strict:
- parser extracts artifacts and returns `ParseResult` only;
- `ParseResult` bundles canonical outlinks (`DiscoveredUrl` list), image references (`ExtractedImage` list), and optional `ExtractedPageMetadata` (e.g. title/description when extracted); see [TS-01](TS-01-interface-contracts.md) data contracts;
- parser MUST NOT call `Storage` directly;
- Stage A ingestion (canonicalize -> policy checks -> dedup/upsert -> link insert) is owned by worker/storage contracts (`TS-02`, `TS-10`).

## Library Requirement

- parser MUST use `Jsoup` as the HTML/DOM parsing engine;
- parser MUST create `Document` with base URL so absolute URL resolution is deterministic;
- `href`, `img`, and `onclick` candidate element selection MUST use Jsoup CSS selectors;
- regex is used only to parse redirect URLs from `onclick` attribute values.

```java
Document doc = Jsoup.parse(html, canonicalUrl);
Elements hrefLinks = doc.select("a[href]");
Elements imageNodes = doc.select("img[src]");
Elements onclickNodes = doc.select("[onclick]");
```

## Required Extraction Targets

- links from `a[href]`;
- links from JavaScript `onclick` redirects (`location.href`, `document.location`);
- image references from `img[src]`;
- anchor/context text for scoring.

## Link Extraction Rules

- resolve all URLs to absolute before ingestion;
- preserve source `from_page_id` for link graph insertion;
- normalize anchor/context text for scoring input;
- skip malformed URLs safely (log and continue).

Concrete extraction example (after resolving and canonicalizing the href per TS-05, and computing relevance):

```java
for (Element link : doc.select("a[href]")) {
    String absolute = link.attr("abs:href");
    String anchorText = link.text();
    String canonical = canonicalizer.canonicalize(absolute, canonicalUrl).canonicalUrl();
    double score = relevanceScorer.compute(canonical, anchorText, surroundingText(link));
    discovered.add(new DiscoveredUrl(
            canonical, targetSiteId, currentPageId, anchorText, surroundingText(link), score));
}
```

## Onclick Extraction Pattern

- regex MUST detect single or double quoted URL assignment patterns;
- parser MUST include text context from clickable element;
- results are passed to Stage A pipeline identically to `href` links.
- non-http(s) schemes are rejected in Stage A (`TS-05`) and MUST NOT trigger fetch/persistence.

Jsoup is used to identify elements containing the `onclick` attribute, but since it does not parse JavaScript, a regular expression is required to extract the target URL from the attribute's string value.

For example, an element might look like:
`<button onclick="location.href='https://example.com/page'">Click Me</button>`

```java
Pattern p = Pattern.compile("(?:location\\.href|document\\.location)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]");
for (Element el : doc.select("[onclick]")) {
    Matcher m = p.matcher(el.attr("onclick"));
    if (m.find()) {
        String extractedUrl = m.group(1);
        // pass to Stage A with anchor/context text
    }
}
```

Accepted assignment variants include both:
- `location.href='...'`
- `document.location="..."`

## Image Extraction Rules

- store image URL reference only; no binary download;
- map filename from the URL path when inferable (e.g. last segment); **`content_type` is always the literal `BINARY`** for every extracted `img[src]` row (assignment requirement);
- persist with `accessed_time`.
- **Storage contract:** `crawldb.image.data` (**`bytea`**) MUST remain **NULL** for this project—only URL metadata (`filename`, `content_type`, `accessed_time`, `page_id`) is in scope ([04-domain-and-scope-definition.md](../04-domain-and-scope-definition.md), [TS-11](TS-11-database-schema-and-migrations.md)).

Example:
- image URL: `https://raw.githubusercontent.com/org/repo/main/docs/img.png`
- persisted metadata:
  - filename: `img.png`
  - content_type: `BINARY`
  - data payload: not stored

## Required Tests

- `href` extraction with relative and absolute URLs;
- `onclick` extraction from multiple assignment variants;
- malformed HTML resilience;
- image extraction count and `BINARY` content type on each reference.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/downloader/extract/`
- key file(s): `downloader/extract/HtmlParser.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/downloader/extract/` and `.../integration/downloader/extract/`

