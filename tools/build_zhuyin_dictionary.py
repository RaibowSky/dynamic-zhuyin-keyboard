import argparse
import gzip
import hashlib
import re
from collections import OrderedDict
from pathlib import Path


MCBOPOMOFO_PHRASE_OCC_SHA256 = (
    "0fc51c5245a8820e1003e3fa3fb2759b0d1b502a71da81bbfa265e9ac6c9fb5a"
)
# The CC-CEDICT URL serves MDBG's latest release and is not immutable. The
# archive checksum below pins the accepted rebuild input. It is newer than, and
# must not be presented as the source snapshot of, the currently bundled asset;
# see NOTICE.md for that provenance limitation.
CC_CEDICT_SOURCE_URL = (
    "https://www.mdbg.net/chinese/export/cedict/"
    "cedict_1_0_ts_utf-8_mdbg.txt.gz"
)
CC_CEDICT_RETRIEVED_DATE = "2026-07-15"
CC_CEDICT_ARCHIVE_SHA256 = (
    "33d79ec1cc91fd1bc76fe7e590723d474cfe6ab364648eef9b7b52677e897d87"
)


TONE = {"1": "ˉ", "2": "ˊ", "3": "ˇ", "4": "ˋ", "5": "˙", "0": "˙"}
INITIALS = OrderedDict(
    [
        ("zh", "ㄓ"),
        ("ch", "ㄔ"),
        ("sh", "ㄕ"),
        ("b", "ㄅ"),
        ("p", "ㄆ"),
        ("m", "ㄇ"),
        ("f", "ㄈ"),
        ("d", "ㄉ"),
        ("t", "ㄊ"),
        ("n", "ㄋ"),
        ("l", "ㄌ"),
        ("g", "ㄍ"),
        ("k", "ㄎ"),
        ("h", "ㄏ"),
        ("j", "ㄐ"),
        ("q", "ㄑ"),
        ("x", "ㄒ"),
        ("r", "ㄖ"),
        ("z", "ㄗ"),
        ("c", "ㄘ"),
        ("s", "ㄙ"),
    ]
)

FINALS = {
    "": "",
    "a": "ㄚ",
    "o": "ㄛ",
    "e": "ㄜ",
    "ê": "ㄝ",
    "eh": "ㄝ",
    "ai": "ㄞ",
    "ei": "ㄟ",
    "ao": "ㄠ",
    "ou": "ㄡ",
    "an": "ㄢ",
    "en": "ㄣ",
    "ang": "ㄤ",
    "eng": "ㄥ",
    "er": "ㄦ",
    "i": "ㄧ",
    "ia": "ㄧㄚ",
    "ie": "ㄧㄝ",
    "iao": "ㄧㄠ",
    "iu": "ㄧㄡ",
    "ian": "ㄧㄢ",
    "in": "ㄧㄣ",
    "iang": "ㄧㄤ",
    "ing": "ㄧㄥ",
    "u": "ㄨ",
    "ua": "ㄨㄚ",
    "uo": "ㄨㄛ",
    "uai": "ㄨㄞ",
    "ui": "ㄨㄟ",
    "uan": "ㄨㄢ",
    "un": "ㄨㄣ",
    "uang": "ㄨㄤ",
    "ong": "ㄨㄥ",
    "ueng": "ㄨㄥ",
    "v": "ㄩ",
    "ve": "ㄩㄝ",
    "van": "ㄩㄢ",
    "vn": "ㄩㄣ",
    "iong": "ㄩㄥ",
}

ZERO_INITIAL = {
    "yi": "ㄧ",
    "ya": "ㄧㄚ",
    "yo": "ㄧㄛ",
    "ye": "ㄧㄝ",
    "yai": "ㄧㄞ",
    "yao": "ㄧㄠ",
    "you": "ㄧㄡ",
    "yan": "ㄧㄢ",
    "yin": "ㄧㄣ",
    "yang": "ㄧㄤ",
    "ying": "ㄧㄥ",
    "wu": "ㄨ",
    "wa": "ㄨㄚ",
    "wo": "ㄨㄛ",
    "wai": "ㄨㄞ",
    "wei": "ㄨㄟ",
    "wan": "ㄨㄢ",
    "wen": "ㄨㄣ",
    "wang": "ㄨㄤ",
    "weng": "ㄨㄥ",
    "yu": "ㄩ",
    "yue": "ㄩㄝ",
    "yuan": "ㄩㄢ",
    "yun": "ㄩㄣ",
    "yong": "ㄩㄥ",
}

