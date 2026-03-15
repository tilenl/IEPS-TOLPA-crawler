# TS-09 Deduplication URL And Content

## Responsibility

Ensure crawler processes each URL once while identifying content-equivalent pages.

## Level 1: URL Deduplication

- authoritative check is DB uniqueness on canonical URL;
- use insert-if-absent contract;
- if URL exists, skip new `page` row but still insert `link`.
- canonical SQL mechanism:
  - `INSERT INTO crawldb.page (...) VALUES (...) ON CONFLICT (url) DO NOTHING`.

## Level 2: Content Deduplication

- after HTML fetch, compute SHA-256 hash;
- if hash exists for another page, mark current as `DUPLICATE`;
- set `html_content` to null for duplicate records;
- duplicate relationship link insertion is required for graph/report integrity.

## Data Requirements

- `page.content_hash` (schema extension) for hash lookup;
- unique or indexed hash lookup depending on chosen duplicate policy.
- DB-only URL dedup is normative for this architecture (no probabilistic pre-check stage).

## Example Flows

- URL duplicate:
  - discovered canonical URL already exists;
  - no new `page` row inserted;
  - `link(from=currentPage, to=existingPage)` still inserted.
- content duplicate:
  - new URL inserted and fetched;
  - hash collision found in `page.content_hash`;
  - current page updated to `DUPLICATE`, `html_content=NULL`.

## Required Tests

- URL duplicate path inserts link without duplicating page row;
- content duplicate path sets `DUPLICATE` and clears HTML;
- distinct URLs with same content are correctly detected;
- non-duplicate content remains `HTML`.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/queue/dedup/`, `.../downloader/dedup/`, `.../storage/postgres/`
- key file(s): `queue/dedup/UrlSeenIndex.java`, `downloader/dedup/ContentHasherImpl.java`, `storage/postgres/repositories/PageRepository.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/queue/dedup/`, `.../unit/downloader/dedup/`, `.../integration/storage/postgres/`
