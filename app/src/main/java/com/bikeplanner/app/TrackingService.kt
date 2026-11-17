package com.bikeplanner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.UUID
import kotlin.math.max

/** 前景服務：螢幕關閉、App 在背景時仍持續記錄 GPS。 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.bikeplanner.app.action.START"
        const val ACTION_PAUSE = "com.bikeplanner.app.action.PAUSE"
        const val ACTION_RESUME = "com.bikeplanner.app.action.RESUME"
        const val ACTION_STOP = "com.bikeplanner.app.action.STOP"
        const val EXTRA_PLAN_ID = "plan_id"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context, planId: String?) {
            val i = Intent(ctx, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PLAN_ID, planId)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun send(ctx: Context, action: String) {
            ctx.startService(Intent(ctx, TrackingService::class.java).setAction(action))
        }
    }

    private lateinit var client: FusedLocationProviderClient

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) handleLocation(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(intent.getStringExtra(EXTRA_PLAN_ID))
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> stopAndSave()
            else -> if (!TrackingState.state.value.recording) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        super.onDestroy()
    }

    // ---------- 開始 / 暫停 / 結束 ----------

    private fun startTracking(planId: String?) {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            toast("無法啟動定位服務，請確認已授權定位權限")
            stopSelf()
            return
        }
        if (TrackingState.state.value.recording) return // 已在記錄中

        TrackingState.state.value = RecState(
            recording = true,
            planId = planId,
            startTime = System.currentTimeMillis()
        )

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(2f)
            .build()
        try {
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            toast("沒有定位權限")
            TrackingState.state.value = RecState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun setPaused(pause: Boolean) {
        val s = TrackingState.state.value
        if (!s.recording || s.paused == pause) return
        val now = System.currentTimeMillis()
        TrackingState.state.value =
            if (pause) s.copy(paused = true, pauseStart = now)
            else s.copy(paused = false, pausedTotalMs = s.pausedTotalMs + (now - s.pauseStart), pauseStart = 0L)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun stopAndSave() {
        val s = TrackingState.state.value
        client.removeLocationUpdates(callback)
        if (s.recording) {
            val duration = s.elapsedMs(System.currentTimeMillis())
            if (s.points.size >= 2 && s.distanceM >= 20.0) {
                val meta = RideMeta(
                    id = UUID.randomUUID().toString(),
                    name = "騎乘 " + fmtDateTime(s.startTime),
                    startTime = s.startTime,
                    durationMs = duration,
                    distanceM = s.distanceM,
                    maxSpeedMps = s.maxSpeedMps,
                    planId = s.planId
                )
                Storage.get(this).saveRide(meta, s.points)
                TrackingState.savedCounter.value = TrackingState.savedCounter.value + 1
                toast("已儲存：" + fmtDistance(s.distanceM))
            } else {
                toast("距離太短，未儲存")
            }
        }
        TrackingState.state.value = RecState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- 處理定位 ----------

    private fun handleLocation(loc: Location) {
        val s = TrackingState.state.value
        if (!s.recording || s.paused) return
        if (loc.hasAccuracy() && loc.accuracy > 35f) return // 精度太差的點丟掉

        val now = if (loc.time > 0) loc.time else System.currentTimeMillis()
        val last = s.points.lastOrNull()
        var added = 0.0
        var speed = if (loc.hasSpeed()) loc.speed else 0f

        if (last != null) {
            val probe = TrackPoint(loc.latitude, loc.longitude, now)
            val d = haversine(last, probe)
            val dt = (now - last.time) / 1000.0
            if (d < 2.0) { // 原地抖動，忽略
                TrackingState.state.value = s.copy(currentSpeedMps = if (loc.hasSpeed()) loc.speed else 0f)
                return
            }
            if (dt > 0 && d / dt > 25.0) return // 瞬間位移超過 90 km/h，視為飄點
            added = d
            if (!loc.hasSpeed() && dt > 0) speed = (d / dt).toFloat()
        }

        val p = TrackPoint(
            lat = loc.latitude,
            lon = loc.longitude,
            time = now,
            alt = if (loc.hasAltitude()) loc.altitude else 0.0,
            speed = speed
        )
        TrackingState.state.value = s.copy(
            points = s.points + p,
            distanceM = s.distanceM + added,
            maxSpeedMps = max(s.maxSpeedMps, speed),
            currentSpeedMps = speed
        )
    }

    // ---------- 通知 ----------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "騎乘記錄", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun actionIntent(action: String, req: Int): PendingIntent =
        PendingIntent.getService(
            this, req,
            Intent(this, TrackingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val paused = TrackingState.state.value.paused
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (paused) "騎乘已暫停" else "騎乘記錄中")
            .setContentText("點開 App 查看路線與數據")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                0,
                if (paused) "繼續" else "暫停",
                actionIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 1)
            )
            .addAction(0, "結束並儲存", actionIntent(ACTION_STOP, 2))
            .build()
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
    }
}
