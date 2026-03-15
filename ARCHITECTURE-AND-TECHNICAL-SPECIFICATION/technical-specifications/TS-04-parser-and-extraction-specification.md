# TS-04 Parser And Extraction Specification

## Responsibility

Extract assignment-required artifacts from HTML document.

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

## Onclick Extraction Pattern

- regex MUST detect single or double quoted URL assignment patterns;
- parser MUST include text context from clickable element;
- results are passed to Stage A pipeline identically to `href` links.

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

## Image Extraction Rules

- store image URL reference only; no binary download;
- map filename/content type when inferable;
- persist with `accessed_time`.

## Required Tests

- `href` extraction with relative and absolute URLs;
- `onclick` extraction from multiple assignment variants;
- malformed HTML resilience;
- image extraction count and metadata inference.

