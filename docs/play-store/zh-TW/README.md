# Google Play 商店圖片

本次已製作兩份可上傳的圖片，尚未上傳至 Play Console：

- `app-icon-512.png`：512 × 512，從現有 Android adaptive icon 的向量前景及藍色背景匯出。
- `feature-graphic-1024x500.png`：1024 × 500，使用內建 imagegen 生成藍白注音鍵帽主題橫幅，並校正為 Play 規定的尺寸。

已檢查文字、注音符號、尺寸與檔案大小。圖示小於 1 MB，主題圖片小於 15 MB。
主題橫幅是品牌插圖，不是手機螢幕截圖。

## 手機截圖建議

Console 目前要求至少 2 張、最多 8 張；建議準備 4 張 1080 × 1920 的真實 App 截圖，順序如下：

1. 注音輸入與候選字：輸入一般日常短句，讓候選列清楚可見。
2. 內建英文頁：展示 ABC／注切換與英文按鍵。
3. 剪貼簿歷史及釘選：使用測試內容，例如「下午三點見」，不要放私人紀錄。
4. 鍵盤高度與外觀：設定頁與試打區，展示可調整的版面。

應以待上架的版本重新擷取。`docs/images` 的舊截圖未在本次標記為上架用素材；AI 品牌插圖不能替代真實功能截圖。
本次沒有連接裝置，未新增手機截圖。圖示加橫幅加 4 張截圖，共 6 張素材。

## 生成紀錄

使用內建 imagegen，未使用 CLI 或外部 API。最終提示詞：

```text
Use case: ads-marketing. Create one finished Google Play feature graphic for a Traditional Chinese offline Android keyboard app. Exact canvas size 1024 by 500 pixels, landscape 2.048:1, opaque PNG. Premium clean blue and white design consistent with an app whose existing icon is a white keyboard with blue and light gray keys. Warm white to very pale ice-blue background, generous whitespace, crisp professional Traditional Chinese typography, strong contrast. Text verbatim: main headline「動態注音鍵盤」; subtitle「離線輸入・本機學習」. Headline large and easy to read in the left half; subtitle smaller below. On right, an elegant conceptual cluster of four white rounded physical keycaps with subtle soft shadows, showing the exact Bopomofo symbols「ㄅ」「ㄆ」「ㄇ」「ㄈ」in blue, with a small blue spacebar-like accent. Treat the keycaps as decorative brand art, not a screenshot or a phone interface. Keep all important content at least 64 pixels from edges. Restrained tasteful editorial composition. No fake app UI, no device frame, no Google Play badge, no ranking, no awards, no ratings, no download CTA, no extra text, no watermark. Render Chinese and Bopomofo glyphs accurately.
```

格式匯出腳本：`tools/export_play_graphics.ps1`。保留原始生成檔，未覆寫 App 的現有圖示資源。
