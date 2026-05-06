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
"""

from __future__ import annotations

import argparse
import logging
import os
import re
import statistics
from functools import lru_cache
from dataclasses import dataclass
from typing import Any, Iterator

HEADING_RE = re.compile(r"^\[H([1-6])\]\s+(.+?)\s*$")
WORDLIKE_RE = re.compile(r"\w+|[^\w\s]", re.UNICODE)
LIST_LINE_RE = re.compile(r"^\s*(?:[-*+]\s+|\d+[.)]\s+)")

DEFAULT_STRATEGY = "heading_structure_v1"
V2_STRATEGY = "heading_structure_v2"


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
    segment_type: str
    segment_text: str
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


@dataclass(frozen=True)
class V2PackedChunk:
    """Intermediate packed chunk before final hard-cap splitting."""

    heading_path: str | None
    parts: list[V2ChunkPart]


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


def _emit_chunk(
    *,
    page_id: int,
    strategy: str,
    heading_path: str | None,
    chunk_index: int,
    chunk_blocks: list[Block],
) -> Segment:
    """Construct one Segment object from collected chunk blocks."""
    segment_text = "\n\n".join(block.text for block in chunk_blocks).strip()
    return Segment(
        page_id=page_id,
        chunk_index=chunk_index,
        strategy=strategy,
        heading_path=heading_path,
        segment_type=_segment_type_for_chunk(chunk_blocks),
        segment_text=segment_text,
        token_estimate=_estimate_tokens(segment_text),
        char_count=len(segment_text),
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
                )
            )
            chunk_index += 1

    return segments


def _build_v2_section_units(parsed: list[Block], cfg: SegmentationConfig) -> list[V2SectionUnit]:
    """Build section-local units consumed by v2 collapse and split stages."""
    units: list[V2SectionUnit] = []
    for heading_path, section_blocks in _iter_sections(parsed, cfg):
        prepared = _bundle_consecutive_list_items(section_blocks, cfg)
        if not prepared:
            continue
        section_text = "\n\n".join(block.text for block in prepared).strip()
        if not section_text:
            continue
        units.append(
            V2SectionUnit(
                full_heading_path=heading_path,
                parent_heading_path=_parent_heading_path(heading_path),
                heading_title=_last_heading_title(heading_path, cfg),
                text=section_text,
                segment_type=_segment_type_for_chunk(prepared),
                tokens=_count_tokens(section_text, cfg, strict=True),
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


def _render_v2_part(part: V2ChunkPart) -> str:
    """Render one child subsection contribution inside a collapsed segment."""
    if part.heading_title.strip():
        return f"### {part.heading_title}\n{part.text}".strip()
    return part.text.strip()


def _split_v2_text_by_token_cap(
    text: str,
    cfg: SegmentationConfig,
    *,
    kind_hint: str,
) -> list[str]:
    """Split text by cap-aware fallbacks while preserving structure as much as possible."""
    if _count_tokens(text, cfg, strict=True) <= cfg.v2_hard_cap_tokens:
        return [text]

    def force_char_splits(value: str) -> list[str]:
        """Split extremely long continuous strings (e.g. URLs) by char spans."""
        if not value:
            return []
        if _count_tokens(value, cfg, strict=True) <= cfg.v2_hard_cap_tokens:
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
                if _count_tokens(candidate, cfg, strict=True) <= cfg.v2_hard_cap_tokens:
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
            if current and _count_tokens(candidate, cfg, strict=True) > cfg.v2_hard_cap_tokens:
                out.append("\n".join(current).strip())
                current = [line]
            else:
                current.append(line)
        if current:
            out.append("\n".join(current).strip())
        normalized = [chunk for chunk in out if chunk]
        if all(_count_tokens(chunk, cfg, strict=True) <= cfg.v2_hard_cap_tokens for chunk in normalized):
            return normalized
        refined: list[str] = []
        for chunk in normalized:
            if _count_tokens(chunk, cfg, strict=True) <= cfg.v2_hard_cap_tokens:
                refined.append(chunk)
            else:
                # NOTE: Some logs/CSV rows are single lines that still exceed cap; use generic fallback.
                refined.extend(
                    _split_v2_text_by_token_cap(
                        chunk,
                        cfg,
                        kind_hint="prose",
                    )
                )
        return refined

    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    if len(paragraphs) > 1:
        out: list[str] = []
        current: list[str] = []
        for paragraph in paragraphs:
            candidate = "\n\n".join(current + [paragraph]).strip()
            if current and _count_tokens(candidate, cfg, strict=True) > cfg.v2_hard_cap_tokens:
                out.append("\n\n".join(current).strip())
                current = [paragraph]
            else:
                current.append(paragraph)
        if current:
            out.append("\n\n".join(current).strip())
        normalized = [chunk for chunk in out if chunk]
        if all(_count_tokens(chunk, cfg, strict=True) <= cfg.v2_hard_cap_tokens for chunk in normalized):
            return normalized
        refined: list[str] = []
        for chunk in normalized:
            if _count_tokens(chunk, cfg, strict=True) <= cfg.v2_hard_cap_tokens:
                refined.append(chunk)
            else:
                refined.extend(
                    _split_v2_text_by_token_cap(
                        chunk,
                        cfg,
                        kind_hint=kind_hint,
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
            if current and _count_tokens(candidate, cfg, strict=True) > cfg.v2_hard_cap_tokens:
                out.append(" ".join(current).strip())
                current = [sentence]
            else:
                current.append(sentence)
        if current:
            out.append(" ".join(current).strip())
        normalized = [chunk for chunk in out if chunk]
        if all(_count_tokens(chunk, cfg, strict=True) <= cfg.v2_hard_cap_tokens for chunk in normalized):
            return normalized
        refined: list[str] = []
        for chunk in normalized:
            if _count_tokens(chunk, cfg, strict=True) <= cfg.v2_hard_cap_tokens:
                refined.append(chunk)
            else:
                refined.extend(
                    _split_v2_text_by_token_cap(
                        chunk,
                        cfg,
                        kind_hint=kind_hint,
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
            if _count_tokens(candidate, cfg, strict=True) <= cfg.v2_hard_cap_tokens:
                best = mid
                low = mid + 1
            else:
                high = mid - 1
        chunk = " ".join(words[start:best]).strip()
        if chunk:
            if _count_tokens(chunk, cfg, strict=True) <= cfg.v2_hard_cap_tokens:
                out.append(chunk)
            else:
                out.extend(force_char_splits(chunk))
        start = max(best, start + 1)
    normalized = [chunk for chunk in out if chunk]
    if all(_count_tokens(chunk, cfg, strict=True) <= cfg.v2_hard_cap_tokens for chunk in normalized):
        return normalized
    fully_enforced: list[str] = []
    for chunk in normalized:
        if _count_tokens(chunk, cfg, strict=True) <= cfg.v2_hard_cap_tokens:
            fully_enforced.append(chunk)
        else:
            fully_enforced.extend(force_char_splits(chunk))
    return [chunk for chunk in fully_enforced if chunk]


def _split_v2_packed_chunks(packed_chunks: list[V2PackedChunk], cfg: SegmentationConfig) -> list[V2PackedChunk]:
    """Stage B: enforce hard cap using boundary-priority splitting."""
    out: list[V2PackedChunk] = []
    for packed in packed_chunks:
        rendered_parts = [_render_v2_part(part) for part in packed.parts]
        rendered_tokens = [_count_tokens(text, cfg, strict=True) for text in rendered_parts]
        if _count_tokens("\n\n".join(rendered_parts), cfg, strict=True) <= cfg.v2_hard_cap_tokens:
            out.append(packed)
            continue

        # Priority 1: split by child subsection boundaries.
        if len(packed.parts) > 1:
            current_parts: list[V2ChunkPart] = []
            current_rendered_parts: list[str] = []
            for part, rendered_text, part_tokens in zip(packed.parts, rendered_parts, rendered_tokens):
                if part_tokens > cfg.v2_hard_cap_tokens:
                    if current_parts:
                        out.append(V2PackedChunk(heading_path=packed.heading_path, parts=list(current_parts)))
                        current_parts = []
                        current_rendered_parts = []
                    for text in _split_v2_text_by_token_cap(
                        rendered_text,
                        cfg,
                        kind_hint=part.segment_type,
                    ):
                        out.append(
                            V2PackedChunk(
                                heading_path=packed.heading_path,
                                parts=[
                                    V2ChunkPart(
                                        heading_title="",
                                        text=text,
                                        segment_type=part.segment_type,
                                        tokens=_count_tokens(text, cfg, strict=True),
                                    )
                                ],
                            )
                        )
                    continue
                candidate_joined = "\n\n".join(current_rendered_parts + [rendered_text]).strip()
                if current_parts and _count_tokens(candidate_joined, cfg, strict=True) > cfg.v2_hard_cap_tokens:
                    out.append(V2PackedChunk(heading_path=packed.heading_path, parts=list(current_parts)))
                    current_parts = [part]
                    current_rendered_parts = [rendered_text]
                else:
                    current_parts.append(part)
                    current_rendered_parts.append(rendered_text)
            if current_parts:
                out.append(V2PackedChunk(heading_path=packed.heading_path, parts=list(current_parts)))
            continue

        # Priorities 2-4: split one large part by paragraph/sentence/window fallback.
        single_part = packed.parts[0]
        chunks = _split_v2_text_by_token_cap(
            _render_v2_part(single_part),
            cfg,
            kind_hint=single_part.segment_type,
        )
        for text in chunks:
            out.append(
                V2PackedChunk(
                    heading_path=packed.heading_path,
                    parts=[
                        V2ChunkPart(
                            heading_title="",
                            text=text,
                            segment_type=single_part.segment_type,
                            tokens=_count_tokens(text, cfg, strict=True),
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
    final_chunks = _split_v2_packed_chunks(packed, cfg)

    segments: list[Segment] = []
    for chunk_index, packed_chunk in enumerate(final_chunks):
        chunk_text = "\n\n".join(_render_v2_part(part) for part in packed_chunk.parts).strip()
        segment_type = "mixed"
        part_types = {part.segment_type for part in packed_chunk.parts}
        if len(part_types) == 1:
            only = next(iter(part_types))
            if only in {"prose", "code_block", "table_rows", "list_bundle"}:
                segment_type = only
        segments.append(
            Segment(
                page_id=page_id,
                chunk_index=chunk_index,
                strategy=strategy,
                heading_path=packed_chunk.heading_path,
                segment_type=segment_type,
                segment_text=chunk_text,
                token_estimate=_count_tokens(chunk_text, cfg, strict=True),
                char_count=len(chunk_text),
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
            segment.segment_type,
            segment.segment_text,
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
  segment_type,
  segment_text,
  token_estimate,
  char_count
)
VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (page_id, strategy, chunk_index)
DO UPDATE SET
  heading_path = EXCLUDED.heading_path,
  segment_type = EXCLUDED.segment_type,
  segment_text = EXCLUDED.segment_text,
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
    )
    stats = RunStats(strategy=strategy)
    stats.hard_cap_tokens = cfg.v2_hard_cap_tokens if strategy == V2_STRATEGY else cfg.hard_cap_tokens
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

    print("segment_cleaned_content self-test: OK")


def _main(argv: list[str] | None = None) -> int:
    """CLI entrypoint for Strategy C segmentation."""
    parser = argparse.ArgumentParser(
        description="Segment crawldb.page.cleaned_content into crawldb.page_segment."
    )
    parser.add_argument(
        "--strategy",
        default=DEFAULT_STRATEGY,
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
