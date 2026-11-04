package com.ngocsi.music

import android.media.AudioManager
import androidx.media3.common.Player

internal data class NgocSiAiTrackCandidate(
    val title: String,
    val artist: String
)

/** Exact title matches win over earlier partial-title or artist matches. */
internal fun selectNgocSiAiTrackMatchIndex(
    candidates: List<NgocSiAiTrackCandidate>,
    query: String
): Int? {
    val normalizedQuery = normalizeNgocSiAiVoicePhrase(query)
    if (normalizedQuery.isBlank()) return null
    val exactTitle = candidates.indexOfFirst { candidate ->
        normalizeNgocSiAiVoicePhrase(candidate.title) == normalizedQuery
    }
    if (exactTitle >= 0) return exactTitle
    return candidates.indexOfFirst { candidate ->
        normalizeNgocSiAiVoicePhrase(candidate.title).contains(normalizedQuery) ||
            normalizeNgocSiAiVoicePhrase(candidate.artist).contains(normalizedQuery)
    }.takeIf { it >= 0 }
}

internal fun findNgocSiAiTrackMatchIndex(player: Player, query: String): Int? {
    if (query.isBlank() || player.mediaItemCount <= 0) return null
    val candidates = (0 until player.mediaItemCount).map { index ->
        val metadata = player.getMediaItemAt(index).mediaMetadata
        NgocSiAiTrackCandidate(
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty()
        )
    }
    return selectNgocSiAiTrackMatchIndex(candidates, query)
}

/**
 * One Media3 executor for typed chat and hands-free voice. Navigation, Android volume,
 * STOP target selection and YouTube WebView controls remain in their lifecycle-aware hosts.
 * Returns null only for commands owned by one of those host-specific paths.
 */
internal fun executeNgocSiAiMediaCommand(
    player: Player,
    command: NgocSiAiLocalCommand,
    originalText: String
): String? = runCatching {
    when (command) {
        NgocSiAiLocalCommand.PLAY -> {
            if (player.mediaItemCount <= 0) {
                "Hàng đợi đang trống. Hãy mở Thư viện và chọn bài nhạc trước."
            } else {
                if (player.currentMediaItem == null) {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
                player.play()
                "Đã gửi lệnh phát nhạc đến trình phát."
            }
        }
        NgocSiAiLocalCommand.PLAY_SECOND -> {
            if (player.mediaItemCount > 1) {
                player.seekTo(1, 0L)
                player.play()
                "Đang phát bài thứ hai trong hàng đợi."
            } else "Hàng đợi hiện tại chưa có bài thứ hai."
        }
        NgocSiAiLocalCommand.PAUSE -> {
            player.pause()
            "Đã gửi lệnh tạm dừng nhạc."
        }
        NgocSiAiLocalCommand.NEXT -> {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                "Đang chuyển sang bài tiếp theo."
            } else "Không có bài tiếp theo trong hàng đợi hiện tại."
        }
        NgocSiAiLocalCommand.PREVIOUS -> {
            if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
                "Đang chuyển về bài trước."
            } else "Không có bài trước trong hàng đợi hiện tại."
        }
        NgocSiAiLocalCommand.SET_PLAYBACK_SPEED -> {
            val speed = extractNgocSiAiPlaybackSpeed(originalText)
                ?: return@runCatching "Hãy chọn tốc độ 0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 1.75x hoặc 2.0x."
            player.setPlaybackSpeed(speed)
            "Đã đặt tốc độ phát ${speed}x."
        }
        NgocSiAiLocalCommand.SEEK_FORWARD_10 -> {
            val target = (player.currentPosition + 10_000L).coerceAtLeast(0L)
                .let { position -> player.duration.takeIf { it > 0L }?.let(position::coerceAtMost) ?: position }
            player.seekTo(target)
            "Đã tua tiến 10 giây."
        }
        NgocSiAiLocalCommand.SEEK_BACK_10 -> {
            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
            "Đã tua lùi 10 giây."
        }
        NgocSiAiLocalCommand.SEEK_FORWARD_CUSTOM,
        NgocSiAiLocalCommand.SEEK_BACK_CUSTOM -> {
            val request = extractNgocSiAiSeekRequest(originalText)
                ?: return@runCatching "Hãy nói rõ thời lượng tua từ 1 đến 120 giây hoặc 1 đến 2 phút."
            if (request.youtubeExplicit) {
                return@runCatching "Lệnh tua YouTube phải được chuyển đến trình phát YouTube."
            }
            val directionMatches = when (command) {
                NgocSiAiLocalCommand.SEEK_FORWARD_CUSTOM -> request.deltaMs > 0L
                NgocSiAiLocalCommand.SEEK_BACK_CUSTOM -> request.deltaMs < 0L
                else -> false
            }
            if (!directionMatches) return@runCatching "Hướng tua chưa khớp với lệnh. Chưa thay đổi vị trí phát."
            val duration = player.duration.takeIf { it > 0L }
            val target = (player.currentPosition + request.deltaMs).coerceAtLeast(0L)
                .let { if (duration == null) it else it.coerceAtMost(duration) }
            player.seekTo(target)
            val seconds = kotlin.math.abs(request.deltaMs) / 1_000L
            val direction = if (request.deltaMs > 0L) "tiến" else "lùi"
            "Đã tua $direction $seconds giây."
        }
        NgocSiAiLocalCommand.CURRENT_TRACK -> {
            val item = player.currentMediaItem
            val title = item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Chưa rõ tên bài hát"
            val artist = item?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() }
            val status = when {
                player.isPlaying -> "Đang phát"
                player.playbackState == Player.STATE_BUFFERING && player.playWhenReady -> "Đang tải"
                player.playbackState == Player.STATE_ENDED -> "Đã phát hết"
                else -> "Đang tạm dừng"
            }
            if (artist == null) "$status: $title" else "$status: $title — $artist"
        }
        NgocSiAiLocalCommand.SHUFFLE_ON -> {
            player.shuffleModeEnabled = true
            "Đã bật phát ngẫu nhiên cho hàng đợi hiện tại."
        }
        NgocSiAiLocalCommand.SHUFFLE_OFF -> {
            player.shuffleModeEnabled = false
            "Đã tắt phát ngẫu nhiên."
        }
        NgocSiAiLocalCommand.REPEAT_OFF -> {
            player.repeatMode = Player.REPEAT_MODE_OFF
            "Đã tắt chế độ lặp."
        }
        NgocSiAiLocalCommand.REPEAT_ALL -> {
            player.repeatMode = Player.REPEAT_MODE_ALL
            "Đã bật lặp toàn bộ hàng đợi."
        }
        NgocSiAiLocalCommand.REPEAT_ONE -> {
            player.repeatMode = Player.REPEAT_MODE_ONE
            "Đã bật lặp bài hiện tại."
        }
        else -> null
    }
}.getOrElse {
    "Không thể thực hiện lệnh Media3 lúc này. Ứng dụng chưa xác nhận thao tác hoàn tất."
}

