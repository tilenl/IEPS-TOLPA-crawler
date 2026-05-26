# PA3 — Retrieval-Augmented Generation (RAG)

Programming Assignment 3 for IEPS: answer questions over the PA2 GitHub README corpus using **With Context (RAG)** and **Without Context (LLM-only)** modes, with inspectable retrieval evidence.

## Prerequisites

1. **PostgreSQL** with PA2 data (same as PA2):
   - Container: `postgresql-wier` (see [`DATABASE_SETUP_CLEANUP.md`](../DATABASE_SETUP_CLEANUP.md))
   - Restore dump from link in [`pa2/extraction-db/db.txt`](../pa2/extraction-db/db.txt)
   - **Important:** PA2/PA3 connect to database **`crawldb`** by default. pgAdmin restore instructions often use **`crawldb_restore`**, which leaves `crawldb` empty.

   Check both databases:
   ```bash
   bash pa3/scripts/db_status.sh
   ```

   If data is only in `crawldb_restore` (typical after pgAdmin restore), pick one fix:

   **Quick (per terminal session):**
   ```bash
   export PGDATABASE=crawldb_restore
   ```

   **Permanent (rename DB — recommended):**
   ```bash
   bash pa3/scripts/fix_db_name.sh
   ```

   You want a non-zero embedding count on the DB you use:
   ```bash
   docker exec postgresql-wier psql -U user -d crawldb -t -c \
     "SELECT COUNT(*) FROM crawldb.page_segment WHERE embedding IS NOT NULL;"
   ```

2. **Ollama** (local LLM) — **must be installed** (`command not found: ollama` means it is missing):

   **macOS install (choose one):**
   ```bash
   brew install --cask ollama
   # or download: https://ollama.com/download/mac
   ```

   **Start the server** (menu-bar app is easiest):
   ```bash
   open -a Ollama
   ```

   **Pull the model** (once, needs network):
   ```bash
   ollama pull llama3.2:1b
   ```

   **Verify** (works without PA3 venv):
   ```bash
   ollama --version
   curl http://localhost:11434/api/tags
   python3 pa3/rag/check_ollama.py
   ```

   After `pip install -r requirements.txt` in `pa3/rag/.venv`:
   ```bash
   python cli.py --check-ollama
   ```

   Expected: `API reachable: True` and `Model 'llama3.2:1b' available: True`.

3. **Python 3.10+**

## Install

```bash
cd pa3/rag
python3 -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

You may reuse the PA2 venv instead: activate `pa2/implementation-extraction/.venv` and `pip install dspy PyYAML`.

First run downloads SentenceTransformer and cross-encoder weights (requires network).

## Quick start

Single query (both modes, prints retrieved chunks then answers):

```bash
cd pa3/rag
source .venv/bin/activate
python cli.py --query "How do I run Segment Anything (SAM) inference?" --mode both
```

RAG only:

```bash
python cli.py --query "What are food image classification repositories?" --mode rag
```

LLM-only:

```bash
python cli.py --query "What are food image classification repositories?" --mode llm_only
```

Save full trace (hits, prompts, answers) to JSON:

```bash
python cli.py --query "How do I evaluate mIoU?" --mode both \
  --output ../output/run.json
```

## Batch evaluation (report table)

Runs all 9 queries (6 good + 3 poor) from [`rag/eval/queries.yaml`](rag/eval/queries.yaml):

```bash
cd pa3/rag
python eval/run_eval.py --output ../output/eval_run.json
```

Outputs:

- `pa3/output/eval_run.json` — full results
- `pa3/output/eval_run.md` — Markdown table for the report

Retrieval-only (no Ollama, faster smoke test):

```bash
python eval/run_eval.py --skip-llm --output ../output/eval_retrieval_only.json
```

## Architecture

| Module | Role |
|--------|------|
| [`rag/pa2_retrieval.py`](rag/pa2_retrieval.py) | Imports PA2 `retrieve()` |
| [`rag/prompts.py`](rag/prompts.py) | Context formatting and prompt templates |
| [`rag/llm.py`](rag/llm.py) | Ollama + dspy (`SimpleRAG`, `SimpleQA`) |
| [`rag/pipeline.py`](rag/pipeline.py) | End-to-end `answer_query()` |
| [`rag/cli.py`](rag/cli.py) | Interactive CLI |
| [`rag/eval/run_eval.py`](rag/eval/run_eval.py) | Batch evaluation |

**Defaults:** MiniLM embeddings, `heading_structure_v4`, top-5 after reranking 20 candidates, `llama3.2:1b`, max context 7000 chars.

## Explainability

For every RAG run the CLI prints:

- Ranked segments (`segment_id`, URL, distance or rerank_score, full text)
- Generated answer

JSON output includes the same fields for offline analysis.

## Report

Draft: [`report.md`](report.md). Export to PDF for submission:

```bash
# If pandoc is installed:
pandoc report.md -o report.pdf --pdf-engine=pdflatex
```

Or open `report.md` in Word/LibreOffice and export as `report.pdf`. After running eval, copy the table from `output/eval_run.md` into the report.

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `demo.py` / retrieval returns no hits | Restore PA2 DB dump; check embedding count SQL above |
| `command not found: ollama` | Install: `brew install --cask ollama`, then `open -a Ollama` |
| Ollama connection error | Run `open -a Ollama` or `ollama serve`; verify `curl http://localhost:11434/api/tags` |
| Model missing | `ollama pull llama3.2:1b` then `python cli.py --check-ollama` |
| Slow first query | Model download + cross-encoder load; subsequent queries faster |
| Slow generation | Reduce `--max-context-chars` or `--top-k` |
| Hugging Face download errors | Set `HF_HOME` to cached models or fix proxy/network |

## Related documentation

- PA2 retrieval: [`pa2/README.md`](../pa2/README.md)
- Database setup: [`DATABASE_SETUP_CLEANUP.md`](../DATABASE_SETUP_CLEANUP.md)
- Course notebook: [`assignment-2-notebooks/vaje5-RAG_simple_demo.ipynb`](../assignment-2-notebooks/vaje5-RAG_simple_demo.ipynb)
