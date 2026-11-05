package com.ngocsi.music

/** Intent extra passed when the assistant is opened from a feature page. */
internal const val NGOC_SI_AI_ENTRY_CONTEXT_EXTRA =
    "com.ngocsi.music.extra.NGOC_SI_AI_ENTRY_CONTEXT"

/**
 * Convert app-owned navigation state into a small allowlisted page label.
 * Unknown values are discarded rather than forwarded as arbitrary instructions.
 */
internal fun normalizeNgocSiAiEntryContext(rawContext: String?): String? =
    when (normalizeNgocSiAiVoicePhrase(rawContext.orEmpty())) {
        "trang chu" -> "Trang chủ"
        "thu vien" -> "Thư viện"
        "album" -> "Album"
        "thu muc" -> "Thư mục"
        "thiet bi" -> "Thiết bị"
        "nhac yeu thich" -> "Yêu thích"
        "online", "tat ca" -> "Online"
        "youtube" -> "YouTube"
        "drive", "google drive" -> "Google Drive"
        "yeu thich", "yeu thich online" -> "Yêu thích"
        "radio" -> "Radio"
        "tv" -> "TV"
        "playlist" -> "Playlist"
        "cai dat" -> "Cài đặt"
        "trinh phat", "dang phat", "now playing" -> "Trình phát"
        else -> null
    }

/**
 * The page hint is system context only: it is never inserted into the user's
 * visible transcript or persisted conversation history.
 */
internal fun buildNgocSiAiEntryContextInstruction(rawContext: String?): String {
    val page = normalizeNgocSiAiEntryContext(rawContext) ?: return ""
    val sourceGuidance = when (page) {
        "YouTube" ->
            " Đây là trang YouTube: khi người dùng yêu cầu vừa tìm vừa phát, ưu tiên " +
                "search_and_play_youtube; nếu chỉ muốn tìm thì dùng search_youtube. " +
                "Các thao tác lên video YouTube đang phát phải dùng nhóm youtube_*; " +
                "không dùng lệnh Media3 để điều khiển nhầm trình phát."
        "Google Drive" ->
            " Đây là trang Google Drive: ưu tiên search_drive cho yêu cầu tìm tệp trên Drive; " +
                "chỉ báo đã mở hoặc phát tệp khi app_feedback xác nhận. Không tự giả định " +
                "người dùng đã đăng nhập, đã cấp quyền hoặc đang ở một thư mục cụ thể."
        "Radio" ->
            " Đây là trang Radio: khi người dùng nêu tên đài, dùng play_radio_station. " +
                "Các lệnh phát/tạm dừng/âm lượng phải áp dụng cho nguồn đang phát đã được xác nhận; " +
                "không nhầm đài Radio với video YouTube hoặc bài nhạc trong máy."
        "TV" ->
            " Đây là trang TV: chỉ dùng open_tv_channel khi người dùng nêu hoặc xác nhận tên kênh. " +
                "Không khẳng định kênh đã phát nếu app_feedback mới chỉ xác nhận mở màn hình hoặc gửi lệnh."
        "Trình phát" ->
            " Đây là giao diện Trình phát: xác định nguồn đang phát trước khi chọn thao tác. " +
                "Nếu nguồn là YouTube, dùng lệnh youtube_*; nếu là nhạc máy, Drive hoặc Radio, " +
                "dùng lệnh Media3 tương ứng. Không giả định nguồn phát chỉ dựa vào trang đang mở."
        "Thư viện", "Album", "Thư mục", "Thiết bị" ->
            " Đây là khu vực thư viện nhạc: ưu tiên các lệnh phát và quản lý nhạc trong ứng dụng. " +
                "Chỉ tìm hoặc phát bài khi có tên bài hoặc dữ liệu được app cung cấp; không tự bịa kết quả."
        "Playlist" ->
            " Đây là khu vực Playlist: phân biệt rõ tạo playlist, phát playlist, thêm bài đang phát " +
                "và thêm toàn bộ hàng đợi. Trước khi nói đã lưu, phải có app_feedback xác nhận."
        "Online" ->
            " Đây là khu vực nội dung trực tuyến: xác định đúng tab và nguồn (YouTube, Drive hoặc Yêu thích) " +
                "trước khi tìm kiếm hoặc điều khiển."
        else -> ""
    }
    return " Bối cảnh giao diện: người dùng vừa mở NGỌC SĨ AI từ trang $page. " +
        "Khi họ nói 'ở đây', 'trang này', 'tìm tiếp' hoặc dùng đại từ về nội dung đang xem, " +
        "hãy ưu tiên trang này và trạng thái ứng dụng hiện tại. " +
        sourceGuidance +
        " Không tự suy đoán tên bài, đài, video, thư mục hay kết quả chưa được ứng dụng cung cấp."
}
