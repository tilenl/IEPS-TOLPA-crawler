# PA1 Assignment Report

## Introduction
This report describes the implementation of a preferential web crawler for the `github.com` domain, focused on **image segmentation**.

## Crawl scope (host allowlist)

The crawler **only follows and enqueues** discovered HTTP(S) links whose **registry host** is in scope for the active configuration. For the default **`GITHUB`** crawl scope (see `crawler.crawlScope` in runtime configuration), **in-scope hosts are:

- `github.com`
- `www.github.com` (treated as the same site as `github.com` for scope, `crawldb.site` keys, and deduplication alignment with TS-05 canonicalization)

Other `*.github.com` subdomains (e.g. `api.github.com`, `gist.github.com`) are **not** in scope for frontier expansion.

Hosts such as **`github.io`** (GitHub Pages), **`githubusercontent.com`** / **`raw.githubusercontent.com`**, and other third-party domains are **not** in scope for frontier expansion: those URLs are not added as new crawl candidates, and robots content for those hosts is **not** written to `crawldb.site` (in-memory robots rules may still be used for redirect hops only).

**Image references (`img[src]`)** are not filtered by this crawl scope: extracted image URLs may still point at CDNs or other hosts for metadata storage only (no extra crawl of those hosts as HTML pages via the image row alone).

---

## Crawler architecture (technical specification overview)

The architecture in `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION` describes a **preferential web crawler** for segmentation-related material on `github.com`, respecting assignment constraints, `robots.txt`, and per-domain rate limits.

### Overall pattern and layers

The design is a **producer–consumer** system: the **frontier** is a persistent, database-backed buffer between **link discovery** (producers) and **page processing** (consumers). Two views are used in the docs:

- **Assignment view (five components):** HTTP downloader/renderer, data extractor, duplicate detector, URL frontier, datastore (PostgreSQL, `crawldb.sql`).
- **Engineering view:** `Scheduler`, `Worker`/`WorkerLoop`, `Frontier`/`FrontierStore`, `Fetcher`, `Parser`, `Canonicalizer`, `RobotsTxtCache`, `RateLimiterRegistry`, `RelevanceScorer`, `ContentHasher`, `Storage` (and related queue/claim/enqueue services). Cross-cutting concerns live under `config/`, `error/`, and `observability/`. The **only CLI entrypoint** is `cli/Main.java`; lifecycle and composition are owned by `app/PreferentialCrawler.java`. Components depend on **interfaces** defined in `TS-01` rather than on concrete types.

### Dataflow: Stage A and Stage B

Work is split into two stages:

- **Stage A (ingestion):** each discovered URL is **canonicalized**, checked against **robots** (before frontier insert), **deduplicated** at the URL level, **scored** for relevance, then written to the frontier (with link rows). Robots metadata is loaded through a shared **`RobotsTxtCache`** with per-domain single-flight; `/robots.txt` fetches share the same domain limiter budget as content fetches.
- **Stage B (fetch):** a worker **atomically claims** the next eligible frontier row, passes a **rate-limit / politeness gate**, **fetches** the page, **parses** it, **hashes** content for duplicate detection, and **persists** the outcome plus newly discovered links (which feed Stage A again).

Preferential behaviour is explicit: **scoring runs at ingestion**, before frontier insertion; the queue orders by **`relevance_score` descending**, with age-based tie-breaking.

### Major components and how they connect

Conceptually, the loop is: **seeds → frontier (PostgreSQL) → scheduler/worker → politeness + robots → fetcher → parser → canonicalizer + scorer → storage → frontier**. The fetcher uses **`HttpClient`** where sufficient and **Selenium (headless Chromium)** where JavaScript rendering is required (TS-03). The parser extracts **`href` and `onclick` links** and **image references** (TS-04). **URL deduplication** is **database-authoritative** (`page.url` uniqueness and atomic frontier insert semantics per TS-10); **content deduplication** uses **SHA-256** of HTML and may mark a page as `DUPLICATE` (TS-09). **Canonical URLs** are the sole form on the storage boundary (TS-05).

### Page lifecycle and termination

