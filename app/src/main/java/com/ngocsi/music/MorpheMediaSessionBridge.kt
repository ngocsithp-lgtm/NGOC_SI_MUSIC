package com.ngocsi.music

import android.content.ComponentName
import android.content.Context
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.media.session.MediaButtonReceiver
import android.media.session.MediaSessionManager
import android.media.session.MediaController as PlatformMediaController

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
