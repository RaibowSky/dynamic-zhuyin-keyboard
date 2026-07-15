# Notices and Third-Party References

This file records external data sources and references used while developing the
project.

[繁體中文版本](NOTICE.zh-TW.md)

## Bundled Third-Party Data

### CC-CEDICT

The file `app/src/main/assets/zhuyin_cedict.tsv` is generated from CC-CEDICT.

- Source project: CC-CEDICT
- Download page: https://www.mdbg.net/chinese/dictionary?page=cc-cedict
- Project/editor page: https://cc-cedict.org/
- License: Creative Commons Attribution-ShareAlike 4.0 International
  (CC BY-SA 4.0)
- License URL: https://creativecommons.org/licenses/by-sa/4.0/
- Local conversion script: `tools/build_zhuyin_dictionary.py`
- Bundled asset SHA-256: `50f057aab946c7da7e22fbad1dd845c5d406894c8ce03100817c101e5b7c0ea4`

Transformation performed:

- Read Traditional Chinese entries and Pinyin readings from `cedict.txt.gz`.
- Convert Pinyin syllables into Zhuyin symbols and tone marks.
- Generate keyed candidate rows in `key<TAB>candidate1 candidate2 ...` format.
- Include both toned and untoned lookup keys for IME candidate lookup.
- Rank candidates using the separately attributed McBopomofo aggregate
  phrase-frequency data described below.

The generated file is a derived data asset and should continue to carry
CC-CEDICT attribution and compatible license handling when redistributed.

The exact CC-CEDICT archive used for the currently bundled asset was not
retained, so its upstream release and source-archive checksum cannot be stated
or reproduced byte-for-byte. The conversion script now pins a newer rebuild
baseline downloaded from MDBG on 2026-07-15 with SHA-256
`33d79ec1cc91fd1bc76fe7e590723d474cfe6ab364648eef9b7b52677e897d87`.
That verified baseline produces a different generated asset and is not being
claimed as the source snapshot of the asset currently in the repository. The
MDBG download URL points to its latest release and is mutable; the checksum is
the identity of the accepted rebuild input.
To avoid accidentally replacing the current asset with that different
baseline, the conversion script writes to `build/dictionary-rebuild/` by
default. Replacing the bundled target requires explicit `--target` and
`--license-file` paths and corresponding notice updates.

### McBopomofo aggregate phrase-frequency data

`tools/data/mcbopomofo_phrase.occ` is a line-ending-normalized copy of
McBopomofo's aggregate phrase-occurrence table. CRLF was normalized to LF; the
phrase and count content is unchanged.

- Source project: McBopomofo
- Repository: https://github.com/openvanilla/McBopomofo
- Upstream file: https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/Source/Data/phrase.occ
- Pinned commit: `14f672cd9296deb4ff87034b05003b15a1e796f5`
- Retrieved: 2026-07-11
- Upstream SHA-256: `2140ccad6945fd972dc0004ad44d2b4ba6ad50dd91dd883f51b72951fd01ed4e`
- LF-normalized SHA-256: `0fc51c5245a8820e1003e3fa3fb2759b0d1b502a71da81bbfa265e9ac6c9fb5a`
- License: MIT
- License URL: https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/LICENSE.txt
- Copyright: Copyright (c) 2011-2026 Mengjuei Hsieh et al.
- Local license copy: `tools/data/McBopomofo_LICENSE.txt`

The exact phrase counts are used only to reorder candidates already generated
from CC-CEDICT. This process does not import McBopomofo dictionary entries,
readings, application source code, or the corpus underlying the aggregate
counts. The source frequency file is retained in this repository as a
build-time input for reproducibility; it is not packaged as an Android runtime
asset. The generated asset carries both projects' attribution in
`app/src/main/assets/zhuyin_cedict_LICENSE.txt`.

### ToneOZ Pinyin WenKai Bopomofo subset

`app/src/main/assets/bopomofo.ttf` is a reproducible subset of ToneOZ Pinyin
WenKai Regular containing only Bopomofo symbols and tone marks.

- Source project: ToneOZ Pinyin WenKai
- Repository: https://github.com/jeffreyxuan/toneoz-font-pinyin-wenkai
- Font lineage: Fontworks Klee -> LXGW WenKai -> ToneOZ Pinyin WenKai
- Additional copyright holders retained in the font metadata:
  - Copyright 2021 LXGW (https://github.com/lxgw/LxgwWenKai)
  - Copyright 2020 The Klee Project Authors (https://github.com/fontworks-fonts/Klee)
- Pinned commit: `55facb136a7b22afd60ddf30ac0226661614d870`
- Upstream file: `fonts/ttf/ToneOZ-Pinyin-WenKai-Regular.ttf`
- Upstream SHA-256: `153a826f06fd6d578adfd7235c72d3b5298698a319a48ff088dff43bd87c83e8`
- License: SIL Open Font License 1.1
- Local license: `app/src/main/assets/bopomofo_OFL.txt`
- Local notice: `app/src/main/assets/bopomofo_FONT_NOTICE.txt`
- Rebuild script: `tools/build_bopomofo_font.py`

The subset contains U+3105-U+3129 and U+02C7, U+02C9, U+02CA, U+02CB, and
U+02D9.
The font software was modified only by subsetting; the retained glyph outlines
were not modified. The bundled subset SHA-256 is
`7d2630c930012253c214100dae4fdccef582ed02be6bcbc313bed831ad672800`.

## Non-Bundled References

This project was implemented independently.

During development, publicly available Chinese input methods and linguistic
resources were consulted to understand general input workflows, keyboard
interaction patterns, and Zhuyin conventions.

These references were used only for behavior study, design comparison, and
linguistic validation. Unless explicitly documented in the bundled third-party
data section above, this repository does not include third-party source code,
proprietary dictionaries, visual assets, trademark assets, scraped datasets, or
other copyrighted materials from those references.

The bundled dictionary entries and readings are generated only from CC-CEDICT.
Their default candidate order is informed by the separately attributed
McBopomofo aggregate phrase-frequency data documented above.

## Repository Policy for Future Data

Before adding any new dictionary, word-frequency list, keyboard layout asset, or
other third-party material:

1. Confirm the license permits the intended use.
2. Add source URL, retrieval date, and license terms to this file.
3. Keep generated data separate from scripts when possible.
4. Document transformations clearly enough that the data can be regenerated.
5. Do not commit scraped/proprietary data unless the license is understood and
   compatible with redistribution.
