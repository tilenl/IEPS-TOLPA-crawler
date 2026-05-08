#!/usr/bin/env python3
"""
Strategy C segmentation pipeline for PA2 cleaned README content.

Purpose:
- Read `crawldb.page.cleaned_content` rows produced by `extract_readme.py`.
- Build heading-aware, structure-aware segments suitable for retrieval.
- Persist deterministic chunks into `crawldb.page_segment`.

System role:
- Upstream dependency: `extract_readme.py` populates cleaned content with `[H1]..[H6]`.
- Downstream dependency: embedding/indexing pipeline consumes `crawldb.page_segment`.

Assumptions and invariants:
- Cleaned content is separated into logical blocks with blank lines (`\\n\\n`).
- Heading markers follow the shape `[H<level>] Heading title`.
- Segment boundaries never cross heading section boundaries.

Created: 2026-05-06
Major revisions:
- 2026-05-06: Initial Strategy C implementation (chunking + DB ingestion + quality gates).
- 2026-05-06: Added `heading_structure_v3` (multi-pass greedy packing + tiny-tail repair).
- 2026-05-07: Added `heading_structure_v4` with clean `segment_text` + additive `embedding_text` combined at encode time.
- 2026-05-08: v4 merge-group surfacing (iterative with merge-group consolidation) and Nested_scope embedding prefix lines; `v4_surface_merge_max_iterations` / `PA2_V4_SURFACE_MERGE_MAX_ITERATIONS`.
- 2026-05-08: v4 additive `embedding_text` uses display `Type:` labels (`code block`, heading-based `authors`/`references`); DB `segment_type` keeps internal classifiers (`code_block`, …).
"""

from __future__ import annotations

import argparse
import logging
import os
import re
import statistics
from functools import lru_cache
from dataclasses import dataclass, replace
from typing import Any, Iterator

from embedding_common import combined_v4_embed_input

HEADING_RE = re.compile(r"^\[H([1-6])\]\s+(.+?)\s*$")
WORDLIKE_RE = re.compile(r"\w+|[^\w\s]", re.UNICODE)
LIST_LINE_RE = re.compile(r"^\s*(?:[-*+]\s+|\d+[.)]\s+)")

DEFAULT_STRATEGY = "heading_structure_v1"
V2_STRATEGY = "heading_structure_v2"
V3_STRATEGY = "heading_structure_v3"
V4_STRATEGY = "heading_structure_v4"


@dataclass(frozen=True)
class SegmentationConfig:
    """Tunable parameters for Strategy C chunking."""

    target_min_tokens: int = 180
    target_max_tokens: int = 320
    hard_cap_tokens: int = 420
    list_bundle_min_items: int = 8
    list_bundle_max_items: int = 15
    overlap_blocks_when_split: int = 1
    root_heading: str = "README"
    # v2 combine-then-split configuration (strict tokenizer counting).
    v2_target_tokens: int = 200
    v2_collapse_upper_bound_tokens: int = 220
    v2_hard_cap_tokens: int = 240
    v2_small_subsection_max_tokens: int = 40
    v2_model_name: str = "sentence-transformers/all-MiniLM-L6-v2"
    v2_local_files_only: bool = False
    # v3 multi-pass combine/split/repair configuration.
    v3_soft_target_tokens: int = 200
    v3_hard_cap_tokens: int = 240
    v3_min_chunk_tokens: int = 35
    v3_repair_max_passes: int = 2
    v3_refine_rounds: int = 1
    # v4: iterative merge-group surfacing + consolidation (0 = surfacing disabled; still one consolidate pass).
    v4_surface_merge_max_iterations: int = 5


@dataclass(frozen=True)
class Block:
    """One parsed logical block derived from cleaned content."""

    text: str
    kind: str
    token_estimate: int
    heading_level: int | None = None
    heading_text: str | None = None


@dataclass(frozen=True)
class Segment:
    """Final segment row ready for insertion."""

    page_id: int
    chunk_index: int
    strategy: str
    heading_path: str | None
    merge_group_parent: str | None
    segment_type: str
    segment_text: str
    embedding_text: str
    token_estimate: int
    char_count: int


@dataclass
class RunStats:
    """Aggregated counters and distributions for quality checks."""

    pages_read: int = 0
    pages_with_segments: int = 0
    total_segments: int = 0
    heading_path_non_null_segments: int = 0
    chunks_per_page: list[int] | None = None
    tokens_per_segment: list[int] | None = None
    strategy: str = DEFAULT_STRATEGY
    short_chunks_lt20: int = 0
    short_chunks_lt40: int = 0
    overflow_chunks_gt_cap: int = 0
    hard_cap_tokens: int = 0

    def __post_init__(self) -> None:
        if self.chunks_per_page is None:
            self.chunks_per_page = []
        if self.tokens_per_segment is None:
            self.tokens_per_segment = []


@dataclass(frozen=True)
class V2SectionUnit:
    """Section-level unit for v2 collapse and split stages."""

    full_heading_path: str | None
    parent_heading_path: str | None
    heading_title: str
    text: str
    segment_type: str
    tokens: int


@dataclass(frozen=True)
class V2ChunkPart:
    """One subsection contribution inside a v2 packed chunk."""

    heading_title: str
    text: str
    segment_type: str
    tokens: int
    source_heading_path: str | None = None


@dataclass(frozen=True)
class V2PackedChunk:
    """Intermediate packed chunk before final hard-cap splitting."""

    heading_path: str | None
    parts: list[V2ChunkPart]
    merge_group_path: str | None = None
    # Raw merge-group / heading paths promoted away during surfacing; emitted under Nested_scope (normalized).
    v4_nested_scope_paths: tuple[str, ...] = ()


@lru_cache(maxsize=1)
def _get_v2_tokenizer(model_name: str, local_files_only: bool) -> Any:
    """Load and cache tokenizer used for strict v2 token budgeting."""
    from transformers import AutoTokenizer

    try:
        return AutoTokenizer.from_pretrained(
            model_name,
            local_files_only=local_files_only,
            use_fast=True,
        )
    except Exception as exc:
        mode = "local cache only" if local_files_only else "download/cache"
        raise RuntimeError(
            f"Unable to load tokenizer '{model_name}' in {mode} mode. "
            "For strict heading_structure_v2 token counting, ensure model files are available."
        ) from exc


def _count_tokens(text: str, cfg: SegmentationConfig, *, strict: bool) -> int:
    """Count tokens with either strict model tokenizer or fast estimate."""
    if not strict:
        return _estimate_tokens(text)
    tokenizer = _get_v2_tokenizer(cfg.v2_model_name, cfg.v2_local_files_only)
    encoded = tokenizer(
        text,
        add_special_tokens=True,
        truncation=False,
        return_attention_mask=False,
        return_token_type_ids=False,
    )
    return len(encoded["input_ids"])


def _resolve_conn_kwargs() -> dict[str, Any]:
    """Resolve PostgreSQL connection kwargs from environment with PA defaults."""
    return {
        "host": os.environ.get("PGHOST", "localhost"),
        "port": int(os.environ.get("PGPORT", "5432")),
        "dbname": os.environ.get("PGDATABASE", "crawldb"),
        "user": os.environ.get("PGUSER", "user"),
        "password": os.environ.get("PGPASSWORD", "SecretPassword"),
    }


def _estimate_tokens(text: str) -> int:
    """Approximate token count with a lightweight regex tokenizer."""
    return len(WORDLIKE_RE.findall(text))


def _split_cleaned_content_into_blocks(cleaned_content: str) -> list[Block]:
    """Parse cleaned content into typed blocks, preserving heading markers."""
    blocks: list[Block] = []
    for raw in re.split(r"\n\s*\n", cleaned_content.strip()):
        text = raw.strip()
        if not text:
            continue
        heading_match = HEADING_RE.match(text)
        if heading_match:
            level = int(heading_match.group(1))
            heading_text = heading_match.group(2).strip()
            blocks.append(
                Block(
                    text=heading_text,
                    kind="heading",
                    token_estimate=_estimate_tokens(heading_text),
                    heading_level=level,
                    heading_text=heading_text,
                )
            )
            continue
        kind = _classify_block_kind(text)
        blocks.append(Block(text=text, kind=kind, token_estimate=_estimate_tokens(text)))
    return blocks


