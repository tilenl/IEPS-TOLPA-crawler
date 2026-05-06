#!/usr/bin/env python3
"""
Extract rendered GitHub README (and similar) plain text from stored HTML.

PA1 persists full page HTML in ``crawldb.page.html_content``. GitHub serves the
README inside ``<article class="markdown-body ..." itemprop="text">``. Some
terminals (commits, wiki history) do not include that region; those rows get
``NULL`` / empty ``cleaned_content``.

Why block-level iteration instead of a single ``get_text()``:
    Headings and paragraphs stay separated by blank lines so later chunking
    (Phase 2) can respect structure instead of one flat wall of text.

Connection defaults match ``pa1/crawler/src/main/resources/application.properties``
and the Docker recipe in ``pa1/README.md`` (host ``localhost``, db ``crawldb``).
"""

from __future__ import annotations

import argparse
import html
import logging
import os
import re
import sys
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any

from bs4 import BeautifulSoup, NavigableString, Tag

# Block tags that are emitted into cleaned text.
_EMITTABLE_BLOCK_NAMES: frozenset[str] = frozenset(
    {
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "p",
        "pre",
        "li",
        "blockquote",
        "tr",
    }
)

# Block tags used for hierarchy decisions.
# ``ul`` / ``ol`` are structural only: they are not emitted directly, but they
# prevent parent ``li`` nodes from flattening nested lists into one giant line.
_STRUCTURAL_BLOCK_NAMES: frozenset[str] = _EMITTABLE_BLOCK_NAMES | {"ul", "ol"}

# GitHub puts the exact copy buffer on a wrapper ``div`` (and sometimes embeds) so
# highlighted ``<pre>`` (many ``<span>`` tokens) does not corrupt YAML/shell layout.
_GH_CLIPBOARD_ATTR = "data-snippet-clipboard-copy-content"
_WEAK_LINK_LABELS: frozenset[str] = frozenset(
    {
        "here",
        "link",
        "links",
        "paper",
        "code",
        "repo",
        "repository",
        "website",
        "more",
        "read more",
        "details",
        "demo",
        "project",
        "arxiv",
    }
)
_TOC_HEADING_NAMES: frozenset[str] = frozenset({"table of contents", "contents", "toc"})


@dataclass(frozen=True)
class ExtractedBlock:
    """One extracted block with minimal metadata for post-processing."""

    kind: str
    text: str
    heading_level: int | None = None


def _pre_plain_from_github_clipboard(pre: Tag) -> str | None:
    """If *pre* sits under GitHub's snippet wrapper, return its copy payload."""
    current: Tag | BeautifulSoup | None = pre
    while current is not None and isinstance(current, Tag):
        raw = current.get(_GH_CLIPBOARD_ATTR)
        if isinstance(raw, str) and raw.strip():
            text = html.unescape(raw).strip()
            if text:
                return text
        current = current.parent
    return None


def _child_block_tags(el: Tag) -> list[str]:
    """Return block-tag names among *direct* children (used to detect leaf blocks)."""
    names: list[str] = []
    for child in el.children:
        name = getattr(child, "name", None)
        if name and name in _STRUCTURAL_BLOCK_NAMES:
            names.append(name)
    return names


def _normalize_whitespace(text: str) -> str:
    """Collapse repeated spaces and tidy spaces around newlines."""
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    return text.strip()


def _is_absolute_url(href: str) -> bool:
    return href.startswith("http://") or href.startswith("https://")


def _is_weak_anchor_label(label: str) -> bool:
    normalized = label.strip().lower()
    if not normalized:
        return True
    if normalized in _WEAK_LINK_LABELS:
        return True
    words = [w for w in re.split(r"\s+", normalized) if w]
    if len(words) == 1 and len(words[0]) <= 4:
        return True
    return False


def _render_anchor_text(label: str, href: str | None) -> str:
    """Render anchor text while preserving informative URLs for weak labels."""
    clean_label = _normalize_whitespace(label)
    if not href:
        return clean_label
    href = href.strip()
    if not href or href.startswith("#"):
        return clean_label
    if not _is_absolute_url(href):
        return clean_label
    if not clean_label:
        return href
    if _is_weak_anchor_label(clean_label):
        return f"{clean_label} ({href})"
    return clean_label


