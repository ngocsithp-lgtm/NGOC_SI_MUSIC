package com.ngocsi.music

import java.text.Normalizer
import java.util.Locale

internal enum class NgocSiAiLocalCommand {
    PLAY,
    PLAY_SECOND,
    PLAY_TRACK,
    ADD_TRACK_TO_QUEUE,
    PLAY_RADIO_STATION,
    OPEN_TV_CHANNEL,
    CREATE_PLAYLIST,
    PLAY_NAMED_PLAYLIST,
    ADD_CURRENT_TRACK_TO_PLAYLIST,
    ADD_QUEUE_TO_PLAYLIST,
    RESUME_YOUTUBE,
    PAUSE_YOUTUBE,
    NEXT_YOUTUBE_VIDEO,
    PREVIOUS_YOUTUBE_VIDEO,
    YOUTUBE_SEEK_FORWARD_10,
    YOUTUBE_SEEK_BACK_10,
    YOUTUBE_SEEK_FORWARD_CUSTOM,
    YOUTUBE_SEEK_BACK_CUSTOM,
    YOUTUBE_SHUFFLE_ON,
    YOUTUBE_SHUFFLE_OFF,
    YOUTUBE_REPEAT_OFF,
    YOUTUBE_REPEAT_ALL,
    YOUTUBE_REPEAT_ONE,
    YOUTUBE_FAVORITE,
    YOUTUBE_WATCH_LATER,
    PAUSE,
    STOP,
    NEXT,
    PREVIOUS,
    CURRENT_TRACK,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    UNMUTE,
    SET_PLAYBACK_SPEED,
    SEEK_FORWARD_10,
    SEEK_BACK_10,
    SEEK_FORWARD_CUSTOM,
    SEEK_BACK_CUSTOM,
    FAVORITE_CURRENT_TRACK,
    SHUFFLE_ON,
    SHUFFLE_OFF,
    REPEAT_OFF,
    REPEAT_ALL,
    REPEAT_ONE,
    OPEN_HOME,
    OPEN_LIBRARY,
    OPEN_FAVORITES,
    OPEN_ALBUMS,
    OPEN_FOLDERS,
    OPEN_ONLINE,
    SEARCH_ONLINE_MUSIC,
    SEARCH_DRIVE,
    SEARCH_AND_PLAY_YOUTUBE,
    SEARCH_YOUTUBE,
    OPEN_YOUTUBE,
    OPEN_DRIVE,
    OPEN_RADIO,
    OPEN_TV,
    OPEN_PLAYLISTS,
    OPEN_QUEUE,
    OPEN_PLAYER,
    OPEN_SETTINGS,
    OPEN_SLEEP_TIMER,
    SLEEP_TIMER_15,
    SLEEP_TIMER_30,
    SLEEP_TIMER_45,
    SLEEP_TIMER_60,
    SLEEP_TIMER_90,
    SLEEP_TIMER_120,
    SLEEP_TIMER_CUSTOM,
    CANCEL_SLEEP_TIMER,
    HELP
}


/**
 * Maps a recognized hands-free app command to a MainActivity destination.
 * Kept pure so chat, wake-word routing and tests agree on the same navigation contract.
 */
internal fun ngocSiAiWakeWordDestination(
    command: NgocSiAiLocalCommand,
    hasSavedYouTubeVideo: Boolean = true
): String? = when (command) {
        NgocSiAiLocalCommand.OPEN_HOME -> "home"
        NgocSiAiLocalCommand.OPEN_LIBRARY -> "library"
        NgocSiAiLocalCommand.OPEN_FAVORITES -> "favorites"
        NgocSiAiLocalCommand.FAVORITE_CURRENT_TRACK -> "favorite_current"
        NgocSiAiLocalCommand.OPEN_ALBUMS -> "albums"
        NgocSiAiLocalCommand.OPEN_FOLDERS -> "folders"
        NgocSiAiLocalCommand.OPEN_ONLINE -> "online"
        NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC -> "search_online"
        NgocSiAiLocalCommand.SEARCH_DRIVE -> "drive_search"
        NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE -> "queue_track"
        NgocSiAiLocalCommand.OPEN_YOUTUBE,
        NgocSiAiLocalCommand.SEARCH_YOUTUBE -> "youtube"
        NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE -> "youtube_search_play"
        NgocSiAiLocalCommand.OPEN_DRIVE -> "drive"
        NgocSiAiLocalCommand.OPEN_RADIO -> "radio"
        NgocSiAiLocalCommand.PLAY_RADIO_STATION -> "radio_station"
        NgocSiAiLocalCommand.OPEN_TV -> "tv"
        NgocSiAiLocalCommand.OPEN_TV_CHANNEL -> "tv_channel"
        NgocSiAiLocalCommand.CREATE_PLAYLIST -> "create_playlist"
        NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST -> "play_playlist"
        NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST -> "add_current_to_playlist"
        NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST -> "add_queue_to_playlist"
        NgocSiAiLocalCommand.RESUME_YOUTUBE,
        NgocSiAiLocalCommand.PAUSE_YOUTUBE,
        NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO,
        NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO,
        NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON,
        NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF,
        NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF,
        NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL,
        NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE,
        NgocSiAiLocalCommand.YOUTUBE_FAVORITE,
        NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER ->
            if (hasSavedYouTubeVideo) "youtube_command" else "youtube"
        NgocSiAiLocalCommand.OPEN_PLAYLISTS -> "playlists"
        NgocSiAiLocalCommand.OPEN_QUEUE -> "queue"
        NgocSiAiLocalCommand.OPEN_PLAYER -> "player"
        NgocSiAiLocalCommand.OPEN_SETTINGS -> "settings"
        NgocSiAiLocalCommand.OPEN_SLEEP_TIMER -> "sleep_timer"
        NgocSiAiLocalCommand.SLEEP_TIMER_15 -> "sleep_timer_15"
        NgocSiAiLocalCommand.SLEEP_TIMER_30 -> "sleep_timer_30"
        NgocSiAiLocalCommand.SLEEP_TIMER_45 -> "sleep_timer_45"
        NgocSiAiLocalCommand.SLEEP_TIMER_60 -> "sleep_timer_60"
        NgocSiAiLocalCommand.SLEEP_TIMER_90 -> "sleep_timer_90"
        NgocSiAiLocalCommand.SLEEP_TIMER_120 -> "sleep_timer_120"
        NgocSiAiLocalCommand.SLEEP_TIMER_CUSTOM -> "sleep_timer_custom"
        NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER -> "sleep_timer_off"
        else -> null
    }

/**
 * These controls act on the live Media3 queue. If the controller is still connecting,
 * the wake-word service should wait briefly instead of immediately dropping the command.
 * Navigation and commands with a MainActivity fallback do not need this wait.
 */
private val NGOC_SI_AI_PLAYER_CONNECTION_COMMANDS = setOf(
    NgocSiAiLocalCommand.PAUSE,
    NgocSiAiLocalCommand.STOP,
    NgocSiAiLocalCommand.NEXT,
    NgocSiAiLocalCommand.PREVIOUS,
    NgocSiAiLocalCommand.PLAY_SECOND,
    NgocSiAiLocalCommand.CURRENT_TRACK,
    NgocSiAiLocalCommand.SET_PLAYBACK_SPEED,
    NgocSiAiLocalCommand.SEEK_FORWARD_10,
    NgocSiAiLocalCommand.SEEK_BACK_10,
    NgocSiAiLocalCommand.SEEK_FORWARD_CUSTOM,
    NgocSiAiLocalCommand.SEEK_BACK_CUSTOM,
    NgocSiAiLocalCommand.SHUFFLE_ON,
    NgocSiAiLocalCommand.SHUFFLE_OFF,
    NgocSiAiLocalCommand.REPEAT_OFF,
    NgocSiAiLocalCommand.REPEAT_ALL,
    NgocSiAiLocalCommand.REPEAT_ONE
)

internal fun ngocSiAiCommandRequiresPlayerConnection(command: NgocSiAiLocalCommand): Boolean =
    command in NGOC_SI_AI_PLAYER_CONNECTION_COMMANDS

internal data class NgocSiAiSeekRequest(
    val deltaMs: Long,
    val youtubeExplicit: Boolean
)

private val NGOC_SI_AI_SPOKEN_SEEK_AMOUNTS = mapOf(
    "mot" to 1, "hai" to 2, "ba" to 3, "bon" to 4, "tu" to 4,
    "nam" to 5, "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9,
    "muoi" to 10, "muoi lam" to 15, "hai muoi" to 20,
    "ba muoi" to 30, "bon muoi" to 40, "nam muoi" to 50,
    "sau muoi" to 60, "mot tram" to 100, "mot tram hai muoi" to 120
)

/**
 * Parses an explicit bounded seek request. The maximum is two minutes; free-form
 * time expressions and ambiguous requests are rejected rather than guessed.
 */
internal fun extractNgocSiAiSeekRequest(input: String): NgocSiAiSeekRequest? {
    var phrase = normalizeNgocSiAiVoicePhrase(input)
    val wakePrefixes = listOf("hey ai ", "ok ai ", "alo ai ", "ai oi ", "ai ",
        "hey ngoc si ", "ok ngoc si ", "alo ngoc si ", "ngoc si oi ", "ngoc si ")
    wakePrefixes.firstOrNull { phrase.startsWith(it) }?.let { phrase = phrase.removePrefix(it).trim() }
    phrase = trimNgocSiAiNormalizedCommandFillers(phrase)
    val youtubeExplicit = phrase.contains("youtube")
    phrase = phrase.removeSuffix(" tren youtube").trim()

    val forwardPrefixes = listOf(
        "tua youtube tien toi ", "tua youtube toi ", "tua tien toi ",
        "tua toi ", "tien toi ", "nhay toi ", "forward ", "seek forward "
    )
    val backPrefixes = listOf(
        "tua youtube ve truoc ", "tua youtube lui ", "tua lui ",
        "quay lai ", "tro lai ", "lui lai ", "lui ve ", "lui ",
        "back ", "seek back "
    )
    val direction = when {
        forwardPrefixes.any { phrase.startsWith(it) } -> 1
        backPrefixes.any { phrase.startsWith(it) } -> -1
        else -> return null
    }
    val prefix = (if (direction > 0) forwardPrefixes else backPrefixes)
        .first { phrase.startsWith(it) }
    val remainder = phrase.removePrefix(prefix).trim()
    val unit = when {
        remainder.endsWith(" giay") -> "giay"
        remainder.endsWith(" phut") -> "phut"
        else -> return null
    }
    val amountText = remainder.removeSuffix(" $unit").trim()
    val amount = amountText.toIntOrNull() ?: NGOC_SI_AI_SPOKEN_SEEK_AMOUNTS[amountText] ?: return null
    if (amount !in 1..120) return null
    val seconds = if (unit == "phut") amount * 60 else amount
    if (seconds !in 1..120) return null
    val deltaMs = direction.toLong() * seconds * 1_000L
    return NgocSiAiSeekRequest(deltaMs = deltaMs, youtubeExplicit = youtubeExplicit)
}

internal fun buildNgocSiAiYouTubeSeekAction(deltaMs: Long): String? {
    if (deltaMs == 0L || kotlin.math.abs(deltaMs) > 120_000L || deltaMs % 1_000L != 0L) return null
    return "seek_by_ms:$deltaMs"
}

internal fun parseNgocSiAiYouTubeSeekAction(action: String): Long? {
    if (!action.startsWith("seek_by_ms:")) return null
    val deltaMs = action.removePrefix("seek_by_ms:").toLongOrNull() ?: return null
    return deltaMs.takeIf {
        it != 0L && kotlin.math.abs(it) <= 120_000L && it % 1_000L == 0L
    }
}

