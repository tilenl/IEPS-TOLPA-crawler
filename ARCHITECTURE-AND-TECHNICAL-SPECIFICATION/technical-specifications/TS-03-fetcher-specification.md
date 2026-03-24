# TS-03 Fetcher Specification

## Responsibility

Download/render page content and return normalized fetch response metadata.

## Boundary With Parser

- `Fetcher` is responsible for transport and rendering only (status/content-type/body/timestamps/mode);
- HTML DOM traversal and link/image extraction are parser responsibilities in `TS-04` using `Jsoup`;
- `Fetcher` MUST NOT implement link extraction logic to avoid duplicated parsing behavior.

## Domain-Aware Strategy

- `github.com`: default to Java `HttpClient` fetch + Jsoup parse compatibility.
- external JS-heavy domains: Selenium headless Chrome path.
- fallback MAY switch to Selenium when plain HTTP fetch indicates incomplete shell.
- headless mode MUST be capacity-limited by runtime configuration (`TS-13`).

Rationale:
- GitHub repository/topic pages are largely server-rendered and cheaper via `HttpClient`;
- external linked domains may require JS execution for content/links and use Selenium path.

## HTTP Redirects (normative)

**Policy: hop-by-hop (no transparent auto-follow as the sole mechanism).** Relying only on `HttpClient` **automatic** redirect following is **NOT** sufficient: **robots** and **per-domain rate limiting** MUST run **before each HTTP request** in the chain (each hop’s URL/host). Implementation MUST use a **manual redirect loop** (or an `HttpClient` redirect handler that invokes the same gates between hops), not a single call that follows `3xx` without policy between hops.

**Per hop (repeat until final non-`3xx` or failure):**

1. Resolve the **current request URL** (initially the **claimed / frontier** canonical URL).
2. Derive **domain** for politeness/robots using the same keying as elsewhere (`TS-08`, `TS-06`).
3. **`robotsTxtCache.ensureLoaded(domain)`** then **`evaluate(absoluteUrlForThisHop)`** — on `DISALLOWED` / `TEMPORARY_DENY`, **stop** the chain and surface the same outcomes as a normal fetch ([TS-12](TS-12-error-model-and-recovery-policy.md)) (terminal vs reschedule per category).
4. **`rateLimiter.tryAcquire(domain)`** — same per-domain bucket as content and `/robots.txt`. **Mid-chain delay:** MAY **block** the fetcher’s calling thread (e.g. virtual-thread sleep) for the limiter wait **within remaining lease margin** per [TS-02](TS-02-worker-orchestration-and-pipeline.md) / [TS-08](TS-08-rate-limiting-and-backoff.md); if the wait would exceed safe lease margin, **abort** the chain so the **worker** can `frontier.reschedule` and restart from the **claimed URL** on the next claim.
5. Issue **GET** (or policy-allowed method) for the current URL. On **`3xx`** with `Location`, resolve the next absolute URL, increment hop count, and loop. Enforce **`crawler.fetch.maxRedirects`** total hops across the chain ([TS-13](TS-13-configuration-and-runtime-parameters.md)).

**Collapsed persistence:** **`page.url`** remains the **original claimed** canonical URL; stored **`http_status_code`** and **body** come from the **final** non-redirect response. Intermediate hop URLs do **not** create `page` rows ([TS-10](TS-10-storage-and-sql-contracts.md)).

- exceeding the hop limit or detecting a redirect loop MUST yield a **fetch failure** classified per [TS-12](TS-12-error-model-and-recovery-policy.md).
- emit structured logging per hop: `url`, `domain`, `workerId`, hop index (align with [TS-15](TS-15-observability-logging-and-metrics.md) where applicable).

**Known limitation (operator-facing):** **`meta refresh` / HTML redirects** are **not** handled by the normative fetch path (`HttpClient` does not follow them; parser does not re-drive fetch unless explicitly extended). Sites that rely on meta/JS-only navigation may return **shell HTML** or **missing links** compared with a full browser. See **Crawl behavior limitations** in [migration-rollback-runbook.md](../migration-rollback-runbook.md). Parser-driven or headless follow-up is **optional future work**.

