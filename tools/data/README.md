# Reproducible dictionary inputs

CC-CEDICT snapshot: `cedict.txt.gz`, retrieved from MDBG on 2026-09-23.
Source: https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz

SHA-256: `98c1a804e9ecf103494c85c18300dcd792acced9d238350ebcd84297e77a2c30`. License: CC BY-SA 4.0.
The exact archive is committed; rebuilding requires no download.

From the repository root with Python 3.10+:

```sh
python tools/rebuild_dictionary.py --check
# Explicitly regenerate app/src/main/assets/zhuyin_cedict{.tsv,_LICENSE.txt}:
python tools/rebuild_dictionary.py
```

Expected TSV SHA-256: `df1664881c7417be73d430ee90915ba13d70aa67f19632a70d170bdeefb5ead9`.
Both commands verify all three data source checksums before generation.
The check command compares the asset and complete generated license notice.
Prefix lookup discovers sorted length boundaries using binary search; no separate
index can go stale and no full scan is performed on first prefix lookup.

# McBopomofo Dictionary Sources

This directory retains two data files from the same pinned McBopomofo commit.
They are build-time inputs and are not packaged directly in the Android app.

## Phrase readings

`mcbopomofo_bpmf_mappings.txt` is an unmodified copy of McBopomofo's
multi-character phrase-to-Bopomofo mapping table. The generated Android
dictionary includes its phrase entries under both toned and untoned lookup
keys. Duplicate candidates are merged with the existing CC-CEDICT-derived
asset, then ranked with the occurrence data below.

- Upstream file: https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/Source/Data/BPMFMappings.txt
- Pinned commit: `14f672cd9296deb4ff87034b05003b15a1e796f5`
- Retrieved: 2026-07-17
- SHA-256: `51715ee5c731f9994be3b168d7681ed9692b8c335ae6fc2e7cfd4af6fd0e0781`
- Upstream description: multi-character phrases maintained by McBopomofo;
  the upstream data README notes that the table was originally simplified
  from libtabe's `tsi.src` (BSD licensed) and subsequently modified.

## Aggregate phrase frequency

`mcbopomofo_phrase.occ` is a line-ending-normalized copy of McBopomofo's
aggregate phrase-occurrence table. CRLF was normalized to LF; phrase and count
content is unchanged.

- Upstream file: https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/Source/Data/phrase.occ
- Pinned commit: `14f672cd9296deb4ff87034b05003b15a1e796f5`
- Retrieved: 2026-07-11
- Upstream SHA-256: `2140ccad6945fd972dc0004ad44d2b4ba6ad50dd91dd883f51b72951fd01ed4e`
- LF-normalized SHA-256: `0fc51c5245a8820e1003e3fa3fb2759b0d1b502a71da81bbfa265e9ac6c9fb5a`

## License and reproduction

- Project: McBopomofo
- Repository: https://github.com/openvanilla/McBopomofo
- License: MIT
- License URL: https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/LICENSE.txt
- Copyright: Copyright (c) 2011-2026 Mengjuei Hsieh et al.
- Local license copy: `McBopomofo_LICENSE.txt`
- Merge script: `tools/merge_mcbopomofo_dictionary.py`

The scripts verify both source SHA-256 values before use. Updating either
source requires updating the pinned commit, checksums, notices, tests, and
generated asset attribution together. No underlying source corpus is included.

License text build input SHA-256 (`McBopomofo_LICENSE.txt`): `72ed32193b0c629def66df63791b2fb125946b593a3251380e6b949488f47b72`.
