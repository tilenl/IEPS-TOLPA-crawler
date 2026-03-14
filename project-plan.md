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
- hyperlinks to other repositories or project documentation
- image URL references found within README content (note: image URLs are recorded as strings only — the image files themselves are not fetched, as they are served from raw.githubusercontent.com or camo.githubusercontent.com, which are separate domains with their own robots.txt rules)

The crawler does not aim to clone repositories or traverse the full source tree. This is aligned with the assignment requirement to extract safe textual and structured information such as project descriptions, documentation pages, and README files.

### Relevance Scoring

A page is considered relevant if it contains one or more segmentation-related indicators. The relevance score is computed as a weighted count of matched keywords across the repository's metadata and content fields:

| Field | Weight | Rationale |
|---|---|---|
| Topic tags | Highest | Explicitly curated by the repo author |
| Repository name | High | Strongly signals intent |
| Short description | High | Author-written summary |
| README content | Lower | Broad, expensive to extract, may include unrelated text |

**Primary keywords** (highest score contribution): `image segmentation`, `semantic segmentation`, `instance segmentation`, `mask`, `segment anything`, `U-Net`, `DeepLab`, `SegFormer`, `panoptic segmentation`, `SAM`, `MaskRCNN`.

**Secondary keywords** (lower score, used when no primary matches are found): `object detection`, `image recognition`, `feature extraction`. These represent repositories adjacent to the main topic — they receive a non-zero priority so the frontier does not stall if primary-topic URLs are exhausted, but they are deprioritized against primary matches.

Repositories that concern only image classification, generic model collections without segmentation content, or unrelated CV tasks receive a score of zero and are excluded from the frontier.

> ⚠️ **Important:** The relevance score is computed **before** a URL enters the frontier, not after it is dequeued. Scoring is part of the URL ingestion pipeline (see Section 5). This is what makes the crawler *preferential* rather than a filtered BFS — the frontier is a priority queue ordered by score, so higher-relevance URLs are always crawled first.

A secondary scoring benefit is that surrounding anchor text is passed alongside each extracted URL to the scoring function. This allows the scorer to infer relevance from context even when the URL itself contains no segmentation-related keywords (e.g., a link labelled "segmentation benchmark results" pointing to a generic-looking URL).

### Discovery Strategy

Discovery relies on two complementary mechanisms:

1. **Topic pages** — GitHub topic pages (`/topics/{tag}`) are server-side rendered, not blocked by `robots.txt`, and each lists ~30 repositories per paginated page. These are the most efficient discovery source because GitHub has already grouped repositories by tag. Pagination via `?page=N` is followed by the crawler.

2. **Repo-to-repo link following** — Repository landing pages link to related projects, benchmarks, pretrained model hubs, and documentation. These cross-links are extracted and fed into the frontier pipeline for scoring.

> ⚠️ **Limitation:** Discovery is constrained by GitHub's `robots.txt` rules and by the hyperlink structure among repository landing pages. The crawler cannot traverse the file tree (`/tree/` is disallowed), so it cannot discover files within a repository. The collected dataset will represent a topic-focused subset rather than the whole GitHub ecosystem.

---

## 2. Seed URLs

| URL | Type | Rationale |
|---|---|---|
| `https://github.com/topics/image-segmentation` | Topic page | Direct topic index |
| `https://github.com/topics/semantic-segmentation` | Topic page | Direct topic index |
| `https://github.com/topics/medical-image-segmentation` | Topic page | Direct topic index |
| `https://github.com/facebookresearch/segment-anything` | Repository | Canonical SAM implementation, heavily cross-linked |
| `https://github.com/open-mmlab/mmsegmentation` | Repository | Segmentation-dedicated framework, links to 300+ related repos |
| `https://github.com/jfzhang95/pytorch-deeplab-xception` | Repository | DeepLab reference implementation |
| `https://github.com/milesial/Pytorch-UNet` | Repository | Canonical U-Net implementation, heavily cross-linked |
| `https://github.com/TexasInstruments/edgeai-modelzoo` | Repository | Generic model zoo — lower priority seed, useful for cross-links |
| `https://github.com/onnx/models` | Repository | Generic model zoo — lower priority seed, useful for cross-links |

> ⚠️ `edgeai-modelzoo` and `onnx/models` are generic model zoos spanning many tasks. As seeds they will generate irrelevant crawl candidates that must be scored and filtered. They are included for their cross-link value but should be treated as low-priority seeds.

---

## 3. GitHub `robots.txt` Compliance

The full `robots.txt` for `github.com` (`User-agent: *` section) disallows the following paths relevant to our crawler:

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

