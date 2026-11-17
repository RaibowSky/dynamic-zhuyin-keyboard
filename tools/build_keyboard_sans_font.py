"""Build the default keyboard font: a subset of Noto Sans TC (思源黑體).

Noto Sans CJK is Google's release of the Source Han Sans design (SIL Open Font
License 1.1). The subset keeps ASCII, CJK and full-width punctuation, Bopomofo
with tone marks, and every Han character used by the bundled dictionary, so
candidates render in one typeface; anything else falls back to the system font.

Usage from the repository root:
    py tools/build_keyboard_sans_font.py --source NotoSansTC-Regular.otf
    py tools/build_keyboard_sans_font.py --source NotoSansTC-Regular.otf --check
"""
import argparse
import hashlib
import sys
import tempfile
from pathlib import Path

UPSTREAM_COMMIT = "523d033d6cb47f4a80c58a35753646f5c3608a78"  # tag Sans2.004
UPSTREAM_URL = (
    "https://raw.githubusercontent.com/notofonts/noto-cjk/"
    f"{UPSTREAM_COMMIT}/Sans/SubsetOTF/TC/NotoSansTC-Regular.otf"
)
UPSTREAM_SHA256 = "5bab0cb3c1cf89dde07c4a95a4054b195afbcfe784d69d75c340780712237537"
REQUIRED_FONTTOOLS_VERSION = "4.63.0"
ROOT = Path(__file__).resolve().parents[1]
DICTIONARY = ROOT / "app" / "src" / "main" / "assets" / "zhuyin_cedict.tsv"
TARGET = ROOT / "app" / "src" / "main" / "assets" / "keyboard_sans.otf"
FIXED_RANGES = [
    (0x20, 0x7E),      # ASCII
    (0xA0, 0xFF),      # Latin-1 punctuation
    (0x2010, 0x206F),  # general punctuation
    (0x2190, 0x21FF),  # arrows
    (0x2460, 0x24FF),  # enclosed numbers
    (0x2500, 0x25FF),  # box drawing and shapes
    (0x3000, 0x303F),  # CJK punctuation
    (0x3105, 0x312F),  # Bopomofo
    (0x31A0, 0x31BF),  # Bopomofo extended
    (0xFE30, 0xFE4F),  # CJK compatibility forms
    (0xFF00, 0xFFEF),  # full-width forms
]
TONE_MARKS = [0x02C7, 0x02C9, 0x02CA, 0x02CB, 0x02D9]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def codepoints() -> list[int]:
    wanted = set(TONE_MARKS)
    for start, end in FIXED_RANGES:
        wanted.update(range(start, end + 1))
    for line in DICTIONARY.read_text(encoding="utf-8").splitlines():
        if not line.startswith("#"):
            wanted.update(ord(ch) for ch in line if ord(ch) >= 0x3400)
    return sorted(wanted)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", type=Path, required=True, help=f"pinned upstream file from {UPSTREAM_URL}")
    parser.add_argument("--check", action="store_true", help="verify the bundled subset is up to date")
    args = parser.parse_args()
    if sha256(args.source) != UPSTREAM_SHA256:
        raise ValueError(f"Unexpected upstream SHA-256; download {UPSTREAM_URL}")
    import fontTools
    from fontTools import subset
    if fontTools.__version__ != REQUIRED_FONTTOOLS_VERSION:
        raise RuntimeError(f"fonttools {REQUIRED_FONTTOOLS_VERSION} is required (tools/requirements-fonts.txt)")

    with tempfile.TemporaryDirectory() as temporary:
        output = Path(temporary) / "keyboard_sans.otf"
        subset.main([
            str(args.source),
            f"--output-file={output}",
            "--unicodes=" + ",".join(f"U+{cp:04X}" for cp in codepoints()),
            "--layout-features=*",
            "--name-IDs=*",
            "--name-languages=*",
            "--no-hinting",
            "--no-recalc-timestamp",
        ])
        if args.check:
            if not TARGET.exists() or TARGET.read_bytes() != output.read_bytes():
                print(f"{TARGET} is out of date; run without --check")
                return 1
            print(f"up to date sha256={sha256(TARGET)}")
            return 0
        TARGET.write_bytes(output.read_bytes())
    print(f"wrote {TARGET} ({TARGET.stat().st_size} bytes) sha256={sha256(TARGET)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