## Incomplete shell → headless escalation (observability)

- when plain `HttpClient` response is treated as an **incomplete shell** and the fetcher **escalates** to **headless** (or otherwise flags shell HTML), the implementation MUST emit structured log event **`FETCH_INCOMPLETE_SHELL`** with `url`, `domain`, `workerId` per [TS-15](TS-15-observability-logging-and-metrics.md). **No** normative link-count or DOM-marker thresholds are required for this assignment.

## FetchResult Contract

- `canonicalUrl` (frontier / claimed URL)
- `httpStatusCode` (from **final** non-redirect response after the **hop-by-hop** chain)
- `contentType`
- `body` (optional for binary outcomes)
- `accessedTime`
- `fetchMode` (`PLAIN_HTTP` or `HEADLESS`)
- optional `finalUrlAfterRedirects` (for diagnostics/logging when redirects were followed; may be omitted if same as claimed URL)

## Timeout Policy

- connect timeout MUST be configured;
- read/render timeout MUST be configured;
- timeout errors map to retryable fetch failures per `TS-12`.

## Headless Fetch Baseline

Reference setup for JS-rendered fetch mode:

```java
ChromeOptions options = new ChromeOptions();
options.addArguments("--headless=new", "--no-sandbox", "--disable-gpu");
options.addArguments("--user-agent=fri-wier-IEPS-TOLPA");
WebDriver driver = new ChromeDriver(options);
driver.get(canonicalUrl);
new WebDriverWait(driver, Duration.ofSeconds(5))
    .until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
String html = driver.getPageSource();
```

This snippet is normative for behavior, not exact class placement.

## Headless Capacity Governance (Normative)

- concurrent headless sessions MUST be bounded by `crawler.fetch.maxHeadlessSessions`;
- worker must acquire a headless slot with timeout `crawler.fetch.headlessAcquireTimeoutMs`;
- if slot acquisition times out:
  - fetcher MUST execute deterministic fallback policy (`FETCH_CAPACITY_EXHAUSTED` classification or plain-HTTP fallback when allowed by policy);
  - worker MUST NOT block indefinitely while holding queue lease;
- repeated slot-acquire failures above `crawler.fetch.headlessCircuitOpenThreshold` SHOULD open short circuit and skip headless attempts for cooldown period;
- browser lifecycle MUST close/recycle driver instances on both success and error paths.

## Content Classification

- HTML-like content -> parse candidate;
- binary document types (`.doc`, `.docx`, `.ppt`, `.pptx`) -> `BINARY` handling;
- unsupported/empty body -> recoverable failure or non-HTML outcome by policy.

Examples:
- `https://github.com/open-mmlab/mmsegmentation` -> expected `PLAIN_HTTP` + HTML.
- JS-only external docs page returning shell HTML via plain fetch -> retry with `HEADLESS`.

## Required Tests

- mode selection tests by domain;
- timeout and HTTP error mapping tests;
- hop-by-hop redirect tests: second-hop host **robots** deny / **TEMPORARY_DENY**; politeness **token consumed per hop**; cross-host chain obeys both hosts’ buckets;
- max-hops / loop failure tests (`crawler.fetch.maxRedirects`);
- rate-limit delay **mid-chain**: wait-within-lease continues hop vs **reschedule** when lease margin insufficient;
- binary-vs-html classification tests.
- `FETCH_INCOMPLETE_SHELL` log emission when escalating plain HTTP → headless.
- user-agent propagation test (`fri-wier-IEPS-TOLPA`) in both fetch modes.
- headless slot cap test under concurrent workers;
- saturation fallback test when all headless slots are exhausted;
- driver cleanup test on exceptional paths.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/downloader/fetch/`
- key file(s): `downloader/fetch/HttpFetcher.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/downloader/fetch/` and `.../integration/downloader/fetch/`

