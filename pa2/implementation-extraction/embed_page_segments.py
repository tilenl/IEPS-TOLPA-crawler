#!/usr/bin/env python3
"""
Generate and store embeddings for `crawldb.page_segment`.

Purpose:
- Read segmented rows produced by `segment_cleaned_content.py`.
- Encode embedding-facing text with a registered SentenceTransformer bi-encoder.
- Persist vectors into `crawldb.page_segment.embedding` (MiniLM, 384d) or
  `embedding_labse` (LaBSE, 768d).

System role:
- Upstream dependency: `segment_cleaned_content.py` writes deterministic segments.
- Downstream dependency: retrieval/demo pipeline queries vector similarity.

Assumptions and invariants:
- Target pgvector column exists with the dimension required by the selected backend.
- Segment text is English README-derived content.
- Embedding generation is resumable by row id and can be re-run safely.

Created: 2026-05-06
Major revisions:
- 2026-05-06: Initial Phase 4 embedding pipeline with retry + diagnostics.
- 2026-05-10: `--embedding {minilm,labse}` and dual-column writes.
"""

from __future__ import annotations

import argparse
import logging
import statistics
import time
from dataclasses import dataclass
from typing import Any, Sequence

import numpy as np
from embedding_common import (
    combined_v4_embed_input,
    encode_texts,
    parse_embedding_backend,
    resolve_conn_kwargs,
)

V4_STRATEGY = "heading_structure_v4"

_ALLOWED_PG_EMBEDDING_COLUMNS = frozenset({"embedding", "embedding_labse"})

_UPDATE_SQL_BY_COLUMN: dict[str, str] = {
    "embedding": """
UPDATE crawldb.page_segment
SET embedding = %s
WHERE id = %s
""",
    "embedding_labse": """
UPDATE crawldb.page_segment
SET embedding_labse = %s
WHERE id = %s
""",
}


@dataclass(frozen=True)
class SegmentRow:
    """One segment row loaded from PostgreSQL for embedding generation."""

    id: int
    page_id: int
    chunk_index: int
    strategy: str
    segment_text: str
    embedding_text: str


@dataclass
class EmbeddingRunStats:
    """Aggregated metrics and anomalies collected during one embedding run."""

    rows_seen: int = 0
    rows_with_text: int = 0
    rows_skipped_empty_text: int = 0
    rows_embedded: int = 0
    failed_rows: int = 0
    failed_batches: int = 0
    norm_values: list[float] | None = None
    near_zero_norm_count: int = 0
    sparse_vector_count: int = 0

    def __post_init__(self) -> None:
        if self.norm_values is None:
            self.norm_values = []


def _fetch_batch(
    conn: Any,
    *,
    last_seen_id: int,
    strategy: str | None,
    page_id: int | None,
    include_already_embedded: bool,
    limit: int | None,
    batch_size: int,
    embedding_column: str,
) -> list[SegmentRow]:
    """Fetch the next deterministic batch of segment rows by primary key."""
    if embedding_column not in _ALLOWED_PG_EMBEDDING_COLUMNS:
        raise ValueError(f"Invalid embedding column: {embedding_column!r}")
    sql = """
SELECT id, page_id, chunk_index, strategy, segment_text, embedding_text
FROM crawldb.page_segment
WHERE id > %s
"""
    params: list[Any] = [last_seen_id]
    if strategy is not None:
        sql += " AND strategy = %s"
        params.append(strategy)
    if page_id is not None:
        sql += " AND page_id = %s"
        params.append(page_id)
    if not include_already_embedded:
        sql += f" AND {embedding_column} IS NULL"
    sql += " ORDER BY id"
    sql += " LIMIT %s"
    params.append(batch_size if limit is None else min(batch_size, max(limit, 0)))

    if limit is not None and limit <= 0:
        return []

    rows = conn.execute(sql, params).fetchall()
    return [
        SegmentRow(
            id=int(row[0]),
            page_id=int(row[1]),
            chunk_index=int(row[2]),
            strategy=str(row[3]),
            segment_text=str(row[4] or ""),
            embedding_text=str(row[5] or ""),
        )
        for row in rows
    ]


