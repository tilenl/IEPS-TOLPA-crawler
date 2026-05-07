-- PA2 Phase 4: pgvector enablement and embedding storage on page_segment.
-- Dimension 384 is aligned with sentence-transformers/all-MiniLM-L6-v2.

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE crawldb.page_segment
	ADD COLUMN IF NOT EXISTS embedding vector(384);

COMMENT ON COLUMN crawldb.page_segment.embedding IS
	'Sentence embedding for embedding-facing segment text (all-MiniLM-L6-v2, 384 dimensions).';

-- Create ANN index now so environments are immediately query-ready.
-- For large bulk loads, it is still valid to recreate this index after loading.
CREATE INDEX IF NOT EXISTS idx_page_segment_embedding_hnsw_cosine
	ON crawldb.page_segment
	USING hnsw (embedding vector_cosine_ops);
