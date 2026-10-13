package com.ngocsi.music

import java.text.Normalizer
import java.util.Locale

internal enum class NgocSiAiLocalCommand {
    PLAY,
    PLAY_SECOND,
    PLAY_TRACK,
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
    CANCEL_SLEEP_TIMER,
    HELP
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
    NgocSiAiLocalCommand.SHUFFLE_ON,
    NgocSiAiLocalCommand.SHUFFLE_OFF,
    NgocSiAiLocalCommand.REPEAT_OFF,
    NgocSiAiLocalCommand.REPEAT_ALL,
    NgocSiAiLocalCommand.REPEAT_ONE
)

internal fun ngocSiAiCommandRequiresPlayerConnection(command: NgocSiAiLocalCommand): Boolean =
    command in NGOC_SI_AI_PLAYER_CONNECTION_COMMANDS


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
    NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_ON,
    NgocSiAiLocalCommand.YOUTUBE_SHUFFLE_OFF,
    NgocSiAiLocalCommand.YOUTUBE_REPEAT_OFF,
    NgocSiAiLocalCommand.YOUTUBE_REPEAT_ALL,
    NgocSiAiLocalCommand.YOUTUBE_REPEAT_ONE,
    NgocSiAiLocalCommand.YOUTUBE_FAVORITE,
    NgocSiAiLocalCommand.YOUTUBE_WATCH_LATER,
    NgocSiAiLocalCommand.CURRENT_TRACK,
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
        " nhe nha", " voi nhe", " giup toi", " giup minh", " cho toi",
        " nhe", " nha", " voi", " di", " len"
    )
    while (true) {
        val suffix = suffixes.firstOrNull { phrase.endsWith(it) && phrase.length > it.length } ?: break
        phrase = phrase.removeSuffix(suffix).trimEnd()
    }
    return phrase
}

/** Keeps the user's spelling while removing polite speech tails from extracted names. */
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
        "ngoc si", "ngoc si oi", "ok ngoc si", "alo ngoc si"
    )

/** Accepts a command only when wake phrase and command occur in the same utterance. */
internal fun classifyNgocSiAiWakePhrase(input: String): NgocSiAiLocalCommand? {
    val normalized = normalizeNgocSiAiVoicePhrase(input)
    if (!normalized.startsWith("ngoc si ")) return null
    val commandPhrase = normalized.removePrefix("ngoc si ").trim()
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

    return classifyNgocSiAiActivatedCommand(commandPhrase)
}

/**
 * Once the user has explicitly activated the wake word, accept only the safe
 * playback-control allowlist. Navigation, account and timer actions stay out of
 * always-listening mode.
 */
internal fun classifyNgocSiAiActivatedCommand(input: String): NgocSiAiLocalCommand? =
    classifyNgocSiAiLocalCommand(input)?.takeIf { it in NGOC_SI_AI_WAKE_WORD_COMMANDS }