def _extract_inline_text(node: Tag | NavigableString) -> str:
    """Extract readable inline text while applying anchor rendering policy."""
    if isinstance(node, NavigableString):
        return str(node)
    if not isinstance(node, Tag):
        return ""
    if node.name == "br":
        return "\n"
    if node.name == "a":
        return _render_anchor_text(
            node.get_text(separator=" ", strip=True),
            node.get("href"),
        )
    parts: list[str] = []
    for child in node.children:
        piece = _extract_inline_text(child)
        if piece:
            parts.append(piece)
    return "".join(parts)


def _text_from_tag(tag: Tag) -> str:
    """Extract normalized text for prose/list blocks."""
    text = _extract_inline_text(tag)
    text = text.replace("\n", " ")
    return _normalize_whitespace(text)


def _text_from_table_row(row: Tag) -> str:
    """Join table row cells into a markdown-like representation."""
    cells: list[str] = []
    for cell in row.find_all(["th", "td"], recursive=False):
        t = _text_from_tag(cell)
        if t:
            cells.append(t)
    return " | ".join(cells).strip()


def _has_nested_emittable_child(el: Tag) -> bool:
    """
    True when *el* should not be emitted because child blocks will represent it.

    We intentionally treat ``ul`` / ``ol`` as structural descendants. This avoids
    flattening a parent ``li`` that contains a nested list (common in ToC blocks).
    """
    return bool(_child_block_tags(el))


def _readme_root_from_soup(soup: BeautifulSoup) -> Tag | None:
    """
    Locate the README markdown container.

    Primary selector matches GitHub's repo overview README (see DevTools /
    embedded ``richText`` payloads). Fallbacks widen slightly without grabbing
    arbitrary site chrome.
    """
    # Canonical GitHub README article (SPA and traditional HTML responses). - ignore the nav, file table... only the README subtree is processed
    node = soup.select_one('article.markdown-body[itemprop="text"]')
    if node:
        return node
    node = soup.select_one("article.markdown-body.entry-content")
    if node:
        return node
    node = soup.select_one("article.markdown-body")
    if node:
        return node
    return None


def _strip_noise(root: Tag) -> None:
    """
    Drop chrome that should not contribute to embeddings: scripts, badges-only
    images, inline SVG anchors, etc.

    We mutate *root* in place so the tree stays a single BS tree.
    """
    for tag in root.find_all(["script", "style", "noscript"]):
        tag.decompose()
    # Linked heading anchors (¶ / link icon): remove empty or icon-only anchors.
    for a in root.find_all("a", class_=re.compile(r"\banchor\b")):
        if not a.get_text(strip=True) and not a.attrs.get("title"):
            a.decompose()
    for svg in root.find_all("svg"):
        svg.decompose()
    # User asked to drop image pixels; alt text rarely adds repo semantics for READMEs.
    for img in root.find_all("img"):
        img.decompose()


def _iter_leaf_blocks(article_root: Tag) -> Iterator[ExtractedBlock]:
    """
    Yield human-readable snippets in document order.

    Skip outer blocks that still contain nested block tags so we emit *leaf*
    blocks only (e.g. ``blockquote > p`` yields the ``p``, not both).
    """
    for el in article_root.find_all(_EMITTABLE_BLOCK_NAMES, recursive=True):
        if not isinstance(el, Tag):
            continue
        if _has_nested_emittable_child(el):
            # Non-leaf: a heading wrapper or nested structure will surface via children.
            continue
        heading_level: int | None = None
        if el.name == "tr":
            chunk = _text_from_table_row(el)
        elif el.name == "pre":
            chunk = _pre_plain_from_github_clipboard(el) or el.get_text(
                separator="\n", strip=True
            )
        else:
            chunk = _text_from_tag(el)
            if el.name in {"h1", "h2", "h3", "h4", "h5", "h6"}:
                heading_level = int(el.name[1])
        if chunk:
            yield ExtractedBlock(kind=el.name, text=chunk, heading_level=heading_level)


