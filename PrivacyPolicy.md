# Privacy Policy

Last updated: 2026-10-04

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

Since 1.2.0, the keyboard also records adjacent candidate selections locally for next-word
suggestions, using the same learning pause, clear, and export controls. Password and
no-personalized-learning fields do not use these records.

Since 1.2.0, the Clipboard panel offers optional local text history, off by default.
After enabling Save history, the input method records the first plain-text item when it
receives clipboard changes or opens the keyboard. It cannot recover older clipboard items
or guarantee capture while its process is stopped. Items marked sensitive by the source app,
blank text, and items over 20,000 UTF-16 code units are excluded. Capture is suppressed while
the active editor is a password or no-personalized-learning field. These checks cannot
identify all sensitive text copied by other apps.

History is stored in a private local database, separate from dictionary learning and exports.
Unpinned history is limited to 50 items, and items older than one hour are removed (cleanup is not scheduled); cleanup runs on history
access or changes and periodically while the panel is open. Up to 50 pinned items remain
until unpinned or explicitly deleted. Low-memory process termination does not erase the
stored history. Turning history off stops new capture without deleting existing items.
The panel provides individual deletion and Clear unpinned. Uninstalling the app or clearing
its data removes pins too. Images and content URIs are not collected. Paste current content
reads the first text item only when tapped and does not require history to be enabled.

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