LINE_RE = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]+)\]\s+/.*/$")
PINYIN_RE = re.compile(r"^([a-zA-ZüÜ:êÊ]+)([0-5])$")


def convert_syllable(raw: str) -> str | None:
    raw = raw.strip().lower().replace("u:", "v").replace("ü", "v")
    match = PINYIN_RE.match(raw)
    if not match:
        return None

    body, tone = match.groups()
    if body in ZERO_INITIAL:
        return ZERO_INITIAL[body] + TONE[tone]

    initial = ""
    initial_zhuyin = ""
    for candidate, zhuyin in INITIALS.items():
        if body.startswith(candidate):
            initial = candidate
            initial_zhuyin = zhuyin
            break
    final = body[len(initial) :]

    if initial in {"zh", "ch", "sh", "r", "z", "c", "s"} and final == "i":
        final = ""
    elif initial in {"j", "q", "x"} and final.startswith("u"):
        final = "v" + final[1:]
    elif initial in {"n", "l"}:
        final = final.replace("v", "v")

    final_zhuyin = FINALS.get(final)
    if final_zhuyin is None:
        return None
    return initial_zhuyin + final_zhuyin + TONE[tone]


def strip_tones(zhuyin: str) -> str:
    return zhuyin.translate(str.maketrans("", "", "ˉ˙ˊˇˋ"))


def append_unique(bucket: dict[str, list[str]], key: str, value: str) -> None:
    if not key or not value:
        return
    values = bucket.setdefault(key, [])
    if value not in values:
        values.append(value)


def load_phrase_frequencies(source: Path) -> dict[str, int]:
    if not source.exists():
        raise FileNotFoundError(f"McBopomofo frequency source not found: {source}")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest != MCBOPOMOFO_PHRASE_OCC_SHA256:
        raise ValueError(
            "Unexpected McBopomofo phrase.occ SHA-256: "
            f"expected {MCBOPOMOFO_PHRASE_OCC_SHA256}, got {digest}"
        )

    frequencies: dict[str, int] = {}
    with source.open("r", encoding="utf-8") as file:
        for line_number, line in enumerate(file, start=1):
            parts = line.rstrip("\n").rsplit(maxsplit=1)
            if len(parts) != 2:
                raise ValueError(f"Invalid phrase.occ row at line {line_number}")
            phrase, raw_count = parts
            try:
                count = int(raw_count)
            except ValueError as error:
                raise ValueError(
                    f"Invalid phrase.occ count at line {line_number}: {raw_count}"
                ) from error
            if not phrase or count < 0:
                raise ValueError(f"Invalid phrase.occ row at line {line_number}")
            frequencies[phrase] = max(frequencies.get(phrase, 0), count)
    if not frequencies:
        raise ValueError(f"McBopomofo frequency source is empty: {source}")
    return frequencies


def verify_source(source: Path, expected_sha256: str, label: str) -> None:
    if not source.exists():
        raise FileNotFoundError(f"{label} source not found: {source}")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest != expected_sha256:
        raise ValueError(
            f"Unexpected {label} SHA-256: expected {expected_sha256}, got {digest}"
        )


