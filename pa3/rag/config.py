"""PA3 RAG default configuration."""

from __future__ import annotations

import os
from dataclasses import dataclass, field

from pa2_retrieval import RetrievalConfig


@dataclass(frozen=True)
class RagConfig:
    """End-to-end RAG pipeline settings."""

    retrieval: RetrievalConfig = field(
        default_factory=lambda: RetrievalConfig(
            top_k=5,
            candidate_k=20,
            rerank=True,
            strategy="heading_structure_v4",
            metric="cosine",
            embedding="minilm",
        )
    )
    llm_model: str = "ollama_chat/llama3.2:1b"
    ollama_api_base: str = "http://localhost:11434"
    max_context_chars: int = 7000
    database_url: str | None = field(
        default_factory=lambda: os.environ.get("DATABASE_URL")
    )


DEFAULT_CONFIG = RagConfig()
