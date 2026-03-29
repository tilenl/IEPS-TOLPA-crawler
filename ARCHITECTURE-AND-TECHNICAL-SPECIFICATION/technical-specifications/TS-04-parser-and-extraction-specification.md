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

**Crawl outlinks (href + onclick):** selectors MUST be restricted to **`body` descendants** in the **parsed** DOM (`body a[href]`, `body [onclick]`). Elements that remain under `<head>` after HTML5 tree construction (e.g. typical `<script>` nodes) therefore do not contribute outlinks. **Note:** invalid markup such as `<a href>` placed in `<head>` is often **re-parented into `<body>`** by the HTML5 algorithm; those anchors may still match `body a[href]` and be discovered. Image and metadata extraction are unchanged (images may appear anywhere in the document the parser exposes; title/meta remain head-based via `Document` APIs).

```java
Document doc = Jsoup.parse(html, canonicalUrl);
Elements hrefLinks = doc.select("body a[href]");
Elements imageNodes = doc.select("img[src]");
Elements onclickNodes = doc.select("body [onclick]");
```

## Required Extraction Targets

- links from `body a[href]` (anchors inside `<body>` only);
- links from JavaScript `onclick` redirects (`location.href`, `document.location`) on elements inside `<body>` only (`body [onclick]`);
- image references from `img[src]`;
- anchor/context text for scoring.

## Link Extraction Rules

- resolve all URLs to absolute before ingestion;
- preserve source `from_page_id` for link graph insertion;
- normalize anchor/context text for scoring input;
- skip malformed URLs safely (log and continue);
- only nodes under the parsed `<body>` element qualify; nodes remaining under `<head>` do not (see HTML5 reparenting note above).

Concrete extraction example (after resolving and canonicalizing the href per TS-05, and computing relevance):

```java
for (Element link : doc.select("body a[href]")) {
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
- only elements matching `body [onclick]` are considered (same head/body rule as `a[href]`).

Jsoup is used to identify elements containing the `onclick` attribute, but since it does not parse JavaScript, a regular expression is required to extract the target URL from the attribute's string value.

For example, an element might look like:
`<button onclick="location.href='https://example.com/page'">Click Me</button>`

```java
Pattern p = Pattern.compile("(?:location\\.href|document\\.location)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]");
for (Element el : doc.select("body [onclick]")) {
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

- `href` extraction with relative and absolute URLs (inside `body`);
- `onclick` extraction from multiple assignment variants (inside `body`);
- `onclick` on a node that **remains** under `<head>` after parse (e.g. `<script>` in head) MUST NOT produce discovered crawl outlinks;
- malformed HTML resilience;
- image extraction count and `BINARY` content type on each reference.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/downloader/extract/`
- key file(s): `downloader/extract/HtmlParser.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/downloader/extract/` and `.../integration/downloader/extract/`

