package com.ngocsi.music

import java.lang.ref.WeakReference

internal data class NgocSiAiYoutubeMediaContextSnapshot(
    val videoId: String,
    val title: String,
    val channel: String
)

/**
 * Exposes whether MainActivity's inline YouTube control receiver is registered
 * in this process. The reference is cleared at onDestroy and is never persisted.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object NgocSiMainActivityState {
    @Volatile
    private var currentActivity: WeakReference<MainActivity>? = null

    // Updated by MainActivity navigation; read by the wake-word service on every turn.
    @Volatile
    private var activeEntryContext: String? = null

    // Latest selected YouTube media is shared with the wake-word Service in this process.
    // It is deliberately volatile and in-memory: no playback metadata is persisted here.
    @Volatile
    private var activeYoutubeMediaContext: NgocSiAiYoutubeMediaContextSnapshot? = null

    fun updateEntryContext(rawContext: String?) {
        activeEntryContext = normalizeNgocSiAiEntryContext(rawContext)
    }

    fun entryContextOr(fallback: String? = null): String? =
        activeEntryContext ?: normalizeNgocSiAiEntryContext(fallback)

    fun updateYoutubeMediaContext(videoId: String?, title: String?, channel: String?) {
        val safeVideoId = normalizeNgocSiAiYoutubeVideoId(videoId)
        activeYoutubeMediaContext = safeVideoId?.let {
            NgocSiAiYoutubeMediaContextSnapshot(
                videoId = it,
                title = title.orEmpty().trim().take(160),
                channel = channel.orEmpty().trim().take(100)
            )
        }
    }

    fun youtubeMediaContextOrNull(): NgocSiAiYoutubeMediaContextSnapshot? =
        activeYoutubeMediaContext

    @Synchronized
    fun attach(activity: MainActivity) {
        currentActivity = WeakReference(activity)
    }

    @Synchronized
    fun detach(activity: MainActivity) {
        if (currentActivity?.get() === activity) {
            currentActivity = null
        }
    }

    fun hasLiveInstance(): Boolean {
        val activity = currentActivity?.get() ?: return false
        return !activity.isFinishing && !activity.isDestroyed
    }
}
