"""Deduplication module.

Implements SimHash for near-duplicate detection and exact substring dedup.
"""

from __future__ import annotations

import hashlib
import re
from typing import Dict, List, Optional, Tuple

try:
    from simhash import Simhash
    HAS_SIMHASH = True
except ImportError:
    HAS_SIMHASH = False


def deduplicate(
    text: str,
    threshold: float = 0.85,
    min_paragraph_length: int = 50,
) -> Tuple[str, dict]:
    """Remove duplicate and near-duplicate paragraphs from text.

    Args:
        text: Input text.
        threshold: Similarity threshold (0.0-1.0). Paragraphs with SimHash
            similarity above this are considered duplicates.
        min_paragraph_length: Minimum characters for a paragraph to be
            considered for dedup.

    Returns:
        Tuple of (deduplicated_text, stats_dict).
        stats_dict contains: original_count, unique_count, removed_count, removal_pct.
    """
    if not text or not text.strip():
        return text, {
            "original_count": 0,
            "unique_count": 0,
            "removed_count": 0,
            "removal_pct": 0.0,
        }

    # Split into paragraphs
    paragraphs = _split_paragraphs(text)

    if len(paragraphs) <= 1:
        return text, {
            "original_count": len(paragraphs),
            "unique_count": len(paragraphs),
            "removed_count": 0,
            "removal_pct": 0.0,
        }

    # Step 1: Exact dedup — same normalized text
    seen_exact: Dict[str, int] = {}  # normalized -> first index
    exact_kept: List[int] = []
    for i, para in enumerate(paragraphs):
        normalized = _normalize_for_exact_match(para)
        if len(normalized) < min_paragraph_length:
            exact_kept.append(i)  # Always keep short paragraphs
        elif normalized not in seen_exact:
            seen_exact[normalized] = i
            exact_kept.append(i)
        # else: skip duplicate

    # Step 2: Near-duplicate detection via SimHash
    if HAS_SIMHASH and len(exact_kept) > 1:
        hashes: list = []
        for idx in exact_kept:
            para = paragraphs[idx]
            if len(para) >= min_paragraph_length:
                hashes.append(Simhash(_tokenize_for_simhash(para)))
            else:
                hashes.append(None)

        # Hamming distance threshold: (1 - threshold) * 64 bits
        hamming_threshold = int((1.0 - threshold) * 64)

        kept_indices: List[int] = []
        for i, idx in enumerate(exact_kept):
            is_dup = False
            if hashes[i] is not None:
                for j in kept_indices:
                    if hashes[j] is not None:
                        distance = hashes[i].distance(hashes[j])
                        if distance <= hamming_threshold:
                            is_dup = True
                            break
            if not is_dup:
                kept_indices.append(i)

        final_indices = [exact_kept[i] for i in kept_indices]
    else:
        # Without SimHash, just use exact dedup results
        final_indices = exact_kept

    unique_paragraphs = [paragraphs[i] for i in sorted(final_indices)]

    result = "\n\n".join(unique_paragraphs)

    stats = {
        "original_count": len(paragraphs),
        "unique_count": len(unique_paragraphs),
        "removed_count": len(paragraphs) - len(unique_paragraphs),
        "removal_pct": round(
            (len(paragraphs) - len(unique_paragraphs)) / max(len(paragraphs), 1) * 100, 1
        ),
    }

    return result, stats


def _split_paragraphs(text: str) -> List[str]:
    """Split text into paragraphs by blank lines."""
    # Normalize line endings
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    # Split on one or more blank lines
    raw = re.split(r"\n\s*\n", text)
    return [p.strip() for p in raw if p.strip()]


def _normalize_for_exact_match(paragraph: str) -> str:
    """Normalize a paragraph for exact comparison."""
    # Lowercase, collapse whitespace
    normalized = re.sub(r"\s+", " ", paragraph.strip().lower())
    return normalized


def _tokenize_for_simhash(text: str) -> List[str]:
    """Tokenize text for SimHash fingerprinting.

    Uses character bigrams which works well for both Chinese and English.
    """
    # Normalize whitespace
    text = re.sub(r"\s+", " ", text.strip())
    if len(text) < 4:
        return [text]

    tokens: List[str] = []
    # Character bigrams
    for i in range(len(text) - 1):
        tokens.append(text[i : i + 2])

    # Also include word bigrams for English text
    words = text.split()
    for i in range(len(words) - 1):
        tokens.append(f"w_{words[i]}_{words[i + 1]}")

    return tokens


def _java_style_simhash(text: str) -> int:
    """Compute a simplified 64-bit hash fingerprint for text.

    This mirrors the Java fallback implementation and does NOT require
    the 'simhash' Python package.

    Args:
        text: Input text.

    Returns:
        64-bit hash value.
    """
    if not text:
        return 0

    # Tokenize into features
    features = _tokenize_for_simhash(text)
    if not features:
        return 0

    # Count feature occurrences
    feature_counts: Dict[str, int] = {}
    for f in features:
        feature_counts[f] = feature_counts.get(f, 0) + 1

    # 64-bit vector accumulation
    v = [0] * 64
    for feature, count in feature_counts.items():
        h = _murmur3_64(feature)
        for i in range(64):
            bit = (h >> i) & 1
            if bit:
                v[i] += count
            else:
                v[i] -= count

    # Produce fingerprint
    fingerprint = 0
    for i in range(64):
        if v[i] > 0:
            fingerprint |= (1 << i)

    return fingerprint


def _murmur3_64(key: str, seed: int = 0) -> int:
    """Simplified MurmurHash3-like 64-bit hash for a string."""
    data = key.encode("utf-8")
    h = seed ^ (len(data) * 0xC6A4A7935BD1E995)

    # Process 8-byte blocks
    for i in range(0, len(data) - 7, 8):
        k = int.from_bytes(data[i : i + 8], "little", signed=False)
        k = (k * 0x87C37B91114253D5) & 0xFFFFFFFFFFFFFFFF
        k = ((k << 31) | (k >> 33)) & 0xFFFFFFFFFFFFFFFF
        k = (k * 0x4CF5AD432745937F) & 0xFFFFFFFFFFFFFFFF
        h ^= k
        h = ((h << 27) | (h >> 37)) & 0xFFFFFFFFFFFFFFFF
        h = (h * 5 + 0x52DCE729) & 0xFFFFFFFFFFFFFFFF

    # Process remaining bytes
    remaining = data[-(len(data) % 8):] if len(data) % 8 else b""
    if remaining:
        k = int.from_bytes(remaining.ljust(8, b"\x00"), "little", signed=False)
        k = (k * 0x87C37B91114253D5) & 0xFFFFFFFFFFFFFFFF
        k = ((k << 31) | (k >> 33)) & 0xFFFFFFFFFFFFFFFF
        k = (k * 0x4CF5AD432745937F) & 0xFFFFFFFFFFFFFFFF
        h ^= k

    h ^= len(data)
    h ^= (h >> 33)
    h = (h * 0xC6A4A7935BD1E995) & 0xFFFFFFFFFFFFFFFF
    h ^= (h >> 33)

    return h


def hamming_distance(hash1: int, hash2: int) -> int:
    """Compute Hamming distance between two 64-bit hashes."""
    return (hash1 ^ hash2).bit_count()