/**
 * Maps transport commands to the YouTube player when YouTube is the selected
 * playback source. Explicit YouTube commands always target YouTube; generic
 * commands follow the currently selected source instead of a separate Media3 queue.
 */
internal fun ngocSiAiYouTubeActionForCommand(
    command: NgocSiAiLocalCommand,
    youtubeActiveSource: Boolean,
    seekDeltaMs: Long? = null
): String? = when (command) {
    NgocSiAiLocalCommand.RESUME_YOUTUBE -> "play"
    NgocSiAiLocalCommand.PAUSE_YOUTUBE -> "pause"
    NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO -> "next"
    NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO -> "previous"
    NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10 -> "seek_forward_10"
    NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10 -> "seek_back_10"
    NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_CUSTOM ->
        seekDeltaMs?.takeIf { it > 0L }?.let(::buildNgocSiAiYouTubeSeekAction)
    NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_CUSTOM ->
        seekDeltaMs?.takeIf { it < 0L }?.let(::buildNgocSiAiYouTubeSeekAction)
    NgocSiAiLocalCommand.SEEK_FORWARD_10 -> if (youtubeActiveSource) "seek_forward_10" else null
    NgocSiAiLocalCommand.SEEK_BACK_10 -> if (youtubeActiveSource) "seek_back_10" else null
    NgocSiAiLocalCommand.SEEK_FORWARD_CUSTOM ->
        if (youtubeActiveSource) seekDeltaMs?.takeIf { it > 0L }?.let(::buildNgocSiAiYouTubeSeekAction) else null
    NgocSiAiLocalCommand.SEEK_BACK_CUSTOM ->
        if (youtubeActiveSource) seekDeltaMs?.takeIf { it < 0L }?.let(::buildNgocSiAiYouTubeSeekAction) else null
    NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON -> "shuffle_on"
    NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF -> "shuffle_off"
    NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF -> "repeat_off"
    NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL -> "repeat_all"
    NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE -> "repeat_one"
    NgocSiAiLocalCommand.YOUTUBE_FAVORITE -> "favorite"
    NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER -> "watch_later"
    NgocSiAiLocalCommand.PLAY -> if (youtubeActiveSource) "play" else null
    NgocSiAiLocalCommand.PAUSE -> if (youtubeActiveSource) "pause" else null
    NgocSiAiLocalCommand.STOP -> if (youtubeActiveSource) "stop" else null
    NgocSiAiLocalCommand.NEXT -> if (youtubeActiveSource) "next" else null
    NgocSiAiLocalCommand.PREVIOUS -> if (youtubeActiveSource) "previous" else null
    NgocSiAiLocalCommand.CURRENT_TRACK -> if (youtubeActiveSource) "current_track" else null
    NgocSiAiLocalCommand.FAVORITE_CURRENT_TRACK -> if (youtubeActiveSource) "favorite" else null
    // Generic shuffle/repeat remain bound to Media3. Inline YouTube gets these
    // controls only through explicit YouTube commands; its dedicated player has
    // a separate queue-mode implementation.
    else -> null
}

internal enum class NgocSiAiYouTubeDispatchTarget {
    OPEN_YOUTUBE,
    MAIN_INLINE_PLAYER,
    LIVE_CONTROL_HOST
}

/**
 * Selects a YouTube control destination only from current runtime state.
 * A live control host means either the dedicated YouTube player or MainActivity's
 * registered inline-WebView receiver; a persisted Activity flag is never trusted.
 */
internal fun selectNgocSiAiYouTubeDispatchTarget(
    hasSavedVideo: Boolean,
    liveControlHostAvailable: Boolean
): NgocSiAiYouTubeDispatchTarget = when {
    !hasSavedVideo -> NgocSiAiYouTubeDispatchTarget.OPEN_YOUTUBE
    liveControlHostAvailable -> NgocSiAiYouTubeDispatchTarget.LIVE_CONTROL_HOST
    else -> NgocSiAiYouTubeDispatchTarget.MAIN_INLINE_PLAYER
}

internal enum class NgocSiAiPlaybackTarget {
    PRODUCTION,
    PREVIEW
}

/**
 * Prefer whichever app is actively playing. When neither is playing, prefer an existing
 * production queue; fall back to Preview so first-use play commands can populate its queue.
 */
internal fun selectNgocSiAiPlaybackTarget(
    productionConnected: Boolean,
    productionActive: Boolean,
    productionHasItems: Boolean,
    previewConnected: Boolean,
    previewActive: Boolean,
    previewHasItems: Boolean
): NgocSiAiPlaybackTarget? = when {
    productionConnected && productionActive -> NgocSiAiPlaybackTarget.PRODUCTION
    previewConnected && previewActive -> NgocSiAiPlaybackTarget.PREVIEW
    productionConnected && productionHasItems -> NgocSiAiPlaybackTarget.PRODUCTION
    previewConnected && previewHasItems -> NgocSiAiPlaybackTarget.PREVIEW
    previewConnected -> NgocSiAiPlaybackTarget.PREVIEW
    productionConnected -> NgocSiAiPlaybackTarget.PRODUCTION
    else -> null
}

/**
 * A hard-stop command must not report success after stopping an idle Preview session
 * while the production session is unavailable. Prefer active playback; otherwise use
 * a connected production session. Preview is a valid fallback only when it is playing.
 */
internal fun selectNgocSiAiStopPlaybackTarget(
    productionConnected: Boolean,
    productionActive: Boolean,
    previewConnected: Boolean,
    previewActive: Boolean
): NgocSiAiPlaybackTarget? = when {
    productionConnected && productionActive -> NgocSiAiPlaybackTarget.PRODUCTION
    previewConnected && previewActive -> NgocSiAiPlaybackTarget.PREVIEW
    productionConnected -> NgocSiAiPlaybackTarget.PRODUCTION
    else -> null
}


/**
 * Recognizes only short, explicit playback commands. A normal question such as
 * "Làm thế nào để phát nhạc?" is intentionally not treated as an action.
 */
/**
 * Accepts a playback command only when the recognized utterance starts with the
 * explicit wake phrase "Ngọc Sĩ". This prevents background speech from triggering actions.
 */
private val NGOC_SI_AI_WAKE_WORD_COMMANDS = setOf(
    NgocSiAiLocalCommand.PLAY,
    NgocSiAiLocalCommand.PAUSE,
    NgocSiAiLocalCommand.STOP,
    NgocSiAiLocalCommand.NEXT,
    NgocSiAiLocalCommand.PREVIOUS,
    NgocSiAiLocalCommand.PLAY_SECOND,
    NgocSiAiLocalCommand.PLAY_TRACK,
    NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE,
    NgocSiAiLocalCommand.PLAY_RADIO_STATION,
    NgocSiAiLocalCommand.OPEN_TV_CHANNEL,
    NgocSiAiLocalCommand.CREATE_PLAYLIST,
    NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST,
    NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST,
    NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST,
    NgocSiAiLocalCommand.RESUME_YOUTUBE,
    NgocSiAiLocalCommand.PAUSE_YOUTUBE,
    NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO,
    NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO,
    NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10,
    NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10,
    NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_CUSTOM,
    NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_CUSTOM,
    NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON,
    NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF,
    NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF,
    NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL,
    NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE,
    NgocSiAiLocalCommand.YOUTUBE_FAVORITE,
    NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER,
    NgocSiAiLocalCommand.CURRENT_TRACK,
    NgocSiAiLocalCommand.VOLUME_UP,
    NgocSiAiLocalCommand.VOLUME_DOWN,
    NgocSiAiLocalCommand.MUTE,
    NgocSiAiLocalCommand.UNMUTE,
    NgocSiAiLocalCommand.SET_PLAYBACK_SPEED,
    NgocSiAiLocalCommand.SEEK_FORWARD_10,
    NgocSiAiLocalCommand.SEEK_BACK_10,
    NgocSiAiLocalCommand.SEEK_FORWARD_CUSTOM,
    NgocSiAiLocalCommand.SEEK_BACK_CUSTOM,
    NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_CUSTOM,
    NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_CUSTOM,
    NgocSiAiLocalCommand.FAVORITE_CURRENT_TRACK,
    NgocSiAiLocalCommand.SHUFFLE_ON,
    NgocSiAiLocalCommand.SHUFFLE_OFF,
    NgocSiAiLocalCommand.REPEAT_OFF,
    NgocSiAiLocalCommand.REPEAT_ALL,
    NgocSiAiLocalCommand.REPEAT_ONE,
    // Wake-word mode can route to every existing top-level screen and timer action.
    NgocSiAiLocalCommand.OPEN_HOME,
    NgocSiAiLocalCommand.OPEN_LIBRARY,
    NgocSiAiLocalCommand.OPEN_FAVORITES,
    NgocSiAiLocalCommand.OPEN_ALBUMS,
    NgocSiAiLocalCommand.OPEN_FOLDERS,
    NgocSiAiLocalCommand.OPEN_ONLINE,
    NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC,
    NgocSiAiLocalCommand.SEARCH_DRIVE,
    NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE,
    NgocSiAiLocalCommand.SEARCH_YOUTUBE,
    NgocSiAiLocalCommand.OPEN_YOUTUBE,
    NgocSiAiLocalCommand.OPEN_DRIVE,
    NgocSiAiLocalCommand.OPEN_RADIO,
    NgocSiAiLocalCommand.OPEN_TV,
    NgocSiAiLocalCommand.OPEN_PLAYLISTS,
    NgocSiAiLocalCommand.OPEN_QUEUE,
    NgocSiAiLocalCommand.OPEN_PLAYER,
    NgocSiAiLocalCommand.OPEN_SETTINGS,
    NgocSiAiLocalCommand.OPEN_SLEEP_TIMER,
    NgocSiAiLocalCommand.SLEEP_TIMER_15,
    NgocSiAiLocalCommand.SLEEP_TIMER_30,
    NgocSiAiLocalCommand.SLEEP_TIMER_45,
    NgocSiAiLocalCommand.SLEEP_TIMER_60,
    NgocSiAiLocalCommand.SLEEP_TIMER_90,
    NgocSiAiLocalCommand.SLEEP_TIMER_120,
    NgocSiAiLocalCommand.SLEEP_TIMER_CUSTOM,
    NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER,
    NgocSiAiLocalCommand.HELP
)

internal fun normalizeNgocSiAiVoicePhrase(input: String): String =
    Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('đ', 'd')
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()


/**
 * Removes only common speech fillers at the end of an explicit voice command.
 * This makes commands tolerant of conversational phrasing without broad fuzzy
 * matching that could accidentally trigger an unrelated request.
 */
private fun trimNgocSiAiNormalizedCommandFillers(input: String): String {
    var phrase = input.trim()
    val suffixes = listOf(
        " duoc khong", " duoc chu", " giup toi nhe", " giup minh nhe",
        " nhe nha", " voi nhe", " giup toi", " giup minh", " cho toi",
        " nhe", " nha", " voi", " di", " len"
    )
    while (true) {
        val suffix = suffixes.firstOrNull { phrase.endsWith(it) && phrase.length > it.length } ?: break
        phrase = phrase.removeSuffix(suffix).trimEnd()
    }
    return phrase
}

/**
 * Recognizes a direct request to disable hands-free voice control.
 * Exact normalized phrases are used to avoid stopping on unrelated conversation.
 */
