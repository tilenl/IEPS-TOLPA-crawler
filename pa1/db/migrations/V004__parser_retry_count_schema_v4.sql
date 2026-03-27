-- Upgrade crawldb schema from version 3 → 4.
-- TS-12: parser-stage retry budgeting independent of fetch-stage attempt_count (crawldb.page.parser_retry_count).

ALTER TABLE crawldb.page
    ADD COLUMN IF NOT EXISTS parser_retry_count integer NOT NULL DEFAULT 0;

INSERT INTO crawldb.schema_version (id, version) VALUES (1, 4)
ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version;
