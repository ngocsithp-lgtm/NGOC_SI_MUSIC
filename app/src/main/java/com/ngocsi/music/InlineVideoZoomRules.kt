package com.ngocsi.music

/**
 * Bounds the inline YouTube pinch-to-fill zoom so the video can be enlarged
 * without changing its aspect ratio or allowing an unbounded view transform.
 */
internal object InlineVideoZoomRules {
    const val MIN_SCALE = 1.0f
    const val MAX_SCALE = 1.6f
    const val FILL_THRESHOLD = 1.04f

    fun updateScale(currentScale: Float, gestureScaleFactor: Float): Float {
        if (!currentScale.isFinite()) return MIN_SCALE
        val current = currentScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (!gestureScaleFactor.isFinite() || gestureScaleFactor <= 0f) return current

        // ScaleGestureDetector reports incremental factors, often only 1–2% per event.
        // Preserve each valid increment so a slow pinch can accumulate smoothly.
        return (current * gestureScaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun isFillActive(scale: Float): Boolean =
        scale.isFinite() && scale >= FILL_THRESHOLD

    /**
     * Take ownership as soon as a second pointer lands in landscape. Do not wait
     * for span movement, because the embedded player may already be tracking a
     * one-finger long-press gesture that can temporarily activate 2x playback.
     */
    fun shouldCapturePinchOnPointerDown(pointerCount: Int, isLandscape: Boolean): Boolean =
        isLandscape && pointerCount >= 2
}
