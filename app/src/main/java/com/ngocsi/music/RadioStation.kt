package com.ngocsi.music

/**
 * A radio station with ordered stream fallbacks.
 *
 * Direct candidates are used by Media3. When they fail, the app falls back
 * to the station's official web source instead of silently showing a dead
 * playback button.
 */
data class RadioStation(
    val title: String,
    val description: String,
    val sourceUrl: String,
    val streamUrls: List<String>
)

object RadioCatalog {
    val stations: List<RadioStation> = listOf(
        RadioStation("VOV1 • Thời sự", "Thời sự, chính trị, kinh tế và đời sống", "https://vov1.vov.vn/",
            listOf(
                "https://audio-lss.vov.vn/han/live/vov1/audio/manifest.m3u8",
                "https://str.vov.gov.vn/vovlive/vov1vov5Vietnamese.sdp_aac/playlist.m3u8"
            )),
        RadioStation("VOV2 • Văn hóa", "Văn hóa, giáo dục, khoa học và đời sống", "https://vov2.vov.vn/",
            listOf(
                "https://audio-lss.vov.vn/han/live/vov2/audio/manifest.m3u8",
                "https://str.vov.gov.vn/vovlive/vov2.sdp_aac/playlist.m3u8"
            )),
        RadioStation("VOV3 • Âm nhạc", "Âm nhạc, dân ca, chương trình giải trí", "https://vov3.vov.vn/",
            listOf(
                "https://audio-lss.vov.vn/han/live/vov3/audio/manifest.m3u8",
                "https://str.vov.gov.vn/vovlive/vov3.sdp_aac/playlist.m3u8"
            )),
        RadioStation("VOV4 • Dân tộc", "Phát thanh dành cho đồng bào các dân tộc", "https://vov4.vov.vn/",
            listOf(
                "https://audio-lss.vov.vn/han/live/vov4/audio/manifest.m3u8",
                "https://str.vov.gov.vn/vovlive/vov4.TayNguyen.sdp_aac/playlist.m3u8"
            )),
        RadioStation("VOV5 • Đối ngoại", "Phát thanh đối ngoại đa ngôn ngữ", "https://vovworld.vn/",
            listOf(
                "https://media-audio.vov.vn/vov5.sdp_aac/playlist.m3u8"
            )),
        RadioStation("VOV6 • Văn nghệ", "Văn học, nghệ thuật và nội dung chuyên đề", "https://vov6.vov.vn/",
            listOf(
                "https://audio-lss.vov.vn/han/live/vov6/audio/manifest.m3u8",
                "https://stream.vovmedia.vn/vov6"
            )),
        RadioStation("VOV Giao Thông • Hà Nội", "Giao thông và thông tin đô thị Hà Nội", "https://vovgiaothong.vn/",
            listOf(
                "https://play.vovgiaothong.vn/live/gthn/playlist.m3u8",
                "https://str.vov.gov.vn/vovlive/vovGTHN.sdp_aac/chunklist_w601606653.m3u8"
            )),
        RadioStation("VOV Giao Thông • TP.HCM", "Giao thông và thông tin đô thị TP.HCM", "https://vovgiaothong.vn/",
            listOf(
                "https://play.vovgiaothong.vn/live/gthcm/playlist.m3u8",
                "https://str.vov.gov.vn/vovlive/vovGTHCM.sdp_aac/chunklist_w1213978008.m3u8"
            )),
        RadioStation("VOV Giao Thông • Mekong", "Giao thông và thông tin khu vực Mekong", "https://vovgiaothong.vn/",
            listOf(
                "https://play.vovgiaothong.vn/live/mekong/playlist.m3u8",
                "https://str.vov.gov.vn/vovlive/vovgtMeKong.sdp_aac/chunklist_w1338501805.m3u8"
            )),
        RadioStation("VOV Giao Thông • Duyên Hải", "Giao thông khu vực duyên hải", "https://vovgiaothong.vn/",
            listOf("https://play.vovgiaothong.vn/live/duyenhai2/playlist.m3u8")),
        RadioStation("VOH • FM 95.6", "Phát thanh VOH TP.HCM", "https://voh.com.vn/radio",
            listOf(
                "https://strmx.voh.com.vn/radio/channel1/playlist.m3u8",
                "https://strm.voh.com.vn/radio/channel1/playlist.m3u8"
            )),
        RadioStation("VOH • AM 610", "Kênh phát thanh VOH AM 610", "https://voh.com.vn/radio",
            listOf(
                "https://strmx.voh.com.vn/radio/channel2/playlist.m3u8",
                "https://strm.voh.com.vn/radio/channel2/playlist.m3u8"
            )),
        RadioStation("VOH • FM 99.9", "Phát thanh VOH FM 99.9 MHz", "https://voh.com.vn/radio",
            listOf(
                "https://strmx.voh.com.vn/radio/channel3/playlist.m3u8",
                "https://strm.voh.com.vn/radio/channel3/playlist.m3u8"
            ))
    )

    private val byTitle = stations.associateBy { it.title }

    fun find(title: String): RadioStation? = byTitle[title]
}
