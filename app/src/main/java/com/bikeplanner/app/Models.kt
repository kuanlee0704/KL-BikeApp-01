package com.bikeplanner.app

/** 一個 GPS 點（規劃用的路徑點只用到 lat/lon）。 */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val time: Long = 0L,
    val alt: Double = 0.0,
    val speed: Float = 0f
)

/** 預先安排的路線。 */
data class Plan(
    val id: String,
    val name: String,
    val plannedAt: Long, // 預計出發時間（epoch ms），0 = 未設定
    val notes: String,
    val waypoints: List<TrackPoint>
) {
    val distanceM: Double get() = pathDistance(waypoints)
}

/** 一次已完成的騎乘（摘要；軌跡點另外存檔）。 */
data class RideMeta(
    val id: String,
    val name: String,
    val startTime: Long,
    val durationMs: Long,
    val distanceM: Double,
    val maxSpeedMps: Float,
    val planId: String?
) {
    val avgSpeedMps: Double
        get() = if (durationMs > 0) distanceM / (durationMs / 1000.0) else 0.0
}
