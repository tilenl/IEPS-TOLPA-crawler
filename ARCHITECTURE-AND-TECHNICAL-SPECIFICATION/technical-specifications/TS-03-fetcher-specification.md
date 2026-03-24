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

- Java `HttpClient` MUST **automatically follow** HTTP redirect responses (`3xx` with `Location`) up to **`crawler.fetch.maxRedirects`** ([TS-13](TS-13-configuration-and-runtime-parameters.md)).
- **Collapsed persistence:** the **claimed / canonical URL** from the frontier remains `page.url`; the stored **`http_status_code`** and **body** come from the **final** non-redirect response after following the chain. Intermediate hop URLs do **not** create additional `page` rows ([TS-10](TS-10-storage-and-sql-contracts.md)).
- exceeding the hop limit or detecting a redirect loop MUST yield a **fetch failure** classified per [TS-12](TS-12-error-model-and-recovery-policy.md) (typically retryable or terminal `FETCH_HTTP_CLIENT` / overload-style policy as documented in implementation notes).
- **Robots / politeness:** pre-fetch `robotsTxtCache.evaluate` applies to the **claimed URL**; following redirects during fetch is part of retrieving that resource for **assignment scope**.
- **`meta refresh` / HTML redirects** are **not** handled by `HttpClient`; defer or document as unsupported unless implemented elsewhere (parser-driven).

## Incomplete shell → headless escalation (observability)

- when plain `HttpClient` response is treated as an **incomplete shell** and the fetcher **escalates** to **headless** (or otherwise flags shell HTML), the implementation MUST emit structured log event **`FETCH_INCOMPLETE_SHELL`** with `url`, `domain`, `workerId` per [TS-15](TS-15-observability-logging-and-metrics.md). **No** normative link-count or DOM-marker thresholds are required for this assignment.

## FetchResult Contract

- `canonicalUrl` (frontier / claimed URL)
- `httpStatusCode` (from **final** response after redirects when using `HttpClient`)
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
- redirect follow + max-hops / loop failure tests (`crawler.fetch.maxRedirects`);
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

