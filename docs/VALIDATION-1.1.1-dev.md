# Inline English validation (2026-10-03)

Build: local debug `1.1.1-dev`, versionCode `3`, on Samsung SM-S9280,
Android 16 / API 36. Installed with `adb install -r` over the same-key debug
1.1.0 build, without uninstalling or clearing application data.

## Reproduced behavior before the change

In 1.1.0, tapping ABC in the debug EditorProbeActivity selected Gboard, but
Gboard retained its Zhuyin layout. The first empty-field switch also hid the
keyboard until the field was tapped again. Switching with pending `ㄅ` committed
that text and selected Gboard's Zhuyin layout. No composition-rejection failure
was reproduced. The original 1.0 debug installation had built-in English.

## Automated checks

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`: passed.
- 77 JVM tests passed. Five tests for the removed external-delegation policy
  were removed; existing editor-mode tests now expect built-in English.
- Lint: zero errors, 30 warnings.
- Two `EnglishKeyboardDeviceTest` instrumented tests passed on this phone.
  They use actual canvas-key hit regions and MotionEvent dispatch to cover all
  letter keys, one-shot Shift, ABC / 注 round trips, and the ASCII-only return
  from number mode. These view tests do not replace the service checks below.

## Actual IME and screen checks

Using the debug-only EditorProbeActivity and synthetic text:

- ABC showed the built-in QWERTY page while the selected IME remained
  `com.ioszhuyin.keyboard/.IOSZhuyinIME` and the keyboard stayed visible.
- Typing `q`, Shift + `a`, then `z` produced `qAz`; Shift returned to lowercase.
- English -> 123 -> #+= used half-width number/symbol pages. Typing `1#`,
  then ABC returned to English; 注 returned directly to Zhuyin.
- Entering an unfinished `ㄅ`, then ABC preserved it in the editor and showed
  English without changing the selected IME or hiding the keyboard.
- Password, Email and FORCE_ASCII fields each showed built-in English without
  an external handoff; the 注 control was absent in these restricted fields.
- Existing synthetic text remained intact across field and language changes.

Screen captures `fix-03-english.png` through `fix-12-ascii.png` were inspected
locally during verification. The phone was left on the normal test field with
Dynamic Zhuyin selected. No other keyboard was disabled.

## Limits

This is a local development build, not a published release. The original social
app, other OEMs, older Android versions, landscape, and editor rejection were
not exercised. English provides direct letter entry and one-shot Shift; English
autocorrect/prediction and Caps Lock are outside this change. The historical
1.1.0 release and its validation record remain unchanged.

## Stable 1.1.1 packaging and upgrade

The same implementation was packaged as release `1.1.1` (versionCode `3`).
The stable APK passed signature verification using the existing v1.1.0
certificate SHA-256:
`5ea36ca68890ffede01393a39450476fabfa0e77f4de56f38066fa5f2c61e97d`.

On a fresh Android 16 / API 36.1 emulator, the published v1.1.0 APK was installed
and learning was paused through the settings UI. `adb install -r` of the signed
1.1.1 APK succeeded, package metadata reported versionCode 3 / versionName 1.1.1
without DEBUGGABLE, and the settings screen still showed learning paused.
The reproducible dictionary check passed with the unchanged dictionary checksum.
