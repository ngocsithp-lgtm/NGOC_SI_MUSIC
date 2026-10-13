package com.ngocsi.music

/**
 * Converts Markdown-formatted assistant replies into natural text for Android TextToSpeech.
 * The chat UI keeps the original answer; only spoken output is normalized.
 */
internal object NgocSiAiSpeechTextRules {
    private val markdownImage = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val markdownLink = Regex("""\[([^\]]+)]\((?:[^()]|\([^()]*\))*\)""")
    private val htmlTag = Regex("""</?[A-Za-z][^>]*>""")
    private val horizontalRule = Regex("""(?m)^[ \t]*(?:-{3,}|\*{3,}|_{3,})[ \t]*$""")
    private val headingPrefix = Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]*""")
    private val quotePrefix = Regex("""(?m)^[ \t]{0,3}>[ \t]?""")
    private val listPrefix = Regex("""(?m)^[ \t]*(?:[-+*•]|[0-9]+[.)])[ \t]+""")
    private val markdownMarkers = Regex("""[*_~`]+""")
    private val diagnosticCode = Regex("""(?iu)\s*(?:mã(?:\s+chẩn\s+đoán)?|code)\s*:\s*[A-Z][A-Z0-9_-]{2,}[.!?]?""")
    private val lineBreaks = Regex("""[ \t]*\n+[ \t]*""")
    private val repeatedPunctuation = Regex("""([.!?])(?:[ \t]*[.!?]){1,}""")
    private val spacesBeforePunctuation = Regex("""\s+([,;:!?])""")
    private val repeatedPeriods = Regex("""(?:[ \t]*\.[ \t]*){2,}""")

    fun cleanForSpeech(input: String): String {
        if (input.isBlank()) return ""

        var text = input.replace("\r\n", "\n").replace('\r', '\n')
        text = markdownImage.replace(text, "$1")
        text = markdownLink.replace(text, "$1")
        text = htmlTag.replace(text, "")
        text = horizontalRule.replace(text, " ")
        text = headingPrefix.replace(text, "")
        text = quotePrefix.replace(text, "")
        text = listPrefix.replace(text, "")
        // Strip visible Markdown delimiters so TTS never says "star star" for bold text.
        text = markdownMarkers.replace(text, "")
        // Keep diagnostic codes visible in chat, but never make TextToSpeech read them aloud.
        text = diagnosticCode.replace(text, "")
        text = text.replace("|", " ")
        text = lineBreaks.replace(text, ". ")
        text = text.replace(Regex("""[ \t]{2,}"""), " ")
        text = spacesBeforePunctuation.replace(text, "$1")
        text = repeatedPunctuation.replace(text, "$1")
        text = repeatedPeriods.replace(text, ". ")
        return text.trim()
    }
}
