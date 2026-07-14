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

執行的轉換內容：

- 從 `cedict.txt.gz` 讀取繁體中文詞條與拼音讀音。
- 將拼音音節轉換為注音符號與聲調標記。
- 產生 `key<TAB>candidate1 candidate2 ...` 格式的候選字資料列。
- 同時包含有聲調與無聲調的查詢鍵，以供輸入法候選字查詢使用。
- 使用下方另行標示來源的 McBopomofo 彙總詞頻資料排列候選字順序。

產生出的檔案屬於衍生資料資產；重新散布時應持續保留 CC-CEDICT 的標示，並採用相容的授權處理方式。

### McBopomofo 彙總詞頻資料

`tools/data/mcbopomofo_phrase.occ` 是 McBopomofo 彙總詞語出現次數表的未修改副本。

- 來源專案：McBopomofo
- Repository：https://github.com/openvanilla/McBopomofo
- 上游檔案：https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/Source/Data/phrase.occ
- 固定 commit：`14f672cd9296deb4ff87034b05003b15a1e796f5`
- 取得日期：2026-07-11
- SHA-256：`2140ccad6945fd972dc0004ad44d2b4ba6ad50dd91dd883f51b72951fd01ed4e`
- 授權：MIT
- 授權網址：https://github.com/openvanilla/McBopomofo/blob/14f672cd9296deb4ff87034b05003b15a1e796f5/LICENSE.txt
- 著作權標示：Copyright (c) 2011-2026 Mengjuei Hsieh et al.
- 本地授權副本：`tools/data/McBopomofo_LICENSE.txt`

本專案只使用其中的詞語出現次數，重新排列已由 CC-CEDICT 產生的候選字；不會匯入 McBopomofo 的詞條、讀音、應用程式原始碼，或產生彙總次數所使用的底層語料。為了讓建置可重現，來源詞頻檔保留在 repository 中作為建置階段的輸入，但不會打包成 Android 執行階段資產。產生後的資產會在 `app/src/main/assets/zhuyin_cedict_LICENSE.txt` 同時保留兩個專案的來源與授權標示。

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
