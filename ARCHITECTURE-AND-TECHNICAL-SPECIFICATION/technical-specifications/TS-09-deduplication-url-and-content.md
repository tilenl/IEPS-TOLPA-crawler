# TS-09 Deduplication URL And Content

## Responsibility

Ensure crawler processes each URL once while identifying content-equivalent pages.

## Level 1: URL Deduplication

- authoritative check is DB uniqueness on canonical URL;
- use insert-if-absent contract;
- if URL exists, skip new `page` row but still insert `link`.
- canonical storage mechanism: [TS-10](TS-10-storage-and-sql-contracts.md) **`insertFrontierIfAbsent`** (sentinel `ON CONFLICT ... DO UPDATE ... RETURNING id`).

## Level 2: Content Deduplication

- after HTML fetch, compute SHA-256 hash;
- dedup decision MUST be atomic and DB-authoritative via content ownership contract;
- exactly one canonical owner page exists per `content_hash`;
- non-owner pages with same hash are marked `DUPLICATE` and reference owner;
- set `html_content` to null for duplicate records;
- duplicate relationship link insertion is required for graph/report integrity.

## Data Requirements

- `page.content_hash` (schema extension) for hash lookup;
- `content_owner(content_hash PRIMARY KEY, owner_page_id)` table for deterministic ownership;
- check-then-act dedup (`SELECT hash` then separate update) is forbidden.
- DB-only URL dedup is normative for this architecture (no probabilistic pre-check stage).

## Example Flows

- URL duplicate:
  - discovered canonical URL already exists;
  - no new `page` row inserted;
  - `link(from=currentPage, to=existingPage)` still inserted.
- content duplicate:
  - new URL inserted and fetched;
  - owner registration conflict on `content_owner.content_hash`;
  - current page updated to `DUPLICATE`, `html_content=NULL`, owner reference persisted.

## Atomic Dedup Contract (Normative)

For each fetched HTML page with hash `H`, **inside** the Stage B **`persistFetchOutcomeWithLinks`** transaction ([TS-10](TS-10-storage-and-sql-contracts.md), [TS-02](TS-02-worker-orchestration-and-pipeline.md)):

1. Execute deterministic ownership upsert with winner rule `min(page_id)`:
   - `INSERT ... ON CONFLICT (content_hash) DO UPDATE SET owner_page_id = LEAST(existing.owner_page_id, EXCLUDED.owner_page_id) RETURNING owner_page_id`.
2. Use returned `owner_page_id` in the **same** transaction as all other `persistFetchOutcomeWithLinks` effects.
3. If `owner_page_id == currentPageId`, mark page as canonical `HTML` and persist `content_hash`.
4. If `owner_page_id != currentPageId`, mark page `DUPLICATE`, clear HTML payload, persist duplicate linkage.

Binary / non-HTML outcomes in `persistFetchOutcomeWithLinks` still use the **same** transaction boundary; content-owner steps apply only on the HTML path.

This contract MUST yield deterministic outcome under concurrent workers.

Isolation (normative):
- the **entire** `persistFetchOutcomeWithLinks` database transaction (content_owner upsert / `LEAST` when applicable, **`page`** terminal updates, **and** accepted discovered-link / image / related inserts in that method) MUST use PostgreSQL **`SERIALIZABLE`** isolation — **not** `REPEATABLE READ` and **not** `READ COMMITTED` for this unit of work;
- **other** `Storage` transactions (claim, reschedule, Stage A inserts, etc.) remain **`READ COMMITTED`** unless otherwise specified ([TS-10](TS-10-storage-and-sql-contracts.md));
- on **`SQLSTATE 40001`** (`serialization_failure`), the implementation MUST **retry** the **whole** `persistFetchOutcomeWithLinks` transaction with bounded backoff + jitter per [TS-10](TS-10-storage-and-sql-contracts.md) / [TS-12](TS-12-error-model-and-recovery-policy.md);
- **do not** split content dedup into a separate lower-isolation transaction while still claiming atomic persist+links.

## Required Tests

- URL duplicate path inserts link without duplicating page row;
- content duplicate path sets `DUPLICATE` and clears HTML;
- distinct URLs with same content are correctly detected;
- non-duplicate content remains `HTML`.
- concurrent same-hash workers produce one canonical owner and deterministic duplicates.
- repeated reruns of same-hash concurrency tests must produce identical owner selection outcome **for each fixed content hash `H`** (same batch shape, same `min(page_id)` winner rule on every rerun of that hash).
- **Test design note (traceability):** when one test method applies a **single** DB reset for the whole method (for example one `@BeforeEach` truncation per method), repeated stress iterations may use **isolated same-hash batches** — a **distinct** `H` per iteration — so `content_owner` and related rows from iteration *k* do not collide with iteration *k+1* while still exercising concurrent same-hash persistence each time. Another valid pattern is **one fixed `H`** with truncation or deletion of `content_owner` (and dependent rows) between iterations. Integration tests in `Ts16ConcurrencyRestartGateIT` follow the isolated-batch pattern; `Ts09DedupAssertions` centralizes postconditions per batch.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/queue/dedup/`, `.../downloader/dedup/`, `.../storage/postgres/`
- key file(s): `queue/dedup/UrlSeenIndex.java`, `downloader/dedup/ContentHasherImpl.java`, `storage/postgres/repositories/PageRepository.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/queue/dedup/`, `.../unit/downloader/dedup/`, `.../integration/storage/postgres/`
