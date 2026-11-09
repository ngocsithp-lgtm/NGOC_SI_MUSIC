package com.ngocsi.music

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveAudioMetadataRulesTest {
    @Test
    fun prefersEmbeddedTitleAndPerformerOverDriveFileName() {
        val result = resolveDriveAudioMetadata(
            fallbackFileName = "001 Anh Da Thay Long.mp3",
            embeddedTitle = "Anh Đã Thay Lòng",
            embeddedArtist = "Tuấn Vũ",
            embeddedAlbumArtist = "Nghệ sĩ khác"
        )

        assertEquals("Anh Đã Thay Lòng", result.title)
        assertEquals("Tuấn Vũ", result.artist)
    }

    @Test
    fun usesAlbumArtistWhenTrackArtistIsMissing() {
        val result = resolveDriveAudioMetadata(
            fallbackFileName = "001 Anh Da Thay Long.mp3",
            embeddedTitle = null,
            embeddedArtist = " ",
            embeddedAlbumArtist = "Tuấn Vũ"
        )

        assertEquals("001 Anh Da Thay Long", result.title)
        assertEquals("Tuấn Vũ", result.artist)
    }

    @Test
    fun doesNotInventArtistWhenEmbeddedTagsAreMissing() {
        val result = resolveDriveAudioMetadata(
            fallbackFileName = "001 Anh Da Thay Long.mp3",
            embeddedTitle = null,
            embeddedArtist = null,
            embeddedAlbumArtist = null
        )

        assertEquals("001 Anh Da Thay Long", result.title)
        assertEquals("Nghệ sĩ chưa xác định", result.artist)
    }
}
