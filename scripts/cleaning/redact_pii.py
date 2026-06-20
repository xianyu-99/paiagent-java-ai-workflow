"""PII redaction module.

Detects and redacts personally identifiable information:
- Chinese ID card numbers (18-digit with checksum validation)
- Chinese phone numbers (13x/15x/18x/19x patterns)
- Email addresses
- Optional: names, addresses
"""

from __future__ import annotations

import re
from typing import Dict, List, Tuple

# Chinese ID card number: 18 digits, last digit can be X
# Pattern: 6-digit area code + 8-digit birth date (YYYYMMDD) + 3-digit sequence + 1 check digit
_ID_CARD_RE = re.compile(
    r"(?<!\d)"
    r"[1-9]\d{5}"  # Area code (6 digits, first 1-9)
    r"(?:19|20)\d{2}"  # Year (1900-2099)
    r"(?:0[1-9]|1[0-2])"  # Month (01-12)
    r"(?:0[1-9]|[12]\d|3[01])"  # Day (01-31)
    r"\d{3}"  # Sequence (3 digits)
    r"[0-9Xx]"  # Check digit
    r"(?!\d)"
)

# Chinese phone numbers: 1[3-9]xxxxxxxxx
_PHONE_RE = re.compile(
    r"(?<!\d)"
    r"1[3-9]\d{9}"
    r"(?!\d)"
)

# Email addresses
_EMAIL_RE = re.compile(
    r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"
)

# ID card checksum weights (GB 11643-1999)
_ID_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
_ID_CHECK_CHARS = "10X98765432"


def redact_pii(
    text: str,
    mode: str = "redact",
    redact_id_card: bool = True,
    redact_phone: bool = True,
    redact_email: bool = True,
) -> Tuple[str, dict]:
    """Detect and redact PII from text.

    Args:
        text: Input text.
        mode: "redact" to replace with [REDACTED] tags, "remove" to delete.
        redact_id_card: Whether to redact Chinese ID card numbers.
        redact_phone: Whether to redact Chinese phone numbers.
        redact_email: Whether to redact email addresses.

    Returns:
        Tuple of (redacted_text, stats_dict).
        stats_dict contains counts of each PII type redacted.
    """
    if not text or not text.strip():
        return text, {
            "id_cards_redacted": 0,
            "phone_numbers_redacted": 0,
            "emails_redacted": 0,
            "total_redacted": 0,
        }

    replacement = "" if mode == "remove" else "[REDACTED]"

    stats: Dict[str, int] = {
        "id_cards_redacted": 0,
        "phone_numbers_redacted": 0,
        "emails_redacted": 0,
    }

    # Redact ID card numbers (with checksum validation)
    if redact_id_card:
        def replace_id_card(match: re.Match) -> str:
            id_number = match.group(0).upper()
            if _validate_id_checksum(id_number):
                stats["id_cards_redacted"] += 1
                return replacement
            return id_number  # Don't redact if checksum fails (false positive)

        text = _ID_CARD_RE.sub(replace_id_card, text)

    # Redact phone numbers
    if redact_phone:
        def replace_phone(match: re.Match) -> str:
            stats["phone_numbers_redacted"] += 1
            return replacement

        text = _PHONE_RE.sub(replace_phone, text)

    # Redact email addresses
    if redact_email:
        def replace_email(match: re.Match) -> str:
            stats["emails_redacted"] += 1
            return replacement

        text = _EMAIL_RE.sub(replace_email, text)

    stats["total_redacted"] = (
        stats["id_cards_redacted"]
        + stats["phone_numbers_redacted"]
        + stats["emails_redacted"]
    )

    return text, stats


def _validate_id_checksum(id_number: str) -> bool:
    """Validate the checksum of a Chinese 18-digit ID card number.

    Uses the GB 11643-1999 standard algorithm.
    """
    if len(id_number) != 18:
        return False

    # Sum of weighted first 17 digits
    total = 0
    for i in range(17):
        digit = id_number[i]
        if not digit.isdigit():
            return False
        total += int(digit) * _ID_WEIGHTS[i]

    # Check digit
    expected_check = _ID_CHECK_CHARS[total % 11]
    actual_check = id_number[17].upper()

    return expected_check == actual_check
