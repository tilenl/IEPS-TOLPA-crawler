# Crawler Plan — `fri-wier-IEPS-TOLPA`

---

## 1. Crawler Search Domain

The crawler `fri-wier-IEPS-TOLPA` operates on the `github.com` domain. Its purpose is to gather URLs to GitHub repositories connected with **image segmentation** in computer vision.

It targets a narrow subset of the `github.com` domain: public repositories whose name, short description, README content, or topic tags indicate work on **semantic segmentation**, **instance segmentation**, **medical image segmentation**, segmentation datasets, or segmentation model implementations.

The main crawl units are repository landing pages of the form `https://github.com/{owner}/{repo}`, because these pages contain structured metadata and server-rendered README content. From such pages, the crawler extracts:

- the repository identifier (`{owner}/{repo}`)
- the short description
- topic tags
- README text
- hyperlinks to other repositories or project documentation (from both `href` attributes and `onclick` JavaScript events — see Section 4)
- image URL references found within README content (**note:** image URLs are recorded as strings and stored in the `image` table without fetching the binary data — images are served from `raw.githubusercontent.com` or `camo.githubusercontent.com`, separate domains outside the crawl scope)

The crawler does not aim to clone repositories or traverse the full source tree. This is aligned with the assignment requirement to extract safe textual and structured information such as project descriptions, documentation pages, and README files.

### Relevance Scoring

A page is considered relevant if it contains one or more segmentation-related indicators. The relevance score is computed as a weighted count of matched keywords across the repository's metadata and content fields:


| Field             | Weight  | Rationale                                               |
| ----------------- | ------- | ------------------------------------------------------- |
| Topic tags        | Highest | Explicitly curated by the repo author                   |
| Repository name   | High    | Strongly signals intent                                 |
| Short description | High    | Author-written summary                                  |
| README content    | Lower   | Broad, expensive to extract, may include unrelated text |


**Primary keywords** (highest score contribution): `image segmentation`, `semantic segmentation`, `instance segmentation`, `mask`, `segment anything`, `U-Net`, `DeepLab`, `SegFormer`, `panoptic segmentation`, `SAM`, `MaskRCNN`.

**Secondary keywords** (lower score, used when no primary matches are found): `object detection`, `image recognition`, `feature extraction`. These represent repositories adjacent to the main topic — they receive a non-zero priority so the frontier does not stall if primary-topic URLs are exhausted, but they are deprioritized against primary matches.

Repositories that concern only image classification, generic model collections without segmentation content, or unrelated CV tasks receive a score of zero and are excluded from the frontier.

> ⚠️ **Important:** The relevance score is computed **before** a URL enters the frontier, not after it is dequeued. Scoring is part of the URL ingestion pipeline (see Section 5). This is what makes the crawler *preferential* rather than a filtered BFS — the frontier is a priority queue ordered by score, so higher-relevance URLs are always crawled first.

A secondary scoring benefit is that surrounding anchor text is passed alongside each extracted URL to the scoring function. This allows the scorer to infer relevance from context even when the URL itself contains no segmentation-related keywords (e.g., a link labelled "segmentation benchmark results" pointing to a generic-looking URL).

### Discovery Strategy

Discovery relies on two complementary mechanisms:

1. **Topic pages** — GitHub topic pages (`/topics/{tag}`) are server-side rendered, not blocked by `robots.txt`, and each lists ~30 repositories per paginated page. These are the most efficient discovery source because GitHub has already grouped repositories by tag. Pagination via `?page=N` is followed by the crawler.
2. **Repo-to-repo link following** — Repository landing pages link to related projects, benchmarks, pretrained model hubs, and documentation. These cross-links are extracted and fed into the frontier pipeline for scoring. Linked pages may be on external domains (e.g. `arxiv.org`, `paperswithcode.com`, `huggingface.co`) — these are scored and crawled if relevant.

> ⚠️ **Limitation:** Discovery on `github.com` is constrained by `robots.txt` rules and by the hyperlink structure among repository landing pages. The crawler cannot traverse the file tree (`/tree/` is disallowed), so it cannot discover files within a repository. The collected dataset will represent a topic-focused subset rather than the whole GitHub ecosystem.

---

## 2. Seed URLs

The crawler bootstraps by populating the `page` table with seed URLs set to `page_type_code = 'FRONTIER'`. On startup, if the table is empty, these rows are inserted. The Scheduler then begins dequeuing from the database frontier (see Section 6).


