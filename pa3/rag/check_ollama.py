#!/usr/bin/env python3
"""Standalone Ollama check (no PA2/dspy dependencies)."""

from __future__ import annotations

import argparse
import sys

from ollama_health import check_ollama, format_install_help


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify Ollama install and llama3.2:1b.")
    parser.add_argument("--api-base", default="http://localhost:11434")
    parser.add_argument("--model", default="llama3.2:1b")
    args = parser.parse_args()

    status = check_ollama(api_base=args.api_base, model=args.model)
    print(f"CLI on PATH: {status.cli_on_path}")
    print(f"API reachable: {status.api_reachable}")
    print(f"Model {args.model!r} available: {status.model_available}")
    if status.detail:
        print(f"Detail: {status.detail}")
    if status.api_reachable and status.model_available:
        print("\nOllama is ready for PA3.")
        return 0
    print()
    print(format_install_help(status))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
