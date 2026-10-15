package com.ngocsi.music

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds a separately hosted audio recording matching a YouTube video's title.
 * It never extracts or downloads audio from YouTube. Local/saved audio is preferred,
 * followed by public Audius tracks and Openverse entries restricted to open licenses.
 */
data class BackgroundAudioCandidate(
    val title: String,
    val artist: String,
    val uri: Uri,
    val artworkUri: Uri?,
    val sourceLabel: String,
    val licenseLabel: String,
    val landingUrl: String?,
    val matchScore: Double
)

internal object BackgroundAudioMatchRules {
    private val metadataWords = Regex(
        """(?i)\b(official(\s+(music\s+)?video|\s+audio)?|lyrics?\s+video|lyric\s+video|audio\s+only|visuali[sz]er|karaoke|4k|hd|uhd|mv|remaster(ed)?|music\s+video)\b"""
    )
    private val bracketMetadata = Regex(
        """(?i)[\[(][^\])]*(official|lyrics?|visuali[sz]er|karaoke|remaster|music video|audio only|4k|hd)[^\])]*[\])]"""
    )
    private val trailingMetadata = Regex(
        """(?i)\s*(?:\||•)\s*(official.*|lyrics?.*|visuali[sz]er.*|karaoke.*|music video.*|audio only.*)$"""
    )
    private val separators = Regex("""\s+[-–—|•]\s+""")
    private val whitespace = Regex("""\s+""")
    private val nonWord = Regex("""[^a-z0-9]+""")

    fun normalize(value: String): String {
        val unmarked = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return nonWord.replace(unmarked.lowercase(Locale.ROOT), " ")
            .trim()
            .let(whitespace::replace)
    }

    fun cleanTitle(value: String): String = value
        .replace(bracketMetadata, " ")
        .replace(trailingMetadata, " ")
        .replace(metadataWords, " ")
        .replace(Regex("""\b(official|vevo)\b""", RegexOption.IGNORE_CASE), " ")
        .replace(whitespace, " ")
        .trim()

    fun titleVariants(value: String): List<String> {
        val cleaned = cleanTitle(value)
        val direct = normalize(cleaned)
        val parts = separators.split(cleaned).map(::normalize).filter { it.length >= 4 }
        return (listOf(direct) + parts).filter { it.length >= 4 }.distinct()
    }

