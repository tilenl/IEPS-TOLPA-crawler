-- PA2 Phase 2 extension: persist v3 merge-group provenance for debugging.
-- Keep schema idempotent so migration can be re-applied safely.

ALTER TABLE crawldb.page_segment
	ADD COLUMN IF NOT EXISTS merge_group_parent TEXT;

COMMENT ON COLUMN crawldb.page_segment.merge_group_parent IS
	'Parent heading-group key used by v3 packing/repair (merge_group_path); NULL when not applicable.';
