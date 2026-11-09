package com.ngocsi.music

internal data class ResolvedDriveAudioMetadata(
    val title: String,
    val artist: String,
    val genre: String?
)

/**
 * Resolve display metadata without confusing the storage provider with the performer.
 * Embedded artist tags take priority; album artist is a secondary metadata fallback.
 */
internal fun resolveDriveAudioMetadata(
    fallbackFileName: String,
    embeddedTitle: String?,
    embeddedArtist: String?,
    embeddedAlbumArtist: String?,
    embeddedGenre: String? = null
): ResolvedDriveAudioMetadata {
    val fallbackTitle = fallbackFileName.substringAfterLast('/')
        .substringBeforeLast('.')
        .trim()
        .ifBlank { "Nhạc Google Drive" }
    val title = embeddedTitle?.trim()?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val artist = embeddedArtist?.trim()?.takeIf { it.isNotBlank() }
        ?: embeddedAlbumArtist?.trim()?.takeIf { it.isNotBlank() }
        ?: "Nghệ sĩ chưa xác định"
    val genre = embeddedGenre?.trim()?.takeIf { it.isNotBlank() }
    return ResolvedDriveAudioMetadata(title = title, artist = artist, genre = genre)
}
