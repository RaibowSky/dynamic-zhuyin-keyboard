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

Transformation performed:

- Read Traditional Chinese entries and Pinyin readings from `cedict.txt.gz`.
- Convert Pinyin syllables into Zhuyin symbols and tone marks.
- Generate keyed candidate rows in `key<TAB>candidate1 candidate2 ...` format.
- Include both toned and untoned lookup keys for IME candidate lookup.
- Rank candidates using the separately attributed McBopomofo aggregate
  phrase-frequency data described below.

The generated file is a derived data asset and should continue to carry
CC-CEDICT attribution and compatible license handling when redistributed.

### McBopomofo aggregate phrase-frequency data

`tools/data/mcbopomofo_phrase.occ` is an unmodified copy of McBopomofo's
aggregate phrase-occurrence table.

- Source project: McBopomofo
- Repository: https://github.com/openvanilla/McBopomofo
- Upstream file: https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/Source/Data/phrase.occ
- Pinned commit: `14f672cd9296deb4ff87034b05003b15a1e796f5`
- Retrieved: 2026-07-11
- SHA-256: `2140ccad6945fd972dc0004ad44d2b4ba6ad50dd91dd883f51b72951fd01ed4e`
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
