"""Rebuild/check all dictionary artifacts from retained checksum-pinned sources."""
import argparse
import hashlib
import subprocess
import sys
from pathlib import Path
from build_zhuyin_dictionary import CC_CEDICT_ARCHIVE_SHA256
from merge_mcbopomofo_dictionary import MCBOPOMOFO_COMMIT, MCBOPOMOFO_MAPPINGS_SHA256

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    out = ROOT / "build/dictionary-rebuild"
    out.mkdir(parents=True, exist_ok=True)
    base = out / "base.tsv"
    license_file = out / "zhuyin_cedict_LICENSE.txt"
    target = out / "zhuyin_cedict.tsv"
    subprocess.run([sys.executable, str(ROOT / "tools/build_zhuyin_dictionary.py"),
                    "--target", str(base), "--license-file", str(license_file)], check=True)
    subprocess.run([sys.executable, str(ROOT / "tools/merge_mcbopomofo_dictionary.py"),
                    "--base", str(base), "--target", str(target)], check=True)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    text = license_file.read_text(encoding="utf-8")
    text = text.replace(hashlib.sha256(base.read_bytes()).hexdigest(), digest)
    text += (f"\nPhrase readings: McBopomofo BPMFMappings.txt\nPinned commit: {MCBOPOMOFO_COMMIT}\n"
             f"Source SHA-256: {MCBOPOMOFO_MAPPINGS_SHA256}\n"
             "Transformation: merge toned/untoned keys, deduplicate and rank by phrase.occ.\n"
             "BPMFMappings originated from libtabe tsi.src (BSD); see McBopomofo upstream data notices.\n"
             "Retained CC-CEDICT input: tools/data/cedict.txt.gz\n"
             f"Retained archive SHA-256: {CC_CEDICT_ARCHIVE_SHA256}\n")
    license_file.write_text(text, encoding="utf-8", newline="\n")
    for file in (target, license_file):
        destination = ASSETS / file.name
        if args.check:
            if destination.read_bytes() != file.read_bytes():
                raise SystemExit(f"Not reproducible: {destination}")
        else:
            destination.write_bytes(file.read_bytes())
    print(f"Reproducible dictionary SHA-256: {digest}")


if __name__ == "__main__":
    main()