internal fun classifyNgocSiAiLocalCommand(input: String): NgocSiAiLocalCommand? {
    var phrase = Normalizer.normalize(input.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace('đ', 'd')
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()
    if (phrase.isBlank()) return null

    val politePrefixes = listOf("ngoc si ", "hay ", "vui long ", "lam on ")
    while (true) {
        val prefix = politePrefixes.firstOrNull { phrase.startsWith(it) } ?: break
        phrase = phrase.removePrefix(prefix).trim()
    }
    phrase = trimNgocSiAiNormalizedCommandFillers(phrase)

    fun isAnyOf(vararg options: String) = phrase in options.toSet()
    fun startsWithAny(vararg options: String) = options.any { phrase == it || phrase.startsWith("$it ") }

    return when {
        startsWithAny(
            "hat bai thu hai trong danh sach", "phat bai thu hai trong danh sach",
            "phat bai thu hai", "phat bai so hai", "hat bai so hai",
            "bai thu hai trong danh sach", "bai so hai trong danh sach",
            "cho phat bai thu hai", "phat bai thu hai trong hang doi"
        ) -> NgocSiAiLocalCommand.PLAY_SECOND

        // Explicit YouTube controls must not be mistaken for local music controls.
        startsWithAny("tam dung youtube", "tam dung video youtube", "pause youtube", "pause video") ->
            NgocSiAiLocalCommand.PAUSE_YOUTUBE

        // A YouTube request with an actual search phrase must win over the bare
        // "phát YouTube" resume command (for example, "Phát YouTube nhạc Trịnh").
        extractNgocSiAiYoutubeQuery(input) != null -> NgocSiAiLocalCommand.SEARCH_YOUTUBE

        startsWithAny("phat youtube", "tiep tuc youtube", "tiep tuc video youtube", "phat tiep youtube", "resume youtube", "play youtube") ->
            NgocSiAiLocalCommand.RESUME_YOUTUBE
        startsWithAny("video youtube tiep theo", "youtube tiep theo", "video tiep theo tren youtube", "next youtube video") ->
            NgocSiAiLocalCommand.NEXT_YOUTUBE_VIDEO
        startsWithAny("video youtube truoc", "youtube truoc", "video truoc tren youtube", "previous youtube video") ->
            NgocSiAiLocalCommand.PREVIOUS_YOUTUBE_VIDEO
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
            "play", "play music", "resume", "resume music",
            "mo nhac", "mo nhac di", "mo nhac len"
        ) -> NgocSiAiLocalCommand.PLAY

        startsWithAny(
            "dung han", "dung nhac hoan toan", "dung hoan toan", "dung phat han",
            "ngung han", "ngung phat han", "stop playback", "stop completely"
        ) -> NgocSiAiLocalCommand.STOP

        isAnyOf(
            "tam dung", "tam dung nhac", "tam dung lai", "tam dung bai hat",
            "tam dung phat nhac", "tam dung phat nhac di",
            "dung", "dung lai", "dung lai di", "dung nhac", "dung nhac lai",
            "dung phat", "dung phat di", "dung phat nhac", "dung phat nhac di",
            "ngung phat nhac", "tat nhac", "tat nhac di",
            "pause", "pause music", "stop", "stop music"
        ) -> NgocSiAiLocalCommand.PAUSE

        startsWithAny(
            "tiep theo", "bai tiep theo", "nhac tiep theo", "bai ke tiep", "nhac ke tiep",
            "chuyen bai", "chuyen sang bai tiep theo", "chuyen sang bai ke tiep", "sang bai tiep theo",
            "chuyen qua bai sau", "bai sau", "nhac tiep",
            "qua bai", "bo qua bai nay", "skip", "skip song", "skip to next song", "next", "next song"
        ) -> NgocSiAiLocalCommand.NEXT

        // Treat a standalone "quay lại" as Previous, but let "quay lại trang chủ"
        // continue to the more specific home-navigation rule below.
        isAnyOf("quay lai") -> NgocSiAiLocalCommand.PREVIOUS

        startsWithAny(
            "bai truoc", "nhac truoc", "quay lai bai truoc", "tro ve bai truoc", "bai truoc do",
            "quay ve bai truoc", "lui lai bai truoc",
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

        // Timer phrases require an explicit duration; vague mentions only open the timer dialog.
        startsWithAny("huy hen gio", "bo hen gio", "tat hen gio", "xoa hen gio", "cancel sleep timer") ->
            NgocSiAiLocalCommand.CANCEL_SLEEP_TIMER

        phrase.contains("hen gio") && phrase.contains("15 phut") ||
            startsWithAny("tat nhac sau 15 phut", "dung nhac sau 15 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_15
        phrase.contains("hen gio") && phrase.contains("30 phut") ||
            startsWithAny("tat nhac sau 30 phut", "dung nhac sau 30 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_30
        phrase.contains("hen gio") && phrase.contains("45 phut") ||
            startsWithAny("tat nhac sau 45 phut", "dung nhac sau 45 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_45
        phrase.contains("hen gio") && phrase.contains("60 phut") ||
            startsWithAny("tat nhac sau 60 phut", "dung nhac sau 60 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_60
        phrase.contains("hen gio") && phrase.contains("90 phut") ||
            startsWithAny("tat nhac sau 90 phut", "dung nhac sau 90 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_90
        phrase.contains("hen gio") && phrase.contains("120 phut") ||
            startsWithAny("tat nhac sau 120 phut", "dung nhac sau 120 phut") ->
            NgocSiAiLocalCommand.SLEEP_TIMER_120

        isAnyOf("ban lam duoc gi", "ban co the giup gi", "ban co the giup toi nhung gi", "toi co the hoi gi", "ai lam duoc gi", "tro giup", "huong dan su dung", "cac chuc nang cua ai") ||
            phrase.contains("ban co the giup toi viec gi") ||
            phrase.contains("ban co the giup minh viec gi") ||
            phrase.contains("hom nay ban giup duoc gi") ||
            phrase.contains("cac chuc nang cua ban") ->
            NgocSiAiLocalCommand.HELP

        startsWithAny(
            "ve trang chu", "mo trang chu", "mo home", "home", "trang chu",
            "quay lai trang chu", "tro ve trang chu", "tro lai trang chu",
            "quay ve trang chu", "mo lai trang chu", "quay lai man hinh chinh",
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

        ((phrase.startsWith("tim nhac ") || phrase.startsWith("tim bai hat ") ||
            phrase.startsWith("tim ca khuc ") || phrase.startsWith("kiem nhac ") ||
            phrase.startsWith("kiem bai hat ")) &&
            !phrase.contains("youtube") && !phrase.contains("audius") && !phrase.contains("jamendo")) ->
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
internal fun selectNgocSiAiLocalCommandCandidate(candidates: List<String>): String? {
    val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
    return cleaned.firstOrNull { classifyNgocSiAiLocalCommand(it) != null }
        ?: cleaned.firstOrNull()
}

/**
 * After the wake word has activated the short command window, rank Android speech
 * hypotheses by actionable intent. Choosing the first raw hypothesis can miss a
 * valid command when the recognizer offers several close Vietnamese transcriptions.
 */
internal fun selectNgocSiAiActivatedCommandCandidate(candidates: List<String>): String? {
    val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
    return cleaned.firstOrNull { classifyNgocSiAiActivatedCommand(it) != null }
        ?: cleaned.firstOrNull()
}

/** Wake-word mode must keep its stricter explicit “Ngọc Sĩ” requirement. */
internal fun selectNgocSiAiWakePhraseCandidate(candidates: List<String>): String? {
    val cleaned = candidates.map { it.trim() }.filter { it.isNotBlank() }
    return cleaned.firstOrNull { classifyNgocSiAiWakePhrase(it) != null }
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
            Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tạo|tao|lập|lap)\s+(?:(?:một|mot)\s+)?(?:playlist|danh sách phát|danh sach phat)\s+""", RegexOption.IGNORE_CASE)
        )
    } else {
        listOf(
            Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|chạy|chay|mở|mo)\s+(?:playlist|danh sách phát|danh sach phat)\s+""", RegexOption.IGNORE_CASE)
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
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:thêm|them|lưu|luu)\s+(?:toàn bộ\s+|toan bo\s+)?(?:hàng đợi|hang doi|queue)\s+(?:(?:hiện tại|hien tai)\s+)?(?:(?:vào|vao)\s+)?(?:playlist|danh sách phát|danh sach phat)\s+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:lưu|luu)\s+hàng đợi thành\s+(?:playlist|danh sách phát)\s+""", RegexOption.IGNORE_CASE)
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
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:thêm|them|lưu|luu)\s+(?:bài đang phát|bai dang phat|bài hiện tại|bai hien tai)\s+(?:(?:vào|vao)\s+)?(?:playlist|danh sách phát|danh sach phat)\s+""", RegexOption.IGNORE_CASE)
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
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|mở|mo|nghe|bật|bat)\s+(?:(?:đài|dai)\s+(?:phát|phat)\s+(?:thanh)|đài|dai|radio)\s+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|mở|mo|nghe)\s+(?:vov|voh)\s*""", RegexOption.IGNORE_CASE)
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
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:mở|mo|xem|bật|bat|phát|phat)\s+(?:(?:kênh|kenh)\s+)?(?:(?:tv)\s+)?""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:chuyển|chuyen)\s+(?:(?:sang|đến|den)\s+)?(?:(?:kênh|kenh)\s+)?""", RegexOption.IGNORE_CASE)
    )
    val prefix = prefixes.firstOrNull { it.containsMatchIn(raw) } ?: return null
    val query = trimNgocSiAiExtractedQuery(prefix.replaceFirst(raw, ""))
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    val channelPattern = Regex("""^(?:(?:kenh|tv)\s+)?(?:vtv\s*[1-9]|htv\s*[1-9]|vtv can tho|thvl\s*[1-4]|vtvgo|htvm)$""")
    return query.take(60).takeIf { channelPattern.matches(normalized) }
}
/**
 * Extract a named song from an explicit play/open/listen command.
 * Short filler words are rejected so "Mở bài hát lên" remains a generic Play command.
 */
