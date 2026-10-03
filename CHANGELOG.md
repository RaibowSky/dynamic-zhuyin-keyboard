# Changelog

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
