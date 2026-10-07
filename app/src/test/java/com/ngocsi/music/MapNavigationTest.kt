package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNavigationTest {
    @Test
    fun instruction_buildsUsefulVietnameseText() {
        val turn = buildMapInstruction("turn", "left", "Nguyễn Trãi")
        assertTrue(turn.startsWith("Rẽ trái"))
        assertTrue(turn.contains("Nguyễn Trãi"))
        assertEquals("Xuất phát theo Lê Lợi", buildMapInstruction("depart", "", "Lê Lợi"))
        assertEquals("Đã đến điểm đích", buildMapInstruction("arrive", "", ""))
    }

    @Test
    fun haversineDistance_isReasonable() {
        val meters = haversineDistanceMeters(10.8231, 106.6297, 10.8241, 106.6297)
        assertTrue(meters in 100.0..120.0)
    }

    @Test
    fun nearestRouteDistance_findsClosestPoint() {
        val route = listOf(
            10.8000 to 106.6000,
            10.8200 to 106.6200,
            10.8400 to 106.6400
        )
        val distance = nearestRouteDistanceMeters(10.8202, 106.6201, route)
        assertTrue(distance < 30.0)
    }

    @Test
    fun nearestRouteDistance_usesSegmentNotOnlyVertices() {
        val route = listOf(
            10.8200 to 106.6200,
            10.8400 to 106.6400
        )
        // Position is close to the middle of the segment while being far from
        // both stored vertices. It should still be treated as on-route.
        val distance = nearestRouteDistanceMeters(10.8300, 106.6302, route)
        assertTrue(distance < 40.0)
    }

    @Test
    fun invalidCoordinateReturnsInfinity() {
        assertEquals(
            Double.POSITIVE_INFINITY,
            haversineDistanceMeters(Double.NaN, 106.0, 10.0, 106.0),
            0.0
        )
    }
}
