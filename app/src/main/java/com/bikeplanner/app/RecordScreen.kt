package com.bikeplanner.app

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun RecordScreen(
    plans: List<Plan>,
    selectedPlanId: String?,
    onSelectPlan: (String?) -> Unit,
    hasLoc: Boolean,
    requestPerms: () -> Unit
) {
    val ctx = LocalContext.current
    val rec by TrackingState.state.collectAsState()
    var follow by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }

    val activePlanId = if (rec.recording) rec.planId else selectedPlanId
    val plan = plans.find { it.id == activePlanId }
    val planGeo = plan?.waypoints?.map { it.toGeo() } ?: emptyList()
    val trackGeo = rec.points.map { it.toGeo() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            OsmMap(
                modifier = Modifier.fillMaxSize(),
                lines = listOf(
                    MapLine(planGeo, COLOR_PLAN, 9f),
                    MapLine(trackGeo, COLOR_TRACK, 12f)
                ),
                markers = planGeo.mapIndexed { i, g -> MapMarker(g, "第 ${i + 1} 點") },
                showMyLocation = hasLoc,
                centerOnFirstFix = planGeo.size < 2,
                followPoint = if (rec.recording && follow) trackGeo.lastOrNull() else null,
                fitPoints = if (!rec.recording) planGeo else emptyList(),
                fitKey = activePlanId
            )
            FilledTonalButton(
                onClick = { follow = !follow },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) { Text(if (follow) "跟隨中" else "已解除跟隨") }
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                if (!rec.recording) {
                    Box {
                        OutlinedButton(onClick = { menu = true }) {
                            Text("路線：" + (plan?.name?.ifBlank { "未命名路線" } ?: "自由騎乘"))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("自由騎乘（不跟隨路線）") },
                                onClick = {
                                    onSelectPlan(null)
                                    menu = false
                                }
                            )
                            plans.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name.ifBlank { "未命名路線" }) },
                                    onClick = {
                                        onSelectPlan(p.id)
                                        menu = false
                                    }
                                )
                            }
                        }
                    }
                    if (!hasLoc) {
                        Text(
                            "尚未授權定位權限，按「開始騎乘」會跳出授權視窗。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            when {
                                !hasLoc -> requestPerms()
                                !isGpsEnabled(ctx) -> {
                                    Toast.makeText(ctx, "請先開啟手機的定位（GPS）", Toast.LENGTH_LONG).show()
                                    ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                }
                                else -> TrackingService.start(ctx, selectedPlanId)
                            }
                        }) { Text("開始騎乘") }
                        if (plan != null) {
                            OutlinedButton(onClick = { openInGoogleMaps(ctx, plan.waypoints) }) {
                                Text("Google 地圖導航")
                            }
                        }
                    }
                } else {
                    LiveStats(rec)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            TrackingService.send(
                                ctx,
                                if (rec.paused) TrackingService.ACTION_RESUME else TrackingService.ACTION_PAUSE
                            )
                        }) { Text(if (rec.paused) "繼續" else "暫停") }
                        Button(
                            onClick = { TrackingService.send(ctx, TrackingService.ACTION_STOP) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("結束並儲存") }
                    }
                }
            }
        }
    }
}

/** 獨立成一個 Composable，每秒更新計時而不會重畫整個地圖畫面。 */
@Composable
private fun LiveStats(rec: RecState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.recording, rec.paused) {
        now = System.currentTimeMillis()
        while (rec.recording && !rec.paused) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = rec.elapsedMs(now)
    val avg = if (elapsed > 0) rec.distanceM / (elapsed / 1000.0) else 0.0

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        StatBox(fmtDistance(rec.distanceM), "距離")
        StatBox(fmtDuration(elapsed) + if (rec.paused) "（暫停）" else "", "時間")
        StatBox(fmtSpeed(rec.currentSpeedMps.toDouble()), "目前速度")
        StatBox(fmtSpeed(avg), "平均速度")
    }
}

@Composable
fun StatBox(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