internal fun isNgocSiAiStopVoiceControlPhrase(input: String): Boolean {
    var phrase = normalizeNgocSiAiVoicePhrase(input)
    // Remember whether the user explicitly addressed the assistant. This lets
    // "AI, tạm dừng" stop hands-free listening without making a bare "tạm dừng"
    // disable the assistant when it was intended as a music playback command.
    val explicitlyAddressedAssistant = listOf(
        "hey ngoc si ", "ok ngoc si ", "alo ngoc si ", "ngoc si oi ", "ngoc si ",
        "hey ai ", "ok ai ", "alo ai ", "ai oi ", "ai "
    ).any { phrase.startsWith(it) }
    val prefixes = listOf(
        "hey ngoc si ", "ok ngoc si ", "alo ngoc si ", "ngoc si oi ", "ngoc si ",
        "hey ai ", "ok ai ", "alo ai ", "ai oi ", "ai ",
        "vui long ", "lam on ", "hay "
    )
    while (true) {
        val prefix = prefixes.firstOrNull { phrase.startsWith(it) } ?: break
        phrase = phrase.removePrefix(prefix).trim()
    }
    phrase = trimNgocSiAiNormalizedCommandFillers(phrase)
    if (explicitlyAddressedAssistant && phrase == "tam dung") return true
    return phrase in setOf(
        "tam dung ai",
        "dung ai",
        "ngung ai",
        "tat ai",
        "tam dung nghe ai",
        "dung nghe ai",
        "ngung nghe ai",
        "dung nghe",
        "ngung nghe",
        "dung lang nghe",
        "ngung lang nghe",
        "tam dung dieu khien giong noi",
        "tam dung giong noi",
        "tam dung nghe",
        "dung giong noi",
        "ngung giong noi",
        "tat giong noi",
        "tam dung dieu khien bang giong noi",
        "dung dieu khien giong noi",
        "dung dieu khien bang giong noi",
        "ngung dieu khien giong noi",
        "tat dieu khien giong noi",
        "tat dieu khien bang giong noi",
        "dung nhan lenh giong noi",
        "ngung nhan lenh giong noi",
        "tat micro",
        "tat mic",
        "dung micro",
        "tat tro ly giong noi",
        "dung tro ly giong noi",
        "ngung tro ly giong noi",
        "tam dung tro chuyen",
        "thoat che do nghe",
        "thoat che do tro chuyen",
        "ket thuc tro chuyen",
        "ngung tro chuyen voi ai",
        "ket thuc tro chuyen voi ai",
        "dung nghe tu khoa",
        "ngung nghe tu khoa",
        "tat nghe tu khoa"
    )
}

/** Keeps the user's spelling while removing polite speech tails from extracted names. */
/**
 * Recognizes an explicit immediate stop request, including common wake-word and polite forms.
 * Time-qualified requests are deliberately excluded so "stop the music after 15 minutes"
 * continues to route to the sleep timer rather than stopping immediately.
 */
internal fun isNgocSiAiExplicitStopPhrase(input: String): Boolean {
    var phrase = normalizeNgocSiAiVoicePhrase(input)
    val prefixes = listOf(
        "hey ngoc si ", "ok ngoc si ", "alo ngoc si ",
        "hey ai ", "ok ai ", "alo ai ", "ai oi ", "ai ",
        "ngoc si oi ", "ngoc si ", "toi muon ban ", "minh muon ban ",
        "ban giup toi ", "ban giup minh ", "toi muon ", "minh muon ",
        "hay ", "vui long ", "lam on ", "giup toi ", "giup minh "
    )
    while (true) {
        val prefix = prefixes.firstOrNull { phrase.startsWith(it) } ?: break
        phrase = phrase.removePrefix(prefix).trim()
    }
    if (phrase.startsWith("ban ")) {
        val afterYouPrefix = phrase.removePrefix("ban ").trim()
        val explicitActionStarts = listOf(
            "dung ", "ngung ", "tat nhac", "tat het nhac", "stop ",
            "stop", "stop the music", "stop music"
        )
        if (explicitActionStarts.any { afterYouPrefix == it.trim() || afterYouPrefix.startsWith(it) }) {
            phrase = afterYouPrefix
        }
    }
    phrase = trimNgocSiAiNormalizedCommandFillers(phrase)

    if (phrase.isBlank() || phrase.startsWith("tam dung") || phrase.startsWith("pause")) {
        return false
    }
    if (phrase.contains("hen gio") ||
        Regex("""\b(sau|trong)\s+\d+\s+phut\b""").containsMatchIn(phrase)
    ) {
        return false
    }

    val hardStopPrefixes = listOf(
        "dung han", "dung hoan toan", "dung het",
        "dung nhac hoan toan", "dung phat han", "dung phat hoan toan",
        "ngung han", "ngung phat han", "ngung nhac han",
        "tat han nhac", "tat nhac han", "ket thuc phat nhac",
        "dung nhac luon", "ngung nhac luon",
        "dung nhac nua", "dung phat nua", "dung phat nhac nua",
        "ngung nhac nua", "ngung phat nua", "ngung phat nhac nua",
        "tat nhac luon", "tat het nhac luon", "dung bai nay luon",
        "stop playback", "stop completely"
    )
    if (hardStopPrefixes.any { phrase == it || phrase.startsWith("$it ") }) return true

    // These short commands are exact matches only; broad prefix matching would mistake
    // "tắt nhạc sau 15 phút" for an immediate stop instead of a sleep-timer request.
    return phrase in setOf(
        "dung", "dung lai", "dung nhac", "dung phat nhac",
        "ngung nhac", "ngung phat nhac",
        "tat nhac", "tat het nhac", "tat phat nhac",
        "dung bai hat", "stop", "stop music", "stop the music", "end music"
    )
}

private fun trimNgocSiAiExtractedQuery(input: String): String {
    var query = input.trim().trim(',', ':', '-', '.', '?', '!', ';')
    val suffix = Regex(
        """(?iu)\s+(?:giúp tôi|giup toi|giùm tôi|gium toi|giúp mình|giup minh|cho tôi|cho toi|nhé nha|nhe nha|với nhé|voi nhe|nhé|nhe|nha|với|voi|đi|di|lên|len)[.!?,;:]*$"""
    )
    while (suffix.containsMatchIn(query)) {
        query = suffix.replaceFirst(query, "").trim().trim(',', ':', '-', '.', '?', '!', ';')
    }
    return query
}

/** True when recognition heard only the wake phrase, not a playback command yet. */
internal fun isNgocSiAiWakeWordOnly(input: String): Boolean =
    normalizeNgocSiAiVoicePhrase(input) in setOf(
        "ai", "ai oi", "hey ai", "ok ai", "alo ai",
        "hey ai oi", "ok ai oi", "alo ai oi"
    )

/** Accepts a command only when wake phrase and command occur in the same utterance. */
internal fun classifyNgocSiAiWakePhrase(
    input: String,
    entryContext: String? = null
): NgocSiAiLocalCommand? {
    val normalized = normalizeNgocSiAiVoicePhrase(input)
    // Accept the same natural wake-word variants the microphone UI recognizes.
    // Check the longer prefixes first so "AI ơi" is stripped as one greeting.
    val wakePrefixes = listOf(
        "hey ai oi ", "ok ai oi ", "alo ai oi ",
        "ai oi ", "hey ai ", "ok ai ", "alo ai ", "ai "
    )
    val wakePrefix = wakePrefixes.firstOrNull { normalized.startsWith(it) } ?: return null
    var commandPhrase = normalized.removePrefix(wakePrefix).trim()
    if (commandPhrase.isBlank()) return null

    // Keep these common home aliases explicit in wake-word mode too; speech
    // services often normalize “về giao diện chính” differently from “về trang chủ”.
    if (
        commandPhrase == "ve giao dien chinh" ||
        commandPhrase.startsWith("ve giao dien chinh ") ||
        commandPhrase == "tro ve giao dien chinh" ||
        commandPhrase.startsWith("tro ve giao dien chinh ") ||
        commandPhrase == "quay ve giao dien chinh" ||
        commandPhrase.startsWith("quay ve giao dien chinh ")
    ) {
        return NgocSiAiLocalCommand.OPEN_HOME
    }

    // Rebuild a canonical explicit wake phrase after accepting "Hey AI"/"Alo AI".
    // This keeps the safety guard for seek, speed and favorite commands active.
    return classifyNgocSiAiActivatedCommand("AI, $commandPhrase", entryContext)
}

/**
 * Once the user has explicitly activated the wake word, accept only the safe
 * playback-control allowlist. Navigation, account and timer actions stay out of
 * always-listening mode.
 */
internal fun classifyNgocSiAiActivatedCommand(
    input: String,
    entryContext: String? = null
): NgocSiAiLocalCommand? {
    val command = classifyNgocSiAiLocalCommand(input, entryContext) ?: return null
    if (command !in NGOC_SI_AI_WAKE_WORD_COMMANDS) return null

    // Sensitive transport settings require an explicit assistant address so
    // ambient conversation cannot unexpectedly change speed, seek or favorites.
    val requiresExplicitAddress = command in setOf(
        NgocSiAiLocalCommand.SET_PLAYBACK_SPEED,
        NgocSiAiLocalCommand.SEEK_FORWARD_10,
        NgocSiAiLocalCommand.SEEK_BACK_10,
        NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10,
        NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10,
        NgocSiAiLocalCommand.SEEK_FORWARD_CUSTOM,
        NgocSiAiLocalCommand.SEEK_BACK_CUSTOM,
        NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_CUSTOM,
        NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_CUSTOM,
        NgocSiAiLocalCommand.FAVORITE_CURRENT_TRACK
    )
    if (!requiresExplicitAddress) return command

    val normalized = normalizeNgocSiAiVoicePhrase(input)
    val explicitlyAddressed = normalized.startsWith("ai ") ||
        normalized.startsWith("hey ai ") ||
        normalized.startsWith("ok ai ") ||
        normalized.startsWith("alo ai ") ||
        normalized.startsWith("ai oi ") ||
        normalized.startsWith("ngoc si ") ||
        normalized.startsWith("ngoc si oi ")
    return command.takeIf { explicitlyAddressed }
}

/**
 * Extract a requested sleep duration from an explicit timer command.
 * Supports numeric and common spoken Vietnamese values, minute/hour units,
 * and a strict 1–1440 minute (24-hour) safety bound.
 */
internal fun extractNgocSiAiSleepTimerMinutes(input: String): Int? {
    var phrase = normalizeNgocSiAiVoicePhrase(input)
    val wakePrefixes = listOf(
        "hey ai oi ", "ok ai oi ", "alo ai oi ", "ai oi ",
        "hey ai ", "ok ai ", "alo ai ", "ai ",
        "hey ngoc si ", "ok ngoc si ", "alo ngoc si ", "ngoc si oi ", "ngoc si "
    )
    wakePrefixes.firstOrNull { phrase.startsWith(it) }?.let {
        phrase = phrase.removePrefix(it).trim()
    }
    phrase = trimNgocSiAiNormalizedCommandFillers(phrase)
    val match = Regex(
        """(?:hen gio|sleep timer|tat nhac|dung nhac)\s+(?:(?:tat nhac)\s+)?(?:(?:sau|trong|after|for)\s+)?([a-z0-9 ]+?)\s+(phut|gio|tieng|minutes?|hours?)$"""
    ).find(phrase) ?: return null
    val amountText = match.groupValues[1].trim()
    val amount = amountText.toIntOrNull() ?: when (amountText) {
        "mot" -> 1
        "hai" -> 2
        "ba" -> 3
        "bon", "tu" -> 4
        "nam" -> 5
        "sau" -> 6
        "bay" -> 7
        "tam" -> 8
        "chin" -> 9
        "muoi" -> 10
        "muoi lam" -> 15
        "hai muoi" -> 20
        "hai muoi bon" -> 24
        "ba muoi" -> 30
        "bon muoi" -> 40
        "nam muoi" -> 50
        "sau muoi" -> 60
        "mot tram" -> 100
        "mot tram hai muoi" -> 120
        else -> return null
    }
    if (amount !in 1..1_440) return null
    val minutes = if (match.groupValues[2] in setOf("gio", "tieng", "hour", "hours")) amount * 60 else amount
    return minutes.takeIf { it in 1..1_440 }
}

