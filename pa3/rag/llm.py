"""Ollama LLM integration via dspy."""

from __future__ import annotations

from typing import Literal

import dspy

from config import RagConfig
from ollama_health import require_ollama

Mode = Literal["rag", "llm_only"]

_lm_configured = False


def configure_lm(config: RagConfig | None = None) -> dspy.LM:
    """Configure and return the shared dspy language model."""
    global _lm_configured
    cfg = config or RagConfig()
    lm = dspy.LM(
        cfg.llm_model,
        api_base=cfg.ollama_api_base,
        api_key="",
    )
    if not _lm_configured:
        dspy.configure(lm=lm)
        _lm_configured = True
    return lm


class SimpleRAG(dspy.Signature):
    """Answer the user's question based on the retrieved context."""

    context: str = dspy.InputField(desc="Retrieved document chunks")
    question: str = dspy.InputField(desc="User question")
    answer: str = dspy.OutputField(desc="Grounded answer citing evidence when possible")


class SimpleQA(dspy.Signature):
    """Answer the user's question without external context."""

    question: str = dspy.InputField(desc="User question")
    answer: str = dspy.OutputField(desc="Answer without repository-specific invention")


def generate_answer(
    question: str,
    *,
    context: str = "",
    mode: Mode = "rag",
    config: RagConfig | None = None,
    use_signature: bool = True,
) -> str:
    """
    Generate an answer for the given mode.

    When `use_signature` is True, uses dspy Predict modules; otherwise plain prompts.
    """
    cfg = config or RagConfig()
    # dspy talks to Ollama over HTTP; the `ollama` CLI must be installed and the server running.
    model_tag = cfg.llm_model.split("/", 1)[-1] if "/" in cfg.llm_model else cfg.llm_model
    require_ollama(api_base=cfg.ollama_api_base, model=model_tag)
    configure_lm(cfg)

    if use_signature:
        if mode == "rag":
            predictor = dspy.Predict(SimpleRAG)
            result = predictor(context=context, question=question)
            return str(result.answer).strip()
        predictor = dspy.Predict(SimpleQA)
        result = predictor(question=question)
        return str(result.answer).strip()

    from prompts import build_llm_only_prompt, build_rag_prompt

    if mode == "rag":
        prompt = build_rag_prompt(question, context)
    else:
        prompt = build_llm_only_prompt(question)

    lm = dspy.settings.lm
    response = lm(prompt)
    if isinstance(response, list) and response:
        return str(response[0]).strip()
    return str(response).strip()
