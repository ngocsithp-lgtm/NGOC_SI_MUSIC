package com.ngocsi.music

/**
 * Incremental speech chunks extracted from the cumulative Gemini stream.
 * nextCharacterOffset is an index into the same cumulative text supplied on the next call.
 */
internal data class NgocSiAiSpeechStreamChunkResult(
    val chunks: List<String>,
    val nextCharacterOffset: Int
)

/**
 * Extract complete sentences as soon as punctuation arrives. If Gemini emits a very long
 * sentence, split at a whitespace boundary so TTS can start without cutting a word.
 */
internal fun extractNgocSiAiSpeechStreamChunks(
    cumulativeText: String,
    alreadySpokenCharacters: Int,
    flushRemainder: Boolean = false,
    maxChunkCharacters: Int = 160
): NgocSiAiSpeechStreamChunkResult {
    require(maxChunkCharacters >= 40) { "maxChunkCharacters must be at least 40." }

    val chunks = mutableListOf<String>()
    var cursor = alreadySpokenCharacters.coerceIn(0, cumulativeText.length)

    while (cursor < cumulativeText.length) {
        var sentenceEnd = -1
        for (index in cursor until cumulativeText.length) {
            val character = cumulativeText[index]
            if (character !in charArrayOf('.', '!', '?', '\n')) continue

            // Do not split a decimal such as 3.14, or a trailing dot that may still
            // receive its next digit in the following stream event.
            if (character == '.') {
                val decimalPoint =
                    index > cursor &&
                        index + 1 < cumulativeText.length &&
                        cumulativeText[index - 1].isDigit() &&
                        cumulativeText[index + 1].isDigit()
                val trailingPartialDot =
                    index == cumulativeText.lastIndex && !flushRemainder
                if (decimalPoint || trailingPartialDot) continue
            }

            val atEnd = index == cumulativeText.lastIndex
            val followedBySpace = index + 1 < cumulativeText.length &&
                cumulativeText[index + 1].isWhitespace()
            if (atEnd || followedBySpace || character == '\n') {
                sentenceEnd = index + 1
                break
            }
        }

        if (sentenceEnd >= 0) {
            val chunk = cumulativeText.substring(cursor, sentenceEnd).trim()
            if (chunk.isNotBlank()) chunks += chunk
            cursor = sentenceEnd
            while (cursor < cumulativeText.length && cumulativeText[cursor].isWhitespace()) cursor++
            continue
        }

        val remaining = cumulativeText.length - cursor
        if (remaining > maxChunkCharacters) {
            val hardLimit = (cursor + maxChunkCharacters).coerceAtMost(cumulativeText.lastIndex)
            var splitAt = cumulativeText.lastIndexOf(' ', hardLimit)
            if (splitAt <= cursor) {
                splitAt = cumulativeText.indexOf(' ', hardLimit).takeIf { it > cursor } ?: -1
            }
            if (splitAt > cursor) {
                val chunk = cumulativeText.substring(cursor, splitAt).trim()
                if (chunk.isNotBlank()) chunks += chunk
                cursor = splitAt + 1
                while (cursor < cumulativeText.length && cumulativeText[cursor].isWhitespace()) cursor++
                continue
            }
        }

        if (flushRemainder) {
            val chunk = cumulativeText.substring(cursor).trim()
            if (chunk.isNotBlank()) chunks += chunk
            cursor = cumulativeText.length
        }
        break
    }

    return NgocSiAiSpeechStreamChunkResult(chunks, cursor)
}
