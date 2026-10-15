package com.ngocsi.music

/** Recognition routes for hands-free voice control; the bundled path stays fully offline. */
internal enum class NgocSiAiSpeechRecognitionPath {
    ANDROID_ON_DEVICE,
    BUNDLED_VIETNAMESE_OFFLINE
}

/**
 * Prefer Android's offline recognizer when it is available. Otherwise use the bundled
 * Vietnamese ASR; voice verification also uses the bundled capture/transcription path.
 */
internal fun selectNgocSiAiSpeechRecognitionPath(
    platformOfflineAvailable: Boolean,
    voiceVerificationEnabled: Boolean
): NgocSiAiSpeechRecognitionPath = if (
    voiceVerificationEnabled || !platformOfflineAvailable
) {
    NgocSiAiSpeechRecognitionPath.BUNDLED_VIETNAMESE_OFFLINE
} else {
    NgocSiAiSpeechRecognitionPath.ANDROID_ON_DEVICE
}

/** Show the recognizer warning only when listening stopped unexpectedly. */
internal fun shouldShowNgocSiWakeWordStoppedWarning(
    wasListening: Boolean,
    stoppedByCommand: Boolean
): Boolean = wasListening && !stoppedByCommand
