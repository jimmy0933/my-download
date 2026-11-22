# X 改版知識庫（my-download）

> 目的：記錄 2026 年 11 月這一輪「App 改成 X 專用」與因應 X 網頁改版所做的需求、異動、
> 觀察到的 X 網頁結構與踩過的坑。下次 X 又改版、或要再調整時，先讀這份。
> 最後更新：2026-11-22（對應 build #323，commit `1f393b8`）。
>
> 這份文件會被打包進 App（`app/build.gradle.kts` 把 `docs/` 當成 assets）。
> App 裡：**設定 → X 改版知識（查看並複製）**，可一鍵複製整份貼給開發者。
> 更新這份文件就會同步更新 App 內的內容（不要另外複製一份）。

---

## 1. 專案與工作流程

- App：Android（Kotlin / Jetpack Compose / WebView / OkHttp），在 WebView 裡跑 x.com，
  注入 JavaScript 做排版、隱藏、手勢、影片偵測與下載。
- **這個環境沒有 Android SDK，無法本機編譯或在手機上測試。** 唯一的編譯檢查是 CI。
  所有 WebView 行為只能靠使用者在手機上回報，所以「診斷記錄」是主要除錯手段（見第 6 節）。
- 使用者長期指示：**每次改完就 commit → 開 PR → squash 合併到 `main` → 等 CI → 主動貼 APK 連結**
  （連結格式 `https://github.com/jimmy0933/my-download/releases/download/latest/my-download-vNNN.apk`，
  檔名、大小、SHA256 都從 `get_latest_release` 讀，不要猜）。使用者不想一再問「好了嗎」。
- CI：`.github/workflows/android-release.yml`，push 到 `main` 就建置、簽章、發佈 tag `latest`。
- 回覆語言：繁體中文，簡短、先講結論。
- 分支規則：開發分支 `claude/mydownload-review-k3voga`。
  - **禁止 force-push**（會被自動分類器擋）。要覆蓋遠端舊 commit 時用
    `git merge -s ours --no-edit FETCH_HEAD` 再一般 push。
  - **每次改動前先 `git checkout -B claude/mydownload-review-k3voga origin/main`**。
    PR 是 squash 合併，若分支還帶著上一個 PR 合併前的 commit，下一個 PR 會「有衝突」。
    遇到時：`cherry-pick` 自己的 commit 到最新 `origin/main` 再重送。
- 只改文件也會觸發一次建置（會產生新的 APK 編號，內容不變）。

---

## 2. 使用者需求清單（依主題）

### 2.1 App 定位
- 改成 **X 專用**：拿掉網址列、分頁入口、三點選單、書籤切換鈕。
- 工具列重新設計為 6 顆大按鈕：返回、X 首頁、X 書籤、重新整理、下載管理、設定。
- 新安裝仍要能**登入 X**（含 Google / Apple 這類彈出視窗登入）。
- 下載管理頁：拿掉「貼上連結 / 解析下載」區塊，每筆縮成一小列 + 預覽圖 + 圖示按鈕；
  切到下載管理時，檔案已不存在的「完成」紀錄自動清除。

### 2.2 清單頁（首頁 / 書籤 / 讚）
- 隱藏 X 底部導覽列與右下角浮動發文鈕。
- **外部網站連結不准點**：擋下並顯示提示，1 秒後自動消失（含廣告）。
- 點清單裡的影片：**另開新分頁播放**，清單頁完全不動；按返回只關掉分頁，位置不跑。
- 點影片時盡量跳過「貼文頁」，直接進播放頁（加速）。
- 清單捲動要順（不能因為我們的腳本變卡）。

### 2.3 播放頁（/mediaviewer）
- 愛心、收藏要顯示；回覆、轉貼、分享、觀看數依設定隱藏。
- 作者資訊：**只留頭像**；名稱、@帳號、內文、「顯示更多」、「翻譯自…」、藍勾勾、「跟隨」鈕都隱藏。
- 點頭像：進作者個人頁；返回先回到影片，再返回才回清單。
- 直向、橫向都要處理（座標規則分開）。
- 全螢幕要真的填滿；X 原生進度條要永遠藏起來（連換影片時也不能閃），改用自訂進度條，
  位置不要貼著按鈕；點影片最下方也要能叫出進度條。
