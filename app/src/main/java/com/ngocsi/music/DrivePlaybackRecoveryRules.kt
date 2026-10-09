package com.ngocsi.music

/**
 * Guards asynchronous Drive playback recovery from overwriting a newer selection.
 */
internal object DrivePlaybackRecoveryRules {
    private const val DRIVE_FILE_URI_PREFIX = "https://www.googleapis.com/drive/v3/files/"

    fun shouldReplaceActiveItem(failedUri: String, activeUri: String): Boolean =
        failedUri.isNotBlank() &&
            failedUri == activeUri &&
            failedUri.startsWith(DRIVE_FILE_URI_PREFIX)
}
