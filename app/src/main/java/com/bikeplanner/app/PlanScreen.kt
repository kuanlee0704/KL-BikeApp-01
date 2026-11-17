package com.bikeplanner.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val MAX_POINTS = 27 // Routes API：起點 + 25 個途經點 + 終點

// =====================================================================
// 路線清單
// =====================================================================

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
                    "還沒有規劃的路線。\n\n點右下角「新增路線」，搜尋地點或直接點地圖加入起點、途經點與終點，App 會沿道路算出實際距離。",
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(p.name.ifBlank { "未命名路線" }, style = MaterialTheme.typography.titleLarge)
            Text(
                if (p.plannedAt == 0L) "未設定出發時間" else "出發　" + fmtDateTime(p.plannedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBox(fmtDistance(p.distanceM), if (p.hasRoute) "道路距離" else "直線距離")
                StatBox(if (p.routeDurationS > 0) fmtMinutes(p.routeDurationS) else "—", "預估時間")
                StatBox("${p.waypoints.size}", "路徑點")
            }
            if (p.hasRoute && modeLabel(p.routeMode).isNotEmpty()) {
                Text(
                    modeLabel(p.routeMode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (p.notes.isNotBlank()) {
                Text(
                    p.notes,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRide,
                    enabled = p.waypoints.size >= 2,
                    modifier = Modifier.weight(1f)
                ) { Text("開始騎乘") }
                OutlinedButton(
                    onClick = onMaps,
                    enabled = p.waypoints.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Google 導航") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("編輯") }
                TextButton(onClick = onDelete) { Text("刪除") }
            }
        }
    }
}

// =====================================================================
// 路線編輯器：全螢幕 Google 地圖 + 搜尋列 + 底部資訊卡
// =====================================================================

@Composable
private fun PlanEditor(
    initial: Plan,
    hasLoc: Boolean,
    onCancel: () -> Unit,
    onSave: (Plan) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val cam = rememberBikeCamera()

    var name by remember { mutableStateOf(initial.name) }
    var notes by remember { mutableStateOf(initial.notes) }
    var plannedAt by remember { mutableLongStateOf(initial.plannedAt) }
    val pts = remember { mutableStateListOf<TrackPoint>().apply { addAll(initial.waypoints) } }

    var route by remember {
        mutableStateOf(
            if (initial.hasRoute) {
                GoogleApi.RouteResult(initial.routeEncoded, initial.routeDistanceM, initial.routeDurationS, initial.routeMode)
            } else null
        )
    }
    var routedFor by remember { mutableStateOf(initial.waypoints) }
    var routing by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GoogleApi.PlaceHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchMsg by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<GoogleApi.PlaceHit?>(null) }

    var showSettings by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    BackHandler { onCancel() }

    // 路徑點改變 → 0.6 秒後重新請 Google 規劃道路路線
    val snapshot = pts.toList()
    LaunchedEffect(snapshot) {
        if (snapshot.size < 2) {
            route = null
            routeError = null
            routedFor = snapshot
            return@LaunchedEffect
        }
        if (route != null && snapshot == routedFor) return@LaunchedEffect
        if (!GoogleApi.keyConfigured) return@LaunchedEffect
        delay(600)
        routing = true
        routeError = null
        try {
            GoogleApi.computeRoute(ctx, snapshot)
                .onSuccess {
                    route = it
                    routedFor = snapshot
                }
                .onFailure {
                    route = null
                    routeError = it.message ?: "路線規劃失敗"
                }
        } finally {
            routing = false
        }
    }

    // 地圖載入後：編輯既有路線就縮放到整條路線，新路線則移到目前位置
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        if (initial.waypoints.size >= 2) {
            cam.fit(initial.path())
        } else if (hasLoc) {
            lastKnownLatLng(ctx)?.let {
                cam.animate(CameraUpdateFactory.newLatLngZoom(it, 15f))
            }
        }
    }

    fun addPoint(p: TrackPoint) {
        if (pts.size >= MAX_POINTS) {
            Toast.makeText(ctx, "最多 $MAX_POINTS 個路徑點", Toast.LENGTH_SHORT).show()
        } else {
            pts.add(p)
        }
    }

    fun doSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        focus.clearFocus()
        scope.launch {
            searching = true
            searchMsg = null
            GoogleApi.searchPlaces(ctx, q, cam.position.target)
                .onSuccess {
                    results = it
                    if (it.isEmpty()) searchMsg = "找不到相符的地點"
                }
                .onFailure {
                    results = emptyList()
                    searchMsg = "搜尋失敗：" + (it.message ?: "未知錯誤")
                }
            searching = false
        }
    }

    fun choose(h: GoogleApi.PlaceHit) {
        preview = h
        results = emptyList()
        searchMsg = null
        scope.launch { cam.animate(CameraUpdateFactory.newLatLngZoom(LatLng(h.lat, h.lon), 16f)) }
    }

    val routePath = remember(route?.encoded) { route?.let { decodePolyline(it.encoded) } ?: emptyList() }
    val lines = buildList {
        if (routePath.isNotEmpty()) {
            add(MapLine(routePath, PlanBlue, 14f))
        } else if (pts.size >= 2) {
            add(MapLine(pts.map { it.toLatLng() }, PlanBlue.copy(alpha = 0.45f), 8f))
        }
    }
    val markers = buildList {
        pts.forEachIndexed { i, p ->
            val (title, hue) = when {
                i == 0 -> "起點" to BitmapDescriptorFactory.HUE_GREEN
                i == pts.lastIndex -> "終點" to BitmapDescriptorFactory.HUE_RED
                else -> "途經點 $i" to BitmapDescriptorFactory.HUE_AZURE
            }
            add(MapMarker(p.toLatLng(), if (p.label.isNotBlank()) "$title · ${p.label}" else title, hue = hue))
        }
        preview?.let {
            add(MapMarker(LatLng(it.lat, it.lon), it.name, it.address, BitmapDescriptorFactory.HUE_VIOLET))
        }
    }

    Box(Modifier.fillMaxSize()) {
        BikeMap(
            modifier = Modifier.fillMaxSize(),
            cameraState = cam,
            lines = lines,
            markers = markers,
            hasLoc = hasLoc,
            contentPadding = PaddingValues(top = 72.dp, bottom = 190.dp),
            onTap = { addPoint(TrackPoint(it.latitude, it.longitude)) },
            onLoaded = { loaded = true }
        )

        // ---------- 上方：搜尋列與搜尋結果 ----------
        Column(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜尋地點、地址或店名") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSearch() })
                    )
                    if (searching) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 14.dp).size(22.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { doSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "搜尋")
                        }
                    }
                }
            }
            if (!GoogleApi.keyConfigured) {
                KeyMissingBanner(Modifier.padding(top = 8.dp))
            }
            searchMsg?.let {
                Surface(
                    Modifier.padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 4.dp
                ) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            if (results.isNotEmpty()) {
                Surface(
                    Modifier.padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 6.dp
                ) {
                    Column {
                        results.forEach { h ->
                            Column(
                                Modifier.fillMaxWidth().clickable { choose(h) }.padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(h.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    h.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------- 下方：資訊卡 ----------
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(16.dp)) {
                val pv = preview
                if (pv != null) {
                    Text(pv.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        pv.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                addPoint(TrackPoint(pv.lat, pv.lon, label = pv.name))
                                preview = null
                                query = ""
                            }
                        ) { Text("加入為路徑點") }
                        OutlinedButton(onClick = { preview = null }) { Text("取消") }
                    }
                }

                // 摘要
                val r = route
                val summary = when {
                    routing -> "正在計算道路路線…"
                    r != null && routedFor == snapshot -> buildString {
                        append("${pts.size} 個點 · 道路距離 ${fmtDistance(r.distanceM)}")
                        if (r.durationS > 0) append(" · 約 ${fmtMinutes(r.durationS)}")
                    }
                    pts.size < 2 -> "搜尋或點地圖加入起點、途經點與終點（至少 2 點）"
                    else -> "${pts.size} 個點 · 直線約 ${fmtDistance(pathDistance(pts))}（尚未取得道路路線）"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (routing) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    }
                    Text(summary, style = MaterialTheme.typography.titleSmall)
                }
                if (r != null && routedFor == snapshot && modeLabel(r.mode).isNotEmpty()) {
                    Text(
                        modeLabel(r.mode) + if (r.mode == "walk") "（此地區無腳踏車路線資料，僅供參考）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                routeError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { if (pts.isNotEmpty()) pts.removeAt(pts.lastIndex) }) { Text("復原") }
                    TextButton(onClick = { pts.clear() }) { Text("清空") }
                    OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.weight(1f)) {
                        Text("名稱 / 時間")
                    }
                    Button(
                        enabled = pts.size >= 2 && !routing,
                        onClick = {
                            val ready = route.takeIf { routedFor == pts.toList() }
                            onSave(
                                initial.copy(
                                    name = name.trim().ifBlank { "路線 " + fmtDateTime(System.currentTimeMillis()) },
                                    plannedAt = plannedAt,
                                    notes = notes.trim(),
                                    waypoints = pts.toList(),
                                    routeEncoded = ready?.encoded ?: "",
                                    routeDistanceM = ready?.distanceM ?: 0.0,
                                    routeDurationS = ready?.durationS ?: 0L,
                                    routeMode = ready?.mode ?: ""
                                )
                            )
                        }
                    ) { Text("儲存") }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("路線資訊") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            TextButton(onClick = { plannedAt = 0L }) { Text("清除") }
                        }
                    }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("備註（同行者、補給點…）") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成") } }
        )
    }
}