- 影片放大狀態下，左右各連點兩下仍要能快轉 / 倒退 10 秒。
- 下載鈕與全螢幕鈕合併成**同一顆可拖曳的膠囊**（`MovableVideoControls`）：左邊綠色圓形下載、右邊全螢幕，
  整顆一起拖、位置依直 / 橫向各存一份（沿用 `floatingFullscreenPosition`）；沒有影片可下載時只顯示全螢幕。
  膠囊現在有三顆：綠色實心箭頭＝下載這部影片、影片庫圖示（`VideoLibrary`）＝下載管理、全螢幕；
  工具列的下載管理鈕也改用同一個影片庫圖示，避免和「下載」箭頭混淆。
  設定 →「浮動膠囊」可調整透明度（10～100%，預設 85%），卡片內有即時預覽。
- 換上下一部影片：維持 X 原生的推薦順序（不要改成清單順序）。

### 2.4 下載
- 下載要有聲音（HLS 影音分離需合併）；品質清單要顯示預估檔案大小；
  要能在設定查看診斷記錄、能複製貼給開發者。
- 手機空間不足要給明確訊息。

---

### 2.5 設定頁整理（2026-11-22）
從設定移除、改為「App 固定行為」或已失效的項目（資料結構 `XDisplayOptions` 沒刪，舊的儲存值在
`xDisplayScript` / `xVideoProgressScript` 內一律當作 false，不會再暗中生效）：
- 底部導覽列整組 7 個開關（整排、Home、搜尋、Grok、聊天、通知、底部分享）→ 底部導覽列與發文鈕現在固定隱藏。
- 「觀看圖片／影片時隱藏文字說明 / 標題」→ 只對舊的 `#layers [role=dialog]` 檢視器有效，`/mediaviewer` 不適用。
- 「播放時隱藏進度條下方資訊 / 只隱藏影片下方內文 / 只隱藏影片下方作者／標題」→ 播放頁現在固定只留頭像
  （`X_MEDIAVIEWER_INFO_SCRIPT`）。
- 「隱藏進度條旁的作者頁按鈕」→ 同上，且會和「點頭像進作者頁」衝突。
- 新安裝的預設：「隱藏 X 原本進度條」與「顯示新版進度條」預設為開（已存過的設定不變）。
- 仍保留且有效：下載位置、阻擋廣告頁（說明已改）、下載／全螢幕膠囊透明度、影片播放器、X 文字大小、
  隱藏回覆 / 轉發 / 觀看次數 / 分享、X 進度條（原生隱藏 / 新版進度條 / 自動隱藏秒數）、顯示愛心 / 收藏、
  影片底部隱藏觀看次數 / 分享、影片修復、重設 X 網站資料、診斷記錄、X 改版知識。

---

## 3. 重大設計決策與原因

| 決策 | 原因 |
|---|---|
| 清單影片用「另開檢視分頁」，不是在同頁開 | X 在同頁返回時會重建清單並反覆重新量測高度，位置在返回後 1.5～3 秒會被推走數千像素，**無法靠追位置修好**。另開分頁讓清單 WebView 完全不動。 |
| 返回鍵一次跳過所有 viewer 歷史（`goBackOrForward(-n)`） | 在播放頁上下滑換片時，X 每換一部就 push 一筆歷史，單次 `goBack()` 會退到上一部影片而不是清單。 |
| 隱藏按鈕改用「文字 / 位置 / aria-label」判斷 | 新版 X 幾乎沒有穩定的 `data-testid`。 |
| 外部連結用「頁面層擋點擊 + 原生層擋導向」兩層 | 廣告常是 X 自己的程式導頁或開新視窗，只擋 `<a>` 點擊不夠。 |
| 清單捲動時不掃 DOM（`viewerPossible()`） | MutationObserver 在 X 捲動時每 0.14 秒觸發一次全頁掃描，造成卡頓。 |
| 雙擊快轉不再因放大而停用 | 平移 >24px、縮放是雙指，本來就不會被當成點擊。 |

---

## 4. X 網頁結構觀察（2026-11，會再變，改版先重驗）

- **路由**：影片播放頁是 `/<handle>/status/<id>/mediaviewer`（可帶 `?source=home`）；
  舊的 `vss-scroll-view` / `immersive-viewer` / `#layers [role=dialog]` 容器**在這個路由不存在**。
  所有「是否在播放頁」的判斷要認這個路由（`XMediaNavigation.mediaPath`、各腳本的 regex、
  `X_DEDICATED_X_VIDEO_VIEWER_SCRIPT`）。
- **按鈕只有 aria-label，沒有 data-testid**：愛心 `"N 喜歡. 喜歡"`、收藏 `"N 書籤. 加入書籤"`、
  影片控制 `"影片控制"`、拖曳條 `"搜尋滑桿"`、回覆 / 轉貼 / 分享等同理。
  → 用 `[aria-label*="喜歡"]`、`[aria-label*="書籤"]` 等比對。
