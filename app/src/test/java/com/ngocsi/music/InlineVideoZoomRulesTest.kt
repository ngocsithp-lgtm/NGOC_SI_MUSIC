package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineVideoZoomRulesTest {
    @Test
    fun spreadingTwoFingersEnlargesTheVideoTowardTheFillBoundary() {
        assertEquals(1.02f, InlineVideoZoomRules.updateScale(1.0f, 1.02f), 0.001f)
    }

    @Test
    fun pinchingBackDownReturnsToFitMode() {
        assertEquals(1.0f, InlineVideoZoomRules.updateScale(InlineVideoZoomRules.MAX_SCALE, 0.7f), 0.001f)
    }

    @Test
    fun zoomStopsExactlyAtTheLandscapeFillBoundary() {
        assertEquals(
            InlineVideoZoomRules.MAX_SCALE,
            InlineVideoZoomRules.updateScale(1.0f, 1.5f),
            0.001f
        )
        assertEquals(
            InlineVideoZoomRules.MAX_SCALE,
            InlineVideoZoomRules.updateScale(1.03f, 1.5f),
            0.001f
        )
        assertEquals(1.25f, InlineVideoZoomRules.MAX_SCALE, 0.001f)
    }

    @Test
    fun smallGestureUpdatesAccumulateOnlyUntilTheFillBoundary() {
        val firstUpdate = InlineVideoZoomRules.updateScale(1.0f, 1.02f)
        val secondUpdate = InlineVideoZoomRules.updateScale(firstUpdate, 1.02f)
        val thirdUpdate = InlineVideoZoomRules.updateScale(secondUpdate, 1.3f)
        assertEquals(1.02f, firstUpdate, 0.001f)
        assertEquals(1.0404f, secondUpdate, 0.001f)
        assertEquals(1.25f, thirdUpdate, 0.001f)
        assertFalse(InlineVideoZoomRules.isFillActive(firstUpdate))
        assertFalse(InlineVideoZoomRules.isFillActive(secondUpdate))
    }

    @Test
    fun invalidGestureValuesCannotCorruptTheCurrentScale() {
        assertEquals(1.02f, InlineVideoZoomRules.updateScale(1.02f, Float.NaN), 0.001f)
        assertEquals(1.02f, InlineVideoZoomRules.updateScale(1.02f, 0f), 0.001f)
        assertEquals(1.0f, InlineVideoZoomRules.updateScale(Float.NaN, 1.2f), 0.001f)
    }

    @Test
    fun fillStatusUsesTheSameThresholdAsTheGestureRule() {
        assertFalse(InlineVideoZoomRules.isFillActive(1.19f))
        assertTrue(InlineVideoZoomRules.isFillActive(1.20f))
    }

    @Test
    fun secondPointerIsCapturedImmediatelyInLandscape() {
        assertTrue(InlineVideoZoomRules.shouldCapturePinchOnPointerDown(2, true))
        assertTrue(InlineVideoZoomRules.shouldCapturePinchOnPointerDown(3, true))
    }

    @Test
    fun pinchCaptureDoesNotStartForSinglePointerOrPortrait() {
        assertFalse(InlineVideoZoomRules.shouldCapturePinchOnPointerDown(1, true))
        assertFalse(InlineVideoZoomRules.shouldCapturePinchOnPointerDown(2, false))
    }
}
