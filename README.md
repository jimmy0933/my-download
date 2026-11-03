# My Download 📥

Android 影片瀏覽與下載 App（Kotlin + Jetpack Compose）。

> **目前主要儲存庫：** [jimmy0933/my-download](https://github.com/jimmy0933/my-download)。
> 這是從既有功能規格重建的新專案。舊 GitHub 帳號的原始碼目前無法由此連線讀取，並非原始 Git 歷史的逐位元複製。

## 功能

- Android WebView 直接瀏覽 AV01，網站縮圖、連結、播放器以及全螢幕操作
- 對目前可見且有影片連結的縮圖，用**原生浮動下載按鈕**提供快速下載，不改動網站 DOM
- 影片詳細頁固定「下載此影片」，按鈕直接打開畫質選擇
- MP4 / 未加密 M3U8 解析與下載，HLS 多畫質選擇
- AV01 的公開影片資訊、Geo、Manifest、CDN-access API 解析（需網站正常授予權限；API 可能變動）
- MP4 背景下載、HLS 分段下載、下載紀錄、進度、暫停及續傳
- HLS 盡可能轉成可快轉的 MP4；若無法無損重封裝則保留原片段檔
- 「設定」可以選擇下載資料夾，使用 Android 系統資料夾選擇器並保存授權
- 「設定」可以開關輕量廣告阻擋：攔截常見廣告網域與外站彈出頁
- GitHub Actions：測試、簽章、檢查 APK 簽名、更新最新 Release，刪除較舊的 Release

## 使用

開啟 App，在「瀏覽網站」瀏覽影片，點縮圖旁的 **↓ 下載**，或進入影片頁按 **下載此影片**，然後選擇畫質。下載進度在「下載管理」。

亦可在下載管理手動貼入 HTTPS 網址。

下載位置預設為 `Movies/MyDownload`。要更改請開啟「設定 → 下載位置」，選擇系統允許存取的資料夾；新的下載任務會記住設定當下的路徑。Android 11 以上可能無法選擇根目錄或整個 Downloads，建議使用其子資料夾。

廣告阻擋預設關閉。開啟後會嘗試阻止常見廣告網域與第三方彈出頁；若網站變更導致部分內容不可用，可以關閉。

## Android 與串流限制

- Android 10（API 29）以上
- 只下載有權保存的無 DRM、未加密影片。不繞過登入、付費、DRM 或網站的存取限制。
- 不保證 AV01 每個影片或畫質都可解析。網站的 API、影片來源或下載權限可能隨時變更。
- 非所有縮圖都一定有可辨識的 `/video/` 連結；這些縮圖可能不顯示浮動鈕。可改用詳細頁下載。
- HLS 的加密、BYTERANGE、DISCONTINUITY、部分 fMP4 等可能不支援；不能無損封裝時可能留下 TS/原始片段格式，快轉可能受限。
- 續傳依賴伺服器 HTTP Range 或完整 HLS 分段；令牌過期後可能需重新解析。
- 必須有足夠手機儲存空間。部分影片在下載時會暫存片段與輸出檔。
- 重新安裝或從其他 applicationId 切換時，不會自動搬移舊 App 的下載紀錄。

## 最新版 APK

- [GitHub Actions](https://github.com/jimmy0933/my-download/actions)
- [最新簽章 APK Release](https://github.com/jimmy0933/my-download/releases/latest)

新 GitHub 儲存庫必須**獨立設定**以下四個 Repository Actions Secrets，舊帳號的 Secrets 不會自動複製：

| Secrets 名稱 | 用途 |
| --- | --- |
| `KEYSTORE_BASE64` | Android 簽章檔 base64 |
| `KEYSTORE_PASSWORD` | keystore 密碼 |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | 簽章 key 密碼 |

在 **Settings → Secrets and variables → Actions** 設定。相同 Android applicationId 若想無痛覆蓋安裝，請使用之前 APK 的**同一份簽章憑證**。

工作流程在每次推送 `main` 時運行，也能手動於 Actions → `Signed Android APK - latest only` 執行。通過單元測試、完成正式簽章和 APK 驗證後，才會建立或更新 `latest`，並刪除舊的 Release。

### 本機除錯建置

JDK 17、Android SDK 35、Gradle 8.11.1：

```bash
gradle :app:assembleDebug
```

> **進度說明**：已建立原始碼與 CI。GitHub Actions/實機下載成功與否，要以新儲存庫的執行結果為準。
