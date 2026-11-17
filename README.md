# 單車日誌 BikePlanner

Android 腳踏車 App：**搜尋地點、沿道路規劃路線 → GPS 軌跡記錄 → 騎完回顧**。
推到 GitHub 後由 GitHub Actions 自動編譯 APK，手機直接下載安裝。

## 功能

| 分頁 | 功能 |
|---|---|
| 規劃 | **Google 地圖**全螢幕；上方搜尋列可搜尋地點／地址／店名（Places API）；點搜尋結果預覽後「加入為路徑點」，也可直接點地圖加點；每次改動自動請 Google 規劃**沿道路**的路線，顯示道路距離與預估時間；可設定名稱、出發時間、備註；可一鍵交給 Google 地圖 App 導航 |
| 記錄 | 前景服務持續記錄 GPS（螢幕關閉也會記）；上方大字顯示時間、距離、目前/平均速度；可暫停、結束並儲存；規劃路線為藍線，實際軌跡為紅線；拖曳地圖會解除跟隨，可按「回到目前位置」 |
| 回顧 | 歷次騎乘清單與累計統計；點進去看紅藍線對照、均速/極速；匯出 **GPX**；刪除 |

## 一、設定 Google API Key（必做，否則地圖是空白的）

地圖、搜尋、道路路線都走 Google Maps Platform，需要一組 API Key。

1. 打開 [Google Cloud Console](https://console.cloud.google.com/)，建立專案並**啟用帳單**（需綁信用卡；Google 有每月免費額度，個人使用通常用不完，但建議之後在「帳單 → 預算與快訊」設個預算提醒）。
2. 到「API 和服務 → 程式庫」，啟用這三個：
   - **Maps SDK for Android**
   - **Routes API**
   - **Places API (New)**
3. 到「API 和服務 → 憑證 → 建立憑證 → API 金鑰」，複製金鑰。
4. （建議）編輯該金鑰加上限制，避免被別人盜用：
   - 應用程式限制：**Android 應用程式** → 新增項目
     - 套件名稱：`com.bikeplanner.app`
     - SHA-1 憑證指紋：`1A:97:9A:24:7D:41:7D:DF:8D:8E:70:AA:1F:0D:84:02:95:EB:C1:37`
       （這是專案內 `app/debug.keystore` 的指紋，每次建置都相同）
   - API 限制：只勾選上面三個 API
5. 到你的 GitHub repo → **Settings → Secrets and variables → Actions → New repository secret**
   - Name：`MAPS_API_KEY`
   - Secret：貼上金鑰
6. 之後每次 push，Actions 就會把金鑰編進 APK。

> 在自己電腦用 Android Studio 編譯時，把 `MAPS_API_KEY=你的金鑰` 加到專案根目錄的 `local.properties`。

## 二、推到 GitHub 並產出 APK

```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<你的帳號>/BikePlanner.git
git push -u origin main
```

1. repo 的 **Actions** 分頁會自動跑 `Build APK`（約 3–6 分鐘）。
2. 完成後用手機瀏覽器開 repo 的 **Releases** → 最新的 `Build N` → 下載 `BikePlanner.apk`。
3. 如果 Release 沒出現：Settings → Actions → General → Workflow permissions → 選 **Read and write permissions**。

## 三、安裝到手機

1. 開啟下載的 APK，依提示允許「安裝不明來源應用程式」。
2. 第一次開啟 App 會直接進入**「開始前先完成設定」**頁，所有權限集中在這一頁，按底部「下一步」一步步完成：
   - **定位權限（必要）**：跳出視窗請選「精確」＋「使用 App 時允許」。
   - **開啟手機定位 GPS（必要）**：會帶你到系統的位置開關。
   - **通知（建議，Android 13 以上）**：騎乘中的通知列與暫停／結束按鈕。
   - **背景執行／電池不受限制（建議）**：避免螢幕關閉時記錄被中斷。
   
   從系統設定回來時 App 會自動重新檢查，已完成的項目會打勾。若曾選過「不再詢問」，按鈕會改帶你到 App 的系統設定頁手動開啟。必要項目沒完成前可按「先跳過」，但按「開始騎乘」時會再次帶你完成。
3. 之後想再檢查，到「記錄」頁右下角的「權限與設定」。
4. 之後 push 出的新 APK 可直接覆蓋安裝，資料會保留（簽章固定）。

> 從舊版（OpenStreetMap 版）升級：簽章不同，需先移除舊版再安裝，舊資料會消失。

## 四、使用方式

1. **規劃** → 新增路線 → 搜尋起點並「加入為路徑點」→ 再加途經點與終點（或直接點地圖）→ 看到道路距離後「名稱 / 時間」設定出發時間 → 儲存。
2. 出發前在路線卡片按「開始騎乘」；要語音導航按「Google 導航」。
3. **記錄**：按「開始騎乘」，手機放口袋/車把。結束按「結束並儲存」（距離不足 20 m 不會儲存）。
4. **回顧**：點任一筆看軌跡，或匯出 GPX。

## 關於腳踏車路線

App 會先請 Google 規劃**腳踏車路線**；如果該地區沒有腳踏車路線資料，會自動退回一般道路（步行）路徑，並在畫面上標示「道路路徑（步行）」。這時距離是沿道路的合理估計，但不保證是適合騎車的路（可能經過人行道、樓梯）。

## 專案結構

```
app/src/main/java/com/bikeplanner/app/
  MainActivity.kt    入口、三分頁導覽、權限閘門
  SetupScreen.kt     集中式權限引導頁（定位、GPS、通知、電池）
  PlanScreen.kt      規劃：清單 + 地圖編輯器（搜尋、道路路線）
  RecordScreen.kt    即時記錄畫面
  HistoryScreen.kt   回顧清單與詳情
  BikeMap.kt         Google 地圖元件（Maps Compose）
  GoogleApi.kt       Routes API 與 Places API (New) 呼叫
  TrackingService.kt GPS 前景服務
  TrackingState.kt   即時狀態（Service ↔ UI）
  Storage.kt         JSON 檔案儲存（路線、騎乘紀錄）
  Utils.kt           距離、格式化、polyline 解碼、Google 地圖開啟、GPX 匯出
app/debug.keystore   固定的 debug 簽章（公開專案可以放，只用於側載）
.github/workflows/build-apk.yml   自動編譯 + 發佈 APK
```

## 已知限制

- 目前是 **debug APK**（可直接安裝，不能上架 Play 商店）。
- 路徑點最多 27 個（Routes API 限制：起點 + 25 個途經點 + 終點）。
- 部分手機（小米、OPPO、vivo、三星等）會積極殺背景，若記錄中斷，請把本 App 的電池用量設為「不受限制」。
- 可再加：出發前提醒通知、路線匯入 GPX、海拔曲線圖。