- **貼文 `<article>` 沒有 `data-testid="tweet"`**：用 `article`。找貼文編號時
  `<time>` 外的連結不存在 → 用任何 `a[href*="/status/<id>"]`（排除 article 內 `role=link tabindex` 的被引用貼文）。
- **清單影片上蓋一顆 `button[aria-label="查看媒體"]`**（absolute 覆蓋）。舊邏輯把它當成「別的可點元素」略過，
  導致攔截失效。純圖片貼文也有類似按鈕，需另外確認貼文內有 `video`。
- **控制列閒置淡出**：淡出的是**包含 `<video>` 的上層容器**（opacity 0）。
  Android 影片是獨立圖層，不受祖先 opacity 影響，所以影片看得到、按鈕卻透明。
  → 強制「含愛心 / 收藏的祖先」`opacity:1!important`，**不能**排除含 video 的容器。
- **底部導覽列**是膠囊形、不是 `[data-testid=BottomBar]`；**發文鈕**不吃 `a[href^=/compose]` 等選擇器。
  最後用：`nav:has(a[href="/home"]):has(a[href^="/notifications"])` 之類 + 「右下角小型 fixed 元素」位置偵測。
- **作者頭像**：圖片有時是佔位圖，`src*=profile_images` 在剛載入的第一部影片對不上 →
  改用「手指位置底下的小正方形圖片 / 背景圖」判斷。
- **廣告**：常用 X 自己的 JS 導頁或 `window.open`，不一定有 `<a href>` 外部連結。
- **返回清單**：X 先把位置還原正確（約 0.5 秒），之後重新量測上方貼文高度，
  在約 1.5～3 秒把畫面推走，再慢慢拉回。**不要試圖追位置**，用另開分頁避開。
- `t.co` 是 X 的外部連結包裝，要當外部網站擋。
- Google 會拒絕在內嵌 WebView 登入（可能顯示「不安全的瀏覽器」）→ 建議用帳密 / Email 登入。

---

## 5. 程式地圖

`app/src/main/java/com/jimmy/mydownload/`

| 檔案 | 內容 |
|---|---|
| `MultiTabBrowser.kt`（最大，~3700 行） | 瀏覽器本體、分頁狀態、所有注入的 X 腳本、輪詢迴圈（`LaunchedEffect(visible, selected.id)`）、返回邏輯、外部連結封鎖、工具列。 |
| `XVideoProgress.kt` | 自訂進度條、藏原生拖曳條的 CSS、單點叫出進度條的邏輯（含點影片下方 140px）。 |
| `DownloadService.kt` | 前景服務、HLS 下載 / 合併（`MediaExtractor`/`MediaMuxer`）、空間檢查。 |
| `StreamResolver.kt`, `MediaCandidateResolver.kt`, `BrowserVideoDiscovery.kt` | 影片來源偵測、品質清單與大小預估、主影片 ID 對應。 |
| `MainActivity.kt` | 下載管理頁（緊湊列、預覽圖 `DownloadThumb`、清除不存在的檔案）、設定、分享連結直接解析。 |
| `DownloadSettingsScreen.kt`, `DownloadSettings.kt` | 設定與「診斷記錄」檢視 / 複製 / 清除。 |
| `AppLog.kt` | 診斷記錄（見第 6 節）。 |
| `XMediaNavigation.kt` | 媒體路由判斷（`isMediaViewer`），有單元測試。 |

`MultiTabBrowser.kt` 內的重要腳本常數 / 函式（搜尋名稱即可定位）：

- `X_DISPLAY_SCRIPT`：隱藏規則（底部導覽、發文鈕、回覆 / 轉貼 / 分享 / 觀看數的 aria-label 版本、
  右下角浮動鈕位置偵測 `data-mydownload-fab`）。
- `X_VIDEO_NATIVE_ACTION_VISIBILITY_SCRIPT`：愛心 / 收藏保護；只在可能有播放頁時掃描；
  含 `html[data-mydownload-actions-route="1"]` 的強制不透明規則。
- `X_VIEW_IN_TAB_SCRIPT`：清單點影片 → 取得作者 + 貼文編號 → `mydownloadviewer://open?u=…`
  （直接播放頁，5 秒內沒有 `<video>` 就退回貼文頁）；同一支腳本也負責**外部連結點擊封鎖**
  （`[MDEXT]`）與診斷日誌（`[MDVIT]`）。