| Path | Example | Allowed? |
|---|---|---|
| Topic pages | `/topics/image-segmentation` | ✅ |
| Topic pagination | `/topics/image-segmentation?page=2` | ✅ |
| Repository landing page | `/facebookresearch/segment-anything` | ✅ |
| Individual file blob | `/torvalds/linux/blob/master/README` | ✅ (not listed) |
| File tree browser | `/torvalds/linux/tree/master/kernel` | ❌ |
| Raw file content | `/torvalds/linux/raw/master/README` | ❌ |
| Commit history | `/torvalds/linux/commits/` | ❌ |
| Gists | `gist.github.com/...` | ❌ |
| Search | `/search?q=segmentation` | ❌ |

> ⚠️ The `/*/tree/` disallow rule means the crawler cannot use the file browser to discover files within a repository. Repository landing pages (which render the README) and individual known blob URLs are accessible, but systematic file tree traversal is not permitted.

### `robots.txt` Implementation — `crawler-commons`

We use the `crawler-commons` library (`SimpleRobotRulesParser`) for `robots.txt` parsing. It is the most production-hardened Java option, follows RFC 9309, supports `Crawl-delay` and `Sitemap` extensions, and handles failed fetches gracefully:

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

> ⚠️ `robots.txt` rules are per-domain, not per-URL. Cache the parsed rules using a domain-keyed Caffeine cache with a 24-hour TTL to avoid redundant fetches. For a single-domain crawler like ours this is less critical, but it is still good practice — `robots.txt` can change during a long crawl session.

```java
LoadingCache<String, BaseRobotRules> robotsCache = Caffeine.newBuilder()
    .maximumSize(1_000)
    .expireAfterWrite(24, TimeUnit.HOURS)
    .build(domain -> fetchAndParseRobots(domain));
```

> ⚠️ The `robots.txt` fetch itself is an HTTP request to `github.com` and should be counted against the domain's rate limit. Process it through the same per-domain rate limiter used for regular page fetches, with higher priority so it is resolved before any regular URLs from that domain are dequeued.

---

## 4. HTML Fetching — No Headless Browser Required

GitHub renders all repository landing pages and topic pages **server-side**. The full README content, description, topic tags, and pagination links are present in the raw HTML response. No JavaScript execution is required.

This can be verified by disabling JavaScript in a browser and visiting any GitHub repository — all content still loads. GitHub uses server-side rendering specifically to remain indexable by search engines.

A plain `HttpClient` + Jsoup fetch is sufficient for all pages in scope:

```java
HttpClient client = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build();

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://github.com/facebookresearch/segment-anything"))
    .header("User-Agent", "fri-wier-IEPS-TOLPA (contact@student.uni-lj.si)")
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
Document doc = Jsoup.parse(response.body());
```

### Extracting Structured Data from a Repository Page

```java
// Repository identifier — from URL or OG meta tag
String identifier = doc.selectFirst("meta[property=og:url]").attr("content");
// → "https://github.com/facebookresearch/segment-anything"

// Short description
String description = doc.selectFirst("p[itemprop=about]").text();

// Topic tags
Elements topics = doc.select("a.topic-tag");
// → ["image-segmentation", "computer-vision", "deep-learning", ...]

// README content
String readme = doc.selectFirst("article.markdown-body").text();
```

### Extracting Data from a Topic Page

```java
// Each repository card on a topic page
Elements repoCards = doc.select("article.border");

for (Element card : repoCards) {
    String repoPath = card.selectFirst("h3 a").attr("href");
    // → "/facebookresearch/segment-anything"

    String description = card.selectFirst("p.text-gray").text();

    Elements tags = card.select("a.topic-tag");
    // → ["image-segmentation", "computer-vision", ...]
}

// Pagination — present on topic pages, absent on repository pages
Element nextLink = doc.selectFirst("a[rel=next]");
if (nextLink != null) {
    String nextPage = nextLink.attr("href");
    // → "/topics/image-segmentation?page=2"
}
```

> ⚠️ The `a[rel=next]` selector is the correct way to detect and follow pagination. Do not construct `?page=N` URLs manually — always follow the link present in the HTML to avoid constructing URLs for pages that do not exist.

---

## 5. URL Processing Pipeline

Every URL extracted from a fetched page passes through two distinct pipeline stages before it can be fetched.

### Stage A — URL Ingestion (extracted URL → frontier)

