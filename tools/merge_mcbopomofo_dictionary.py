"""Merge pinned McBopomofo phrase readings into the bundled Zhuyin dictionary."""

import argparse
import hashlib
from pathlib import Path

from build_zhuyin_dictionary import (
    append_unique,
    load_phrase_frequencies,
    rank_candidates,
    strip_tones,
)


MCBOPOMOFO_COMMIT = "14f672cd9296deb4ff87034b05003b15a1e796f5"
MCBOPOMOFO_MAPPINGS_SHA256 = (
    "51715ee5c731f9994be3b168d7681ed9692b8c335ae6fc2e7cfd4af6fd0e0781"
)
MAX_CANDIDATES_PER_KEY = 128


def verify_sha256(source: Path, expected: str) -> None:
    if not source.exists():
        raise FileNotFoundError(f"McBopomofo mappings not found: {source}")
    actual = hashlib.sha256(source.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError(
            f"Unexpected McBopomofo mappings SHA-256: expected {expected}, got {actual}"
        )


def load_dictionary(source: Path) -> dict[str, list[str]]:
    entries: dict[str, list[str]] = {}
    with source.open("r", encoding="utf-8") as file:
        for line_number, line in enumerate(file, start=1):
            clean = line.rstrip("\n")
            if not clean or clean.startswith("#"):
                continue
            key, separator, raw_candidates = clean.partition("\t")
            if not separator or not key or not raw_candidates:
                raise ValueError(f"Invalid dictionary row at line {line_number}")
            for candidate in raw_candidates.split():
                append_unique(entries, key, candidate)
    if not entries:
        raise ValueError(f"Dictionary is empty: {source}")
    return entries


def merge_mappings(
    entries: dict[str, list[str]],
    source: Path,
) -> tuple[int, int, int]:
    verify_sha256(source, MCBOPOMOFO_MAPPINGS_SHA256)
    original_keys = len(entries)
    original_pairs = sum(len(candidates) for candidates in entries.values())
    parsed = 0

    with source.open("r", encoding="utf-8") as file:
        for line_number, line in enumerate(file, start=1):
            parts = line.strip().split()
            if len(parts) < 2:
                raise ValueError(f"Invalid BPMFMappings row at line {line_number}")
            phrase, *syllables = parts
            toned_key = "".join(syllables)
            untoned_key = strip_tones(toned_key)
            if not phrase or not toned_key or not untoned_key:
                raise ValueError(f"Invalid BPMFMappings row at line {line_number}")
            append_unique(entries, toned_key, phrase)
            append_unique(entries, untoned_key, phrase)
            parsed += 1

    added_keys = len(entries) - original_keys
    added_pairs = sum(len(candidates) for candidates in entries.values()) - original_pairs
    return parsed, added_keys, added_pairs


def write_dictionary(
    target: Path,
    entries: dict[str, list[str]],
    frequencies: dict[str, int],
) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8", newline="\n") as file:
        file.write("# CC-CEDICT converted to Zhuyin by tools/build_zhuyin_dictionary.py\n")
        file.write(
            "# Extended with McBopomofo BPMFMappings.txt and ranked with phrase.occ\n"
        )
        file.write(f"# McBopomofo pinned commit: {MCBOPOMOFO_COMMIT}\n")
        file.write("# key<TAB>candidate1 candidate2 ...\n")
        for key in sorted(entries, key=lambda value: (len(value), value)):
            candidates = rank_candidates(entries[key], frequencies)
            file.write(f"{key}\t{' '.join(candidates[:MAX_CANDIDATES_PER_KEY])}\n")


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Build a comparison dictionary extended with McBopomofo phrases"
    )
    parser.add_argument(
        "--base",
        type=Path,
        default=root / "app" / "src" / "main" / "assets" / "zhuyin_cedict.tsv",
    )
    parser.add_argument(
        "--mappings",
        type=Path,
        default=root / "tools" / "data" / "mcbopomofo_bpmf_mappings.txt",
    )
    parser.add_argument(
        "--target",
        type=Path,
        default=root / "build" / "dictionary-comparison" / "zhuyin_cedict.tsv",
    )
    args = parser.parse_args()

    frequencies = load_phrase_frequencies(root / "tools" / "data" / "mcbopomofo_phrase.occ")
    entries = load_dictionary(args.base)
    parsed, added_keys, added_pairs = merge_mappings(entries, args.mappings)
    write_dictionary(args.target, entries, frequencies)
    digest = hashlib.sha256(args.target.read_bytes()).hexdigest()
    print(
        f"parsed_mappings={parsed} added_keys={added_keys} added_pairs={added_pairs} "
        f"total_keys={len(entries)} bytes={args.target.stat().st_size} "
        f"sha256={digest} target={args.target}"
    )


if __name__ == "__main__":
    main()