/** Maps only the four explicit, allowlisted media-volume commands. */
internal fun ngocSiAiVolumeAdjustment(command: NgocSiAiLocalCommand): Int? = when (command) {
    NgocSiAiLocalCommand.VOLUME_UP -> AudioManager.ADJUST_RAISE
    NgocSiAiLocalCommand.VOLUME_DOWN -> AudioManager.ADJUST_LOWER
    NgocSiAiLocalCommand.MUTE -> AudioManager.ADJUST_MUTE
    NgocSiAiLocalCommand.UNMUTE -> AudioManager.ADJUST_UNMUTE
    else -> null
}

/**
 * Executes Android media-volume commands identically from chat and hands-free voice.
 * Null means this command is not an Android system-volume operation.
 */
internal fun executeNgocSiAiSystemVolumeCommand(
    audioManager: AudioManager?,
    command: NgocSiAiLocalCommand
): String? {
    val adjustment = ngocSiAiVolumeAdjustment(command) ?: return null
    if (audioManager == null) return "Không truy cập được điều khiển âm lượng của điện thoại."
    return runCatching {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            adjustment,
            AudioManager.FLAG_SHOW_UI
        )
        when (command) {
            NgocSiAiLocalCommand.VOLUME_UP -> "Đã gửi lệnh tăng âm lượng media."
            NgocSiAiLocalCommand.VOLUME_DOWN -> "Đã gửi lệnh giảm âm lượng media."
            NgocSiAiLocalCommand.MUTE -> "Đã gửi lệnh tắt tiếng media."
            NgocSiAiLocalCommand.UNMUTE -> "Đã gửi lệnh bật tiếng media."
            else -> "Đã gửi lệnh điều chỉnh âm lượng."
        }
    }.getOrElse {
        "Không thể điều chỉnh âm lượng media trên thiết bị này."
    }
}
