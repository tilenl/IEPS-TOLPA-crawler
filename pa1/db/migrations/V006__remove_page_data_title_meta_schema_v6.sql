-- Upgrade crawldb schema from version 5 → 6.
-- Remove TITLE / META_DESCRIPTION from page_data and data_type; HTML metadata lives in page.html_content only.

DELETE FROM crawldb.page_data WHERE data_type_code IN ('TITLE', 'META_DESCRIPTION');

DELETE FROM crawldb.data_type WHERE code IN ('TITLE', 'META_DESCRIPTION');

INSERT INTO crawldb.schema_version (id, version) VALUES (1, 6)
ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version;
