package com.ngocsi.music

/**
 * Prevents delayed Drive OAuth responses from overriding a newer playback choice.
 */
internal object PlaybackIntentRules {
    fun shouldResume(
        requestGeneration: Long,
        currentGeneration: Long,
        requestedUri: String,
        activeUri: String,
        requestedSource: String,
        activeSource: String
    ): Boolean =
        requestGeneration == currentGeneration &&
            requestedUri.isNotBlank() &&
            requestedUri == activeUri &&
            requestedSource.isNotBlank() &&
            requestedSource == activeSource
}
