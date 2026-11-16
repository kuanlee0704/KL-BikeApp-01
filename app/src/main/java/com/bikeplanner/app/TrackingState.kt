package com.bikeplanner.app

import kotlinx.coroutines.flow.MutableStateFlow

/** 目前這次騎乘的即時狀態（Service 寫入、UI 讀取）。 */
data class RecState(
    val recording: Boolean = false,
    val paused: Boolean = false,
    val planId: String? = null,
    val startTime: Long = 0L,
    val pausedTotalMs: Long = 0L,
    val pauseStart: Long = 0L,
    val points: List<TrackPoint> = emptyList(),
    val distanceM: Double = 0.0,
    val maxSpeedMps: Float = 0f,
    val currentSpeedMps: Float = 0f
) {
    /** 騎乘時間（不含暫停）。 */
    fun elapsedMs(now: Long): Long {
        if (!recording) return 0L
        val pausedNow = if (paused) now - pauseStart else 0L
        return (now - startTime - pausedTotalMs - pausedNow).coerceAtLeast(0L)
    }
}

object TrackingState {
    val state = MutableStateFlow(RecState())

    /** 每儲存一筆騎乘就 +1，讓 UI 重新載入清單。 */
    val savedCounter = MutableStateFlow(0)
}
