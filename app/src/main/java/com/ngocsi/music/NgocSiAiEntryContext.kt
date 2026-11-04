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
        "online", "tat ca" -> "Online"
        "youtube" -> "YouTube"
        "drive", "google drive" -> "Google Drive"
        "yeu thich", "yeu thich online" -> "Yêu thích"
        "radio" -> "Radio"
        "tv" -> "TV"
        "playlist" -> "Playlist"
        "cai dat" -> "Cài đặt"
        else -> null
    }

/**
 * The page hint is system context only: it is never inserted into the user's
 * visible transcript or persisted conversation history.
 */
internal fun buildNgocSiAiEntryContextInstruction(rawContext: String?): String {
    val page = normalizeNgocSiAiEntryContext(rawContext) ?: return ""
    return " Bối cảnh giao diện: người dùng vừa mở NGỌC SĨ AI từ trang $page. " +
        "Khi họ nói 'ở đây', 'trang này', 'tìm tiếp' hoặc dùng đại từ về nội dung đang xem, " +
        "hãy ưu tiên trang này và trạng thái ứng dụng hiện tại. Không tự suy đoán tên bài, " +
        "đài, video, thư mục hay kết quả chưa được ứng dụng cung cấp."
}
