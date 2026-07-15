import argparse
import hashlib
import tempfile
from pathlib import Path


UPSTREAM_COMMIT = "55facb136a7b22afd60ddf30ac0226661614d870"
UPSTREAM_URL = (
    "https://raw.githubusercontent.com/jeffreyxuan/"
    f"toneoz-font-pinyin-wenkai/{UPSTREAM_COMMIT}/"
    "fonts/ttf/ToneOZ-Pinyin-WenKai-Regular.ttf"
)
UPSTREAM_SHA256 = "153a826f06fd6d578adfd7235c72d3b5298698a319a48ff088dff43bd87c83e8"
OUTPUT_SHA256 = "7d2630c930012253c214100dae4fdccef582ed02be6bcbc313bed831ad672800"
REQUIRED_FONTTOOLS_VERSION = "4.63.0"
UNICODES = "U+02C7,U+02C9,U+02CA,U+02CB,U+02D9,U+3105-3129"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Build the bundled Bopomofo font subset")
    parser.add_argument(
        "--source",
        type=Path,
        default=root / "ToneOZ-Pinyin-WenKai-Regular.ttf",
        help=f"Pinned upstream font downloaded from {UPSTREAM_URL}",
    )
    parser.add_argument(
        "--target",
        type=Path,
        default=root / "app" / "src" / "main" / "assets" / "bopomofo.ttf",
    )
    args = parser.parse_args()

    if not args.source.exists():
        raise FileNotFoundError(
            f"Upstream font not found: {args.source}\nDownload the pinned file from {UPSTREAM_URL}"
        )
    source_hash = sha256(args.source)
    if source_hash != UPSTREAM_SHA256:
        raise ValueError(
            f"Unexpected upstream font SHA-256: expected {UPSTREAM_SHA256}, got {source_hash}"
        )

    try:
        import fontTools
        from fontTools import subset
    except ImportError as error:
        raise RuntimeError(
            "Install the pinned font tooling with "
            "python -m pip install -r tools/requirements-fonts.txt"
        ) from error
    if fontTools.__version__ != REQUIRED_FONTTOOLS_VERSION:
        raise RuntimeError(
            f"fonttools {REQUIRED_FONTTOOLS_VERSION} is required; found {fontTools.__version__}"
        )

    args.target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        prefix="bopomofo-",
        suffix=".ttf",
        dir=args.target.parent,
        delete=False,
    ) as temporary:
        temporary_path = Path(temporary.name)
    temporary_path.unlink()
    try:
        subset.main(
            [
                str(args.source),
                f"--output-file={temporary_path}",
                f"--unicodes={UNICODES}",
                "--glyph-names",
                "--symbol-cmap",
                "--legacy-cmap",
                "--name-IDs=*",
                "--name-languages=*",
                "--name-legacy",
                "--layout-features=*",
                "--no-recalc-timestamp",
            ]
        )
        output_hash = sha256(temporary_path)
        if output_hash != OUTPUT_SHA256:
            raise ValueError(
                f"Unexpected subset SHA-256: expected {OUTPUT_SHA256}, got {output_hash}"
            )
        temporary_path.replace(args.target)
    finally:
        temporary_path.unlink(missing_ok=True)

    print(f"source={args.source} target={args.target} sha256={OUTPUT_SHA256}")


if __name__ == "__main__":
    main()
