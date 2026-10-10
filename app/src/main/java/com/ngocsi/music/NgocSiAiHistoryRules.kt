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
    val selected = messages.takeLast(NGOC_SI_AI_MAX_HISTORY_MESSAGES)
    if (selected.any { it.role != "user" && it.role != "assistant" }) {
        throw IllegalArgumentException("Loại tin nhắn không được hỗ trợ.")
    }
    if (selected.isEmpty() || selected.last().role != "user") {
        throw IllegalArgumentException("Tin nhắn chưa hợp lệ. Hãy gửi một câu hỏi mới.")
    }

    var history = selected.mapIndexed { index, message ->
        val content = message.content.trim()
        if (content.isBlank()) {
            throw IllegalArgumentException("Tin nhắn không được để trống.")
        }
        if (index == selected.lastIndex && content.length > NGOC_SI_AI_MAX_MESSAGE_CHARS) {
            throw IllegalArgumentException("Mỗi tin nhắn phải có tối đa 2.000 ký tự.")
        }
        // Earlier assistant answers can be longer than the server's input limit.
        // Clip historical context, never the newest user message.
        message.copy(content = content.take(NGOC_SI_AI_MAX_MESSAGE_CHARS))
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
