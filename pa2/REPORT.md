# PA2 report — checklist and extraction notes

This file is **source material** for **`report-extraction.pdf`**. Turn the checklist into full prose in the PDF; keep this Markdown as your working outline.

---

## What to cover in **`report-extraction.pdf`** (course §6.1)

Use the official headings that match your write-up flow; include every bullet.

1. **Website filtering / extraction** — What you threw away globally (menus, chrome, scripts, assets) versus what you kept. For this project: crawler already limits to GitHub-ish pages; extraction further isolates **only the rendered README** subtree (see below).
2. **Chunking strategy** — Criteria for thematic sections (paragraphs vs fixed windows vs headings, merge/split rules, token limits tied to the embedding model). Implemented strategy plus **advantages and disadvantages**.
3. **Embeddings** — Which model(s) you chose, English vs multilingual rationale, experiments with alternatives ([sentence-transformers](https://www.sbert.net/) / Hugging Face), and models you tried but discarded.
4. **Similarity metric** — Cosine vs L2 vs inner product in pgvector, index type (IVFFlat or HSNW), and why it matches how you encode queries and segments.
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

*Last aligned with extraction logic in [`implementation-extraction/extract_readme.py`](implementation-extraction/extract_readme.py). Extend this file when chunking, embeddings, querying, and reranking are finalized.*
