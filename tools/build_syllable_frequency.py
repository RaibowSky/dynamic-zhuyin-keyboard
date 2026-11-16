"""Derive how often each Zhuyin syllable is typed, for key-target prediction.

Inputs are the pinned McBopomofo files already used for the dictionary
(MIT License): phrase.occ gives word counts and BPMFMappings.txt gives
readings. A word's count is added to every syllable of its reading. A single
character with several readings (的: ㄉㄜ˙, ㄉㄧˊ, ㄉㄧˋ) is split in proportion to
how often each reading occurs for it inside multi-character words.

Usage from the repository root:
    py tools/build_syllable_frequency.py          # regenerate the asset
    py tools/build_syllable_frequency.py --check  # verify it is up to date
"""
from __future__ import annotations

import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OCC = ROOT / "tools" / "data" / "mcbopomofo_phrase.occ"
MAPPINGS = ROOT / "tools" / "data" / "mcbopomofo_bpmf_mappings.txt"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "zhuyin_syllable_frequency.tsv"
ZHUYIN = set("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ")
TONES = set("ˊˇˋ˙")


def valid(syllable: str) -> bool:
    base = syllable[:-1] if syllable[-1:] in TONES else syllable
    return 1 <= len(base) <= 3 and all(ch in ZHUYIN for ch in base)


def build() -> str:
    readings: dict[str, list[list[str]]] = defaultdict(list)
    for line in MAPPINGS.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) < 2:
            continue
        phrase, sounds = parts[0], parts[1:]
        if len(sounds) == len(phrase) and all(valid(s) for s in sounds):
            readings[phrase].append(sounds)

    counts: dict[str, int] = {}
    for line in OCC.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            counts[parts[0]] = int(parts[1])

    # How often each character takes each reading inside unambiguous words.
    char_reading: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))
    for phrase, options in readings.items():
        if len(phrase) > 1 and len(options) == 1 and phrase in counts:
            for ch, sound in zip(phrase, options[0]):
                char_reading[ch][sound] += counts[phrase]

    frequency: dict[str, float] = defaultdict(float)
    for phrase, count in counts.items():
        options = readings.get(phrase)
        if not options:
            continue
        if len(phrase) == 1 and len(options) > 1:
            weights = [char_reading[phrase].get(o[0], 0.0) for o in options]
            total = sum(weights)
            shares = [w / total for w in weights] if total > 0 else [1 / len(options)] * len(options)
        else:
            shares = [1 / len(options)] * len(options)
        for option, share in zip(options, shares):
            for sound in option:
                frequency[sound] += count * share

    rows = sorted((s, round(c)) for s, c in frequency.items() if round(c) > 0)
    header = [
        "# Zhuyin syllable frequency for key-target prediction.",
        "# Derived by tools/build_syllable_frequency.py from McBopomofo phrase.occ and",
        "# BPMFMappings.txt (MIT License, https://github.com/openvanilla/McBopomofo).",
        "# syllable<TAB>weighted occurrence count; no tone mark means first tone",
    ]
    return "\n".join(header + [f"{s}\t{c}" for s, c in rows]) + "\n"


def main() -> int:
    text = build()
    if "--check" in sys.argv:
        if OUTPUT.read_text(encoding="utf-8") != text:
            print(f"{OUTPUT} is out of date; run without --check")
            return 1
        print("up to date")
        return 0
    OUTPUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUTPUT} ({len(text.splitlines()) - 4} syllables)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
