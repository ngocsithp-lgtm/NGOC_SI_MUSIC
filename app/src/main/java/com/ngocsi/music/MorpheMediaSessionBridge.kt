package com.ngocsi.music

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.media.session.MediaSessionManager

/**
 * Minimal bridge to Morphe's standard Android media session.
 *
 * This does not patch or embed YouTube/Morphe. It can only control Morphe when
 * Morphe publishes a normal Android MediaSession and notification access is granted.
 */
internal object MorpheMediaSessionBridge {
    private const val MORPHE_PACKAGE = "app.morphe.android.youtube"

    fun tryControl(context: Context, action: String): String? {
        if (action !in setOf("play", "pause", "next", "previous")) return null

        val sessions = try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            manager.getActiveSessions(
                ComponentName(context, MorpheMediaSessionNotificationListener::class.java)
            )
        } catch (_: SecurityException) {
            return "Để điều khiển YouTube Morphe bằng giọng nói, hãy bật quyền Truy cập thông báo cho NGỌC SĨ AI Preview rồi thử lại."
        } catch (_: Exception) {
            return null
        }

        val session = sessions.firstOrNull { it.packageName == MORPHE_PACKAGE } ?: return null
        // Do not swallow commands the active Morphe session does not advertise.
        // In that case the caller can fall back to the embedded YouTube player.
        val supportedActions = session.playbackState?.actions ?: 0L
        val supportsAction = when (action) {
            "play" -> (supportedActions and (
                android.media.session.PlaybackState.ACTION_PLAY or
                    android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                )) != 0L
            "pause" -> (supportedActions and (
                android.media.session.PlaybackState.ACTION_PAUSE or
                    android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                )) != 0L
            "next" -> (supportedActions and android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
            "previous" -> (supportedActions and android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
            else -> false
        }
        if (!supportsAction) return null

        return try {
            when (action) {
                "play" -> {
                    session.transportControls.play()
                    "Đã gửi lệnh phát tới phiên YouTube Morphe."
                }
                "pause" -> {
                    session.transportControls.pause()
                    "Đã gửi lệnh tạm dừng tới phiên YouTube Morphe."
                }
                "next" -> {
                    session.transportControls.skipToNext()
                    "Đã gửi lệnh chuyển tiếp tới phiên YouTube Morphe."
                }
                "previous" -> {
                    session.transportControls.skipToPrevious()
                    "Đã gửi lệnh quay lại tới phiên YouTube Morphe."
                }
                else -> null
            }
        } catch (_: Exception) {
            "Đã tìm thấy phiên Morphe nhưng gửi lệnh không thành công. Hãy kiểm tra Morphe đang phát rồi thử lại."
        }
    }
}

/** Android uses this service component to authorize active-session queries. */
class MorpheMediaSessionNotificationListener : NotificationListenerService()
