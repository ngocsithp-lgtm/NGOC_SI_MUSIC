package com.ngocsi.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIntentRulesTest {
    @Test
    fun allowsTheSameCurrentDriveTrackAfterAuthorization() {
        assertTrue(
            PlaybackIntentRules.shouldResume(
                requestGeneration = 4L,
                currentGeneration = 4L,
                requestedUri = "https://www.googleapis.com/drive/v3/files/abc?alt=media",
                activeUri = "https://www.googleapis.com/drive/v3/files/abc?alt=media",
                requestedSource = "Google Drive • Music",
                activeSource = "Google Drive • Music"
            )
        )
    }

    @Test
    fun rejectsAnOlderAuthorizationAfterAnotherTrackWasChosen() {
        assertFalse(
            PlaybackIntentRules.shouldResume(
                requestGeneration = 4L,
                currentGeneration = 5L,
                requestedUri = "https://www.googleapis.com/drive/v3/files/abc?alt=media",
                activeUri = "https://www.googleapis.com/drive/v3/files/abc?alt=media",
                requestedSource = "Google Drive • Music",
                activeSource = "Google Drive • Music"
            )
        )
    }

    @Test
    fun rejectsWhenTheTargetAtTheSavedIndexChanged() {
        assertFalse(
            PlaybackIntentRules.shouldResume(
                requestGeneration = 4L,
                currentGeneration = 4L,
                requestedUri = "https://www.googleapis.com/drive/v3/files/abc?alt=media",
                activeUri = "content://media/external/audio/media/7",
                requestedSource = "Google Drive • Music",
                activeSource = "Thiết bị"
            )
        )
    }

    @Test
    fun rejectsBlankOrDifferentSources() {
        assertFalse(
            PlaybackIntentRules.shouldResume(
                requestGeneration = 2L,
                currentGeneration = 2L,
                requestedUri = "content://media/1",
                activeUri = "content://media/1",
                requestedSource = "",
                activeSource = "Thiết bị"
            )
        )
        assertFalse(
            PlaybackIntentRules.shouldResume(
                requestGeneration = 2L,
                currentGeneration = 2L,
                requestedUri = "content://media/1",
                activeUri = "content://media/1",
                requestedSource = "Google Drive • A",
                activeSource = "Google Drive • B"
            )
        )
    }
}