```
Raw URL extracted from page
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
        (keep only meaningful params — see below)
        │
        ▼
robots.txt disallow check
        (domain-keyed cache lookup — discard if disallowed)
        │
        ▼
Bloom filter + exact set deduplication check
        (discard if already seen)
        │
        ▼
Relevance scoring
        (score URL using surrounding context + URL content)
        │
        ▼
Insert into priority frontier queue (ordered by score)
```

### Stage B — Fetch Pipeline (dequeued URL → back into Stage A)

```
URL dequeued from frontier
        │
        ▼
Per-domain rate limiter (Bucket4j)
        ├─ not ready → reschedule via DelayQueue, pick next URL from frontier
        └─ ready → proceed
        │
        ▼
HTTP fetch (HttpClient + Jsoup)
        │
        ▼
Parse page → extract URLs and structured content
        │
        ▼
Feed each extracted URL into Stage A
```

> ⚠️ `robots.txt` checking happens in Stage A (ingestion), not Stage B (fetch). A disallowed URL is discarded before it is marked as seen — this ensures that if `robots.txt` rules change during the crawl, URLs that were previously disallowed can be reconsidered in future sessions.

> ⚠️ Rate limiting belongs exclusively in Stage B. It governs **when** to fetch, not whether a URL is valid. Applying rate limiting in Stage A would incorrectly block URL ingestion.

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

SSURT is useful as the frontier's internal key because all `github.com` URLs sort together, making domain-level operations (rate limit lookups, domain grouping) cheaper.

```java
ParsedUrl parsed = ParsedUrl.parseUrl(rawUrl);
Canonicalizer.WHATWG.canonicalize(parsed);
String canonical = parsed.toString();
// SSURT: WhatwgCanonicalizer.ssurt(parsed)
```

> ⚠️ urlcanon's WHATWG canonicalizer does **not** strip query parameters. `/topics/image-segmentation?page=2` is preserved intact — it will not be collapsed to `/topics/image-segmentation`. This is the correct behaviour for our crawler.

### GitHub Parameter Allowlist

urlcanon does not filter query parameters by meaning. A separate allowlist step is required to remove tracking noise while preserving meaningful parameters:

```java
// Without this step, these two URLs would not deduplicate:
// /topics/image-segmentation?page=1
// /topics/image-segmentation?page=1&utm_source=homepage

private static final Set<String> MEANINGFUL_PARAMS = Set.of(
    "page"   // pagination on topic pages
    // "q" and "tab" are meaningful in theory but disallowed by robots.txt anyway
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

Result:

| Input | Output |
|---|---|
| `?page=2` | `?page=2` ✅ kept |
| `?page=2&utm_source=homepage` | `?page=2` ✅ noise stripped |
| `?ref_cta=signup` | *(no query)* ✅ stripped (also disallowed by robots.txt) |
| `?tab=readme` | *(no query)* ✅ stripped (disallowed by robots.txt) |

---

## 7. Deduplication

Canonicalization produces a consistent string key. Deduplication checks whether that key has been seen before. A two-layer hybrid approach is used:

**Layer 1 — Guava `BloomFilter`** (probabilistic, ~14MB for 10M URLs at 0.1% false positive rate): eliminates the vast majority of duplicates with near-zero allocation per check.

**Layer 2 — Caffeine bounded exact cache** (precise, memory-bounded): handles the rare false positives from the Bloom filter.

```java
// Layer 1: probabilistic — eliminates ~99.9% of duplicates cheaply
BloomFilter<String> bloomSeen = BloomFilter.create(
    Funnels.stringFunnel(StandardCharsets.UTF_8),
    10_000_000,
    0.001
);

// Layer 2: exact — bounded to prevent unbounded memory growth
Cache<String, Boolean> exactSeen = Caffeine.newBuilder()
    .maximumSize(2_000_000)
    .build();

boolean isNew(String canonical) {
    if (!bloomSeen.put(canonical)) return false; // bloom says seen → fast path discard
    return exactSeen.asMap().putIfAbsent(canonical, true) == null;
}
```

> ⚠️ Guava's `BloomFilter` is fixed-size at creation and cannot be resized. If the crawl significantly exceeds the expected URL count, false positive rates will degrade and valid URLs may be skipped. Set the expected insertion count conservatively high.

> ⚠️ `bloomSeen.put()` returns `true` if the element was definitely not present before insertion (bits changed), and `false` if it may have been present. This is the correct check — do not use `mightContain()` separately, as that would introduce a race condition between the check and the insert.

---

## 8. Rate Limiting — Bucket4j

We use **Bucket4j** for per-domain rate limiting. It is preferred over Guava's `RateLimiter` because:

- Its `tryConsumeAndReturnRemaining()` returns the **exact nanoseconds** until the next token is available — used to schedule precise re-queue delays without hot cycling
- It is lock-free and uses integer arithmetic (no float rounding errors)
- It supports multiple bandwidth rules per domain (e.g., per-second limit AND per-day quota simultaneously)

```java
// Per-domain bucket — created on first encounter, evicted after 1 hour of inactivity
LoadingCache<String, Bucket> domainBuckets = Caffeine.newBuilder()
    .expireAfterAccess(1, TimeUnit.HOURS)
    .maximumSize(100_000)
    .build(domain -> buildBucketForDomain(domain));

