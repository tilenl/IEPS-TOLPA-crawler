-- PA2 Phase 2 extension: persist embedding-facing text separately from display text.
-- Keep schema idempotent so migration can be re-applied safely.

ALTER TABLE crawldb.page_segment
	ADD COLUMN IF NOT EXISTS embedding_text TEXT;

COMMENT ON COLUMN crawldb.page_segment.embedding_text IS
	'Embedding input text used for vector encoding; can include contextual prefixes while segment_text stays clean.';
