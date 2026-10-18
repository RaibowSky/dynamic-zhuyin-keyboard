# Changelog

## Unreleased

- Holding Backspace plays the key sound for every repeated deletion, not only the first press (when the key sound is on).
- Add an in-app 「開放原始碼授權」 page showing the app license, the Apple non-affiliation notice and every bundled third-party notice, including the Apache 2.0 text and the libraries compiled into the app.
- Bundle libtabe's license and acknowledgements (`libtabe_LICENSE.txt`), keep the CC-CEDICT/CEDICT copyright line in the dictionary notice, and document `next_word.tsv` and the Apple disclaimer in NOTICE.

## 1.3.0 (2026-10-18)

- Register taps more reliably: a short finger roll keeps the pressed key, overlapping fingers are tracked separately, and gaps between keys, staggered-row margins and the strip below the candidate row belong to the nearest key or candidate. Haptics fire when the finger lands.
- Add an optional key sound with its own switch and volume, independent of the system touch-sound setting (off by default; silent and vibrate modes mute it).
- Add an adjustable Zhuyin/tone key label size (16–40) with a live preview; the settings button is now 「字體大小、鍵盤高度與外觀」.
- Unconverted Zhuyin can be edited: tap inside it or hold space and slide to move the caret, then insert, delete or change a tone. Moving the caret no longer selects the first candidate; leaving the reading keeps its literal text.
- Emoji panel has seven categories with ◀/▶ paging; the spacebar shows the current page.
- Warm up the dictionary in the background to reduce first-key latency.
- Debug builds install as `.debug`, labeled 動態注音鍵盤（測試版）, beside the released app.
- Add `github` and `play` distribution flavors. GitHub APKs keep `com.ioszhuyin.keyboard` and update previous GitHub releases in place; the Google Play build uses `dynamic.zhuyin.keyboard`. The two are separate apps that do not share data. Kotlin/resource namespace remains unchanged.

## 1.2.0 (2026-10-04)

- Share one fixed-height header between idle tools, composition candidates and next-word predictions. The first Backspace dismisses predictions without deleting; another press deletes normally.
- Hold the spacebar, then slide horizontally to move the cursor; short taps retain their existing behavior.
- Add Chinese full-width punctuation, 「」『』【】, and a full/half-width toolbar switch.
- Add a common emoji panel, a clipboard history panel, and a shortcut to settings in the IME's profile. Clipboard history is opt-in, local, bounded to 50 recent clips for one hour plus 50 persistent pins, with individual deletion and clear-unpinned controls; sensitive-marked text is excluded.
- Expose keyboard height, spacing, bottom padding, width ratios and candidate sizing in release settings; presets now refresh slider values.
- Suggest next words after selecting a Chinese candidate, using a small offline starter list plus local selection-pair learning. Existing pause, clear and opt-in export controls apply.
- Label debug builds with a `-dev` version suffix.

## 1.1.1 (2026-10-03)

- Restore built-in English QWERTY with one-shot Shift and ABC / 注 round-trip controls.
- Keep password, email and force-ASCII editors in this IME using its English page.
- Use half-width numbers and punctuation from English, with a direct ABC return.
- Preserve pending composition when an editor rejects it and explain why the switch was stopped.

## 1.1.0

- System-aware light/dark colors for the keyboard, dictionary settings and bars.
- English/ASCII input delegates to another enabled system keyboard, trying the
  previous keyboard, then next keyboard, then the picker. Number/symbol pages remain.
- Local TTF/OTF import with preview, private storage, safe glyph fallback and reset.
- Continuous sentence candidates combine known phrases and characters with a
  deterministic beam; leading-character correction preserves the remaining input.
- Binary search discovers dictionary length ranges without a full cold-prefix scan.
- Retained, checksum-pinned dictionary sources and byte-for-byte rebuild checks.
- Literal percent, underscore and backslash search; asynchronous settings counts.
- Build/test/lint CI, permanent application identity and persistent release signing.

The previous Build Week APK was debug-signed. Export your dictionary before
uninstalling it to move to the stable signing identity. Future stable APKs use
the same signing identity and support normal Android updates.

## 0.1.0-buildweek (historical demo)

Debug APK showcasing the initial dynamic Zhuyin keyboard and local learning.
Its embedded Android versionName was 1.0, versionCode 1. Retained for history.
