# TS-11 Database Schema And Migrations

## Base Schema Source

- baseline schema: `pa1/db/crawldb.sql`
- core tables: `site`, `page`, `link`, `image`, `page_data`, `page_type`, `data_type`

## Required Extensions

- `page.relevance_score DOUBLE PRECISION NOT NULL DEFAULT 0.0`
- `page.content_hash VARCHAR(64)` for content dedup
- `page.next_attempt_at TIMESTAMP NOT NULL DEFAULT now()`
- `page.attempt_count INTEGER NOT NULL DEFAULT 0`
- `page.claimed_by VARCHAR(128)`
- `page.claimed_at TIMESTAMP`
- `page.claim_expires_at TIMESTAMP`
- `page.last_error_category VARCHAR(64)`
- `page.last_error_message TEXT`
- `page.last_error_at TIMESTAMP`
- supporting indexes for frontier dequeue, lease recovery, and hash lookup
- `content_owner` table for deterministic content-dedup ownership:
  - `content_hash VARCHAR(64) PRIMARY KEY`
  - `owner_page_id INTEGER NOT NULL REFERENCES crawldb.page(id)`
  - `created_at TIMESTAMP NOT NULL`
- `page_type` lookup extension values:
  - `PROCESSING`
  - `ERROR`

## Required Indexes

- `idx_page_frontier_priority (page_type_code, next_attempt_at ASC, relevance_score DESC, accessed_time ASC, id ASC)`
- `idx_page_processing_lease (page_type_code, claim_expires_at ASC)`
- index on `page(content_hash)` for duplicate read path
- unique/primary key on `content_owner(content_hash)`
- if a legacy frontier index exists without `next_attempt_at` and `id`, migration MUST drop and recreate it.

## Migration Strategy

- migrations MUST be idempotent;
- schema changes documented in this file before implementation;
- migration order:
  1. add columns
  2. backfill/defaults if needed
  3. add/extend `page_type` lookup (`PROCESSING`, `ERROR`)
  4. add ownership table for content hash
  5. add indexes and constraints (including frontier index replacement)
  6. validate claim/retry defaults for existing rows

## Verification Checklist

- schema exists in expected namespace;
- required tables and lookup data present;
- extension columns and indexes present;
- smoke insert/read/delete succeeds.
- `PROCESSING` lease fields are nullable/initialized correctly on existing data.
- delayed frontier rows are excluded from claim until due time.
- content owner uniqueness enforces one canonical page per hash.

Practical verification commands (example):
- `SELECT * FROM crawldb.page_type;`
- `SELECT column_name FROM information_schema.columns WHERE table_schema='crawldb' AND table_name='page';`
- `SELECT indexname FROM pg_indexes WHERE schemaname='crawldb' AND indexname='idx_page_frontier_priority';`
- `SELECT content_hash, owner_page_id FROM crawldb.content_owner LIMIT 5;`

Bootstrap seed verification:
- on empty frontier bootstrap, configured seeds must exist as `page_type_code='FRONTIER'`;
- seeded rows must initialize `next_attempt_at=now()` and `attempt_count=0`;
- repeated bootstrap runs must be idempotent (no duplicate `url` rows).

## Implementation Location

- primary folder(s): `pa1/db/`, `pa1/crawler/src/main/java/si/uni_lj/fri/wier/storage/postgres/`
- key file(s): `pa1/db/crawldb.sql`, migration SQL files in `pa1/db/`, `storage/postgres/repositories/PageRepository.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/integration/storage/postgres/`