| URL                                                     | Type       | Rationale                                                       |
| ------------------------------------------------------- | ---------- | --------------------------------------------------------------- |
| `https://github.com/topics/image-segmentation`          | Topic page | Direct topic index                                              |
| `https://github.com/topics/semantic-segmentation`       | Topic page | Direct topic index                                              |
| `https://github.com/topics/medical-image-segmentation`  | Topic page | Direct topic index                                              |
| `https://github.com/facebookresearch/segment-anything`  | Repository | Canonical SAM implementation, heavily cross-linked              |
| `https://github.com/open-mmlab/mmsegmentation`          | Repository | Segmentation-dedicated framework, links to 300+ related repos   |
| `https://github.com/jfzhang95/pytorch-deeplab-xception` | Repository | DeepLab reference implementation                                |
| `https://github.com/milesial/Pytorch-UNet`              | Repository | Canonical U-Net implementation, heavily cross-linked            |
| `https://github.com/TexasInstruments/edgeai-modelzoo`   | Repository | Generic model zoo — lower priority seed, useful for cross-links |
| `https://github.com/onnx/models`                        | Repository | Generic model zoo — lower priority seed, useful for cross-links |


> ⚠️ `edgeai-modelzoo` and `onnx/models` are generic model zoos spanning many tasks. As seeds they will generate irrelevant crawl candidates that must be scored and filtered. They are included for their cross-link value but should be treated as low-priority seeds.

---

## 3. GitHub `robots.txt` Compliance

The full `robots.txt` for `github.com` (`User-agent: `* section) disallows the following paths relevant to our crawler:

```
Disallow: /*/tree/          ← entire file browser — blocks directory traversal
Disallow: /*/commits/       ← commit history
Disallow: /*/raw/           ← raw file content
Disallow: /*/archive/       ← zip/tar downloads
Disallow: /gist/            ← all gists
Disallow: /search$          ← search page
Disallow: /*/*/forks
Disallow: /*/*/stargazers
Disallow: /*/*/contributors
Disallow: /*/*/branches
Disallow: /*/*/tags
Disallow: /*/*/pulse
Disallow: /*/*/graphs
Disallow: /*/*/compare
Disallow: /*q=
Disallow: /*tab=*
Disallow: /*author=*
Disallow: /*since=*
Disallow: /*until=*
Disallow: /*ref_cta=*
Disallow: /*ref_page=*
Disallow: /*return_to=*
... (and other query parameter patterns)
```

What this means for our crawler:


| Path                    | Example                              | Allowed?       |
| ----------------------- | ------------------------------------ | -------------- |
| Topic pages             | `/topics/image-segmentation`         | ✅              |
| Topic pagination        | `/topics/image-segmentation?page=2`  | ✅              |
| Repository landing page | `/facebookresearch/segment-anything` | ✅              |
| Individual file blob    | `/torvalds/linux/blob/master/README` | ✅ (not listed) |
| File tree browser       | `/torvalds/linux/tree/master/kernel` | ❌              |
| Raw file content        | `/torvalds/linux/raw/master/README`  | ❌              |
| Commit history          | `/torvalds/linux/commits/`           | ❌              |
| Gists                   | `gist.github.com/...`                | ❌              |
| Search                  | `/search?q=segmentation`             | ❌              |


> ⚠️ The `/*/tree/` disallow rule means the crawler cannot use the file browser to discover files within a repository. Repository landing pages (which render the README) and individual known blob URLs are accessible, but systematic file tree traversal is not permitted.

### `robots.txt` Implementation — `crawler-commons`

We use the `crawler-commons` library (`SimpleRobotRulesParser`) for `robots.txt` parsing. It follows RFC 9309, supports `Crawl-delay` and `Sitemap` extensions, and handles failed fetches gracefully:

- HTTP `4xx` → assumes no `robots.txt` exists → allows all crawling
- HTTP `3xx` or `5xx` → assumes temporary error → returns deny-all rule set

```java
SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
byte[] robotsTxtContent = fetchRobotsTxt("https://github.com/robots.txt");

BaseRobotRules rules = parser.parseContent(
    "https://github.com/robots.txt",
    robotsTxtContent,
    "text/plain",
    "fri-wier-IEPS-TOLPA"
);

if (rules.isAllowed("https://github.com/some-path")) {
    // proceed
}
long crawlDelay = rules.getCrawlDelay();
```

The raw text of `robots.txt` must be persisted to the `site.robots_content` column in the database. Similarly, any `Sitemap` directives found must be stored in `site.sitemap_content`. The in-memory Caffeine cache handles runtime lookups, but the raw content must be written to the `site` row on first encounter of a domain.

