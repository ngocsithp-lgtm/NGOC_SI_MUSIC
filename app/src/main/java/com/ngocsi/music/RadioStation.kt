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
                "https://str.vov.gov.vn/vovlive/vov1vov5Vietnamese.sdp_aac/playlist.m3u8",
                "https://media-audio.vov.vn/vov1vov5Vietnamese.sdp_aac/playlist.m3u8",
                "https://audio-lss.vov.vn/han/live/vov1/audio/manifest.m3u8",
                "https://audio-lss.vov.vn/live/vov1.m3u8"
            )
        ),
        RadioStation(
            title = "VOV2 • Văn hóa",
            description = "Văn hóa, giáo dục, khoa học và giải trí",
            sourceUrl = "https://vov2.vov.vn/",
            streamUrls = listOf(
                "https://media-audio.vov.vn/vov2.sdp_aac/playlist.m3u8",
                "https://audio-lss.vov.vn/han/live/vov2/audio/manifest.m3u8",
                "https://str.vov.gov.vn/vovlive/vov2.sdp_aac/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOV3 • Âm nhạc",
            description = "Âm nhạc, dân ca, chương trình giải trí",
            sourceUrl = "https://vov3.vov.vn/",
            streamUrls = listOf(
                "https://media-audio.vov.vn/vov3.sdp_aac/playlist.m3u8",
                "https://audio-lss.vov.vn/han/live/vov3/audio/manifest.m3u8",
                "https://str.vov.gov.vn/vovlive/vov3.sdp_aac/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOV5 • Đối ngoại",
            description = "Phát thanh đối ngoại của VOV",
            sourceUrl = "https://vovworld.vn/",
            streamUrls = listOf(
                "https://media-audio.vov.vn/vov5.sdp_aac/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOV Giao thông • Hà Nội",
            description = "Giao thông và thông tin đô thị Hà Nội",
            sourceUrl = "https://vovgiaothong.vn/",
            streamUrls = listOf(
                "https://play.vovgiaothong.vn/live/gthn/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOV Giao thông • TP.HCM",
            description = "Giao thông và thông tin đô thị TP.HCM",
            sourceUrl = "https://vovgiaothong.vn/",
            streamUrls = listOf(
                "https://play.vovgiaothong.vn/live/gthcm/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOH • FM 95.6",
            description = "Kênh phát thanh FM 95.6 MHz",
            sourceUrl = "https://voh.com.vn/radios",
            streamUrls = listOf(
                "https://strm.voh.com.vn/radio/channel1/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOH • FM 99.9",
            description = "Kênh phát thanh FM 99.9 MHz",
            sourceUrl = "https://voh.com.vn/radios",
            streamUrls = listOf(
                "https://strm.voh.com.vn/radio/channel3/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOH • AM 610",
            description = "Kênh phát thanh AM 610 kHz",
            sourceUrl = "https://voh.com.vn/radios",
            streamUrls = listOf(
                "https://strm.voh.com.vn/radio/channel2/playlist.m3u8"
            )
        )
    )

    private val byTitle = stations.associateBy { it.title }

    fun find(title: String): RadioStation? = byTitle[title]
}
