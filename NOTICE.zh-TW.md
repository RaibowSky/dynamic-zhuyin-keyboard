# 聲明與第三方參考資料

本檔案記錄本專案開發過程中使用的外部資料來源與參考資料。

[English version](NOTICE.md)

## 實際打包的第三方資料

### CC-CEDICT

`app/src/main/assets/zhuyin_cedict.tsv` 是由 CC-CEDICT 轉換產生。

- 來源專案：CC-CEDICT
- 下載頁面：https://www.mdbg.net/chinese/dictionary?page=cc-cedict
- 專案／編輯頁面：https://cc-cedict.org/
- 授權：Creative Commons Attribution-ShareAlike 4.0 International
  (CC BY-SA 4.0)
- 授權網址：https://creativecommons.org/licenses/by-sa/4.0/
- 本地轉換腳本：`tools/build_zhuyin_dictionary.py`
- 打包資產 SHA-256：`50f057aab946c7da7e22fbad1dd845c5d406894c8ce03100817c101e5b7c0ea4`

執行的轉換內容：

- 從 `cedict.txt.gz` 讀取繁體中文詞條與拼音讀音。
- 將拼音音節轉換為注音符號與聲調標記。
- 產生 `key<TAB>candidate1 candidate2 ...` 格式的候選字資料列。
- 同時包含有聲調與無聲調的查詢鍵，以供輸入法候選字查詢使用。
- 使用下方另行標示來源的 McBopomofo 彙總詞頻資料排列候選字順序。

產生出的檔案屬於衍生資料資產；重新散布時應持續保留 CC-CEDICT 的標示，並採用相容的授權處理方式。

目前打包資產當初使用的 CC-CEDICT archive 並未保留，因此無法誠實標示其精確上游 release、來源 archive checksum，或逐 byte 重建。轉換腳本目前固定一份較新的重建基準：2026-07-15 從 MDBG 下載，SHA-256 為 `33d79ec1cc91fd1bc76fe7e590723d474cfe6ab364648eef9b7b52677e897d87`。該份已驗證的基準會產生不同的字典資產，不能視為目前 repository 內資產的來源快照。MDBG 下載網址會隨最新版變動；checksum 才是轉換腳本接受的重建輸入識別。為避免意外用不同基準覆寫目前資產，腳本預設輸出到 `build/dictionary-rebuild/`；若要替換打包資產，必須明確指定 `--target`、`--license-file`，並同步更新來源聲明。

### McBopomofo 彙總詞頻資料

`tools/data/mcbopomofo_phrase.occ` 是 McBopomofo 彙總詞語出現次數表的換行正規化副本；只將 CRLF 改為 LF，詞語與次數內容不變。

- 來源專案：McBopomofo
- Repository：https://github.com/openvanilla/McBopomofo
- 上游檔案：https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/Source/Data/phrase.occ
- 固定 commit：`14f672cd9296deb4ff87034b05003b15a1e796f5`
- 取得日期：2026-07-11
- 上游 SHA-256：`2140ccad6945fd972dc0004ad44d2b4ba6ad50dd91dd883f51b72951fd01ed4e`
- LF 正規化 SHA-256：`0fc51c5245a8820e1003e3fa3fb2759b0d1b502a71da81bbfa265e9ac6c9fb5a`
- 授權：MIT
- 授權網址：https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/LICENSE.txt
- 著作權標示：Copyright (c) 2011-2026 Mengjuei Hsieh et al.
- 本地授權副本：`tools/data/McBopomofo_LICENSE.txt`

本專案只使用其中的詞語出現次數，重新排列已由 CC-CEDICT 產生的候選字；不會匯入 McBopomofo 的詞條、讀音、應用程式原始碼，或產生彙總次數所使用的底層語料。為了讓建置可重現，來源詞頻檔保留在 repository 中作為建置階段的輸入，但不會打包成 Android 執行階段資產。產生後的資產會在 `app/src/main/assets/zhuyin_cedict_LICENSE.txt` 同時保留兩個專案的來源與授權標示。

### ToneOZ 拼音文楷注音 subset

`app/src/main/assets/bopomofo.ttf` 是由 ToneOZ Pinyin WenKai Regular 可重現產生的 subset，只包含注音符號與聲調符號。

- 來源專案：ToneOZ Pinyin WenKai
- Repository：https://github.com/jeffreyxuan/toneoz-font-pinyin-wenkai
- 字型衍生關係：Fontworks Klee -> LXGW WenKai -> ToneOZ Pinyin WenKai
- 字型 metadata 中保留的其他著作權人：
  - Copyright 2021 LXGW (https://github.com/lxgw/LxgwWenKai)
  - Copyright 2020 The Klee Project Authors (https://github.com/fontworks-fonts/Klee)
- 固定 commit：`55facb136a7b22afd60ddf30ac0226661614d870`
- 上游檔案：`fonts/ttf/ToneOZ-Pinyin-WenKai-Regular.ttf`
- 上游 SHA-256：`153a826f06fd6d578adfd7235c72d3b5298698a319a48ff088dff43bd87c83e8`
- 授權：SIL Open Font License 1.1
- 本地授權副本：`app/src/main/assets/bopomofo_OFL.txt`
- 本地來源聲明：`app/src/main/assets/bopomofo_FONT_NOTICE.txt`
- 重建腳本：`tools/build_bopomofo_font.py`

subset 包含 U+3105-U+3129 與 U+02C7、U+02C9、U+02CA、U+02CB、U+02D9。字型軟體只因 subset 而修改，保留的 glyph outlines 沒有修改。打包檔案的 SHA-256 為 `7d2630c930012253c214100dae4fdccef582ed02be6bcbc313bed831ad672800`。

## 未打包的參考資料

本專案為獨立實作。

開發過程中曾參考公開可取得的中文輸入法與語言資源，以了解一般輸入流程、鍵盤互動設計及注音輸入習慣。

這些參考資料僅用於行為研究、設計比較與語言資料驗證。除前述「實際打包的第三方資料」明確列出的內容外，本 repository 不包含來自這些參考資料的第三方程式碼、專有詞庫、視覺素材、商標素材、爬取資料集或其他受版權保護內容。

目前實際隨專案提供的候選字典，其詞條與讀音僅由 CC-CEDICT 產生；預設候選順序則使用前述另行標示來源的 McBopomofo 彙總詞頻資料調整。

## 未來新增資料的 repository 政策

新增任何字典、詞頻表、鍵盤版面素材或其他第三方資料前，請先完成以下事項：

1. 確認授權允許預期用途。
2. 在本檔案中加入來源網址、取得日期與授權條款。
3. 盡可能將產生後的資料與轉換腳本分開保存。
4. 清楚記錄轉換方式，使資料能夠重新產生。
5. 不要提交授權不明或與重新散布不相容的爬取資料、專有資料或受保護資料。