def _classify_block_kind(text: str) -> str:
    """Classify a content block to drive structure-aware chunking behavior."""
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines:
        return "prose"

    list_like_lines = sum(1 for line in lines if LIST_LINE_RE.match(line))
    if list_like_lines >= max(1, int(0.6 * len(lines))):
        return "list_item"

    pipe_lines = sum(1 for line in lines if "|" in line)
    if len(lines) >= 2 and pipe_lines >= max(2, len(lines) // 2):
        return "table_rows"

    if "```" in text:
        return "code_block"

    indented_lines = sum(1 for line in lines if line.startswith("    ") or line.startswith("\t"))
    punctuation_heavy_lines = sum(
        1 for line in lines if re.search(r"[{}();:=<>#\[\]]", line) is not None
    )
    if len(lines) >= 2 and (indented_lines >= 1 or punctuation_heavy_lines >= len(lines) // 2):
        return "code_block"

    return "prose"


def _update_heading_stack(
    heading_stack: list[tuple[int, str]], level: int, title: str
) -> list[tuple[int, str]]:
    """Return updated hierarchical heading stack after seeing a new heading."""
    trimmed = [entry for entry in heading_stack if entry[0] < level]
    trimmed.append((level, title))
    return trimmed


def _heading_path_from_stack(heading_stack: list[tuple[int, str]]) -> str | None:
    """Render a stable heading path string from the current stack."""
    if not heading_stack:
        return None
    return " > ".join([title for _, title in heading_stack])


def _split_heading_path(heading_path: str | None) -> list[str]:
    """Split heading path into components while dropping empty fragments."""
    if not heading_path:
        return []
    return [part.strip() for part in heading_path.split(" > ") if part.strip()]


def _parent_heading_path(heading_path: str | None) -> str | None:
    """Return parent heading path (without the last component)."""
    parts = _split_heading_path(heading_path)
    if len(parts) <= 1:
        return heading_path
    return " > ".join(parts[:-1])


def _last_heading_title(heading_path: str | None, cfg: SegmentationConfig) -> str:
    """Return last heading title or root fallback label."""
    parts = _split_heading_path(heading_path)
    if not parts:
        return cfg.root_heading
    return parts[-1]


def _split_oversize_block(block: Block, hard_cap_tokens: int) -> list[Block]:
    """Split an oversize block into cap-safe pieces while keeping structure."""
    if block.token_estimate <= hard_cap_tokens:
        return [block]

    if block.kind in {"code_block", "table_rows"}:
        # NOTE: For code/table blocks, line boundaries preserve meaning better than sentence cuts.
        return _split_block_by_lines(block, hard_cap_tokens)

    # NOTE: For prose/list bundles, sentence boundaries usually keep semantic coherence.
    sentence_chunks = _split_block_by_sentences(block, hard_cap_tokens)
    if sentence_chunks:
        return sentence_chunks
    return _split_block_by_words(block, hard_cap_tokens)


def _split_block_by_lines(block: Block, hard_cap_tokens: int) -> list[Block]:
    """Split a block by line groups that fit the hard cap."""
    lines = [line for line in block.text.splitlines() if line.strip()]
    pieces: list[Block] = []
    current_lines: list[str] = []
    current_tokens = 0
    for line in lines:
        line_tokens = _estimate_tokens(line)
        if current_lines and current_tokens + line_tokens > hard_cap_tokens:
            text = "\n".join(current_lines).strip()
            if text:
                pieces.append(
                    Block(
                        text=text,
                        kind=block.kind,
                        token_estimate=_estimate_tokens(text),
                    )
                )
            current_lines = [line]
            current_tokens = line_tokens
        else:
            current_lines.append(line)
            current_tokens += line_tokens
    if current_lines:
        text = "\n".join(current_lines).strip()
        if text:
            pieces.append(
                Block(text=text, kind=block.kind, token_estimate=_estimate_tokens(text))
            )
    return pieces if pieces else [block]


def _split_block_by_sentences(block: Block, hard_cap_tokens: int) -> list[Block]:
    """Split prose-like block by sentence boundaries, then cap by token count."""
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", block.text) if s.strip()]
    if len(sentences) <= 1:
        return []
    pieces: list[Block] = []
    current: list[str] = []
    current_tokens = 0
    for sentence in sentences:
        sentence_tokens = _estimate_tokens(sentence)
        if current and current_tokens + sentence_tokens > hard_cap_tokens:
            text = " ".join(current).strip()
            if text:
                pieces.append(
                    Block(text=text, kind=block.kind, token_estimate=_estimate_tokens(text))
                )
            current = [sentence]
            current_tokens = sentence_tokens
        else:
            current.append(sentence)
            current_tokens += sentence_tokens
    if current:
        text = " ".join(current).strip()
        if text:
            pieces.append(Block(text=text, kind=block.kind, token_estimate=_estimate_tokens(text)))
    return pieces


def _split_block_by_words(block: Block, hard_cap_tokens: int) -> list[Block]:
    """Last-resort split by words for very long unpunctuated text."""
    words = block.text.split()
    pieces: list[Block] = []
    start = 0
    while start < len(words):
        current_words: list[str] = []
        current_tokens = 0
        i = start
        while i < len(words):
            candidate = words[i]
            candidate_tokens = _estimate_tokens(candidate)
            if current_words and current_tokens + candidate_tokens > hard_cap_tokens:
                break
            current_words.append(candidate)
            current_tokens += candidate_tokens
            i += 1
        text = " ".join(current_words).strip()
        if text:
            pieces.append(Block(text=text, kind=block.kind, token_estimate=_estimate_tokens(text)))
        start = max(i, start + 1)
    return pieces if pieces else [block]


def _bundle_consecutive_list_items(
    blocks: list[Block], cfg: SegmentationConfig
) -> list[Block]:
    """Group consecutive list items into medium bundles to avoid microchunks."""
    output: list[Block] = []
    i = 0
    while i < len(blocks):
        if blocks[i].kind != "list_item":
            output.append(blocks[i])
            i += 1
            continue
        j = i
        while j < len(blocks) and blocks[j].kind == "list_item":
            j += 1
        list_items = blocks[i:j]
        cursor = 0
        while cursor < len(list_items):
            remaining = len(list_items) - cursor
            if remaining <= cfg.list_bundle_max_items:
                take = remaining
            else:
                take = cfg.list_bundle_max_items
                # NOTE: Keep the tail from degenerating into many tiny bundles.
                tail = remaining - take
                if 0 < tail < cfg.list_bundle_min_items:
                    take = max(cfg.list_bundle_min_items, remaining - cfg.list_bundle_min_items)
            group = list_items[cursor : cursor + take]
            bundle_text = "\n".join(item.text for item in group).strip()
            output.append(
                Block(
                    text=bundle_text,
                    kind="list_bundle",
                    token_estimate=_estimate_tokens(bundle_text),
                )
            )
            cursor += take
        i = j
    return output


def _iter_sections(blocks: list[Block], cfg: SegmentationConfig) -> Iterator[tuple[str | None, list[Block]]]:
    """Yield section-local blocks keyed by heading path."""
    heading_stack: list[tuple[int, str]] = [(0, cfg.root_heading)]
    current_path = _heading_path_from_stack(heading_stack)
    current_blocks: list[Block] = []

    for block in blocks:
        if block.kind == "heading" and block.heading_level is not None and block.heading_text:
            if current_blocks:
                yield current_path, current_blocks
                current_blocks = []
            heading_stack = _update_heading_stack(
                heading_stack, level=block.heading_level, title=block.heading_text
            )
            current_path = _heading_path_from_stack(heading_stack)
            continue
        current_blocks.append(block)

    if current_blocks:
        yield current_path, current_blocks


def _segment_type_for_chunk(blocks: list[Block]) -> str:
    """Resolve a segment type label based on the composing block kinds."""
    kinds = {block.kind for block in blocks}
    if len(kinds) == 1:
        only = next(iter(kinds))
        if only in {"list_item", "list_bundle"}:
            return "list_bundle"
        if only in {"code_block", "table_rows", "prose"}:
            return only
    return "mixed"


def _normalize_heading_path_for_context(
    heading_path: str | None, cfg: SegmentationConfig
) -> str | None:
    """Normalize heading path for embedding context by dropping root-only labels."""
    parts = _split_heading_path(heading_path)
    if parts and parts[0] == cfg.root_heading:
        parts = parts[1:]
    if not parts:
        return None
    return " > ".join(parts)


# Normalized subsection title tokens for v4 embedding_text `Type:` display labels (DB `segment_type` stays internal).
_V4_AUTHORS_HEADINGS = frozenset({"authors", "author", "maintainers", "credits"})
_V4_REFERENCES_HEADINGS = frozenset(
    {
        "references",
        "bibliography",
        "works cited",
        "citations",
        "publications",
        "further reading",
    }
)


def _normalize_heading_title_for_v4_type_label(raw: str) -> str:
    """Lowercase, collapse whitespace, strip punctuation for heading allowlist lookup."""
    t = raw.strip().lower()
    t = re.sub(r"\s+", " ", t)
    t = re.sub(r"[^\w\s]", "", t)
    return t.strip()


def _effective_subsection_title_for_v4(part: V2ChunkPart, cfg: SegmentationConfig) -> str:
    """Subsection title for type labeling; prefers explicit title, else last segment of source path."""
    ht = part.heading_title.strip()
    if ht:
        return ht
    if part.source_heading_path:
        return _last_heading_title(part.source_heading_path, cfg)
    return ""


def _v4_embedding_type_label(
    internal_type: str,
    *,
    parts: list[V2ChunkPart] | None,
    heading_path: str | None,
    cfg: SegmentationConfig,
) -> str:
    """
    Human-readable `Type:` value for v4 additive embedding prefix.

    Priority: authors headings > references headings > `code block` for code-like chunks > internal_type.
    """
    titles: list[str] = []
    if parts:
        for part in parts:
            t = _effective_subsection_title_for_v4(part, cfg)
            if t:
                titles.append(t)
    if not titles and heading_path:
        titles = [_last_heading_title(heading_path, cfg)]

    if titles:
        norms = [_normalize_heading_title_for_v4_type_label(t) for t in titles]
        if norms and all(n in _V4_AUTHORS_HEADINGS for n in norms):
            return "authors"
        if norms and all(n in _V4_REFERENCES_HEADINGS for n in norms):
            return "references"

    if internal_type == "code_block":
        return "code block"
    return internal_type


def _build_embedding_text(
    *,
    heading_path: str | None,
    segment_type: str,
    body_text: str,
    cfg: SegmentationConfig,
    include_context_prefix: bool,
    type_display_label: str | None = None,
) -> str:
    """Build embedding-facing text with optional contextual prefix metadata."""
    body = body_text.strip()
    if not include_context_prefix:
        return body

    context_lines: list[str] = []
    context_path = _normalize_heading_path_for_context(heading_path, cfg)
    if context_path:
        context_lines.append(f"Context: {context_path}")
    type_line = type_display_label if type_display_label is not None else segment_type
    context_lines.append(f"Type: {type_line}")
    prefix = "\n".join(context_lines).strip()
    if not prefix:
        return body
    if not body:
        return prefix
    return f"{prefix}\n\n{body}".strip()


def _v4_merge_titles_for_parts(parts: list[V2ChunkPart]) -> tuple[str, ...]:
    """Ordered subsection titles for Merged_sections line when a chunk spans multiple parts."""
    if len(parts) <= 1:
        return ()
    titles = [part.heading_title.strip() for part in parts if part.heading_title.strip()]
    return tuple(titles) if len(titles) > 1 else ()


def _build_v4_embedding_prefix(
    heading_path: str | None,
    segment_type: str,
    cfg: SegmentationConfig,
    *,
    merged_section_titles: tuple[str, ...] = (),
) -> str:
    """
    V4 additive prefix only (no segment body). Context + Type, optional Merged_sections.
    """
    type_label = _v4_embedding_type_label(
        segment_type,
        parts=None,
        heading_path=heading_path,
        cfg=cfg,
    )
    base = _build_embedding_text(
        heading_path=heading_path,
        segment_type=segment_type,
        body_text="",
        cfg=cfg,
        include_context_prefix=True,
        type_display_label=type_label,
    ).strip()
    if len(merged_section_titles) > 1:
        merged_line = "Merged_sections: " + "; ".join(merged_section_titles)
        if base:
            return f"{base}\n{merged_line}".strip()
        return merged_line
    return base


def _union_v4_nested_scope_paths(
    left: tuple[str, ...], right: tuple[str, ...]
) -> tuple[str, ...]:
    """Deterministic union of raw scope path strings (dedupe, sorted)."""
    return tuple(sorted(set((*left, *right))))


def _v4_embedding_prefix_for_packed(
    packed: V2PackedChunk,
    cfg: SegmentationConfig,
    *,
    parts_for_titles: list[V2ChunkPart] | None = None,
) -> str:
    """
    V4 prefix for a packed chunk: Context, Type, optional Nested_scope (Option B), optional Merged_sections.
    `parts_for_titles` overrides which parts drive Merged_sections / segment type when splitting sub-ranges.
    """
    title_parts = parts_for_titles if parts_for_titles is not None else packed.parts
    segment_type = _segment_type_from_parts(title_parts)
    type_label = _v4_embedding_type_label(
        segment_type,
        parts=title_parts,
        heading_path=packed.heading_path,
        cfg=cfg,
    )
    merged_titles = _v4_merge_titles_for_parts(title_parts)

    scope_bits: list[str] = []
    for raw in sorted(set(packed.v4_nested_scope_paths)):
        n = _normalize_heading_path_for_context(raw, cfg)
        if n:
            scope_bits.append(n)

    if packed.v4_nested_scope_paths:
        ctx_norm = _normalize_heading_path_for_context(
            packed.merge_group_path or packed.heading_path, cfg
        )
        if not ctx_norm and scope_bits:
            ctx_norm = scope_bits[0]
    else:
        ctx_norm = _normalize_heading_path_for_context(packed.heading_path, cfg)

    context_lines: list[str] = []
    if ctx_norm:
        context_lines.append(f"Context: {ctx_norm}")
    context_lines.append(f"Type: {type_label}")
    base = "\n".join(context_lines).strip()

    extra: list[str] = []
    nested_display = [s for s in scope_bits if s != ctx_norm]
    if nested_display:
        extra.append("Nested_scope: " + "; ".join(nested_display))
    if len(merged_titles) > 1:
        extra.append("Merged_sections: " + "; ".join(merged_titles))
    if not base:
        return "\n".join(extra).strip() if extra else ""
    if not extra:
        return base
    return f"{base}\n" + "\n".join(extra)


def _v4_packed_combined_token_count(packed: V2PackedChunk, cfg: SegmentationConfig) -> int:
    """
    Strict token count for v4 model input (embedding prefix + segment body), matching emit/split budget.
    """
    segment_text = "\n\n".join(
        _render_v2_part(part, include_subheading=False) for part in packed.parts
    ).strip()
    embedding_text = _v4_embedding_prefix_for_packed(packed, cfg)
    combined = combined_v4_embed_input(embedding_text, segment_text)
    return _count_tokens(combined, cfg, strict=True)


def _emit_chunk(
    *,
    page_id: int,
    strategy: str,
    heading_path: str | None,
    chunk_index: int,
    chunk_blocks: list[Block],
    cfg: SegmentationConfig,
) -> Segment:
    """Construct one Segment object from collected chunk blocks."""
    segment_text = "\n\n".join(block.text for block in chunk_blocks).strip()
    segment_type = _segment_type_for_chunk(chunk_blocks)
    embedding_text = _build_embedding_text(
        heading_path=heading_path,
        segment_type=segment_type,
        body_text=segment_text,
        cfg=cfg,
        include_context_prefix=True,
    )
    return Segment(
        page_id=page_id,
        chunk_index=chunk_index,
        strategy=strategy,
        heading_path=heading_path,
        merge_group_parent=None,
        segment_type=segment_type,
        segment_text=segment_text,
        embedding_text=embedding_text,
        token_estimate=_estimate_tokens(embedding_text),
        char_count=len(embedding_text),
    )


def _segment_page_v1(*, page_id: int, cleaned_content: str, strategy: str, cfg: SegmentationConfig) -> list[Segment]:
    """Original v1 segmentation flow (kept unchanged for A/B comparison)."""
    parsed = _split_cleaned_content_into_blocks(cleaned_content)
    if not parsed:
        return []

    segments: list[Segment] = []
    chunk_index = 0
    for heading_path, section_blocks in _iter_sections(parsed, cfg):
        prepared = _bundle_consecutive_list_items(section_blocks, cfg)
        cap_safe_blocks: list[Block] = []
        for block in prepared:
            cap_safe_blocks.extend(_split_oversize_block(block, cfg.hard_cap_tokens))

        current_blocks: list[Block] = []
        current_tokens = 0
        for block in cap_safe_blocks:
            would_exceed = (
                current_blocks
                and current_tokens + block.token_estimate > cfg.target_max_tokens
            )
            if would_exceed:
                segments.append(
                    _emit_chunk(
                        page_id=page_id,
                        strategy=strategy,
                        heading_path=heading_path,
                        chunk_index=chunk_index,
                        chunk_blocks=current_blocks,
                        cfg=cfg,
                    )
                )
                chunk_index += 1
                # NOTE: overlap is intentionally section-local to avoid cross-heading bleed.
                overlap = current_blocks[-cfg.overlap_blocks_when_split :] if cfg.overlap_blocks_when_split > 0 else []
                current_blocks = list(overlap)
                current_tokens = sum(item.token_estimate for item in current_blocks)

            current_blocks.append(block)
            current_tokens += block.token_estimate

        if current_blocks:
            segments.append(
                _emit_chunk(
                    page_id=page_id,
                    strategy=strategy,
                    heading_path=heading_path,
                    chunk_index=chunk_index,
                    chunk_blocks=current_blocks,
                    cfg=cfg,
                )
            )
            chunk_index += 1

    return segments


def _build_v2_section_units(
    parsed: list[Block], cfg: SegmentationConfig, *, v4_combined_budget: bool = False
) -> list[V2SectionUnit]:
    """Build section-local units consumed by v2 collapse and split stages."""
    units: list[V2SectionUnit] = []
    for heading_path, section_blocks in _iter_sections(parsed, cfg):
        prepared = _bundle_consecutive_list_items(section_blocks, cfg)
        if not prepared:
            continue
        section_text = "\n\n".join(block.text for block in prepared).strip()
        if not section_text:
            continue
        segment_type = _segment_type_for_chunk(prepared)
        if v4_combined_budget:
            prefix = _build_v4_embedding_prefix(
                heading_path,
                segment_type,
                cfg,
                merged_section_titles=(),
            )
            token_total = _count_tokens(
                combined_v4_embed_input(prefix, section_text),
                cfg,
                strict=True,
            )
        else:
            token_total = _count_tokens(
                _render_v2_part(
                    V2ChunkPart(
                        heading_title=_last_heading_title(heading_path, cfg),
                        text=section_text,
                        segment_type=segment_type,
                        tokens=0,
                    ),
                    include_subheading=True,
                ),
                cfg,
                strict=True,
            )
        units.append(
            V2SectionUnit(
                full_heading_path=heading_path,
                parent_heading_path=_parent_heading_path(heading_path),
                heading_title=_last_heading_title(heading_path, cfg),
                text=section_text,
                segment_type=segment_type,
                tokens=token_total,
            )
        )
    return units


def _collapse_v2_small_subsections(units: list[V2SectionUnit], cfg: SegmentationConfig) -> list[V2PackedChunk]:
    """Stage A: merge sibling small subsections under same parent heading."""
    packed: list[V2PackedChunk] = []
    active_parent: str | None = None
    active_parts: list[V2ChunkPart] = []
    active_tokens = 0

    def flush_active() -> None:
        nonlocal active_parent, active_parts, active_tokens
        if active_parts:
            packed.append(V2PackedChunk(heading_path=active_parent, parts=list(active_parts)))
        active_parent = None
        active_parts = []
        active_tokens = 0

    for unit in units:
        is_small_unit = unit.tokens <= cfg.v2_small_subsection_max_tokens
        if unit.tokens >= cfg.v2_collapse_upper_bound_tokens:
            flush_active()
            packed.append(
                V2PackedChunk(
                    heading_path=unit.full_heading_path,
                    parts=[
                        V2ChunkPart(
                            heading_title=unit.heading_title,
                            text=unit.text,
                            segment_type=unit.segment_type,
                            tokens=unit.tokens,
                        )
                    ],
                )
            )
            continue

        can_extend_active = (
            active_parts
            and active_parent == unit.parent_heading_path
            and active_tokens + unit.tokens <= cfg.v2_collapse_upper_bound_tokens
        )
        should_start_or_extend_collapse = is_small_unit or bool(active_parts)

        if should_start_or_extend_collapse and can_extend_active:
            active_parts.append(
                V2ChunkPart(
                    heading_title=unit.heading_title,
                    text=unit.text,
                    segment_type=unit.segment_type,
                    tokens=unit.tokens,
                )
            )
            active_tokens += unit.tokens
            continue

        flush_active()
        if should_start_or_extend_collapse:
            active_parent = unit.parent_heading_path
            active_parts = [
                V2ChunkPart(
                    heading_title=unit.heading_title,
                    text=unit.text,
                    segment_type=unit.segment_type,
                    tokens=unit.tokens,
                )
            ]
            active_tokens = unit.tokens
            continue

        packed.append(
            V2PackedChunk(
                heading_path=unit.full_heading_path,
                parts=[
                    V2ChunkPart(
                        heading_title=unit.heading_title,
                        text=unit.text,
                        segment_type=unit.segment_type,
                        tokens=unit.tokens,
                    )
                ],
            )
        )

    flush_active()
    return packed


def _part_from_unit(unit: V2SectionUnit) -> V2ChunkPart:
    """Create a reusable chunk part payload from one section unit."""
    return V2ChunkPart(
        heading_title=unit.heading_title,
        text=unit.text,
        segment_type=unit.segment_type,
        tokens=unit.tokens,
        source_heading_path=unit.full_heading_path,
    )


def _pack_v3_by_parent_greedy(units: list[V2SectionUnit], cfg: SegmentationConfig) -> list[V2PackedChunk]:
    """Stage A(v3): greedily pack consecutive sibling units under a soft token target."""
    packed: list[V2PackedChunk] = []
    active_parent: str | None = None
    active_parts: list[V2ChunkPart] = []
    active_tokens = 0

    def flush_active() -> None:
        """Flush current active group while preserving provenance for singletons."""
        nonlocal active_parent, active_parts, active_tokens
        if not active_parts:
            return
        if len(active_parts) == 1 and active_parts[0].source_heading_path:
            chunk_heading_path = active_parts[0].source_heading_path
        else:
            chunk_heading_path = active_parent
        packed.append(
            V2PackedChunk(
                heading_path=chunk_heading_path,
                parts=list(active_parts),
                merge_group_path=active_parent,
            )
        )
        active_parent = None
        active_parts = []
        active_tokens = 0

    for unit in units:
        unit_part = _part_from_unit(unit)
        # NOTE: Oversized units are emitted as standalone chunks and handled by split stage.
        if unit.tokens >= cfg.v3_hard_cap_tokens:
            flush_active()
            packed.append(
                V2PackedChunk(
                    heading_path=unit.full_heading_path,
                    parts=[unit_part],
                    merge_group_path=unit.parent_heading_path,
                )
            )
            continue

        if not active_parts:
            active_parent = unit.parent_heading_path
            active_parts = [unit_part]
            active_tokens = unit.tokens
            continue

        same_parent = active_parent == unit.parent_heading_path
        fits_soft_target = (active_tokens + unit.tokens) <= cfg.v3_soft_target_tokens
        if same_parent and fits_soft_target:
            active_parts.append(unit_part)
            active_tokens += unit.tokens
            continue

        flush_active()
        active_parent = unit.parent_heading_path
        active_parts = [unit_part]
        active_tokens = unit.tokens

    flush_active()
    return packed


def _render_v2_part(part: V2ChunkPart, *, include_subheading: bool) -> str:
    """Render one child subsection contribution inside a collapsed segment."""
    if include_subheading and part.heading_title.strip():
        return f"### {part.heading_title}\n{part.text}".strip()
    return part.text.strip()


def _segment_type_from_parts(parts: list[V2ChunkPart]) -> str:
    """Resolve segment type for one packed chunk from its composing part types."""
    segment_type = "mixed"
    part_types = {part.segment_type for part in parts}
    if len(part_types) == 1:
        only = next(iter(part_types))
        if only in {"prose", "code_block", "table_rows", "list_bundle"}:
            segment_type = only
    return segment_type


def _estimate_packed_tokens(packed: V2PackedChunk) -> int:
    """Estimate chunk token budget from already-counted strict part token values."""
    return sum(max(0, part.tokens) for part in packed.parts)


def _merge_v3_packed_chunks(left: V2PackedChunk, right: V2PackedChunk) -> V2PackedChunk:
    """Merge two neighboring packed chunks while preserving heading provenance."""
    merged_parts = list(left.parts) + list(right.parts)
    source_paths = {
        part.source_heading_path for part in merged_parts if part.source_heading_path
    }
    if len(source_paths) == 1:
        heading_path = next(iter(source_paths))
    else:
        heading_path = left.merge_group_path or right.merge_group_path or left.heading_path
    merge_group = left.merge_group_path or right.merge_group_path or _parent_heading_path(heading_path)
    nested_scopes = _union_v4_nested_scope_paths(
        left.v4_nested_scope_paths, right.v4_nested_scope_paths
    )
    return V2PackedChunk(
        heading_path=heading_path,
        parts=merged_parts,
        merge_group_path=merge_group,
        v4_nested_scope_paths=nested_scopes,
    )


def _repair_v3_underfilled_chunks(
    packed_chunks: list[V2PackedChunk],
    cfg: SegmentationConfig,
    *,
    v4_combined_repair_budget: bool = False,
) -> list[V2PackedChunk]:
    """Stage C(v3): merge tiny chunks with adjacent siblings when safe and deterministic."""

    def _merged_fits_hard_cap(left: V2PackedChunk, right: V2PackedChunk) -> bool:
        if v4_combined_repair_budget:
            merged = _merge_v3_packed_chunks(left, right)
            return _v4_packed_combined_token_count(merged, cfg) <= cfg.v3_hard_cap_tokens
        return _estimate_packed_tokens(left) + _estimate_packed_tokens(right) <= cfg.v3_hard_cap_tokens

    repaired = list(packed_chunks)
    if not repaired:
        return repaired

    for _ in range(max(1, cfg.v3_repair_max_passes)):
        changed = False
        i = 0
        while i < len(repaired):
            current = repaired[i]
            current_tokens = _estimate_packed_tokens(current)
            if current_tokens >= cfg.v3_min_chunk_tokens:
                i += 1
                continue

            merged = False
            prev_idx = i - 1
            if prev_idx >= 0:
                prev_chunk = repaired[prev_idx]
                same_group = prev_chunk.merge_group_path == current.merge_group_path
                fits_cap = _merged_fits_hard_cap(prev_chunk, current)
                if same_group and fits_cap:
                    repaired[prev_idx] = _merge_v3_packed_chunks(prev_chunk, current)
                    repaired.pop(i)
                    changed = True
                    merged = True
                    i = max(0, prev_idx)
            if merged:
                continue

            next_idx = i + 1
            if next_idx < len(repaired):
                next_chunk = repaired[next_idx]
                same_group = next_chunk.merge_group_path == current.merge_group_path
                fits_cap = _merged_fits_hard_cap(current, next_chunk)
                if same_group and fits_cap:
                    repaired[i] = _merge_v3_packed_chunks(current, next_chunk)
                    repaired.pop(next_idx)
                    changed = True
                    continue
            i += 1
        if not changed:
            break
    return repaired


def _consolidate_v4_merge_group_neighbors(
    packed_chunks: list[V2PackedChunk],
    cfg: SegmentationConfig,
    *,
    hard_cap_tokens: int | None = None,
) -> list[V2PackedChunk]:
    """
    v4-only post-split pass: greedily merge adjacent chunks sharing merge_group_path while true
    combined v4 embed input stays under the hard cap.
    """
    cap = hard_cap_tokens if hard_cap_tokens is not None else cfg.v3_hard_cap_tokens
    out: list[V2PackedChunk] = []
    i = 0
    n = len(packed_chunks)
    while i < n:
        acc = packed_chunks[i]
        j = i
        while j + 1 < n:
            nxt = packed_chunks[j + 1]
            mg = acc.merge_group_path
            if mg is None or mg != nxt.merge_group_path:
                break
            merged = _merge_v3_packed_chunks(acc, nxt)
            if _v4_packed_combined_token_count(merged, cfg) <= cap:
                acc = merged
                j += 1
            else:
                break
        out.append(acc)
        i = j + 1
    return out


def _surface_v4_merge_groups_lonely_children(
    packed_chunks: list[V2PackedChunk],
    _cfg: SegmentationConfig,
) -> list[V2PackedChunk]:
    """
    Promote merge_group_path one level toward parent when this chunk is the only adjacent
    occupant of its merge group and that parent matches a neighbour's merge_group_path.
    Records the previous merge group string on v4_nested_scope_paths for Nested_scope prefix lines.
    """
    out: list[V2PackedChunk] = []
    n = len(packed_chunks)
    for i, chunk in enumerate(packed_chunks):
        mg = chunk.merge_group_path
        if mg is None:
            out.append(chunk)
            continue
        parent = _parent_heading_path(mg)
        if parent is None or parent == mg:
            out.append(chunk)
            continue
        prev_mg = packed_chunks[i - 1].merge_group_path if i > 0 else None
        next_mg = packed_chunks[i + 1].merge_group_path if i + 1 < n else None
        lonely_prev = i == 0 or prev_mg != mg
        lonely_next = i == n - 1 or next_mg != mg
        if not (lonely_prev and lonely_next):
            out.append(chunk)
            continue
        aligned = (prev_mg is not None and parent == prev_mg) or (
            next_mg is not None and parent == next_mg
        )
        if not aligned:
            out.append(chunk)
            continue
        new_scopes = _union_v4_nested_scope_paths(chunk.v4_nested_scope_paths, (mg,))
        out.append(
            replace(
                chunk,
                merge_group_path=parent,
                v4_nested_scope_paths=new_scopes,
            )
        )
    return out


def _split_v2_text_by_token_cap(
    text: str,
    cfg: SegmentationConfig,
    *,
    kind_hint: str,
    hard_cap_tokens: int | None = None,
) -> list[str]:
    """Split text by cap-aware fallbacks while preserving structure as much as possible."""
    cap = hard_cap_tokens if hard_cap_tokens is not None else cfg.v2_hard_cap_tokens
    if _count_tokens(text, cfg, strict=True) <= cap:
        return [text]

    def force_char_splits(value: str) -> list[str]:
        """Split extremely long continuous strings (e.g. URLs) by char spans."""
        if not value:
            return []
        if _count_tokens(value, cfg, strict=True) <= cap:
            return [value]
        pieces: list[str] = []
        start = 0
        while start < len(value):
            low = start + 1
            high = len(value)
            best = low
            while low <= high:
                mid = (low + high) // 2
                candidate = value[start:mid]
                if not candidate:
                    low = mid + 1
                    continue
                if _count_tokens(candidate, cfg, strict=True) <= cap:
                    best = mid
                    low = mid + 1
                else:
                    high = mid - 1
            piece = value[start:best].strip()
            if piece:
                pieces.append(piece)
            start = max(best, start + 1)
        return pieces

    # NOTE: Keep line boundaries for code/table-like chunks whenever possible.
    if kind_hint in {"code_block", "table_rows"}:
        lines = [line for line in text.splitlines() if line.strip()]
        out: list[str] = []
        current: list[str] = []
        for line in lines:
            candidate = "\n".join(current + [line]).strip()
            if current and _count_tokens(candidate, cfg, strict=True) > cap:
                out.append("\n".join(current).strip())
                current = [line]
            else:
                current.append(line)
        if current:
            out.append("\n".join(current).strip())
        normalized = [chunk for chunk in out if chunk]
        if all(_count_tokens(chunk, cfg, strict=True) <= cap for chunk in normalized):
            return normalized
        refined: list[str] = []
        for chunk in normalized:
            if _count_tokens(chunk, cfg, strict=True) <= cap:
                refined.append(chunk)
            else:
                # NOTE: Some logs/CSV rows are single lines that still exceed cap; use generic fallback.
                refined.extend(
                    _split_v2_text_by_token_cap(
                        chunk,
                        cfg,
                        kind_hint="prose",
                        hard_cap_tokens=cap,
                    )
                )
        return refined

    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    if len(paragraphs) > 1:
        out: list[str] = []
        current: list[str] = []
        for paragraph in paragraphs:
            candidate = "\n\n".join(current + [paragraph]).strip()
            if current and _count_tokens(candidate, cfg, strict=True) > cap:
                out.append("\n\n".join(current).strip())
                current = [paragraph]
            else:
                current.append(paragraph)
        if current:
            out.append("\n\n".join(current).strip())
        normalized = [chunk for chunk in out if chunk]
        if all(_count_tokens(chunk, cfg, strict=True) <= cap for chunk in normalized):
            return normalized
        refined: list[str] = []
        for chunk in normalized:
            if _count_tokens(chunk, cfg, strict=True) <= cap:
                refined.append(chunk)
            else:
                refined.extend(
                    _split_v2_text_by_token_cap(
                        chunk,
                        cfg,
                        kind_hint=kind_hint,
                        hard_cap_tokens=cap,
                    )
                )
        return refined

    # Sentence fallback.
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", text) if s.strip()]
    if len(sentences) > 1:
        out: list[str] = []
        current: list[str] = []
        for sentence in sentences:
            candidate = " ".join(current + [sentence]).strip()
            if current and _count_tokens(candidate, cfg, strict=True) > cap:
                out.append(" ".join(current).strip())
                current = [sentence]
            else:
                current.append(sentence)
        if current:
            out.append(" ".join(current).strip())
        normalized = [chunk for chunk in out if chunk]
        if all(_count_tokens(chunk, cfg, strict=True) <= cap for chunk in normalized):
            return normalized
        refined: list[str] = []
        for chunk in normalized:
            if _count_tokens(chunk, cfg, strict=True) <= cap:
                refined.append(chunk)
            else:
                refined.extend(
                    _split_v2_text_by_token_cap(
                        chunk,
                        cfg,
                        kind_hint=kind_hint,
                        hard_cap_tokens=cap,
                    )
                )
        return refined

    # Last resort: strict token windows by words.
    words = text.split()
    out: list[str] = []
    start = 0
    while start < len(words):
        low = start + 1
        high = len(words)
        best = low
        while low <= high:
            mid = (low + high) // 2
            candidate = " ".join(words[start:mid]).strip()
            if not candidate:
                low = mid + 1
                continue
            if _count_tokens(candidate, cfg, strict=True) <= cap:
                best = mid
                low = mid + 1
            else:
                high = mid - 1
        chunk = " ".join(words[start:best]).strip()
        if chunk:
            if _count_tokens(chunk, cfg, strict=True) <= cap:
                out.append(chunk)
            else:
                out.extend(force_char_splits(chunk))
        start = max(best, start + 1)
    normalized = [chunk for chunk in out if chunk]
    if all(_count_tokens(chunk, cfg, strict=True) <= cap for chunk in normalized):
        return normalized
    fully_enforced: list[str] = []
    for chunk in normalized:
        if _count_tokens(chunk, cfg, strict=True) <= cap:
            fully_enforced.append(chunk)
        else:
            fully_enforced.extend(force_char_splits(chunk))
    return [chunk for chunk in fully_enforced if chunk]


def _split_v2_packed_chunks(
    packed_chunks: list[V2PackedChunk],
    cfg: SegmentationConfig,
    *,
    hard_cap_tokens: int | None = None,
    include_subheadings: bool = True,
    include_context_prefix_in_budget: bool = False,
    v4_embed_text_split: bool = False,
) -> list[V2PackedChunk]:
    """Stage B: enforce hard cap using boundary-priority splitting."""
    cap = hard_cap_tokens if hard_cap_tokens is not None else cfg.v2_hard_cap_tokens
    out: list[V2PackedChunk] = []
    for packed in packed_chunks:
        rendered_parts = [
            _render_v2_part(part, include_subheading=include_subheadings)
            for part in packed.parts
        ]

        def _v4_prefix_for_parts(candidate_parts: list[V2ChunkPart], _: str) -> str:
            return _v4_embedding_prefix_for_packed(
                replace(packed, parts=list(candidate_parts)),
                cfg,
            )

        def budget_text_for(
            candidate_parts: list[V2ChunkPart], candidate_rendered_parts: list[str]
        ) -> str:
            if v4_embed_text_split and include_context_prefix_in_budget:
                stype = _segment_type_from_parts(candidate_parts)
                body_text = "\n\n".join(
                    _render_v2_part(part, include_subheading=False) for part in candidate_parts
                ).strip()
                prefix = _v4_prefix_for_parts(candidate_parts, stype)
                return combined_v4_embed_input(prefix, body_text)
            body_text = "\n\n".join(candidate_rendered_parts).strip()
            return _build_embedding_text(
                heading_path=packed.heading_path,
                segment_type=_segment_type_from_parts(candidate_parts),
                body_text=body_text,
                cfg=cfg,
                include_context_prefix=include_context_prefix_in_budget,
            )

        if v4_embed_text_split and include_context_prefix_in_budget:
            rendered_tokens = [
                _count_tokens(
                    combined_v4_embed_input(
                        _v4_embedding_prefix_for_packed(replace(packed, parts=[part]), cfg),
                        _render_v2_part(part, include_subheading=False),
                    ),
                    cfg,
                    strict=True,
                )
                for part in packed.parts
            ]
        else:
            rendered_tokens = [
                _count_tokens(budget_text_for([part], [rendered_text]), cfg, strict=True)
                for part, rendered_text in zip(packed.parts, rendered_parts, strict=True)
            ]
        if v4_embed_text_split and include_context_prefix_in_budget:
            full_within_cap = _v4_packed_combined_token_count(packed, cfg) <= cap
        else:
            full_within_cap = (
                _count_tokens(budget_text_for(packed.parts, rendered_parts), cfg, strict=True) <= cap
            )
        if full_within_cap:
            out.append(packed)
            continue

        # Priority 1: split by child subsection boundaries.
        if len(packed.parts) > 1:
            current_parts: list[V2ChunkPart] = []
            current_rendered_parts: list[str] = []
            for part, rendered_text, part_tokens in zip(packed.parts, rendered_parts, rendered_tokens):
                if part_tokens > cap:
                    if current_parts:
                        out.append(replace(packed, parts=list(current_parts)))
                        current_parts = []
                        current_rendered_parts = []
                    part_prefix_token_overhead = 0
                    if include_context_prefix_in_budget:
                        if v4_embed_text_split:
                            part_pref = _v4_embedding_prefix_for_packed(
                                replace(packed, parts=[part]),
                                cfg,
                            )
                            part_prefix_token_overhead = _count_tokens(
                                combined_v4_embed_input(part_pref, ""),
                                cfg,
                                strict=True,
                            )
                        else:
                            empty_wrapped = _build_embedding_text(
                                heading_path=packed.heading_path,
                                segment_type=part.segment_type,
                                body_text="",
                                cfg=cfg,
                                include_context_prefix=True,
                            )
                            part_prefix_token_overhead = _count_tokens(
                                empty_wrapped, cfg, strict=True
                            )
                    split_cap = max(1, cap - part_prefix_token_overhead)
                    part_body = (
                        _render_v2_part(part, include_subheading=False)
                        if v4_embed_text_split
                        else rendered_text
                    )
                    for text in _split_v2_text_by_token_cap(
                        part_body,
                        cfg,
                        kind_hint=part.segment_type,
                        hard_cap_tokens=split_cap,
                    ):
                        out.append(
                            replace(
                                packed,
                                parts=[
                                    V2ChunkPart(
                                        heading_title="",
                                        text=text,
                                        segment_type=part.segment_type,
                                        tokens=_count_tokens(text, cfg, strict=True),
                                        source_heading_path=part.source_heading_path,
                                    )
                                ],
                            )
                        )
                    continue
                candidate_parts = current_parts + [part]
                candidate_rendered = current_rendered_parts + [rendered_text]
                if v4_embed_text_split and include_context_prefix_in_budget:
                    candidate_packed = replace(packed, parts=list(candidate_parts))
                    over_cap = _v4_packed_combined_token_count(candidate_packed, cfg) > cap
                else:
                    candidate_budget_text = budget_text_for(candidate_parts, candidate_rendered)
                    over_cap = _count_tokens(candidate_budget_text, cfg, strict=True) > cap
                if current_parts and over_cap:
                    out.append(replace(packed, parts=list(current_parts)))
                    current_parts = [part]
                    current_rendered_parts = [rendered_text]
                else:
                    current_parts.append(part)
                    current_rendered_parts.append(rendered_text)
            if current_parts:
                out.append(
                    replace(
                        packed,
                        parts=list(current_parts),
                    )
                )
            continue

        # Priorities 2-4: split one large part by paragraph/sentence/window fallback.
        single_part = packed.parts[0]
        single_part_rendered = _render_v2_part(
            single_part, include_subheading=include_subheadings
        )
        single_body = (
            _render_v2_part(single_part, include_subheading=False)
            if v4_embed_text_split
            else single_part_rendered
        )
        single_prefix_token_overhead = 0
        if include_context_prefix_in_budget:
            if v4_embed_text_split:
                single_pref = _v4_embedding_prefix_for_packed(
                    replace(packed, parts=[single_part]),
                    cfg,
                )
                single_prefix_token_overhead = _count_tokens(
                    combined_v4_embed_input(single_pref, ""),
                    cfg,
                    strict=True,
                )
            else:
                single_prefix_token_overhead = _count_tokens(
                    _build_embedding_text(
                        heading_path=packed.heading_path,
                        segment_type=single_part.segment_type,
                        body_text="",
                        cfg=cfg,
                        include_context_prefix=True,
                    ),
                    cfg,
                    strict=True,
                )
        split_cap = max(1, cap - single_prefix_token_overhead)
        chunks = _split_v2_text_by_token_cap(
            single_body,
            cfg,
            kind_hint=single_part.segment_type,
            hard_cap_tokens=split_cap,
        )
        for text in chunks:
            out.append(
                replace(
                    packed,
                    parts=[
                        V2ChunkPart(
                            heading_title="",
                            text=text,
                            segment_type=single_part.segment_type,
                            tokens=_count_tokens(text, cfg, strict=True),
                            source_heading_path=single_part.source_heading_path,
                        )
                    ],
                )
            )
    return out


def _segment_page_v2(*, page_id: int, cleaned_content: str, strategy: str, cfg: SegmentationConfig) -> list[Segment]:
    """v2 strategy: collapse small subsections first, then split oversized chunks."""
    parsed = _split_cleaned_content_into_blocks(cleaned_content)
    if not parsed:
        return []

    units = _build_v2_section_units(parsed, cfg)
    packed = _collapse_v2_small_subsections(units, cfg)
    final_chunks = _split_v2_packed_chunks(
        packed,
        cfg,
        include_subheadings=True,
        include_context_prefix_in_budget=False,
    )

    segments: list[Segment] = []
    for chunk_index, packed_chunk in enumerate(final_chunks):
        segment_text = "\n\n".join(
            _render_v2_part(part, include_subheading=False) for part in packed_chunk.parts
        ).strip()
        segment_type = _segment_type_from_parts(packed_chunk.parts)
        embedding_body = "\n\n".join(
            _render_v2_part(part, include_subheading=True) for part in packed_chunk.parts
        ).strip()
        embedding_text = _build_embedding_text(
            heading_path=packed_chunk.heading_path,
            segment_type=segment_type,
            body_text=embedding_body,
            cfg=cfg,
            include_context_prefix=False,
        )
        segments.append(
            Segment(
                page_id=page_id,
                chunk_index=chunk_index,
                strategy=strategy,
                heading_path=packed_chunk.heading_path,
                merge_group_parent=packed_chunk.merge_group_path,
                segment_type=segment_type,
                segment_text=segment_text,
                embedding_text=embedding_text,
                token_estimate=_count_tokens(embedding_text, cfg, strict=True),
                char_count=len(embedding_text),
            )
        )
    return segments


def _segment_page_v3(*, page_id: int, cleaned_content: str, strategy: str, cfg: SegmentationConfig) -> list[Segment]:
    """v3 strategy: greedy parent packing, hard-cap split, then underfilled-tail repair."""
    parsed = _split_cleaned_content_into_blocks(cleaned_content)
    if not parsed:
        return []

    units = _build_v2_section_units(parsed, cfg)
    chunks = _pack_v3_by_parent_greedy(units, cfg)
    for _ in range(max(1, cfg.v3_refine_rounds)):
        chunks = _split_v2_packed_chunks(
            chunks,
            cfg,
            hard_cap_tokens=cfg.v3_hard_cap_tokens,
            include_subheadings=True,
            include_context_prefix_in_budget=False,
        )
        chunks = _repair_v3_underfilled_chunks(chunks, cfg)
    final_chunks = _split_v2_packed_chunks(
        chunks,
        cfg,
        hard_cap_tokens=cfg.v3_hard_cap_tokens,
        include_subheadings=True,
        include_context_prefix_in_budget=False,
    )

    segments: list[Segment] = []
    for chunk_index, packed_chunk in enumerate(final_chunks):
        segment_text = "\n\n".join(
            _render_v2_part(part, include_subheading=False) for part in packed_chunk.parts
        ).strip()
        segment_type = _segment_type_from_parts(packed_chunk.parts)
        embedding_body = "\n\n".join(
            _render_v2_part(part, include_subheading=True) for part in packed_chunk.parts
        ).strip()
        embedding_text = _build_embedding_text(
            heading_path=packed_chunk.heading_path,
            segment_type=segment_type,
            body_text=embedding_body,
            cfg=cfg,
            include_context_prefix=False,
        )
        segments.append(
            Segment(
                page_id=page_id,
                chunk_index=chunk_index,
                strategy=strategy,
                heading_path=packed_chunk.heading_path,
                merge_group_parent=packed_chunk.merge_group_path,
                segment_type=segment_type,
                segment_text=segment_text,
                embedding_text=embedding_text,
                token_estimate=_count_tokens(embedding_text, cfg, strict=True),
                char_count=len(embedding_text),
            )
        )
    return segments


def _segment_page_v4(*, page_id: int, cleaned_content: str, strategy: str, cfg: SegmentationConfig) -> list[Segment]:
    """v4 strategy: v3 packing/splitting with clean display text and contextual embedding text."""
    parsed = _split_cleaned_content_into_blocks(cleaned_content)
    if not parsed:
        return []

    units = _build_v2_section_units(parsed, cfg, v4_combined_budget=True)
    chunks = _pack_v3_by_parent_greedy(units, cfg)
    for _ in range(max(1, cfg.v3_refine_rounds)):
        chunks = _split_v2_packed_chunks(
            chunks,
            cfg,
            hard_cap_tokens=cfg.v3_hard_cap_tokens,
            include_subheadings=True,
            include_context_prefix_in_budget=True,
            v4_embed_text_split=True,
        )
        chunks = _repair_v3_underfilled_chunks(chunks, cfg, v4_combined_repair_budget=True)
    final_chunks = _split_v2_packed_chunks(
        chunks,
        cfg,
        hard_cap_tokens=cfg.v3_hard_cap_tokens,
        include_subheadings=True,
        include_context_prefix_in_budget=True,
        v4_embed_text_split=True,
    )
    if cfg.v4_surface_merge_max_iterations <= 0:
        final_chunks = _consolidate_v4_merge_group_neighbors(
            final_chunks, cfg, hard_cap_tokens=cfg.v3_hard_cap_tokens
        )
    else:
        for _ in range(cfg.v4_surface_merge_max_iterations):
            before = final_chunks
            surfaced = _surface_v4_merge_groups_lonely_children(before, cfg)
            merged = _consolidate_v4_merge_group_neighbors(
                surfaced, cfg, hard_cap_tokens=cfg.v3_hard_cap_tokens
            )
            final_chunks = merged
            if surfaced == before and merged == surfaced:
                break

    segments: list[Segment] = []
    for chunk_index, packed_chunk in enumerate(final_chunks):
        segment_type = _segment_type_from_parts(packed_chunk.parts)
        segment_text = "\n\n".join(
            _render_v2_part(part, include_subheading=False) for part in packed_chunk.parts
        ).strip()
        embedding_text = _v4_embedding_prefix_for_packed(packed_chunk, cfg)
        combined = combined_v4_embed_input(embedding_text, segment_text)
        segments.append(
            Segment(
                page_id=page_id,
                chunk_index=chunk_index,
                strategy=strategy,
                heading_path=packed_chunk.heading_path,
                merge_group_parent=packed_chunk.merge_group_path,
                segment_type=segment_type,
                segment_text=segment_text,
                embedding_text=embedding_text,
                token_estimate=_v4_packed_combined_token_count(packed_chunk, cfg),
                char_count=len(combined),
            )
        )
    return segments


def segment_page_cleaned_content(
    *,
    page_id: int,
    cleaned_content: str,
    strategy: str,
    cfg: SegmentationConfig,
) -> list[Segment]:
    """Segment one page using the requested strategy version."""
    if strategy == V3_STRATEGY:
        return _segment_page_v3(
            page_id=page_id,
            cleaned_content=cleaned_content,
            strategy=strategy,
            cfg=cfg,
        )
    if strategy == V4_STRATEGY:
        return _segment_page_v4(
            page_id=page_id,
            cleaned_content=cleaned_content,
            strategy=strategy,
            cfg=cfg,
        )
    if strategy == V2_STRATEGY:
        return _segment_page_v2(
            page_id=page_id,
            cleaned_content=cleaned_content,
            strategy=strategy,
            cfg=cfg,
        )
    return _segment_page_v1(
        page_id=page_id,
        cleaned_content=cleaned_content,
        strategy=strategy,
        cfg=cfg,
    )


def _fetch_candidate_pages(
    conn: Any, *, limit: int | None, page_id: int | None
) -> list[tuple[int, str]]:
    """Load pages eligible for segmentation."""
    sql = """
SELECT p.id, p.cleaned_content
FROM crawldb.page p
WHERE p.cleaned_content IS NOT NULL
  AND LENGTH(TRIM(p.cleaned_content)) > 0
"""
    params: list[Any] = []
    if page_id is not None:
        sql += " AND p.id = %s"
        params.append(page_id)
    sql += " ORDER BY p.id"
    if limit is not None:
        sql += " LIMIT %s"
        params.append(limit)
    rows = conn.execute(sql, params).fetchall()
    return [(int(row[0]), str(row[1])) for row in rows]


def _delete_segments_for_strategy(
    conn: Any, *, strategy: str, page_id: int | None
) -> int:
    """Delete previously generated segments for one strategy (optionally one page)."""
    if page_id is not None:
        result = conn.execute(
            "DELETE FROM crawldb.page_segment WHERE strategy = %s AND page_id = %s",
            (strategy, page_id),
        )
    else:
        result = conn.execute(
            "DELETE FROM crawldb.page_segment WHERE strategy = %s",
            (strategy,),
        )
    return int(result.rowcount or 0)


def _insert_segments(conn: Any, segments: list[Segment]) -> None:
    """Insert or update segment rows in one batched statement."""
    if not segments:
        return
    payload = [
        (
            segment.page_id,
            segment.chunk_index,
            segment.strategy,
            segment.heading_path,
            segment.merge_group_parent,
            segment.segment_type,
            segment.segment_text,
            segment.embedding_text,
            segment.token_estimate,
            segment.char_count,
        )
        for segment in segments
    ]
    with conn.cursor() as cur:
        cur.executemany(
            """
INSERT INTO crawldb.page_segment (
  page_id,
  chunk_index,
  strategy,
  heading_path,
  merge_group_parent,
  segment_type,
  segment_text,
  embedding_text,
  token_estimate,
  char_count
)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (page_id, strategy, chunk_index)
DO UPDATE SET
  heading_path = EXCLUDED.heading_path,
  merge_group_parent = EXCLUDED.merge_group_parent,
  segment_type = EXCLUDED.segment_type,
  segment_text = EXCLUDED.segment_text,
  embedding_text = EXCLUDED.embedding_text,
  token_estimate = EXCLUDED.token_estimate,
  char_count = EXCLUDED.char_count,
  created_at = NOW()
""",
            payload,
        )


def _percentile(values: list[int], pct: float) -> int:
    """Compute integer percentile with nearest-rank semantics."""
    if not values:
        return 0
    ordered = sorted(values)
    rank = max(1, int(round((pct / 100.0) * len(ordered))))
    return ordered[min(len(ordered) - 1, rank - 1)]


def _log_quality_summary(stats: RunStats) -> None:
    """Log quality-oriented summary metrics for acceptance checks."""
    if stats.pages_read == 0:
        logging.warning("No candidate pages found for segmentation.")
        return

    coverage = (100.0 * stats.pages_with_segments) / max(1, stats.pages_read)
    heading_coverage = (100.0 * stats.heading_path_non_null_segments) / max(1, stats.total_segments)
    chunk_min = min(stats.chunks_per_page) if stats.chunks_per_page else 0
    chunk_median = (
        int(statistics.median(stats.chunks_per_page)) if stats.chunks_per_page else 0
    )
    chunk_p95 = _percentile(stats.chunks_per_page, 95.0)
    token_min = min(stats.tokens_per_segment) if stats.tokens_per_segment else 0
    token_median = (
        int(statistics.median(stats.tokens_per_segment)) if stats.tokens_per_segment else 0
    )
    token_p95 = _percentile(stats.tokens_per_segment, 95.0)

    logging.info(
        "quality summary: strategy=%s pages_read=%s pages_with_segments=%s coverage=%.1f%% segments=%s",
        stats.strategy,
        stats.pages_read,
        stats.pages_with_segments,
        coverage,
        stats.total_segments,
    )
    logging.info(
        "chunks/page: min=%s median=%s p95=%s | tokens/chunk: min=%s median=%s p95=%s",
        chunk_min,
        chunk_median,
        chunk_p95,
        token_min,
        token_median,
        token_p95,
    )
    logging.info("heading_path non-null ratio: %.1f%%", heading_coverage)
    if stats.total_segments > 0:
        short20_ratio = 100.0 * stats.short_chunks_lt20 / stats.total_segments
        short40_ratio = 100.0 * stats.short_chunks_lt40 / stats.total_segments
        overflow_ratio = 100.0 * stats.overflow_chunks_gt_cap / stats.total_segments
        logging.info(
            "micro/overflow: <20=%s (%.1f%%) <40=%s (%.1f%%) >cap(%s)=%s (%.1f%%)",
            stats.short_chunks_lt20,
            short20_ratio,
            stats.short_chunks_lt40,
            short40_ratio,
            stats.hard_cap_tokens,
            stats.overflow_chunks_gt_cap,
            overflow_ratio,
        )

    # NOTE: These warnings enforce the acceptance criteria as practical guardrails.
    if coverage < 95.0:
        logging.warning(
            "Coverage below target (%.1f%% < 95%%). Check extraction gaps or segmentation errors.",
            coverage,
        )
    if token_median < 120:
        logging.warning(
            "Median chunk size is low (%s tokens). Potential over-fragmentation.",
            token_median,
        )
    if token_p95 > 450:
        logging.warning(
            "95th percentile chunk size is high (%s tokens). Consider stricter split rules.",
            token_p95,
        )
    if stats.overflow_chunks_gt_cap > 0:
        logging.warning(
            "Found %s chunks above configured hard cap (%s).",
            stats.overflow_chunks_gt_cap,
            stats.hard_cap_tokens,
        )


def run_segmentation(
    *,
    strategy: str = DEFAULT_STRATEGY,
    limit: int | None = None,
    page_id: int | None = None,
    batch_size: int = 200,
    rebuild: bool = False,
    dry_run: bool = False,
    v3_refine_rounds: int | None = None,
    v4_surface_merge_max_iterations: int | None = None,
) -> RunStats:
    """
    Execute full DB segmentation flow.

    Side effects:
    - Reads from `crawldb.page`.
    - Optionally deletes existing `crawldb.page_segment` rows for strategy/page.
    - Optionally inserts or updates segment rows.
    """
    import psycopg

    if batch_size <= 0:
        raise ValueError("batch_size must be > 0")

    cfg = SegmentationConfig(
        v2_target_tokens=int(os.environ.get("PA2_V2_TARGET_TOKENS", "200")),
        v2_collapse_upper_bound_tokens=int(
            os.environ.get("PA2_V2_COLLAPSE_UPPER_BOUND_TOKENS", "220")
        ),
        v2_hard_cap_tokens=int(os.environ.get("PA2_V2_HARD_CAP_TOKENS", "240")),
        v2_small_subsection_max_tokens=int(
            os.environ.get("PA2_V2_SMALL_SUBSECTION_MAX_TOKENS", "40")
        ),
        v2_local_files_only=os.environ.get("PA2_V2_LOCAL_FILES_ONLY", "0") in {"1", "true", "TRUE"},
        v3_soft_target_tokens=int(os.environ.get("PA2_V3_SOFT_TARGET_TOKENS", "200")),
        v3_hard_cap_tokens=int(os.environ.get("PA2_V3_HARD_CAP_TOKENS", "240")),
        v3_min_chunk_tokens=int(os.environ.get("PA2_V3_MIN_CHUNK_TOKENS", "35")),
        v3_repair_max_passes=int(os.environ.get("PA2_V3_REPAIR_MAX_PASSES", "2")),
        v3_refine_rounds=(
            int(os.environ.get("PA2_V3_REFINE_ROUNDS", "1"))
            if v3_refine_rounds is None
            else v3_refine_rounds
        ),
        v4_surface_merge_max_iterations=(
            int(os.environ.get("PA2_V4_SURFACE_MERGE_MAX_ITERATIONS", "5"))
            if v4_surface_merge_max_iterations is None
            else v4_surface_merge_max_iterations
        ),
    )
    stats = RunStats(strategy=strategy)
    if strategy == V2_STRATEGY:
        stats.hard_cap_tokens = cfg.v2_hard_cap_tokens
    elif strategy in {V3_STRATEGY, V4_STRATEGY}:
        stats.hard_cap_tokens = cfg.v3_hard_cap_tokens
    else:
        stats.hard_cap_tokens = cfg.hard_cap_tokens
    conn_kwargs = _resolve_conn_kwargs()
    with psycopg.connect(**conn_kwargs, autocommit=False) as conn:
        conn.execute("SET search_path TO crawldb, public")
        if rebuild and not dry_run:
            deleted = _delete_segments_for_strategy(conn, strategy=strategy, page_id=page_id)
            logging.info("rebuild cleanup: deleted=%s strategy=%s", deleted, strategy)

        pages = _fetch_candidate_pages(conn, limit=limit, page_id=page_id)
        stats.pages_read = len(pages)
        pending: list[Segment] = []
        for candidate_page_id, cleaned_content in pages:
            segments = segment_page_cleaned_content(
                page_id=candidate_page_id,
                cleaned_content=cleaned_content,
                strategy=strategy,
                cfg=cfg,
            )
            if segments:
                stats.pages_with_segments += 1
            stats.chunks_per_page.append(len(segments))
            stats.total_segments += len(segments)
            for segment in segments:
                if segment.heading_path:
                    stats.heading_path_non_null_segments += 1
                stats.tokens_per_segment.append(segment.token_estimate)
                if segment.token_estimate < 20:
                    stats.short_chunks_lt20 += 1
                if segment.token_estimate < 40:
                    stats.short_chunks_lt40 += 1
                if segment.token_estimate > stats.hard_cap_tokens:
                    stats.overflow_chunks_gt_cap += 1
            pending.extend(segments)

            if not dry_run and len(pending) >= batch_size:
                _insert_segments(conn, pending)
                conn.commit()
                pending.clear()

        if not dry_run and pending:
            _insert_segments(conn, pending)
            conn.commit()
            pending.clear()

        if dry_run:
            conn.rollback()

    _log_quality_summary(stats)
    return stats


def _self_test() -> None:
    """Run embedded Strategy C behavior checks without database access."""
    sample = """
[H1] Root title

Intro paragraph about segmentation models and dataset setup.

[H2] Install

- pip install torch

- pip install albumentations

- pip install segmentation-models-pytorch

- pip install opencv-python

- pip install matplotlib

- pip install numpy

- pip install scipy

- pip install pillow

- pip install timm

[H2] Training

```yaml
Train:
  max_epoch: 50
  batch_size: 8
```

Use cosine LR schedule and mixed precision.
""".strip()

    cfg = SegmentationConfig(
        target_min_tokens=20,
        target_max_tokens=40,
        hard_cap_tokens=60,
        list_bundle_min_items=3,
        list_bundle_max_items=4,
    )
    segments = segment_page_cleaned_content(
        page_id=42,
        cleaned_content=sample,
        strategy=DEFAULT_STRATEGY,
        cfg=cfg,
    )

    assert segments, "Expected non-empty segments"
    assert all("[H" not in s.segment_text for s in segments), "Heading markers leaked into segments"
    assert any("Install" in (s.heading_path or "") for s in segments), "Heading path missing"
    assert any(s.segment_type == "list_bundle" for s in segments), "List bundles were not produced"
    assert any("max_epoch" in s.segment_text for s in segments), "Code block content missing"
    assert all(s.chunk_index == i for i, s in enumerate(segments)), "Chunk indices must be contiguous"

    # v3 pass-A regression: if one chunk is full-ish, later medium siblings should still combine.
    v3_cfg = SegmentationConfig(
        v3_soft_target_tokens=200,
        v3_hard_cap_tokens=240,
        v3_min_chunk_tokens=35,
        v3_repair_max_passes=2,
        v3_refine_rounds=1,
        v2_local_files_only=True,
    )
    units = [
        V2SectionUnit(
            full_heading_path="README > Parent > Big",
            parent_heading_path="README > Parent",
            heading_title="Big",
            text="big",
            segment_type="prose",
            tokens=240,
        ),
        V2SectionUnit(
            full_heading_path="README > Parent > MidA",
            parent_heading_path="README > Parent",
            heading_title="MidA",
            text="mida",
            segment_type="prose",
            tokens=50,
        ),
        V2SectionUnit(
            full_heading_path="README > Parent > MidB",
            parent_heading_path="README > Parent",
            heading_title="MidB",
            text="midb",
            segment_type="prose",
            tokens=80,
        ),
    ]
    v3_packed = _pack_v3_by_parent_greedy(units, v3_cfg)
    assert len(v3_packed) == 2, "Expected v3 to keep 240 alone and combine 50+80."
    assert _estimate_packed_tokens(v3_packed[0]) == 240
    assert _estimate_packed_tokens(v3_packed[1]) == 130

    # v3 pass-C regression: tiny tail should merge into adjacent sibling under same parent group.
    tiny_repair_input = [
        V2PackedChunk(
            heading_path="README > Parent > Abstract",
            merge_group_path="README > Parent",
            parts=[
                V2ChunkPart(
                    heading_title="",
                    text="chunk-a",
                    segment_type="prose",
                    tokens=210,
                    source_heading_path="README > Parent > Abstract",
                )
            ],
        ),
        V2PackedChunk(
            heading_path="README > Parent > Abstract",
            merge_group_path="README > Parent",
            parts=[
                V2ChunkPart(
                    heading_title="",
                    text="[Paper] [Code]",
                    segment_type="prose",
                    tokens=8,
                    source_heading_path="README > Parent > Abstract",
                )
            ],
        ),
    ]
    repaired = _repair_v3_underfilled_chunks(tiny_repair_input, v3_cfg)
    assert len(repaired) == 1, "Expected tiny tail to merge with previous sibling."
    assert _estimate_packed_tokens(repaired[0]) == 218, "Unexpected merged token sum."
    assert repaired[0].parts[0].text == "chunk-a"
    assert repaired[0].parts[1].text == "[Paper] [Code]"

    # v4 merge-group consolidation: adjacent chunks with same merge_group_path merge if true v4 budget allows.
    con_parent = "README > ParentDoc"
    consolidate_input = [
        V2PackedChunk(
            heading_path="README > ParentDoc > SubA",
            merge_group_path=con_parent,
            parts=[
                V2ChunkPart(
                    heading_title="SubA",
                    text="one two three",
                    segment_type="prose",
                    tokens=3,
                    source_heading_path="README > ParentDoc > SubA",
                )
            ],
        ),
        V2PackedChunk(
            heading_path="README > ParentDoc > SubB",
            merge_group_path=con_parent,
            parts=[
                V2ChunkPart(
                    heading_title="SubB",
                    text="four five six",
                    segment_type="prose",
                    tokens=3,
                    source_heading_path="README > ParentDoc > SubB",
                )
            ],
        ),
    ]
    consolidated = _consolidate_v4_merge_group_neighbors(consolidate_input, v3_cfg)
    assert len(consolidated) == 1, "Expected merge-group consolidation of two siblings."
    assert _v4_packed_combined_token_count(consolidated[0], v3_cfg) <= v3_cfg.v3_hard_cap_tokens

    # v4 surfacing: lonely deep merge group aligns with neighbour parent; Nested_scope captures promoted path.
    pd_path = "README > ParentDoc"
    leaf_path = "README > ParentDoc > LonelyLeaf"
    surf_triplet = [
        V2PackedChunk(
            heading_path=f"{pd_path} > A",
            merge_group_path=pd_path,
            parts=[
                V2ChunkPart(
                    heading_title="A",
                    text="alpha",
                    segment_type="prose",
                    tokens=5,
                    source_heading_path=f"{pd_path} > A",
                )
            ],
        ),
        V2PackedChunk(
            heading_path=f"{leaf_path} > Body",
            merge_group_path=leaf_path,
            parts=[
                V2ChunkPart(
                    heading_title="Body",
                    text="beta",
                    segment_type="prose",
                    tokens=5,
                    source_heading_path=f"{leaf_path} > Body",
                )
            ],
        ),
        V2PackedChunk(
            heading_path="README > Other > X",
            merge_group_path="README > Other",
            parts=[
                V2ChunkPart(
                    heading_title="X",
                    text="gamma",
                    segment_type="prose",
                    tokens=5,
                    source_heading_path="README > Other > X",
                )
            ],
        ),
    ]
    surfaced_once = _surface_v4_merge_groups_lonely_children(surf_triplet, v3_cfg)
    assert surfaced_once[1].merge_group_path == pd_path
    assert leaf_path in surfaced_once[1].v4_nested_scope_paths
    surf_merged = _consolidate_v4_merge_group_neighbors(surfaced_once, v3_cfg)
    assert len(surf_merged) == 2, "Expected surfacing to enable one consolidation."
    surf_prefix = _v4_embedding_prefix_for_packed(surf_merged[0], v3_cfg)
    assert "Nested_scope:" in surf_prefix
    assert "ParentDoc > LonelyLeaf" in surf_prefix

    # v4 regression: additive embedding_text; model input = prefix + segment_text; hard cap on combined.
    v4_segments = _segment_page_v4(
        page_id=42,
        cleaned_content=sample,
        strategy=V4_STRATEGY,
        cfg=v3_cfg,
    )
    assert v4_segments, "Expected non-empty v4 segments"
    assert all("Context:" not in s.segment_text for s in v4_segments), "V4 segment_text must stay clean."
    assert any("Context:" in s.embedding_text for s in v4_segments), "V4 embedding_text missing context prefix."
    for s in v4_segments:
        combined = combined_v4_embed_input(s.embedding_text, s.segment_text)
        assert _count_tokens(combined, v3_cfg, strict=True) <= v3_cfg.v3_hard_cap_tokens, (
            "V4 combined embed input overflowed hard cap."
        )
    authors_page = """
[H1] README

[H2] Implementations

[H3] Cluster GAN

[H3] Authors

Sudipto Mukherjee, Himanshu Asnani, Eugene Lin, Sreeram Kannan
""".strip()
    v4_authors = _segment_page_v4(
        page_id=43,
        cleaned_content=authors_page,
        strategy=V4_STRATEGY,
        cfg=v3_cfg,
    )
    authors_seg = next(s for s in v4_authors if "Sudipto" in s.segment_text)
    authors_combined = combined_v4_embed_input(authors_seg.embedding_text, authors_seg.segment_text)
    assert "### Authors" not in authors_combined, "V4 must not inject redundant ### leaf heading into combined input."
    assert "Sudipto Mukherjee" not in authors_seg.embedding_text
    assert "Type: authors" in authors_seg.embedding_text

    refs_page = """
[H1] README

[H3] References

- Doe et al., Conference 2020.
- Smith and Jones, Conference 2021.
""".strip()
    v4_refs = _segment_page_v4(
        page_id=44,
        cleaned_content=refs_page,
        strategy=V4_STRATEGY,
        cfg=v3_cfg,
    )
    refs_seg = next(s for s in v4_refs if "Doe et al." in s.segment_text)
    assert "Type: references" in refs_seg.embedding_text

    code_page = """
[H1] README

[H2] Install

```
pip install torch
```
""".strip()
    v4_code = _segment_page_v4(
        page_id=45,
        cleaned_content=code_page,
        strategy=V4_STRATEGY,
        cfg=v3_cfg,
    )
    code_seg = next(s for s in v4_code if "pip install" in s.segment_text)
    assert "Type: code block" in code_seg.embedding_text
    assert "Type: code_block" not in code_seg.embedding_text

    print("segment_cleaned_content self-test: OK")


def _main(argv: list[str] | None = None) -> int:
    """CLI entrypoint for Strategy C segmentation."""
    parser = argparse.ArgumentParser(
        description="Segment crawldb.page.cleaned_content into crawldb.page_segment."
    )
    parser.add_argument(
        "--strategy",
        default=DEFAULT_STRATEGY,
        choices=[DEFAULT_STRATEGY, V2_STRATEGY, V3_STRATEGY, V4_STRATEGY],
        help=f"Strategy label stored in DB (default: {DEFAULT_STRATEGY}).",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Process at most N pages (after DB filters).",
    )
    parser.add_argument(
        "--page-id",
        type=int,
        default=None,
        help="Process only one crawldb.page.id.",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=200,
        help="Insert/update commit threshold in number of segments.",
    )
    parser.add_argument(
        "--rebuild",
        action="store_true",
        help="Delete existing rows for this strategy before inserting.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Compute segments and print quality metrics without DB writes.",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Enable DEBUG logging.",
    )
    parser.add_argument(
        "--v3-refine-rounds",
        type=int,
        default=None,
        help="Number of v3 split/repair refinement rounds before final split.",
    )
    parser.add_argument(
        "--v4-surface-merge-max-iterations",
        type=int,
        default=None,
        help="v4 only: max iterations of surfacing + merge-group consolidation (0 = disable surfacing). "
        "Overrides PA2_V4_SURFACE_MERGE_MAX_ITERATIONS when set.",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run embedded checks and exit.",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )

    if args.self_test:
        _self_test()
        return 0

    stats = run_segmentation(
        strategy=args.strategy,
        limit=args.limit,
        page_id=args.page_id,
        batch_size=args.batch_size,
        rebuild=args.rebuild,
        dry_run=args.dry_run,
        v3_refine_rounds=args.v3_refine_rounds,
        v4_surface_merge_max_iterations=args.v4_surface_merge_max_iterations,
    )
    logging.info(
        "done: pages_read=%s pages_with_segments=%s total_segments=%s strategy=%s dry_run=%s",
        stats.pages_read,
        stats.pages_with_segments,
        stats.total_segments,
        stats.strategy,
        args.dry_run,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
