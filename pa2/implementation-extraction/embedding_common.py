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
from typing import Any, Sequence

import numpy as np

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
EXPECTED_DIMENSION = 384


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

    model = SentenceTransformer(model_name)
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
