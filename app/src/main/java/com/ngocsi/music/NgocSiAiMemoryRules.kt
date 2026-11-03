package com.ngocsi.music

import android.content.SharedPreferences

internal const val NGOC_SI_AI_CHAT_PREFERENCES_NAME = "ngoc_si_ai_chat_history"
internal const val NGOC_SI_AI_LONG_TERM_MEMORY_KEY = "app_music_preferences_v1"
internal const val NGOC_SI_AI_MAX_MEMORY_ITEMS = 8
internal const val NGOC_SI_AI_MAX_MEMORY_ITEM_CHARS = 160

internal enum class NgocSiAiMemoryCommandType {
    REMEMBER,
    LIST,
    CLEAR
}

internal data class NgocSiAiMemoryCommand(
    val type: NgocSiAiMemoryCommandType,
    val note: String = ""
)

internal data class NgocSiAiMemoryResult(
    val memories: List<String>,
    val reply: String,
    val shouldPersist: Boolean
)

private val NGOC_SI_AI_MEMORY_WAKE_PREFIX = Regex(
    """^(?:(?:hey|ok|alo)\s+)?(?:(?:ngoc\s+si|ngọc\s+sĩ)\s+)?ai(?:\s*[,.:]\s*|\s+)""",
    RegexOption.IGNORE_CASE
)

private val NGOC_SI_AI_MEMORY_REMEMBER_PREFIX = Regex(
    """^(?:hãy\s+|hay\s+)?(?:ghi\s+nhớ|ghi\s+nho|nhớ\s+rằng|nho\s+rang|nhớ|nho)\s+(?:là\s+|la\s+)?(.+)$""",
    RegexOption.IGNORE_CASE
)

private val NGOC_SI_AI_MEMORY_ALLOWED_TOPICS = listOf(
    "nhac", "bolero", "trinh", "nhac vang", "nhac song", "nhac xua",
    "cai luong", "jazz", "rock", "pop", "youtube", "drive", "radio", "tv",
    "playlist", "hang doi", "thu vien", "yeu thich", "mini player", "video",
    "giong doc", "giong noi", "am luong", "hen gio", "phat nen", "background"
)

private val NGOC_SI_AI_MEMORY_BLOCKED_TOPICS = listOf(
    "mat khau", "password", "ma pin", "otp", "so tai khoan", "tai khoan ngan hang",
    "cccd", "cmnd", "so dien thoai", "dia chi nha", "email", "the ngan hang",
    "the tin dung", "suc khoe", "benh ly", "chan doan", "thuoc men",
    "chinh tri", "ton giao", "dan toc", "gioi tinh", "doi song tinh duc"
)

private fun stripNgocSiAiMemoryWakePrefix(input: String): String =
    NGOC_SI_AI_MEMORY_WAKE_PREFIX.replaceFirst(input.trim(), "").trim()

private fun isSafeNgocSiAiMemoryNote(note: String): Boolean {
    val normalized = normalizeNgocSiAiVoicePhrase(note)
    if (normalized.isBlank() || normalized.length < 4 ||
        normalized.length > NGOC_SI_AI_MAX_MEMORY_ITEM_CHARS
    ) return false
    if (NGOC_SI_AI_MEMORY_BLOCKED_TOPICS.any(normalized::contains)) return false
    return NGOC_SI_AI_MEMORY_ALLOWED_TOPICS.any(normalized::contains)
}

/**
 * Recognizes only explicit remember/list/clear commands for music and app-use preferences.
 * General personal facts and sensitive details are deliberately not accepted.
 */
internal fun parseNgocSiAiMemoryCommand(input: String): NgocSiAiMemoryCommand? {
    val raw = stripNgocSiAiMemoryWakePrefix(input)
    if (raw.isBlank()) return null
    val normalized = normalizeNgocSiAiVoicePhrase(raw)

    if (normalized in setOf(
            "nho gi ve toi", "ban nho gi ve toi", "ai nho gi ve toi",
            "hien thi ghi nho", "xem ghi nho", "liet ke ghi nho",
            "ghi nho cua toi", "ban dang nho gi"
        )
    ) {
        return NgocSiAiMemoryCommand(NgocSiAiMemoryCommandType.LIST)
    }

    if (normalized in setOf(
            "quen het ghi nho", "xoa het ghi nho", "xoa ghi nho",
            "quen ghi nho", "xoa bo nho", "quen het bo nho",
            "xoa bo nho dai han", "xoa ghi nho cua toi"
        )
    ) {
        return NgocSiAiMemoryCommand(NgocSiAiMemoryCommandType.CLEAR)
    }

    val match = NGOC_SI_AI_MEMORY_REMEMBER_PREFIX.matchEntire(raw) ?: return null
    val note = match.groupValues[1]
        .trim()
        .trimEnd('.', '!', '?', ';', ',')
        .replace(Regex("""\s+"""), " ")
    return NgocSiAiMemoryCommand(NgocSiAiMemoryCommandType.REMEMBER, note)
}