Rows move through **`FRONTIER` → `PROCESSING` →** a terminal type: **`HTML`**, **`BINARY`**, **`DUPLICATE`**, or **`ERROR`**. Rate-limit and retry paths reschedule work via **`next_attempt_at`**; stale leases can be reclaimed to `FRONTIER`. Crawl completion is defined when **no** `FRONTIER` or `PROCESSING` rows remain, subject to a configured **termination grace** window ([TS-02], [TS-07]).

### Budgets and bounded growth

The specification caps **concurrent** stored pages (e.g. **5,000** total `page` rows) and frontier size. When admitting a new URL would exceed a cap, **score-based replacement** may **evict the lowest-scoring `FRONTIER`** row (and its link edges) if the newcomer’s score is **strictly higher**; terminal and in-flight rows are not removed this way ([TS-13], domain/scope doc). This keeps the frontier and database from growing without bound while preserving the most relevant candidates.

### Deployment and politeness invariant

**Politeness** (e.g. Bucket4j per domain, **5-second floor** unless `robots.txt` specifies otherwise) and **robots caching** are **in-process**. **Exactly one crawler JVM** must run against a given database for a crawl: two processes would **double** effective request rate and break robots single-flight ([TS-08], [TS-06]).

### Assignment-aligned component summary

1. **HTTP Downloader and Renderer**: Selenium for JS-heavy pages, Java 21 `HttpClient` for efficient fetching when HTML is already sufficient.
2. **Data Extractor**: Hyperlinks (including `onclick`) and image references.
3. **Duplicate Detector**: SHA-256 over HTML for content-level duplicates.
4. **URL Frontier**: PostgreSQL-backed priority queue with safe concurrent claims; the live crawler uses **domain-scoped** dequeue coordinated with politeness (see Scheduling below).
5. **Datastore**: PostgreSQL following `crawldb.sql`.

## Scheduling and work orchestration

Runtime scheduling is implemented in `VirtualThreadCrawlerScheduler` and `DomainFrontierPump` (`pa1/crawler/.../scheduler/`). The design separates **who may claim the next URL** from **per-host politeness** (rate limiting), which matters especially when almost all frontier URLs share one crawl domain (e.g. `github.com`): a naive pattern of many workers each doing a **global** high-priority claim and only then hitting the rate limiter causes repeated leases and reschedules without useful fetch work.

### Threads and concurrency

- A **single virtual-thread executor** hosts three roles: the **domain pump** loop, **pipeline** tasks (fetch → parse → persist), and shares the same pool with any work submitted from those tasks.
- **`crawler.nCrawlers`** (CLI `--n-crawlers`) sets the capacity of a **semaphore**: at most that many **pipelines** may run at once **globally**. It does *not* mean “N independent pollers” all competing for the same global frontier row in parallel.
- A **termination supervisor** thread polls the database until there are no `FRONTIER` or `PROCESSING` rows, then applies a configured grace window before signalling shutdown and stopping the executor.

### Domain frontier pump

The **pump** runs in its own virtual thread (`runPumpLoop`). It maintains an in-memory **priority queue of wakeups** `(crawlDomain, dueEpochMs)`. When a wakeup is due, it runs `processDueDomain(domain)` for that domain key (matching `crawldb.site.domain`).

**Ordering of operations for one domain (simplified):**

1. **Serialize per domain**: at most one **in-flight pipeline** per domain; if a pipeline is still running, the pump re-schedules a short retry for that domain.
2. **Claim first, domain-scoped**: `FrontierStore.claimNextEligibleFrontierForDomain` runs the usual bounded stale-lease recovery, then atomically claims the best eligible `FRONTIER` row **for that site only** (same priority order as globally: relevance score, `next_attempt_at`, age, id). Claiming before consuming a politeness token avoids spending a token when there is nothing eligible for that domain.
3. **Outer politeness**: `PolitenessGate.tryAcquire(domain)` (Bucket4j per domain). If delayed, the leased row is **rescheduled** to `FRONTIER` with an appropriate `next_attempt_at`, and the domain is re-queued after the reported wait—**no busy-wait** on the outer gate.
4. **Pipeline handoff**: after a successful acquire, the pump submits a task to the executor that runs `WorkerLoop.runClaimedPipeline(row, true)`. The boolean flag means the **hop-0** HTTP fetch must **not** consume a second token for the same domain; the token was already taken in step 3. Inner redirect hops still follow the fetcher’s politeness rules.
5. **Next wakeup**: when the pipeline task finishes (success, failure handled inside the worker, or shutdown paths), the semaphore permit is released, the domain’s “in-flight” flag is cleared, and the domain is **scheduled again** so the next URL for that host can be claimed when allowed.

