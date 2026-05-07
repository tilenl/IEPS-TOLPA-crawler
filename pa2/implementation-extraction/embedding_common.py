#!/usr/bin/env python3
"""
Shared embedding and PostgreSQL connection helpers for PA2 scripts.

Purpose:
- Keep model configuration and encode logic in one place.
- Keep database connection resolution consistent across embedding and retrieval.

System role:
- Imported by `embed_page_segments.py` and `demo.py`.
- Ensures both scripts use identical defaults and dimension checks.

Created: 2026-05-06
Major revisions:
- 2026-05-06: Initial shared helper extraction for Phase 5 retrieval work.
"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any, Sequence

import numpy as np

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
EXPECTED_DIMENSION = 384


def combined_v4_embed_input(embedding_text: str, segment_text: str) -> str:
    """
    Build the exact string encoded for V4 segments: additive prefix plus body.

    `embedding_text` stores only contextual lines (Context, Type, optional
    Merged_sections); `segment_text` is the display body. Both are joined with
    a blank line when the prefix is non-empty.
    """
    prefix = (embedding_text or "").strip()
    body = (segment_text or "").strip()
    if not prefix:
        return body
    if not body:
        return prefix
    return f"{prefix}\n\n{body}"


def _resolve_local_sentence_model_path() -> str | None:
    """Return a local sentence-transformers snapshot path when available."""
    # NOTE: Support explicit override first so runs can pin a specific local model path.
    explicit = os.environ.get("PA2_LOCAL_SENTENCE_MODEL_PATH", "").strip()
    if explicit:
        explicit_path = Path(explicit).expanduser()
        if explicit_path.exists() and explicit_path.is_dir():
            return str(explicit_path)

    # NOTE: Prefer the repo-local HF cache populated by project setup scripts.
    repo_cache_root = (
        Path(__file__).resolve().parent / ".hf_cache" / "hub" / "models--sentence-transformers--all-MiniLM-L6-v2" / "snapshots"
    )
    if not repo_cache_root.exists() or not repo_cache_root.is_dir():
        return None

    candidates = sorted([path for path in repo_cache_root.iterdir() if path.is_dir()])
    if not candidates:
        return None
    return str(candidates[-1])


def _resolve_model_name_or_path(model_name: str) -> str:
    """Resolve model identifier to a local snapshot when available."""
    local_path = _resolve_local_sentence_model_path()
    if local_path:
        return local_path
    return model_name


def resolve_conn_kwargs() -> dict[str, Any]:
    """Resolve PostgreSQL connection kwargs from environment with PA defaults."""
    return {
        "host": os.environ.get("PGHOST", "localhost"),
        "port": int(os.environ.get("PGPORT", "5432")),
        "dbname": os.environ.get("PGDATABASE", "crawldb"),
        "user": os.environ.get("PGUSER", "user"),
        "password": os.environ.get("PGPASSWORD", "SecretPassword"),
    }


@lru_cache(maxsize=8)
def load_sentence_model(model_name: str = MODEL_NAME) -> Any:
    """
    Load and cache a sentence-transformers model.

    Raises:
    - RuntimeError: if the model output dimension does not match PA2 schema.
    """
    from sentence_transformers import SentenceTransformer

    resolved_model = _resolve_model_name_or_path(model_name)
    model = SentenceTransformer(resolved_model)
    model_dimension = int(model.get_sentence_embedding_dimension())
    if model_dimension != EXPECTED_DIMENSION:
        raise RuntimeError(
            f"Model '{model_name}' dimension mismatch: expected {EXPECTED_DIMENSION}, got {model_dimension}."
        )
    return model


def encode_texts(
    texts: Sequence[str],
    *,
    normalize_embeddings: bool,
    model_name: str = MODEL_NAME,
) -> np.ndarray:
    """Encode texts and enforce deterministic `N x 384` float32 output."""
    model = load_sentence_model(model_name)
    embeddings = model.encode(
        list(texts),
        convert_to_numpy=True,
        normalize_embeddings=normalize_embeddings,
        show_progress_bar=False,
    )
    if embeddings.ndim != 2:
        raise RuntimeError(f"Unexpected embedding tensor rank: {embeddings.ndim}.")
    if embeddings.shape[1] != EXPECTED_DIMENSION:
        raise RuntimeError(
            f"Encoded dimension mismatch: expected {EXPECTED_DIMENSION}, got {embeddings.shape[1]}."
        )
    return embeddings.astype(np.float32, copy=False)
