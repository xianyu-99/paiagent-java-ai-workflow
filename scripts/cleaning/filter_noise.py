"""Quality filtering module.

Filters out low-quality text segments based on configurable thresholds.
Includes Chinese-specific heuristics.
"""

from __future__ import annotations

import re
import unicodedata
from typing import Dict, List, Tuple


def filter_noise(
    text: str,
    min_length: int = 50,
    max_punctuation_ratio: float = 0.60,
    max_control_char_ratio: float = 0.10,
    min_cjk_ratio: float = 0.10,
    remove_empty_lines: bool = True,
) -> Tuple[str, dict]:
    """Filter noisy/low-quality segments from text.

    Args:
        text: Input text.
        min_length: Minimum character length for a paragraph to keep.
        max_punctuation_ratio: Maximum ratio of punctuation chars before
            a paragraph is considered garbage.
        max_control_char_ratio: Maximum ratio of control characters.
        min_cjk_ratio: For Chinese documents, if CJK character ratio is
            below this threshold, the paragraph is likely garbage.
        remove_empty_lines: Strip blank lines.

    Returns:
        Tuple of (filtered_text, stats_dict).
        stats_dict contains: original_chars, filtered_chars, removed_lines,
        kept_lines, filter_pct.
    """
    if not text or not text.strip():
        return text, {
            "original_chars": 0,
            "filtered_chars": 0,
            "removed_lines": 0,
            "kept_lines": 0,
            "filter_pct": 0.0,
        }

    # Split into lines
    lines = text.split("\n")
    original_count = len(lines)

    kept_lines: List[str] = []
    removed_count = 0

    for line in lines:
        stripped = line.strip()

        # Remove empty lines
        if remove_empty_lines and not stripped:
            removed_count += 1
            continue

        # Skip lines that are too short (but keep structural whitespace)
        if stripped and len(stripped) < min_length:
            # Keep very short lines that look like headings
            if _looks_like_heading(stripped):
                kept_lines.append(line)
            else:
                removed_count += 1
            continue

        # Check punctuation ratio
        punct_ratio = _compute_punctuation_ratio(stripped)
        if punct_ratio > max_punctuation_ratio:
            removed_count += 1
            continue

        # Check control character ratio
        ctrl_ratio = _compute_control_char_ratio(stripped)
        if ctrl_ratio > max_control_char_ratio:
            removed_count += 1
            continue

        # Chinese-specific: CJK ratio check
        if min_cjk_ratio > 0:
            cjk_ratio = _compute_cjk_ratio(stripped)
            # Only apply CJK filter if there are CJK characters present at all
            if cjk_ratio > 0 and cjk_ratio < min_cjk_ratio:
                removed_count += 1
                continue

        kept_lines.append(line)

    # Clean up multiple blank lines in result
    result = "\n".join(kept_lines)
    result = re.sub(r"\n{3,}", "\n\n", result)

    original_chars = len(text)
    filtered_chars = len(result)

    stats = {
        "original_chars": original_chars,
        "filtered_chars": filtered_chars,
        "removed_lines": removed_count,
        "kept_lines": len(kept_lines),
        "filter_pct": round(
            (original_chars - filtered_chars) / max(original_chars, 1) * 100, 1
        ),
    }

    return result.strip(), stats


def _compute_punctuation_ratio(text: str) -> float:
    """Calculate the ratio of punctuation characters in text."""
    if not text:
        return 0.0

    punct_count = 0
    for ch in text:
        cat = unicodedata.category(ch)
        if cat.startswith("P") or cat.startswith("S"):  # Punctuation or Symbol
            punct_count += 1
        elif ch in "，。！？；：""''（）【】《》…—～·":
            punct_count += 1

    return punct_count / len(text)


def _compute_control_char_ratio(text: str) -> float:
    """Calculate the ratio of control characters (excluding common whitespace)."""
    if not text:
        return 0.0

    control_count = 0
    for ch in text:
        cp = ord(ch)
        if cp < 32 and cp not in (9, 10, 13):  # tab, LF, CR are OK
            control_count += 1
        elif cp in (0x7F, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88,
                     0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E, 0x8F, 0x90, 0x91, 0x92,
                     0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0x9B, 0x9C):
            control_count += 1

    return control_count / len(text)


def _compute_cjk_ratio(text: str) -> float:
    """Calculate the ratio of CJK characters in text."""
    if not text:
        return 0.0

    cjk_count = 0
    for ch in text:
        cp = ord(ch)
        if (_is_cjk_code_point(cp)):
            cjk_count += 1

    return cjk_count / len(text)


def _is_cjk_code_point(cp: int) -> bool:
    """Check if a code point is CJK."""
    return (
        (0x4E00 <= cp <= 0x9FFF)
        or (0x3400 <= cp <= 0x4DBF)
        or (0x20000 <= cp <= 0x2A6DF)
        or (0x2A700 <= cp <= 0x2B73F)
        or (0x2B740 <= cp <= 0x2B81F)
        or (0x2B820 <= cp <= 0x2CEAF)
        or (0x2F800 <= cp <= 0x2FA1F)
        or (0x3000 <= cp <= 0x303F)
        or (0x3040 <= cp <= 0x309F)
        or (0x30A0 <= cp <= 0x30FF)
        or (0xAC00 <= cp <= 0xD7AF)
        or (0xF900 <= cp <= 0xFAFF)
        or (0xFF00 <= cp <= 0xFFEF)
    )


def _looks_like_heading(text: str) -> bool:
    """Heuristic: does this short text look like a heading?"""
    if not text:
        return False
    # Starts with markdown heading markers
    if re.match(r"^#{1,6}\s", text):
        return True
    # Short text ending with Chinese heading patterns
    if len(text) <= 30 and re.search(r"[第第].*[章节]|[一二三四五六七八九十]+[、．.]", text):
        return True
    # Short text ending with colon (Chinese or English)
    if len(text) <= 40 and text.rstrip().endswith(("：", ":", "?")):
        return True
    return False
