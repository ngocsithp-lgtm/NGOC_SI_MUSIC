package com.ngocsi.music

/**
 * Fast, deterministic, offline guidance for questions about the app itself.
 * This path is shared by screen chat and the wake-word conversation service,
 * so app-orientation questions never wait for a Gemini network round-trip.
 */
internal fun localNgocSiAiAppHelpFallback(userText: String): String? {
    val normalized = normalizeNgocSiAiVoicePhrase(userText)
    if (normalized.isBlank()) return null

    val guideMarkers = listOf(
        "huong dan", "cach su dung", "cach dung", "su dung nhu the nao",
        "giai thich", "chuc nang", "gioi thieu", "lam duoc gi",
        "co nhung gi", "ho tro nhung gi", "dung app", "dung ung dung"
    )
    val hasAppReference = listOf(
        "app", "ung dung", "ngoc si", "nhac", "thu vien", "youtube",
        "drive", "radio", "tv", "hen gio", "playlist", "hang doi"
    ).any { normalized.contains(it) }
    val asksForGuide = guideMarkers.any { normalized.contains(it) }
    if (!asksForGuide || !hasAppReference) return null

    return when {
        normalized.contains("youtube") ->
            "Cách dùng YouTube: mở mục YouTube trong ứng dụng, nhập tên bài hát hoặc video vào ô tìm kiếm, rồi chọn kết quả để phát. Khi YouTube đang là nguồn phát, bạn có thể yêu cầu AI tạm dừng, phát tiếp, chuyển video, quay lại video trước hoặc tua tiến/lùi. Ví dụ: “AI, mở YouTube”, “AI, tìm nhạc Trịnh Công Sơn”, “AI, tua tới 10 giây”. Cần có kết nối Internet."

        normalized.contains("google drive") || normalized.contains("drive") ->
            "Cách dùng Google Drive: mở mục Drive, đăng nhập tài khoản Google và cấp quyền truy cập được yêu cầu. Chọn thư mục, mở thư mục chứa nhạc, rồi chọn bài để phát hoặc đưa vào hàng đợi. Nếu chưa đăng nhập hoặc chưa cấp quyền, hãy hoàn tất bước đó trước. Việc duyệt và phát tệp trên đám mây cần Internet."

        normalized.contains("radio") ->
            "Cách dùng Radio: mở mục Radio, chọn đài muốn nghe và bắt đầu phát. Khi Radio đang phát, có thể dùng các lệnh phát, tạm dừng hoặc dừng nhạc được hỗ trợ. Chất lượng và khả năng phát phụ thuộc kết nối Internet và trạng thái của đài."

        normalized.contains("tv") || normalized.contains("truyen hinh") ->
            "Cách dùng TV: mở mục TV, chọn kênh trong danh sách để xem. Kênh trực tuyến cần Internet và có thể tạm ngừng khi nguồn phát của kênh gặp sự cố."

        normalized.contains("hen gio") || normalized.contains("tat nhac") ->
            "Cách dùng hẹn giờ: mở mục Hẹn giờ, chọn khoảng thời gian muốn nghe rồi bắt đầu đếm ngược. Khi hết thời gian, bộ hẹn giờ sẽ thực hiện hành động dừng phát theo thiết lập của ứng dụng. Bạn cũng có thể yêu cầu AI đặt hoặc hủy những mốc hẹn giờ được hỗ trợ."

        normalized.contains("thu vien") || normalized.contains("nhac tren dien thoai") ||
            normalized.contains("nhac trong may") ->
            "Cách dùng Thư viện nhạc: mở mục Thư viện, tìm bài hát hoặc duyệt theo danh sách có sẵn trên điện thoại. Chọn một bài để phát; có thể dùng hàng đợi để chuyển bài, phát bài trước/sau, bật phát ngẫu nhiên hoặc lặp khi các điều khiển đó khả dụng."

        normalized.contains("playlist") || normalized.contains("hang doi") ||
            normalized.contains("yeu thich") ->
            "Hàng đợi là danh sách bài đang chờ phát; bạn có thể chuyển bài tiếp theo hoặc quay lại bài trước. Playlist dùng để gom các bài muốn nghe theo nhóm, còn Yêu thích giúp lưu nhanh bài đang phát. Hãy mở mục tương ứng trong ứng dụng để xem và quản lý danh sách."

        else ->
            "NGỌC SĨ MUSIC có các nhóm chức năng chính:\n" +
                "1. Thư viện nhạc: tìm và phát nhạc có trên điện thoại.\n" +
                "2. YouTube: tìm và phát nhạc hoặc video trực tuyến.\n" +
                "3. Google Drive: đăng nhập, duyệt thư mục và phát tệp nhạc trên Drive.\n" +
                "4. Radio và TV: chọn đài phát thanh hoặc kênh trực tuyến.\n" +
                "5. Hàng đợi, Playlist và Yêu thích: sắp xếp nội dung muốn nghe.\n" +
                "6. Hẹn giờ: đặt thời gian dừng phát.\n" +
                "7. NGỌC SĨ AI: hỏi đáp, nhận lệnh giọng nói và điều khiển các thao tác ứng dụng đã hỗ trợ.\n\n" +
                "Cách bắt đầu: chọn nguồn nhạc (Thư viện, YouTube, Drive hoặc Radio), tìm nội dung, chọn bài để phát; sau đó dùng trình phát và hàng đợi để điều khiển. Với AI, đánh thức bằng từ “AI”, chờ AI xác nhận đang nghe rồi nói yêu cầu; có thể nói liên tục mà không cần lặp lại từ đánh thức. Ví dụ: “AI, mở YouTube”, “AI, phát nhạc”, “AI, tạm dừng”, “AI, mở Radio” hoặc “AI, hẹn giờ 30 phút”. Nói “AI, tạm dừng AI” để ngừng nghe từ khóa. YouTube, Drive, Radio và TV cần Internet; Drive cần đăng nhập. Hỏi đáp Gemini trực tuyến có thể bị giới hạn hạn mức, nhưng hướng dẫn ứng dụng này và các lệnh cục bộ được hỗ trợ vẫn hoạt động trên máy."
    }
}