// At fetch time (Stage B):
String domain = URI.create(url).getHost();
Bucket bucket = domainBuckets.get(domain);
ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

if (probe.isConsumed()) {
    fetchUrl(url);
} else {
    long delayNs = probe.getNanosToWaitForRefill();
    scheduler.schedule(() -> frontier.offer(url), delayNs, TimeUnit.NANOSECONDS);
    // Worker immediately picks up the next URL — no thread is parked
}
```

> ⚠️ Workers must **never block** on a rate limiter. If a domain is not ready, the URL is rescheduled and the worker immediately dequeues the next available URL. This is the core reason Guava's blocking `acquire()` is unsuitable — it parks the thread (or virtual thread) and prevents other URLs from being processed in the meantime.

> ⚠️ Exponential backoff must be applied per domain on HTTP `429` or `503` responses. A fixed crawl delay that ignores server-side throttling signals will cause repeated failures and potential IP bans.

```java
ConcurrentHashMap<String, Integer> failureCounts = new ConcurrentHashMap<>();

long getDelay(String domain, int httpStatus) {
    if (httpStatus == 429 || httpStatus == 503) {
        int failures = failureCounts.merge(domain, 1, Integer::sum);
        return Math.min(BASE_CRAWL_DELAY_MS * (1L << failures), MAX_BACKOFF_MS);
    }
    failureCounts.put(domain, 0); // reset on success
    return BASE_CRAWL_DELAY_MS;
}
```

---

## 9. Concurrency Model

The crawler uses **Java 21 virtual threads** via `Executors.newVirtualThreadPerTaskExecutor()`. Virtual threads make blocking I/O essentially free — each worker can block on an HTTP response without consuming an OS thread, allowing thousands of concurrent crawlers with minimal resource cost.

### Number of Workers

The number of parallel crawlers is a startup parameter:

```
ieps-tolpa --n-crawlers 5
```

If not specified, the default is estimated from system properties. For an I/O-bound crawler, the optimal worker count is not the number of CPU cores — it is determined by the ratio of wait time to work time:

```java
int estimateWorkerCount() {
    int cpuCores = Runtime.getRuntime().availableProcessors();
    // For I/O-bound work: workers = cores × (1 + wait_time / cpu_time)
    // Empirical estimate for HTTP crawling: wait/cpu ratio ≈ 10–50
    // A reasonable default is 4× cores, capped at a reasonable maximum
    return Math.min(cpuCores * 4, 64);
}
```

> ⚠️ With virtual threads, the concern is not thread count but memory — task creation must not outpace task completion by too large a margin. Use a bounded `ArrayBlockingQueue` as the frontier backing structure to enforce back-pressure.

### Producer-Consumer Pattern

Each worker is both a consumer (takes a URL from the frontier) and a producer (adds new URLs back to the frontier after parsing). This is a classic mixed producer-consumer problem.

```java
BlockingQueue<ScoredUrl> frontier = new PriorityBlockingQueue<>(); // ordered by score
AtomicInteger inFlight = new AtomicInteger(0);

