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
│   └── 002_cleaned_content_canonicalization.sql
├── implementation-extraction/
│   ├── requirements.txt
│   ├── extract_readme.py      # Phase 1: HTML → README plain text → cleaned_content
│   ├── build_canonical_content_map.py  # Canonical dedup map for segmentation
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

**Connection**: defaults `localhost:5432`, database `crawldb`, user `user`, password `SecretPassword`. Override with `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`.

---

Database restore steps for submission dumps will be documented when `extraction-db/` is finalized.

**See also**: [PROGRAMMING-ASSIGNMENT-2.md](../PROGRAMMING-ASSIGNMENT-2.md) for full notes and official rubric; [REPORT.md](REPORT.md) for the PDF checklist and GitHub README extraction summary.
