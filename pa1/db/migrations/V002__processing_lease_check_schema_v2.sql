-- Upgrade crawldb schema from version 1 → 2.
-- Preconditions: schema_version row (id=1) exists and version = 1; crawler stopped;
--                 ck_page_processing_lease not already present (skip if DB was created from current crawldb.sql).

UPDATE crawldb.page
SET page_type_code = 'FRONTIER',
    claimed_by = NULL,
    claimed_at = NULL,
    claim_expires_at = NULL
WHERE page_type_code = 'PROCESSING'
  AND (claim_expires_at IS NULL OR claimed_at IS NULL OR claimed_by IS NULL);

ALTER TABLE crawldb.page
	ADD CONSTRAINT ck_page_processing_lease CHECK (
		page_type_code <> 'PROCESSING'
		OR (
			claim_expires_at IS NOT NULL
			AND claimed_at IS NOT NULL
			AND claimed_by IS NOT NULL
		)
	);

INSERT INTO crawldb.schema_version (id, version) VALUES (1, 2)
ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version;