internal fun extractNgocSiAiTrackQuery(input: String): String? {
    val raw = input.trim()
    val prefixes = listOf(
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|mở|mo|nghe)\s+(?:bài hát|bai hat|bài|bai|ca khúc|ca khuc)\s+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:play|listen to)\s+(?:song|track)\s+""", RegexOption.IGNORE_CASE)
    )
    val prefix = prefixes.firstOrNull { it.containsMatchIn(raw) } ?: return null
    val query = trimNgocSiAiExtractedQuery(prefix.replaceFirst(raw, ""))
    val normalized = normalizeNgocSiAiVoicePhrase(query)
    if (query.length < 3 || normalized in setOf("di", "len", "nhe", "luon", "di nhe", "len nhe")) {
        return null
    }
    return query.take(100).takeIf { it.isNotBlank() }
}
/**
 * Extract free-text search for the app's general Online hub (Jamendo, Audius and YouTube).
 */
internal fun extractNgocSiAiOnlineQuery(input: String): String? {
    var query = input.trim()
    val prefixes = listOf(
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+(?:bài hát|bai hat|ca khúc|ca khuc|nhạc|nhac)\s+""", RegexOption.IGNORE_CASE)
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
 * Extract a user-supplied YouTube search phrase while preserving Vietnamese spelling.
 * The caller sends it only to the app's existing YouTube search UI after an explicit request.
 */
internal fun extractNgocSiAiYoutubeQuery(input: String): String? {
    var query = input.trim()
    val prefixes = listOf(
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+(?:video|bài hát|bai hat|nhạc|nhac)\s+(?:(?:trên|tren)\s+)?youtube\s*[:,\-]?\s*""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+youtube\s*[:,\-]?\s*""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:tìm|tim|kiếm|kiem)\s+(?:video|bài hát|bai hat|nhạc|nhac)\s+""", RegexOption.IGNORE_CASE),
        Regex("""^(?:(?:ngọc sĩ|ngoc si)[\s,:]+)?(?:(?:hãy|hay)\s+)?(?:phát|phat|mở|mo)\s+youtube\s*[:,\-]?\s*""", RegexOption.IGNORE_CASE)
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