package com.ngocsi.music

/**
 * A radio station with ordered stream fallbacks.
 *
 * Direct streams are kept as candidates rather than a single hard-coded URL.
 * When all candidates fail, the UI falls back to the station's official source.
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
            description = "Văn hóa, giáo dục, khoa học và đời sống",
            sourceUrl = "https://vov2.vov.vn/",
            streamUrls = listOf(
                "https://audio-lss.vov.vn/han/live/vov2/audio/manifest.m3u8",
                "https://audio-lss.vov.vn/live/vov2.m3u8",
                "https://media-audio.vov.vn/vov2.sdp_aac/playlist.m3u8",
                "https://str.vov.gov.vn/vovlive/vov2.sdp_aac/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOV3 • Âm nhạc",
            description = "Âm nhạc, dân ca, chương trình giải trí",
            sourceUrl = "https://vov3.vov.vn/",
            streamUrls = listOf(
                "https://audio-lss.vov.vn/han/live/vov3/audio/manifest.m3u8",
                "https://audio-lss.vov.vn/live/vov3.m3u8",
                "https://media-audio.vov.vn/vov3.sdp_aac/playlist.m3u8",
                "https://str.vov.gov.vn/vovlive/vov3.sdp_aac/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOV5 • Đối ngoại",
            description = "Phát thanh đối ngoại đa ngôn ngữ",
            sourceUrl = "https://vovworld.vn/",
            streamUrls = listOf(
                "https://media-audio.vov.vn/vov5.sdp_aac/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOV6 • Văn nghệ",
            description = "Văn học, nghệ thuật và nội dung chuyên đề",
            sourceUrl = "https://vov6.vov.vn/",
            streamUrls = listOf(
                "https://audio-lss.vov.vn/han/live/vov6/audio/manifest.m3u8"
            )
        ),
        RadioStation(
            title = "VOV Giao Thông • Hà Nội",
            description = "Giao thông và thông tin đô thị Hà Nội",
            sourceUrl = "https://vovgiaothong.vn/",
            streamUrls = listOf(
                "https://play.vovgiaothong.vn/live/gthn/playlist.m3u8"
            )
        ),
        RadioStation(
            title = "VOV Giao Thông • TP.HCM",
            description = "Giao thông và thông tin đô thị TP.HCM",
            sourceUrl = "https://vovgiaothong.vn/",
            streamUrls = listOf(
                "https://play.vovgiaothong.vn/live/gthcm/playlist.m3u8"
            )
        )
    )

    private val byTitle = stations.associateBy { it.title }

    fun find(title: String): RadioStation? = byTitle[title]
}
