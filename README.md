# 單車日誌 BikePlanner

Android 腳踏車 App：**預先規劃路線 → GPS 軌跡記錄 → 騎完回顧**。
推到 GitHub 後，由 GitHub Actions 自動編譯出 APK，手機直接下載安裝。

## 功能

| 分頁 | 功能 |
|---|---|
| 規劃 | 在地圖上點選路徑點建立路線、設定出發時間與備註；可一鍵用 **Google 地圖（腳踏車模式）** 開啟導航 |
| 記錄 | 前景服務持續記錄 GPS（螢幕關閉也會記）；顯示距離、時間、目前/平均速度；可暫停、結束並儲存；規劃路線以藍線疊在地圖上，實際軌跡為紅線 |
| 回顧 | 歷次騎乘清單與累計統計；點進去看完整軌跡（紅）與原規劃路線（藍）對照、均速/極速；可匯出 **GPX**（Strava、Komoot、Google Earth 等可匯入）、刪除 |

地圖使用 OpenStreetMap（osmdroid），**不需要任何 API Key**。

## 一、推到 GitHub 並產出 APK

1. 在 GitHub 建立一個新的空 repository（例如 `BikePlanner`）。
2. 在本專案資料夾執行：

   ```bash
   git init
   git add .
   git commit -m "Initial commit"
   git branch -M main
   git remote add origin https://github.com/<你的帳號>/BikePlanner.git
   git push -u origin main
   ```

3. 到 repo 的 **Actions** 分頁，會看到 `Build APK` 正在執行（約 3–6 分鐘）。
4. 完成後有兩種方式拿 APK：
   - **Releases**（推薦，手機瀏覽器可直接點）：repo 右側 Releases → 最新的 `Build N` → 下載 `BikePlanner.apk`
   - **Actions → 該次執行 → Artifacts → BikePlanner-apk**（是 zip，需先解壓）

> 第一次若 Release 沒出現：repo → Settings → Actions → General → Workflow permissions，選 **Read and write permissions**。

## 二、安裝到手機

1. 用手機瀏覽器開啟 Releases 頁面，下載 `BikePlanner.apk`。
2. 點開 APK，依提示允許「安裝不明來源應用程式」（只需對該瀏覽器允許一次）。
3. 開啟 App，授權**定位**與**通知**權限。
   - 定位請選「使用 App 時」或「一律允許」皆可（記錄時有前景通知，螢幕關閉仍會持續記錄）。
4. 之後每次 push 到 `main` 都會產生新的 APK；直接覆蓋安裝即可保留資料（簽章用 debug key，每次相同）。

## 三、使用方式

1. **規劃**：新增路線 → 點地圖加路徑點 → 設定出發時間 → 儲存。
2. 出發前在路線卡片按「開始騎乘」（或到「記錄」頁選路線）。要語音導航可按「Google 地圖」。
3. **記錄**：按「開始騎乘」，把手機放口袋/車把即可。結束時按「結束並儲存」（距離不足 20 m 不會儲存）。
4. **回顧**：點任何一筆查看軌跡，或匯出 GPX。

## 專案結構

```
app/src/main/java/com/bikeplanner/app/
  MainActivity.kt    入口、三分頁導覽、權限
  PlanScreen.kt      規劃（清單 + 地圖編輯器）
  RecordScreen.kt    即時記錄畫面
  HistoryScreen.kt   回顧清單與詳情
  OsmMap.kt          OpenStreetMap 地圖元件
  TrackingService.kt GPS 前景服務
  TrackingState.kt   即時狀態（Service ↔ UI）
  Storage.kt         JSON 檔案儲存（路線、騎乘紀錄）
  Utils.kt           距離、格式化、Google 地圖開啟、GPX 匯出
.github/workflows/build-apk.yml   自動編譯 + 發佈 APK
```

## 已知限制與可延伸方向

- 規劃的路徑點之間以**直線**連接（距離為估算值）。需要沿道路的精準導航請用「Google 地圖」按鈕交給 Google 地圖規劃。
- 目前出的是 **debug APK**（可直接安裝）。若要上架 Play 商店，需要建立簽章金鑰並改用 `bundleRelease`。
- 想改用 Google Maps SDK 取代 OpenStreetMap：需申請 Maps API Key，替換 `OsmMap.kt` 即可。
- 可再加：出發前提醒通知、地點搜尋、海拔曲線圖、路線匯入 GPX。
- 部分手機（小米、OPPO、vivo、三星等）會積極殺背景，若記錄中斷，請到系統設定把本 App 的電池用量設為「不受限制」。
