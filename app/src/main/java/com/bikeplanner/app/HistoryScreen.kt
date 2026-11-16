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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    } else {
        Column(Modifier.fillMaxSize()) {
            if (rides.isEmpty()) {
                Box(Modifier.fillMaxSize()) {
                    Text(
                        "還沒有騎乘紀錄。\n到「記錄」頁按開始騎乘，結束後會出現在這裡。",
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val total = rides.sumOf { it.distanceM }
                Card(Modifier.fillMaxWidth().padding(12.dp, 12.dp, 12.dp, 0.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBox("${rides.size}", "總次數")
                        StatBox(fmtDistance(total), "總距離")
                        StatBox(fmtDuration(rides.sumOf { it.durationMs }), "總時間")
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rides, key = { it.id }) { r ->
                        Card(Modifier.fillMaxWidth().clickable { selectedId = r.id }) {
                            Column(Modifier.padding(14.dp)) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${fmtDistance(r.distanceM)} · ${fmtDuration(r.durationMs)} · 均速 ${fmtSpeed(r.avgSpeedMps)}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
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
    var points by remember(meta.id) { mutableStateOf<List<TrackPoint>?>(null) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(meta.id) {
        points = withContext(Dispatchers.IO) { storage.loadPoints(meta.id) }
    }
    BackHandler(onBack = onBack)

    val geo = remember(points) { points?.map { it.toGeo() } ?: emptyList() }
    val planGeo = plan?.waypoints?.map { it.toGeo() } ?: emptyList()
    val markers = if (geo.size >= 2) {
        listOf(MapMarker(geo.first(), "起點"), MapMarker(geo.last(), "終點"))
    } else emptyList()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column {
                Text(meta.name, style = MaterialTheme.typography.titleMedium)
                if (plan != null) {
                    Text(
                        "對照路線：" + plan.name.ifBlank { "未命名路線" } + "（藍線）",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            OsmMap(
                modifier = Modifier.fillMaxSize(),
                lines = listOf(
                    MapLine(planGeo, COLOR_PLAN, 8f),
                    MapLine(geo, COLOR_TRACK, 12f)
                ),
                markers = markers,
                fitPoints = geo,
                fitKey = meta.id + ":" + geo.size
            )
            if (points == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatBox(fmtDistance(meta.distanceM), "距離")
            StatBox(fmtDuration(meta.durationMs), "時間")
            StatBox(fmtSpeed(meta.avgSpeedMps), "平均速度")
            StatBox(fmtSpeed(meta.maxSpeedMps.toDouble()), "最高速度")
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = !points.isNullOrEmpty(),
                onClick = { points?.let { shareGpx(ctx, meta, it) } }
            ) { Text("匯出 GPX") }
            OutlinedButton(
                enabled = !points.isNullOrEmpty(),
                onClick = { points?.let { openInGoogleMaps(ctx, it.take(1) + it.takeLast(1)) } }
            ) { Text("Google 地圖") }
            TextButton(onClick = { confirm = true }) { Text("刪除") }
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
