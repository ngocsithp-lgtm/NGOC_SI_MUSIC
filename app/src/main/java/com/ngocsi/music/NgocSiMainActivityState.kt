package com.ngocsi.music

import java.lang.ref.WeakReference

/**
 * Exposes whether MainActivity's inline YouTube control receiver is registered
 * in this process. The reference is cleared at onDestroy and is never persisted.
 */
internal object NgocSiMainActivityState {
    @Volatile
    private var currentActivity: WeakReference<MainActivity>? = null

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
