package com.bikeplanner.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(
    rides: List<RideMeta>,
    plans: List<Plan>,
    onDelete: (String) -> Unit
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val sel = rides.find { it.id == selectedId }

    if (sel != null) {
        RideDetail(
            meta = sel,
            plan = plans.find { it.id == sel.planId },
            onBack = { selectedId = null },
            onDelete = {
                onDelete(sel.id)
                selectedId = null
            }
        )
    } else if (rides.isEmpty()) {
        Box(Modifier.fillMaxSize()) {
            Text(
                "還沒有騎乘紀錄。\n到「記錄」頁按開始騎乘，結束後會出現在這裡。",
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBox("${rides.size}", "總次數")
                        StatBox(fmtDistance(rides.sumOf { it.distanceM }), "總距離")
                        StatBox(fmtDuration(rides.sumOf { it.durationMs }), "總時間")
                    }
                }
            }
            items(rides, key = { it.id }) { r ->
                Card(
                    Modifier.fillMaxWidth().clickable { selectedId = r.id },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatBox(fmtDistance(r.distanceM), "距離")
                            StatBox(fmtDuration(r.durationMs), "時間")
                            StatBox(fmtSpeed(r.avgSpeedMps), "均速")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RideDetail(
    meta: RideMeta,
    plan: Plan?,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    val storage = remember { Storage.get(ctx) }
    val cam = rememberBikeCamera()
    var points by remember(meta.id) { mutableStateOf<List<TrackPoint>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(meta.id) {
        points = withContext(Dispatchers.IO) { storage.loadPoints(meta.id) }
    }
    BackHandler(onBack = onBack)

    val geo = remember(points) { points?.map { it.toLatLng() } ?: emptyList() }
    val planPath = remember(plan) { plan?.path() ?: emptyList() }

    LaunchedEffect(loaded, geo) {
        if (loaded && geo.size >= 2) cam.fit(geo)
    }

    val markers = if (geo.size >= 2) {
        listOf(
            MapMarker(geo.first(), "起點", hue = BitmapDescriptorFactory.HUE_GREEN),
            MapMarker(geo.last(), "終點", hue = BitmapDescriptorFactory.HUE_RED)
        )
    } else emptyList()

    Box(Modifier.fillMaxSize()) {
        BikeMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = cam,
            lines = listOf(
                MapLine(planPath, PlanBlue, 10f),
                MapLine(geo, TrackRed, 14f)
            ),
            markers = markers,
            contentPadding = PaddingValues(top = 72.dp, bottom = 230.dp),
            onLoaded = { loaded = true }
        )
        if (points == null) CircularProgressIndicator(Modifier.align(Alignment.Center))

        // 上方：返回 + 標題
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(meta.name, style = MaterialTheme.typography.titleMedium)
                    if (plan != null) {
                        Text(
                            "藍線：規劃路線「" + plan.name.ifBlank { "未命名路線" } + "」　紅線：實際騎乘",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 下方：統計 + 操作
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatBox(fmtDistance(meta.distanceM), "距離")
                    StatBox(fmtDuration(meta.durationMs), "時間")
                    StatBox(fmtSpeed(meta.avgSpeedMps), "平均")
                    StatBox(fmtSpeed(meta.maxSpeedMps.toDouble()), "最高")
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !points.isNullOrEmpty(),
                        onClick = { points?.let { shareGpx(ctx, meta, it) } }
                    ) { Text("匯出 GPX") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !points.isNullOrEmpty(),
                        onClick = { points?.let { openInGoogleMaps(ctx, it.take(1) + it.takeLast(1)) } }
                    ) { Text("Google 地圖") }
                    TextButton(onClick = { confirm = true }) { Text("刪除") }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("刪除紀錄") },
            text = { Text("確定要刪除這筆騎乘紀錄嗎？無法復原。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}
