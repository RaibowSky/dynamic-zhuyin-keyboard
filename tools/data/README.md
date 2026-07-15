# McBopomofo Phrase-Frequency Source

`mcbopomofo_phrase.occ` is a line-ending-normalized copy of McBopomofo's
aggregate phrase-occurrence table. CRLF was normalized to LF; the phrase and
count content is unchanged. It is used at build time only to rank candidates that
were generated from CC-CEDICT; it does not add McBopomofo dictionary entries or
bundle the underlying source corpus.

- Project: McBopomofo
- Repository: https://github.com/openvanilla/McBopomofo
- Upstream file: https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/Source/Data/phrase.occ
- Pinned commit: `14f672cd9296deb4ff87034b05003b15a1e796f5`
- Retrieved: 2026-07-11
- Upstream SHA-256: `2140ccad6945fd972dc0004ad44d2b4ba6ad50dd91dd883f51b72951fd01ed4e`
- LF-normalized SHA-256: `0fc51c5245a8820e1003e3fa3fb2759b0d1b502a71da81bbfa265e9ac6c9fb5a`
- License: MIT
- License URL: https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/LICENSE.txt
- Copyright: Copyright (c) 2011-2026 Mengjuei Hsieh et al.
- Local license copy: `McBopomofo_LICENSE.txt`

The build script verifies the normalized SHA-256 before using the file. Updating the
source requires updating the pinned commit, checksum, notices, and generated
asset attribution together.
