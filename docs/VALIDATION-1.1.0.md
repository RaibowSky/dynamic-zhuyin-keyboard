# 1.1.0 validation (2026-09-23)

Environment: Windows, JDK 17, Android SDK/build-tools 36.1; Android 16 / API 36
x86_64 Resizable emulator. No physical handset or API 24 device was available.

## Automated checks

- `gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease`:
  passed; 82 unit tests, no failures; lint has no errors (warnings remain).
- `python tools/rebuild_dictionary.py --check`: TSV and license byte-for-byte match.
  TSV SHA-256: `df1664881c7417be73d430ee90915ba13d70aa67f19632a70d170bdeefb5ead9`.
- Four instrumented tests passed on Android SQLite/font/rendering APIs: literal
  wildcard search/count agreement; font copy, invalid import and reset; cold prefix
  lookup; a complete five-syllable sentence assembled from separate dictionary words.
- Unit coverage includes 80-syllable input, competing segmentations, user phrase
  priority, incomplete final syllables, leading correction, 一/不 tone sandhi and
  exception-safe previous/next/picker switching.

## Device interaction and visuals

- Light and dark keyboard/candidate screenshots are actual emulator captures
  using the debug-only EditorProbeActivity (excluded from release).
- Toggling night mode while this editor and IME stayed visible changed the keyboard
  palette and kept the current composition. Settings colors also changed.
- Removed the PR's opaque whole-view background after it hid the editor above
  the bottom-aligned keyboard during visual testing.
- ABC switched to Gboard. Selecting this IME in password, email and force-ASCII
  editors automatically handed back to Gboard. With other IMEs disabled, ABC
  opened the Android IME picker with only this keyboard available.
- Android's document picker selected a local TTF, displayed the multilingual
  preview, applied a private copy with the same SHA-256, and restored default.
  Instrumented tests delete the staging source and reject invalid content.
- IME registration was confirmed through `ime list -a`, enable and selection.

## Timing samples

`DictionaryDeviceTest.coldPrefixLookup` times only the first prefix lookup after
initialization, in a fresh instrumentation process, for `ㄋ` and nine candidates.
The pre-change build took 577.67 ms; after binary boundary search, samples were
54.64, 128.55 and 65.24 ms. Samples reflect host contention (including a second
emulator boot), not a controlled benchmark or a physical-device guarantee.
The original and newly rebuilt approximately 12 MB dictionaries returned the same
nine candidates for this probe. Five-syllable `所以我現在` decoding took 4.95–14.17 ms.

The reader still uses ByteBuffer and bounded caches. Length boundaries are derived
by binary search on sorted rows; no generated index can become mismatched. Syllable
metadata stops once key length exceeds four rather than traversing all phrases.

## Signing and update

- APK verified with apksigner (APK signature scheme v2, RSA 3072).
- Certificate SHA-256:
  `5ea36ca68890ffede01393a39450476fabfa0e77f4de56f38066fa5f2c61e97d`.
- Installed stable versionCode 2 on a separate clean emulator, paused learning,
  then installed an unpublished versionCode 3 validation build with `adb install -r`.
  Upgrade succeeded and the paused-learning setting remained. Both used the same key.
- Private key and credentials are outside the repository. VersionCode 3 was only
  a temporary validation artifact; this release remains 1.1.0 / versionCode 2.

## Limits and follow-up areas

OEM behavior, older Android versions, physical handset latency and prolonged
typing sessions still need wider testing. Font collection enumeration is deferred;
local TTF/OTF import is the supported first phase. Sentence ranking uses bounded
frequency rank/word-length/user bonuses, without a statistical transition model
or post-commit next-word prediction. See RELEASING.md for the permanent ID decision.
