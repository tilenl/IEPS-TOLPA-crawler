# TS-11 Database Schema And Migrations

## Base Schema Source

- baseline schema: `pa1/db/crawldb.sql`
- core tables: `site`, `page`, `link`, `image`, `page_data`, `page_type`, `data_type`

## Allowed Extensions

- `page.relevance_score DOUBLE PRECISION NOT NULL DEFAULT 0.0`
- `page.content_hash VARCHAR(64)` for content dedup
- supporting indexes for frontier dequeue and hash lookup

## Required Indexes

- `idx_page_frontier_priority (page_type_code, relevance_score DESC, accessed_time ASC)`
- index on `page(content_hash)` for duplicate checks

## Migration Strategy

- migrations MUST be idempotent;
- schema changes documented in this file before implementation;
- migration order:
  1. add columns
  2. backfill/defaults if needed
  3. add indexes

## Verification Checklist

- schema exists in expected namespace;
- required tables and lookup data present;
- extension columns and indexes present;
- smoke insert/read/delete succeeds.

## Implementation Location

- primary folder(s): `pa1/db/`, `pa1/crawler/src/main/java/si/uni_lj/fri/wier/storage/postgres/`
- key file(s): `pa1/db/crawldb.sql`, migration SQL files in `pa1/db/`, `storage/postgres/repositories/PageRepository.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/integration/storage/postgres/`

