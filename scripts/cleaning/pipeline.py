#!/usr/bin/env python3
"""Data Cleaning Pipeline — main orchestration script.

Usage:
    python pipeline.py --input <file> --output <file> [--steps clean_html,dedup,filter,redact]
    python pipeline.py --input <file> --steps clean_html,redact
    cat dirty.txt | python pipeline.py --steps clean_html,dedup > clean.txt
    python pipeline.py --help

Steps (run in this order):
    clean_html  — Strip HTML tags, preserve paragraph structure
    dedup       — Remove duplicate/near-duplicate paragraphs
    filter      — Quality filtering (min length, punctuation, CJK ratio)
    redact      — PII redaction (ID cards, phone numbers, emails)

When no --steps are specified, all steps are run.
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Dict, List, Optional, Tuple

# Allow running from scripts/cleaning/ directory or from project root
try:
    from clean_html import clean_html
    from deduplicate import deduplicate
    from filter_noise import filter_noise
    from redact_pii import redact_pii
except ImportError:
    # Fallback: add current directory to path
    import os as _os
    _dir = _os.path.dirname(_os.path.abspath(__file__))
    if _dir not in sys.path:
        sys.path.insert(0, _dir)
    from clean_html import clean_html  # noqa: E402
    from deduplicate import deduplicate  # noqa: E402
    from filter_noise import filter_noise  # noqa: E402
    from redact_pii import redact_pii  # noqa: E402


AVAILABLE_STEPS: Dict[str, str] = {
    "clean_html": "Strip HTML tags, preserve paragraph structure",
    "dedup": "Remove duplicate/near-duplicate paragraphs",
    "filter": "Quality filtering (min length, punctuation, CJK ratio)",
    "redact": "PII redaction (ID cards, phone numbers, emails)",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Data Cleaning Pipeline — clean and normalize text for RAG ingestion",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Steps run in order: clean_html → dedup → filter → redact\n"
               "If --input is omitted, reads from stdin.\n"
               "If --output is omitted, writes to stdout.",
    )
    parser.add_argument(
        "--input", "-i",
        help="Input file path (reads from stdin if not provided)",
    )
    parser.add_argument(
        "--output", "-o",
        help="Output file path (writes to stdout if not provided)",
    )
    parser.add_argument(
        "--steps",
        help="Comma-separated list of steps to run. Available: %s. Default: all" %
             ", ".join(AVAILABLE_STEPS),
        default="clean_html,dedup,filter,redact",
    )
    parser.add_argument(
        "--format",
        choices=["text", "json"],
        default="text",
        help="Output format: 'text' for cleaned text, 'json' for text + stats (default: text)",
    )
    parser.add_argument(
        "--dedup-threshold",
        type=float,
        default=0.85,
        help="Deduplication similarity threshold (0.0-1.0, default: 0.85)",
    )
    parser.add_argument(
        "--min-length",
        type=int,
        default=50,
        help="Minimum character length for filter step (default: 50)",
    )
    parser.add_argument(
        "--pii-mode",
        choices=["redact", "remove"],
        default="redact",
        help="PII handling: 'redact' with [REDACTED] tags or 'remove' (default: redact)",
    )
    parser.add_argument(
        "--stats-only",
        action="store_true",
        help="Only output statistics, don't output cleaned text",
    )
    return parser.parse_args()


def run_pipeline(
    text: str,
    steps: List[str],
    dedup_threshold: float = 0.85,
    min_length: int = 50,
    pii_mode: str = "redact",
) -> Tuple[str, dict]:
    """Run the cleaning pipeline on text.

    Args:
        text: Raw input text.
        steps: Ordered list of step names to run.
        dedup_threshold: Threshold for dedup.
        min_length: Min length for quality filter.
        pii_mode: "redact" or "remove" for PII step.

    Returns:
        Tuple of (cleaned_text, all_stats_dict).
    """
    all_stats: dict = {}
    current_text = text

    for step in steps:
        step = step.strip()
        if step not in AVAILABLE_STEPS:
            print(f"Warning: unknown step '{step}', skipping", file=sys.stderr)
            continue

        if step == "clean_html":
            current_text = clean_html(current_text)
        elif step == "dedup":
            current_text, stats = deduplicate(current_text, threshold=dedup_threshold)
            all_stats["dedup"] = stats
        elif step == "filter":
            current_text, stats = filter_noise(current_text, min_length=min_length)
            all_stats["filter"] = stats
        elif step == "redact":
            current_text, stats = redact_pii(current_text, mode=pii_mode)
            all_stats["redact"] = stats

    # Compute overall stats
    all_stats["overall"] = {
        "input_chars": len(text),
        "output_chars": len(current_text),
        "reduction_pct": round(
            (len(text) - len(current_text)) / max(len(text), 1) * 100, 1
        ),
    }

    return current_text, all_stats


def main() -> None:
    args = parse_args()

    # Parse steps
    steps = [s.strip() for s in args.steps.split(",") if s.strip()]
    if not steps:
        steps = list(AVAILABLE_STEPS)

    # Read input
    if args.input:
        try:
            with open(args.input, "r", encoding="utf-8") as f:
                text = f.read()
        except FileNotFoundError:
            print(f"Error: input file '{args.input}' not found", file=sys.stderr)
            sys.exit(1)
        except Exception as e:
            print(f"Error reading input file: {e}", file=sys.stderr)
            sys.exit(1)
    else:
        # Read from stdin
        text = sys.stdin.read()

    if not text:
        print("Warning: empty input", file=sys.stderr)
        text = ""

    # Run pipeline
    cleaned_text, stats = run_pipeline(
        text,
        steps=steps,
        dedup_threshold=args.dedup_threshold,
        min_length=args.min_length,
        pii_mode=args.pii_mode,
    )

    # Output
    if args.stats_only:
        output = json.dumps(stats, ensure_ascii=False, indent=2)
    elif args.format == "json":
        output = json.dumps({
            "text": cleaned_text,
            "stats": stats,
        }, ensure_ascii=False)
    else:
        output = cleaned_text

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(output)
            if output and not output.endswith("\n"):
                f.write("\n")
    else:
        print(output)


if __name__ == "__main__":
    main()
