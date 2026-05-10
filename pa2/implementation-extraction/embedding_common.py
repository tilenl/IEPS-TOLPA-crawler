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
- 2026-05-10: Embedding backend registry (MiniLM + LaBSE) with per-model dimensions.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal, Sequence

import numpy as np

EmbeddingBackendKey = Literal["minilm", "labse"]

MINILM_MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
LABSE_MODEL_ID = "sentence-transformers/LaBSE"

# Backward-compatible defaults (MiniLM).
MODEL_NAME = MINILM_MODEL_ID
EXPECTED_DIMENSION = 384


@dataclass(frozen=True)
class EmbeddingBackendSpec:
    """One first-stage bi-encoder backend: HF model id, output dim, pgvector column."""

    key: EmbeddingBackendKey
    model_id: str
    dimension: int
    pg_column: str


_EMBEDDING_BACKENDS: dict[EmbeddingBackendKey, EmbeddingBackendSpec] = {
    "minilm": EmbeddingBackendSpec(
        key="minilm",
        model_id=MINILM_MODEL_ID,
        dimension=384,
        pg_column="embedding",
    ),
    "labse": EmbeddingBackendSpec(
        key="labse",
        model_id=LABSE_MODEL_ID,
        dimension=768,
        pg_column="embedding_labse",
    ),
}


def parse_embedding_backend(value: str) -> EmbeddingBackendSpec:
    """Return the spec for `minilm` or `labse`; raise ValueError on unknown keys."""
    key = value.strip().lower()
    if key == "minilm":
        return _EMBEDDING_BACKENDS["minilm"]
    if key == "labse":
        return _EMBEDDING_BACKENDS["labse"]
    allowed = ", ".join(sorted(_EMBEDDING_BACKENDS))
    raise ValueError(f"Unknown embedding backend {value!r}; expected one of: {allowed}.")


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


def _expected_dimension_for_model_id(model_id: str) -> int:
    """Return output dimension for a registered SentenceTransformer model id."""
    for spec in _EMBEDDING_BACKENDS.values():
        if spec.model_id == model_id:
            return spec.dimension
    raise ValueError(
        f"Model {model_id!r} is not a registered PA2 embedding model. "
        f"Use one of: {', '.join(spec.model_id for spec in _EMBEDDING_BACKENDS.values())}."
    )


def _hub_cache_snapshot_dir(model_id: str) -> Path | None:
    """Return .../hub/models--org--name/snapshots under the repo-local HF cache if present."""
    # HF hub flattens repo ids: "sentence-transformers/LaBSE" -> models--sentence-transformers--LaBSE
    safe = model_id.replace("/", "--")
    repo_cache_root = Path(__file__).resolve().parent / ".hf_cache" / "hub" / f"models--{safe}" / "snapshots"
    if not repo_cache_root.exists() or not repo_cache_root.is_dir():
        return None
    candidates = sorted([path for path in repo_cache_root.iterdir() if path.is_dir()])
    if not candidates:
        return None
    return candidates[-1]


def _resolve_local_model_path(model_id: str) -> str | None:
    """Return a local sentence-transformers snapshot path when available for `model_id`."""
    if model_id == MINILM_MODEL_ID:
        explicit = os.environ.get("PA2_LOCAL_SENTENCE_MODEL_PATH", "").strip()
        if explicit:
            explicit_path = Path(explicit).expanduser()
            if explicit_path.exists() and explicit_path.is_dir():
                return str(explicit_path)
        snap = _hub_cache_snapshot_dir(model_id)
        return str(snap) if snap is not None else None

    if model_id == LABSE_MODEL_ID:
        explicit = os.environ.get("PA2_LOCAL_LABSE_MODEL_PATH", "").strip()
        if explicit:
            explicit_path = Path(explicit).expanduser()
            if explicit_path.exists() and explicit_path.is_dir():
                return str(explicit_path)
        snap = _hub_cache_snapshot_dir(model_id)
        return str(snap) if snap is not None else None

    return None


def _resolve_model_name_or_path(model_id: str) -> str:
    """Resolve HF model id to a local snapshot when available."""
    local_path = _resolve_local_model_path(model_id)
    if local_path:
        return local_path
    return model_id


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
    - ValueError: if `model_name` is not a registered PA2 embedding model id.
    - RuntimeError: if the model output dimension does not match the registry.
    """
    from sentence_transformers import SentenceTransformer

    expected_dim = _expected_dimension_for_model_id(model_name)
    resolved_model = _resolve_model_name_or_path(model_name)
    model = SentenceTransformer(resolved_model)
    model_dimension = int(model.get_sentence_embedding_dimension())
    if model_dimension != expected_dim:
        raise RuntimeError(
            f"Model '{model_name}' dimension mismatch: expected {expected_dim}, got {model_dimension}."
        )
    return model


def encode_texts(
    texts: Sequence[str],
    *,
    normalize_embeddings: bool,
    model_name: str = MODEL_NAME,
) -> np.ndarray:
    """Encode texts to float32 matrix ``(len(texts), D)`` where ``D`` matches the registry for ``model_name``."""
    expected_dim = _expected_dimension_for_model_id(model_name)
    model = load_sentence_model(model_name)
    embeddings = model.encode(
        list(texts),
        convert_to_numpy=True,
        normalize_embeddings=normalize_embeddings,
        show_progress_bar=False,
    )
    if embeddings.ndim != 2:
        raise RuntimeError(f"Unexpected embedding tensor rank: {embeddings.ndim}.")
    if embeddings.shape[1] != expected_dim:
        raise RuntimeError(
            f"Encoded dimension mismatch: expected {expected_dim}, got {embeddings.shape[1]}."
        )
    return embeddings.astype(np.float32, copy=False)
