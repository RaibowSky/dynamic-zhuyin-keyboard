# Dynamic Zhuyin Keyboard for Android

A Zhuyin (Bopomofo / ㄅㄆㄇ) input method that runs entirely on the device.
It uses a dynamic-keyboard layout inspired by iOS Zhuyin input behavior while
staying fully offline and privacy-first.

[中文 README](README.md)

## What is this?

Dynamic Zhuyin Keyboard keeps Zhuyin keys in fixed positions and updates only
the candidate row and the available next keys as you type, reducing key
movement while offering candidates from a bundled on-device dictionary.

## Why?

- Give Traditional Chinese users an iOS-like dynamic Zhuyin typing feel.
- Fully offline: no Internet permission is requested; composition and candidate
  learning stay on the device.
- Privacy-first: typed content is not collected or uploaded.

## Key features

- Dynamic Zhuyin keyboard with stable, non-jumping key positions.
- Zhuyin candidate lookup from a locally generated dictionary asset.
- Continuous multi-syllable composition with phrase candidates, per-syllable
  sentence fallback, and 一/不 tone-sandhi recovery.
- Multiple input modes: Zhuyin, English, numbers, and symbols.
- On-device candidate learning that ranks frequently used characters and words
  higher over time.
- A user dictionary with manual entries, learning pause/clear, and import/export.
- First-tone marks are merged into the space key, so no separate first-tone key
  is shown.

## Installation

A Build Week demo APK is available on the
[Releases](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/releases) page.
That build is a historical debug demo; stable releases will be published through
a separate release process (see Roadmap below).

You can also build from source (see Build and install below).

## Build and install

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

1. Tap **Enable keyboard** and enable Dynamic Zhuyin Keyboard in Android's
   input-method settings.
2. Return to the app, tap **Switch keyboard**, and select Dynamic Zhuyin Keyboard.
3. Type Zhuyin in any text field; the candidate row updates after every symbol.

## Screenshots

> **To be added**: a screenshot (or short GIF) of the keyboard and candidate row
> taken on a real device.

## Roadmap and known limitations

The project is still early-stage and does not yet have a stable release process.
Planned or in-progress items include:

- System light/dark theme (issue [#1](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/1)).
- Reproducible dictionary build (issue [#2](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/2)).
- Signing, versioning, and stable APK releases (issue [#3](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/3)).
- Long-term application ID (issue [#7](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/7)).
- Configurable keyboard fonts with local font import (issue [#8](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/8)).
- Delegating English input to an external IME (issue [#9](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/9)).
- Continuous Zhuyin sentence decoding (issue [#10](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/10)).
- Removing the first-prefix-lookup full-dictionary scan (issue [#11](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/11)).

Known limitations:

- Only a debug APK is provided; no stable signed release exists yet.
- System light/dark theme is not yet applied to every screen.

## Privacy

The keyboard does not request Internet permission. Typed content is processed
locally on the device and is not uploaded. Candidate learning and the user
dictionary are also stored on the device.

Privacy policies:

- `PrivacyPolicy.md`
- `PrivacyPolicy.zh-TW.md`

## Dictionary data

The currently bundled dictionary asset is:

- `app/src/main/assets/zhuyin_cedict.tsv`

The asset combines CC-CEDICT Traditional entries (converted from Pinyin into
Zhuyin keys) with McBopomofo multi-character phrase readings, ranked with
McBopomofo aggregate phrase frequency. No underlying corpus is packaged.

Relevant scripts:

- `tools/build_zhuyin_dictionary.py`
- `tools/rank_zhuyin_dictionary.py`
- `tools/merge_mcbopomofo_dictionary.py`

For source and license attribution, see:

- `NOTICE.md`
- `NOTICE.zh-TW.md`
- `app/src/main/assets/zhuyin_cedict_LICENSE.txt`
- `tools/data/README.md`

## Reporting issues and contributing

Found a bug or want to suggest a feature? Open an
[issue](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues).

Before contributing:

- Confirm the issue is not already being handled and describe your plan there.
- Before adding third-party data, dictionaries, fonts, or assets, verify the
  license and update `NOTICE.md` and `NOTICE.zh-TW.md` with the source URL,
  retrieval date, license terms, and transformation.
- Run `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug` to verify tests,
  lint, and the build.
- Rebase or sync with `main` before opening a PR, and describe the change scope
  clearly.

## Project history

This project originated during OpenAI Build Week, where Codex and GPT-5.6 were
used to extend and stabilize an Android Zhuyin keyboard that already worked
before the event. Pre-event work remains the baseline, and features or fixes
added during the event are recorded in the commit history.

All model-generated changes were reviewed, built, and tested against the real
application before acceptance. This history is kept here for transparency and is
not the project's central framing today.

## License

Except where otherwise noted, this project's original source code is licensed
under the [Apache License 2.0](LICENSE).

The root Apache-2.0 license does not relicense third-party material. The
generated data derived from CC-CEDICT is subject to CC BY-SA 4.0, the McBopomofo
phrase readings and aggregate frequency data are subject to its MIT License and
upstream data notices, and the ToneOZ font subset is subject to the SIL Open Font
License 1.1. See [NOTICE.md](NOTICE.md) and [NOTICE.zh-TW.md](NOTICE.zh-TW.md)
for complete attribution, transformations, and local license copies.