- `X_MEDIAVIEWER_INFO_SCRIPT`：播放頁隱藏名稱 / 內文 / 藍勾勾 / 跟隨鈕；頭像點擊進作者頁。
  直向與橫向比例不同（橫向 `within` 起點 0.2、左側 0.5；直向 0.35、0.4）。
- `xMediaviewerFillScript`：播放頁全螢幕填滿（每張卡片各自縮放）。
- `X_VIDEO_DOUBLE_TAP_SEEK_SCRIPT`：左右連點兩下 ±10 秒；放大時也有效。
- `X_MEDIA_PINCH_ZOOM_SCRIPT`：雙指縮放。
- `X_LAYOUT_DIAG_SCRIPT`：播放頁版面診斷。
- Kotlin 端：`openViewerTab`、`closeViewerTab`、`closePopupTab`、`popupParents`（登入彈出視窗）、
  `goBackPreservingScroll`、`restoreScroll`（等位置穩定才顯示）、`isBlockedExternal`
  （`shouldOverrideUrlLoading` + `onPageStarted`）、`showBlockedLinkToast`（1 秒 Toast）、
  `spaNavigate`（首頁 / 書籤鈕在頁內跳轉，不重新載入）。

---

## 6. 診斷系統（除錯主要手段）

「設定 → 診斷記錄」可複製貼給開發者。標籤：

| 標籤 | 內容 |
|---|---|
| 下載 / 合併 | HLS 片段數、是否 fMP4、音訊清單、合併軌道與首個樣本時間、錯誤。 |
| 偵測 | 影片 ID 對應（`strict`、已捕捉 / 關聯 / 使用）。 |
| 版面 | 播放頁 JSON：路徑、視窗大小、`data-mydownload-*` 屬性、影片 / 愛心 / 收藏矩形、被哪一層藏起來（`DIV:block/visible/0`）。 |
| 返回 | 「按返回 從 … 退 N 步 到 … 已存 Y」與返回後 0.5/1.5/3 秒的實際 Y；「點清單影片 → 開檢視分頁」；「播放頁沒有影片，改走貼文頁」。 |
| 點擊 | 頁面內 `console.log('[MDVIT] …')` 轉成的記錄：腳本安裝、原始 touchstart / pointerdown / click、攔截的判斷與略過原因、`擋下外部連結 / 跳轉`、`頭像探測 橫/直 命中/沒中 + 元素鏈`。 |
| 設定 | 設定變更。 |

**原則：改不好時先加診斷，不要連猜好幾版。** 這輪「點清單影片沒另開分頁」就是靠
逐層加記錄才找到三個不同原因（見第 7 節）。頁面端要寫入診斷記錄，用 `console.log('[MDVIT] …')`，
`WebChromeClient.onConsoleMessage` 會轉進 `AppLog`。注意頁面端的 rate limit 不要把同一次點擊的
後半段記錄吃掉。

---

## 7. 踩過的坑（按問題整理）

1. **愛心 / 收藏在直向消失**：不是被「隱藏」，而是被閒置淡出（含影片的祖先 opacity 0）。
   先以為是 `[aria-label*="timeline"]` 的整頁隱藏規則誤傷，那條確實該縮小，但真正原因是淡出。
2. **強制按鈕不透明後，轉貼 / 分享鈕「跑出來」**：它們原本也是被淡出蓋住，不是被隱藏；
   設定裡的隱藏開關靠 `data-testid`，對新版完全沒效 → 補 aria-label 版本。
3. **發文鈕每版都擋不到**：選擇器全猜錯。診斷「角落」記錄顯示右下角元素鏈，再用位置偵測。
4. **點清單影片沒另開分頁（三層原因）**：
   - ① `article[data-testid="tweet"]` 找不到貼文（靜默回傳 null，所以連「略過」記錄都沒有）；
   - ② 影片上的「查看媒體」按鈕被當成其他可點元素；
   - ③ 沒有 `<time>` 連結、備用連結的 regex / 排除條件太嚴。
   → 教訓：**每個提早 return 都要有記錄**，否則看不出卡在哪。
5. **返回位置不穩**：見第 4 節。連續三版想「追位置」都徒勞，改用另開分頁。
6. **返回退到上一部影片**：換片 push 歷史；用 `goBackOrForward(-n)`。
7. **清單捲動卡頓**：觀察者每 0.14 秒全頁掃描、發文鈕偵測每 5 秒打近百個 `elementFromPoint`。
   改成只在可能有播放頁時掃、發文鈕只在沒找到時偵測。
