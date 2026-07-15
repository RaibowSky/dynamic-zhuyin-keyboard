# iOS-style Zhuyin Keyboard for Android

Android input method prototype for an iOS-style dynamic Zhuyin keyboard.

This project is an independently developed Android Zhuyin input method.

Except for third-party resources explicitly documented in this README and
`NOTICE.md`, this project does not include third-party source code, proprietary
dictionaries, trademark assets, or other copyrighted materials.

## Current Features

- Dynamic Zhuyin keyboard flow inspired by iOS-style input behavior.
- Stable key positions for Zhuyin and English layouts.
- Zhuyin candidate lookup from a generated dictionary asset.
- Continuous multi-syllable composition with phrase candidates, per-syllable sentence fallback, and tone-sandhi recovery for 一 and 不.
- English, number, and symbol input modes.
- Offline processing without network permission.

## OpenAI Build Week: Codex and GPT-5.6

This was an existing working project before OpenAI Build Week. During Build Week,
Codex and GPT-5.6 are being used to meaningfully extend and stabilize it rather
than to generate a new project from scratch.

Their role includes:

- reviewing the existing Android/Kotlin codebase and identifying high-impact reliability work;
- implementing and testing improvements to Zhuyin composition and candidate behavior;
- completing on-device user-dictionary learning controls, including pause, clear, reset, import, and export flows;
- improving large-dictionary processing, transaction safety, and build verification;
- documenting which features and commits were completed during Build Week.

All model-generated changes are reviewed, built, and tested against the real
application before they are accepted. Work completed before Build Week remains
part of the project baseline and is not presented as new event work.

## Dictionary Data

The currently bundled dictionary asset is:

- `app/src/main/assets/zhuyin_cedict.tsv`

Its entries and readings were generated from CC-CEDICT data downloaded from
MDBG and converted from Pinyin readings into Zhuyin keys. Default candidate
ordering is informed by McBopomofo aggregate phrase-frequency data; this does
not add McBopomofo entries or its underlying corpus. The relevant scripts are:

- `tools/build_zhuyin_dictionary.py`
- `tools/rank_zhuyin_dictionary.py`

See `NOTICE.md`, `tools/data/README.md`, and
`app/src/main/assets/zhuyin_cedict_LICENSE.txt` for source and license
attribution.

## Privacy

The keyboard currently does not request network permission. Typed content is
processed locally on the device and is not uploaded.

Privacy policies:

- `PrivacyPolicy.md`
- `PrivacyPolicy.zh-TW.md`

## References

This project was implemented independently.

During development, several publicly available Chinese input methods and
linguistic resources were consulted to understand general input workflows,
keyboard interaction patterns, and Zhuyin conventions.

Unless explicitly documented in `NOTICE.md`, this repository does not contain
third-party source code, proprietary dictionaries, visual assets, or other
copyrighted materials.

The bundled dictionary entries and readings come from CC-CEDICT. Default
candidate ordering uses McBopomofo aggregate phrase-frequency data. Keyboard
Bopomofo glyphs use a pinned subset of ToneOZ Pinyin WenKai. Sources,
transformations, and licenses are documented in `NOTICE.md`.
The subset covers U+3105-U+3129 and all five tone marks used by the keyboard,
including the first-tone macron U+02C9; its SHA-256 is
`7d2630c930012253c214100dae4fdccef582ed02be6bcbc313bed831ad672800`.

## Licensing

No open-source license has been granted for this repository's original project
code yet. All rights are reserved by the project owner unless a `LICENSE` file is
added later.

Third-party material retains its own license. The generated dictionary derived
from CC-CEDICT is subject to CC BY-SA 4.0, the McBopomofo aggregate
phrase-frequency data used for candidate ordering is subject to the MIT
License, and the ToneOZ font subset is subject to SIL Open Font License 1.1.
See `NOTICE.md` for complete attribution.
