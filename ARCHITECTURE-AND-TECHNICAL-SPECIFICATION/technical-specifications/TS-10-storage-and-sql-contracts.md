# TS-10 Storage And SQL Contracts

## Purpose

Define authoritative database operations and method-to-SQL mappings.

## Contracted Operations

- `claimFrontierRow()`
- `insertFrontierIfAbsent(...)`
- `insertLink(...)`
- `updatePageHtml(...)`
- `markPageBinary(...)`
- `markPageDuplicate(...)`
- `upsertSite(...)`
- `insertImageRef(...)`
- `insertPageData(...)`

## SQL Catalog (Normative)

- **Claim next frontier row**
  - uses `ORDER BY relevance_score DESC, accessed_time ASC LIMIT 1 FOR UPDATE SKIP LOCKED`
- **Insert frontier if absent**
  - `INSERT ... ON CONFLICT (url) DO NOTHING`
- **Insert link edge**
  - `INSERT INTO crawldb.link (from_page, to_page) ...`
- **Mark HTML outcome**
  - `UPDATE crawldb.page SET page_type_code='HTML', html_content=?, http_status_code=?, accessed_time=? WHERE id=?`
- **Mark duplicate**
  - `UPDATE crawldb.page SET page_type_code='DUPLICATE', html_content=NULL, content_hash=?, accessed_time=? WHERE id=?`

## Transaction Rules

- claim transaction isolated and short;
- page outcome update and related insertions SHOULD be atomic;
- ingestion insertions MUST be idempotent for retry safety.

## Data Integrity Rules

- `page.url` uniqueness is source of truth for seen URLs;
- every discovered relation must generate a `link` record;
- `site` must exist before `page` insert (`site_id` FK integrity).

## Required Tests

- contract tests with real PostgreSQL for each method;
- conflict-path tests (`ON CONFLICT`) for URL dedup;
- multi-worker claim contention tests.
