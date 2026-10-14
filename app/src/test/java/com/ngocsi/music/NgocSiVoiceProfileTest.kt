package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NgocSiVoiceProfileTest {

    @Test
    fun speakerEmbeddingMustHaveAtLeastThreeEnrolmentSamples() {
        val owner = unitVector(0)
        assertNull(NgocSiVoiceProfile.combine(listOf(owner, owner)))
        assertNotNull(NgocSiVoiceProfile.combine(listOf(owner, owner, owner)))
    }

    @Test
    fun identicalSpeakerEmbeddingsAreAccepted() {
        val enrolled = unitVector(0)
        val candidate = unitVector(0)
        assertTrue(NgocSiVoiceProfile.matches(enrolled, candidate))
    }

    @Test
    fun orthogonalSpeakerEmbeddingsAreRejected() {
        val enrolled = unitVector(0)
        val other = unitVector(1)
        assertFalse(NgocSiVoiceProfile.matches(enrolled, other))
    }

    @Test
    fun multiTemplateVerificationAcceptsAnyEnrolledSpeakingStyle() {
        val templates = listOf(unitVector(0), unitVector(5), unitVector(9))
        assertTrue(NgocSiVoiceProfile.matches(templates, unitVector(9)))
        assertFalse(NgocSiVoiceProfile.matches(templates, unitVector(190)))
    }

    @Test
    fun multiTemplateVerificationRejectsEmptyOrMalformedProfiles() {
        assertFalse(NgocSiVoiceProfile.matches(emptyList(), unitVector(0)))
        val malformed = unitVector(0).also { it[12] = Float.NaN }
        assertFalse(NgocSiVoiceProfile.matches(listOf(malformed), unitVector(0)))
    }

    @Test
    fun combinedProfileIsNormalizedAndRejectsInconsistentDimensions() {
        val owner = unitVector(0)
        val combined = requireNotNull(
            NgocSiVoiceProfile.combine(listOf(owner, owner, owner))
        )
        var normSquared = 0.0
        for (value in combined) normSquared += value.toDouble() * value
        assertEquals(1.0, normSquared, 0.0001)

        val wrongSize = FloatArray(96)
        wrongSize[0] = 1f
        assertNull(NgocSiVoiceProfile.combine(listOf(owner, owner, wrongSize)))
    }

    @Test
    fun malformedAndNonFiniteEmbeddingsAreNeverAccepted() {
        val owner = unitVector(0)
        assertFalse(NgocSiVoiceProfile.matches(owner, FloatArray(32)))
        val malformed = unitVector(0)
        malformed[3] = Float.NaN
        assertFalse(NgocSiVoiceProfile.matches(owner, malformed))
    }

    private fun unitVector(index: Int): FloatArray =
        FloatArray(192).also { it[index] = 1f }
}
