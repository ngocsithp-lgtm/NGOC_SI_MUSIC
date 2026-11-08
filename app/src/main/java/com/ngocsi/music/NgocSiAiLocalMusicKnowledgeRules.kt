package com.ngocsi.music

/**
 * Deterministic Vietnamese music and vocal concepts for use when Gemini quota is exhausted.
 * General learning only: never treat these notes as evidence about a specific playing recording.
 */
internal fun localNgocSiAiMusicKnowledgeFallback(userText: String): String? {
    val normalized = stripNgocSiAiDirectIntentWakePrefix(
        normalizeNgocSiAiVoicePhrase(userText)
    ).removePrefix("hay ").trim()
    if (normalized.isBlank()) return null

    // A generic definition cannot establish what is actually audible or visible in a player item.
    val refersToSpecificMedia = listOf(
        "video dang phat", "video nay", "clip nay", "clip dang phat",
        "ban thu nay", "ban thu dang phat", "ca si nay", "ca si dang hat",
        "nguoi dang hat", "dang hat", "dang phat", "trong video", "trong clip",
        "trong ban thu", "doan nhac nay", "doan hat nay", "bai dang phat",
        "hien tai dang nghe", "dang nghe", "dang xem", "canh quay nay",
        "youtube nay", "video youtube"
    ).any(normalized::contains)
    if (refersToSpecificMedia) return null

    val asksDefinition = listOf(
        "la gi", "nghia la gi", "la the nao", "la dong nhac gi",
        "nguon goc", "bat nguon", "hinh thanh", "lich su"
    ).any(normalized::contains)
    val asksBolero = normalized.contains("bolero")
    val asksVocalComparison = listOf(
        "tieu chi danh gia giong hat", "danh gia giong hat",
        "nghe ca si hat hay", "cach nghe giong hat",
        "phan tich giong hat", "so sanh cach hat", "so sanh ca si"
    ).any(normalized::contains)

    return when {
        asksBolero && asksDefinition ->
            "Bolero có nhiều truyền thống âm nhạc khác nhau, nên cần phân biệt cách dùng tên gọi. " +
            "Bolero Tây Ban Nha ban đầu là một điệu nhảy; Bolero Cuba phát triển thành thể loại ca khúc trữ tình ở vùng Santiago de Cuba vào cuối thế kỷ 19. " +
            "Trong đời sống âm nhạc Việt Nam, “Bolero” thường được dùng để nói về những ca khúc trữ tình có tiết điệu Bolero hoặc Rumba-Bolero, thường chậm rãi, nhịp nhàng và giàu tính tự sự. " +
            "Không nên đồng nhất mọi nhạc vàng, mọi ca khúc trước 1975 hay mọi bản nhạc buồn với Bolero. " +
            "Đây là giải thích khái quát về thể loại, không phải nhận diện một bài hát cụ thể."

        asksBolero && listOf(
            "dac diem", "phong cach", "cach hat", "the hien", "cam xuc",
            "tieu chi", "phan tich", "nghe sao", "hat bolero"
        ).any(normalized::contains) ->
            "Khi tìm hiểu cách thể hiện Bolero, có thể chú ý sáu điểm: " +
            "1. Nhịp điệu đều và ổn định, không kéo hoặc đẩy phách tùy tiện. " +
            "2. Nhả chữ rõ nhưng tự nhiên, không tách vụn lời ca. " +
            "3. Luyến láy vừa đủ để làm rõ sắc thái giai điệu, không phô diễn liên tục. " +
            "4. Hơi thở được phân bổ theo câu nhạc để giữ âm cuối ổn định. " +
            "5. Rung giọng có kiểm soát, đặt đúng chỗ và đúng thời điểm. " +
            "6. Cảm xúc phải phù hợp với ý nghĩa lời ca và diễn tiến giai điệu. " +
            "Đây là bộ tiêu chí nghe chung; muốn nhận xét một ca sĩ hoặc bản thu cụ thể cần có âm thanh thực tế."

        (normalized.contains("luyen lay") || normalized.contains("luyen la")) && asksDefinition ->
            "Luyến láy là cách người hát nối hoặc tô điểm các nốt bằng những chuyển động cao độ nhỏ, chẳng hạn lướt từ nốt này sang nốt khác, nhấn nhẹ hoặc điểm thêm nốt trang sức. " +
            "Luyến thường nhấn vào sự nối liền giữa các cao độ; láy thường tạo nét nhấn hoặc vòng nốt ngắn quanh âm chính. " +
            "Trong Bolero, luyến láy có thể giúp câu hát mềm và giàu cảm xúc, nhưng cần đúng cao độ, đúng nhịp và không che mất lời ca."

        (normalized.contains("nha chu") || normalized.contains("phat am khi hat") ||
            normalized.contains("phat am trong ca hat")) && asksDefinition ->
            "Nhả chữ là cách người hát phát âm và đưa từng từ vào giai điệu. " +
            "Một cách nhả chữ tốt giúp người nghe nhận ra nguyên âm, phụ âm và ý nghĩa câu hát, nhưng vẫn giữ được độ liền mạch của âm thanh. " +
            "Không nên cắn phụ âm quá mạnh, kéo sai dấu tiếng Việt hoặc ngắt câu ở vị trí làm đổi nghĩa lời ca. " +
            "Khi luyện tập, hãy đọc rõ lời theo nhịp trước, sau đó hát chậm và giữ nguyên độ rõ của tiếng."

        (normalized.contains("rung giong") || normalized.contains("vibrato")) && asksDefinition ->
            "Rung giọng, thường gọi là vibrato, là dao động nhỏ và tương đối đều của cao độ khi giữ một nốt hát. " +
            "Khi được kiểm soát, nó có thể làm âm thanh mềm, đầy và biểu cảm hơn. " +
            "Rung giọng không đồng nghĩa với run giọng: dao động quá rộng, quá chậm hoặc không ổn định có thể làm nốt nghe chênh và thiếu vững. " +
            "Không cần rung ở mọi nốt; hãy ưu tiên cao độ ổn định và chỉ dùng khi phù hợp với câu nhạc."

        (normalized.contains("melisma") || normalized.contains("mot am tiet nhieu not") ||
            normalized.contains("nhieu not tren mot am tiet")) && asksDefinition ->
            "Melisma là cách hát nhiều nốt nhạc trên một âm tiết của lời ca. " +
            "Đây là một dạng trang trí giai điệu thường gặp trong nhiều truyền thống thanh nhạc. " +
            "Melisma khác với cách hát âm tiết: mỗi âm tiết không nhất thiết chỉ ứng với một nốt. " +
            "Khi dùng trong ca khúc trữ tình, cần giữ rõ nguyên âm và không làm mất nội dung lời."

        (normalized.contains("hoi tho") || normalized.contains("hoi khi hat") ||
            normalized.contains("kiem soat hoi") || normalized.contains("lay hoi")) &&
            (asksDefinition || normalized.endsWith("the nao") ||
                normalized.contains("hat bolero") || normalized.contains("giu cau hat")) ->
            "Hỗ trợ hơi thở khi hát là việc phối hợp luồng hơi với việc tạo âm và chia câu nhạc. " +
            "Người hát nên xác định trước vị trí lấy hơi, tránh hít gấp giữa một ý lời, và giữ luồng hơi ổn định để không ép cổ họng ở cuối câu. " +
            "Khi luyện, hãy hát câu ngắn ở âm lượng vừa phải, đánh dấu chỗ lấy hơi, rồi tăng dần độ dài mà vẫn giữ âm thanh thoải mái. " +
            "Nếu thấy đau hoặc căng cổ, nên dừng và điều chỉnh kỹ thuật thay vì cố hát lớn hơn."

        asksVocalComparison ->
            "Để đánh giá hoặc so sánh cách hát một cách công bằng, hãy dùng cùng một đoạn thu âm hoặc bài hát và đối chiếu: " +
            "1. Cao độ và độ ổn định của nốt. " +
            "2. Nhịp, phách và cách xử lý cuối câu. " +
            "3. Màu giọng và độ vang. " +
            "4. Nhả chữ, phát âm và dấu tiếng Việt. " +
            "5. Luyến láy, rung giọng và cách giữ hơi. " +
            "6. Diễn đạt cảm xúc có phù hợp lời ca hay không. " +
            "Không thể kết luận ai hát tốt hơn chỉ từ tên ca sĩ hoặc tiêu đề video; cần nghe bản thu thực tế và xét điều kiện thu âm tương đương."

        else -> null
    }
}
