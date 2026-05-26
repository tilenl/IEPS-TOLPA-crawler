"""PA3 RAG pipeline: retrieve, prompt, generate."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from typing import Any, Literal

from config import RagConfig, DEFAULT_CONFIG
from llm import Mode, generate_answer
from prompts import build_llm_only_prompt, build_rag_prompt, format_context
from pa2_retrieval import RetrievalHit, retrieve

ModeName = Literal["rag", "llm_only"]


@dataclass
class RunResult:
    """One end-to-end query run (single mode)."""

    query: str
    mode: ModeName
    hits: list[RetrievalHit] = field(default_factory=list)
    context: str = ""
    rag_prompt: str = ""
    llm_only_prompt: str = ""
    prompt: str = ""
    answer: str = ""
    config: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        """Serialize for JSON export."""
        data = asdict(self)
        data["hits"] = [
            {
                "segment_id": h.segment_id,
                "page_id": h.page_id,
                "chunk_index": h.chunk_index,
                "strategy": h.strategy,
                "distance": h.distance,
                "segment_text": h.segment_text,
                "source_url": h.source_url,
                "rerank_score": h.rerank_score,
            }
            for h in self.hits
        ]
        return data


def _retrieval_kwargs(config: RagConfig) -> dict[str, Any]:
    rc = config.retrieval
    db_url = config.database_url or rc.database_url
    return {
        "top_k": rc.top_k,
        "candidate_k": rc.candidate_k,
        "rerank": rc.rerank,
        "strategy": rc.strategy,
        "metric": rc.metric,
        "embedding": rc.embedding,
        "model_name": rc.model_name,
        "rerank_model": rc.rerank_model,
        "database_url": db_url,
        "normalize_embeddings": rc.normalize_embeddings,
    }


def answer_query(
    query: str,
    *,
    mode: ModeName,
    config: RagConfig | None = None,
    use_signature: bool = True,
) -> RunResult:
    """Run retrieval (if RAG mode), build prompt, and generate an answer."""
    cfg = config or DEFAULT_CONFIG
    hits: list[RetrievalHit] = []
    context = ""

    if mode == "rag":
        hits = retrieve(query, config=cfg.retrieval, database_url=cfg.database_url)
        context = format_context(hits, max_chars=cfg.max_context_chars)

    prompt = (
        build_rag_prompt(query, context)
        if mode == "rag"
        else build_llm_only_prompt(query)
    )

    answer = generate_answer(
        query,
        context=context,
        mode=mode,  # type: ignore[arg-type]
        config=cfg,
        use_signature=use_signature,
    )

    rc = cfg.retrieval
    config_snapshot = {
        "llm_model": cfg.llm_model,
        "ollama_api_base": cfg.ollama_api_base,
        "max_context_chars": cfg.max_context_chars,
        "top_k": rc.top_k,
        "candidate_k": rc.candidate_k,
        "rerank": rc.rerank,
        "strategy": rc.strategy,
        "metric": rc.metric,
        "embedding": rc.embedding,
        "use_signature": use_signature,
    }

    return RunResult(
        query=query,
        mode=mode,
        hits=hits,
        context=context,
        rag_prompt=build_rag_prompt(query, context) if mode == "rag" else "",
        llm_only_prompt=build_llm_only_prompt(query),
        prompt=prompt,
        answer=answer,
        config=config_snapshot,
    )


def answer_both_modes(
    query: str,
    *,
    config: RagConfig | None = None,
    use_signature: bool = True,
) -> dict[str, RunResult]:
    """Run the same query in RAG and LLM-only modes."""
    return {
        "rag": answer_query(query, mode="rag", config=config, use_signature=use_signature),
        "llm_only": answer_query(
            query, mode="llm_only", config=config, use_signature=use_signature
        ),
    }


def save_run_result(path: str, payload: dict[str, Any]) -> None:
    """Write run results as JSON."""
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, ensure_ascii=False)
