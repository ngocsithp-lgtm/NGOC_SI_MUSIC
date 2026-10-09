package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class RadioCatalogTest {
    @Test
    fun stationTitlesAndDescriptionsArePresentAndUnique() {
        val stations = RadioCatalog.stations
        assertFalse("Radio catalog must not be empty", stations.isEmpty())
        assertEquals(
            "Station titles should be unique",
            stations.size,
            stations.map { it.title.trim() }.distinct().size
        )
        stations.forEach { station ->
            assertTrue("Blank station title", station.title.isNotBlank())
            assertTrue("Blank station description for ${station.title}", station.description.isNotBlank())
        }
    }

    @Test
    fun everyStationHasAnHttpsSourceAndAtLeastOneFallbackStream() {
        RadioCatalog.stations.forEach { station ->
            assertHttpsUrl("Source page for ${station.title}", station.sourceUrl)
            assertFalse("No stream candidates for ${station.title}", station.streamUrls.isEmpty())
            station.streamUrls.forEach { url ->
                assertHttpsUrl("Stream for ${station.title}", url)
            }
        }
    }

    @Test
    fun fallbackStreamsAreNotDuplicatedWithinAStation() {
        RadioCatalog.stations.forEach { station ->
            val streams = station.streamUrls.map(String::trim)
            assertEquals(
                "Duplicate fallback stream for ${station.title}",
                streams.size,
                streams.distinct().size
            )
        }
    }

    private fun assertHttpsUrl(label: String, rawUrl: String) {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
        assertTrue("$label must use HTTPS", uri?.scheme.equals("https", ignoreCase = true))
        assertTrue("$label must have a host", !uri?.host.isNullOrBlank())
    }
}
