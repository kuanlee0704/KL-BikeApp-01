package com.bikeplanner.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

val PlanBlue = Color(0xFF1E88E5) // 預先規劃的路線
val TrackRed = Color(0xFFE53935) // 實際騎乘軌跡

private val TAIPEI = LatLng(25.0330, 121.5654)

data class MapLine(val points: List<LatLng>, val color: Color, val width: Float = 10f)

data class MapMarker(
    val point: LatLng,
    val title: String,
    val snippet: String? = null,
    val hue: Float = BitmapDescriptorFactory.HUE_AZURE
)

@Composable
fun rememberBikeCamera(): CameraPositionState =
    rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(TAIPEI, 13f) }

/** 縮放到涵蓋所有點（必須在地圖載入完成後呼叫）。 */
suspend fun CameraPositionState.fit(points: List<LatLng>, paddingPx: Int = 140) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        animate(CameraUpdateFactory.newLatLngZoom(points[0], 16f))
        return
    }
    val b = LatLngBounds.builder()
    points.forEach { b.include(it) }
    try {
        animate(CameraUpdateFactory.newLatLngBounds(b.build(), paddingPx))
    } catch (e: IllegalStateException) {
        // 地圖尚未完成配置，忽略
    }
}

/** Google 地圖（Maps Compose）。 */
@Composable
fun BikeMap(
    modifier: Modifier = Modifier,
    cameraState: CameraPositionState,
    lines: List<MapLine> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    hasLoc: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(),
    onTap: ((LatLng) -> Unit)? = null,
    onLoaded: () -> Unit = {}
) {
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraState,
        contentPadding = contentPadding,
        properties = MapProperties(isMyLocationEnabled = hasLoc),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = hasLoc,
            mapToolbarEnabled = false
        ),
        onMapClick = { onTap?.invoke(it) },
        onMapLoaded = onLoaded
    ) {
        lines.forEach { l ->
            if (l.points.size >= 2) {
                Polyline(points = l.points, color = l.color, width = l.width)
            }
        }
        markers.forEachIndexed { i, m ->
            key(i, m.point.latitude, m.point.longitude) {
                Marker(
                    state = rememberMarkerState(position = m.point),
                    title = m.title,
                    snippet = m.snippet,
                    icon = BitmapDescriptorFactory.defaultMarker(m.hue)
                )
            }
        }
    }
}

/** 沒設定 API Key 時顯示的提示。 */
@Composable
fun KeyMissingBanner(modifier: Modifier = Modifier) {
    if (GoogleApi.keyConfigured) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            "尚未設定 Google API Key：地圖、地點搜尋與道路路線都無法使用。請照 README「設定 Google API Key」操作後重新建置。",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}