def _embedding_source_text(row: SegmentRow) -> str:
    """Resolve the text payload to encode for one segment row."""
    # NOTE: V4 stores additive metadata in embedding_text; the model input is prefix + body.
    if row.strategy == V4_STRATEGY:
        if not row.embedding_text.strip() and not row.segment_text.strip():
            raise ValueError(
                f"V4 segment row id={row.id} has empty embedding_text and segment_text. "
                "Rebuild V4 segmentation before embedding."
            )
        return combined_v4_embed_input(row.embedding_text, row.segment_text)
    return row.segment_text


def _split_valid_rows(rows: Sequence[SegmentRow]) -> tuple[list[SegmentRow], int]:
    """Separate rows with usable text from rows that should be skipped."""
    valid: list[SegmentRow] = []
    skipped = 0
    for row in rows:
        source_text = _embedding_source_text(row)
        # NOTE: Empty/whitespace-only rows cannot produce meaningful embeddings.
        if not source_text or not source_text.strip():
            skipped += 1
            continue
        valid.append(row)
    return valid, skipped


def _record_embedding_quality(
    stats: EmbeddingRunStats,
    embeddings: np.ndarray,
    *,
    near_zero_norm_threshold: float,
    sparse_non_zero_ratio_threshold: float,
) -> None:
    """Update diagnostics that detect suspicious vectors."""
    norms = np.linalg.norm(embeddings, axis=1)
    stats.norm_values.extend(float(value) for value in norms.tolist())
    stats.near_zero_norm_count += int(np.count_nonzero(norms <= near_zero_norm_threshold))

    # NOTE: Sparse-ish dense vectors can signal broken tokenization/encoding paths.
    non_zero_ratio = np.count_nonzero(embeddings, axis=1) / float(embeddings.shape[1])
    stats.sparse_vector_count += int(
        np.count_nonzero(non_zero_ratio <= sparse_non_zero_ratio_threshold)
    )


def _update_embeddings(
    conn: Any,
    rows: Sequence[SegmentRow],
    embeddings: np.ndarray,
    *,
    embedding_column: str,
) -> None:
    """Persist one embedding batch via parameterized UPDATE statements."""
    if embedding_column not in _ALLOWED_PG_EMBEDDING_COLUMNS:
        raise ValueError(f"Invalid embedding column: {embedding_column!r}")
    sql = _UPDATE_SQL_BY_COLUMN[embedding_column]
    payload = [(embeddings[index], row.id) for index, row in enumerate(rows)]
    with conn.cursor() as cur:
        cur.executemany(sql, payload)


def _run_batch_with_retries(
    conn: Any,
    *,
    rows: Sequence[SegmentRow],
    dry_run: bool,
    normalize_embeddings: bool,
    max_retries: int,
    retry_delay_seconds: float,
    near_zero_norm_threshold: float,
    sparse_non_zero_ratio_threshold: float,
    stats: EmbeddingRunStats,
    model_name: str,
    embedding_column: str,
) -> None:
    """Encode and persist one batch with bounded retries and rollback safety."""
    attempts = max(1, max_retries + 1)
    for attempt in range(1, attempts + 1):
        try:
            embeddings = encode_texts(
                [_embedding_source_text(row) for row in rows],
                normalize_embeddings=normalize_embeddings,
                model_name=model_name,
            )
            _record_embedding_quality(
                stats,
                embeddings,
                near_zero_norm_threshold=near_zero_norm_threshold,
                sparse_non_zero_ratio_threshold=sparse_non_zero_ratio_threshold,
            )
            if not dry_run:
                _update_embeddings(
                    conn, rows, embeddings, embedding_column=embedding_column
                )
                conn.commit()
            stats.rows_embedded += len(rows)
            return
        except Exception as exc:
            if not dry_run:
                conn.rollback()
            is_last = attempt == attempts
            logging.warning(
                "batch failed (attempt %s/%s, rows=%s, first_segment_id=%s): %s",
                attempt,
                attempts,
                len(rows),
                rows[0].id if rows else None,
                exc,
            )
            if is_last:
                stats.failed_batches += 1
                stats.failed_rows += len(rows)
                return
            # NOTE: Linear backoff keeps retries simple and visible in logs.
            time.sleep(retry_delay_seconds * float(attempt))


