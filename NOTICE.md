# Notices and Third-Party References

This file records external data sources and references used while developing the
project.

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

The generated file is a derived data asset and should continue to carry
CC-CEDICT attribution and compatible license handling when redistributed.

## Non-Bundled References

These references influenced behavior checks or design decisions, but their
source code, visual assets, proprietary dictionaries, and bundled data are not
included in this repository.

### Apple iOS Zhuyin Keyboard

- Used for behavioral observation of dynamic Zhuyin keyboard flow.
- Reference material included a user-provided screen recording.
- No Apple source code, artwork, or private data is included.
- This project is not affiliated with Apple.

### Sogou Zhuyin / Sogou Input Method

- Used as a comparison reference for input method behavior and interaction
  concepts.
- No Sogou source code, artwork, proprietary dictionary, or data package is
  included.
- This project is not affiliated with Sogou.

### Taiwan Ministry of Education Dictionary Resources

- Used during earlier experimentation and checking of Mandarin/Zhuyin data.
- No Ministry of Education dictionary dump is bundled in the current repository
  state.
- If Ministry of Education data is reintroduced later, add the exact source URL,
  dataset name, retrieval date, license terms, and transformation notes here
  before committing the data.

Commonly referenced Ministry of Education dictionary resources include:

- Revised Mandarin Chinese Dictionary, Ministry of Education / National Academy
  for Educational Research: https://dict.revised.moe.edu.tw/
- Ministry of Education dictionary public authorization information should be
  verified from the official source before redistributing any derived data.

## Repository Policy for Future Data

Before adding any new dictionary, word-frequency list, keyboard layout asset, or
other third-party material:

1. Confirm the license permits the intended use.
2. Add source URL, retrieval date, and license terms to this file.
3. Keep generated data separate from scripts when possible.
4. Document transformations clearly enough that the data can be regenerated.
5. Do not commit scraped/proprietary data unless the license is understood and
   compatible with redistribution.
