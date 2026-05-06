-- PA2 Phase 1: plain-text README column (idempotent).
-- Apply against the same database as PA1 (default: crawldb on postgresql-wier).

ALTER TABLE crawldb.page
	ADD COLUMN IF NOT EXISTS cleaned_content TEXT;

COMMENT ON COLUMN crawldb.page.cleaned_content IS
	'PA2: README/plain text extracted from html_content (GitHub rendered markdown body).';