def _log_summary(stats: EmbeddingRunStats, *, near_zero_norm_threshold: float) -> None:
    """Emit human-readable run summary and anomaly warnings."""
    if stats.norm_values:
        norm_min = min(stats.norm_values)
        norm_median = statistics.median(stats.norm_values)
        norm_p95 = _percentile(stats.norm_values, 95.0)
    else:
        norm_min = 0.0
        norm_median = 0.0
        norm_p95 = 0.0

    logging.info(
        (
            "embedding summary: rows_seen=%s rows_with_text=%s embedded=%s "
            "skipped_empty=%s failed_rows=%s failed_batches=%s"
        ),
        stats.rows_seen,
        stats.rows_with_text,
        stats.rows_embedded,
        stats.rows_skipped_empty_text,
        stats.failed_rows,
        stats.failed_batches,
    )
    logging.info(
        "norm stats: min=%.6f median=%.6f p95=%.6f near_zero(<=%.2e)=%s sparse_vectors=%s",
        norm_min,
        norm_median,
        norm_p95,
        near_zero_norm_threshold,
        stats.near_zero_norm_count,
        stats.sparse_vector_count,
    )

    if stats.rows_embedded == 0:
        logging.warning("No embeddings were generated. Check filters or source data.")
    if stats.near_zero_norm_count > 0:
        logging.warning(
            "Detected %s near-zero embeddings. Inspect segment text quality or model runtime.",
            stats.near_zero_norm_count,
        )
    if stats.failed_rows > 0:
        logging.warning(
            "Failed to embed %s rows after retries. Re-run with narrower filters for debugging.",
            stats.failed_rows,
        )


def _percentile(values: Sequence[float], pct: float) -> float:
    """Compute percentile value with nearest-rank semantics."""
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(1, int(round((pct / 100.0) * len(ordered))))
    return float(ordered[min(len(ordered) - 1, rank - 1)])


def run_embedding_pipeline(
    *,
    strategy: str | None = None,
    page_id: int | None = None,
    limit: int | None = None,
    batch_size: int = 64,
    rebuild_embeddings: bool = False,
    dry_run: bool = False,
    normalize_embeddings: bool = True,
    max_retries: int = 2,
    retry_delay_seconds: float = 1.0,
    near_zero_norm_threshold: float = 1e-6,
    sparse_non_zero_ratio_threshold: float = 0.05,
    embedding: str = "minilm",
) -> EmbeddingRunStats:
    """
    Generate embeddings for segment rows and store them in PostgreSQL.

    Side effects:
    - Reads from `crawldb.page_segment`.
    - Updates the target embedding column unless `dry_run=True`.
    """
    if batch_size <= 0:
        raise ValueError("batch_size must be > 0")
    if limit is not None and limit < 0:
        raise ValueError("limit must be >= 0 when provided")
    if max_retries < 0:
        raise ValueError("max_retries must be >= 0")

    backend = parse_embedding_backend(embedding)
    model_name = backend.model_id
    embedding_column = backend.pg_column

    import psycopg
    from pgvector.psycopg import register_vector

    conn_kwargs = resolve_conn_kwargs()
    stats = EmbeddingRunStats()
    remaining = limit
    last_seen_id = 0

    with psycopg.connect(**conn_kwargs, autocommit=False) as conn:
        conn.execute("SET search_path TO crawldb, public")
        register_vector(conn)

        while True:
            rows = _fetch_batch(
                conn,
                last_seen_id=last_seen_id,
                strategy=strategy,
                page_id=page_id,
                include_already_embedded=rebuild_embeddings,
                limit=remaining,
                batch_size=batch_size,
                embedding_column=embedding_column,
            )
            if not rows:
                break
            last_seen_id = rows[-1].id
            stats.rows_seen += len(rows)

            valid_rows, skipped_empty = _split_valid_rows(rows)
            stats.rows_skipped_empty_text += skipped_empty
            stats.rows_with_text += len(valid_rows)
            if valid_rows:
                _run_batch_with_retries(
                    conn,
                    rows=valid_rows,
                    dry_run=dry_run,
                    normalize_embeddings=normalize_embeddings,
                    max_retries=max_retries,
                    retry_delay_seconds=retry_delay_seconds,
                    near_zero_norm_threshold=near_zero_norm_threshold,
                    sparse_non_zero_ratio_threshold=sparse_non_zero_ratio_threshold,
                    stats=stats,
                    model_name=model_name,
                    embedding_column=embedding_column,
                )

            if remaining is not None:
                remaining -= len(rows)
                if remaining <= 0:
                    break

        if dry_run:
            # NOTE: Defensive rollback guarantees no accidental writes in dry runs.
            conn.rollback()

    _log_summary(stats, near_zero_norm_threshold=near_zero_norm_threshold)
    return stats


