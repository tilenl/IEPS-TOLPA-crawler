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

For each fetched HTML page with hash `H`:

1. In one transaction, execute deterministic ownership upsert with winner rule `min(page_id)`:
   - `INSERT ... ON CONFLICT (content_hash) DO UPDATE SET owner_page_id = LEAST(existing.owner_page_id, EXCLUDED.owner_page_id) RETURNING owner_page_id`.
2. Use returned `owner_page_id` in the same transaction.
3. If `owner_page_id == currentPageId`, mark page as canonical `HTML` and persist `content_hash`.
4. If `owner_page_id != currentPageId`, mark page `DUPLICATE`, clear HTML payload, persist duplicate linkage.

This contract MUST yield deterministic outcome under concurrent workers.

Isolation (normative):
- the **whole transaction** that performs steps 1–4 (ownership upsert + `page` updates) MUST use **`SERIALIZABLE`** isolation, or **`REPEATABLE READ`** only if explicitly justified for the statement mix under PostgreSQL;
- on **`SQLSTATE 40001`** (`serialization_failure`), the implementation MUST **retry** the transaction with bounded backoff + jitter per [TS-10](TS-10-storage-and-sql-contracts.md) / [TS-12](TS-12-error-model-and-recovery-policy.md);
- **do not** use a “raise isolation only if stress tests fail” posture; stronger isolation for this path is **required by spec**, not test-gated.

## Required Tests

- URL duplicate path inserts link without duplicating page row;
- content duplicate path sets `DUPLICATE` and clears HTML;
- distinct URLs with same content are correctly detected;
- non-duplicate content remains `HTML`.
- concurrent same-hash workers produce one canonical owner and deterministic duplicates.
- repeated reruns of same-hash concurrency tests must produce identical owner selection outcome.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/queue/dedup/`, `.../downloader/dedup/`, `.../storage/postgres/`
- key file(s): `queue/dedup/UrlSeenIndex.java`, `downloader/dedup/ContentHasherImpl.java`, `storage/postgres/repositories/PageRepository.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/queue/dedup/`, `.../unit/downloader/dedup/`, `.../integration/storage/postgres/`
