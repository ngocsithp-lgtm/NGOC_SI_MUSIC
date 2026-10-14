package com.ngocsi.music

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiVoiceProfileTest {

    @Test
    fun extractFeaturesRejectsSilenceAndTooShortAudio() {
        assertNull(NgocSiVoiceProfile.extractFeatures(ByteArray(64)))
        assertNull(NgocSiVoiceProfile.extractFeatures(ByteArray(8_192)))
    }

    @Test
    fun identicalLocalVoiceFeaturesMatchAndEnrollmentNeedsThreeSamples() {
        val pcm = syntheticSpeechLikeAudio()
        val features = NgocSiVoiceProfile.extractFeatures(pcm)
        assertNotNull(features)
        val vector = requireNotNull(features)

        assertTrue(NgocSiVoiceProfile.matches(vector, vector))
        assertNotNull(NgocSiVoiceProfile.combine(listOf(vector, vector, vector)))
        assertNull(NgocSiVoiceProfile.combine(listOf(vector, vector)))
    }

    @Test
    fun unrelatedSyntheticToneDoesNotMatchEnrolledFingerprint() {
        val enrolled = requireNotNull(NgocSiVoiceProfile.extractFeatures(syntheticSpeechLikeAudio(180.0)))
        val other = requireNotNull(NgocSiVoiceProfile.extractFeatures(syntheticSpeechLikeAudio(900.0)))
        assertFalse(NgocSiVoiceProfile.matches(enrolled, other))
    }

    private fun syntheticSpeechLikeAudio(fundamental: Double = 180.0): ByteArray {
        val sampleRate = 16_000.0
        val count = 8_192
        val buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.BIG_ENDIAN)
        for (i in 0 until count) {
            val t = i / sampleRate
            val envelope = 0.55 + 0.35 * sin(2.0 * PI * 2.3 * t)
            val voice = sin(2.0 * PI * fundamental * t) +
                0.47 * sin(2.0 * PI * fundamental * 2.0 * t) +
                0.19 * sin(2.0 * PI * fundamental * 3.0 * t)
            buffer.putShort((voice * envelope * 8_000.0).toInt().coerceIn(-32_000, 32_000).toShort())
        }
        return buffer.array()
    }
}
