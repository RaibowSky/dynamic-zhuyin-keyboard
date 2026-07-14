# Privacy Policy

Last updated: 2026-07-14

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
adjust candidate ordering. This data remains on the device and is not uploaded.

A settings screen for clearing local candidate frequency data has not been added
yet. If this feature is added later, this policy should be updated.

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

The bundled candidate entries and readings are generated from CC-CEDICT.
Their default order is informed by offline McBopomofo aggregate
phrase-frequency data. These resources are processed locally without runtime
network lookup. See `NOTICE.md` for source and license attribution.

## Contact

For questions about this policy or the project, please contact the maintainer
through the GitHub repository.
