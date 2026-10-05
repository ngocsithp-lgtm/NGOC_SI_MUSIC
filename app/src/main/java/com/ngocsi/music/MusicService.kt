package com.ngocsi.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures

@UnstableApi
class MusicService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val widgetHandler = Handler(Looper.getMainLooper())
    private val driveRecoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var driveRecoveryInProgress = false
    private val prefs by lazy { getSharedPreferences("ngoc_si_music", MODE_PRIVATE) }
    private val widgetTicker = object : Runnable {
        override fun run() {
            savePlaybackPosition()
            broadcastWidget()
            if (player.isPlaying) widgetHandler.postDelayed(this, 5_000L)
        }
    }


    // PRO sleep timer watchdog: keep the timer active in the MediaSession
    // service so it can still stop playback when the screen is off and the UI
    // Activity is no longer in the foreground.
    private val sleepTimerRunnable = object : Runnable {
        override fun run() {
            val endAt = prefs.getLong("sleep_timer_end_at", 0L)
            if (endAt > 0L && System.currentTimeMillis() >= endAt) {
                // The timer is wall-clock based. Expire it even if playback was
                // paused, so a stale timer cannot survive indefinitely.
                player.pause()
                player.seekTo(0L)
                prefs.edit().remove("sleep_timer_end_at").apply()
                savePlaybackState()
                broadcastWidget()
            }
            widgetHandler.postDelayed(this, 1000L)
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val uri = prefs.getString("last_song_uri", null)
            val position = prefs.getLong("last_position", 0L).coerceAtLeast(0L)
            val queueUris = prefs.getString("queue_order", null)
                ?.split("\n")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
            val orderedUris = if (queueUris.isNotEmpty()) queueUris else listOfNotNull(uri)
            if (orderedUris.isEmpty()) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
                )
            }

            // During boot/resumption, the system only needs the current item
            // metadata when playback is not being started immediately.
            // Returning the full queue here is unnecessary and can increase
            // startup work, so keep the non-playback path lightweight.
            val activeIndex = uri?.let { orderedUris.indexOf(it) }?.takeIf { it >= 0 } ?: 0
            val resumptionUris = if (isForPlayback) orderedUris
            else listOf(orderedUris[activeIndex.coerceIn(0, orderedUris.lastIndex)])

            val metadataByUri = mutableMapOf<String, org.json.JSONObject>()
            prefs.getString("queue_metadata", null)?.let { raw ->
                runCatching {
                    val array = org.json.JSONArray(raw)
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val itemUri = item.optString("uri")
                        if (itemUri.isNotBlank()) metadataByUri[itemUri] = item
                    }
                }
            }

            val items = resumptionUris.map { itemUri ->
                val saved = metadataByUri[itemUri]
                val title = saved?.optString("title").orEmpty()
                    .ifBlank { itemUri.substringAfterLast('/').ifBlank { "NGỌC SĨ MUSIC" } }
                val artist = saved?.optString("artist").orEmpty()
                val artwork = saved?.optString("artworkUri").orEmpty()
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { if (artist.isNotBlank()) setArtist(artist) }
                    .apply { if (artwork.isNotBlank()) setArtworkUri(android.net.Uri.parse(artwork)) }
                MediaItem.Builder()
                    .setMediaId(itemUri)
                    .setUri(itemUri)
                    .setMediaMetadata(metadataBuilder.build())
                    .build()
            }

            val resumeIndex = if (isForPlayback) {
                activeIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
            } else 0
            if (isForPlayback) {
                player.setShuffleModeEnabled(prefs.getBoolean("shuffle", false))
                player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
                player.setPlaybackSpeed(
                    prefs.getFloat("playback_speed", 1.0f).coerceIn(0.5f, 2.0f)
                )
            }

            val startPosition = if (isForPlayback) position else androidx.media3.common.C.TIME_UNSET
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, resumeIndex, startPosition)
            )        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            savePlaybackPosition()
            broadcastWidget()
            widgetHandler.removeCallbacks(widgetTicker)
            if (isPlaying) widgetHandler.postDelayed(widgetTicker, 5_000L)
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            savePlaybackState()
            broadcastWidget()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            savePlaybackState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            savePlaybackPosition()
            broadcastWidget()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (isDriveAuthorizationError(error) && !driveRecoveryInProgress) {
                recoverDrivePlayback()
            }
            savePlaybackPosition()
            broadcastWidget()
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            savePlaybackState()
            broadcastWidget()
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            savePlaybackPosition()
            broadcastWidget()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            savePlaybackPosition()
            broadcastWidget()
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Keep the base HTTP client free of Google OAuth headers. The Bearer token
        // must only be attached to Google Drive REST media requests; attaching it
        // globally would leak the Drive credential to YouTube/Radio/other hosts.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("NGOC-SI-MUSIC/5.9 (Android)")

        // Resolve each request by URI so Drive gets a fresh OAuth token while all
        // other online sources remain completely unauthenticated.
        val driveAwareHttpFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val uri = dataSpec.uri.toString()
            if (uri.startsWith("https://www.googleapis.com/drive/v3/files/")) {
                val token = refreshDriveTokenBlocking()
                if (token.isNotBlank()) {
                    dataSpec.withRequestHeaders(
                        mapOf("Authorization" to "Bearer $token")
                    )
                } else {
                    dataSpec
                }
            } else {
                dataSpec
            }
        }

        // DefaultHttpDataSource only handles http/https. Drive files selected
        // through the Android/Google Drive document picker use content:// URIs.
        // Wrap the HTTP factory in DefaultDataSource so Media3 can resolve both
        // content:// (SAF/Drive provider) and https:// (online/Drive REST) media.
        val mediaDataSourceFactory = DefaultDataSource.Factory(this, driveAwareHttpFactory)

        // Separate local and streaming buffering so local/Drive files stay
        // responsive while network audio has a deeper rebuffer cushion.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForLocalPlayback(5_000, 50_000, 500, 1_000)
            .setBufferDurationsMsForStreaming(20_000, 90_000, 1_500, 4_000)
            .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(mediaDataSourceFactory))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )

        // Keep playback alive when the UI Activity is closed, while still
        // allowing Android to pause safely when audio output becomes noisy.
        player.setHandleAudioBecomingNoisy(true)

        player.addListener(playerListener)
        broadcastWidget()
        widgetHandler.post(sleepTimerRunnable)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ProMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(mediaSessionCallback)
            .build()

        broadcastWidget()
    }

    private fun isDriveAuthorizationError(error: androidx.media3.common.PlaybackException): Boolean {
        var cause: Throwable? = error
        repeat(8) {
            when (cause) {
                is HttpDataSource.InvalidResponseCodeException -> {
                    val code = (cause as HttpDataSource.InvalidResponseCodeException).responseCode
                    return code == 401 || code == 403
                }
            }
            cause = cause?.cause
        }
        return false
    }

    private fun recoverDrivePlayback() {
        val current = player.currentMediaItem ?: return
        val uri = current.localConfiguration?.uri?.toString().orEmpty()
        if (!uri.contains("googleapis.com/drive/v3/files/")) return

        val resumePosition = player.currentPosition.coerceAtLeast(0L)
        driveRecoveryInProgress = true
        driveRecoveryScope.launch {
            val token = refreshDriveTokenBlocking(forceRefresh = true)
            widgetHandler.post {
                try {
                    if (token.isBlank()) {
                        driveRecoveryInProgress = false
                        return@post
                    }
                    val wasPlaying = player.isPlaying
                    val replacement = current.buildUpon().build()
                    player.setMediaItem(replacement, resumePosition)
                    player.prepare()
                    if (wasPlaying) player.play()
                } finally {
                    driveRecoveryInProgress = false
                }
            }
        }
    }

    @Volatile
    private var cachedDriveToken: String = ""

    @Volatile
    private var cachedDriveTokenAtMs: Long = 0L

    private companion object {
        const val DRIVE_TOKEN_CACHE_MS = 45L * 60L * 1000L
    }

    private fun refreshDriveTokenBlocking(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh &&
            cachedDriveToken.isNotBlank() &&
            now - cachedDriveTokenAtMs < DRIVE_TOKEN_CACHE_MS
        ) {
            return cachedDriveToken
        }

        return runCatching {
            val manager = DriveOAuthManager(this)
            val token = kotlinx.coroutines.runBlocking {
                manager.accessToken()
            }.orEmpty()
            if (token.isNotBlank()) {
                cachedDriveToken = token
                cachedDriveTokenAtMs = now
            } else if (forceRefresh) {
                cachedDriveToken = ""
                cachedDriveTokenAtMs = 0L
            }
            token
        }.getOrElse { "" }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    private fun savePlaybackPosition() {
        val mediaItem = player.currentMediaItem ?: return
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: return

        prefs.edit()
            .putString("last_song_uri", uri)
            .putLong("last_position", player.currentPosition.coerceAtLeast(0L))
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .putFloat("playback_speed", player.playbackParameters.speed)
            .apply()
    }

    private var lastPersistedQueueSignature: String? = null

    private fun buildQueueSignature(): String {
        return buildString {
            for (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                val md = item.mediaMetadata
                append(item.mediaId)
                append('\u0000')
                append(item.localConfiguration?.uri?.toString().orEmpty())
                append('\u0000')
                append(md.title?.toString().orEmpty())
                append('\u0000')
                append(md.artist?.toString().orEmpty())
                append('\u0000')
                append(md.artworkUri?.toString().orEmpty())
                append('\n')
            }
        }
    }

    private fun saveQueueSnapshotIfChanged() {
        val signature = buildQueueSignature()
        if (signature == lastPersistedQueueSignature) return

        if (player.mediaItemCount > 0) {
            val queueUris = buildString {
                for (index in 0 until player.mediaItemCount) {
                    if (index > 0) append('\n')
                    append(player.getMediaItemAt(index).mediaId)
                }
            }
            val metadata = org.json.JSONArray()
            for (index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                val itemUri = item.localConfiguration?.uri?.toString().orEmpty()
                val md = item.mediaMetadata
                metadata.put(
                    org.json.JSONObject().apply {
                        put("uri", itemUri)
                        put("title", md.title?.toString().orEmpty())
                        put("artist", md.artist?.toString().orEmpty())
                        put("artworkUri", md.artworkUri?.toString().orEmpty())
                    }
                )
            }
            prefs.edit()
                .putString("queue_order", queueUris)
                .putString("queue_metadata", metadata.toString())
                .apply()
        } else {
            prefs.edit()
                .remove("queue_order")
                .remove("queue_metadata")
                .apply()
        }

        lastPersistedQueueSignature = signature
    }

    private fun savePlaybackState() {
        savePlaybackPosition()
        saveQueueSnapshotIfChanged()
    }

    private fun broadcastWidget() {
        sendBroadcast(
            Intent(this, MusicWidgetProvider::class.java).setAction(
                MusicWidgetProvider.ACTION_REFRESH
            )
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Persist the latest item and position before the task is removed.
        savePlaybackState()
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Persist once more before releasing Media3 resources.
        savePlaybackState()
        widgetHandler.removeCallbacks(widgetTicker)
        widgetHandler.removeCallbacks(sleepTimerRunnable)
        player.removeListener(playerListener)
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}