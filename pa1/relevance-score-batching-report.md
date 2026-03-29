# Relevance score batching — DB and HTML verification

**Database:** `postgresql-wier` / `crawldb`  
**Analyzed:** 2026-03-29

## Executive summary

**Is batching explained by “too broad a window” around each link?**  
**Partly, but it is not the main story in this dataset.**

In the current crawl snapshot, **all 294 `crawldb.link` rows originate from a single parent page** (`page.id = 1`, `https://github.com/topics/image-segmentation`). Every discovered URL therefore receives the **same page-level context (B):** `document.title` and `meta[name=description]`. The title is **`image-segmentation · GitHub Topics · GitHub`**, which contains the primary keyword substring **`segmentation`**. That alone contributes **+0.18** (one primary hit per [`KeywordRelevanceScorer`](../crawler/src/main/java/si/uni_lj/fri/wier/downloader/extract/KeywordRelevanceScorer.java)) to **every** outlink from that page, before any anchor-specific text is considered.

So **large score groups (e.g. 60 rows at 0.18)** are expected: many links (global nav, footer, feature pages) add **no further** keyword hits beyond that shared title. **Broad DOM windows** still matter for *which* extra keywords appear (e.g. repo cards vs navbar), but **shared metadata (B) + discrete per-keyword weights** are sufficient to explain most of the batching observed here.

---

## 1. Quantitative results

### 1.1 Score distribution (`crawldb.page`)

| relevance_score (as stored) | count |
|----------------------------|------:|
| 0.18 | 60 |
| ~0.90 | 49 |
| ~0.63 | 45 |
| ~0.45 | 30 |
| 0.27 | 24 |
| 0.72 | 24 |
| 1.44 | 19 |
| 0.54 | 18 |
| 0.36 | 9 |
| 1000 | 9 |
| ~0.81 | 7 |
| 0.63 | 6 |

**Notes:**

- **`1000`:** matches configured seed scores ([`application.properties`](../crawler/src/main/resources/application.properties) / TS-13); nine seed rows.
- **Non-integer decimals** (e.g. `0.8999999999999999`) are **floating-point representation** of sums like `5 × 0.18 = 0.9`.
- **300** total pages (aligns with `crawler.budget.maxTotalPages=300`).

### 1.2 Link graph

Query: edges grouped by parent.

| from_page | parent URL | edges |
|-----------|------------|------:|
| 1 | https://github.com/topics/image-segmentation | 294 |

**294** pages have an incoming link; **294** edges all share **`from_page = 1`**. No other parent appears in `crawldb.link` for this database.

### 1.3 “Same parent → same score”

Grouping `(from_page, relevance_score)` for children:

All non-trivial batches are **`from_page = 1`** (e.g. 60 children at 0.18, 49 at ~0.9, …). This is consistent with **one hub page** exporting the frontier, not with many independent sources.

---

## 2. Parent page HTML (page `id = 1`)

### 2.1 Shared context (B) — applies to every link

- **Title:** `image-segmentation · GitHub Topics · GitHub`  
  → substring **`segmentation`** matches a **primary** keyword ([`keywords.json`](keywords.json)).
- **Meta description:** generic GitHub marketing text (“discover, fork, contribute…”). It does **not** need to match the keyword list for the **0.18 floor** to exist; the **title** already forces one primary hit for all body links.

### 2.2 Worked example A — “floor” score 0.18 (global nav)

Child examples with **0.18:** `https://github.com/features/copilot`, `https://github.com/login`, etc.

**HTML context (abbreviated):** the `features/copilot` href sits inside a large **navbar** dropdown (`NavDropdown-module__…`, “AI CODE CREATION”, SVG icons). In [`HtmlParser.anchorCenteredNeighborhood`](../crawler/src/main/java/si/uni_lj/fri/wier/downloader/extract/HtmlParser.java), the **4-ancestor** scope and up to **3000** characters of combined text mean this link’s **local** slice is dominated by **global chrome**, not repo-specific prose. The **canonical URL** and **anchor** for these items typically **do not** add extra keywords from `keywords.json`.

**Scoring interpretation:** `haystack = url + " " + anchor + " " + context` still includes **`segmentation`** from **B** (title + meta merged into every link’s context). **No** additional primary/secondary hits → **0.18**.

This illustrates how a **wide structural window** (nav shared across many `<a>` nodes) yields **similar A**, but the **guaranteed shared B** is what fixes the **minimum** score at **0.18** for this page.

### 2.3 Worked example B — high score 1.44 (topic repo card)

Children such as `https://github.com/qubvel-org/segmentation_models.pytorch` (and related `/issues`, `/pulls`, …) have **relevance_score = 1.44**.

**HTML context (abbreviated):** the repository heading link appears as:

```text
href="/qubvel-org/segmentation_models.pytorch" ... class="Link text-bold wb-break-word">
segmentation_models.pytorch</a>
```

The **URL** and **anchor** contain **`segmentation`** and **`pytorch`** (both primaries). **Topic tags** on the same GitHub topic card (e.g. `python`, `machine-learning` in `a.topic-tag.topic-tag-link`) feed **A2** in `linkScoringContext`, enlarging the haystack for in-card links.

**Scoring interpretation:** multiple primary substring hits across **URL + anchor + A2 + B + neighborhood** produce a **higher discrete sum**; **`1.44 = 8 × 0.18`** is consistent with **eight** primary keyword matches under the current scorer (exact token overlap depends on the full merged context string).

---

## 3. Verdict on the hypothesis

| Factor | Role in this dataset |
|--------|----------------------|
| **Shared B (title/meta) on parent** | **Dominant.** One keyword from the title hits **every** link → strong batching at **0.18** and raises baselines for everyone. |
| **Discrete weights** | **Strong.** Scores are sums of **0.18 / 0.09** steps, so many URLs **must** share the same numeric score even with different URLs. |
| **Broad anchor window (4 hops, 3000 chars, ±120, idx→0 fallback)** | **Moderate.** Explains why **nav/footer** links get **similar A** and rarely gain extra keywords; combines with B to keep **large** groups at the **same** score. |
| **Child page `html_content`** | **Not used** for `relevance_score`; only parent HTML at discovery time matters. |

**Conclusion:** Your intuition that **too much shared text per link** drives batching is **directionally correct**, but the implementation makes that explicit: **the entire page’s title and description are appended to every link’s context**, which is **broader than any per-anchor window**. In this crawl, **a single high-fan-out topic page** amplifies the effect. Narrowing **only** the anchor neighborhood without addressing **B** would leave much of the batching (especially the **0.18** cluster) unchanged.

---

## 4. Queries used (reproducible)

```sql
SELECT relevance_score, COUNT(*) AS n
FROM crawldb.page
GROUP BY relevance_score
ORDER BY n DESC, relevance_score;

SELECT l.from_page, pf.url, COUNT(*) AS edges
FROM crawldb.link l
JOIN crawldb.page pf ON pf.id = l.from_page
GROUP BY l.from_page, pf.url
ORDER BY edges DESC;

SELECT l.from_page, p.relevance_score, COUNT(*) AS cnt
FROM crawldb.link l
JOIN crawldb.page p ON p.id = l.to_page
GROUP BY l.from_page, p.relevance_score
HAVING COUNT(*) > 3
ORDER BY cnt DESC;
```
