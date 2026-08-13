# Android 注音動態鍵盤

這是一個 Android 輸入法原型，目標是做出接近 iOS 注音動態鍵盤體驗的注音鍵盤。

[English README](README.en.md)

本專案為獨立開發的 Android 注音輸入法。

除 README 與 `NOTICE.md` 明確列出的第三方資料外，本專案未包含任何第三方程式碼、專有詞庫、商標素材或其他受版權保護內容。

## 目前功能

- 接近 iOS 風格的動態注音輸入流程。
- 注音、數字與符號鍵盤按鍵位置固定，避免輸入時鍵位跳動。
- 使用產生後的注音候選字典查詢候選字。
- 支援連續多音節組字、完整片語候選、逐音節組句保底，以及「一／不」變調還原。
- 支援注音、數字與符號輸入模式；英文輸入委派給系統其他已啟用的輸入法。
- 一聲與空白鍵邏輯合併，不另外顯示一聲按鍵。

## OpenAI Build Week：Codex 與 GPT-5.6

這是一個在 OpenAI Build Week 前就已能運作的既有專案。活動期間使用 Codex 與 GPT-5.6 協助延伸與穩定現有鍵盤，而不是把既有成果包裝成全新的專案。

活動期間的工作重點包括：

- 檢查現有 Android／Kotlin 程式碼並找出高影響問題。
- 改善注音 composing text、候選列與輸入可靠性。
- 完成本機使用者詞典的暫停學習、清除、重設、匯入與匯出流程。
- 改善大型詞典處理、transaction 安全性與建置驗證。
- 實作每輸入一個注音符號即更新候選、連續組句與片語候選保底。
- 修正密碼、網址、搜尋與一般文字欄位的輸入模式判斷。
- 以單元測試、模擬 `InputConnection`、Android 模擬器與實機反覆驗證輸入行為。
- 保留活動期間的 commit 與工作紀錄，區分活動前 baseline 與活動期間新增成果。

所有模型產生的修改都會經過人工檢查、實際建置與裝置測試後才接受。更完整的英文說明請看 [README.en.md](README.en.md#openai-build-week-codex-and-gpt-56)。

## 建置與安裝

需求：

- JDK 17
- Android SDK 36.1
- Android Build Tools 36.1.0
- Android 7.0（API 24）以上的裝置或模擬器

在 Windows PowerShell 執行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

產生的 APK 位於：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可以連接已開啟 USB 偵錯的 Android 裝置後直接安裝：

```powershell
.\gradlew.bat installDebug
```

安裝後開啟「動態注音鍵盤」應用程式：

1. 點選「啟用鍵盤」，在 Android 輸入法設定中啟用「動態注音鍵盤」。
2. 返回應用程式並點選「切換鍵盤」，選擇「動態注音鍵盤」。
3. 在任意文字欄位輸入注音；候選列會隨每個注音符號更新。

## 評審快速測試

- 連續輸入多個注音音節，觀察動態候選、片語候選與逐音節組句保底。
- 選取不同候選後再次輸入相同讀音，確認本機候選學習會調整排序。
- 開啟主應用程式，測試暫停／清除學習、手動詞彙及字典匯入／匯出。
- 按下「ABC」確認會切換到系統其他已啟用的輸入法；若無其他輸入法，應開啟系統輸入法選擇器。
- 在密碼欄確認不會學習，且可切換到系統輸入法輸入英文；在 Chrome 網址列確認仍可輸入中文搜尋。
- 本專案不宣告網路權限，所有輸入與候選學習都在裝置本機完成。

## 字典資料

目前實際打包在專案中的候選字典是：

- `app/src/main/assets/zhuyin_cedict.tsv`

這份檔案合併兩種已標示來源的資料：CC-CEDICT 的繁體詞條與拼音讀音會轉成注音查詢鍵；McBopomofo 的多字片語讀音會補充台灣常用詞，並使用其彙總詞頻排列候選。原始語料不會打包進 App。

轉換腳本：

- `tools/build_zhuyin_dictionary.py`
- `tools/rank_zhuyin_dictionary.py`
- `tools/merge_mcbopomofo_dictionary.py`

資料來源與授權請看：

- `NOTICE.md`
- `app/src/main/assets/zhuyin_cedict_LICENSE.txt`
- `tools/data/README.md`

## 隱私

本鍵盤目前不需要網路權限，輸入內容在裝置本機處理，不會上傳。

注意:沒接網路　字典就簡單的你用越多次的字／詞越前面　


隱私權政策請看：

- `PrivacyPolicy.zh-TW.md`
- `PrivacyPolicy.md`

## 參考資料

本專案為獨立實作。

開發過程中曾參考多種公開可取得的中文輸入法與語言資源，以了解一般輸入流程、鍵盤互動設計及注音輸入習慣。

除 `NOTICE.md` 明確列出的第三方資料外，本 repository 不包含任何第三方程式碼、專有詞庫、視覺素材或其他受版權保護內容。

目前候選字典合併 CC-CEDICT 衍生詞條與 McBopomofo 片語讀音，並使用 McBopomofo 彙總詞頻排序。鍵盤注音 glyph 使用 ToneOZ Pinyin WenKai 的固定版本 subset。來源、轉換方式與授權請參閱 `NOTICE.md`。

該 subset 包含 U+3105-U+3129 與鍵盤使用的五個聲調符號（包含一聲 U+02C9），SHA-256 為 `7d2630c930012253c214100dae4fdccef582ed02be6bcbc313bed831ad672800`。

## 授權狀態

除另有標示者外，本專案的原始程式碼採用 [Apache License 2.0](LICENSE)。

根目錄的 Apache-2.0 不會重新授權第三方資料。由 CC-CEDICT 轉換而來的候選資料遵守 CC BY-SA 4.0；McBopomofo 片語讀音與彙總詞頻資料遵守其 MIT License 與上游資料聲明；ToneOZ 字型 subset 遵守 SIL Open Font License 1.1。完整來源、轉換方式與授權副本請見 [NOTICE.zh-TW.md](NOTICE.zh-TW.md) 與 [NOTICE.md](NOTICE.md)。

## 開發提醒

如果之後要新增任何字典、詞頻表、鍵盤素材或其他第三方資料，請先確認授權，並在提交前同步更新 `NOTICE.md`，包含來源網址、取得日期、授權條款與轉換方式。
