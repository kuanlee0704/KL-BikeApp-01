package com.bikeplanner.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

const val COLOR_PLAN = 0xFF1E88E5.toInt() // 藍：預先規劃路線
const val COLOR_TRACK = 0xFFE53935.toInt() // 紅：實際騎乘軌跡

data class MapLine(val points: List<GeoPoint>, val color: Int, val width: Float = 10f)
data class MapMarker(val point: GeoPoint, val title: String)

fun TrackPoint.toGeo(): GeoPoint = GeoPoint(lat, lon)

private val DEFAULT_CENTER = GeoPoint(25.0330, 121.5654) // 台北

/**
 * OpenStreetMap 地圖（osmdroid），不需要 API Key。
 *  - lines / markers：每次重組時重畫
 *  - onTap：點擊地圖回呼（規劃路線時用來加點）
 *  - showMyLocation：顯示目前位置
 *  - followPoint：非 null 時地圖跟隨到該點
 *  - fitPoints + fitKey：fitKey 改變時，縮放到涵蓋所有 fitPoints
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    onTap: ((GeoPoint) -> Unit)? = null,
    showMyLocation: Boolean = false,
    centerOnFirstFix: Boolean = false,
    followPoint: GeoPoint? = null,
    fitPoints: List<GeoPoint> = emptyList(),
    fitKey: Any? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
            controller.setCenter(DEFAULT_CENTER)
        }
    }

    // 跟著 Activity 生命週期暫停 / 恢復
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 點擊地圖
    val tapState = rememberUpdatedState(onTap)
    DisposableEffect(mapView) {
        val overlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                val cb = tapState.value ?: return false
                cb(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        mapView.overlays.add(0, overlay)
        onDispose { mapView.overlays.remove(overlay) }
    }

    // 目前位置
    val myLoc = remember(mapView) { MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) }
    DisposableEffect(mapView, showMyLocation) {
        if (showMyLocation) {
            myLoc.enableMyLocation()
            mapView.overlays.add(myLoc)
            if (centerOnFirstFix) {
                myLoc.runOnFirstFix {
                    val loc = myLoc.myLocation
                    if (loc != null) {
                        mapView.post {
                            mapView.controller.setZoom(16.0)
                            mapView.controller.animateTo(loc)
                        }
                    }
                }
            }
        }
        onDispose {
            myLoc.disableMyLocation()
            mapView.overlays.remove(myLoc)
        }
    }

    val followedOnce = remember { booleanArrayOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { map ->
            // 重畫線與標記
            map.overlays.removeAll { it is Polyline || it is Marker }
            for (l in lines) {
                if (l.points.size >= 2) {
                    val pl = Polyline(map)
                    pl.setPoints(l.points)
                    pl.outlinePaint.color = l.color
                    pl.outlinePaint.strokeWidth = l.width
                    map.overlays.add(pl)
                }
            }
            for (m in markers) {
                val mk = Marker(map)
                mk.position = m.point
                mk.title = m.title
                mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                mk.setOnMarkerClickListener { marker, _ ->
                    if (marker.isInfoWindowShown) marker.closeInfoWindow() else marker.showInfoWindow()
                    true
                }
                map.overlays.add(mk)
            }
            // 讓「目前位置」圖示永遠在最上層
            if (showMyLocation && map.overlays.contains(myLoc)) {
                map.overlays.remove(myLoc)
                map.overlays.add(myLoc)
            }

            // 縮放到涵蓋指定路線（fitKey 改變時才做一次）
            if (fitPoints.size >= 2 && map.tag != fitKey) {
                map.tag = fitKey
                val doFit = {
                    val bb = BoundingBox.fromGeoPoints(fitPoints)
                    if (bb.latitudeSpan > 1e-6 || bb.longitudeSpan > 1e-6) {
                        map.zoomToBoundingBox(bb, false, 100)
                    } else {
                        map.controller.setCenter(fitPoints.first())
                    }
                }
                if (map.width > 0 && map.height > 0) {
                    doFit()
                } else {
                    map.addOnFirstLayoutListener { _, _, _, _, _ -> doFit() }
                }
            }

            // 跟隨目前位置
            if (followPoint != null) {
                if (!followedOnce[0]) {
                    map.controller.setZoom(17.0)
                    followedOnce[0] = true
                }
                map.controller.animateTo(followPoint)
            }

            map.invalidate()
        }
    )
}
