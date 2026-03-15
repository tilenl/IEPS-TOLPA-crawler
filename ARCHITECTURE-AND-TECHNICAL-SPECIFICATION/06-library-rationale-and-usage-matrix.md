# Library Rationale And Usage Matrix

This file is the single source of truth for why each approved library/mechanism is used and where it is specified.

| Library / Mechanism | Why Used | Where Used (Specs) | Fallback / Constraints |
| --- | --- | --- | --- |
| Selenium + ChromeDriver | Headless browser path for pages that require JS rendering before extraction. | `technical-specifications/TS-03-fetcher-specification.md`, `02-assignment-compliance-mapping.md` | Use only when plain HTTP is insufficient; must respect rate limiting and timeout policy. |
| Jsoup | DOM parser for deterministic extraction of `href`, `img`, and `onclick` candidates via CSS selectors. | `technical-specifications/TS-04-parser-and-extraction-specification.md`, `technical-specifications/TS-03-fetcher-specification.md` | Mandatory for parser stage; regex only for parsing redirect payload inside `onclick`. |
| Java 21 `HttpClient` | Fast plain-HTTP fetch path, especially for server-rendered GitHub pages. | `technical-specifications/TS-03-fetcher-specification.md`, `02-assignment-compliance-mapping.md` | Must honor timeout settings and rate-limit policy; fallback to headless fetch allowed when needed. |
| `iipc/urlcanon` | WHATWG URL canonicalization for stable dedup and normalization semantics. | `technical-specifications/TS-05-url-canonicalization-and-normalization.md`, `02-assignment-compliance-mapping.md` | `crawler4j` URL canonicalizer is forbidden. |
| `crawler-commons` (`SimpleRobotRulesParser`) | Standards-compliant robots parsing and crawl-delay handling. | `technical-specifications/TS-06-robots-policy-and-site-metadata.md`, `02-assignment-compliance-mapping.md` | Robots decision must happen before frontier insertion. |
| Bucket4j | Per-domain request pacing and exact wait-time based delay decisions. | `technical-specifications/TS-08-rate-limiting-and-backoff.md`, `technical-specifications/TS-02-worker-orchestration-and-pipeline.md` | Must enforce assignment minimum floor of 5 seconds. |
| Caffeine | In-memory caches for robots rules and domain limiter buckets with eviction/TTL. | `technical-specifications/TS-06-robots-policy-and-site-metadata.md`, `technical-specifications/TS-08-rate-limiting-and-backoff.md` | Cache config governed by runtime config (`TS-13`). |
| PostgreSQL + JDBC | Authoritative persistence for frontier, crawl outputs, graph relations, and dedup constraints. | `technical-specifications/TS-10-storage-and-sql-contracts.md`, `technical-specifications/TS-11-database-schema-and-migrations.md`, `technical-specifications/TS-13-configuration-and-runtime-parameters.md` | SQL contracts must be idempotent and transaction-safe. |
| Java 21 virtual threads | High-concurrency worker execution with cheap blocking for I/O-heavy crawling. | `technical-specifications/TS-14-concurrency-and-threading-model.md` | Worker count still constrained by rate limiting and DB contention behavior. |
| PostgreSQL `FOR UPDATE SKIP LOCKED` | Lock-safe multi-worker dequeue without double processing. | `technical-specifications/TS-07-frontier-and-priority-dequeue.md`, `technical-specifications/TS-10-storage-and-sql-contracts.md` | Must be used with deterministic ordering and short claim transactions. |

## Forbidden Libraries

The following crawler frameworks remain forbidden and MUST NOT be added directly or transitively: Scrapy, Apache Nutch, `crawler4j`, gecco, Norconex HTTP Collector, webmagic, Webmuncher.
