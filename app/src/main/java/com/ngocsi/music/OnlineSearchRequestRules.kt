package com.ngocsi.music

/**
 * Rejects callbacks from an older request after a new search or query edit invalidates it.
 */
internal object OnlineSearchRequestRules {
    fun isCurrent(requestGeneration: Long, latestGeneration: Long): Boolean =
        requestGeneration == latestGeneration
}
