package com.bikeplanner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List as ListIcon
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF2E7D32),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFC8E6C9),
                    secondary = Color(0xFF1E88E5)
                )
            ) {
                App()
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val storage = remember { Storage.get(ctx) }

    val plans = remember { mutableStateListOf<Plan>().apply { addAll(storage.loadPlans()) } }
    var rides by remember { mutableStateOf(storage.loadRides()) }
    val savedCount by TrackingState.savedCounter.collectAsState()
    LaunchedEffect(savedCount) { rides = storage.loadRides() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var ridePlanId by rememberSaveable { mutableStateOf<String?>(null) }

    // 如果 App 重新開啟時正在騎乘，直接切到「記錄」頁
    LaunchedEffect(Unit) {
        if (TrackingState.state.value.recording) tab = 1
    }

    // ---------- 權限：集中在引導頁處理 ----------
    var perm by remember { mutableStateOf(readPermState(ctx)) }
    // 必要項目沒完成就先進引導頁；使用者從系統設定回來時重新讀取狀態
    var showSetup by rememberSaveable { mutableStateOf(!perm.requiredOk) }

    val owner = remember(ctx) { ctx.findActivity() as? LifecycleOwner }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) perm = readPermState(ctx)
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    if (showSetup) {
        SetupScreen(
            perm = perm,
            onRefresh = { perm = readPermState(ctx) },
            onDone = { showSetup = false }
        )
        return
    }

    val labels = listOf("規劃", "記錄", "回顧")
    val icons = listOf(Icons.Default.DateRange, Icons.Default.LocationOn, Icons.AutoMirrored.Filled.ListIcon)

    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icons[i], contentDescription = label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> PlanScreen(
                    plans = plans,
                    hasLoc = perm.anyLocation,
                    onSave = { p ->
                        val i = plans.indexOfFirst { it.id == p.id }
                        if (i >= 0) plans[i] = p else plans.add(p)
                        storage.savePlans(plans.toList())
                    },
                    onDelete = { id ->
                        plans.removeAll { it.id == id }
                        storage.savePlans(plans.toList())
                        if (ridePlanId == id) ridePlanId = null
                    },
                    onRide = { id ->
                        ridePlanId = id
                        tab = 1
                    }
                )

                1 -> RecordScreen(
                    plans = plans,
                    selectedPlanId = ridePlanId,
                    onSelectPlan = { ridePlanId = it },
                    hasLoc = perm.anyLocation,
                    perm = perm,
                    onOpenSetup = {
                        perm = readPermState(ctx)
                        showSetup = true
                    }
                )

                else -> HistoryScreen(
                    rides = rides,
                    plans = plans,
                    onDelete = { id ->
                        storage.deleteRide(id)
                        rides = storage.loadRides()
                    }
                )
            }
        }
    }
}
