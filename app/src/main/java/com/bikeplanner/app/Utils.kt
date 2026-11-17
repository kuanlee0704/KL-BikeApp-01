package com.bikeplanner.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------- 距離計算 ----------

fun haversine(a: TrackPoint, b: TrackPoint): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val s = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(s))
}

fun pathDistance(pts: List<TrackPoint>): Double {
    var d = 0.0
    for (i in 1 until pts.size) d += haversine(pts[i - 1], pts[i])
    return d
}

// ---------- 格式化 ----------

fun fmtDistance(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.US, "%.2f km", m / 1000.0)

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

fun fmtSpeed(mps: Double): String = String.format(Locale.US, "%.1f km/h", mps * 3.6)

fun fmtDateTime(ms: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(ms))

fun fmtMinutes(seconds: Long): String {
    val m = (seconds / 60).toInt()
    return if (m < 60) "$m 分" else "${m / 60} 小時 ${m % 60} 分"
}

fun modeLabel(mode: String): String = when (mode) {
    "bicycle" -> "腳踏車路線"
    "walk" -> "道路路徑（步行）"
    else -> ""
}

// ---------- 座標 / 路線 ----------

fun TrackPoint.toLatLng(): LatLng = LatLng(lat, lon)

/** 解碼 Google encoded polyline。 */
fun decodePolyline(encoded: String): List<LatLng> {
    val out = ArrayList<LatLng>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lng += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)

        out.add(LatLng(lat / 1e5, lng / 1e5))
    }
    return out
}

/** 規劃路線在地圖上要畫的路徑：有道路路線用道路，否則用點對點直線。 */
fun Plan.path(): List<LatLng> =
    if (routeEncoded.isNotEmpty()) decodePolyline(routeEncoded) else waypoints.map { it.toLatLng() }

/** 取得最近一次定位（需已有定位權限）；沒有快取位置時再要一次目前位置。 */
@SuppressLint("MissingPermission")
suspend fun lastKnownLatLng(ctx: Context): LatLng? {
    if (!hasLocationPermission(ctx)) return null
    val client = LocationServices.getFusedLocationProviderClient(ctx)
    return suspendCancellableCoroutine { cont ->
        client.lastLocation
            .addOnSuccessListener { l ->
                if (l != null) {
                    cont.resume(LatLng(l.latitude, l.longitude))
                } else {
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { c ->
                            cont.resume(c?.let { LatLng(it.latitude, it.longitude) })
                        }
                        .addOnFailureListener { cont.resume(null) }
                }
            }
            .addOnFailureListener { cont.resume(null) }
    }
}

// ---------- 權限 / 定位狀態 ----------

fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

fun hasFineLocation(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun isGpsEnabled(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
}

// ---------- 日期時間選擇 ----------

fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { if (initial > 0) timeInMillis = initial }
    DatePickerDialog(
        ctx,
        { _, y, m, d ->
            TimePickerDialog(
                ctx,
                { _, h, min ->
                    val c = Calendar.getInstance()
                    c.set(y, m, d, h, min, 0)
                    c.set(Calendar.MILLISECOND, 0)
                    onPicked(c.timeInMillis)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}

// ---------- Google 地圖整合 ----------

private fun coord(p: TrackPoint): String = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lon)

/**
 * 用 Google 地圖（腳踏車模式）開啟路線。
 * 第一點 = 起點、最後一點 = 終點、中間點 = 途經點（Google 限制最多約 9 個，超過會均勻抽樣）。
 */
fun openInGoogleMaps(ctx: Context, pts: List<TrackPoint>) {
    if (pts.isEmpty()) return
    val sb = StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=bicycling")
    if (pts.size >= 2) {
        sb.append("&origin=").append(coord(pts.first()))
        val mid = pts.subList(1, pts.size - 1)
        val picked = if (mid.size <= 9) mid else List(9) { i -> mid[(i * (mid.size - 1)) / 8] }
        if (picked.isNotEmpty()) {
            sb.append("&waypoints=").append(picked.joinToString("%7C") { coord(it) })
        }
    }
    sb.append("&destination=").append(coord(pts.last()))

    val uri = Uri.parse(sb.toString())
    val withPkg = Intent(Intent.ACTION_VIEW, uri)
        .setPackage("com.google.android.apps.maps")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(withPkg)
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---------- GPX 匯出 ----------

private fun escapeXml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun buildGpx(name: String, pts: List<TrackPoint>): String {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"BikePlanner\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    sb.append("<trk><name>").append(escapeXml(name)).append("</name><trkseg>\n")
    for (p in pts) {
        sb.append(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\">", p.lat, p.lon))
        if (p.alt != 0.0) sb.append(String.format(Locale.US, "<ele>%.1f</ele>", p.alt))
        if (p.time > 0) sb.append("<time>").append(iso.format(Date(p.time))).append("</time>")
        sb.append("</trkpt>\n")
    }
    sb.append("</trkseg></trk></gpx>\n")
    return sb.toString()
}

fun shareGpx(ctx: Context, meta: RideMeta, pts: List<TrackPoint>) {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(meta.startTime))
    val file = File(dir, "ride_$stamp.gpx")
    file.writeText(buildGpx(meta.name, pts))
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "匯出 GPX"))
}