internal fun classifyNgocSiAiLocalCommand(
    input: String,
    entryContext: String? = null
): NgocSiAiLocalCommand? {
    // A sentence asking the assistant to remember a music preference can also contain
    // words such as “nghe nhạc”; never reinterpret that sentence as a playback request.
    if (parseNgocSiAiMemoryCommand(input) != null) return null

    // When AI is opened from YouTube, an otherwise source-neutral search-and-play
    // request belongs to the visible YouTube page. Outside that page, do not guess.
    if (extractNgocSiAiContextualYoutubeAutoPlayQuery(input, entryContext) != null) {
        return NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE
    }
    if (extractNgocSiAiContextualYoutubeQuery(input, entryContext) != null) {
        return NgocSiAiLocalCommand.SEARCH_YOUTUBE
    }
    if (extractNgocSiAiContextualDriveQuery(input, entryContext) != null) {
        return NgocSiAiLocalCommand.SEARCH_DRIVE
    }

    var phrase = Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('đ', 'd')
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
    if (phrase.isBlank()) return null

    val politePrefixes = listOf(
        "hey ai ", "ok ai ", "alo ai ", "ai oi ", "ai ",
        "hey ngoc si ", "ok ngoc si ", "alo ngoc si ", "ngoc si oi ", "ngoc si ",
        "toi muon ban ", "minh muon ban ", "ban giup toi ", "ban giup minh ",
        "toi muon ", "minh muon ",
        "hay ", "vui long ", "lam on "
    )
    // “Giúp tôi tìm nhạc vui” is an open-ended request, not a short offline
    // player command. Strip “giúp tôi/minh” only when followed by a known direct action.
    val helpPrefix = listOf("giup toi ", "giup minh ").firstOrNull { phrase.startsWith(it) }
    if (helpPrefix != null) {
        val afterHelp = phrase.removePrefix(helpPrefix).trim()
        val explicitActions = listOf(
            "dung ", "tam dung ", "ngung ", "tat ", "phat ", "bat ", "mo ",
            "chuyen ", "tiep tuc ", "qua bai", "bai tiep", "bai truoc",
            "ve trang chu", "tro ve ", "quay ve ", "hen gio ", "dat hen gio ",
            "huy hen gio", "lap ", "bo hen gio", "luu ", "them "
        )
        if (explicitActions.any { afterHelp == it.trim() || afterHelp.startsWith(it) }) {
            phrase = afterHelp
        }
    }
    while (true) {
        val prefix = politePrefixes.firstOrNull { phrase.startsWith(it) } ?: break
        phrase = phrase.removePrefix(prefix).trim()
    }
    // Allow an ordinary conversational “bạn, …” before an explicit command,
    // but do not strip “bạn” from questions such as “Bạn làm được gì?”.
    if (phrase.startsWith("ban ")) {
        val afterYouPrefix = phrase.removePrefix("ban ").trim()
        val explicitActionStarts = listOf(
            "dung ", "tam dung ", "ngung ", "tat ", "phat ", "bat ", "mo ",
            "chuyen ", "tiep tuc ", "qua bai", "bai tiep", "bai truoc",
            "ve trang chu", "tro ve ", "quay ve ", "hen gio ", "dat hen gio ",
            "huy hen gio", "tim nhac ", "tim bai hat ", "tim youtube ",
            "lap ", "bo hen gio", "luu ", "them "
        )
        if (explicitActionStarts.any { afterYouPrefix == it.trim() || afterYouPrefix.startsWith(it) }) {
            phrase = afterYouPrefix
        }
    }
    phrase = trimNgocSiAiNormalizedCommandFillers(phrase)

    fun isAnyOf(vararg options: String) = phrase in options.toSet()
    fun startsWithAny(vararg options: String) = options.any { phrase == it || phrase.startsWith("$it ") }

    val speedMatch = Regex(
        """^(?:dat )?toc do phat (0 5|0 75|1 0|1 25|1 5|1 75|2 0)x?$"""
    ).matchEntire(phrase)
    if (speedMatch != null) return NgocSiAiLocalCommand.SET_PLAYBACK_SPEED

    // Explicit system media-volume controls. Keep these phrases narrow so ordinary
    // questions about sound or audio quality do not trigger device-wide changes.
    extractNgocSiAiSeekRequest(phrase)?.let { request ->
        if (kotlin.math.abs(request.deltaMs) == 10_000L) {
            return when {
                request.youtubeExplicit && request.deltaMs > 0L -> NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10
                request.youtubeExplicit && request.deltaMs < 0L -> NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10
                request.deltaMs > 0L -> NgocSiAiLocalCommand.SEEK_FORWARD_10
                else -> NgocSiAiLocalCommand.SEEK_BACK_10
            }
        }
        return when {
            request.youtubeExplicit && request.deltaMs > 0L -> NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_CUSTOM
            request.youtubeExplicit && request.deltaMs < 0L -> NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_CUSTOM
            request.deltaMs > 0L -> NgocSiAiLocalCommand.SEEK_FORWARD_CUSTOM
            else -> NgocSiAiLocalCommand.SEEK_BACK_CUSTOM
        }
    }

    when {
        startsWithAny("tang am luong", "tang tieng", "mo to am luong", "volume up", "increase volume", "raise volume", "louder") ->
            return NgocSiAiLocalCommand.VOLUME_UP
        startsWithAny("giam am luong", "giam tieng", "nho tieng", "volume down", "decrease volume", "lower volume", "quieter") ->
            return NgocSiAiLocalCommand.VOLUME_DOWN
        startsWithAny("tat tieng", "tat am thanh", "mute", "mute audio", "turn sound off") ->
            return NgocSiAiLocalCommand.MUTE
        startsWithAny("bat tieng", "mo tieng", "bat am thanh", "unmute", "turn sound on") ->
            return NgocSiAiLocalCommand.UNMUTE
    }

    val sleepTimerMinutes = extractNgocSiAiSleepTimerMinutes(input)

    return when {
        startsWithAny(
            "hat bai thu hai trong danh sach", "phat bai thu hai trong danh sach",
            "phat bai thu hai", "phat bai so hai", "hat bai so hai",
            "bai thu hai trong danh sach", "bai so hai trong danh sach",
            "cho phat bai thu hai", "phat bai thu hai trong hang doi"
        ) -> NgocSiAiLocalCommand.PLAY_SECOND

        // Explicit YouTube seek phrases must be classified before generic 10-second seek.
        startsWithAny(
            "tua youtube toi 10 giay", "tua youtube toi muoi giay",
            "tua youtube tien toi 10 giay", "tua youtube tien toi muoi giay",
            "tua toi 10 giay tren youtube", "tua toi muoi giay tren youtube",
            "tien toi 10 giay tren youtube", "tien toi muoi giay tren youtube",
            "nhay toi 10 giay tren youtube", "nhay toi muoi giay tren youtube",
            "seek youtube forward 10 seconds"
        ) -> NgocSiAiLocalCommand.YOUTUBE_SEEK_FORWARD_10

        startsWithAny(
            "tua youtube lui 10 giay", "tua youtube lui muoi giay",
            "tua youtube ve truoc 10 giay", "tua youtube ve truoc muoi giay",
            "tua lui 10 giay tren youtube", "tua lui muoi giay tren youtube",
            "quay lai 10 giay tren youtube", "quay lai muoi giay tren youtube",
            "tro lai 10 giay tren youtube", "tro lai muoi giay tren youtube",
            "seek youtube back 10 seconds"
        ) -> NgocSiAiLocalCommand.YOUTUBE_SEEK_BACK_10

        startsWithAny(
            "tua toi 10 giay", "tua toi muoi giay",
            "tua tien toi 10 giay", "tua tien toi muoi giay",
            "tien toi 10 giay", "tien toi muoi giay",
            "nhay toi 10 giay", "nhay toi muoi giay",
            "forward 10 seconds", "seek forward 10 seconds"
        ) -> NgocSiAiLocalCommand.SEEK_FORWARD_10

        startsWithAny(
            "lui 10 giay", "lui muoi giay",
            "tua lui 10 giay", "tua lui muoi giay",
            "lui lai 10 giay", "lui lai muoi giay",
            "lui ve 10 giay", "lui ve muoi giay",
            "quay lai 10 giay", "quay lai muoi giay",
            "tro lai 10 giay", "tro lai muoi giay",
            "back 10 seconds", "seek back 10 seconds"
        ) -> NgocSiAiLocalCommand.SEEK_BACK_10

        startsWithAny(
            "them bai dang phat vao yeu thich", "luu bai dang phat vao yeu thich",
            "yeu thich bai dang phat", "favorite current song"
        ) -> NgocSiAiLocalCommand.FAVORITE_CURRENT_TRACK

        // Explicit YouTube controls must not be mistaken for local music controls.
        startsWithAny("tam dung youtube", "tam dung video youtube", "pause youtube", "pause video") ->
            NgocSiAiLocalCommand.PAUSE_YOUTUBE

        // An explicit search-then-play request is one atomic command. Do not stop
        // after rendering search results and leave the user to tap Play.
        extractNgocSiAiYoutubeAutoPlayQuery(input) != null -> NgocSiAiLocalCommand.SEARCH_AND_PLAY_YOUTUBE

        // A YouTube request with an actual search phrase must win over the bare
        // "phát YouTube" resume command (for example, "Phát YouTube nhạc Trịnh").
        extractNgocSiAiYoutubeQuery(input) != null -> NgocSiAiLocalCommand.SEARCH_YOUTUBE

        startsWithAny(
            "phat youtube", "tiep tuc youtube", "tiep tuc video youtube",
            "phat tiep youtube", "resume youtube", "play youtube",
            "phat lai youtube", "phat lai video youtube",
            "tiep tuc phat youtube", "tiep tuc phat video youtube",
            "play video youtube", "resume video youtube"
        ) -> NgocSiAiLocalCommand.RESUME_YOUTUBE
        startsWithAny(
            "video youtube tiep theo", "youtube tiep theo",
            "video tiep theo tren youtube", "next youtube video",
            "chuyen bai youtube", "chuyen bai tren youtube",
            "chuyen video youtube", "chuyen video tren youtube",
            "bai tiep theo youtube", "bai tiep theo tren youtube",
            "bai youtube tiep theo", "ke tiep youtube",
            "bo qua video youtube", "skip youtube", "skip video youtube",
            "next video youtube", "youtube next"
        ) -> NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO
        startsWithAny(
            "video youtube truoc", "youtube truoc",
            "video truoc tren youtube", "previous youtube video",
            "bai truoc youtube", "bai truoc tren youtube",
            "bai youtube truoc", "quay lai video youtube",
            "lui video youtube", "previous video youtube", "youtube previous"
        ) -> NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO
        startsWithAny("bat ngau nhien youtube", "youtube shuffle on", "bat phat ngau nhien youtube") ->
            NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON
        startsWithAny("tat ngau nhien youtube", "youtube shuffle off", "tat phat ngau nhien youtube") ->
            NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF
        startsWithAny("tat lap youtube", "youtube repeat off", "tat che do lap youtube") ->
            NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF
        startsWithAny("lap hang doi youtube", "youtube repeat all", "lap tat ca video youtube") ->
            NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL
        startsWithAny("lap video youtube", "lap mot video youtube", "youtube repeat one") ->
            NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE
        startsWithAny("thich video youtube", "them video youtube vao yeu thich", "yeu thich video dang mo") ->
            NgocSiAiLocalCommand.YOUTUBE_FAVORITE
        startsWithAny("luu video youtube xem sau", "luu video nay xem sau", "them video youtube vao xem sau") ->
            NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER

        // Named feature requests take precedence over generic section navigation.
        extractNgocSiAiRadioStationQuery(input) != null -> NgocSiAiLocalCommand.PLAY_RADIO_STATION
        extractNgocSiAiTvChannelQuery(input) != null -> NgocSiAiLocalCommand.OPEN_TV_CHANNEL
        extractNgocSiAiPlaylistQuery(input, create = true) != null -> NgocSiAiLocalCommand.CREATE_PLAYLIST
        extractNgocSiAiPlaylistQuery(input, create = false) != null -> NgocSiAiLocalCommand.PLAY_NAMED_PLAYLIST
        extractNgocSiAiAddToPlaylistQuery(input) != null -> NgocSiAiLocalCommand.ADD_CURRENT_TRACK_TO_PLAYLIST
        extractNgocSiAiQueueToPlaylistQuery(input) != null -> NgocSiAiLocalCommand.ADD_QUEUE_TO_PLAYLIST

        extractNgocSiAiQueueTrackQuery(input) != null -> NgocSiAiLocalCommand.ADD_TRACK_TO_QUEUE

        // Match named-song commands before generic play/pause phrases.
        extractNgocSiAiTrackQuery(input) != null -> NgocSiAiLocalCommand.PLAY_TRACK

        isAnyOf(
            "phat nhac", "phat nhac di", "phat nhac len", "toi muon phat nhac",
            "phat nhac tren dien thoai", "phat nhac trong may", "phat nhac giup toi",
            "phat bai hat", "phat bai hat di", "nghe bai hat", "nghe bai hat di",
            "nghe nhac di", "toi muon nghe nhac", "mo bai hat", "mo bai hat len", "cho toi nghe nhac",
            "bat nhac", "bat nhac len",
            "tiep tuc phat", "tiep tuc phat nhac", "tiep tuc nghe nhac",
            "bat dau phat nhac", "bat dau nghe nhac", "nghe nhac",
            "cho nhac chay", "cho nhac chay tiep", "phat tiep nhac", "nghe tiep nhac", "tiep tuc nghe",
            "bat nhac lai", "bat lai nhac", "phat lai nhac",
            "mo trinh phat nhac", "phat nhac tiep di", "phat nhac tiep",
            "phat tiep", "phat tiep bai dang nghe",
            "tiep tuc phat bai", "tiep tuc phat bai hat", "cho nhac phat lai",
            "nghe tiep bai dang phat", "nghe tiep bai nay", "phat tiep bai nay",
            "tiep tuc bai dang phat", "cho bai dang phat phat tiep",
            "play", "play music", "resume", "resume music",
            "mo nhac", "mo nhac di", "mo nhac len"
        ) -> NgocSiAiLocalCommand.PLAY

        startsWithAny(
            "dung han", "dung nhac hoan toan", "dung hoan toan", "dung phat han",
            "dung phat hoan toan", "tat han nhac", "tat nhac han", "ngung han",
            "ngung nhac han", "ngung phat han", "ket thuc phat nhac",
            "dung nhac nua", "dung phat nua", "dung phat nhac nua",
            "ngung nhac nua", "ngung phat nua", "ngung phat nhac nua",
            "tat nhac luon", "tat het nhac luon", "dung bai nay luon",
            "stop playback", "stop completely"
        ) -> NgocSiAiLocalCommand.STOP

        isAnyOf(
            "tam dung", "tam dung nhac", "tam dung lai", "tam dung bai hat",
            "tam dung phat nhac", "tam dung phat nhac di", "dung tam thoi",
            "tam ngung nhac", "tam ngung phat nhac", "ngung phat nhac tam thoi",
            "dung phat nhac mot chut",
            "dung mot chut", "nghi mot chut", "ngung lai", "ngung nhac",
            "ngung bai hat", "ngung phat", "ngung phat nhac",
            "dung", "dung lai", "dung lai di", "dung nhac", "dung nhac lai",
            "dung phat", "dung phat di", "dung phat nhac", "dung phat nhac di",
            "tam dung bai hat", "tam dung bai hat mot chut",
            "tam dung nhac mot chut", "dung nhac mot chut",
            "tat nhac", "tat nhac di", "cho dung nhac",
            "pause", "pause music", "stop", "stop music"
        ) -> NgocSiAiLocalCommand.PAUSE

        startsWithAny(
            "tiep theo", "bai tiep theo", "nhac tiep theo", "bai ke tiep", "nhac ke tiep",
            "video tiep theo", "video ke tiep", "video sau", "chuyen video tiep theo",
            "chuyen video ke tiep", "chuyen sang video tiep theo", "sang video tiep theo",
            "chuyen bai", "chuyen bai hat", "doi bai", "phat bai tiep theo",
            "chuyen sang bai tiep theo", "chuyen sang bai ke tiep", "sang bai tiep theo",
            "chuyen sang bai ke", "chuyen qua bai ke", "bai ke", "ke tiep bai hat",
            "chuyen qua bai sau", "bai sau", "nhac tiep", "cho bai tiep theo",
            "chuyen sang bai khac", "sang bai khac", "doi sang bai khac", "doi bai khac",
            "qua bai", "bo bai nay", "bo qua bai nay", "bo qua bai dang phat",
            "skip", "skip song", "skip this song", "skip this track",
            "skip to next song", "next", "next song", "next track"
        ) -> NgocSiAiLocalCommand.NEXT

        // Treat a standalone "quay lại" as Previous, but let "quay lại trang chủ"
        // continue to the more specific home-navigation rule below.
        isAnyOf("quay lai") -> NgocSiAiLocalCommand.PREVIOUS

        startsWithAny(
            "bai truoc", "nhac truoc", "video truoc", "video truoc do",
            "quay lai video truoc", "quay lai video vua roi",
            "quay lai bai truoc", "tro ve bai truoc", "bai truoc do",
            "quay ve bai truoc", "lui lai bai truoc",
            "quay ve bai vua roi", "tro lai bai vua roi", "phat lai bai truoc do",
            "previous", "previous song", "previous track"
        ) -> NgocSiAiLocalCommand.PREVIOUS

        isAnyOf(
            "dang phat bai gi", "bai gi dang phat", "ten bai hat hien tai",
            "bai dang phat la gi", "bai nao dang phat", "ten bai dang phat",
            "bai hat nao dang phat", "what is playing", "current song", "what song is playing"
        ) ->
            NgocSiAiLocalCommand.CURRENT_TRACK

        startsWithAny("bat phat ngau nhien", "bat ngau nhien", "phat ngau nhien",
            "enable shuffle", "turn shuffle on", "shuffle on") -> NgocSiAiLocalCommand.SHUFFLE_ON

        startsWithAny("tat phat ngau nhien", "tat ngau nhien", "tat phat tron",
            "disable shuffle", "turn shuffle off", "shuffle off") -> NgocSiAiLocalCommand.SHUFFLE_OFF

        startsWithAny("tat lap", "tat che do lap", "khong lap", "repeat off", "disable repeat") ->
            NgocSiAiLocalCommand.REPEAT_OFF

        startsWithAny("lap hang doi", "lap danh sach", "lap tat ca", "repeat all", "loop playlist") ->
            NgocSiAiLocalCommand.REPEAT_ALL

        startsWithAny("lap bai", "lap mot bai", "lap mot bai hat", "repeat one", "loop current song") ->
            NgocSiAiLocalCommand.REPEAT_ONE

        // Parse the duration once so spoken-number and hour variants map to the same
        // timer presets as numeric minute phrases. Vague timer mentions remain navigation.
        startsWithAny("huy hen gio", "bo hen gio", "tat hen gio", "xoa hen gio", "cancel sleep timer") ->
            NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER

        sleepTimerMinutes != null -> when (sleepTimerMinutes) {
            15 -> NgocSiAiLocalCommand.SLEEP_TIMER_15
            30 -> NgocSiAiLocalCommand.SLEEP_TIMER_30
            45 -> NgocSiAiLocalCommand.SLEEP_TIMER_45
            60 -> NgocSiAiLocalCommand.SLEEP_TIMER_60
            90 -> NgocSiAiLocalCommand.SLEEP_TIMER_90
            120 -> NgocSiAiLocalCommand.SLEEP_TIMER_120
            else -> NgocSiAiLocalCommand.SLEEP_TIMER_CUSTOM
        }

        isAnyOf(
            "ban lam duoc gi", "ban lam duoc gi vay",
            "ban co the lam gi", "ban co the lam gi vay",
            "ban co the giup gi", "ban co the giup toi gi",
            "ban co the giup toi nhung gi", "toi co the hoi gi", "ai lam duoc gi",
            "tro giup", "huong dan su dung", "cac chuc nang cua ai"
        ) ||
            phrase.contains("ban co the giup toi viec gi") ||
            phrase.contains("ban co the giup minh viec gi") ||
            phrase.contains("hom nay ban giup duoc gi") ||
            phrase.contains("cac chuc nang cua ban") ->
            NgocSiAiLocalCommand.HELP

        startsWithAny(
            "ve trang chu", "mo trang chu", "mo home", "home", "trang chu",
            "quay lai trang chu", "tro ve trang chu", "tro lai trang chu",
            "quay ve trang chu", "mo lai trang chu", "quay lai man hinh chinh",
            "ve lai trang chu", "dua toi ve trang chu", "dua toi ra trang chu",
            "dua toi ve man hinh chinh", "dua toi ra man hinh chinh",
            "dua toi ve giao dien chinh", "dua toi ra giao dien chinh",
            "ve man hinh dau", "tro ve man hinh dau", "quay ve man hinh dau",
            "mo man hinh dau", "ve giao dien ban dau", "tro ve giao dien ban dau",
            "tro ve man hinh chinh", "tro lai man hinh chinh", "ve trang chinh",
            "mo man hinh chinh", "ve man hinh chinh", "ve giao dien chinh",
            "tro ve giao dien chinh", "quay ve giao dien chinh",
            "open home", "go home", "back to home", "return home"
        ) -> NgocSiAiLocalCommand.OPEN_HOME

        startsWithAny(
            "mo yeu thich", "mo muc yeu thich", "mo nhac yeu thich",
            "mo bai hat yeu thich", "mo thu vien yeu thich", "mo danh sach yeu thich",
            "vao yeu thich", "xem yeu thich", "mo favorites", "open favorites"
        ) -> NgocSiAiLocalCommand.OPEN_FAVORITES

        startsWithAny(
            "mo album", "mo cac album", "mo muc album", "xem album", "vao album",
            "mo albums", "open albums"
        ) -> NgocSiAiLocalCommand.OPEN_ALBUMS

        startsWithAny(
            "mo thu muc", "mo cac thu muc", "mo muc thu muc", "xem thu muc",
            "vao thu muc", "open folders"
        ) -> NgocSiAiLocalCommand.OPEN_FOLDERS

        startsWithAny("mo online", "vao online", "mo muc online", "mo nguon truc tuyen", "mo cac nguon") ->
            NgocSiAiLocalCommand.OPEN_ONLINE

        startsWithAny(
            "mo thu vien", "vao thu vien", "mo muc thu vien", "mo nhac trong thu vien",
            "mo thu vien nhac", "vao muc thu vien", "chuyen den thu vien",
            "chuyen sang thu vien", "mo ung dung thu vien", "mo nhac trong dien thoai",
            "mo nhac tren dien thoai", "mo nhac local"
        ) -> NgocSiAiLocalCommand.OPEN_LIBRARY

        extractNgocSiAiDriveQuery(input) != null -> NgocSiAiLocalCommand.SEARCH_DRIVE

        ((phrase.startsWith("tim nhac ") || phrase.startsWith("tim bai hat ") ||
            phrase.startsWith("tim ca khuc ") || phrase.startsWith("kiem nhac ") ||
            phrase.startsWith("kiem bai hat ")) &&
            !phrase.contains("youtube") && !phrase.contains("audius") &&
            !phrase.contains("jamendo") && !phrase.contains("drive")) ->
            NgocSiAiLocalCommand.SEARCH_ONLINE_MUSIC

        (phrase == "tim youtube" || phrase.startsWith("tim youtube ") ||
            phrase.startsWith("kiem youtube ") ||
            ((phrase.startsWith("tim nhac ") || phrase.startsWith("tim bai hat ") ||
                phrase.startsWith("tim video ")) && phrase.contains("youtube"))) ->
            NgocSiAiLocalCommand.SEARCH_YOUTUBE

        startsWithAny(
            "mo youtube", "vao youtube", "mo video youtube", "mo muc youtube",
            "chuyen den youtube", "chuyen den mot youtube", "vao muc youtube",
            "chuyen sang youtube", "mo ung dung youtube", "mo youtube len",
            "bat youtube", "bat video youtube", "open youtube"
        ) ->
            NgocSiAiLocalCommand.OPEN_YOUTUBE

        startsWithAny(
            "mo drive", "vao drive", "mo google drive", "vao google drive", "mo muc drive",
            "chuyen den drive", "chuyen sang drive", "chuyen den google drive",
            "chuyen sang google drive", "vao muc google drive", "mo ung dung google drive",
            "mo google drive cua toi", "mo drive cua toi"
        ) -> NgocSiAiLocalCommand.OPEN_DRIVE

        startsWithAny(
            "mo radio", "vao radio", "mo dai phat thanh", "vao dai phat thanh",
            "vao muc radio", "chuyen den radio", "chuyen sang radio", "mo ung dung radio",
            "chuyen den dai phat thanh", "chuyen sang dai phat thanh", "vao muc dai phat thanh"
        ) -> NgocSiAiLocalCommand.OPEN_RADIO

        startsWithAny(
            "mo tv", "vao tv", "mo truyen hinh", "vao truyen hinh", "mo tivi",
            "vao muc tv", "chuyen den tv", "chuyen sang tv", "mo ung dung tv",
            "vao muc truyen hinh", "chuyen den truyen hinh", "chuyen sang truyen hinh",
            "vao tivi", "chuyen den tivi", "chuyen sang tivi"
        ) -> NgocSiAiLocalCommand.OPEN_TV

        startsWithAny(
            "mo danh sach phat", "mo playlist", "mo cac playlist", "vao playlist",
            "vao muc playlist", "chuyen den playlist", "chuyen sang playlist",
            "chuyen den danh sach phat", "chuyen sang danh sach phat", "mo muc danh sach phat"
        ) -> NgocSiAiLocalCommand.OPEN_PLAYLISTS

        startsWithAny(
            "mo hang doi", "mo danh sach cho phat", "mo queue", "xem hang doi",
            "vao hang doi", "chuyen den hang doi", "chuyen sang hang doi",
            "mo muc hang doi", "xem danh sach cho", "mo hang cho phat"
        ) -> NgocSiAiLocalCommand.OPEN_QUEUE

        startsWithAny(
            "mo trinh phat", "mo man hinh dang phat", "mo player", "xem bai dang phat",
            "mo man hinh phat nhac", "vao man hinh dang phat", "chuyen den trinh phat",
            "chuyen sang trinh phat"
        ) -> NgocSiAiLocalCommand.OPEN_PLAYER

        startsWithAny(
            "mo cai dat", "vao cai dat", "mo phan cai dat", "vao phan cai dat",
            "chuyen den cai dat", "chuyen sang cai dat", "mo thiet lap"
        ) -> NgocSiAiLocalCommand.OPEN_SETTINGS

        startsWithAny("hen gio", "mo hen gio", "dat hen gio", "hen gio tat nhac", "mo bo dem gio") ->
            NgocSiAiLocalCommand.OPEN_SLEEP_TIMER

        else -> null
    }
}

