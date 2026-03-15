# Assignment Compliance Mapping

## Required Block Mapping


| Assignment Component         | Implementation Mapping                     | Compliance Notes                                                                   |
| ---------------------------- | ------------------------------------------ | ---------------------------------------------------------------------------------- |
| HTTP downloader and renderer | `Fetcher` + `Worker`                       | Selenium path retained for JS-rendered pages; GitHub fast path may use plain HTTP. |
| Data extractor               | `Parser`                                   | MUST extract `href`, `onclick` redirects, and image references.                    |
| Duplicate detector           | `Storage` URL uniqueness + `ContentHasher` | URL dedup and content dedup are separate concerns.                                 |
| URL frontier                 | `Frontier` + `Scheduler`                   | DB-backed preferential dequeue with `FOR UPDATE SKIP LOCKED`.                      |
| Datastore                    | `Storage` over PostgreSQL schema           | Uses `site`, `page`, `link`, `image`, `page_data`, and allowed schema extensions.  |


## Assignment Component To Package Path Mapping

All implementation paths below are relative to:
`pa1/crawler/src/main/java/si/uni_lj/fri/wier/`


| Assignment Component         | Primary Package Paths                                               |
| ---------------------------- | ------------------------------------------------------------------- |
| HTTP downloader and renderer | `downloader/fetch/`, `downloader/worker/`, `downloader/politeness/` |
| Data extractor               | `downloader/extract/`                                               |
| Duplicate detector           | `queue/dedup/`, `downloader/dedup/`, `storage/postgres/`            |
| URL frontier                 | `queue/claim/`, `queue/enqueue/`, `storage/frontier/`, `scheduler/` |
| Datastore                    | `storage/postgres/`, `storage/frontier/`                            |


## Bootstrap Ownership

- CLI entrypoint is `pa1/crawler/src/main/java/si/uni_lj/fri/wier/cli/Main.java`.
- Main delegates crawler lifecycle orchestration to `pa1/crawler/src/main/java/si/uni_lj/fri/wier/app/PreferentialCrawler.java`.
- Startup preflight validation (config + DB reachability + required runtime checks) is owned by `app/PreferentialCrawler.java` and specified in `TS-13`/`TS-14`.

## Hard Compliance Requirements

- MUST honor `robots.txt` before entering frontier.
- MUST enforce minimum per-domain delay of 5 seconds, even if robots delay is lower/absent.
- MUST extract and process `onclick` links.
- MUST persist link edges for discovered relationships, including already-known URLs.
- MUST avoid forbidden crawler frameworks in `assignment.md`.

## Library Compliance

For rationale and traceability (why each library is used and where), use `06-library-rationale-and-usage-matrix.md` as the authoritative source.

- Allowed and planned:
  - Selenium + ChromeDriver
  - Jsoup
  - Java `HttpClient`
  - `iipc/urlcanon`
  - `crawler-commons`
  - Bucket4j
  - Caffeine
  - PostgreSQL + JDBC
- Not allowed:
  - Scrapy, Apache Nutch, `crawler4j`, gecco, Norconex HTTP Collector, webmagic, Webmuncher

## Evidence Checklist

- `TS-04` explicitly documents `onclick` extraction.
- `TS-06` documents robots parsing and DB persistence.
- `TS-07` documents lock-safe frontier claims.
- `TS-08` documents delay floor and backoff.
- `TS-09` documents two-level deduplication.
- `TS-16` defines test cases proving these behaviors.