def _normalize_for_match(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def _is_toc_heading(text: str) -> bool:
    normalized = _normalize_for_match(text)
    return normalized in _TOC_HEADING_NAMES


def _looks_like_toc_entry(text: str) -> bool:
    normalized = _normalize_for_match(text)
    if not normalized:
        return False
    words = normalized.split()
    if "http" in normalized:
        return False
    if len(words) <= 16 and len(text) <= 160 and not re.search(r"[.!?]\s*$", text):
        return True
    return False


def _drop_table_of_contents_blocks(blocks: list[ExtractedBlock]) -> list[ExtractedBlock]:
    """Remove ToC heading + immediate nav-like items that follow it."""
    kept: list[ExtractedBlock] = []
    i = 0
    n = len(blocks)
    while i < n:
        block = blocks[i]
        if _is_toc_heading(block.text):
            i += 1
            while i < n:
                candidate = blocks[i]
                if candidate.heading_level is not None:
                    break
                if candidate.kind == "li" or _looks_like_toc_entry(candidate.text):
                    i += 1
                    continue
                break
            continue
        kept.append(block)
        i += 1
    return kept


def _format_block_for_output(block: ExtractedBlock) -> str:
    """Render extracted block with stable heading markers for chunking."""
    if block.heading_level is not None:
        return f"[H{block.heading_level}] {block.text}"
    return block.text


def _collapse_blank_lines(text: str) -> str:
    return re.sub(r"\n{3,}", "\n\n", text.strip())


def extract_readme_plain_text(html: str | None) -> str | None:
    """
    Parse *html* and return cleaned README/plain text, or ``None`` if nothing
    useful was found.

    Returns:
        Non-empty string, or ``None`` when extraction is impossible (missing HTML,
        no README blob, README explicitly empty, parsing failure).
    """
    if not html or not str(html).strip():
        return None
    try:
        soup = BeautifulSoup(html, "lxml")
    except Exception:
        logging.exception("BeautifulSoup parse failed")
        return None

    # get only the README subtree from the soup - only relevant content we want to process.
    readme = _readme_root_from_soup(soup)
    if readme is None:
        return None

    assert isinstance(readme, Tag)
    # README subtree only: safe to strip in place (row HTML is not reused).
    _strip_noise(readme)
    blocks = list(_iter_leaf_blocks(readme))
    blocks = _drop_table_of_contents_blocks(blocks)
    if not blocks:
        return None
    parts = [_format_block_for_output(block) for block in blocks]
    text = "\n\n".join(parts)
    text = _collapse_blank_lines(text)
    return text or None


# ---------------------------------------------------------------------------
# Database batch updater
# ---------------------------------------------------------------------------

_BASE_WHERE = """
page_type_code = 'HTML'
  AND html_content IS NOT NULL
  AND LENGTH(TRIM(html_content)) > 0
  AND (
    %(only_missing)s = FALSE
    OR cleaned_content IS NULL
    OR LENGTH(TRIM(cleaned_content)) = 0
  )
"""


def _resolve_conn_kwargs() -> dict[str, Any]:
    """Merge env overrides with PA1-aligned defaults."""
    return {
        "host": os.environ.get("PGHOST", "localhost"),
        "port": int(os.environ.get("PGPORT", "5432")),
        "dbname": os.environ.get("PGDATABASE", "crawldb"),
        "user": os.environ.get("PGUSER", "user"),
        "password": os.environ.get("PGPASSWORD", "SecretPassword"),
    }


def run_extraction(
    *,
    only_missing: bool = True,
    limit: int | None = None,
    dry_run: bool = False,
    batch_size: int = 100,
    verbose: bool = False,
) -> tuple[int, int, int]:
    """
    Pull HTML rows, compute ``cleaned_content``, write back.

    Uses keyset pagination (``id > last_id``) so each batch fits in memory and
    UPDATEs can safely interleave on one connection (named server-side cursors
    would block concurrent commands on the same PostgreSQL session).

    Returns:
        ``(rows_read, rows_updated_non_null, rows_cleared_null)`` where
        "cleared" means we stored SQL NULL for empty extraction.
    """
    import psycopg
    from psycopg.rows import dict_row

    conn_kwargs = _resolve_conn_kwargs()
    read = 0
    updated = 0
    cleared = 0
    last_id = 0

    select_sql = (
        """
SELECT id, html_content
FROM crawldb.page
WHERE id > %(last_id)s
  AND """
        + _BASE_WHERE
        + """
ORDER BY id
LIMIT %(chunk)s
"""
    )

    with psycopg.connect(**conn_kwargs, autocommit=False, row_factory=dict_row) as conn:
        conn.execute("SET search_path TO crawldb, public")
        pending_since_commit = 0

        while True:
            if limit is not None and read >= limit:
                break

            chunk = batch_size
            if limit is not None:
                chunk = min(chunk, limit - read)
            if chunk <= 0:
                break

            rows = conn.execute(
                select_sql,
                {
                    "last_id": last_id,
                    "chunk": chunk,
                    "only_missing": only_missing,
                },
            ).fetchall()

            if not rows:
                break

            for row in rows:
                if limit is not None and read >= limit:
                    break

                page_id = row["id"]
                last_id = page_id
                html = row["html_content"]
                cleaned = extract_readme_plain_text(html)

                read += 1

                if dry_run:
                    if verbose:
                        preview = (
                            (cleaned[:120] + "…") if cleaned and len(cleaned) > 120 else cleaned
                        )
                        logging.debug(
                            "dry-run id=%s len=%s preview=%r",
                            page_id,
                            len(cleaned or ""),
                            preview,
                        )
                    elif read % max(batch_size * 5, 1) == 0:
                        logging.info("dry-run progress: %s rows scanned", read)
                    continue

                if cleaned:
                    conn.execute(
                        "UPDATE crawldb.page SET cleaned_content = %s WHERE id = %s",
                        (cleaned, page_id),
                    )
                    updated += 1
                else:
                    conn.execute(
                        "UPDATE crawldb.page SET cleaned_content = NULL WHERE id = %s",
                        (page_id,),
                    )
                    cleared += 1

                pending_since_commit += 1
                if pending_since_commit >= batch_size:
                    conn.commit()
                    pending_since_commit = 0
                    logging.info("committed progress: %s rows processed", read)

            if limit is not None and read >= limit:
                break

        if not dry_run and pending_since_commit:
            conn.commit()

    return read, updated, cleared


def _self_test() -> None:
    """Minimal regression samples (no DB)."""
    sample = """
    <html><body>
    <article class="markdown-body entry-content container-lg" itemprop="text">
      <div class="markdown-heading"><h1 class="heading-element" dir="auto">Title</h1></div>
      <p>First <strong>para</strong>.</p>
      <p>Second.</p>
      <pre>code line</pre>
    </article>
    </body></html>
    """
    out = extract_readme_plain_text(sample)
    assert out and "[H1] Title" in out and "First para" in out and "code line" in out, out

    gh_yaml = """
    <article class="markdown-body" itemprop="text"><div
      data-snippet-clipboard-copy-content="Net:\\n  enc_type: 'resnet18'">
      <pre><span>Net</span><span>:</span><span>junk</span></pre>
    </div></article>
    """
    gh_out = extract_readme_plain_text(gh_yaml)
    assert gh_out and "enc_type: 'resnet18'" in gh_out and "junk" not in gh_out, gh_out

    toc = """
    <article class="markdown-body" itemprop="text">
      <h2>Table of Contents</h2>
      <ul>
        <li><a href="#install">Install</a></li>
        <li><a href="#usage">Usage</a>
          <ul><li><a href="#cli">CLI</a></li></ul>
        </li>
      </ul>
      <h2>Install</h2>
      <p>Use pip.</p>
    </article>
    """
    toc_out = extract_readme_plain_text(toc)
    assert toc_out and "Table of Contents" not in toc_out, toc_out
    assert toc_out and "[H2] Install" in toc_out and "Use pip." in toc_out, toc_out

    links = """
    <article class="markdown-body" itemprop="text">
      <p>Read <a href="https://example.com/paper">paper</a> and
      <a href="https://example.com/very-descriptive-resource">segmentation benchmark details</a>.</p>
    </article>
    """
    links_out = extract_readme_plain_text(links)
    assert links_out and "paper (https://example.com/paper)" in links_out, links_out
    assert links_out and "segmentation benchmark details" in links_out, links_out

    assert extract_readme_plain_text(None) is None
    assert extract_readme_plain_text("<html><body></body></html>") is None
    print("extract_readme self-test: OK", file=sys.stderr)


def _main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extract GitHub README text into crawldb.page.cleaned_content."
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Parse and log previews only; do not UPDATE the database.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Process at most N HTML rows (after filter), for smoke tests.",
    )
    parser.add_argument(
        "--recompute-all",
        action="store_true",
        help="Also rewrite rows that already have cleaned_content (default: only NULL/empty).",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=100,
        help="Commit every N rows (default: 100).",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="DEBUG logging.",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run embedded unit checks and exit.",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )

    if args.self_test:
        _self_test()
        return 0

    read, updated, cleared = run_extraction(
        only_missing=not args.recompute_all,
        limit=args.limit,
        dry_run=args.dry_run,
        batch_size=args.batch_size,
        verbose=args.verbose,
    )
    logging.info(
        "done: rows=%s updated_non_null=%s set_null=%s dry_run=%s",
        read,
        updated,
        cleared,
        args.dry_run,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
