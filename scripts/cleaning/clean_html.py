"""HTML cleaning module.

Strips HTML tags while preserving paragraph structure.
Uses BeautifulSoup4 + lxml for robust parsing.
"""

from __future__ import annotations

import re
from typing import Optional

try:
    from bs4 import BeautifulSoup, Comment, NavigableString
except ImportError:
    BeautifulSoup = None  # type: ignore[assignment]


# Tags whose text content should be preserved and separated by newlines
BLOCK_TAGS = {"p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "br", "tr"}

# Tags that should be removed entirely (including their content)
REMOVE_TAGS = {"script", "style", "nav", "footer", "header", "aside", "noscript"}

# Inline tags that we keep but don't add newlines for
INLINE_TAGS = {"b", "strong", "i", "em", "u", "a", "span", "code", "pre"}


def clean_html(html_text: str) -> str:
    """Strip HTML tags and return clean plain text preserving paragraph structure.

    Args:
        html_text: Raw HTML string.

    Returns:
        Cleaned plain text with paragraph structure preserved.
    """
    if not html_text or not html_text.strip():
        return ""

    if BeautifulSoup is None:
        # Fallback: basic regex-based tag stripping when bs4 is not installed
        return _regex_clean_html(html_text)

    soup = BeautifulSoup(html_text, "lxml")

    # Remove unwanted tags entirely
    for tag_name in REMOVE_TAGS:
        for tag in soup.find_all(tag_name):
            tag.decompose()

    # Remove HTML comments
    for comment in soup.find_all(string=lambda s: isinstance(s, Comment)):
        comment.extract()

    lines: list[str] = []
    _extract_text(soup, lines)

    text = "\n".join(lines)

    # Collapse excessive whitespace
    text = _collapse_whitespace(text)

    return text.strip()


def _extract_text(element, lines: list[str]) -> None:
    """Recursively extract text from BeautifulSoup elements."""
    for child in element.children if hasattr(element, "children") else []:
        if isinstance(child, NavigableString):
            stripped = child.string.strip() if child.string else ""
            if stripped:
                lines.append(stripped)
        elif hasattr(child, "name"):
            tag_name = child.name.lower() if child.name else ""
            if tag_name in REMOVE_TAGS:
                continue
            if tag_name in BLOCK_TAGS:
                # Flush accumulated inline text before the block tag
                text_parts: list[str] = []
                _collect_inline(child, text_parts)
                if text_parts:
                    lines.append(" ".join(text_parts))
                # Add a blank line after block-level tags (except <br>)
                if tag_name != "br":
                    lines.append("")
            elif tag_name in INLINE_TAGS:
                _extract_text(child, lines)
            else:
                _extract_text(child, lines)


def _collect_inline(element, text_parts: list[str]) -> None:
    """Collect inline text from an element without adding block breaks."""
    for child in element.children if hasattr(element, "children") else []:
        if isinstance(child, NavigableString):
            stripped = child.string.strip() if child.string else ""
            if stripped:
                text_parts.append(stripped)
        elif hasattr(child, "name"):
            tag_name = child.name.lower() if child.name else ""
            if tag_name in REMOVE_TAGS:
                continue
            if tag_name in BLOCK_TAGS:
                if text_parts:
                    text_parts.append("\n")
                _collect_inline(child, text_parts)
            else:
                _collect_inline(child, text_parts)


def _regex_clean_html(html_text: str) -> str:
    """Fallback HTML cleaner using regex when BeautifulSoup is not available."""
    # Remove script, style, nav, footer blocks
    text = re.sub(r"<(script|style|nav|footer|header|aside|noscript)\b[^>]*>.*?</\1>",
                  "", html_text, flags=re.DOTALL | re.IGNORECASE)

    # Remove HTML comments
    text = re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)

    # Replace block-level tags with newlines
    block_pattern = r"</?(?:p|h[1-6]|li|div|section|article|br|tr|table|ul|ol|dl|dt|dd|blockquote|pre|hr)\b[^>]*>"
    text = re.sub(block_pattern, "\n", text, flags=re.IGNORECASE)

    # Remove remaining HTML tags
    text = re.sub(r"<[^>]+>", "", text)

    # Decode common HTML entities
    text = text.replace("&amp;", "&")
    text = text.replace("&lt;", "<")
    text = text.replace("&gt;", ">")
    text = text.replace("&quot;", '"')
    text = text.replace("&#39;", "'")
    text = text.replace("&nbsp;", " ")

    return _collapse_whitespace(text).strip()


def _collapse_whitespace(text: str) -> str:
    """Collapse multiple blank lines and trim whitespace per line."""
    # Collapse >2 consecutive newlines to 2
    text = re.sub(r"\n{3,}", "\n\n", text)
    # Trim whitespace on each line
    lines = [line.strip() for line in text.split("\n")]
    # Remove leading/trailing empty lines
    while lines and not lines[0]:
        lines.pop(0)
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines)


# Public alias for direct use
strip_html = clean_html