/**
 * Speech services can return several hypotheses. Prefer one that maps to a
 * supported explicit command rather than blindly using the first transcript.
 */
internal fun selectNgocSiAiLocalCommandCandidate(
    candidates: List<String>,
    entryContext: String? = null
): String? {
    val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
    return cleaned.firstOrNull { classifyNgocSiAiLocalCommand(it, entryContext) != null }
        ?: cleaned.firstOrNull()
}

/**
 * After the wake word has activated the short command window, rank Android speech
 * hypotheses by actionable intent. Choosing the first raw hypothesis can miss a
 * valid command when the recognizer offers several close Vietnamese transcriptions.
 */
internal fun selectNgocSiAiActivatedCommandCandidate(
    candidates: List<String>,
    entryContext: String? = null
): String? {
    val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
    return cleaned.firstOrNull { classifyNgocSiAiActivatedCommand(it, entryContext) != null }
        ?: cleaned.firstOrNull()
}

/**
 * Select a voice-control exit only when no higher-ranked alternative is already
 * a recognized app/playback command. Android returns alternatives in recognition
 * order; a weak “stop listening” hypothesis must not override a clear music command.
 */
internal fun selectNgocSiAiStopVoiceControlCandidate(candidates: List<String>): String? {
    val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
    val stopIndex = cleaned.indexOfFirst(::isNgocSiAiStopVoiceControlPhrase)
    if (stopIndex < 0) return null

    val earlierActionableCommand = cleaned.take(stopIndex).any { candidate ->
        classifyNgocSiAiWakePhrase(candidate) != null ||
            classifyNgocSiAiActivatedCommand(candidate) != null ||
            classifyNgocSiAiLocalCommand(candidate) != null
    }
    return if (earlierActionableCommand) null else cleaned[stopIndex]
}

