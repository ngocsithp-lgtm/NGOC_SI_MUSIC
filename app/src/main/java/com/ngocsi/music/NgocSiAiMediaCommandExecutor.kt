package com.ngocsi.music

import android.media.AudioManager
import androidx.media3.common.Player

internal data class NgocSiAiTrackCandidate(
    val title: String,
    val artist: String
)

private val NGOC_SI_AI_KNOWN_CHANNEL_LABELS = setOf(
    "yeu nhac vang", "yeu nhac", "official", "official music",
    "music channel", "youtube", "youtube music", "official channel"
)

/** Removes a recognized channel/branding suffix without guessing a singer name. */
internal fun normalizeNgocSiAiCurrentTrackArtist(rawArtist: String?): String? {
    val raw = rawArtist?.trim()?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() } ?: return null
    if (normalizeNgocSiAiVoicePhrase(raw) in setOf(
            "nghe si chua xac dinh", "nghe si khong ro", "unknown artist", "chua ro"
        )
    ) return null

    val parts = raw.split(Regex("\\s*[-–—|•]\\s*"))
        .map(String::trim)
        .filter(String::isNotBlank)
    var keepCount = parts.size
    while (keepCount > 1 &&
        normalizeNgocSiAiVoicePhrase(parts[keepCount - 1]) in NGOC_SI_AI_KNOWN_CHANNEL_LABELS
    ) {
        keepCount--
    }
    return parts.take(keepCount).joinToString(" - ").takeIf { it.isNotBlank() }
}

/** Channel branding is not a musical genre and must not be returned as one. */
internal fun normalizeNgocSiAiCurrentTrackGenre(rawGenre: String?): String? {
    val raw = rawGenre?.trim()?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() } ?: return null
    val normalized = normalizeNgocSiAiVoicePhrase(raw)
    if (normalized in NGOC_SI_AI_KNOWN_CHANNEL_LABELS ||
        normalized in setOf("chua co metadata", "khong ro", "unknown", "unknown genre")
    ) return null
    return raw
}

/** Extract only an explicitly appended performer label from a YouTube video title. */
internal fun extractNgocSiAiArtistFromVideoTitle(videoTitle: String?): String? {
    val title = videoTitle?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val parts = title.split(Regex("\\s+[-–—|•]\\s+")).map(String::trim)
    if (parts.size < 2) return null
    return normalizeNgocSiAiCurrentTrackArtist(parts.last())
}

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
            val title = item?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
                ?: "Chưa rõ tên bài hát"
            val artist = normalizeNgocSiAiCurrentTrackArtist(
                item?.mediaMetadata?.artist?.toString()
            )
            val status = when {
                player.isPlaying -> "Đang phát"
                player.playbackState == Player.STATE_BUFFERING && player.playWhenReady -> "Đang tải"
                player.playbackState == Player.STATE_ENDED -> "Đã phát hết"
                else -> "Đang tạm dừng"
            }
            if (artist == null) "$status: $title" else "$status: $title — $artist"
        }
        NgocSiAiLocalCommand.CURRENT_ARTIST -> {
            val artist = normalizeNgocSiAiCurrentTrackArtist(
                player.currentMediaItem?.mediaMetadata?.artist?.toString()
            )
            if (artist == null) {
                "Chưa xác định được ca sĩ từ metadata của bài đang phát. Tên nguồn hoặc tên kênh không được xem là tên ca sĩ."
            } else {
                "Ca sĩ được ghi trong metadata của bài đang phát: $artist. Đây là thông tin metadata, chưa phải nhận diện giọng hát trực tiếp."
            }
        }
        NgocSiAiLocalCommand.CURRENT_GENRE -> {
            val genre = normalizeNgocSiAiCurrentTrackGenre(
                player.currentMediaItem?.mediaMetadata?.genre?.toString()
            )
            if (genre == null) {
                "Chưa có metadata thể loại cho bài đang phát. Tôi không thể xác nhận thể loại chỉ từ tên bài hoặc tên kênh."
            } else {
                "Thể loại được ghi trong metadata của bài đang phát: $genre."
            }
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