```java
// On first encounter of a domain:
String robotsTxtRaw = fetchRobotsTxtAsString(domain);
String sitemapUrl   = extractSitemapUrl(robotsTxtRaw); // may be null

db.upsertSite(domain, robotsTxtRaw, sitemapUrl);

BaseRobotRules rules = parser.parseContent(..., robotsTxtRaw.getBytes(), ...);
robotsCache.put(domain, rules);
```

> ⚠️ The `robots.txt` fetch is itself an HTTP request to the domain and must count against the per-domain rate limiter. Process it through the same Bucket4j rate limiter used for regular page fetches, at higher priority so it resolves before any regular URLs from that domain are dequeued.

> ⚠️ `robots.txt` rules are cached in memory with a 24-hour TTL (Caffeine `LoadingCache`). The raw content is stored in the database once per domain on first fetch. If the crawler restarts, the `site` table provides the persisted raw text and the in-memory cache is re-populated on demand.

---

## 4. HTML Fetching — Headless Browser Required

The assignment requires using a headless browser to ensure content placed into the DOM by JavaScript is correctly captured. This is particularly relevant for external domains that repository READMEs link to — pages on `arxiv.org`, `paperswithcode.com`, `huggingface.co`, or project documentation sites may use JavaScript frameworks (React, Vue, Angular) that serve an empty HTML shell and populate content client-side.

We use **Selenium** with a headless Chromium instance as the rendering layer.

```java
ChromeOptions options = new ChromeOptions();
options.addArguments("--headless=new", "--no-sandbox", "--disable-gpu");
options.addArguments("--user-agent=fri-wier-IEPS-TOLPA");
WebDriver driver = new ChromeDriver(options);

driver.get(url);
new WebDriverWait(driver, Duration.ofSeconds(5))
    .until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));

String html = driver.getPageSource();
Document doc = Jsoup.parse(html);
```

> ⚠️ **Optimization opportunity — domain-aware fetcher:** GitHub renders all repository landing pages and topic pages **server-side**. The README content, description, topic tags, and pagination links are all present in the raw HTTP response — no JavaScript rendering is required. This can be verified by disabling JavaScript in a browser and visiting any GitHub repository; all content still loads. GitHub uses server-side rendering specifically to remain indexable by search engines.
>
> A domain-aware `Fetcher` implementation could bypass the headless browser entirely for the `github.com` domain and use a plain `HttpClient` + Jsoup fetch instead. This would eliminate the overhead of Chromium for the vast majority of fetches in this crawler, while still using the headless browser for external domains where JavaScript rendering may be necessary.
>
> ```java
> // Proposed optimization — to be implemented after the baseline headless path is working
> if (domain.equals("github.com")) {
>     return plainHttpFetch(url);       // ~50–200ms, zero browser overhead
> } else {
>     return headlessBrowserFetch(url); // ~1–3s, full JS rendering
> }
> ```
>
> This is worth implementing as a second pass once the basic headless path is verified, as it would significantly reduce crawl time for the `github.com` portion of the frontier.

### Link Extraction Requirements

The assignment requires extracting links from two sources — both must be implemented:

**1. `href` attributes** (standard anchor tags):

```java
Elements links = doc.select("a[href]");
for (Element link : links) {
    String href = link.attr("abs:href"); // Jsoup resolves relative URLs automatically
    String anchorText = link.text();     // passed to relevance scorer as context
    processExtractedUrl(href, anchorText);
}
```

**2. `onclick` JavaScript events** (`location.href`, `document.location`):

```java
Elements clickable = doc.select("[onclick]");
Pattern locationPattern = Pattern.compile(
    "(?:location\\.href|document\\.location)\\s*=\\s*['\"]([^'\"]+)['\"]"
);
for (Element el : clickable) {
    Matcher m = locationPattern.matcher(el.attr("onclick"));
    if (m.find()) {
        String url = m.group(1);
        processExtractedUrl(url, el.text());
    }
}
```

> ⚠️ `onclick` link extraction is a graded assignment requirement. Omitting it is not acceptable even if such links are rare on GitHub pages.

### Image Detection

Images are detected exclusively via `<img>` tags where the `src` attribute points to an image URL. Each image found is stored as a row in the `image` table with `filename`, `content_type`, and `accessed_time` populated. The `data` column is left `NULL` — the binary image file is not fetched.

