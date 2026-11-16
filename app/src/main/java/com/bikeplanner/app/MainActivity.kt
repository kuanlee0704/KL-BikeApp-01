package com.bikeplanner.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid 設定：User-Agent 與快取放在 App 私有目錄（不需儲存空間權限）
        val osm = Configuration.getInstance()
        osm.load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osm.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid").apply { mkdirs() }
        osm.osmdroidBasePath = base
        osm.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }

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

    // 權限
    var hasLoc by remember { mutableStateOf(hasLocationPermission(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasLoc = hasLocationPermission(ctx) }
    val requestPerms = {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(list.toTypedArray())
    }
    LaunchedEffect(Unit) { if (!hasLoc) requestPerms() }

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
                    hasLoc = hasLoc,
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
                    hasLoc = hasLoc,
                    requestPerms = { requestPerms() }
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
