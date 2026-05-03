#!/usr/bin/env python3
"""
PA2 retrieval demo for course markers.

Loads a query, embeds it, searches segment embeddings in PostgreSQL (pgvector),
and optionally reranks hits with a cross-encoder.

Implementation is completed in later phases; this stub exists so the repo
matches the required submission layout early.
"""

from __future__ import annotations

import argparse
import sys


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "PA2 demo: semantic search over crawldb.page_segment (stub until "
            "retrieval logic is wired)."
        )
    )
    parser.add_argument("--query", type=str, help="Natural language query.")
    parser.add_argument(
        "--top-k",
        type=int,
        default=5,
        help="Number of segments to retrieve (default: 5).",
    )
    _ = parser.parse_args(argv)

    sys.stderr.write(
        "demo.py: retrieval not implemented yet — complete Phase 3–5 of the PA2 plan.\n"
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