```java
Elements images = doc.select("img[src]");
for (Element img : images) {
    String src = img.attr("abs:src");
    String filename = src.substring(src.lastIndexOf('/') + 1);
    db.insertImage(currentPageId, filename, inferContentType(src), Instant.now());
}
```

---

## 5. URL Processing Pipeline

Every URL extracted from a fetched page passes through two distinct pipeline stages.

### Stage A — URL Ingestion (extracted URL → database frontier)

```
Raw URL extracted from page (href or onclick)
        │
        ▼
Resolve relative → absolute (urlcanon)
        │
        ▼
WHATWG canonicalize (urlcanon)
        (lowercase scheme/host, resolve path segments, normalize percent-encoding)
        │
        ▼
Strip fragment (#...)
        (fragments are client-side only and never affect the server response)
        │
        ▼
Apply GitHub parameter allowlist
        (keep only meaningful params — see Section 6)
        │
        ▼
robots.txt disallow check
        (domain-keyed Caffeine cache — discard if disallowed)
        │
        ▼
Bloom filter pre-check
        (probabilistic fast path — if bloom says "seen", skip DB lookup)
        │
        ▼
Database URL existence check
        (SELECT id FROM page WHERE url = ? — authoritative deduplication)
        │
        ├─ URL already exists (any page_type_code)
        │       → do NOT re-insert the page row
        │       → DO insert link(from_page=current, to_page=existing) ← required
        │
        └─ URL is new
                ▼
        Compute relevance score
        (URL path + surrounding anchor text → scorer)
                │
                ▼
        INSERT INTO page (url, page_type_code='FRONTIER', site_id, relevance_score)
        INSERT INTO link (from_page=current_page_id, to_page=new_page_id)
```

> ⚠️ The `link` table must be populated for **every** discovered URL relationship — including cases where the target URL was already crawled. The assignment explicitly requires this: "just add a record into the table `link` accordingly." Skipping link insertion for already-seen URLs is incorrect.

> ⚠️ `robots.txt` checking happens in Stage A (ingestion), not Stage B (fetch). A disallowed URL is discarded before it enters the database — this ensures that if `robots.txt` rules change during the crawl, URLs that were previously disallowed are not permanently poisoned in the seen-set.

> ⚠️ Rate limiting belongs exclusively in Stage B. It governs **when** to fetch, not whether a URL is valid. Applying rate limiting in Stage A would block the ingestion of URLs from fast-responding domains while waiting for slow ones.

### Stage B — Fetch Pipeline (dequeued URL → back into Stage A)

```
SELECT p.* FROM page p
WHERE p.page_type_code = 'FRONTIER'
ORDER BY p.relevance_score DESC
LIMIT 1
FOR UPDATE SKIP LOCKED
        │
        ▼
Per-domain rate limiter (Bucket4j)
        ├─ not ready → release DB lock, reschedule, pick next FRONTIER row
        └─ ready → proceed
        │
        ▼
HTTP fetch (Selenium headless, or plain HttpClient for github.com)
        │
        ├─ Compute SHA-256 of HTML body
        │       └─ Hash already in DB → UPDATE page SET page_type_code='DUPLICATE',
        │                               html_content=NULL
        │
        ├─ HTML  → UPDATE page SET page_type_code='HTML',
        │           html_content=..., http_status_code=..., accessed_time=...
        │
        ├─ Binary file (.doc/.docx/.ppt/.pptx)
        │        → UPDATE page SET page_type_code='BINARY', html_content=NULL
        │           INSERT INTO page_data (page_id, data_type_code, data=NULL)
        │
        └─ Feed all extracted URLs + anchor text into Stage A
```

> ⚠️ `FOR UPDATE SKIP LOCKED` is the PostgreSQL mechanism that safely allows multiple workers to dequeue from the `page` table concurrently. Each worker locks the row it claims; other workers automatically skip past locked rows to the next available `FRONTIER` entry. This replaces the in-memory `AtomicInteger` + `PriorityBlockingQueue` pattern and makes worker coordination persistence-safe across restarts.

---

## 6. URL Canonicalization

We use the `iipc/urlcanon` library with the WHATWG canonicalizer. It implements the same URL parsing rules used by browsers, correctly resolving edge cases such as:

```
HTTP://EXAMPLE.com:80/foo/../bar  →  http://example.com/bar
```

It also produces **SSURT** (Sort-friendly URI Reordering Transform), which inverts domain labels:

```
http://github.com/torvalds/linux  →  SSURT: "com,github,//:http/torvalds/linux"
```

