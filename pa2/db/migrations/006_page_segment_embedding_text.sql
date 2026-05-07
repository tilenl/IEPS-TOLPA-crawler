-- PA2 Phase 2 extension: persist embedding-facing text separately from display text.
-- Keep schema idempotent so migration can be re-applied safely.

ALTER TABLE crawldb.page_segment
	ADD COLUMN IF NOT EXISTS embedding_text TEXT;

COMMENT ON COLUMN crawldb.page_segment.embedding_text IS
	'For heading_structure_v4: additive prefix only (Context, Type, optional Merged_sections); full model input is prefix plus segment_text. Other strategies may store the full embedding payload.';
