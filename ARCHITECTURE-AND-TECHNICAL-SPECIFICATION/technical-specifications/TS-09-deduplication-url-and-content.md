# TS-09 Deduplication URL And Content

## Responsibility

Ensure crawler processes each URL once while identifying content-equivalent pages.

## Level 1: URL Deduplication

- authoritative check is DB uniqueness on canonical URL;
- use insert-if-absent contract;
- if URL exists, skip new `page` row but still insert `link`.

## Level 2: Content Deduplication

- after HTML fetch, compute SHA-256 hash;
- if hash exists for another page, mark current as `DUPLICATE`;
- set `html_content` to null for duplicate records;
- link duplicate page to canonical/original page if required by reporting strategy.

## Data Requirements

- `page.content_hash` (schema extension) for hash lookup;
- unique or indexed hash lookup depending on chosen duplicate policy.

## Required Tests

- URL duplicate path inserts link without duplicating page row;
- content duplicate path sets `DUPLICATE` and clears HTML;
- distinct URLs with same content are correctly detected;
- non-duplicate content remains `HTML`.

## Implementation Location

- primary folder(s): `pa1/crawler/src/main/java/si/uni_lj/fri/wier/queue/dedup/`, `.../downloader/dedup/`, `.../storage/postgres/`
- key file(s): `queue/dedup/UrlSeenIndex.java`, `downloader/dedup/ContentHasherImpl.java`, `storage/postgres/repositories/PageRepository.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/unit/queue/dedup/`, `.../unit/downloader/dedup/`, `.../integration/storage/postgres/`