8. **頭像第一部影片點不了**：`src*=profile_images` 對不上，改用座標底下的小正方形圖。
9. **診斷 JSON 的 `acts` 裡出現 `DIV:block/visible/0`**：就是淡出的證據，記得看這個欄位。
10. **PR 有衝突**：分支沒重新從最新 `origin/main` 開（見第 1 節）。
11. **Kotlin raw string（`""" … """`）**：`$` 後面接識別字會被當樣板；JS 正則裡的 `$/`、`$)` 是安全的。
    寫 JS 正則的反斜線時，Python 產生檔案要用 `\\/` → 檔案內才是 `\/`。
12. **協程**：`async` 必須在 `coroutineScope { … }` 的 receiver 內呼叫（CI 抓過編譯錯誤）。
13. **雙擊快轉**：X 自己的雙擊追蹤會和我們配對，導致隨機大跳躍；已有 `guardNativeSeek` / 過期目標防護，
    不要輕易拿掉。放大時的停用判斷已移除（本輪）。
14. **連續瀏覽幾部之後，下載會抓到之前抓過的影片**：播放頁卡片沒有 poster、來源是 blob，影片 ID 只能靠
    `recentSegmentId`（看 `performance.getEntriesByType('resource')` 最近的片段）。**瀏覽器的 resource timing
    緩衝只有 250 筆，滿了就不再記錄**，之後「最近 12 筆」其實是舊資料。修法：`setResourceTimingBufferSize(5000)`、
    `resourcetimingbufferfull` 時 `clearResourceTimings()`，並優先看最近 10 秒內的片段
    （`BrowserVideoDiscovery.ACTIVE_VIDEO_SCAN_SCRIPT`）。診斷看「偵測」記錄的 `影片ID`。
15. **下載檔名要用影片內文**：`VideoTitle.preferred` 本來就優先用貼文內文，但播放頁卡片不是 `<article>`、
    內文也沒有 `tweetText` 標籤，讀不到就退成「X影片」。現在 `ACTIVE_VIDEO_SCAN_SCRIPT` 內的
    `mediaviewerText()` 取「含該影片與愛心鈕、且不含別的影片」的卡片文字（排除按鈕、進度條、作者名稱 / 帳號連結、
    數字、時間、「顯示更多」等），讀 `textContent`（不受我們自己的 `visibility:hidden` 影響）。檔名最多 78 字。

---

## 8. 下次 X 改版的檢查清單

1. **先看診斷記錄**，不要先改程式。要使用者：重開 App → 做一次問題操作 → 複製「點擊 / 返回 / 版面」。
2. 確認路由：播放頁是不是還在 `/…/mediaviewer`？有沒有新的路由或容器？
   更新 `XMediaNavigation`、`X_DEDICATED_X_VIDEO_VIEWER_SCRIPT` 與各腳本 regex。
3. 確認 **`article` 與按鈕的標記**：`data-testid` / `aria-label` 變了嗎？
   （愛心 / 收藏 / 分享 / 轉貼 / 回覆 / 查看媒體 / 影片控制 / 搜尋滑桿 / 藍勾勾 `icon-verified`）。
4. 清單點影片攔截：看「點擊」記錄是哪一步略過（`在貼文內`、`命中`、`略過:…`、`攔截成功`、
   `點清單影片 → 開檢視分頁`）。
5. 隱藏項目（底部導覽、發文鈕、跟隨、名稱 / 內文）：用文字 / 位置規則，必要時看「角落」類診斷。
6. 返回：看「按返回 … 退 N 步」與 0.5/1.5/3 秒的 Y。
7. 外部連結：看「擋下外部連結 / 跳轉 / 頁面載入」記錄。
8. 效能：改完觀察清單捲動；任何新的全頁掃描都要加「只有需要時才跑」的條件與節流。
9. 橫向與直向都要測；第一部影片與滑到下一部都要測。
10. 改完一律 commit → PR → squash → 等 CI → **主動**貼連結與測試步驟。

---

## 9. 已知限制與待驗證

- 以下是依日誌推論的修法，**尚未在手機上確認**：頭像點擊（直 / 橫）、橫向隱藏區域比例、
  直接載入播放頁的成功率（失敗會自動退回貼文頁）、放大時雙擊快轉的手感。
- 播放頁隱藏名稱 / 內文靠輪詢（約 1 秒），換片時可能先閃一下才消失。
- 外部連結封鎖只在清單頁（首頁、書籤、讚）生效；登入頁不受影響。
- 隱藏規則靠文字與位置，X 改字樣或排版可能失效。
- Google 登入在 WebView 可能被拒。
- 部分 HLS 下載仍可能沒有聲音（沒抓到主播放清單）；大檔下載受手機空間限制。
