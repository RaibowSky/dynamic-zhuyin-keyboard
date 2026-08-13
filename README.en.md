# iOS-style Zhuyin Keyboard for Android

Android input method prototype for an iOS-style dynamic Zhuyin keyboard.

This project is an independently developed Android Zhuyin input method.

Except for third-party resources explicitly documented in this README and
`NOTICE.md`, this project does not include third-party source code, proprietary
dictionaries, trademark assets, or other copyrighted materials.

## Current Features

- Dynamic Zhuyin keyboard flow inspired by iOS-style input behavior.
- Stable key positions for the Zhuyin, number, and symbol layouts.
- Zhuyin candidate lookup from a generated dictionary asset.
- Continuous multi-syllable composition with phrase candidates, per-syllable sentence fallback, and tone-sandhi recovery for 一 and 不.
- Number and symbol input modes; English input is delegated to the user's other enabled system IMEs.
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
- updating candidates after every Zhuyin symbol and adding continuous-composition fallbacks;
- correcting editor-mode handling for password, URL, search, and ordinary text fields;
- repeatedly validating behavior with unit tests, a simulated `InputConnection`, Android emulators, and a physical device;
- documenting which features and commits were completed during Build Week.

All model-generated changes are reviewed, built, and tested against the real
application before they are accepted. Work completed before Build Week remains
part of the project baseline and is not presented as new event work.

## Build and Install

Requirements:

- JDK 17
- Android SDK 36.1
- Android Build Tools 36.1.0
- An Android 7.0 (API 24) or newer device or emulator

From Windows PowerShell, run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The generated APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To install directly on an Android device with USB debugging enabled:

```powershell
.\gradlew.bat installDebug
```

After installation, open the Dynamic Zhuyin Keyboard app:

1. Tap **Enable keyboard** and enable Dynamic Zhuyin Keyboard in Android's input-method settings.
2. Return to the app, tap **Switch keyboard**, and select Dynamic Zhuyin Keyboard.
3. Type Zhuyin in any text field; the candidate row updates after every symbol.

## Quick Test for Judges

- Enter several Zhuyin syllables continuously and inspect dynamic phrase candidates and per-syllable sentence fallback.
- Select a non-default candidate, then type the same reading again to verify local candidate learning.
- Open the main app to test learning pause/clear controls, manual dictionary entries, and import/export.
- Confirm that the ABC key switches to another enabled system IME, or opens the system IME picker when none is available.
- Confirm that password fields never learn and still let users reach their system keyboard for ASCII input, while Chrome's omnibox still accepts Chinese search input.
- The manifest declares no Internet permission; composition and candidate learning remain on the device.

## Dictionary Data

The currently bundled dictionary asset is:

- `app/src/main/assets/zhuyin_cedict.tsv`

The asset combines CC-CEDICT entries converted from Pinyin into Zhuyin keys
with McBopomofo multi-character phrase readings. McBopomofo aggregate phrase
frequency ranks the merged candidates; no underlying corpus is packaged. The
relevant scripts are:

- `tools/build_zhuyin_dictionary.py`
- `tools/rank_zhuyin_dictionary.py`
- `tools/merge_mcbopomofo_dictionary.py`

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

The bundled dictionary combines CC-CEDICT-derived entries with McBopomofo
phrase readings and aggregate frequency data. Keyboard
Bopomofo glyphs use a pinned subset of ToneOZ Pinyin WenKai. Sources,
transformations, and licenses are documented in `NOTICE.md`.
The subset covers U+3105-U+3129 and all five tone marks used by the keyboard,
including the first-tone macron U+02C9; its SHA-256 is
`7d2630c930012253c214100dae4fdccef582ed02be6bcbc313bed831ad672800`.

## Licensing

Except where otherwise noted, this project's original source code is licensed
under the [Apache License 2.0](LICENSE).

The root Apache-2.0 license does not relicense third-party material. The
generated data derived from CC-CEDICT is subject to CC BY-SA 4.0, the
McBopomofo phrase readings and aggregate frequency data are subject to its MIT
License and upstream data notices, and the ToneOZ font subset is subject to SIL Open
Font License 1.1. See [NOTICE.md](NOTICE.md) and
[NOTICE.zh-TW.md](NOTICE.zh-TW.md) for complete attribution, transformations,
and local license copies.