**Cross-domain behaviour**: different domains can each have a pipeline in flight at the same time, bounded only by the global semaphore (and by “one pipeline per domain” per domain).

### Waking the pump (new frontier work)

The pump must learn when a domain has new or due work without polling the whole database continuously:

- **Startup**: `seedDomainsWithFrontierWork()` queries distinct domains that currently have at least one `FRONTIER` row and schedules each.
- **After inserts**: `PageRepository` accepts an optional **wake notifier** (`Consumer<String>` crawl domain). It is invoked after standalone `insertFrontierIfAbsent` calls, and **after commit** when discovered URLs are ingested inside `persistFetchOutcomeWithLinks`, so the pump never tries to claim rows that are still uncommitted.
- The application wires an `AtomicReference<Consumer<String>>` so the notifier is registered when the scheduler starts (`PreferentialCrawler` / `Main`).

### Relation to the database frontier

The frontier remains authoritative in PostgreSQL (`FRONTIER` → `PROCESSING` lease, reschedule, terminal types). The pump does not replace the queue; it **restricts which claim API is used** (per-domain atomic `UPDATE ... RETURNING`) and **schedules when** to attempt work so that politeness and dequeue stay aligned.

## Preferential Crawling Strategy
Relevance is computed at discovery time based on repository metadata (name, description, topic tags) and anchor text. Higher relevance scores correspond to higher crawl priority in the frontier.

**Seed URLs** are assigned a **fixed relevance score** from `crawler.scoring.seedRelevanceScore` when they are inserted into the frontier at crawl startup. Startup validation requires this value to be **strictly greater** than the maximum possible keyword-based score (so operator-chosen seeds stay ahead of any discovered link). Seeds are not scored with the keyword-based scorer, and we **do not** HTTP-fetch seed pages during bootstrap: fetching would have to respect **robots.txt** (including crawl-delay / politeness, e.g. on the order of **5 seconds** between requests per host), which would make seeding unnecessarily slow before normal worker fetching begins. Discovered URLs use keyword-based scoring from configuration (uncapped sum of per-hit weights).

### Keyword list size and crawl skew

A **very large** `keywords.json` (many primary and secondary terms, including generic machine-learning and tooling vocabulary) **skewed the set of GitHub repositories** stored in the database toward **broad ML and model-engineering** topics. Those pages accumulated relevance from **substring matches** in the combined **URL, anchor text, and surrounding HTML context**—not only from repository cards—so marketing and site-wide chrome could dominate the score. The result was **not generic enough** for **image segmentation**: high-priority URLs were often “anything ML,” not segmentation-focused projects.

We **corrected the crawler’s search path** by **returning to a shorter, simpler keyword list** (`pa1/keywords.json`): core segmentation terms (for example `segmentation`, `mask`, `semantic`, `instance`, familiar model families), a modest set of framework keywords, and secondary terms aligned with vision pipelines rather than a long tail of architectures, datasets, and MLOps stack words.

**Example of skew:** `github.com/pricing` could receive a **strong relevance score** despite having nothing to do with image segmentation, because GitHub’s pricing and product pages mention **Copilot**, **neural networks**, **machine learning**, and related wording in **large navigation and marketing blocks**. With many ML-oriented keywords and a wide DOM context window, those pages matched repeatedly and looked like excellent crawl targets. A tighter dictionary reduces that kind of **false-positive boosting** and keeps the frontier closer to segmentation-related repositories.

## Deduplication Strategy
- **URL Deduplication**: Handled by the database unique constraint on the `url` column.
- **Content Deduplication**: SHA-256 hash of the HTML body is compared against existing hashes in the database.

## Implementation and tuning notes

The following observations were recorded while developing and running the crawler:

- **Crawl-delay and subdomains:** The **5-second crawl-delay rule per domain** was respected, as seen from access timestamps. Each **subdomain** under `github.com` had its **own** timer; the crawler soon **stopped expanding** those subdomains when they yielded no useful links.