// Worker loop
void workerLoop() {
    while (true) {
        ScoredUrl item = frontier.poll(500, TimeUnit.MILLISECONDS);
        if (item == null) {
            if (inFlight.get() == 0 && frontier.isEmpty()) break; // termination
            continue;
        }
        inFlight.incrementAndGet();
        try {
            fetch(item.url()); // parses and enqueues new URLs via Stage A
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
```

> ⚠️ Termination detection is non-trivial. The crawl is complete only when **both** the frontier queue is empty **and** no workers are currently in-flight. Checking only the queue is wrong — a worker may be mid-fetch and about to produce new URLs. The `inFlight` counter must be checked atomically with the queue-empty condition.

---

## 10. Code Architecture

The system is divided into the following components, each with a defined interface:

| Interface | Responsibility |
|---|---|
| `Scheduler` | Manages the worker pool lifecycle, startup, and shutdown |
| `Frontier` | Priority queue of URLs to be crawled, with deduplication |
| `Worker` | Dequeues a URL, fetches it, parses it, and feeds extracted URLs back |
| `Fetcher` | HTTP layer — executes the request, respects rate limits |
| `Parser` | Extracts structured data and raw URLs from an HTML document |
| `Canonicalizer` | Normalises a raw URL through the full ingestion pipeline |
| `RobotsTxtCache` | Domain-keyed cache of parsed `robots.txt` rules |
| `RateLimiterRegistry` | Per-domain Bucket4j rate limiter map with eviction |
| `RelevanceScorer` | Assigns a priority score to a URL given its context |
| `Storage` | Persists crawled pages, metadata, and frontier state to the database |

> Each component is defined as a Java interface and implemented in a concrete class. No component should depend directly on a concrete implementation of another — always depend on the interface. This allows individual components to be replaced (e.g. swapping the in-memory frontier for a database-backed one) and tested in isolation with mock implementations.

All code must be documented with concise Javadoc. Non-obvious logic should include inline comments explaining *why*, not *what*.

---

## 11. Libraries Summary

| Purpose | Library |
|---|---|
| HTML parsing | Jsoup |
| HTTP client | Java 21 `HttpClient` |
| URL canonicalization | `iipc/urlcanon` |
| `robots.txt` parsing | `crawler-commons` (`SimpleRobotRulesParser`) |
| Rate limiting | Bucket4j |
| Deduplication (probabilistic) | Guava `BloomFilter` |
| Deduplication (exact, bounded) | Caffeine cache |
| `robots.txt` + domain bucket caching | Caffeine `LoadingCache` |
| Concurrency | Java 21 virtual threads |
| Termination detection | `AtomicInteger` + `PriorityBlockingQueue` |

---

## Open Questions & TODOs

- [ ] **Frontier strategy detail** — Write a detailed plan of the preferential frontier strategy as described in `vaje-2-Preferential-crawling.ipynb`. Include specifics on the priority queue ordering, score decay over time, and how the secondary scoring tier (image recognition vs. image segmentation) is implemented.

- [ ] **Domain section expansion** — What more can be written about the crawl domain to make the specification more precise and detailed?

- [ ] **Python Jupyter environment** — Are there any benefits of using the Python Jupyter environment for writing the crawler, as written in Section 3 of `assignment.md`? Evaluate whether Java or Python is the better fit given the assignment constraints.

- [ ] **Database design** — Add a section on how the database should be created and how to validate its schema. Clarify whether the `FRONTIER` page type tag means the database is used as the initial queue — i.e. the crawler bootstraps by reading rows with `page_type = FRONTIER` from the database and does not discard duplicates, instead tagging them as `DUPLICATE`. Reason how this interpretation affects the architecture of the frontier and the deduplication step.

- [ ] **Image storage question** — Based on the `crawldb.sql` schema and `assignment.md`, do we need to store image files at all? We currently record image URLs as strings only. Clarify whether binary image storage is in scope.

- [ ] **Testing and debugging strategy** — Discuss how the system should be implemented so that individual components can be tested in isolation. Define what observable output each component should produce so that failures can be localized when the full crawler is run. Propose a staged integration approach (e.g. Fetcher → Parser → Canonicalizer → Frontier → Storage).

- [ ] **GitHub API usage** — The GitHub REST API provides structured access to repository metadata, README content, and file trees without `robots.txt` restrictions. Confirm with the professor whether API usage is permitted for this assignment, as it would significantly change the fetching and parsing architecture.

- [ ] **Contextual URL scoring implementation** — Implement the idea that surrounding anchor text is passed alongside each extracted URL to the scoring function, allowing the scorer to infer relevance from context even when the URL path contains no segmentation keywords.

- [ ] **Document splitting** — Decide how many separate markdown files this plan should be split into for the specification writing phase. Each file should represent one finished component section, define the Java interface, describe the logic flow, specify libraries, and include a section on programming patterns. Proposed split: `01-domain.md`, `02-url-pipeline.md`, `03-fetcher.md`, `04-frontier.md`, `05-parser.md`, `06-storage.md`, `07-concurrency.md`.

- [ ] **`abhinavsingh/fuge` reference** — Review `https://github.com/abhinavsingh/fuge` and evaluate whether its crawler architecture has any patterns applicable to our implementation.

- [ ] **Pre-implementation review** — Are all parts of the system thoroughly discussed before moving into the specification writing phase? Identify any gaps or unresolved design decisions.

- [ ] **Non HTML URLs** - If a crawler hits a URL that does not contain an HTML document (if crawler detects a binary file - e.g. .doc), html_content is set to NULL and a record in the page_data table is created.