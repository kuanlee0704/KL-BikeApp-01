package com.bikeplanner.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Google Routes API（沿道路規劃路線與距離）與 Places API (New)（地點搜尋）。
 * 需要在 Google Cloud 啟用這兩個 API 並設定 MAPS_API_KEY（見 README）。
 */
object GoogleApi {

    private const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
    private const val PLACES_URL = "https://places.googleapis.com/v1/places:searchText"

    val keyConfigured: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

    data class RouteResult(
        val encoded: String,
        val distanceM: Double,
        val durationS: Long,
        val mode: String // "bicycle" 或 "walk"
    )

    data class PlaceHit(
        val name: String,
        val address: String,
        val lat: Double,
        val lon: Double
    )

    // ---------- 路線 ----------

    /**
     * 規劃「起點 → 途經點 → 終點」的道路路線。
     * 先請 Google 規劃腳踏車路線；該地區沒有腳踏車資料時，退回一般道路（步行）路徑。
     * Routes API 最多 25 個途經點，所以最多 27 個點。
     */
    suspend fun computeRoute(ctx: Context, pts: List<TrackPoint>): Result<RouteResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(pts.size >= 2) { "至少需要 2 個點" }
                if (!keyConfigured) throw IOException("尚未設定 Google API Key")
                var lastError: IOException? = null
                for ((apiMode, label) in listOf("BICYCLE" to "bicycle", "WALK" to "walk")) {
                    try {
                        val r = routeWithMode(ctx, pts, apiMode, label)
                        if (r != null) return@runCatching r
                    } catch (e: IOException) {
                        lastError = e
                    }
                }
                throw lastError ?: IOException("找不到可行的道路路線，請調整路徑點位置")
            }
        }

    private fun routeWithMode(ctx: Context, pts: List<TrackPoint>, apiMode: String, label: String): RouteResult? {
        val body = JSONObject()
            .put("origin", waypoint(pts.first()))
            .put("destination", waypoint(pts.last()))
            .put("travelMode", apiMode)
            .put("languageCode", "zh-TW")
        if (pts.size > 2) {
            val mid = JSONArray()
            pts.subList(1, pts.size - 1).forEach { mid.put(waypoint(it)) }
            body.put("intermediates", mid)
        }
        val json = post(
            ctx, ROUTES_URL,
            "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline",
            body
        )
        val routes = json.optJSONArray("routes")
        if (routes == null || routes.length() == 0) return null
        val r = routes.getJSONObject(0)
        val enc = r.optJSONObject("polyline")?.optString("encodedPolyline") ?: ""
        if (enc.isEmpty()) return null
        val dur = r.optString("duration", "0s").removeSuffix("s").toDoubleOrNull()?.toLong() ?: 0L
        return RouteResult(enc, r.optDouble("distanceMeters", 0.0), dur, label)
    }

    private fun waypoint(p: TrackPoint): JSONObject = JSONObject().put(
        "location",
        JSONObject().put(
            "latLng",
            JSONObject().put("latitude", p.lat).put("longitude", p.lon)
        )
    )

    // ---------- 地點搜尋 ----------

    suspend fun searchPlaces(ctx: Context, query: String, near: LatLng?): Result<List<PlaceHit>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!keyConfigured) throw IOException("尚未設定 Google API Key")
                val body = JSONObject()
                    .put("textQuery", query)
                    .put("languageCode", "zh-TW")
                    .put("maxResultCount", 6)
                if (near != null) {
                    body.put(
                        "locationBias",
                        JSONObject().put(
                            "circle",
                            JSONObject()
                                .put("center", JSONObject().put("latitude", near.latitude).put("longitude", near.longitude))
                                .put("radius", 30000.0)
                        )
                    )
                }
                val json = post(
                    ctx, PLACES_URL,
                    "places.displayName,places.formattedAddress,places.location",
                    body
                )
                val arr = json.optJSONArray("places") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val p = arr.getJSONObject(i)
                    val loc = p.optJSONObject("location") ?: return@mapNotNull null
                    PlaceHit(
                        name = p.optJSONObject("displayName")?.optString("text").orEmpty(),
                        address = p.optString("formattedAddress"),
                        lat = loc.getDouble("latitude"),
                        lon = loc.getDouble("longitude")
                    )
                }
            }
        }

    // ---------- HTTP ----------

    private fun post(ctx: Context, url: String, fieldMask: String, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
            conn.setRequestProperty("X-Goog-FieldMask", fieldMask)
            // 若 API Key 設了「Android 應用程式」限制，需帶上套件名稱與簽章 SHA-1
            conn.setRequestProperty("X-Android-Package", ctx.packageName)
            certSha1(ctx)?.let { conn.setRequestProperty("X-Android-Cert", it) }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    text.take(200)
                }
                throw IOException("HTTP $code：$msg")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun certSha1(ctx: Context): String? = try {
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        sig?.let {
            MessageDigest.getInstance("SHA-1").digest(it.toByteArray())
                .joinToString("") { b -> "%02X".format(b) }
        }
    } catch (e: Exception) {
        null
    }
}
