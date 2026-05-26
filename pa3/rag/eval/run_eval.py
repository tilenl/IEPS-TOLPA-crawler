#!/usr/bin/env python3
"""
Batch PA3 evaluation: run all queries in rag + llm_only modes.

Usage (from pa3/rag/):
  python eval/run_eval.py --output ../output/eval_run.json
  python eval/run_eval.py --output ../output/eval_run.json --skip-llm
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

# Ensure pa3/rag is on path when invoked as eval/run_eval.py
_RAG_ROOT = Path(__file__).resolve().parents[1]
if str(_RAG_ROOT) not in sys.path:
    sys.path.insert(0, str(_RAG_ROOT))

import yaml  # noqa: E402

from config import RagConfig  # noqa: E402
from pipeline import answer_both_modes, answer_query, save_run_result  # noqa: E402
from pa2_retrieval import retrieve  # noqa: E402


def _load_queries(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        data = yaml.safe_load(handle)
    return list(data.get("queries", []))


def _retrieval_summary(hits: list) -> str:
    if not hits:
        return "(no hits)"
    parts = []
    for index, hit in enumerate(hits[:3], start=1):
        url = hit.source_url or "N/A"
        snippet = hit.segment_text.strip().replace("\n", " ")[:120]
        parts.append(f"[{index}] {url}: {snippet}...")
    return " | ".join(parts)


def _markdown_table(rows: list[dict]) -> str:
    lines = [
        "| ID | Category | Query | Retrieval (top-3) | RAG answer | LLM-only answer | Notes |",
        "|----|----------|-------|-------------------|------------|-----------------|-------|",
    ]
    for row in rows:
        def cell(value: str, max_len: int = 200) -> str:
            text = (value or "").replace("\n", " ").replace("|", "\\|")
            if len(text) > max_len:
                return text[: max_len - 3] + "..."
            return text

        lines.append(
            "| {id} | {cat} | {q} | {ret} | {rag} | {llm} | {notes} |".format(
                id=row.get("id", ""),
                cat=row.get("category", ""),
                q=cell(row.get("query", ""), 80),
                ret=cell(row.get("retrieval_summary", ""), 120),
                rag=cell(row.get("rag_answer", ""), 150),
                llm=cell(row.get("llm_only_answer", ""), 150),
                notes=cell(row.get("expected_behavior", ""), 100),
            )
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="PA3 batch evaluation runner.")
    parser.add_argument(
        "--queries",
        type=str,
        default=str(Path(__file__).parent / "queries.yaml"),
        help="Path to queries YAML.",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=str(_RAG_ROOT.parent / "output" / "eval_run.json"),
        help="JSON output path.",
    )
    parser.add_argument(
        "--markdown",
        type=str,
        default=None,
        help="Optional Markdown table output (default: same name as JSON with .md).",
    )
    parser.add_argument("--skip-llm", action="store_true", help="Only run retrieval, skip LLM.")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--no-rerank", action="store_true")
    parser.add_argument(
        "--database-url",
        type=str,
        default=os.environ.get("DATABASE_URL"),
    )
    args = parser.parse_args(argv)

    from pa2_retrieval import RetrievalConfig

    config = RagConfig(
        retrieval=RetrievalConfig(
            top_k=args.top_k,
            rerank=not args.no_rerank,
        ),
        database_url=args.database_url,
    )

    queries = _load_queries(Path(args.queries))
    report_rows: list[dict] = []
    full_results: list[dict] = []

    for item in queries:
        qid = item["id"]
        query = item["query"]
        category = item["category"]
        print(f"\n>>> [{qid}] {query}")

        hits = retrieve(query, config=config.retrieval, database_url=config.database_url)
        retrieval_summary = _retrieval_summary(hits)

        row = {
            "id": qid,
            "category": category,
            "query": query,
            "expected_behavior": item.get("expected_behavior", ""),
            "retrieval_summary": retrieval_summary,
            "hits": [
                {
                    "segment_id": h.segment_id,
                    "source_url": h.source_url,
                    "distance": h.distance,
                    "rerank_score": h.rerank_score,
                    "segment_text": h.segment_text,
                }
                for h in hits
            ],
        }

        if not args.skip_llm:
            both = answer_both_modes(query, config=config)
            row["rag_answer"] = both["rag"].answer
            row["llm_only_answer"] = both["llm_only"].answer
            row["rag_run"] = both["rag"].to_dict()
            row["llm_only_run"] = both["llm_only"].to_dict()
            print(f"  RAG: {row['rag_answer'][:120]}...")
            print(f"  LLM-only: {row['llm_only_answer'][:120]}...")
        else:
            row["rag_answer"] = ""
            row["llm_only_answer"] = ""

        report_rows.append(row)
        full_results.append(row)

    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "config": {
            "top_k": config.retrieval.top_k,
            "rerank": config.retrieval.rerank,
            "llm_model": config.llm_model,
        },
        "results": full_results,
    }

    out_json = Path(args.output)
    out_json.parent.mkdir(parents=True, exist_ok=True)
    save_run_result(str(out_json), payload)
    print(f"\nWrote JSON: {out_json}")

    md_path = Path(args.markdown) if args.markdown else out_json.with_suffix(".md")
    md_content = "# PA3 Evaluation Results\n\n" + _markdown_table(report_rows)
    md_path.write_text(md_content, encoding="utf-8")
    print(f"Wrote Markdown table: {md_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
