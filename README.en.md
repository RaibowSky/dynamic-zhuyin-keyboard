# iOS-style Zhuyin Keyboard for Android

Android input method prototype for an iOS-style dynamic Zhuyin keyboard.

This project is an independently developed Android Zhuyin input method.

Except for third-party resources explicitly documented in this README and
`NOTICE.md`, this project does not include third-party source code, proprietary
dictionaries, trademark assets, or other copyrighted materials.

## Current Features

- Dynamic Zhuyin keyboard flow inspired by iOS-style input behavior.
- Stable key positions for Zhuyin and English layouts.
- Zhuyin candidate lookup from a generated dictionary asset.
- English, number, and symbol input modes.

## Dictionary Data

The currently bundled dictionary asset is:

- `app/src/main/assets/zhuyin_cedict.tsv`

It was generated from CC-CEDICT data downloaded from MDBG and converted from
Pinyin readings into Zhuyin keys by:

- `tools/build_zhuyin_dictionary.py`

See `NOTICE.md` and `app/src/main/assets/zhuyin_cedict_LICENSE.txt` for source
and license attribution.

## References

This project was implemented independently.

During development, several publicly available Chinese input methods and
linguistic resources were consulted to understand general input workflows,
keyboard interaction patterns, and Zhuyin conventions.

Unless explicitly documented in `NOTICE.md`, this repository does not contain
third-party source code, proprietary dictionaries, visual assets, or other
copyrighted materials.

The bundled dictionary currently included in this repository is generated only
from CC-CEDICT as described above.

## Licensing

No open-source license has been granted for this repository's original project
code yet. All rights are reserved by the project owner unless a `LICENSE` file is
added later.

Third-party data retains its own license. In particular, the bundled generated
dictionary derived from CC-CEDICT is subject to CC-CEDICT's license terms.
