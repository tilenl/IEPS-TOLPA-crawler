# Programming Assignment 2 — Extraction and vector retrieval

This folder follows the course submission layout:

```
pa2/
├── report-extraction.pdf      # Final report (added when complete)
├── REPORT.md                   # Report checklist + extraction writeup (source for the PDF)
├── README.md                  # This file — setup, DB restore (expanded in later phases)
├── pa2_context.ipynb           # Condensed assignment context for notebooks / collaborators
├── db/migrations/
│   ├── 001_page_cleaned_content.sql
│   ├── 002_cleaned_content_canonicalization.sql
│   ├── 003_page_segment.sql
│   ├── 004_page_segment_embedding.sql
│   ├── 005_page_segment_merge_group_parent.sql
│   ├── 006_page_segment_embedding_text.sql
│   └── 007_page_segment_embedding_labse.sql
├── implementation-extraction/
│   ├── requirements.txt
│   ├── extract_readme.py      # Phase 1: HTML → README plain text → cleaned_content
│   ├── build_canonical_content_map.py  # Canonical dedup map for segmentation
│   ├── segment_cleaned_content.py  # Phase 2: Strategy C segmentation → page_segment
│   ├── embed_page_segments.py  # Phase 4: embeddings → page_segment.embedding / embedding_labse
│   └── demo.py                # Query demo for markers (later phases)
└── extraction-db/              # PostgreSQL dumps and restore artefacts
```

## Quick start (Python)

From `implementation-extraction/`:

