# Programming Assignment 2 — Extraction and vector retrieval

This folder follows the course submission layout:

```
pa2/
├── report-extraction.pdf      # Final report (added when complete)
├── README.md                  # This file — setup, DB restore (expanded in later phases)
├── pa2_context.ipynb           # Condensed assignment context for notebooks / collaborators
├── db/migrations/
│   └── 001_page_cleaned_content.sql
├── implementation-extraction/
│   ├── requirements.txt
│   ├── extract_readme.py      # Phase 1: HTML → README plain text → cleaned_content
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

2. **Populate** text from GitHub-rendered README (`article.markdown-body[itemprop="text"]`) into `cleaned_content`:

   ```bash
   cd pa2/implementation-extraction
   python3 -m venv .venv && source .venv/bin/activate
   pip install -r requirements.txt
   python extract_readme.py --batch-size 50
   ```

   Rows without a README block (commits, wiki history, empty repo) receive `cleaned_content = NULL`.

Optional flags: `--dry-run`, `--limit N`, `--verbose`, `--recompute-all`.

**Connection**: defaults `localhost:5432`, database `crawldb`, user `user`, password `SecretPassword`. Override with `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`.

---

Database restore steps for submission dumps will be documented when `extraction-db/` is finalized.

**See also**: [PROGRAMMING-ASSIGNMENT-2.md](../PROGRAMMING-ASSIGNMENT-2.md) for full notes, official rubric, and report checklist.
