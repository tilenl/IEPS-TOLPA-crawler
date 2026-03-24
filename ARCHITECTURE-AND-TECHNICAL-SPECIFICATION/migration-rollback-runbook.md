# Migration Rollback Runbook (Assignment Scope)

## Purpose

Provide a manual rollback path for local schema changes in this project scope.

## Scope

- local laptop workflow;
- manual execution only (no automated downgrade tooling);
- use when a migration attempt leaves schema in an undesired state.

## Preconditions

1. stop crawler process;
2. connect with a DB user allowed to run DDL in schema `crawldb`;
3. take a backup if data must be preserved.

## Rollback Steps (Manual)

1. inspect current state:
   - `SELECT version FROM crawldb.schema_version;`
   - `SELECT indexname FROM pg_indexes WHERE schemaname='crawldb';`
2. rollback latest schema changes as needed:
   - drop/recreate frontier index if order is incorrect;
   - drop added columns/tables only if safe for your current data needs;
   - restore previous lookup/constraint shape if the migration changed it.
3. set schema version to the previous expected value:
   - `UPDATE crawldb.schema_version SET version = <previous_version>;`
4. rerun verification checks from `TS-11`.

## SQL Snippets For Current Baseline

Rollback frontier index to pre-fix ordering (only if explicitly needed):

```sql
DROP INDEX IF EXISTS crawldb.idx_page_frontier_priority;
CREATE INDEX idx_page_frontier_priority
ON crawldb.page (page_type_code, next_attempt_at ASC, relevance_score DESC, accessed_time ASC, id ASC);
```

Rollback `schema_version` table (only if reverting to an older baseline that did not use it):

```sql
DROP TABLE IF EXISTS crawldb.schema_version;
```

Restore `schema_version` table and set baseline version:

```sql
CREATE TABLE IF NOT EXISTS crawldb.schema_version (
    version integer NOT NULL
);
TRUNCATE TABLE crawldb.schema_version;
INSERT INTO crawldb.schema_version(version) VALUES (1);
```

## Verification After Rollback

- `SELECT version FROM crawldb.schema_version;`
- `SELECT indexdef FROM pg_indexes WHERE schemaname='crawldb' AND indexname='idx_page_frontier_priority';`
- run crawler startup validation; confirm schema version check outcome is as expected.

## Ownership

- migration execution owner: developer/operator running local environment setup;
- application responsibility: validate connectivity and schema version, fail fast on mismatch.
