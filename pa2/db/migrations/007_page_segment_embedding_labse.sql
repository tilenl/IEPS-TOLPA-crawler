-- PA2: optional LaBSE sentence embeddings alongside MiniLM (384d) column.
-- Dimension 768 is aligned with sentence-transformers/LaBSE.

ALTER TABLE crawldb.page_segment
	ADD COLUMN IF NOT EXISTS embedding_labse vector(768);

COMMENT ON COLUMN crawldb.page_segment.embedding_labse IS
	'Sentence embedding for embedding-facing segment text (LaBSE, 768 dimensions).';

CREATE INDEX IF NOT EXISTS idx_page_segment_embedding_labse_hnsw_cosine
	ON crawldb.page_segment
	USING hnsw (embedding_labse vector_cosine_ops);
