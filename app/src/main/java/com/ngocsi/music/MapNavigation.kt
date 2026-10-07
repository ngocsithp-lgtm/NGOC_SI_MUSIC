package com.ngocsi.music

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class MapNavigationStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverLat: Double,
    val maneuverLon: Double
)

data class MapRouteSummary(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<MapNavigationStep>
)

fun buildMapInstruction(type: String, modifier: String, streetName: String): String {
    val road = streetName.trim().ifBlank { "đường phía trước" }
    val direction = when (modifier.lowercase()) {
        "left" -> "rẽ trái"
        "right" -> "rẽ phải"
        "slight left" -> "chếch trái"
        "slight right" -> "chếch phải"
        "sharp left" -> "rẽ gấp trái"
        "sharp right" -> "rẽ gấp phải"
        "uturn" -> "quay đầu"
        "straight" -> "đi thẳng"
        else -> ""
    }

    val instruction = when (type.lowercase()) {
        "depart" -> "Xuất phát theo $road"
        "arrive" -> "Đã đến điểm đích"
        "roundabout", "rotary" -> {
            if (direction.isBlank()) "Đi vào vòng xoay theo $road" else "Vào vòng xoay, $direction theo $road"
        }
        "fork" -> if (direction.isBlank()) "Đi theo nhánh phù hợp trên $road" else "Tại nhánh đường, $direction theo $road"
        "merge" -> if (direction.isBlank()) "Nhập vào $road" else "Nhập đường và $direction theo $road"
        "on ramp", "on_ramp" -> "Nhập vào $road"
        "off ramp", "off_ramp" -> "Ra khỏi đường vào $road"
        "end of road", "end_of_road" -> if (direction.isBlank()) "Cuối đường, tiếp tục theo $road" else "Cuối đường, $direction theo $road"
        "new name" -> if (direction.isBlank()) "Tiếp tục theo $road" else "$direction theo $road"
        "continue", "use lane", "use_lane" -> if (direction.isBlank()) "Tiếp tục theo $road" else "$direction theo $road"
        "notification" -> if (direction.isBlank()) "Tiếp tục theo $road" else "$direction theo $road"
        "turn" -> if (direction.isBlank()) "Rẽ vào $road" else "$direction vào $road"
        else -> if (direction.isBlank()) "Tiếp tục theo $road" else "$direction theo $road"
    }
    return instruction.replaceFirstChar { it.titlecase() }
}

fun haversineDistanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {
    if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) return Double.POSITIVE_INFINITY
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadius * atan2(sqrt(a), sqrt(1 - a))
}

fun nearestRouteDistanceMeters(
    lat: Double,
    lon: Double,
    route: List<Pair<Double, Double>>
): Double {
    if (route.isEmpty()) return Double.POSITIVE_INFINITY
    return route.minOf { (pointLat, pointLon) ->
        haversineDistanceMeters(lat, lon, pointLat, pointLon)
    }
}