SSURT is used as the internal key in the Bloom filter and as the stored canonical form, because all `github.com` URLs sort together — making domain-level operations (rate limit lookups, domain grouping) cheaper.

```java
ParsedUrl parsed = ParsedUrl.parseUrl(rawUrl);
Canonicalizer.WHATWG.canonicalize(parsed);
String canonical = parsed.toString();
```

> ⚠️ Do **not** use `crawler4j`'s `URLCanonicalizer` — even as a copied utility class. The assignment explicitly forbids `crawler4j` as a dependency. All canonicalization must go through `iipc/urlcanon`.

> ⚠️ urlcanon's WHATWG canonicalizer does **not** strip query parameters. `/topics/image-segmentation?page=2` is preserved intact. This is correct behaviour — the parameter allowlist step below handles semantic filtering.

### GitHub Parameter Allowlist

A separate allowlist step removes tracking noise while preserving meaningful parameters, preventing false duplicates in deduplication:

```java
// Without this step, these two URLs would not deduplicate:
// /topics/image-segmentation?page=1
// /topics/image-segmentation?page=1&utm_source=homepage

private static final Set<String> MEANINGFUL_PARAMS = Set.of(
    "page"   // pagination on topic pages
    // "q" and "tab" are disallowed by robots.txt anyway
);

String stripNoisyParams(String url) {
    URI uri = URI.create(url);
    if (uri.getQuery() == null) return url;

    String cleanQuery = Arrays.stream(uri.getQuery().split("&"))
        .filter(param -> MEANINGFUL_PARAMS.contains(param.split("=")[0]))
        .sorted()
        .collect(Collectors.joining("&"));

    return new URI(
        uri.getScheme(), uri.getHost(), uri.getPath(),
        cleanQuery.isEmpty() ? null : cleanQuery,
        null // fragment already stripped in previous step
    ).toString();
}
```


| Input                         | Output                                                  |
| ----------------------------- | ------------------------------------------------------- |
| `?page=2`                     | `?page=2` ✅ kept                                        |
| `?page=2&utm_source=homepage` | `?page=2` ✅ noise stripped                              |
| `?ref_cta=signup`             | *(no query)* ✅ stripped (also disallowed by robots.txt) |
| `?tab=readme`                 | *(no query)* ✅ stripped (disallowed by robots.txt)      |


---

## 7. Database Design

The crawler uses PostgreSQL with the schema defined in `crawldb.sql`. The base schema must not be modified, but can be extended with additional columns or tables (e.g. `relevance_score`, `content_hash`).

### Schema Overview

```
site          — one row per domain (github.com, arxiv.org, ...)
  ├── page    — one row per URL, including FRONTIER entries
  │     ├── link       — directed edge: from_page → to_page
  │     ├── page_data  — binary file metadata (.doc, .docx, .ppt, .pptx)
  │     └── image      — image references found on the page
  └── (robots_content, sitemap_content stored on site row)
```

### The `page_type_code` Lifecycle

Every URL that enters the system gets a row in `page` immediately upon discovery, tagged as `FRONTIER`. The type code is promoted as the crawl progresses:


| Code        | Meaning                                        | `html_content` |
| ----------- | ---------------------------------------------- | -------------- |
| `FRONTIER`  | Discovered, not yet fetched                    | `NULL`         |
| `HTML`      | Successfully fetched HTML page                 | populated      |
| `BINARY`    | Binary file (`.doc`, `.docx`, `.ppt`, `.pptx`) | `NULL`         |
| `DUPLICATE` | Content hash matches an existing page          | `NULL`         |


The database **is** the frontier queue. The Scheduler reads `WHERE page_type_code = 'FRONTIER' ORDER BY relevance_score DESC` — there is no separate in-memory priority queue. This means the frontier survives process restarts automatically.

### URL Duplicates vs Content Duplicates

These are two distinct concepts that must not be conflated:

**URL duplicate** — a URL that has already been seen (regardless of its current `page_type_code`). Handling: do not insert a new `page` row; do insert a `link` row recording the relationship.

**Content duplicate** — a URL with a different address whose fetched HTML body hashes to the same value as an already-crawled page. Handling: update `page_type_code = 'DUPLICATE'`, set `html_content = NULL`.

```java
// After fetching HTML:
String contentHash = sha256(htmlBody);
Optional<Integer> existingPageId = db.findPageByContentHash(contentHash);

if (existingPageId.isPresent()) {
    db.updatePage(currentPageId, "DUPLICATE", null, httpStatus, now);
    db.insertLink(currentPageId, existingPageId.get()); // duplicate → original
} else {
    db.updatePage(currentPageId, "HTML", htmlBody, httpStatus, now);
    db.storeContentHash(currentPageId, contentHash);
}
```

