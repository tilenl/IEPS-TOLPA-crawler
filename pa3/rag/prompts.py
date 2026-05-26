"""Prompt templates and context formatting for RAG and LLM-only modes."""

from __future__ import annotations

from typing import Sequence

from pa2_retrieval import RetrievalHit

RAG_PROMPT_TEMPLATE = """Answer the question using only the provided context.
If the context is insufficient, say so explicitly.
Cite which chunk numbers support your claims.

Context:
{context}

Question: {question}
"""

LLM_ONLY_PROMPT_TEMPLATE = """Answer the following question.
Do not invent repository-specific facts, URLs, or metrics that you cannot verify.
If you lack specific information, say so.

Question: {question}
"""


def _hit_score_label(hit: RetrievalHit) -> str:
    if hit.rerank_score is not None:
        return f"rerank_score={hit.rerank_score:.4f}"
    return f"distance={hit.distance:.4f}"


def format_chunk(hit: RetrievalHit, rank: int) -> str:
    """Format one retrieved segment as a numbered evidence block."""
    url = hit.source_url or "N/A"
    header = (
        f"[{rank}] URL: {url} | segment_id: {hit.segment_id} | "
        f"page_id: {hit.page_id} | {_hit_score_label(hit)}"
    )
    body = hit.segment_text.strip()
    return f"{header}\n{body}"


def format_context(
    hits: Sequence[RetrievalHit],
    *,
    max_chars: int | None = None,
) -> str:
    """
    Join retrieved segments into one context string.

    When `max_chars` is set, drop lowest-ranked chunks until within budget.
    """
    if not hits:
        return ""

    ranked = list(hits)
    while ranked:
        blocks = [format_chunk(hit, index) for index, hit in enumerate(ranked, start=1)]
        context = "\n\n".join(blocks)
        if max_chars is None or len(context) <= max_chars:
            return context
        ranked = ranked[:-1]

    return format_chunk(hits[0], 1)[:max_chars] if max_chars and hits else ""


def build_rag_prompt(question: str, context: str) -> str:
    """Build the With Context (RAG) prompt string."""
    return RAG_PROMPT_TEMPLATE.format(context=context, question=question)


def build_llm_only_prompt(question: str) -> str:
    """Build the Without Context prompt string."""
    return LLM_ONLY_PROMPT_TEMPLATE.format(question=question)