/** Wake-word mode must keep its stricter explicit “AI” requirement. */
internal fun selectNgocSiAiWakePhraseCandidate(
    candidates: List<String>,
    entryContext: String? = null
): String? {
    val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
    return cleaned.firstOrNull { classifyNgocSiAiWakePhrase(it, entryContext) != null }
        ?: cleaned.firstOrNull { isNgocSiAiWakeWordOnly(it) }
        ?: cleaned.firstOrNull()
}


/**
 * Extract a playlist name for explicit create/play actions.
 * "Mở playlist" remains generic navigation; "Phát playlist Nhạc Trịnh" targets a specific list.
 */
internal fun extractNgocSiAiPlaylistQuery(input: String, create: Boolean): String? {
    val raw = input.trim()
    val prefixes = if (create) {
        listOf(
            Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tạo|tao|lập|lap)\s+(?:(?:một|mot)\s+)?(?:playlist|danh sách phát|danh sach phat)\s+""", RegexOption.IGNORE_CASE)
        )
    } else {
        listOf(
            Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|chạy|chay|mở|mo)\s+(?:playlist|danh sách phát|danh sach phat)\s+""", RegexOption.IGNORE_CASE)
        )
    }
    val prefix = prefixes.firstOrNull { it.containsMatchIn(raw) } ?: return null
    val query = trimNgocSiAiExtractedQuery(prefix.replaceFirst(raw, ""))
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    if (normalized.isBlank() || normalized in setOf("moi", "moi nhe", "nhe", "di")) return null
    return query.take(100)
}
/**
 * Extract a playlist name for saving the current queue.
 */
internal fun extractNgocSiAiQueueToPlaylistQuery(input: String): String? {
    val raw = input.trim()
    val patterns = listOf(
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:thêm|them|lưu|luu)\s+(?:toàn bộ\s+|toan bo\s+)?(?:hàng đợi|hang doi|queue)\s+(?:(?:hiện tại|hien tai)\s+)?(?:(?:vào|vao)\s+)?(?:playlist|danh sách phát|danh sach phat)\s+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:lưu|luu)\s+hàng đợi thành\s+(?:playlist|danh sách phát)\s+""", RegexOption.IGNORE_CASE)
    )
    val prefix = patterns.firstOrNull { it.containsMatchIn(raw) } ?: return null
    val query = trimNgocSiAiExtractedQuery(prefix.replaceFirst(raw, ""))
    return query.take(100).takeIf { normalizeNgocSiAiVoicePhrase(it).isNotBlank() }
}

/**
 * Extract a playlist target for adding the currently playing song.
 */
internal fun extractNgocSiAiAddToPlaylistQuery(input: String): String? {
    val raw = input.trim()
    val prefixes = listOf(
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:thêm|them|lưu|luu)\s+(?:bài đang phát|bai dang phat|bài hiện tại|bai hien tai)\s+(?:(?:vào|vao)\s+)?(?:playlist|danh sách phát|danh sach phat)\s+""", RegexOption.IGNORE_CASE)
    )
    val prefix = prefixes.firstOrNull { it.containsMatchIn(raw) } ?: return null
    val query = trimNgocSiAiExtractedQuery(prefix.replaceFirst(raw, ""))
    return query.take(100).takeIf { normalizeNgocSiAiVoicePhrase(it).isNotBlank() }
}

/**
 * Extract an explicit radio-station request. Examples: "Phát Radio VOV3", "Mở Radio VOH FM 95.6".
 */
internal fun extractNgocSiAiRadioStationQuery(input: String): String? {
    val raw = input.trim()
    val prefixes = listOf(
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|mở|mo|nghe|bật|bat)\s+(?:(?:đài|dai)\s+(?:phát|phat)\s+(?:thanh)|đài|dai|radio)\s+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|mở|mo|nghe)\s+(?:vov|voh)\s*""", RegexOption.IGNORE_CASE)
    )
    val prefix = prefixes.firstOrNull { it.containsMatchIn(raw) } ?: return null
    val query = trimNgocSiAiExtractedQuery(prefix.replaceFirst(raw, ""))
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    if (normalized.isBlank() || normalized in setOf("viet nam", "radio", "dai", "nhac")) return null
    return query.take(80)
}
/**
 * Extract an explicit TV channel request such as "Mở VTV3" or "Mở kênh HTV7".
 * Bare "Mở TV" remains the generic TV screen route.
 */
internal fun extractNgocSiAiTvChannelQuery(input: String): String? {
    val raw = input.trim()
    val prefixes = listOf(
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:mở|mo|xem|bật|bat|phát|phat)\s+(?:(?:kênh|kenh)\s+)?(?:(?:tv)\s+)?""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:chuyển|chuyen)\s+(?:(?:sang|đến|den)\s+)?(?:(?:kênh|kenh)\s+)?""", RegexOption.IGNORE_CASE)
    )
    val prefix = prefixes.firstOrNull { it.containsMatchIn(raw) } ?: return null
    val query = trimNgocSiAiExtractedQuery(prefix.replaceFirst(raw, ""))
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    val channelPattern = Regex("""^(?:(?:kenh|tv)\s+)?(?:vtv\s*[1-9]|htv\s*[1-9]|vtv can tho|thvl\s*[1-4]|vtvgo|htvm)$""")
    return query.take(60).takeIf { channelPattern.matches(normalized) }
}
/**
 * Extracts a song title for the non-destructive "add to queue" action.
 * Requires an explicit destination so ordinary requests mentioning a song
 * cannot silently change the queue.
 */
internal fun extractNgocSiAiQueueTrackQuery(input: String): String? {
    val raw = input.trim()
    val pattern = Regex(
        """^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:thêm|them|đưa|dua)\s+(?:bài hát|bai hat|bài|bai|ca khúc|ca khuc)\s+(.+?)\s+(?:vào|vao)\s+(?:hàng đợi|hang doi|queue)\s*[?!.,]*$""",
        RegexOption.IGNORE_CASE
    )
    val match = pattern.matchEntire(raw) ?: return null
    val query = trimNgocSiAiExtractedQuery(match.groupValues[1])
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    if (normalized.length < 2 || normalized in setOf("nhe", "di", "luon", "giup toi")) return null
    return query.take(100)
}

