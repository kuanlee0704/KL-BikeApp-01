package com.bikeplanner.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
fun PlanScreen(
    plans: List<Plan>,
    hasLoc: Boolean,
    onSave: (Plan) -> Unit,
    onDelete: (String) -> Unit,
    onRide: (String) -> Unit
) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Plan?>(null) }
    var deleting by remember { mutableStateOf<Plan?>(null) }

    val e = editing
    if (e != null) {
        PlanEditor(
            initial = e,
            hasLoc = hasLoc,
            onCancel = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            }
        )
    } else {
        val sorted = plans.sortedBy { if (it.plannedAt == 0L) Long.MAX_VALUE else it.plannedAt }
        Box(Modifier.fillMaxSize()) {
            if (sorted.isEmpty()) {
                Text(
                    "還沒有規劃的路線。\n點右下角「新增路線」，在地圖上點選路徑點，並設定出發時間。",
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sorted, key = { it.id }) { p ->
                    PlanCard(
                        p = p,
                        onRide = { onRide(p.id) },
                        onMaps = { openInGoogleMaps(ctx, p.waypoints) },
                        onEdit = { editing = p },
                        onDelete = { deleting = p }
                    )
                }
            }
            ExtendedFloatingActionButton(
                onClick = { editing = Plan(UUID.randomUUID().toString(), "", 0L, "", emptyList()) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新增路線") }
            )
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除路線") },
            text = { Text("確定要刪除「${p.name.ifBlank { "未命名路線" }}」嗎？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(p.id)
                    deleting = null
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun PlanCard(
    p: Plan,
    onRide: () -> Unit,
    onMaps: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(p.name.ifBlank { "未命名路線" }, style = MaterialTheme.typography.titleMedium)
            Text(
                if (p.plannedAt == 0L) "未設定出發時間" else "出發：" + fmtDateTime(p.plannedAt),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "${p.waypoints.size} 個路徑點 · 約 ${fmtDistance(p.distanceM)}（點對點直線估算）",
                style = MaterialTheme.typography.bodySmall
            )
            if (p.notes.isNotBlank()) {
                Text(p.notes, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRide, enabled = p.waypoints.size >= 2) { Text("開始騎乘") }
                OutlinedButton(onClick = onMaps, enabled = p.waypoints.isNotEmpty()) { Text("Google 地圖") }
            }
            Row {
                TextButton(onClick = onEdit) { Text("編輯") }
                TextButton(onClick = onDelete) { Text("刪除") }
            }
        }
    }
}

@Composable
private fun PlanEditor(
    initial: Plan,
    hasLoc: Boolean,
    onCancel: () -> Unit,
    onSave: (Plan) -> Unit
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial.name) }
    var notes by remember { mutableStateOf(initial.notes) }
    var plannedAt by remember { mutableLongStateOf(initial.plannedAt) }
    val pts = remember { mutableStateListOf<TrackPoint>().apply { addAll(initial.waypoints) } }

    BackHandler { onCancel() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("路線名稱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDateTime(ctx, plannedAt) { plannedAt = it } }) {
                    Text(if (plannedAt == 0L) "設定出發時間" else fmtDateTime(plannedAt))
                }
                if (plannedAt != 0L) {
                    TextButton(onClick = { plannedAt = 0L }) { Text("清除時間") }
                }
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("備註（同行者、補給點…）") },
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "點地圖加入路徑點：共 ${pts.size} 點，約 ${fmtDistance(pathDistance(pts))}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        OsmMap(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            lines = listOf(MapLine(pts.map { it.toGeo() }, COLOR_PLAN, 10f)),
            markers = pts.mapIndexed { i, p -> MapMarker(p.toGeo(), "第 ${i + 1} 點") },
            onTap = { g -> pts.add(TrackPoint(g.latitude, g.longitude)) },
            showMyLocation = hasLoc,
            centerOnFirstFix = initial.waypoints.size < 2,
            fitPoints = initial.waypoints.map { it.toGeo() },
            fitKey = initial.id
        )

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { if (pts.isNotEmpty()) pts.removeAt(pts.lastIndex) }) { Text("復原") }
            OutlinedButton(onClick = { pts.clear() }) { Text("清空") }
            Box(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("取消") }
            Button(
                enabled = pts.size >= 2,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { "未命名路線" },
                            plannedAt = plannedAt,
                            notes = notes.trim(),
                            waypoints = pts.toList()
                        )
                    )
                }
            ) { Text("儲存") }
        }
    }
}
