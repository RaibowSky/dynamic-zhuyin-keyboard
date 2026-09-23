# Changelog

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