- **`maxOccurrencesPerKeyword`:** When this was set **too high**, the crawler **filled the database with `/topics` pages**, because those pages are dense in the primary keywords. During tuning it was **capped at 3**, which balanced **topics pages vs. repositories**. It was kept **above 1** so repositories mentioned **more than once** got a **higher score** (stronger relevance). At **128**, topics pages **flooded** the database.

- **Two-phase collection (topics vs. repos):** The crawler was still **converging on topics-only** results. The workaround: set a **high** occurrence limit (**128**), run with a **30-page** cap so the crawl converged on **highly relevant topics pages**, **seed** those URLs, **block further crawling of `/topics`**, and **re-run** with a large page limit (**5,000**). That **stopped topics from dominating** while still rewarding **multiple keyword hits** (including on topics, via seeds). **Phase 1:** 30-page run to lock in topics. **Phase 2:** seeds + **5,000-page** run with **`/topics` denied**—this produced the **best repository mix**.

- **`github.com` and `www.github.com`:** Both were restricted because GitHub **redirects** `www.github.com/robots.txt` to `github.com/robots.txt`, so they share one **robots / fetch bucket**; since `www` redirects to the apex host anyway, treating both consistently was appropriate.

- **Near-duplicate URLs:** Test runs hit duplicates such as `.../search` vs. `.../search/en`. The translated `/en` variant was treated as a **duplicate**, with a **canonical** relationship to the original.

- **Subdomain noise (pre-restriction):** Before scope was tightened, the DB contained many **`*.github.com`** hosts (`api`, `cli`, `help`, …) with **no useful content** (`relevance_score = 0`). Crawling was therefore **limited to the main `github.com` scope** (aligned with the allowlist described above).

- **Headless rendering:** **GitHub does not require** headless browsing for the pages we cared about—`/topics`, `/readme.md`, and similar **server-rendered** HTML were verified. **Headless** remains in the stack and is used when a page has **no links** or when a **“JavaScript is not enabled”** message appears.

- **`.py` and extension URLs on GitHub:** Many file URLs are **HTML views**, not raw bytes, so **binary bodies** are uncommon on `github.com` itself; true binaries usually **link off-site**. Such external cases are **out of scope** for following as crawl targets.

- **Binary rows in testing:** Binaries observed in DB tests **pointed outside** `github.com` (e.g. **PDFs** not hosted on GitHub). They **do not** dominate the production crawl set. Many would-be binary paths also sit under **`robots.txt`**-disallowed areas (e.g. deep `blob/` paths).

- **5,000-page cap and eviction:** The crawler is designed around a **5,000-page** budget. If a **new** page scores **higher** than the **worst** page currently admitted under the cap, the **lowest-scoring frontier** candidate can be **dropped** and replaced—**in-place** control so neither frontier nor DB grows without bound (see Budgets above).

## Statistics
(To be populated after crawling)

## Image references in the database (hash-like names and `BINARY` content type)

During manual inspection of the `crawldb.image` table, some rows have a `filename` that looks like a long opaque string (similar to a content hash) with **no** conventional extension (`.png`, `.webp`, `.jpg`, and so on). That can look odd in a table of “images,” but **manual checks** (opening the URL or inspecting the response) often show **real images** served from CDNs or apps that omit extensions in the path.

The crawler stores **metadata only** for images (URL reference, filename segment, `accessed_time`); it does **not** download image bytes. Per the assignment, **`content_type` is always the literal `BINARY`** for every `img[src]` row—not a MIME type such as `image/png` and not `NULL`. The filename column still reflects the last path segment when present, which may or may not include an extension.

**Migrating older databases:** if you crawled before this rule, run  
`UPDATE crawldb.image SET content_type = 'BINARY' WHERE content_type IS NULL OR content_type <> 'BINARY';`  
(adjust as needed; back up first).

For future work, true MIME types would require an extra request or sniffing and are out of current scope.

---

### IMPORTANT: Submission Note
The final submission in the `pa1` directory should only contain the `report.pdf` version of this report. This markdown file is for development purposes only.

To convert this markdown file into a PDF, you can use tools like `pandoc`, `grip`, or VSCode's "Markdown PDF" extension.
