#!/usr/bin/env python3
"""
PA2 semantic retrieval library (shared by demo.py and PA3 RAG).

Purpose:
- Embed queries and search crawldb.page_segment with pgvector.
- Optionally rerank candidates with a cross-encoder.

Created: 2026-05-26
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Literal, Sequence

import psycopg
from pgvector.psycopg import register_vector

from embedding_common import encode_texts, parse_embedding_backend, resolve_conn_kwargs

METRIC_TO_OPERATOR = {
    "cosine": "<=>",
    "l2": "<->",
    "inner_product": "<#>",
    "l1": "<+>",
}

MetricName = Literal["cosine", "l2", "inner_product", "l1"]
EmbeddingKey = Literal["minilm", "labse"]

DEFAULT_RERANK_MODEL = "cross-encoder/ms-marco-MiniLM-L-6-v2"


@dataclass(frozen=True)
class RetrievalHit:
    """One retrieved segment with optional reranking metadata."""

    segment_id: int
    page_id: int
    chunk_index: int
    strategy: str
    distance: float
    segment_text: str
    source_url: str | None
    rerank_score: float | None = None


@dataclass(frozen=True)
class RetrievalConfig:
    """Parameters for one retrieval run."""

    top_k: int = 5
    candidate_k: int = 20
    rerank: bool = True
    strategy: str = "heading_structure_v4"
    metric: MetricName = "cosine"
    embedding: EmbeddingKey = "minilm"
    model_name: str | None = None
    rerank_model: str = DEFAULT_RERANK_MODEL
    database_url: str | None = None
    normalize_embeddings: bool = True


def connect(database_url: str | None = None) -> Any:
    """Create PostgreSQL connection and register pgvector adapter."""
    if database_url:
        conn = psycopg.connect(database_url, autocommit=True)
    else:
        conn = psycopg.connect(**resolve_conn_kwargs(), autocommit=True)
    conn.execute("SET search_path TO crawldb, public")
    register_vector(conn)
    return conn


def embed_query(query: str, *, model_name: str, normalize_embeddings: bool = True) -> Any:
    """Encode one query text and return vector payload acceptable by pgvector."""
    embeddings = encode_texts(
        [query],
        normalize_embeddings=normalize_embeddings,
        model_name=model_name,
    )
    return embeddings[0]


def _qualified_pg_embedding_column(pg_column: str) -> str:
    """Return `ps.<column>` for whitelisted page_segment embedding columns."""
    if pg_column == "embedding":
        return "ps.embedding"
    if pg_column == "embedding_labse":
        return "ps.embedding_labse"
    raise ValueError(f"Unsupported embedding column: {pg_column!r}")


def retrieve_hits(
    conn: Any,
    *,
    query_vector: Any,
    top_k: int,
    strategy: str | None,
    metric: MetricName,
    embedding_pg_column: str,
) -> list[RetrievalHit]:
    """Execute metric-aware vector search and map rows to typed hits."""
    operator = METRIC_TO_OPERATOR[metric]
    col_sql = _qualified_pg_embedding_column(embedding_pg_column)
    sql = f"""
SELECT
    ps.id,
    ps.page_id,
    ps.chunk_index,
    ps.strategy,
    ps.segment_text,
    p.url,
    ({col_sql} {operator} %s) AS distance
FROM crawldb.page_segment ps
JOIN crawldb.page p ON p.id = ps.page_id
WHERE {col_sql} IS NOT NULL
"""
    params: list[Any] = [query_vector]
    if strategy:
        sql += " AND ps.strategy = %s"
        params.append(strategy)
    sql += " ORDER BY distance ASC LIMIT %s"
    params.append(top_k)

    rows = conn.execute(sql, params).fetchall()
    return [
        RetrievalHit(
            segment_id=int(row[0]),
            page_id=int(row[1]),
            chunk_index=int(row[2]),
            strategy=str(row[3]),
            segment_text=str(row[4] or ""),
            source_url=str(row[5]) if row[5] is not None else None,
            distance=float(row[6]),
        )
        for row in rows
    ]


def rerank_hits(
    query: str,
    hits: Sequence[RetrievalHit],
    *,
    rerank_model: str,
    top_k: int,
) -> list[RetrievalHit]:
    """Rerank candidates with a cross-encoder and return top-ranked hits."""
    from sentence_transformers import CrossEncoder

    if not hits:
        return []
    model = CrossEncoder(rerank_model)
    pairs = [[query, hit.segment_text] for hit in hits]
    scores = model.predict(pairs)

    scored = [
        RetrievalHit(
            segment_id=hit.segment_id,
            page_id=hit.page_id,
            chunk_index=hit.chunk_index,
            strategy=hit.strategy,
            distance=hit.distance,
            segment_text=hit.segment_text,
            source_url=hit.source_url,
            rerank_score=float(score),
        )
        for hit, score in zip(hits, scores, strict=True)
    ]
    scored.sort(
        key=lambda item: item.rerank_score if item.rerank_score is not None else -1e9,
        reverse=True,
    )
    return scored[:top_k]


def retrieve(
    query: str,
    *,
    config: RetrievalConfig | None = None,
    top_k: int | None = None,
    candidate_k: int | None = None,
    rerank: bool | None = None,
    strategy: str | None = None,
    metric: MetricName | None = None,
    embedding: EmbeddingKey | None = None,
    model_name: str | None = None,
    rerank_model: str | None = None,
    database_url: str | None = None,
    normalize_embeddings: bool | None = None,
) -> list[RetrievalHit]:
    """Embed query, search pgvector, optionally rerank, and return top hits."""
    cfg = config or RetrievalConfig()
    top_k = top_k if top_k is not None else cfg.top_k
    candidate_k = candidate_k if candidate_k is not None else cfg.candidate_k
    rerank = rerank if rerank is not None else cfg.rerank
    strategy = strategy if strategy is not None else cfg.strategy
    metric = metric if metric is not None else cfg.metric
    embedding = embedding if embedding is not None else cfg.embedding
    model_name = model_name if model_name is not None else cfg.model_name
    rerank_model = rerank_model if rerank_model is not None else cfg.rerank_model
    database_url = database_url if database_url is not None else cfg.database_url
    normalize_embeddings = (
        normalize_embeddings if normalize_embeddings is not None else cfg.normalize_embeddings
    )

    if top_k <= 0:
        raise ValueError("top_k must be > 0")
    if candidate_k <= 0:
        raise ValueError("candidate_k must be > 0")

    backend = parse_embedding_backend(embedding)
    resolved_model = model_name or backend.model_id
    if resolved_model != backend.model_id:
        raise ValueError(
            f"model_name {resolved_model!r} does not match embedding {embedding!r} "
            f"(expected {backend.model_id!r})."
        )

    query_vector = embed_query(
        query,
        model_name=resolved_model,
        normalize_embeddings=normalize_embeddings,
    )
    pool_k = max(top_k, candidate_k if rerank else top_k)

    with connect(database_url) as conn:
        initial_hits = retrieve_hits(
            conn,
            query_vector=query_vector,
            top_k=pool_k,
            strategy=strategy,
            metric=metric,
            embedding_pg_column=backend.pg_column,
        )

    final_hits = initial_hits[:top_k]
    if rerank and initial_hits:
        try:
            final_hits = rerank_hits(
                query,
                initial_hits,
                rerank_model=rerank_model,
                top_k=top_k,
            )
        except Exception as exc:
            logging.warning("Reranking failed, falling back to base retrieval: %s", exc)
            final_hits = initial_hits[:top_k]

    return final_hits
