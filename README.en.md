# iOS-style Zhuyin Keyboard for Android

Android input method prototype for an iOS-style dynamic Zhuyin keyboard.

This project is not affiliated with, endorsed by, or sponsored by Apple, Sogou,
the Ministry of Education of Taiwan, or the maintainers of CC-CEDICT.

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

The keyboard behavior and layout were developed from observation and comparison
of existing input methods. These references were used for behavioral study only;
their source code, visual assets, proprietary dictionaries, and bundled data are
not copied into this repository unless explicitly documented in `NOTICE.md`.

- Apple iOS Zhuyin keyboard behavior, observed from user-provided screen
  recording and manual testing.
- Sogou Zhuyin/Input Method behavior, used as a comparison reference for IME
  interaction concepts.
- Taiwan Ministry of Education dictionary resources, used during earlier
  experimentation/checking. No Ministry of Education dictionary dump is bundled
  in the current repository state.
- CC-CEDICT, used as the current bundled dictionary data source.

## Licensing

No open-source license has been granted for this repository's original project
code yet. All rights are reserved by the project owner unless a `LICENSE` file is
added later.

Third-party data retains its own license. In particular, the bundled generated
dictionary derived from CC-CEDICT is subject to CC-CEDICT's license terms.