internal fun decodeNgocSiAiMemory(raw: String?): List<String> =
    raw.orEmpty()
        .split('\n')
        .map { it.trim() }
        .filter(::isSafeNgocSiAiMemoryNote)
        .distinctBy(::normalizeNgocSiAiVoicePhrase)
        .takeLast(NGOC_SI_AI_MAX_MEMORY_ITEMS)

internal fun encodeNgocSiAiMemory(memories: List<String>): String =
    decodeNgocSiAiMemory(memories.joinToString("\n")).joinToString("\n")

internal fun applyNgocSiAiMemoryCommand(
    command: NgocSiAiMemoryCommand,
    currentMemories: List<String>
): NgocSiAiMemoryResult {
    val memories = decodeNgocSiAiMemory(currentMemories.joinToString("\n"))
    return when (command.type) {
        NgocSiAiMemoryCommandType.LIST -> {
            val reply = if (memories.isEmpty()) {
                "Hiện chưa có ghi nhớ sở thích nhạc hoặc cách dùng NGỌC SĨ MUSIC. " +
                    "Bạn có thể nói “AI, hãy nhớ tôi thích nghe Bolero”."
            } else {
                "Mình đang lưu \${memories.size} ghi nhớ trên thiết bị: " +
                    memories.mapIndexed { index, item -> "\${index + 1}. $item" }.joinToString("; ") +
                    ". Bạn có thể nói “AI, quên hết ghi nhớ” để xóa."
            }
            NgocSiAiMemoryResult(memories, reply, shouldPersist = false)
        }

        NgocSiAiMemoryCommandType.CLEAR ->
            NgocSiAiMemoryResult(
                memories = emptyList(),
                reply = "Đã xóa toàn bộ ghi nhớ sở thích nhạc và cách dùng ứng dụng trên thiết bị.",
                shouldPersist = true
            )

        NgocSiAiMemoryCommandType.REMEMBER -> {
            val note = command.note.trim().take(NGOC_SI_AI_MAX_MEMORY_ITEM_CHARS)
            if (!isSafeNgocSiAiMemoryNote(note)) {
                NgocSiAiMemoryResult(
                    memories,
                    "Mình chỉ lưu ghi nhớ liên quan đến nhạc và cách sử dụng NGỌC SĨ MUSIC, " +
                        "không lưu mật khẩu, thông tin tài khoản hoặc dữ liệu nhạy cảm.",
                    shouldPersist = false
                )
            } else {
                val exists = memories.any {
                    normalizeNgocSiAiVoicePhrase(it) == normalizeNgocSiAiVoicePhrase(note)
                }
                val updated = if (exists) {
                    memories
                } else {
                    (memories + note).takeLast(NGOC_SI_AI_MAX_MEMORY_ITEMS)
                }
                val reply = if (exists) {
                    "Mình đã có ghi nhớ này rồi: “$note”."
                } else {
                    "Đã ghi nhớ trên thiết bị: “$note”. Ghi chú này sẽ được dùng làm ngữ cảnh " +
                        "khi bạn trò chuyện trực tuyến với Gemini. Nói “AI nhớ gì về tôi?” để xem " +
                        "hoặc “AI, quên hết ghi nhớ” để xóa."
                }
                NgocSiAiMemoryResult(updated, reply, shouldPersist = !exists)
            }
        }
    }
}

/** Formats only user-approved music/app preferences for Gemini's system context. */
internal fun buildNgocSiAiMemorySystemInstruction(memories: List<String>): String {
    val safeMemories = decodeNgocSiAiMemory(memories.joinToString("\n"))
    if (safeMemories.isEmpty()) return ""
    return "\nGhi nhớ sở thích nhạc và cách dùng ứng dụng do người dùng chủ động lưu trên thiết bị: " +
        safeMemories.joinToString("; ") +
        ". Chỉ sử dụng các ghi nhớ này khi liên quan; yêu cầu mới nhất của người dùng luôn được ưu tiên. " +
        "Không suy diễn chúng thành thông tin cá nhân khác và không lưu thêm điều gì nếu người dùng chưa yêu cầu."
}

/** Handles a local memory command; all writes remain in the app's private preferences. */
internal fun handleNgocSiAiMemoryCommand(
    userText: String,
    preferences: SharedPreferences
): String? {
    val command = parseNgocSiAiMemoryCommand(userText) ?: return null
    val current = decodeNgocSiAiMemory(
        preferences.getString(NGOC_SI_AI_LONG_TERM_MEMORY_KEY, null)
    )
    val result = applyNgocSiAiMemoryCommand(command, current)
    if (result.shouldPersist) {
        preferences.edit()
            .putString(NGOC_SI_AI_LONG_TERM_MEMORY_KEY, encodeNgocSiAiMemory(result.memories))
            .apply()
    }
    return result.reply
}