/**
 * Extracts a phrase for filtering the currently opened Drive root/folder list.
 * Search is constrained to the app's existing Drive browser; it does not edit cloud data.
 */
internal fun extractNgocSiAiDriveQuery(input: String): String? {
    val raw = input.trim()
    val patterns = listOf(
        Regex(
            """^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+(?:bài hát|bai hat|nhạc|nhac|file|tệp|tep)\s+(.+?)\s+(?:trên|tren|trong)\s+(?:google\s+)?drive\s*[?!.,]*$""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+(?:(?:trên|tren|trong)\s+)?(?:google\s+)?drive\s*[:,\-]?\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
    )
    val match = patterns.firstNotNullOfOrNull { it.matchEntire(raw) } ?: return null
    val query = trimNgocSiAiExtractedQuery(match.groupValues[1])
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    if (normalized.isBlank() || normalized in setOf("nhac", "bai hat", "file", "tep", "cua toi")) return null
    return query.take(120)
}

/**
 * Resolve a source-neutral search to Drive only when the assistant was opened from Drive.
 * Explicit YouTube/Online source requests are never overridden by page context.
 */
internal fun extractNgocSiAiContextualDriveQuery(
    input: String,
    entryContext: String?
): String? {
    extractNgocSiAiDriveQuery(input)?.let { return it }
    if (normalizeNgocSiAiEntryContext(entryContext) != "Google Drive") return null

    var phrase = stripNgocSiAiOuterQuotationMarks(input).replace(
        Regex("""^(?:(?:hey|ok|alo)\s+)?(?:ai|ngọc sĩ|ngoc si)\b[\s,:-]*""", RegexOption.IGNORE_CASE),
        ""
    ).trim()
    phrase = phrase.replace(Regex("""^(?:hãy|hay)\s+""", RegexOption.IGNORE_CASE), "")

    val match = Regex(
        """^(?:tìm|tim|kiếm|kiem)\s+(?:(?:bài hát|bai hat|ca khúc|ca khuc|nhạc|nhac|file|tệp|tep)\s+)?(.+?)[.!?,;:]*$""",
        RegexOption.IGNORE_CASE
    ).matchEntire(phrase) ?: return null
    var query = match.groupValues[1].trim()
    // Keep a named source explicit: a Drive page must not hijack YouTube or Online searches.
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    if (listOf("youtube", "audius", "jamendo", "online", "truc tuyen", "tren mang")
            .any { normalized.contains(it) }
    ) return null

    query = query.replace(
        Regex(
            """\s+(?:trong|ở|o)\s+(?:thư mục này|thu muc nay|thư mục hiện tại|thu muc hien tai|đây|day)$""",
            RegexOption.IGNORE_CASE
        ),
        ""
    ).trim().trim(',', ':', '-', '.', '?', '!')
    query = trimNgocSiAiExtractedQuery(query)
    val normalizedQuery = normalizeNgocSiAiVoicePhrase(query)
    if (normalizedQuery.isBlank() || normalizedQuery in setOf(
            "nhac", "bai hat", "ca khuc", "file", "tep", "cua toi", "thu muc", "day"
        )
    ) return null
    return query.take(120).takeIf { it.length >= 2 }
}

/**
 * Extract a named song from an explicit play/open/listen command.
 * Short filler words are rejected so "Mở bài hát lên" remains a generic Play command.
 */
internal fun extractNgocSiAiTrackQuery(input: String): String? {
    val raw = input.trim()
    val prefixes = listOf(
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|mở|mo|nghe)\s+(?:bài hát|bai hat|bài|bai|ca khúc|ca khuc)\s+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:play|listen to)\s+(?:song|track)\s+""", RegexOption.IGNORE_CASE)
    )
    val prefix = prefixes.firstOrNull { it.containsMatchIn(raw) } ?: return null
    val query = trimNgocSiAiExtractedQuery(prefix.replaceFirst(raw, ""))
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    if (query.length < 3 || normalized in setOf(
            "di", "len", "nhe", "luon", "di nhe", "len nhe",
            "tiep theo", "ke tiep", "bai tiep theo", "bai ke tiep",
            "sau", "truoc", "bai sau", "bai truoc"
        )
    ) {
        return null
    }
    return query.take(100).takeIf { it.isNotBlank() }
}
/**
 * Extract free-text search for the app's general Online hub (Jamendo, Audius and YouTube).
 */
/** Removes quotation marks wrapped around a complete spoken or typed command. */
private fun stripNgocSiAiOuterQuotationMarks(input: String): String =
    input.trim().trim { it.isWhitespace() || it in "\"“”'‘’" }.trim()

