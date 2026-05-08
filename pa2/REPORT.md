# PA2 report — checklist and extraction notes

This file is **source material** for **`report-extraction.pdf`**. Turn the checklist into full prose in the PDF; keep this Markdown as your working outline.

---

## What to cover in **`report-extraction.pdf`** (course §6.1)

Use the official headings that match your write-up flow; include every bullet.

1. **Website filtering / extraction** — What you threw away globally (menus, chrome, scripts, assets) versus what you kept. For this project: crawler already limits to GitHub-ish pages; extraction further isolates **only the rendered README** subtree (see below).
2. **Chunking strategy** — Criteria for thematic sections (paragraphs vs fixed windows vs headings, merge/split rules, token limits tied to the embedding model). Implemented strategy plus **advantages and disadvantages**.
3. **Embeddings** — Which model(s) you chose, English vs multilingual rationale, experiments with alternatives ([sentence-transformers](https://www.sbert.net/) / Hugging Face), and models you tried but discarded.
4. **Similarity metric** — Cosine vs L2 vs inner product in pgvector, index type (IVFFlat or HNSW), and why it matches how you encode queries and segments.
5. **Queries and answers** — **Three** queries that retrieve **good** segments for your domain, **three** that are **weak or misleading**, each with short note of what came back (IDs, URLs, or quoted snippets).
6. **Reranking** — Cross-encoder (or equivalent) identity, rerank pairs (query + segment), **examples where order improves** on the weaker queries from (5).
7. **Where reranking still fails** — At least **one** honest example after reranking (still wrong ranking or irrelevant top hit).
8. **Retriever limitations** — Failure modes that remain (coverage, jargon, non-English, empty README repos, oversized chunks, domain mismatch).
9. **Methods explored but dropped** — e.g. XPath vs regex-only, whole-body `get_text()`, other chunking/embeddings/layout heuristics, with one line each on why.

**When drafting `report-extraction.pdf`:** In the website filtering / extraction part, **include the figure** [`pytorch-segmentation-code-block.png`](pytorch-segmentation-code-block.png) (saved next to this file) alongside the code-block discussion so the PDF shows how the YAML looks on GitHub before contrasting it with broken vs fixed plain-text extraction (see *Code blocks* below).

**Submission housekeeping (not necessarily inside the PDF):** same repo layout as the brief, **`opbieps`** read access on GitHub, `extraction-db` dump path and restore instructions in [`README.md`](README.md).

---

## Extraction implementation (GitHub-specific)

### Why the README is easy to pinpoint on repo pages

A saved GitHub **repository landing** HTML is dominated by chrome: global header, sidebar, tabs, metadata, files table, footer, trackers, JSON/React bootstrap blobs, etc. The **actually rendered README** lives in **one recognizable subtree**, not sprinkled across unrelated divs like on a generic CMS page.

Semantics: GitHub exposes the prose as **`itemprop="text"`** inside an **`article`** whose classes include **`markdown-body`** (often also **`entry-content`** and **`container-lg`**). That combination is deliberately narrow: outside it you still find interactive UI and lists of files—the README block is distinct.

### Locating only that subtree

The pipeline parses the stored document with BeautifulSoup (**lxml**). It resolves the README root **in priority order**:

1. **`article.markdown-body[itemprop="text"]`** — matches the advertised “repository README” prose region.
2. **`article.markdown-body.entry-content`** — slightly wider fall-back if markup varies.
3. **`article.markdown-body`** — last resort inside the markdown article only.

Everything **outside** the chosen `article` is ignored: navigation, menus, file browser, unrelated sections never enter the extractor.

### Caveats intrinsic to GitHub pages

Some crawled URLs are **commits**, **wiki history**, etc. Those snapshots often **do not** contain this README `<article>`; they return **no** usable README tree. Repos **without** a README (or with empty prose) similarly yield nothing to extract—that is intentional: `cleaned_content` stays empty / `NULL` rather than hallucinating repo text from unrelated DOM.

Inside the subtree, **noise** is stripped locally (scripts/styles, badges-as-images logic, redundant heading-anchor chrome, SVG) so embeddings are not polluted by markup-only nodes. Structured blocks (**headings, paragraphs, lists, blockquotes, `pre`, table rows**) are turned into newline-separated paragraphs for later segmentation.

To improve chunk quality for PA2 retrieval, extraction also applies four normalization rules before writing `cleaned_content`:

1. **Nested-list protection:** parent list items that contain nested lists are not flattened, which prevents duplicated "Table of Contents"-style text.
2. **ToC suppression:** explicit *Table of Contents* sections are removed.
3. **Selective link preservation:** weak anchor labels such as "paper" / "code" preserve external targets in the text (`paper (https://...)`), while descriptive anchor text stays readable.
4. **Heading markers:** headings are emitted as `[H1]` ... `[H6]` to preserve section structure for chunkers and embeddings.

### Code blocks: syntax highlighting vs plain-text layout

On the live page, a fenced code block looks like a normal monospace snippet with sensible line breaks and indentation. In the **HTML**, GitHub applies syntax highlighting by wrapping tokens in many nested **`<span>`** elements. Our extractor walks block-level nodes and turns each `<pre>` into text by concatenating descendant strings. With one string fragment per span, that inserts **extra line breaks between pieces** that were a single logical line on screen—so the **extracted layout no longer matches** what readers see.

**Example:** [nyoki-mtl/pytorch-segmentation](https://github.com/nyoki-mtl/pytorch-segmentation) — the YAML config sample in the README (see screenshot [`pytorch-segmentation-code-block.png`](pytorch-segmentation-code-block.png)) was first extracted like this:

```text
Net
:
enc_type
:
'
resnet18
'
dec_type
:
'
unet_scse
'
num_filters
:
8
pretrained
:
True
Data
:
dataset
:
'
pascal
'
target_size
:
(512, 512)
Train
:
max_epoch
:
20
batch_size
:
2
fp16
:
True
resume
:
False
pretrained_path
:
Loss
:
loss_type
:
'
Lovasz
'
ignore_index
:
255
Optimizer
:
mode
:
'
adam
'
base_lr
:
0.001
t_max
:
10
```

That representation is awkward for humans and for downstream chunking.

**Edge case / fix:** GitHub wraps many code blocks in a container that carries the **exact string users get from the “copy code” control** in the HTML attribute **`data-snippet-clipboard-copy-content`** (on a parent `div` of the highlighted `<pre>`, not split across per-token spans). When we detect a `<pre>`, we **walk up the tree**; if an ancestor exposes that attribute, we **use its decoded value** as the block text instead of DOM `get_text()`. That recovers the author-intended layout, e.g.:

```text
Net:
  enc_type: 'resnet18'
  dec_type: 'unet_scse'
  num_filters: 8
  pretrained: True
Data:
  dataset: 'pascal'
  target_size: (512, 512)
Train:
  max_epoch: 20
  batch_size: 2
  fp16: True
  resume: False
  pretrained_path:
Loss:
  loss_type: 'Lovasz'
  ignore_index: 255
Optimizer:
  mode: 'adam'
  base_lr: 0.001
  t_max: 10
```

If the attribute is missing (older markup or non-GitHub pages), we still fall back to `<pre>` text extraction as before.

Together, this focuses retrieval on **one stable semantic island** of the crawl—README prose—rather than scraping the entire GitHub SPA shell.

### Deduplication before embeddings

We also evaluated a canonicalization step based on fingerprinting full `cleaned_content` (`md5(cleaned_content)`) before embedding. This would merge only pages with **identical full extracted README text** (not sentence-level overlap), e.g.:

- Page A: `This is a segmentation repository. Train with U-Net.`
- Page B: `This is a segmentation repository. Train with U-Net.`
- Page C: `This is a segmentation repository. Train with DeepLabV3+.`

In this scenario, A and B would map to one canonical content row, while C would remain separate.

However, in our final pipeline this step is **not required**, because duplicate pages were already removed earlier during crawling/collection. Therefore, we keep canonical mapping as an optional safeguard, but it is not necessary for normal embedding generation in our current dataset.

---

## Segmentation strategy selected for Phase 2

We evaluated three chunking families and selected **Strategy C (heading-aware + structure-aware)** as the implementation target for `crawldb.page_segment`.

### Why Strategy C over A/B for this dataset

- **Strategy A (fixed token windows)** is easy to baseline, but it can merge unrelated sections when windows cross heading boundaries.
- **Strategy B (paragraph merge)** improves size stability, but still risks blending conceptually different subtopics when many short blocks are adjacent.
- **Strategy C** leverages extraction-time `[H1]..[H6]` markers to preserve section semantics, which directly helps domain questions such as model recommendations and pipeline choices.

In practice, the heading path (`README > section > subsection`) carries context that should remain coupled with each segment when generating embeddings.

### Strategy C rules implemented

1. **Section boundaries first:** segmentation never crosses heading transitions.
2. **Adaptive size window:** target chunks are built toward a medium token range with a hard cap for outliers.
3. **Structure-aware handling:**
   - code and table-like blocks are kept atomic where possible,
   - oversized atomic blocks are split internally with safe boundaries,
   - list-heavy (awesome-list style) sections are grouped into medium bundles (not one giant chunk, not many tiny chunks).
4. **Local overlap only:** one-block overlap is used only when a section is split due to size cap; overlap never crosses sections.
5. **Deterministic chunk identity:** each row is stored by `(page_id, strategy, chunk_index)` for reproducible rebuilds and strategy comparisons.

### Database extension for segmentation

Phase 2 introduces `crawldb.page_segment` (migration `003_page_segment.sql`) with fields required for retrieval analysis before embeddings:
- `page_id`, `chunk_index`, `strategy`,
- `heading_path`, `segment_type`,
- `segment_text`, `embedding_text`, `token_estimate`, `char_count`.

This keeps segmentation experiments traceable and allows multiple strategy labels side-by-side without overwriting historical runs.

### Segment type metadata and classification rules

The `segment_type` label is not cosmetic; it captures the structural role of a chunk and helps downstream ranking/prompting.

- **`prose`**: narrative/explanatory text blocks (typical README paragraphs).
- **`list_bundle`**: grouped bullet/numbered items from list-heavy sections (for example awesome-list entries).
- **`code_block`**: code/config/log-like content where line structure and symbols carry meaning.
- **`table_rows`**: table-like rows where columns are important.
- **`mixed`**: a merged chunk that contains multiple structural kinds (for example prose + code snippet).

`code_block` is assigned using explicit heuristics during block classification:
- fenced markers (```) indicate code immediately,
- line-level indentation patterns (`    ` or tab-prefixed lines) indicate preformatted blocks,
- symbol-heavy lines (e.g. many `{ } ( ) : = < > # [ ]`) suggest config/code/log syntax,
- for multi-line blocks, high punctuation/code-symbol density increases confidence that the block is code-like.

This rule-set is intentionally conservative: we prefer preserving technical formatting as `code_block` instead of flattening it into prose, because many repository answers depend on exact option names, keys, commands, or stack-trace fragments.

**Dynamic type assignment during chunking:**
When combining multiple blocks or parts into a single segment (e.g., during v2/v3 packing), the segmentation logic explicitly checks the types of the composing parts. If all parts share the exact same type (e.g., all are `prose` or all are `code_block`), the combined segment inherits that specific type. However, if the combined segment contains a mixture of different types (such as a `code_block` combined with a `prose` block), the `segment_type` is automatically updated to `"mixed"`.

### How this metadata improves LLM answers

The retrieval payload can include `heading_path`, `segment_type`, and size metadata (`token_estimate`, `char_count`) alongside `segment_text`. This adds context for the LLM before answer generation:
- **Better interpretation:** `heading_path` tells the model where the text sits in repository structure (`README > Training > Hyperparameters`).
- **Better weighting:** `segment_type` allows the pipeline to prioritize `prose` for conceptual questions and `code_block`/`table_rows` for implementation-detail questions.
- **Safer synthesis:** metadata reduces accidental blending of unrelated sections and helps justify why a segment is relevant.

As additional provenance metadata, we also include the **original repository link** for each segment (resolved from `page_id` by joining to the source page URL). This enables citation-ready answers where the LLM can state where the information came from, improving transparency and trust in generated responses.

### `heading_structure_v2`: combine-then-split refinement

After running `heading_structure_v1`, we observed many micro-segments on subsection-heavy pages. To address this, we added `heading_structure_v2` with a two-stage policy:

1. **Combine stage (section-aware collapse):**
   - sibling subsections under the same parent are merged when they are individually small and the merged chunk stays below a collapse upper bound;
   - subsection labels are kept in embedding-facing chunk text (for example `### Our best model yet`) so local structure is preserved even when `heading_path` is promoted to the parent section while `segment_text` can remain clean for display.
2. **Split stage (strict safety):**
   - chunks above the model cap are split by boundary priority:
     1. subsection boundary,
     2. paragraph/line boundary,
     3. sentence boundary,
     4. token-window fallback.

In implementation (`implementation-extraction/segment_cleaned_content.py`), the split is greedy and cap-driven:
- if an oversized packed chunk has multiple subsection parts, it first packs as many whole subsections as possible into one output chunk without crossing the hard cap; when the next subsection would overflow, it starts a new chunk;
- if one subsection part is still too large on its own, boundary fallback is applied inside that part:
  - for code/table-like content, split by lines first (line boundaries preserve structure better than sentence cuts),
  - for prose/list-like content, split by paragraph blocks first;
- if the paragraph/line pass still leaves an oversized piece, it falls back to sentence splitting;
- if sentences still cannot satisfy the cap (for example very long unpunctuated spans), the last resort is strict token-window splitting (word windows sized to the tokenizer cap, with character-slice fallback for pathological long tokens/URLs).

This design is constrained by the embedding model limit: `all-MiniLM-L6-v2` has a maximum sequence length of **256 tokens**. Because of that, v2 uses **tokenizer-based budgeting** (accurate to the model budget, but slower) instead of simple word/regex budgeting (faster, but less accurate).

For this strategy, token lengths are measured using the actual tokenizer of `sentence-transformers/all-MiniLM-L6-v2`. This ensures chunk budgets match what the embedding model actually sees at inference time.

We also track additional quality diagnostics for v2:
- percentage of chunks shorter than 20/40 tokens (micro-chunk rate),
- percentage of chunks above the configured hard cap (must be 0),
- min/median/p95 token counts and per-page chunk spread.

### Quality statistics: `heading_structure_v1` vs `heading_structure_v2`

Metrics were recomputed from `crawldb.page_segment` for both strategies on the same eligible page set:

- pages read: `4031` (non-empty `cleaned_content` pages)
- pages with segments: `4011` for both strategies (coverage `99.5%`; 20 pages remained segment-empty)

`heading_structure_v1`:
- total segments: `42893`
- tokens per segment: min `1`, median `78`, p95 `311`
- micro-segments: `<20` tokens `6264` (`14.6%`), `<40` tokens `12960` (`30.2%`)
- overflow above hard cap (`420` tokens): `261` (`0.6%`)

`heading_structure_v2`:
- total segments: `46603`
- tokens per segment: min `3`, median `149`, p95 `236`
- micro-segments: `<20` tokens `955` (`2.0%`), `<40` tokens `3766` (`8.1%`) (repositories with only a few words in the README 
could not be grouped any more, as the few words are all that was in the README file. As such, these repositories explain why we 
have 2% micro-segments under 20 tokens, and 8.1% micro segments under 40 tokens)
- overflow above hard cap (`240` tokens): `0` (`0.0%`)

Interpretation:
- v2 significantly reduces micro-fragmentation (especially `<40` token chunks),
- raises median chunk density closer to the target context budget,
- and enforces the configured hard cap strictly.

### Why v2 still left avoidable fragmentation

Despite strong global gains, we found a local limitation in v2 combine logic:

- if the first subsection in a parent group is already large (`>= v2_collapse_upper_bound_tokens = 220`), v2 flushes the active collapse buffer and emits it as a standalone chunk,
- later medium siblings such as `50` and `80` tokens are **not** considered "small" (`v2_small_subsection_max_tokens = 40`),
- therefore these later siblings do not necessarily start/extend a collapse group in the same pass, even when `50 + 80` would fit comfortably.

This is the concrete `240 + 50 + 80` failure mode that motivated v3.

We also observed another pattern on subsection-heavy pages: after split fallback, tiny tails can resurface as standalone chunks (for example short link-only or citation-only tails). In v2 there is no post-split recombination stage, so these fragments remain small even when adjacent sibling chunks could absorb them without violating the cap.

![Observed residual fragmentation in v2](../why-we-implemented-v3-of-segmentation.png)

### `heading_structure_v3`: multi-pass combine/split repair

To fix these cases we implemented `heading_structure_v3`:

1. **Pass A (parent-aware greedy combine):** greedily combine consecutive sibling subsections under the same parent up to a soft target budget.
2. **Refinement rounds (`PA2_V3_REFINE_ROUNDS`):** repeat Pass B + Pass C `N` times (default `N = 1`).
3. **Pass B (strict hard-cap split):** keep v2 boundary-priority split logic to enforce model-safe max tokens.
4. **Pass C (tiny-tail repair):** merge underfilled chunks with adjacent siblings (same parent group, cap-safe), where `PA2_V3_REPAIR_MAX_PASSES` caps the inner merge attempts per round.
5. **Final split:** re-check hard-cap safety after refinement.

This multi-pass flow addresses both:
- missed combinations after one large sibling already filled capacity,
- small fragments created by split fallback.

Configuration note:
- `PA2_V3_REFINE_ROUNDS` controls how many outer split/repair cycles run.
- `PA2_V3_REPAIR_MAX_PASSES` controls how many inner merge passes one repair stage may perform.
- The aggregate comparisons below were produced with `PA2_V3_REFINE_ROUNDS=1` (default).

### Concrete v3 improvement statistics (targeted pages)

To provide concrete evidence, we recomputed segmentation for known problematic pages and compared existing v2 DB rows with v3 outputs under the same tokenizer budgeting:

| Page ID | Strategy | Segments | Min | Median | Avg | Max | `<20` | `<40` | `>cap` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `8` | v2 | `21` | `40` | `130` | `134` | `226` | `0` | `0` | `0` |
| `8` | v3 | `21` | `40` | `130` | `134` | `226` | `0` | `0` | `0` |
| `715` | v2 | `76` | `20` | `207` | `178` | `240` | `0` | `3` | `0` |
| `715` | v3 | `74` | `21` | `207` | `183` | `240` | `0` | `1` | `0` |
| `312135` | v2 | `122` | `8` | `35` | `84` | `236` | `25` | `64` | `0` |
| `312135` | v3 | `107` | `11` | `49` | `95` | `240` | `17` | `49` | `0` |

Interpretation on these pages:
- v3 keeps hard-cap safety (`>cap = 0`),
- reduces micro-segments (`<20` and `<40`) where v2 still fragmented heavily,
- increases median and average chunk density without flattening unrelated sections.

### Persisted SQL aggregate comparison (all segmented pages)

After running a full rebuild for `heading_structure_v3`, we compared persisted rows directly in `crawldb.page_segment` for both strategies:

| Strategy | Segments | Pages with segments | Min | Median | P95 | Avg | Max | `<20` | `<20 %` | `<40` | `<40 %` | `>240` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `heading_structure_v2` | `46603` | `4011` | `3` | `149` | `236` | `142.71` | `240` | `955` | `2.05%` | `3766` | `8.08%` | `0` |
| `heading_structure_v3` | `43303` | `4011` | `5` | `165` | `236` | `153.44` | `240` | `369` | `0.85%` | `2048` | `4.73%` | `0` |

Global interpretation:
- v3 reduces micro-fragmentation by more than half on `<20` chunks (`2.05% -> 0.85%`),
- v3 substantially lowers `<40` chunks (`8.08% -> 4.73%`),
- median and average chunk density increase while hard-cap safety remains strict (`>240 = 0`).

### `heading_structure_v4`: clean display text + contextual embedding text

Based on v2/v3 observations, we added `heading_structure_v4` to separate display and embedding payloads:
- `segment_text` is kept clean for output/reranking display,
- `embedding_text` carries contextual prefix metadata (`Context: ...`, `Type: ...`) so semantic search can match hierarchical intent words that are not present in raw snippet text (especially for short lists and code blocks),
- hard-cap checks use the **true v4 combined model input** (prefix + `segment_text` at encode time), so the payload the embedder sees stays within the hard token cap.

`heading_structure_v4` reuses the v3 **greedy pack → split/repair rounds → final split** backbone, then adds **step 6 — merge-group consolidation** (always on for v4, no env toggle): after the final hard-cap split, adjacent chunks that share the same `merge_group_parent` (internal sibling bucket under one parent heading) are merged left-to-right while the merged chunk’s real combined v4 token count still respects the hard cap. Tiny-tail repair inside the refine loop also uses this same true v4 budget for cap checks, so repair and split stay aligned with what we persist as `token_estimate`.

#### Pipeline overview (seven steps)

1. **Parse blocks** — Split `cleaned_content` into logical blocks with preserved `[H1]`…`[H6]` structure. *Needed so* all later stages respect section boundaries and never merge unrelated topics across heading transitions.

2. **Build section units** — One unit per heading section with body text and a strict token estimate (for v4, per-section combined prefix+body). *Needed so* downstream packing has atomic, fairly sized siblings to combine.

3. **Parent-aware greedy pack** (`_pack_v3_by_parent_greedy`) — Consecutive units under the **same parent** are concatenated into one `V2PackedChunk` while the running total stays under the **soft** target. *Needed so* subsection-heavy pages produce fewer, denser chunks before any splitting, without exceeding a gentle density goal.

4. **Refine loop: hard split then tiny-tail repair** (repeated `PA2_V3_REFINE_ROUNDS` times) — **Split** enforces the **hard** cap on v4 combined input (splitting oversized packed chunks at part boundaries, then text fallbacks). **Repair** re-attaches **underfilled** fragments to an adjacent sibling in the same merge group when the **merged** true v4 budget is cap-safe. *Needed so* oversized groups become model-safe, and split-induced micro-tails are absorbed where possible without crossing merge groups.

5. **Final hard split** — One more cap pass after the last repair (no repair immediately after). *Needed so* the list leaving the loop cannot violate the hard cap after the last merge operations.

6. **Merge-group consolidation** (v4 only) — Greedy chain-merge of **adjacent** chunks sharing `merge_group_parent` while merged true v4 tokens stay under the hard cap. *Needed so* siblings that stayed separate after soft packing and splitting (e.g. two medium chunks under one parent) can still become one segment when the real embedder input allows it—something soft packing alone does not guarantee.

7. **Emit `page_segment` rows** — Map each final packed chunk to `segment_text`, `embedding_text`, `heading_path`, `merge_group_parent`, and strict `token_estimate`. *Needed so* the database stores exactly what downstream embedding and retrieval consume.

#### Full-corpus v4 statistics (after merge-group consolidation)

A full `heading_structure_v4` segmentation run over the README-backed pages produced the following quality summary (logger output from `segment_cleaned_content.py`):

- **Coverage:** `pages_read=4031`, `pages_with_segments=4009`, `total_segments=39998` (`dry_run=False`).
- **Chunks per page:** min `0`, median `6`, p95 `29` (distribution of segment counts per page).
- **Tokens per segment (strict, v4 combined input):** min `9`, median `188`, p95 `246`.
- **Heading paths:** non-null ratio `100%`.
- **Tail / cap safety:** segments with `token_estimate < 20`: `107` (`0.3%`); `< 40`: `1076` (`2.7%`); over configured hard cap (`>cap(250)` in this run): `0` (`0.0%`).

Compared to the persisted **v3** aggregate in the table above (`43303` segments on `4011` pages, median token length `165`, `<20` at `0.85%`, `<40` at `4.73%`), v4 achieves **fewer total segments** (`39998`, i.e. lower fragmentation) with a **higher median token length** (`188` vs `165`), while keeping **no over-cap rows** in this run. The extra consolidation step directly improves the chunk-size distribution by merging same-parent neighbors that remained split after the v3-style passes.

### Why we rely on headers (tree structure) instead of flat README text

We intentionally treat README content as a **hierarchical tree** (`H1..H6`) rather than a flat stream:

- **Local context preservation:** combine/split operates on neighboring text inside the same section lineage, so chunk content stays topically coherent.
- **Safer merges:** parent/sibling constraints prevent blending unrelated topics (for example setup commands with model-comparison prose).
- **Better retrieval grounding:** `heading_path` gives the retriever/LLM explicit structural provenance (`README > Training > Hyperparameters`), improving ranking and answer traceability.
- **Deterministic chunk identity:** tree boundaries provide stable segment reconstruction across rebuilds and strategy comparisons.

A flat-text approach would simplify implementation but would lose section semantics and increase cross-topic bleed when packing by token budget alone.

### Expected advantages and known trade-offs

**Advantages**
- Better topical purity per chunk (less cross-section bleed).
- Stronger retrieval context due to stored heading path.
- Better behavior on mixed README layouts (prose + lists + config/code blocks).

**Trade-offs**
- More complex parser/chunker logic than fixed windows.
- Heuristics are tuned for README-like markdown and may require adjustment for non-standard pages.
- Tokenizer-based budgeting is more accurate for model limits, but slower than lightweight word/regex estimates.


Additional statistics:
- pages read: `4031` (of all the 5000 HTML pages, some did not contain README files at all, and this is why only 4031 pages 
were 
read in the end)
- pages with segments: `4011` (coverage `99.5%`) (additionally 20 pages with README files had them empty, and as such had 
no 
segments)

---

## Phase 4 — Embeddings and storage implementation

Phase 4 is implemented as a dedicated pipeline (`implementation-extraction/embed_page_segments.py`) that reads rows from `crawldb.page_segment`, encodes embedding-facing text (`embedding_text` for v4 strategy rows), and writes vectors back to `crawldb.page_segment.embedding`.

### Model and dimensionality choice

- Primary model: `sentence-transformers/all-MiniLM-L6-v2` (English README corpus, strong quality/speed trade-off).
- Embedding dimension is fixed to **384**, and both schema and runtime checks enforce this:
  - migration `004_page_segment_embedding.sql` defines `embedding vector(384)`,
  - the script validates `model.get_sentence_embedding_dimension() == 384` before processing.

This prevents the common mismatch error where a `vector(768)` schema is used with a 384-dimensional model.

### Storage and index decisions

We use pgvector inside PostgreSQL:

- `CREATE EXTENSION IF NOT EXISTS vector`,
- `ALTER TABLE crawldb.page_segment ADD COLUMN IF NOT EXISTS embedding vector(384)`,
- `ALTER TABLE crawldb.page_segment ADD COLUMN IF NOT EXISTS embedding_text text`,
- cosine ANN index: `USING hnsw (embedding vector_cosine_ops)`.

Cosine search is aligned with normalized sentence embeddings and with the retrieval setup planned for `demo.py`.

We selected **HNSW** over **IVFFlat** for this project because it better fits our current workload and data scale:

- our collection size is moderate (about 46k segments), where HNSW usually offers stronger recall/latency trade-offs with less parameter tuning;
- this pipeline is mostly bulk-build then read/query, so a heavier index build step is acceptable for better query quality;
- IVFFlat is highly usable too, but it depends more on tuning (`lists`, `probes`) and is more attractive when faster/cheaper rebuilds are the primary goal.

Given our assignment focus (retrieval quality over many rebuild cycles), HNSW is the safer default for final semantic search results.

### Reliability and quality checks

The embedding pipeline includes resilience and sanity diagnostics:

- **Batch processing + retries**: failed batches are retried with bounded backoff; failed rows are reported explicitly.
- **Safe transactions**: each batch uses commit/rollback boundaries to avoid partial corrupt writes.
- **Anomaly logging**:
  - near-zero vector norm count (possible encoding/runtime issues),
  - sparse vector count (very low non-zero-dimension ratio),
  - norm distribution (`min`, `median`, `p95`) for quick run-level health checks.
- **Data hygiene**: empty embedding-source rows are skipped and counted; for v4 rows, missing/empty `embedding_text` is treated as an error.

### Alternatives considered but not implemented in this phase

- Running multiple embedding models in parallel (deferred to keep Phase 4 reproducible and focused).
- Canonical-content-first embedding (`cleaned_content_canonical`) remains available but is not required for the current deduplicated dataset.

---

## Phase 5 — Retrieval demo and similarity metrics

Phase 5 is implemented in `implementation-extraction/demo.py` and follows the required loop:
1. receive query text,
2. compute query embedding (`all-MiniLM-L6-v2` by default),
3. execute pgvector similarity search on `crawldb.page_segment.embedding`,
4. print top-k segments with source metadata (`page_id`, URL, `chunk_index`, strategy).

### Similarity metric choice and rationale

The retriever supports four metrics by mapping CLI `--metric` to pgvector operators:
- cosine -> `<=>`
- l2 -> `<->`
- inner product -> `<#>`
- l1 -> `<+>`

Current migration (`004_page_segment_embedding.sql`) creates a cosine HNSW index (`vector_cosine_ops`), so cosine is the default and expected best latency-quality balance for this project. Other metrics are still available for assignment experimentation, but they may run slower without metric-specific ANN indexes.

### Qualitative evaluation queries (required 3 good + 3 weak)

`demo.py --run-eval` includes the following built-in query sets:

**Expected-good queries**
1. `How do I run DeepLabV3+ training with this repository?`
2. `Which configuration keys control optimizer learning rate?`
3. `How can I use a pretrained segmentation model from the README?`

**Weak/unexpected-result queries**
1. `What is the best segmentation model for medical biology images in 2026?`
2. `How does this project compare to Segment Anything 2 benchmarks?`
3. `Can this codebase guarantee state-of-the-art mIoU on every dataset?`

These weak queries are intentionally broader than repository README scope and are useful for showing both baseline retrieval limits and reranking behavior.

### How to capture report evidence

Run:

```bash
cd pa2/implementation-extraction
.venv/bin/python demo.py --run-eval --top-k 5
.venv/bin/python demo.py --run-eval --top-k 5 --rerank --candidate-k 20
```

For the final PDF section, record:
- one short snippet + source URL for each of the 3 good-query outputs,
- one short snippet showing weak-query mismatch before rerank,
- at least one weak query where reranking improves top ordering,
- at least one weak query where reranking still fails.

---

## Phase 6 — Reranking behavior

The retrieval script supports optional cross-encoder reranking (`--rerank`) using:
- default model: `cross-encoder/ms-marco-MiniLM-L-6-v2`,
- input pairs: `(query, segment_text)` for top-N embedding candidates (`--candidate-k`, default 20),
- output: reranked top-k list shown with `rerank_score`.

If the reranker model cannot load, the script logs a warning and returns baseline embedding-ranking results, ensuring demo execution still succeeds.

### Known limitations (current retriever + reranker)

- README-only corpus limits answer coverage for generic or external-comparison questions.
- Cosine-index alignment is currently strongest; non-cosine experiments may be slower.
- Reranking can reorder relevant items, but cannot recover facts absent from extracted README content.

---

*Last aligned with extraction logic in [`implementation-extraction/extract_readme.py`](implementation-extraction/extract_readme.py). Extend this file when chunking, embeddings, querying, and reranking are finalized.*
