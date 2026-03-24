# Crawler Architecture Overview

## System Goal

Build a preferential web crawler for segmentation-related repositories, centered on `github.com`, compliant with assignment constraints and robots/rate-limit policies.

## Architecture Layers

The system follows a **Producer-Consumer pattern** where the `Frontier` acts as the decoupled, persistent buffer between link discovery (producers) and page processing (consumers).

- **Layer 1 (assignment view)**: HTTP downloader/renderer, data extractor, duplicate detector, URL frontier, datastore.
- **Layer 2 (engineering view)**: `Scheduler`, `Worker`, `Fetcher`, `Parser`, `Canonicalizer`, `RobotsTxtCache`, `RateLimiterRegistry`, `RelevanceScorer`, `ContentHasher`, `Storage`, `Frontier`.

## High-Level Components

- `Scheduler`: starts/stops workers and handles graceful termination.
- `Worker`: orchestrates dequeue -> policy checks -> fetch -> parse -> deduplicate -> persist.
- `Frontier`: database-backed priority queue (`FRONTIER`, score descending, lock-safe claims).
- `Fetcher`: domain-aware renderer (`HttpClient` for GitHub, Selenium for JS-heavy pages).
- `Parser`: extracts text, links (`href` + `onclick`), and image refs.
- `Storage`: authoritative persistence and SQL contracts.

## Drift Resolution Profile

- URL deduplication authority: PostgreSQL `page.url` uniqueness and **`insertFrontierIfAbsent`** per [TS-10](technical-specifications/TS-10-storage-and-sql-contracts.md): sentinel `INSERT ... ON CONFLICT (url) DO UPDATE ... RETURNING id` (single round-trip `page_id` on insert and conflict paths—not `ON CONFLICT DO NOTHING` as the primary contract).
- No probabilistic dedup pre-check layer is part of the current architecture profile.
- Politeness is enforced per-domain with 5-second floor and robots-aware delay override.

## Preferential Scoring Placement

- scoring is performed during Stage A ingestion, before frontier insertion;
- frontier ordering is score-first (`relevance_score DESC`) and then age-based tie-break.

## Dependency Rule

Components depend on interfaces, not concrete classes. Cross-component calls MUST flow through contracts in `technical-specifications/TS-01-interface-contracts.md`.

## Deployment Invariant (Single Process Per Database)

Politeness (`RateLimiterRegistry`, [TS-08](technical-specifications/TS-08-rate-limiting-and-backoff.md)) and robots loading/cache ([TS-06](technical-specifications/TS-06-robots-policy-and-site-metadata.md)) are **in-process** (e.g. Bucket4j + Caffeine). **Exactly one crawler JVM process** MUST run against a given PostgreSQL database for a crawl. Starting **two** CLI instances (or two processes) pointing at the **same** `crawler.db.url` **doubles effective per-domain request rate** and **breaks** per-domain robots single-flight — violating the intended politeness and robots contracts. For multiple crawls or environments, use **separate databases** or explicitly documented external coordination (out of assignment scope).

## Core Diagram

```mermaid
flowchart LR
seedInput[SeedInput] --> frontierDb[FrontierDb]
frontierDb --> scheduler[Scheduler]
scheduler --> workerLoop[WorkerLoop]
workerLoop --> rateLimiter[RateLimiterRegistry]
workerLoop --> robotsCache[RobotsTxtCache]
workerLoop --> fetcher[Fetcher]
fetcher --> parser[Parser]
parser --> canonicalizer[Canonicalizer]
canonicalizer --> scorer[RelevanceScorer]
scorer --> storage[Storage]
storage --> frontierDb
workerLoop --> contentHasher[ContentHasher]
contentHasher --> storage
```

## Output and Persistence

- URLs discovered are normalized, scored, deduplicated, and inserted to frontier.
- Fetch results are persisted as `HTML`, `BINARY`, or `DUPLICATE`.
- Link graph is persisted for every discovered relationship, including already-seen targets.
