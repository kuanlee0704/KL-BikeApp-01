package com.bikeplanner.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** 目前各項權限／系統設定的狀態。 */
data class PermState(
    val fineLocation: Boolean,
    val anyLocation: Boolean,
    val gps: Boolean,
    val notif: Boolean,
    val battery: Boolean
) {
    /** 沒有這兩項就無法記錄軌跡。 */
    val requiredOk: Boolean get() = fineLocation && gps
}

fun readPermState(ctx: Context): PermState {
    val notif = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return PermState(
        fineLocation = hasFineLocation(ctx),
        anyLocation = hasLocationPermission(ctx),
        gps = isGpsEnabled(ctx),
        notif = notif,
        battery = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    )
}

private class Step(
    val title: String,
    val short: String,
    val desc: String,
    val required: Boolean,
    val done: Boolean,
    val button: String,
    val action: () -> Unit
)

/**
 * 集中式權限引導：把所有需要使用者手動處理的項目放在同一頁，
 * 一步步帶著完成；從系統設定回來時由 App 重新讀取狀態。
 */
@Composable
fun SetupScreen(
    perm: PermState,
    onRefresh: () -> Unit,
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    var askedLoc by rememberSaveable { mutableStateOf(false) }
    var askedNotif by rememberSaveable { mutableStateOf(false) }

    val locLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefresh() }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    fun openAppSettings() {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // 被「不再詢問」擋住時，系統不會再跳出對話框，只能請使用者到設定頁手動開
    fun blocked(askedBefore: Boolean, permission: String): Boolean =
        askedBefore && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    fun askLocation() {
        if (blocked(askedLoc, Manifest.permission.ACCESS_FINE_LOCATION)) {
            openAppSettings()
        } else {
            askedLoc = true
            locLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    fun askNotification() {
        if (Build.VERSION.SDK_INT < 33) return
        if (blocked(askedNotif, Manifest.permission.POST_NOTIFICATIONS)) {
            openAppSettings()
        } else {
            askedNotif = true
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun openGpsSettings() {
        ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun askBattery() {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                openAppSettings()
            }
        }
    }

    val steps = buildList {
        add(
            Step(
                title = "定位權限（精確位置）",
                short = "授權定位",
                desc = "用來記錄騎乘軌跡、顯示你在地圖上的位置。跳出視窗時請選「精確」，再選「使用 App 時允許」即可。",
                required = true,
                done = perm.fineLocation,
                button = "授權定位",
                action = ::askLocation
            )
        )
        add(
            Step(
                title = "開啟手機定位（GPS）",
                short = "開啟定位",
                desc = "手機的「位置資訊」開關必須是開的，否則收不到 GPS 訊號。",
                required = true,
                done = perm.gps,
                button = "開啟定位",
                action = ::openGpsSettings
            )
        )
        if (Build.VERSION.SDK_INT >= 33) {
            add(
                Step(
                    title = "通知",
                    short = "允許通知",
                    desc = "騎乘中會在通知列顯示記錄狀態，並提供「暫停／結束」按鈕。不允許也能記錄，只是看不到這則通知。",
                    required = false,
                    done = perm.notif,
                    button = "允許通知",
                    action = ::askNotification
                )
            )
        }
        add(
            Step(
                title = "背景執行（電池不受限制）",
                short = "允許背景執行",
                desc = "避免手機在螢幕關閉時把 App 停掉而中斷記錄。小米、OPPO、vivo、三星等品牌可能還要另外到系統的「電池」或「自啟動」選項放行。",
                required = false,
                done = perm.battery,
                button = "允許背景執行",
                action = ::askBattery
            )
        )
    }

    val doneCount = steps.count { it.done }
    val next = steps.firstOrNull { it.required && !it.done }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Text("開始前先完成設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "這幾項都處理好，騎乘記錄才不會中斷。已完成 $doneCount / ${steps.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            steps.forEach { s -> StepCard(s) }

            if (!GoogleApi.keyConfigured) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "這個版本沒有 Google API Key",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "地圖、地點搜尋與道路路線將無法使用。這需要在 GitHub 設定 MAPS_API_KEY 後重新建置，步驟見 README。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                if (next != null) {
                    Button(
                        onClick = next.action,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("下一步：${next.short}", style = MaterialTheme.typography.titleMedium) }
                    TextButton(
                        onClick = onDone,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("先跳過（之後在「記錄」頁還會提醒）") }
                } else {
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(
                            if (doneCount == steps.size) "全部完成，進入 App" else "進入 App",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    if (doneCount < steps.size) {
                        Text(
                            "未完成的是建議項目，之後想設定可隨時回到這一頁。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepCard(s: Step) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (s.done) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (s.done) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = when {
                        s.done -> MaterialTheme.colorScheme.primary
                        s.required -> MaterialTheme.colorScheme.error
                        else -> Color(0xFFF9A825)
                    },
                    modifier = Modifier.size(24.dp)
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            s.done -> "已完成"
                            s.required -> "必要"
                            else -> "建議"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (!s.done && s.required) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                s.desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (!s.done) {
                OutlinedButton(
                    onClick = s.action,
                    modifier = Modifier.padding(top = 10.dp)
                ) { Text(s.button) }
            }
        }
    }
}
