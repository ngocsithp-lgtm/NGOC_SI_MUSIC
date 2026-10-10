package com.ngocsi.music

internal const val NGOC_SI_AI_MAX_HISTORY_MESSAGES = 8
internal const val NGOC_SI_AI_MAX_MESSAGE_CHARS = 2_000
internal const val NGOC_SI_AI_MAX_TOTAL_CHARS = 8_000

/**
 * Validates the messages for a Gemini request and trims the oldest complete turns
 * until the payload fits the server's total-character budget.
 *
 * The newest user message is always retained. Older context is dropped in pairs so
 * trimming does not normally leave an assistant message at the start of the history.
 */
internal fun normalizeNgocSiAiHistory(
    messages: List<NgocSiAiMessage>
): List<NgocSiAiMessage> {
    var history = messages.takeLast(NGOC_SI_AI_MAX_HISTORY_MESSAGES).map { message ->
        if (message.role != "user" && message.role != "assistant") {
            throw IllegalArgumentException("Loại tin nhắn không được hỗ trợ.")
        }
        val content = message.content.trim()
        if (content.length !in 1..NGOC_SI_AI_MAX_MESSAGE_CHARS) {
            throw IllegalArgumentException("Mỗi tin nhắn phải có từ 1 đến 2.000 ký tự.")
        }
        message.copy(content = content)
    }

    if (history.firstOrNull()?.role == "assistant") {
        history = history.drop(1)
    }
    if (history.isEmpty() || history.last().role != "user") {
        throw IllegalArgumentException("Tin nhắn chưa hợp lệ. Hãy gửi một câu hỏi mới.")
    }

    while (history.sumOf { it.content.length } > NGOC_SI_AI_MAX_TOTAL_CHARS && history.size > 1) {
        history = history.drop(1)
        if (history.firstOrNull()?.role == "assistant") {
            history = history.drop(1)
        }
    }

    if (history.isEmpty() || history.last().role != "user") {
        throw IllegalArgumentException("Tin nhắn chưa hợp lệ. Hãy gửi một câu hỏi mới.")
    }
    if (history.sumOf { it.content.length } > NGOC_SI_AI_MAX_TOTAL_CHARS) {
        throw IllegalArgumentException("Lịch sử trò chuyện quá dài. Hãy bắt đầu câu hỏi ngắn hơn.")
    }
    return history
}
