package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Test

class NgocSiAiVoiceRecognitionRulesTest {
    @Test
    fun explicitVoiceControlStopDoesNotLookLikeOfflineRecognizerFailure() {
        assertFalse(
            shouldShowNgocSiWakeWordStoppedWarning(
                wasListening = true,
                stoppedByCommand = true
            )
        )
        assertTrue(
            shouldShowNgocSiWakeWordStoppedWarning(
                wasListening = true,
                stoppedByCommand = false
            )
        )
        assertFalse(
            shouldShowNgocSiWakeWordStoppedWarning(
                wasListening = false,
                stoppedByCommand = false
            )
        )
    }

    @Test
    fun prefersNativeOfflineRecognitionWhenAvailableAndVerificationIsOff() {
        assertEquals(
            NgocSiAiSpeechRecognitionPath.ANDROID_ON_DEVICE,
            selectNgocSiAiSpeechRecognitionPath(
                platformOfflineAvailable = true,
                voiceVerificationEnabled = false
            )
        )
    }

    @Test
    fun fallsBackToBundledVietnameseRecognizerWhenPlatformOfflineAsrIsUnavailable() {
        assertEquals(
            NgocSiAiSpeechRecognitionPath.BUNDLED_VIETNAMESE_OFFLINE,
            selectNgocSiAiSpeechRecognitionPath(
                platformOfflineAvailable = false,
                voiceVerificationEnabled = false
            )
        )
    }

    @Test
    fun voiceVerificationRouteContinuesUsingBundledLocalAudioPath() {
        assertEquals(
            NgocSiAiSpeechRecognitionPath.BUNDLED_VIETNAMESE_OFFLINE,
            selectNgocSiAiSpeechRecognitionPath(
                platformOfflineAvailable = true,
                voiceVerificationEnabled = true
            )
        )
    }
}
