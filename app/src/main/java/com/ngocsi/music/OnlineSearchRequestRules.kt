package com.ngocsi.music

/**
 * Rejects stale online-search callbacks even when two consecutive searches use the same text.
 */
internal object OnlineSearchRequestRules {
    fun isCurrent(
        requestGeneration: Long,
        latestGeneration: Long,
        requestedQuery: String,
        currentQuery: String
    ): Boolean =
        requestGeneration == latestGeneration &&
            requestedQuery.isNotBlank() &&
            requestedQuery == currentQuery
}
