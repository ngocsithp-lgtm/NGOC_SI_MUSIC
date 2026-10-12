package com.ngocsi.music

import java.text.Normalizer
import java.util.Locale

/**
 * Small allowlist for direct, deterministic music-player actions.
 * Unrecognized text is sent to the conversational model instead.
 */
enum class NgocSiAiMusicCommand {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    SHUFFLE_ON,
    SHUFFLE_OFF,
    REPEAT_ONE,
    REPEAT_ALL,
    REPEAT_OFF,
    OPEN_SLEEP_TIMER,
    CANCEL_SLEEP_TIMER,
    SET_SLEEP_TIMER_5,
    SET_SLEEP_TIMER_10,
    SET_SLEEP_TIMER_15,
    SET_SLEEP_TIMER_20,
    SET_SLEEP_TIMER_30,
    SET_SLEEP_TIMER_45,
    SET_SLEEP_TIMER_60,
    SET_SLEEP_TIMER_90,
    OPEN_LIBRARY,
    OPEN_YOUTUBE,
    OPEN_RADIO,
    OPEN_QUEUE,
    OPEN_PLAYER,
    OPEN_TV,
    OPEN_DRIVE,
    OPEN_SETTINGS
}

internal object NgocSiAiMusicCommandParser {
    fun parse(input: String): NgocSiAiMusicCommand? {
        var text = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace('đ', 'd')
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Permit common polite prefixes, but don't match arbitrary mid-sentence mentions.
        val prefixes = listOf("ban hay ", "vui long ", "lam on ", "giup toi ", "toi muon ", "hay ")
        for (prefix in prefixes) {
            if (text.startsWith(prefix)) {
                text = text.removePrefix(prefix).trim()
                break
            }
        }

        return when {
            startsWithAny(text, "tat hen gio", "huy hen gio", "dung hen gio", "cancel sleep timer", "turn off sleep timer") ->
                NgocSiAiMusicCommand.CANCEL_SLEEP_TIMER

            startsWithAny(text, "mo hen gio", "mo hen gio tat nhac", "hen gio cai dat", "open sleep timer") ->
                NgocSiAiMusicCommand.OPEN_SLEEP_TIMER

            startsWithAny(text, "hen gio tat nhac sau 5 phut", "hen gio tat nhac 5 phut", "hen gio tat nhac trong 5 phut", "hen gio sau 5 phut", "hen gio 5 phut", "tat nhac sau 5 phut", "sleep timer 5") ->
                NgocSiAiMusicCommand.SET_SLEEP_TIMER_5

            startsWithAny(text, "hen gio tat nhac sau 10 phut", "hen gio tat nhac 10 phut", "hen gio tat nhac trong 10 phut", "hen gio sau 10 phut", "hen gio 10 phut", "tat nhac sau 10 phut", "sleep timer 10") ->
                NgocSiAiMusicCommand.SET_SLEEP_TIMER_10

            startsWithAny(text, "hen gio tat nhac sau 15 phut", "hen gio tat nhac 15 phut", "hen gio tat nhac trong 15 phut", "hen gio sau 15 phut", "hen gio 15 phut", "tat nhac sau 15 phut", "sleep timer 15") ->
                NgocSiAiMusicCommand.SET_SLEEP_TIMER_15

            startsWithAny(text, "hen gio tat nhac sau 20 phut", "hen gio tat nhac 20 phut", "hen gio tat nhac trong 20 phut", "hen gio sau 20 phut", "hen gio 20 phut", "tat nhac sau 20 phut", "sleep timer 20") ->
                NgocSiAiMusicCommand.SET_SLEEP_TIMER_20

            startsWithAny(text, "hen gio tat nhac sau 30 phut", "hen gio tat nhac 30 phut", "hen gio tat nhac trong 30 phut", "hen gio sau 30 phut", "hen gio 30 phut", "tat nhac sau 30 phut", "sleep timer 30") ->
                NgocSiAiMusicCommand.SET_SLEEP_TIMER_30

            startsWithAny(text, "hen gio tat nhac sau 45 phut", "hen gio tat nhac 45 phut", "hen gio tat nhac trong 45 phut", "hen gio sau 45 phut", "hen gio 45 phut", "tat nhac sau 45 phut", "sleep timer 45") ->
                NgocSiAiMusicCommand.SET_SLEEP_TIMER_45

            startsWithAny(text, "hen gio tat nhac sau 60 phut", "hen gio tat nhac 60 phut", "hen gio tat nhac trong 60 phut", "hen gio sau 60 phut", "hen gio 60 phut", "tat nhac sau 60 phut", "sleep timer 60") ->
                NgocSiAiMusicCommand.SET_SLEEP_TIMER_60

            startsWithAny(text, "hen gio tat nhac sau 90 phut", "hen gio tat nhac 90 phut", "hen gio tat nhac trong 90 phut", "hen gio sau 90 phut", "hen gio 90 phut", "tat nhac sau 90 phut", "sleep timer 90") ->
                NgocSiAiMusicCommand.SET_SLEEP_TIMER_90

            startsWithAny(text, "tat xao tron", "tat phat ngau nhien", "shuffle off", "stop shuffle") ->
                NgocSiAiMusicCommand.SHUFFLE_OFF

            startsWithAny(text, "bat xao tron", "xao tron", "phat ngau nhien", "bat phat ngau nhien", "shuffle on", "shuffle", "random play") ->
                NgocSiAiMusicCommand.SHUFFLE_ON

            startsWithAny(text, "lap lai mot bai", "lap lai bai nay", "lap bai hien tai", "repeat one", "repeat current") ->
                NgocSiAiMusicCommand.REPEAT_ONE

            startsWithAny(text, "lap lai tat ca", "lap lai toan bo", "lap lai playlist", "bat lap lai", "repeat all") ->
                NgocSiAiMusicCommand.REPEAT_ALL

            startsWithAny(text, "tat lap lai", "huy lap lai", "khong lap lai", "repeat off", "no repeat") ->
                NgocSiAiMusicCommand.REPEAT_OFF

            startsWithAny(text, "mo thu vien", "vao thu vien", "mo danh sach bai hat") ->
                NgocSiAiMusicCommand.OPEN_LIBRARY

            startsWithAny(text, "mo youtube", "vao youtube", "mo tab youtube") ->
                NgocSiAiMusicCommand.OPEN_YOUTUBE

            startsWithAny(text, "mo radio", "vao radio", "mo dai phat thanh", "mo dai radio") ->
                NgocSiAiMusicCommand.OPEN_RADIO

            startsWithAny(text, "mo hang doi", "xem hang doi", "mo danh sach cho", "xem danh sach cho") ->
                NgocSiAiMusicCommand.OPEN_QUEUE

            startsWithAny(text, "mo trinh phat", "mo bai dang phat", "mo man hinh dang phat", "mo player") ->
                NgocSiAiMusicCommand.OPEN_PLAYER

            startsWithAny(text, "mo tv", "vao tv", "mo truyen hinh", "vao truyen hinh") ->
                NgocSiAiMusicCommand.OPEN_TV

            startsWithAny(text, "mo google drive", "vao google drive", "mo drive", "vao drive") ->
                NgocSiAiMusicCommand.OPEN_DRIVE

            startsWithAny(text, "mo cai dat", "vao cai dat", "mo settings") ->
                NgocSiAiMusicCommand.OPEN_SETTINGS

            startsWithAny(
                text,
                "bai tiep theo", "nhac tiep theo", "chuyen bai", "qua bai",
                "phat bai tiep theo", "phat nhac tiep theo", "next", "next song", "next bai"
            ) -> NgocSiAiMusicCommand.NEXT

            startsWithAny(text, "phat bai truoc", "phat nhac truoc") ->
                NgocSiAiMusicCommand.PREVIOUS

            startsWithAny(
                text,
                "bai truoc", "nhac truoc", "quay lai bai truoc", "lui bai",
                "previous", "previous song", "bai vua roi"
            ) -> NgocSiAiMusicCommand.PREVIOUS

            startsWithAny(
                text,
                "tam dung nhac", "dung nhac", "dung phat nhac",
                "pause", "pause music", "tam dung"
            ) -> NgocSiAiMusicCommand.PAUSE

            startsWithAny(
                text,
                "tiep tuc phat", "phat tiep", "bat nhac", "phat nhac",
                "cho phat nhac", "play", "play music", "resume"
            ) -> NgocSiAiMusicCommand.PLAY

            else -> null
        }
    }

    private fun startsWithAny(text: String, vararg phrases: String): Boolean =
        phrases.any { text == it || text.startsWith("$it ") }
}