def _main(argv: list[str] | None = None) -> int:
    """CLI entrypoint for the Phase 4 embedding pipeline."""
    parser = argparse.ArgumentParser(
        description="Generate embeddings for crawldb.page_segment (MiniLM or LaBSE)."
    )
    parser.add_argument(
        "--embedding",
        choices=("minilm", "labse"),
        default="minilm",
        help="Bi-encoder backend and target column: minilm→embedding(384), labse→embedding_labse(768).",
    )
    parser.add_argument(
        "--strategy",
        type=str,
        default=None,
        help="Only process one strategy label (default: all strategies).",
    )
    parser.add_argument(
        "--page-id",
        type=int,
        default=None,
        help="Only process one crawldb.page.id.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Process at most N segment rows after DB filters.",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=64,
        help="Rows per embedding/update batch (default: 64).",
    )
    parser.add_argument(
        "--rebuild-embeddings",
        action="store_true",
        help="Re-embed rows even when embedding is already present.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Generate embeddings and diagnostics without database writes.",
    )
    parser.add_argument(
        "--no-normalize",
        action="store_true",
        help="Disable L2 normalization in SentenceTransformer.encode().",
    )
    parser.add_argument(
        "--max-retries",
        type=int,
        default=2,
        help="Retry count for failed batches before giving up (default: 2).",
    )
    parser.add_argument(
        "--retry-delay-seconds",
        type=float,
        default=1.0,
        help="Base delay for retry backoff in seconds (default: 1.0).",
    )
    parser.add_argument(
        "--near-zero-norm-threshold",
        type=float,
        default=1e-6,
        help="Warn on embedding norms less than or equal to this value.",
    )
    parser.add_argument(
        "--sparse-non-zero-ratio-threshold",
        type=float,
        default=0.05,
        help=(
            "Warn when (non-zero dimensions / total dimensions) is below this value; "
            "default: 0.05."
        ),
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Enable DEBUG logging.",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )

    run_embedding_pipeline(
        strategy=args.strategy,
        page_id=args.page_id,
        limit=args.limit,
        batch_size=args.batch_size,
        rebuild_embeddings=args.rebuild_embeddings,
        dry_run=args.dry_run,
        normalize_embeddings=not args.no_normalize,
        max_retries=args.max_retries,
        retry_delay_seconds=args.retry_delay_seconds,
        near_zero_norm_threshold=args.near_zero_norm_threshold,
        sparse_non_zero_ratio_threshold=args.sparse_non_zero_ratio_threshold,
        embedding=args.embedding,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
