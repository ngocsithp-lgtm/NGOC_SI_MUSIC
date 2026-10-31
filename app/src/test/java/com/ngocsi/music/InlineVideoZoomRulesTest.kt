package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineVideoZoomRulesTest {
    @Test
    fun spreadingTwoFingersEnlargesTheVideo() {
        assertEquals(1.2f, InlineVideoZoomRules.updateScale(1.0f, 1.2f), 0.001f)
    }

    @Test
    fun pinchingBackDownReturnsToFitMode() {
        assertEquals(1.0f, InlineVideoZoomRules.updateScale(1.2f, 0.7f), 0.001f)
    }

    @Test
    fun zoomIsBoundedToAvoidAnUnusableOversizedView() {
        assertEquals(1.6f, InlineVideoZoomRules.updateScale(1.5f, 1.5f), 0.001f)
    }

    @Test
    fun tinyGesturesDoNotCauseUnwantedZoom() {
        assertEquals(1.0f, InlineVideoZoomRules.updateScale(1.0f, 1.02f), 0.001f)
    }

    @Test
    fun invalidGestureValuesCannotCorruptTheCurrentScale() {
        assertEquals(1.2f, InlineVideoZoomRules.updateScale(1.2f, Float.NaN), 0.001f)
        assertEquals(1.2f, InlineVideoZoomRules.updateScale(1.2f, 0f), 0.001f)
        assertEquals(1.0f, InlineVideoZoomRules.updateScale(Float.NaN, 1.2f), 0.001f)
    }

    @Test
    fun fillStatusUsesTheSameThresholdAsTheGestureRule() {
        assertFalse(InlineVideoZoomRules.isFillActive(1.03f))
        assertTrue(InlineVideoZoomRules.isFillActive(1.04f))
    }
}
