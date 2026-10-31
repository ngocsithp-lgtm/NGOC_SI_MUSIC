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
        val current = if (currentScale.isFinite()) {
            currentScale.coerceIn(MIN_SCALE, MAX_SCALE)
        } else {
            MIN_SCALE
        }
        if (!gestureScaleFactor.isFinite() || gestureScaleFactor <= 0f) return current

        val scaled = (current * gestureScaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        return if (scaled < FILL_THRESHOLD) MIN_SCALE else scaled
    }

    fun isFillActive(scale: Float): Boolean =
        scale.isFinite() && scale >= FILL_THRESHOLD
}