internal fun extractNgocSiAiOnlineQuery(input: String): String? {
    var query = stripNgocSiAiOuterQuotationMarks(input)
    val prefixes = listOf(
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+(?:bài hát|bai hat|ca khúc|ca khuc|nhạc|nhac)\s+""", RegexOption.IGNORE_CASE)
    )
    val prefix = prefixes.firstOrNull { it.containsMatchIn(query) } ?: return null
    query = prefix.replaceFirst(query, "")
    query = query.replace(
        Regex("""\s+(?:(?:trên|tren)\s+)?(?:mạng|mang|online|trực tuyến|truc tuyen)\s*[?!.,]*$""", RegexOption.IGNORE_CASE),
        ""
    ).trim().trim(',', ':', '-', '.', '?', '!')
    query = trimNgocSiAiExtractedQuery(query)
    return query.take(120).takeIf { it.length >= 2 }
}

/**
 * Extracts a YouTube query only when the user explicitly asks to search and then play.
 * Supports combined navigation/search phrasing without turning an ordinary search into
 * unexpected playback. The original spelling and accents are retained for the API query.
 */
internal fun extractNgocSiAiYoutubeAutoPlayQuery(input: String): String? {
    var phrase = stripNgocSiAiOuterQuotationMarks(input)
    phrase = phrase.replace(
        Regex("""^(?:(?:hey|ok|alo)\s+)?(?:ai|ngọc sĩ|ngoc si)\b[\s,:-]*""", RegexOption.IGNORE_CASE),
        ""
    )
    val normalized = normalizeNgocSiAiVoicePhrase(phrase)
    val explicitPlayAfterSearch = Regex(
        """(?:\b(?:roi|va|sau do|then)\s+(?:phat|play))$"""
    ).containsMatchIn(normalized)
    if (!explicitPlayAfterSearch || !normalized.contains("youtube")) return null

    phrase = phrase.replace(
        Regex(
            """\s+(?:(?:rồi|roi|và|va|sau đó|sau do|then)\s+)?(?:phát|phat|play)(?:\s+(?:đi|di))?[.!?,;:]*$""",
            RegexOption.IGNORE_CASE
        ),
        ""
    ).trim()
    phrase = phrase.replace(
        Regex(
            """^(?:(?:hãy|hay)\s+)?(?:mở|mo|vào|vao|chuyển sang|chuyen sang)\s+(?:mục\s+)?youtube\b(?:\s+(?:và|va|rồi|roi))?[\s,;:]*""",
            RegexOption.IGNORE_CASE
        ),
        ""
    ).trim()

    val searchPrefix = Regex(
        """^(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+""",
        RegexOption.IGNORE_CASE
    )
    if (!searchPrefix.containsMatchIn(phrase)) return null
    var query = searchPrefix.replaceFirst(phrase, "").trim()
    query = query.replace(
        Regex("""^youtube\b[\s,;:]*""", RegexOption.IGNORE_CASE),
        ""
    )
    query = query.replace(
        Regex("""^(?:video|bài hát|bai hat|nhạc|nhac)\s+""", RegexOption.IGNORE_CASE),
        ""
    )
    query = query.replace(
        Regex("""\s+(?:(?:trên|tren)\s+)?youtube[.!?,;:]*$""", RegexOption.IGNORE_CASE),
        ""
    )
    query = trimNgocSiAiExtractedQuery(query).trim(',', ':', '-', '.', '?', '!')
    return query.take(120).takeIf { it.length >= 2 }
}

/**
 * Resolve source-neutral search-and-play speech against the page that launched AI.
 * A plain search never autoplays; an explicit play suffix is required. This helper
 * is intentionally disabled outside the YouTube page to avoid guessing the source.
 */
internal fun extractNgocSiAiContextualYoutubeAutoPlayQuery(
    input: String,
    entryContext: String?
): String? {
    // Explicit YouTube requests work from any page. Context inference is the only
    // behavior restricted to YouTube, so other pages never silently change source.
    extractNgocSiAiYoutubeAutoPlayQuery(input)?.let { return it }
    if (normalizeNgocSiAiEntryContext(entryContext) != "YouTube") return null

    var phrase = stripNgocSiAiOuterQuotationMarks(input).replace(
        Regex("""^(?:(?:hey|ok|alo)\s+)?(?:ai|ngọc sĩ|ngoc si)\b[\s,:-]*""", RegexOption.IGNORE_CASE),
        ""
    )
    val normalized = normalizeNgocSiAiVoicePhrase(phrase)
    // An explicit but malformed YouTube request must not be silently rewritten.
    if (normalized.contains("youtube")) return null
    val explicitPlaySuffix = Regex(
        """(?:\b(?:roi|va|sau do|then)\s+(?:phat|play)|\b(?:phat|play)\s+(?:luon|di))$"""
    )
    if (!explicitPlaySuffix.containsMatchIn(normalized)) return null

    phrase = phrase.replace(
        Regex(
            """\s+(?:(?:rồi|roi|và|va|sau đó|sau do|then)\s+)?(?:phát|phat|play)(?:\s+(?:luôn|luon|đi|di))?[.!?,;:]*$""",
            RegexOption.IGNORE_CASE
        ),
        ""
    ).trim()
    val searchPrefix = Regex(
        """^(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+(?:nhạc|nhac|bài hát|bai hat|ca khúc|ca khuc|video)\s+""",
        RegexOption.IGNORE_CASE
    )
    if (!searchPrefix.containsMatchIn(phrase)) return null
    val query = trimNgocSiAiExtractedQuery(
        searchPrefix.replaceFirst(phrase, "").trim()
    ).trim(',', ':', '-', '.', '?', '!')
    return query.take(120).takeIf { it.length >= 2 }
}

/**
 * Resolve a source-neutral, search-only request to YouTube when AI was opened
 * from the YouTube page. Explicitly named sources always keep their own route.
 * This helper never interprets the request as autoplay.
 */
internal fun extractNgocSiAiContextualYoutubeQuery(
    input: String,
    entryContext: String?
): String? {
    extractNgocSiAiYoutubeQuery(input)?.let { return it }
    if (normalizeNgocSiAiEntryContext(entryContext) != "YouTube") return null

    var phrase = stripNgocSiAiOuterQuotationMarks(input).replace(
        Regex("""^(?:(?:hey|ok|alo)\\s+)?(?:ai|ngọc sĩ|ngoc si)\\b[\\s,:-]*""", RegexOption.IGNORE_CASE),
        ""
    ).trim()
    phrase = phrase.replace(Regex("""^(?:hãy|hay)\\s+""", RegexOption.IGNORE_CASE), "")
    val normalized = normalizeNgocSiAiVoicePhrase(phrase)
    // A play suffix belongs to the dedicated search-and-play parser above.
    if (Regex("""(?:\\b(?:roi|va|sau do|then)\\s+(?:phat|play)|\\b(?:phat|play)\\s+(?:luon|di))$""")
            .containsMatchIn(normalized)
    ) return null
    // Do not let a YouTube page override a source explicitly named by the user.
    val explicitOtherSources = listOf(
        "drive", "google drive", "online", "audius", "jamendo",
        "radio", "truc tuyen", "tren mang"
    )
    if (explicitOtherSources.any(normalized::contains)) return null

    val match = Regex(
        """^(?:tìm|tim|kiếm|kiem)\\s+(?:(?:bài hát|bai hat|ca khúc|ca khuc|nhạc|nhac|video)\\s+)?(.+?)[.!?,;:]*$""",
        RegexOption.IGNORE_CASE
    ).matchEntire(phrase) ?: return null
    val query = trimNgocSiAiExtractedQuery(match.groupValues[1])
        .trim(',', ':', '-', '.', '?', '!')
    val normalizedQuery = normalizeNgocSiAiVoicePhrase(query)
    if (normalizedQuery.isBlank() || normalizedQuery in setOf("nhac", "bai hat", "ca khuc", "video")) return null
    return query.take(120).takeIf { it.length >= 2 }
}

/**
 * Extract a user-supplied YouTube search phrase while preserving Vietnamese spelling.
 * The caller sends it only to the app's existing YouTube search UI after an explicit request.
 */
internal fun extractNgocSiAiYoutubeQuery(input: String): String? {
    var query = input.trim()
    val prefixes = listOf(
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+(?:video|bài hát|bai hat|nhạc|nhac)\s+(?:(?:trên|tren)\s+)?youtube\s*[:,\-]?\s*""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+youtube\s*[:,\-]?\s*""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+(?:video|bài hát|bai hat|nhạc|nhac)\s+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ai)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|mở|mo)\s+youtube\s*[:,\-]?\s*""", RegexOption.IGNORE_CASE)
    )
    val prefixIndex = prefixes.indexOfFirst { it.containsMatchIn(query) }
    if (prefixIndex < 0) return null
    // The generic "tìm nhạc ..." form belongs to the Online hub unless the
    // user explicitly mentions YouTube (as a suffix or in the prefix).
    if (prefixIndex == 2 && !normalizeNgocSiAiVoicePhrase(query).contains("youtube")) return null
    val prefix = prefixes[prefixIndex]
    query = prefix.replaceFirst(query, "")
    query = query.replace(
        Regex("""\s+(?:(?:trên|tren)\s+)?youtube\s*[?!.,]*$""", RegexOption.IGNORE_CASE),
        ""
    ).trim().trim(',', ':', '-', '.', '?', '!')
    query = trimNgocSiAiExtractedQuery(query)
    val normalizedQuery = normalizeNgocSiAiVoicePhrase(query)
    // Filler words such as “lên” in “Mở YouTube lên” are not search terms.
    // Preserve meaningful phrases (e.g. “Mở YouTube nhạc Trịnh Công Sơn”).
    if (normalizedQuery in setOf("len", "nhe", "di", "luon", "len nhe", "di nhe")) return null
    return query.take(120).takeIf { it.length >= 2 }
}


/**
 * Reads a supported playback speed from an explicit AI command.
 * Only speeds already exposed by the app's Settings screen are accepted.
 */
internal fun extractNgocSiAiPlaybackSpeed(input: String): Float? {
    val phrase = normalizeNgocSiAiVoicePhrase(input)
    val match = Regex(
        """^(?:dat )?toc do phat (0 5|0 75|1 0|1 25|1 5|1 75|2 0)x?$"""
    ).matchEntire(phrase) ?: return null
    return match.groupValues[1].replace(" ", ".").toFloatOrNull()
}

/**
 * Pure queue-index policy used by the inline YouTube player.
 * repeatMode: 0=off, 1=all, 2=one. A shuffle target is supplied by the caller so this
 * rule can be tested deterministically without invoking randomness.
 */
internal fun selectNgocSiAiManualYouTubeQueueIndex(
    currentIndex: Int,
    queueSize: Int,
    offset: Int,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    shuffleTargetIndex: Int? = null
): Int? {
    if (queueSize <= 0 || currentIndex !in 0 until queueSize || offset !in setOf(-1, 1)) return null
    if (offset > 0 && shuffleEnabled && queueSize > 1) {
        return shuffleTargetIndex?.takeIf {
            it in 0 until queueSize && it != currentIndex
        }
    }
    if (repeatMode == 1) return (currentIndex + offset).mod(queueSize)
    return (currentIndex + offset).takeIf { it in 0 until queueSize }
}

internal fun selectNgocSiAiEndedYouTubeQueueIndex(
    currentIndex: Int,
    queueSize: Int,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    shuffleTargetIndex: Int? = null
): Int? {
    if (queueSize <= 0 || currentIndex !in 0 until queueSize) return null
    return when {
        repeatMode == 2 -> currentIndex
        shuffleEnabled && queueSize > 1 ->
            shuffleTargetIndex?.takeIf { it in 0 until queueSize && it != currentIndex }
        repeatMode == 1 -> (currentIndex + 1).mod(queueSize)
        currentIndex + 1 < queueSize -> currentIndex + 1
        else -> null
    }
}

/**
 * Converts the model's constrained app-action name into the same explicit phrases
 * already understood by the deterministic local command router. Unknown actions are
 * rejected; Gemini never receives an unrestricted Android Intent or method name.
 */
internal fun buildNgocSiAiToolCommandPhrase(action: String, query: String = ""): String? {
    val normalizedAction = action.trim().lowercase(Locale.ROOT)
    val safeQuery = query.trim()
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .take(180)
    val needsQuery = setOf(
        "play_track", "add_track_to_queue", "play_radio_station", "open_tv_channel",
        "create_playlist", "play_playlist", "add_current_to_playlist",
        "add_queue_to_playlist", "search_youtube", "search_and_play_youtube", "search_online_music", "search_drive",
        "set_playback_speed", "seek_by_seconds", "youtube_seek_by_seconds",
        "sleep_timer_custom"
    )
    if (normalizedAction in needsQuery && safeQuery.isBlank()) return null

    return when (normalizedAction) {
        "play" -> "phát nhạc"
        "play_second" -> "phát bài thứ hai"
        "play_track" -> "phát bài hát $safeQuery"
        "add_track_to_queue" -> "thêm bài hát $safeQuery vào hàng đợi"
        "search_drive" -> "tìm Drive $safeQuery"
        "pause" -> "tạm dừng nhạc"
        "stop" -> "dừng hẳn nhạc"
        "next" -> "chuyển bài"
        "previous" -> "bài trước"
        "current_track" -> "đang phát bài gì"
        "volume_up" -> "tăng âm lượng"
        "volume_down" -> "giảm âm lượng"
        "mute" -> "tắt tiếng"
        "unmute" -> "bật tiếng"
        "set_playback_speed" -> {
            val speed = safeQuery.trim().lowercase(Locale.ROOT).removeSuffix("x").replace(',', '.')
            if (speed !in setOf("0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0")) return null
            "đặt tốc độ phát ${speed}x"
        }
        "seek_forward_10" -> "tua tới 10 giây"
        "seek_back_10" -> "lùi 10 giây"
        "seek_by_seconds", "youtube_seek_by_seconds" -> {
            val seconds = safeQuery.trim().toIntOrNull() ?: return null
            if (seconds == 0 || kotlin.math.abs(seconds) > 120) return null
            val forward = seconds > 0
            val duration = kotlin.math.abs(seconds)
            val youtube = normalizedAction == "youtube_seek_by_seconds"
            when {
                youtube && forward -> "tua youtube tới $duration giây"
                youtube -> "tua youtube lùi $duration giây"
                forward -> "tua tới $duration giây"
                else -> "lùi $duration giây"
            }
        }
        "favorite_current_track" -> "thêm bài đang phát vào yêu thích"
        "shuffle_on" -> "bật phát ngẫu nhiên"
        "shuffle_off" -> "tắt phát ngẫu nhiên"
        "repeat_off" -> "tắt lặp"
        "repeat_all" -> "lặp hàng đợi"
        "repeat_one" -> "lặp bài"
        "open_home" -> "về trang chủ"
        "open_library" -> "mở thư viện"
        "open_favorites" -> "mở yêu thích"
        "open_albums" -> "mở album"
        "open_folders" -> "mở thư mục"
        "open_online" -> "mở online"
        "search_online_music" -> "tìm nhạc $safeQuery"
        "open_youtube" -> "mở youtube"
        "search_youtube" -> "tìm youtube $safeQuery"
        "search_and_play_youtube" -> "tìm youtube $safeQuery rồi phát"
        "youtube_play" -> "phát youtube"
        "youtube_pause" -> "tạm dừng youtube"
        "youtube_next" -> "youtube tiếp theo"
        "youtube_previous" -> "youtube trước"
        "youtube_seek_forward_10" -> "tua youtube tới 10 giây"
        "youtube_seek_back_10" -> "tua youtube lùi 10 giây"
        "youtube_shuffle_on" -> "bật ngẫu nhiên youtube"
        "youtube_shuffle_off" -> "tắt ngẫu nhiên youtube"
        "youtube_repeat_off" -> "tắt lặp youtube"
        "youtube_repeat_all" -> "lặp hàng đợi youtube"
        "youtube_repeat_one" -> "lặp video youtube"
        "youtube_favorite" -> "thích video youtube"
        "youtube_watch_later" -> "lưu video youtube xem sau"
        "open_drive" -> "mở google drive"
        "open_radio" -> "mở radio"
        "play_radio_station" -> "phát radio $safeQuery"
        "open_tv" -> "mở tv"
        "open_tv_channel" -> "mở kênh $safeQuery"
        "create_playlist" -> "tạo playlist $safeQuery"
        "play_playlist" -> "phát playlist $safeQuery"
        "add_current_to_playlist" -> "thêm bài đang phát vào playlist $safeQuery"
        "add_queue_to_playlist" -> "thêm hàng đợi vào playlist $safeQuery"
        "open_playlists" -> "mở danh sách phát"
        "open_queue" -> "mở hàng đợi"
        "open_player" -> "mở trình phát"
        "open_settings" -> "mở cài đặt"
        "open_sleep_timer" -> "mở hẹn giờ"
        "sleep_timer_15" -> "hẹn giờ tắt nhạc sau 15 phút"
        "sleep_timer_30" -> "hẹn giờ tắt nhạc sau 30 phút"
        "sleep_timer_45" -> "hẹn giờ tắt nhạc sau 45 phút"
        "sleep_timer_60" -> "hẹn giờ tắt nhạc sau 60 phút"
        "sleep_timer_90" -> "hẹn giờ tắt nhạc sau 90 phút"
        "sleep_timer_120" -> "hẹn giờ tắt nhạc sau 120 phút"
        "sleep_timer_custom" -> {
            val minutes = safeQuery.toIntOrNull()?.takeIf { it in 1..1_440 } ?: return null
            "hẹn giờ tắt nhạc sau $minutes phút"
        }
        "cancel_sleep_timer" -> "hủy hẹn giờ"
        "help" -> "trợ giúp"
        else -> null
    }
}

internal data class NgocSiAiToolCommandRoute(
    val command: NgocSiAiLocalCommand,
    val phrase: String
)

/**
 * Shared allowlist boundary for Gemini actions. Both the chat screen and hands-free
 * service use this exact conversion; the service additionally requires an activated
 * AI session before a tool action can reach the local command executor.
 */
internal fun resolveNgocSiAiToolCommand(
    action: String,
    query: String,
    requireActivatedSession: Boolean,
    entryContext: String? = null
): NgocSiAiToolCommandRoute? {
    val phrase = buildNgocSiAiToolCommandPhrase(action, query) ?: return null
    val command = if (requireActivatedSession) {
        classifyNgocSiAiActivatedCommand("AI, $phrase", entryContext)
    } else {
        classifyNgocSiAiLocalCommand(phrase, entryContext)
    } ?: return null
    return NgocSiAiToolCommandRoute(command = command, phrase = phrase)
}
