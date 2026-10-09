package com.ngocsi.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivePlaybackRecoveryRulesTest {
    @Test
    fun allowsRetryOnlyForTheSameActiveDriveFile() {
        val uri = "https://www.googleapis.com/drive/v3/files/file123?alt=media&resourceKey=key"
        assertTrue(DrivePlaybackRecoveryRules.shouldReplaceActiveItem(uri, uri))
    }

    @Test
    fun doesNotReplaceAnItemSelectedWhileTokenRefreshWasRunning() {
        val failedUri = "https://www.googleapis.com/drive/v3/files/file123?alt=media"
        val selectedUri = "content://media/external/audio/media/99"
        assertFalse(DrivePlaybackRecoveryRules.shouldReplaceActiveItem(failedUri, selectedUri))
    }

    @Test
    fun rejectsBlankOrNonDriveUris() {
        assertFalse(DrivePlaybackRecoveryRules.shouldReplaceActiveItem("", ""))
        assertFalse(
            DrivePlaybackRecoveryRules.shouldReplaceActiveItem(
                "https://example.com/audio.mp3",
                "https://example.com/audio.mp3"
            )
        )
    }
}
