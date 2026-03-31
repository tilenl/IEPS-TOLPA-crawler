-- Upgrade crawldb from schema version 7 to 8: HUB page type, github_hub_page flag.
-- Run after backup; align crawler.db.expectedSchemaVersion=8.

INSERT INTO crawldb.page_type (code) VALUES ('HUB') ON CONFLICT (code) DO NOTHING;

ALTER TABLE crawldb.page
	ADD COLUMN IF NOT EXISTS github_hub_page boolean NOT NULL DEFAULT false;

UPDATE crawldb.schema_version SET version = 8 WHERE id = 1;

-- Optional backfill (conservative): mark known topic paths only. Profile-style single-segment URLs are
-- classified at insert time on new crawls; omit complex URL parsing here.

UPDATE crawldb.page
SET github_hub_page = true
WHERE page_type_code IN ('FRONTIER', 'PROCESSING', 'HUB')
  AND github_hub_page = false
  AND url ILIKE 'https://github.com/topics%';

UPDATE crawldb.page
SET github_hub_page = true
WHERE page_type_code IN ('FRONTIER', 'PROCESSING', 'HUB')
  AND github_hub_page = false
  AND url ILIKE 'https://www.github.com/topics%';
