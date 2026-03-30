# Implementation specifics — preferential crawler (evaluation)

This document summarizes **how the work on branch `feature/implementation` aligns with the PA1 assignment** (`assignment/assignment.md`), how **design decisions in `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION`** shaped the crawler, and how **`pa1/report.md`** reflects operational choices. It stays **broad**: goals, strategy, and major trade-offs—not line-by-line implementation detail.

---

## 1. Assignment intent and how we addressed it

The assignment asks for a **standalone preferential crawler** with the classic five-part decomposition (fetch/render, extract links and images, duplicate handling, frontier, database), **parallel workers** whose count is a **startup parameter**, **robots.txt** compliance (including crawl-delay and sitemap awareness where applicable), **politeness** (at least **one request per host/IP every five seconds** unless robots demands more delay), **canonical URLs only** in storage, **duplicate pages** by content (with URL-level “already seen” handled without re-fetch), **link extraction** from `href` and relevant `onclick` patterns, **images** from `img[src]` only, **HTML** (and PDF where relevant) as downloadable page bodies, other binary types recorded in `page_data` without storing bytes, **fixed User-Agent** `fri-wier-IEPS-TOLPA`, and **no full crawler frameworks** listed as forbidden.

Our implementation targets **`github.com`** around **image segmentation** (topics, representative repos, bounded exploration). That choice matches the assignment’s “subset of GitHub” pattern: we aim for **metadata and HTML** (README-style views, topics), not cloning repositories or exhaustive tree walks, consistent with ethics and scope notes in the architecture and report.

**Bonus (LSH + Jaccard near-duplicate detection):** the delivered path follows the **required** content deduplication story (**hash-based exact match** on HTML). The optional LSH approach from the assignment is **not** claimed here; the report and specs describe **SHA-256**-style content identity and database-level duplicate handling.

---

## 2. Architecture decisions (spec + code evolution)

The **`ARCHITECTURE-AND-TECHNICAL-SPECIFICATION`** directory is the backbone: it fixes **interfaces**, **two-stage dataflow** (ingestion vs fetch), **PostgreSQL as the authoritative frontier**, **politeness and robots** contracts, **canonicalization**, **deduplication semantics**, **budgets**, and **termination**. The high-level picture in `01-crawler-architecture-overview.md` and `03-system-sequence-and-dataflow.md` is what we implemented toward: a **producer–consumer** design where discovery feeds a **persistent priority frontier** and workers drain it under **shared policy gates**.

Commit history on **`feature/implementation`** reads as a deliberate progression from **planning and schema** → **modular TS documents** → **storage and frontier claims** → **URL canonicalization** → **fetcher, politeness, robots** → **worker pipeline and parsing** → **content deduplication and observability** → **GitHub-specific scope and scoring refinements** → **scheduling that respects single-host politeness** → **visualization of the link graph**. That arc matches “design first, then harden concurrency and policy, then tune domain behaviour.”

Notable **cross-cutting decisions** reflected in both specs and commits:

- **Single process per database** for politeness and robots single-flight: running two JVMs against one DB would violate rate and robots assumptions.
- **Atomic frontier claims** and **lease recovery** so multiple workers do not process the same URL; stale work returns to the frontier safely.
- **Robots loaded on a shared path** for ingestion and fetch; **robots fetches** participate in the same **per-host rate** story as normal pages.
- **Persistence boundary** uses **canonical URLs only**; discovery is normalized before scoring and enqueue.
- **Bounded growth**: caps on total pages and frontier size, with **score-based eviction** of the weakest frontier candidates when a strictly better URL arrives—so preferential crawling stays meaningful under a finite budget.

---

## 3. Preferential crawling strategy (what “preferential” means here)

**Preferential** is implemented as **priority in the frontier**, not as “random parallel BFS.”

1. **When a link is discovered (Stage A)**  
   After canonicalization and robots-aware admission checks, the system assigns a **numeric relevance score** from **configurable keyword weights** and **context** (URL, anchor text, surrounding text from parsing). **Seeds** get a **fixed high floor** so operator-chosen entry points stay ahead of generic discovered links, without slow per-seed HTTP work at bootstrap beyond what policy already requires.

2. **Where the priority lives**  
   The database frontier orders work by **score descending**, with **deterministic tie-breaks** (time, id) so behaviour is stable under concurrency.

3. **How execution respects priority under politeness**  
   Because **`github.com`** concentrates most URLs on one crawl host, a naive “many workers each grab the global top URL” pattern would **stall on the rate limiter** and churn leases. The **domain-scoped frontier pump** coordinates **which domain** may run a pipeline next, **claims the best eligible URL for that domain**, acquires a **politeness token once per fetch**, and hands work to the pipeline. Other domains can still progress in parallel within the global **worker cap** (`--n-crawlers` / configured parallelism).

4. **Tuning lessons (from `pa1/report.md`)**  
   A **large, generic keyword list** skewed the crawl toward **broad ML** and even **marketing pages** that mention ML terms. The team **narrowed keywords** toward **segmentation** vocabulary and adjusted **occurrence caps** and optional **blocking of `/topics`** in later runs so **repository-shaped** pages dominate—still preferential, but aligned with the **image segmentation** domain story.

Together, this is the **strategy narrative** the assignment asks for: **relevance at enqueue time**, **priority dequeue**, **ethical and robots-aware fetch ordering**, and **explicit operator controls** (seeds, keywords, budgets, optional path blocks).

---

## 4. Other assignment-aligned choices (brief)

- **Extraction:** `href` and `onclick`-style navigation hints, plus **`img[src]`** metadata into the database (**content type treated as binary metadata** for images, per assignment-style storage rules).
- **Duplicates:** **URL** uniqueness prevents duplicate rows; **content hash** marks **duplicate HTML** and records **graph linkage** to the canonical page. Already-seen URLs discovered again extend the **link** graph without a second fetch.
- **Rendering:** **HTTP client** where server-rendered HTML suffices; **headless browser** remains available for **JS-heavy** or shell pages, matching the assignment’s emphasis on DOM completeness without using a turnkey crawler library.
- **Observability and operations:** metrics, heartbeats, run summaries, and tooling for **status visibility** evolved alongside core crawling—supporting reproducible runs and debugging without changing the assignment’s core data model.

---

## 5. Traceability

| Source | Role |
|--------|------|
| `assignment/assignment.md` | Normative coursework requirements |
| `ARCHITECTURE-AND-TECHNICAL-SPECIFICATION/` | Engineering contracts, dataflow, and compliance mapping |
| `pa1/report.md` | Crawl scope, scheduling narrative, preferential tuning, deduplication, and known pitfalls |
| `git log feature/implementation` | Chronology of spec hardening, feature delivery, GitHub-scope fixes, scoring and pump work, visualization |

This file is an **implementation-level evaluation** for readers who want the **decision story** in one place; detailed evidence, SQL, and statistics belong in **`pa1/report.md`** (and the PDF report derived from it) as required by the course.
