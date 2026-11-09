package com.ngocsi.music

internal data class ResolvedDriveAudioMetadata(
    val title: String,
    val artist: String
)

/**
 * Resolve display metadata without confusing the storage provider with the performer.
 * Embedded artist tags take priority; album artist is a secondary metadata fallback.
 */
internal fun resolveDriveAudioMetadata(
    fallbackFileName: String,
    embeddedTitle: String?,
    embeddedArtist: String?,
    embeddedAlbumArtist: String?
): ResolvedDriveAudioMetadata {
    val fallbackTitle = fallbackFileName.substringAfterLast('/')
        .substringBeforeLast('.')
        .trim()
        .ifBlank { "Nhạc Google Drive" }
    val title = embeddedTitle?.trim()?.takeIf { it.isNotBlank() } ?: fallbackTitle
    val artist = embeddedArtist?.trim()?.takeIf { it.isNotBlank() }
        ?: embeddedAlbumArtist?.trim()?.takeIf { it.isNotBlank() }
        ?: "Nghệ sĩ chưa xác định"
    return ResolvedDriveAudioMetadata(title = title, artist = artist)
}
