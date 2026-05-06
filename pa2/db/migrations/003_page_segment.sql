-- PA2 Phase 2: segmentation storage table for Strategy C and future variants.
-- Keep schema idempotent so migrations can be re-applied safely during development.

CREATE TABLE IF NOT EXISTS crawldb.page_segment (
	id BIGSERIAL PRIMARY KEY,
	page_id INTEGER NOT NULL REFERENCES crawldb.page(id) ON DELETE CASCADE,
	chunk_index INTEGER NOT NULL,
	strategy TEXT NOT NULL,
	heading_path TEXT,
	segment_type TEXT NOT NULL,
	segment_text TEXT NOT NULL,
	token_estimate INTEGER NOT NULL,
	char_count INTEGER NOT NULL,
	created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
	CONSTRAINT uq_page_segment_page_strategy_chunk UNIQUE (page_id, strategy, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_page_segment_page_id
	ON crawldb.page_segment(page_id);

CREATE INDEX IF NOT EXISTS idx_page_segment_strategy
	ON crawldb.page_segment(strategy);

CREATE INDEX IF NOT EXISTS idx_page_segment_page_strategy
	ON crawldb.page_segment(page_id, strategy);

COMMENT ON TABLE crawldb.page_segment IS
	'PA2: segmented chunks generated from crawldb.page.cleaned_content for retrieval and embedding.';
COMMENT ON COLUMN crawldb.page_segment.chunk_index IS
	'0-based deterministic order of chunks for one page and one strategy.';
COMMENT ON COLUMN crawldb.page_segment.strategy IS
	'Segmentation strategy version (for example heading_structure_v1).';
COMMENT ON COLUMN crawldb.page_segment.heading_path IS
	'Hierarchical heading context extracted from markers like [H2], [H3].';
COMMENT ON COLUMN crawldb.page_segment.segment_type IS
	'Chunk class: prose, list_bundle, code_block, table_rows, mixed.';
