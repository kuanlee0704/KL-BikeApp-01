package com.bikeplanner.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.maps.android.compose.CameraMoveStartedReason
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun RecordScreen(
    plans: List<Plan>,
    selectedPlanId: String?,
    onSelectPlan: (String?) -> Unit,
    hasLoc: Boolean,
    perm: PermState,
    onOpenSetup: () -> Unit
) {
    val ctx = LocalContext.current
    val rec by TrackingState.state.collectAsState()
    val cam = rememberBikeCamera()
    var follow by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    val activePlanId = if (rec.recording) rec.planId else selectedPlanId
    val plan = plans.find { it.id == activePlanId }
    val planPath = remember(plan) { plan?.path() ?: emptyList() }
    val track = rec.points.map { it.toLatLng() }

    // 未開始：縮放到所選路線，或移到目前位置
    LaunchedEffect(loaded, activePlanId, rec.recording) {
        if (!loaded || rec.recording) return@LaunchedEffect
        if (planPath.size >= 2) {
            cam.fit(planPath)
        } else if (hasLoc) {
            lastKnownLatLng(ctx)?.let { cam.animate(CameraUpdateFactory.newLatLngZoom(it, 15f)) }
        }
    }

    // 記錄中：地圖跟隨最新位置
    LaunchedEffect(rec.points.size) {
        if (rec.recording && follow) {
            rec.points.lastOrNull()?.let {
                cam.animate(
                    CameraUpdateFactory.newLatLngZoom(it.toLatLng(), max(cam.position.zoom, 16f))
                )
            }
        }
    }

    // 使用者自己拖曳地圖 → 解除跟隨
    LaunchedEffect(cam.isMoving) {
        if (cam.isMoving && cam.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE) {
            follow = false
        }
    }

    val markers = buildList {
        val wp = plan?.waypoints.orEmpty()
        if (wp.size >= 2) {
            add(MapMarker(wp.first().toLatLng(), "起點", hue = BitmapDescriptorFactory.HUE_GREEN))
            add(MapMarker(wp.last().toLatLng(), "終點", hue = BitmapDescriptorFactory.HUE_RED))
        }
    }

    Box(Modifier.fillMaxSize()) {
        BikeMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = cam,
            lines = listOf(
                MapLine(planPath, PlanBlue, 12f),
                MapLine(track, TrackRed, 14f)
            ),
            markers = markers,
            hasLoc = hasLoc,
            contentPadding = PaddingValues(top = if (rec.recording) 120.dp else 8.dp, bottom = 190.dp),
            onLoaded = { loaded = true }
        )

        // ---------- 上方：即時數據 ----------
        if (rec.recording) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(Modifier.padding(vertical = 14.dp, horizontal = 8.dp)) { LiveStats(rec) }
            }
        } else if (!GoogleApi.keyConfigured) {
            KeyMissingBanner(Modifier.align(Alignment.TopCenter).padding(12.dp))
        }

        // ---------- 下方：控制 ----------
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp)) {
                if (!rec.recording) {
                    Box {
                        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
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
                    if (plan != null) {
                        Text(
                            "${fmtDistance(plan.distanceM)}" +
                                if (plan.routeDurationS > 0) " · 預估 ${fmtMinutes(plan.routeDurationS)}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (!perm.requiredOk) {
                        Text(
                            "還沒完成定位設定，按「開始騎乘」會帶你完成。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.weight(1f).height(52.dp),
                            onClick = {
                                // 即時重新檢查（使用者可能剛從下拉選單開了 GPS）
                                if (readPermState(ctx).requiredOk) {
                                    follow = true
                                    TrackingService.start(ctx, selectedPlanId)
                                } else {
                                    onOpenSetup()
                                }
                            }
                        ) { Text("開始騎乘", style = MaterialTheme.typography.titleMedium) }
                        if (plan != null) {
                            OutlinedButton(
                                modifier = Modifier.height(52.dp),
                                onClick = { openInGoogleMaps(ctx, plan.waypoints) }
                            ) { Text("Google 導航") }
                        }
                    }
                    TextButton(
                        onClick = onOpenSetup,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            if (perm.requiredOk && perm.notif && perm.battery) "權限與設定" else "權限與設定（有未完成項目）",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f).height(52.dp),
                            onClick = {
                                TrackingService.send(
                                    ctx,
                                    if (rec.paused) TrackingService.ACTION_RESUME else TrackingService.ACTION_PAUSE
                                )
                            }
                        ) { Text(if (rec.paused) "繼續" else "暫停") }
                        Button(
                            modifier = Modifier.weight(1f).height(52.dp),
                            onClick = { TrackingService.send(ctx, TrackingService.ACTION_STOP) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("結束並儲存") }
                    }
                    if (!follow) {
                        TextButton(
                            onClick = { follow = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text("回到目前位置") }
                    }
                }
            }
        }
    }
}

/** 獨立成一個 Composable，每秒更新計時而不會重畫整張地圖。 */
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

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            fmtDuration(elapsed) + if (rec.paused) "  已暫停" else "",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBox(fmtDistance(rec.distanceM), "距離")
            StatBox(fmtSpeed(rec.currentSpeedMps.toDouble()), "目前速度")
            StatBox(fmtSpeed(avg), "平均速度")
        }
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
