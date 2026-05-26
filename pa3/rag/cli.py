#!/usr/bin/env python3
"""
PA3 RAG CLI — single-query runs with explainability output.

Usage (from pa3/rag/ with venv activated):
  python cli.py --query "How do I run SAM inference?" --mode both
  python cli.py --query "..." --mode rag --output ../output/run.json
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from ollama_health import check_ollama, format_install_help


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="PA3 RAG: answer questions with or without retrieved context."
    )
    parser.add_argument("--query", type=str, default=None, help="Natural-language question.")
    parser.add_argument(
        "--mode",
        choices=("rag", "llm_only", "both"),
        default="both",
        help="RAG (with context), LLM-only, or both (default: both).",
    )
    parser.add_argument("--top-k", type=int, default=5, help="Retrieval top-k (default: 5).")
    parser.add_argument(
        "--candidate-k",
        type=int,
        default=20,
        help="Candidate pool before reranking (default: 20).",
    )
    parser.add_argument(
        "--no-rerank",
        action="store_true",
        help="Disable cross-encoder reranking.",
    )
    parser.add_argument(
        "--max-context-chars",
        type=int,
        default=7000,
        help="Max characters in retrieved context (default: 7000).",
    )
    parser.add_argument(
        "--llm-model",
        type=str,
        default="ollama_chat/llama3.2:1b",
        help="Ollama model id for dspy (default: llama3.2:1b).",
    )
    parser.add_argument(
        "--database-url",
        type=str,
        default=os.environ.get("DATABASE_URL"),
        help="PostgreSQL URI (default: PG* env vars).",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=None,
        help="Optional JSON file to save full run (hits, prompts, answers).",
    )
    parser.add_argument(
        "--plain-prompt",
        action="store_true",
        help="Use plain string prompts instead of dspy Signature modules.",
    )
    parser.add_argument("--verbose", action="store_true", help="Enable debug logging.")
    parser.add_argument(
        "--check-ollama",
        action="store_true",
        help="Only verify Ollama install/API/model, then exit.",
    )
    parser.add_argument(
        "--skip-ollama-check",
        action="store_true",
        help="Skip Ollama preflight (not recommended).",
    )
    return parser.parse_args(argv)


def _build_config(args: argparse.Namespace) -> "RagConfig":
    from config import RagConfig
    from pa2_retrieval import RetrievalConfig

    retrieval = RetrievalConfig(
        top_k=args.top_k,
        candidate_k=args.candidate_k,
        rerank=not args.no_rerank,
    )
    return RagConfig(
        retrieval=retrieval,
        llm_model=args.llm_model,
        max_context_chars=args.max_context_chars,
        database_url=args.database_url,
    )


def _print_hits(hits: list, *, query: str) -> None:
    print()
    print("=" * 100)
    print(f"RETRIEVED EVIDENCE for: {query}")
    print("=" * 100)
    if not hits:
        print("  (no segments retrieved)")
        return
    for rank, hit in enumerate(hits, start=1):
        score = (
            f"rerank_score={hit.rerank_score:.4f}"
            if hit.rerank_score is not None
            else f"distance={hit.distance:.4f}"
        )
        print()
        print(f"--- Chunk {rank} | segment_id={hit.segment_id} | {score} ---")
        print(f"URL: {hit.source_url or 'N/A'}")
        print(hit.segment_text.strip())
        print("-" * 80)


def _print_result(label: str, result) -> None:
    print()
    print("#" * 100)
    print(f"{label} | mode={result.mode}")
    print("#" * 100)
    if result.mode == "rag":
        _print_hits(result.hits, query=result.query)
    print()
    print("--- ANSWER ---")
    print(result.answer)
    print()


def main(argv: list[str] | None = None) -> int:
    import logging

    from config import RagConfig

    args = _parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )

    config = _build_config(args)
    use_signature = not args.plain_prompt
    model_tag = config.llm_model.split("/", 1)[-1]

    if args.check_ollama:
        status = check_ollama(api_base=config.ollama_api_base, model=model_tag)
        print(f"CLI on PATH: {status.cli_on_path}")
        print(f"API reachable: {status.api_reachable}")
        print(f"Model {model_tag!r} available: {status.model_available}")
        if status.detail:
            print(f"Detail: {status.detail}")
        if status.api_reachable and status.model_available:
            return 0
        print()
        print(format_install_help(status))
        return 1

    if not args.skip_ollama_check:
        status = check_ollama(api_base=config.ollama_api_base, model=model_tag)
        if not (status.api_reachable and status.model_available):
            sys.stderr.write(format_install_help(status) + "\n")
            sys.stderr.write("\nTip: run  python cli.py --check-ollama  after fixing setup.\n")
            return 1

    if not args.query:
        sys.stderr.write("Provide --query (or use --check-ollama only).\n")
        return 2

    from pipeline import answer_both_modes, answer_query, save_run_result

    try:
        if args.mode == "both":
            results = answer_both_modes(
                args.query, config=config, use_signature=use_signature
            )
            _print_result("WITH CONTEXT (RAG)", results["rag"])
            _print_result("WITHOUT CONTEXT (LLM-only)", results["llm_only"])
            payload = {key: val.to_dict() for key, val in results.items()}
        else:
            result = answer_query(
                args.query,
                mode=args.mode,  # type: ignore[arg-type]
                config=config,
                use_signature=use_signature,
            )
            _print_result(args.mode.upper(), result)
            payload = {args.mode: result.to_dict()}

        if args.output:
            out_path = Path(args.output)
            out_path.parent.mkdir(parents=True, exist_ok=True)
            save_run_result(str(out_path), payload)
            print(f"Saved run to {out_path}")

        return 0
    except Exception as exc:
        logging.error("RAG run failed: %s", exc)
        if args.verbose:
            logging.exception("Details:")
        sys.stderr.write(f"cli.py error: {exc}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
