package com.ngocsi.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

class MusicWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.ngocsi.music.widget.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.ngocsi.music.widget.PREVIOUS"
        const val ACTION_NEXT = "com.ngocsi.music.widget.NEXT"
        const val ACTION_OPEN = "com.ngocsi.music.widget.OPEN"
        const val ACTION_REFRESH = "com.ngocsi.music.widget.REFRESH"

        fun refresh(context: Context) {
            context.sendBroadcast(Intent(context, MusicWidgetProvider::class.java).setAction(ACTION_REFRESH))
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE, ACTION_PREVIOUS, ACTION_NEXT -> handlePlayerAction(context, intent.action!!)
            ACTION_OPEN -> {
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(open)
            }
            ACTION_REFRESH -> updateAll(context)
        }
    }

    private fun handlePlayerAction(context: Context, action: String) {
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                when (action) {
                    ACTION_PLAY_PAUSE -> if (controller.isPlaying) controller.pause() else controller.play()
                    ACTION_PREVIOUS -> {
                        // Match the main player: restart the current track when
                        // already a few seconds in; otherwise go to the previous item.
                        if (controller.currentPosition > 3_000L) {
                            controller.seekTo(0L)
                        } else {
                            controller.seekToPreviousMediaItem()
                        }
                        controller.play()
                    }
                    ACTION_NEXT -> {
                        controller.seekToNextMediaItem()
                        controller.play()
                    }
                }
                updateAll(context, controller)
                controller.release()
            } catch (_: Exception) {
                updateAll(context)
            }
        }, MoreExecutors.directExecutor())
    }

    private fun updateAll(context: Context, existingController: MediaController? = null) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, MusicWidgetProvider::class.java))
        if (ids.isEmpty()) return

        if (existingController != null) {
            updateViews(context, manager, ids, existingController)
            return
        }

        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val controller = future.get()
                updateViews(context, manager, ids, controller)
                controller.release()
            } catch (_: Exception) {
                ids.forEach { id -> updateFallback(context, manager, id) }
            }
        }, MoreExecutors.directExecutor())
    }

    private fun updateViews(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        controller: MediaController
    ) {
        val item = controller.currentMediaItem
        val metadata = item?.mediaMetadata
        val title = metadata?.title?.toString()?.ifBlank { null } ?: "Chưa chọn bài hát"
        val artist = metadata?.artist?.toString()?.ifBlank { null } ?: "NGỌC SĨ MUSIC"
        val playIcon = if (controller.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val duration = controller.duration.coerceAtLeast(0L)
        val position = controller.currentPosition.coerceIn(0L, duration.coerceAtLeast(1L))
        val progress = if (duration > 0L) ((position * 1000L) / duration).toInt().coerceIn(0, 1000) else 0
        val time = formatTime(position)

        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.music_widget)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)
            views.setImageViewResource(R.id.widget_play, playIcon)
            views.setProgressBar(R.id.widget_progress, 1000, progress, false)
            views.setTextViewText(R.id.widget_time, time)
            views.setOnClickPendingIntent(R.id.widget_previous, broadcastPending(context, ACTION_PREVIOUS, id))
            views.setOnClickPendingIntent(R.id.widget_play, broadcastPending(context, ACTION_PLAY_PAUSE, id))
            views.setOnClickPendingIntent(R.id.widget_next, broadcastPending(context, ACTION_NEXT, id))
            views.setOnClickPendingIntent(R.id.widget_root, broadcastPending(context, ACTION_OPEN, id))

            val artworkUri = metadata?.artworkUri
            if (artworkUri != null) {
                views.setImageViewUri(R.id.widget_art, artworkUri)
            } else {
                views.setImageViewResource(R.id.widget_art, android.R.drawable.ic_media_play)
            }
            manager.updateAppWidget(id, views)
        }
    }

    private fun updateFallback(context: Context, manager: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.music_widget)
        views.setTextViewText(R.id.widget_title, "NGỌC SĨ MUSIC")
        views.setTextViewText(R.id.widget_artist, "Sẵn sàng phát nhạc")
        views.setImageViewResource(R.id.widget_play, android.R.drawable.ic_media_play)
        views.setImageViewResource(R.id.widget_art, android.R.drawable.ic_media_play)
        views.setProgressBar(R.id.widget_progress, 1000, 0, false)
        views.setTextViewText(R.id.widget_time, "00:00")
        views.setOnClickPendingIntent(R.id.widget_previous, broadcastPending(context, ACTION_PREVIOUS, id))
        views.setOnClickPendingIntent(R.id.widget_play, broadcastPending(context, ACTION_PLAY_PAUSE, id))
        views.setOnClickPendingIntent(R.id.widget_next, broadcastPending(context, ACTION_NEXT, id))
        views.setOnClickPendingIntent(R.id.widget_root, broadcastPending(context, ACTION_OPEN, id))
        manager.updateAppWidget(id, views)
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
        return String.format("%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun broadcastPending(context: Context, action: String, id: Int): PendingIntent {
        val intent = Intent(context, MusicWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            id + action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