> ⚠️ The `content_hash` column is an extension to the base schema — add it as `page.content_hash VARCHAR(64)`. The assignment explicitly suggests extending the schema with a hash for duplicate detection.

### Binary Files and Images

**Binary files** (`.doc`, `.docx`, `.ppt`, `.pptx`) — set `page_type_code = 'BINARY'`, `html_content = NULL`, and insert a row in `page_data` with the appropriate `data_type_code`. The `data` field in `page_data` is left `NULL` — binary content is not downloaded.

**Images** — insert a row in the `image` table per `<img src="...">` found on the page. Populate `filename`, `content_type`, and `accessed_time`. The `data` column is left `NULL` — image binaries are not fetched.

### Multi-worker Safety

Multiple workers concurrently dequeue from the `page` table using PostgreSQL row-level locking:

```sql
SELECT id, url, relevance_score
FROM page
WHERE page_type_code = 'FRONTIER'
ORDER BY relevance_score DESC
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

`SKIP LOCKED` causes each worker to skip rows already claimed by other workers, ensuring no two workers process the same URL simultaneously — without any application-level locking.

**Termination condition:** the crawl is complete when `SELECT COUNT(*) FROM page WHERE page_type_code = 'FRONTIER'` returns zero and no workers currently hold `FOR UPDATE` locks.

---

## 8. Deduplication

Deduplication operates at two levels:

### Level 1 — URL Deduplication (has this URL been seen before?)

A two-layer approach avoids a database round-trip for every extracted URL:

**Layer 1 — Guava `BloomFilter`** (probabilistic pre-check, ~14MB for 10M URLs at 0.1% FPP): eliminates the vast majority of duplicates with near-zero cost. A `false` result from `put()` means the URL is definitely already known — skip the DB lookup.

**Layer 2 — Database unique constraint** (authoritative): any URL that passes the Bloom filter is checked against the `page.url` unique constraint via `INSERT ... ON CONFLICT DO NOTHING`. The database is the single source of truth.

```java
boolean isNewUrl(String canonicalUrl) {
    // Fast path: bloom says "definitely seen" → skip DB
    if (!bloomSeen.put(canonicalUrl)) return false;
    // Authoritative check: attempt insert, return whether it succeeded
    return db.insertFrontierIfAbsent(canonicalUrl, siteId, relevanceScore);
}
```

> ⚠️ The Bloom filter is a **performance optimization on top of the database**, not a replacement for it. The database `url` unique constraint is the authoritative deduplication mechanism.

> ⚠️ Guava's `BloomFilter` is fixed-size at creation and cannot be resized. Set the expected insertion count conservatively high — significantly exceeding it causes the false positive rate to degrade, causing valid new URLs to be silently skipped.

### Level 2 — Content Deduplication (does this page content already exist?)

After fetching, a SHA-256 hash of the HTML body is computed and checked against `page.content_hash`. A match means the page is a content duplicate regardless of its URL — set `page_type_code = 'DUPLICATE'`. See Section 7 for the full handling logic.

---

## 9. Rate Limiting — Bucket4j

We use **Bucket4j** for per-domain rate limiting. The assignment sets a hard minimum crawl delay floor:

> "do not send request to the same server more often than **one request in 5 seconds** (not only domain but also IP)"

The Bucket4j configuration must enforce this floor regardless of what `robots.txt` specifies. If `robots.txt` specifies a longer `Crawl-delay`, that value takes precedence over the 5-second minimum.

```java
long buildCrawlDelayMs(String domain, BaseRobotRules rules) {
    long robotsDelayMs = rules.getCrawlDelay(); // -1 if not specified
    long minimumDelayMs = 5_000;                // assignment hard floor
    return Math.max(robotsDelayMs > 0 ? robotsDelayMs : 0, minimumDelayMs);
}
```

Per-domain buckets are stored in a Caffeine `LoadingCache` with a 1-hour inactivity eviction:

```java
LoadingCache<String, Bucket> domainBuckets = Caffeine.newBuilder()
    .expireAfterAccess(1, TimeUnit.HOURS)
    .maximumSize(100_000)
    .build(domain -> buildBucketForDomain(domain));