def rank_candidates(candidates: list[str], frequencies: dict[str, int]) -> list[str]:
    # Python's sort is stable, so candidates with equal or unknown frequency
    # retain CC-CEDICT's original order.
    return sorted(candidates, key=lambda candidate: -frequencies.get(candidate, 0))


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    rebuild_output = root / "build" / "dictionary-rebuild"
    parser = argparse.ArgumentParser(
        description="Build the pinned Zhuyin dictionary rebuild baseline"
    )
    parser.add_argument("--source", type=Path, default=root / "cedict.txt.gz")
    parser.add_argument(
        "--target",
        type=Path,
        default=None,
    )
    parser.add_argument(
        "--license-file",
        type=Path,
        default=None,
    )
    args = parser.parse_args()
    if (args.target is None) != (args.license_file is None):
        parser.error("--target and --license-file must be specified together")
    source = args.source
    target = args.target or rebuild_output / "zhuyin_cedict.tsv"
    license_file = args.license_file or rebuild_output / "zhuyin_cedict_LICENSE.txt"
    target.parent.mkdir(parents=True, exist_ok=True)
    license_file.parent.mkdir(parents=True, exist_ok=True)
    frequency_source = root / "tools" / "data" / "mcbopomofo_phrase.occ"
    frequency_license = root / "tools" / "data" / "McBopomofo_LICENSE.txt"
    verify_source(source, CC_CEDICT_ARCHIVE_SHA256, "CC-CEDICT archive")
    frequencies = load_phrase_frequencies(frequency_source)
    mcbopomofo_license = frequency_license.read_text(encoding="utf-8").strip()

    entries: dict[str, list[str]] = {}
    parsed = 0
    skipped = 0
    with gzip.open(source, "rt", encoding="utf-8") as f:
        for line in f:
            if not line or line.startswith("#"):
                continue
            match = LINE_RE.match(line.rstrip("\n"))
            if not match:
                skipped += 1
                continue
            traditional, _simplified, pinyin = match.groups()
            syllables = []
            for token in pinyin.split():
                converted = convert_syllable(token)
                if converted is None:
                    syllables = []
                    break
                syllables.append(converted)
            if not syllables:
                skipped += 1
                continue

            toned_key = "".join(syllables)
            base_key = strip_tones(toned_key)
            append_unique(entries, toned_key, traditional)
            append_unique(entries, base_key, traditional)
            parsed += 1

    with target.open("w", encoding="utf-8", newline="\n") as f:
        f.write("# CC-CEDICT converted to Zhuyin by tools/build_zhuyin_dictionary.py\n")
        f.write("# Candidates ranked with McBopomofo phrase.occ frequency data\n")
        f.write("# key<TAB>candidate1 candidate2 ...\n")
        for key in sorted(entries.keys(), key=lambda k: (len(k), k)):
            ranked = rank_candidates(entries[key], frequencies)
            f.write(f"{key}\t{' '.join(ranked[:128])}\n")

    generated_asset_sha256 = hashlib.sha256(target.read_bytes()).hexdigest()
    license_file.write_text(
        "Source: CC-CEDICT from MDBG Chinese Dictionary\n"
        f"Download: {CC_CEDICT_SOURCE_URL}\n"
        f"Retrieved: {CC_CEDICT_RETRIEVED_DATE}\n"
        f"SHA-256: {CC_CEDICT_ARCHIVE_SHA256}\n"
        "License: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)\n"
        "License URL: https://creativecommons.org/licenses/by-sa/4.0/\n"
        "Changes: Traditional entries and Pinyin readings were converted into "
        "Zhuyin lookup keys; candidate lists were truncated and ranked as documented below.\n"
        "Generated by tools/build_zhuyin_dictionary.py from cedict.txt.gz.\n"
        f"Generated asset SHA-256: {generated_asset_sha256}\n"
        "Source URL note: the MDBG download URL is mutable; the source SHA-256 "
        "above identifies the accepted archive snapshot.\n"
        "\n"
        "Candidate ranking source: McBopomofo Source/Data/phrase.occ\n"
        "Upstream: https://github.com/openvanilla/McBopomofo\n"
        "Pinned commit: 14f672cd9296deb4ff87034b05003b15a1e796f5\n"
        "Upstream SHA-256: 2140ccad6945fd972dc0004ad44d2b4ba6ad50dd91dd883f51b72951fd01ed4e\n"
        f"LF-normalized SHA-256: {MCBOPOMOFO_PHRASE_OCC_SHA256}\n"
        "Retrieved: 2026-07-11\n"
        "License: MIT\n"
        "Transformation: CRLF line endings were normalized to LF. Exact phrase "
        "occurrence counts were used only to reorder "
        "CC-CEDICT candidates; no underlying source corpus is packaged in the "
        "Android app.\n"
        "\n"
        f"{mcbopomofo_license}\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"parsed={parsed} skipped={skipped} keys={len(entries)} "
        f"frequencies={len(frequencies)} target={target}"
    )


if __name__ == "__main__":
    main()
