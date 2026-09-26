package com.ngocsi.music

/**
 * A radio station with ordered stream fallbacks.
 *
 * The first reachable stream should be preferred; later entries are used when
 * Media3 reports an error or stays buffering for too long.
 */
data class RadioStation(
    val title: String,
    val description: String,
    val sourceUrl: String,
    val streamUrls: List<String>
)

object RadioCatalog {
    val stations: List<RadioStation> = listOf(
        RadioStation(
            title = "VOV1 • Thời sự",
            description = "Thời sự, chính trị, kinh tế và đời sống",
            sourceUrl = "https://vov1.vov.vn/",
            streamUrls = listOf(
                "https://audio-lss.vov.vn/han/live/vov1/audio/manifest.m3u8",
                "https://media-audio.vov.vn/vov1vov5Vietnamese.sdp_aac/playlist.m3u8",
                "https://str.vov.gov.vn/vovlive/vov1vov5Vietnamese.sdp_aac/playlist.m3u8",
                "https://audio-lss.vov.vn/live/vov1.m3u8"
            )
        ),
        RadioStation(
            title = "VOV2 • Văn hóa",
            description = "Văn hóa, giáo dục, khoa học và giải trí",
            sourceUrl = "https://vov2.vov.vn/",
            // Direct VOV2 HLS endpoints are not currently stable enough to expose
            // as a guaranteed in-app stream. Keep the official source button instead
            // of showing a play button that can silently fail.
            streamUrls = emptyList()
        ),
        RadioStation(
            title = "VOV3 • Âm nhạc",
            description = "Âm nhạc, dân ca, chương trình giải trí",
            sourceUrl = "https://vov3.vov.vn/",
            // Direct VOV3 HLS endpoints are not currently stable enough to expose
            // as a guaranteed in-app stream. Open the official source instead.
            streamUrls = emptyList()
        ),
    )

    private val byTitle = stations.associateBy { it.title }

    fun find(title: String): RadioStation? = byTitle[title]
}
