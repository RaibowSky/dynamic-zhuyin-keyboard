# 動態注音鍵盤（Dynamic Zhuyin Keyboard）

一套在 Android 裝置本機運作的注音（Bopomofo／ㄅㄆㄇ）輸入法，採用「動態鍵盤」
設計，目標是提供接近 iOS 注音輸入法的輸入體驗，同時保持完全離線、注重隱私。

[English README](README.en.md)

## 這是什麼？

「動態注音鍵盤」把注音符號放在固定位置，輸入時只更新候選列與可用的下一鍵，
減少鍵位跳動，並以裝置本機的詞庫提供候選字／詞。

## 為什麼做這個？

- 提供繁體中文使用者更接近 iOS 注音輸入法的動態鍵盤手感。
- 完全離線：不宣告網路權限，輸入內容與候選學習都在裝置本機完成。
- 注重隱私：不蒐集、不上傳打字內容。

## 主要功能

- 動態注音鍵盤：按鍵位置固定，輸入時不跳位。
- 注音候選查詢：使用本地產生的注音候選字典。
- 連續多音節組字：支援完整片語候選、逐音節組句保底，以及「一／不」變調還原。
- 多種輸入模式：注音、英文、數字、符號。
- 本機候選學習：常用的字／詞會隨著使用往前排。
- 使用者詞典：支援手動詞彙、暫停／清除學習、匯入／匯出。
- 一聲與空白鍵邏輯合併，不另外顯示一聲按鍵。

## 安裝

目前提供一個 Build Week 展示用的 debug APK，見
[Releases](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/releases)。
該版本是歷史展示用建置，後續穩定版會以獨立 release 流程發布（見下方
〈Roadmap 與已知限制〉）。

也可以從原始碼自行建置（見下方〈建置與安裝〉）。

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

## 螢幕截圖

> **待補**：此處將放一張實際裝置上的鍵盤與候選列截圖（或短 GIF）。

## Roadmap 與已知限制

本專案仍在早期階段，尚未建立穩定版發布流程。已規劃或進行中的項目包括：

- 系統淺色／深色主題自動切換（issue [#1](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/1)）。
- 字典建置可重現性（issue [#2](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/2)）。
- 簽署、版本與穩定 APK 發布（issue [#3](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/3)）。
- 長期應用程式 ID（issue [#7](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/7)）。
- 可設定的鍵盤字型與本機字型匯入（issue [#8](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/8)）。
- 英文輸入委派給外部 IME（issue [#9](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/9)）。
- 連續注音整句解碼（issue [#10](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/10)）。
- 消除首次前綴查詢的全字典掃描（issue [#11](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues/11)）。

已知限制：

- 目前僅提供 debug APK，尚未提供穩定簽署版。
- 系統淺色／深色主題尚未完整套用到所有畫面。

## 隱私

本鍵盤不宣告網路權限，輸入內容在裝置本機處理，不會上傳。候選學習與使用者詞典
也都保存在裝置本機。

隱私權政策請看：

- `PrivacyPolicy.zh-TW.md`
- `PrivacyPolicy.md`

## 字典資料

目前實際打包在專案中的候選字典是：

- `app/src/main/assets/zhuyin_cedict.tsv`

這份檔案合併兩種已標示來源的資料：CC-CEDICT 的繁體詞條與拼音讀音會轉成注音查詢鍵；
McBopomofo 的多字片語讀音會補充台灣常用詞，並使用其彙總詞頻排列候選。原始語料不會
打包進 App。

轉換腳本：

- `tools/build_zhuyin_dictionary.py`
- `tools/rank_zhuyin_dictionary.py`
- `tools/merge_mcbopomofo_dictionary.py`

資料來源與授權請看：

- `NOTICE.md`
- `NOTICE.zh-TW.md`
- `app/src/main/assets/zhuyin_cedict_LICENSE.txt`
- `tools/data/README.md`

## 回報問題與貢獻

發現 bug 或想提功能建議，請到
[Issues](https://github.com/RaibowSky/dynamic-zhuyin-keyboard/issues) 開新的 issue。

貢獻前請注意：

- 先確認 issue 尚未有人處理，並在 issue 中說明你的計畫。
- 新增第三方資料、詞庫、字型或素材前，請先確認授權，並同步更新 `NOTICE.md` 與
  `NOTICE.zh-TW.md`（包含來源網址、取得日期、授權條款與轉換方式）。
- 修改後請執行 `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug` 確認測試、
  lint 與建置通過。
- 發 PR 前請與 `main` 同步，並清楚說明改動範圍。

## 專案歷史

本專案起源於 OpenAI Build Week 期間，以 Codex 與 GPT-5.6 協助延伸與穩定一個
活動前就已能運作的 Android 注音鍵盤原型；既有成果保留為 baseline，活動期間新增的
功能與修正則逐項記錄於 commit 歷史。

所有模型產生的修改都經過人工檢查、實際建置與裝置測試後才接受。這段歷史保留在本節
作為透明紀錄，不作為專案目前的中心定位。

## 授權

除另有標示者外，本專案的原始程式碼採用 [Apache License 2.0](LICENSE)。

根目錄的 Apache-2.0 不會重新授權第三方資料。由 CC-CEDICT 轉換而來的候選資料遵守
CC BY-SA 4.0；McBopomofo 片語讀音與彙總詞頻資料遵守其 MIT License 與上游資料聲明；
ToneOZ 字型 subset 遵守 SIL Open Font License 1.1。完整來源、轉換方式與授權副本請見
[NOTICE.zh-TW.md](NOTICE.zh-TW.md) 與 [NOTICE.md](NOTICE.md)。
