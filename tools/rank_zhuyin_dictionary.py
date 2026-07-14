"""Re-rank an existing generated Zhuyin dictionary reproducibly."""

import os
from pathlib import Path

from build_zhuyin_dictionary import load_phrase_frequencies, rank_candidates


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    dictionary = root / "app" / "src" / "main" / "assets" / "zhuyin_cedict.tsv"
    frequency_source = root / "tools" / "data" / "mcbopomofo_phrase.occ"
    frequencies = load_phrase_frequencies(frequency_source)
    if not frequencies:
        raise SystemExit(f"No frequency data found in {frequency_source}")

    temporary = dictionary.with_suffix(".tsv.tmp")
    rows = 0
    with dictionary.open("r", encoding="utf-8") as source, temporary.open(
        "w", encoding="utf-8", newline="\n"
    ) as target:
        attribution_written = False
        for line in source:
            if line.startswith("#"):
                if "McBopomofo phrase.occ" in line:
                    attribution_written = True
                target.write(line.rstrip("\n") + "\n")
                continue
            if not attribution_written:
                target.write("# Candidates ranked with McBopomofo phrase.occ frequency data\n")
                attribution_written = True
            clean = line.rstrip("\n")
            if not clean:
                continue
            key, separator, values = clean.partition("\t")
            if not separator:
                raise ValueError(f"Invalid dictionary row: {clean[:80]}")
            candidates = values.split()
            target.write(f"{key}\t{' '.join(rank_candidates(candidates, frequencies))}\n")
            rows += 1

    os.replace(temporary, dictionary)
    print(f"ranked_rows={rows} frequencies={len(frequencies)} target={dictionary}")


if __name__ == "__main__":
    main()
