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
- `schema_version` table for assignment-scope drift checks:
  - `id INTEGER NOT NULL PRIMARY KEY` with **`CHECK (id = 1)`** (singleton anchor row only)
  - `version INTEGER NOT NULL`
  - exactly **one** row (`id = 1`) is required; migrations MUST **upsert** the version in place, never append a second row:
    - `INSERT INTO crawldb.schema_version (id, version) VALUES (1, :new_version) ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version`
- `page_type` lookup extension values:
  - `PROCESSING`
  - `ERROR`
- **`ck_page_processing_lease`** on `crawldb.page`: if `page_type_code = 'PROCESSING'` then **`claim_expires_at`**, **`claimed_at`**, and **`claimed_by`** are **NOT NULL**. Apply only after a **one-time repair** `UPDATE` that moves broken `PROCESSING` rows (any null lease column) back to `FRONTIER` and clears partial lease fields (see `pa1/db/crawldb.sql`).

## Required Indexes

- `idx_page_frontier_priority (page_type_code, relevance_score DESC, next_attempt_at ASC, accessed_time ASC, id ASC)`
- `idx_page_processing_lease (page_type_code, claim_expires_at ASC)`
- index on `page(content_hash)` for duplicate read path
- unique/primary key on `content_owner(content_hash)`
- if a legacy frontier index exists without `next_attempt_at` and `id`, migration MUST drop and recreate it.

## Versioned migration artifacts

- incremental DDL lives under **`pa1/db/migrations/`** with ordered names, e.g. `V002__processing_lease_check_schema_v2.sql`.
- **`pa1/db/migrations/checksums.sha256`** records **SHA-256** (GNU `shasum -a 256` format) for each file so operators or CI can verify files were not corrupted or edited accidentally; **recompute and update** that file whenever a migration script changes.
- **New database:** continue to apply full baseline via **`pa1/db/crawldb.sql`** (includes current `schema_version`).
- **Existing database** at an older `schema_version`: apply only the **pending** numbered migrations in order, then set `crawler.db.expectedSchemaVersion` to match.

| Migration file | Purpose |
| --- | --- |
| `V002__processing_lease_check_schema_v2.sql` | Repair orphan `PROCESSING` rows; add `ck_page_processing_lease`; bump `schema_version` to **2**. |
| `V003__page_data_metadata_schema_v3.sql` | Add `TITLE` / `META_DESCRIPTION` to `data_type`; unique index on `page_data(page_id, data_type_code)` for TS-10 upserts; bump `schema_version` to **3**. |

## Migration Strategy

- migrations SHOULD be idempotent where PostgreSQL allows; document one-shot steps when not;
- schema changes documented in this file before implementation;
- migration order:
  1. add columns
  2. backfill/defaults if needed
  3. add/extend `page_type` lookup (`PROCESSING`, `ERROR`)
  4. add ownership table for content hash
  5. add `schema_version` table (`id=1` PK + `CHECK (id=1)`) and set expected version via upsert
  6. add indexes and constraints (including frontier index replacement and **`ck_page_processing_lease`** after orphan repair `UPDATE`)
  7. validate claim/retry defaults for existing rows

Assignment-scope migration policy:
- complex rollback/compatibility matrices are out of scope;
- schema drift handling relies on startup/readiness exact-version checks (`TS-15`);
- migration policy expansion is deferred unless real migration incidents occur;
- migration execution owner for this project is manual local execution before crawler start (`psql -f pa1/db/crawldb.sql`, IDE SQL runner, or documented Gradle task if added later);
- crawler startup MUST validate connectivity and `schema_version` only, and MUST NOT auto-apply DDL migrations on process start;
- manual rollback procedure is documented in `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/migration-rollback-runbook.md`.

## Verification Checklist

- schema exists in expected namespace;
- required tables and lookup data present;
- extension columns and indexes present;
- smoke insert/read/delete succeeds.
- `PROCESSING` rows have **non-null** lease fields (`CHECK`); orphan repair migration applied before adding `ck_page_processing_lease` on upgrades.
- delayed frontier rows are excluded from claim until due time.
- content owner uniqueness enforces one canonical page per hash.
- **`crawldb.image.data`** is **NULL** for all inserted image rows (URL-only policy; [TS-04](TS-04-parser-and-extraction-specification.md)).

Practical verification commands (example):
- `SELECT * FROM crawldb.page_type;`
- `SELECT column_name FROM information_schema.columns WHERE table_schema='crawldb' AND table_name='page';`
- `SELECT indexname FROM pg_indexes WHERE schemaname='crawldb' AND indexname='idx_page_frontier_priority';`
- `SELECT content_hash, owner_page_id FROM crawldb.content_owner LIMIT 5;`
- `SELECT id, version FROM crawldb.schema_version WHERE id = 1;`
- `SELECT COUNT(*) FROM crawldb.schema_version;` MUST be `1`

Bootstrap seed verification:
- on empty frontier bootstrap, configured seeds must exist as `page_type_code='FRONTIER'`;
- seeded rows must initialize `next_attempt_at=now()` and `attempt_count=0`;
- repeated bootstrap runs must be idempotent (no duplicate `url` rows).

## Implementation Location

- primary folder(s): `pa1/db/`, `pa1/crawler/src/main/java/si/uni_lj/fri/wier/storage/postgres/`
- key file(s): `pa1/db/crawldb.sql`, migration SQL files in `pa1/db/`, `storage/postgres/repositories/PageRepository.java`
- test location(s): `pa1/crawler/src/test/java/si/uni_lj/fri/wier/integration/storage/postgres/`

