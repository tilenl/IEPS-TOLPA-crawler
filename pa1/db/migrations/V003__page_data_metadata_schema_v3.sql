-- Upgrade crawldb schema from version 2 → 3.
-- Adds TS-04/TS-10 page metadata data_type rows and unique (page_id, data_type_code) for idempotent page_data upserts.

INSERT INTO crawldb.data_type (code) VALUES ('TITLE'), ('META_DESCRIPTION')
ON CONFLICT (code) DO NOTHING;

CREATE UNIQUE INDEX IF NOT EXISTS unq_page_data_page_id_data_type ON crawldb.page_data (page_id, data_type_code);

INSERT INTO crawldb.schema_version (id, version) VALUES (1, 3)
ON CONFLICT (id) DO UPDATE SET version = EXCLUDED.version;