    fun titleScore(expectedTitle: String, candidateTitle: String): Double {
        val expected = titleVariants(expectedTitle)
        val actual = titleVariants(candidateTitle)
        if (expected.isEmpty() || actual.isEmpty()) return 0.0
        var best = 0.0
        for (left in expected) {
            for (right in actual) {
                val score = when {
                    left == right -> 1.0
                    left.length >= 7 && right.length >= 7 &&
                        (left.contains(right) || right.contains(left)) ->
                        minOf(left.length, right.length).toDouble() / maxOf(left.length, right.length)
                    else -> {
                        val leftTokens = left.split(' ').filter(String::isNotBlank).toSet()
                        val rightTokens = right.split(' ').filter(String::isNotBlank).toSet()
                        val union = leftTokens union rightTokens
                        val jaccard = if (union.isEmpty()) 0.0
                        else (leftTokens intersect rightTokens).size.toDouble() / union.size
                        maxOf(jaccard * 0.9, editSimilarity(left, right))
                    }
                }
                best = maxOf(best, score)
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    fun artistScore(expectedTitle: String, expectedArtist: String, candidateArtist: String): Double {
        val candidate = normalizeArtist(candidateArtist)
        if (candidate.isBlank()) return 0.0

        val expectedArtists = linkedSetOf<String>()
        normalizeArtist(expectedArtist).takeIf(String::isNotBlank)?.let(expectedArtists::add)
        val parts = separators.split(cleanTitle(expectedTitle))
        if (parts.size >= 2) {
            normalizeArtist(parts.first()).takeIf(String::isNotBlank)?.let(expectedArtists::add)
        }
        if (expectedArtists.isEmpty()) return 0.0

        return expectedArtists.maxOf { expected ->
            when {
                expected == candidate -> 1.0
                expected.length >= 5 && candidate.length >= 5 &&
                    (expected.contains(candidate) || candidate.contains(expected)) -> 0.82
                else -> editSimilarity(expected, candidate)
            }
        }.coerceIn(0.0, 1.0)
    }

    fun isStrongMatch(
        expectedTitle: String,
        expectedArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        minimumTitleScore: Double
    ): Boolean {
        val title = titleScore(expectedTitle, candidateTitle)
        if (title < minimumTitleScore) return false

        val expectedArtistKnown = normalizeArtist(expectedArtist).isNotBlank() ||
            separators.split(cleanTitle(expectedTitle)).size >= 2
        val candidateArtistKnown = normalizeArtist(candidateArtist).isNotBlank()
        if (!expectedArtistKnown || !candidateArtistKnown) {
            return title >= maxOf(minimumTitleScore, 0.96)
        }
        return artistScore(expectedTitle, expectedArtist, candidateArtist) >= 0.50
    }

    fun normalizeArtist(value: String): String {
        var cleaned = value.trim()
        cleaned = cleaned.replace(Regex("""(?i)\s*[-|•]\s*(topic|vevo|official)$"""), "")
        cleaned = cleaned.replace(Regex("""(?i)\s+(official|vevo|topic)\s+(channel|videos?)$"""), "")
        cleaned = cleaned.replace(Regex("""(?i)\s+(official|vevo)$"""), "")
        return normalize(cleaned).takeUnless {
            it in setOf("unknown artist", "unknown", "youtube", "various artists", "official")
        }.orEmpty()
    }

    private fun editSimilarity(left: String, right: String): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        if (kotlin.math.abs(left.length - right.length) > 12) return 0.0
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
    }
}

object YoutubeBackgroundAudioResolver {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun findBest(
        context: Context,
        youtubeTitle: String,
        youtubeChannel: String
    ): BackgroundAudioCandidate? = withContext(Dispatchers.IO) {
        val title = youtubeTitle.trim().take(220)
        val artist = youtubeChannel.trim().take(120)
        if (title.isBlank()) return@withContext null

        val localAndSaved = buildList {
            findSavedQueue(context, title, artist)?.let(::add)
            findDeviceAudio(context, title, artist)?.let(::add)
        }.sortedByDescending { it.matchScore }
        localAndSaved.firstOrNull()?.let { return@withContext it }

        searchAudius(title, artist).maxByOrNull { it.matchScore }?.let { return@withContext it }
        searchOpenverse(title, artist).maxByOrNull { it.matchScore }
    }

    private fun findSavedQueue(context: Context, title: String, artist: String): BackgroundAudioCandidate? {
        val raw = context.getSharedPreferences("ngoc_si_music", Context.MODE_PRIVATE)
            .getString("queue_metadata", null) ?: return null
        val items = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        var best: BackgroundAudioCandidate? = null
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val uriText = item.optString("uri").trim()
            val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: continue
            if (!isAllowedAudioUri(uri)) continue
            val candidate = makeCandidate(
                expectedTitle = title,
                expectedArtist = artist,
                candidateTitle = item.optString("title").trim(),
                candidateArtist = item.optString("artist").trim(),
                uri = uri,
                artworkUri = item.optString("artworkUri").takeIf(String::isNotBlank)?.let(Uri::parse),
                sourceLabel = "Hàng đợi nhạc đã lưu",
                licenseLabel = "Nguồn đã được người dùng thêm vào thư viện",
                landingUrl = null,
                minimumTitleScore = 0.89
            ) ?: continue
            if (best == null || candidate.matchScore > best.matchScore) best = candidate
        }
        return best
    }

    private fun findDeviceAudio(context: Context, title: String, artist: String): BackgroundAudioCandidate? {
        val permitted = if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!permitted) return null

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        var best: BackgroundAudioCandidate? = null
        try {
            context.contentResolver.query(
                collection,
                projection,
                MediaStore.Audio.Media.IS_MUSIC + " != 0",
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (cursor.moveToNext()) {
                    val candidate = makeCandidate(
                        expectedTitle = title,
                        expectedArtist = artist,
                        candidateTitle = cursor.getString(titleIndex).orEmpty(),
                        candidateArtist = cursor.getString(artistIndex).orEmpty(),
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex)),
                        artworkUri = null,
                        sourceLabel = "Nhạc trên thiết bị",
                        licenseLabel = "Tệp âm thanh trên thiết bị",
                        landingUrl = null,
                        minimumTitleScore = 0.89
                    ) ?: continue
                    if (best == null || candidate.matchScore > best.matchScore) best = candidate
                }
            }
        } catch (_: SecurityException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return best
    }

    private fun searchAudius(title: String, artist: String): List<BackgroundAudioCandidate> {
        val url = Uri.parse("https://api.audius.co/v1/tracks/search")
            .buildUpon()
            .appendQueryParameter("query", BackgroundAudioMatchRules.cleanTitle(title).take(140))
            .appendQueryParameter("limit", "10")
            .appendQueryParameter("app_name", "ngoc_si_music")
            .build()
            .toString()
        val body = getJson(url) ?: return emptyList()
        val tracks = body.optJSONArray("data") ?: return emptyList()
        val candidates = mutableListOf<BackgroundAudioCandidate>()
        for (index in 0 until tracks.length()) {
            val track = tracks.optJSONObject(index) ?: continue
            val id = track.optString("id").trim()
            if (id.isBlank() || id == "null") continue
            val artworkObject = track.optJSONObject("artwork")
            val artwork = artworkObject?.optString("150x150")
                ?.takeIf { it.startsWith("https://") }?.let(Uri::parse)
            val streamUri = Uri.parse("https://api.audius.co/v1/tracks")
                .buildUpon().appendPath(id).appendPath("stream")
                .appendQueryParameter("app_name", "ngoc_si_music").build()
            val permalink = track.optString("permalink").takeIf { it.startsWith("/") }
                ?.let { "https://audius.co$it" }
            makeCandidate(
                expectedTitle = title,
                expectedArtist = artist,
                candidateTitle = track.optString("title").trim(),
                candidateArtist = track.optJSONObject("user")?.optString("name").orEmpty().trim(),
                uri = streamUri,
                artworkUri = artwork,
                sourceLabel = "Audius • nhạc độc lập",
                licenseLabel = "Theo điều kiện của bản phát hành trên Audius",
                landingUrl = permalink,
                minimumTitleScore = 0.91
            )?.let(candidates::add)
        }
        return candidates
    }

    private fun searchOpenverse(title: String, artist: String): List<BackgroundAudioCandidate> {
        val url = Uri.parse("https://api.openverse.org/v1/audio/")
            .buildUpon()
            .appendQueryParameter("q", BackgroundAudioMatchRules.cleanTitle(title).take(140))
            .appendQueryParameter("license", "cc0,by,by-sa,pdm")
            .appendQueryParameter("page_size", "10")
            .build().toString()
        val body = getJson(url) ?: return emptyList()
        val results = body.optJSONArray("results") ?: return emptyList()
        val candidates = mutableListOf<BackgroundAudioCandidate>()
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val audioUrl = item.optString("url").trim()
            val audioUri = runCatching { Uri.parse(audioUrl) }.getOrNull() ?: continue
            if (!audioUri.scheme.equals("https", ignoreCase = true) || !isAllowedAudioUri(audioUri)) continue
            val license = item.optString("license").trim().lowercase(Locale.ROOT)
            if (license !in setOf("cc0", "by", "by-sa", "pdm")) continue
            val version = item.optString("license_version").takeIf(String::isNotBlank)
            val candidate = makeCandidate(
                expectedTitle = title,
                expectedArtist = artist,
                candidateTitle = item.optString("title").trim(),
                candidateArtist = item.optString("creator").trim(),
                uri = audioUri,
                artworkUri = item.optString("thumbnail").takeIf { it.startsWith("https://") }?.let(Uri::parse),
                sourceLabel = "Openverse • kho âm thanh có giấy phép mở",
                licenseLabel = license.uppercase(Locale.ROOT) + version?.let { " $it" }.orEmpty(),
                landingUrl = item.optString("foreign_landing_url").takeIf { it.startsWith("https://") },
                minimumTitleScore = 0.92
            )
            if (candidate != null) candidates.add(candidate)
        }
        return candidates
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "NGOC-SI-MUSIC-Preview/1.0 (Android)")
            .get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val payload = response.body?.string().orEmpty()
            if (payload.isBlank()) return null
            JSONObject(payload)
        }
    }.getOrNull()

    private fun makeCandidate(
        expectedTitle: String,
        expectedArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        uri: Uri,
        artworkUri: Uri?,
        sourceLabel: String,
        licenseLabel: String,
        landingUrl: String?,
        minimumTitleScore: Double
    ): BackgroundAudioCandidate? {
        if (candidateTitle.isBlank() || !isAllowedAudioUri(uri)) return null
        if (!BackgroundAudioMatchRules.isStrongMatch(
                expectedTitle, expectedArtist, candidateTitle, candidateArtist, minimumTitleScore
            )
        ) return null

        return BackgroundAudioCandidate(
            title = candidateTitle,
            artist = candidateArtist.ifBlank { "Chưa rõ nghệ sĩ" },
            uri = uri,
            artworkUri = artworkUri,
            sourceLabel = sourceLabel,
            licenseLabel = licenseLabel,
            landingUrl = landingUrl,
            matchScore = BackgroundAudioMatchRules.titleScore(expectedTitle, candidateTitle)
        )
    }

    private fun isAllowedAudioUri(uri: Uri): Boolean {
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("content", "file", "https", "http")) return false
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" || host.endsWith(".youtu.be")
        ) return false
        return true
    }
}