```bash
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

## Phase 1 — README extraction (`cleaned_content`)

1. **Migration** (once per database): apply [`db/migrations/001_page_cleaned_content.sql`](db/migrations/001_page_cleaned_content.sql) (adds `crawldb.page.cleaned_content`).

   Example with Docker (`postgresql-wier` matches `pa1/README.md`):

   ```bash
   docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/001_page_cleaned_content.sql
   ```

   Optional (only if duplicate cleaned pages are expected): canonical dedup mapping migration:

   ```bash
   docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/002_cleaned_content_canonicalization.sql
   ```

2. **Populate** text from GitHub-rendered README (`article.markdown-body[itemprop="text"]`) into `cleaned_content`:

   ```bash
   cd pa2/implementation-extraction
   python3 -m venv .venv && source .venv/bin/activate
   pip install -r requirements.txt
   python extract_readme.py --batch-size 50
   ```

   Rows without a README block (commits, wiki history, empty repo) receive `cleaned_content = NULL`.

   Extraction now additionally:
   - prevents nested-list flattening duplicates (common in ToC blocks),
   - drops explicit Table-of-Contents sections,
   - preserves weak anchor links as `label (https://...)`,
   - prefixes headings as `[H1]` ... `[H6]` for downstream chunking.

Optional flags: `--dry-run`, `--limit N`, `--verbose`, `--recompute-all`.

3. **Optional: build canonical cleaned-content mapping** (skip in our current pipeline, because duplicate pages were already removed during crawling):

   ```bash
   cd pa2/implementation-extraction
   .venv/bin/python build_canonical_content_map.py
   ```

   If used, this materializes:
   - `crawldb.cleaned_content_canonical` (one row per unique `cleaned_content`)
   - `crawldb.page_cleaned_content_map` (maps every page to canonical content)

   Use `--dry-run` to inspect canonical/mapped counts without writes.
   This step is kept as a safeguard for future datasets where duplicate README content may reappear.

## Phase 2 — Strategy C segmentation (`page_segment`)

1. **Migration** (once per database):
   - apply [`db/migrations/003_page_segment.sql`](db/migrations/003_page_segment.sql) to create `crawldb.page_segment`,
   - then apply [`db/migrations/005_page_segment_merge_group_parent.sql`](db/migrations/005_page_segment_merge_group_parent.sql) to add `merge_group_parent` (v3 merge-group debug provenance),
   - then apply [`db/migrations/006_page_segment_embedding_text.sql`](db/migrations/006_page_segment_embedding_text.sql) to add `embedding_text` (v4 embedding payload).

   ```bash
   docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/003_page_segment.sql
   docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/005_page_segment_merge_group_parent.sql
   docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/006_page_segment_embedding_text.sql
   ```

2. **Run Strategy C segmentation** (heading-aware + structure-aware).  
   You can choose one of four strategy variants: `heading_structure_v1`, `heading_structure_v2`, `heading_structure_v3`, or `heading_structure_v4`.

   ```bash
   # v1 (baseline heading-aware segmentation)
   cd pa2/implementation-extraction
   .venv/bin/python segment_cleaned_content.py --strategy heading_structure_v1 --rebuild

   # v2 (combine-then-split with strict tokenizer budgeting)
   .venv/bin/python segment_cleaned_content.py --strategy heading_structure_v2 --rebuild
   
   # v3 (multi-pass combine/split + tiny-tail repair)
   .venv/bin/python segment_cleaned_content.py --strategy heading_structure_v3 --rebuild

   # v4 (v3 chunking + clean segment_text + contextual embedding_text)
   .venv/bin/python segment_cleaned_content.py --strategy heading_structure_v4 --rebuild
   ```

   For `heading_structure_v4`, after the final hard-cap split the pipeline runs **merge-group consolidation** (adjacent chunks that share the same internal `merge_group_parent` merge left-to-right while the real v4 combined model input stays under the hard token cap), optionally preceded by repeated **surfacing** rounds: a lonely deep merge group whose parent matches a neighbour’s merge group is promoted one level and the previous merge-group path is recorded for an additive **`Nested_scope:`** line in `embedding_text`. Surfacing and consolidation repeat until stable or until **`v4_surface_merge_max_iterations`** (default **5**), configurable via **`PA2_V4_SURFACE_MERGE_MAX_ITERATIONS`** or **`--v4-surface-merge-max-iterations`**. Use **`0`** to disable only the surfacing loop (one consolidation pass still runs). Consolidation itself remains always on for v4.

   Recommended first smoke test:

   ```bash
   .venv/bin/python segment_cleaned_content.py --dry-run --limit 50 --verbose
   ```

3. **Segment behavior highlights**:
   - section boundaries are derived from `[H1]` ... `[H6]` markers in `cleaned_content`,
   - list-heavy pages are bundled into medium list groups (instead of microchunks),
   - code/table-like blocks remain atomic where possible and are split safely when oversized,
   - one-block overlap is applied only for cap-triggered splits inside one section,
   - rows are deterministic per `(page_id, strategy, chunk_index)` and can be rebuilt by strategy.
   - for `heading_structure_v4`, each row stores:
     - `segment_text` (clean display text),
     - `embedding_text` (additive contextual prefix for v4: `Context:` / `Type:` / optional `Nested_scope:` / `Merged_sections:` — combined with `segment_text` only when encoding embeddings).
     - **Note:** `segment_type` in the database stays an **internal** structural label (`code_block`, `list_bundle`, `prose`, …). The `Type:` line inside `embedding_text` uses **display** wording when useful for retrieval: `code block` (two words so queries matching “code” or “block” overlap the prefix), and heading-based **`authors`** / **`references`** when every subsection title in the chunk normalizes to the documented authors/references heading sets (see `segment_cleaned_content.py`).

4. **Useful CLI flags**:
   - `--page-id <id>`: process a single page for debugging,
   - `--batch-size <n>`: insert/update threshold,
   - `--dry-run`: compute quality stats without writes,
   - `--rebuild`: delete old rows for the chosen strategy before inserting,
   - `--v3-refine-rounds <n>`: outer split/repair refinement rounds for `heading_structure_v3`,
   - `--self-test`: run embedded algorithm checks without DB access.

5. **`heading_structure_v2` token policy** (for `all-MiniLM-L6-v2`):
   - strict tokenizer counting (not regex/word estimation),
   - Stage A: collapse small sibling subsections under the same parent heading,
   - Stage B: split oversized chunks by subsection/paragraph/sentence/token-window fallbacks,
   - child subsection names are kept in embedding-facing text while `segment_text` remains clean.

   Optional environment overrides:
   - `PA2_V2_TARGET_TOKENS` (default `200`)
   - `PA2_V2_COLLAPSE_UPPER_BOUND_TOKENS` (default `220`)
   - `PA2_V2_HARD_CAP_TOKENS` (default `240`)
   - `PA2_V2_SMALL_SUBSECTION_MAX_TOKENS` (default `40`)
   - `PA2_V2_LOCAL_FILES_ONLY=1` to force tokenizer loading from local cache only.

6. **`heading_structure_v3` token policy** (for `all-MiniLM-L6-v2`):
   - Stage A: greedily packs consecutive sibling subsections under the same parent heading up to a soft target.
   - Refinement rounds: repeat Stage B + Stage C `N` times (`N = PA2_V3_REFINE_ROUNDS`).
   - Stage B: strict hard-cap splitting by subsection/paragraph/sentence/token-window fallbacks.
   - Stage C: repairs underfilled chunks by merging tiny tails with adjacent sibling chunks when cap-safe.
   - Final output is split once more to enforce the hard cap after repair.

   Optional environment overrides:
   - `PA2_V3_SOFT_TARGET_TOKENS` (default `200`)
   - `PA2_V3_HARD_CAP_TOKENS` (default `240`)
   - `PA2_V3_MIN_CHUNK_TOKENS` (default `35`)
   - `PA2_V3_REPAIR_MAX_PASSES` (default `2`)
   - `PA2_V3_REFINE_ROUNDS` (default `1`)
   - `PA2_V3_REPAIR_MAX_PASSES` is the inner merge loop cap inside one Stage C run.

7. **`heading_structure_v4` token policy** (for `all-MiniLM-L6-v2`):
   - Uses v3 combine/split/repair flow as the structural backbone.
   - Keeps `segment_text` clean (without subsection marker injection).
   - Builds additive `embedding_text` with context metadata (`Context: ...`, `Type: ...`) where `Type:` uses display labels as described above (not necessarily identical to the `segment_type` column).
   - Enforces hard-cap checks on the **true** combined model input (`embedding_text` prefix plus `segment_text` body), matching what `embed_page_segments.py` encodes.

## Phase 4 — Embeddings (`page_segment.embedding`)

1. **Migration** (once per database): apply [`db/migrations/004_page_segment_embedding.sql`](db/migrations/004_page_segment_embedding.sql).

   ```bash
   docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/004_page_segment_embedding.sql
   ```

   This migration:
   - enables pgvector extension (`CREATE EXTENSION IF NOT EXISTS vector`),
   - adds `embedding vector(384)` to `crawldb.page_segment`,
   - creates cosine HNSW ANN index (`vector_cosine_ops`).

   Optional **LaBSE** column (768 dimensions) and its cosine HNSW index:

   ```bash
   docker exec -i postgresql-wier psql -U user -d crawldb < pa2/db/migrations/007_page_segment_embedding_labse.sql
   ```

2. **Generate embeddings** (`--embedding minilm` is the default; use `labse` after applying migration `007`):

   ```bash
   cd pa2/implementation-extraction
   .venv/bin/python embed_page_segments.py --strategy heading_structure_v4 --batch-size 64
   .venv/bin/python embed_page_segments.py --embedding labse --strategy heading_structure_v4 --batch-size 32
   ```

3. **Recommended smoke test** (no writes):

   ```bash
   .venv/bin/python embed_page_segments.py --strategy heading_structure_v4 --limit 200 --dry-run --verbose
   .venv/bin/python embed_page_segments.py --embedding labse --strategy heading_structure_v4 --limit 50 --dry-run --verbose
   ```

4. **Useful CLI flags**:
   - `--embedding {minilm,labse}`: bi-encoder + target column (`embedding` vs `embedding_labse`),
   - `--rebuild-embeddings`: re-encode rows even if the target embedding column is already populated,
   - `--page-id <id>`: isolate one page during debugging,
   - `--max-retries <n>` and `--retry-delay-seconds <s>`: tune failure recovery,
   - `--near-zero-norm-threshold <f>`: stricter/looser anomaly warning,
   - `--no-normalize`: disable L2 normalization during encoding.

5. **Diagnostics logged per run**:
   - rows seen/embedded/skipped/failed,
   - norm distribution (min/median/p95),
   - near-zero vector count,
   - sparse-vector count (very low non-zero dimension ratio).

**Connection**: defaults `localhost:5432`, database `crawldb`, user `user`, password `SecretPassword`. Override with `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`.

Optional local snapshot directory for LaBSE (mirrors `PA2_LOCAL_SENTENCE_MODEL_PATH` for MiniLM): set **`PA2_LOCAL_LABSE_MODEL_PATH`** to an absolute path when using offline-first runs.

## Phase 5 — Retrieval demo (`demo.py`)

`demo.py` now implements the full assignment retrieval loop:
1. read query,
2. embed query with the selected bi-encoder (`--embedding minilm` default, or `labse` when that column is populated),
3. run pgvector similarity search on the matching column,
4. print top-k segment hits with `page_id`, `segment_id`, `chunk_index`, `strategy`, and source URL.

Run from `pa2/implementation-extraction/` and activate the virtual environment first:

```bash
cd pa2/implementation-extraction
source .venv/bin/activate
python demo.py --query "How do I use pretrained models?" --top-k 5
python demo.py --embedding labse --strategy heading_structure_v4 --query "How do I use pretrained models?" --top-k 5
```

### Similarity metric experiments

Cosine (recommended with current schema/index):

```bash
python demo.py --query "optimizer learning rate settings" --metric cosine --top-k 5
```

L2 distance:

```bash
python demo.py --query "optimizer learning rate settings" --metric l2 --top-k 5
```

Inner product:

```bash
python demo.py --query "optimizer learning rate settings" --metric inner_product --top-k 5
```

L1 distance:

```bash
python demo.py --query "optimizer learning rate settings" --metric l1 --top-k 5
```

### Optional reranking

Use cross-encoder reranking on top of embedding retrieval:

```bash
python demo.py --query "how to train DeepLabV3+" --top-k 5 --rerank --candidate-k 20
```

If reranking model loading fails, the script logs a warning and falls back to base retrieval output.

### Built-in qualitative evaluation set

The script includes:
- 3 expected-good domain queries,
- 3 intentionally weak/hard queries.

Run all six queries:

```bash
python demo.py --run-eval --top-k 5
```

Run evaluation with reranking enabled:

```bash
python demo.py --run-eval --top-k 5 --rerank --candidate-k 20
```

### Index / metric alignment note

Migrations `004_page_segment_embedding.sql` and `007_page_segment_embedding_labse.sql` create cosine HNSW indexes (`vector_cosine_ops`) on `embedding` and `embedding_labse` respectively.
Other metrics (`l2`, `inner_product`, `l1`) are still valid for experimentation, but may run slower because no matching ANN index is currently created for those operators.

---

Database restore steps for submission dumps will be documented when `extraction-db/` is finalized.

**See also**: [PROGRAMMING-ASSIGNMENT-2.md](../PROGRAMMING-ASSIGNMENT-2.md) for full notes and official rubric; [REPORT.md](REPORT.md) for the PDF checklist and GitHub README extraction summary.
