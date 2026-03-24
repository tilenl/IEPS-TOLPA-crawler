# TS-10 Storage And SQL Contracts

## Purpose

Define authoritative database operations and method-to-SQL mappings.

## Contracted Operations

- `claimNextEligibleFrontier(...)`
- `recoverExpiredLeases(...)`
- `reschedulePage(...)`
- `markPageTerminalError(...)`
- `insertFrontierIfAbsent(...)`
- `insertLink(...)`
- `ingestDiscoveredUrls(...)`
- `updatePageHtml(...)`
- `markPageBinary(...)`
- `markPageDuplicate(...)`
- `persistFetchOutcomeWithLinks(...)`
- `upsertSite(...)`
- `insertImageRef(...)`
- `insertPageData(...)`
- `registerContentOwnership(...)`
- `resolveContentOwner(...)`

## SQL Safety Contract (Normative)

- all SQL execution MUST use prepared/bound parameters; string interpolation/concatenation with runtime input is forbidden;
- this applies to URLs, worker identifiers, error fields, and all dynamic predicates/values;
- query snippets shown in this spec are logical templates; concrete implementation MUST preserve bound-parameter semantics.

## SQL Catalog (Normative)

- **Claim next eligible frontier row (atomic)**
  - owned by `Frontier` contract (`TS-07`); implementation MUST mutate state to `PROCESSING` in same statement and return claimed row;
  - eligibility requires `page_type_code='FRONTIER'` and `next_attempt_at <= now()`.
- **Recover expired leases**
  - rows stuck in `PROCESSING` where `claim_expires_at < now()` are reset to `FRONTIER` with diagnostic update.
- **Reschedule claimed row**
  - transition `PROCESSING -> FRONTIER` with updated `next_attempt_at`, incremented `attempt_count`, and cleared lease fields.
- **Mark terminal error**
  - transition `PROCESSING -> ERROR` with category/message and terminal timestamp.
- **Insert frontier if absent**
  - `INSERT ... ON CONFLICT (url) DO NOTHING`
  - on conflict, caller MUST resolve existing row id with `SELECT id FROM crawldb.page WHERE url = ?`
  - returns insertion status and `page_id` so caller can branch new-vs-existing URL behavior deterministically
- **Insert link edge**
  - `INSERT INTO crawldb.link (from_page, to_page) VALUES (?, ?) ON CONFLICT (from_page, to_page) DO NOTHING`
- **Batch ingest discovered URLs**
  - storage API MUST accept a batch (`ingestDiscoveredUrls(Collection<DiscoveredUrl>)`) and process each discovered URL through canonicalization policy, URL upsert, and idempotent link insert;
  - implementation MAY apply per-item savepoints inside the same transaction only; savepoints MUST NOT imply intermediate commits;
  - contract outcome MUST explicitly report accepted/rejected URLs with reason codes.
- **Mark HTML outcome**
  - `UPDATE crawldb.page SET page_type_code='HTML', html_content=?, http_status_code=?, accessed_time=? WHERE id=?`
- **Mark duplicate**
  - `UPDATE crawldb.page SET page_type_code='DUPLICATE', html_content=NULL, content_hash=?, accessed_time=? WHERE id=?`
- **Register content ownership (atomic dedup)**
  - deterministic winner rule for same-hash concurrency: smallest `owner_page_id` wins;
  - canonical pattern:
    - `INSERT INTO crawldb.content_owner(content_hash, owner_page_id, created_at)
       VALUES (?, ?, now())
       ON CONFLICT (content_hash) DO UPDATE
       SET owner_page_id = LEAST(crawldb.content_owner.owner_page_id, EXCLUDED.owner_page_id)
       RETURNING owner_page_id`
- **Persist fetch outcome with links (atomic)**
  - `persistFetchOutcomeWithLinks(...)` MUST execute page outcome transition and discovered-link ingestion for the current source page in one transaction;
  - if any non-tolerable DB failure occurs, both outcome and associated discovered-link effects MUST roll back together;
  - expected per-URL policy rejections in a healthy transaction (for example `URL_TOO_LONG` or disallowed canonicalized URL) are recorded as rejected outcomes and do NOT require transaction rollback.

Atomicity clarification (normative):
- "atomic" means one commit unit for the fetched source page: page outcome + all accepted discovered-link effects become visible together, or none become visible;
- non-tolerable failures are storage/transaction failures (connection loss, deadlock/serialization abort, forced rollback, equivalent infrastructure errors) and MUST roll back the full unit of work;
- tolerable per-URL rejections are policy outcomes, not storage failures; they MUST be present in batch outcome reporting and MUST NOT be treated as atomicity violations.

## Transaction Rules

- claim transaction isolated and short with atomic state mutation;
- minimum isolation level for storage transactions is `READ COMMITTED`;
- if concurrency anomalies are observed in content-dedup stress tests, implementation MUST elevate dedup flow isolation/locking (e.g., `SERIALIZABLE` for that flow);
- page outcome update and related insertions MUST be atomic;
- page outcome + discovered-link ingestion for the current fetched page MUST be atomic via `persistFetchOutcomeWithLinks(...)`;
- ingestion insertions MUST be idempotent for retry safety;
- rejected discovered URLs MUST be represented in batch outcome metadata (with reason), not silently dropped.
- retry transition updates (`attempt_count`, `next_attempt_at`, diagnostics) MUST be atomic with queue state transition;
- content dedup owner registration and duplicate/owner state update MUST happen in one transaction.

## Data Integrity Rules

- `page.url` uniqueness is source of truth for seen URLs;
- every discovered relation must generate a `link` record;
- `site` must exist before `page` insert (`site_id` FK integrity).
- conflict path MUST be idempotent across retries and concurrent workers.
- `insertLink` MUST be idempotent across retries and concurrent workers (duplicate edge insert is a no-op).
- content hash ownership MUST be deterministic: one canonical owner per hash, all others marked duplicate of that owner.
- no row may remain indefinitely in `PROCESSING` without valid lease.

## Required Tests

- contract tests with real PostgreSQL for each method;
- conflict-path tests (`ON CONFLICT`) for URL dedup;
- multi-worker claim contention tests with atomic claim transition;
- reschedule and retry-attempt persistence tests;
- expired lease recovery tests;
- concurrent same-hash dedup ownership tests.
- deterministic same-hash winner tests (`min(page_id)` ownership rule across repeated runs).
- atomicity test ensuring discovered links and page terminal state commit/rollback together.
- batch ingestion contract tests for mixed valid/invalid discovered URLs.
- atomicity failure-injection test: force non-tolerable mid-transaction failure and assert no partial persistence.
- expected-rejection atomicity test: batch with policy rejections still commits source page outcome and accepted links.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/storage/`, `.../storage/postgres/`, `.../storage/frontier/`
- key file(s): `storage/postgres/repositories/PageRepository.java`, `storage/frontier/FrontierStore.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/integration/storage/postgres/` and `.../integration/storage/frontier/`
