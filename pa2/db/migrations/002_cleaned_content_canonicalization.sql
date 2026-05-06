-- PA2 Phase 2 prep: canonicalize identical cleaned_content before segmentation.
-- This avoids duplicate embeddings for mirrored / renamed / alias repositories.

CREATE TABLE IF NOT EXISTS crawldb.cleaned_content_canonical (
	content_fingerprint CHAR(32) PRIMARY KEY,
	canonical_page_id INTEGER NOT NULL REFERENCES crawldb.page(id) ON DELETE CASCADE,
	cleaned_content TEXT NOT NULL,
	content_length INTEGER NOT NULL,
	duplicate_count INTEGER NOT NULL DEFAULT 1,
	updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS crawldb.page_cleaned_content_map (
	page_id INTEGER PRIMARY KEY REFERENCES crawldb.page(id) ON DELETE CASCADE,
	content_fingerprint CHAR(32) NOT NULL REFERENCES crawldb.cleaned_content_canonical(content_fingerprint) ON DELETE CASCADE,
	is_canonical BOOLEAN NOT NULL DEFAULT FALSE,
	mapped_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_page_cleaned_content_map_fingerprint
	ON crawldb.page_cleaned_content_map(content_fingerprint);

COMMENT ON TABLE crawldb.cleaned_content_canonical IS
	'Canonical unique cleaned_content payloads keyed by md5(cleaned_content).';
COMMENT ON TABLE crawldb.page_cleaned_content_map IS
	'Mapping from crawldb.page.id to canonical cleaned_content fingerprints.';
