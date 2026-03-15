# TS-10 Storage And SQL Contracts

## Purpose

Define authoritative database operations and method-to-SQL mappings.

## Contracted Operations

- `insertFrontierIfAbsent(...)`
- `insertLink(...)`
- `updatePageHtml(...)`
- `markPageBinary(...)`
- `markPageDuplicate(...)`
- `upsertSite(...)`
- `insertImageRef(...)`
- `insertPageData(...)`
- `findPageByContentHash(...)`
- `storeContentHash(...)`

## SQL Catalog (Normative)

- **Claim next frontier row**
  - owned by `Frontier` contract (`TS-07`); storage repositories provide SQL implementation details.
- **Insert frontier if absent**
  - `INSERT ... ON CONFLICT (url) DO NOTHING`
  - returns insertion status so caller can branch new-vs-existing URL behavior
- **Insert link edge**
  - `INSERT INTO crawldb.link (from_page, to_page) ...`
- **Mark HTML outcome**
  - `UPDATE crawldb.page SET page_type_code='HTML', html_content=?, http_status_code=?, accessed_time=? WHERE id=?`
- **Mark duplicate**
  - `UPDATE crawldb.page SET page_type_code='DUPLICATE', html_content=NULL, content_hash=?, accessed_time=? WHERE id=?`
- **Find by content hash**
  - `SELECT id FROM crawldb.page WHERE content_hash=? LIMIT 1`

## Transaction Rules

- claim transaction isolated and short;
- page outcome update and related insertions SHOULD be atomic;
- ingestion insertions MUST be idempotent for retry safety.

## Data Integrity Rules

- `page.url` uniqueness is source of truth for seen URLs;
- every discovered relation must generate a `link` record;
- `site` must exist before `page` insert (`site_id` FK integrity).
- conflict path MUST be idempotent across retries and concurrent workers.

## Required Tests

- contract tests with real PostgreSQL for each method;
- conflict-path tests (`ON CONFLICT`) for URL dedup;
- multi-worker claim contention tests.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/storage/`, `.../storage/postgres/`, `.../storage/frontier/`
- key file(s): `storage/postgres/repositories/PageRepository.java`, `storage/frontier/FrontierStore.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/integration/storage/postgres/` and `.../integration/storage/frontier/`
