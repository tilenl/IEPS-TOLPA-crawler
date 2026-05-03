# Programming Assignment 2 — Extraction and vector retrieval

This folder follows the course submission layout:

```
pa2/
├── report-extraction.pdf      # Final report (added when complete)
├── README.md                  # This file — setup, DB restore (expanded in later phases)
├── pa2_context.ipynb           # Condensed assignment context for notebooks / collaborators
├── implementation-extraction/
│   ├── requirements.txt
│   └── demo.py                # Query demo for markers (CLI retriever + optional reranking)
└── extraction-db/              # PostgreSQL dumps and restore artefacts
```

## Quick start (Python)

From `implementation-extraction/`:

```bash
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

Database connection variables and restore steps will be documented here once migrations and dumps are finalized.

---

**See also**: [PROGRAMMING-ASSIGNMENT-2.md](../PROGRAMMING-ASSIGNMENT-2.md) for full notes, official rubric, and report checklist.