```

`tryConsumeAndReturnRemaining()` returns the **exact nanoseconds** until the next token, enabling precise rescheduling without hot-polling:

```java
ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
if (probe.isConsumed()) {
    fetchUrl(url);
} else {
    long delayNs = probe.getNanosToWaitForRefill();
    // Release the DB row lock and reschedule — worker immediately picks up the next FRONTIER row
    scheduler.schedule(() -> requeueUrl(url), delayNs, TimeUnit.NANOSECONDS);
}
```

> ⚠️ Workers must **never block** on a rate limiter. If a domain is not ready, the URL is rescheduled and the worker immediately queries the next available `FRONTIER` row from the database.

### Exponential Backoff on Errors

HTTP `429` or `503` responses trigger exponential backoff per domain:

```java
ConcurrentHashMap<String, Integer> failureCounts = new ConcurrentHashMap<>();

long getDelayMs(String domain, int httpStatus) {
    if (httpStatus == 429 || httpStatus == 503) {
        int failures = failureCounts.merge(domain, 1, Integer::sum);
        return Math.min(5_000L * (1L << failures), MAX_BACKOFF_MS); // 5s, 10s, 20s, 40s...
    }
    failureCounts.put(domain, 0);
    return buildCrawlDelayMs(domain, robotsCache.get(domain));
}
```

---

## 10. Concurrency Model

The crawler uses **Java 21 virtual threads** via `Executors.newVirtualThreadPerTaskExecutor()`. Virtual threads make blocking I/O essentially free — each worker can block on a database query or HTTP response without consuming an OS thread.

### Number of Workers and Realistic Throughput

The number of parallel crawlers is a startup parameter:

```
ieps-tolpa --n-crawlers 5
```

> ⚠️ **Single-domain throughput constraint:** With a 5-second minimum crawl delay on `github.com` and one rate-limiter token per 5 seconds, fetch throughput is capped at **1 fetch per 5 seconds** regardless of worker count. Multiple workers remain valuable because they pipeline the non-fetch stages concurrently:
>
> ```
> Worker 1: [rate-limit wait]──fetch──[parse + score + DB write]──[wait]──
> Worker 2:      [rate-limit wait]──fetch──[parse + score + DB write]──
> Worker 3:           [rate-limit wait]──fetch──[parse + score + DB write]──
> ```
>
> Workers are staggered — while one is fetching, others are parsing or writing to the database. However, the practical ceiling on useful workers for a strict single-domain crawl is approximately **2–3**, given that parse + DB write takes ~0.5s and the rate limit window is 5s. Additional workers beyond that point provide no fetch throughput benefit for `github.com`.
>
> The ceiling rises naturally as the frontier accumulates URLs from external domains (linked from GitHub READMEs) — each external domain has its own independent rate limiter, so workers assigned to `arxiv.org` or `huggingface.co` URLs operate concurrently without interfering with the `github.com` rate limiter.

If the worker count is not specified, the default is:

```java
int estimateWorkerCount() {
    int cpuCores = Runtime.getRuntime().availableProcessors();
    // 2-3 workers saturate the github.com rate limiter
    // Additional workers handle external domains discovered via cross-links
    return Math.min(cpuCores * 2, 8);
}
```

### Producer-Consumer Pattern

Each worker is both a consumer (dequeues a `FRONTIER` row from the database) and a producer (inserts new `FRONTIER` rows into the database after parsing). The database frontier replaces the in-memory `PriorityBlockingQueue` from the previous design.

```java
void workerLoop(DataSource db) {
    while (true) {
        Optional<FrontierRow> row = db.dequeueNextFrontier(); // SELECT ... FOR UPDATE SKIP LOCKED
        if (row.isEmpty()) {
            if (noOtherWorkersActive()) break; // termination
            Thread.sleep(500);
            continue;
        }
        try {
            fetchAndProcess(row.get()); // fetch → parse → Stage A for each extracted URL
        } catch (Exception e) {
            db.markPageAsError(row.get().id(), e.getMessage());
        }
    }
}
```

---

## 11. Code Architecture

The system is divided into the following components, each defined as a Java interface implemented in a concrete class:


| Interface             | Responsibility                                                                    |
| --------------------- | --------------------------------------------------------------------------------- |
| `Scheduler`           | Manages the worker pool lifecycle, startup, and graceful shutdown                 |
| `Frontier`            | Database-backed priority queue — wraps `FOR UPDATE SKIP LOCKED` dequeue logic     |
| `Worker`              | Dequeues a URL, delegates to Fetcher, Parser, Scorer, and Storage                 |
| `Fetcher`             | HTTP layer — domain-aware: headless Selenium or plain HttpClient per domain       |
| `Parser`              | Extracts structured data, `href` links, `onclick` links, and image refs from HTML |
| `Canonicalizer`       | Normalises a raw URL through the full ingestion pipeline                          |
| `RobotsTxtCache`      | Domain-keyed Caffeine cache of parsed `robots.txt` rules + DB persistence         |
| `RateLimiterRegistry` | Per-domain Bucket4j bucket map with Caffeine eviction                             |
| `RelevanceScorer`     | Assigns a priority score to a URL given its path and surrounding anchor text      |
| `ContentHasher`       | Computes SHA-256 of HTML body; checks and stores hashes for content deduplication |
| `Storage`             | All database operations — page CRUD, link insertion, image insertion, site upsert |


> Each component is defined as a Java interface and implemented in a concrete class. No component should depend directly on a concrete implementation of another — always depend on the interface. This allows individual components to be replaced and tested in isolation with mock implementations.

All code must be documented with concise Javadoc. Non-obvious logic must include inline comments explaining *why*, not *what*.

---

## 12. Libraries Summary


| Purpose                                        | Library                                      |
| ---------------------------------------------- | -------------------------------------------- |
| Headless browser (JS rendering)                | Selenium + ChromeDriver                      |
| HTML parsing                                   | Jsoup                                        |
| Plain HTTP client (GitHub domain optimization) | Java 21 `HttpClient`                         |
| URL canonicalization                           | `iipc/urlcanon`                              |
| `robots.txt` parsing                           | `crawler-commons` (`SimpleRobotRulesParser`) |
| Rate limiting                                  | Bucket4j                                     |
| URL deduplication pre-check (probabilistic)    | Guava `BloomFilter`                          |
| `robots.txt` cache + domain bucket cache       | Caffeine `LoadingCache`                      |
| Database                                       | PostgreSQL + JDBC                            |
| Concurrency                                    | Java 21 virtual threads                      |
| Multi-worker frontier dequeue                  | PostgreSQL `FOR UPDATE SKIP LOCKED`          |


> ⚠️ The following libraries are **explicitly forbidden** by the assignment and must not be used or transitively imported: Scrapy, Apache Nutch, `crawler4j`, gecco, Norconex HTTP Collector, webmagic, Webmuncher. In particular, do not copy code from `crawler4j` (e.g. its `URLCanonicalizer`) even as a standalone utility — use `iipc/urlcanon` exclusively.

---

## Open Questions & TODOs

- **Frontier strategy detail** — Write a detailed plan of the preferential frontier strategy as described in `vaje-2-Preferential-crawling.ipynb`. Include specifics on score computation, and how to map to the `relevance_score` column stored in the database. Also include how we can use bag of words, to test against many relevant words,
- **Python Jupyter environment** — Are there any benefits of using the Python Jupyter environment for writing the crawler, as written in Section 3 of `assignment.md`? Evaluate whether Java or Python is the better fit given the requirements for multiple parallel workers and PostgreSQL integration.
- **Testing and debugging strategy** — Define how each component can be tested in isolation. Propose a staged integration approach: Fetcher → Parser → Canonicalizer → Frontier → Storage. Define what observable output each component should produce to localize failures.
- **GitHub API usage** — Confirm with the professor whether the GitHub REST API is permitted. It unlocks structured access to metadata and file trees without `robots.txt` restrictions, which would significantly change the fetching and parsing architecture.
- **Contextual URL scoring implementation** — Finalise the implementation where surrounding anchor text is passed alongside each extracted URL to the scoring function.
- **Document splitting** — Split this plan into per-component specification files for the implementation phase. Proposed split: `01-domain.md`, `02-url-pipeline.md`, `03-fetcher.md`, `04-frontier.md`, `05-parser.md`, `06-storage.md`, `07-concurrency.md`. Each file should define the Java interface, describe the logic flow, specify libraries, and include a section on programming patterns.
- **Domain section expansion** — Expand the crawl domain section with more specifics about what constitutes a relevant page, how the relevance score maps to queue ordering, and edge cases in link discovery.
- `**abhinavsingh/fuge` reference** — Review `https://github.com/abhinavsingh/fuge` and assess whether its crawler architecture has patterns applicable to our implementation.
- **Pre-implementation review** — Confirm all design decisions are resolved before moving into the specification writing phase. Identify any remaining gaps.
- **Simple down the deduplication checks** - using Guavas BloomFilter can speed up the process, but for now lets simplify the process by looking into the database if we have seen the URL. No layer 1 is needed for now.

