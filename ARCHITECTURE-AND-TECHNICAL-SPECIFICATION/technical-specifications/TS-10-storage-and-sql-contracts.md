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
- `updatePageHtml(...)`
- `markPageBinary(...)`
- `markPageDuplicate(...)`
- `upsertSite(...)`
- `insertImageRef(...)`
- `insertPageData(...)`
- `registerContentOwnership(...)`
- `resolveContentOwner(...)`

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
  - returns insertion status so caller can branch new-vs-existing URL behavior
- **Insert link edge**
  - `INSERT INTO crawldb.link (from_page, to_page) ...`
- **Mark HTML outcome**
  - `UPDATE crawldb.page SET page_type_code='HTML', html_content=?, http_status_code=?, accessed_time=? WHERE id=?`
- **Mark duplicate**
  - `UPDATE crawldb.page SET page_type_code='DUPLICATE', html_content=NULL, content_hash=?, accessed_time=? WHERE id=?`
- **Register content ownership (atomic dedup)**
  - `INSERT INTO crawldb.content_owner(content_hash, owner_page_id, created_at) VALUES (?, ?, now()) ON CONFLICT (content_hash) DO NOTHING`
- **Resolve content owner**
  - `SELECT owner_page_id FROM crawldb.content_owner WHERE content_hash=?`

## Transaction Rules

- claim transaction isolated and short with atomic state mutation;
- page outcome update and related insertions MUST be atomic;
- ingestion insertions MUST be idempotent for retry safety;
- retry transition updates (`attempt_count`, `next_attempt_at`, diagnostics) MUST be atomic with queue state transition;
- content dedup owner registration and duplicate/owner state update MUST happen in one transaction.

## Data Integrity Rules

- `page.url` uniqueness is source of truth for seen URLs;
- every discovered relation must generate a `link` record;
- `site` must exist before `page` insert (`site_id` FK integrity).
- conflict path MUST be idempotent across retries and concurrent workers.
- content hash ownership MUST be deterministic: one canonical owner per hash, all others marked duplicate of that owner.
- no row may remain indefinitely in `PROCESSING` without valid lease.

## Required Tests

- contract tests with real PostgreSQL for each method;
- conflict-path tests (`ON CONFLICT`) for URL dedup;
- multi-worker claim contention tests with atomic claim transition;
- reschedule and retry-attempt persistence tests;
- expired lease recovery tests;
- concurrent same-hash dedup ownership tests.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/storage/`, `.../storage/postgres/`, `.../storage/frontier/`
- key file(s): `storage/postgres/repositories/PageRepository.java`, `storage/frontier/FrontierStore.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/integration/storage/postgres/` and `.../integration/storage/frontier/`
