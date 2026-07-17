# Privacy Policy

Last updated: 2026-07-17

This policy applies to the Android input method "Dynamic Zhuyin Keyboard".

## Summary

- The keyboard currently does not request network permission.
- Typed content is processed locally on the device and is not uploaded.
- The keyboard currently has no ads, analytics, or third-party tracking.
- The keyboard currently has no cloud sync or crash reporting integration.

## Typed Content

As an input method, the keyboard processes Zhuyin input, candidate selection,
and text output on the device. This processing is currently local only.

The keyboard does not transmit typed text, candidates, passwords, account
information, or other input content over the network.

## Local Data

The keyboard may store local candidate selection frequency on the device to
adjust candidate ordering. The app does not upload this data. Android cloud
backup is disabled with `allowBackup=false`, and the backup rules exclude every
supported app-data domain from Android cloud backup and Android-to-Android
device transfer. Android 16 QPR2 cross-platform transfer is not configured
because this project has no paired iOS app; this policy does not claim that
mode is disabled.

Standard exports contain only words manually added by the user and exclude
candidate-learning records. A user may explicitly choose to include learning
records after seeing a privacy warning. The exported file is not encrypted and
may contain names, addresses, or other private terms, so it should be stored
carefully and not shared casually. Learning-record exports do not include
selection timestamps.

The settings screen provides controls to pause or resume candidate learning and
to clear learning records. Pausing stops new records while retaining and using
existing ordering in ordinary text fields. Password fields and editors that
request no personalized learning neither record nor apply personalized
candidate ordering.

## Permissions

Permissions currently used:

- `VIBRATE`: used for key press haptic feedback.
- `BIND_INPUT_METHOD`: required by Android for input method services.

Permissions not currently used:

- Network access.
- Advertising ID.
- Contacts, location, camera, microphone, file access, or other sensitive
  permissions.

## Third-Party Services

The keyboard currently does not use third-party analytics, ads, cloud
candidates, cloud sync, or crash reporting services.

## Dictionary Data

The bundled dictionary combines CC-CEDICT-derived entries and readings with
McBopomofo multi-character phrase readings. Offline McBopomofo aggregate
phrase-frequency data ranks the merged candidates. These resources are
processed locally without runtime network lookup. See `NOTICE.md` for source
and license attribution.

## Contact

For questions about this policy or the project, please contact the maintainer
through the GitHub repository.
